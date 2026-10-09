import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('same server reconnect preserves pending input', { timeout: 120_000 }, async () => { await lifecycle('same-server',60); });
