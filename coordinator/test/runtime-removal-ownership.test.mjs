import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fixture, gate, record, until, flush } from './fixtures/runtime-inbox-fixture.mjs';
import { PendingConversationInbox } from '../src/pending-conversation-inbox.mjs';

for (const reconnect of [false, true]) test(`mailbox removal ${reconnect ? 'cannot clear replacement-session membership or hooks' : 'cleans the current agent'}`, async t => {
	const memoryDirectory = await mkdtemp(join(tmpdir(), 'runtime-removal-'));
	t.after(() => rm(memoryDirectory, { recursive: true, force: true }));
	const original = PendingConversationInbox.prototype.remove, held = gate(), hooks = [];
	let entered = false, f;
	PendingConversationInbox.prototype.remove = async function(...args) { await original.apply(this, args); entered = true; await held.promise; };
	try {
		f = await fixture({ memoryDirectory, runtimeHooks: { onRemoved: id => hooks.push({ id, model: f.registry.get(id)?.model ?? null }) } });
		const pending = f.bridge.deliver('agent_removed', { goalRevision: 0 });
		await until(() => entered, 'mailbox removal complete');
		if (reconnect) {
			f.bridge.emit('disconnected', { connectionEpoch: 1 }); await flush();
			f.bridge.emit('ready', { serverInstanceId: 'r17-server', connectionEpoch: 2, registry: [{ ...record(), model: 'gpt-6-sol' }] });
			await until(() => f.bridge.sent.some(m => m.type === 'agent_ready' && m.connectionEpoch === 2), 'replacement ready');
		}
		held.resolve(); await pending;
		if (reconnect) {
			await f.statusTick(); await flush();
			assert.equal(f.registry.get('agent-a').model, 'gpt-6-sol');
			assert.deepEqual(hooks, [], 'obsolete cleanup cannot invoke current-resource removal hooks');
			const statuses = f.bridge.sent.filter(m => m.type === 'coordinator_status' && m.connectionEpoch === 2);
			assert.ok(statuses.length > 0);
			assert.ok(statuses.every(m => m.payload.supportedProfileCount === 1 && m.payload.rosterReadyCount === 1));
		} else {
			assert.equal(f.registry.get('agent-a'), null);
			assert.deepEqual(hooks, [{ id: 'agent-a', model: null }]);
		}
	} finally { held.resolve(); PendingConversationInbox.prototype.remove = original; await f?.coordinator.stop(); }
});
