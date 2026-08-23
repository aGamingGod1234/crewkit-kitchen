const DEFAULT_ENDPOINT = 'https://api.fish.audio/v1/tts';
const DEFAULT_MODEL = 's2.1-pro-free';
const MAX_PCM_BYTES = 44_100 * 2 * 20;

export class FishTtsProvider {
	#apiKey;
	#fetch;
	#endpoint;
	#timeoutMs;

	constructor({ apiKey, fetchImpl = globalThis.fetch, endpoint = DEFAULT_ENDPOINT, timeoutMs = 30_000 } = {}) {
		if (typeof apiKey !== 'string' || apiKey.isBlank?.() || apiKey.trim() === '') {
			throw new TypeError('Fish API key must not be blank');
		}
		if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl must be a function');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be positive');
		this.#apiKey = apiKey;
		this.#fetch = fetchImpl;
		this.#endpoint = endpoint;
		this.#timeoutMs = timeoutMs;
	}

	async synthesize({ text, voiceId, speed = 1, signal } = {}) {
		requireText(text, 'text');
		requireText(voiceId, 'voiceId');
		if ([...text].length > 280) throw new TypeError('text must be at most 280 Unicode code points');
		if (!Number.isFinite(speed) || speed < 0.5 || speed > 2) throw new TypeError('speed must be between 0.5 and 2');
		const timeoutSignal = AbortSignal.timeout(this.#timeoutMs);
		const combinedSignal = signal === undefined ? timeoutSignal : AbortSignal.any([signal, timeoutSignal]);
		const response = await this.#fetch(this.#endpoint, {
			method: 'POST',
			headers: {
				Authorization: `Bearer ${this.#apiKey}`,
				'Content-Type': 'application/json',
				model: DEFAULT_MODEL,
			},
			body: JSON.stringify({
				text,
				reference_id: voiceId,
				format: 'pcm',
				sample_rate: 44_100,
				latency: 'balanced',
				prosody: { speed, volume: 0, normalize_loudness: true },
				normalize: true,
			}),
			signal: combinedSignal,
		});
		if (!response.ok) {
			const retryAfter = response.headers?.get?.('retry-after');
			const error = new Error(`Fish TTS failed with HTTP ${response.status}`);
			error.code = response.status === 429 ? 'TTS_RATE_LIMITED' : 'TTS_PROVIDER_ERROR';
			error.retryAfter = retryAfter;
			throw error;
		}
		const declaredLength = Number(response.headers?.get?.('content-length'));
		if (Number.isFinite(declaredLength) && declaredLength > MAX_PCM_BYTES) {
			throw typedError('TTS_AUDIO_TOO_LONG', 'Fish TTS response exceeds the 20 second PCM limit');
		}
		const pcm = Buffer.from(await response.arrayBuffer());
		if (pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_PCM_BYTES) {
			throw typedError('TTS_MALFORMED_AUDIO', 'Fish TTS returned invalid mono signed 16-bit PCM');
		}
		return Object.freeze({ sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm });
	}
}

function requireText(value, name) {
	if (typeof value !== 'string' || value.trim() === '') throw new TypeError(`${name} must not be blank`);
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
