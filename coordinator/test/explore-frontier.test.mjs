import assert from 'node:assert/strict';
import test from 'node:test';

import {
	CELL_SIZE,
	ExplorationOccupancy,
	cellKey,
	extractPosition,
} from '../src/explore-frontier.mjs';

function observation({ x = 0, y = 64, z = 0, blocks = [], dimension = 'minecraft:overworld' } = {}) {
	return {
		position: { x, y, z },
		player: { health: 20, x, y, z },
		world: { dimension, gameTime: 20, dayTime: 1_000, raining: false, thundering: false },
		blocks,
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1, slot: 0 }] },
	};
}

test('occupancy marks the player cell and line-of-sight blocks as known', () => {
	const occupancy = new ExplorationOccupancy();
	occupancy.ingest('agent-a', observation({
		blocks: [{ blockId: 'minecraft:stone', x: 12, y: 63, z: 1 }],
	}));
	const snap = occupancy.snapshot('agent-a');
	assert.equal(snap.dimension, 'minecraft:overworld');
	assert.equal(snap.knownCells, 2);
	assert.equal(cellKey(0, 0), '0,0');
	assert.equal(cellKey(12, 1), '1,0');
});

test('frontier selection is deterministic and prefers unknown adjacent space', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation();
	const first = occupancy.select('agent-a', view, { seek: 'any', radius: 24 });
	const second = occupancy.select('agent-a', view, { seek: 'any', radius: 24 });
	assert.equal(first.kind, 'frontier');
	assert.deepEqual(first.destination, second.destination);
	assert.equal(first.destination.y, 64);
	assert.ok(Math.hypot(first.destination.x, first.destination.z) > 0);
	assert.ok(Math.hypot(first.destination.x, first.destination.z) <= 24);
});

test('nether seek walks toward a visible portal cue instead of the nearest empty cell', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({
		blocks: [{ blockId: 'minecraft:obsidian', x: 10, y: 64, z: 0 }],
	});
	const nether = occupancy.select('agent-a', view, { seek: 'nether', radius: 24 });
	assert.equal(nether.kind, 'cue');
	assert.equal(nether.cue.class, 'portal');
	assert.equal(nether.cue.blockId, 'minecraft:obsidian');
	assert.deepEqual(nether.destination, { x: 10.5, y: 64, z: 0.5 });

	const village = occupancy.select('agent-b', view, { seek: 'village', radius: 24 });
	assert.equal(village.kind, 'frontier');
	assert.equal(village.cue, null);
	assert.notDeepEqual(village.destination, nether.destination);
});

test('a matching cue already in range does not invent another walk', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		blocks: [{ blockId: 'minecraft:obsidian', x: 1, y: 64, z: 0 }],
	}), { seek: 'nether', radius: 24 });
	assert.equal(selected.kind, 'cue_in_view');
	assert.ok(selected.cue.distance <= 2.5);
});

test('a generic chest does not terminate structure exploration by itself', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		blocks: [{ blockId: 'minecraft:chest', x: 1, y: 64, z: 0 }],
	}), { seek: 'structure', radius: 24 });
	assert.notEqual(selected.kind, 'cue_in_view');
	assert.equal(selected.cue, null);
});

test('blocked destinations are skipped on the next frontier hop', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation();
	const first = occupancy.select('agent-a', view, { seek: 'any', radius: 24 });
	occupancy.markBlocked('agent-a', 'minecraft:overworld', first.destination.x, first.destination.z);
	const next = occupancy.select('agent-a', view, { seek: 'any', radius: 24 });
	assert.equal(next.kind, 'frontier');
	assert.notDeepEqual(next.destination, first.destination);
});

test('no unknown adjacent cell inside the radius returns NO_FRONTIER rather than a random walk', () => {
	const occupancy = new ExplorationOccupancy();
	const filled = observation({
		blocks: [
			{ blockId: 'minecraft:stone', x: -4, y: 63, z: -4 },
			{ blockId: 'minecraft:stone', x: -4, y: 63, z: 4 },
			{ blockId: 'minecraft:stone', x: 4, y: 63, z: -4 },
		],
	});
	const selected = occupancy.select('agent-a', filled, { seek: 'any', radius: 8 });
	assert.equal(selected.kind, 'no_frontier');
	assert.equal(selected.destination, null);
	assert.match(selected.reason, /unknown adjacent/i);
});

test('missing player position fails closed without a destination', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', { player: { health: 20 }, blocks: [] }, { seek: 'any' });
	assert.equal(selected.kind, 'no_observation');
	assert.equal(selected.destination, null);
});

test('dimension changes reset occupancy so nether mapping is not mixed with overworld cells', () => {
	const occupancy = new ExplorationOccupancy();
	occupancy.ingest('agent-a', observation({ x: 32, z: 32 }));
	assert.equal(occupancy.snapshot('agent-a').knownCells, 1);
	occupancy.ingest('agent-a', observation({
		x: 0, z: 0, dimension: 'minecraft:the_nether',
	}));
	assert.equal(occupancy.snapshot('agent-a').dimension, 'minecraft:the_nether');
	assert.equal(occupancy.snapshot('agent-a').knownCells, 1);
});

test('extractPosition accepts wire, adapted, and fixture shapes', () => {
	assert.deepEqual(extractPosition({ position: { x: 1, y: 2, z: 3 } }), { x: 1, y: 2, z: 3 });
	assert.deepEqual(extractPosition({ player: { x: 1, y: 2, z: 3 } }), { x: 1, y: 2, z: 3 });
	assert.deepEqual(extractPosition({ player: { position: { x: 1, y: 2, z: 3 } } }), { x: 1, y: 2, z: 3 });
	assert.equal(extractPosition({ player: { health: 20 } }), null);
});

test('cell size stays inside local navigation range', () => {
	assert.equal(CELL_SIZE, 8);
	assert.ok(CELL_SIZE * 3 <= 32);
});

test('ordinary netherrack in the Nether is not a portal cue', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		x: 0, y: 64, z: 0,
		dimension: 'minecraft:the_nether',
		blocks: [{ blockId: 'minecraft:netherrack', x: 1, y: 64, z: 0 }],
	}), { seek: 'nether', radius: 24 });
	assert.notEqual(selected.kind, 'cue_in_view');
	assert.notEqual(selected.kind, 'cue');
	assert.equal(selected.cue, null);
});

test('nether-brick fortress cues still attract a nether seek', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		x: 0, y: 64, z: 0,
		dimension: 'minecraft:the_nether',
		blocks: [{ blockId: 'minecraft:nether_bricks', x: 12, y: 64, z: 0 }],
	}), { seek: 'nether', radius: 24 });
	assert.equal(selected.kind, 'cue');
	assert.equal(selected.cue.class, 'fortress');
	assert.equal(selected.cue.blockId, 'minecraft:nether_bricks');
});

test('overworld netherrack remains a ruined-portal cue', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		blocks: [{ blockId: 'minecraft:netherrack', x: 10, y: 64, z: 0 }],
	}), { seek: 'nether', radius: 24 });
	assert.equal(selected.kind, 'cue');
	assert.equal(selected.cue.class, 'ruined_portal');
});

test('a nearby chest does not terminate structure exploration', () => {
	const occupancy = new ExplorationOccupancy();
	const selected = occupancy.select('agent-a', observation({
		blocks: [{ blockId: 'minecraft:chest', x: 1, y: 64, z: 0 }],
	}), { seek: 'structure', radius: 24 });
	assert.notEqual(selected.kind, 'cue_in_view');
	assert.notEqual(selected.kind, 'cue');
	assert.equal(selected.cue, null);
});
