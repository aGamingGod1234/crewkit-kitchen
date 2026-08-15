import assert from 'node:assert/strict';
import test from 'node:test';

import { createFactView } from '../src/arena-script/facts.mjs';

function observation(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20 },
		items: [],
		entities: [],
		blocks: [],
		inventory: { items: [], tagCounts: { '#minecraft:logs': 0 } },
		...overrides,
	};
}

test('nearest considers only the candidate set selected by model code', () => {
	const facts = createFactView(observation({
		items: [
			{ stableId: 'log-far', itemId: 'minecraft:oak_log', count: 2, x: 8, y: 64, z: 0, reachable: true },
			{ stableId: 'dirt-near', itemId: 'minecraft:dirt', count: 1, x: 1, y: 64, z: 0, reachable: true },
		],
	}));
	const logs = facts.world.items({ itemId: 'minecraft:oak_log', reachable: true });
	assert.equal(facts.world.nearest(logs).stableId, 'log-far');
});

test('factual views are immutable and nearest breaks distance ties by stable id', () => {
	const facts = createFactView(observation({
		items: [
			{ stableId: 'z', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 },
			{ stableId: 'a', itemId: 'minecraft:oak_log', count: 1, x: -1, y: 64, z: 0 },
		],
	}));
	assert.throws(() => { facts.player.health = 1; }, TypeError);
	assert.equal(facts.world.nearest(facts.world.items({ itemId: 'minecraft:oak_log' })).stableId, 'a');
});

test('inventory helpers measure only observed inventory and tag facts', () => {
	const facts = createFactView(observation({
		inventory: {
			items: [{ itemId: 'minecraft:oak_log', count: 5 }, { itemId: 'minecraft:dirt', count: 2 }],
			tagCounts: { '#minecraft:logs': 5 },
		},
	}));
	assert.equal(facts.inventory.count('minecraft:oak_log'), 5);
	assert.equal(facts.inventory.countTag('#minecraft:logs'), 5);
	assert.equal(facts.inventory.countTag('#minecraft:unknown'), 0);
});
