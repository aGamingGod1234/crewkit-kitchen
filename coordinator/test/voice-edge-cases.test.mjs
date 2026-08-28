import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { DeepgramSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
import { FishTtsProvider } from '../src/voice/fish-tts-provider.mjs';
import { TtsCache } from '../src/voice/tts-cache.mjs';
import { createVoiceHttpServer } from '../src/voice/voice-http-server.mjs';
import { loadPersistentVoiceProfileStore, VoiceProfileStore } from '../src/voice/voice-profile-store.mjs';

const SECRET = 'voice-edge-verification-secret';
const AGENT = '00000000-0000-4000-8000-000000000001';
const PLAYER = '10000000-0000-4000-8000-000000000001';

test('TTS lifecycle automatically probes and recovers while STT remains independently ready', async () => {
	let calls = 0;
	let probes = 0;
	let releaseProbe;
	const probeGate = new Promise((resolve) => { releaseProbe = resolve; });
	await withWorker({
		provider: { async synthesize() {
			calls += 1;
			const error = new Error('temporary provider failure');
			error.code = 'TTS_UNAVAILABLE';
			throw error;
		}, async probe() { probes += 1; await probeGate; } },
		initialProbeDelayMs: 10,
		maxProbeDelayMs: 10,
	}, async ({ worker, baseUrl }) => {
		assert.equal(worker.statusSnapshot().state, 'ready');
		const failed = await fetch(`${baseUrl}/v1/tts`, { method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()) });
		assert.equal(failed.status, 502);
		const failedSnapshots = worker.statusSnapshots();
		assert.equal(failedSnapshots.find(({ component }) => component === 'voice:tts').failureCode, 'TTS_UNAVAILABLE');
		assert.equal(failedSnapshots.find(({ component }) => component === 'voice:stt').state, 'ready');
		assert.ok(Number.isSafeInteger(failedSnapshots.find(({ component }) => component === 'voice:tts').nextProbeAtEpochMs));
		releaseProbe();
		await eventually(() => worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state === 'ready');
		assert.equal(probes, 1, 'idle recovery uses one lightweight probe');
		assert.equal(calls, 1, 'recovery does not need another user TTS request');
	});
});

test('permanent STT failure remains degraded after successful TTS traffic', async () => {
	let sttProbes = 0;
	await withWorker({
		provider: { async synthesize() { return validSynthesis(); } },
		sttProvider: {
			async transcribe() { throw Object.assign(new Error('STT offline'), { code: 'STT_UNAVAILABLE' }); },
			async probe() { sttProbes += 1; throw Object.assign(new Error('STT offline'), { code: 'STT_UNAVAILABLE' }); },
		},
		initialProbeDelayMs: 10,
		maxProbeDelayMs: 20,
	}, async ({ worker, baseUrl }) => {
		const failed = await fetch(`${baseUrl}/v1/stt`, { method: 'POST', headers: sttHeaders(), body: Buffer.alloc(2) });
		assert.equal(failed.status, 503);
		const tts = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(tts.status, 200);
		await eventually(() => sttProbes >= 1);
		const snapshots = worker.statusSnapshots();
		assert.equal(snapshots.find(({ component }) => component === 'voice:tts').state, 'ready');
		assert.equal(snapshots.find(({ component }) => component === 'voice:stt').state, 'degraded');
		assert.equal(snapshots.find(({ component }) => component === 'voice').failureCode, 'STT_UNAVAILABLE');
	});
});

test('transient STT failure automatically recovers while idle', async () => {
	let probes = 0;
	await withWorker({
		sttProvider: {
			async transcribe() { throw Object.assign(new Error('temporary STT failure'), { code: 'STT_TIMEOUT' }); },
			async probe() { probes += 1; },
		},
		initialProbeDelayMs: 10,
		maxProbeDelayMs: 10,
	}, async ({ worker, baseUrl }) => {
		const failed = await fetch(`${baseUrl}/v1/stt`, { method: 'POST', headers: sttHeaders(), body: Buffer.alloc(2) });
		assert.equal(failed.status, 502);
		await eventually(() => worker.statusSnapshots().find(({ component }) => component === 'voice:stt').state === 'ready');
		assert.equal(probes, 1);
	});
});

test('TTS route rejects a non-JSON content type before synthesis', async () => {
	let calls = 0;
	await withWorker({
		provider: { async synthesize() { calls++; return validSynthesis(); } },
	}, async ({ worker, baseUrl }) => {
		const response = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST',
			headers: { Authorization: `Bearer ${SECRET}`, 'Content-Type': 'text/plain' },
			body: JSON.stringify(ttsPayload()),
		});
		assert.equal(response.status, 400);
		assert.equal((await response.json()).code, 'INVALID_REQUEST');
		assert.equal(calls, 0);
	});
});

test('STT route rejects a non-PCM content type before transcription', async () => {
	let calls = 0;
	await withWorker({
		sttProvider: { async transcribe() { calls++; return { transcript: 'ignored', confidence: 1 }; } },
	}, async ({ baseUrl }) => {
		const response = await fetch(`${baseUrl}/v1/stt`, {
			method: 'POST',
			headers: sttHeaders({ 'Content-Type': 'application/octet-stream' }),
			body: Buffer.alloc(2),
		});
		assert.equal(response.status, 400);
		assert.equal((await response.json()).code, 'INVALID_REQUEST');
		assert.equal(calls, 0);
	});
});

test('TTS route rejects provider audio that is not mono signed 16-bit PCM', async () => {
	await withWorker({
		provider: { async synthesize() {
			return { sampleRateHz: 44_100, channels: 2, sampleFormat: 'float32', pcm: Buffer.alloc(8) };
		} },
	}, async ({ baseUrl }) => {
		const response = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(response.status, 502);
		assert.equal((await response.json()).code, 'TTS_MALFORMED_AUDIO');
	});
});

test('STT route rejects malformed provider transcripts', async () => {
	await withWorker({
		sttProvider: { async transcribe() { return { transcript: 7, confidence: Number.NaN }; } },
	}, async ({ baseUrl }) => {
		const response = await fetch(`${baseUrl}/v1/stt`, {
			method: 'POST', headers: sttHeaders(), body: Buffer.alloc(2),
		});
		assert.equal(response.status, 502);
		assert.equal((await response.json()).code, 'STT_PROVIDER_RESPONSE');
	});
});

test('voice worker enforces its shared TTS and STT concurrency limit', async () => {
	let release;
	const waiting = new Promise((resolve) => { release = resolve; });
	let entered;
	const started = new Promise((resolve) => { entered = resolve; });
	await withWorker({
		maxConcurrent: 1,
		provider: { async synthesize() { entered(); await waiting; return validSynthesis(); } },
	}, async ({ baseUrl }) => {
		const first = fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		await started;
		const second = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), conversationSequence: 2 }),
		});
		assert.equal(second.status, 429);
		assert.equal((await second.json()).code, 'TTS_CAPACITY');
		release();
		assert.equal((await first).status, 200);
	});
});

test('TTS route aborts synthesis when the client closes before the response', async () => {
	let entered;
	const providerEntered = new Promise((resolve) => { entered = resolve; });
	let providerSignal;
	await withWorker({
		provider: { synthesize({ signal }) {
			providerSignal = signal;
			entered();
			return new Promise((resolve, reject) => {
				signal.addEventListener('abort', () => {
					const error = new Error('synthesis cancelled');
					error.name = 'AbortError';
					reject(error);
				}, { once: true });
			});
		} },
	}, async ({ worker, baseUrl }) => {
		const controller = new AbortController();
		const request = fetch(`${baseUrl}/v1/tts`, {
			method: 'POST',
			headers: ttsHeaders(),
			body: JSON.stringify(ttsPayload()),
			signal: controller.signal,
		});
		await providerEntered;
		controller.abort();
		await assert.rejects(request, (error) => error?.name === 'AbortError');
		await new Promise((resolve) => setTimeout(resolve, 50));
		assert.equal(providerSignal.aborted, true);
		assert.equal(worker.statusSnapshot().state, 'ready', 'client cancellation is not a provider outage');
	});
});

test('client cancellation releases its shared slot even when TTS ignores abort', async () => {
	let first = true;
	let releaseLate;
	const late = new Promise((resolve) => { releaseLate = resolve; });
	await withWorker({
		maxConcurrent: 1,
		provider: { async synthesize() {
			if (first) { first = false; return late; }
			return validSynthesis();
		} },
	}, async ({ baseUrl }) => {
		const controller = new AbortController();
		const cancelled = fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'cancel me' }), signal: controller.signal,
		});
		await eventuallyActive(baseUrl, 1);
		controller.abort();
		await assert.rejects(cancelled, (error) => error?.name === 'AbortError');
		await eventuallyActive(baseUrl, 0);

		try {
			const replacement = await fetch(`${baseUrl}/v1/tts`, {
				method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'replacement' }),
			});
			assert.equal(replacement.status, 200);
		} finally {
			releaseLate(validSynthesis());
		}
	});
});

test('TTS timeout releases its slot and fences a late synthesis result from cache', async () => {
	let calls = 0;
	let releaseLate;
	const late = new Promise((resolve) => { releaseLate = resolve; });
	await withWorker({
		maxConcurrent: 1,
		requestTimeoutMs: 20,
		provider: { async synthesize({ text }) {
			calls += 1;
			if (text === 'late') return late;
			return validSynthesis();
		} },
	}, async ({ baseUrl }) => {
		let timedOut;
		try {
			timedOut = await Promise.race([
				fetch(`${baseUrl}/v1/tts`, {
					method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'late' }),
				}),
				new Promise((_, reject) => setTimeout(() => reject(new Error('voice request did not time out')), 250)),
			]);
		} finally {
			releaseLate(validSynthesis());
		}
		assert.equal(timedOut.status, 504);
		await eventuallyActive(baseUrl, 0);

		const healthy = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'healthy' }),
		});
		assert.equal(healthy.status, 200);
		await new Promise((resolve) => setImmediate(resolve));
		const retried = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'late', conversationSequence: 3 }),
		});
		assert.equal(retried.status, 200);
		assert.equal(calls, 3, 'late timed-out audio was not cached or promoted');
	});
});

test('STT provider errors release the shared slot exactly once', async () => {
	let calls = 0;
	await withWorker({
		maxConcurrent: 1,
		sttProvider: { async transcribe() {
			calls += 1;
			if (calls === 1) throw Object.assign(new Error('temporary STT failure'), { code: 'STT_UNAVAILABLE' });
			return { transcript: 'heard', confidence: 0.9 };
		} },
	}, async ({ baseUrl }) => {
		const failed = await fetch(`${baseUrl}/v1/stt`, { method: 'POST', headers: sttHeaders(), body: Buffer.alloc(2) });
		assert.equal(failed.status, 503);
		assert.equal((await health(baseUrl)).active, 0);
		const recovered = await fetch(`${baseUrl}/v1/stt`, { method: 'POST', headers: sttHeaders({ 'X-Utterance-Sequence': '2' }), body: Buffer.alloc(2) });
		assert.equal(recovered.status, 200);
		assert.equal((await health(baseUrl)).active, 0);
	});
});

test('voice worker start and close are bounded when called concurrently', async () => {
	const worker = createVoiceHttpServer({
		provider: { async synthesize() { return validSynthesis(); } },
		profileStore: new VoiceProfileStore(),
		secret: SECRET,
		port: 0,
	});
	const addresses = await Promise.all([worker.start(), worker.start()]);
	assert.equal(addresses[0].port, addresses[1].port);
	await Promise.all([worker.close(), worker.close()]);
	assert.equal(worker.server.listening, false);
});

test('TTS cache copies values and evicts the least recently used entry', () => {
	const cache = new TtsCache({ maxBytes: 4 });
	const first = Buffer.from([1, 2]);
	cache.set('first', first);
	first[0] = 9;
	assert.deepEqual(cache.get('first'), Buffer.from([1, 2]));
	cache.set('second', Buffer.from([3, 4]));
	cache.get('first');
	cache.set('third', Buffer.from([5, 6]));
	assert.equal(cache.get('second'), null);
	assert.deepEqual(cache.get('first'), Buffer.from([1, 2]));
	assert.deepEqual(cache.get('third'), Buffer.from([5, 6]));
});

test('persistent voice assignments survive a store reload', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profiles-'));
	const file = path.join(root, 'assignments.json');
	try {
		const first = await loadPersistentVoiceProfileStore(file);
		const assigned = first.store.resolve(AGENT);
		await first.flush();
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.equal(document.schemaVersion, 1);
		assert.equal(document.assignments[AGENT], assigned.profileId);
		const reloaded = await loadPersistentVoiceProfileStore(file);
		assert.equal(reloaded.store.resolve(AGENT).profileId, assigned.profileId);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('Fish provider rejects odd-length PCM from the remote service', async () => {
	const provider = new FishTtsProvider({
		apiKey: 'fish-test-token',
		fetchImpl: async () => new Response(Buffer.alloc(3), { status: 200 }),
	});
	await assert.rejects(provider.synthesize({ text: 'Hello', voiceId: 'voice-id' }), (error) => {
		assert.equal(error.code, 'TTS_MALFORMED_AUDIO');
		return true;
	});
});

test('Fish provider preserves rate-limit retry metadata', async () => {
	const provider = new FishTtsProvider({
		apiKey: 'fish-test-token',
		fetchImpl: async () => new Response(null, { status: 429, headers: { 'retry-after': '12' } }),
	});
	await assert.rejects(provider.synthesize({ text: 'Hello', voiceId: 'voice-id' }), (error) => {
		assert.equal(error.code, 'TTS_RATE_LIMITED');
		assert.equal(error.retryAfter, '12');
		return true;
	});
});

test('Deepgram provider rejects unbounded or incomplete PCM before fetch', async () => {
	let calls = 0;
	const provider = new DeepgramSttProvider({
		apiKey: 'deepgram-test-token',
		fetchImpl: async () => { calls++; return Response.json({}); },
	});
	await assert.rejects(provider.transcribe({ pcm: Buffer.alloc(3) }), (error) => error.code === 'STT_MALFORMED_AUDIO');
	await assert.rejects(
		provider.transcribe({ pcm: Buffer.alloc(48_000 * 2 * 20 + 2) }),
		(error) => error.code === 'STT_MALFORMED_AUDIO',
	);
	assert.equal(calls, 0);
});

async function withWorker(options, verification) {
	const worker = createVoiceHttpServer({
		provider: options.provider ?? { async synthesize() { return validSynthesis(); } },
		sttProvider: options.sttProvider,
		profileStore: new VoiceProfileStore(),
		secret: SECRET,
		maxConcurrent: options.maxConcurrent,
		requestTimeoutMs: options.requestTimeoutMs,
		initialProbeDelayMs: options.initialProbeDelayMs,
		maxProbeDelayMs: options.maxProbeDelayMs,
		port: 0,
	});
	const address = await worker.start();
	try {
		await verification({ worker, baseUrl: `http://127.0.0.1:${address.port}` });
	} finally {
		await worker.close();
	}
}

async function health(baseUrl) {
	return (await fetch(`${baseUrl}/health`)).json();
}

async function eventuallyActive(baseUrl, expected) {
	for (let attempt = 0; attempt < 100; attempt += 1) {
		if ((await health(baseUrl)).active === expected) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(`voice worker active count did not reach ${expected}`);
}

async function eventually(predicate) {
	for (let attempt = 0; attempt < 100; attempt += 1) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error('voice lifecycle did not reach the expected state');
}

function validSynthesis() {
	return { sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(4) };
}

function ttsPayload() {
	return {
		agentId: AGENT,
		text: 'Testing voice.',
		profileId: 'voice.auto.v1',
		radius: 48,
		conversationSequence: 1,
	};
}

function ttsHeaders(overrides = {}) {
	return { Authorization: `Bearer ${SECRET}`, 'Content-Type': 'application/json', ...overrides };
}

function sttHeaders(overrides = {}) {
	return {
		Authorization: `Bearer ${SECRET}`,
		'Content-Type': 'audio/l16;rate=48000;channels=1',
		'X-Player-Id': PLAYER,
		'X-Utterance-Sequence': '1',
		'X-Whispering': 'false',
		...overrides,
	};
}
