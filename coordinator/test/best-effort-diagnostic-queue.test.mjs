import assert from 'node:assert/strict';
import test from 'node:test';

import { BestEffortDiagnosticQueue } from '../src/best-effort-diagnostic-queue.mjs';

test('diagnostic sink invocation is deferred outside the control call stack', async () => {
	let calls = 0;
	const queue = new BestEffortDiagnosticQueue();
	assert.equal(queue.submit(() => { calls += 1; }), true);
	assert.equal(calls, 0, 'submit must not invoke an observational sink inline');
	await queue.close();
	assert.equal(calls, 1);
});

test('diagnostic queue bounds a hung sink and drops overflow without blocking submitters', async () => {
	let calls = 0;
	const queue = new BestEffortDiagnosticQueue({
		maxPending: 2,
		operationTimeoutMs: 20,
		closeTimeoutMs: 10,
	});
	const hung = () => {
		calls += 1;
		return new Promise(() => {});
	};

	assert.equal(queue.submit(hung), true);
	assert.equal(queue.submit(hung), true);
	for (let index = 0; index < 100; index += 1) assert.equal(queue.submit(hung), false);
	assert.equal(queue.droppedCount, 100);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(calls, 1, 'only the active sink operation starts while it is hung');

	const firstClose = queue.close();
	assert.strictEqual(queue.close(), firstClose, 'cleanup is idempotent');
	await firstClose;
	assert.equal(queue.submit(() => {}), false, 'closed diagnostics remain best effort');
});

test('diagnostic queue observes sync throws and async rejection then drains later work', async () => {
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ operationTimeoutMs: 50, closeTimeoutMs: 100 });
	queue.submit(() => { throw new Error('sync sink failure'); });
	queue.submit(async () => { throw new Error('async sink failure'); });
	queue.submit(() => { completed.push('healthy'); });

	await queue.close();
	assert.deepEqual(completed, ['healthy']);
	assert.equal(queue.statusSnapshot().state, 'ready');
	assert.equal(queue.statusSnapshot().failedOperationCount, 2);
	assert.equal(queue.statusSnapshot().incompleteCapture, true);
});

test('close timeout accounts for abandoned pending work before an active operation timeout', async () => {
	let release;
	const queue = new BestEffortDiagnosticQueue({ operationTimeoutMs: 1_000, closeTimeoutMs: 10 });
	queue.submit(() => new Promise((resolve) => { release = resolve; }));
	queue.submit(() => assert.fail('close must abandon this pending row'));
	await new Promise(setImmediate);
	await queue.close();
	assert.equal(queue.statusSnapshot().state, 'degraded');
	assert.equal(queue.statusSnapshot().failureCode, 'DIAGNOSTIC_CLOSE_TIMEOUT');
	assert.equal(queue.droppedCount, 1);
	assert.equal(queue.statusSnapshot().incompleteCapture, true);
	release();
	await new Promise(setImmediate);
	assert.equal(queue.statusSnapshot().incompleteCapture, true, 'a late successful append cannot erase abandoned evidence');
});

test('diagnostic queue contains hostile then getters and continues draining', async () => {
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ operationTimeoutMs: 50, closeTimeoutMs: 100 });
	const hostileThenable = Object.create(null, {
		then: {
			get() { throw new Error('hostile then getter'); },
		},
	});

	queue.submit(() => hostileThenable);
	queue.submit(() => { completed.push('healthy'); });

	await queue.close();
	assert.deepEqual(completed, ['healthy']);
	assert.equal(queue.statusSnapshot().state, 'ready');
});

test('timed-out sink ownership stays bounded while later healthy work can recover', async () => {
	let hungCalls = 0;
	const completed = [];
	const timers = [];
	const queue = new BestEffortDiagnosticQueue({
		maxPending: 8,
		maxDetachedOperations: 2,
		operationTimeoutMs: 10,
		closeTimeoutMs: 100,
		dispatch: (callback) => callback(),
		schedule: (callback) => {
			const timer = { active: true, callback };
			timers.push(timer);
			return timer;
		},
		cancel: (timer) => { timer.active = false; },
	});
	const expireNext = () => {
		const timer = timers.find((candidate) => candidate.active);
		assert.ok(timer, 'a sink timeout must be scheduled');
		timer.active = false;
		timer.callback();
	};
	queue.submit(() => { hungCalls += 1; return new Promise(() => {}); });
	queue.submit(() => { completed.push('healthy'); });
	for (let index = 0; index < 10; index += 1) {
		queue.submit(() => { hungCalls += 1; return new Promise(() => {}); });
	}
	expireNext();
	await Promise.resolve();
	assert.deepEqual(completed, ['healthy']);
	assert.equal(hungCalls, 2, 'detached hung sink ownership is capped');
	expireNext();
	await Promise.resolve();
	assert.ok(queue.droppedCount > 0);
	await queue.close();
});

test('setup barrier keeps admission bounded and starts operation timers only after readiness', async () => {
	let release;
	const ready = new Promise(resolve => { release = resolve; });
	const timers = new Set(), completed = [];
	const queue = new BestEffortDiagnosticQueue({ ready, maxPending: 2, dispatch: queueMicrotask,
		schedule(callback, delay) { const timer = { callback, delay }; timers.add(timer); return timer; },
		cancel(timer) { timers.delete(timer); } });
	assert.equal(queue.submit(async () => { completed.push(1); }), true);
	assert.equal(queue.submit(async () => { completed.push(2); }), true);
	assert.equal(queue.submit(() => assert.fail('overflow')), false);
	await new Promise(setImmediate);
	assert.equal(timers.size, 0);
	assert.deepEqual(completed, []);
	const closing = queue.close();
	assert.deepEqual([...timers].map(timer => timer.delay), [1_000]);
	release();
	await closing;
	assert.deepEqual(completed, [1, 2]);
	assert.equal(queue.droppedCount, 1);
	assert.equal(queue.statusSnapshot().failedOperationCount, 0);
	assert.equal(queue.statusSnapshot().incompleteCapture, true);
	assert.equal(timers.size, 0);
});

test('rejected setup is observed and lets operations choose independent sink outcomes', async () => {
	let reject;
	const ready = new Promise((_resolve, no) => { reject = no; });
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ ready });
	queue.submit(() => completed.push('other sink'));
	reject(new Error('setup failed'));
	await queue.close();
	assert.deepEqual(completed, ['other sink']);
	assert.equal(queue.statusSnapshot().failedOperationCount, 1);
	assert.equal(queue.statusSnapshot().incompleteCapture, true);
});

test('setup cannot resurrect pending rows after close timer creation fails', async () => {
	let release;
	const ready = new Promise(resolve => { release = resolve; });
	const queue = new BestEffortDiagnosticQueue({ ready,
		schedule() { throw new Error('timer unavailable'); } });
	queue.submit(() => assert.fail('abandoned operation'));
	await queue.close();
	assert.equal(queue.droppedCount, 1);
	assert.equal(queue.statusSnapshot().failureCode, 'DIAGNOSTIC_CLOSE_TIMEOUT');
	release();
	await new Promise(setImmediate);
	assert.equal(queue.submit(() => {}), false);
});
