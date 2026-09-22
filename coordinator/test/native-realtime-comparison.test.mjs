import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NATIVE_REALTIME_WORKLOAD, runNativeRealtimeTrial, summarizeComparison, summarizeNearestRank } from '../src/benchmark/native-realtime-comparison.mjs';

test('nearest-rank summaries retain missing event-dependent measurements', () => {
	assert.deepEqual(summarizeNearestRank([2, null, 4, 1]), {
		count: 3,
		missing: 1,
		min: 1,
		p50: 2,
		p95: 4,
		max: 4,
	});
});

test('paired event metrics never turn an absent baseline into a zero delta', () => {
	const sample = (arm, metrics) => ({
		arm,
		pairingKey: 'pair-1',
		order: arm === 'baseline' ? 1 : 2,
		status: 'passed',
		metrics,
		observed: { nativeEventObserved: arm === 'optimized', bodyContinuity: arm === 'optimized', controllerFollowed: true, bodyActionCount: 2 },
	});
	const summary = summarizeComparison([
		sample('baseline', { preparationLeadMs: null, usefulOverlapMs: null, decisionGapMs: 100, bodyActionDurationMs: 220 }),
		sample('optimized', { preparationLeadMs: 200, usefulOverlapMs: 120, decisionGapMs: 2, bodyActionDurationMs: 220 }),
	]);
	assert.equal(summary.pairedDeltaOptimizedMinusBaselineMs.preparationLeadMs.count, 0);
	assert.equal(summary.pairedDeltaOptimizedMinusBaselineMs.preparationLeadMs.missing, 1);
	assert.equal(summary.pairedDeltaOptimizedMinusBaselineMs.usefulOverlapMs.count, 0);
	assert.equal(summary.pairedDeltaOptimizedMinusBaselineMs.decisionGapMs.p50, -98);
});

test('real executor trial measures explicit successor handoff and body continuity', async () => {
	const baselineLike = await runNativeRealtimeTrial(NativeProgramExecutor, {
		arm: 'baseline',
		repetition: 0,
		actionDurationMs: 220,
		config: { ...NATIVE_REALTIME_WORKLOAD, planningLeadMs: undefined },
		pairingKey: 'pair-1',
		order: 1,
	});
	assert.equal(baselineLike.status, 'passed');
	assert.equal(baselineLike.observed.nativeEventObserved, false);
	assert.equal(baselineLike.observed.preparationTrigger, 'program_completion');
	assert.equal(baselineLike.observed.bodyActionCount, 2);
	assert.equal(baselineLike.observed.controllerFollowed, true);
	assert.ok(baselineLike.metrics.decisionGapMs >= 80, `expected completion-triggered gap, got ${baselineLike.metrics.decisionGapMs}`);
	assert.equal(baselineLike.metrics.usefulOverlapMs, null);

	const optimized = await runNativeRealtimeTrial(NativeProgramExecutor, {
		arm: 'optimized',
		repetition: 0,
		actionDurationMs: 220,
		config: NATIVE_REALTIME_WORKLOAD,
		pairingKey: 'pair-1',
		order: 2,
	});
	assert.equal(optimized.status, 'passed');
	assert.equal(optimized.observed.nativeEventObserved, true);
	assert.equal(optimized.observed.preparationTrigger, 'native_event');
	assert.equal(optimized.observed.bodyContinuity, true);
	assert.ok(optimized.metrics.usefulOverlapMs > 0);
	assert.ok(optimized.metrics.decisionGapMs >= 0,
		`successor handoff gap must be non-negative, got ${optimized.metrics.decisionGapMs}`);
});
