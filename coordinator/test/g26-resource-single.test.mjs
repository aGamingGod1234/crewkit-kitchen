import test from 'node:test';
import { verifyResourcePeak } from './fixtures/g26-resource-fixture.mjs';
test('real single trial resource peaks survive into cleanup', {skip:process.platform!=='win32', timeout: 180_000 },()=>verifyResourcePeak('single'));
