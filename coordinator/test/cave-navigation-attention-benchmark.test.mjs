import assert from 'node:assert/strict';
import test from 'node:test';
import { runBenchmark } from '../src/benchmark/cave-navigation-attention.mjs';

test('cave attention benchmark compares actual public native interfaces with identical authored controls', async () => {
  const result = await runBenchmark({ settings: { repetitions: 1, warmups: 0, actionMs: 5, decisionMs: 15, observationMs: 1 } });
  assert.equal(result.providerUsed, false);
  assert.equal(result.minecraftServerUsed, false);
  assert.equal(result.installedClientVerified, false);
  assert.equal(Object.keys(result.comparisons).length, 6);
  for (const [name, comparison] of Object.entries(result.comparisons)) {
    assert.equal(comparison.before.factualSuccesses, 1, name);
    assert.equal(comparison.after.factualSuccesses, 1, name);
    assert.equal(comparison.before.actionShapeSha256, comparison.after.actionShapeSha256, name);
    assert.deepEqual(comparison.before.planningTurns, [6], name);
    assert.deepEqual(comparison.after.planningTurns, [name.endsWith('predicate_opt_in') ? 0 : 6], name);
    assert.equal(comparison.rows.before[0].observations.length, 6, 'fresh post-result facts remain supplied');
    assert.equal(comparison.rows.after[0].observations.length, 6, 'predicate filters notification, not perception');
  }
  assert.equal(Object.keys(result.behaviorCases).length, 9);
  assert.ok(Object.values(result.behaviorCases).every(row => row.result === 'PASSED'));
  assert.equal(result.behaviorCases.blocked_route.reasonCode, 'PROGRAM_CHECKPOINT');
  assert.equal(result.behaviorCases.urgent_false_predicate.trigger, 'damage');
  assert.equal(result.behaviorCases.repeated_failure_false_predicate.trigger, 'action_failure');
});
