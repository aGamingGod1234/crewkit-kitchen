const MAX_OPERATION_LENGTH = 128;
const MAX_OPERATION_CAP = 16;
const LOCAL_OPERATIONS = new Set([
	'minecraft_change_to_publication',
	'event_receipt_to_branch',
	'branch_to_bridge_send',
	'command_to_first_progress',
	'action_completion',
]);

export class ControlLatencyRegistry {
	#windowSize;
	#operationCap;
	#samples = new Map();

	constructor({ windowSize = 50, operationCap = MAX_OPERATION_CAP } = {}) {
		if (!Number.isSafeInteger(windowSize) || windowSize < 1) {
			throw new TypeError('windowSize must be a positive safe integer');
		}
		if (!Number.isSafeInteger(operationCap) || operationCap < 1 || operationCap > MAX_OPERATION_CAP) {
			throw new TypeError(`operationCap must be in [1, ${MAX_OPERATION_CAP}]`);
		}
		this.#windowSize = windowSize;
		this.#operationCap = operationCap;
	}

	record(operationValue, durationValue) {
		const operation = requireOperation(operationValue);
		const durationMs = requireDuration(durationValue);
		let samples = this.#samples.get(operation);
		if (samples === undefined) {
			if (this.#samples.size >= this.#operationCap) {
				throw new RangeError('latency operation capacity is full');
			}
			samples = [];
			this.#samples.set(operation, samples);
		}
		samples.push(durationMs);
		if (samples.length > this.#windowSize) samples.splice(0, samples.length - this.#windowSize);
		return Object.freeze({ operation, durationMs });
	}

	snapshot() {
		return this.#summaries(false);
	}

	performanceSnapshot() {
		return this.#summaries(true);
	}

	#summaries(includeP99) {
		return [...this.#samples.entries()]
			.sort(([left], [right]) => left.localeCompare(right))
			.map(([operation, values]) => {
				const sorted = [...values].sort((left, right) => left - right);
				return Object.freeze({
					operation,
					count: sorted.length,
					p50Ms: percentile(sorted, 0.5),
					p95Ms: percentile(sorted, 0.95),
					...(includeP99 ? { p99Ms: percentile(sorted, 0.99) } : {}),
				});
			});
	}
}

function requireOperation(value) {
	if (typeof value !== 'string') throw new TypeError('operation must be a string');
	const operation = value.trim();
	if (operation.length === 0 || operation.length > MAX_OPERATION_LENGTH) {
		throw new TypeError(`operation must be nonblank and at most ${MAX_OPERATION_LENGTH} characters`);
	}
	if (!LOCAL_OPERATIONS.has(operation)) throw new TypeError('operation must be a named local control operation');
	return operation;
}

function requireDuration(value) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError('duration must be non-negative and finite');
	return value;
}

function percentile(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}
