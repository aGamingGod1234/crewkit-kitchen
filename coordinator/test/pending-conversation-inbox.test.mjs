import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readdir, readFile, rm, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { PendingConversationInbox } from '../src/pending-conversation-inbox.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';

const entry = (sequence) => ({ sequence, kind: 'player_message', sourceId: 'fixture-player', recipientId: 'fixture-agent',
	scope: 'direct', text: `  Instruction ${sequence}: ${'x'.repeat(250)}  `, goalRevision: sequence % 3, observedAtEpochMs: sequence });
async function fixture(t, storeFactory) {
	const directory = await mkdtemp(join(tmpdir(), 'pending-test-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const create = () => new PendingConversationInbox({ directory, agentId: 'fixture-agent', ...(storeFactory ? { storeFactory } : {}) });
	return { directory, create, inbox: create() };
}
async function drain(inbox) {
	const entries = [];
	for (;;) {
		const reserved = await inbox.reserve();
		entries.push(...reserved.conversation.entries);
		await inbox.commit(reserved.token);
		if (!reserved.more) return entries;
	}
}

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

test('pending inbox: disk pages, exact text/revision, reload and reclamation remain bounded', async (t) => {
	const { directory, inbox, create } = await fixture(t);
	for (let n = 1; n <= 160; n++) await inbox.append('server-one', entry(n));
	const [folder] = await readdir(directory);
	for (const name of await readdir(join(directory, folder))) {
		const data = await readFile(join(directory, folder, name));
		assert.ok(data.length < 4096, 'neither page nor index grows with backlog');
		const value = JSON.parse(data);
		if (value.version === 1) assert.ok(data.length < 1024);
	}
	const pending = await inbox.reserve();
	assert.equal(pending.conversation.entries.length, 32);
	await inbox.close(); // Uncommitted reservation must survive a clean process boundary.
	const reloaded = create(); await reloaded.open('server-one');
	assert.deepEqual(await drain(reloaded), Array.from({ length: 160 }, (_, i) => entry(i + 1)));
	await reloaded.close();
	const final = create(); await final.open('server-one');
	assert.deepEqual(await drain(final), []);
	assert.equal((await readdir(join(directory, folder))).length, 1, 'only bounded metadata remains');
	await final.close();
});

test('pending inbox: later steering commit cannot consume a rejected earlier reservation', async (t) => {
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

test('pending inbox: actual serialized prefix only; scope/removal fencing and duplicate collisions', async (t) => {
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

test('pending inbox: append/index failure is not admitted, partial commit retries only uncommitted bodies', async (t) => {
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
