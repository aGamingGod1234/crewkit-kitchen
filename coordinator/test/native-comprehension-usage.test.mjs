import assert from 'node:assert/strict';
import test from 'node:test';
import { usageValue, deltaUsage, summarizeUsage, summarizeArms } from '../src/benchmark/native-input-comprehension.mjs';

const zero = usageValue({ inputTokens: 0, cachedInputTokens: 0, outputTokens: 0 });
const observed = usageValue({ inputTokens: 100, cachedInputTokens: 60, outputTokens: 10 });

test('missing or unchanged telemetry cannot establish a zero-token turn', () => {
	assert.equal(deltaUsage(zero, usageValue(null)).status, 'missing');
	assert.equal(deltaUsage(observed, observed, { updated: false }).inputTokens, null);
	assert.equal(summarizeUsage([]).uncachedInputTokens, null);
});

test('counter resets and inconsistent cache deltas remain unknown', () => {
	assert.equal(deltaUsage(observed, zero).status, 'counter_reset');
	assert.equal(deltaUsage(zero, usageValue({ inputTokens: 10, cachedInputTokens: 20, outputTokens: 0 })).status, 'inconsistent');
});

test('one incomplete pair prevents totals from looking like measured savings', () => {
	const complete = { arm: 'after', usage: deltaUsage(zero, observed), factsMatch: true, nativeCallsValid: true };
	assert.equal(summarizeUsage([complete]).uncachedInputTokens, 40);
	const incomplete = { ...complete, usage: deltaUsage(observed, observed, { updated: false }) };
	const totals = summarizeArms([complete, incomplete]).after;
	assert.equal(totals.factualPasses, 2);
	assert.equal(totals.status, 'incomplete');
	assert.equal(totals.inputTokens, null);
});
