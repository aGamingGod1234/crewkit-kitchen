import assert from 'node:assert/strict';
import test from 'node:test';

import { RetryPolicy } from '../src/retry-policy.mjs';

test('uses bounded exponential backoff and resets after success', () => {
	const policy = new RetryPolicy({ initialDelayMs: 100, maximumDelayMs: 500, multiplier: 2 });
	assert.deepEqual([policy.nextDelay(), policy.nextDelay(), policy.nextDelay(), policy.nextDelay()], [100, 200, 400, 500]);
	policy.reset();
	assert.equal(policy.nextDelay(), 100);
});
