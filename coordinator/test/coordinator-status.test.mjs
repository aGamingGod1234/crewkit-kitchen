import assert from 'node:assert/strict';
import test from 'node:test';

import { buildCoordinatorStatus } from '../src/coordinator-status.mjs';

const pressure = {
	active: 0, pending: 0, maxConcurrent: 4, maxPending: 12, warning: false,
	mode: 'fixed', configuredTarget: 4, target: 4, minConcurrency: 4, maxConcurrency: 16,
	urgentReserve: 1, ordinaryActiveLimit: 3, activeOrdinary: 0, activeUrgent: 0,
	pendingOrdinary: 0, pendingUrgent: 0, growthCount: 0, backoffCount: 0,
	lastChangeReason: 'initial', healthyCompletions: 0, ordinaryReservationRejections: 0,
	urgentReservationRejections: 0,
};

test('coordinator status publishes exact profiles, epochs, runtime generation, and independent recovery components', () => {
	const generation = 'a'.repeat(64);
	const status = buildCoordinatorStatus({
		reconciled: true,
		records: [{ agentId: 'luna', provider: 'codex', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'priority', state: 'ACTING' }],
		supportedAgentIds: new Set(['luna']),
		readyStates: new Set(['ACTING']),
		pressure,
		healthSnapshots: [{ provider: 'codex', model: 'gpt-5.6-luna', operation: 'decide', count: 1, p50Ms: 20, p95Ms: 30, failureRate: 0, circuit: 'closed' }],
		latencies: [],
		bridgeSessionEpoch: 7,
		runtimeGeneration: generation,
		components: [{
			component: 'provider:codex', state: 'degraded', fallbackMode: 'last_valid', boundary: 'create',
			failureCode: 'PROVIDER_TIMEOUT', consecutiveFailureCount: 2, nextProbeAtEpochMs: 4_000,
			generation: 3, lastRecoveryAtEpochMs: null,
		}],
	});

	assert.deepEqual(status.profiles, [{ agentId: 'luna', provider: 'codex', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'priority' }]);
	assert.equal(status.bridgeSessionEpoch, 7);
	assert.equal(status.runtimeGeneration, generation);
	assert.deepEqual(status.components.find(({ component }) => component === 'provider:codex'), {
		component: 'provider:codex', state: 'degraded', fallbackMode: 'last_valid', boundary: 'create',
		failureCode: 'PROVIDER_TIMEOUT', consecutiveFailureCount: 2, nextProbeAtEpochMs: 4_000,
		generation: 3, lastRecoveryAtEpochMs: null,
	});
	assert.equal(status.components.find(({ component }) => component === 'bridge').state, 'ready');
});

test('missing or hostile optional component data is omitted without degrading core status', () => {
	assert.doesNotThrow(() => buildCoordinatorStatus({
		reconciled: false, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [], latencies: [], bridgeSessionEpoch: 1, runtimeGeneration: 'not-a-generation',
		components: [null, { component: 'voice', get state() { throw new Error('optional voice unavailable'); } }],
	}));
	const status = buildCoordinatorStatus({
		reconciled: false, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [], latencies: [], bridgeSessionEpoch: 1, runtimeGeneration: 'not-a-generation', components: [],
	});
	assert.equal(status.runtimeGeneration, null);
	assert.deepEqual(status.components.map(({ component }) => component), ['bridge']);
});
