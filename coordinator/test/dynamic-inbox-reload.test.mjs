import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('coordinator restart retains the 60-message backlog and new-event control', { timeout: 120_000 }, async () => { await durableCoordinatorReload(); });
