import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { TaskMemoryStore } from '../src/task-memory-store.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';

const scope = { agentId: 'sol', goalRevision: 1, worldId: 'world-one', dimension: 'minecraft:overworld' };
const position = (x = 0, y = 64, z = 0) => ({ x, y, z });
const live = (x = 0, items = [{ itemId: 'minecraft:iron_pickaxe', count: 1 }]) => ({ ready: true, player: { ...position(x), health: 20 }, world: { worldId: scope.worldId, dimension: scope.dimension, gameTime: 20 }, inventory: { items } });
const place = (key, x) => ({ kind: 'place', key, label: key, summary: 'Observed staircase junction; check clearance again.', position: position(x) });
const death = (x, at) => ({ ready: false, player: { dead: true }, inventory: { items: [] }, death: { ...position(x), cause: 'zombie', dimensionId: scope.dimension, diedAtEpochMs: at } });

test('earlier equipment losses and connected staircase routes survive repeated empty-handed deaths and restart', async (t) => {
 const directory = await mkdtemp(join(tmpdir(), 'task-memory-'));
 t.after(() => rm(directory, { recursive: true, force: true }));
 const first = new TaskMemoryStore({ directory });
 await first.remember(scope, place('base', 0)); await first.remember(scope, place('stair-2', 10));
 await first.remember(scope, { kind: 'route', key: 'base-to-stair-2', label: 'Existing mine access', summary: 'Walked downhill; uphill clearance not yet checked.', from: 'base', to: 'stair-2', waypoints: [position(), position(5, 62), position(10, 60)] });
 for (const x of [0, 2, 4]) await first.observe(scope, live(x));
 await first.observe(scope, death(5, 100));
 await first.observe(scope, live(0, [])); await first.observe(scope, death(1, 200));
 await first.flush();
 const restored = new TaskMemoryStore({ directory });
 const summary = await restored.summary({ ...scope, goalRevision: 2 });
 assert.equal(summary.deaths.length, 2); assert.equal(summary.currentGoalRevision, 2);
 assert.equal(summary.deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 assert.deepEqual(summary.deaths[1].lostInventory, []);
 assert.equal(summary.deaths[0].availability, 'unverified'); assert.equal(summary.deaths[0].reverseVerified, false);
 assert.equal(summary.routes[0].from, 'base'); assert.equal(summary.routes[0].to, 'stair-2');
 const routes = await restored.query(scope, { kind: 'route' });
 assert.deepEqual(routes.entries[0].waypoints, [position(), position(5, 62), position(10, 60)]);
 assert.equal((await restored.query(scope, { kind: 'deaths' })).entries[0].outboundTrail.length, 3);
});

test('automatic observations stay private; sharing a model note is explicit and scoped to world and dimension', async () => {
	const store = new TaskMemoryStore();
 await store.observe(scope, { ...live(), blocks: [{ ...position(1), blockId: 'minecraft:crafting_table' }, { ...position(3), blockId: 'minecraft:red_bed' }] });
 await store.observe(scope, death(4, 100));
 await store.remember(scope, place('private-base', 0));
 await store.remember(scope, { ...place('shared-base', 0), shared: true });
 const teammate = { ...scope, agentId: 'luna' };
 assert.deepEqual((await store.query(teammate)).entries.map((e) => e.key), ['shared-base']);
 assert.equal((await store.query({ ...teammate, dimension: 'minecraft:the_nether' })).total, 0);
 assert.equal((await store.query({ ...teammate, worldId: 'world-two' })).total, 0);
 assert.equal((await store.query(scope, { kind: 'assets' })).total, 2);
 await store.remember(scope, { ...place('shared-base', 0), shared: false });
 assert.equal((await store.query(teammate)).total, 0);
});

test('a sparse death can be enriched once without inventing or overwriting lost inventory', async () => {
 const store = new TaskMemoryStore();
 await store.observe(scope, death(2, 100));
 await store.observe(scope, { ...death(2, 100), lastLiveInventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] } });
 await store.observe(scope, death(2, 100));
 assert.equal((await store.query(scope, { kind: 'deaths' })).total, 1);
 assert.equal((await store.summary(scope)).deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
});

test('a partial live observation preserves inventory evidence and reobserved missing infrastructure is marked changed', async () => {
 const store = new TaskMemoryStore();
 await store.observe(scope, { ...live(), blocks: [{ ...position(1), blockId: 'minecraft:furnace' }] });
 await store.observe(scope, { ...live(), inventory: {}, nearbyContainers: [{ ...position(1), containerId: 2 }] });
 assert.equal((await store.query(scope, { kind: 'assets' })).entries[0].availability, 'last_observed');
 await store.observe(scope, { ...live(2), inventory: undefined, blocks: [{ ...position(1), blockId: 'minecraft:air' }] });
 await store.observe(scope, death(3, 100));
 assert.equal((await store.summary(scope)).deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 assert.equal((await store.query(scope, { kind: 'assets' })).entries[0].availability, 'observed_changed');
});

test('after respawning in another dimension the agent can recall earlier death records without sharing private memory or changing worlds', async () => {
 const store = new TaskMemoryStore();
 const nether = { ...scope, dimension: 'minecraft:the_nether' };
 await store.observe(nether, { ...live(), world: { worldId: scope.worldId, dimension: nether.dimension } });
 await store.observe(nether, { ...death(4, 100), death: { ...death(4, 100).death, dimensionId: nether.dimension } });
 const tool = normalizeMinecraftToolCall('taskMemory', { operation: 'query', query: { kind: 'deaths', dimension: nether.dimension } });
 const remembered = await store.query(scope, tool.query);
 assert.equal(remembered.dimension, nether.dimension);
 assert.equal(remembered.entries[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 assert.equal((await store.query({ ...scope, agentId: 'another-agent' }, tool.query)).total, 0);
 assert.equal((await store.query({ ...scope, worldId: 'another-world' }, tool.query)).total, 0);
 assert.equal((await store.query(scope, { kind: 'deaths' })).total, 0);
 assert.throws(() => normalizeMinecraftToolCall('taskMemory', { operation: 'query', query: { worldId: 'another-world' } }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
 await store.flush();
});

test('long routes expose omissions and summaries stay bounded with long notes and inventory', async () => {
 const store = new TaskMemoryStore();
 for (let x = 0; x < 150; x++) await store.observe(scope, live(x * 2, Array.from({ length: 64 }, (_, i) => ({ itemId: `minecraft:${'x'.repeat(200)}_${i}`, count: 64 }))));
 await store.observe(scope, death(302, 100));
 for (const kind of ['lesson', 'progress', 'place']) await store.remember(scope, { ...place(kind, 0), kind, summary: '很'.repeat(1024) });
 const query = await store.query(scope, { kind: 'deaths' });
 assert.equal(query.entries[0].outboundTrail.length, 128); assert.equal(query.entries[0].omittedWaypoints, 22);
 assert.equal(query.entries[0].reverseVerified, false);
 const summary = await store.summary(scope);
 assert.ok(Buffer.byteLength(JSON.stringify(summary)) <= 6000);
 assert.equal(summary.totals.deaths, 1);
 await store.flush();
});

test('retired entries free capacity without evicting another agent records; queries expose pagination', async () => {
 const store = new TaskMemoryStore();
 for (let i = 0; i < 256; i++) await store.remember(scope, place(`junction-${i}`, i));
 await assert.rejects(store.remember(scope, place('overflow', 300)), /TASK_MEMORY_FULL/);
 await store.remember(scope, { ...place('junction-0', 0), status: 'retired' });
 await store.remember(scope, place('overflow', 300));
 const page = await store.query(scope, { kind: 'place', limit: 64 });
 assert.equal(page.total, 256); assert.equal(page.nextOffset, 64);
 assert.equal((await store.query(scope, { kind: 'place', offset: page.nextOffset, limit: 64 })).entries.length, 64);
 await store.flush();
});

test('native taskMemory contract rejects fabricated source, missing route endpoints and excess waypoints', () => {
 for (const entry of [{ ...place('base', 0), source: 'server' }, { kind: 'route', key: 'r', label: 'r', summary: 'r', waypoints: [position(), position(1)] }, { ...place('bad', 0), waypoints: Array(65).fill(position()) }]) {
  assert.throws(() => normalizeMinecraftToolCall('taskMemory', { operation: 'remember', entry }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
 }
 assert.equal(normalizeMinecraftToolCall('taskMemory', { operation: 'query', query: { text: '' } }).query.kind, 'all');
});

test('native observation and memory tools deliver recovery automatically to respawn events across goals', async () => {
 const memory = new RuntimeMemoryContext(); let record = { agentId: scope.agentId, goalRevision: 1, provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium' };
 const runtime = new NativeToolRuntime({ registry: { get: () => record }, bridge: { send: async () => {} }, memoryObservation: (r, o) => memory.observe(r, o), taskContext: (r) => memory.taskContext(r), memoryOperation: (r, operation) => memory.execute(r, operation) });
 runtime.updateObservation(record, live(), { eventSequence: 1 });
 const request = { agentId: scope.agentId, goalRevision: 1, turnId: 'turn', callId: 'memory', tool: normalizeMinecraftToolCall('taskMemory', { operation: 'remember', entry: place('base', 0) }) };
 assert.equal((await runtime.execute(request, record)).reasonCode, 'TASK_MEMORY_WRITTEN');
 runtime.updateObservation(record, death(2, 100), { eventSequence: 2 });
 runtime.updateObservation(record, live(0, []), { eventSequence: 3 });
 record = { ...record, goalRevision: 2 };
 runtime.updateObservation(record, live(0, []), { eventSequence: 4 });
 const taskMemory = await memory.taskContext(record);
 const observed = await runtime.execute({ ...request, goalRevision: 2, callId: 'observe', tool: { kind: 'observe' } }, record);
 assert.equal(observed.taskMemory.deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 const input = buildNativeEventInput(record, { event: 'respawn', taskMemory, observation: observed.observation });
 const payload = JSON.parse(input.slice(input.indexOf('\n') + 1));
 assert.equal(payload.taskMemory.places[0].key, 'base');
 assert.equal(payload.taskMemory.deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 assert.equal(payload.taskMemory.deaths[0].availability, 'unverified');
 assert.equal(await memory.taskContext({ ...record, goalRevision: 1 }), null);
 await runtime.dispose(record.agentId); await memory.flush();
});

test('runtime snapshots input before asynchronous memory ingestion and does not carry a dimension into another world', async () => {
 const memory = new RuntimeMemoryContext(); const record = { agentId: 'sol', goalRevision: 1 };
 const input = live(); memory.observe(record, input); input.player.x = 999; input.inventory.items = [];
 memory.observe(record, death(2, 100));
 assert.equal((await memory.taskContext(record)).deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
 memory.observe({ ...record, goalRevision: 2 }, { world: { worldId: 'world-two' } });
 assert.equal(await memory.taskContext({ ...record, goalRevision: 2 }), null);
 await memory.flush();
});
