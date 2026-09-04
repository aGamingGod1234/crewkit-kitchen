import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { constrainGoalBoundNavigation, NativeToolRuntime } from '../src/native-tool-runtime.mjs';

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
		results: [{ actionType: 'navigate_to', state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true }],
	});
	assert.equal(sent.length, 1);
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
