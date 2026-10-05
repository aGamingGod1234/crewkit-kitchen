import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { TaskMemoryStore } from '../src/task-memory-store.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';

const record = { agentId: 'agent-a', goalRevision: 1, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const provenance = { provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort, serviceTier: record.serviceTier,
	goalRevision: 1, programId: 'program-1', programVersion: 1, sourceStepId: 'step-4' };
const observation = { world: { worldId: 'world-one', dimension: 'minecraft:overworld' }, worldTick: 10 };
const dispatch = { actionId: 'session-one:action-1', actionType: 'wait', goalRevision: 1, arguments: { durationMs: 1 } };
function note(key = 'plan', text = 'Inspect the east bank.') { return { operation: 'write', arguments: { key, text }, provenance }; }

test('shared notes retain the selected author and support bounded pagination across modes and goal revisions', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	for (let index = 0; index < 5; index++) await memory.execute(record, note(`plan-${index}`));
	const nativePage = await memory.notebook.query(record.agentId, { worldId: memory.worldId(record), kind: 'notes', limit: 2 });
	assert.equal(nativePage.entries[0].provenance.model, record.model);
	assert.equal(nativePage.entries[0].provenance.sourceStepId, 'step-4');
	const later = { ...record, goalRevision: 2 };
	memory.observe(later, observation);
	const page = await memory.execute(later, { operation: 'query', arguments: { kind: 'notes', offset: nativePage.nextOffset, limit: 2 } });
	assert.equal(page.state, 'SUCCEEDED');
	assert.equal(page.total, 5);
	assert.deepEqual(page.entries.map((entry) => entry.key), ['plan-2', 'plan-1']);
	assert.equal(page.nextOffset, 4);
	assert.equal((await memory.notebook.query('another-agent', { worldId: 'world-one' })).total, 0);
});

test('note validation prevents profile spoofing, overlong content and hostile accessors before storage', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	await assert.rejects(memory.execute(record, { ...note(), provenance: { ...provenance, model: 'another-model' } }), { code: 'INVALID_MEMORY_PROVENANCE' });
	await assert.rejects(memory.execute(record, { ...note(), provenance: { ...provenance, goalRevision: 2 } }), { code: 'INVALID_MEMORY_PROVENANCE' });
	await assert.rejects(memory.execute(record, note('k'.repeat(129))));
	await assert.rejects(memory.execute(record, note('plan', 't'.repeat(2049))));
	await assert.rejects(memory.execute(record, { operation: 'query', arguments: { offset: -1 } }));
	let reads = 0;
	const unsafe = Object.defineProperty({}, 'provider', { enumerable: true, get() { reads++; return 'codex'; } });
	await assert.rejects(memory.execute(record, { ...note(), provenance: unsafe }));
	assert.equal(reads, 0);
	assert.equal((await memory.notebook.query(record.agentId, { worldId: 'world-one' })).total, 0);
	await memory.execute(record, note('max', 't'.repeat(2048)));
});

test('unknown worlds use unique runtime namespaces and stale or forgotten contexts cannot access notes', async () => {
	const notebook = new ModelNotebook();
	const first = new RuntimeMemoryContext({ notebook });
	const second = new RuntimeMemoryContext({ notebook });
	await assert.rejects(first.execute(record, note()), { code: 'WORLD_ID_REQUIRED' });
	first.observe(record, {}); second.observe(record, {});
	assert.notEqual(first.worldId(record), second.worldId(record));
	await first.execute(record, note());
	assert.equal((await second.execute(record, { operation: 'query', arguments: {} })).total, 0);
	assert.equal(first.worldId({ ...record, goalRevision: 2 }), null);
	first.forget(record.agentId);
	assert.equal(first.worldId(record), null);
});

test('receipts capture the dispatch world, remain unknown after disconnect, and accept only matching terminal evidence', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	assert.equal((await memory.recordDispatch(record, dispatch)).state, 'DISPATCHED');
	assert.equal((await memory.markUnknown(record.agentId, 'BRIDGE_DISCONNECTED'))[0].state, 'UNKNOWN');
	const unresolved = await memory.unresolved(record);
	assert.equal(unresolved.entries[0].actionId, dispatch.actionId);
	assert.deepEqual(unresolved.entries[0].arguments, dispatch.arguments);
	assert.equal(unresolved.entries[0].source, 'coordinator_uncertain');
	assert.equal(await memory.recordResult(record, { ...dispatch, actionId: 'unseen', state: 'SUCCEEDED', reasonCode: '' }), false);
	assert.equal(await memory.recordResult(record, { ...dispatch, goalRevision: 9, state: 'SUCCEEDED', reasonCode: '' }), false);
	assert.equal(await memory.recordResult(record, { ...dispatch, state: 'RUNNING', reasonCode: '' }), false);
	const later = { ...record, goalRevision: 2 };
	memory.observe(later, { world: { worldId: 'world-two' } });
	const terminal = { actionId: dispatch.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, physicalAttempted: false, actionObservation: { worldTick: 20 } };
	assert.equal(await memory.recordResult(later, terminal), true);
	const first = await memory.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId });
	assert.equal(first.worldId, 'world-one');
	assert.equal(first.source, 'server_action_result');
	assert.equal(first.tick, 20);
	assert.equal(first.executionStarted, true);
	assert.equal(first.physicalAttempted, false);
	assert.equal(await memory.recordResult(later, terminal), true);
	assert.deepEqual(await memory.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId }), first);
	assert.equal((await memory.execute(later, { operation: 'query', arguments: { kind: 'receipts' } })).total, 0);
});

test('an authoritative terminal replay reconciles a persisted dispatch after coordinator restart', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'runtime-memory-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const first = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	first.observe(record, observation);
	await first.recordDispatch(record, dispatch);
	await first.markUnknown(undefined, 'COORDINATOR_STOPPED');
	const restored = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	assert.notEqual(restored.sessionId, first.sessionId);
	assert.equal(await restored.recordResult(record, { actionId: dispatch.actionId, goalRevision: 1, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }), true);
	const result = await restored.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId });
	assert.equal(result.state, 'CANCELLED');
	assert.equal(result.source, 'server_action_result');
	assert.equal(result.worldId, 'world-one');
});

test('persisted unknown receipts release dispatch capacity and retained receipts accept late results', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	for (let index = 0; index < 300; index++) {
		const action = { ...dispatch, actionId: `lost-action-${index}` };
		await memory.recordDispatch(record, action);
		const unknown = await memory.markUnknown(record.agentId);
		assert.equal(unknown.length, 1);
		assert.equal(unknown[0].actionId, action.actionId);
		assert.equal(unknown[0].state, 'UNKNOWN');
	}
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
	const unresolved = await memory.unresolved(record);
	assert.equal(unresolved.total, 300);
	assert.equal(unresolved.evictedReceipts, 172);
	assert.equal(await memory.recordResult(record, { actionId: 'lost-action-299', goalRevision: 1,
		state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await memory.notebook.findReceipt(record.agentId, { actionId: 'lost-action-299' })).source, 'server_action_result');
	assert.equal((await memory.unresolved(record)).total, 299);
});

test('failed unknown persistence retains the dispatch for a subsequent retry', async () => {
	const notebook = new ModelNotebook();
	const persistUnknown = notebook.recordUnknown.bind(notebook);
	let fail = true;
	notebook.recordUnknown = (...args) => fail ? Promise.reject(new Error('disk unavailable')) : persistUnknown(...args);
	const memory = new RuntimeMemoryContext({ notebook });
	memory.observe(record, observation);
	await memory.recordDispatch(record, dispatch);
	await assert.rejects(memory.markUnknown(record.agentId), /disk unavailable/);
	assert.equal((await notebook.findReceipt(record.agentId, { actionId: dispatch.actionId })).state, 'DISPATCHED');
	await assert.rejects(memory.recordDispatch(record, { ...dispatch, arguments: { durationMs: 2 } }), { code: 'RECEIPT_CONFLICT' });
	fail = false;
	assert.equal((await memory.markUnknown(record.agentId))[0].state, 'UNKNOWN');
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
});

test('a terminal result arriving during unknown persistence remains authoritative', async () => {
	const notebook = new ModelNotebook();
	const persistUnknown = notebook.recordUnknown.bind(notebook);
	const entered = Promise.withResolvers();
	const release = Promise.withResolvers();
	notebook.recordUnknown = async (...args) => {
		entered.resolve();
		await release.promise;
		return persistUnknown(...args);
	};
	const memory = new RuntimeMemoryContext({ notebook });
	memory.observe(record, observation);
	await memory.recordDispatch(record, dispatch);
	const unknown = memory.markUnknown(record.agentId);
	await entered.promise;
	assert.equal(await memory.recordResult(record, { ...dispatch, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	release.resolve();
	assert.equal((await unknown)[0].state, 'SUCCEEDED');
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
	assert.equal((await notebook.findReceipt(record.agentId, { actionId: dispatch.actionId })).source, 'server_action_result');
});

const taskEntry = (key) => ({ kind: 'lesson', key, label: key, summary: 'Recheck clearance before reusing the route.' });
const taskRequest = (key) => ({ operation: 'task', arguments: { operation: 'remember', entry: taskEntry(key) } });
const taskScope = { agentId: record.agentId, goalRevision: record.goalRevision, ...observation.world };

test('task remember acknowledges only after the note can be reloaded from disk', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'runtime-task-ack-'));
	const memory = new RuntimeMemoryContext({ taskMemory: new TaskMemoryStore({ directory }) });
	const started = Promise.withResolvers(), release = Promise.withResolvers();
	const originalWrite = AtomicAgentStore.prototype.write;
	t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, value) {
		const snapshot = structuredClone(value);
		started.resolve();
		await release.promise;
		return originalWrite.call(this, key, snapshot);
	});
	memory.observe(record, observation);
	let acknowledged = false;
	const remembering = memory.execute(record, taskRequest('durable-note')).then((result) => { acknowledged = true; return result; });
	try {
		await started.promise;
		assert.equal(acknowledged, false, 'pending disk I/O must not produce TASK_MEMORY_WRITTEN');
		release.resolve();
		const result = await remembering;
		assert.equal(result.state, 'SUCCEEDED');
		assert.equal(result.reasonCode, 'TASK_MEMORY_WRITTEN');
		const restored = new TaskMemoryStore({ directory });
		assert.deepEqual((await restored.query(taskScope)).entries, [result.entry]);
	} finally {
		release.resolve();
		await remembering;
		await memory.flush();
		await rm(directory, { recursive: true, force: true });
	}
});

test('task remember rejects failed persistence and an explicit retry becomes reloadable', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'runtime-task-failure-'));
	const memory = new RuntimeMemoryContext({ taskMemory: new TaskMemoryStore({ directory }) });
	const originalWrite = AtomicAgentStore.prototype.write;
	let fail = true;
	t.mock.method(AtomicAgentStore.prototype, 'write', function (...args) {
		return fail ? Promise.reject(new Error('storage unavailable')) : originalWrite.apply(this, args);
	});
	memory.observe(record, observation);
	try {
		await assert.rejects(memory.execute(record, taskRequest('retry-note')), /TASK_MEMORY_WRITE_FAILED.*storage unavailable/);
		assert.equal((await new TaskMemoryStore({ directory }).query(taskScope)).total, 0);
		fail = false;
		const result = await memory.execute(record, taskRequest('retry-note'));
		assert.equal(result.reasonCode, 'TASK_MEMORY_WRITTEN');
		assert.deepEqual((await new TaskMemoryStore({ directory }).query(taskScope)).entries, [result.entry]);
	} finally {
		fail = false;
		await memory.flush().catch(() => {});
		await rm(directory, { recursive: true, force: true });
	}
});

// Snapshot fences are exercised through the real runtime entry point.
{
const scope = { agentId: 'review-a', goalRevision: 1, worldId: 'review-world', dimension: 'minecraft:overworld' };
const record = { agentId: scope.agentId, goalRevision: 1 };
const other = { agentId: 'review-b', goalRevision: 1 };
const entry = (summary = 'authored note') => ({ kind: 'lesson', key: 'review-note', label: 'Review note', summary });
const request = (summary) => ({ operation: 'task', arguments: { operation: 'remember', entry: entry(summary) } });
const observation = (x) => ({ world: { worldId: scope.worldId, dimension: scope.dimension }, ...(x === undefined ? { ready: false } : { position: { x, y: 64, z: 0 } }) });
const turn = () => new Promise(setImmediate);

// A finite snapshot gate preserves AtomicAgentStore's snapshot-at-call contract.
// Every successful write still uses its real file.sync + rename implementation.
function gates(t, count, failedWrite = -1) {
  const slots = Array.from({ length: count }, () => ({ started: Promise.withResolvers(), release: Promise.withResolvers(), finished: Promise.withResolvers() }));
  const snapshots = [];
  const original = AtomicAgentStore.prototype.write;
  t.mock.method(AtomicAgentStore.prototype, 'write', async function (key, value) {
    const index = snapshots.length;
    const snapshot = structuredClone(value);
    snapshots.push(snapshot);
    assert.ok(index < count, 'unexpected extra write exceeds finite probe schedule');
    const slot = slots[index];
    slot.started.resolve(snapshot);
    await slot.release.promise;
    if (index === failedWrite) {
      slot.finished.resolve({ failed: true });
      throw new Error(`review injected write ${index} failure`);
    }
    await original.call(this, key, snapshot);
    slot.finished.resolve({ durable: true });
  });
  return { slots, snapshots, releaseAll() { for (const s of slots) s.release.resolve(); } };
}
function track(promise) {
  const result = { settled: false };
  result.done = promise.then(value => { result.settled = true; return { value }; }, error => { result.settled = true; return { error: error.message }; });
  return result;
}
async function setup(t, label) {
  const directory = await mkdtemp(join(tmpdir(), `runtime-memory-fence-${label}-`));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const store = new TaskMemoryStore({ directory });
  const memory = new RuntimeMemoryContext({ taskMemory: store });
  memory.observe(record, observation());
  await memory.taskContext(record);
  return { directory, store, memory };
}
async function diskEntries(directory) { return (await new TaskMemoryStore({ directory }).query(scope, { kind: 'lesson' })).entries; }

test('a note must finish after its required follow-up snapshot despite continued observations', async t => {
  const { directory, memory } = await setup(t, 'continuous');
  const gate = gates(t, 5);
  memory.observe(record, observation(0));
  await gate.slots[0].started.promise;
  const note = track(memory.execute(record, request()));
  const durableButPending = [];
  try {
    await turn();
    assert.equal(note.settled, false);
    gate.slots[0].release.resolve();
    await gate.slots[1].started.promise;
    assert.equal((await diskEntries(directory)).length, 0, 'initial observation snapshot cannot persist the later note');
    assert.equal(note.settled, false, 'required follow-up remains blocked');
    for (let i = 1; i <= 3; i++) {
      const observedRecord = i === 2 ? record : other;
      memory.observe(observedRecord, observation(i * 4 - 2));
      memory.observe(observedRecord, observation(i * 4));
      await memory.taskContext(observedRecord);
      assert.equal(gate.snapshots.length, i + 1, 'ingestion finishes during blocked disk I/O');
      gate.slots[i].release.resolve();
      await gate.slots[i + 1].started.promise;
      const saved = await diskEntries(directory);
      assert.equal(saved[0]?.summary, entry().summary, 'authored note is already durable');
      durableButPending.push({ completedSnapshot: i, pendingSnapshot: i + 1, settled: note.settled, observedAgent: observedRecord.agentId });
    }
    gate.slots[4].release.resolve();
    const outcome = await note.done;
    assert.equal(outcome.value?.reasonCode, 'TASK_MEMORY_WRITTEN');
    await memory.flush();
    assert.equal(gate.snapshots.length, 5, 'six overlapping observations form only three follow-up snapshots');
    const loaded = new TaskMemoryStore({ directory });
    assert.deepEqual((await loaded.query(scope, { kind: 'trail' })).entries[0].waypoints.map(p => p.x), [0, 6, 8]);
    assert.deepEqual((await loaded.query({ ...scope, agentId: other.agentId }, { kind: 'trail' })).entries[0].waypoints.map(p => p.x), [2, 4, 10, 12]);
    assert.ok(durableButPending.every(v => v.settled), 'note waited for unrelated later snapshots after its required snapshot was durable');
  } finally {
    gate.releaseAll();
    await note.done;
    await memory.flush().catch(() => {});
  }
});

test('each TASK_MEMORY_WRITTEN must correspond to an authored version that was persisted', async t => {
  const { directory, memory } = await setup(t, 'same-key');
  const gate = gates(t, 2);
  const first = track(memory.execute(record, request('first authored version')));
  const second = track(memory.execute(record, request('second authored version')));
  try {
    await gate.slots[0].started.promise;
    assert.equal(first.settled, false);
    assert.equal(second.settled, false);
    gate.slots[0].release.resolve();
    const firstOutcome = await first.done;
    assert.equal(firstOutcome.value?.reasonCode, 'TASK_MEMORY_WRITTEN');
    assert.deepEqual(await diskEntries(directory), [firstOutcome.value.entry], 'first acknowledged version must really reach disk');
    await gate.slots[1].started.promise;
    assert.equal(second.settled, false, 'second version needs its own successful snapshot');
    gate.slots[1].release.resolve();
    const outcomes = await Promise.all([first.done, second.done]);
    await memory.flush();
    assert.ok(outcomes.every(o => o.value?.reasonCode === 'TASK_MEMORY_WRITTEN'));
    const saved = await diskEntries(directory);
    const persistedVersions = gate.snapshots.flatMap(s => s.entries.map(e => e.summary));
    assert.equal(saved[0]?.summary, 'second authored version');
    assert.ok(persistedVersions.includes('first authored version'), 'first call reports TASK_MEMORY_WRITTEN although its authored version was never in a disk snapshot');
  } finally {
    gate.releaseAll();
    await Promise.all([first.done, second.done]);
    await memory.flush().catch(() => {});
  }
});

test('an unrelated later observation failure must not reject an already persisted note', async t => {
  const { directory, memory } = await setup(t, 'later-failure');
  const gate = gates(t, 3, 1);
  const note = track(memory.execute(record, request()));
  try {
    await gate.slots[0].started.promise;
    memory.observe(other, observation(8));
    await memory.taskContext(other);
    gate.slots[0].release.resolve();
    await gate.slots[1].started.promise;
    const savedBeforeFailure = await diskEntries(directory);
    assert.equal(savedBeforeFailure[0]?.summary, entry().summary);
    const settledAfterDurability = note.settled;
    gate.slots[1].release.resolve();
    const outcome = await note.done;
    const savedAfterFailure = await diskEntries(directory);
    assert.deepEqual(savedAfterFailure, savedBeforeFailure);
    await assert.rejects(memory.flush(), /TASK_MEMORY_WRITE_FAILED.*write 1 failure/);
    const retry = track(memory.execute(record, request()));
    await gate.slots[2].started.promise;
    gate.slots[2].release.resolve();
    const retryOutcome = await retry.done;
    assert.equal(retryOutcome.value?.reasonCode, 'TASK_MEMORY_WRITTEN');
    await memory.flush();
    assert.deepEqual(await diskEntries(directory), [retryOutcome.value.entry]);
    assert.equal(outcome.value?.reasonCode, 'TASK_MEMORY_WRITTEN', 'later observation failure was attributed to the already durable authored note');
  } finally {
    gate.releaseAll();
    await note.done;
    await memory.flush().catch(() => {});
  }
});
}
