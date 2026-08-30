import assert from 'node:assert/strict';
import test from 'node:test';

import { PlanningScheduler } from '../src/planning-scheduler.mjs';

function deferred() {
	let resolve;
	let reject;
	const promise = new Promise((resolveValue, rejectValue) => { resolve = resolveValue; reject = rejectValue; });
	return { promise, resolve, reject };
}

test('default scheduler admits four turns and retains twelve pending turns', async () => {
	const scheduler = new PlanningScheduler();
	const gates = Array.from({ length: 16 }, () => deferred());
	const started = gates.map(() => deferred());
	const runs = gates.map((gate, index) => scheduler.schedule(`agent-${index}`, async () => {
		started[index].resolve();
		if (index < 4) await gate.promise;
	}));
	await Promise.all(started.slice(0, 4).map((entry) => entry.promise));
	assert.equal(scheduler.maxConcurrent, 4);
	assert.equal(scheduler.maxPending, 12);
	assert.equal(scheduler.activeCount, 4);
	assert.equal(scheduler.pendingCount, 12);
	for (const gate of gates.slice(0, 4)) gate.resolve();
	await Promise.all(started.map((entry) => entry.promise));
	await Promise.all(runs);
});

test('scheduler rejects configurations above the global sixteen-turn cap', () => {
	assert.throws(() => new PlanningScheduler({ maxConcurrent: 17, maxPending: 0 }), /must not exceed 16/);
	assert.throws(() => new PlanningScheduler({ maxConcurrent: 16, maxPending: 1 }), /must not exceed 16/);
});

test('scheduler caps concurrency and starts queued agents in FIFO order', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 2 });
	const gates = [deferred(), deferred(), deferred(), deferred()];
	const startSignals = gates.map(() => deferred());
	const started = [];
	let peak = 0;
	const runs = gates.map((gate, index) => scheduler.schedule(`agent-${index}`, async () => {
		started.push(index);
		startSignals[index].resolve();
		peak = Math.max(peak, scheduler.activeCount);
		await gate.promise;
		return index;
	}));
	await Promise.all([startSignals[0].promise, startSignals[1].promise]);
	assert.deepEqual(started, [0, 1]);
	assert.equal(peak, 2);
	gates[0].resolve();
	await startSignals[2].promise;
	assert.deepEqual(started, [0, 1, 2]);
	gates[1].resolve();
	gates[2].resolve();
	gates[3].resolve();
	assert.deepEqual(await Promise.all(runs), [0, 1, 2, 3]);
});

test('scheduler round-robins provider lanes while preserving lane FIFO', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 5 });
	const gate = deferred();
	const started = [];
	const first = scheduler.schedule('gemini-1', async () => {
		started.push('gemini-1');
		await gate.promise;
	}, { lane: 'gemini', priority: 'ordinary' });
	const runs = [
		first,
		scheduler.schedule('gemini-2', async () => started.push('gemini-2'), { lane: 'gemini', priority: 'ordinary' }),
		scheduler.schedule('kimi-1', async () => started.push('kimi-1'), { lane: 'kimi', priority: 'ordinary' }),
		scheduler.schedule('kimi-2', async () => started.push('kimi-2'), { lane: 'kimi', priority: 'ordinary' }),
	];
	await Promise.resolve();
	assert.deepEqual(started, ['gemini-1']);
	gate.resolve();
	await Promise.all(runs);
	assert.deepEqual(started, ['gemini-1', 'kimi-1', 'gemini-2', 'kimi-2']);
});

test('urgent turns lead ordinary turns but ordinary work is admitted after a bounded burst', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 6, maxUrgentBurst: 3 });
	const bootstrapGate = deferred();
	const started = [];
	const bootstrap = scheduler.schedule('bootstrap', async () => {
		started.push('bootstrap');
		await bootstrapGate.promise;
	}, { lane: 'codex', priority: 'ordinary' });
	const ordinary = scheduler.schedule('ordinary', async () => started.push('ordinary'), { lane: 'ordinary-lane', priority: 'ordinary' });
	const urgentRuns = Array.from({ length: 4 }, (_, index) => scheduler.schedule(`urgent-${index}`, async () => started.push(`urgent-${index}`), {
		lane: `urgent-lane-${index}`,
		priority: 'urgent',
	}));
	bootstrapGate.resolve();
	await Promise.all([bootstrap, ordinary, ...urgentRuns]);
	assert.deepEqual(started.slice(0, 5), ['bootstrap', 'urgent-0', 'urgent-1', 'urgent-2', 'ordinary']);
});

test('scheduler permits at most one active or pending turn per agent', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1 });
	const gate = deferred();
	const active = scheduler.schedule('agent-a', () => gate.promise);
	await Promise.resolve();
	await assert.rejects(scheduler.schedule('agent-a', async () => null), (error) => error.code === 'PLAN_ALREADY_ACTIVE');
	gate.resolve('done');
	assert.equal(await active, 'done');
});

test('scheduler releases a completed slot before resolving its promise', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	assert.equal(await scheduler.schedule('agent-a', async () => 'first'), 'first');
	assert.equal(scheduler.activeCount, 0);
	assert.equal(await scheduler.schedule('agent-a', async () => 'second'), 'second');
});

test('cancelling an active turn aborts its dependency-injected signal', async () => {
	const scheduler = new PlanningScheduler();
	let signal;
	const completed = scheduler.schedule('agent-a', ({ signal: value }) => {
		signal = value;
		return new Promise((resolve, reject) => value.addEventListener('abort', () => reject(value.reason), { once: true }));
	});
	await Promise.resolve();
	assert.equal(scheduler.cancel('agent-a', 'stopped'), true);
	await assert.rejects(completed, (error) => error.code === 'PLAN_CANCELLED');
	assert.equal(signal.aborted, true);
});

test('cancelling ignored-abort work releases global capacity but fences its agent until settlement', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	const ignoredAbort = deferred();
	const cancelled = scheduler.schedule('agent-a', () => ignoredAbort.promise);
	await Promise.resolve();
	assert.equal(scheduler.cancel('agent-a', 'superseded'), true);
	assert.equal(scheduler.activeCount, 0);
	assert.equal(scheduler.hasScheduled('agent-a'), true);
	await assert.rejects(scheduler.schedule('agent-a', async () => 'overlap'), (error) => error.code === 'PLAN_CANCELLING');
	assert.equal(await scheduler.schedule('agent-b', async () => 'available'), 'available');
	ignoredAbort.resolve('late result');
	await assert.rejects(cancelled, (error) => error.code === 'PLAN_CANCELLED');
	assert.equal(scheduler.hasScheduled('agent-a'), false);
});

test('cancelling an admitted task before its microtask starts never invokes provider work', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	let invoked = false;
	const cancelled = scheduler.schedule('agent-a', async () => { invoked = true; });
	assert.equal(scheduler.cancel('agent-a', 'superseded'), true);
	await assert.rejects(cancelled, (error) => error.code === 'PLAN_CANCELLED');
	assert.equal(invoked, false);
});

test('cancelling a pending turn releases capacity for another agent', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1 });
	const gate = deferred();
	const active = scheduler.schedule('agent-a', () => gate.promise);
	await Promise.resolve();
	const cancelled = scheduler.schedule('agent-b', async () => 'cancelled');
	assert.equal(scheduler.cancel('agent-b', 'stopped'), true);
	await assert.rejects(cancelled, (error) => error.code === 'PLAN_CANCELLED');
	const replacement = scheduler.schedule('agent-c', async () => 'replacement');
	gate.resolve('active');
	assert.equal(await active, 'active');
	assert.equal(await replacement, 'replacement');
});

test('hard planning lease releases capacity and fences a late ignored-abort completion', async () => {
	let timeoutCallback;
	const cancelled = [];
	const expired = [];
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1,
		maxPending: 0,
		scheduleTimeout: (callback, delay) => { timeoutCallback = callback; assert.equal(delay, 25); return 1; },
		cancelTimeout: (handle) => cancelled.push(handle),
	});
	const ignoredAbort = deferred();
	const run = scheduler.schedule('agent-a', () => ignoredAbort.promise, {
		leaseTimeoutMs: 25,
		onLeaseExpired: (event) => expired.push(event),
	});
	await Promise.resolve();
	assert.equal(scheduler.activeCount, 1);
	timeoutCallback();
	await assert.rejects(run, (error) => error?.code === 'PLANNING_LEASE_EXPIRED');
	assert.equal(scheduler.activeCount, 0);
	assert.equal(expired.length, 1);
	assert.equal(expired[0].signal.aborted, true);

	const replacementGate = deferred();
	const replacement = scheduler.schedule('agent-a', () => replacementGate.promise);
	await Promise.resolve();
	assert.equal(scheduler.activeCount, 1);
	ignoredAbort.resolve('late');
	await Promise.resolve();
	assert.equal(scheduler.activeCount, 1, 'late completion cannot release the replacement generation');
	replacementGate.resolve('replacement');
	assert.equal(await replacement, 'replacement');
	assert.deepEqual(cancelled, [1]);
});

test('a failed provider turn releases its slot without affecting another lane', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 2 });
	const failure = Object.assign(new Error('gemini unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
	const started = [];
	const failed = scheduler.schedule('gemini-1', async () => {
		started.push('gemini-1');
		throw failure;
	}, { lane: 'gemini', priority: 'ordinary' });
	const healthy = scheduler.schedule('kimi-1', async () => {
		started.push('kimi-1');
		return 'healthy';
	}, { lane: 'kimi', priority: 'ordinary' });
	await assert.rejects(failed, (error) => error === failure);
	assert.equal(await healthy, 'healthy');
	assert.deepEqual(started, ['gemini-1', 'kimi-1']);
});

test('scheduler warns at 75 percent and rejects beyond its hard capacity', async () => {
	const pressure = [];
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1,
		maxPending: 3,
		onPressure: (snapshot) => pressure.push(snapshot),
	});
	const gates = [deferred(), deferred(), deferred(), deferred()];
	const runs = gates.map((gate, index) => scheduler.schedule(`agent-${index}`, () => gate.promise));
	await Promise.resolve();
	assert.equal(scheduler.totalCapacity, 4);
	assert.equal(scheduler.activeCount, 1);
	assert.equal(scheduler.pendingCount, 3);
	assert.ok(pressure.some((snapshot) => snapshot.warning && snapshot.used === 3));
	await assert.rejects(
		scheduler.schedule('agent-over-cap', async () => null),
		(error) => error.code === 'SCHEDULER_CAPACITY',
	);

	for (const gate of gates) gate.resolve('done');
	await Promise.all(runs);
	assert.equal(scheduler.activeCount, 0);
	assert.equal(scheduler.pendingCount, 0);
});

test('adaptive target grows one slot per four demand-backed successful decisions', () => {
	const events = [];
	const scheduler = new PlanningScheduler({
		planningMode: 'adaptive',
		maxConcurrent: 4,
		maxPending: 12,
		urgentReserve: 1,
		recorder: { record: (stage, context, fields) => events.push({ stage, context, fields }) },
	});

	assert.equal(scheduler.planningMode, 'adaptive');
	assert.equal(scheduler.target, 4);
	for (let index = 0; index < 3; index += 1) {
		scheduler.observeProviderTelemetry({ operation: 'decide', errorCode: null }, { pendingOrdinary: 1 });
		assert.equal(scheduler.target, 4);
	}
	scheduler.observeProviderTelemetry({ operation: 'decide', errorCode: null }, { pendingOrdinary: 1 });
	assert.equal(scheduler.target, 5);
	assert.equal(events.filter((event) => event.stage === 'scheduler_target_changed').length, 1);
	assert.equal(events.at(-1).fields.reason, 'healthy_growth');
	assert.deepEqual(Object.keys(events.at(-1).fields).sort(), [
		'active', 'activeOrdinary', 'activeUrgent', 'maxConcurrency', 'minConcurrency', 'mode',
		'ordinaryActiveLimit', 'pending', 'pendingOrdinary', 'pendingUrgent', 'previousTarget',
		'reason', 'target', 'urgentReserve',
	].sort());

	for (let index = 0; index < 4; index += 1) {
		scheduler.observeProviderTelemetry({ operation: 'decide', errorCode: null }, { pendingOrdinary: 0 });
	}
	assert.equal(scheduler.target, 5, 'idle completions do not create a later burst growth');
});

test('fixed target ignores provider and tick feedback', () => {
	const scheduler = new PlanningScheduler({
		planningMode: 'fixed',
		maxConcurrent: 8,
		maxPending: 8,
		urgentReserve: 1,
	});

	for (const errorCode of ['RATE_LIMITED', 'OVERLOADED', 'PLANNING_TIMEOUT', null]) {
		scheduler.observeProviderTelemetry({ operation: 'decide', errorCode }, { pendingOrdinary: 1 });
	}
	for (let index = 0; index < 3; index += 1) scheduler.observeSystemHealth({ tickP95Ms: 60 });
	assert.equal(scheduler.target, 8);
	assert.equal(scheduler.backoffCount, 0);
});

test('ordinary work leaves one active reservation for urgent work', async () => {
	const scheduler = new PlanningScheduler({ planningMode: 'adaptive', maxConcurrent: 4, maxPending: 12, urgentReserve: 1 });
	const gates = Array.from({ length: 4 }, () => deferred());
	const ordinaryRuns = [0, 1, 2].map((index) => scheduler.schedule(`ordinary-${index}`, () => gates[index].promise));
	await Promise.resolve();
	assert.equal(scheduler.activeCount, 3);
	assert.equal(scheduler.activeOrdinaryCount, 3);
	assert.equal(scheduler.pendingCount, 0);

	const urgentGate = deferred();
	const urgent = scheduler.schedule('urgent', () => urgentGate.promise, { priority: 'urgent', lane: 'urgent' });
	await Promise.resolve();
	assert.equal(scheduler.activeCount, 4);
	assert.equal(scheduler.activeUrgentCount, 1);
	assert.equal(scheduler.activeAgentIds.includes('urgent'), true);

	for (const gate of gates) gate.resolve();
	urgentGate.resolve();
	await Promise.all([...ordinaryRuns, urgent]);
});

test('reserved urgent admission bypasses a fairness-selected ordinary turn that cannot start', async () => {
	const scheduler = new PlanningScheduler({ planningMode: 'adaptive', maxConcurrent: 4, maxPending: 12, urgentReserve: 1, maxUrgentBurst: 1 });
	const gates = Array.from({ length: 3 }, () => deferred());
	const active = gates.map((gate, index) => scheduler.schedule(`ordinary-${index}`, () => gate.promise));
	await Promise.resolve();
	const ordinary = scheduler.schedule('ordinary-pending', async () => 'ordinary');
	const urgent = scheduler.schedule('urgent-pending', async () => 'urgent', { priority: 'urgent' });
	await Promise.resolve();
	assert.equal(await urgent, 'urgent');
	assert.equal(scheduler.pendingAgentIds.includes('ordinary-pending'), true);
	for (const gate of gates) gate.resolve();
	await Promise.all([...active, ordinary]);
});

test('auxiliary work has bounded pending capacity beyond sixteen scheduled agent turns', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 16, maxPending: 0, maxAuxiliaryPending: 2 });
	const gates = Array.from({ length: 16 }, () => deferred());
	const active = gates.map((gate, index) => scheduler.schedule(`agent-${index}`, () => gate.promise));
	await Promise.resolve();
	const auxiliaryA = scheduler.schedule('goal-spec-a', async () => 'a', { capacityClass: 'auxiliary' });
	const auxiliaryB = scheduler.schedule('goal-spec-b', async () => 'b', { capacityClass: 'auxiliary' });
	await assert.rejects(
		scheduler.schedule('goal-spec-c', async () => 'c', { capacityClass: 'auxiliary' }),
		(error) => error.code === 'SCHEDULER_CAPACITY',
	);
	assert.equal(scheduler.pendingAuxiliaryCount, 2);
	gates[0].resolve();
	gates[1].resolve();
	assert.deepEqual(await Promise.all([auxiliaryA, auxiliaryB]), ['a', 'b']);
	for (const gate of gates.slice(2)) gate.resolve();
	await Promise.all(active);
});

test('auxiliary backlog does not consume default pending admission capacity', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1, maxAuxiliaryPending: 2 });
	const gate = deferred();
	const active = scheduler.schedule('active', () => gate.promise);
	await Promise.resolve();
	const auxiliary = scheduler.schedule('goal-spec', async () => 'auxiliary', { capacityClass: 'auxiliary' });
	const ordinary = scheduler.schedule('ordinary', async () => 'ordinary');
	assert.equal(scheduler.pendingAuxiliaryCount, 1);
	assert.equal(scheduler.pendingAgentIds.includes('ordinary'), true);
	gate.resolve();
	assert.deepEqual(await Promise.all([active, auxiliary, ordinary]), [undefined, 'auxiliary', 'ordinary']);
});

test('ordinary queue capacity preserves the reserved urgent entry at the global boundary', async () => {
	const scheduler = new PlanningScheduler({ planningMode: 'adaptive', maxConcurrent: 4, maxPending: 12, urgentReserve: 1 });
	const gates = Array.from({ length: 3 }, () => deferred());
	const active = gates.map((gate, index) => scheduler.schedule(`active-${index}`, () => gate.promise));
	await Promise.resolve();
	const pending = Array.from({ length: 12 }, (_, index) => scheduler.schedule(`pending-${index}`, async () => null));
	await assert.rejects(scheduler.schedule('ordinary-over-cap', async () => null), (error) => error.code === 'SCHEDULER_CAPACITY');
	const urgentGate = deferred();
	const urgent = scheduler.schedule('urgent-at-boundary', () => urgentGate.promise, { lane: 'urgent', priority: 'urgent' });
	await Promise.resolve();
	assert.equal(scheduler.activeUrgentCount, 1);
	assert.equal(scheduler.ordinaryReservationRejections, 1);
	for (const gate of gates) gate.resolve();
	urgentGate.resolve();
	await Promise.all([...active, ...pending, urgent]);
});

test('adaptive pressure backs off immediately and tick pressure needs three samples', () => {
	const scheduler = new PlanningScheduler({ planningMode: 'adaptive', maxConcurrent: 8, maxPending: 8, urgentReserve: 1 });
	scheduler.observeProviderTelemetry({ operation: 'decide', errorCode: 'RATE_LIMITED' });
	assert.equal(scheduler.target, 7);
	scheduler.observeProviderTelemetry({ operation: 'decide', errorCode: 'OVERLOADED' });
	assert.equal(scheduler.target, 6);
	for (const errorCode of ['AUTH_FAILED', 'MALFORMED_DECISION', 'PLAN_CANCELLED', 'STALE_PLAN', 'MISSING_FINAL_MESSAGE']) {
		scheduler.observeProviderTelemetry({ operation: 'decide', errorCode });
	}
	assert.equal(scheduler.target, 6);

	scheduler.observeSystemHealth({ tickP95Ms: 60 });
	scheduler.observeSystemHealth({ tickP95Ms: 60 });
	assert.equal(scheduler.target, 6);
	scheduler.observeSystemHealth({ tickP95Ms: 60 });
	assert.equal(scheduler.target, 5);
	assert.equal(scheduler.lastChangeReason, 'tick_pressure');
});
