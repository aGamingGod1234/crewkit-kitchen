import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('entry burst preserves accepted steering', async () => { await burst(40,false,false); });

test('entry burst preserves rejected steering', async () => { await burst(40,true,false); });

test('rejected start preserves successful steering', async () => { await rejectedStart(); });
