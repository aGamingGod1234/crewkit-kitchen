import assert from 'node:assert/strict';
import test from 'node:test';

import {
	changedInterpreterFactDomains,
	createFactView,
	createInterpreterFacts,
	isTrustedInterpreterFacts,
} from '../src/arena-script/facts.mjs';
import { ALL_FACT_DOMAINS, FACT_DOMAIN } from '../src/arena-script/fact-domains.mjs';

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
			{ stableId: 'log-far', itemId: 'minecraft:oak_log', count: 2, x: 8, y: 64, z: 0, tags: ['#minecraft:logs'] },
			{ stableId: 'dirt-near', itemId: 'minecraft:dirt', count: 1, x: 1, y: 64, z: 0 },
		],
	}));
	const logs = facts.world.items({ itemId: 'minecraft:oak_log' });
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
			items: [
				{ itemId: 'minecraft:oak_log', count: 5, slot: 0, tags: ['#minecraft:logs'] },
				{ itemId: 'minecraft:dirt', count: 2, slot: 1 },
			],
			tagCounts: { '#minecraft:logs': 5 },
		},
	}));
	assert.equal(facts.inventory.count('minecraft:oak_log'), 5);
	assert.equal(facts.inventory.countTag('#minecraft:logs'), 5);
	assert.equal(facts.inventory.countTag('#minecraft:unknown'), 0);
});

test('preserves the observed attacker for model-authored damage watchers', () => {
	const facts = createFactView(observation({
		player: {
			health: 18,
			lastAttacker: { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2.5 },
		},
	}));
	assert.equal(facts.player.lastAttacker.uuid, 'mob-1');
	assert.equal(facts.player.lastAttacker.type, 'minecraft:zombie');
	assert.equal(facts.player.lastAttacker.distance, 2.5);
	assert.throws(() => { facts.player.lastAttacker.type = 'minecraft:creeper'; }, TypeError);
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

test('trusted interpreter facts share unchanged domains without exposing a forgeable brand', () => {
	const first = createInterpreterFacts(observation({
		items: [{ stableId: 'item', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 }],
	}));
	const identical = createInterpreterFacts(observation({
		items: [{ stableId: 'item', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 }],
	}), first);
	assert.equal(identical, first);
	assert.equal(changedInterpreterFactDomains(first, identical), 0);

	const healthChanged = createInterpreterFacts(observation({
		player: { x: 0, y: 64, z: 0, health: 19 },
		items: [{ stableId: 'item', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 }],
	}), first);
	assert.equal(healthChanged.world.items, first.world.items);
	assert.equal(healthChanged.inventory.items, first.inventory.items);
	assert.equal(healthChanged.inventory.tagCounts, first.inventory.tagCounts);
	assert.equal(changedInterpreterFactDomains(first, healthChanged), FACT_DOMAIN.player);
	assert.equal(isTrustedInterpreterFacts(healthChanged), true);
	const negativeZero = createInterpreterFacts(observation({
		player: { x: -0, y: 64, z: 0, health: 20 },
		items: [{ stableId: 'item', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 }],
	}), first);
	assert.equal(changedInterpreterFactDomains(first, negativeZero), FACT_DOMAIN.player);

	const imitation = Object.freeze({ ...healthChanged });
	assert.equal(isTrustedInterpreterFacts(imitation), false);
	assert.equal(changedInterpreterFactDomains(healthChanged, imitation), ALL_FACT_DOMAINS);
});

test('trusted fact creation still validates every new observation before reusing prior data', () => {
	const trusted = createInterpreterFacts(observation());
	let reads = 0;
	const hostilePlayer = Object.defineProperty({}, 'health', { enumerable: true, get() { reads += 1; return 20; } });
	assert.throws(() => createInterpreterFacts(observation({ player: hostilePlayer }), trusted), TypeError);
	assert.equal(reads, 0);
});

test('fact trees beyond interpreter limits never receive the trusted fast-path brand', () => {
	const oversized = createInterpreterFacts(observation({
		items: Array.from({ length: 257 }, (_unused, index) => ({
			stableId: `item-${index}`, itemId: 'minecraft:stone', count: 1, x: index, y: 64, z: 0,
		})),
	}));
	assert.equal(isTrustedInterpreterFacts(oversized), false);
});
