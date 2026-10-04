import assert from 'node:assert/strict';
import test from 'node:test';
import { ExplorationOccupancy, cellKey, extractPosition } from '../src/explore-frontier.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { ObservedMemoryStore } from '../src/observed-memory-store.mjs';

function observation({ x = 0, y = 64, z = 0, blocks = [], landmarks = [], dimension = 'minecraft:overworld', gameTime = 20, worldId = 'world-a' } = {}) {
	return { position: { x, y, z }, world: { dimension, worldId, gameTime }, blocks, landmarks };
}

test('candidate queries return facts without selecting a destination or mutating remembered state', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ blocks: [{ blockId: 'minecraft:obsidian', x: 12, y: 64, z: 0 }] });
	const before = occupancy.snapshot('a');
	const first = occupancy.candidates('a', view);
	assert.equal(first.kind, 'candidates');
	assert.equal(first.destination, null);
	assert.ok(first.candidates.some((entry) => entry.kind === 'observed_block'));
	assert.ok(first.candidates.some((entry) => entry.kind === 'unknown_cell'));
	assert.ok(first.candidates.every((entry) => entry.reachability === 'unknown'));
	assert.deepEqual(occupancy.candidates('a', view), first);
	assert.deepEqual(occupancy.snapshot('a'), before);
});

test('goals and historical seek keywords never favor portal or fortress material', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ blocks: [{ blockId: 'minecraft:obsidian', x: 12, y: 64, z: 0 }, { blockId: 'minecraft:stone', x: 1, y: 64, z: 0 }] });
	const result = occupancy.select('a', view, { seek: 'nether' });
	assert.deepEqual(result, occupancy.select('a', view, { seek: 'village' }));
	const blocks = result.candidates.filter((entry) => entry.kind === 'observed_block');
	assert.equal(blocks[0].blockId, 'minecraft:stone');
	assert.equal(blocks[1].class, undefined);
	assert.equal(blocks[1].weight, undefined);
});

test('distance includes altitude and far vertical landmarks are outside the radius', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ landmarks: [{ blockId: 'minecraft:chest', x: 0, y: 84, z: 0 }, { blockId: 'minecraft:gold_block', x: 0, y: 120, z: 0 }] });
	const result = occupancy.candidates('a', view);
	const chest = result.candidates.find((entry) => entry.blockId === 'minecraft:chest');
	assert.ok(chest.distance > 20);
	assert.equal(chest.position.y, 84.5);
	assert.equal(result.candidates.some((entry) => entry.blockId === 'minecraft:gold_block'), false);
});

test('candidate pages are bounded and literal block filters retain no unknown guesses', () => {
	const occupancy = new ExplorationOccupancy();
	const result = occupancy.candidates('a', observation(), { limit: 1 });
	assert.equal(result.candidates.length, 1);
	assert.equal(result.truncated, true);
	const view = observation({ landmarks: [{ blockId: 'minecraft:chest', x: 8, y: 64, z: 0 }] });
	const filtered = occupancy.candidates('a', view, { blockId: 'minecraft:chest' });
	assert.equal(filtered.candidates.length, 1);
	assert.equal(filtered.candidates[0].blockId, 'minecraft:chest');
});

test('unknown kind filter retrieves frontiers behind 100 nearby blocks without changing the default page', () => {
	const occupancy = new ExplorationOccupancy();
	const floor = [];
	for (let x = -1; x <= 8; x++) for (let z = -1; z <= 8; z++) floor.push({ x, y: 63, z, blockId: 'minecraft:stone' });
	for (let offset = 0; offset < floor.length; offset += 8) occupancy.ingest('a', observation({ x: 4, z: 4, gameTime: offset + 1, blocks: floor.slice(offset, offset + 8) }));
	const view = observation({ x: 4, z: 4, gameTime: 110 });
	const before = occupancy.snapshot('a');
	const defaults = occupancy.candidates('a', view, { limit: 64 });
	assert.equal(defaults.candidates.length, 64);
	assert.ok(defaults.candidates.every((entry) => entry.kind === 'observed_block'));
	assert.deepEqual(occupancy.candidates('a', view, { limit: 64, kind: 'all' }), defaults);
	const unknown = occupancy.candidates('a', view, { limit: 64, kind: 'unknown_cell' });
	assert.equal(unknown.totalCandidates, 30);
	assert.equal(unknown.candidates.length, 30);
	assert.equal(unknown.truncated, false);
	assert.ok(unknown.candidates.every((entry) => entry.kind === 'unknown_cell' && entry.reachability === 'unknown'));
	assert.deepEqual(unknown.candidates, [...unknown.candidates].sort((left, right) => left.distance - right.distance || left.id.localeCompare(right.id)));
	assert.equal(unknown.destination, null);
	assert.deepEqual(occupancy.candidates('a', view, { kind: 'unknown_cell', limit: 2 }).candidates, unknown.candidates.slice(0, 2));
	assert.equal(occupancy.candidates('a', view, { kind: 'observed_block', limit: 64 }).totalCandidates, 100);
	assert.deepEqual(occupancy.candidates('a', view, { kind: 'unknown_cell', blockId: 'minecraft:stone' }).candidates, []);
	assert.deepEqual(occupancy.snapshot('a'), before);
});

test('kind filters preserve world and dimension scope and reject unsupported kinds', () => {
	const occupancy = new ExplorationOccupancy();
	occupancy.ingest('a', observation({ blocks: [{ blockId: 'minecraft:chest', x: 12, y: 64, z: 0 }] }));
	for (const view of [observation({ dimension: 'minecraft:the_nether' }), observation({ worldId: 'world-b' })]) {
		assert.deepEqual(occupancy.candidates('a', view, { kind: 'observed_block' }).candidates, []);
		assert.deepEqual(occupancy.candidates('a', view, { kind: 'unknown_cell' }), new ExplorationOccupancy().candidates('a', view, { kind: 'unknown_cell' }));
	}
	assert.throws(() => occupancy.candidates('a', observation(), { kind: 'nearest' }), /kind must be/);
});

test('occupancy preserves snapshot returns and forwards snapshot suppression to the real store', (t) => {
	const memoryStore = new ObservedMemoryStore();
	const occupancy = new ExplorationOccupancy({ memoryStore });
	const queries = t.mock.method(memoryStore, 'query');
	const view = observation({ blocks: [{ blockId: 'minecraft:chest', x: 12, y: 64, z: 0 }] });
	assert.equal(occupancy.ingest('a', view).blocks[0].blockId, 'minecraft:chest');
	assert.equal(queries.mock.callCount(), 1);
	assert.equal(occupancy.ingest('a', { ...view, position: { x: 24, y: 64, z: 0 } }, { snapshot: false }), undefined);
	assert.equal(queries.mock.callCount(), 1, 'discarded snapshots never query/copy retained records');
	assert.equal(occupancy.snapshot('a').visitedCells, 2);
});

test('missing player position returns no destination, including compatibility select', () => {
	const result = new ExplorationOccupancy().select('a', { player: { health: 20 } });
	assert.equal(result.kind, 'no_observation');
	assert.equal(result.destination, null);
	assert.deepEqual(result.candidates, []);
});

test('seen landmarks do not count as visited and height layers remain independent', () => {
	const occupancy = new ExplorationOccupancy();
	occupancy.ingest('a', observation({ landmarks: [{ blockId: 'minecraft:stone', x: 0, y: 80, z: 0 }] }));
	const result = occupancy.snapshot('a');
	assert.equal(result.knownCells, 2);
	assert.equal(result.visitedCells, 1);
	assert.equal(result.seenCells, 1);
	assert.notEqual(cellKey(0, 64, 0), cellKey(0, 80, 0));
});

test('position adapters accept current wire and nested player shapes', () => {
	assert.deepEqual(extractPosition({ position: { x: 1, y: 2, z: 3 } }), { x: 1, y: 2, z: 3 });
	assert.deepEqual(extractPosition({ player: { position: { x: 1, y: 2, z: 3 } } }), { x: 1, y: 2, z: 3 });
	assert.equal(extractPosition({ player: { health: 20 } }), null);
});

test('adapted door properties survive duplicate landmark summaries and reach exploration candidates', () => {
	const occupancy = new ExplorationOccupancy();
	const door = { blockId: 'minecraft:oak_door', x: 12, y: 64, z: 0 };
	const adapted = (gameTime, open) => adaptObservation({
		...observation({ gameTime, blocks: [{ ...door, state: { open, facing: 'north' } }],
			landmarks: [{ ...door, distance: 12, bearing: 90, elevation: 0 }] }),
		ready: true, view: { yaw: 0, pitch: 0 }, player: {}, entities: [], inventory: { items: [] },
		world: { worldId: 'world-a', dimension: 'minecraft:overworld', gameTime, dayTime: gameTime, raining: false, thundering: false },
	});
	occupancy.ingest('a', adapted(20, 'false'));
	occupancy.markBlocked('a', 'minecraft:overworld', door.x, door.z, { y: door.y });
	const opened = adapted(21, 'true');
	occupancy.ingest('a', opened);
	const candidate = occupancy.candidates('a', opened, { blockId: door.blockId }).candidates[0];
	assert.deepEqual(JSON.parse(candidate.blockState), { facing: 'north', open: 'true' });
	assert.equal(candidate.blocked, false);
	assert.equal(candidate.visited, false);
	assert.equal(candidate.reachability, 'unknown');
	assert.equal(occupancy.candidates('a', observation({ gameTime: 22 }), { blockId: door.blockId }).candidates[0].blockState, candidate.blockState);
});

test('partial-only candidate sightings retain fresh same-block properties and discard replaced or stale ones', () => {
	const occupancy = new ExplorationOccupancy();
	const door = { blockId: 'minecraft:oak_door', x: 12, y: 64, z: 0 };
	occupancy.ingest('a', observation({ gameTime: 20, blocks: [{ ...door, state: { open: 'false' } }] }));
	const partial = observation({ gameTime: 21, landmarks: [door] });
	occupancy.ingest('a', partial);
	assert.equal(occupancy.candidates('a', partial, { blockId: door.blockId }).candidates[0].blockState, '{"open":"false"}');
	const updated = observation({ gameTime: 22, blocks: [{ ...door, state: { open: 'true' } }] });
	assert.equal(occupancy.candidates('a', updated, { blockId: door.blockId }).candidates[0].blockState, '{"open":"true"}');
	const replaced = observation({ gameTime: 22, landmarks: [{ ...door, blockId: 'minecraft:stone' }] });
	assert.equal(occupancy.candidates('a', replaced, { blockId: 'minecraft:stone' }).candidates[0].blockState, undefined);
	const stale = observation({ gameTime: 1222, landmarks: [door] });
	occupancy.ingest('a', stale);
	assert.equal(occupancy.candidates('a', stale, { blockId: door.blockId }).candidates[0].blockState, undefined);
	const fresh = observation({ gameTime: 1223, blocks: [{ ...door, state: { open: 'true' } }] });
	occupancy.ingest('a', fresh);
	assert.equal(occupancy.candidates('a', fresh, { blockId: door.blockId }).candidates[0].blockState, '{"open":"true"}');
});
