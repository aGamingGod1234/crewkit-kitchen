import { DEFAULT_SERVICE_TIER } from './constants.mjs';

const RUNTIME_GENERATION = /^[0-9a-f]{64}$/;
const COMPONENT_STATES = new Set(['ready', 'degraded', 'backoff', 'blocked_retryable', 'unknown']);

/** Builds the bounded public recovery snapshot without trusting optional components. */
export function buildCoordinatorStatus({
	reconciled,
	records,
	supportedAgentIds,
	readyStates,
	pressure,
	healthSnapshots,
	latencies,
	bridgeSessionEpoch,
	runtimeGeneration,
	components = [],
}) {
	const profiles = records
		.filter((record) => supportedAgentIds.has(record.agentId))
		.map((record) => ({
			agentId: record.agentId,
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		}))
		.sort((left, right) => left.agentId.localeCompare(right.agentId));
	const recovery = new Map();
	recovery.set('bridge', bridgeComponent(Boolean(reconciled), bridgeSessionEpoch));
	if (Array.isArray(components)) {
		for (const candidate of components.slice(0, 32)) {
			const component = safeComponent(candidate);
			if (component !== null && component.component !== 'bridge') recovery.set(component.component, component);
		}
	}
	return {
		reconciled: Boolean(reconciled),
		profiles,
		supportedProfileCount: profiles.length,
		rosterReadyCount: records.filter((record) => supportedAgentIds.has(record.agentId) && readyStates.has(record.state)).length,
		rosterCount: records.length,
		scheduler: { ...pressure },
		circuits: Array.isArray(healthSnapshots) ? healthSnapshots.slice(0, 32) : [],
		latencies: Array.isArray(latencies) ? latencies.slice(0, 16) : [],
		bridgeSessionEpoch: nonnegativeIntegerOrZero(bridgeSessionEpoch),
		runtimeGeneration: typeof runtimeGeneration === 'string' && RUNTIME_GENERATION.test(runtimeGeneration) ? runtimeGeneration : null,
		components: [...recovery.values()].sort((left, right) => left.component.localeCompare(right.component)).slice(0, 32),
	};
}

export function providerRecoveryComponents(value) {
	if (!Array.isArray(value)) return [];
	const result = [];
	for (const recovery of value.slice(0, 16)) {
		try {
			if (recovery === null || typeof recovery !== 'object' || typeof recovery.provider !== 'string') continue;
			result.push({
				component: `provider:${recovery.provider}`,
				state: recovery.state === 'live' ? 'ready' : recovery.state === 'idle' ? 'unknown' : recovery.state,
				fallbackMode: recovery.fallbackMode ?? null,
				boundary: recovery.boundary ?? null,
				failureCode: recovery.failureCode ?? null,
				consecutiveFailureCount: recovery.consecutiveFailureCount ?? 0,
				nextProbeAtEpochMs: recovery.nextProbeAtEpochMs ?? null,
				generation: recovery.generation ?? 0,
				lastRecoveryAtEpochMs: recovery.lastRecoveryAtEpochMs ?? null,
			});
		} catch { /* optional provider status is observational */ }
	}
	return result;
}

function bridgeComponent(reconciled, generation) {
	return {
		component: 'bridge',
		state: reconciled ? 'ready' : 'degraded',
		fallbackMode: reconciled ? null : 'waiting',
		boundary: reconciled ? null : 'bridge_reconciliation',
		failureCode: reconciled ? null : 'RECONCILING',
		consecutiveFailureCount: 0,
		nextProbeAtEpochMs: null,
		generation: nonnegativeIntegerOrZero(generation),
		lastRecoveryAtEpochMs: null,
	};
}

function safeComponent(value) {
	try {
		if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
		const component = boundedOptionalText(value.component, false);
		const state = boundedOptionalText(value.state, false);
		if (component === null || state === null || !COMPONENT_STATES.has(state)) return null;
		return {
			component,
			state,
			fallbackMode: boundedOptionalText(value.fallbackMode),
			boundary: boundedOptionalText(value.boundary),
			failureCode: boundedOptionalText(value.failureCode),
			consecutiveFailureCount: nonnegativeIntegerOrZero(value.consecutiveFailureCount),
			nextProbeAtEpochMs: nullableNonnegativeInteger(value.nextProbeAtEpochMs),
			generation: nonnegativeIntegerOrZero(value.generation),
			lastRecoveryAtEpochMs: nullableNonnegativeInteger(value.lastRecoveryAtEpochMs),
		};
	} catch {
		return null;
	}
}

function boundedOptionalText(value, nullable = true) {
	if (value === null || value === undefined) return nullable ? null : null;
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > 128) return null;
	return value;
}

function nullableNonnegativeInteger(value) {
	return value === null || value === undefined ? null : nonnegativeIntegerOrZero(value);
}

function nonnegativeIntegerOrZero(value) {
	return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
