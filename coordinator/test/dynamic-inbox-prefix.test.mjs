import test from 'node:test';
import { burst } from './fixtures/runtime-inbox-scenarios.mjs';

test('entry burst preserves accepted steering', { timeout: 120_000 }, async () => { await burst(40,false,false); });
