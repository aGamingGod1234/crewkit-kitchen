import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import test from 'node:test';

import { startCoordinatorControl, startVoiceWorker } from '../src/dynamic-main.mjs';
import { VoiceSupervisor } from '../src/voice/voice-supervisor.mjs';

const SECRET = 'voice-supervisor-test-secret';

test('coordinator control starts before optional voice and never awaits its warmup', async () => {
	const order = [];
	let releaseVoice;
	const voiceGate = new Promise((resolve) => { releaseVoice = resolve; });
	const coordinator = { async start() { order.push('coordinator'); } };
	const voiceSupervisor = { start() { order.push('voice'); return voiceGate; } };

	await startCoordinatorControl(coordinator, voiceSupervisor);
	assert.deepEqual(order, ['coordinator', 'voice']);
	releaseVoice();
});

test('voice supervisor retries an occupied port and recovers after release without coordinator restart', async () => {
	const blocker = createServer();
	await new Promise((resolve) => blocker.listen(0, '127.0.0.1', resolve));
	const port = blocker.address().port;
	const supervisor = new VoiceSupervisor({
		startWorker: () => startVoiceWorker({ bridge: { secret: SECRET }, voice: { port } }, {
			FISH_API_KEY: 'test-key',
		}, {
			platform: 'linux',
			loadProfileStore: async () => ({ store: { resolve: () => probeProfile() } }),
			createTtsProvider: () => ({ async synthesize() { return validSynthesis(); } }),
		}),
		initialRetryMs: 10,
		maxRetryMs: 20,
		startupTimeoutMs: 500,
		warmupTimeoutMs: 500,
	});
	try {
		supervisor.start();
		await eventually(() => component(supervisor, 'voice').state === 'degraded');
		assert.ok(Number.isSafeInteger(component(supervisor, 'voice').nextProbeAtEpochMs));
		await new Promise((resolve, reject) => blocker.close((error) => error ? reject(error) : resolve()));
		await eventually(() => component(supervisor, 'voice').state === 'ready');
		assert.ok(Number.isSafeInteger(component(supervisor, 'voice').lastRecoveryAtEpochMs));
		assert.equal((await fetch(`http://127.0.0.1:${port}/health`)).status, 200);
	} finally {
		if (blocker.listening) await new Promise((resolve) => blocker.close(resolve));
		await supervisor.close();
	}
});

test('voice supervisor uses capped retries and close fences a late startup success', async () => {
	const timers = new ManualTimers();
	let attempts = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attempts += 1;
			throw Object.assign(new Error('bind failed'), { code: 'EADDRINUSE' });
		},
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 200,
		startupTimeoutMs: 1_000,
		warmupTimeoutMs: 1_000,
	});
	supervisor.start();
	await flush();
	assert.equal(attempts, 1);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 100);
	await timers.runNext();
	assert.equal(attempts, 2);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 300);
	await timers.runNext();
	assert.equal(attempts, 3);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 500, 'retry delay is capped');
	await supervisor.close();
	await timers.runNextEvenIfCancelled();
	assert.equal(attempts, 3, 'closed supervisor ignores captured retry callbacks');

	let releaseStartup;
	const startup = new Promise((resolve) => { releaseStartup = resolve; });
	let lateCloses = 0;
	const late = new VoiceSupervisor({
		startWorker: () => startup,
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		startupTimeoutMs: 1_000,
		warmupTimeoutMs: 1_000,
	});
	late.start();
	await flush();
	await late.close();
	releaseStartup({ async close() { lateCloses += 1; }, statusSnapshots: readySnapshots });
	await flush();
	assert.equal(lateCloses, 1, 'late worker is closed exactly once instead of being promoted');
});

test('timed-out startup generation closes a late worker and cannot replace its retry', async () => {
	const timers = new ManualTimers();
	let releaseStartup;
	const startup = new Promise((resolve) => { releaseStartup = resolve; });
	let closes = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: () => startup,
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 100,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 50,
	});
	supervisor.start();
	await flush();
	await timers.runNext();
	assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_START_TIMEOUT');
	releaseStartup({ async close() { closes += 1; }, statusSnapshots: readySnapshots });
	await flush();
	assert.equal(closes, 1, 'late timed-out worker is closed exactly once');
	assert.equal(component(supervisor, 'voice').state, 'degraded', 'late success cannot clear retry state');
	await supervisor.close();
});

test('hung warmup is bounded, closes its candidate, and enters automatic retry', async () => {
	const timers = new ManualTimers();
	let closes = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => ({
			warmup: () => new Promise(() => {}),
			async close() { closes += 1; },
			statusSnapshots: readySnapshots,
		}),
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 100,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 25,
	});
	supervisor.start();
	await flush();
	await timers.runNext();
	assert.equal(closes, 1);
	assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_WARMUP_TIMEOUT');
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 125);
	await supervisor.close();
});

function component(supervisor, name) {
	return supervisor.statusSnapshots().find(({ component: candidate }) => candidate === name);
}

function readySnapshots() {
	return [
		status('voice', 'ready'),
		status('voice:tts', 'ready'),
		status('voice:stt', 'ready'),
	];
}

function status(componentName, state) {
	return {
		component: componentName,
		state,
		fallbackMode: state === 'ready' ? null : 'text',
		boundary: null,
		failureCode: null,
		consecutiveFailureCount: 0,
		nextProbeAtEpochMs: null,
		generation: 1,
		lastRecoveryAtEpochMs: null,
	};
}

class ManualTimers {
	now = 0;
	tasks = [];

	schedule = (callback, delay) => {
		const task = { callback, at: this.now + delay, canceled: false };
		this.tasks.push(task);
		return task;
	};

	cancel = (task) => { task.canceled = true; };

	async runNext() {
		const task = this.tasks.find((candidate) => !candidate.canceled);
		if (task === undefined) throw new Error('no scheduled timer');
		task.canceled = true;
		this.now = task.at;
		task.callback();
		await flush();
	}

	async runNextEvenIfCancelled() {
		const task = this.tasks.at(-1);
		if (task === undefined) return;
		this.now = Math.max(this.now, task.at);
		task.callback();
		await flush();
	}
}

function probeProfile() {
	return { provider: 'test', model: 'test', voiceId: 'test', revision: 1, speed: 1, profileId: 'voice.test' };
}

function validSynthesis() {
	return { sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(4) };
}

async function flush() {
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate) {
	for (let attempt = 0; attempt < 200; attempt += 1) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error('condition did not become true');
}
