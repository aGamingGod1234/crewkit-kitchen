import assert from 'node:assert/strict';
import test from 'node:test';

import { startVoiceWorker } from '../src/dynamic-main.mjs';

const SECRET = 'voice-bootstrap-test-secret';

test('voice bootstrap is disabled without an environment Fish key', async () => {
	let profileLoads = 0;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { port: 0 },
		fishApiKey: 'config-must-not-be-used',
	}, {}, {
		loadProfileStore: async () => {
			profileLoads++;
			throw new Error('profile store must not load when voice is disabled');
		},
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
