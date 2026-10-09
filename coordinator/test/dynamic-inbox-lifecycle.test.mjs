import test from 'node:test';
import { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck } from './fixtures/runtime-inbox-scenarios.mjs';

test('goal change preserves pending input', { timeout: 120_000 }, async () => { await lifecycle('goal-change',60); });

test('new server fences old pending input', { timeout: 120_000 }, async () => { await lifecycle('new-server',12); });
