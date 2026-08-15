import assert from 'node:assert/strict';
import test from 'node:test';

import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';

test('latency registry bounds samples and reports nearest-rank percentiles', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 3, operationCap: 2 });
	for (const value of [10.25, 20.5, 30.75, 40.125]) registry.record('event_receipt_to_branch', value);
	registry.record('branch_to_bridge_send', 2.5);

	assert.deepEqual(registry.snapshot(), [
		{ operation: 'branch_to_bridge_send', count: 1, p50Ms: 2.5, p95Ms: 2.5 },
		{ operation: 'event_receipt_to_branch', count: 3, p50Ms: 30.75, p95Ms: 40.125 },
	]);
});

test('latency registry rejects provider inference and accepts only named local operations', () => {
	const registry = new ControlLatencyRegistry();
	assert.throws(() => registry.record('provider_inference', 1), /operation/i);
	for (const operation of [
		'minecraft_change_to_publication', 'event_receipt_to_branch', 'branch_to_bridge_send',
		'command_to_first_progress', 'action_completion',
	]) registry.record(operation, 1);
	assert.deepEqual(registry.snapshot().map((entry) => entry.operation), [
		'action_completion', 'branch_to_bridge_send', 'command_to_first_progress',
		'event_receipt_to_branch', 'minecraft_change_to_publication',
	]);
});

test('latency registry rejects invalid samples and new identities beyond capacity', () => {
	const registry = new ControlLatencyRegistry({ operationCap: 1 });
	registry.record('action_completion', 1);

	assert.throws(() => registry.record('branch_to_bridge_send', 1), /capacity/);
	assert.throws(() => registry.record('action_completion', -1), /duration/);
	assert.throws(() => registry.record('', 1), /operation/);
});
