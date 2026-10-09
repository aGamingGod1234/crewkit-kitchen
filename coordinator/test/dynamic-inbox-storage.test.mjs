import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('failed admission never acknowledges a wake', { timeout: 120_000 }, async () => { await failedAdmission(); });

test('removal owns unopened persisted inbox', { timeout: 120_000 }, async () => { await removedUnopenedInbox(); });

test('missing pending receipt blocks admission retry', { timeout: 120_000 }, async () => { await missingPendingReceipt('missing'); });

test('null pending receipt blocks admission retry', { timeout: 120_000 }, async () => { await missingPendingReceipt('null'); });

test('wake acknowledgement precedes provider acceptance', { timeout: 120_000 }, async () => { await wakeAck(); });
