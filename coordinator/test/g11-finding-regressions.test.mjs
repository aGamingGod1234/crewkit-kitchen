import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, readdir, unlink, rmdir } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { FishTtsProvider } from '../src/voice/fish-tts-provider.mjs';
import { DeepgramSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
import { OpenAiTtsProvider, OpenAiSttProvider } from '../src/voice/openai-speech-provider.mjs';
import { createVoiceHttpServer, createVoiceRequestHeaders } from '../src/voice/voice-http-server.mjs';
import { VoiceProfileStore, loadPersistentVoiceProfileStore } from '../src/voice/voice-profile-store.mjs';

const secret = 'g11-synthetic-loopback-secret';
const uuid = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const flush = async () => { for (let n = 0; n < 8; n++) await new Promise(setImmediate); };
const pcm = () => ({ sampleRateHz: 24_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(24) });
function clock() {
	let now = 0;
	const jobs = new Set();
	return {
		now: () => now,
		schedule(callback, delay) { const job = { callback, at: now + delay }; jobs.add(job); return job; },
		cancel: job => jobs.delete(job),
		pending: () => [...jobs].map(job => job.at).sort((a, b) => a - b),
		async advance(target) {
			let count = 0;
			for (;;) {
				const next = [...jobs].sort((a, b) => a.at - b.at)[0];
				if (!next || next.at > target) break;
				assert.ok(++count < 50, 'bounded fixture scheduler');
				jobs.delete(next); now = next.at; next.callback(); await flush();
			}
			now = target; await flush();
		},
	};
}
async function withWorker(options, run) {
	const time = clock();
	const worker = createVoiceHttpServer({ profileStore: new VoiceProfileStore(), secret, port: 0,
		now: time.now, scheduleProbe: time.schedule, cancelProbe: time.cancel, ...options });
	const address = await worker.start();
	let sequence = 0;
	async function post(channel, overrides = {}) {
		const identityHeaders = channel === 'stt' ? { 'X-Player-Id': uuid(++sequence), 'X-Utterance-Sequence': '1', 'X-Whispering': 'false' } : {};
		const body = channel === 'stt' ? Buffer.alloc(1920) : Buffer.from(JSON.stringify({
			agentId: uuid(1), text: 'Fixture speech.', profileId: 'voice.auto.v1', radius: 48, conversationSequence: 1, ...overrides,
		}));
		const contentType = channel === 'stt' ? 'audio/l16;rate=48000;channels=1' : 'application/json';
		const route = `/v1/${channel}`;
		const response = await fetch(`http://127.0.0.1:${address.port}${route}`, { method: 'POST', body,
			headers: { ...createVoiceRequestHeaders({ secret, path: route, body, contentType, identityHeaders }), ...identityHeaders, 'Content-Type': contentType },
			signal: AbortSignal.timeout(5000) });
		const bytes = Buffer.from(await response.arrayBuffer());
		await flush();
		return { status: response.status, retryAfter: response.headers.get('retry-after'), bytes };
	}
	try { await run({ worker, time, post }); }
	finally { await worker.close(); assert.deepEqual(time.pending(), []); }
}

for (const [name, Provider, invoke] of [
	['Fish', FishTtsProvider, provider => provider.synthesize({ text: 'Hello.', voiceId: 'fixture' })],
	['Deepgram', DeepgramSttProvider, provider => provider.transcribe({ pcm: Buffer.alloc(2) })],
]) {
	test(`${name} releases unfinished HTTP errors without awaiting cancellation`, async () => {
		for (const status of [401, 429, 503]) {
			let signal, cancellations = 0, finishCancel;
			const cancelled = new Promise(resolve => { finishCancel = resolve; });
			const response = new Response(new ReadableStream({ cancel() { cancellations++; return cancelled; } }),
				{ status, headers: { 'retry-after': '60' } });
			const provider = new Provider({ apiKey: 'fixture', fetchImpl: async (_, options) => { signal = options.signal; return response; } });
			try {
				await assert.rejects(invoke(provider), error => {
					assert.match(error.code, /AUTHENTICATION_FAILED|RATE_LIMITED|PROVIDER_ERROR/);
					if (status === 429) assert.equal(error.retryAfter, '60');
					return true;
				});
				assert.equal(signal.aborted, true);
				assert.equal(cancellations, 1);
			} finally { finishCancel(); await response.body.cancel(); }
		}
	});
}

for (const channel of ['tts', 'stt']) {
	test(`${channel} recovery respects Retry-After and foreground success cancels it`, async () => {
		let calls = 0, failed = true;
		const Provider = channel === 'tts' ? OpenAiTtsProvider : OpenAiSttProvider;
		const provider = new Provider({ apiKey: 'fixture', fetchImpl: async () => {
			calls++;
			if (failed) return new Response(null, { status: 429, headers: { 'retry-after': '60' } });
			return channel === 'tts' ? new Response(Buffer.alloc(24)) : Response.json({ text: 'Recovered.' });
		} });
		await withWorker({ provider: channel === 'tts' ? provider : null, sttProvider: channel === 'stt' ? provider : null }, async ({ time, post }) => {
			const first = await post(channel);
			assert.equal(first.status, 429); assert.equal(first.retryAfter, '60');
			assert.deepEqual(time.pending(), [60000]);
			await time.advance(59999); assert.equal(calls, 1);
			await time.advance(60000); assert.equal(calls, 2);
			assert.deepEqual(time.pending(), [120000]);
			failed = false;
			assert.equal((await post(channel, { text: 'Foreground recovery.' })).status, 200);
			assert.deepEqual(time.pending(), []);
			await time.advance(180000); assert.equal(calls, 3);
		});
	});
}

test('later rate limit extends a pending recovery deadline; malformed headers preserve backoff', async () => {
	let retryAfter = 'invalid', probes = 0;
	await withWorker({ provider: { synthesize() { throw Object.assign(new Error('limited'), { code: 'TTS_RATE_LIMITED', retryAfter }); }, probe() { probes++; } } },
		async ({ time, post }) => {
			assert.equal((await post('tts')).status, 429);
			assert.deepEqual(time.pending(), [1000]);
			await time.advance(500); retryAfter = '60';
			assert.equal((await post('tts')).status, 429);
			assert.deepEqual(time.pending(), [60500]);
			await time.advance(60499); assert.equal(probes, 0);
			await time.advance(60500); assert.equal(probes, 1);
		});
});

for (const mode of ['cooperative', 'ignores-abort', 'before-deadline']) {
	test(`probe deadline retains ownership and distinguishes ${mode}`, async () => {
		let settleProbe, started = false, aborted = false, calls = 0, terminal = 0, settleStt;
		const gate = new Promise((resolve, reject) => { settleProbe = { resolve, reject }; });
		const sttGate = new Promise(resolve => { settleStt = resolve; });
		await withWorker({ provider: {
			synthesize({ text }) { if (text === 'Fail.') throw Object.assign(new Error('offline'), { code: 'TTS_UNAVAILABLE' }); calls++; return pcm(); },
			probe({ signal }) { started = true; signal.addEventListener('abort', () => { aborted = true;
				if (mode === 'cooperative') setImmediate(() => settleProbe.reject(signal.reason));
			}, { once: true }); return gate; },
		}, sttProvider: { transcribe: () => sttGate } }, async ({ time, worker, post }) => {
			worker.onFailure(() => terminal++);
			assert.equal((await post('tts')).status, 200);
			assert.equal((await post('tts')).status, 200); assert.equal(calls, 1);
			assert.equal((await post('tts', { text: 'Fail.' })).status, 502);
			await time.advance(1000); assert.equal(started, true);
			const concurrent = post('stt');
			try {
				if (mode === 'before-deadline') { await time.advance(10999); settleProbe.resolve(); await flush(); }
				await time.advance(11000);
				assert.equal(terminal, 0, 'cancellation must precede any terminal classification');
				assert.equal(aborted, mode !== 'before-deadline');
				await time.advance(12000);
				assert.equal(terminal, mode === 'ignores-abort' ? 1 : 0);
				assert.equal((await post('tts')).status, 200); assert.equal(calls, 1, 'cache survives channel cancellation');
			} finally {
				settleProbe.resolve(); settleStt({ transcript: 'Uninterrupted.', confidence: 1 });
				assert.equal((await concurrent).status, 200); await flush();
			}
		});
	});
}

test('unsupported OpenAI tones are client errors, while every supported tone and default remain healthy', async () => {
	let calls = 0;
	const provider = new OpenAiTtsProvider({ apiKey: 'fixture', fetchImpl: async () => { calls++; return new Response(Buffer.alloc(24)); } });
	await withWorker({ provider, directorUsesPrimaryProvider: true }, async ({ worker, time, post }) => {
		const invalid = await post('tts', { profileId: 'voice.laura.v1', tone: 'happy', speed: 1 });
		assert.equal(invalid.status, 400); assert.equal(JSON.parse(invalid.bytes).code, 'INVALID_REQUEST');
		assert.equal(calls, 0); assert.deepEqual(time.pending(), []);
		assert.equal(worker.statusSnapshots().find(row => row.component === 'voice:tts').state, 'ready');
		for (const tone of ['neutral', 'warm', 'excited', 'serious', 'dramatic', 'whisper', 'robotic', 'angry']) {
			assert.equal((await post('tts', { tone, speed: 1, text: tone })).status, 200);
		}
		assert.equal((await post('tts')).status, 200); assert.equal(calls, 9);
	});
});

test('reload retains shared assignments for removal without weakening allocation or owner fences', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'arena-g11-profiles-'));
	const file = path.join(directory, 'assignments.json');
	const owners = [];
	try {
		const original = await loadPersistentVoiceProfileStore(file); owners.push(original);
		for (let n = 1; n <= 17; n++) original.store.resolve(uuid(n));
		const seed = original.store.snapshotAssignments();
		assert.equal(new Set(Object.values(seed).slice(0, 16)).size, 16);
		await original.close();
		const loaded = await loadPersistentVoiceProfileStore(file); owners.push(loaded);
		assert.deepEqual(loaded.store.snapshotAssignments(), seed);
		assert.equal(loaded.store.remove(uuid(17)), true); await loaded.close();
		const current = await loadPersistentVoiceProfileStore(file); owners.push(current);
		assert.equal(Object.hasOwn(current.store.snapshotAssignments(), uuid(17)), false);
		const retained = current.store.resolve(uuid(1));
		const newer = await loadPersistentVoiceProfileStore(file); owners.push(newer);
		newer.store.resolve(uuid(18)); await newer.flush();
		current.store.remove(uuid(1)); await current.flush();
		assert.equal(JSON.parse(await readFile(file, 'utf8')).assignments[uuid(1)], retained.profileId,
			'older snapshot cannot remove an assignment claimed by the newer owner');
	} finally {
		for (const owner of owners) await owner.close();
		assert.equal(path.dirname(file), directory);
		assert.deepEqual(await readdir(directory), ['assignments.json']);
		await unlink(file); await rmdir(directory);
	}
});
