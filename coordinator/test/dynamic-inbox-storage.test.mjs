import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('failed admission never acknowledges a wake', async () => { await failedAdmission(); });

test('removal owns unopened persisted inbox', async () => { await removedUnopenedInbox(); });

test('missing pending receipt blocks admission retry', async () => { await missingPendingReceipt('missing'); });

test('null pending receipt blocks admission retry', async () => { await missingPendingReceipt('null'); });

test('wake acknowledgement precedes provider acceptance', async () => { await wakeAck(); });
