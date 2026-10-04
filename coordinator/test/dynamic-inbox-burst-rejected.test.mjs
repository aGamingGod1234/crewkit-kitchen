import test from 'node:test';
import { burst } from './fixtures/runtime-inbox-scenarios.mjs';

test('byte burst preserves rejected steering', async () => { await burst(60,true); });
