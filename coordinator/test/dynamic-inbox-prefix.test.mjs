import test from 'node:test';
import { burst } from './fixtures/runtime-inbox-scenarios.mjs';

test('entry burst preserves accepted steering', async () => { await burst(40,false,false); });
