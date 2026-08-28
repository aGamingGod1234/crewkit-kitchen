const DEFAULT_INITIAL_RETRY_MS = 1_000;
const DEFAULT_MAX_RETRY_MS = 30_000;
const DEFAULT_STARTUP_TIMEOUT_MS = 10_000;
const DEFAULT_WARMUP_TIMEOUT_MS = 30_000;

export class VoiceSupervisor {
	#startWorker;
	#now;
	#schedule;
	#cancelSchedule;
	#initialRetryMs;
	#maxRetryMs;
	#startupTimeoutMs;
	#warmupTimeoutMs;
	#started = false;
	#closed = false;
	#worker = null;
	#candidate = null;
	#retryTimer = null;
	#operationEpoch = 0;
	#statusGeneration = 0;
	#failures = 0;
	#nextRetryAt = null;
	#failureCode = 'VOICE_NOT_STARTED';
	#lastRecoveryAt = null;
	#controllers = new Set();
	#closedWorkers = new WeakSet();

	constructor({
		startWorker,
		now = Date.now,
		schedule = defaultSchedule,
		cancelSchedule = clearTimeout,
		initialRetryMs = DEFAULT_INITIAL_RETRY_MS,
		maxRetryMs = DEFAULT_MAX_RETRY_MS,
		startupTimeoutMs = DEFAULT_STARTUP_TIMEOUT_MS,
		warmupTimeoutMs = DEFAULT_WARMUP_TIMEOUT_MS,
	} = {}) {
		if (typeof startWorker !== 'function') throw new TypeError('startWorker must be a function');
		if (typeof now !== 'function' || typeof schedule !== 'function' || typeof cancelSchedule !== 'function') {
			throw new TypeError('voice supervisor clock and scheduler must be functions');
		}
		for (const [name, value] of Object.entries({ initialRetryMs, maxRetryMs, startupTimeoutMs, warmupTimeoutMs })) {
			if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${name} must be positive`);
		}
		if (maxRetryMs < initialRetryMs) throw new TypeError('maxRetryMs must not be less than initialRetryMs');
		this.#startWorker = startWorker;
		this.#now = now;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#initialRetryMs = initialRetryMs;
		this.#maxRetryMs = maxRetryMs;
		this.#startupTimeoutMs = startupTimeoutMs;
		this.#warmupTimeoutMs = warmupTimeoutMs;
	}

	start() {
		if (this.#closed || this.#started) return;
		this.#started = true;
		queueMicrotask(() => this.#attempt());
	}

	statusSnapshots() {
		if (this.#worker !== null) {
			try {
				const snapshots = this.#worker.statusSnapshots?.();
				if (Array.isArray(snapshots)) return snapshots.slice(0, 3).map((snapshot) => this.#withStartupHistory(snapshot));
				const snapshot = this.#worker.statusSnapshot?.();
				if (snapshot !== null && typeof snapshot === 'object') return [this.#withStartupHistory(snapshot)];
			} catch { /* optional voice status is observational */ }
		}
		const state = this.#failures === 0 ? 'unknown' : 'degraded';
		const boundary = state === 'unknown' ? 'voice_start' : 'voice_start';
		return ['voice', 'voice:tts', 'voice:stt'].map((component) => Object.freeze({
			component,
			state,
			fallbackMode: 'text',
			boundary,
			failureCode: this.#failureCode,
			consecutiveFailureCount: this.#failures,
			nextProbeAtEpochMs: this.#nextRetryAt,
			generation: this.#statusGeneration,
			lastRecoveryAtEpochMs: this.#lastRecoveryAt,
		}));
	}

	async close() {
		if (this.#closed) return;
		this.#closed = true;
		this.#operationEpoch += 1;
		if (this.#retryTimer !== null) {
			this.#cancelSchedule(this.#retryTimer);
			this.#retryTimer = null;
		}
		for (const controller of this.#controllers) controller.abort();
		this.#controllers.clear();
		const workers = [this.#worker, this.#candidate];
		this.#worker = null;
		this.#candidate = null;
		await Promise.allSettled(workers.map((worker) => this.#closeWorker(worker)));
	}

	async #attempt() {
		if (this.#closed || this.#worker !== null) return;
		const epoch = ++this.#operationEpoch;
		this.#retryTimer = null;
		let startup;
		try {
			startup = Promise.resolve().then(() => this.#bounded(
				(signal) => this.#startWorker({ signal }),
				this.#startupTimeoutMs,
				'VOICE_START_TIMEOUT',
				(worker) => this.#closeWorker(worker),
			));
			startup.then((worker) => {
				if (epoch !== this.#operationEpoch || this.#closed) void this.#closeWorker(worker);
			}, () => {});
			const worker = await startup;
			if (worker === null || typeof worker !== 'object' || typeof worker.close !== 'function') {
				throw voiceError('VOICE_UNAVAILABLE', 'Voice worker is unavailable');
			}
			if (epoch !== this.#operationEpoch || this.#closed) {
				await this.#closeWorker(worker);
				return;
			}
			this.#candidate = worker;
			if (typeof worker.warmup === 'function') {
				await this.#bounded((signal) => worker.warmup({ signal }), this.#warmupTimeoutMs, 'VOICE_WARMUP_TIMEOUT');
			}
			if (epoch !== this.#operationEpoch || this.#closed) {
				await this.#closeWorker(worker);
				return;
			}
			this.#candidate = null;
			this.#worker = worker;
			if (this.#failures > 0) this.#lastRecoveryAt = this.#now();
			this.#failures = 0;
			this.#nextRetryAt = null;
			this.#failureCode = null;
			this.#statusGeneration += 1;
		} catch (error) {
			if (epoch !== this.#operationEpoch || this.#closed) return;
			this.#operationEpoch += 1;
			const failedCandidate = this.#candidate;
			this.#candidate = null;
			await this.#closeWorker(failedCandidate);
			if (this.#closed) return;
			this.#failures = Math.min(1_000_000, this.#failures + 1);
			this.#failureCode = failureCode(error);
			this.#statusGeneration += 1;
			const delay = Math.min(this.#maxRetryMs, this.#initialRetryMs * 2 ** Math.min(20, this.#failures - 1));
			this.#nextRetryAt = this.#now() + delay;
			const retryEpoch = this.#operationEpoch;
			this.#retryTimer = this.#schedule(() => {
				if (this.#closed || retryEpoch !== this.#operationEpoch) return;
				void this.#attempt();
			}, delay);
		}
	}

	#bounded(operation, timeoutMs, code, onLateResult = null) {
		const controller = new AbortController();
		this.#controllers.add(controller);
		let timeout;
		let expired = false;
		const work = Promise.resolve().then(() => operation(controller.signal));
		work.then((result) => {
			if (expired && typeof onLateResult === 'function') void onLateResult(result);
		}, () => {});
		let onAbort;
		const aborted = new Promise((_, reject) => {
			onAbort = () => {
				expired = true;
				const error = voiceError('VOICE_OPERATION_CANCELLED', 'Voice lifecycle operation was cancelled');
				error.name = 'AbortError';
				reject(error);
			};
			controller.signal.addEventListener('abort', onAbort, { once: true });
		});
		const deadline = new Promise((_, reject) => {
			timeout = this.#schedule(() => {
				expired = true;
				reject(voiceError(code, 'Voice lifecycle operation timed out'));
				controller.abort();
			}, timeoutMs);
		});
		return Promise.race([work, deadline, aborted]).finally(() => {
			this.#cancelSchedule(timeout);
			controller.signal.removeEventListener('abort', onAbort);
			this.#controllers.delete(controller);
		});
	}

	#withStartupHistory(snapshot) {
		if (snapshot === null || typeof snapshot !== 'object') return snapshot;
		const workerGeneration = Number.isSafeInteger(snapshot.generation) && snapshot.generation >= 0
			? snapshot.generation
			: 0;
		const workerRecovery = Number.isSafeInteger(snapshot.lastRecoveryAtEpochMs)
			? snapshot.lastRecoveryAtEpochMs
			: null;
		return Object.freeze({
			...snapshot,
			generation: workerGeneration + this.#statusGeneration,
			lastRecoveryAtEpochMs: workerRecovery === null
				? this.#lastRecoveryAt
				: this.#lastRecoveryAt === null ? workerRecovery : Math.max(workerRecovery, this.#lastRecoveryAt),
		});
	}

	#closeWorker(worker) {
		if (worker === null || typeof worker !== 'object' || typeof worker.close !== 'function') return Promise.resolve();
		if (this.#closedWorkers.has(worker)) return Promise.resolve();
		this.#closedWorkers.add(worker);
		try { return Promise.resolve(worker.close()).then(() => undefined, () => undefined); }
		catch { return Promise.resolve(); }
	}
}

function defaultSchedule(callback, delay) {
	const timer = setTimeout(callback, delay);
	timer.unref?.();
	return timer;
}

function failureCode(error) {
	return typeof error?.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code)
		? error.code
		: 'VOICE_UNAVAILABLE';
}

function voiceError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
