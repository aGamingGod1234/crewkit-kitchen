import assert from 'node:assert/strict';
import test from 'node:test';

import { buildCoordinatorStatus } from '../src/coordinator-status.mjs';
import { ProviderTurnRecorder } from '../src/provider-turn-recorder.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const pressure = {
	active: 0, pending: 0, maxConcurrent: 4, maxPending: 12, warning: false,
	mode: 'fixed', configuredTarget: 4, target: 4, minConcurrency: 4, maxConcurrency: 16,
	urgentReserve: 1, ordinaryActiveLimit: 3, activeOrdinary: 0, activeUrgent: 0,
	pendingOrdinary: 0, pendingUrgent: 0, growthCount: 0, backoffCount: 0,
	lastChangeReason: 'initial', healthyCompletions: 0, ordinaryReservationRejections: 0,
	urgentReservationRejections: 0,
};

test('recorder recovery preserves cumulative capture loss through the public status wire', async () => {
	let failSink = true;
	let sinkCalls = 0;
	const recorder = new ProviderTurnRecorder({
		runId: 'capture-status', scenarioId: 'recovery', privatePath: null,
		publicSink: () => { sinkCalls += 1; if (failSink) throw new Error('sink unavailable'); },
	});
	const waitFor = async (predicate) => {
		for (let attempt = 0; attempt < 100; attempt += 1) {
			if (predicate()) return;
			await new Promise(setImmediate);
		}
		assert.fail('recorder did not settle');
	};
	const snapshot = () => buildCoordinatorStatus({
		reconciled: true, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [], latencies: [], bridgeSessionEpoch: 1, runtimeGeneration: null,
		components: [recorder.statusSnapshot()],
	});
	try {
		await recorder.record({});
		await waitFor(() => recorder.statusSnapshot().failedOperationCount === 1);
		assert.equal(snapshot().components.find(({ component }) => component === 'provider_audit').state, 'degraded');
		failSink = false;
		await recorder.record({});
		await waitFor(() => sinkCalls === 2 && recorder.statusSnapshot().state === 'ready');
		const payload = JSON.parse(JSON.stringify(snapshot()));
		const wire = validateProtocolV2Payload('coordinator_status', payload);
		assert.deepEqual(wire, payload);
		const audit = wire.components.find(({ component }) => component === 'provider_audit');
		assert.equal(audit.state, 'ready');
		assert.equal(audit.consecutiveFailureCount, 0);
		assert.equal(audit.failedOperationCount, 1);
		assert.equal(audit.droppedCount, 0);
		assert.equal(audit.incompleteCapture, true);
		for (const [field, invalid] of [
			['failedOperationCount', -1], ['failedOperationCount', null], ['failedOperationCount', Number.MAX_SAFE_INTEGER + 1],
			['droppedCount', 0.5], ['droppedCount', '1'], ['incompleteCapture', null], ['incompleteCapture', 'false'],
		]) {
			assert.throws(() => validateProtocolV2Payload('coordinator_status', {
				...payload, components: [{ ...audit, [field]: invalid }],
			}));
		}
		const { failedOperationCount, droppedCount, incompleteCapture, ...legacy } = audit;
		assert.deepEqual(validateProtocolV2Payload('coordinator_status', { ...payload, components: [legacy] }).components, [legacy]);
	} finally { await recorder.close(); }
});

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

test('hostile optional arrays and proxies are omitted without throwing', () => {
	const hostile = new Proxy([], { get() { throw new Error('hostile optional status'); } });
	let status;
	assert.doesNotThrow(() => {
		status = buildCoordinatorStatus({
			reconciled: true, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
			healthSnapshots: hostile, latencies: hostile, components: hostile,
			bridgeSessionEpoch: 2, runtimeGeneration: null,
		});
	});
	assert.deepEqual(status.circuits, []);
	assert.deepEqual(status.latencies, []);
	assert.deepEqual(status.components.map(({ component }) => component), ['bridge']);
});

test('voice lifecycle is represented as an independent truthful component', () => {
	const status = buildCoordinatorStatus({
		reconciled: true, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [], latencies: [], bridgeSessionEpoch: 3, runtimeGeneration: null,
		components: [{
			component: 'voice', state: 'degraded', fallbackMode: 'text', boundary: 'voice_provider',
			failureCode: 'STT_TIMEOUT', consecutiveFailureCount: 2, nextProbeAtEpochMs: 12_000,
			generation: 4, lastRecoveryAtEpochMs: 10_000,
		}],
	});
	assert.deepEqual(status.components.find(({ component }) => component === 'voice'), {
		component: 'voice', state: 'degraded', fallbackMode: 'text', boundary: 'voice_provider',
		failureCode: 'STT_TIMEOUT', consecutiveFailureCount: 2, nextProbeAtEpochMs: 12_000,
		generation: 4, lastRecoveryAtEpochMs: 10_000,
	});
});

test('optional status entries are detached own-data records and never retain hostile behavior', () => {
	let getterCalls = 0;
	const accessorCircuit = { model: 'm', operation: 'decide', count: 1, p50Ms: 1, p95Ms: 1, failureRate: 0, circuit: 'closed' };
	Object.defineProperty(accessorCircuit, 'provider', { enumerable: true, get() { getterCalls += 1; throw new Error('must not run'); } });
	const inheritedCircuit = Object.assign(Object.create({ inherited: true }), {
		provider: 'codex', model: 'm', operation: 'decide', count: 1, p50Ms: 1, p95Ms: 1, failureRate: 0, circuit: 'closed',
	});
	const symbolCircuit = {
		provider: 'codex', model: 'symbol', operation: 'decide', count: 1, p50Ms: 1, p95Ms: 1, failureRate: 0, circuit: 'closed',
		[Symbol('hostile')]: 'hidden',
	};
	const validCircuit = {
		provider: 'codex', model: 'gpt-5.6-sol', operation: 'decide', count: 2,
		p50Ms: 10, p95Ms: 20, failureRate: 0.5, circuit: 'half_open',
	};
	const validLatency = { operation: 'action_completion', count: 2, p50Ms: 10.5, p95Ms: 20.5 };
	const hostileLatency = Object.create(null, {
		operation: { enumerable: true, value: 'action_completion' },
		count: { enumerable: true, get() { getterCalls += 1; throw new Error('must not run'); } },
	});
	const hostileComponent = Object.create(null, {
		component: { enumerable: true, value: 'voice' },
		state: { enumerable: true, get() { getterCalls += 1; throw new Error('must not run'); } },
	});

	const status = buildCoordinatorStatus({
		reconciled: true, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [accessorCircuit, inheritedCircuit, symbolCircuit, validCircuit],
		latencies: [hostileLatency, validLatency], components: [hostileComponent],
		bridgeSessionEpoch: 4, runtimeGeneration: null,
	});
	validCircuit.model = 'mutated-after-build';
	validLatency.operation = 'mutated-after-build';
	assert.equal(getterCalls, 0);
	assert.deepEqual(status.circuits, [{
		provider: 'codex', model: 'gpt-5.6-sol', operation: 'decide', count: 2,
		p50Ms: 10, p95Ms: 20, failureRate: 0.5, circuit: 'half_open',
	}]);
	assert.deepEqual(status.latencies, [{ operation: 'action_completion', count: 2, p50Ms: 10.5, p95Ms: 20.5 }]);
	assert.doesNotThrow(() => JSON.stringify(status));
	assert.equal(getterCalls, 0);
});

test('invalid optional circuit and latency text or numbers are omitted', () => {
	const status = buildCoordinatorStatus({
		reconciled: true, records: [], supportedAgentIds: new Set(), readyStates: new Set(), pressure,
		healthSnapshots: [
			{ provider: 'x'.repeat(129), model: 'm', operation: 'decide', count: 1, p50Ms: 1, p95Ms: 1, failureRate: 0, circuit: 'closed' },
			{ provider: 'codex', model: 'm', operation: 'decide', count: 1, p50Ms: Number.NaN, p95Ms: 1, failureRate: 0, circuit: 'closed' },
		],
		latencies: [{ operation: 'action_completion', count: 1, p50Ms: -1, p95Ms: 2 }],
		components: [], bridgeSessionEpoch: 5, runtimeGeneration: null,
	});
	assert.deepEqual(status.circuits, []);
	assert.deepEqual(status.latencies, []);
});
