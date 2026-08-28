import assert from 'node:assert/strict';
import test from 'node:test';

import { startVoiceWorker } from '../src/dynamic-main.mjs';

const SECRET = 'voice-bootstrap-test-secret';

test('voice bootstrap uses credential-free Windows speech when Fish is not configured', async () => {
	let profileLoads = 0;
	let localProviders = 0;
	let starts = 0;
	const serverWorker = {
		async start() { starts++; return { port: 8766 }; },
		async close() {},
	};
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 0 },
		fishApiKey: 'config-must-not-be-used',
	}, {}, {
		platform: 'win32',
		loadProfileStore: async () => { profileLoads++; return { store: { resolve() {} } }; },
		createWindowsTtsProvider: () => { localProviders++; return { synthesize: async () => ({}) }; },
		createVoiceServer: ({ provider }) => {
			assert.equal(typeof provider.synthesize, 'function');
			return serverWorker;
		},
	});

	assert.equal(created, serverWorker);
	assert.equal(profileLoads, 1);
	assert.equal(localProviders, 1);
	assert.equal(starts, 1);
	await created.close();
});

test('voice bootstrap remains disabled without a provider on non-Windows hosts', async () => {
	let profileLoads = 0;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 0 },
	}, {}, {
		platform: 'linux',
		loadProfileStore: async () => { profileLoads++; return { store: { resolve() {} } }; },
	});

	assert.equal(worker, null);
	assert.equal(profileLoads, 0);
});

test('voice bootstrap reads Fish and optional STT credentials from environment only', async () => {
	const captured = {};
	const worker = {
		async start() { captured.started = true; return { port: 8766 }; },
		async close() { captured.closed = (captured.closed ?? 0) + 1; },
	};
	const profiles = { store: { resolve() {} }, flush: async () => {} };
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 8766, maxConcurrent: 2 },
		fishApiKey: 'config-must-not-be-used',
	}, {
		FISH_AUDIO_API_KEY: '',
		FISH_API_KEY: 'fish-from-environment',
		DEEPGRAM_API_KEY: 'deepgram-from-environment',
	}, {
		loadProfileStore: async (filePath) => {
			captured.profilePath = filePath;
			return profiles;
		},
		createTtsProvider: ({ apiKey }) => {
			captured.fishApiKey = apiKey;
			return { synthesize: async () => ({}) };
		},
		createSttProvider: ({ apiKey }) => {
			captured.deepgramApiKey = apiKey;
			return { transcribe: async () => ({ transcript: '', confidence: 0 }) };
		},
		createVoiceServer: (options) => {
			captured.serverOptions = options;
			return worker;
		},
		profilePath: 'test-profile-assignments.json',
	});

	assert.equal(created, worker);
	assert.equal(captured.fishApiKey, 'fish-from-environment');
	assert.equal(captured.deepgramApiKey, 'deepgram-from-environment');
	assert.equal(captured.serverOptions.secret, SECRET);
	assert.equal(captured.serverOptions.port, 8766);
	assert.equal(captured.serverOptions.maxConcurrent, 2);
	assert.equal(captured.started, true);
	assert.equal(captured.profilePath, 'test-profile-assignments.json');
	assert.doesNotMatch(JSON.stringify(captured.serverOptions), /fish-from-environment|deepgram-from-environment/);

	await created.close();
	assert.equal(captured.closed, 1);
});

test('Windows voice bootstrap falls back to local speech when Fish rejects a stale credential', async () => {
	const rejected = new Error('Fish rejected stale key must-not-reach-output');
	rejected.code = 'TTS_PROVIDER_ERROR';
	let provider;
	let localCalls = 0;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 8_766 },
	}, { FISH_AUDIO_API_KEY: 'stale-fish-credential' }, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { throw rejected; } }),
		createWindowsTtsProvider: () => ({ async synthesize() {
			localCalls++;
			return { sampleRateHz: 16_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(2) };
		} }),
		createVoiceServer: (options) => {
			provider = options.provider;
			return { async start() {}, async close() {} };
		},
	});

	const result = await provider.synthesize({ text: 'Hello.', voiceId: 'ignored', speed: 1 });
	assert.equal(result.sampleRateHz, 16_000);
	assert.equal(result.cacheable, false, 'fallback audio cannot populate the Fish profile cache');
	assert.equal(localCalls, 1);
	assert.doesNotMatch(JSON.stringify(result), /stale-fish-credential|must-not-reach-output/);
	await worker.close();
});

test('voice bootstrap closes a worker when binding fails', async () => {
	let closes = 0;
	await assert.rejects(
		startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 0 } }, { FISH_AUDIO_API_KEY: 'fish-from-environment' }, {
			loadProfileStore: async () => ({ store: { resolve() {} } }),
			createVoiceServer: () => ({
				async start() { throw new Error('bind failed'); },
				async close() { closes++; },
			}),
		}),
		/bind failed/,
	);
	assert.equal(closes, 1);
});

test('voice bootstrap closes a prepared local provider when later setup fails', async () => {
	let closes = 0;
	const local = {
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() { closes += 1; },
	};
	await assert.rejects(
		startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {}, {
			platform: 'win32',
			createLocalSpeechProvider: async () => local,
			loadProfileStore: async () => { throw new Error('profile setup failed'); },
		}),
		/profile setup failed/,
	);
	assert.equal(closes, 1, 'partially prepared provider is not leaked between retries');
});

test('voice bootstrap prefers one local speech runtime for both expressive TTS and STT', async () => {
	const captured = {};
	let localCloses = 0;
	let warmups = 0;
	let serverCloses = 0;
	const local = {
		async warmup() { warmups++; },
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() { localCloses++; },
	};
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 8_766 },
	}, {}, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createLocalSpeechProvider: async () => local,
		createWindowsTtsProvider: () => { throw new Error('Windows fallback must not replace an available local provider'); },
		createVoiceServer: (options) => {
			captured.options = options;
			return { async start() {}, async close() { serverCloses++; } };
		},
	});

	assert.notEqual(captured.options.provider, local, 'local speech is wrapped so a failed warmup can fail over');
	assert.notEqual(captured.options.sttProvider, local, 'local STT is wrapped so a failed warmup can fail over');
	assert.equal(warmups, 0, 'worker bind does not await optional model warmup');
	await created.warmup();
	assert.equal(warmups, 1);
	await created.close();
	assert.equal(serverCloses, 1);
	assert.equal(localCloses, 1);
});

test('voice bootstrap exposes slow local warmup without delaying the bound worker', async () => {
	let releaseWarmup;
	const warmupGate = new Promise((resolve) => { releaseWarmup = resolve; });
	const local = {
		async warmup() { await warmupGate; },
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {}, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createLocalSpeechProvider: async () => local,
		createVoiceServer: () => ({ async start() {}, async close() {} }),
	});
	let settled = false;
	const warming = worker.warmup().then(() => { settled = true; });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(settled, false);
	releaseWarmup();
	await warming;
	assert.equal(settled, true);
	await worker.close();
});

test('local model warmup failure switches both channels to configured remote providers', async () => {
	let active;
	let localCloses = 0;
	const local = {
		async warmup() { throw Object.assign(new Error('Chatterbox import failed'), { code: 'LOCAL_SPEECH_WARMUP_FAILED' }); },
		async synthesize() { throw new Error('local TTS must be replaced'); },
		async transcribe() { throw new Error('local STT must be replaced'); },
		async close() { localCloses += 1; },
	};
	const fish = { async synthesize() { return {}; } };
	const deepgram = { async transcribe() { return { transcript: 'fallback', confidence: 1 }; } };
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() { return { profileId: 'voice.test', provider: 'fish', model: 'test', voiceId: 'fish-id', revision: 1, speed: 1 }; } } }),
		createTtsProvider: () => fish,
		createSttProvider: () => deepgram,
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal(localCloses, 1, 'the unused local process is released as soon as routing changes');
		assert.notEqual(active.provider, local);
		assert.notEqual(active.sttProvider, local);
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'fallback');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('local cleanup failure cannot roll back a successful external fallback switch', async () => {
	let active;
	const local = {
		async warmup() { throw Object.assign(new Error('local models failed'), { code: 'LOCAL_SPEECH_WARMUP_FAILED' }); },
		async synthesize() { throw new Error('closed local TTS must not receive traffic'); },
		async transcribe() { throw new Error('closed local STT must not receive traffic'); },
		async close() { throw new Error('local process already exited'); },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { return { provider: 'fish' }; } }),
		createSttProvider: () => ({ async transcribe() { return { transcript: 'remote', confidence: 1 }; } }),
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal((await active.provider.synthesize({ text: 'hi' })).provider, 'fish');
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'remote');
	} finally {
		await worker.close();
	}
});

test('voice bootstrap propagates startup cancellation into provider discovery', async () => {
	const controller = new AbortController();
	let observedSignal = null;
	const starting = startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {}, {
		platform: 'win32',
		signal: controller.signal,
		createLocalSpeechProvider: async ({ signal }) => {
			observedSignal = signal;
			if (signal === undefined) throw new Error('startup signal was not propagated');
			return new Promise((resolve, reject) => signal.addEventListener('abort', () => {
				const error = new Error('provider discovery aborted');
				error.name = 'AbortError';
				reject(error);
			}, { once: true }));
		},
	});
	controller.abort();
	await assert.rejects(starting, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
});

test('voice bootstrap propagates startup cancellation into HTTP binding', async () => {
	const controller = new AbortController();
	let observedSignal = null;
	let markStartEntered;
	const startEntered = new Promise((resolve) => { markStartEntered = resolve; });
	const starting = startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {
		FISH_API_KEY: 'test-key',
	}, {
		signal: controller.signal,
		platform: 'linux',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { return {}; } }),
		createVoiceServer: () => ({
			start: ({ signal }) => {
				observedSignal = signal;
				markStartEntered();
				return new Promise((resolve, reject) => signal.addEventListener('abort', () => {
					const error = new Error('bind aborted');
					error.name = 'AbortError';
					reject(error);
				}, { once: true }));
			},
			async close() {},
		}),
	});
	await startEntered;
	controller.abort();
	await assert.rejects(starting, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
});

test('voice bootstrap keeps supervisor ownership over the production profile read seam', async () => {
	const controller = new AbortController();
	const unrelated = new AbortController();
	let observedSignal = null;
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {
		FISH_API_KEY: 'test-key',
	}, {
		signal: controller.signal,
		platform: 'linux',
		localSpeechAccess: async () => { throw Object.assign(new Error('not installed'), { code: 'ENOENT' }); },
		voiceProfileIo: {
			signal: unrelated.signal,
			readFile: async (filePath, options) => {
				observedSignal = options.signal;
				throw Object.assign(new Error('new store'), { code: 'ENOENT' });
			},
		},
		createTtsProvider: () => ({ async synthesize() { return {}; } }),
		createVoiceServer: () => ({ async start() {}, async close() {} }),
	});
	try {
		assert.equal(observedSignal, controller.signal);
	} finally {
		await worker.close();
	}
});
