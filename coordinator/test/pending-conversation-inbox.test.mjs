import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readdir, readFile, rm, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { PendingConversationInbox } from '../src/pending-conversation-inbox.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';
import { MAX_IDENTIFIER_LENGTH } from '../src/constants.mjs';

import { entry, fixture, drain, DISK_BOUND } from './fixtures/pending-inbox-fixture.mjs';

test('pending inbox: exact retained wake survives consumption, later input and restart', async t => {
	const { inbox, create } = await fixture(t);
	const wake = { transactionId: 'w'.repeat(MAX_IDENTIFIER_LENGTH), fingerprint: JSON.stringify({ event: entry(1), control: { operation: 'start', goalRevision: 1 } }) };
	await inbox.append('one', entry(1), wake); await drain(inbox);
	await inbox.append('one', entry(2)); await drain(inbox); await inbox.close();
	const reload = create(); await reload.open('one');
	assert.equal(await reload.checkWake('one', entry(1), wake), true);
	assert.equal(await reload.append('one', entry(1), wake), false);
	assert.equal(await reload.needsDelivery(1), false);
	assert.deepEqual(await drain(reload), []);
	await assert.rejects(reload.append('one', { ...entry(1), text: 'changed' }, wake), { code: 'CONVERSATION_COLLISION' });
	await assert.rejects(reload.checkWake('one', entry(1), { ...wake, fingerprint: 'changed control' }), { code: 'TRANSACTION_COLLISION' });
	await assert.rejects(reload.append('one', entry(1), { ...wake, transactionId: 'different-wake' }), { code: 'TRANSACTION_COLLISION' });
	await assert.rejects(reload.append('one', entry(1)), { code: 'CONVERSATION_OUT_OF_ORDER' });
	assert.equal(await reload.checkWake('two', entry(1), wake), false, 'wake identity is server scoped');
	assert.equal(await reload.append('two', entry(1), wake), true);
	assert.deepEqual(await drain(reload), [entry(1)]);
	// Only the server's latest per-agent wake is replayable, regardless of backlog.
	const next = { transactionId: 'wake-two', fingerprint: 'second exact wake' };
	await reload.append('two', entry(2), next); await drain(reload);
	await assert.rejects(reload.append('two', entry(1), wake), { code: 'CONVERSATION_OUT_OF_ORDER' });
	await reload.close();
});

test('pending inbox: removal queued behind I/O cannot delete a replacement session mailbox', async t => {
	let release, entered, armed = false;
	const gate = new Promise(resolve => { release = resolve; });
	const writing = new Promise(resolve => { entered = resolve; });
	const { inbox } = await fixture(t, options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: async (key, value) => {
			if (armed && key === 'index' && value.staged) { armed = false; entered(); await gate; }
			return disk.write(key, value);
		} };
	});
	let current = true, admission, removal;
	try {
		await inbox.open('one'); armed = true;
		admission = inbox.append('one', entry(1)); await writing;
		removal = inbox.remove(() => current);
		current = false; inbox.fence(); release();
		await admission; await removal;
		await inbox.open('one');
		assert.deepEqual(await drain(inbox), [entry(1)], 'obsolete queued deletion has no mailbox authority');
	} finally { release(); await admission; await removal; await inbox.close(); }
});

test('pending inbox: fence during reclamation cannot invent unread backlog', async (t) => {
	let release, entered, armed = false;
	const gate = new Promise(resolve => { release = resolve; });
	const writingHead = new Promise(resolve => { entered = resolve; });
	const { inbox } = await fixture(t, options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: async (key, value) => {
			if (armed && key === 'index' && value.head === 1) {
				armed = false;
				entered();
				await gate;
			}
			return disk.write(key, value);
		} };
	});
	let commit;
	try {
		await inbox.append('one', entry(1));
		const first = await inbox.reserve();
		armed = true;
		commit = inbox.commit(first.token);
		await writingHead;
		// A new goal can fence accepted delivery while its durable head advances.
		inbox.fence();
		release();
		assert.equal(await commit, true);
		const empty = await inbox.reserve();
		assert.deepEqual(empty.conversation.entries, []);
		assert.equal(empty.more, false, 'consumed input must not trigger another turn');
		await inbox.commit(empty.token);
		await inbox.append('one', entry(2));
		assert.deepEqual(await drain(inbox), [entry(2)], 'new input remains deliverable');
	} finally {
		release();
		await commit;
		await inbox.close();
	}
});

test('pending inbox: later steering commit cannot consume a rejected earlier reservation', DISK_BOUND, async (t) => {
	const { inbox } = await fixture(t);
	await inbox.append('one', entry(1));
	const start = await inbox.reserve();
	for (let n = 2; n <= 60; n++) await inbox.append('one', entry(n));
	const steer = await inbox.reserve();
	await assert.rejects(inbox.reserve(), { code: 'INBOX_RESERVATION_BUSY' });
	await inbox.commit(steer.token);
	inbox.rollback(start.token);
	assert.equal(await inbox.commit(start.token), false, 'late callback has no authority');
	const remaining = await drain(inbox);
	assert.deepEqual(remaining.map(e => e.sequence), [1, ...Array.from({ length: 27 }, (_, i) => i + 34)]);
	await inbox.close();
});

test('pending inbox: actual serialized prefix only; scope/removal fencing and duplicate collisions', DISK_BOUND, async (t) => {
	const { inbox } = await fixture(t);
	for (let n = 1; n <= 10; n++) await inbox.append('one', entry(n));
	assert.equal(await inbox.append('one', entry(3)), false);
	await assert.rejects(inbox.append('one', { ...entry(3), text: 'changed' }), { code: 'CONVERSATION_COLLISION' });
	const reserved = await inbox.reserve(); inbox.trim(reserved.token, 2); await inbox.commit(reserved.token);
	const old = await inbox.reserve();
	inbox.fence();
	assert.equal(await inbox.commit(old.token), false);
	assert.deepEqual((await drain(inbox)).map(e => e.sequence), [3, 4, 5, 6, 7, 8, 9, 10]);
	await inbox.append('one', entry(11));
	const late = await inbox.reserve();
	await inbox.append('two', entry(1));
	assert.equal(await inbox.commit(late.token), false);
	assert.deepEqual((await drain(inbox)).map(e => e.sequence), [1]);
	await inbox.append('two', entry(2)); await inbox.remove(); await inbox.append('two', entry(1));
	assert.deepEqual((await drain(inbox)).map(e => e.sequence), [1]);
	await inbox.close();
});

test('pending inbox: append/index failure is not admitted, partial commit retries only uncommitted bodies', DISK_BOUND, async (t) => {
	let fail = () => false;
	const storeFactory = options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: async (key, value) => {
			if (fail(key, value)) throw Object.assign(new Error('injected disk failure'), { code: 'EIO' });
			return disk.write(key, value);
		} };
	};
	const { inbox, create } = await fixture(t, storeFactory);
	await inbox.open('one');
	fail = (key, value) => key === 'index' && value.tail === 1;
	await assert.rejects(inbox.append('one', entry(1)), { code: 'CONVERSATION_STORAGE_FAILED' });
	assert.deepEqual(await drain(inbox), []);
	fail = () => false;
	await inbox.close();
	const reload = create();
	assert.equal(await reload.append('one', entry(1)), true);
	for (let n = 2; n <= 5; n++) await reload.append('one', entry(n));
	const reserved = await reload.reserve();
	fail = (_key, value) => value.delivered === true && value.entry.sequence === 3;
	await assert.rejects(reload.commit(reserved.token), { code: 'CONVERSATION_STORAGE_FAILED' });
	fail = () => false;
	await reload.close();
	const recovered = create(); await recovered.open('one');
	assert.deepEqual((await drain(recovered)).map(e => e.sequence), [3, 4, 5]);
	await recovered.close();
});

test('pending inbox: failure after page receipt but before head update preserves delivery receipt', async (t) => {
	let fail = false;
	const { inbox, create } = await fixture(t, options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: (key, value) => {
			if (fail && key === 'index' && value.head > 0) return Promise.reject(Object.assign(new Error('fault'), { code: 'EIO' }));
			return disk.write(key, value);
		} };
	});
	await inbox.append('one', entry(1)); const reserved = await inbox.reserve();
	fail = true; await assert.rejects(inbox.commit(reserved.token), { code: 'CONVERSATION_STORAGE_FAILED' });
	fail = false;
	// Read from disk without closing the first object, so its cleanup cannot hide the fault.
	const reload = create(); await reload.open('one');
	assert.deepEqual(await drain(reload), []); await reload.close(); inbox.fence();
});


test('pending inbox: ambiguous final metadata write remains replayable without a duplicate body', async (t) => {
	let fail = true;
	const { inbox } = await fixture(t, options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: async (key, value) => {
			await disk.write(key, value);
			if (fail && key === 'index' && value.tail === 1) throw Object.assign(new Error('after rename'), { code: 'EIO' });
		} };
	});
	await assert.rejects(inbox.append('one', entry(1)), { code: 'CONVERSATION_STORAGE_FAILED' });
	fail = false;
	assert.equal(await inbox.append('one', entry(1)), false);
	assert.equal(await inbox.needsDelivery(1), true);
	const reserved = await inbox.reserve();
	assert.equal(await inbox.needsDelivery(1), false, 'a duplicate cannot overlap an existing reservation');
	await inbox.commit(reserved.token);
	assert.equal(await inbox.needsDelivery(1), false);
	assert.deepEqual(await drain(inbox), []);
	await inbox.close();
});


test('pending inbox: interrupted reclamation resumes after unlink without replaying delivered text', async (t) => {
	let fail = false;
	const { inbox, create } = await fixture(t, options => {
		const disk = new AtomicAgentStore(options);
		return { read: key => disk.read(key), write: (key, value) => {
			if (fail && key === 'index' && value.garbage?.head === 1 && value.garbage?.tail === 1) {
				return Promise.reject(Object.assign(new Error('cleanup cursor fault'), { code: 'EIO' }));
			}
			return disk.write(key, value);
		} };
	});
	await inbox.append('one', entry(1)); const reserved = await inbox.reserve();
	fail = true; await assert.rejects(inbox.commit(reserved.token), { code: 'CONVERSATION_STORAGE_FAILED' });
	fail = false; inbox.fence();
	const recovered = create(); await recovered.open('one');
	assert.deepEqual(await drain(recovered), []);
	await recovered.append('one', entry(2));
	assert.deepEqual((await drain(recovered)).map(e => e.sequence), [2]);
	await recovered.close();
});

// Metadata failures must be visible before any recovery cleanup can discard bodies.
for (const damage of ['missing', 'null', 'missing-scope', 'missing-last', 'invalid-staged', 'invalid-garbage']) {
 test(`pending inbox: damaged index ${damage} fails without modifying retained records`, async (t) => {
  const { directory, inbox, create } = await fixture(t);
  await inbox.append('one', entry(1)); await inbox.close();
  const [folder] = await readdir(directory);
  const disk = join(directory, folder);
  const path = join(disk, `pending-${createHash('sha256').update('index').digest('hex')}.json`);
  const saved = JSON.parse(await readFile(path, 'utf8'));
  if (damage === 'missing') await unlink(path);
  else if (damage === 'null') await writeFile(path, 'null');
  else {
   if (damage === 'missing-scope') delete saved.scope;
   if (damage === 'missing-last') delete saved.last;
   if (damage === 'invalid-staged') saved.staged = { position: 0, sequence: 1 };
   if (damage === 'invalid-garbage') saved.garbage = { generation: saved.generation, head: 0, tail: 1 };
   await writeFile(path, JSON.stringify(saved));
  }
  const snapshot = async () => Object.fromEntries(await Promise.all((await readdir(disk)).sort().map(async name => [name, await readFile(join(disk, name), 'utf8')])));
  const before = await snapshot();
  const recovered = create();
  await assert.rejects(recovered.open('one'), { code: 'CONVERSATION_STORAGE_FAILED' });
  await assert.rejects(recovered.remove(), { code: 'CONVERSATION_STORAGE_FAILED' });
  assert.deepEqual(await snapshot(), before, 'invalid metadata cannot authorize cleanup');
 });
}
for (const damage of ['missing', 'null']) {
 test(`pending inbox: ${damage} pending receipt rejects retry but consumed duplicate stays suppressed`, async (t) => {
  const { directory, inbox, create } = await fixture(t);
  await inbox.append('one', entry(1)); await inbox.close();
  const [folder] = await readdir(directory);
  const keyPath = key => join(directory, folder, `pending-${createHash('sha256').update(key).digest('hex')}.json`);
  const saved = JSON.parse(await readFile(keyPath('index'), 'utf8'));
  const receipt = keyPath(`${saved.generation}:sequence:1`);
  if (damage === 'missing') await unlink(receipt); else await writeFile(receipt, 'null');
  const recovered = create(); await recovered.open('one');
  assert.equal(await recovered.append('one', entry(1)), false);
  await assert.rejects(recovered.needsDelivery(1), { code: 'CONVERSATION_STORAGE_FAILED' });
  assert.deepEqual(await drain(recovered), [entry(1)], 'pending body remains recoverable');
  assert.equal(await recovered.append('one', entry(1)), false);
  assert.equal(await recovered.needsDelivery(1), false, 'consumed duplicate requires no receipt');
  await recovered.close();
  const consumed = create(); await consumed.open('one');
  assert.equal(await consumed.append('one', entry(1)), false);
  assert.equal(await consumed.needsDelivery(1), false);
  await consumed.close();
 });
}
