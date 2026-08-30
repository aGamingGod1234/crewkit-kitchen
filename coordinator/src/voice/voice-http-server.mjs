import { createServer } from 'node:http';

import { NoSttProvider } from './deepgram-stt-provider.mjs';
import { resampleS16leMono } from './pcm-audio.mjs';
import { TtsCache } from './tts-cache.mjs';
import { providerCacheNamespace, synthesisCacheNamespace } from './tts-cache-identity.mjs';
import { builtInVoiceProfiles } from './voice-profile-store.mjs';

const MAX_REQUEST_BYTES = 8 * 1024;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_INITIAL_PROBE_DELAY_MS = 1_000;
const DEFAULT_MAX_PROBE_DELAY_MS = 30_000;
const DEFAULT_PROBE_TIMEOUT_MS = 10_000;
const PROBE_PROFILE = builtInVoiceProfiles()[0];

export function createVoiceHttpServer({
	provider,
	sttProvider = null,
	profileStore,
	secret,
	cache = new TtsCache(),
	host = '127.0.0.1',
	port = 8_766,
	maxConcurrent = 5,
	reservedStt = maxConcurrent > 1 ? 1 : 0,
	requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
	now = Date.now,
	scheduleProbe = defaultSchedule,
	cancelProbe = clearTimeout,
	initialProbeDelayMs = DEFAULT_INITIAL_PROBE_DELAY_MS,
	maxProbeDelayMs = DEFAULT_MAX_PROBE_DELAY_MS,
	probeTimeoutMs = DEFAULT_PROBE_TIMEOUT_MS,
} = {}) {
	if (provider !== null && typeof provider?.synthesize !== 'function') throw new TypeError('provider.synthesize is required');
	if (profileStore === null || typeof profileStore?.resolve !== 'function') throw new TypeError('profileStore.resolve is required');
	if (typeof secret !== 'string' || secret.length < 16) throw new TypeError('voice secret must contain at least 16 characters');
	if (host !== '127.0.0.1' && host !== '::1') throw new TypeError('voice server must bind to loopback');
	if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) throw new TypeError('port is invalid');
	if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent < 1 || maxConcurrent > 5) throw new TypeError('maxConcurrent must be between 1 and 5');
	if (!Number.isSafeInteger(reservedStt) || reservedStt < 0 || reservedStt >= maxConcurrent) {
		throw new TypeError('reservedStt must be between 0 and maxConcurrent - 1');
	}
	if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs < 1 || requestTimeoutMs > 600_000) throw new TypeError('requestTimeoutMs must be between 1 and 600000');
	if (typeof now !== 'function' || typeof scheduleProbe !== 'function' || typeof cancelProbe !== 'function') {
		throw new TypeError('voice probe clock and scheduler must be functions');
	}
	for (const [name, value] of Object.entries({ initialProbeDelayMs, maxProbeDelayMs, probeTimeoutMs })) {
		if (!Number.isSafeInteger(value) || value < 1 || value > 120_000) throw new TypeError(`${name} must be between 1 and 120000`);
	}
	if (maxProbeDelayMs < initialProbeDelayMs) throw new TypeError('maxProbeDelayMs must not be less than initialProbeDelayMs');

	let active = 0;
	let activeTts = 0;
	let activeStt = 0;
	const controllers = new Set();
	const inFlightTts = new Map();
	const failureListeners = new Set();
	let startPromise = null;
	let closePromise = null;
	let live = false;
	let closing = false;
	let terminalFailure = null;
	const lifecycleOptions = {
		now, schedule: scheduleProbe, cancelSchedule: cancelProbe,
		initialRetryMs: initialProbeDelayMs, maxRetryMs: maxProbeDelayMs, probeTimeoutMs,
	};
	const ttsLifecycle = new VoiceChannelLifecycle({
		component: 'voice:tts', boundary: 'voice_tts_provider',
		probe: provider === null ? null : (signal) => probeTts(provider, signal),
		onStalled: (error) => recordTerminalFailure(error),
		initialFailureCode: provider === null ? 'TTS_UNAVAILABLE' : null,
		...lifecycleOptions,
	});
	const sttUnavailable = sttProvider === null || sttProvider instanceof NoSttProvider;
	const effectiveReservedStt = sttUnavailable ? 0 : reservedStt;
	const sttLifecycle = new VoiceChannelLifecycle({
		component: 'voice:stt', boundary: 'voice_stt_provider',
		probe: sttUnavailable ? null : (signal) => probeStt(sttProvider, signal),
		onStalled: (error) => recordTerminalFailure(error),
		initialFailureCode: sttUnavailable ? 'STT_UNAVAILABLE' : null,
		...lifecycleOptions,
	});
	const server = createServer(async (request, response) => {
		if (request.method === 'GET' && request.url === '/health') {
			const admittedReservedStt = currentReservedStt();
			respondJson(response, 200, {
				ready: true,
				active,
				activeTts,
				activeStt,
				maxConcurrent,
				reservedStt: admittedReservedStt,
			});
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
		const channel = request.url === '/v1/stt' ? 'stt' : 'tts';
		const ttsLimit = maxConcurrent - currentReservedStt();
		if (active >= maxConcurrent || (channel === 'tts' && activeTts >= ttsLimit)) {
			respondJson(response, 429, { code: channel === 'stt' ? 'STT_CAPACITY' : 'TTS_CAPACITY' });
			return;
		}
		const controller = new AbortController();
		controllers.add(controller);
		let released = false;
		let providerOperation = null;
		let releaseSharedTtsCapacity = null;
		active += 1;
		if (channel === 'stt') activeStt += 1;
		else activeTts += 1;
		const release = () => {
			if (released) return;
			released = true;
			active -= 1;
			if (channel === 'stt') activeStt -= 1;
			else activeTts -= 1;
			controllers.delete(controller);
		};
		const onRequestAborted = () => controller.abort();
		const onResponseClosed = () => {
			if (!response.writableFinished) controller.abort();
		};
		request.once('aborted', onRequestAborted);
		response.once('close', onResponseClosed);
		const timeout = setTimeout(() => {
			const error = typedError(request.url === '/v1/stt' ? 'STT_TIMEOUT' : 'TTS_TIMEOUT', 'Voice provider request timed out');
			error.name = 'TimeoutError';
			controller.abort(error);
		}, requestTimeoutMs);
		let attemptedLifecycle = null;
		try {
			if (request.url === '/v1/stt') {
				requireContentType(request.headers['content-type'], 'audio/l16;rate=48000;channels=1');
				if (sttProvider === null || typeof sttProvider.transcribe !== 'function') {
					throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
				}
				const metadata = validateSttHeaders(request.headers);
				const pcm = await awaitAbortable(readBytes(request, 48_000 * 2 * 20), controller.signal);
				attemptedLifecycle = sttLifecycle;
				providerOperation = Promise.resolve().then(() => sttProvider.transcribe({
					pcm,
					signal: controller.signal,
				}));
				const result = validateTranscriptResult(await awaitAbortable(providerOperation, controller.signal));
				sttLifecycle.recordReady();
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
			if (provider === null) {
				attemptedLifecycle = ttsLifecycle;
				const error = typedError('TTS_UNAVAILABLE', 'Speech synthesis is not configured');
				error.httpStatus = 503;
				throw error;
			}
			const profile = profileStore.resolve(payload.agentId);
			const profileNamespace = `${profile.provider}/${profile.model}`;
			const cacheKey = synthesisCacheKey(profile, payload, providerCacheNamespace(provider, profileNamespace));
			let output = cache.get(cacheKey);
			if (output === null) {
				attemptedLifecycle = ttsLifecycle;
				const joined = joinTtsSynthesis({
					cacheKey,
					profile,
					profileNamespace,
					payload,
					signal: controller.signal,
				});
				attemptedLifecycle = null;
				releaseSharedTtsCapacity = joined.releaseCapacity;
				output = await joined.waiter;
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
			const recordLifecycle = attemptedLifecycle !== null && error?.name !== 'AbortError';
			if (!response.headersSent) respondJson(response, statusFor(error), {
				code: String(error?.code ?? 'TTS_ERROR').slice(0, 64),
				message: String(error?.message ?? error).slice(0, 256),
			});
			if (recordLifecycle) attemptedLifecycle.recordFailure(error);
		} finally {
			clearTimeout(timeout);
			request.off('aborted', onRequestAborted);
			response.off('close', onResponseClosed);
			if (releaseSharedTtsCapacity !== null) releaseSharedTtsCapacity(release);
			else if (providerOperation === null) release();
			else providerOperation.then(release, release);
		}
	});

	function currentReservedStt() {
		return effectiveReservedStt > 0 && sttLifecycle.snapshot().state === 'ready' ? effectiveReservedStt : 0;
	}

	function joinTtsSynthesis({ cacheKey, profile, profileNamespace, payload, signal }) {
		let entry = inFlightTts.get(cacheKey);
		if (entry === undefined) {
			const providerController = new AbortController();
			entry = { providerController, waiters: 0, settled: false, operation: null };
			const current = entry;
			entry.operation = Promise.resolve().then(async () => {
				try {
					const synthesized = validateSynthesis(await provider.synthesize({
						text: payload.text,
						voiceId: profile.voiceId,
						speed: profile.speed,
						signal: providerController.signal,
					}));
					const output = resampleS16leMono(synthesized.pcm, synthesized.sampleRateHz, 48_000, 20);
					if (output.length === 0) throw typedError('TTS_MALFORMED_AUDIO', 'TTS output was empty');
					if (!providerController.signal.aborted && synthesized.cacheable !== false) {
						const completedKey = synthesisCacheKey(
							profile,
							payload,
							synthesisCacheNamespace(synthesized, provider, profileNamespace),
						);
						cache.set(completedKey, output);
					}
					if (!providerController.signal.aborted) ttsLifecycle.recordReady();
					return output;
				} catch (error) {
					if (error?.name !== 'AbortError') ttsLifecycle.recordFailure(error);
					throw error;
				} finally {
					current.settled = true;
					if (inFlightTts.get(cacheKey) === current) inFlightTts.delete(cacheKey);
				}
			});
			entry.operation.catch(() => {});
			inFlightTts.set(cacheKey, entry);
		}
		entry.waiters += 1;
		const current = entry;
		const waiter = awaitAbortable(current.operation, signal).finally(() => {
			current.waiters -= 1;
			if (current.waiters !== 0 || current.settled) return;
			if (inFlightTts.get(cacheKey) === current) inFlightTts.delete(cacheKey);
			current.providerController.abort(abortError('All synthesis waiters cancelled'));
		});
		return {
			waiter,
			releaseCapacity(release) {
				if (current.waiters > 0 || current.settled) release();
				else current.operation.then(release, release);
			},
		};
	}
	const notifyFailure = (error) => {
		for (const listener of [...failureListeners]) {
			try { listener(error); } catch { /* optional lifecycle listeners are isolated */ }
		}
	};
	const recordTerminalFailure = (error) => {
		if (terminalFailure !== null || closing) return;
		terminalFailure = error;
		live = false;
		notifyFailure(error);
	};
	server.on('error', (error) => {
		if (live) recordTerminalFailure(error);
	});
	server.on('close', () => {
		if (live) recordTerminalFailure(typedError('VOICE_SERVER_CLOSED', 'Voice HTTP server closed unexpectedly'));
		live = false;
	});
	server.on('listening', () => {
		if (closing || terminalFailure !== null) {
			try { server.close(); } catch { /* a canceled late bind must not survive cleanup */ }
		}
	});

	return Object.freeze({
		server,
		onFailure(listener) {
			if (typeof listener !== 'function') throw new TypeError('voice failure listener must be a function');
			if (terminalFailure !== null) {
				try { listener(terminalFailure); } catch { /* replay cannot escape lifecycle subscription */ }
				return () => {};
			}
			if (closing) return () => {};
			failureListeners.add(listener);
			let subscribed = true;
			return () => {
				if (!subscribed) return;
				subscribed = false;
				failureListeners.delete(listener);
			};
		},
		statusSnapshot() {
			return aggregateVoiceStatus(ttsLifecycle.snapshot(), sttLifecycle.snapshot());
		},
		statusSnapshots() {
			const tts = ttsLifecycle.snapshot();
			const stt = sttLifecycle.snapshot();
			return Object.freeze([aggregateVoiceStatus(tts, stt), tts, stt]);
		},
		async start({ signal } = {}) {
			if (closePromise !== null) throw typedError('VOICE_WORKER_CLOSED', 'Voice worker has been closed');
			if (terminalFailure !== null) throw terminalFailure;
			if (signal?.aborted) throw abortReason(signal);
			if (server.listening) return server.address();
			if (startPromise !== null) return startPromise;
			startPromise = new Promise((resolve, reject) => {
				const cleanup = () => {
					server.off('error', onError);
					server.off('listening', onListening);
					signal?.removeEventListener('abort', onAbort);
				};
				const onError = (error) => {
					cleanup();
					reject(error);
				};
				const onListening = () => {
					cleanup();
					live = true;
					resolve(server.address());
				};
				const onAbort = () => {
					cleanup();
					try { server.close(); } catch { /* bind cancellation is best effort */ }
					reject(abortReason(signal));
				};
				server.once('error', onError);
				server.once('listening', onListening);
				signal?.addEventListener('abort', onAbort, { once: true });
				server.listen(port, host);
			});
			try {
				const address = await startPromise;
				return address;
			} catch (error) {
				throw error;
			} finally {
				startPromise = null;
			}
		},
		async close() {
			if (closePromise !== null) return closePromise;
			closing = true;
			failureListeners.clear();
			closePromise = (async () => {
				ttsLifecycle.close();
				sttLifecycle.close();
				if (startPromise !== null) {
					try { await startPromise; }
					catch { /* close still flushes assignments after a failed bind */ }
				}
				for (const controller of controllers) controller.abort();
				if (server.listening) {
					await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
				}
				if (typeof profileStore.close === 'function') await profileStore.close();
				else if (typeof profileStore.flush === 'function') await profileStore.flush();
			})();
			return closePromise;
		},
	});
}

function synthesisCacheKey(profile, payload, synthesizer) {
	return TtsCache.key({
		synthesizer,
		provider: profile.provider,
		model: profile.model,
		voiceId: profile.voiceId,
		profileRevision: profile.revision,
		text: payload.text.normalize('NFC').trim(),
		speed: profile.speed,
		format: 's16le',
		sampleRate: 48_000,
	});
}

class VoiceChannelLifecycle {
	#component;
	#boundary;
	#probe;
	#onStalled;
	#now;
	#schedule;
	#cancelSchedule;
	#initialRetryMs;
	#maxRetryMs;
	#probeTimeoutMs;
	#state = 'ready';
	#failureCode = null;
	#failures = 0;
	#nextProbeAt = null;
	#generation = 1;
	#lastRecoveryAt = null;
	#epoch = 0;
	#timer = null;
	#probeToken = null;
	#retryEnabled;
	#closed = false;
	#broken = false;

	constructor({
		component, boundary, probe, onStalled, initialFailureCode = null,
		now, schedule, cancelSchedule, initialRetryMs, maxRetryMs, probeTimeoutMs,
	}) {
		if (typeof onStalled !== 'function') throw new TypeError('voice stalled probe callback must be a function');
		this.#component = component;
		this.#boundary = boundary;
		this.#probe = probe;
		this.#onStalled = onStalled;
		this.#retryEnabled = typeof probe === 'function';
		this.#now = now;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#initialRetryMs = initialRetryMs;
		this.#maxRetryMs = maxRetryMs;
		this.#probeTimeoutMs = probeTimeoutMs;
		if (initialFailureCode !== null) {
			this.#state = 'degraded';
			this.#failureCode = initialFailureCode;
			this.#failures = 1;
		}
	}

	recordFailure(error) {
		if (this.#closed) return;
		if (this.#broken) return;
		if (this.#probeToken?.invalidated) {
			this.#markStalled();
			return;
		}
		this.#state = 'degraded';
		this.#failureCode = voiceFailureCode(error);
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#generation += 1;
		if (this.#retryEnabled && this.#timer === null && this.#probeToken === null) this.#scheduleRetry();
	}

	recordReady() {
		if (this.#closed || this.#broken) return;
		const recovered = this.#state !== 'ready';
		this.#epoch += 1;
		if (this.#timer !== null) this.#cancelSchedule(this.#timer);
		this.#timer = null;
		if (this.#probeToken !== null) {
			const staleProbe = this.#probeToken;
			staleProbe.invalidated = true;
			if (staleProbe.timeout !== null) this.#cancelSchedule(staleProbe.timeout);
			staleProbe.timeout = null;
			staleProbe.controller.abort();
		}
		this.#state = 'ready';
		this.#failureCode = null;
		this.#failures = 0;
		this.#nextProbeAt = null;
		if (recovered) {
			this.#lastRecoveryAt = this.#now();
			this.#generation += 1;
		}
	}

	snapshot() {
		return Object.freeze({
			component: this.#component,
			state: this.#state,
			fallbackMode: this.#state === 'ready' ? null : 'text',
			boundary: this.#state === 'ready' ? null : this.#boundary,
			failureCode: this.#failureCode,
			consecutiveFailureCount: this.#failures,
			nextProbeAtEpochMs: this.#nextProbeAt,
			generation: this.#generation,
			lastRecoveryAtEpochMs: this.#lastRecoveryAt,
		});
	}

	close() {
		if (this.#closed) return;
		this.#closed = true;
		this.#epoch += 1;
		if (this.#timer !== null) this.#cancelSchedule(this.#timer);
		if (this.#probeToken?.timeout != null) this.#cancelSchedule(this.#probeToken.timeout);
		this.#probeToken?.controller.abort();
		this.#timer = null;
	}

	#scheduleRetry() {
		const delay = Math.min(this.#maxRetryMs, this.#initialRetryMs * 2 ** Math.min(20, this.#failures - 1));
		this.#nextProbeAt = this.#now() + delay;
		const epoch = ++this.#epoch;
		this.#timer = this.#schedule(() => {
			if (this.#closed || epoch !== this.#epoch) return;
			this.#timer = null;
			void this.#runProbe(epoch);
		}, delay);
	}

	async #runProbe(epoch) {
		if (this.#closed || epoch !== this.#epoch || this.#probeToken !== null || !this.#retryEnabled) return;
		const controller = new AbortController();
		const token = { epoch, controller, timeout: null, timedOut: false, invalidated: false };
		this.#probeToken = token;
		const raw = Promise.resolve().then(() => this.#probe(controller.signal));
		token.timeout = this.#schedule(() => {
			if (this.#closed || this.#probeToken !== token || token.epoch !== this.#epoch) return;
			token.timeout = null;
			token.timedOut = true;
			this.#markStalled();
			const error = typedError(`${this.#component === 'voice:stt' ? 'STT' : 'TTS'}_TIMEOUT`, 'Voice health probe timed out');
			error.name = 'TimeoutError';
			controller.abort(error);
		}, this.#probeTimeoutMs);
		raw.then(
			() => this.#settleProbe(token, null),
			(error) => this.#settleProbe(token, error),
		);
	}

	#settleProbe(token, failure) {
		if (token.timeout !== null) this.#cancelSchedule(token.timeout);
		token.timeout = null;
		if (this.#probeToken !== token) return;
		this.#probeToken = null;
		if (this.#closed) return;
		if (token.invalidated) {
			if (!this.#broken && this.#state === 'degraded' && this.#retryEnabled && this.#timer === null) this.#scheduleRetry();
			return;
		}
		if (token.timedOut) {
			if (this.#state === 'degraded' && this.#timer === null) this.#scheduleRetry();
			return;
		}
		if (failure === null) this.recordReady();
		else this.recordFailure(failure);
	}

	#markStalled() {
		if (this.#closed || this.#broken) return;
		this.#broken = true;
		this.#retryEnabled = false;
		this.#state = 'degraded';
		this.#failureCode = `${this.#component === 'voice:stt' ? 'STT' : 'TTS'}_PROVIDER_STALLED`;
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#nextProbeAt = null;
		this.#generation += 1;
		const error = typedError(this.#failureCode, 'Voice provider ignored cancellation and requires replacement');
		this.#onStalled(error);
	}
}

function aggregateVoiceStatus(tts, stt) {
	const failed = [tts, stt].find((snapshot) => snapshot.state !== 'ready');
	if (failed === undefined) {
		return Object.freeze({
			component: 'voice', state: 'ready', fallbackMode: null, boundary: null, failureCode: null,
			consecutiveFailureCount: 0, nextProbeAtEpochMs: null,
			generation: tts.generation + stt.generation,
			lastRecoveryAtEpochMs: latestRecovery(tts, stt),
		});
	}
	return Object.freeze({
		component: 'voice', state: failed.state, fallbackMode: 'text', boundary: 'voice_provider',
		failureCode: failed.failureCode,
		consecutiveFailureCount: Math.max(tts.consecutiveFailureCount, stt.consecutiveFailureCount),
		nextProbeAtEpochMs: earliestProbe(tts, stt),
		generation: tts.generation + stt.generation,
		lastRecoveryAtEpochMs: latestRecovery(tts, stt),
	});
}

async function probeTts(provider, signal) {
	if (typeof provider.probe === 'function') return provider.probe({ signal });
	if (typeof provider.warmup === 'function') return provider.warmup({ signal });
	validateSynthesis(await provider.synthesize({ text: '.', voiceId: PROBE_PROFILE.voiceId, speed: PROBE_PROFILE.speed, signal }));
}

async function probeStt(provider, signal) {
	if (provider === null || typeof provider?.transcribe !== 'function') {
		throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
	}
	if (typeof provider.probe === 'function') return provider.probe({ signal });
	if (typeof provider.warmup === 'function') return provider.warmup({ signal });
	validateTranscriptResult(await provider.transcribe({ pcm: Buffer.alloc(1_920), signal }));
}

function earliestProbe(...snapshots) {
	const values = snapshots.map((snapshot) => snapshot.nextProbeAtEpochMs).filter(Number.isSafeInteger);
	return values.length === 0 ? null : Math.min(...values);
}

function latestRecovery(...snapshots) {
	const values = snapshots.map((snapshot) => snapshot.lastRecoveryAtEpochMs).filter(Number.isSafeInteger);
	return values.length === 0 ? null : Math.max(...values);
}

function defaultSchedule(callback, delay) {
	const timer = setTimeout(callback, delay);
	timer.unref?.();
	return timer;
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
	if (error?.httpStatus === 503) return 503;
	if (error?.name === 'AbortError' || error?.name === 'TimeoutError') return 504;
	if (error?.code === 'TTS_RATE_LIMITED' || error?.code === 'TTS_CAPACITY') return 429;
	if (error?.code === 'STT_RATE_LIMITED' || error?.code === 'STT_CAPACITY') return 429;
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

function abortError(message) {
	const error = new Error(message);
	error.name = 'AbortError';
	return error;
}
