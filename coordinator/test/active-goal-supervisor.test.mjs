import assert from 'node:assert/strict';
import test from 'node:test';

import { ActiveGoalSupervisor } from '../src/active-goal-supervisor.mjs';

const key = Object.freeze({ agentId: 'luna', goalRevision: 4, lifecycleGeneration: 2 });

class FakeTimerQueue {
	#nextId = 0;
	#timers = new Map();
	delays = [];
	history = [];

	schedule = (callback, delay) => {
		const handle = { id: ++this.#nextId };
		this.#timers.set(handle.id, { handle, callback, delay });
		this.history.push({ handle, callback, delay });
		this.delays.push(delay);
		return handle;
	};

	cancel = (handle) => {
		if (handle === undefined || handle === null) return false;
		return this.#timers.delete(handle.id);
	};

	clearRecordedDelays() {
		this.delays.length = 0;
	}

	get pendingCount() {
		return this.#timers.size;
	}

	async runNext() {
		const timer = this.#timers.values().next().value;
		if (timer === undefined) return false;
		this.#timers.delete(timer.handle.id);
		await timer.callback();
		return true;
	}
}

function createSupervisor(clock, requests = []) {
	return new ActiveGoalSupervisor({
		requestObservation: async (requested) => requests.push(requested),
		schedule: clock.schedule,
		cancelSchedule: clock.cancel,
	});
}

test('an idle active goal schedules one observation recovery with capped backoff', async () => {
	const clock = new FakeTimerQueue();
	const requests = [];
	const supervisor = createSupervisor(clock, requests);
	supervisor.activate(key);
	assert.deepEqual(clock.delays, [250]);
	supervisor.ensure(key, 'duplicate signal');
	assert.deepEqual(clock.delays, [250]);
	await clock.runNext();
	assert.deepEqual(requests, [key]);
	assert.equal(clock.delays.at(-1), 500);
	for (let index = 0; index < 8; index += 1) await clock.runNext();
	assert.deepEqual(clock.delays.slice(0, 6), [250, 500, 1_000, 2_000, 4_000, 5_000]);
	assert.equal(Math.max(...clock.delays), 5_000);
});

test('overlapping provider and physical work suppress recovery until both end', () => {
	const clock = new FakeTimerQueue();
	const supervisor = createSupervisor(clock);
	supervisor.activate(key);
	const provider = supervisor.begin(key, 'provider');
	const action = supervisor.begin(key, 'action');
	clock.clearRecordedDelays();
	assert.equal(supervisor.end(action, { progress: true }), true);
	assert.deepEqual(clock.delays, []);
	assert.equal(supervisor.end(provider, { progress: true }), true);
	assert.deepEqual(clock.delays, [250]);
});

test('stale revision callbacks and terminated goals cannot request observations', async () => {
	const clock = new FakeTimerQueue();
	const requests = [];
	const supervisor = createSupervisor(clock, requests);
	supervisor.activate(key);
	const staleRecoveryCallback = clock.history[0].callback;
	supervisor.activate({ ...key, goalRevision: 5, lifecycleGeneration: 3 });
	await staleRecoveryCallback();
	assert.deepEqual(requests, []);
	await clock.runNext();
	assert.equal(requests.length, 1);
	supervisor.terminate({ ...key, goalRevision: 5, lifecycleGeneration: 3 });
	while (await clock.runNext());
	assert.equal(requests.length, 1);
});

test('a fresh observation resets the recovery backoff before the next lease', async () => {
	const clock = new FakeTimerQueue();
	const requests = [];
	const supervisor = createSupervisor(clock, requests);
	supervisor.activate(key);
	await clock.runNext();
	assert.equal(clock.delays.at(-1), 500);
	assert.equal(supervisor.observed(key), true);
	assert.equal(clock.delays.at(-1), 250);
});

test('recoverable failure coalesces to one retry and keeps capped exponential backoff', async () => {
	const clock = new FakeTimerQueue();
	const requests = [];
	const supervisor = createSupervisor(clock, requests);
	supervisor.activate(key);
	const failedProvider = supervisor.begin(key, 'provider');
	clock.clearRecordedDelays();
	supervisor.end(failedProvider);
	supervisor.recover(key, { errorCode: 'PLANNING_TIMEOUT' });
	supervisor.recover(key, { errorCode: 'PROVIDER_UNAVAILABLE' });
	assert.deepEqual(clock.delays, [250]);
	assert.equal(clock.pendingCount, 1);
	await clock.runNext();
	assert.equal(clock.delays.at(-1), 500);
	assert.equal(clock.pendingCount, 1);
});

test('a failed observation request still re-arms the fenced recovery lease', async () => {
	const clock = new FakeTimerQueue();
	let calls = 0;
	const supervisor = new ActiveGoalSupervisor({
		requestObservation: async () => {
			calls += 1;
			throw new Error('bridge unavailable');
		},
		schedule: clock.schedule,
		cancelSchedule: clock.cancel,
	});
	supervisor.activate(key);
	await clock.runNext();
	assert.equal(calls, 1);
	assert.equal(clock.delays.at(-1), 500);
	assert.equal(clock.pendingCount, 1);
});

test('an observation request that never settles cannot strand the recovery lease', async () => {
	const clock = new FakeTimerQueue();
	const supervisor = new ActiveGoalSupervisor({
		requestObservation: () => new Promise(() => {}),
		schedule: clock.schedule,
		cancelSchedule: clock.cancel,
	});
	supervisor.activate(key);
	await clock.runNext();
	assert.equal(clock.delays.at(-1), 500);
	assert.equal(clock.pendingCount, 1);
});

test('explicit suspension cancels recovery and prevents new timers', async () => {
	const clock = new FakeTimerQueue();
	const requests = [];
	const supervisor = createSupervisor(clock, requests);
	supervisor.activate(key);
	assert.equal(supervisor.suspend(key), true);
	assert.equal(clock.pendingCount, 0);
	supervisor.ensure(key, 'suspended goal');
	await clock.runNext();
	assert.deepEqual(requests, []);
});

test('ending a token twice is harmless and close cancels every owned timer', () => {
	const clock = new FakeTimerQueue();
	const supervisor = createSupervisor(clock);
	supervisor.activate(key);
	const token = supervisor.begin(key, 'completion');
	assert.equal(supervisor.end(token), true);
	assert.equal(supervisor.end(token), false);
	assert.equal(clock.pendingCount, 1);
	supervisor.close();
	supervisor.close();
	assert.equal(clock.pendingCount, 0);
});

test('a stale work token can be released without scheduling recovery', () => {
	const clock = new FakeTimerQueue();
	const supervisor = createSupervisor(clock);
	supervisor.activate(key);
	const token = supervisor.begin(key, 'provider');
	assert.equal(supervisor.end(token, { scheduleRecovery: false }), true);
	assert.equal(clock.pendingCount, 0);
});

test('stale tokens cannot settle work belonging to a newer fenced goal', () => {
	const clock = new FakeTimerQueue();
	const supervisor = createSupervisor(clock);
	supervisor.activate(key);
	const staleToken = supervisor.begin(key, 'provider');
	supervisor.activate({ ...key, goalRevision: 5, lifecycleGeneration: 3 });
	assert.equal(supervisor.end(staleToken, { progress: true }), false);
	assert.deepEqual(clock.delays, [250, 250]);
});
