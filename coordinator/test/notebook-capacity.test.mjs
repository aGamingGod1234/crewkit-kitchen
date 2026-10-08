import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ModelNotebook, serializedBytes } from '../src/model-notebook.mjs';
import { NotebookActionJournal, emptyNotebook } from '../src/notebook-action-journal.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';

const byteLength = value => Buffer.byteLength(JSON.stringify(value));
const query = { worldId: 'capacity-world', limit: 64 };
function book(actionId, characters = 32_000) {
	return { worldId: query.worldId, actionId, actionType: 'edit_book', arguments: {
		slot: 0, pages: Array.from({ length: 32 }, (_, index) => 'x'.repeat(Math.max(0, Math.min(1000, characters - index * 1000)))), expectedFingerprint: 'book',
	} };
}
function dispatch(action, revision) {
	return { kind: 'receipt', source: 'coordinator_dispatch', ...action, state: 'DISPATCHED', reasonCode: 'AWAITING_AUTHORITATIVE_RESULT', revision };
}
function capacityState(collection = 'receipts') {
	const state = emptyNotebook();
	state.revision = 124;
	state[collection] = Array.from({ length: 123 }, (_, index) => dispatch(book(`book-${index}`), index + 1));
	state[collection].push(dispatch(book('last-book', 0), 124));
	if (collection === 'recovery') state.evictedReceipts = 124;
	const remaining = 4_000_000 - byteLength(state);
	assert.ok(remaining > 0 && remaining < 32_000);
	state[collection][123].arguments = book('last-book', remaining).arguments;
	assert.equal(byteLength(state), 4_000_000, 'exercise the actual capacity boundary, not a reduced test budget');
	return state;
}
async function fixture(t) {
	const directory = await mkdtemp(join(tmpdir(), 'notebook-capacity-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	return directory;
}
const terminal = action => ({ worldId: action.worldId, actionId: action.actionId, state: 'SUCCEEDED', reasonCode: '',
	executionStarted: true, physicalAttempted: true, actionObservation: { position: { x: 1, y: 64, z: 2 } } });

test('default bounds admit exactly 4 MB of book dispatches and all reconcile, including after restart', async t => {
	const directory = await fixture(t);
	let notebook = new ModelNotebook({ directory });
	const state = capacityState();
	for (const action of state.receipts) await notebook.recordDispatch('a', action);
	assert.equal(byteLength(await new NotebookActionJournal({ directory }).read('a')), 4_000_000);
	await assert.rejects(notebook.recordDispatch('a', book('overflow')), { code: 'RECEIPT_RECOVERY_LIMIT' });
	assert.equal(await notebook.findReceipt('a', { actionId: 'overflow' }), null);
	assert.equal((await notebook.listUnresolved('a', query)).total, 124);
	for (const [index, action] of state.receipts.entries()) {
		const result = await notebook.recordReceipt('a', terminal(action));
		assert.deepEqual(result.arguments, action.arguments);
		assert.deepEqual(result.actionObservation, terminal(action).actionObservation);
		if (index % 31 === 0 || index === 123) notebook = new ModelNotebook({ directory });
		assert.deepEqual(await notebook.findReceipt('a', { actionId: action.actionId }), result);
		assert.equal((await notebook.listUnresolved('a', query)).total, 123 - index);
	}
	await notebook.recordDispatch('a', book('capacity-freed'));
	assert.equal((await notebook.listUnresolved('a', query)).total, 1);
});

test('legacy 4 MB recovery state drains with large escaped evidence without losing pending identities', async t => {
	const directory = await fixture(t), disk = new AtomicAgentStore({ directory, namespace: 'notebook' });
	const state = capacityState('recovery');
	await disk.write('a', state);
	let notebook = new ModelNotebook({ directory });
	const escaped = '\0'.repeat(256), vector = { x: -1.2345678901234567e-200, y: Number.MAX_VALUE, z: -Number.MAX_VALUE };
	const actionObservation = {
		worldTick: Number.MAX_SAFE_INTEGER, observedAtEpochMs: Number.MAX_SAFE_INTEGER, yaw: Number.MAX_VALUE, pitch: -Number.MAX_VALUE,
		position: vector, velocity: vector, collision: { horizontal: false, vertical: false, inWall: false },
		lookedAt: { type: escaped, id: escaped, face: escaped, hitDistance: Number.MAX_VALUE, position: vector },
		reach: { distance: Number.MAX_VALUE, max: Number.MAX_VALUE, within: false },
		target: { kind: escaped, position: vector, expectedId: escaped, currentId: escaped, beforeId: escaped, afterId: escaped,
			worldChanged: false, distanceRemaining: Number.MAX_VALUE, tolerance: Number.MAX_VALUE, standable: false },
		progress: { value: Number.MAX_VALUE, basis: escaped, verified: false },
	};
	assert.ok(byteLength(actionObservation) > 14_000 && byteLength(actionObservation) <= 16_384);
	await assert.rejects(notebook.recordDispatch('a', book('blocked')), /RECEIPT_RECOVERY_LIMIT/);
	await assert.rejects(notebook.writeNote('a', { worldId: query.worldId, key: 'blocked', text: 'Cannot spend the terminal reserve.' }), /RECEIPT_RECOVERY_LIMIT/);
	await assert.rejects(notebook.recordUnknown('a', { ...state.recovery[0], reasonCode: '\0'.repeat(128) }), /RECEIPT_RECOVERY_LIMIT/);
	for (const [index, action] of state.recovery.entries()) {
		assert.deepEqual(await notebook.findReceipt('a', { actionId: action.actionId }), action);
		const result = await notebook.recordReceipt('a', { ...terminal(action), reasonCode: '\0'.repeat(128),
			dimension: '\0'.repeat(128), goalRevision: Number.MAX_SAFE_INTEGER, tick: Number.MAX_SAFE_INTEGER, actionObservation });
		assert.deepEqual(result.arguments, action.arguments);
		assert.deepEqual(result.actionObservation, actionObservation);
		if (index % 16 === 0 || index === 123) notebook = new ModelNotebook({ directory });
		assert.deepEqual(await notebook.findReceipt('a', { actionId: action.actionId }), result);
		assert.equal((await notebook.listUnresolved('a', query)).total, 123 - index);
	}
	assert.ok((await notebook.query('a', query)).evictedReceipts > 124, 'history evictions remain visible');
	assert.ok((await disk.read('a')).revision > 124, 'large terminals cross the real WAL checkpoint boundary');
	for (const name of await readdir(directory)) {
		assert.ok((await readFile(join(directory, name))).length <= 4_194_304, 'checkpoints remain readable by AtomicAgentStore');
	}
});

test('small retention budgets cannot prevent full terminal enrichment or erase it on restart', async t => {
	const directory = await fixture(t), options = { directory, maximumBytes: 4096 };
	const notebook = new ModelNotebook(options), action = book('enriched');
	await notebook.recordDispatch('a', { worldId: action.worldId, actionId: action.actionId });
	const result = await notebook.recordReceipt('a', { ...action, ...terminal(action) });
	assert.ok(byteLength(result) > options.maximumBytes);
	assert.deepEqual(result.arguments, action.arguments);
	const restored = new ModelNotebook(options);
	assert.deepEqual(await restored.findReceipt('a', { actionId: action.actionId }), result);
	assert.equal((await restored.listUnresolved('a', query)).total, 0);
});

test('incremental size accounting equals serializing the whole notebook', () => {
	const entry = (kind, revision, text) => ({ kind, revision, worldId: query.worldId, text, key: `k${revision}` });
	const awkward = `quote " slash ${String.fromCharCode(92)} accent ${String.fromCharCode(0xe9, 0x2028, 0xd83d, 0xde00)}`;
	const empty = emptyNotebook();
	const full = { ...empty, revision: 9, evictedNotes: 3, evictedReceipts: 12,
		notes: [entry('note', 1, 'plain'), entry('note', 2, awkward)],
		receipts: [entry('receipt', 3, 'x'.repeat(500)), entry('receipt', 4, '')], recovery: [entry('receipt', 5, 'pending')] };
	for (const state of [empty, full, { ...full, notes: [] }, { ...full, receipts: [], recovery: [] }, { ...full, recovery: [] }]) {
		assert.equal(serializedBytes(state, true), byteLength(state));
		assert.equal(serializedBytes(state, false), byteLength({ ...state, recovery: [] }));
	}
	// Entries are measured once and remembered: repeating the call on the same objects stays exact.
	assert.equal(serializedBytes(full, true), byteLength(full));
});
