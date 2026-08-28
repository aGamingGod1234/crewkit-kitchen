import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

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
