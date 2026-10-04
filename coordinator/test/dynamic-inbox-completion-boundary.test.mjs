import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { PendingConversationInbox } from '../src/pending-conversation-inbox.mjs';
import { burst } from './fixtures/runtime-inbox-scenarios.mjs';
import { gate, until } from './fixtures/runtime-inbox-fixture.mjs';

test('burst followup waits for the prior correction to commit', async () => {
  const held = gate();
  const original = PendingConversationInbox.prototype.commit;
  let fixture, blocked = false, run;
  PendingConversationInbox.prototype.commit = async function(token) {
    if (!blocked && token.entries.length === 0) {
      blocked = true;
      await held.promise;
    }
    return original.call(this, token);
  };
  try {
    run = burst(40, false, false, value => { fixture = value; });
    // Observe failures immediately even while waiting on the controlled boundary.
    run.catch(() => {});
    await until(() => blocked, 'held empty correction commit');
    // Cross the old 150 ms sleep while storage still owns completion.
    await delay(200);
    assert.equal(fixture.calls.some(call => call.conversation.entries.some(entry => entry.sequence === 41)), false,
      'followup must not reuse an unfinished correction');
    held.resolve();
    await run;
    assert.deepEqual(fixture.errors, []);
  } finally {
    held.resolve();
    await run?.catch(() => {});
    PendingConversationInbox.prototype.commit = original;
  }
});
