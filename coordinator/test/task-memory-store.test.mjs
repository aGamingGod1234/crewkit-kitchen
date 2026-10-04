import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { TaskMemoryStore } from '../src/task-memory-store.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';
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

for (const failedWrite of [null, 0, 1]) {
 test(`remember waits for its follow-up snapshot with failed write ${failedWrite ?? 'none'}`, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'task-memory-follow-up-'));
  const store = new TaskMemoryStore({ directory });
  const started = [Promise.withResolvers(), Promise.withResolvers()];
  const release = [Promise.withResolvers(), Promise.withResolvers()];
  const originalWrite = AtomicAgentStore.prototype.write;
  let writes = 0;
  t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, value) {
   // Match AtomicAgentStore's snapshot-at-call semantics before holding I/O.
   const snapshot = structuredClone(value), index = writes++;
   started[index]?.resolve();
   await release[index]?.promise;
   if (index === failedWrite) throw new Error(`write ${index} unavailable`);
   return originalWrite.call(this, key, snapshot);
  });
  await store.observe(scope, live());
  await started[0].promise;
  let settled = false;
  const remembering = store.remember(scope, place('follow-up', 2)).then(
   (entry) => { settled = true; return { entry }; },
   (error) => { settled = true; return { error }; });
  try {
   await new Promise(setImmediate);
   release[0].resolve();
   await started[1].promise;
   assert.equal(settled, false, 'the earlier snapshot does not contain the note');
   release[1].resolve();
   const result = await remembering;
   const restored = new TaskMemoryStore({ directory });
   if (failedWrite === 1) {
    assert.match(result.error?.message ?? '', /TASK_MEMORY_WRITE_FAILED.*write 1 unavailable/);
    assert.equal((await restored.query(scope, { kind: 'place' })).total, 0);
    await assert.rejects(store.flush(), /TASK_MEMORY_WRITE_FAILED/);
   } else {
    assert.equal(result.error, undefined);
    assert.deepEqual((await restored.query(scope, { kind: 'place' })).entries, [result.entry]);
   }
   assert.equal(writes, 2);
  } finally {
   for (const gate of release) gate.resolve();
   await remembering;
   await store.flush().catch(() => {});
   await rm(directory, { recursive: true, force: true });
  }
 });
}

test('automatic observations return before disk I/O and coalesce each pending batch', async (t) => {
 const directory = await mkdtemp(join(tmpdir(), 'task-memory-coalescing-'));
 const store = new TaskMemoryStore({ directory });
 const started = Promise.withResolvers(), release = Promise.withResolvers();
 const originalWrite = AtomicAgentStore.prototype.write;
 let writes = 0;
 t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, value) {
  const snapshot = structuredClone(value);
  writes++;
  started.resolve();
  await release.promise;
  return originalWrite.call(this, key, snapshot);
 });
 try {
  for (const x of [0, 2, 4]) await store.observe(scope, live(x));
  assert.equal(writes, 0, 'same-turn observations are batched');
  await started.promise;
  for (const x of [6, 8]) await store.observe(scope, live(x));
  assert.equal(writes, 1, 'observation ingestion completes while a write is blocked');
  release.resolve();
  await store.flush();
  assert.equal(writes, 2);
  const trail = (await new TaskMemoryStore({ directory }).query(scope, { kind: 'trail' })).entries[0];
  assert.deepEqual(trail.waypoints.map((p) => p.x), [0, 2, 4, 6, 8]);
 } finally {
  release.resolve();
  await store.flush();
  await rm(directory, { recursive: true, force: true });
 }
});

test('remember ignores pending writes and errors in other worlds and dimensions', async (t) => {
 const directory = await mkdtemp(join(tmpdir(), 'task-memory-scopes-'));
 const store = new TaskMemoryStore({ directory });
 const pendingScope = { ...scope, worldId: 'pending-world' };
 const failedScope = { ...scope, dimension: 'minecraft:the_nether' };
 const started = Promise.withResolvers(), release = Promise.withResolvers();
 const originalWrite = AtomicAgentStore.prototype.write;
 t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, value) {
  const snapshot = structuredClone(value);
  if (value.worldId === pendingScope.worldId) { started.resolve(); await release.promise; }
  if (value.dimension === failedScope.dimension) throw new Error('other dimension unavailable');
  return originalWrite.call(this, key, snapshot);
 });
 let pendingSettled = false;
 const pending = store.remember(pendingScope, place('same-key', 10)).then((entry) => { pendingSettled = true; return entry; });
 try {
  await started.promise;
  await assert.rejects(store.remember(failedScope, place('same-key', 20)), /TASK_MEMORY_WRITE_FAILED/);
  const entry = await store.remember(scope, place('same-key', 0));
  assert.equal(pendingSettled, false);
  const restored = new TaskMemoryStore({ directory });
  assert.deepEqual((await restored.query(scope)).entries, [entry]);
  assert.equal((await restored.query(failedScope)).total, 0);
  release.resolve();
  const pendingEntry = await pending;
  assert.deepEqual((await new TaskMemoryStore({ directory }).query(pendingScope)).entries, [pendingEntry]);
 } finally {
  release.resolve();
  await pending;
  await store.flush().catch(() => {});
  await rm(directory, { recursive: true, force: true });
 }
});

test('concurrent remembers are reloadable and isolated by agent, world and dimension', async (t) => {
 const directory = await mkdtemp(join(tmpdir(), 'task-memory-concurrent-'));
 t.after(() => rm(directory, { recursive: true, force: true }));
 const store = new TaskMemoryStore({ directory });
 const scopes = [scope, { ...scope, agentId: 'luna' }, { ...scope, worldId: 'world-two' }, { ...scope, dimension: 'minecraft:the_nether' }];
 const jobs = scopes.flatMap((current, i) => Array.from({ length: 3 }, (_, j) => ({ scope: current, entry: place(`note-${j}`, i * 10 + j) })));
 const entries = await Promise.all(jobs.map(async (job) => {
  const entry = await store.remember(job.scope, job.entry);
  const reloaded = await new TaskMemoryStore({ directory }).query(job.scope);
  assert.deepEqual(reloaded.entries.find((e) => e.key === entry.key), entry);
  return entry;
 }));
 const restored = new TaskMemoryStore({ directory });
 for (const current of scopes) {
  const expected = entries.filter((_, i) => jobs[i].scope === current);
  assert.deepEqual((await restored.query(current)).entries, expected);
 }
 await store.flush();
});

// Finite snapshot gates exercise conflicts without elapsed-time assumptions.
{
 function holdSnapshots(t, count, failedWrite = -1) {
  const slots = Array.from({ length: count }, () => ({ started: Promise.withResolvers(), release: Promise.withResolvers() }));
  const snapshots = [];
  const original = AtomicAgentStore.prototype.write;
  t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, state) {
   const snapshot = structuredClone(state), index = snapshots.length;
   snapshots.push(snapshot);
   assert.ok(index < count, 'unexpected snapshot');
   slots[index].started.resolve(snapshot);
   await slots[index].release.promise;
   if (index === failedWrite) throw new Error(`snapshot ${index} unavailable`);
   return original.call(this, key, snapshot);
  });
  return { slots, snapshots, releaseAll() { for (const slot of slots) slot.release.resolve(); } };
 }
 test('same-key successors survive failure, other keys coalesce independently, and flush drains queued versions', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'task-memory-versions-'));
  const store = new TaskMemoryStore({ directory });
  const gate = holdSnapshots(t, 3, 0);
  const capture = promise => promise.then(entry => ({ entry }), error => ({ error }));
  const first = capture(store.remember(scope, place('version', 1)));
  const second = capture(store.remember(scope, place('version', 2)));
  const third = capture(store.remember(scope, place('version', 3)));
  const independent = capture(store.remember(scope, place('independent', 4)));
  const otherAgent = capture(store.remember({ ...scope, agentId: 'other-agent' }, place('version', 5)));
  let flushed = false;
  let flushing;
  try {
   const initial = await gate.slots[0].started.promise;
   assert.deepEqual(initial.entries.map(e => e.position.x), [1, 4, 5], 'a blocked same-key successor must not delay different keys or agents');
   flushing = store.flush().then(() => { flushed = true; });
   gate.slots[0].release.resolve();
   const followUp = await gate.slots[1].started.promise;
   assert.match((await first).error.message, /TASK_MEMORY_WRITE_FAILED.*snapshot 0 unavailable/);
   assert.match((await independent).error.message, /TASK_MEMORY_WRITE_FAILED/);
   assert.match((await otherAgent).error.message, /TASK_MEMORY_WRITE_FAILED/);
   assert.equal(followUp.entries.find(e => e.agentId === scope.agentId && e.key === 'version').position.x, 2);
   assert.equal(flushed, false);
   gate.slots[1].release.resolve();
   const last = await gate.slots[2].started.promise;
   const secondOutcome = await second;
   assert.equal(secondOutcome.entry.position.x, 2);
   assert.deepEqual((await new TaskMemoryStore({ directory }).query(scope)).entries.find(e => e.key === 'version'), secondOutcome.entry);
   assert.equal(last.entries.find(e => e.agentId === scope.agentId && e.key === 'version').position.x, 3);
   assert.equal(flushed, false, 'flush includes the final queued authored snapshot');
   gate.slots[2].release.resolve();
   const thirdOutcome = await third;
   await flushing;
   assert.equal(flushed, true);
   assert.deepEqual((await new TaskMemoryStore({ directory }).query(scope)).entries.find(e => e.key === 'version'), thirdOutcome.entry);
  } finally {
   gate.releaseAll();
   await Promise.all([first, second, third, independent, otherAgent]);
   await flushing?.catch(() => {});
   await store.flush().catch(() => {});
   await rm(directory, { recursive: true, force: true });
  }
 });
 test('capacity eviction waits until the retired authored version reaches its own snapshot', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'task-memory-retired-fence-'));
  const store = new TaskMemoryStore({ directory });
  await Promise.all(Array.from({ length: 256 }, (_, i) => store.remember(scope, place(`entry-${i}`, i))));
  await store.flush();
  const gate = holdSnapshots(t, 2);
  const retiring = store.remember(scope, { ...place('entry-0', 0), status: 'retired' });
  const replacing = store.remember(scope, place('replacement', 300));
  try {
   const first = await gate.slots[0].started.promise;
   assert.equal(first.entries.find(e => e.key === 'entry-0')?.status, 'retired');
   assert.equal(first.entries.some(e => e.key === 'replacement'), false);
   gate.slots[0].release.resolve();
   const retired = await retiring;
   await gate.slots[1].started.promise;
   assert.deepEqual((await new TaskMemoryStore({ directory }).query(scope, { text: 'entry-0' })).entries, [retired]);
   gate.slots[1].release.resolve();
   const replacement = await replacing;
   await store.flush();
   const loaded = new TaskMemoryStore({ directory });
   assert.equal((await loaded.query(scope)).total, 256);
   assert.equal((await loaded.query(scope, { text: 'entry-0' })).total, 0);
   assert.deepEqual((await loaded.query(scope, { text: 'replacement' })).entries, [replacement]);
  } finally {
   gate.releaseAll();
   await Promise.allSettled([retiring, replacing]);
   await store.flush().catch(() => {});
   await rm(directory, { recursive: true, force: true });
  }
 });
 test('directory null keeps intentional in-memory behavior for concurrent authored versions', async () => {
  const store = new TaskMemoryStore({ directory: null });
  const entries = await Promise.all([1, 2, 3].map(x => store.remember(scope, place('version', x))));
  assert.deepEqual(entries.map(e => e.position.x), [1, 2, 3]);
  await store.flush();
  assert.deepEqual((await store.query(scope)).entries, [entries[2]]);
  assert.equal((await new TaskMemoryStore({ directory: null }).query(scope)).total, 0);
 });
}
