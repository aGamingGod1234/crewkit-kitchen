import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('read history eviction never redelivers text', async () => { await readHistory(); });
