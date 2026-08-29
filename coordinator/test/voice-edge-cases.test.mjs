import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { DeepgramSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
import { NoSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
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
		sttProvider: { async transcribe() { return { transcript: '', confidence: 1 }; } },
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

test('fallback TTS health probes never allocate or persist a synthetic agent voice', async () => {
	let syntheses = 0;
	let profileResolutions = 0;
	const profileStore = {
		resolve() {
			profileResolutions += 1;
			return builtInProbeProfile();
		},
	};
	const worker = createVoiceHttpServer({
		provider: {
			async synthesize() {
				syntheses += 1;
				if (syntheses === 1) throw Object.assign(new Error('temporary failure'), { code: 'TTS_UNAVAILABLE' });
				return validSynthesis();
			},
		},
		sttProvider: { async transcribe() { return { transcript: '', confidence: 1 }; } },
		profileStore,
		secret: SECRET,
		port: 0,
		initialProbeDelayMs: 5,
		maxProbeDelayMs: 5,
	});
	const address = await worker.start();
	try {
		const response = await fetch(`http://127.0.0.1:${address.port}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(response.status, 502);
		await eventually(() => worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state === 'ready');
		assert.equal(syntheses, 2, 'the provider receives one real request and one non-persisting probe');
		assert.equal(profileResolutions, 1, 'only the real agent request consumes a stored profile');
	} finally {
		await worker.close();
	}
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

test('missing and explicitly unavailable STT begin degraded without retry spam', async () => {
	for (const sttProvider of [null, new NoSttProvider()]) {
		const worker = createVoiceHttpServer({
			provider: { async synthesize() { return validSynthesis(); } },
			sttProvider,
			profileStore: new VoiceProfileStore(),
			secret: SECRET,
			port: 0,
		});
		try {
			const stt = worker.statusSnapshots().find(({ component }) => component === 'voice:stt');
			assert.equal(stt.state, 'degraded');
			assert.equal(stt.failureCode, 'STT_UNAVAILABLE');
			assert.equal(stt.nextProbeAtEpochMs, null);
			assert.equal(worker.statusSnapshot().failureCode, 'STT_UNAVAILABLE');
		} finally {
			await worker.close();
		}
	}
});

test('missing TTS begins degraded while the STT channel remains independently usable', async () => {
	const worker = createVoiceHttpServer({
		provider: null,
		sttProvider: { async transcribe() { return { transcript: 'heard', confidence: 1 }; } },
		profileStore: new VoiceProfileStore(),
		secret: SECRET,
		port: 0,
	});
	try {
		const snapshots = worker.statusSnapshots();
		assert.equal(snapshots.find(({ component }) => component === 'voice:tts').failureCode, 'TTS_UNAVAILABLE');
		assert.equal(snapshots.find(({ component }) => component === 'voice:stt').state, 'ready');
		const address = await worker.start();
		const unavailable = await fetch(`http://127.0.0.1:${address.port}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(unavailable.status, 503);
	} finally {
		await worker.close();
	}
});

test('abort-ignoring provider probe never accumulates a replacement call', async () => {
	let probes = 0;
	let aborts = 0;
	await withWorker({
		provider: {
			async synthesize() { throw Object.assign(new Error('TTS offline'), { code: 'TTS_UNAVAILABLE' }); },
			probe: ({ signal }) => {
				probes += 1;
				signal.addEventListener('abort', () => { aborts += 1; }, { once: true });
				return new Promise(() => {});
			},
		},
		initialProbeDelayMs: 5,
		maxProbeDelayMs: 5,
		probeTimeoutMs: 5,
	}, async ({ worker, baseUrl }) => {
		const failed = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(failed.status, 502);
		await new Promise((resolve) => setTimeout(resolve, 50));
		assert.equal(probes, 1, 'one unresolved provider call retains exact probe ownership');
		assert.equal(aborts, 1, 'probe deadline aborts the underlying provider call');
		assert.equal(worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state, 'degraded');
	});
});

test('new real success recovers logically but a later failure replaces the generation with a hung probe', async () => {
	let synthesisCalls = 0;
	let probes = 0;
	let oldProbeAborted = false;
	let terminalFailure = null;
	let releaseOldProbe;
	const oldProbe = new Promise((resolve) => { releaseOldProbe = resolve; });
	await withWorker({
		provider: {
			async synthesize() {
				synthesisCalls += 1;
				if (synthesisCalls === 1 || synthesisCalls === 3) {
					throw Object.assign(new Error('temporary TTS failure'), { code: 'TTS_UNAVAILABLE' });
				}
				return validSynthesis();
			},
			probe: ({ signal }) => {
				probes += 1;
				if (probes > 1) return Promise.resolve();
				signal.addEventListener('abort', () => { oldProbeAborted = true; }, { once: true });
				return oldProbe;
			},
		},
		initialProbeDelayMs: 5,
		maxProbeDelayMs: 5,
		probeTimeoutMs: 500,
	}, async ({ worker, baseUrl }) => {
		worker.onFailure((error) => { terminalFailure = error; });
		const first = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'first failure' }),
		});
		assert.equal(first.status, 502);
		await eventually(() => probes === 1);
		const success = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'real success', conversationSequence: 2 }),
		});
		assert.equal(success.status, 200);
		assert.equal(oldProbeAborted, true);
		const secondFailure = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'second failure', conversationSequence: 3 }),
		});
		assert.equal(secondFailure.status, 502);
		await eventually(() => terminalFailure !== null);
		assert.equal(terminalFailure.code, 'TTS_PROVIDER_STALLED');
		assert.equal(probes, 1, 'the unresolved physical probe prevents another call on this provider generation');
		releaseOldProbe();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state, 'degraded');
	});
});

test('repeated real recovery cannot accumulate abort-ignoring physical probes', async () => {
	let probes = 0;
	let terminalFailures = 0;
	let releaseProbe;
	const hungProbe = new Promise((resolve) => { releaseProbe = resolve; });
	const worker = createVoiceHttpServer({
		provider: {
			async synthesize({ text }) {
				if (text.startsWith('failure')) throw Object.assign(new Error('temporary TTS failure'), { code: 'TTS_UNAVAILABLE' });
				return validSynthesis();
			},
			probe: ({ signal }) => {
				probes += 1;
				signal.addEventListener('abort', () => {}, { once: true });
				return hungProbe;
			},
		},
		profileStore: new VoiceProfileStore(),
		secret: SECRET,
		initialProbeDelayMs: 5,
		maxProbeDelayMs: 5,
		probeTimeoutMs: 500,
		port: 0,
	});
	worker.onFailure((error) => {
		assert.equal(error.code, 'TTS_PROVIDER_STALLED');
		terminalFailures += 1;
	});
	const address = await worker.start();
	const baseUrl = `http://127.0.0.1:${address.port}`;
	try {
		const firstFailure = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'failure-0' }),
		});
		assert.equal(firstFailure.status, 502);
		await eventually(() => probes === 1);
		const recovered = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'success-0', conversationSequence: 2 }),
		});
		assert.equal(recovered.status, 200);
		assert.equal(worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state, 'ready');

		for (let cycle = 1; cycle <= 110; cycle += 1) {
			const failed = await fetch(`${baseUrl}/v1/tts`, {
				method: 'POST', headers: ttsHeaders(),
				body: JSON.stringify({ ...ttsPayload(), text: `failure-${cycle}`, conversationSequence: cycle * 2 + 1 }),
			});
			assert.equal(failed.status, 502);
			const success = await fetch(`${baseUrl}/v1/tts`, {
				method: 'POST', headers: ttsHeaders(),
				body: JSON.stringify({ ...ttsPayload(), text: `success-${cycle}`, conversationSequence: cycle * 2 + 2 }),
			});
			assert.equal(success.status, 200);
		}
		assert.equal(probes, 1, 'one exact provider generation owns at most one unsettled probe');
		assert.equal(terminalFailures, 1, 'the stalled generation requests one worker replacement');
		assert.equal(worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state, 'degraded');
	} finally {
		releaseProbe();
		await worker.close();
	}
});

test('persistent profile discovery aborts a stalled production read seam', async () => {
	const controller = new AbortController();
	let entered;
	const readEntered = new Promise((resolve) => { entered = resolve; });
	let observedSignal = null;
	const loading = loadPersistentVoiceProfileStore('stalled-profile-store.json', {
		signal: controller.signal,
		readFile: (filePath, options) => {
			observedSignal = options.signal;
			entered();
			return new Promise(() => {});
		},
	});
	const firstBoundary = await Promise.race([
		readEntered.then(() => 'entered'),
		loading.then(() => 'settled', () => 'settled'),
	]);
	assert.equal(firstBoundary, 'entered', 'production profile loader uses the injected read seam');
	controller.abort();
	await assert.rejects(loading, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
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

test('invalid STT client input does not degrade or probe the provider lifecycle', async () => {
	let calls = 0;
	let probes = 0;
	await withWorker({
		sttProvider: {
			async transcribe() { calls++; return { transcript: 'ignored', confidence: 1 }; },
			async probe() { probes++; },
		},
		initialProbeDelayMs: 5,
		maxProbeDelayMs: 5,
	}, async ({ worker, baseUrl }) => {
		const invalidRequests = [
			{ headers: sttHeaders({ 'X-Player-Id': 'not-a-uuid' }), body: Buffer.alloc(2) },
			{ headers: sttHeaders({ 'X-Utterance-Sequence': '0' }), body: Buffer.alloc(2) },
			{ headers: sttHeaders({ 'X-Whispering': 'sometimes' }), body: Buffer.alloc(2) },
			{ headers: sttHeaders(), body: Buffer.alloc(1) },
			{ headers: sttHeaders(), body: Buffer.alloc(48_000 * 2 * 20 + 2) },
		];
		for (const invalid of invalidRequests) {
			const response = await fetch(`${baseUrl}/v1/stt`, {
				method: 'POST', headers: invalid.headers, body: invalid.body,
			});
			assert.equal(response.ok, false);
			const stt = worker.statusSnapshots().find(({ component }) => component === 'voice:stt');
			assert.equal(stt.state, 'ready');
			assert.equal(stt.consecutiveFailureCount, 0);
			assert.equal(stt.nextProbeAtEpochMs, null);
		}
		assert.equal(calls, 0);
		assert.equal(probes, 0);
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
		assert.equal(
			worker.statusSnapshots().find(({ component }) => component === 'voice:tts').state,
			'ready',
			'client cancellation is not a TTS provider outage',
		);
	});
});

test('client cancellation retains its shared slot until abort-ignoring TTS settles', async () => {
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
		assert.equal((await health(baseUrl)).active, 1);
		try {
			const replacement = await fetch(`${baseUrl}/v1/tts`, {
				method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'replacement' }),
			});
			assert.equal(replacement.status, 429);
		} finally {
			releaseLate(validSynthesis());
		}
		await eventuallyActive(baseUrl, 0);
		const replacement = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify({ ...ttsPayload(), text: 'replacement' }),
		});
		assert.equal(replacement.status, 200);
	});
});

test('STT timeout retains its shared slot until abort-ignoring transcription settles', async () => {
	let releaseLate;
	const late = new Promise((resolve) => { releaseLate = resolve; });
	let first = true;
	await withWorker({
		maxConcurrent: 1,
		requestTimeoutMs: 20,
		sttProvider: { transcribe() {
			if (first) { first = false; return late; }
			return { transcript: 'heard', confidence: 1 };
		} },
	}, async ({ baseUrl }) => {
		const timedOut = await fetch(`${baseUrl}/v1/stt`, {
			method: 'POST', headers: sttHeaders(), body: Buffer.alloc(2),
		});
		assert.equal(timedOut.status, 504);
		assert.equal((await health(baseUrl)).active, 1);
		const blocked = await fetch(`${baseUrl}/v1/tts`, {
			method: 'POST', headers: ttsHeaders(), body: JSON.stringify(ttsPayload()),
		});
		assert.equal(blocked.status, 429);
		releaseLate({ transcript: 'late', confidence: 1 });
		await eventuallyActive(baseUrl, 0);
		const recovered = await fetch(`${baseUrl}/v1/stt`, {
			method: 'POST', headers: sttHeaders({ 'X-Utterance-Sequence': '2' }), body: Buffer.alloc(2),
		});
		assert.equal(recovered.status, 200);
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

test('provider-declared fallback audio bypasses the profile cache', async () => {
	let calls = 0;
	await withWorker({
		provider: { async synthesize() {
			calls += 1;
			return { ...validSynthesis(), cacheable: false };
		} },
	}, async ({ baseUrl }) => {
		for (let sequence = 1; sequence <= 2; sequence += 1) {
			const response = await fetch(`${baseUrl}/v1/tts`, {
				method: 'POST',
				headers: ttsHeaders(),
				body: JSON.stringify({ ...ttsPayload(), conversationSequence: sequence }),
			});
			assert.equal(response.status, 200);
		}
		assert.equal(calls, 2, 'fallback output is synthesized again instead of being reused as Fish audio');
	});
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

test('a transient profile publish failure does not poison a later flush', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-retry-'));
	const file = path.join(root, 'assignments.json');
	let mkdirCalls = 0;
	try {
		const persistent = await loadPersistentVoiceProfileStore(file, {
			mkdir: async (...arguments_) => {
				mkdirCalls += 1;
				if (mkdirCalls === 1) throw Object.assign(new Error('directory temporarily locked'), { code: 'EPERM' });
				return mkdir(...arguments_);
			},
		});
		persistent.store.resolve(agentUuid(1_001));
		await assert.rejects(persistent.flush(), (error) => error.code === 'EPERM');
		await persistent.flush();
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.ok(document.assignments[agentUuid(1_001)]);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('paired persistent stores use unique writes and preserve 100 rounds of merged assignments', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-paired-'));
	const file = path.join(root, 'assignments.json');
	const temporaryPaths = new Set();
	let collisions = 0;
	const instrumentedWrite = async (temporary, contents, options) => {
		if (temporaryPaths.has(temporary)) collisions += 1;
		temporaryPaths.add(temporary);
		try { return await writeFile(temporary, contents, options); }
		finally { temporaryPaths.delete(temporary); }
	};
	try {
		const [oldStore, newStore] = await Promise.all([
			loadPersistentVoiceProfileStore(file, { writeFile: instrumentedWrite }),
			loadPersistentVoiceProfileStore(file, { writeFile: instrumentedWrite }),
		]);
		for (let round = 0; round < 100; round += 1) {
			oldStore.store.resolve(agentUuid(2_000 + round));
			newStore.store.resolve(agentUuid(3_000 + round));
		}
		await Promise.all([oldStore.flush(), newStore.flush()]);
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.equal(Object.keys(document.assignments).length, 200);
		assert.equal(collisions, 0, 'physical writes never share temporary ownership');
		await Promise.all([oldStore.close(), newStore.close()]);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('overlapping persistent stores reserve colliding voice profiles once and keep them stable after reload', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-reservation-'));
	const file = path.join(root, 'assignments.json');
	const firstAgent = agentUuid(1);
	const secondAgent = agentUuid(10);
	try {
		const [first, second] = await Promise.all([
			loadPersistentVoiceProfileStore(file),
			loadPersistentVoiceProfileStore(file),
		]);
		const firstProfile = first.store.resolve(firstAgent).profileId;
		const secondProfile = second.store.resolve(secondAgent).profileId;
		assert.notEqual(firstProfile, secondProfile, 'the shared file coordinator reserves profiles across live stores');
		await Promise.all([first.flush(), second.flush()]);
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.equal(document.assignments[firstAgent], firstProfile);
		assert.equal(document.assignments[secondAgent], secondProfile);
		await Promise.all([first.close(), second.close()]);

		const reloaded = await loadPersistentVoiceProfileStore(file);
		assert.equal(reloaded.store.resolve(firstAgent).profileId, firstProfile);
		assert.equal(reloaded.store.resolve(secondAgent).profileId, secondProfile);
		await reloaded.close();
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('a timed-out old profile write cannot overwrite its replacement generation', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-fence-'));
	const file = path.join(root, 'assignments.json');
	const oldController = new AbortController();
	let oldWriteEntered;
	const entered = new Promise((resolve) => { oldWriteEntered = resolve; });
	let releaseOldWrite;
	const oldWriteGate = new Promise((resolve) => { releaseOldWrite = resolve; });
	try {
		const oldStore = await loadPersistentVoiceProfileStore(file, {
			signal: oldController.signal,
			writeFile: async (...arguments_) => {
				oldWriteEntered();
				await oldWriteGate;
				return writeFile(...arguments_);
			},
		});
		oldStore.store.resolve(agentUuid(4_001));
		await entered;
		oldController.abort();
		await Promise.race([
			assert.rejects(oldStore.flush(), (error) => error.name === 'AbortError'),
			new Promise((_, reject) => setTimeout(() => reject(new Error('old flush did not observe cancellation')), 100)),
		]);

		const replacement = await loadPersistentVoiceProfileStore(file);
		replacement.store.resolve(agentUuid(4_002));
		await replacement.flush();
		releaseOldWrite();
		await new Promise((resolve) => setTimeout(resolve, 25));
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.ok(document.assignments[agentUuid(4_001)]);
		assert.ok(document.assignments[agentUuid(4_002)]);
		const oldClose = oldStore.close();
		assert.equal(oldStore.close(), oldClose);
		await Promise.all([
			assert.rejects(oldClose, (error) => error.name === 'AbortError'),
			replacement.close(),
			replacement.close(),
		]);
	} finally {
		releaseOldWrite?.();
		await rm(root, { recursive: true, force: true });
	}
});

test('profile repair drains a second stale publish that lands while the first repair settles', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-repair-latch-'));
	const file = path.join(root, 'assignments.json');
	const firstController = new AbortController();
	const secondController = new AbortController();
	const firstMove = delayedProfileRename();
	const secondMove = delayedProfileRename();
	let signalRepairMoved;
	const repairMoved = new Promise((resolve) => { signalRepairMoved = resolve; });
	let releaseRepair;
	const repairGate = new Promise((resolve) => { releaseRepair = resolve; });
	let signalFollowUpMoved;
	const followUpMoved = new Promise((resolve) => { signalFollowUpMoved = resolve; });
	let currentMoves = 0;
	try {
		const first = await loadPersistentVoiceProfileStore(file, {
			signal: firstController.signal,
			rename: firstMove.rename,
		});
		first.store.resolve(agentUuid(4_101));
		await firstMove.entered;
		firstController.abort();
		await assert.rejects(first.flush(), (error) => error.name === 'AbortError');

		const second = await loadPersistentVoiceProfileStore(file, {
			signal: secondController.signal,
			rename: secondMove.rename,
		});
		second.store.resolve(agentUuid(4_102));
		await secondMove.entered;
		secondController.abort();
		await assert.rejects(second.flush(), (error) => error.name === 'AbortError');

		const current = await loadPersistentVoiceProfileStore(file, {
			rename: async (source, destination) => {
				currentMoves += 1;
				const bytes = await readFile(source);
				await writeFile(destination, bytes);
				if (currentMoves === 2) {
					signalRepairMoved();
					await repairGate;
				}
				if (currentMoves === 3) signalFollowUpMoved();
			},
		});
		current.store.resolve(agentUuid(4_103));
		await current.flush();

		firstMove.release();
		await repairMoved;
		secondMove.release();
		await secondMove.published;
		await new Promise((resolve) => setImmediate(resolve));
		releaseRepair();
		let followUpTimeout;
		try {
			await Promise.race([
				followUpMoved,
				new Promise((_, reject) => {
					followUpTimeout = setTimeout(() => reject(new Error('follow-up profile repair did not run')), 500);
				}),
			]);
		} finally {
			clearTimeout(followUpTimeout);
		}
		assert.equal(currentMoves, 3, 'the second stale publish requests exactly one follow-up repair');
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.ok(document.assignments[agentUuid(4_101)]);
		assert.ok(document.assignments[agentUuid(4_102)]);
		assert.ok(document.assignments[agentUuid(4_103)], 'the latest assignment is repaired after every stale publish');
		await Promise.all([
			assert.rejects(first.close(), (error) => error.name === 'AbortError'),
			assert.rejects(second.close(), (error) => error.name === 'AbortError'),
			current.close(),
		]);
	} finally {
		firstMove.release();
		secondMove.release();
		releaseRepair?.();
		await rm(root, { recursive: true, force: true });
	}
});

test('profile close retries a transient pending write and persists the latest assignment', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-close-retry-'));
	const file = path.join(root, 'assignments.json');
	let writes = 0;
	let signalWriteEntered;
	const writeEntered = new Promise((resolve) => { signalWriteEntered = resolve; });
	let releaseWrite;
	const writeGate = new Promise((resolve) => { releaseWrite = resolve; });
	try {
		const persistent = await loadPersistentVoiceProfileStore(file, {
			writeFile: async (...arguments_) => {
				writes += 1;
				if (writes === 1) {
					signalWriteEntered();
					await writeGate;
					throw Object.assign(new Error('file temporarily locked'), { code: 'EPERM' });
				}
				return writeFile(...arguments_);
			},
		});
		persistent.store.resolve(agentUuid(4_201));
		await writeEntered;
		const closing = persistent.close();
		assert.equal(persistent.close(), closing);
		releaseWrite();
		await closing;
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.ok(document.assignments[agentUuid(4_201)]);
		assert.equal(writes, 2);
	} finally {
		releaseWrite?.();
		await rm(root, { recursive: true, force: true });
	}
});

test('persistent profile close is bounded and idempotent when storage ignores cancellation', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-close-'));
	const file = path.join(root, 'assignments.json');
	let writeEntered;
	const entered = new Promise((resolve) => { writeEntered = resolve; });
	try {
		const persistent = await loadPersistentVoiceProfileStore(file, {
			closeTimeoutMs: 10,
			writeFile: () => {
				writeEntered();
				return new Promise(() => {});
			},
		});
		persistent.store.resolve(agentUuid(5_001));
		await entered;
		const firstClose = persistent.close();
		assert.equal(persistent.close(), firstClose);
		await assert.rejects(Promise.race([
			firstClose,
			new Promise((_, reject) => setTimeout(() => reject(new Error('profile close exceeded its bound')), 100)),
		]), (error) => error.name === 'AbortError');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('profile repair retries a transient publish failure and recovers before close', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-repair-retry-'));
	const file = path.join(root, 'assignments.json');
	const oldController = new AbortController();
	const staleMove = delayedProfileRename();
	let currentMoves = 0;
	let signalRepairPublished;
	const repairPublished = new Promise((resolve) => { signalRepairPublished = resolve; });
	try {
		const oldStore = await loadPersistentVoiceProfileStore(file, {
			signal: oldController.signal,
			rename: staleMove.rename,
		});
		oldStore.store.resolve(agentUuid(4_301));
		await staleMove.entered;
		oldController.abort();
		await assert.rejects(oldStore.flush(), (error) => error.name === 'AbortError');

		const current = await loadPersistentVoiceProfileStore(file, {
			rename: async (source, destination) => {
				const attempt = ++currentMoves;
				if (attempt === 2) throw Object.assign(new Error('file temporarily locked'), { code: 'EPERM' });
				await writeFile(destination, await readFile(source));
				if (attempt === 3) signalRepairPublished();
			},
		});
		current.store.resolve(agentUuid(4_302));
		await current.flush();
		staleMove.release();
		await staleMove.published;
		let repairTimeout;
		try {
			await Promise.race([
				repairPublished,
				new Promise((_, reject) => {
					repairTimeout = setTimeout(() => reject(new Error('profile repair did not publish')), 500);
				}),
			]);
		} finally {
			clearTimeout(repairTimeout);
		}
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.ok(document.assignments[agentUuid(4_302)]);
		await assert.rejects(oldStore.close(), (error) => error.name === 'AbortError');
		await current.close();
	} finally {
		staleMove.release();
		await rm(root, { recursive: true, force: true });
	}
});

test('permanent profile repair failure stops retrying and releases all retry work after close', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-repair-stop-'));
	const file = path.join(root, 'assignments.json');
	const oldController = new AbortController();
	const staleMove = delayedProfileRename();
	let currentMoves = 0;
	try {
		const oldStore = await loadPersistentVoiceProfileStore(file, {
			signal: oldController.signal,
			rename: staleMove.rename,
		});
		oldStore.store.resolve(agentUuid(4_401));
		await staleMove.entered;
		oldController.abort();
		await assert.rejects(oldStore.flush(), (error) => error.name === 'AbortError');

		const current = await loadPersistentVoiceProfileStore(file, {
			closeTimeoutMs: 100,
			rename: async (source, destination) => {
				currentMoves += 1;
				if (currentMoves > 1) throw Object.assign(new Error('file permanently locked'), { code: 'EPERM' });
				await writeFile(destination, await readFile(source));
			},
		});
		current.store.resolve(agentUuid(4_402));
		await current.flush();
		staleMove.release();
		await staleMove.published;
		await waitForCondition(() => currentMoves === 6, 'bounded profile repair attempts did not finish', 1_000);
		await assert.rejects(current.close(), (error) => error.name === 'AbortError');
		await assert.rejects(oldStore.close(), (error) => error.name === 'AbortError');
		const attemptsAfterClose = currentMoves;
		await new Promise((resolve) => setTimeout(resolve, 150));
		assert.equal(currentMoves, attemptsAfterClose, 'closed profile ownership cannot retain retry timers or filesystem work');
	} finally {
		staleMove.release();
		await rm(root, { recursive: true, force: true });
	}
});

test('persistent profile resolve rejects after close without changing its snapshot', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-closed-resolve-'));
	const file = path.join(root, 'assignments.json');
	try {
		const persistent = await loadPersistentVoiceProfileStore(file);
		persistent.store.resolve(agentUuid(4_501));
		await persistent.close();
		const before = persistent.store.snapshotAssignments();
		assert.throws(
			() => persistent.store.resolve(agentUuid(4_502)),
			(error) => error.code === 'VOICE_PROFILE_STORE_CLOSED',
		);
		assert.deepEqual(persistent.store.snapshotAssignments(), before);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('closing an older profile store cannot cancel the latest store owner', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-voice-profile-owner-close-'));
	const file = path.join(root, 'assignments.json');
	try {
		const oldStore = await loadPersistentVoiceProfileStore(file);
		const latestStore = await loadPersistentVoiceProfileStore(file);
		await oldStore.close();
		const assigned = latestStore.store.resolve(agentUuid(4_601));
		await latestStore.flush();
		const document = JSON.parse(await readFile(file, 'utf8'));
		assert.equal(document.assignments[agentUuid(4_601)], assigned.profileId);
		await latestStore.close();
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
		probeTimeoutMs: options.probeTimeoutMs,
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

function builtInProbeProfile() {
	return { profileId: 'voice.test', provider: 'fish', model: 'test', voiceId: 'test', revision: 1, speed: 1 };
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

function agentUuid(value) {
	return `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
}

function delayedProfileRename() {
	let signalEntered;
	const entered = new Promise((resolve) => { signalEntered = resolve; });
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	let signalPublished;
	const published = new Promise((resolve) => { signalPublished = resolve; });
	return {
		entered,
		published,
		release,
		rename: async (source, destination) => {
			const bytes = await readFile(source);
			signalEntered();
			await gate;
			await writeFile(destination, bytes);
			signalPublished();
		},
	};
}

async function waitForCondition(condition, message, timeoutMs = 500) {
	const deadline = Date.now() + timeoutMs;
	while (!condition()) {
		if (Date.now() >= deadline) throw new Error(message);
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
}
