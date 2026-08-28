import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('local speech RPC responses use the original stdout outside global redirects', async () => {
	const source = await readFile(fileURLToPath(new URL('../src/voice/local-speech-worker.py', import.meta.url)), 'utf8');
	assert.match(source, /_RPC_STDOUT\s*=\s*sys\.stdout/);
	assert.match(source, /def _respond\(value\):[\s\S]*?_RPC_STDOUT\.write\(/);
	assert.doesNotMatch(source, /def _respond\(value\):\s+sys\.stdout\.write\(/);
});

test('local speech provider keeps one bounded process for TTS and STT', async () => {
	let providerModule = null;
	try { providerModule = await import('../src/voice/local-speech-provider.mjs'); }
	catch { /* the first red run proves the local provider does not exist yet */ }
	assert.equal(typeof providerModule?.LocalSpeechProvider, 'function');

	const provider = new providerModule.LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 2_000,
	});
	try {
		const synthesized = await provider.synthesize({ text: 'Hello there.', voiceId: 'ignored', speed: 1 });
		assert.deepEqual(synthesized, {
			sampleRateHz: 24_000,
			channels: 1,
			sampleFormat: 's16le',
			pcm: Buffer.from([1, 0, 2, 0]),
			provider: 'local-chatterbox',
			model: 'chatterbox-v1',
			voiceId: 'local.chatterbox.v1.e4530565',
		});
		const transcript = await provider.transcribe({ pcm: Buffer.alloc(1_920, 1) });
		assert.deepEqual(transcript, { transcript: 'I can hear you.', confidence: 0.87 });
	} finally {
		await provider.close();
	}
});

test('local speech provider starts model warmup before the first utterance', async () => {
	const { LocalSpeechProvider } = await import('../src/voice/local-speech-provider.mjs');
	const provider = new LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 2_000,
	});
	try {
		await provider.warmup();
		const synthesized = await provider.synthesize({ text: 'Warm response.', voiceId: 'ignored', speed: 1 });
		assert.equal(synthesized.pcm.length, 4);
	} finally {
		await provider.close();
	}
});

test('local speech provider rejects malformed worker audio instead of forwarding it to voice chat', async () => {
	let providerModule = null;
	try { providerModule = await import('../src/voice/local-speech-provider.mjs'); }
	catch { /* covered by the API assertion below */ }
	assert.equal(typeof providerModule?.LocalSpeechProvider, 'function');
	const provider = new providerModule.LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 2_000,
	});
	try {
		await assert.rejects(
			provider.synthesize({ text: 'malformed', voiceId: 'ignored', speed: 1 }),
			(error) => error?.code === 'TTS_MALFORMED_AUDIO',
		);
	} finally {
		await provider.close();
	}
});

test('aborting inference restarts the serial worker so replacement speech is not delayed', async () => {
	const { LocalSpeechProvider } = await import('../src/voice/local-speech-provider.mjs');
	const provider = new LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 1_000,
	});
	const controller = new AbortController();
	try {
		const blocked = provider.synthesize({ text: 'block-worker', voiceId: 'ignored', speed: 1, signal: controller.signal });
		await new Promise((resolve) => setTimeout(resolve, 50));
		controller.abort();
		await assert.rejects(blocked, (error) => error?.name === 'AbortError');
		const replacement = await provider.synthesize({ text: 'replacement', voiceId: 'ignored', speed: 1 });
		assert.equal(replacement.pcm.length, 4);
	} finally {
		const firstClose = provider.close();
		assert.strictEqual(provider.close(), firstClose);
		await firstClose;
	}
});

test('local speech subprocess receives only the local runtime environment', async () => {
	const { createLocalSpeechEnvironment, localVoiceId } = await import('../src/voice/local-speech-provider.mjs');
	const environment = createLocalSpeechEnvironment({
		PATH: 'runtime-path',
		ARENA_LOCAL_STT_MODEL: 'small.en',
		FISH_AUDIO_API_KEY: 'must-not-cross-boundary',
		DEEPGRAM_API_KEY: 'must-not-cross-boundary',
		ARENA_AGENT_BRIDGE_SECRET: 'must-not-cross-boundary',
		OPENAI_API_KEY: 'must-not-cross-boundary',
	});
	assert.equal(environment.PATH, 'runtime-path');
	assert.equal(environment.ARENA_LOCAL_STT_MODEL, 'small.en');
	assert.equal(environment.FISH_AUDIO_API_KEY, undefined);
	assert.equal(environment.DEEPGRAM_API_KEY, undefined);
	assert.equal(environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(environment.PYTHONUNBUFFERED, '1');
	assert.match(localVoiceId('fish-profile-a'), /^local\.chatterbox\.v1\.[0-9a-f]{8}$/);
	assert.equal(localVoiceId('local.chatterbox.v1.deadbeef'), 'local.chatterbox.v1.deadbeef');
});

test('aborting one inference preserves unrelated pending speech work', async () => {
	const { LocalSpeechProvider } = await import('../src/voice/local-speech-provider.mjs');
	const provider = new LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 1_000,
	});
	const controller = new AbortController();
	try {
		const blocked = provider.synthesize({ text: 'block-worker', signal: controller.signal });
		const pending = provider.transcribe({ pcm: Buffer.alloc(1_920, 1) });
		await new Promise((resolve) => setTimeout(resolve, 50));
		controller.abort();
		await assert.rejects(blocked, (error) => error?.name === 'AbortError');
		assert.deepEqual(await pending, { transcript: 'I can hear you.', confidence: 0.87 });
	} finally {
		await provider.close();
	}
});

test('aborting replayed inference stops its current worker before replacement work', async () => {
	const { LocalSpeechProvider } = await import('../src/voice/local-speech-provider.mjs');
	const provider = new LocalSpeechProvider({
		executable: process.execPath,
		scriptPath: fileURLToPath(new URL('../test-support/local-speech-rpc-fixture.mjs', import.meta.url)),
		timeoutMs: 5_000,
	});
	const firstController = new AbortController();
	const replayedController = new AbortController();
	try {
		const first = provider.synthesize({ text: 'block-worker', signal: firstController.signal });
		const replayed = provider.synthesize({ text: 'block-worker', signal: replayedController.signal });
		await new Promise((resolve) => setTimeout(resolve, 50));
		firstController.abort();
		await assert.rejects(first, (error) => error?.name === 'AbortError');
		await new Promise((resolve) => setTimeout(resolve, 50));
		replayedController.abort();
		await assert.rejects(replayed, (error) => error?.name === 'AbortError');

		const replacement = provider.synthesize({ text: 'replacement' });
		const completed = await Promise.race([
			replacement.then(() => true),
			new Promise((resolve) => setTimeout(() => resolve(false), 1_000)),
		]);
		assert.equal(completed, true, 'replayed cancellation relinquishes the current serial worker');
		assert.equal((await replacement).pcm.length, 4);
	} finally {
		await provider.close();
	}
});

test('default provider discovery aborts promptly through its production access seam', async () => {
	const { LocalSpeechProvider } = await import('../src/voice/local-speech-provider.mjs');
	const controller = new AbortController();
	let entered;
	const accessEntered = new Promise((resolve) => { entered = resolve; });
	let observedSignal = null;
	const discovering = LocalSpeechProvider.createIfAvailable({
		executable: 'stalled-python',
		scriptPath: 'stalled-worker.py',
		signal: controller.signal,
		accessFile: (filePath, signal) => {
			observedSignal = signal;
			entered();
			return new Promise(() => {});
		},
	});
	const firstBoundary = await Promise.race([
		accessEntered.then(() => 'entered'),
		discovering.then(() => 'settled', () => 'settled'),
	]);
	assert.equal(firstBoundary, 'entered', 'production provider discovery uses the injected access seam');
	controller.abort();
	await assert.rejects(discovering, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
});
