import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { NativeToolRuntime } from '../native-tool-runtime.mjs';
import { NativeProgramExecutor } from '../native-program-executor.mjs';
import { normalizeMinecraftToolCall } from '../native-minecraft-tools.mjs';

// Synthetic benchmark: real NativeToolRuntime and ArenaScript, driven by a
// deterministic clock and a fake Minecraft bridge. Provider thinking is fixed
// at two simulated seconds per decision. No provider or live Minecraft server
// is used, and the runtime dispatches only this fixed model-authored fixture.
const [trialsArg = '3'] = process.argv.slice(2);
const trials = Number(trialsArg);
if (!Number.isInteger(trials) || trials < 1 || trials > 20) {
	throw new Error('Usage: node overlap-latency-benchmark.mjs [trials 1..20]');
}

const THINKING_MS = 2_000;
const ACTIONS = [750, 1_000, 1_250, 1_500, 1_750].map((durationMs) => ({
	actionType: 'wait',
	arguments: { durationMs },
}));
const PROGRAM_SOURCE = 'program.onUnhandledAttention("continue_and_notify");\n'
	+ ACTIONS.map(({ arguments: args }) => `await player.wait(${args.durationMs});`).join('\n');
const TOTAL_ACTION_MS = ACTIONS.reduce((sum, action) => sum + action.arguments.durationMs, 0);
const observation = {
	world: { worldId: 'synthetic-overlap-world', dimension: 'minecraft:overworld' },
	player: { x: 0, y: 64, z: 0, health: 20, food: 20 },
	blocks: [], entities: [], items: [], inventory: { items: [], tagCounts: {} },
};

function deterministicClock() {
	let now = 0;
	let nextId = 0;
	const timers = [];
	return {
		now: () => now,
		setTimeoutFn(callback, delayMs) {
			const timer = { id: ++nextId, dueAt: now + delayMs, callback, cancelled: false, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn(timer) { if (timer !== undefined && timer !== null) timer.cancelled = true; },
		async advanceTo(targetMs) {
			assert.ok(targetMs >= now, 'deterministic clock cannot move backwards');
			while (true) {
				const timer = timers
					.filter((candidate) => !candidate.cancelled && candidate.dueAt <= targetMs)
					.sort((left, right) => left.dueAt - right.dueAt || left.id - right.id)[0];
				if (timer === undefined) break;
				timer.cancelled = true;
				now = timer.dueAt;
				timer.callback();
				// Let promise continuations dispatch any next ArenaScript step before
				// selecting the next due timer.
				await new Promise((resolve) => setImmediate(resolve));
			}
			now = targetMs;
			await new Promise((resolve) => setImmediate(resolve));
		},
		advanceBy(durationMs) { return this.advanceTo(now + durationMs); },
	};
}

function createHarness(strategy, trial) {
	const clock = deterministicClock();
	const record = {
		agentId: `overlap-${strategy}-${trial}`,
		provider: 'synthetic-benchmark',
		model: 'selected-model-simulation',
		reasoningEffort: 'fixed-delay',
		goalRevision: 1,
		currentGoal: 'Complete the authored benchmark routine',
	};
	const actions = [];
	let eventSequence = 1;
	let callOrdinal = 0;
	let runtime;
	const programExecutor = new NativeProgramExecutor({
		setTimeoutFn: clock.setTimeoutFn,
		clearTimeoutFn: clock.clearTimeoutFn,
		sessionId: `overlap-${strategy}-${trial}`,
	});
	runtime = new NativeToolRuntime({
		sessionId: `overlap-${strategy}-${trial}`,
		programExecutor,
		bridge: { send: async (type, _agentId, payload) => {
			if (type !== 'action_command') return;
			actions.push({ actionType: payload.actionType, arguments: structuredClone(payload.arguments) });
			clock.setTimeoutFn(() => runtime.onActionResult(record, {
				goalRevision: payload.goalRevision,
				actionId: payload.actionId,
				state: 'SUCCEEDED',
				reasonCode: 'SYNTHETIC_ACTION_COMPLETED',
				executionStarted: true,
			}), payload.arguments.durationMs);
		} },
		requestObservation: async (_currentRecord, { afterEventSequence }) => {
			eventSequence = Math.max(eventSequence, afterEventSequence) + 1;
			return { observation, eventSequence };
		},
	});
	runtime.updateObservation(record, observation, { eventSequence });
	const call = (name, args = {}) => runtime.execute({
		agentId: record.agentId,
		goalRevision: record.goalRevision,
		turnId: `turn-${++callOrdinal}`,
		callId: `call-${callOrdinal}`,
		tool: normalizeMinecraftToolCall(name, args),
	}, record);
	return { clock, record, runtime, call, actions, dispose: () => runtime.disposeAll() };
}

async function think(clock) { await clock.advanceBy(THINKING_MS); }

async function flushDispatch() { await new Promise((resolve) => setImmediate(resolve)); }

async function waitForAction(harness, actionId) {
	const receipt = await harness.call('actionStatus', { actionId });
	assert.equal(receipt.state, 'SUCCEEDED', `action ${actionId} did not complete during its paired thinking interval`);
	return receipt;
}

async function waitForProgram(harness, programId) {
	const result = await harness.call('programStatus', { programId });
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, ACTIONS.length);
	assert.equal(result.receipts.length, ACTIONS.length);
	assert.ok(result.receipts.every((receipt) => receipt.state === 'SUCCEEDED'));
	return result;
}

async function runSerialActions(trial) {
	const harness = createHarness('serial-actions', trial);
	const startedAt = harness.clock.now();
	try {
		for (const action of ACTIONS) {
			await think(harness.clock);
			const pending = harness.call('wait', action.arguments);
			await flushDispatch();
			await harness.clock.advanceBy(action.arguments.durationMs);
			const result = await pending;
			assert.equal(result.state, 'SUCCEEDED');
		}
		return { simulatedElapsedMs: harness.clock.now() - startedAt, actions: harness.actions };
	} finally { await harness.dispose(); }
}

async function runStartActionOverlap(trial) {
	const harness = createHarness('start-action', trial);
	const startedAt = harness.clock.now();
	try {
		// Decision 1 selects step 1. Each later model decision selects the next
		// independent authored step while the current action is in flight.
		await think(harness.clock);
		let handle = await harness.call('startAction', ACTIONS[0]);
		assert.equal(handle.state, 'RUNNING');
		for (let index = 0; index < ACTIONS.length - 1; index += 1) {
			await think(harness.clock);
			await waitForAction(harness, handle.actionId);
			handle = await harness.call('startAction', ACTIONS[index + 1]);
			assert.equal(handle.state, 'RUNNING');
		}
		await harness.clock.advanceBy(ACTIONS.at(-1).arguments.durationMs);
		await waitForAction(harness, handle.actionId);
		return { simulatedElapsedMs: harness.clock.now() - startedAt, actions: harness.actions };
	} finally { await harness.dispose(); }
}

async function runProgram(strategy, trial, background) {
	const harness = createHarness(strategy, trial);
	const startedAt = harness.clock.now();
	try {
		// The selected model authors the same bounded source in both cases.
		await think(harness.clock);
		const programStartAt = harness.clock.now();
		const pending = harness.call('runProgram', {
			source: PROGRAM_SOURCE,
			background,
			maxActions: ACTIONS.length,
			timeoutMs: 30_000,
		});
		if (background) {
			const handle = await pending;
			assert.equal(typeof handle.programId, 'string');
			await flushDispatch();
			await think(harness.clock);
			await harness.clock.advanceTo(programStartAt + TOTAL_ACTION_MS);
			await waitForProgram(harness, handle.programId);
		} else {
			await flushDispatch();
			await harness.clock.advanceTo(programStartAt + TOTAL_ACTION_MS);
			const result = await pending;
			assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
			assert.equal(result.actions, ACTIONS.length);
			assert.equal(result.receipts.length, ACTIONS.length);
			assert.ok(result.receipts.every((receipt) => receipt.state === 'SUCCEEDED'));
			await think(harness.clock);
		}
		return { simulatedElapsedMs: harness.clock.now() - startedAt, actions: harness.actions };
	} finally { await harness.dispose(); }
}

const runners = {
	serialActions: runSerialActions,
	startActionOverlap: runStartActionOverlap,
	foregroundRunProgram: (trial) => runProgram('foreground-program', trial, false),
	backgroundRunProgram: (trial) => runProgram('background-program', trial, true),
};
const wallStartedAt = performance.now();
const samples = Object.fromEntries(Object.keys(runners).map((strategy) => [strategy, []]));
for (let trial = 1; trial <= trials; trial += 1) {
	for (const [strategy, run] of Object.entries(runners)) {
		const result = await run(trial);
		assert.deepEqual(result.actions, ACTIONS, `${strategy} trial ${trial} must execute the authored actions in order`);
		samples[strategy].push(result);
	}
	const hashes = Object.fromEntries(Object.entries(samples).map(([strategy, rows]) => [strategy, actionHash(rows.at(-1).actions)]));
	assert.equal(new Set(Object.values(hashes)).size, 1, `trial ${trial} action sequences must match exactly`);
}

function actionHash(actions) { return createHash('sha256').update(JSON.stringify(actions)).digest('hex'); }
function summarize(rows) {
	const durations = rows.map((row) => row.simulatedElapsedMs);
	return { simulatedElapsedMs: { min: Math.min(...durations), p50: median(durations), max: Math.max(...durations) } };
}
function median(values) {
	const sorted = [...values].sort((left, right) => left - right);
	return sorted[Math.floor(sorted.length / 2)];
}
function pairedComparison(serialName, overlapName) {
	const serial = samples[serialName].map((row) => row.simulatedElapsedMs);
	const overlap = samples[overlapName].map((row) => row.simulatedElapsedMs);
	const serialMedian = median(serial);
	const overlapMedian = median(overlap);
	return {
		serial: serialName,
		overlapped: overlapName,
		serialP50SimulatedMs: serialMedian,
		overlappedP50SimulatedMs: overlapMedian,
		millisecondsSaved: serialMedian - overlapMedian,
		percentSaved: Number(((serialMedian - overlapMedian) / serialMedian * 100).toFixed(1)),
		pairedTrialsFaster: overlap.filter((value, index) => value < serial[index]).length,
	};
}

const strategyResults = Object.fromEntries(Object.entries(samples).map(([strategy, rows]) => [strategy, {
	...summarize(rows),
	modelThinkingDecisionsPerTrial: strategy.startsWith('foreground') || strategy.startsWith('background') ? 2 : ACTIONS.length,
	actionSequences: rows.length,
	actionsPerSequence: ACTIONS.length,
	actionSequenceHash: actionHash(rows[0].actions),
}]));
const output = {
	benchmark: 'native-overlap-latency',
	classification: 'synthetic, deterministic timing; no live provider or Minecraft server',
	controller: 'real NativeToolRuntime and ArenaScript with an injected deterministic clock and fake action bridge',
	trials,
	workload: {
	authorship: 'same fixed model-authored fixture plan in every strategy; runtime only dispatches authored steps',
	actionsPerSequence: ACTIONS.length,
	actions: ACTIONS,
	fixedProviderThinkingMsPerDecision: THINKING_MS,
	totalSyntheticActionMs: TOTAL_ACTION_MS,
	},
	matchedActionSequences: trials * Object.keys(runners).length,
	matchedActionCountPerStrategy: trials * ACTIONS.length,
	correctness: 'PASSED: all action type/argument sequences matched exactly; every action receipt succeeded',
	comparisons: {
		individualActions: pairedComparison('serialActions', 'startActionOverlap'),
		identicalProgramSource: pairedComparison('foregroundRunProgram', 'backgroundRunProgram'),
	},
	strategies: strategyResults,
	benchmarkWallClockMs: Number((performance.now() - wallStartedAt).toFixed(1)),
};
process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
