import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { nativeObservationSignature } from '../src/dynamic-main.mjs';
import { constrainGoalBoundNavigation, NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { toolResultContent } from '../src/native-minecraft-tools.mjs';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

function record(overrides = {}) {
	const fields = {
		originalRequest: 'get one stone',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:stone', count: 1 },
		createdAtTick: 10,
	};
	return {
		agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh', serviceTier: 'fast',
		goalRevision: 3, currentGoal: 'get one stone', currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) }, ...overrides,
	};
}

const testRegistry = { get: (agentId) => record({ agentId }) };

for (const kind of ['action', 'finish']) {
	test(`cancelling ${kind} settles before its bridge publication`, async () => {
		let releasePublication;
		const publication = new Promise((resolve) => { releasePublication = resolve; });
		const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: (type) => type === 'action_cancel' ? Promise.resolve() : publication } });
		let cancellation;
		const pending = runtime.execute({
			agentId: 'agent-a', goalRevision: 3, turnId: 'turn-cancel', callId: 'call-cancel',
			tool: kind === 'action' ? { kind, actionType: 'wait', arguments: { durationMs: 1 } } : { kind, summary: 'done' },
		}, record()).catch((error) => { cancellation = error.code; });
		await runtime.dispose('agent-a', 'bridge_disconnected');
		for (let index = 0; index < 8; index += 1) await Promise.resolve();
		const cancellationBeforeDelivery = cancellation;
		releasePublication();
		await pending;
		assert.match(cancellationBeforeDelivery ?? '', /^NATIVE_(ACTION|COMPLETION)_CANCELLED$/);
	});

	test(`a late ${kind} publication failure cannot erase replacement work`, async () => {
		const published = [];
		let rejectOldPublication;
		const oldPublication = new Promise((resolve, reject) => { rejectOldPublication = reject; });
		const publicationType = kind === 'action' ? 'action_command' : 'goal_completed';
		const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: (type, agentId, payload) => {
			if (type !== publicationType) return Promise.resolve();
			published.push(payload);
			return published.length === 1 ? oldPublication : Promise.resolve();
		} } });
		const request = {
			agentId: 'agent-a', goalRevision: 3, turnId: 'turn-race', callId: 'call-race',
			tool: kind === 'action' ? { kind, actionType: 'wait', arguments: { durationMs: 1 } } : { kind, summary: 'done' },
		};
		const oldResult = runtime.execute(request, record()).catch((error) => error);
		await runtime.dispose('agent-a', 'bridge_disconnected');
		const replacement = runtime.execute({ ...request, callId: 'call-replacement' }, record()).catch((error) => error);
		rejectOldPublication(new Error('old connection failed late'));
		assert.match((await oldResult).code, /^NATIVE_(ACTION|COMPLETION)_CANCELLED$/);
		assert.equal(published.length, 2);
		const accepted = kind === 'action'
			? runtime.onActionResult(record(), { actionId: published[1].actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'DONE' })
			: runtime.onCompletionResult(record(), { traceId: published[1].traceId, goalFingerprint: published[1].goalFingerprint, goalRevision: 3, verified: false, reasonCode: 'INVENTORY_MISSING', facts: [] });
		assert.equal(accepted, true, 'late failure must only remove its own pending publication');
		assert.equal((await replacement).state, kind === 'action' ? 'SUCCEEDED' : 'ACTIVE');
	});
}

test('native telemetry exceptions cannot interrupt body dispatch or completion', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		registry: testRegistry, bridge: { send: async (...args) => sent.push(args) },
		trace: () => { throw new Error('telemetry unavailable'); },
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-telemetry', callId: 'call-telemetry',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record()).catch((error) => error);
	await Promise.resolve();
	assert.equal(sent.length, 1);
	assert.equal(runtime.onActionProgress(record(), { actionId: sent[0][2].actionId, progress: 0.5 }), true);
	assert.equal(runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await pending).state, 'SUCCEEDED');
});

test('rejected asynchronous native telemetry never escapes as an unhandled rejection', async () => {
	const sent = [];
	const traces = [];
	const runtime = new NativeToolRuntime({
		registry: testRegistry, bridge: { send: async (...args) => sent.push(args) },
		trace: async (event) => {
			traces.push(event);
			throw new Error('telemetry storage unavailable');
		},
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-async-telemetry', callId: 'call-async-telemetry',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.onActionProgress(record(), { actionId, progress: 0.5 }), true);
	assert.equal(runtime.onActionResult(record(), { actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await pending).state, 'SUCCEEDED');
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(traces, [
		'native_tool_dispatch_started', 'native_tool_command_sent',
		'native_tool_action_progress', 'native_tool_action_completed',
	]);
});

test('native action is rejected before bridge enqueue when the registry has advanced', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		registry: { get: (agentId) => record({ agentId, goalRevision: 4 }) },
		bridge: { send: async (...args) => sent.push(args) },
	});

	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-stale', callId: 'call-stale',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record()), (error) => error?.code === 'STALE_PLAN');
	assert.equal(sent.some(([type]) => type === 'action_command'), false);
});

test('native body dispatches one correlated action and resolves only its matching result', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), { player: { position: { x: 0, y: 64, z: 0 } }, inventory: [] }, { eventSequence: 8 });

	const result = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
	}, record());
	await Promise.resolve();
	assert.equal(sent.length, 1);
	assert.equal(sent[0][0], 'action_command');
	assert.equal(sent[0][1], 'agent-a');
	assert.equal(sent[0][2].actionType, 'navigate_to');
	assert.equal(sent[0][2].provenance.model, 'gpt-5.6-sol');
	assert.equal(sent[0][2].provenance.sourceStepId, 'call-1');

	assert.equal(runtime.onActionResult(record(), { goalRevision: 3, actionId: 'wrong', state: 'SUCCEEDED' }), false);
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.onActionResult(record(), { goalRevision: 3, actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }), true);
	assert.deepEqual(await result, { state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
});

test('urgent interruption returns a running action without cancelling its body command', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-interrupt', callId: 'call-interrupt',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 20, y: 64, z: 20, timeoutMs: 30_000 } },
	}, record());
	for (let attempt = 0; attempt < 8 && sent.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent.length, 1, 'the body command starts before its interrupt is delivered');
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.interruptBlockingTool?.('agent-a', 'danger'), true);
	const early = await Promise.race([
		pending,
		new Promise((resolve) => setTimeout(() => resolve({ state: 'STILL_BLOCKED' }), 10_000).unref()),
	]);
	assert.deepEqual(early, {
		actionId, goalRevision: 3, actionType: 'navigate_to', state: 'RUNNING', interruptedBy: 'danger',
		recoveryHint: `replaceAction with actionId ${actionId} and your authored fight_target/flee_from switches now; cancelAction with the same actionId stops it.`,
	});
	assert.deepEqual(sent.map(([type]) => type), ['action_command'], 'steering does not cancel the physical action');
	await assert.rejects(runtime.execute(nativeCall({ kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } }), record()), (error) => {
		assert.equal(error.code, 'NATIVE_ACTION_IN_PROGRESS');
		assert.match(error.message, new RegExp(actionId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
		assert.match(error.message, /replaceAction/);
		assert.match(error.message, /cancelAction/);
		return true;
	});
	assert.equal(runtime.onActionResult(record(), { actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
});

test('say uses its own communication slot while the body action keeps running', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const body = runtime.execute(nativeCall({ kind: 'action', actionType: 'navigate_to', arguments: { x: 20, y: 64, z: 20, timeoutMs: 30_000 } }), record());
	for (let attempt = 0; attempt < 8 && sent.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	const bodyId = sent[0][2].actionId;
	const say = runtime.execute(nativeCall({ kind: 'action', actionType: 'chat', arguments: { message: 'I am answering while I continue.', audience: 'direct', recipientId: 'player-a' } }, { callId: 'call-say' }), record());
	for (let attempt = 0; attempt < 8 && sent.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map(([type]) => type), ['action_command', 'action_command']);
	assert.equal(sent[1][2].actionType, 'chat');
	const sayId = sent[1][2].actionId;
	assert.equal(runtime.onActionResult(record(), { actionId: sayId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'CHAT_SENT' }), true);
	assert.equal((await say).state, 'SUCCEEDED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status', actionId: bodyId }), record())).state, 'RUNNING');
	assert.equal(runtime.onActionResult(record(), { actionId: bodyId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'ARRIVED' }), true);
	assert.equal((await body).state, 'SUCCEEDED');
});

test('disposeAll cancels a communication-only action during coordinator shutdown', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const say = runtime.execute(nativeCall({ kind: 'action', actionType: 'chat', arguments: { message: 'One moment.', audience: 'direct', recipientId: 'player-a' } }), record());
	for (let attempt = 0; attempt < 8 && sent.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	const sayId = sent[0][2].actionId;
	await runtime.disposeAll('coordinator_stopped');
	const outcome = await Promise.race([
		say.then(() => ({ state: 'resolved' }), (error) => ({ state: 'rejected', code: error.code })),
		new Promise((resolve) => setTimeout(() => resolve({ state: 'STUCK' }), 10_000).unref()),
	]);
	assert.deepEqual(outcome, { state: 'rejected', code: 'NATIVE_ACTION_CANCELLED' });
	assert.deepEqual(sent.map(([type, _agentId, payload]) => [type, payload.actionId]), [
		['action_command', sayId], ['action_cancel', sayId],
	]);
});

test('a danger-authored fight action replaces the exact interrupted body action', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const body = runtime.execute(nativeCall({ kind: 'action', actionType: 'navigate_to', arguments: { x: 20, y: 64, z: 20, timeoutMs: 30_000 } }), record());
	for (let attempt = 0; attempt < 8 && sent.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	const originalId = sent[0][2].actionId;
	runtime.interruptBlockingTool('agent-a', 'danger');
	assert.equal((await body).actionId, originalId);
	const defense = runtime.execute(nativeCall({ kind: 'action', actionType: 'fight_target', arguments: { targetId: 'mob-uuid', timeoutMs: 15_000 } }, { callId: 'call-defense' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map(([type]) => type), ['action_command', 'action_cancel']);
	assert.deepEqual(sent[1][2], { actionId: originalId, goalRevision: 3 });
	assert.equal(runtime.onActionResult(record(), { actionId: originalId, goalRevision: 3, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' }), true);
	for (let attempt = 0; attempt < 8 && sent.length < 3; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent[2][2].actionType, 'fight_target');
	assert.equal(sent[2][2].provenance.sourceStepId, 'call-defense');
	assert.equal(runtime.onActionResult(record(), { actionId: sent[2][2].actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'TARGET_KILLED' }), true);
	assert.equal((await defense).state, 'SUCCEEDED');
});

test('replace_action can return early while its exact cancellation is still awaiting acknowledgement', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 30_000 } }), record());
	const replacement = runtime.execute(nativeCall({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', arguments: { durationMs: 1 } }), record());
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map(([type]) => type), ['action_command', 'action_cancel']);
	assert.equal(runtime.interruptBlockingTool('agent-a'), true);
	const early = await Promise.race([replacement, new Promise((resolve) => setTimeout(() => resolve({ state: 'STILL_BLOCKED' }), 10_000).unref())]);
	assert.equal(early.state, 'CANCELLING');
	assert.equal(early.actionId, handle.actionId);
	assert.equal(early.interruptedBy, 'danger');
	assert.match(early.recoveryHint, /cancellation.*requested/i);
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1, 'replacement waits for the exact cancellation receipt');
	assert.equal(runtime.onActionResult(record(), { actionId: handle.actionId, goalRevision: 3, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' }), true);
});

test('an interrupted sequence reports its active step and leaves later actions unstarted', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence-interrupt', callId: 'call-sequence-interrupt',
		tool: { kind: 'sequence', actions: [
			{ actionType: 'navigate_to', arguments: { x: 8, y: 64, z: 8, timeoutMs: 30_000 } },
			{ actionType: 'wait', arguments: { durationMs: 1_000 } },
		] },
	}, record());
	for (let attempt = 0; attempt < 8 && sent.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent.length, 1, 'only the current sequence step has started');
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.interruptBlockingTool('agent-a'), true);
	const early = await Promise.race([
		pending,
		new Promise((resolve) => setTimeout(() => resolve({ state: 'STILL_BLOCKED' }), 10_000).unref()),
	]);
	assert.equal(early.state, 'RUNNING');
	assert.equal(early.actionId, actionId);
	assert.equal(early.actionType, 'navigate_to');
	assert.equal(early.interruptedBy, 'danger');
	assert.deepEqual(early.sequence, { runningStep: 1, completedSteps: 0, remainingSteps: 1 });
	assert.match(early.recoveryHint, /Later steps were not started/);
	assert.deepEqual(sent.map(([type]) => type), ['action_command']);
	assert.equal(runtime.onActionResult(record(), { actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map(([type]) => type), ['action_command'], 'the sequence remainder requires a new model-authored call');
});

test('native action results do not attach stale recovery without a fresh observation', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
	}, { eventSequence: 1 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-stale-recovery', callId: 'call-stale-recovery',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, current);
	await Promise.resolve();
	runtime.onActionResult(current, { goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	const result = await pending;
	assert.equal(result.recovery, undefined);
	});
test('native action preserves authoritative progress and terminal observation evidence', async () => {
	const sent = [];
	const traces = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) }, trace: (...args) => traces.push(args) });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-evidence', callId: 'call-evidence',
		tool: { kind: 'action', actionType: 'mine', arguments: { x: 2, y: 64, z: 1, timeoutMs: 10_000 } },
	}, record());
	await Promise.resolve();
	const actionId = sent[0][2].actionId;
	const actionObservation = { worldTick: 9, observedAtEpochMs: 100, target: { kind: 'block', position: { x: 2, y: 64, z: 1 }, currentId: 'minecraft:oak_log' }, progress: { value: 0.25, basis: 'block_damage', verified: true } };
	assert.equal(runtime.onActionProgress(record(), { goalRevision: 3, actionId, progress: 0.25, actionObservation }), true);
	const progressTrace = traces.find(([event]) => event === 'native_tool_action_progress');
	assert.ok(progressTrace);
	const resultObservation = { ...actionObservation, worldTick: 10, target: { ...actionObservation.target, currentId: 'minecraft:air', worldChanged: true }, progress: { value: 1, basis: 'world_mutation', verified: true } };
	assert.equal(runtime.onActionResult(record(), {
		goalRevision: 3, actionId, state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', executionStarted: true, physicalAttempted: true,
		actionObservation: resultObservation,
	}), true);
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', executionStarted: true, physicalAttempted: true, actionObservation: resultObservation,
	});
	assert.deepEqual(progressTrace[1].actionObservation, actionObservation);
});

test('native observe returns latest compact facts without sending a body command', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] }, { eventSequence: 4 });
	const result = await runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'observe-1', tool: { kind: 'observe' } }, record());
	assert.equal(result.freshness.fresh, false);
	assert.equal(result.freshness.reasonCode, 'FRESH_OBSERVATION_UNAVAILABLE');
	delete result.freshness;
	delete result.observation.exploration;
	assert.deepEqual(result, {
		eventSequence: 4,
		goal: 'get one stone',
		goalSpec: record().currentGoalSpec,
		observation: { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] },
	});
	assert.deepEqual(sent, []);
});

test('identical heartbeat refresh advances sequence without re-ingesting world state', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
	const current = record();
	const conversation = {
		mode: 'history',
		nextSequence: 4,
		entries: [{ sequence: 4, kind: 'agent_message', sourceId: 'agent-b', recipientId: 'agent-a', text: 'hello' }],
	};
	const observation = {
		player: { x: 0, y: 64, z: 0, health: 20, dead: false },
		inventory: { items: [{ itemId: 'minecraft:stone', count: 1 }] },
	};
	runtime.updateObservation(current, observation, { eventSequence: 1, conversation });
	assert.equal(runtime.refreshObservation(current, observation, { eventSequence: 2 }), true);
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-refresh', callId: 'observe-refresh', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.eventSequence, 2);
	assert.equal(result.observation.exploration.destination, null);
	delete result.observation.exploration;
	assert.deepEqual(result.observation, {
		...observation,
		recovery: {
			alreadyHave: ['minecraft:stone'],
			alreadyHaveFacts: [{ kind: 'inventory', itemId: 'minecraft:stone', count: 1 }],
			facts: 'Currently evidenced: minecraft:stone.',
		},
	});
	assert.deepEqual(result.conversation, conversation);
	assert.equal(runtime.refreshObservation(current, observation, { eventSequence: 2 }), false, 'duplicate sequence is ignored');
});

test('unchanged actionable heartbeat still refreshes clocks, cooldowns, effects, and live evidence', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
	const current = record();
	const initial = {
		player: { x: 0, y: 64, z: 0, dead: false, effects: [{ id: 'speed', duration: 100 }] },
		inventory: { items: [{ itemId: 'minecraft:stone', count: 1 }] },
		world: { gameTime: 100, dayTime: 100 },
		interaction: { attackCooldown: 0.1, useRemainingTicks: 20 },
	};
	const latest = structuredClone(initial);
	initial.observedAtEpochMs = 100;
	latest.observedAtEpochMs = 200;
	latest.player.effects[0].duration = 90;
	latest.world = { gameTime: 110, dayTime: 110 };
	latest.interaction = { attackCooldown: 0.9, useRemainingTicks: 10 };
	assert.equal(nativeObservationSignature(initial), nativeObservationSignature(latest));
	runtime.updateObservation(current, initial, { eventSequence: 1 });
	assert.equal(runtime.refreshObservation(current, latest, { eventSequence: 2 }), true);
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-refresh', callId: 'observe-refresh', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.eventSequence, 2);
	assert.equal(result.observation.observedAtEpochMs, 200);
	assert.equal(result.freshness.observedAtEpochMs, 200);
	assert.deepEqual(result.observation.world, latest.world);
	assert.deepEqual(result.observation.interaction, latest.interaction);
	assert.deepEqual(result.observation.player.effects, latest.player.effects);
	assert.deepEqual(runtime.snapshotLive(current.agentId), { observation: latest, eventSequence: 2, goalRevision: 3 });
	latest.world.gameTime = 999;
	assert.equal(runtime.snapshotLive(current.agentId).observation.world.gameTime, 110, 'snapshot owns its raw facts');
});

test('lookAround turns the real player in bounded steps and preserves the observed hand and slot', async () => {
	const sent = [];
	const samples = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, requestObservation: async (_record, { afterEventSequence }) => {
		samples.push(afterEventSequence);
		return { eventSequence: afterEventSequence + 1, observation: { observedAtEpochMs: samples.length * 10, world: { dimension: 'minecraft:overworld' }, entities: [{ uuid: `seen-${samples.length}`, type: 'minecraft:pig' }] } };
	} });
	runtime.updateObservation(record(), {
		interaction: { input: { selectedSlot: 3, hand: 'off_hand' } },
	}, { eventSequence: 1 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-look', callId: 'look-1',
		tool: { kind: 'lookAround', centerYaw: 0, pitch: 5, steps: 4, ticksPerStep: 2 },
	}, record());
	for (const [index, yaw] of [90, 180, -90, 0].entries()) {
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(sent.length, index + 1);
		assert.deepEqual(sent[index][2].arguments, {
			forward: 0, strafe: 0, jump: false, sneak: false, sprint: false,
			attack: false, use: false, yaw, pitch: 5, selectedSlot: 3, hand: 'off', ticks: 2,
		});
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	const result = await pending;
	assert.equal(result.state, 'SUCCEEDED');
	assert.deepEqual(samples, [1, 2, 3, 4]);
	assert.deepEqual(result.samples.map((sample) => sample.yaw), [90, 180, -90, 0]);
	assert.deepEqual(result.samples.map((sample) => sample.eventSequence), [2, 3, 4, 5]);
	assert.deepEqual(result.samples.map((sample) => sample.entities[0].uuid), ['seen-1', 'seen-2', 'seen-3', 'seen-4']);
	assert.ok(result.samples.every((sample) => sample.historical && sample.dimension === 'minecraft:overworld'));
});

test('native lifecycle disposal cancels an outstanding body action and rejects the tool', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record());
	await Promise.resolve();
	await runtime.dispose('agent-a', 'goal_steered');
	await assert.rejects(pending, (error) => error?.code === 'NATIVE_ACTION_CANCELLED');
	assert.equal(sent.at(-1)[0], 'action_cancel');
	assert.equal(runtime.isActionResultStale(record(), { goalRevision: 3, actionId: sent[0][2].actionId }), true);
	assert.equal(runtime.isActionResultStale(record(), { goalRevision: 3, actionId: 'unknown' }), false);
});

test('native finish asks Minecraft to verify the immutable server goal before reporting success', async () => {
	const sent = [];
	const finished = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		onFinish: async (request) => finished.push(request),
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Stone acquired.' },
	}, record(), { lifecycleGeneration: 7 });
	await Promise.resolve();
	assert.equal(sent[0][0], 'goal_completed');
	assert.equal(sent[0][2].goalFingerprint, record().currentGoalSpec.fingerprint);
	assert.equal(Object.hasOwn(sent[0][2], 'completionContract'), false);
	assert.equal(runtime.onCompletionResult(record(), {
		goalRevision: 3,
		traceId: sent[0][2].traceId,
		goalFingerprint: sent[0][2].goalFingerprint,
		verified: true,
		reasonCode: 'COMPLETION_VERIFIED',
		facts: [{ type: 'inventory_contains', satisfied: true, expectedValue: 'minecraft:stone x1', observedValue: 'minecraft:stone x1' }],
	}), true);
	assert.deepEqual(await pending, {
		state: 'COMPLETED', verified: true, reasonCode: 'COMPLETION_VERIFIED',
		facts: [{ type: 'inventory_contains', satisfied: true, expectedValue: 'minecraft:stone x1', observedValue: 'minecraft:stone x1' }],
	});
	assert.equal(finished.length, 1);
	assert.equal(finished[0].lifecycleGeneration, 7);
});

test('native observe ignores a stale observation event sequence', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, { player: { health: 20 } }, { eventSequence: 8 });
	runtime.updateObservation(current, { player: { health: 10 } }, { eventSequence: 7 });
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'observe-stale', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.freshness.fresh, false);
	delete result.freshness;
	delete result.observation.exploration;
	assert.deepEqual(result, {
		eventSequence: 8,
		goal: 'get one stone',
		goalSpec: record().currentGoalSpec,
		observation: { player: { health: 20 } },
	});
});

test('goal-bound navigation cannot succeed outside the immutable position radius', async () => {
	const sent = [];
	const fields = {
		originalRequest: 'Move to 12 64 12',
		predicate: { type: 'position_within', x: 12, y: 64, z: 12, radius: 1, stableTicks: 20 },
		createdAtTick: 10,
	};
	const positioned = record({
		currentGoal: fields.originalRequest,
		currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) },
	});
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-position', callId: 'move-position',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 12, y: 64, z: 12, tolerance: 4, sprint: true, timeoutMs: 30_000 } },
	}, positioned);
	await Promise.resolve();
	assert.equal(sent[0][2].arguments.tolerance, 1);
	runtime.onActionResult(positioned, {
		goalRevision: 3,
		actionId: sent[0][2].actionId,
		state: 'FAILED',
		reasonCode: 'PATH_BLOCKED',
		message: 'Navigation could not recover from repeated stalls',
		executionStarted: true,
	});
	assert.deepEqual(await pending, {
		state: 'FAILED', reasonCode: 'PATH_BLOCKED',
		message: 'Navigation could not recover from repeated stalls', executionStarted: true,
		failureClass: 'path',
	});
});

test('goal-bound navigation honors a matching position nested in a compound goal', async () => {
	const sent = [];
	const fields = {
		originalRequest: 'Move to 12 64 12 and survive for a minute',
		predicate: {
			type: 'all_of',
			predicates: [
				{ type: 'survive_duration', ticks: 1_200 },
				{ type: 'position_within', x: 12, y: 64, z: 12, radius: 0.01, stableTicks: 20 },
			],
		},
		createdAtTick: 10,
	};
	const positioned = record({
		currentGoal: fields.originalRequest,
		currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) },
	});
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-position', callId: 'move-position',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 12, y: 64, z: 12, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
	}, positioned);
	await Promise.resolve();
	assert.equal(sent[0][2].arguments.tolerance, 0.01);
	runtime.onActionResult(positioned, {
		goalRevision: 3,
		actionId: sent[0][2].actionId,
		state: 'FAILED',
		reasonCode: 'PATH_BLOCKED',
		executionStarted: true,
	});
	await pending;
});

test('nested position constraints do not clamp navigation to unrelated coordinates', () => {
	const goalSpec = {
		predicate: {
			type: 'any_of',
			predicates: [
				{ type: 'position_within', x: 12, y: 64, z: 12, radius: 0.5, stableTicks: 20 },
				{
					type: 'all_of',
					predicates: [
						{ type: 'position_within', x: 99, y: 70, z: -4, radius: 0.01, stableTicks: 20 },
						{ type: 'survive_duration', ticks: 1_200 },
					],
				},
			],
		},
	};
	const matching = constrainGoalBoundNavigation({
		kind: 'action', actionType: 'navigate_to',
		arguments: { x: 12, y: 64, z: 12, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	}, goalSpec);
	const unrelated = constrainGoalBoundNavigation({
		kind: 'action', actionType: 'navigate_to',
		arguments: { x: 20, y: 64, z: 20, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	}, goalSpec);
	assert.equal(matching.arguments.tolerance, 0.5);
	assert.equal(unrelated.arguments.tolerance, 1);
});

test('a false finish stays active and returns Minecraft evidence to the same turn', async () => {
	const finished = [];
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		onFinish: async (request) => finished.push(request),
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-false',
		tool: { kind: 'finish', summary: 'I made the pickaxe.' },
	}, record());
	await Promise.resolve();
	assert.equal(runtime.onCompletionResult(record(), {
		goalRevision: 3, traceId: sent[0][2].traceId, goalFingerprint: sent[0][2].goalFingerprint,
		verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:stone_pickaxe x1' }],
	}), true);
	assert.deepEqual(await pending, {
		state: 'ACTIVE', verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:stone_pickaxe x1' }],
	});
	assert.deepEqual(finished, []);
});

test('native completion cannot overlap an active physical action', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const action = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'action-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record());
	await Promise.resolve();
	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Done.' },
	}, record()), (error) => error?.code === 'NATIVE_ACTION_IN_PROGRESS');
	assert.deepEqual(sent.map(([type]) => type), ['action_command']);
	runtime.onActionResult(record(), { goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	await action;
});

test('a physical action cannot overlap native completion verification', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const completion = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Done.' },
	}, record());
	await Promise.resolve();
	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'action-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record()), (error) => error?.code === 'NATIVE_COMPLETION_IN_PROGRESS');
	assert.deepEqual(sent.map(([type]) => type), ['goal_completed']);
	runtime.onCompletionResult(record(), {
		goalRevision: 3, traceId: sent[0][2].traceId, goalFingerprint: sent[0][2].goalFingerprint, verified: true, reasonCode: 'COMPLETION_VERIFIED', facts: [],
	});
	await completion;
});

test('native body isolates sixteen concurrent agents and their action results', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const records = Array.from({ length: 16 }, (_, index) => record({ agentId: `agent-${index + 1}` }));
	const pending = records.map((entry, index) => runtime.execute({
		agentId: entry.agentId,
		goalRevision: entry.goalRevision,
		turnId: `turn-${index + 1}`,
		callId: `call-${index + 1}`,
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, entry));
	await Promise.resolve();
	assert.equal(sent.length, 16);
	for (let index = 0; index < records.length; index += 1) {
		assert.equal(runtime.onActionResult(records[index], {
			goalRevision: 3,
			actionId: sent[index][2].actionId,
			state: 'SUCCEEDED',
			reasonCode: '',
		}), true);
	}
	assert.equal((await Promise.all(pending)).every((result) => result.state === 'SUCCEEDED'), true);
	assert.equal(new Set(sent.map((entry) => entry[2].actionId)).size, 16);
});

test('native sequence executes model-authored actions in order and returns every factual result', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence', callId: 'sequence-1',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map((entry) => entry[2].actionType), ['navigate_to']);
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map((entry) => entry[2].actionType), ['navigate_to', 'break_block']);
	runtime.onActionResult(record(), { actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED',
		completed: 2,
		results: [
			{ actionType: 'navigate_to', state: 'SUCCEEDED', reasonCode: '', executionStarted: true },
			{ actionType: 'break_block', state: 'SUCCEEDED', reasonCode: '', executionStarted: true },
		],
	});
});

test('native sequence stops before later actions after the first factual failure', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence-fail', callId: 'sequence-fail',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true });
	assert.deepEqual(await pending, {
		state: 'FAILED',
		completed: 1,
		failedAt: 0,
		results: [{ actionType: 'navigate_to', state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true, failureClass: 'path' }],
	});
	assert.equal(sent.length, 1);
});

test('native sequence verifies a model-authored finish only after successful actions and a newer observation', async () => {
	let runtime;
	let publishFreshFacts;
	const events = [];
	const finished = [];
	runtime = new NativeToolRuntime({
		bridge: { send: async (type, agentId, payload) => {
			events.push(type);
			if (type === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), {
				actionId: payload.actionId, goalRevision: payload.goalRevision, state: 'SUCCEEDED', reasonCode: 'WAIT_COMPLETED',
			}));
			if (type === 'goal_completed') queueMicrotask(() => runtime.onCompletionResult(record(), {
				goalRevision: payload.goalRevision, traceId: payload.traceId, goalFingerprint: payload.goalFingerprint,
				verified: true, reasonCode: 'COMPLETION_VERIFIED', facts: [],
			}));
		} },
		requestObservation: async (_record, { afterEventSequence }) => {
			events.push('fresh_observation');
			return new Promise((resolve) => { publishFreshFacts = () => resolve({
				 eventSequence: afterEventSequence + 1,
				 observation: { inventory: { items: [{ itemId: 'minecraft:stone', count: 1 }] } },
			}); });
		},
		onFinish: async (request) => finished.push(request),
	});
	runtime.updateObservation(record(), { inventory: { items: [] } }, { eventSequence: 4 });
	const pending = runtime.execute(nativeCall({
		kind: 'sequence',
		actions: [1, 2].map((durationMs) => ({ actionType: 'wait', arguments: { durationMs } })),
		finish: { summary: 'The stone is ready.' },
	}), record(), { lifecycleGeneration: 7 });
	for (let attempt = 0; publishFreshFacts === undefined && attempt < 20; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(typeof publishFreshFacts, 'function', 'fresh facts are requested after all authored actions settle');
	assert.deepEqual(events, ['action_command', 'action_command', 'fresh_observation']);
	await assert.rejects(runtime.execute(nativeCall({ kind: 'finish', summary: 'Duplicate finish.' }), record()), { code: 'NATIVE_ACTION_IN_PROGRESS' });
	publishFreshFacts();
	const result = await pending;
	assert.deepEqual(events, ['action_command', 'action_command', 'fresh_observation', 'goal_completed']);
	assert.deepEqual(result.finish, { state: 'COMPLETED', verified: true, reasonCode: 'COMPLETION_VERIFIED', facts: [] });
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.eventSequence, 5);
	assert.equal(finished.length, 1);
	assert.equal(finished[0].lifecycleGeneration, 7);
});

test('native sequence suppresses its authored finish after a failed action or unavailable fresh facts', async () => {
	for (const scenario of ['failed_action', 'stale_facts']) {
		const sent = [];
		let runtime;
		runtime = new NativeToolRuntime({
			bridge: { send: async (type, agentId, payload) => {
				sent.push([type, payload]);
				if (type === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), {
					actionId: payload.actionId, goalRevision: payload.goalRevision,
					state: scenario === 'failed_action' ? 'FAILED' : 'SUCCEEDED',
					reasonCode: scenario === 'failed_action' ? 'INPUT_REJECTED' : 'WAIT_COMPLETED',
				}));
			} },
			requestObservation: scenario === 'failed_action' ? undefined : async () => {
				throw Object.assign(new Error('fresh sample unavailable'), { code: 'INSPECTION_UNAVAILABLE' });
			},
		});
		const result = await runtime.execute(nativeCall({
			kind: 'sequence',
			actions: [1, 2].map((durationMs) => ({ actionType: 'wait', arguments: { durationMs } })),
			finish: { summary: 'Finish only when verified.' },
		}), record());
		assert.deepEqual(result.finish, {
			state: 'SKIPPED', reasonCode: scenario === 'failed_action' ? 'ACTION_FAILED' : 'FRESH_OBSERVATION_REQUIRED',
		});
		assert.equal(sent.some(([type]) => type === 'goal_completed'), false);
		assert.equal(sent.filter(([type]) => type === 'action_command').length, scenario === 'failed_action' ? 1 : 2);
	}
});

test('exploreFrontier returns observed candidates without choosing or executing a route', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), {
		position: { x: 0, y: 64, z: 0 }, world: { worldId: 'world-a', dimension: 'minecraft:overworld' },
		blocks: [{ x: 8, y: 64, z: 0, blockId: 'minecraft:stone' }],
	}, { eventSequence: 1 });
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: { radius: 24, limit: 32 } }), record());
	assert.equal(result.kind, 'candidates');
	assert.equal(result.destination, null);
	assert.ok(result.candidates.some((entry) => entry.kind === 'observed_block'));
	assert.equal(result.freshness.fresh, false);
	assert.deepEqual(sent, []);
});

test('exploreFrontier uses a new sample and gives the AI explicitly unknown candidates', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		requestObservation: async () => ({ eventSequence: 5, observation: { position: { x: 20, y: 70, z: 2 }, world: { worldId: 'world-a', dimension: 'minecraft:the_nether' } } }),
	});
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: { radius: 24, limit: 4 } }), record());
	assert.equal(result.destination, null);
	assert.equal(result.dimension, 'minecraft:the_nether');
	assert.equal(result.freshness.fresh, true);
	assert.ok(result.candidates.length > 0);
	assert.ok(result.candidates.every((entry) => entry.reachability === 'unknown'));
	assert.deepEqual(sent, []);
});

test('exploreFrontier reports missing position without inventing a route', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('read-only query dispatched movement') } });
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: {} }), record());
	assert.equal(result.kind, 'no_observation');
	assert.equal(result.destination, null);
	assert.deepEqual(result.candidates, []);
});

test('action results omit recovery until a fresh observation arrives', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 2 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-craft', callId: 'craft-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record());
	await Promise.resolve();
	runtime.onActionResult(record(), {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true,
	});
	const result = await pending;
	assert.equal(result.recovery, undefined);
});

test('unavailable observations preserve live inventory for death recovery and resume live updates when ready', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	const live = {
		ready: true,
		player: { x: 8, y: 64, z: 2, health: 18, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	};
	runtime.updateObservation(current, live, { eventSequence: 1 });
	const unavailable = adaptObservation({ ready: false, status: 'PLAYER_UNAVAILABLE' });
	runtime.updateObservation(current, unavailable, { eventSequence: 2 });
	runtime.refreshObservation(current, unavailable, { eventSequence: 3 });
	const observed = await runtime.execute({
		agentId: current.agentId, goalRevision: current.goalRevision,
		turnId: 'turn-unavailable', callId: 'observe-unavailable', tool: { kind: 'observe' },
	}, current);
	assert.equal(observed.observation.ready, false);
	assert.equal(observed.observation.status, 'PLAYER_UNAVAILABLE');
	assert.equal(observed.eventSequence, 3);
	assert.deepEqual(runtime.snapshotLive(current.agentId).observation, live);
	const death = { cause: 'lava', x: 8, y: 64, z: 2, dimensionId: 'minecraft:overworld' };
	runtime.updateObservation(current, { death }, { eventSequence: 3, force: true });
	const dead = runtime.decorateObservation(current, { death });
	assert.deepEqual(dead.recovery.lastLostInventory, live.inventory.items);
	assert.equal(dead.recovery.alreadyHave.includes('minecraft:iron_pickaxe'), false);
	const resumed = { ...live, inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] } };
	runtime.updateObservation(current, resumed, { eventSequence: 4 });
	assert.deepEqual(runtime.snapshotLive(current.agentId).observation, resumed);
	assert.equal(runtime.decorateObservation(current, {}).recovery.alreadyHave.includes('minecraft:oak_log'), true);
});

test('death force-updates the observation cache and keeps last live inventory as lost, not held', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 8, y: 64, z: 2, health: 18, dead: false },
		inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
		blocks: [{ blockId: 'minecraft:crafting_table', x: 9, y: 64, z: 2 }],
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 6 });
	const death = { cause: 'lava', x: 8, y: 64, z: 2, dimensionId: 'minecraft:overworld' };
	assert.equal(runtime.updateObservation(current, { death }, { eventSequence: 6, force: true }), true);
	const decorated = runtime.decorateObservation(current, { death });
	assert.equal(decorated.continuity.phase, 'dead');
	assert.equal(decorated.failureClass, 'lifecycle');
	assert.equal(decorated.inventory.items.length, 0);
	assert.equal(decorated.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.equal(decorated.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
	assert.match(decorated.recovery.facts, /Current inventory is empty/);
	assert.equal(decorated.options, undefined, 'recovery facts do not prescribe a strategy');
});

test('bridge disconnect keeps recovery memory until the agent is removed', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 3 });
	await runtime.dispose('agent-a', 'bridge_disconnected');
	const decorated = runtime.decorateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	});
	assert.ok(decorated.recovery.alreadyHave.includes('minecraft:iron_pickaxe'));
	assert.equal(decorated.recovery.doNotRedo, undefined);
	await runtime.dispose('agent-a', 'agent_removed');
	const forgotten = runtime.decorateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
	});
	assert.equal(forgotten.recovery?.alreadyHave?.includes('minecraft:iron_pickaxe') === true, false);
});

test('empty decorate payloads do not wipe live inventory memory', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:diamond_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 2 });
	const decorated = runtime.decorateObservation(current, {});
	assert.ok(decorated.recovery.alreadyHave.includes('minecraft:diamond_pickaxe'));
	assert.equal(decorated.inventory.items[0].itemId, 'minecraft:diamond_pickaxe');
	decorated.inventory.items[0].count = 99;
	assert.equal(runtime.snapshotLive(current.agentId).observation.inventory.items[0].count, 1,
		'fallback decoration owns its nested facts when raw stores share a snapshot');
});

test('native sequence is cancelled if the lifecycle is disposed between steps', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence-dispose', callId: 'sequence-dispose',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'wait', arguments: { durationMs: 1 } },
				{ actionType: 'wait', arguments: { durationMs: 1 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	await runtime.dispose('agent-a', 'goal_replaced');
	await assert.rejects(pending, (error) => error?.code === 'NATIVE_ACTION_CANCELLED');
	assert.equal(sent.length, 1);
});

function nativeCall(tool, overrides = {}) { return { agentId: 'agent-a', goalRevision: 3, turnId: 'turn-new', callId: 'call-new', tool, ...overrides }; }

test('observe waits for a newer server sample and rejects a cached freshness claim', async () => {
	let sampled;
	let barrier;
	const runtime = new NativeToolRuntime({
		bridge: { send: async () => {} },
		requestObservation: async (_record, options) => { barrier = options; return new Promise((resolve) => { sampled = resolve; }); },
	});
	runtime.updateObservation(record(), { player: { health: 12 }, observedAtEpochMs: 10 }, { eventSequence: 4 });
	let returned = false;
	const pending = runtime.execute(nativeCall({ kind: 'observe' }), record()).then((result) => { returned = true; return result; });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(returned, false);
	assert.deepEqual(barrier, { afterEventSequence: 4 });
	sampled({ eventSequence: 5, observation: { player: { health: 9 }, observedAtEpochMs: 20 } });
	const result = await pending;
	assert.equal(result.observation.player.health, 9);
	assert.deepEqual(result.freshness, { fresh: true, afterEventSequence: 4, eventSequence: 5, observedAtEpochMs: 20 });
	const stale = runtime.execute(nativeCall({ kind: 'observe' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	sampled({ eventSequence: 5, observation: { player: { health: 9 } } });
	await assert.rejects(stale, { code: 'FRESH_OBSERVATION_REQUIRED' });
});

test('fresh observation cannot repopulate a disposed lifecycle', async () => {
	let sampled;
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, requestObservation: async () => new Promise((resolve) => { sampled = resolve; }) });
	const pending = runtime.execute(nativeCall({ kind: 'observe' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	await runtime.dispose('agent-a', 'goal_stopped');
	sampled({ eventSequence: 1, observation: { player: { health: 20 } } });
	await assert.rejects(pending, { code: 'STALE_NATIVE_TOOL' });
	assert.equal(runtime.hasCurrent(record()), false);
});

test('inspect uses the focused server query and preserves revisions and coverage', async () => {
	const calls = [];
	const response = { section: 'block', block: { blockId: 'minecraft:oak_sign', text: ['Turn left'] }, revision: 9, gameTime: 50, coverage: { returned: 1, accessible: true } };
	const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('inspection mutated player') }, inspectObservation: async (current, query) => { calls.push({ current, query }); return response; } });
	const query = { kind: 'inspect', section: 'block', x: 1, y: 64, z: 2, offset: 0, limit: 1 };
	const result = await runtime.execute(nativeCall(query), record());
	assert.deepEqual(calls[0].query, { section: 'block', x: 1, y: 64, z: 2, offset: 0, limit: 1 });
	assert.deepEqual(result, response);
	result.block.text[0] = 'changed';
	assert.equal(response.block.text[0], 'Turn left');
	const unavailable = new NativeToolRuntime({ bridge: { send: async () => {} } });
	await assert.rejects(unavailable.execute(nativeCall(query), record()), { code: 'INSPECTION_UNAVAILABLE' });
});

test('startAction exposes a handle, progress and an exact terminal receipt', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	assert.equal(handle.state, 'RUNNING');
	assert.equal(handle.actionId, sent[0][2].actionId);
	assert.equal(handle.goalRevision, 3);
	runtime.onActionProgress(record(), { actionId: handle.actionId, progress: 0.5, elapsedMs: 50 });
	const progress = await runtime.execute(nativeCall({ kind: 'action_status', actionId: handle.actionId }), record());
	assert.deepEqual(progress.progress, { value: 0.5, elapsedMs: 50 });
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	const result = await runtime.execute(nativeCall({ kind: 'action_status', actionId: handle.actionId }), record());
	assert.equal(result.actionId, handle.actionId);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.reasonCode, 'ACTION_COMPLETED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).state, 'IDLE');
});

test('cancel rejects mismatched handles and waits for exact acknowledgement', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	await assert.rejects(runtime.execute(nativeCall({ kind: 'cancel_action', actionId: 'wrong', goalRevision: 3 }), record()), { code: 'STALE_ACTION' });
	await assert.rejects(runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 2 }), record()), { code: 'STALE_ACTION' });
	assert.equal(sent.length, 1);
	let resolved = false;
	const pending = runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 3 }), record()).then((result) => { resolved = true; return result; });
	await Promise.resolve();
	assert.deepEqual(sent[1], ['action_cancel', 'agent-a', { actionId: handle.actionId, goalRevision: 3 }]);
	assert.equal(resolved, false);
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).state, 'CANCELLING');
	assert.equal(runtime.onActionResult(record(), { actionId: 'other', state: 'CANCELLED' }), false);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' });
	assert.equal((await pending).state, 'CANCELLED');
});

test('replace dispatches only after acknowledged cancellation and keeps model provenance', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	const pending = runtime.execute(nativeCall({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 3, actionType: 'look_at', arguments: { x: 2, y: 64, z: 3 } }), record());
	await Promise.resolve();
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent[2][2].actionType, 'look_at');
	assert.equal(sent[2][2].provenance.sourceStepId, 'call-new');
	runtime.onActionResult(record(), { actionId: sent[2][2].actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	assert.equal((await pending).state, 'SUCCEEDED');
});

test('replacement stays unstarted if the previous action finishes before cancellation', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	const pending = runtime.execute(nativeCall({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', arguments: { durationMs: 1 } }), record());
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	assert.equal((await pending).state, 'REPLACEMENT_NOT_STARTED');
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
});

test('replaceAction does not start from an old receipt outside a danger-paused fight or flee', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const previous = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	runtime.onActionResult(record(), { actionId: previous.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	const replacement = await runtime.execute(nativeCall({ kind: 'replace_action', actionId: previous.actionId, goalRevision: 3,
		actionType: 'flee_from', arguments: { targetId: '00000000-0000-0000-0000-0000000000a1', distance: 12, timeoutMs: 8_000 } }), record());
	assert.equal(replacement.state, 'REPLACEMENT_NOT_STARTED');
	assert.equal(replacement.reasonCode, 'ACTION_FINISHED_BEFORE_CANCEL');
	assert.equal(replacement.previous.actionId, previous.actionId);
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
	await runtime.disposeAll();
});

test('notebook scopes model notes and authoritative receipts to the observed world', async () => {
	const writes = [];
	const queries = [];
	const receipts = [];
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, notebook: {
		writeNote: async (...args) => { writes.push(args); return { saved: true, provenance: 'model_note' }; },
		query: async (...args) => { queries.push(args); return { notes: [], receipts: [] }; },
		recordReceipt: async (...args) => { receipts.push(args); },
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a', dimension: 'minecraft:overworld' } }, { eventSequence: 1 });
	await runtime.execute(nativeCall({ kind: 'notebook', key: 'return-route', text: 'Bridge may be east.' }), record());
	assert.deepEqual(writes[0], ['agent-a', { worldId: 'world-a', key: 'return-route', text: 'Bridge may be east.', goalRevision: 3, provenance: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh', serviceTier: 'fast', goalRevision: 3, turnId: 'turn-new', callId: 'call-new' } }]);
	await runtime.execute(nativeCall({ kind: 'query_memory', memoryKind: 'notes', offset: 5, limit: 20, text: 'Bridge' }), record());
	assert.deepEqual(queries[0], ['agent-a', { worldId: 'world-a', kind: 'notes', offset: 5, limit: 20, text: 'Bridge' }]);
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED', actionObservation: { worldTick: 18 } });
	await Promise.resolve();
	assert.deepEqual(receipts[0], ['agent-a', { worldId: 'world-a', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED', actionObservation: { worldTick: 18 }, tick: 18 }]);
});

test('spatial hydration replays queued observations before checkpoint persistence', async () => {
	let hydrate;
	const calls = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, occupancy: {
		load: async () => { calls.push('load'); await new Promise((resolve) => { hydrate = resolve; }); calls.push('loaded'); },
		ingest: (_agentId, observation) => calls.push(`ingest:${observation.world.gameTime}`),
		flush: async () => calls.push('flush'),
		clear: () => calls.push('clear'),
		candidates: () => ({ kind: 'candidates', candidates: [], destination: null }),
	} });
	runtime.updateObservation(record(), { world: { gameTime: 1 } }, { eventSequence: 1 });
	runtime.updateObservation(record(), { world: { gameTime: 2 } }, { eventSequence: 2 });
	await Promise.resolve();
	assert.deepEqual(calls, ['load']);
	hydrate();
	await runtime.initializeMemory('agent-a');
	assert.deepEqual(calls, ['load', 'loaded', 'ingest:1', 'ingest:2']);
	await runtime.dispose('agent-a', 'coordinator_stopped');
	assert.deepEqual(calls.slice(-2), ['flush', 'clear']);
});

test('native action identities remain unique across runtime restarts and long agent identifiers', async () => {
	const current = record({ agentId: 'agent-'.repeat(40) });
	const handles = [];
	for (let index = 0; index < 2; index++) {
		const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
		const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }, { agentId: current.agentId }), current);
		assert.ok(handle.actionId.length <= 128);
		handles.push(handle.actionId);
		runtime.onActionResult(current, { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	}
	assert.notEqual(handles[0], handles[1]);
});

test('fixture session identity is explicit and production runtimes remain random', async () => {
	const runtime = new NativeToolRuntime({ sessionId: 'fixture-replay', bridge: { send: async () => {} } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.match(handle.actionId, /^native:fixture-replay:1:/);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: '' });
	assert.throws(() => new NativeToolRuntime({ sessionId: 'unsafe session', bridge: { send: async () => {} } }), /sessionId/);
	const handles = [];
	for (let index = 0; index < 2; index++) {
		const fixture = new NativeToolRuntime({ sessionId: 'fixture-'.padEnd(128, 'a'), bridge: { send: async () => {} } });
		const result = await fixture.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
		handles.push(result.actionId);
		assert.ok(result.actionId.length <= 128);
		fixture.onActionResult(record(), { actionId: result.actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	assert.equal(handles[0], handles[1]);
});

test('capabilities and observe disclose effective settings without rewriting the selected profile', async () => {
	const settings = { requested: { reasoningEffort: 'medium' }, effective: { reasoningEffort: 'high' }, mapping: 'provider supported level' };
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, executionSettings: (current) => { assert.equal(current.model, record().model); return settings; } });
	for (const kind of ['capabilities', 'observe']) {
		const result = await runtime.execute(nativeCall({ kind }), record());
		assert.deepEqual(result.executionSettings, settings);
		result.executionSettings.effective.reasoningEffort = 'changed';
		assert.equal(settings.effective.reasoningEffort, 'high');
	}
});

test('control reference capabilities preserve topic pagination and perform no player dispatch', async () => {
	const sent = [];
	const settings = { requested: { reasoningEffort: 'medium' }, effective: { reasoningEffort: 'medium' } };
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, executionSettings: () => settings });
	const first = await runtime.execute(nativeCall({ kind: 'capabilities', section: 'control', topic: 'all', offset: 0 }), record());
	assert.equal(first.section, 'control');
	assert.equal(first.topic, 'all');
	assert.ok(first.nextOffset > 0);
	assert.deepEqual(first.executionSettings, settings);
	assert.equal(JSON.parse(toolResultContent(first).contentItems[0].text).reference, first.reference);
	const second = await runtime.execute(nativeCall({ kind: 'capabilities', section: 'control', topic: 'all', offset: first.nextOffset }), record());
	assert.equal(second.offset, first.nextOffset);
	assert.notEqual(second.reference, first.reference);
	assert.deepEqual(sent, []);
});

test('native memory operations share the program helper contract and model provenance', async () => {
	const calls = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async (current, operation) => { calls.push({ current, operation }); return { state: 'SUCCEEDED', reasonCode: 'MEMORY_QUERIED' }; } });
	await runtime.execute(nativeCall({ kind: 'query_memory', memoryKind: 'receipts', offset: 64, limit: 32 }), record());
	assert.deepEqual(calls[0].operation.arguments, { kind: 'receipts', offset: 64, limit: 32 });
	assert.equal(calls[0].operation.operation, 'query');
	assert.equal(calls[0].operation.provenance.model, record().model);
	assert.equal(calls[0].operation.provenance.callId, 'call-new');
});

test('the dispatch chain is traced per call: journal, bridge send and the Java accept, start and end clocks', async () => {
	const traces = [];
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => { await new Promise((resolve) => setTimeout(resolve, 5)); sent.push(args); } },
		trace: (event, fields) => traces.push([event, fields]),
		notebook: { writeNote: async () => {}, query: async () => ({}), recordReceipt: async () => {}, recordDispatch: async () => { await new Promise((resolve) => setTimeout(resolve, 5)); }, recordUnknown: async () => {} },
	});
	runtime.updateObservation(record(), { world: { worldId: 'world-a' } }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	await eventually(() => sent.length > 0);
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.onActionProgress(record(), { actionId, progress: 0.5, elapsedMs: 40, serverTick: 4_012 }), true);
	const timing = { acceptedAtEpochMs: 1_760_000_000_010, startedAtEpochMs: 1_760_000_000_050, startedTick: 4_001, endedTick: 4_020 };
	assert.equal(runtime.onActionResult(record(), { actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'DONE', elapsedMs: 900, observedAtEpochMs: 1_760_000_000_950, timing }), true);
	const modelResult = await pending;
	assert.equal(modelResult.state, 'SUCCEEDED');
	assert.equal(Object.keys(modelResult).some((key) => key.startsWith('java') || key === 'timing'), false, 'the model never sees the latency clocks');
	const by = (event) => traces.find(([name]) => name === event)[1];
	for (const event of ['native_tool_dispatch_started', 'native_tool_journal_written', 'native_tool_command_sent']) assert.equal(by(event).callId, 'call-new', event);
	assert.ok(by('native_tool_journal_written').journalMs >= 0);
	assert.ok(by('native_tool_command_sent').sendMs >= 0 && by('native_tool_command_sent').dispatchMs >= by('native_tool_command_sent').sendMs);
	assert.equal(by('native_tool_action_progress').serverTick, 4_012);
	const completed = by('native_tool_action_completed');
	assert.deepEqual(
		[completed.javaElapsedMs, completed.javaEndedAtEpochMs, completed.javaAcceptedAtEpochMs, completed.javaStartedAtEpochMs, completed.javaStartedTick, completed.javaEndedTick],
		[900, 1_760_000_000_950, 1_760_000_000_010, 1_760_000_000_050, 4_001, 4_020],
	);
});

test('durable preparation fences dispatch and exact cancellation prevents a later send', async () => {
	let release;
	const sent = [];
	const entries = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, notebook: {
		writeNote: async () => {}, query: async () => ({}), recordReceipt: async () => {},
		recordDispatch: async (_agentId, entry) => { entries.push(entry); await new Promise((resolve) => { release = resolve; }); },
		recordUnknown: async (_agentId, entry) => entries.push(entry),
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' } }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	await Promise.resolve();
	assert.equal(sent.length, 0);
	const handle = await runtime.execute(nativeCall({ kind: 'action_status' }), record());
	assert.equal(handle.state, 'PREPARING');
	const cancelled = await runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 3 }), record());
	assert.equal(cancelled.executionStarted, false);
	release();
	assert.equal((await pending).reasonCode, 'CANCELLED_BEFORE_DISPATCH');
	assert.equal(sent.length, 0);
	assert.deepEqual(entries[0].arguments, { durationMs: 100 });
});

test('unknown delivery is inspectable and late terminal evidence cannot cancel a newer action', async () => {
	const notebook = new ModelNotebook();
	let fail = true;
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async () => { if (fail) throw new Error('socket closed'); } } });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' } }, { eventSequence: 1 });
	let unknownId;
	await assert.rejects(runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record()), (error) => { unknownId = error.actionId; return error.message === 'socket closed'; });
	assert.equal((await notebook.findReceipt('agent-a', { actionId: unknownId })).state, 'UNKNOWN');
	const capabilities = await runtime.execute(nativeCall({ kind: 'capabilities' }), record());
	assert.equal(capabilities.unresolvedActions.total, 1);
	assert.equal(capabilities.unresolvedActions.entries[0].actionId, unknownId);
	fail = false;
	const next = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.equal(runtime.onActionResult(record(), { actionId: unknownId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }), true);
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).actionId, next.actionId);
	assert.equal((await notebook.findReceipt('agent-a', { actionId: unknownId })).state, 'SUCCEEDED');
	runtime.onActionResult(record(), { actionId: next.actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: '' });
});

test('restart reconciliation updates only matching durable native identities without execution', async () => {
	const notebook = new ModelNotebook();
	const actionId = 'native:previous-session:1:agent-a:2';
	await notebook.recordDispatch('agent-a', { worldId: 'original-world', actionId, goalRevision: 2, actionType: 'wait', arguments: { durationMs: 100 } });
	const sent = [];
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async (...args) => sent.push(args) } });
	const payload = { actionId, goalRevision: 2, actionType: 'wait', state: 'SUCCEEDED', reasonCode: '', executionStarted: true, actionObservation: { worldTick: 31 } };
	assert.equal(await runtime.reconcileActionReceipt('agent-a', { ...payload, actionId: 'native:unknown' }), false);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', { ...payload, goalRevision: 3 }), false);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', payload), true);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', payload), true);
	const saved = await notebook.findReceipt('agent-a', { actionId });
	assert.equal(saved.worldId, 'original-world');
	assert.equal(saved.state, 'SUCCEEDED');
	assert.equal(saved.executionStarted, true);
	assert.deepEqual(saved.arguments, { durationMs: 100 });
	assert.equal(sent.length, 0);
});

test('authoritative result wins over a later bridge send error', async () => {
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (_type, _agentId, payload) => {
		runtime.onActionResult(record(), { actionId: payload.actionId, state: 'SUCCEEDED', reasonCode: '' });
		throw new Error('late socket error');
	} } });
	const result = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status', actionId: result.actionId }), record())).state, 'SUCCEEDED');
});

test('focused visible target retains its delivered causal baseline for exact body references', async () => {
	const sent = [];
	const targetId = '24f7bbba-c3a7-41e7-831b-bec7291dbb23';
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, inspectObservation: async () => ({ eventSequence: 8, entries: [{ uuid: targetId }] }) });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, entities: [] }, { eventSequence: 7 });
	await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 20, limit: 10 }), record());
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, entities: [] }, { eventSequence: 9 });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
	assert.equal(sent[0][2].provenance.eventSequence, 8);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_VISIBLE' });
});

test('newly observed visible targets replace stale focused baselines for direct and program actions', async () => {
	const sent = [];
	const targetId = '24f7bbba-c3a7-41e7-831b-bec7291dbb23';
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'FAILED', reasonCode: 'OUT_OF_REACH' }));
	} }, inspectObservation: async () => ({ section: 'entities', eventSequence: 1, entries: [{ uuid: targetId }] }) });
	runtime.updateObservation(record(), { player: { health: 20 }, entities: [] }, { eventSequence: 1 });
	await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 0, limit: 1 }), record());
	for (const identity of ['uuid', 'stableId']) {
		runtime.updateObservation(record(), { player: { health: 20 }, entities: [{ [identity]: targetId }] }, { eventSequence: identity === 'uuid' ? 5000 : 5001 });
		await runtime.execute(nativeCall({ kind: 'action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
		assert.equal(sent.at(-1)[2].provenance.eventSequence, identity === 'uuid' ? 5000 : 5001);
	}
	await runtime.execute(nativeCall({ kind: 'run_program', source: `program.onUnhandledAttention("continue_and_notify"); await player.attack({ targetId: "${targetId}", timeoutMs: 1000 });` }), record());
	assert.equal(sent.at(-1)[2].provenance.eventSequence, 5001);
});

test('native programs preserve selected authorship and refresh before a dependent command', async () => {
	const notebook = new ModelNotebook();
	const sent = [];
	let sequence = 1;
	const observation = { world: { worldId: 'world-a' }, player: { x: 0, y: 64, z: 0, health: 20 }, blocks: [], entities: [], items: [], inventory: { items: [], tagCounts: {} } };
	let runtime;
	runtime = new NativeToolRuntime({ notebook, bridge: { send: async (...args) => {
		validateProtocolV2Payload(args[0], args[2]);
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }));
	} }, requestObservation: async () => ({ observation, eventSequence: ++sequence }) });
	runtime.updateObservation(record(), observation, { eventSequence: sequence });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await world.remember({ key: "intent", text: "I chose two waits." }); await player.wait(2); await player.wait(3);', maxActions: 4, timeoutMs: 5000 }), record());
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.deepEqual(sent.map(([, , payload]) => payload.arguments.durationMs), [2, 3]);
	assert.deepEqual(sent.map(([, , payload]) => payload.provenance.eventSequence), [1, 2]);
	assert.ok(sent.every(([, , payload]) => payload.provenance.model === record().model && payload.provenance.reasoningEffort === record().reasoningEffort && payload.provenance.serviceTier === record().serviceTier));
	assert.match(sent[0][2].provenance.programId, /^native-program-/);
	assert.match(result.receipts[0].bodyActionId, /^native:/);
	assert.equal((await notebook.query('agent-a', { worldId: 'world-a', kind: 'notes' })).entries[0].provenance.programId, sent[0][2].provenance.programId);
});

test('saved programs use an exact notebook key in the current world', async () => {
	const notebook = new ModelNotebook();
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async () => {} } });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, player: { health: 20 }, entities: [], blocks: [], items: [], inventory: { items: [] } }, { eventSequence: 1 });
	await runtime.execute(nativeCall({ kind: 'notebook', key: 'routine-other', text: 'program.onUnhandledAttention("continue_and_notify"); program.finish("wrong");' }), record());
	await assert.rejects(runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record()), { code: 'PROGRAM_NOTE_NOT_FOUND' });
	await runtime.execute(nativeCall({ kind: 'notebook', key: 'routine', text: 'program.onUnhandledAttention("continue_and_notify"); program.finish("saved");' }), record());
	assert.equal((await runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record())).reasonCode, 'PROGRAM_FINISH_REQUESTED');
	runtime.updateObservation(record(), { world: { worldId: 'world-b' }, player: { health: 20 } }, { eventSequence: 2 });
	await assert.rejects(runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record()), { code: 'PROGRAM_NOTE_NOT_FOUND' });
});

test('saved program lookup paginates fuzzy note matches until the exact key is found', async () => {
	const queries = [];
	const source = 'program.onUnhandledAttention("continue_and_notify"); program.finish("paged");';
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async (_record, operation) => {
		queries.push(operation.arguments);
		if (operation.arguments.offset === 0) return { entries: Array.from({ length: 64 }, (_, index) => ({ key: `routine-${index}`, text: source })), nextOffset: 64 };
		return { entries: [{ key: 'routine', text: source }], nextOffset: null };
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, player: { health: 20 }, entities: [], blocks: [], items: [], inventory: { items: [] } }, { eventSequence: 1 });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record());
	assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
	assert.deepEqual(queries.map(({ kind, text, offset, limit }) => ({ kind, text, offset, limit })), [
		{ kind: 'notes', text: 'routine', offset: 0, limit: 64 },
		{ kind: 'notes', text: 'routine', offset: 64, limit: 64 },
	]);
});

test('saved program lookup stops paging after its lifecycle is cancelled', async () => {
	let runtime;
	let queries = 0;
	runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async () => {
		queries += 1;
		await runtime.dispose('agent-a', 'goal_changed');
		return { entries: [], nextOffset: 64 };
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, player: { health: 20 }, entities: [], blocks: [], items: [], inventory: { items: [] } }, { eventSequence: 1 });
	await assert.rejects(runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record()), { code: 'NATIVE_PROGRAM_CANCELLED' });
	assert.equal(queries, 1, 'cancellation prevents another paginated lookup');
});

test('native programs inspect a raw page, use its facts in an action, and request finish', async () => {
	const sent = [], queries = [];
	let sequence = 1;
	const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } };
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'SUCCEEDED', reasonCode: 'MENU_CLOSED' }));
	} }, inspectObservation: async (_record, query) => {
		queries.push(query);
		return { section: 'menu', eventSequence: 1, menu: { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 } };
	}, requestObservation: async () => ({ observation, eventSequence: ++sequence }) });
	runtime.updateObservation(record(), observation, { eventSequence: sequence });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: `
		program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "menu" });
		await player.menuClose({ menuId: page.menu.menuId, containerId: page.menu.containerId, stateId: page.menu.stateId });
		program.finish("Container closed");
	` }), record());
	assert.equal(queries.length, 1);
	assert.equal(queries[0].section, 'menu');
	assert.equal(sent.length, 1);
	assert.equal(sent[0][0], 'action_command');
	assert.deepEqual(sent[0][2].arguments, { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 });
	assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
	assert.equal(result.finishRequested, true);
	assert.equal(result.actions, 1);
	assert.equal(result.receipts[0].state, 'SUCCEEDED');
});

for (const failureMode of ['result', 'throw']) {
	test(`native program inspection preserves ${failureMode} failure for the authored branch`, async () => {
		const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('failed inspection dispatched an action') }, inspectObservation: async () => {
			if (failureMode === 'throw') throw Object.assign(new Error('Inspection expired'), { code: 'INSPECTION_EXPIRED' });
			return { state: 'FAILED', reasonCode: 'INSPECTION_EXPIRED' };
		} });
		runtime.updateObservation(record(), { player: { health: 20 } }, { eventSequence: 1 });
		const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); const page = await world.inspect({ section: "menu" }); if (page.state === "FAILED" && page.reasonCode === "INSPECTION_EXPIRED") { program.finish("Inspection expired"); } else { await player.wait(1); }' }), record());
		assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
		assert.equal(result.actions, 0);
	});
}

test('native program cancellation fences its next step and disposal releases its body', async () => {
	const sent = [];
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_cancel') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, goalRevision: 3, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' }));
	} } });
	runtime.updateObservation(record(), { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(100); await player.wait(3);' }), record());
	await Promise.resolve();
	await assert.rejects(runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record()), { code: 'NATIVE_PROGRAM_IN_PROGRESS' });
	await runtime.dispose('agent-a', 'goal_changed');
	const result = await pending;
	assert.notEqual(result.state, 'SUCCEEDED');
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
	assert.ok(sent.some(([type]) => type === 'action_cancel'));
});

test('focused inspection binds only the page entries actually delivered after native compaction', async () => {
	const entries = Array.from({ length: 20 }, (_, index) => ({ uuid: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`, text: 'x'.repeat(2000) }));
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, inspectObservation: async () => ({ eventSequence: 8, offset: 0, entries }) });
	runtime.updateObservation(record(), { player: { health: 20 } }, { eventSequence: 7 });
	const page = await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 0, limit: 20 }), record());
	assert.ok(page.entries.length > 0 && page.entries.length < entries.length);
	for (const [targetId, expected] of [[page.entries[0].uuid, 8], [entries.at(-1).uuid, 7]]) {
		const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
		assert.equal(sent.at(-1)[2].provenance.eventSequence, expected);
		runtime.onActionResult(record(), { actionId: handle.actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_VISIBLE' });
	}
});

test('saved program reserves ownership before asynchronous notebook loading', async () => {
	let release;
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async () => new Promise((resolve) => { release = resolve; }) });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, player: { health: 20 } }, { eventSequence: 1 });
	const first = runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	await assert.rejects(runtime.execute(nativeCall({ kind: 'run_program', source: 'program.finish("other");' }), record()), { code: 'NATIVE_PROGRAM_IN_PROGRESS' });
	release({ entries: [{ key: 'routine', text: 'program.onUnhandledAttention("continue_and_notify"); program.finish("loaded");' }] });
	assert.equal((await first).reasonCode, 'PROGRAM_FINISH_REQUESTED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("next");' }), record())).reasonCode, 'PROGRAM_FINISH_REQUESTED');
});

test('lifecycle disposal fences a program still loading from the notebook', async () => {
	let release;
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async () => new Promise((resolve) => { release = resolve; }) });
	const loading = runtime.execute(nativeCall({ kind: 'run_program', noteKey: 'routine' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	await runtime.disposeAll('shutdown');
	release({ entries: [{ key: 'routine', text: 'program.finish("obsolete");' }] });
	await assert.rejects(loading, { code: 'NATIVE_PROGRAM_CANCELLED' });
});

test('camera sweep stops if a heading cannot obtain fresh facts', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute(nativeCall({ kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 4, ticksPerStep: 1 }), record());
	await new Promise((resolve) => setImmediate(resolve));
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	await assert.rejects(pending, { code: 'FRESH_OBSERVATION_REQUIRED' });
	assert.equal(sent.length, 1);
});

test('sweep waits for each sample, excludes remembered entities, and retains headings under the result budget', async () => {
	const sent = [];
	let release;
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, requestObservation: async (_record, { afterEventSequence }) => new Promise((resolve) => {
		release = () => resolve({ eventSequence: afterEventSequence + 1, observation: {
			world: { dimension: 'minecraft:overworld' }, continuity: { rememberedSections: ['entities'] },
			entities: [{ uuid: 'stale-target' }], blocks: Array.from({ length: 100 }, (_, x) => ({ blockId: 'minecraft:stone', x, y: 64, z: 0 })),
		} });
	}) });
	const pending = runtime.execute(nativeCall({ kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 8, ticksPerStep: 1 }), record());
	for (let index = 0; index < 8; index += 1) {
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(sent.length, index + 1);
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(sent.length, index + 1, 'next turn must wait for its predecessor sample');
		await assert.rejects(runtime.execute(nativeCall({ kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } }), record()), { code: 'NATIVE_ACTION_IN_PROGRESS' });
		release();
	}
	const result = await pending;
	assert.ok(result.samples.every((sample) => sample.entities.length === 0 && sample.omitted.blocks > 0));
	assert.ok(result.samples.every((sample) => Buffer.byteLength(JSON.stringify(sample), 'utf8') <= 1400));
	assert.ok(result.samples.every((sample) => sample.omitted.blocks === 100 - sample.blocks.length));
	result.results[0].actionObservation = { detail: 'x'.repeat(20_000) };
	const text = toolResultContent(result).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16_384);
	assert.equal(JSON.parse(text).samples.length, 8);
});

test('sweep retains a late distinct sighting ahead of repeated terrain', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		requestObservation: async (_record, { afterEventSequence }) => ({
			eventSequence: afterEventSequence + 1,
			observation: {
				world: { dimension: 'minecraft:overworld' },
				entities: Array.from({ length: 12 }, (_, index) => ({ stableId: `pig-${index}`, type: 'minecraft:pig', x: index, y: 64, z: 0 })),
				blocks: [
					...Array.from({ length: 100 }, (_, x) => ({ blockId: 'minecraft:grass_block', x, y: 64, z: 0 })),
				],
				landmarks: [
					...Array.from({ length: 24 }, (_, index) => ({ stableId: `grass-${index}`, blockId: 'minecraft:grass_block', x: index, y: 64, z: 2 })),
					{ stableId: 'tree', blockId: 'minecraft:oak_leaves', x: 9, y: 72, z: 2 },
				],
			},
		}),
	});
	const pending = runtime.execute(nativeCall({ kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 2, ticksPerStep: 1 }), record());
	for (let index = 0; index < 2; index += 1) {
		await new Promise((resolve) => setImmediate(resolve));
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	const result = await pending;
	assert.ok(result.samples.every((sample) => sample.entities.some((entity) => entity.type === 'minecraft:pig')));
	assert.ok(result.samples.every((sample) => sample.blocks.some((block) => block.blockId === 'minecraft:grass_block')));
	assert.ok(result.samples.every((sample) => sample.landmarks.some((landmark) => landmark.blockId === 'minecraft:oak_leaves')));
	assert.ok(result.samples.every((sample) => Buffer.byteLength(JSON.stringify(sample), 'utf8') <= 1400));
	assert.ok(result.samples.every((sample) => sample.omitted.entities === 12 - sample.entities.length));
	assert.ok(result.samples.every((sample) => sample.omitted.blocks === 100 - sample.blocks.length));
	assert.ok(result.samples.every((sample) => sample.omitted.landmarks === 25 - sample.landmarks.length));
});

for (const otherUnmet of [false, true]) {
 test(`completion distinguishes operator confirmation from unmet gameplay: ${otherUnmet}`, async () => {
  const sent = [];
  const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
  const pending = runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'confirm', tool: { kind: 'finish' } }, record());
  await Promise.resolve();
  runtime.onCompletionResult(record(), { goalRevision: 3, traceId: sent[0][2].traceId, goalFingerprint: sent[0][2].goalFingerprint, verified: false, reasonCode: 'PREDICATE_FAILED', facts: [
   { type: 'operator_confirmed', satisfied: false }, { type: 'inventory_contains', satisfied: !otherUnmet },
  ] });
  const result = await pending;
  assert.equal(result.verified, false);
  assert.equal(result.state, otherUnmet ? 'ACTIVE' : 'AWAITING_OPERATOR_CONFIRMATION');
  assert.equal(result.reasonCode, 'PREDICATE_FAILED');
 });
}

test('pickup result includes a fresh inventory sample and no consumed drop', async () => {
 const sent = [];
 let samples = 0;
 const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, requestObservation: async (_record, { afterEventSequence }) => {
  samples++;
  return { eventSequence: afterEventSequence + 1, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 1, slot: 'mainhand' }] }, entities: [], position: { x: 6, y: 201, z: 3 } } };
 } });
 const pending = runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 'pickup', callId: 'pickup', tool: { kind: 'action', actionType: 'pick_up_item', arguments: { targetSelector: '00000000-0000-4000-8000-000000000001' } } }, record());
 await Promise.resolve();
 runtime.onActionResult(record(), { actionId: sent[0][2].actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'ITEM_PICKED_UP' });
 const result = await pending;
 assert.equal(samples, 1);
 assert.equal(result.postAction.freshness.fresh, true);
 assert.equal(result.postAction.observation.inventory.items[0].count, 1);
 assert.deepEqual(result.postAction.observation.entities, []);
});

for (const state of ['SUCCEEDED', 'FAILED']) {
 test(`post-action refresh failure preserves authoritative ${state}`, async () => {
  const sent = [];
  const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, requestObservation: async () => { throw Object.assign(new Error('offline'), { code: 'OBSERVATION_UNAVAILABLE' }); } });
  const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'pick_up_item', arguments: { targetSelector: 'drop' } }), record());
  await new Promise(resolve => setImmediate(resolve));
  runtime.onActionResult(record(), { actionId: sent[0][2].actionId, goalRevision: 3, state, reasonCode: state === 'SUCCEEDED' ? 'ITEM_PICKED_UP' : 'ITEM_NOT_FOUND' });
  const result = await pending;
  assert.equal(result.state, state);
  assert.equal(result.postAction.freshness.fresh, false);
  assert.equal(result.postAction.reasonCode, 'OBSERVATION_UNAVAILABLE');
 });
}

test('post-action feedback cannot repopulate a disposed goal', async () => {
 const sent = [];
 let sampled;
 const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, requestObservation: () => new Promise(resolve => { sampled = resolve; }) });
 const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'pick_up_item', arguments: { targetSelector: 'drop' } }), record());
 await new Promise(resolve => setImmediate(resolve));
 runtime.onActionResult(record(), { actionId: sent[0][2].actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'ITEM_PICKED_UP' });
 await new Promise(resolve => setImmediate(resolve));
 await runtime.dispose('agent-a', 'goal_stopped');
 sampled({ eventSequence: 1, observation: { inventory: { items: [] } } });
 const result = await pending;
 assert.equal(result.state, 'SUCCEEDED');
 assert.equal(result.postAction.reasonCode, 'STALE_NATIVE_TOOL');
 assert.equal(result.postAction.freshness.fresh, false);
 assert.equal(runtime.hasCurrent(record()), false);
});

for (const terminalState of ['SUCCEEDED', 'FAILED']) {
	test(`native sequence samples once after its last attempted action: ${terminalState}`, async () => {
		const sent = [];
		let samples = 0;
		let runtime;
		runtime = new NativeToolRuntime({ bridge: { send: async (type, agentId, payload) => {
			if (type !== 'action_command') return;
			sent.push(payload);
			queueMicrotask(() => runtime.onActionResult(record(), { actionId: payload.actionId, state: sent.length === 2 ? terminalState : 'SUCCEEDED', reasonCode: sent.length === 2 && terminalState === 'FAILED' ? 'TARGET_OBSTRUCTED' : 'BLOCK_BROKEN' }));
		} }, requestObservation: async (_record, { afterEventSequence }) => {
			samples++;
			assert.equal(sent.length, 2, 'intermediate sampling cannot guide an already-authored sequence');
			return { eventSequence: afterEventSequence + 1, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: sent.length }] } } };
		} });
		const result = await runtime.execute(nativeCall({ kind: 'sequence', actions: [0, 1, 2].map(x => ({ actionType: 'break_block', arguments: { x, y: 64, z: 0, expectedBlockId: 'minecraft:oak_log', timeoutMs: 1000 } })).slice(0, terminalState === 'FAILED' ? 3 : 2) }), record());
		assert.equal(result.state, terminalState);
		assert.equal(result.completed, 2);
		assert.equal(sent.length, 2);
		assert.equal(samples, 1);
		assert.equal(result.postAction.freshness.fresh, true);
		assert.equal(result.postAction.observation.inventory.items[0].count, 2);
		assert.equal(result.results.some(step => step.postAction !== undefined), false);
	});
}

test('native program samples action effects once before the authored continuation', async () => {
	let samples = 0;
	let runtime;
	const observation = { player: { health: 20 }, inventory: { items: [] } };
	runtime = new NativeToolRuntime({ bridge: { send: async (type, agentId, payload) => {
		if (type === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: payload.actionId, state: 'SUCCEEDED', reasonCode: 'ARRIVED' }));
	} }, requestObservation: async (_record, { afterEventSequence }) => {
		samples++;
		return { eventSequence: afterEventSequence + 1, observation };
	} });
	runtime.updateObservation(record(), observation, { eventSequence: 1 });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.navigateTo({ x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1000 }); program.finish("Arrived");' }), record());
	assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
	assert.equal(result.actions, 1);
	assert.equal(samples, 1);
});

test('program continuations keep fresh facts without rereading model-facing metadata', async () => {
	let samples = 0;
	let settingsReads = 0;
	let unresolvedReads = 0;
	let runtime;
	const observation = { world: { worldId: 'metadata-test' }, player: { health: 20 }, inventory: { items: [] } };
	runtime = new NativeToolRuntime({
		bridge: { send: async (type, _agentId, payload) => {
			if (type === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), {
				actionId: payload.actionId, state: 'SUCCEEDED', reasonCode: 'WAIT_COMPLETED',
			}));
		} },
		requestObservation: async (_record, { afterEventSequence }) => {
			samples++;
			return { eventSequence: afterEventSequence + 1, observation };
		},
		executionSettings: async () => { settingsReads++; return { effective: { model: 'test' } }; },
		notebook: {
			writeNote: async () => {},
			query: async () => ({ entries: [] }),
			recordReceipt: async () => {},
			listUnresolved: async () => { unresolvedReads++; return { total: 0, entries: [], nextOffset: null }; },
		},
	});
	runtime.updateObservation(record(), observation, { eventSequence: 1 });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);' }), record());
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 2);
	assert.equal(samples, 2, 'each authored step still receives a fresh server sample');
	assert.equal(settingsReads, 0);
	assert.equal(unresolvedReads, 0);
	const publicFacts = await runtime.execute(nativeCall({ kind: 'observe', callId: 'public-observe' }), record());
	assert.equal(publicFacts.freshness.fresh, true);
	assert.equal(publicFacts.executionSettings.effective.model, 'test');
	assert.equal(publicFacts.unresolvedActions.total, 0);
	assert.equal(settingsReads, 1);
	assert.equal(unresolvedReads, 1);
	await runtime.disposeAll();
});

test('fresh observation is fenced after asynchronous metadata finishes', async () => {
	let releaseMetadata;
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} },
		requestObservation: async () => ({ eventSequence: 1, observation: { inventory: { items: [] } } }),
		executionSettings: () => new Promise(resolve => { releaseMetadata = resolve; }),
	});
	const pending = runtime.execute(nativeCall({ kind: 'observe' }), record());
	await new Promise(resolve => setImmediate(resolve));
	await runtime.dispose('agent-a', 'goal_stopped');
	releaseMetadata({});
	await assert.rejects(pending, { code: 'STALE_NATIVE_TOOL' });
});

// A server-shaped runtime: each request stays pending until the test answers it,
// like an inspection that the server serves on its next tick.
function postResultRuntime({ registry = null, ...options } = {}) {
	const sent = [];
	const requests = [];
	const runtime = new NativeToolRuntime({
		...options,
		registry,
		bridge: { send: async (...args) => { sent.push(args); } },
		requestObservation: (_record, { afterEventSequence }) => new Promise((resolve, reject) => { requests.push({ afterEventSequence, resolve, reject }); }),
	});
	return { runtime, sent, requests };
}

const turn = () => new Promise((resolve) => setImmediate(resolve));

test('lookAround continues on the server post-result publication without waiting a tick for its request', async () => {
	const { runtime, sent, requests } = postResultRuntime();
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 2, ticksPerStep: 2 }), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'CONTROL_SEGMENT_COMPLETED' });
	await turn();
	assert.equal(requests.length, 1, 'the explicit request remains the guarantee');
	runtime.updateObservation(record(), { observedAtEpochMs: 20, entities: [{ uuid: 'pushed-1', type: 'minecraft:pig' }] }, { eventSequence: 2 });
	await turn();
	assert.equal(sent.length, 2, 'the next authored heading starts before the request is answered');
	assert.equal(sent[1][2].arguments.yaw, 0);
	// The superseded request still stores its newer sample under the same checks.
	requests[0].resolve({ eventSequence: 3, observation: { observedAtEpochMs: 30, entities: [{ uuid: 'requested-1', type: 'minecraft:cow' }] } });
	await turn();
	runtime.onActionResult(record(), { actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: 'CONTROL_SEGMENT_COMPLETED' });
	runtime.updateObservation(record(), { observedAtEpochMs: 40, entities: [{ uuid: 'pushed-2', type: 'minecraft:pig' }] }, { eventSequence: 4 });
	const result = await pending;
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(requests.length, 1, 'a publication that arrived before sampling needs no request');
	assert.deepEqual(result.samples.map((sample) => [sample.yaw, sample.eventSequence, sample.entities[0].uuid]), [[180, 2, 'pushed-1'], [0, 4, 'pushed-2']]);
});

test('only a publication after the result satisfies the post-action fence', async () => {
	const { runtime, sent, requests } = postResultRuntime();
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 5 });
	const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1000 } }), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	assert.equal(runtime.updateObservation(record(), { stale: true }, { eventSequence: 5 }), false, 'a repeated sequence is not a new sample');
	assert.equal(runtime.updateObservation(record({ goalRevision: 2 }), { stale: true }, { eventSequence: 9 }), true);
	await turn();
	let settled = false;
	void pending.then(() => { settled = true; });
	await turn();
	assert.equal(settled, false, 'older sequences and other goals do not prove post-action facts');
	runtime.updateObservation(record(), { pushed: true }, { eventSequence: 6 });
	const result = await pending;
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.freshness.afterEventSequence, 5, 'freshness is fenced at the action result');
	assert.equal(result.postAction.eventSequence, 6);
	assert.equal(result.postAction.observation.pushed, true);
	requests[0].reject(Object.assign(new Error('late'), { code: 'INSPECTION_TIMEOUT' }));
	await turn();
});

test('a post-result request failure still fails the sample when no publication arrives', async () => {
	const { runtime, sent, requests } = postResultRuntime();
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 2, ticksPerStep: 1 }), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'CONTROL_SEGMENT_COMPLETED' });
	await turn();
	requests[0].reject(Object.assign(new Error('timed out'), { code: 'INSPECTION_TIMEOUT' }));
	await assert.rejects(pending, { code: 'INSPECTION_TIMEOUT' });
	assert.equal(sent.length, 1, 'no heading is authored past an unobserved result');
});

test('a queued post-result publication survives an inspection failure', async () => {
	let runtime;
	const sent = [];
	runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => { sent.push(args); } },
		requestObservation: () => {
			setImmediate(() => runtime.updateObservation(record(), { pushed: true }, { eventSequence: 2 }));
			return Promise.reject(Object.assign(new Error('inspection unavailable'), { code: 'INSPECTION_UNAVAILABLE' }));
		},
	});
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1000 } }), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });

	const result = await pending;
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.eventSequence, 2);
	assert.equal(result.postAction.observation.pushed, true);
});

test('a superseded post-result request cannot repopulate a disposed goal', async () => {
	let current = record();
	const { runtime, sent, requests } = postResultRuntime({ registry: { get: () => current } });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'action', actionType: 'break_block', arguments: { x: 1, y: 64, z: 0 } }), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN' });
	await turn();
	runtime.updateObservation(record(), { pushed: true }, { eventSequence: 2 });
	assert.equal((await pending).postAction.eventSequence, 2);
	current = record({ goalRevision: 4 });
	await runtime.dispose('agent-a', 'goal_steered');
	requests[0].resolve({ eventSequence: 3, observation: { late: true } });
	await turn();
	assert.equal(runtime.hasCurrent(record()), false, 'the late answer is dropped by its lifecycle checks');
});

test('program continuations use post-result publications that arrive before sampling', async () => {
	const { runtime, sent, requests } = postResultRuntime();
	const observation = { player: { health: 20 }, inventory: { items: [] } };
	runtime.updateObservation(record(), observation, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);' }), record());
	for (const [index, sequence] of [2, 3].entries()) {
		while (sent.length <= index) await turn();
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: 'WAIT_COMPLETED' });
		runtime.updateObservation(record(), observation, { eventSequence: sequence });
	}
	const result = await pending;
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 2);
	assert.equal(requests.length, 0, 'both continuations used publications that arrived before sampling');
});

const navigate = { kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1000 } };
// The deadline only bounds a failure; a passing test never waits for it.
async function eventually(predicate, timeoutMs = 20_000) {
	const started = Date.now();
	while (!predicate()) {
		if (Date.now() - started > timeoutMs) throw new Error('condition was not reached');
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
}

// Runs one navigate_to whose result is followed by the server's publication, as the Java bridge does in the same tick.
async function publishedAction(runtime, sent, sequence) {
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	runtime.updateObservation(record(), { pushed: sequence }, { eventSequence: sequence });
	return pending;
}

test('once publications follow results, the post-result request waits for the publication instead of costing a server sample', async () => {
	// The grace never expires here: the publication below ends the wait, so a long grace costs nothing and cannot be outrun by a busy machine.
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 60_000 });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	// Until two results in a row were published, the request still goes out at once.
	await publishedAction(runtime, sent, 2);
	await publishedAction(runtime, sent, 3);
	assert.equal(requests.length, 2, 'unproven publications are raced against an immediate request');
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	await turn();
	assert.equal(requests.length, 2, 'the request is held back while the publication can still arrive');
	runtime.updateObservation(record(), { pushed: 4 }, { eventSequence: 4 });
	const result = await pending;
	assert.equal(requests.length, 2, 'a publication inside the grace period needs no request');
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.eventSequence, 4);
});

test('a missing publication still gets its request once the grace period ends, and stops the waiting', async () => {
	const traces = [];
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 40, trace: (event, fields) => traces.push({ event, ...fields }) });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	await publishedAction(runtime, sent, 2);
	await publishedAction(runtime, sent, 3);
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	assert.equal(requests.length, 2);
	await eventually(() => requests.length === 3);
	assert.equal(requests.length, 3, 'the explicit request remains the guarantee');
	assert.equal(requests[2].afterEventSequence, 3, 'it is fenced at the result, like the immediate request');
	requests[2].resolve({ eventSequence: 9, observation: { requested: true } });
	const result = await pending;
	assert.equal(result.postAction.eventSequence, 9);
	assert.equal(result.postAction.observation.requested, true);
	assert.ok(traces.some(({ event, waitedMs }) => event === 'native_post_result_publication_missed' && waitedMs >= 30));
	// The miss ends the waiting: the next result requests at once again.
	const next = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	assert.equal(requests.length, 4);
	runtime.updateObservation(record(), { pushed: 10 }, { eventSequence: 10 });
	await next;
});

test('a server that never publishes after results is asked at once every time', async () => {
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 500 });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	for (let index = 0; index < 4; index += 1) {
		const pending = runtime.execute(nativeCall(navigate), record());
		await turn();
		runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
		await turn();
		assert.equal(requests.length, index + 1, 'no publication was ever seen, so nothing is waited for');
		requests[index].resolve({ eventSequence: 10 + index, observation: { requested: index } });
		assert.equal((await pending).postAction.eventSequence, 10 + index);
	}
});

test('a lifecycle that ends during the publication grace period sends no request', async (t) => {
	t.mock.timers.enable({ apis: ['setTimeout'] });
	let current = record();
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 40, registry: { get: () => current } });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	await publishedAction(runtime, sent, 2);
	await publishedAction(runtime, sent, 3);
	const pending = runtime.execute(nativeCall(navigate), record()).catch((error) => error);
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	current = record({ goalRevision: 4 });
	await runtime.dispose('agent-a', 'goal_steered');
	t.mock.timers.tick(40);
	const result = await pending;
	assert.equal(requests.length, 2, 'the disposed goal does not ask the server for another sample');
	assert.equal(result.postAction.freshness.fresh, false, 'the action result stays authoritative without fresh facts');
});

test('a forced local death notice does not satisfy the post-result barrier', async () => {
	const { runtime, sent, requests } = postResultRuntime();
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 5 });
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	// The coordinator's synthetic death notice reuses the last live sequence, so it is no newer than the result.
	assert.equal(runtime.updateObservation(record(), { death: { cause: 'minecraft:lava' } }, { eventSequence: 5, force: true }), true);
	let settled = false;
	void pending.then(() => { settled = true; });
	await turn();
	await turn();
	assert.equal(settled, false, 'a stored update that does not advance the sequence proves nothing about the world after the result');
	requests[0].resolve({ eventSequence: 6, observation: { requested: true } });
	const result = await pending;
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.eventSequence, 6, 'freshness comes from a strictly newer authoritative sample');
	assert.equal(result.postAction.observation.requested, true);
});

test('a death notice during the publication grace period neither ends the wait nor counts as a publication', async (t) => {
	t.mock.timers.enable({ apis: ['setTimeout'] });
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 150 });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	await publishedAction(runtime, sent, 2);
	await publishedAction(runtime, sent, 3);
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	runtime.updateObservation(record(), { death: { cause: 'minecraft:lava' } }, { eventSequence: 3, force: true });
	let settled = false;
	void pending.then(() => { settled = true; });
	await turn();
	assert.equal(settled, false);
	assert.equal(requests.length, 2, 'the grace period is still running');
	t.mock.timers.tick(150);
	for (let round = 0; round < 20 && requests.length < 3; round += 1) await turn();
	assert.equal(requests.length, 3, 'with no real publication the request goes out when the grace period ends');
	requests[2].resolve({ eventSequence: 9, observation: { requested: true } });
	const result = await pending;
	assert.equal(result.postAction.eventSequence, 9);
	assert.equal(result.postAction.observation.requested, true);
	// The death notice was not a publication, so the missed one reset the streak: the next result requests at once.
	const next = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	assert.equal(requests.length, 4);
	runtime.updateObservation(record(), { pushed: 10 }, { eventSequence: 10 });
	await next;
});

// Every consumer of the shared raw sample must treat it as read only. Freezing it where the runtime hands it
// over makes any in-place write throw instead of silently corrupting the next reader.
function deepFreeze(value) {
	if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
		Object.freeze(value);
		for (const key of Reflect.ownKeys(value)) deepFreeze(value[key]);
	}
	return value;
}

test('no consumer mutates the stored raw sample', async () => {
	const frozen = [];
	const { runtime, sent, requests } = postResultRuntime({
		memoryObservation: (_record, observation, options) => { assert.equal(options.owned, true); frozen.push(deepFreeze(observation)); },
	});
	const sample = (sequence) => ({ ready: true, player: { health: 20, x: 0, y: 64, z: 0 }, inventory: { items: [{ itemId: 'minecraft:stone', count: sequence }] }, entities: [{ uuid: `e-${sequence}`, type: 'minecraft:pig' }], observedAtEpochMs: sequence });
	runtime.updateObservation(record(), sample(1), { eventSequence: 1 });
	assert.ok(runtime.decorateObservation(record()));
	assert.ok(runtime.snapshotLive('agent-a'));
	const observing = runtime.execute(nativeCall({ kind: 'observe' }), record());
	while (requests.length === 0) await turn();
	requests[0].resolve({ eventSequence: 2, observation: sample(2) });
	assert.equal((await observing).freshness.fresh, true);
	const action = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	await turn();
	runtime.updateObservation(record(), sample(3), { eventSequence: 3 });
	assert.equal((await action).postAction.freshness.fresh, true);
	// Death merging reads the last live sample.
	runtime.updateObservation(record(), { death: { cause: 'minecraft:lava' } }, { eventSequence: 3, force: true });
	assert.ok(runtime.decorateObservation(record(), { death: { cause: 'minecraft:lava' } }));
	assert.ok(frozen.length >= 4);
	assert.ok(frozen.every(Object.isFrozen));
});

test('program continuations read the stored raw sample without mutating it', async () => {
	const { runtime, sent } = postResultRuntime({ memoryObservation: (_record, observation) => { deepFreeze(observation); } });
	const observation = (sequence) => ({ player: { health: 20 }, inventory: { items: [] }, observedAtEpochMs: sequence });
	runtime.updateObservation(record(), observation(1), { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);' }), record());
	for (const [index, sequence] of [2, 3].entries()) {
		while (sent.length <= index) await turn();
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: 'WAIT_COMPLETED' });
		runtime.updateObservation(record(), observation(sequence), { eventSequence: sequence });
	}
	const result = await pending;
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 2);
});

test('a publication read after the grace timer by a busy event loop is not counted as missed', async () => {
	const traces = [];
	const { runtime, sent, requests } = postResultRuntime({ publicationGraceMs: 40, trace: (event) => traces.push(event) });
	runtime.updateObservation(record(), { entities: [] }, { eventSequence: 1 });
	await publishedAction(runtime, sent, 2);
	await publishedAction(runtime, sent, 3);
	const pending = runtime.execute(nativeCall(navigate), record());
	await turn();
	runtime.onActionResult(record(), { actionId: sent.at(-1)[2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' });
	// The loop stalls past the grace period; the socket's publication is only handled after the expired timer.
	setTimeout(() => {
		const until = performance.now() + 60;
		while (performance.now() < until);
		setImmediate(() => runtime.updateObservation(record(), { pushed: 4 }, { eventSequence: 4 }));
	}, 15);
	const result = await pending;
	assert.equal(requests.length, 2, 'the late-read publication needs no request');
	assert.equal(result.postAction.eventSequence, 4);
	assert.equal(traces.includes('native_post_result_publication_missed'), false);
});
