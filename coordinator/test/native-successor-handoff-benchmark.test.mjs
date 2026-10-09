import assert from 'node:assert/strict';
import test from 'node:test';
import { baselineRef, defaultSettings, runBenchmark } from '../src/benchmark/native-successor-handoff.mjs';

test('successor benchmark preserves authored action shapes and exercises guarded runtime behavior', { timeout: 120_000 }, async () => {
	const result = await runBenchmark({ settings: { ...defaultSettings, repetitions: 1, warmups: 0,
		modelDelayMs: 10, predecessorMs: 60, successorMs: 1, observationMs: 1 } });
	assert.equal(result.baseline.commit, baselineRef);
	assert.ok(Object.keys(result.baseline.files).length > 10, 'baseline includes the frozen ArenaScript dependency graph');
	for (const comparison of Object.values(result.comparisons)) {
		assert.equal(comparison.before.actions, 2);
		assert.equal(comparison.after.actions, 2);
		assert.equal(comparison.before.actionShapeHash, comparison.after.actionShapeHash);
		assert.equal(comparison.before.dispatchGap.n, 1);
		assert.equal(comparison.after.dispatchGap.n, 1);
		assert.equal(comparison.before.requestedObservations, 2);
		assert.ok(comparison.after.requestedObservations >= 2, 'the benchmark keeps authoritative post-action sampling');
	}
	assert.equal(result.comparisons.foreground_to_queued.after.preparedBeforeCompletion, 1);
	assert.equal(result.comparisons.individual_to_conditional.after.modelDelayAfterCompletion.p50Ms, 0);
	assert.equal(result.comparisons.foreground_to_queued.after.modelDelayAfterCompletion.p50Ms, 0);
	assert.equal(result.behaviorCases.ready.actions, 2);
	assert.equal(result.behaviorCases.guard_false.actions, 1);
	assert.equal(result.behaviorCases.changed_goal.actions, 1);
	assert.equal(result.behaviorCases.predecessor_failure.actions, 1);
	assert.equal(result.behaviorCases.late_queue_not_ready.actions, 2, 'late preparation uses a new foreground request');
	assert.ok(result.behaviorCases.late_queue_not_ready.rejection.code);
});
