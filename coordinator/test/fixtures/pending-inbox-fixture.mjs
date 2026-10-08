import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { PendingConversationInbox } from '../../src/pending-conversation-inbox.mjs';
// Every append is four fsynced writes, so these tests cost seconds on an idle CI disk and minutes when
// builds share the disk. Their time is I/O latency, not behaviour, so they get their own budget.
export const DISK_BOUND = { timeout: 600_000 };
export const entry = (sequence) => ({ sequence, kind: 'player_message', sourceId: 'fixture-player', recipientId: 'fixture-agent',
	scope: 'direct', text: `  Instruction ${sequence}: ${'x'.repeat(250)}  `, goalRevision: sequence % 3, observedAtEpochMs: sequence });
export async function fixture(t, storeFactory) {
	const directory = await mkdtemp(join(tmpdir(), 'pending-test-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const create = () => new PendingConversationInbox({ directory, agentId: 'fixture-agent', ...(storeFactory ? { storeFactory } : {}) });
	return { directory, create, inbox: create() };
}
export async function drain(inbox) {
	const entries = [];
	for (;;) {
		const reserved = await inbox.reserve();
		entries.push(...reserved.conversation.entries);
		await inbox.commit(reserved.token);
		if (!reserved.more) return entries;
	}
}
