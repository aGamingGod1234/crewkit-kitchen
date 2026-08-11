import assert from 'node:assert/strict';
import test from 'node:test';

import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';

test('latency registry bounds samples and reports nearest-rank percentiles', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 3, operationCap: 2 });
	for (const value of [10, 20, 30, 40]) registry.record('observation_to_plan', value);

	assert.deepEqual(registry.snapshot(), [
		{ operation: 'observation_to_plan', count: 3, p50Ms: 30, p95Ms: 40 },
	]);
});

test('latency registry rejects invalid samples and new identities beyond capacity', () => {
	const registry = new ControlLatencyRegistry({ operationCap: 1 });
	registry.record('action_completion', 1);

	assert.throws(() => registry.record('second_operation', 1), /capacity/);
	assert.throws(() => registry.record('action_completion', -1), /duration/);
	assert.throws(() => registry.record('', 1), /operation/);
});
