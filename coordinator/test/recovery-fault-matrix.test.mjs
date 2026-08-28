import assert from 'node:assert/strict';
import test from 'node:test';

import { DynamicAgentState } from '../src/agent-registry.mjs';
import { BestEffortDiagnosticQueue } from '../src/best-effort-diagnostic-queue.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProviderService } from '../src/provider-service.mjs';
import { WorkLeaseSupervisor } from '../src/work-lease-supervisor.mjs';
import { VoiceSupervisor } from '../src/voice/voice-supervisor.mjs';
import { createNativeGoalHarness } from './fixtures/native-goal-harness.mjs';

const PROFILE = Object.freeze({
	agentId: 'fault-agent',
	provider: 'codex',
	model: 'gpt-5.6-sol',
	reasoningEffort: 'high',
	serviceTier: 'fast',
});
const PROFILE_FINGERPRINT = `sha256:${'a'.repeat(64)}`;

const scenarios = [
	['provider startup hang', providerStartupHang],
	['provider outage and restoration', providerOutageAndRestoration],
	['ignored abort releases scheduler capacity', ignoredAbortReleasesCapacity],
	['disconnect during active work', disconnectDuringActiveWork],
	['stale callback and action replay', staleCallbackAndActionReplay],
	['voice bind failure and recovery', voiceBindFailureAndRecovery],
	['diagnostic hang and rejection', diagnosticHangAndRejection],
	['exact profile session and lease preservation', exactProfileSessionAndLeasePreservation],
];

for (const [name, run] of scenarios) {
	test(`recovery fault matrix: ${name}`, async () => {
		const result = await run();
		assert.equal(result.recovered, true, 'the preferred path returns healthy automatically');
		assert.equal(result.permanentLatch, false, 'the fault cannot create a permanent latch');
		assert.equal(result.exactProfile, true, 'recovery preserves the complete selected profile');
		assert.equal(result.states.includes(DynamicAgentState.PAUSED), false, 'infrastructure cannot pause player work');
		assert.equal(result.states.includes(DynamicAgentState.ERROR), false, 'infrastructure cannot create a domain error');
		assert.ok(result.maxConcurrentLeases <= 1, 'one lifecycle owns at most one live lease of each kind');
		assert.ok(result.maxConcurrentSessions <= 1, 'one exact profile owns at most one current session');
		assert.ok(result.maxConcurrentActions <= 1, 'recovery never duplicates a physical action');
		assert.equal(result.pendingTimers, 0, 'recovery leaves no owned timers');
		assert.equal(result.pendingPromises, 0, 'recovery leaves no owned promises');
		assert.equal(result.childProcesses, 0, 'the deterministic matrix leaves no child process');
		assert.equal(result.listeners, 0, 'the deterministic matrix leaves no listener residue');
	});
}

async function providerStartupHang() {
	const timers = new ManualTimers();
	const services = providerServices();
	let startAttempt = 0;
	let releaseHung;
	services.codex.start = () => {
		startAttempt += 1;
		if (startAttempt === 1) return new Promise((resolve) => { releaseHung = resolve; });
		return Promise.resolve();
	};
	const router = new ProviderService(services, {
		operationTimeoutMs: 25,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	try {
		const first = router.start(['codex']);
		await flush();
		await timers.runNext();
		const firstOutcome = await first;
		assert.equal(firstOutcome[0].reason?.code, 'PROVIDER_TIMEOUT');
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'degraded');

		const secondOutcome = await router.start(['codex']);
		assert.equal(secondOutcome[0].status, 'fulfilled');
		const session = await router.createAgent(PROFILE);
		assert.deepEqual(session.profile, PROFILE);
		releaseHung();
		await flush();
		return contract({
			recovered: router.recoverySnapshot().find(({ provider }) => provider === 'codex').state === 'live',
			exactProfile: sameProfile(session.profile, PROFILE),
			maxConcurrentSessions: services.codex.maxCurrentSessions,
			pendingTimers: timers.pendingCount,
		});
	} finally {
		releaseHung?.();
		await router.stop();
	}
}

async function providerOutageAndRestoration() {
	const services = providerServices();
	let available = false;
	services.codex.catalog.refresh = async () => {
		if (!available) throw Object.assign(new Error('temporary outage'), { code: 'PROVIDER_UNAVAILABLE' });
		return catalog('codex', PROFILE.model);
	};
	const router = new ProviderService(services, { operationTimeoutMs: 100 });
	try {
		const degraded = await router.catalog.refresh({ providers: ['codex'] });
		assert.equal(degraded.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
		available = true;
		const restored = await router.catalog.refresh({ providers: ['codex'], force: true });
		const session = await router.createAgent(PROFILE);
		return contract({
			recovered: restored.recovery.find(({ provider }) => provider === 'codex').state === 'live',
			exactProfile: sameProfile(session.profile, PROFILE),
			maxConcurrentSessions: services.codex.maxCurrentSessions,
		});
	} finally {
		await router.stop();
	}
}

async function ignoredAbortReleasesCapacity() {
	const timers = new ManualTimers();
	let releaseIgnored;
	let releaseReplacement;
	let activeTasks = 0;
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1,
		maxPending: 1,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
	});
	const ignored = scheduler.schedule('fault-agent', async () => {
		activeTasks += 1;
		try { await new Promise((resolve) => { releaseIgnored = resolve; }); }
		finally { activeTasks -= 1; }
	}, { leaseTimeoutMs: 20 });
	const replacement = scheduler.schedule('healthy-agent', async () => {
		activeTasks += 1;
		try {
			await new Promise((resolve) => { releaseReplacement = resolve; });
			return 'healthy';
		} finally { activeTasks -= 1; }
	});
	const ignoredOutcome = assert.rejects(ignored, (error) => error?.code === 'PLANNING_LEASE_EXPIRED');
	await flush();
	await timers.runNext();
	await ignoredOutcome;
	assert.equal(scheduler.activeCount, 1, 'the replacement owns the one released scheduler slot');
	releaseReplacement();
	assert.equal(await replacement, 'healthy');
	assert.equal(scheduler.activeCount, 0);
	releaseIgnored();
	await flush();
	scheduler.close();
	return contract({
		recovered: true,
		maxConcurrentLeases: 1,
		maxConcurrentActions: 0,
		pendingTimers: timers.pendingCount,
		pendingPromises: activeTasks,
	});
}

async function disconnectDuringActiveWork() {
	const result = await createNativeGoalHarness({
		disconnectAtAction: 1,
		turns: [['mine'], ['observe'], ['mine']],
		actionResults: ['SUCCEEDED', 'SUCCEEDED'],
		timeoutMs: 250,
	}).run();
	return contract({
		recovered: result.actionCount === 2 && result.recoveries.includes('BRIDGE_DISCONNECTED'),
		states: result.states,
		maxConcurrentLeases: result.maxRecoveryHandles,
		maxConcurrentActions: 1,
		pendingTimers: result.goalScheduler.pending,
		pendingPromises: result.activeWork,
	});
}

async function staleCallbackAndActionReplay() {
	const result = await createNativeGoalHarness({
		staleCallbackAfterRevision: true,
		turns: [['mine'], ['finish']],
		timeoutMs: 250,
	}).run();
	return contract({
		recovered: result.staleDispatches === 0,
		states: result.states,
		maxConcurrentLeases: result.maxRecoveryHandles,
		maxConcurrentActions: 1,
		pendingTimers: result.goalScheduler.pending,
		pendingPromises: result.activeWork,
	});
}

async function voiceBindFailureAndRecovery() {
	const timers = new ManualTimers();
	let attempts = 0;
	let liveWorkers = 0;
	let maxWorkers = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attempts += 1;
			if (attempts === 1) throw Object.assign(new Error('port occupied'), { code: 'EADDRINUSE' });
			liveWorkers += 1;
			maxWorkers = Math.max(maxWorkers, liveWorkers);
			return {
				statusSnapshots: () => ['voice', 'voice:tts', 'voice:stt'].map((component) => ({ component, state: 'ready', generation: 1, lastRecoveryAtEpochMs: null })),
				async close() { liveWorkers -= 1; },
			};
		},
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 10,
		maxRetryMs: 10,
		startupTimeoutMs: 100,
		warmupTimeoutMs: 100,
		cleanupTimeoutMs: 100,
	});
	supervisor.start();
	await flush();
	assert.equal(supervisor.statusSnapshots()[0].state, 'degraded');
	await timers.runNext();
	await flush();
	const recovered = supervisor.statusSnapshots().every(({ state }) => state === 'ready');
	await supervisor.close();
	return contract({
		recovered,
		maxConcurrentSessions: maxWorkers,
		pendingTimers: timers.pendingCount,
		pendingPromises: liveWorkers,
	});
}

async function diagnosticHangAndRejection() {
	let releaseHung;
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({
		maxPending: 4,
		operationTimeoutMs: 5,
		closeTimeoutMs: 50,
		maxDetachedOperations: 2,
	});
	queue.submit(() => new Promise((resolve) => { releaseHung = resolve; }));
	queue.submit(async () => { throw Object.assign(new Error('disk offline'), { code: 'EIO' }); });
	queue.submit(() => { completed.push('healthy'); });
	await eventually(() => completed.length === 1);
	releaseHung();
	await flush();
	await queue.close();
	return contract({ recovered: queue.statusSnapshot().state === 'ready' && completed.length === 1 });
}

async function exactProfileSessionAndLeasePreservation() {
	const timers = new ManualTimers();
	const supervisor = new WorkLeaseSupervisor({
		clock: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		stuckSchedule: timers.schedule,
		cancelStuckSchedule: timers.cancel,
	});
	const first = leaseKey(1);
	const second = leaseKey(2);
	assert.equal(supervisor.activate(first), true);
	const staleProvider = supervisor.acquire(first, 'provider');
	assert.equal(supervisor.activate(second), true);
	const action = supervisor.acquire(second, 'action');
	assert.equal(supervisor.release(staleProvider), false);
	assert.equal(supervisor.activate({ ...second, profileFingerprint: `sha256:${'b'.repeat(64)}` }), false);
	assert.equal(supervisor.snapshot(second).leases.length, 1);
	assert.equal(supervisor.release(action), true);
	const recovered = supervisor.snapshot(second).state === 'active'
		&& supervisor.snapshot(second).leases.length === 1
		&& supervisor.snapshot(second).key.profileFingerprint === PROFILE_FINGERPRINT;
	supervisor.close();
	return contract({ recovered, maxConcurrentLeases: 1, pendingTimers: timers.pendingCount });
}

function contract(overrides = {}) {
	return {
		recovered: false,
		permanentLatch: false,
		exactProfile: true,
		states: [],
		maxConcurrentLeases: 0,
		maxConcurrentSessions: 0,
		maxConcurrentActions: 0,
		pendingTimers: 0,
		pendingPromises: 0,
		childProcesses: 0,
		listeners: 0,
		...overrides,
	};
}

function providerServices() {
	return Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => {
		let current = null;
		let liveSessions = 0;
		const service = {
			maxCurrentSessions: 0,
			catalog: {
				stale: false,
				refresh: async () => catalog(provider, `${provider}-model`),
				assertSupported() {},
			},
			async start() {},
			async stop() { current = null; liveSessions = 0; },
			async createAgent(profile) {
				if (current === null) {
					liveSessions += 1;
					service.maxCurrentSessions = Math.max(service.maxCurrentSessions, liveSessions);
					current = { agentId: profile.agentId, profile: Object.freeze({ ...profile }), sessionGeneration: 1 };
				}
				return current;
			},
			async replaceAgent(profile) {
				current = { agentId: profile.agentId, profile: Object.freeze({ ...profile }), sessionGeneration: (current?.sessionGeneration ?? 0) + 1 };
				liveSessions = 1;
				service.maxCurrentSessions = Math.max(service.maxCurrentSessions, liveSessions);
				return current;
			},
			getAgent: (agentId) => current?.agentId === agentId ? current : null,
			async removeAgent(agentId) {
				if (current?.agentId !== agentId) return false;
				current = null;
				liveSessions = 0;
				return true;
			},
			async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: catalog(provider, `${provider}-model`) }; },
		};
		return [provider, service];
	}));
}

function catalog(provider, model) {
	return {
		refreshedAtEpochMs: 1,
		models: [{ id: model, model, displayName: model, reasoningEfforts: ['high'], serviceTiers: ['fast'], provider }],
	};
}

function sameProfile(left, right) {
	return ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier'].every((key) => left?.[key] === right[key]);
}

function leaseKey(sessionEpoch) {
	return {
		agentId: PROFILE.agentId,
		goalRevision: 1,
		lifecycleGeneration: 1,
		sessionEpoch,
		profileFingerprint: PROFILE_FINGERPRINT,
	};
}

class ManualTimers {
	now = 0;
	#sequence = 0;
	#timers = new Map();

	get pendingCount() { return this.#timers.size; }

	schedule = (callback, delay = 0) => {
		const handle = { id: ++this.#sequence };
		this.#timers.set(handle.id, { handle, callback, deadline: this.now + delay });
		return handle;
	};

	cancel = (handle) => this.#timers.delete(handle?.id);

	async runNext() {
		const entry = [...this.#timers.values()].sort((left, right) => left.deadline - right.deadline || left.handle.id - right.handle.id)[0];
		if (entry === undefined) throw new Error('no deterministic timer is pending');
		this.#timers.delete(entry.handle.id);
		this.now = entry.deadline;
		await entry.callback();
		await flush();
	}
}

async function flush() {
	await Promise.resolve();
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate, timeoutMs = 250) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await flush();
	}
	throw new Error('fault scenario did not reach its healthy boundary');
}
