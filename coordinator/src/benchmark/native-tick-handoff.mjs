import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Tick-quantized model of the Fabric bridge, used to compare two NativeToolRuntime
// modules without model inference. It mirrors MultiplexedServerBridge phases:
// inbound action commands and inspection requests are admitted only at tick start,
// actions run for their authored ticks, and every action result is followed by a
// forced post-physics observation at the end of that same tick. It is a model of
// server timing, not Minecraft gameplay.
// Usage: node native-tick-handoff.mjs <before-runtime.mjs> <after-runtime.mjs> [repetitions] [tickMs]

const [beforeModule, afterModule, repetitionsArg = '40', tickArg = '50'] = process.argv.slice(2);
const repetitions = Number(repetitionsArg);
const tickMs = Number(tickArg);
if (!beforeModule || !afterModule || !Number.isInteger(repetitions) || repetitions < 1 || repetitions > 500 || !(tickMs >= 5 && tickMs <= 100)) {
	throw new Error('Usage: node native-tick-handoff.mjs <before-runtime.mjs> <after-runtime.mjs> [repetitions 1..500] [tickMs 5..100]');
}

const record = { agentId: 'tick-agent', provider: 'codex', model: 'gpt-6-sol', reasoningEffort: 'low', serviceTier: 'priority', goalRevision: 1, currentGoal: 'Look and act' };
const programSource = 'program.onUnhandledAttention("continue_and_notify"); '
	+ Array.from({ length: 4 }, () => 'await player.wait(100);').join('');
const workloads = {
	lookAround: { kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 8, ticksPerStep: 2 },
	runProgram: { kind: 'run_program', source: programSource, maxActions: 4, timeoutMs: 30_000 },
	navigate: { kind: 'action', actionType: 'navigate_to', arguments: { x: 4, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5_000 } },
};

async function createArm(modulePath, name) {
	const { NativeToolRuntime } = await import(pathToFileURL(path.resolve(modulePath)).href);
	let eventSequence = 1;
	let yaw = 0;
	// Heading-tagged sightings prove each sweep sample; program facts stay minimal.
	let sightings = false;
	const inbound = [];
	const running = [];
	const counters = { requests: 0, pushes: 0, actions: 0 };
	const actions = [];
	let dispatches = [];
	let completions = [];
	let runtime;
	// Socket delivery is ordered; each tick's messages arrive after that tick.
	const outbound = [];
	const deliver = (message) => { outbound.push(message); if (outbound.length === 1) setImmediate(flush); };
	const flush = () => { while (outbound.length > 0) outbound.shift()(); };
	const observation = () => ({ observedAtEpochMs: Date.now(), world: { worldId: 'tick-world', dimension: 'minecraft:overworld' },
		player: { x: 0, y: 64, z: 0, yaw, health: 20 }, entities: sightings ? [{ uuid: `pig-${Math.round(yaw)}`, type: 'minecraft:pig' }] : [], blocks: [], items: [], inventory: { items: [], tagCounts: {} } });
	const tick = () => {
		// Start phase: admit queued work in arrival order, then advance actions.
		for (const task of inbound.splice(0)) task();
		const finished = [];
		for (const action of [...running]) {
			action.remaining -= 1;
			if (action.yaw !== undefined) yaw = action.yaw;
			if (action.remaining <= 0) { running.splice(running.indexOf(action), 1); finished.push(action); }
		}
		for (const action of finished) deliver(() => runtime.onActionResult(record, { goalRevision: 1, actionId: action.actionId, state: 'SUCCEEDED', reasonCode: 'COMPLETED', executionStarted: true, physicalAttempted: true }));
		// End phase: every result forces one attention observation after physics.
		if (finished.length > 0) {
			const sample = observation();
			const sequence = ++eventSequence;
			counters.pushes += 1;
			deliver(() => runtime.updateObservation(record, sample, { eventSequence: sequence, attention: true, changedFacts: [] }));
		}
	};
	let timer = null;
	let nextTickAt = 0;
	const schedule = () => {
		nextTickAt += tickMs;
		timer = setTimeout(() => { tick(); schedule(); }, Math.max(0, nextTickAt - performance.now()));
	};
	runtime = new NativeToolRuntime({
		sessionId: `tick-${name}`,
		bridge: { send: async (type, _agentId, payload) => {
			if (type !== 'action_command') return;
			counters.actions += 1;
			actions.push({ actionType: payload.actionType, arguments: payload.arguments });
			const ticks = payload.actionType === 'control' ? payload.arguments.ticks
				: payload.actionType === 'wait' ? Math.max(1, Math.ceil(payload.arguments.durationMs / tickMs)) : 6;
			inbound.push(() => running.push({ actionId: payload.actionId, remaining: ticks, ...(payload.actionType === 'control' ? { yaw: payload.arguments.yaw } : {}) }));
		} },
		requestObservation: () => new Promise((resolve) => {
			counters.requests += 1;
			inbound.push(() => { const sample = observation(); const sequence = ++eventSequence; deliver(() => resolve({ observation: sample, eventSequence: sequence })); });
		}),
		trace: (event) => {
			if (event === 'native_tool_dispatch_started') dispatches.push(performance.now());
			if (event === 'native_tool_action_completed') completions.push(performance.now());
		},
	});
	runtime.updateObservation(record, observation(), { eventSequence });
	let call = 0;
	return {
		start() { nextTickAt = performance.now(); schedule(); },
		stop() { clearTimeout(timer); return runtime.disposeAll(); },
		counters: () => ({ ...counters }),
		actionHash: (start) => createHash('sha256').update(JSON.stringify(actions.slice(start))).digest('hex'),
		async run(workload) {
			dispatches = [];
			completions = [];
			if (sightings !== (workload === 'lookAround')) {
				sightings = workload === 'lookAround';
				runtime.updateObservation(record, observation(), { eventSequence: ++eventSequence });
			}
			const started = performance.now();
			const result = await runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: `turn-${++call}`, callId: `call-${call}`, tool: structuredClone(workloads[workload]) }, record);
			const durationMs = performance.now() - started;
			assert.ok(result.state === 'SUCCEEDED' || result.reasonCode === 'PROGRAM_EXHAUSTED', `${name} ${workload} ended ${result.state}/${result.reasonCode} ${result.message ?? ''}`);
			if (workload === 'lookAround') assert.deepEqual(result.samples.map((sample) => sample.entities[0].uuid), result.samples.map((sample) => `pig-${Math.round(sample.yaw)}`), 'each sample reflects its own heading');
			if (workload === 'navigate') assert.equal(result.postAction?.freshness?.fresh, true);
			return { durationMs, gapsMs: dispatches.slice(1).map((at, index) => at - completions[index]) };
		},
	};
}

const summarize = (values) => {
	const sorted = [...values].sort((left, right) => left - right);
	const at = (fraction) => sorted.length === 0 ? null : Number(sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(3));
	return { n: values.length, p50Ms: at(0.5), p95Ms: at(0.95) };
};

const arms = { before: await createArm(beforeModule, 'before'), after: await createArm(afterModule, 'after') };
arms.before.start();
arms.after.start();
const output = { benchmark: 'native-tick-handoff', tickMs, repetitions, model: 'tick-quantized bridge model; real NativeToolRuntime and ArenaScript', workloads: {} };
for (const workload of Object.keys(workloads)) {
	for (let index = 0; index < 3; index += 1) for (const arm of Object.values(arms)) await arm.run(workload);
	const starts = Object.fromEntries(Object.entries(arms).map(([name, arm]) => [name, arm.counters()]));
	const samples = { before: [], after: [] };
	for (let index = 0; index < repetitions; index += 1) {
		// Alternate arm order so neither arm always runs first after a model turn.
		for (const name of index % 2 === 0 ? ['before', 'after'] : ['after', 'before']) samples[name].push(await arms[name].run(workload));
	}
	const summary = {};
	for (const [name, arm] of Object.entries(arms)) {
		const end = arm.counters();
		summary[name] = {
			duration: summarize(samples[name].map((row) => row.durationMs)),
			handoff: summarize(samples[name].flatMap((row) => row.gapsMs)),
			requests: end.requests - starts[name].requests, pushes: end.pushes - starts[name].pushes,
			actions: end.actions - starts[name].actions, actionHash: arm.actionHash(starts[name].actions),
		};
	}
	assert.equal(summary.before.actionHash, summary.after.actionHash, `${workload} authored actions must be identical`);
	summary.pairedDurationWins = samples.after.filter((row, index) => row.durationMs < samples.before[index].durationMs).length;
	output.workloads[workload] = summary;
}
await Promise.all(Object.values(arms).map((arm) => arm.stop()));
process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
