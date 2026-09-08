import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ObservedMemoryStore } from '../src/observed-memory-store.mjs';

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

test('atomic observed-memory save survives a new instance and excludes arbitrary world fields', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'observed-memory-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const memory = new ObservedMemoryStore({ directory });
	memory.ingest('../agent', { ...view('one', 'minecraft:overworld', 10, 0, [stone]), secret: 'do-not-copy', world: { worldId: 'one', dimension: 'minecraft:overworld', gameTime: 10, seed: 'hidden-seed' } });
	await memory.flush('../agent');
	const restored = new ObservedMemoryStore({ directory });
	await restored.load('../agent');
	assert.equal(restored.query('../agent', { worldId: 'one', dimension: 'minecraft:overworld' }).blocks[0].blockId, 'minecraft:stone');
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
