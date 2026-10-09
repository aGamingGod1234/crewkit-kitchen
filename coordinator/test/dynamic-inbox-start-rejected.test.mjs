import test from 'node:test';
import { rejectedStart } from './fixtures/runtime-inbox-scenarios.mjs';

test('rejected start preserves successful steering', { timeout: 120_000 }, async () => { await rejectedStart(); });
