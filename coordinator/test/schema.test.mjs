import assert from 'node:assert/strict';
import test from 'node:test';

import { PROTOCOL_VERSION } from '../src/constants.mjs';
import {
	createActionCommand,
	validateAction,
	validateActionResult,
	validateEnvelope,
	validateObservation,
} from '../src/schema.mjs';

const readyObservation = () => ({
	protocolVersion: PROTOCOL_VERSION,
	agentId: 'agent-55',
	type: 'observation',
	messageId: 'server-1',
	ready: true,
	status: 'ready',
	position: { x: 1, y: 64, z: -2 },
	velocity: { x: 0, y: 0, z: 0 },
	view: { yaw: 90, pitch: 0 },
	player: { health: 20, maxHealth: 20, hunger: 20, armor: 0, effects: [] },
	inventory: { selectedSlot: 0, selectedItemId: 'minecraft:air', selectedItemCount: 0, items: [] },
	entities: [],
	blocks: [],
	world: { dimensionId: 'minecraft:overworld', gameTime: 1, defaultClockTime: 1, raining: false, thundering: false },
	currentAction: { present: false, commandId: '', type: '', state: '' },
	lastResult: { present: false, commandId: '', state: '', reasonCode: '', message: '', completedAtEpochMs: 0 },
});

test('accepts every exact action shape and returns a detached value', () => {
	const actions = [
		{ type: 'move_to', x: 1.25, y: 64, z: -2, tolerance: 0.5, sprint: true },
		{ type: 'look_at', x: 1, y: 2, z: 3 },
		{ type: 'attack', targetSelector: 'nearest hostile', timeoutMs: 5_000 },
		{ type: 'select_item', itemId: 'minecraft:diamond_sword' },
		{ type: 'use_item', durationMs: 250 },
		{ type: 'break_block', x: 1, y: 64, z: 2, timeoutMs: 5_000 },
		{ type: 'place_block', x: 1, y: 64, z: 2, face: 'up', itemId: 'minecraft:stone' },
		{ type: 'chat', message: 'Ready.' },
		{ type: 'wait', durationMs: 50 },
		{ type: 'complete_goal', summary: 'Done.' },
	];
	for (const action of actions) assert.deepEqual(validateAction(action), action);
});

test('rejects unknown fields, unsupported actions, and unsafe numeric/text values', () => {
	assert.throws(() => validateAction({ type: 'wait', durationMs: 1, surprise: true }), /Unknown field/);
	assert.throws(() => validateAction({ type: 'teleport', x: 0 }), /Unsupported action/);
	assert.throws(() => validateAction({ type: 'look_at', x: Infinity, y: 0, z: 0 }), /finite/);
	assert.throws(() => validateAction({ type: 'break_block', x: 1.1, y: 0, z: 0, timeoutMs: 1 }), /32-bit integer/);
	assert.throws(() => validateAction({ type: 'wait', durationMs: 0 }), /between 1 and 600000/);
	assert.throws(() => validateAction({ type: 'chat', message: 'x'.repeat(257) }), /at most 256/);
});

test('builds a strict versioned command with integral timestamp', () => {
	assert.deepEqual(createActionCommand(
		{ type: 'wait', durationMs: 25 },
		{ commandId: 'command-1', issuedAtEpochMs: 1_750_000_000_000 },
	), {
		protocolVersion: 1,
		commandId: 'command-1',
		type: 'wait',
		issuedAtEpochMs: 1_750_000_000_000,
		durationMs: 25,
	});
});

test('validates strict envelopes and protocol version', () => {
	assert.deepEqual(validateEnvelope({ protocolVersion: 1, agentId: 'agent-55', type: 'hello_ack', messageId: 'server-1', replyTo: 'client-1' }), {
		protocolVersion: 1, agentId: 'agent-55', type: 'hello_ack', messageId: 'server-1', replyTo: 'client-1',
	});
	assert.throws(() => validateEnvelope({ protocolVersion: 2, agentId: 'agent-55', type: 'hello_ack', messageId: 'x', replyTo: 'y' }), /Unsupported protocolVersion/);
});

test('validates bounded observations with strict nested fields', () => {
	assert.deepEqual(validateObservation(readyObservation()), readyObservation());
	const unknown = readyObservation();
	unknown.position.dimension = 'oops';
	assert.throws(() => validateObservation(unknown), /Unknown field 'position.dimension'/);
	const tooMany = readyObservation();
	tooMany.entities = Array.from({ length: 65 }, (_, index) => ({ stableId: String(index), typeId: 'minecraft:pig', name: '', x: 0, y: 0, z: 0, distanceSquared: 0, health: 10, maxHealth: 10, hostile: false }));
	assert.throws(() => validateObservation(tooMany), /at most 64/);
});

test('accepts terminal action results and rejects nonterminal states', () => {
	const result = { protocolVersion: 1, agentId: 'agent-55', type: 'action_result', messageId: 'server-2', commandId: 'command-1', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', completedAtEpochMs: 1_750_000_001_000 };
	assert.deepEqual(validateActionResult(result), result);
	assert.throws(() => validateActionResult({ ...result, state: 'RUNNING' }), /terminal/);
});
