const DEFAULT_MAX_PENDING = 64;
const DEFAULT_OPERATION_TIMEOUT_MS = 250;
const DEFAULT_CLOSE_TIMEOUT_MS = 1_000;

/** A bounded, non-rejecting queue for observational sinks. */
export class BestEffortDiagnosticQueue {
	#maxPending;
	#operationTimeoutMs;
	#closeTimeoutMs;
	#schedule;
	#cancel;
	#dispatch;
	#now;
	#pending = [];
	#active = false;
	#pumpScheduled = false;
	#closed = false;
	#closePromise = null;
	#idleWaiters = new Set();
	#droppedCount = 0;
	#state = 'ready';
	#failureCode = null;
	#consecutiveFailureCount = 0;
	#generation = 0;
	#lastRecoveryAtEpochMs = null;

	constructor({
		maxPending = DEFAULT_MAX_PENDING,
		operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS,
		closeTimeoutMs = DEFAULT_CLOSE_TIMEOUT_MS,
		schedule = setTimeout,
		cancel = clearTimeout,
		dispatch = setImmediate,
		now = Date.now,
	} = {}) {
		if (!Number.isSafeInteger(maxPending) || maxPending < 1 || maxPending > 1_024) throw new TypeError('maxPending must be in [1, 1024]');
		if (!Number.isSafeInteger(operationTimeoutMs) || operationTimeoutMs < 1) throw new TypeError('operationTimeoutMs must be positive');
		if (!Number.isSafeInteger(closeTimeoutMs) || closeTimeoutMs < 1) throw new TypeError('closeTimeoutMs must be positive');
		if (typeof schedule !== 'function' || typeof cancel !== 'function' || typeof dispatch !== 'function' || typeof now !== 'function') throw new TypeError('diagnostic queue lifecycle dependencies must be functions');
		this.#maxPending = maxPending;
		this.#operationTimeoutMs = operationTimeoutMs;
		this.#closeTimeoutMs = closeTimeoutMs;
		this.#schedule = schedule;
		this.#cancel = cancel;
		this.#dispatch = dispatch;
		this.#now = now;
	}

	get droppedCount() { return this.#droppedCount; }

	submit(operation) {
		if (typeof operation !== 'function') throw new TypeError('diagnostic operation must be a function');
		if (this.#closed) return false;
		if (this.#pending.length + (this.#active ? 1 : 0) >= this.#maxPending) {
			this.#droppedCount = Math.min(Number.MAX_SAFE_INTEGER, this.#droppedCount + 1);
			this.#recordFailure('DIAGNOSTIC_BACKPRESSURE');
			return false;
		}
		this.#pending.push(operation);
		this.#pump();
		return true;
	}

	statusSnapshot(component = 'diagnostics') {
		return Object.freeze({
			component,
			state: this.#state,
			fallbackMode: this.#state === 'ready' ? null : 'drop',
			boundary: this.#state === 'ready' ? null : 'diagnostic_sink',
			failureCode: this.#failureCode,
			consecutiveFailureCount: this.#consecutiveFailureCount,
			nextProbeAtEpochMs: null,
			generation: this.#generation,
			lastRecoveryAtEpochMs: this.#lastRecoveryAtEpochMs,
		});
	}

	close() {
		if (this.#closePromise !== null) return this.#closePromise;
		this.#closed = true;
		this.#closePromise = this.#waitForIdle(this.#closeTimeoutMs).then(() => undefined, () => undefined);
		return this.#closePromise;
	}

	#pump() {
		if (this.#active || this.#pumpScheduled || this.#pending.length === 0) {
			this.#notifyIdle();
			return;
		}
		this.#pumpScheduled = true;
		try {
			this.#dispatch(() => {
				this.#pumpScheduled = false;
				this.#runNext();
			});
		} catch {
			this.#pumpScheduled = false;
			this.#pending.shift();
			this.#recordFailure('DIAGNOSTIC_SINK_FAILED');
			this.#pump();
		}
	}

	#runNext() {
		if (this.#active || this.#pending.length === 0) {
			this.#notifyIdle();
			return;
		}
		const operation = this.#pending.shift();
		let result;
		try { result = operation(); }
		catch {
			this.#recordFailure('DIAGNOSTIC_SINK_FAILED');
			this.#pump();
			return;
		}
		if (result === null || (typeof result !== 'object' && typeof result !== 'function') || typeof result.then !== 'function') {
			this.#recordSuccess();
			this.#pump();
			return;
		}
		this.#active = true;
		this.#settleAsync(result).then((outcome) => {
			this.#active = false;
			if (outcome === 'success') this.#recordSuccess();
			else this.#recordFailure(outcome === 'timeout' ? 'DIAGNOSTIC_SINK_TIMEOUT' : 'DIAGNOSTIC_SINK_FAILED');
			this.#pump();
			this.#notifyIdle();
		});
	}

	#settleAsync(value) {
		return new Promise((resolve) => {
			let settled = false;
			let handle;
			const finish = (outcome) => {
				if (settled) return;
				settled = true;
				try { this.#cancel(handle); } catch { /* timer cleanup is observational */ }
				resolve(outcome);
			};
			try { handle = this.#schedule(() => finish('timeout'), this.#operationTimeoutMs); }
			catch { finish('timeout'); }
			Promise.resolve(value).then(() => finish('success'), () => finish('failure'));
		});
	}

	#waitForIdle(timeoutMs) {
		if (!this.#active && !this.#pumpScheduled && this.#pending.length === 0) return Promise.resolve();
		return new Promise((resolve) => {
			let settled = false;
			let handle;
			const finish = () => {
				if (settled) return;
				settled = true;
				this.#idleWaiters.delete(onIdle);
				this.#pending.length = 0;
				try { this.#cancel(handle); } catch { /* timer cleanup is observational */ }
				resolve();
			};
			const onIdle = () => {
				if (!this.#active && !this.#pumpScheduled && this.#pending.length === 0) finish();
			};
			this.#idleWaiters.add(onIdle);
			try { handle = this.#schedule(finish, timeoutMs); }
			catch { finish(); }
			onIdle();
		});
	}

	#notifyIdle() {
		if (this.#active || this.#pumpScheduled || this.#pending.length > 0) return;
		for (const waiter of [...this.#idleWaiters]) waiter();
	}

	#recordFailure(code) {
		if (this.#state === 'ready') this.#generation += 1;
		this.#state = 'degraded';
		this.#failureCode = code;
		this.#consecutiveFailureCount = Math.min(1_000_000, this.#consecutiveFailureCount + 1);
	}

	#recordSuccess() {
		if (this.#state === 'degraded') {
			let recoveredAt = null;
			try {
				const candidate = this.#now();
				if (Number.isSafeInteger(candidate) && candidate >= 0) recoveredAt = candidate;
			} catch { /* clocks are diagnostic input */ }
			this.#lastRecoveryAtEpochMs = recoveredAt;
		}
		this.#state = 'ready';
		this.#failureCode = null;
		this.#consecutiveFailureCount = 0;
	}
}
