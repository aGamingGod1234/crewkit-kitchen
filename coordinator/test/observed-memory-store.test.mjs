import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AtomicAgentStore, ObservedMemoryStore } from '../src/observed-memory-store.mjs';

const view = (worldId, dimension, gameTime, x = 0, blocks = []) => ({ world: { worldId, dimension, gameTime }, position: { x, y: 64, z: 0 }, blocks });
const stone = { blockId: 'minecraft:stone', x: 12, y: 80, z: 0 };

test('dimension round trips retain independent memory and another world shares no coordinates', () => {
	const memory = new ObservedMemoryStore();
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, [stone]));
	memory.ingest('a', view('one', 'minecraft:the_nether', 11, 40));
	memory.ingest('a', view('two', 'minecraft:overworld', 12, 80));
	assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks[0].blockId, 'minecraft:stone');
	assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:the_nether' }).blocks.length, 0);
	assert.equal(memory.query('a', { worldId: 'two', dimension: 'minecraft:overworld' }).position.x, 80);
	assert.equal(memory.query('b', { worldId: 'one', dimension: 'minecraft:overworld' }).knownCells, 0);
});

test('blocked evidence expires and changed observed blocks clear prior failure without repeated sight pretending a visit', () => {
	const memory = new ObservedMemoryStore({ blockedForTicks: 20, staleAfterTicks: 30 });
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, [stone]));
	memory.markBlocked('a', { worldId: 'one', dimension: 'minecraft:overworld', x: 12, y: 80, z: 0 });
	memory.ingest('a', view('one', 'minecraft:overworld', 11, 0, [stone]));
	assert.equal(memory.query('a').blockedCells, 1);
	assert.equal(memory.query('a').visitedCells, 1);
	assert.equal(memory.query('a', { nowTick: 31 }).blockedCells, 0);
	assert.equal(memory.query('a', { nowTick: 42 }).blocks[0].stale, true);
	memory.ingest('a', view('one', 'minecraft:overworld', 12, 0, [{ ...stone, blockId: 'minecraft:air' }]));
	assert.equal(memory.query('a').blockedCells, 0);
	assert.equal(memory.query('a').blocks[0].blockId, 'minecraft:air');
});

test('observed door state changes clear blocked evidence, while reordered and partial sightings do not', () => {
	const memory = new ObservedMemoryStore();
	const door = { blockId: 'minecraft:oak_door', x: 12, y: 64, z: 0 };
	const ingest = (tick, block) => memory.ingest('a', view('one', 'minecraft:overworld', tick, 0, [block]));
	ingest(10, { ...door, state: { open: 'false', facing: 'north' } });
	memory.markBlocked('a', { worldId: 'one', dimension: 'minecraft:overworld', ...door });
	ingest(11, { ...door, state: { facing: 'north', open: 'false' } });
	assert.equal(memory.query('a').blockedCells, 1, 'property order is not a world change');
	ingest(12, door);
	assert.equal(memory.query('a').blockedCells, 1, 'missing state is not a world change');
	ingest(13, { ...door, state: { facing: 'north', open: 'true' } });
	assert.equal(memory.query('a').blockedCells, 0, 'opening the same door invalidates its old obstruction');
	assert.deepEqual(JSON.parse(memory.query('a').blocks[0].blockState), { facing: 'north', open: 'true' });
});

test('time rollback invalidates only that dimension and remembered records remain bounded', () => {
	const memory = new ObservedMemoryStore({ maximumCells: 2, maximumBlocks: 2 });
	memory.ingest('a', view('one', 'minecraft:the_nether', 1, 8, [stone]));
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, Array.from({ length: 8 }, (_, index) => ({ ...stone, x: index * 8 }))));
	assert.equal(memory.query('a').cells.length, 2);
	assert.equal(memory.query('a').blocks.length, 2);
	memory.ingest('a', view('one', 'minecraft:overworld', 2));
	assert.equal(memory.query('a').blocks.length, 0);
	assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:the_nether' }).blocks.length, 0, 'old dimension records are evicted by the total per-agent budget');
});

test('partial sightings refresh block identity without refreshing remembered property age', () => {
	const memory = new ObservedMemoryStore({ staleAfterTicks: 30 });
	const door = { ...stone, blockId: 'minecraft:oak_door' };
	const ingest = (tick, block) => memory.ingest('a', view('one', 'minecraft:overworld', tick, 0, [block]));
	ingest(10, { ...door, state: { open: 'false' } });
	for (const tick of [20, 30, 40]) ingest(tick, door);
	assert.equal(memory.query('a').blocks[0].blockState, '{"open":"false"}');
	ingest(41, door);
	assert.equal(memory.query('a').blocks[0].stale, false, 'identity was just observed');
	assert.equal(memory.query('a').blocks[0].blockState, undefined, 'expired properties are not current facts');
	ingest(42, { ...door, state: { open: 'true' } });
	assert.equal(memory.query('a').blocks[0].blockState, '{"open":"true"}');
	ingest(43, stone);
	assert.equal(memory.query('a').blocks[0].blockState, undefined, 'replacement does not inherit properties');
});

test('durable property age survives partial sightings and legacy records use lastSeenTick', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'property-age-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory, staleAfterTicks: 30 });
	const door = { ...stone, blockId: 'minecraft:oak_door' };
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, [{ ...door, state: { open: 'false' } }]));
	memory.ingest('a', view('one', 'minecraft:overworld', 40, 0, [door]));
	await memory.flush('a');
	const restored = new ObservedMemoryStore({ directory, staleAfterTicks: 30 });
	await restored.load('a');
	const scope = { worldId: 'one', dimension: 'minecraft:overworld' };
	assert.equal(restored.query('a', { ...scope, nowTick: 40 }).blocks[0].blockState, '{"open":"false"}');
	assert.equal(restored.query('a', { ...scope, nowTick: 41 }).blocks[0].blockState, undefined);
	await new AtomicAgentStore({ directory, namespace: 'observed' }).write('legacy', { version: 1, scopes: [{
		...scope, tick: 10, position: null, cells: [], blocks: [{ ...door, key: '12,80,0', firstSeenTick: 10, lastSeenTick: 10, blockState: '{"open":"false"}' }],
	}] });
	await restored.load('legacy');
	assert.equal(restored.query('legacy', { ...scope, nowTick: 40 }).blocks[0].blockState, '{"open":"false"}');
	restored.ingest('legacy', view('one', 'minecraft:overworld', 41, 0, [door]));
	assert.equal(restored.query('legacy').blocks[0].blockState, undefined);
});

test('atomic observed-memory save survives a new instance and excludes arbitrary world fields', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'observed-memory-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory });
	const door = { ...stone, blockId: 'minecraft:oak_door', state: { open: 'true', facing: 'north' } };
	memory.ingest('../agent', { ...view('one', 'minecraft:overworld', 10, 0, [door]), secret: 'do-not-copy', world: { worldId: 'one', dimension: 'minecraft:overworld', gameTime: 10, seed: 'hidden-seed' } });
	await memory.flush('../agent');
	const restored = new ObservedMemoryStore({ directory });
	await restored.load('../agent');
	const savedDoor = restored.query('../agent', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks[0];
	assert.equal(savedDoor.blockId, 'minecraft:oak_door');
	assert.deepEqual(JSON.parse(savedDoor.blockState), { facing: 'north', open: 'true' });
	const files = await readdir(directory);
	assert.equal(files.length, 1);
	assert.doesNotMatch(await readFile(join(directory, files[0]), 'utf8'), /hidden-seed|do-not-copy/);
});

test('unidentified session memory is not reused by a later process', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'session-memory-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory });
	memory.ingest('a', { position: { x: 0, y: 64, z: 0 }, blocks: [stone] });
	await memory.flush('a');
	const restored = new ObservedMemoryStore({ directory });
	await restored.load('a');
	assert.equal(restored.query('a').knownCells, 0);
});

test('startup hydration preserves newer observations and flush cannot overwrite unhydrated history', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'memory-startup-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const first = new ObservedMemoryStore({ directory });
	first.ingest('a', view('one', 'minecraft:overworld', 10, 0, [stone]));
	await first.flush('a');
	const second = new ObservedMemoryStore({ directory });
	const loading = second.load('a');
	second.ingest('a', view('one', 'minecraft:overworld', 11, 16, [{ blockId: 'minecraft:chest', x: 20, y: 64, z: 0 }]));
	await Promise.all([loading, second.flush('a')]);
	const third = new ObservedMemoryStore({ directory });
	await third.load('a');
	const saved = third.query('a', { worldId: 'one', dimension: 'minecraft:overworld' });
	assert.equal(saved.position.x, 16);
	assert.deepEqual(new Set(saved.blocks.map((block) => block.blockId)), new Set(['minecraft:stone', 'minecraft:chest']));
});

test('cleared agents reload durable facts in the same process without mixing world scopes', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'memory-resummon-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory });
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, [stone]));
	memory.ingest('a', view('one', 'minecraft:the_nether', 10, 40));
	await memory.flush('a');
	for (const clearAll of [false, true]) {
		memory.clear(clearAll ? undefined : 'a');
		assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:overworld' }).knownCells, 0);
		await memory.load('a');
		memory.ingest('a', view('two', 'minecraft:overworld', 11, 80));
		assert.equal(memory.query('a').blocks.length, 0);
		assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:the_nether' }).blocks.length, 0);
		assert.deepEqual(memory.query('a', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks.map((block) => block.blockId), ['minecraft:stone']);
		assert.equal(memory.query('b', { worldId: 'one', dimension: 'minecraft:overworld' }).knownCells, 0);
	}
});

test('clear detaches an old load and fences its eventual result from a new lifecycle', async (t) => {
	const saved = (blockId) => ({ version: 1, scopes: [{
		worldId: 'one', dimension: 'minecraft:overworld', tick: 10, position: null, cells: [],
		blocks: [{ ...stone, blockId, key: '12,80,0', firstSeenTick: 10, lastSeenTick: 10 }],
	}] });
	let releaseOld;
	let reads = 0;
	t.mock.method(AtomicAgentStore.prototype, 'read', () => ++reads === 1
		? new Promise((resolve) => { releaseOld = resolve; }) : Promise.resolve(saved('minecraft:chest')));
	const memory = new ObservedMemoryStore();
	const oldLoad = memory.load('a');
	memory.clear('a');
	await memory.load('a');
	assert.equal(reads, 2);
	releaseOld(saved('minecraft:stone'));
	await oldLoad;
	assert.deepEqual(memory.query('a', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks.map((block) => block.blockId), ['minecraft:chest']);
});

test('clear fences a flush waiting for hydration so it cannot erase saved history', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'memory-clear-flush-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory });
	memory.ingest('a', view('one', 'minecraft:overworld', 10, 0, [stone]));
	await memory.flush('a');
	const flushing = memory.flush('a');
	memory.clear('a');
	await flushing;
	await memory.load('a');
	assert.equal(memory.query('a', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks[0].blockId, 'minecraft:stone');
});

test('durable reads wait for already queued writes before rehydrating memory', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'memory-write-read-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const disk = new AtomicAgentStore({ directory, namespace: 'observed' });
	await disk.write('a', { version: 1, scopes: [] });
	const next = { version: 1, scopes: [{ worldId: 'one' }] };
	const writing = disk.write('a', next);
	assert.deepEqual(await disk.read('a'), next);
	await writing;
});
