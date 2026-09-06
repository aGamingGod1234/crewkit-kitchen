import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { nativeObservationSignature } from '../src/dynamic-main.mjs';
import { constrainGoalBoundNavigation, NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';

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
	assert.deepEqual(result.observation, {
		...observation,
		recovery: {
			alreadyHave: ['minecraft:stone'],
			alreadyHaveFacts: [{ kind: 'inventory', itemId: 'minecraft:stone', count: 1 }],
			doNotRedo: [],
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
	assert.deepEqual(result.observation.world, latest.world);
	assert.deepEqual(result.observation.interaction, latest.interaction);
	assert.deepEqual(result.observation.player.effects, latest.player.effects);
	assert.deepEqual(runtime.snapshotLive(current.agentId), { observation: latest, eventSequence: 2, goalRevision: 3 });
	latest.world.gameTime = 999;
	assert.equal(runtime.snapshotLive(current.agentId).observation.world.gameTime, 110, 'snapshot owns its raw facts');
});

test('lookAround turns the real player in bounded steps and preserves the observed hand and slot', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
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
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED', completed: 4,
		results: [0, 1, 2, 3].map((index) => ({ actionType: 'control', state: 'SUCCEEDED', reasonCode: '' })),
	});
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
		failureClass: 'explore',
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
		results: [{ actionType: 'navigate_to', state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true, failureClass: 'explore' }],
	});
	assert.equal(sent.length, 1);
});

test('exploreFrontier walks one occupancy hop through navigate_to', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record({ currentGoal: 'Beat Minecraft', currentGoalSpec: record().currentGoalSpec });
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0, health: 20 },
		position: { x: 0, y: 64, z: 0 },
		world: { dimension: 'minecraft:overworld' },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		blocks: [],
	}, { eventSequence: 4 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-explore', callId: 'explore-1',
		tool: { kind: 'explore_frontier', arguments: { seek: 'nether', radius: 24, timeoutMs: 15_000 } },
	}, current);
	await Promise.resolve();
	assert.equal(sent[0][0], 'action_command');
	assert.equal(sent[0][2].actionType, 'navigate_to');
	assert.equal(Number.isFinite(sent[0][2].arguments.x), true);
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED', executionStarted: true,
	});
	const result = await pending;
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.frontier.kind, 'frontier');
	assert.equal(result.frontier.seek, 'nether');
});

test('exploreFrontier reports CUE_IN_VIEW without dispatching a walk', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record({ currentGoal: 'Beat Minecraft' });
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0 },
		position: { x: 0, y: 64, z: 0 },
		world: { dimension: 'minecraft:overworld' },
		blocks: [{ blockId: 'minecraft:obsidian', x: 1, y: 64, z: 0 }],
		inventory: { items: [] },
	}, { eventSequence: 2 });
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-cue', callId: 'explore-cue',
		tool: { kind: 'explore_frontier', arguments: { seek: 'nether' } },
	}, current);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.reasonCode, 'CUE_IN_VIEW');
	assert.equal(result.frontier.kind, 'cue_in_view');
	assert.deepEqual(sent, []);
});

test('exploreFrontier without a current observation fails as NO_OBSERVATION', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-empty', callId: 'explore-empty',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any' } },
	}, record());
	assert.equal(result.state, 'FAILED');
	assert.equal(result.reasonCode, 'NO_OBSERVATION');
	assert.equal(result.failureClass, 'replan');
});

test('a blocked frontier hop is not retried on the next exploreFrontier call', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record({ currentGoal: 'Beat Minecraft' });
	const observation = {
		player: { x: 0, y: 64, z: 0 },
		position: { x: 0, y: 64, z: 0 },
		world: { dimension: 'minecraft:overworld' },
		blocks: [],
		inventory: { items: [] },
	};
	runtime.updateObservation(current, observation, { eventSequence: 1 });
	const first = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-block-1', callId: 'explore-block-1',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	const firstDestination = { ...sent[0][2].arguments };
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'FAILED', reasonCode: 'PATH_BLOCKED', executionStarted: true,
	});
	await first;
	const second = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-block-2', callId: 'explore-block-2',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	assert.equal(sent[1][2].actionType, 'navigate_to');
	assert.notDeepEqual(
		{ x: sent[1][2].arguments.x, z: sent[1][2].arguments.z },
		{ x: firstDestination.x, z: firstDestination.z },
	);
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED',
	});
	await second;
});

test('a non-path frontier failure does not poison the destination cell', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record({ currentGoal: 'Beat Minecraft' });
	const observation = {
		player: { x: 0, y: 64, z: 0 },
		position: { x: 0, y: 64, z: 0 },
		world: { dimension: 'minecraft:overworld' },
		blocks: [],
		inventory: { items: [] },
	};
	runtime.updateObservation(current, observation, { eventSequence: 1 });
	const first = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-unloaded-1', callId: 'explore-unloaded-1',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	const firstDestination = { x: sent[0][2].arguments.x, z: sent[0][2].arguments.z };
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_LOADED', executionStarted: true,
	});
	await first;
	const second = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-unloaded-2', callId: 'explore-unloaded-2',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	assert.deepEqual({ x: sent[1][2].arguments.x, z: sent[1][2].arguments.z }, firstDestination);
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED', executionStarted: true,
	});
	await second;
});

test('a timed-out frontier hop is not permanently blocked', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record({ currentGoal: 'Beat Minecraft' });
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0 },
		position: { x: 0, y: 64, z: 0 },
		world: { dimension: 'minecraft:overworld' },
		blocks: [],
		inventory: { items: [] },
	}, { eventSequence: 1 });
	const first = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-timeout-1', callId: 'explore-timeout-1',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	const firstDestination = { x: sent[0][2].arguments.x, z: sent[0][2].arguments.z };
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'FAILED', reasonCode: 'ACTION_TIMEOUT', executionStarted: true,
	});
	await first;
	const second = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-timeout-2', callId: 'explore-timeout-2',
		tool: { kind: 'explore_frontier', arguments: { seek: 'any', radius: 24 } },
	}, current);
	await Promise.resolve();
	assert.deepEqual({ x: sent[1][2].arguments.x, z: sent[1][2].arguments.z }, firstDestination);
	runtime.onActionResult(current, {
		goalRevision: 3, actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED',
	});
	await second;
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
	assert.equal(decorated.failureClass, 'recover');
	assert.equal(decorated.inventory.items.length, 0);
	assert.equal(decorated.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.equal(decorated.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
	assert.match(decorated.recovery.facts, /Current inventory is empty/);
	assert.ok(decorated.options.some((option) => option.id === 'recover_corpse'));
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
	assert.ok(decorated.recovery.doNotRedo.includes('minecraft:stone_pickaxe'));
	await runtime.dispose('agent-a', 'agent_removed');
	const forgotten = runtime.decorateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
	});
	assert.equal(forgotten.recovery?.doNotRedo?.includes('minecraft:stone_pickaxe') === true, false);
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
