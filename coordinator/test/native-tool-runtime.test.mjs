import assert from 'node:assert/strict';
import test from 'node:test';

import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

function record(overrides = {}) {
	return {
		agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh', serviceTier: 'fast',
		goalRevision: 3, currentGoal: 'get one stone', ...overrides,
	};
}

test('native body dispatches one correlated action and resolves only its matching result', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
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

test('native observe returns latest compact facts without sending a body command', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] }, { eventSequence: 4 });
	const result = await runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'observe-1', tool: { kind: 'observe' } }, record());
	assert.deepEqual(result, { eventSequence: 4, goal: 'get one stone', observation: { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] } });
	assert.deepEqual(sent, []);
});

test('native lifecycle disposal cancels an outstanding body action and rejects the tool', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record());
	await Promise.resolve();
	await runtime.dispose('agent-a', 'goal_steered');
	await assert.rejects(pending, (error) => error?.code === 'NATIVE_ACTION_CANCELLED');
	assert.equal(sent.at(-1)[0], 'action_cancel');
});

test('native finish uses the existing factual completion verifier before reporting success', async () => {
	const sent = [];
	const finished = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		onFinish: async (request) => finished.push(request),
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: {
			kind: 'finish', status: 'completed', summary: 'Stone acquired.',
			completionContract: { goalRevision: 3, predicates: [{ type: 'inventory_min', itemId: 'minecraft:stone', count: 1 }] },
		},
	}, record());
	await Promise.resolve();
	assert.equal(sent[0][0], 'goal_completed');
	assert.equal(sent[0][2].completionContract.predicates[0].itemId, 'minecraft:stone');
	assert.match(sent[0][2].contractHash, /^sha256:/);
	assert.equal(runtime.onCompletionResult(record(), {
		goalRevision: 3,
		traceId: sent[0][2].traceId,
		contractHash: sent[0][2].contractHash,
		verified: true,
		reasonCode: 'COMPLETION_VERIFIED',
	}), true);
	assert.deepEqual(await pending, { state: 'COMPLETED', verified: true, reasonCode: 'COMPLETION_VERIFIED' });
	assert.equal(finished.length, 1);
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
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, timeoutMs: 15_000 } },
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
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, timeoutMs: 15_000 } },
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
