import assert from 'node:assert/strict';
import test from 'node:test';

import { ProviderHealthRegistry } from '../src/provider-health-registry.mjs';
import { createProviderTurnTelemetry } from '../src/provider-turn-telemetry.mjs';

test('provider telemetry is a strict redacted allowlist', () => {
	const telemetry = createProviderTurnTelemetry({
		provider: 'gemini',
		model: 'gemini-3.1-pro',
		operation: 'decide',
		attempt: 2,
		queueWaitMs: 25,
		durationMs: 12_000,
		error: Object.assign(new Error('secret prompt contents'), { code: 'planning timeout' }),
		timeout: true,
		retry: true,
		restart: false,
		prompt: 'must never survive',
		output: 'must never survive',
	});

	assert.deepEqual(Object.keys(telemetry), [
		'provider', 'model', 'operation', 'attempt', 'queueWaitMs', 'durationMs',
		'errorCode', 'timeout', 'retry', 'restart',
	]);
	assert.equal(telemetry.errorCode, 'PLANNING_TIMEOUT');
	assert.equal(JSON.stringify(telemetry).includes('secret'), false);
	assert.equal(JSON.stringify(telemetry).includes('prompt'), false);
	assert.equal(createProviderTurnTelemetry({ provider: 'gemini', model: 'pro', operation: 'decide', durationMs: 1, error: new Error('uncoded') }).errorCode, 'ERROR');
});

test('provider health uses deterministic percentiles and one half-open probe', () => {
	let now = 1_000;
	const registry = new ProviderHealthRegistry({
		minimumSamples: 4,
		failureRateToOpen: 0.5,
		cooldownMs: 500,
		now: () => now,
	});
	for (const [durationMs, errorCode] of [
		[100, null], [200, 'PROVIDER_UNAVAILABLE'], [300, null], [12_000, 'PLANNING_TIMEOUT'],
	]) {
		registry.record({ provider: 'gemini', model: 'pro', operation: 'decide', durationMs, errorCode });
	}

	const key = { provider: 'gemini', model: 'pro', operation: 'decide' };
	assert.deepEqual(registry.snapshot(key), {
		...key, count: 4, p50Ms: 200, p95Ms: 12_000,
		failureRate: 0.5, circuit: 'open',
	});
	assert.equal(registry.canAttempt(key, now), false);
	now += 500;
	assert.equal(registry.canAttempt(key, now), true, 'one probe starts after cooldown');
	assert.equal(registry.canAttempt(key, now), false, 'parallel probes are rejected');
	registry.record({ provider: 'gemini', model: 'pro', operation: 'decide', durationMs: 50, errorCode: null });
	assert.equal(registry.snapshot(key).circuit, 'closed');
	assert.equal(registry.canAttempt(key, now), true);
});

test('provider circuits and samples remain isolated by provider, model, and operation', () => {
	const registry = new ProviderHealthRegistry({ minimumSamples: 2, failureRateToOpen: 1 });
	registry.record({ provider: 'codex', model: 'sol', operation: 'decide', durationMs: 10, errorCode: 'FAILED' });
	registry.record({ provider: 'codex', model: 'sol', operation: 'decide', durationMs: 20, errorCode: 'FAILED' });
	registry.record({ provider: 'codex', model: 'sol', operation: 'create_agent', durationMs: 5, errorCode: null });
	registry.record({ provider: 'codex', model: 'spark', operation: 'decide', durationMs: 5, errorCode: null });
	registry.record({ provider: 'kimi', model: 'k2', operation: 'decide', durationMs: 30, errorCode: null });

	assert.equal(registry.snapshot({ provider: 'codex', model: 'sol', operation: 'decide' }).circuit, 'open');
	assert.equal(registry.snapshot({ provider: 'codex', model: 'sol', operation: 'create_agent' }).count, 1);
	assert.equal(registry.snapshot({ provider: 'codex', model: 'spark', operation: 'decide' }).count, 1);
	assert.equal(registry.snapshot({ provider: 'kimi', model: 'k2', operation: 'decide' }).circuit, 'closed');
	assert.equal(registry.canAttempt({ provider: 'kimi', model: 'k2', operation: 'decide' }), true);
});

test('steering cancellation and stale plans never dilute or open provider health', () => {
	const registry = new ProviderHealthRegistry({ minimumSamples: 2, failureRateToOpen: 0.5 });
	const key = { provider: 'codex', model: 'sol', operation: 'decide' };
	registry.record({ ...key, durationMs: 10, errorCode: 'PLAN_CANCELLED' });
	registry.record({ ...key, durationMs: 20, errorCode: 'STALE_PLAN' });
	assert.deepEqual(registry.snapshot(key), { ...key, count: 0, p50Ms: 0, p95Ms: 0, failureRate: 0, circuit: 'closed' });
	registry.record({ ...key, durationMs: 30, error: new Error('uncoded provider exception') });
	registry.record({ ...key, durationMs: 40, errorCode: 'PROVIDER_UNAVAILABLE' });
	assert.equal(registry.snapshot(key).circuit, 'open');
});
