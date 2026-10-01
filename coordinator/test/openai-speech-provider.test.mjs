import assert from 'node:assert/strict';
import test from 'node:test';
import { OpenAiTtsProvider, OpenAiSttProvider, openAiVoiceForProfile } from '../src/voice/openai-speech-provider.mjs';
import { startVoiceWorker } from '../src/dynamic-main.mjs';
import { VoiceProfileStore } from '../src/voice/voice-profile-store.mjs';
import { directorVoiceProfiles } from '../src/voice/voice-profile-store.mjs';
import { createVoiceHttpServer, createVoiceRequestHeaders } from '../src/voice/voice-http-server.mjs';

test('OpenAI TTS reads exactly the selected agent reply as bounded 24 kHz PCM', async () => {
	let request;
	const provider = new OpenAiTtsProvider({ apiKey: 'private-test-key', fetchImpl: async (url, options) => {
		request = { url, options };
		return new Response(Buffer.from([0, 0, 1, 0]), { status: 200 });
	} });
	const spoken = 'The stone pickaxe and axe are ready.';
	const audio = await provider.synthesize({ text: spoken, voiceId: 'profile-for-sol', speed: 1.03, tone: 'warm' });
	assert.equal(request.url, 'https://api.openai.com/v1/audio/speech');
	assert.equal(request.options.headers.Authorization, 'Bearer private-test-key');
	const body = JSON.parse(request.options.body);
	assert.equal(body.model, 'gpt-4o-mini-tts');
	assert.equal(body.input, spoken);
	assert.equal(body.voice, openAiVoiceForProfile('profile-for-sol'));
	assert.equal(body.response_format, 'pcm');
	assert.equal(body.speed, 1.03);
	assert.equal(audio.sampleRateHz, 24_000);
	assert.equal(audio.sampleFormat, 's16le');
	assert.deepEqual(audio.pcm, Buffer.from([0, 0, 1, 0]));
	assert.match(provider.cacheNamespace(), /^openai\/gpt-4o-mini-tts\//);
});

test('OpenAI STT uploads valid WAV containing the unmodified microphone samples', async () => {
	let request;
	const pcm = Buffer.from([1, 0, 254, 255]);
	const provider = new OpenAiSttProvider({ apiKey: 'private-test-key', fetchImpl: async (url, options) => {
		request = { url, options };
		return Response.json({ text: '  Get an iron pickaxe.  ' });
	} });
	const transcript = await provider.transcribe({ pcm });
	assert.equal(request.url, 'https://api.openai.com/v1/audio/transcriptions');
	assert.equal(request.options.body.get('model'), 'gpt-transcribe');
	assert.equal(request.options.body.get('response_format'), 'json');
	assert.equal(request.options.headers['Content-Type'], undefined, 'fetch must add the multipart boundary');
	const wav = Buffer.from(await request.options.body.get('file').arrayBuffer());
	assert.equal(wav.subarray(0, 4).toString(), 'RIFF');
	assert.equal(wav.readUInt32LE(24), 48_000);
	assert.equal(wav.readUInt16LE(22), 1);
	assert.equal(wav.readUInt32LE(40), pcm.length);
	assert.deepEqual(wav.subarray(44), pcm);
	assert.deepEqual(transcript, { transcript: 'Get an iron pickaxe.', confidence: 0 });
});

test('OpenAI speech reports authentication and rate failures without reflecting credentials', async () => {
	for (const status of [401, 403, 429, 500]) {
		const provider = new OpenAiTtsProvider({ apiKey: 'do-not-print-this-key', fetchImpl: async () =>
			new Response('upstream error containing do-not-print-this-key', { status, headers: { 'retry-after': '2' } }) });
		await assert.rejects(provider.synthesize({ text: 'Hello.', voiceId: 'cedar' }), error => {
			assert.doesNotMatch(error.message, /do-not-print/);
			assert.equal(error.code, status === 429 ? 'TTS_RATE_LIMITED' : status < 500 ? 'TTS_AUTHENTICATION_FAILED' : 'TTS_PROVIDER_ERROR');
			assert.equal(error.retryAfter, '2');
			return true;
		});
	}
});

test('OpenAI speech rejects malformed and oversized audio and transcripts', async () => {
	const tts = new OpenAiTtsProvider({ apiKey: 'test-key', fetchImpl: async () => new Response(Buffer.from([1])) });
	await assert.rejects(tts.synthesize({ text: 'Hello.', voiceId: 'cedar' }), { code: 'TTS_MALFORMED_AUDIO' });
	const huge = new OpenAiTtsProvider({ apiKey: 'test-key', fetchImpl: async () => new Response(Buffer.alloc(24_000 * 2 * 20 + 2)) });
	await assert.rejects(huge.synthesize({ text: 'Hello.', voiceId: 'cedar' }), { code: 'TTS_RESPONSE_TOO_LARGE' });
	const stt = new OpenAiSttProvider({ apiKey: 'test-key', fetchImpl: async () => Response.json({ invalid: 'no transcript' }) });
	await assert.rejects(stt.transcribe({ pcm: Buffer.alloc(10) }), { code: 'STT_PROVIDER_RESPONSE' });
	await assert.rejects(stt.transcribe({ pcm: Buffer.alloc(48_000 * 2 * 20 + 2) }), { code: 'STT_MALFORMED_AUDIO' });
});

test('cancelling OpenAI speech cancels the provider request', async () => {
	const controller = new AbortController();
	let entered;
	const started = new Promise(resolve => { entered = resolve; });
	const provider = new OpenAiTtsProvider({ apiKey: 'test-key', fetchImpl: async (_url, { signal }) => {
		entered();
		return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
	} });
	const speech = provider.synthesize({ text: 'Hello.', voiceId: 'cedar', signal: controller.signal });
	await started;
	controller.abort();
	await assert.rejects(speech, { name: 'AbortError' });
});

test('OpenAI speech bootstrap takes precedence over old Fish and Deepgram credentials without loading local models', async () => {
	const tts = new OpenAiTtsProvider({ apiKey: 'test-key' });
	const stt = new OpenAiSttProvider({ apiKey: 'test-key' });
	let started = false;
	let parameters;
	const worker = await startVoiceWorker({ voice: { secret: 'voice-bootstrap-secret', provider: 'openai' } }, {
		OPENAI_API_KEY: 'test-key', FISH_AUDIO_API_KEY: 'old-fish-key', DEEPGRAM_API_KEY: 'old-deepgram-key',
	}, {
		createLocalSpeechProvider: () => { throw new Error('OpenAI must not start local models'); },
		loadProfileStore: async () => ({ store: new VoiceProfileStore() }),
		createOpenAiTtsProvider: options => { assert.equal(options.model, 'gpt-4o-mini-tts'); return tts; },
		createOpenAiSttProvider: options => { assert.equal(options.model, 'gpt-transcribe'); return stt; },
		createVoiceServer: options => { parameters = options; return { async start() { started = true; }, async close() {} }; },
	});
	assert.equal(started, true);
	assert.equal(parameters.provider, tts);
	assert.equal(parameters.sttProvider, stt);
	assert.equal(parameters.fishProvider, undefined);
	assert.equal(parameters.directorUsesPrimaryProvider, true, 'Director speech also uses OpenAI');
	await worker.close();
});

test('explicit OpenAI speech configuration never falls back to Fish when its key is missing', async () => {
	await assert.rejects(startVoiceWorker({ voice: { provider: 'openai' } }, { FISH_AUDIO_API_KEY: 'old-fish-key' }), { code: 'VOICE_OPENAI_KEY_MISSING' });
});

test('signed in-game and Director requests use OpenAI speech through the real loopback worker', async () => {
	const requests = [];
	const fetchImpl = async (url, options) => {
		requests.push({ url, options });
		return url.endsWith('/speech') ? new Response(Buffer.alloc(2400 * 2)) : Response.json({ text: 'Get iron tools.' });
	};
	const secret = 'openai-loopback-test-secret';
	const agentId = '00000000-0000-4000-8000-000000000001';
	const worker = createVoiceHttpServer({
		provider: new OpenAiTtsProvider({ apiKey: 'test-key', fetchImpl }),
		sttProvider: new OpenAiSttProvider({ apiKey: 'test-key', fetchImpl }),
		directorUsesPrimaryProvider: true, profileStore: new VoiceProfileStore(), secret, port: 0,
	});
	const address = await worker.start();
	async function post(path, body, contentType, identityHeaders = {}) {
		const headers = createVoiceRequestHeaders({ secret, path, body, contentType, identityHeaders });
		return globalThis.fetch(`http://127.0.0.1:${address.port}${path}`, {
			method: 'POST', body, headers: { ...identityHeaders, ...headers, 'Content-Type': contentType },
		});
	}
	try {
		for (const profileId of ['voice.auto.v1', directorVoiceProfiles()[0].profileId]) {
			const body = Buffer.from(JSON.stringify({ agentId, text: 'Tools are ready.', profileId, radius: 48, conversationSequence: 1 }));
			const response = await post('/v1/tts', body, 'application/json');
			assert.equal(response.status, 200, await response.clone().text());
			assert.equal((await response.arrayBuffer()).byteLength, 4800 * 2, '24 kHz API audio is resampled to the 48 kHz game transport');
		}
		assert.equal(requests.length, 2);
		assert.ok(requests.every(({ options }) => JSON.parse(options.body).input === 'Tools are ready.'));
		const identity = { 'X-Player-Id': agentId, 'X-Utterance-Sequence': '7', 'X-Whispering': 'true' };
		const response = await post('/v1/stt', Buffer.alloc(1920), 'audio/l16;rate=48000;channels=1', identity);
		assert.equal(response.status, 200, await response.clone().text());
		assert.deepEqual(await response.json(), { playerId: agentId, utteranceSequence: 7, whispering: true, transcript: 'Get iron tools.', confidence: 0 });
	} finally { await worker.close(); }
});
