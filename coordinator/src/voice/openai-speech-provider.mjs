import { createHash } from 'node:crypto';
import { readBoundedResponseBody } from './bounded-response-body.mjs';

export const DEFAULT_OPENAI_TTS_MODEL = 'gpt-4o-mini-tts';
export const DEFAULT_OPENAI_STT_MODEL = 'gpt-transcribe';
const VOICES = Object.freeze(['cedar', 'marin', 'alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'nova', 'onyx', 'sage', 'shimmer', 'verse']);
const TONES = Object.freeze({ neutral: 'Speak clearly and naturally.', warm: 'Speak warmly.', excited: 'Sound excited.',
	serious: 'Use a serious tone.', dramatic: 'Use a dramatic tone.', whisper: 'Speak in a whisper.', robotic: 'Use a robotic delivery.', angry: 'Sound angry.' });

/** Stable existing profile IDs map to built-in voices; speech never generates or rewrites the agent's words. */
export function openAiVoiceForProfile(voiceId) {
	if (typeof voiceId !== 'string' || voiceId.trim() === '') throw new TypeError('voiceId must not be blank');
	if (VOICES.includes(voiceId)) return voiceId;
	return VOICES[createHash('sha256').update(voiceId).digest().readUInt32BE(0) % VOICES.length];
}

export class OpenAiTtsProvider {
	#client;
	#model;
	constructor({ model = DEFAULT_OPENAI_TTS_MODEL, ...options } = {}) {
		this.#client = new SpeechClient(options);
		this.#model = requireModel(model);
	}
	cacheNamespace() { return `openai/${this.#model}/profiles-v1`; }

	async synthesize({ text, voiceId, speed = 1, tone = 'neutral', signal } = {}) {
		if (typeof text !== 'string' || text.trim() === '' || [...text].length > 280) throw new TypeError('text must contain 1 to 280 Unicode code points');
		if (!Number.isFinite(speed) || speed < 0.5 || speed > 2) throw new TypeError('speed must be between 0.5 and 2');
		if (!Object.hasOwn(TONES, tone)) throw new TypeError('Unsupported delivery tone');
		const voice = openAiVoiceForProfile(voiceId);
		const pcm = await this.#client.request('speech', {
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ model: this.#model, input: text, voice, speed, instructions: TONES[tone], response_format: 'pcm' }),
		}, { signal, maxBytes: 24_000 * 2 * 20, prefix: 'TTS' });
		if (pcm.length === 0 || pcm.length % 2 !== 0) throw failure('TTS_MALFORMED_AUDIO', 'OpenAI returned invalid mono PCM');
		return Object.freeze({ sampleRateHz: 24_000, channels: 1, sampleFormat: 's16le', pcm });
	}
}

export class OpenAiSttProvider {
	#client;
	#model;
	constructor({ model = DEFAULT_OPENAI_STT_MODEL, ...options } = {}) {
		this.#client = new SpeechClient(options);
		this.#model = requireModel(model);
	}
	async transcribe({ pcm, signal } = {}) {
		if (!Buffer.isBuffer(pcm) || pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > 48_000 * 2 * 20) {
			throw failure('STT_MALFORMED_AUDIO', 'STT input must be at most 20 seconds of 48 kHz mono PCM');
		}
		const form = new FormData();
		form.set('model', this.#model);
		form.set('response_format', 'json');
		form.set('file', new Blob([wavFile(pcm)], { type: 'audio/wav' }), 'speech.wav');
		const body = await this.#client.request('transcriptions', { body: form }, { signal, maxBytes: 256 * 1024, prefix: 'STT' });
		let result;
		try { result = JSON.parse(body.toString('utf8')); }
		catch { throw failure('STT_PROVIDER_RESPONSE', 'OpenAI returned malformed transcription JSON'); }
		if (typeof result?.text !== 'string') throw failure('STT_PROVIDER_RESPONSE', 'OpenAI returned no transcript');
		// The API does not return calibrated confidence. Zero represents unavailable, not an invented certainty.
		return Object.freeze({ transcript: [...result.text.trim()].slice(0, 512).join(''), confidence: 0 });
	}
}

class SpeechClient {
	#key;
	#fetch;
	#base;
	#timeoutMs;
	constructor({ apiKey, fetchImpl = globalThis.fetch, baseUrl = 'https://api.openai.com/v1/audio', timeoutMs = 30_000 } = {}) {
		if (typeof apiKey !== 'string' || apiKey.trim() === '') throw new TypeError('OpenAI API key must not be blank');
		if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl must be a function');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be positive');
		this.#key = apiKey;
		this.#fetch = fetchImpl;
		this.#base = baseUrl.replace(/\/$/, '');
		this.#timeoutMs = timeoutMs;
	}
	async request(endpoint, options, { signal, maxBytes, prefix }) {
		const controller = new AbortController();
		const combined = AbortSignal.any([controller.signal, AbortSignal.timeout(this.#timeoutMs), ...(signal === undefined ? [] : [signal])]);
		let response;
		try {
			response = await this.#fetch(`${this.#base}/${endpoint}`, {
				...options, method: 'POST', headers: { ...options.headers, Authorization: `Bearer ${this.#key}` }, signal: combined,
			});
		} catch (error) {
			if (combined.aborted) throw combined.reason;
			throw failure(`${prefix}_PROVIDER_ERROR`, 'OpenAI speech transport failed');
		}
		if (!response.ok) {
			await response.body?.cancel?.();
			const code = response.status === 401 || response.status === 403 ? `${prefix}_AUTHENTICATION_FAILED`
				: response.status === 429 ? `${prefix}_RATE_LIMITED` : `${prefix}_PROVIDER_ERROR`;
			const error = failure(code, `OpenAI speech failed with HTTP ${response.status}`);
			error.retryAfter = response.headers?.get?.('retry-after');
			throw error;
		}
		return readBoundedResponseBody(response, maxBytes,
			() => failure(`${prefix}_RESPONSE_TOO_LARGE`, 'OpenAI speech response exceeds the audio or transcript limit'),
			{ onLimit: error => controller.abort(error) });
	}
}

function wavFile(pcm) {
	const header = Buffer.alloc(44);
	header.write('RIFF', 0); header.writeUInt32LE(36 + pcm.length, 4); header.write('WAVEfmt ', 8);
	header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
	header.writeUInt32LE(48_000, 24); header.writeUInt32LE(96_000, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
	header.write('data', 36); header.writeUInt32LE(pcm.length, 40);
	return Buffer.concat([header, pcm]);
}
function requireModel(model) {
	if (typeof model !== 'string' || !/^[a-z0-9][a-z0-9.-]{0,79}$/.test(model)) throw new TypeError('Invalid speech model');
	return model;
}
function failure(code, message) { return Object.assign(new Error(message), { code }); }
