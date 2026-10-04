import assert from 'node:assert/strict';
import test from 'node:test';

import { buildNativeEventInput } from '../src/dynamic-main.mjs';

const record = {
	currentGoal: 'Beat Minecraft',
	currentGoalSpec: { originalRequest: 'Beat Minecraft' },
	goalRevision: 4,
};

function payloadOf(input) {
	return JSON.parse(input.slice(input.indexOf('\n') + 1));
}

test('native death input keeps last live inventory, recovery, and empty current items', () => {
	const input = buildNativeEventInput(record, {
		event: 'player_death',
		trigger: 'player_death',
		observation: {
			death: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
			player: { dead: true, health: 0, x: 12, y: 64, z: -8 },
			inventory: { items: [] },
			continuity: { sameGoal: true, phase: 'dead' },
			lastLiveInventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
			recovery: {
				lastDeath: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Current inventory is empty. Lost on death: minecraft:stone_pickaxe.',
			},
			failureClass: 'recover',
			options: [{ id: 'recover_corpse', feasible: true, moveTo: { x: 12, y: 64, z: -8 } }],
			world: { dimension: 'minecraft:overworld' },
		},
	});
	assert.match(input, /advance the current goal using fresh facts/i);
	assert.match(input, /"phase":"dead"/);
	assert.match(input, /lastLostInventory/);
	assert.match(input, /minecraft:stone_pickaxe/);
	assert.match(input, /Current inventory is empty/);
	assert.match(input, /recover_corpse/);
});

test('oversized native event input keeps death recovery instead of dropping it', () => {
	const input = buildNativeEventInput(record, {
		event: 'player_death',
		trigger: 'player_death',
		observation: {
			death: { cause: 'lava', x: 1, y: 64, z: 1, dimensionId: 'minecraft:overworld' },
			player: { dead: true, health: 0 },
			inventory: { items: Array.from({ length: 64 }, (_, index) => ({ itemId: `minecraft:filler_${index}`, count: 64 })) },
			recovery: {
				lastDeath: { cause: 'lava', x: 1, y: 64, z: 1, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Lost on death: minecraft:iron_pickaxe.',
			},
			failureClass: 'recover',
			world: { dimension: 'minecraft:overworld', extra: 'n'.repeat(20_000) },
			blocks: [...Array.from({ length: 200 }, (_, index) => ({ blockId: 'minecraft:stone', x: index, y: 64, z: 0 })), { blockId: 'minecraft:lava', x: 400, y: 64, z: 0 }],
			continuity: { sameGoal: true, phase: 'dead' },
		},
		conversation: Array.from({ length: 12 }, (_, sequence) => ({ sequence: sequence + 1, text: `event-${sequence + 1}` })),
	});
	const payload = payloadOf(input);
	assert.match(input, /lastDeath/);
	assert.match(input, /iron_pickaxe/);
	assert.match(input, /"phase":"dead"/);
	assert.ok(payload.observation.blocks.some(({ blockId }) => blockId === 'minecraft:lava'));
	assert.equal(payload.observation.resultCoverage.blocks.availableInSnapshot, 201);
	assert.equal(payload.observation.resultCoverage.blocks.omitted, 189);
	assert.equal(payload.observation.resultCoverage.inventory.omitted, 48);
	assert.deepEqual(payload.conversation.entries.map(entry => entry.sequence), Array.from({ length: 12 }, (_, index) => index + 1));
	assert.equal(payload.conversation.nextSequence, 12);
	assert.equal(payload.conversation.omittedEntries, 0, 'optional world compaction makes room for the entire unread conversation');
	assert.ok(Buffer.byteLength(input, 'utf8') <= 20_000);
});

test('planning due input carries timing and version while keeping the current routine advisory', () => {
	const input = buildNativeEventInput(record, {
		event: 'program_planning_due',
		programId: 'program-1',
		planningLeadMs: 2_000,
		status: { state: 'RUNNING', engineState: 'ACTIVE', programVersion: 7, deadlineEpochMs: 9_000 },
		observation: {
			observedAtEpochMs: 8_000,
			coverage: { complete: false, sections: { blocks: { returned: 1, total: 40, omittedByWire: 39 } } },
			player: { x: 4, y: 65, z: -2 },
			blocks: [{ blockId: 'minecraft:lava', x: 4, y: 64, z: -2 }],
			currentAction: { type: 'move_to', state: 'RUNNING' },
		},
	});
	const payload = payloadOf(input);
	assert.match(input, /prepare the next intention while the current authorised routine keeps running/i);
	assert.match(input, /does not require a pending decisionId/i);
	assert.equal(payload.event, 'program_planning_due');
	assert.equal(payload.trigger, 'program_planning_due');
	assert.deepEqual(payload.program, {
		programId: 'program-1', state: 'RUNNING', engineState: 'ACTIVE', programVersion: 7,
		deadlineEpochMs: 9_000, planningLeadMs: 2_000,
	});
	assert.equal(payload.observation.observedAtEpochMs, 8_000);
	assert.equal(payload.observation.coverage.sections.blocks.omittedByWire, 39);
	assert.equal(payload.observation.blocks[0].blockId, 'minecraft:lava');
	assert.equal(payload.observation.currentAction.state, 'RUNNING');
	assert.equal(Object.hasOwn(payload.program, 'decision'), false);
});
