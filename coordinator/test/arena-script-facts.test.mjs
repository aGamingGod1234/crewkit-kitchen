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

test('rejects hostile observation records and synthetic nearest candidate arrays', () => {
	let read = 0;
	const accessor = Object.defineProperties({}, {
		stableId: { enumerable: true, value: 'bad' }, itemId: { enumerable: true, value: 'minecraft:oak_log' }, count: { enumerable: true, value: 1 },
		x: { enumerable: true, get() { read += 1; return 1; } }, y: { enumerable: true, value: 64 }, z: { enumerable: true, value: 0 },
	});
	assert.throws(() => createFactView(observation({ items: [accessor] })), TypeError);
	assert.equal(read, 0);
	const facts = createFactView(observation({ items: [{ stableId: 'item', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 }] }));
	assert.throws(() => facts.world.nearest([{ stableId: 'invented', x: 0, y: 0, z: 0 }]), TypeError);
});

test('omits candidates with invalid coordinates rather than assigning a synthetic origin', () => {
	const facts = createFactView(observation({
		items: [{ stableId: 'bad-coordinate', itemId: 'minecraft:oak_log', count: 1, x: Number.NaN, y: 64, z: 0 }],
	}));
	assert.equal(facts.world.items({ itemId: 'minecraft:oak_log' }).length, 0);
});
