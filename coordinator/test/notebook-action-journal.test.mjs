import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, writeFile, rm, open } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';

const identity = (actionId = 'action-1', worldId = 'world-one') => ({ worldId, actionId, actionType: 'wait', goalRevision: 1, arguments: { durationMs: 1 } });
const terminal = (actionId = 'action-1', worldId = 'world-one') => ({ ...identity(actionId, worldId), state: 'SUCCEEDED', reasonCode: '', executionStarted: true, physicalAttempted: false });
const query = { worldId: 'world-one', limit: 64 };
const record = { agentId: 'a', goalRevision: 1 };
const turn = () => new Promise(setImmediate);
async function fixture(t) {
	const directory = await mkdtemp(join(tmpdir(), 'action-journal-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	return directory;
}
async function logPath(directory) { return join(directory, (await readdir(directory)).find(name => name.endsWith('.jsonl'))); }

test('action deltas preserve exact durable dispatch and terminal evidence without rewriting historical notes', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	await notebook.writeNote('a', { worldId: 'world-one', key: 'source', text: 'x'.repeat(2048) });
	const checkpointPath = join(directory, (await readdir(directory))[0]);
	const checkpoint = await readFile(checkpointPath);
	const dispatched = await notebook.recordDispatch('a', identity());
	assert.deepEqual(await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'action-1' }), dispatched);
	const receipt = await notebook.recordReceipt('a', { ...terminal(), actionObservation: { worldTick: 42, position: { x: 1, y: 64, z: 2 } } });
	const restored = new ModelNotebook({ directory });
	assert.deepEqual(await restored.findReceipt('a', { actionId: 'action-1' }), receipt);
	assert.deepEqual(await readFile(checkpointPath), checkpoint);
	const log = await readFile(await logPath(directory), 'utf8');
	assert.doesNotMatch(log, /x{100}/, 'unchanged note text never appears on the action path');
	assert.equal(log.trim().split('\n').length, 2);
	assert.equal((await restored.listUnresolved('a', query)).total, 0);
	await assert.rejects(restored.recordReceipt('a', { ...terminal(), state: 'FAILED' }), /RECEIPT_CONFLICT/);
	await assert.rejects(restored.recordDispatch('a', { ...identity(), goalRevision: 2 }), /RECEIPT_CONFLICT/);
	assert.equal(await readFile(await logPath(directory), 'utf8'), log);
});

test('legacy v1 checkpoints migrate lazily and bounded compaction survives a crash before log truncation', async t => {
	const directory = await fixture(t);
	const disk = new AtomicAgentStore({ directory, namespace: 'notebook' });
	const legacy = { version: 1, revision: 2, notes: [{ kind: 'note', source: 'model_authored', worldId: 'world-one', key: 'legacy', text: 'Saved source.', revision: 1 }],
		receipts: [{ ...identity('legacy-action'), kind: 'receipt', source: 'coordinator_dispatch', state: 'DISPATCHED', reasonCode: 'AWAITING_AUTHORITATIVE_RESULT', revision: 2 }], evictedReceipts: 0, evictedNotes: 0 };
	await disk.write('a', legacy);
	const notebook = new ModelNotebook({ directory, maximumReceipts: 4 });
	await notebook.recordReceipt('a', terminal('legacy-action'));
	assert.equal((await disk.read('a')).revision, 2, 'first action does not rewrite the legacy snapshot');
	for (let i = 0; i < 127; i++) await notebook.recordReceipt('a', terminal(`result-${i}`));
	const path = await logPath(directory), oldLog = await readFile(path);
	assert.equal(oldLog.toString().trim().split('\n').length, 128);
	await notebook.recordDispatch('a', identity('after-checkpoint'));
	const newLog = await readFile(path);
	assert.equal(newLog.toString().trim().split('\n').length, 1);
	assert.equal((await disk.read('a')).revision, 130);
	// Recreate the crash window: new checkpoint exists, old pre-checkpoint log
	// records are still present. Recovery must skip them, not reapply evictions.
	await writeFile(path, Buffer.concat([oldLog, newLog]));
	const restored = new ModelNotebook({ directory, maximumReceipts: 4 });
	assert.deepEqual(await restored.query('a', query), await notebook.query('a', query));
	assert.equal((await restored.findNote('a', { worldId: 'world-one', key: 'legacy' })).text, 'Saved source.');
	await restored.recordReceipt('a', terminal('after-checkpoint'));
	assert.equal((await new ModelNotebook({ directory, maximumReceipts: 4 }).listUnresolved('a', query)).total, 0);
});

test('failed checkpoint publication leaves the old journal replayable and the new action uncommitted', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	for (let i = 0; i < 128; i++) await notebook.recordReceipt('a', terminal(`result-${i}`));
	const path = await logPath(directory), originalLog = await readFile(path);
	const write = AtomicAgentStore.prototype.write;
	let fail = true;
	t.mock.method(AtomicAgentStore.prototype, 'write', function (...args) {
		if (fail) return Promise.reject(new Error('checkpoint unavailable'));
		return write.apply(this, args);
	});
	await assert.rejects(notebook.recordDispatch('a', identity()), /checkpoint unavailable/);
	assert.deepEqual(await readFile(path), originalLog);
	assert.equal(await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'action-1' }), null);
	fail = false;
	await notebook.recordDispatch('a', identity());
	assert.equal((await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'action-1' })).state, 'DISPATCHED');
});

test('large action arguments trigger the byte checkpoint bound before the record count bound', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory, maximumReceipts: 8 });
	for (let i = 0; i < 24; i++) {
		const action = { worldId: 'world-one', actionId: `book-${i}`, actionType: 'edit_book', arguments: { slot: 0, pages: Array(24).fill('x'.repeat(1024)), expectedFingerprint: `book-${i}` } };
		await notebook.recordDispatch('a', action);
		await notebook.recordReceipt('a', { ...action, state: 'SUCCEEDED', reasonCode: '' });
	}
	const snapshot = await new AtomicAgentStore({ directory, namespace: 'notebook' }).read('a');
	assert.ok(snapshot.revision > 0 && snapshot.revision < 48);
	assert.ok((await readFile(await logPath(directory))).length <= 1_048_576);
	assert.deepEqual(await new ModelNotebook({ directory, maximumReceipts: 8 }).query('a', query), await notebook.query('a', query));
});

test('every tested torn final-record boundary recovers the dispatch and allows authoritative replay', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	const dispatch = await notebook.recordDispatch('a', identity());
	const path = await logPath(directory), prefix = await readFile(path);
	await notebook.recordReceipt('a', terminal());
	const complete = await readFile(path), tail = complete.subarray(prefix.length);
	for (const cut of [1, 63, 65, Math.floor(tail.length / 2), tail.length - 1]) {
		await writeFile(path, Buffer.concat([prefix, tail.subarray(0, cut)]));
		const restored = new ModelNotebook({ directory });
		assert.deepEqual(await restored.findReceipt('a', { actionId: 'action-1' }), dispatch);
		assert.deepEqual(await readFile(path), prefix, 'torn bytes must be removed before the next append');
		await restored.recordReceipt('a', terminal());
		assert.equal((await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'action-1' })).state, 'SUCCEEDED');
	}
});

test('complete-record corruption and missing revisions fail closed rather than silently dropping results', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	await notebook.recordDispatch('a', identity());
	await notebook.recordReceipt('a', terminal());
	const path = await logPath(directory), original = await readFile(path, 'utf8');
	const corrupt = original.replace('SUCCEEDED', 'CANCELLED');
	await writeFile(path, corrupt);
	await assert.rejects(new ModelNotebook({ directory }).query('a', query), /INVALID_ACTION_JOURNAL/);
	assert.equal(await readFile(path, 'utf8'), corrupt);
	await writeFile(path, original.split('\n')[1] + '\n');
	await assert.rejects(new ModelNotebook({ directory }).query('a', query), /REVISION_GAP/);
});

test('dispatch publication and terminal ACK eligibility await the actual file sync; failed sync is retryable', async t => {
	const directory = await fixture(t);
	const sample = await open(join(directory, 'probe'), 'w');
	const prototype = Object.getPrototypeOf(sample), sync = prototype.sync;
	await sample.close();
	let gate = null, fail = false;
	t.mock.method(prototype, 'sync', async function (...args) {
		if (gate !== null) { const current = gate; gate = null; current.entered.resolve(); await current.release.promise; }
		if (fail) { fail = false; throw new Error('injected sync failure'); }
		return sync.apply(this, args);
	});
	const memory = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	memory.observe(record, { world: { worldId: 'world-one' } });
	for (const operation of ['dispatch', 'terminal']) {
		const blocked = { entered: Promise.withResolvers(), release: Promise.withResolvers() };
		gate = blocked;
		let settled = false;
		const pending = (operation === 'dispatch' ? memory.recordDispatch(record, identity()) : memory.recordResult(record, terminal())).then(result => { settled = true; return result; });
		await blocked.entered.promise;
		await turn();
		assert.equal(settled, false, `${operation} must not release its caller before fsync`);
		blocked.release.resolve();
		await pending;
		assert.equal((await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'action-1' })).state, operation === 'dispatch' ? 'DISPATCHED' : 'SUCCEEDED');
	}
	await memory.recordDispatch(record, identity('retry'));
	fail = true;
	await assert.rejects(memory.recordResult(record, terminal('retry')), /injected sync failure/);
	assert.equal((await new ModelNotebook({ directory }).findReceipt('a', { actionId: 'retry' })).state, 'DISPATCHED');
	assert.equal(await memory.recordResult(record, terminal('retry')), true);
	assert.equal(await memory.recordResult(record, terminal('unmapped')), false, 'unknown actions are not ACK eligible');
});

test('partial append failure rolls back before retry and rollback failure poisons the owner', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	await notebook.recordDispatch('a', identity());
	const path = await logPath(directory), prefix = await readFile(path);
	const sample = await open(path, 'a'), prototype = Object.getPrototypeOf(sample);
	const write = prototype.writeFile, truncate = prototype.truncate;
	await sample.close();
	let failWrite = true, failRollback = false;
	t.mock.method(prototype, 'writeFile', async function (bytes, ...args) {
		if (!failWrite) return write.call(this, bytes, ...args);
		failWrite = false;
		await write.call(this, bytes.subarray(0, 100), ...args);
		throw new Error('partial append');
	});
	t.mock.method(prototype, 'truncate', function (...args) {
		if (failRollback) return Promise.reject(new Error('rollback unavailable'));
		return truncate.apply(this, args);
	});
	await assert.rejects(notebook.recordReceipt('a', terminal()), /partial append/);
	assert.deepEqual(await readFile(path), prefix);
	await notebook.recordReceipt('a', terminal());
	failWrite = true; failRollback = true;
	await assert.rejects(notebook.recordDispatch('a', identity('poisoned')), /partial append/);
	await assert.rejects(notebook.recordDispatch('a', identity('poisoned')), /RELOAD_REQUIRED/);
	failRollback = false;
	const restored = new ModelNotebook({ directory });
	assert.equal(await restored.findReceipt('a', { actionId: 'poisoned' }), null);
	await restored.recordDispatch('a', identity('poisoned'));
});

test('complete but unsynced recovery must sync successfully before an idempotent terminal retry can ACK', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	await notebook.recordDispatch('a', identity());
	const path = await logPath(directory), sample = await open(path, 'r+');
	const prototype = Object.getPrototypeOf(sample), sync = prototype.sync, truncate = prototype.truncate;
	await sample.close();
	let broken = true;
	t.mock.method(prototype, 'sync', function (...args) {
		return broken ? Promise.reject(new Error('sync unavailable')) : sync.apply(this, args);
	});
	t.mock.method(prototype, 'truncate', function (...args) {
		return broken ? Promise.reject(new Error('rollback unavailable')) : truncate.apply(this, args);
	});
	await assert.rejects(notebook.recordReceipt('a', terminal()), /sync unavailable/);
	assert.match(await readFile(path, 'utf8'), /SUCCEEDED/, 'a complete append may exist despite failed durability');
	const restored = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	await assert.rejects(restored.recordResult(record, terminal()), /unavailable/);
	broken = false;
	assert.equal(await restored.recordResult(record, terminal()), true);
});

test('history eviction, retention migration and note clearing cannot discard recovery mappings', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory, maximumReceipts: 4 });
	await notebook.recordDispatch('a', identity('old-pending'));
	for (let i = 0; i < 3; i++) await notebook.recordReceipt('a', terminal(`complete-${i}`));
	const smaller = new ModelNotebook({ directory, maximumReceipts: 1 });
	assert.equal((await smaller.query('a', query)).evictedReceipts, 3);
	await smaller.recordDispatch('a', identity('new-pending'));
	await smaller.writeNote('a', { worldId: 'world-one', key: 'plan', text: 'Model source.' });
	await smaller.clear('a', { worldId: 'world-one' });
	const restored = new ModelNotebook({ directory, maximumReceipts: 1 });
	assert.equal((await restored.listUnresolved('a', query)).total, 2);
	assert.deepEqual(await restored.query('a', query), await smaller.query('a', query));
	await assert.rejects(restored.recordDispatch('a', { ...identity('old-pending'), arguments: { durationMs: 2 } }), /RECEIPT_CONFLICT/);
	const memory = new RuntimeMemoryContext({ notebook: restored });
	assert.equal(await memory.recordResult(record, terminal('old-pending')), true);
	assert.equal((await new ModelNotebook({ directory, maximumReceipts: 1 }).listUnresolved('a', query)).total, 1);
	assert.equal(await memory.recordResult(record, terminal('unmapped')), false);
});

test('recovery capacity rejects admission without losing pending actions and terminal reconciliation frees capacity', async () => {
	const notebook = new ModelNotebook({ maximumReceipts: 1 });
	for (let i = 0; i < 1024; i++) await notebook.recordDispatch('a', identity(`pending-${i}`));
	await assert.rejects(notebook.recordDispatch('a', identity('overflow')), /RECEIPT_RECOVERY_LIMIT/);
	assert.equal(await notebook.findReceipt('a', { actionId: 'overflow' }), null);
	assert.equal((await notebook.listUnresolved('a', query)).total, 1024);
	await notebook.recordReceipt('a', terminal('pending-0'));
	await notebook.recordDispatch('a', identity('overflow'));
	assert.equal((await notebook.listUnresolved('a', query)).total, 1024);
});

test('agent/world isolation, session exclusion and exact note lookup survive journal replay', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	for (const agentId of ['../a', 'b']) {
		for (const worldId of ['world-one', 'world-two', 'session:private']) {
			await notebook.writeNote(agentId, { worldId, key: 'exact', text: `${agentId} ${worldId}` });
			await notebook.writeNote(agentId, { worldId, key: 'prefix-exact', text: 'Decoy text exact.' });
			await notebook.recordDispatch(agentId, identity('same-action', worldId));
		}
	}
	const restored = new ModelNotebook({ directory });
	assert.equal((await restored.findNote('../a', { worldId: 'world-one', key: 'exact' })).text, '../a world-one');
	assert.equal(await restored.findNote('../a', { worldId: 'world-one', key: 'exac' }), null);
	assert.equal(await restored.findNote('../a', { worldId: 'session:private', key: 'exact' }), null);
	assert.equal(await restored.findReceipt('../a', { worldId: 'session:private', actionId: 'same-action' }), null);
	await assert.rejects(restored.findReceipt('../a', { actionId: 'same-action' }), /WORLD_REQUIRED/);
	assert.equal((await restored.findReceipt('b', { worldId: 'world-two', actionId: 'same-action' })).worldId, 'world-two');
	for (const name of await readdir(directory)) assert.doesNotMatch(await readFile(join(directory, name), 'utf8'), /session:private/);
});

test('local persistence fixture reports append versus full-snapshot timings without provider or gameplay claims', async t => {
	const directory = await fixture(t), notebook = new ModelNotebook({ directory });
	for (let i = 0; i < 64; i++) await notebook.writeNote('a', { worldId: 'world-one', key: `note-${i}`, text: 'x'.repeat(2048) });
	const legacy = new AtomicAgentStore({ directory, namespace: 'fixture-snapshot' });
	const state = await new AtomicAgentStore({ directory, namespace: 'notebook' }).read('a');
	const baseline = [], appended = [];
	let snapshotBytes = 0;
	for (let i = 0; i < 40; i++) {
		for (const isTerminal of [false, true]) {
			const receipt = { ...(isTerminal ? terminal(`timing-${i}`) : identity(`timing-${i}`)), kind: 'receipt', source: isTerminal ? 'server_action_result' : 'coordinator_dispatch', revision: ++state.revision };
			state.receipts = [...state.receipts.filter(value => value.actionId !== receipt.actionId), receipt];
			let start = performance.now();
			await legacy.write('a', state);
			baseline.push(performance.now() - start);
			snapshotBytes += Buffer.byteLength(JSON.stringify(state));
			start = performance.now();
			await notebook[isTerminal ? 'recordReceipt' : 'recordDispatch']('a', isTerminal ? terminal(`timing-${i}`) : identity(`timing-${i}`));
			appended.push(performance.now() - start);
		}
	}
	const percentile = (values, p) => [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) * p)].toFixed(2);
	const logBytes = (await readFile(await logPath(directory))).length;
	assert.ok(logBytes < snapshotBytes / 20, 'small actions must not copy full note history');
	t.diagnostic(JSON.stringify({ fixture: '64 notes / 40 actions; local disk only; snapshot baseline excludes notebook validation', snapshotBytes, logBytes,
		fullSnapshotMs: { p50: percentile(baseline, .5), p95: percentile(baseline, .95) }, notebookAppendMs: { p50: percentile(appended, .5), p95: percentile(appended, .95) } }));
});
