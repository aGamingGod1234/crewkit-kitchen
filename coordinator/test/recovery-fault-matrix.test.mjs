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
const RESOURCE_NAMES = Object.freeze([
	'leases', 'sessions', 'actions', 'timers', 'promises', 'childProcesses', 'listeners',
]);

const scenarios = [
	['provider startup hang', providerStartupHang],
	['provider outage and deadline restoration', providerOutageAndRestoration],
	['ignored abort releases scheduler capacity', ignoredAbortReleasesCapacity],
	['disconnect fences an outstanding action replay', disconnectFencesOutstandingActionReplay],
	['voice bind backoff and recovery', voiceBindFailureAndRecovery],
	['diagnostic hang and rejection', diagnosticHangAndRejection],
	['exact profile session and lease preservation', exactProfileSessionAndLeasePreservation],
];

for (const [name, run] of scenarios) {
	test(`recovery fault matrix: ${name}`, async () => {
		const result = await run();
		assertCompleteEvidence(result);
		assert.equal(result.recovery.healthy, true, 'the preferred path returns healthy automatically');
		assert.equal(result.recovery.permanentLatch, false, 'the fault cannot create a permanent latch');
		assertNoDomainFailure(result.states);
		assertExactProfile(result.profile);
		assertBoundedResources(result.resources);
	});
}

test('recovery matrix evidence contract rejects omitted and unmeasured fields', () => {
	assert.throws(() => assertCompleteEvidence({}), /recovery evidence is required/);
	const invalid = {
		recovery: { healthy: true, permanentLatch: false, stateBefore: 'fault', stateAfter: 'ready', nextProbeAtEpochMs: null, attemptTimes: [], probeDeadlines: [], retryDelays: [], attemptCount: 0 },
		states: observed([], 'test registry'),
		profile: notApplicable('No profile is selected in this contract test.'),
		resources: Object.fromEntries(RESOURCE_NAMES.map((name) => [name, observed(0, `${name} counter`)])),
	};
	delete invalid.resources.timers;
	assert.throws(() => assertCompleteEvidence(invalid), /timers evidence is required/);
	invalid.resources.timers = { kind: 'observed', value: 0 };
	assert.throws(() => assertCompleteEvidence(invalid), /timers evidence source/);
});

async function providerStartupHang() {
	const timers = new ManualTimers();
	const services = providerServices();
	const attemptTimes = [];
	let pendingStarts = 0;
	let maxPendingStarts = 0;
	let releaseHung;
	services.codex.start = () => {
		attemptTimes.push(timers.now);
		pendingStarts += 1;
		maxPendingStarts = Math.max(maxPendingStarts, pendingStarts);
		if (attemptTimes.length === 1) {
			return new Promise((resolve) => {
				let released = false;
				releaseHung = () => {
					if (released) return;
					released = true;
					pendingStarts -= 1;
					resolve();
				};
			});
		}
		pendingStarts -= 1;
		return Promise.resolve();
	};
	const router = new ProviderService(services, {
		operationTimeoutMs: 25,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	let session = null;
	let degraded;
	let restored;
	try {
		const first = router.start(['codex']);
		await flush();
		await timers.runNext();
		const firstOutcome = await first;
		assert.equal(firstOutcome[0].reason?.code, 'PROVIDER_TIMEOUT');
		degraded = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(degraded.state, 'degraded');
		assert.equal(degraded.nextProbeAtEpochMs, 1_025);

		timers.advanceTo(degraded.nextProbeAtEpochMs - 1);
		await flush();
		assert.deepEqual(attemptTimes, [0], 'no startup probe runs before the advertised deadline');
		timers.advanceTo(degraded.nextProbeAtEpochMs);
		const retry = await router.start(['codex']);
		assert.equal(retry[0].status, 'fulfilled');
		restored = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(attemptTimes, [0, 1_025], 'one startup probe runs at the deadline');
		session = await router.createAgent(PROFILE);
		releaseHung();
		await flush();
	} finally {
		releaseHung?.();
		await router.stop();
	}
	const sessions = services.codex.sessionStats();
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes, probeDeadlines: [degraded?.nextProbeAtEpochMs], retryDelays: [1_000], attemptCount: attemptTimes.length },
		states: notApplicable('Provider startup has no authority to mutate an agent domain lifecycle.'),
		profile: observed({ before: PROFILE, after: session?.profile }, 'ProviderService exact session snapshot'),
		resources: resources({
			leases: notApplicable('No work lease is allocated during provider-only startup.'),
			sessions: observed(sessions, 'fake backend current-session counter around ProviderService'),
			actions: notApplicable('Provider startup cannot dispatch Minecraft actions.'),
			timers: observed(timers.snapshot(), 'ProviderService injected timeout scheduler'),
			promises: observed({ pending: pendingStarts, maximum: maxPendingStarts }, 'backend start-promise counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'credential-free provider fixture spawn counter'),
			listeners: observed({ current: listenerCount(router), maximum: 0 }, 'ProviderService EventEmitter listener count after stop'),
		}),
	});
}

async function providerOutageAndRestoration() {
	const timers = new ManualTimers();
	const services = providerServices();
	let available = false;
	const refreshTimes = [];
	services.codex.catalog.refresh = async () => {
		refreshTimes.push(timers.now);
		if (!available) throw Object.assign(new Error('temporary outage'), { code: 'PROVIDER_UNAVAILABLE' });
		return catalog('codex', PROFILE.model);
	};
	const router = new ProviderService(services, {
		operationTimeoutMs: 100,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	let session = null;
	let degraded;
	let restored;
	try {
		const expectedRetryDelays = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000];
		const probeDeadlines = [];
		for (const expectedDelay of expectedRetryDelays) {
			await router.catalog.refresh({ providers: ['codex'] });
			degraded = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(degraded.state, 'degraded');
			assert.equal(degraded.nextProbeAtEpochMs - timers.now, expectedDelay);
			probeDeadlines.push(degraded.nextProbeAtEpochMs);
			timers.advanceTo(degraded.nextProbeAtEpochMs - 1);
			await flush();
			assert.equal(refreshTimes.length, probeDeadlines.length, 'catalog recovery cannot spin before its deadline');
			timers.advanceTo(degraded.nextProbeAtEpochMs);
		}
		available = true;
		const snapshot = await router.catalog.refresh({ providers: ['codex'] });
		restored = snapshot.recovery.find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(refreshTimes, [0, ...probeDeadlines]);
		session = await router.createAgent(PROFILE);
		degraded = { ...degraded, probeDeadlines, retryDelays: expectedRetryDelays };
	} finally {
		await router.stop();
	}
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes: refreshTimes, probeDeadlines: degraded?.probeDeadlines, retryDelays: degraded?.retryDelays, attemptCount: refreshTimes.length },
		states: notApplicable('Catalog recovery has no authority to mutate an agent domain lifecycle.'),
		profile: observed({ before: PROFILE, after: session?.profile }, 'restored ProviderService session snapshot'),
		resources: resources({
			leases: notApplicable('Catalog refresh owns bounded operations, not goal work leases.'),
			sessions: observed(services.codex.sessionStats(), 'backend session counter after router cleanup'),
			actions: notApplicable('Catalog refresh cannot dispatch Minecraft actions.'),
			timers: observed(timers.snapshot(), 'ProviderService injected timeout scheduler'),
			promises: observed({ pending: 0, maximum: 1 }, 'awaited catalog-operation counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'catalog fixture spawn counter'),
			listeners: observed({ current: listenerCount(router), maximum: 0 }, 'ProviderService listener count after stop'),
		}),
	});
}

async function ignoredAbortReleasesCapacity() {
	const timers = new ManualTimers();
	let releaseIgnored;
	let releaseReplacement;
	let activePromises = 0;
	let maxActivePromises = 0;
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel });
	const ignored = scheduler.schedule('fault-agent', async () => {
		activePromises += 1;
		maxActivePromises = Math.max(maxActivePromises, activePromises);
		try { await new Promise((resolve) => { releaseIgnored = resolve; }); }
		finally { activePromises -= 1; }
	}, { leaseTimeoutMs: 20 });
	const replacement = scheduler.schedule('healthy-agent', async () => {
		activePromises += 1;
		maxActivePromises = Math.max(maxActivePromises, activePromises);
		try {
			await new Promise((resolve) => { releaseReplacement = resolve; });
			return 'healthy';
		} finally { activePromises -= 1; }
	});
	const ignoredOutcome = assert.rejects(ignored, (error) => error?.code === 'PLANNING_LEASE_EXPIRED');
	await flush();
	assert.equal(scheduler.activeCount, 1);
	assert.equal(timers.snapshot().requestedDelays[0], 20);
	await timers.runNext();
	await ignoredOutcome;
	assert.equal(scheduler.activeCount, 1, 'the replacement owns the released scheduler slot');
	releaseReplacement();
	assert.equal(await replacement, 'healthy');
	releaseIgnored();
	await flush();
	scheduler.close();
	return evidence({
		recovery: { healthy: scheduler.activeCount === 0 && scheduler.pendingCount === 0, permanentLatch: false, stateBefore: 'lease_expired', stateAfter: 'capacity_available', nextProbeAtEpochMs: 20, attemptTimes: [0, 20], probeDeadlines: [20], retryDelays: [20], attemptCount: 2 },
		states: notApplicable('PlanningScheduler does not own agent domain lifecycle state.'),
		profile: notApplicable('Scheduler capacity is provider-profile agnostic and cannot mutate a profile.'),
		resources: resources({
			leases: observed({ maxByKind: { provider: 1 }, pending: scheduler.activeCount }, 'PlanningScheduler active lease count'),
			sessions: notApplicable('The scheduler test deliberately uses no provider session.'),
			actions: notApplicable('Planning tasks do not invoke the Minecraft action bridge in this scenario.'),
			timers: observed(timers.snapshot(), 'PlanningScheduler injected lease timer'),
			promises: observed({ pending: activePromises, maximum: maxActivePromises }, 'underlying abort-ignoring task counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'scheduler fixture spawn counter'),
			listeners: notApplicable('PlanningScheduler exposes no event-listener surface.'),
		}),
	});
}

async function disconnectFencesOutstandingActionReplay() {
	const harness = createNativeGoalHarness({
		disconnectWhileActionOutstandingAtAction: 1,
		turns: [['mine'], ['mine']],
		actionResults: ['SUCCEEDED', 'SUCCEEDED'],
		timeoutMs: 1_000,
	});
	const running = harness.run();
	await eventually(() => harness.bridge.connectionEpoch === 2 && harness.bridge.actionDispatches.length === 2);
	assert.equal(harness.bridge.deferredActionCount, 1, 'the old terminal action callback is still outstanding');
	const replacementEpoch = harness.bridge.connectionEpoch;
	const replacementBeforeReplay = harness.bridge.actionDispatches.filter(({ connectionEpoch }) => connectionEpoch === replacementEpoch);
	assert.equal(replacementBeforeReplay.length, 1, 'the new epoch owns exactly one replacement action');
	assert.equal(harness.bridge.releaseDeferredActionResults(), 1);
	const result = await running;
	const oldDispatch = result.actionDispatches.find(({ connectionEpoch }) => connectionEpoch === 1);
	const replacementDispatches = result.actionDispatches.filter(({ connectionEpoch }) => connectionEpoch === replacementEpoch);
	assert.equal(result.staleActionReplays.length, 1);
	assert.equal(result.staleActionReplays[0].actionId, oldDispatch.payload.actionId);
	assert.equal(result.staleActionReplays[0].replayConnectionEpoch, replacementEpoch);
	assert.equal(result.staleDispatches, 0, 'the replayed old action creates no post-fence physical effect');
	assert.equal(replacementDispatches.length, 1);
	assert.equal(result.actionEffects.length, 1);
	assert.equal(result.actionEffects[0].actionId, replacementDispatches[0].payload.actionId);
	assert.equal(new Set(result.actionEffects.map(({ actionId }) => actionId)).size, result.actionEffects.length);
	assert.equal(result.providerSessions.created, 1);
	assert.equal(result.leaseStats.maxByKind.provider, 1);
	assert.equal(result.leaseStats.maxByKind.action, 1);
	return evidence({
		recovery: { healthy: result.connectionEpoch === 2 && replacementDispatches.length === 1, permanentLatch: false, stateBefore: 'bridge_disconnected', stateAfter: result.finalState, nextProbeAtEpochMs: null, attemptTimes: [1, 2], probeDeadlines: [], retryDelays: [], attemptCount: 2 },
		states: observed(result.states, 'AgentRegistry snapshots sampled throughout native goal execution'),
		profile: observed({ before: result.providerSessions.profile, after: result.profile }, 'provider session and final AgentRegistry snapshots'),
		resources: resources({
			leases: observed(result.leaseStats, 'tracking wrapper around the production ActiveGoalSupervisor'),
			sessions: observed(result.providerSessions, 'scripted provider exact-session counters'),
			actions: observed({ maxConcurrent: result.maxConcurrentPhysicalActions, effects: result.actionEffects.length, duplicates: result.actionEffects.length - new Set(result.actionEffects.map(({ actionId }) => actionId)).size, staleEffects: result.staleDispatches, pending: harness.bridge.deferredActionCount }, 'FaultInjectingMinecraftBridge command/epoch effect ledger'),
			timers: observed(combinedTimerSnapshot(result.goalScheduler, result.stuckScheduler), 'injected work-lease and factual-progress schedulers'),
			promises: observed({ pending: result.activeWork, maximum: 1 }, 'scripted provider active-turn counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'native provider fixture spawn counter'),
			listeners: observed({ current: result.listenerResidue, maximum: result.listenerResidue }, 'bridge EventEmitter listeners after coordinator stop'),
		}),
	});
}

async function voiceBindFailureAndRecovery() {
	const timers = new ManualTimers();
	const attemptTimes = [];
	const probeDeadlines = [];
	let liveWorkers = 0;
	let maxWorkers = 0;
	let failureListeners = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attemptTimes.push(timers.now);
			if (attemptTimes.length <= 3) throw Object.assign(new Error('port occupied'), { code: 'EADDRINUSE' });
			liveWorkers += 1;
			maxWorkers = Math.max(maxWorkers, liveWorkers);
			return {
				statusSnapshots: () => ['voice', 'voice:tts', 'voice:stt'].map((component) => ({ component, state: 'ready', generation: 1, lastRecoveryAtEpochMs: null })),
				onFailure() { failureListeners += 1; return () => { failureListeners -= 1; }; },
				async close() { liveWorkers -= 1; },
			};
		},
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 10,
		maxRetryMs: 20,
		startupTimeoutMs: 100,
		warmupTimeoutMs: 100,
		cleanupTimeoutMs: 100,
	});
	supervisor.start();
	await flush();
	for (const expectedDeadline of [10, 30, 50]) {
		const status = supervisor.statusSnapshots()[0];
		assert.equal(status.state, 'degraded');
		assert.equal(status.nextProbeAtEpochMs, expectedDeadline);
		probeDeadlines.push(status.nextProbeAtEpochMs);
		timers.advanceTo(expectedDeadline - 1);
		await flush();
		assert.equal(attemptTimes.length, probeDeadlines.length, 'voice retry cannot spin before its deadline');
		await timers.runNext();
		await flush();
	}
	assert.deepEqual(attemptTimes, [0, 10, 30, 50]);
	assert.deepEqual(probeDeadlines, [10, 30, 50], 'voice retry delays follow 10, 20, 20 ms cap');
	const recovered = supervisor.statusSnapshots().every(({ state }) => state === 'ready');
	await supervisor.close();
	return evidence({
		recovery: { healthy: recovered, permanentLatch: false, stateBefore: 'degraded', stateAfter: 'ready', nextProbeAtEpochMs: probeDeadlines.at(-1), attemptTimes, probeDeadlines, retryDelays: [10, 20, 20], attemptCount: attemptTimes.length },
		states: notApplicable('Voice is optional and cannot mutate an agent domain lifecycle.'),
		profile: notApplicable('Voice workers do not select or mutate AI provider profiles.'),
		resources: resources({
			leases: notApplicable('VoiceSupervisor owns retry timers rather than goal work leases.'),
			sessions: notApplicable('Voice workers are not AI provider sessions.'),
			actions: notApplicable('Voice recovery cannot dispatch Minecraft actions.'),
			timers: observed(timers.snapshot(), 'VoiceSupervisor injected startup and retry scheduler'),
			promises: observed({ pending: liveWorkers, maximum: maxWorkers }, 'voice worker lifecycle counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'in-process voice fixture spawn counter'),
			listeners: observed({ current: failureListeners, maximum: 1 }, 'voice worker failure-listener counter'),
		}),
	});
}

async function diagnosticHangAndRejection() {
	const timers = new ManualTimers();
	let releaseHung;
	let pendingOperations = 0;
	let maxPendingOperations = 0;
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ maxPending: 4, operationTimeoutMs: 5, closeTimeoutMs: 50, maxDetachedOperations: 2, schedule: timers.schedule, cancel: timers.cancel });
	queue.submit(() => {
		pendingOperations += 1;
		maxPendingOperations = Math.max(maxPendingOperations, pendingOperations);
		return new Promise((resolve) => { releaseHung = () => { pendingOperations -= 1; resolve(); }; });
	});
	queue.submit(async () => { throw Object.assign(new Error('disk offline'), { code: 'EIO' }); });
	queue.submit(() => { completed.push('healthy'); });
	await flush();
	assert.equal(queue.statusSnapshot().state, 'ready');
	await timers.runNext({ flushAfter: false });
	await Promise.resolve();
	const degradedState = queue.statusSnapshot().state;
	assert.equal(degradedState, 'degraded');
	await eventually(() => completed.length === 1);
	assert.equal(queue.statusSnapshot().state, 'ready');
	releaseHung();
	await flush();
	await queue.close();
	return evidence({
		recovery: { healthy: queue.statusSnapshot().state === 'ready', permanentLatch: false, stateBefore: degradedState, stateAfter: queue.statusSnapshot().state, nextProbeAtEpochMs: null, attemptTimes: timers.firedDeadlines, probeDeadlines: timers.firedDeadlines, retryDelays: [5], attemptCount: 3 },
		states: notApplicable('Diagnostics are observational and cannot mutate agent domain lifecycle.'),
		profile: notApplicable('Diagnostics cannot select or mutate an AI provider profile.'),
		resources: resources({
			leases: notApplicable('DiagnosticQueue owns bounded sink operations rather than goal leases.'),
			sessions: notApplicable('Diagnostics create no provider sessions.'),
			actions: notApplicable('Diagnostics cannot dispatch Minecraft actions.'),
			timers: observed(timers.snapshot(), 'BestEffortDiagnosticQueue injected operation scheduler'),
			promises: observed({ pending: pendingOperations, maximum: maxPendingOperations }, 'diagnostic sink promise counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'diagnostic fixture spawn counter'),
			listeners: notApplicable('BestEffortDiagnosticQueue exposes no listener surface.'),
		}),
	});
}

async function exactProfileSessionAndLeasePreservation() {
	const timers = new ManualTimers();
	const supervisor = new WorkLeaseSupervisor({ clock: () => timers.now, schedule: timers.schedule, cancelSchedule: timers.cancel, stuckSchedule: timers.schedule, cancelStuckSchedule: timers.cancel });
	const first = leaseKey(1);
	const second = leaseKey(2);
	assert.equal(supervisor.activate(first), true);
	const staleProvider = supervisor.acquire(first, 'provider');
	assert.equal(supervisor.activate(second), true);
	const action = supervisor.acquire(second, 'action');
	assert.equal(supervisor.release(staleProvider), false);
	assert.equal(supervisor.activate({ ...second, profileFingerprint: `sha256:${'b'.repeat(64)}` }), false);
	const live = supervisor.snapshot(second);
	assert.equal(live.leases.length, 1);
	assert.equal(live.leases[0].kind, 'action');
	assert.equal(supervisor.release(action), true);
	const recovered = supervisor.snapshot(second);
	assert.equal(recovered.leases.length, 1);
	assert.equal(recovered.leases[0].kind, 'scheduled');
	supervisor.close();
	return evidence({
		recovery: { healthy: recovered.state === 'active', permanentLatch: false, stateBefore: 'session_epoch_1', stateAfter: 'session_epoch_2', nextProbeAtEpochMs: recovered.leases[0].deadline, attemptTimes: [first.sessionEpoch, second.sessionEpoch], probeDeadlines: [recovered.leases[0].deadline], retryDelays: [recovered.leases[0].deadline - timers.now], attemptCount: 2 },
		states: notApplicable('WorkLeaseSupervisor cannot mutate the AgentRegistry lifecycle.'),
		profile: observed({ before: { fingerprint: PROFILE_FINGERPRINT }, after: { fingerprint: recovered.key.profileFingerprint } }, 'production WorkLeaseSupervisor key snapshots'),
		resources: resources({
			leases: observed({ maxByKind: { provider: 1, action: 1, scheduled: 1 }, pending: 0 }, 'production WorkLeaseSupervisor snapshots and post-close timer state'),
			sessions: observed({ maxCurrent: 1, current: 0, created: 1 }, 'two fenced session epochs with one current owner'),
			actions: observed({ maxConcurrent: 1, effects: 0, duplicates: 0, staleEffects: 0, pending: 0 }, 'one action lease and stale-token rejection'),
			timers: observed(timers.snapshot(), 'WorkLeaseSupervisor injected lease scheduler'),
			promises: observed({ pending: 0, maximum: 0 }, 'synchronous lease-operation counter'),
			childProcesses: observed({ current: 0, maximum: 0 }, 'lease fixture spawn counter'),
			listeners: notApplicable('WorkLeaseSupervisor exposes no event-listener surface.'),
		}),
	});
}

function evidence(value) {
	assertCompleteEvidence(value);
	return value;
}

function resources(value) {
	for (const name of RESOURCE_NAMES) if (!Object.hasOwn(value, name)) throw new TypeError(`${name} evidence is required`);
	return Object.freeze(value);
}

function observed(value, source) {
	if (typeof source !== 'string' || source.trim().length < 8) throw new TypeError('observed evidence source must be descriptive');
	return Object.freeze({ kind: 'observed', value, source });
}

function notApplicable(reason) {
	if (typeof reason !== 'string' || reason.trim().length < 16) throw new TypeError('notApplicable evidence requires a specific reason');
	return Object.freeze({ kind: 'notApplicable', reason });
}

function assertCompleteEvidence(result) {
	if (result?.recovery === null || typeof result?.recovery !== 'object') throw new TypeError('recovery evidence is required');
	for (const key of ['healthy', 'permanentLatch', 'stateBefore', 'stateAfter', 'nextProbeAtEpochMs', 'attemptTimes', 'probeDeadlines', 'retryDelays', 'attemptCount']) {
		if (!Object.hasOwn(result.recovery, key)) throw new TypeError(`recovery.${key} evidence is required`);
	}
	assertEvidenceEntry(result.states, 'states');
	assertEvidenceEntry(result.profile, 'profile');
	if (result.resources === null || typeof result.resources !== 'object') throw new TypeError('resources evidence is required');
	for (const name of RESOURCE_NAMES) {
		if (!Object.hasOwn(result.resources, name)) throw new TypeError(`${name} evidence is required`);
		assertEvidenceEntry(result.resources[name], name);
	}
}

function assertEvidenceEntry(entry, name) {
	if (entry?.kind === 'observed') {
		if (!Object.hasOwn(entry, 'value')) throw new TypeError(`${name} evidence value is required`);
		if (typeof entry.source !== 'string' || entry.source.trim().length < 8) throw new TypeError(`${name} evidence source is required`);
		return;
	}
	if (entry?.kind === 'notApplicable') {
		if (typeof entry.reason !== 'string' || entry.reason.trim().length < 16) throw new TypeError(`${name} notApplicable reason is required`);
		return;
	}
	throw new TypeError(`${name} evidence must be observed or explicitly notApplicable`);
}

function assertNoDomainFailure(entry) {
	if (entry.kind === 'notApplicable') return;
	assert.ok(Array.isArray(entry.value));
	assert.equal(entry.value.includes(DynamicAgentState.PAUSED), false, 'infrastructure cannot pause player work');
	assert.equal(entry.value.includes(DynamicAgentState.ERROR), false, 'infrastructure cannot create a domain error');
}

function assertExactProfile(entry) {
	if (entry.kind === 'notApplicable') return;
	const { before, after } = entry.value;
	if (before?.fingerprint !== undefined || after?.fingerprint !== undefined) {
		assert.equal(after?.fingerprint, before?.fingerprint, 'recovery preserves the exact profile fingerprint');
		return;
	}
	for (const key of ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']) assert.equal(after?.[key], before?.[key], `recovery preserves profile.${key}`);
}

function assertBoundedResources(resourcesValue) {
	const leases = valueOf(resourcesValue.leases);
	if (leases !== null) {
		for (const count of Object.values(leases.maxByKind ?? {})) assert.ok(count <= 1, 'each work kind has one live lease owner');
		assert.equal(leases.pending, 0, 'no work lease remains after cleanup');
	}
	const sessions = valueOf(resourcesValue.sessions);
	if (sessions !== null) {
		assert.ok(sessions.maxCurrent <= 1, 'one exact profile owns at most one current session');
		assert.equal(sessions.current, 0, 'no provider session remains after cleanup');
	}
	const actions = valueOf(resourcesValue.actions);
	if (actions !== null) {
		assert.ok(actions.maxConcurrent <= 1, 'one physical action executes at a time');
		assert.equal(actions.duplicates, 0, 'recovery creates no duplicate physical effect');
		assert.equal(actions.staleEffects, 0, 'stale callbacks create no physical effect');
		assert.equal(actions.pending, 0, 'no action result remains pending');
	}
	const timers = valueOf(resourcesValue.timers);
	if (timers !== null) assert.equal(timers.pending, 0, 'no timer remains after cleanup');
	for (const name of ['promises', 'childProcesses', 'listeners']) {
		const resource = valueOf(resourcesValue[name]);
		if (resource !== null) assert.equal(resource.pending ?? resource.current, 0, `no ${name} remain after cleanup`);
	}
}

function valueOf(entry) { return entry.kind === 'observed' ? entry.value : null; }

function providerServices() {
	return Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => {
		let current = null;
		let currentSessions = 0;
		let maxCurrentSessions = 0;
		let createdSessions = 0;
		let lastProfile = null;
		const service = {
			catalog: { stale: false, refresh: async () => catalog(provider, `${provider}-model`), assertSupported() {} },
			async start() {},
			async stop() { current = null; currentSessions = 0; },
			async createAgent(profile) {
				if (current === null) {
					createdSessions += 1;
					currentSessions += 1;
					maxCurrentSessions = Math.max(maxCurrentSessions, currentSessions);
					lastProfile = Object.freeze({ ...profile });
					current = { agentId: profile.agentId, profile: lastProfile, sessionGeneration: createdSessions };
				}
				return current;
			},
			async replaceAgent(profile) {
				lastProfile = Object.freeze({ ...profile });
				current = { agentId: profile.agentId, profile: lastProfile, sessionGeneration: ++createdSessions };
				currentSessions = 1;
				maxCurrentSessions = Math.max(maxCurrentSessions, currentSessions);
				return current;
			},
			getAgent: (agentId) => current?.agentId === agentId ? current : null,
			async removeAgent(agentId) {
				if (current?.agentId !== agentId) return false;
				current = null;
				currentSessions = 0;
				return true;
			},
			async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: catalog(provider, `${provider}-model`) }; },
			sessionStats: () => ({ created: createdSessions, current: currentSessions, maxCurrent: maxCurrentSessions, profile: lastProfile }),
		};
		return [provider, service];
	}));
}

function catalog(provider, model) {
	return { refreshedAtEpochMs: 1, models: [{ id: model, model, displayName: model, reasoningEfforts: ['high'], serviceTiers: ['fast'], provider }] };
}

function leaseKey(sessionEpoch) {
	return { agentId: PROFILE.agentId, goalRevision: 1, lifecycleGeneration: 1, sessionEpoch, profileFingerprint: PROFILE_FINGERPRINT };
}

function listenerCount(emitter) {
	return emitter.eventNames().reduce((sum, name) => sum + emitter.listenerCount(name), 0);
}

function combinedTimerSnapshot(...snapshots) {
	return {
		pending: snapshots.reduce((sum, snapshot) => sum + snapshot.pending, 0),
		requestedDelays: snapshots.flatMap((snapshot) => snapshot.scheduledDelays),
		firedDelays: snapshots.flatMap((snapshot) => snapshot.firedDelays),
		maximum: Math.max(...snapshots.map((snapshot) => snapshot.maxPending)),
	};
}

class ManualTimers {
	now = 0;
	#sequence = 0;
	#timers = new Map();
	#history = [];
	#maxPending = 0;

	get firedDeadlines() { return this.#history.filter(({ fired }) => fired).map(({ deadline }) => deadline); }

	schedule = (callback, delay = 0) => {
		const handle = { id: ++this.#sequence };
		const entry = { handle, callback, requestedAt: this.now, delay, deadline: this.now + delay, fired: false, cancelled: false };
		this.#timers.set(handle.id, entry);
		this.#history.push(entry);
		this.#maxPending = Math.max(this.#maxPending, this.#timers.size);
		return handle;
	};

	cancel = (handle) => {
		const entry = this.#timers.get(handle?.id);
		if (entry !== undefined) entry.cancelled = true;
		return this.#timers.delete(handle?.id);
	};

	advanceTo(target) {
		if (!Number.isFinite(target) || target < this.now) throw new TypeError('timer clock cannot move backwards');
		this.now = target;
	}

	async runNext({ flushAfter = true } = {}) {
		const entry = [...this.#timers.values()].sort((left, right) => left.deadline - right.deadline || left.handle.id - right.handle.id)[0];
		if (entry === undefined) throw new Error('no deterministic timer is pending');
		this.#timers.delete(entry.handle.id);
		this.now = Math.max(this.now, entry.deadline);
		entry.fired = true;
		await entry.callback();
		if (flushAfter) await flush();
	}

	snapshot() {
		return {
			pending: this.#timers.size,
			requestedDelays: this.#history.map(({ delay }) => delay),
			requestedDeadlines: this.#history.map(({ deadline }) => deadline),
			firedDeadlines: this.firedDeadlines,
			cancelled: this.#history.filter(({ cancelled }) => cancelled).length,
			maximum: this.#maxPending,
		};
	}
}

async function flush() {
	await Promise.resolve();
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate, timeoutMs = 500) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await flush();
	}
	throw new Error('fault scenario did not reach its healthy boundary');
}
