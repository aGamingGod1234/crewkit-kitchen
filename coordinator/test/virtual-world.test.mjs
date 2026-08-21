import test from 'node:test';
import assert from 'node:assert/strict';

import { VirtualWorld } from '../src/simulator/virtual-world.mjs';

function scenario(overrides = {}) {
	return {
		seed: 42,
		dimension: 'minecraft:overworld',
		agents: {
			'alice': {
				position: { x: 0, y: 1, z: 0 },
				health: 20,
			},
		},
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
		],
		items: [],
		entities: [],
		...overrides,
	};
}

test('equal seeds produce equal observations and different seeds affect only declared random events', () => {
	const base = scenario({
		randomEvents: [{
			tick: 1,
			type: 'spawn_item',
			itemId: 'minecraft:apple',
			count: 1,
			positions: [{ x: 2, y: 1, z: 0 }, { x: 3, y: 1, z: 0 }],
		}],
	});
	const first = VirtualWorld.fromScenario(base);
	const second = VirtualWorld.fromScenario(base);
	const different = VirtualWorld.fromScenario({ ...base, seed: 45 });
	first.stepTicks(1);
	second.stepTicks(1);
	different.stepTicks(1);
	assert.deepEqual(first.observation('alice'), second.observation('alice'));
	assert.deepEqual(first.observation('alice').blocks, different.observation('alice').blocks);
	assert.deepEqual(first.observation('alice').player, different.observation('alice').player);
	assert.notDeepEqual(first.observation('alice').entities, different.observation('alice').entities);
});

test('exactly 20 ticks advance one simulated second', () => {
	const world = VirtualWorld.fromScenario(scenario());
	world.stepTicks(20);
	assert.equal(world.tickCount, 20);
	assert.equal(world.timeMs, 1_000);
	assert.equal(world.observation('alice').world.gameTime, 20);
});

test('continuous player state cannot penetrate a solid voxel', () => {
	const world = VirtualWorld.fromScenario(scenario({
		agents: { alice: { position: { x: 0.25, y: 1, z: 0 }, velocity: { x: 1, y: 0, z: 0 } } },
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		],
	}));
	world.stepTicks(5);
	assert.ok(world.observation('alice').position.x < 1, 'player must remain on the near side of the wall');
});

test('lava damages, drops pick up only within radius, and checkpoint respawn restores the player', () => {
	const world = VirtualWorld.fromScenario(scenario({
		agents: { alice: { position: { x: 0, y: 1, z: 0 }, health: 5 } },
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:lava' },
		],
		items: [
			{ id: 'near-drop', itemId: 'minecraft:cobblestone', count: 2, position: { x: 1, y: 1, z: 0 } },
			{ id: 'far-drop', itemId: 'minecraft:stick', count: 1, position: { x: 2, y: 1, z: 0 } },
		],
	}));
	world.recordCheckpoint('alice', { x: 5, y: 1, z: 5 });
	world.stepTicks(1);
	const damaged = world.observation('alice');
	assert.ok(damaged.player.health < 5);
	assert.equal(damaged.inventory.items.some((item) => item.itemId === 'minecraft:cobblestone'), true);
	assert.equal(damaged.inventory.items.some((item) => item.itemId === 'minecraft:stick'), false);
	world.damage('alice', 100);
	world.stepTicks(1);
	assert.equal(world.observation('alice').ready, false);
	assert.equal(world.observation('alice').status, 'PLAYER_DEAD');
	assert.equal(world.respawn('alice'), true);
	const respawned = world.observation('alice');
	assert.equal(respawned.ready, true);
	assert.deepEqual(respawned.position, { x: 5, y: 1, z: 5 });
	assert.equal(respawned.player.health, 20);
});

test('scenario input is immutable and start/stop use an idempotent injected scheduler', () => {
	const input = scenario();
	const original = structuredClone(input);
	let nextHandle = 0;
	let scheduled = 0;
	let cancelled = 0;
	const scheduler = {
		setInterval() { scheduled += 1; return ++nextHandle; },
		clearInterval() { cancelled += 1; },
	};
	const world = VirtualWorld.fromScenario(input, { scheduler });
	world.start();
	world.start();
	world.stop();
	world.stop();
	world.stepTicks(2);
	assert.equal(scheduled, 1);
	assert.equal(cancelled, 1);
	assert.deepEqual(input, original);
});
