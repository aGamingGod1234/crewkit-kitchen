import { createServer } from 'node:http';

import { resampleS16leMono } from './pcm-audio.mjs';
import { TtsCache } from './tts-cache.mjs';

const MAX_REQUEST_BYTES = 8 * 1024;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

export function createVoiceHttpServer({
	provider,
	sttProvider = null,
	profileStore,
	secret,
	cache = new TtsCache(),
	host = '127.0.0.1',
	port = 8_766,
	maxConcurrent = 5,
	requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
} = {}) {
	if (provider === null || typeof provider?.synthesize !== 'function') throw new TypeError('provider.synthesize is required');
	if (profileStore === null || typeof profileStore?.resolve !== 'function') throw new TypeError('profileStore.resolve is required');
	if (typeof secret !== 'string' || secret.length < 16) throw new TypeError('voice secret must contain at least 16 characters');
	if (host !== '127.0.0.1' && host !== '::1') throw new TypeError('voice server must bind to loopback');
	if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) throw new TypeError('port is invalid');
	if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent < 1 || maxConcurrent > 5) throw new TypeError('maxConcurrent must be between 1 and 5');
	if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs < 1 || requestTimeoutMs > 120_000) throw new TypeError('requestTimeoutMs must be between 1 and 120000');

	let active = 0;
	const controllers = new Set();
	let startPromise = null;
	let closePromise = null;
	let lifecycleState = 'unknown';
	let lifecycleBoundary = 'voice_start';
	let lifecycleCode = 'VOICE_NOT_STARTED';
	let lifecycleFailures = 0;
	let lifecycleGeneration = 0;
	let lastRecoveryAtEpochMs = null;
	const recordFailure = (error, boundary) => {
		lifecycleState = 'degraded';
		lifecycleBoundary = boundary;
		lifecycleCode = voiceFailureCode(error);
		lifecycleFailures = Math.min(1_000_000, lifecycleFailures + 1);
		lifecycleGeneration += 1;
	};
	const recordReady = () => {
		if (lifecycleState === 'ready') return;
		if (lifecycleState === 'degraded') lastRecoveryAtEpochMs = Date.now();
		lifecycleState = 'ready';
		lifecycleBoundary = null;
		lifecycleCode = null;
		lifecycleFailures = 0;
		lifecycleGeneration += 1;
	};
	const server = createServer(async (request, response) => {
		if (request.method === 'GET' && request.url === '/health') {
			respondJson(response, 200, { ready: true, active, maxConcurrent });
			return;
		}
		if (request.method !== 'POST' || !['/v1/tts', '/v1/stt'].includes(request.url)) {
			respondJson(response, 404, { code: 'NOT_FOUND' });
			return;
		}
		if (request.headers.authorization !== `Bearer ${secret}`) {
			respondJson(response, 401, { code: 'UNAUTHORIZED' });
			return;
		}
		if (active >= maxConcurrent) {
			respondJson(response, 429, { code: 'TTS_CAPACITY' });
			return;
		}
		const controller = new AbortController();
		controllers.add(controller);
		let released = false;
		active += 1;
		const release = () => {
			if (released) return;
			released = true;
			active -= 1;
		};
		const onRequestAborted = () => controller.abort();
		const onResponseClosed = () => {
			if (!response.writableFinished) controller.abort();
		};
		const onOperationAborted = () => release();
		request.once('aborted', onRequestAborted);
		response.once('close', onResponseClosed);
		controller.signal.addEventListener('abort', onOperationAborted, { once: true });
		const timeout = setTimeout(() => {
			const error = typedError(request.url === '/v1/stt' ? 'STT_TIMEOUT' : 'TTS_TIMEOUT', 'Voice provider request timed out');
			error.name = 'TimeoutError';
			controller.abort(error);
		}, requestTimeoutMs);
		let providerAttempted = false;
		try {
			if (request.url === '/v1/stt') {
				requireContentType(request.headers['content-type'], 'audio/l16;rate=48000;channels=1');
				if (sttProvider === null || typeof sttProvider.transcribe !== 'function') {
					throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
				}
				const metadata = validateSttHeaders(request.headers);
				const pcm = await awaitAbortable(readBytes(request, 48_000 * 2 * 20), controller.signal);
				providerAttempted = true;
				const result = validateTranscriptResult(await awaitAbortable(
					Promise.resolve().then(() => sttProvider.transcribe({ pcm, signal: controller.signal })),
					controller.signal,
				));
				recordReady();
				respondJson(response, 200, {
					playerId: metadata.playerId,
					utteranceSequence: metadata.utteranceSequence,
					whispering: metadata.whispering,
					transcript: result.transcript,
					confidence: result.confidence,
				});
				return;
			}
			requireJsonContentType(request.headers['content-type']);
			const payload = validateRequest(await awaitAbortable(readJson(request), controller.signal));
			const profile = profileStore.resolve(payload.agentId);
			const cacheKey = TtsCache.key({
				provider: profile.provider,
				model: profile.model,
				voiceId: profile.voiceId,
				profileRevision: profile.revision,
				text: payload.text.normalize('NFC').trim(),
				speed: profile.speed,
				format: 's16le',
				sampleRate: 48_000,
			});
			let output = cache.get(cacheKey);
			if (output === null) {
				providerAttempted = true;
				const synthesized = validateSynthesis(await awaitAbortable(
					Promise.resolve().then(() => provider.synthesize({
						text: payload.text,
						voiceId: profile.voiceId,
						speed: profile.speed,
						signal: controller.signal,
					})),
					controller.signal,
				));
				output = resampleS16leMono(synthesized.pcm, synthesized.sampleRateHz, 48_000, 20);
				if (output.length === 0) throw typedError('TTS_MALFORMED_AUDIO', 'TTS output was empty');
				recordReady();
				cache.set(cacheKey, output);
			}
			response.writeHead(200, {
				'Content-Type': 'audio/L16',
				'Content-Length': output.length,
				'X-Audio-Sample-Rate': '48000',
				'X-Audio-Channels': '1',
				'X-Voice-Profile': profile.profileId,
				'Cache-Control': 'private, immutable',
			});
			response.end(output);
		} catch (error) {
			if (providerAttempted && error?.name !== 'AbortError') recordFailure(error, 'voice_provider');
			if (!response.headersSent) respondJson(response, statusFor(error), {
				code: String(error?.code ?? 'TTS_ERROR').slice(0, 64),
				message: String(error?.message ?? error).slice(0, 256),
			});
		} finally {
			clearTimeout(timeout);
			request.off('aborted', onRequestAborted);
			response.off('close', onResponseClosed);
			controller.signal.removeEventListener('abort', onOperationAborted);
			release();
			controllers.delete(controller);
		}
	});

	return Object.freeze({
		server,
		statusSnapshot() {
			return Object.freeze({
				component: 'voice', state: lifecycleState, fallbackMode: lifecycleState === 'ready' ? null : 'text',
				boundary: lifecycleBoundary, failureCode: lifecycleCode, consecutiveFailureCount: lifecycleFailures,
				nextProbeAtEpochMs: null, generation: lifecycleGeneration, lastRecoveryAtEpochMs,
			});
		},
		async start() {
			if (closePromise !== null) throw typedError('VOICE_WORKER_CLOSED', 'Voice worker has been closed');
			if (server.listening) return server.address();
			if (startPromise !== null) return startPromise;
			startPromise = new Promise((resolve, reject) => {
				const onError = (error) => {
					server.off('listening', onListening);
					reject(error);
				};
				const onListening = () => {
					server.off('error', onError);
					resolve(server.address());
				};
				server.once('error', onError);
				server.once('listening', onListening);
				server.listen(port, host);
			});
			try {
				const address = await startPromise;
				recordReady();
				return address;
			} catch (error) {
				recordFailure(error, 'voice_start');
				throw error;
			} finally {
				startPromise = null;
			}
		},
		async close() {
			if (closePromise !== null) return closePromise;
			closePromise = (async () => {
				if (startPromise !== null) {
					try { await startPromise; }
					catch { /* close still flushes assignments after a failed bind */ }
				}
				for (const controller of controllers) controller.abort();
				if (server.listening) {
					await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
				}
				if (typeof profileStore.flush === 'function') await profileStore.flush();
			})();
			return closePromise;
		},
	});
}

function voiceFailureCode(error) {
	const value = error?.code;
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'VOICE_UNAVAILABLE';
}

async function readJson(request) {
	const chunks = [];
	let bytes = 0;
	for await (const chunk of request) {
		bytes += chunk.length;
		if (bytes > MAX_REQUEST_BYTES) throw typedError('REQUEST_TOO_LARGE', 'Voice request exceeds 8 KiB');
		chunks.push(chunk);
	}
	try {
		return JSON.parse(Buffer.concat(chunks).toString('utf8'));
	} catch {
		throw typedError('INVALID_JSON', 'Voice request must be valid JSON');
	}
}

async function readBytes(request, maximum) {
	const chunks = [];
	let bytes = 0;
	for await (const chunk of request) {
		bytes += chunk.length;
		if (bytes > maximum) throw typedError('REQUEST_TOO_LARGE', 'Audio request exceeds the 20 second limit');
		chunks.push(chunk);
	}
	const result = Buffer.concat(chunks);
	if (result.length === 0 || result.length % 2 !== 0) throw typedError('STT_MALFORMED_AUDIO', 'STT audio is invalid');
	return result;
}

function validateSttHeaders(headers) {
	const playerId = headers['x-player-id'];
	const sequence = Number(headers['x-utterance-sequence']);
	const whispering = headers['x-whispering'];
	if (typeof playerId !== 'string' || !/^[0-9a-f-]{36}$/i.test(playerId)) throw typedError('INVALID_REQUEST', 'X-Player-Id must be a UUID');
	if (!Number.isSafeInteger(sequence) || sequence < 1) throw typedError('INVALID_REQUEST', 'X-Utterance-Sequence is invalid');
	if (!['true', 'false'].includes(whispering)) throw typedError('INVALID_REQUEST', 'X-Whispering is invalid');
	return { playerId, utteranceSequence: sequence, whispering: whispering === 'true' };
}

function requireJsonContentType(value) {
	if (typeof value !== 'string' || value.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
		throw typedError('INVALID_REQUEST', 'Content-Type must be application/json');
	}
}

function requireContentType(value, expected) {
	if (typeof value !== 'string' || value.replaceAll(' ', '').toLowerCase() !== expected) {
		throw typedError('INVALID_REQUEST', `Content-Type must be ${expected}`);
	}
}

function validateSynthesis(value) {
	if (value === null || typeof value !== 'object' || !Buffer.isBuffer(value.pcm)
			|| !Number.isSafeInteger(value.sampleRateHz) || value.sampleRateHz < 8_000 || value.sampleRateHz > 192_000
			|| value.channels !== 1 || value.sampleFormat !== 's16le'
			|| value.pcm.length === 0 || value.pcm.length % 2 !== 0
			|| value.pcm.length > value.sampleRateHz * 2 * 20) {
		throw typedError('TTS_MALFORMED_AUDIO', 'TTS provider returned invalid mono signed 16-bit PCM');
	}
	return value;
}

function validateTranscriptResult(value) {
	if (value === null || typeof value !== 'object' || typeof value.transcript !== 'string'
			|| !Number.isFinite(value.confidence)) {
		throw typedError('STT_PROVIDER_RESPONSE', 'STT provider returned an invalid transcript');
	}
	return Object.freeze({
		transcript: [...value.transcript.trim()].slice(0, 512).join(''),
		confidence: Math.max(0, Math.min(1, value.confidence)),
	});
}

function validateRequest(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw typedError('INVALID_REQUEST', 'Voice request must be an object');
	const keys = Object.keys(value).sort();
	if (keys.join(',') !== 'agentId,conversationSequence,profileId,radius,text') throw typedError('INVALID_REQUEST', 'Voice request fields are invalid');
	if (!/^[0-9a-f-]{36}$/i.test(value.agentId)) throw typedError('INVALID_REQUEST', 'agentId must be a UUID');
	if (typeof value.text !== 'string' || value.text.trim() === '' || [...value.text].length > 280) throw typedError('INVALID_REQUEST', 'text must contain 1 to 280 code points');
	if (value.profileId !== 'voice.auto.v1') throw typedError('INVALID_REQUEST', 'profileId is not supported');
	if (!Number.isSafeInteger(value.radius) || value.radius < 1 || value.radius > 128) throw typedError('INVALID_REQUEST', 'radius is invalid');
	if (!Number.isSafeInteger(value.conversationSequence) || value.conversationSequence < 0) throw typedError('INVALID_REQUEST', 'conversationSequence is invalid');
	return value;
}

function statusFor(error) {
	if (error?.name === 'AbortError' || error?.name === 'TimeoutError') return 504;
	if (error?.code === 'TTS_RATE_LIMITED' || error?.code === 'TTS_CAPACITY') return 429;
	if (error?.code === 'STT_RATE_LIMITED') return 429;
	if (error?.code === 'STT_UNAVAILABLE') return 503;
	if (['INVALID_REQUEST', 'INVALID_JSON', 'REQUEST_TOO_LARGE'].includes(error?.code)) return 400;
	return 502;
}

function respondJson(response, status, value) {
	const body = Buffer.from(JSON.stringify(value));
	response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': body.length });
	response.end(body);
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}

function awaitAbortable(value, signal) {
	if (signal.aborted) return Promise.reject(abortReason(signal));
	return new Promise((resolve, reject) => {
		let settled = false;
		const finish = (operation, result) => {
			if (settled) return;
			settled = true;
			signal.removeEventListener('abort', onAbort);
			operation(result);
		};
		const onAbort = () => finish(reject, abortReason(signal));
		signal.addEventListener('abort', onAbort, { once: true });
		Promise.resolve(value).then(
			(result) => signal.aborted ? onAbort() : finish(resolve, result),
			(error) => finish(reject, error),
		);
	});
}

function abortReason(signal) {
	if (signal.reason instanceof Error) return signal.reason;
	const error = new Error('Voice provider request was cancelled');
	error.name = 'AbortError';
	return error;
}
