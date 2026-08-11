import assert from 'node:assert/strict';
import test from 'node:test';

import { PlanningScheduler } from '../src/planning-scheduler.mjs';

function deferred() {
	let resolve;
	let reject;
	const promise = new Promise((resolveValue, rejectValue) => { resolve = resolveValue; reject = rejectValue; });
	return { promise, resolve, reject };
}

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

test('scheduler permits at most one active or pending turn per agent', async () => {
	const scheduler = new PlanningScheduler({ maxConcurrent: 1 });
	const gate = deferred();
	const active = scheduler.schedule('agent-a', () => gate.promise);
	await Promise.resolve();
	await assert.rejects(scheduler.schedule('agent-a', async () => null), (error) => error.code === 'PLAN_ALREADY_ACTIVE');
	gate.resolve('done');
	assert.equal(await active, 'done');
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
