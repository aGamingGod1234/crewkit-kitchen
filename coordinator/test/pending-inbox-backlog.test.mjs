import test from 'node:test';
import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { entry, fixture, drain } from './fixtures/pending-inbox-fixture.mjs';

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
