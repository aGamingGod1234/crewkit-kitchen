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
const INSTRUMENTED_VALUES = new WeakSet();

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

test('recovery matrix evidence contract rejects omitted, null, and fabricated measurements', () => {
	assert.throws(() => assertCompleteEvidence({}), /recovery evidence is required/);
	assert.throws(() => observed(null, 'null measurement'), /non-null instrumented value/);
	assert.throws(() => observed({ pending: 0, maximum: 0 }, 'hardcoded fixture counter'), /instrumented measurement/);
	const invalid = {
		recovery: { healthy: true, permanentLatch: false, stateBefore: 'fault', stateAfter: 'ready', nextProbeAtEpochMs: null, attemptTimes: [], probeDeadlines: [], retryDelays: [], attemptCount: 0 },
		states: measured([], 'test registry sampler'),
		profile: notApplicable('No profile is selected in this contract test.'),
		resources: Object.fromEntries(RESOURCE_NAMES.map((name) => [name, notApplicable(`${name} is deliberately absent from this evidence-contract-only fixture.`)])),
	};
	delete invalid.resources.timers;
	assert.throws(() => assertCompleteEvidence(invalid), /timers evidence is required/);
	invalid.resources.timers = { kind: 'observed', value: null, source: 'malicious all-null fixture' };
	assert.throws(() => assertCompleteEvidence(invalid), /non-null instrumented value/);
	invalid.resources.timers = { kind: 'notApplicable' };
	assert.throws(() => assertCompleteEvidence(invalid), /notApplicable reason/);
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
	const listeners = new ListenerGauge(router);
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
		await timers.runNext();
		restored = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(attemptTimes, [0, 1_025], 'one startup probe runs at the deadline');
		assert.equal(maxPendingStarts, 2, 'the timed-out start remains owned until its underlying promise settles');
		assert.equal(pendingStarts, 1, 'logical timeout does not fabricate settlement of the old start');
		session = await router.createAgent(PROFILE);
		releaseHung();
		await flush();
	} finally {
		releaseHung?.();
		await router.stop();
		listeners.sample();
	}
	const sessions = services.codex.sessionStats();
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes, probeDeadlines: [degraded?.nextProbeAtEpochMs], retryDelays: [1_000], attemptCount: attemptTimes.length },
		states: notApplicable('Provider startup has no authority to mutate an agent domain lifecycle.'),
		profile: measured({ before: PROFILE, after: session?.profile }, 'ProviderService exact session snapshot'),
		resources: resources({
			leases: notApplicable('No work lease is allocated during provider-only startup.'),
			sessions: measured(sessions, 'fake backend current-session counter around ProviderService'),
			actions: notApplicable('Provider startup cannot dispatch Minecraft actions.'),
			timers: measured(timers.snapshot(), 'ProviderService injected timeout and recovery scheduler'),
			promises: measured({ pending: pendingStarts, maximum: maxPendingStarts }, 'backend start-promise ownership counter'),
			childProcesses: notApplicable('This in-process provider fixture has no child-process creation capability.'),
			listeners: measured(listeners.snapshot(), 'ProviderService EventEmitter listener sampler'),
		}),
	});
}

async function providerOutageAndRestoration() {
	const timers = new ManualTimers();
	const services = providerServices();
	let available = false;
	const refreshTimes = [];
	let pendingRefreshes = 0;
	let maxPendingRefreshes = 0;
	services.codex.catalog.refresh = () => trackedOperation(async () => {
		refreshTimes.push(timers.now);
		if (!available) throw Object.assign(new Error('temporary outage'), { code: 'PROVIDER_UNAVAILABLE' });
		return catalog('codex', PROFILE.model);
	}, (delta) => {
		pendingRefreshes += delta;
		maxPendingRefreshes = Math.max(maxPendingRefreshes, pendingRefreshes);
	});
	const router = new ProviderService(services, {
		operationTimeoutMs: 100,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	const listeners = new ListenerGauge(router);
	let session = null;
	let degraded;
	let restored;
	try {
		const expectedRetryDelays = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000];
		const probeDeadlines = [];
		await router.catalog.refresh({ providers: ['codex'] });
		for (let index = 0; index < expectedRetryDelays.length; index += 1) {
			const expectedDelay = expectedRetryDelays[index];
			degraded = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(degraded.state, 'degraded');
			assert.equal(degraded.nextProbeAtEpochMs - timers.now, expectedDelay);
			probeDeadlines.push(degraded.nextProbeAtEpochMs);
			timers.advanceTo(degraded.nextProbeAtEpochMs - 1);
			await flush();
			assert.equal(refreshTimes.length, index + 1, 'catalog recovery cannot spin before its deadline');
			if (index === expectedRetryDelays.length - 1) available = true;
			await timers.runNext();
			assert.equal(refreshTimes.length, index + 2, 'production recovery executes exactly one probe at the deadline');
		}
		restored = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(refreshTimes, [0, ...probeDeadlines]);
		session = await router.createAgent(PROFILE);
		degraded = { ...degraded, probeDeadlines, retryDelays: expectedRetryDelays };
	} finally {
		await router.stop();
		listeners.sample();
	}
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes: refreshTimes, probeDeadlines: degraded?.probeDeadlines, retryDelays: degraded?.retryDelays, attemptCount: refreshTimes.length },
		states: notApplicable('Catalog recovery has no authority to mutate an agent domain lifecycle.'),
		profile: measured({ before: PROFILE, after: session?.profile }, 'restored ProviderService session snapshot'),
		resources: resources({
			leases: notApplicable('Catalog refresh owns bounded operations, not goal work leases.'),
			sessions: measured(services.codex.sessionStats(), 'backend session counter after router cleanup'),
			actions: notApplicable('Catalog refresh cannot dispatch Minecraft actions.'),
			timers: measured(timers.snapshot(), 'ProviderService injected timeout and recovery scheduler'),
			promises: measured({ pending: pendingRefreshes, maximum: maxPendingRefreshes }, 'catalog refresh-promise ownership counter'),
			childProcesses: notApplicable('This in-process catalog fixture has no child-process creation capability.'),
			listeners: measured(listeners.snapshot(), 'ProviderService EventEmitter listener sampler'),
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
	const schedulerLeases = new SchedulerLeaseGauge(scheduler);
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
	schedulerLeases.sample();
	assert.equal(scheduler.activeCount, 1);
	assert.equal(timers.snapshot().requestedDelays[0], 20);
	await timers.runNext();
	await ignoredOutcome;
	assert.equal(scheduler.activeCount, 1, 'the replacement owns the released scheduler slot');
	assert.equal(activePromises, 2, 'the ignored-abort promise remains owned while replacement capacity is released');
	schedulerLeases.sample();
	releaseReplacement();
	assert.equal(await replacement, 'healthy');
	releaseIgnored();
	await flush();
	scheduler.close();
	schedulerLeases.sample();
	return evidence({
		recovery: { healthy: scheduler.activeCount === 0 && scheduler.pendingCount === 0, permanentLatch: false, stateBefore: 'lease_expired', stateAfter: 'capacity_available', nextProbeAtEpochMs: 20, attemptTimes: [0, 20], probeDeadlines: [20], retryDelays: [20], attemptCount: 2 },
		states: notApplicable('PlanningScheduler does not own agent domain lifecycle state.'),
		profile: notApplicable('Scheduler capacity is provider-profile agnostic and cannot mutate a profile.'),
		resources: resources({
			leases: measured(schedulerLeases.snapshot(), 'PlanningScheduler active lease sampler'),
			sessions: notApplicable('The scheduler test deliberately uses no provider session.'),
			actions: notApplicable('Planning tasks do not invoke the Minecraft action bridge in this scenario.'),
			timers: measured(timers.snapshot(), 'PlanningScheduler injected lease timer'),
			promises: measured({ pending: activePromises, maximum: maxActivePromises }, 'underlying abort-ignoring task ownership counter'),
			childProcesses: notApplicable('This in-process scheduler fixture has no child-process creation capability.'),
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
	assert.equal(result.staleActionReplays[0].originConnectionEpoch, oldDispatch.connectionEpoch);
	assert.equal(result.staleActionReplays[0].replayConnectionEpoch, replacementEpoch);
	assert.equal(replacementDispatches.length, 1);
	assert.equal(result.actionAttempts.length, 2, 'the old command and one replacement each reach the physical boundary');
	assert.equal(result.actionEffects.length, 1);
	assert.equal(result.actionEffects[0].actionId, oldDispatch.payload.actionId, 'the first dispatched command honestly mutates the world before disconnect');
	assert.deepEqual(result.acceptedActionResults, [{ actionId: replacementDispatches[0].payload.actionId, connectionEpoch: replacementEpoch }], 'the replacement result is accepted exactly once');
	assert.equal(result.acceptedActionResults.some(({ actionId }) => actionId === oldDispatch.payload.actionId), false, 'the explicitly old-epoch result is rejected by the coordinator fence');
	assert.equal(result.staleDispatches, 0, 'no old-epoch terminal result crosses the coordinator fence');
	assert.equal(result.deliveredActionResults.find(({ actionId }) => actionId === oldDispatch.payload.actionId)?.connectionEpoch, oldDispatch.connectionEpoch);
	const duplicateDispatches = result.actionDispatches.length - new Set(result.actionDispatches.map(({ payload }) => payload.actionId)).size;
	assert.equal(duplicateDispatches, 0);
	assert.equal(result.providerSessions.created, 1);
	assert.equal(result.leaseStats.maxByKind.provider, 1);
	assert.equal(result.leaseStats.maxByKind.action, 1);
	return evidence({
		recovery: { healthy: result.connectionEpoch === 2 && replacementDispatches.length === 1, permanentLatch: false, stateBefore: 'bridge_disconnected', stateAfter: result.finalState, nextProbeAtEpochMs: null, attemptTimes: [1, 2], probeDeadlines: [], retryDelays: [], attemptCount: 2 },
		states: measured(result.states, 'AgentRegistry snapshots sampled throughout native goal execution'),
		profile: measured({ before: result.providerSessions.profile, after: result.profile }, 'provider session and final AgentRegistry snapshots'),
		resources: resources({
			leases: measured(result.leaseStats, 'tracking wrapper around the production ActiveGoalSupervisor'),
			sessions: measured(result.providerSessions, 'scripted provider exact-session counters'),
			actions: measured({
				maxConcurrent: result.maxConcurrentPhysicalActions,
				effects: result.actionEffects.length,
				duplicates: duplicateDispatches,
				staleEffects: result.acceptedActionResults.filter(({ connectionEpoch }) => connectionEpoch !== replacementEpoch).length,
				pending: harness.bridge.deferredActionCount,
				attempts: result.actionAttempts.length,
				postFenceAccepted: result.acceptedActionResults.length,
			}, 'FaultInjectingMinecraftBridge physical ledger and coordinator actionResult acceptance events'),
			timers: measured(combinedTimerSnapshot(result.goalScheduler, result.stuckScheduler), 'injected work-lease and factual-progress schedulers'),
			promises: measured({ pending: result.activeWork, maximum: result.providerSessions.maxTurnsPending }, 'scripted provider unsettled-turn ownership counter'),
			childProcesses: notApplicable('This in-process native-provider fixture has no child-process creation capability.'),
			listeners: measured(result.listenerStats, 'bridge and coordinator EventEmitter listener sampler'),
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
	let maxFailureListeners = 0;
	let pendingStarts = 0;
	let maxPendingStarts = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: () => trackedOperation(async () => {
			attemptTimes.push(timers.now);
			if (attemptTimes.length <= 3) throw Object.assign(new Error('port occupied'), { code: 'EADDRINUSE' });
			liveWorkers += 1;
			maxWorkers = Math.max(maxWorkers, liveWorkers);
			return {
				statusSnapshots: () => ['voice', 'voice:tts', 'voice:stt'].map((component) => ({ component, state: 'ready', generation: 1, lastRecoveryAtEpochMs: null })),
				onFailure() { failureListeners += 1; maxFailureListeners = Math.max(maxFailureListeners, failureListeners); return () => { failureListeners -= 1; }; },
				async close() { liveWorkers -= 1; },
			};
		}, (delta) => {
			pendingStarts += delta;
			maxPendingStarts = Math.max(maxPendingStarts, pendingStarts);
		}),
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
			timers: measured(timers.snapshot(), 'VoiceSupervisor injected startup and retry scheduler'),
			promises: measured({ pending: pendingStarts, maximum: maxPendingStarts }, 'voice startup-promise ownership counter'),
			childProcesses: notApplicable('This in-process voice fixture has no child-process creation capability.'),
			listeners: measured({ current: failureListeners, maximum: maxFailureListeners }, 'voice worker failure-listener registration counter'),
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
	const trackDiagnostic = (operation) => trackedOperation(operation, (delta) => {
		pendingOperations += delta;
		maxPendingOperations = Math.max(maxPendingOperations, pendingOperations);
	});
	queue.submit(() => trackDiagnostic(() => new Promise((resolve) => { releaseHung = resolve; })));
	queue.submit(() => trackDiagnostic(async () => { throw Object.assign(new Error('disk offline'), { code: 'EIO' }); }));
	queue.submit(() => trackDiagnostic(() => { completed.push('healthy'); }));
	await flush();
	assert.equal(queue.statusSnapshot().state, 'ready');
	await timers.runNext({ flushAfter: false });
	await Promise.resolve();
	const degradedState = queue.statusSnapshot().state;
	assert.equal(degradedState, 'degraded');
	await eventually(() => completed.length === 1);
	assert.equal(queue.statusSnapshot().state, 'ready');
	assert.equal(pendingOperations, 1, 'the detached timed-out write remains owned until the sink settles');
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
			timers: measured(timers.snapshot(), 'BestEffortDiagnosticQueue injected operation scheduler'),
			promises: measured({ pending: pendingOperations, maximum: maxPendingOperations }, 'diagnostic sink-promise ownership counter'),
			childProcesses: notApplicable('This in-process diagnostic fixture has no child-process creation capability.'),
			listeners: notApplicable('BestEffortDiagnosticQueue exposes no listener surface.'),
		}),
	});
}

async function exactProfileSessionAndLeasePreservation() {
	const timers = new ManualTimers();
	const supervisor = new WorkLeaseSupervisor({ clock: () => timers.now, schedule: timers.schedule, cancelSchedule: timers.cancel, stuckSchedule: timers.schedule, cancelStuckSchedule: timers.cancel });
	const leases = new LeaseSnapshotGauge();
	const first = leaseKey(1);
	const second = leaseKey(2);
	assert.equal(supervisor.activate(first), true);
	leases.sample(supervisor.snapshot(first));
	const staleProvider = supervisor.acquire(first, 'provider');
	leases.sample(supervisor.snapshot(first));
	assert.equal(supervisor.activate(second), true);
	leases.sample(supervisor.snapshot(second));
	const action = supervisor.acquire(second, 'action');
	leases.sample(supervisor.snapshot(second));
	assert.equal(supervisor.release(staleProvider), false);
	assert.equal(supervisor.activate({ ...second, profileFingerprint: `sha256:${'b'.repeat(64)}` }), false);
	const live = supervisor.snapshot(second);
	assert.equal(live.leases.length, 1);
	assert.equal(live.leases[0].kind, 'action');
	assert.equal(supervisor.release(action), true);
	const recovered = supervisor.snapshot(second);
	assert.equal(recovered.leases.length, 1);
	assert.equal(recovered.leases[0].kind, 'scheduled');
	leases.sample(recovered);
	supervisor.close();
	leases.sample(null);
	return evidence({
		recovery: { healthy: recovered.state === 'active', permanentLatch: false, stateBefore: 'session_epoch_1', stateAfter: 'session_epoch_2', nextProbeAtEpochMs: recovered.leases[0].deadline, attemptTimes: [first.sessionEpoch, second.sessionEpoch], probeDeadlines: [recovered.leases[0].deadline], retryDelays: [recovered.leases[0].deadline - timers.now], attemptCount: 2 },
		states: notApplicable('WorkLeaseSupervisor cannot mutate the AgentRegistry lifecycle.'),
		profile: measured({ before: { fingerprint: PROFILE_FINGERPRINT }, after: { fingerprint: recovered.key.profileFingerprint } }, 'production WorkLeaseSupervisor key snapshots'),
		resources: resources({
			leases: measured(leases.snapshot(), 'production WorkLeaseSupervisor lifecycle snapshots'),
			sessions: notApplicable('Lease fencing uses session keys but does not create provider sessions.'),
			actions: notApplicable('Action lease fencing does not dispatch a physical Minecraft action.'),
			timers: measured(timers.snapshot(), 'WorkLeaseSupervisor injected lease scheduler'),
			promises: notApplicable('WorkLeaseSupervisor lease operations are synchronous and own no promises.'),
			childProcesses: notApplicable('WorkLeaseSupervisor has no child-process creation capability.'),
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
	if (value === null || value === undefined || (typeof value !== 'object' && typeof value !== 'function')) throw new TypeError('observed evidence requires a non-null instrumented value');
	if (!INSTRUMENTED_VALUES.has(value)) throw new TypeError('observed evidence requires an instrumented measurement');
	return Object.freeze({ kind: 'observed', value, source });
}

function measured(value, source) {
	if (value === null || value === undefined || (typeof value !== 'object' && typeof value !== 'function')) throw new TypeError('measurement value must be a non-null object');
	INSTRUMENTED_VALUES.add(value);
	return observed(value, source);
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
		if (!Object.hasOwn(entry, 'value') || entry.value === null || entry.value === undefined) throw new TypeError(`${name} evidence requires a non-null instrumented value`);
		if (!INSTRUMENTED_VALUES.has(entry.value)) throw new TypeError(`${name} evidence requires an instrumented measurement`);
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

function trackedOperation(operation, update) {
	update(1);
	return Promise.resolve().then(operation).finally(() => update(-1));
}

class ListenerGauge {
	#emitter;
	#maximum = 0;
	#current = 0;

	constructor(emitter) {
		this.#emitter = emitter;
		this.sample();
	}

	sample() {
		this.#current = listenerCount(this.#emitter);
		this.#maximum = Math.max(this.#maximum, this.#current);
	}

	snapshot() { return { current: this.#current, maximum: this.#maximum }; }
}

class SchedulerLeaseGauge {
	#scheduler;
	#maximum = 0;
	#current = 0;

	constructor(scheduler) {
		this.#scheduler = scheduler;
		this.sample();
	}

	sample() {
		this.#current = this.#scheduler.activeCount;
		this.#maximum = Math.max(this.#maximum, this.#current);
	}

	snapshot() { return { maxByKind: { provider: this.#maximum }, pending: this.#current }; }
}

class LeaseSnapshotGauge {
	#maximumByKind = new Map();
	#pending = 0;

	sample(snapshot) {
		const leases = snapshot?.leases ?? [];
		this.#pending = leases.length;
		for (const { kind } of leases) {
			const current = leases.filter((lease) => lease.kind === kind).length;
			this.#maximumByKind.set(kind, Math.max(this.#maximumByKind.get(kind) ?? 0, current));
		}
	}

	snapshot() { return { maxByKind: Object.fromEntries(this.#maximumByKind), pending: this.#pending }; }
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
