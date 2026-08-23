import assert from 'node:assert/strict';
import test from 'node:test';

import { WindowsTtsProvider } from '../src/voice/windows-tts-provider.mjs';

const windowsOnly = { skip: process.platform !== 'win32' };

test('Windows TTS returns bounded mono signed 16-bit PCM without treating text as PowerShell', windowsOnly, async () => {
	const provider = new WindowsTtsProvider({ timeoutMs: 10_000 });
	const result = await provider.synthesize({
		text: "Hello from Luna. 你好. '; throw 'injected'; $env:PATH",
		speed: 1,
	});

	assert.equal(result.sampleRateHz, 16_000);
	assert.equal(result.channels, 1);
	assert.equal(result.sampleFormat, 's16le');
	assert.ok(result.pcm.length > 3_200);
	assert.equal(result.pcm.length % 2, 0);
	assert.ok(result.pcm.length <= 16_000 * 2 * 20);
	assert.ok(result.pcm.some((byte) => byte !== 0));
});

test('Windows TTS stops synthesis when its caller aborts', windowsOnly, async () => {
	const provider = new WindowsTtsProvider({ timeoutMs: 10_000 });
	const controller = new AbortController();
	const pending = provider.synthesize({
		text: 'This deliberately long sentence gives the caller time to cancel speech synthesis before the process can finish reading all of it aloud.',
		speed: 0.5,
		signal: controller.signal,
	});
	controller.abort();

	await assert.rejects(pending, (error) => error?.name === 'AbortError');
});
