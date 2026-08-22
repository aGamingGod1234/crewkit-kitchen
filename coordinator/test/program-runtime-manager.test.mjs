import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';
const DEATH = Object.freeze({
	cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 0, y: 64, z: 0,
	respawnDimensionId: 'minecraft:overworld', respawnX: 100.5, respawnY: 70, respawnZ: -20.5,
	respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'survival', diedAtEpochMs: 2,
});

function record(agentId = 'agent-a') {
	return { agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'wait', queue: [] };
}

function observation(overrides = {}) {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} }, ...overrides };
}

function harness(options = {}) {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => { requests.push(request); return { summary: 'Continue.', directive: 'continue' }; } },
		onCompleted: options.onCompleted,
		benchmarkRecorder: options.benchmarkRecorder,
		latencyRegistry: options.latencyRegistry,
	});
	return { manager, registry, sent, requests };
}

test('retains the planning trace through program dispatch and first world action without fabricating verification', async () => {
	const rows = [];
	const run = harness({ benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } } });
	const traceId = 'trace-runtime-1';
	const decision = {
		summary: 'Wait once.', directive: 'replace', traceId,
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	};
	await run.manager.installDecision(run.registry.get('agent-a'), decision, { observation: observation(), eventSequence: 1 });
	const command = run.sent.find((message) => message.type === 'action_command');
	assert.equal(command.payload.traceId, traceId);
	assert.equal(command.payload.provenance.traceId, traceId);
	await run.manager.onActionProgress(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 2, state: 'RUNNING', progress: 0.1,
	});
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 3, state: 'SUCCEEDED', reasonCode: 'DONE',
	});
	assert.equal(rows.every((row) => row.context.traceId === traceId), true);
	assert.equal(rows.some((row) => row.stage === 'first_command_dispatch'), true);
	assert.equal(rows.some((row) => row.stage === 'first_world_action'), true);
	const verification = rows.find((row) => row.stage === 'completion_verification');
	assert.equal(verification?.fields.outcome, 'skipped');
	assert.equal(verification?.fields.retryReason, 'VERIFIER_NOT_INSTALLED');
});

test('bridge pre-execution rejection does not count as first world action', async () => {
	const rows = [];
	const latencyRegistry = new ControlLatencyRegistry();
	const run = harness({ latencyRegistry, benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } } });
	const traceId = 'trace-rejected-before-execution';
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait once.', directive: 'replace', traceId,
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const command = run.sent.find((message) => message.type === 'action_command');
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 2, state: 'FAILED', reasonCode: 'STALE_REVISION',
		executionStarted: false, physicalAttempted: false,
	});
	assert.equal(rows.some((row) => row.stage === 'first_world_action'), false);
	assert.equal(latencyRegistry.completeTrace(traceId).phases.some((phase) => phase.phase === 'first_world_action'), false);
});

test('installs a model-authored program and dispatches its next primitive without a provider turn', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait twice.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	assert.equal(run.sent.length, 1);
	assert.equal(run.sent[0].payload.actionType, 'wait');
	assert.deepEqual(run.sent[0].payload.arguments, { durationMs: 1 });
	assert.equal(run.sent[0].payload.provenance.programId, 'program-1-1');
	const wire = validateProtocolV2Payload('action_command', run.sent[0].payload);
	assert.deepEqual(wire.provenance, { ...run.sent[0].payload.provenance });
	assert.match(wire.provenance.sourceStepId, /^step-\d+-\d+$/);
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: run.sent[0].payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	assert.equal(run.sent.length, 2);
	assert.equal(run.requests.length, 0, 'pre-authored continuation must not call the provider');
});

test('reports a completed ArenaScript program after its final action result', async () => {
	const changes = [];
	const run = harness({ onCompleted: (changed) => changes.push(changed) });
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait, then finish.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	const command = run.sent[0];
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(changes.at(-1)?.state, DynamicAgentState.COMPLETED, 'terminal state is reported to the bridge owner');
});

test('reports a completed ArenaScript program that needs no physical action', async () => {
	const changes = [];
	const run = harness({ onCompleted: (changed) => changes.push(changed) });
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Nothing else is required.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });

	assert.equal(run.sent.length, 0, 'zero-action completion must not fabricate a bridge command');
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(changes.at(-1)?.state, DynamicAgentState.COMPLETED, 'zero-action completion is reported to the bridge owner');
});

test('treats a terminal dead-state model decision as handled without changing authoritative death', async () => {
	const registry = new AgentRegistry();
	registry.register({
		...record(), state: DynamicAgentState.DEAD, currentGoal: null, goalRevision: 2, death: DEATH,
	});
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => assert.fail('terminal decision must not dispatch an action') },
		planner: { requestPlan: async () => assert.fail('terminal decision must not request another plan') },
	});

	const dead = registry.get('agent-a');
	await manager.installDecision(dead, {
		summary: 'No active goal to resume.', directive: 'finish', status: 'impossible',
	}, { observation: { death: DEATH }, eventSequence: 0 });

	assert.equal(registry.get('agent-a').state, DynamicAgentState.DEAD);
	assert.equal(manager.hasCurrent(dead), true, 'the handled turn prevents duplicate dead-state planning');
});

test('emits a provenance-bearing coordinate-free respawn primitive only from authored player code', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Respawn at the vanilla target.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();',
	}, { observation: observation(), eventSequence: 9 });
	assert.equal(run.sent.length, 1);
	assert.equal(run.sent[0].type, 'action_command');
	assert.equal(run.sent[0].payload.actionType, 'respawn');
	assert.deepEqual(run.sent[0].payload.arguments, {});
	assert.equal(run.sent[0].payload.provenance.provider, 'codex');
	assert.equal(run.sent[0].payload.provenance.model, 'gpt-5.6-sol');
	assert.equal(run.sent[0].payload.provenance.eventSequence, 9);
});

test('routes invalid source correction to the selected agent planner without a local replacement', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'not valid ArenaScript {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 0);
	assert.equal(run.requests.length, 1);
	assert.equal(run.requests[0].agentId, 'agent-a');
	assert.equal(run.requests[0].preserveState, true);
	assert.match(run.requests[0].input, /ArenaScript compiler correction/);
});

test('corrects an omitted mine argument through the selected model before bridge dispatch', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: {
			requestPlan: async (request) => {
				requests.push(request);
				return {
					summary: 'Use a valid primitive argument.',
					directive: 'replace',
					source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
				};
			},
		},
	});

	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Mine a block.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.mine();',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));

	assert.equal(requests.length, 1);
	assert.equal(requests[0].agentId, 'agent-a');
	assert.equal(requests[0].preserveState, true);
	assert.match(requests[0].input, /ArenaScript compiler correction/);
	assert.equal(sent.length, 1);
	assert.equal(sent[0].payload.actionType, 'wait');
});

test('uses an authored watcher before asking the provider for unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Watch health.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 37, attention: true });
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 38 });
	assert.equal(run.sent.at(-1).payload.arguments.durationMs, 9);
	assert.equal(run.sent.at(-1).payload.provenance.eventSequence, 37, 'watcher command repeats the triggering server event identity');
	assert.equal(run.requests.length, 0);
});

test('replans from bounded failed-action context instead of pausing after a repeated deterministic failure', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			return {
				summary: 'Use a different action.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
			};
		} },
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: `
			program.onUnhandledAttention("continue_and_notify");
			await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
				await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
			});
		`,
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), { actionId: sent[0].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	await manager.onActionResult(registry.get('agent-a'), { actionId: sent[1].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	for (let attempt = 0; attempt < 10 && sent.length < 3; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.match(requests[0].input, /"decisionContext":"program_action_failure"/);
	assert.match(requests[0].input, /"recipeId":"minecraft:planks"/);
	assert.equal(sent.length, 3);
	assert.equal(sent[2].payload.actionType, 'wait');
});

test('serializes coalesced reactive planner requests for one program', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const requests = [];
	const errors = [];
	const traces = [];
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	let releaseFirst;
	const firstPlan = new Promise((resolve) => { releaseFirst = resolve; });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) },
		planner: {
			requestPlan: (request) => scheduler.schedule(request.agentId, async () => {
				requests.push(request);
				if (requests.length === 1) return firstPlan;
				return { directive: 'continue', summary: 'continue' };
			}),
		},
		reportError: (_agentId, error) => errors.push(error),
		trace: (event, fields) => traces.push({ event, ...fields }),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3, attention: true });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1, 'coalesced attention must not start a second planner request before the first response is applied');
	releaseFirst({
		directive: 'replace', summary: 'replace stale context',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(9);',
	});
	for (let attempt = 0; attempt < 5 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2, 'the newest coalesced context is still serviced after the first response');
	assert.equal(traces.some((entry) => entry.event === 'program_replaced' && entry.version === 2), false,
		'a replacement rejected by the engine request fence must not be reported as installed');
	assert.equal(traces.some((entry) => entry.event === 'program_replacement_rejected' && entry.result?.code === 'STALE_MODEL_REQUEST'), true,
		'a replacement rejected by the engine request fence is explicitly diagnosed');
	assert.equal(errors.filter((error) => error.code === 'PLAN_ALREADY_ACTIVE').length, 0, 'the scheduler must not reject the coalesced request as a duplicate active turn');
	scheduler.close();
});

test('passes urgent trigger metadata through a coalesced reactive planner turn', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	let release;
	const first = new Promise((resolve) => { release = resolve; });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => { requests.push(request); if (requests.length === 1) return first; return { directive: 'continue', summary: 'continue' }; } },
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true, priority: 'ordinary', trigger: 'observation' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3, attention: true, priority: 'urgent', trigger: 'damage' });
	release({ directive: 'continue', summary: 'continue' });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(requests[1].planningPriority, 'urgent');
	assert.equal(requests[1].priority, 'urgent');
	assert.match(requests[1].input, /"attentionPriority":"urgent"/);
	assert.match(requests[1].input, /"attentionTrigger":"damage"/);
});

test('measures one thousand watcher branches with the real monotonic clock', async () => {
	const registry = new AgentRegistry();
	for (const agentId of ['agent-a', 'agent-b', 'agent-c', 'agent-d']) registry.register(record(agentId));
	const latencies = new ControlLatencyRegistry({ windowSize: 1_000 });
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		latencyRegistry: latencies,
		clock: performance.now.bind(performance),
	});
	for (const agentId of ['agent-a', 'agent-b', 'agent-c', 'agent-d']) {
		await manager.installDecision(registry.get(agentId), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(1); }); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
		await new Promise((resolve) => setImmediate(resolve));
		let sequence = 2;
		for (let event = 0; event < 250; event++) {
			await manager.onObservation(registry.get(agentId), { observation: observation({ player: { health: 19 } }), receiptMonotonicMs: performance.now(), receiptEpochMs: Date.now(), observedAtEpochMs: Date.now() - 1, eventSequence: sequence++, attention: true });
			await new Promise((resolve) => setImmediate(resolve));
			await manager.onActionResult(registry.get(agentId), { actionId: sent.at(-1).payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: sequence++ });
			await new Promise((resolve) => setImmediate(resolve));
			await manager.onObservation(registry.get(agentId), { observation: observation({ player: { health: 20 } }), receiptMonotonicMs: performance.now(), receiptEpochMs: Date.now(), observedAtEpochMs: Date.now() - 1, eventSequence: sequence++, attention: false });
		}
	}
	const snapshot = latencies.snapshot();
	console.log(`REAL_TIMER_LATENCY_SUMMARY ${JSON.stringify({
		basis: 'performance_now_monotonic_clock',
		segments: snapshot.filter(({ operation }) => ['event_receipt_to_branch', 'branch_to_bridge_send'].includes(operation)),
	})}`);
	for (const operation of ['event_receipt_to_branch', 'branch_to_bridge_send']) {
		const metric = snapshot.find((entry) => entry.operation === operation);
		assert.ok(metric, `${operation} is recorded`);
		assert.equal(metric.count, 1_000, `${operation} has exactly one sample per watcher branch`);
		assert.ok(Number.isFinite(metric.p95Ms) && metric.p95Ms > 0 && metric.p95Ms < 5, `${operation} p95 is positive and stays below 5ms`);
	}
	assert.equal(sent.filter((message) => message.payload.provenance.eventSequence > 1).length, 1_000, 'each watcher event produces exactly one command');
	assert.equal(snapshot.find((entry) => entry.operation === 'branch_to_bridge_send').count, 1_000, 'each watcher command contributes one branch-to-send sample');
});

test('ignores duplicate server observations and omits skewed epoch telemetry', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 100;
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, receiptMonotonicMs: 100, receiptEpochMs: 1_000, observedAtEpochMs: 2_000, attention: false });
	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 2, receiptMonotonicMs: 101, receiptEpochMs: 900, observedAtEpochMs: 800, attention: true });
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'event_receipt_to_branch'), false, 'duplicate server event is ignored before branch timing');
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'minecraft_change_to_publication'), false, 'duplicate future/skewed event is omitted instead of coerced to zero');
});

test('records completion from bridge send even when no progress arrives', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 10;
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await manager.onActionResult(registry.get('agent-a'), { actionId: sent[0].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	const completion = latencies.snapshot().find((entry) => entry.operation === 'action_completion');
	assert.equal(completion.count, 1);
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'command_to_first_progress'), false);
});

test('records only the first progress event for an action', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 10;
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(manager.onActionProgress(registry.get('agent-a'), { actionId: sent[0].actionId }), true);
	assert.equal(manager.onActionProgress(registry.get('agent-a'), { actionId: sent[0].actionId }), true);
	assert.equal(latencies.snapshot().find((entry) => entry.operation === 'command_to_first_progress').count, 1);
});

test('telemetry clock faults omit samples without interrupting program control', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const samples = [Number.NaN, -1, 10, 9, new Error('clock unavailable')];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) },
		planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: new ControlLatencyRegistry(),
		clock: () => {
			const value = samples.shift();
			if (value instanceof Error) throw value;
			return value ?? 10;
		},
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(1); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(await manager.onActionResult(registry.get('agent-a'), { actionId: sent[0].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(await manager.onActionResult(registry.get('agent-a'), { actionId: sent[1].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent.length, 3, 'NaN, negative, regressing, and throwing clock reads cannot stop commands');
	assert.ok(await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: true,
		receiptMonotonicMs: -1, receiptEpochMs: Number.NaN, observedAtEpochMs: 1,
	}), 'invalid receipt timestamps cannot reject a valid observation');
});

test('disposes an old goal program so late action results cannot advance it', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	run.manager.onGoalControl(run.registry.get('agent-a'), 'steer');
	assert.equal(run.sent.at(-1).type, 'action_cancel');
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 1);
});

test('does not turn an ordinary active-action observation into unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), attention: false });
	assert.equal(run.requests.length, 0);
});

test('keeps versions and external action identities monotonic across remove and recreate', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const old = run.sent[0].payload;
	run.manager.dispose('agent-a');
	run.registry.remove('agent-a');
	run.registry.register(record());
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const replacement = run.sent.at(-1).payload;
	assert.equal(replacement.provenance.programId, 'program-1-2');
	assert.notEqual(replacement.actionId, old.actionId);
	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: old.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' }), false);
});

test('caps recursive compiler correction and reports exhaustion without a fallback action', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = []; let requests = 0;
	const manager = new ProgramRuntimeManager({ registry, bridge: { send: async () => assert.fail('must not dispatch') }, planner: { requestPlan: async () => { requests += 1; return { summary: 'Still bad.', directive: 'replace', source: 'broken {' }; } }, reportError: (_agentId, error) => errors.push(error), compilerCorrectionLimit: 1 });
	await manager.installDecision(registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'broken {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(requests, 1);
	assert.equal(errors.at(-1).code, 'ARENA_SCRIPT_COMPILER_EXHAUSTED');
});

test('bridge send rejection unwedges the active program with a stable failed result', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => { throw Object.assign(new Error('queue full'), { code: 'AGENT_BACKPRESSURE' }); } },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_id, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(errors.at(-1).code, 'AGENT_BACKPRESSURE');
	const snapshot = await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: false });
	assert.equal(snapshot.activeActionId, null, 'failed send is terminally acknowledged instead of wedging the engine');
});

test('bridge send rejection contains a model execution error without terminating the coordinator', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => { throw Object.assign(new Error('stale revision'), { code: 'STALE_GOAL_REVISION' }); } },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_id, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); const result = await tryResult(player.wait(1)); const invalid = result.yaw; program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').state, DynamicAgentState.PAUSED);
	assert.equal(errors.some((error) => error.code === 'STALE_GOAL_REVISION'), true);
	assert.equal(errors.some((error) => error.code === 'UNKNOWN_MEMBER'), true);
});
