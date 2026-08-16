import assert from 'node:assert/strict';
import test from 'node:test';

import { PROTOCOL_VERSION } from '../src/constants.mjs';
import {
	createActionCommand,
	validateAction,
	validateActionCommandPayload,
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
		{ type: 'set_door', x: 1, y: 64, z: 2, open: true },
		{ type: 'drop_item', slot: 0, count: 1 },
		{ type: 'navigate_to', x: 10, y: 64, z: -5, tolerance: 1.25, sprint: true, timeoutMs: 30_000 },
		{
			type: 'transfer_container', x: 1, y: 64, z: -2,
			sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
			count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
		},
		{ type: 'craft_inventory', recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 5_000 },
		{ type: 'craft_table', recipeId: 'minecraft:crafting_table', x: 2, y: 64, z: 3, count: 1, timeoutMs: 5_000 },
		{ type: 'furnace_transaction', x: 2, y: 64, z: 3, operation: 'insert_input', inventorySlot: 5, count: 1, expectedItemId: 'minecraft:raw_iron', timeoutMs: 5_000 },
		{ type: 'equip_item', sourceSlot: 5, targetSlot: 'chest', expectedItemId: 'minecraft:iron_chestplate' },
		{ type: 'select_tool', sourceSlot: 5, hotbarSlot: 1, expectedItemId: 'minecraft:iron_pickaxe', minRemainingDurability: 32 },
		{ type: 'block_with_shield', durationMs: 750 },
		{ type: 'use_ranged', targetSelector: 'nearest_hostile', drawDurationMs: 1_000, timeoutMs: 5_000 },
	];
	for (const action of actions) assert.deepEqual(validateAction(action), action);
});

test('rejects unknown fields, unsupported actions, and unsafe numeric/text values', () => {
	assert.throws(() => validateAction({ type: 'wait', durationMs: 1, surprise: true }), /Unknown field/);
	assert.throws(() => validateAction({ type: 'teleport', x: 0 }), /Unsupported action/);
	assert.throws(() => validateAction({ type: 'look_at', x: Infinity, y: 0, z: 0 }), /finite/);
	assert.throws(() => validateAction({ type: 'break_block', x: 1.1, y: 0, z: 0, timeoutMs: 1 }), /32-bit integer/);
	assert.throws(() => validateAction({ type: 'wait', durationMs: 0 }), /between 1 and 600000/);
	assert.throws(() => validateAction({ type: 'drop_item', slot: 36, count: 1 }), /between 0 and 35/);
	assert.throws(() => validateAction({ type: 'chat', message: 'x'.repeat(257) }), /at most 256/);
	const validTransfer = {
		type: 'transfer_container', x: 1, y: 64, z: -2,
		sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
		count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
	};
	assert.deepEqual(validateAction(validTransfer), validTransfer);
	assert.throws(() => validateAction({ ...validTransfer, count: 0 }), /count/);
	assert.throws(() => validateAction({ ...validTransfer, extra: true }), /Unknown/);
	assert.throws(() => validateAction({ type: 'equip_item', sourceSlot: 5, targetSlot: 'mainhand', expectedItemId: 'minecraft:iron_chestplate' }), /targetSlot/);
	for (const type of ['build_sequence', 'pick_up_item', 'fight_target', 'flee_from', 'follow_entity', 'complete_goal']) {
		assert.throws(() => validateAction({ type }), /Unsupported action/);
	}
});

test('requires detached complete model-program provenance for action commands', () => {
	const action = { type: 'wait', durationMs: 25 };
	const provenance = {
		provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
		programId: 'program-1-1', programVersion: 1, sourceStepId: 'step-80-126', eventSequence: 4,
	};
	const command = { goalRevision: 1, actionId: 'action-1', action, provenance };
	assert.deepEqual(validateActionCommandPayload(command), command);
	const validated = validateActionCommandPayload(command);
	assert.throws(() => { validated.provenance.programId = 'forged'; }, TypeError);
	assert.equal(command.provenance.programId, 'program-1-1');
	assert.throws(
		() => validateActionCommandPayload({ goalRevision: 1, actionId: 'action-1', action }),
		/provenance/,
	);
	assert.throws(() => validateActionCommandPayload({ ...command, goalStatus: 'in_progress' }), /Unknown field/);
	assert.throws(() => validateActionCommandPayload({ ...command, actions: [action] }), /Unknown field/);
	for (const field of ['provider', 'model', 'reasoningEffort', 'serviceTier', 'programId', 'sourceStepId']) {
		assert.throws(() => validateActionCommandPayload({ ...command, provenance: { ...provenance, [field]: '\u00a0' } }), new RegExp(`provenance\\.${field}`));
	}
	for (const field of ['programVersion', 'eventSequence']) {
		assert.throws(() => validateActionCommandPayload({ ...command, provenance: { ...provenance, [field]: -1 } }), new RegExp(`provenance\\.${field}`));
		assert.throws(() => validateActionCommandPayload({ ...command, provenance: { ...provenance, [field]: Number.MAX_SAFE_INTEGER + 1 } }), new RegExp(`provenance\\.${field}`));
	}
});

test('rejects ordinary and non-breaking whitespace-only required action text', () => {
	const validTransfer = {
		type: 'transfer_container', x: 1, y: 64, z: -2,
		sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
		count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
	};
	const validCraft = { type: 'craft_inventory', recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 5_000 };
	const validRanged = { type: 'use_ranged', targetSelector: 'nearest_hostile', drawDurationMs: 1_000, timeoutMs: 5_000 };
	for (const whitespace of [' \t\r\n', '\u00a0']) {
		assert.throws(() => validateAction({ ...validTransfer, expectedItemId: whitespace }), /expectedItemId.*blank/);
		assert.throws(() => validateAction({ ...validCraft, recipeId: whitespace }), /recipeId.*blank/);
		assert.throws(() => validateAction({ ...validRanged, targetSelector: whitespace }), /targetSelector.*blank/);
	}
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
