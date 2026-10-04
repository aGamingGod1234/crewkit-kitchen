import test from 'node:test';
import { burst } from './fixtures/runtime-inbox-scenarios.mjs';

test('byte burst preserves accepted steering', async () => { await burst(60,false); });
