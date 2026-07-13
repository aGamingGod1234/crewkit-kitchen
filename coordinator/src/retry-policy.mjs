const DEFAULT_INITIAL_DELAY_MS = 500;
const DEFAULT_MAXIMUM_DELAY_MS = 5_000;
const DEFAULT_MULTIPLIER = 2;

export class RetryPolicy {
	#initialDelayMs;
	#maximumDelayMs;
	#multiplier;
	#nextDelayMs;

	constructor({
		initialDelayMs = DEFAULT_INITIAL_DELAY_MS,
		maximumDelayMs = DEFAULT_MAXIMUM_DELAY_MS,
		multiplier = DEFAULT_MULTIPLIER,
	} = {}) {
		this.#initialDelayMs = positiveInteger(initialDelayMs, 'initialDelayMs');
		this.#maximumDelayMs = positiveInteger(maximumDelayMs, 'maximumDelayMs');
		if (this.#initialDelayMs > this.#maximumDelayMs) throw new TypeError('initialDelayMs must not exceed maximumDelayMs');
		if (typeof multiplier !== 'number' || !Number.isFinite(multiplier) || multiplier <= 1) throw new TypeError('multiplier must be finite and greater than one');
		this.#multiplier = multiplier;
		this.#nextDelayMs = this.#initialDelayMs;
	}

	nextDelay() {
		const current = this.#nextDelayMs;
		this.#nextDelayMs = Math.min(this.#maximumDelayMs, Math.ceil(current * this.#multiplier));
		return current;
	}

	reset() {
		this.#nextDelayMs = this.#initialDelayMs;
	}
}

function positiveInteger(value, name) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${name} must be a positive safe integer`);
	return value;
}
