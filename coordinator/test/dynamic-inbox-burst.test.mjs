import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('byte burst preserves accepted steering', async () => { await burst(60,false); });

test('byte burst preserves rejected steering', async () => { await burst(60,true); });
