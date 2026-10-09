import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { mkdtemp, writeFile, readFile, readdir, unlink, rmdir } from 'node:fs/promises';
import { resolve, join, relative, isAbsolute } from 'node:path';
import { TaskMemoryStore } from '../src/task-memory-store.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';

const temporaryRoot = tmpdir();
const world = { worldId: 'g04-world', dimension: 'minecraft:overworld' };
const scope = { ...world, agentId: 'agent-a', goalRevision: 1 };
const hash = value => createHash('sha256').update(value).digest('hex');
const key = hash(JSON.stringify([world.worldId, world.dimension]));
const note = (key, summary = key, extra = {}) => ({ kind: 'lesson', key, label: key, summary, ...extra });
const observation = { world, position: { x: 0, y: 64, z: 0 }, inventory: { items: [] } };
async function fixture(t) {
  const directory = await mkdtemp(join(temporaryRoot, 'task-memory-regression-'));
  t.after(async () => {
    const part = relative(temporaryRoot, resolve(directory));
    assert.ok(part && !part.startsWith('..') && !isAbsolute(part));
    for (const name of await readdir(directory)) await unlink(join(directory, name));
    await rmdir(directory);
  });
  return directory;
}

test('f038: repaired durable file retries in the same memory context and preserves its facts', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  await writeFile(join(directory, `task-world-${hash(key)}.json`), '{broken-json');
  const store = new TaskMemoryStore({ directory });
  const memory = new RuntimeMemoryContext({ taskMemory: store });
  const b = { ...scope, agentId: 'agent-b' };
  memory.observe(scope, observation); memory.observe(b, observation);
  const failures = await Promise.allSettled([memory.taskContext(scope), memory.taskContext(b)]);
  assert.ok(failures.every(result => result.status === 'rejected' && result.reason instanceof SyntaxError));
  assert.equal(failures[0].reason, failures[1].reason, 'concurrent loads share one failure');
  const saved = { version: 1, revision: 3, ...world, agents: {}, assets: [], entries: [{ ...note('durable', 'Existing fact', { shared: true }), agentId: scope.agentId, goalRevision: 1, source: 'model_authored', historical: true }] };
  await new AtomicAgentStore({ directory, namespace: 'task-world' }).write(key, saved);
  memory.observe(scope, observation); memory.observe(b, observation);
  assert.equal((await memory.taskContext(scope)).lessons[0].key, 'durable');
  assert.equal((await memory.taskContext(b)).lessons[0].key, 'durable');
  await memory.flush();
  assert.equal((await new TaskMemoryStore({ directory }).query(scope)).total, 1);
  assert.equal((await store.summary({ ...scope, dimension: 'minecraft:the_nether' })).totals.entries, 0);
  assert.equal((await new TaskMemoryStore().summary(scope)).totals.entries, 0);
});

test('f044: authored capacity is per owner across restart, with owner-only retirement and sharing', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  const store = new TaskMemoryStore({ directory });
  await Promise.all(Array.from({ length: 256 }, (_, i) => store.remember(scope, note(`a-${i}`))));
  await assert.rejects(store.remember(scope, note('a-overflow')), /TASK_MEMORY_FULL/);
  const b = { ...scope, agentId: 'agent-b' };
  assert.equal((await store.query(b)).total, 0);
  await store.remember(b, note('a-0', 'B owns its same-named key', { shared: true }));
  const loaded = new TaskMemoryStore({ directory });
  assert.equal((await loaded.query(b)).total, 1);
  assert.equal((await loaded.query(scope)).total, 257);
  assert.equal((await loaded.query(scope, { text: 'a-0' })).entries[0].summary, 'a-0');
  await loaded.remember(scope, note('a-0', 'retired by owner', { status: 'retired' }));
  await loaded.remember(scope, note('replacement'));
  assert.equal((await loaded.query(scope)).total, 257);
  assert.equal((await loaded.query(b)).total, 1);
  await loaded.flush();
});

test('f045: replacements enter every summary window while query pagination and goal preference remain stable', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  const store = new TaskMemoryStore({ directory });
  for (const [kind, field, width] of [['progress', 'progress', 2], ['route', 'routes', 4], ['place', 'places', 4], ['lesson', 'lessons', 3]]) {
    const entry = (i, summary = `old ${i}`) => ({ kind, key: `${kind}-${i}`, label: `${kind}-${i}`, summary,
      ...(kind === 'place' ? { position: { x: i, y: 64, z: 0 } } : {}),
      ...(kind === 'route' ? { from: 'base', to: 'mine', waypoints: [{ x: 0, y: 64, z: 0 }, { x: 2, y: 64, z: 0 }] } : {}) });
    for (let i = 0; i <= width; i++) await store.remember(scope, entry(i));
    await store.remember(scope, entry(0, 'fresh replacement'));
    assert.equal((await store.summary(scope))[field].at(-1).summary, 'fresh replacement');
    const loaded = new TaskMemoryStore({ directory });
    assert.equal((await loaded.summary(scope))[field].at(-1).summary, 'fresh replacement');
    assert.equal((await loaded.query(scope, { kind, limit: 1 })).entries[0].key, `${kind}-0`);
  }
  await store.remember({ ...scope, goalRevision: 2 }, { kind: 'progress', key: 'new-goal', label: 'new goal', summary: 'current' });
  await store.remember(scope, { kind: 'progress', key: 'progress-0', label: 'old goal', summary: 'later but old goal' });
  assert.equal((await store.summary({ ...scope, goalRevision: 2 })).progress.at(-1).key, 'new-goal');
  await store.flush();
});

test('f063: sixteen complete recovery histories and subsequent task notes survive real-disk restart', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  const store = new TaskMemoryStore({ directory });
  const memory = new RuntimeMemoryContext({ taskMemory: store });
  const scopes = Array.from({ length: 16 }, (_, i) => ({ ...scope, agentId: `history-${i}` }));
  const inventory = { items: Array.from({ length: 36 }, () => ({ itemId: 'minecraft:cobblestone', count: 64 })) };
  let tick = 1_000_000;
  for (const current of scopes) {
    for (let death = 0; death < 32; death++) {
      for (let waypoint = 0; waypoint < 128; waypoint++) memory.observe(current, {
        world: { ...world, gameTime: tick++ }, ready: true, position: { x: 12345.125 + waypoint * 2.125, y: 64, z: 6789.625 }, inventory });
      memory.observe(current, { world, ready: false, death: { x: 12615, y: 64, z: 6789.625, cause: 'fell from a high place', diedAtEpochMs: 1_700_000_000_000 + death } });
      await memory.taskContext(current);
    }
    await memory.flush();
  }
  await store.remember(scopes[0], note('first-after-history'));
  await store.remember(scopes[15], note('last-after-history'));
  const loaded = new TaskMemoryStore({ directory });
  for (const current of scopes) {
    const deaths = await loaded.query(current, { kind: 'deaths', limit: 64 });
    assert.equal(deaths.total, 32);
    assert.ok(deaths.entries.every(d => d.outboundTrail.length === 128 && d.lostInventory.length === 36 && d.omittedWaypoints === 0));
  }
  assert.equal((await loaded.query(scopes[0], { kind: 'lesson' })).entries[0].key, 'first-after-history');
  assert.equal((await loaded.query(scopes[15], { kind: 'lesson' })).entries[0].key, 'last-after-history');
  assert.equal((await loaded.query({ ...scopes[0], worldId: 'other-world' }, { kind: 'deaths' })).total, 0);
  const sizes = await Promise.all((await readdir(directory)).map(async name => (await readFile(join(directory, name))).length));
  assert.ok(sizes.reduce((sum, size) => sum + size, 0) > 4_194_304, 'full history exceeds the former aggregate budget');
  assert.ok(sizes.every(size => size <= 4_194_304), 'every atomic record retains the existing physical byte bound');
});

for (const failure of ['owner', 'manifest']) test(`migration preserves the legacy snapshot after ${failure} write failure and retries without losing history`, async t => {
  const directory = await fixture(t);
  const legacy = { version: 1, revision: 9, ...world, agents: {
    [scope.agentId]: { trail: [{ x: 0, y: 64, z: 0, tick: 1 }], omittedWaypoints: 0, deaths: [], lastLive: null },
  }, assets: [], entries: [
    { ...note('private'), agentId: scope.agentId, goalRevision: 1, source: 'model_authored', historical: true },
    { ...note('shared', 'shared evidence', { shared: true }), agentId: 'agent-b', goalRevision: 1, source: 'model_authored', historical: true },
  ] };
  const legacyDisk = new AtomicAgentStore({ directory, namespace: 'task-world' });
  await legacyDisk.write(key, legacy);
  const store = new TaskMemoryStore({ directory });
  assert.equal((await store.query(scope)).total, 2);
  let injected = false;
  const originalRename = fs.rename;
  const mocked = t.mock.method(fs, 'rename', async (from, to) => {
    if (String(to).startsWith(directory)) {
      const value = JSON.parse(await readFile(from, 'utf8'));
      if (!injected && (failure === 'manifest' ? value.version === 2 : value.agentId === 'agent-b')) {
        injected = true; throw Object.assign(new Error('fixture migration write failed'), { code: 'EIO' });
      }
    }
    return originalRename(from, to);
  });
  syncBuiltinESMExports();
  try {
    await assert.rejects(store.remember(scope, note('new')), /TASK_MEMORY_WRITE_FAILED/);
    assert.equal(injected, true);
    await assert.rejects(store.flush(), /TASK_MEMORY_WRITE_FAILED/);
    assert.deepEqual(await legacyDisk.read(key), legacy, 'legacy manifest is untouched until every partition commits');
    const duringFailure = new TaskMemoryStore({ directory });
    assert.deepEqual((await duringFailure.query(scope)).entries.map(e => e.key), ['private', 'shared']);
    assert.equal((await duringFailure.query(scope, { kind: 'trail' })).entries[0].waypoints.length, 1);
  } finally {
    mocked.mock.restore(); syncBuiltinESMExports();
  }
  await store.remember(scope, note('retry'));
  await store.flush();
  const loaded = new TaskMemoryStore({ directory });
  assert.deepEqual((await loaded.query(scope)).entries.map(e => e.key), ['private', 'shared', 'new', 'retry']);
  assert.deepEqual((await loaded.query({ ...scope, agentId: 'agent-b' })).entries.map(e => e.key), ['shared']);
  assert.equal((await loaded.query(scope, { kind: 'trail' })).entries[0].waypoints.length, 1);
  const manifest = JSON.parse(await readFile(join(directory, `task-world-${hash(key)}.json`), 'utf8'));
  assert.equal(manifest.version, 2);
  assert.equal(manifest.owners.length, 2);
  assert.equal((await readdir(directory)).length, 3, 'stable owner files create no per-snapshot garbage');
});

test('missing owner records fail visibly, then retry after repair without resetting the world', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  const store = new TaskMemoryStore({ directory });
  await store.remember(scope, note('retained'));
  const ownerPath = join(directory, `task-world-${hash(`part:${hash(JSON.stringify([key, scope.agentId]))}`)}.json`);
  const bytes = await readFile(ownerPath);
  await unlink(ownerPath);
  const loaded = new TaskMemoryStore({ directory });
  await assert.rejects(loaded.summary(scope), /INVALID_TASK_MEMORY/);
  await writeFile(ownerPath, bytes);
  assert.equal((await loaded.summary(scope)).lessons[0].key, 'retained');
});

test('native entry admits a new owner at foreign capacity and serializes a refreshed replacement summary', { timeout: 180_000 }, async t => {
  const directory = await fixture(t);
  const store = new TaskMemoryStore({ directory });
  await Promise.all(Array.from({ length: 256 }, (_, i) => store.remember(scope, note(`a-${i}`))));
  const record = { agentId: 'native-b', goalRevision: 1, provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'priority' };
  const memory = new RuntimeMemoryContext({ taskMemory: store });
  let bridgeCalls = 0, call = 0;
  const runtime = new NativeToolRuntime({ registry: { get: () => record },
    bridge: { send: async () => { bridgeCalls++; throw new Error('Unexpected bridge call'); } },
    memoryObservation: (r, o) => memory.observe(r, o), memoryOperation: (r, op) => memory.execute(r, op), taskContext: r => memory.taskContext(r) });
  t.after(async () => { await runtime.dispose(record.agentId); await memory.flush(); });
  runtime.updateObservation(record, { ...observation, ready: true, player: { x: 0, y: 64, z: 0, health: 20 } }, { eventSequence: 1 });
  const execute = (name, argumentsValue) => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'g04-native', callId: String(++call), tool: normalizeMinecraftToolCall(name, argumentsValue) }, record);
  for (let i = 0; i < 4; i++) assert.equal((await execute('taskMemory', { operation: 'remember', entry: note(`b-${i}`) })).reasonCode, 'TASK_MEMORY_WRITTEN');
  await execute('taskMemory', { operation: 'remember', entry: note('b-0', 'fresh native replacement') });
  assert.equal(memory.peekTaskContext(record).lessons.at(-1).summary, 'fresh native replacement');
  const observed = await execute('observe', {});
  assert.equal(observed.taskMemory.lessons.at(-1).summary, 'fresh native replacement');
  const input = buildNativeEventInput(record, { event: 'respawn', observation: observed.observation, taskMemory: observed.taskMemory });
  assert.equal(JSON.parse(input.slice(input.indexOf('\n') + 1)).taskMemory.lessons.at(-1).summary, 'fresh native replacement');
  assert.equal((await execute('taskMemory', { operation: 'query', query: { limit: 1 } })).entries[0].key, 'b-0');
  assert.equal((await new TaskMemoryStore({ directory }).summary({ ...record, ...world })).lessons.at(-1).summary, 'fresh native replacement');
  assert.equal(bridgeCalls, 0);
});
