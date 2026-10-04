import test from 'node:test';
import { rejectedStart } from './fixtures/runtime-inbox-scenarios.mjs';

test('rejected start preserves successful steering', async () => { await rejectedStart(); });
