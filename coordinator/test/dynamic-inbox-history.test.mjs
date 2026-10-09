import test from 'node:test';
import assert from 'node:assert/strict';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';
import { fixture, event, until } from './fixtures/runtime-inbox-fixture.mjs';

test('read history eviction never redelivers text', async () => { await readHistory(); });

test('inbox fixture rejects a steer after its modeled provider turn ended', async () => {
	const run = await fixture();
	try {
		await run.bridge.deliver('conversation_event', event(1));
		await until(() => run.calls.some((call) => call.kind === 'start' && call.accepted), 'completed provider turn');
		await assert.rejects(run.planner.steerNativeTurn({ agentId: 'agent-a', goalRevision: 0, input: 'event: conversation\n{"event":"conversation","observation":{},"conversation":{"mode":"unread","baseSequence":null,"nextSequence":0,"omittedEntries":0,"entries":[]}}' }), { code: 'TURN_NOT_ACTIVE' });
	} finally { await run.coordinator.stop(); }
});
