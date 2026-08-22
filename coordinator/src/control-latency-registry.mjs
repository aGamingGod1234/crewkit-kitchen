const MAX_OPERATION_LENGTH = 128;
const MAX_OPERATION_CAP = 16;
const MAX_TRACE_ID_BYTES = 128;
const MAX_RETRY_REASON_LENGTH = 64;
const MAX_TRACE_CAP = 4_096;

const LOCAL_OPERATIONS = new Set([
	'minecraft_change_to_publication',
	'event_receipt_to_branch',
	'branch_to_bridge_send',
	'command_to_first_progress',
	'action_completion',
]);

export const TRACE_PHASES = Object.freeze([
	'queue_wait',
	'provider_first_byte',
	'provider_final_byte',
	'parse',
	'first_command_dispatch',
	'first_world_action',
	'completion_verification',
]);

export const REQUIRED_TRACE_PHASES = Object.freeze(TRACE_PHASES.slice(0, 6));
export const TRACE_OUTCOMES = Object.freeze(['completed', 'failed', 'skipped']);
const TRACE_PHASE_INDEX = new Map(TRACE_PHASES.map((phase, index) => [phase, index]));
const TRACE_OUTCOME_SET = new Set(TRACE_OUTCOMES);

export class ControlLatencyRegistry {
	#windowSize;
	#operationCap;
	#samples = new Map();
	#traces = new Map();
	#traceCap;

	constructor({ windowSize = 50, operationCap = MAX_OPERATION_CAP, traceCap = MAX_TRACE_CAP } = {}) {
		if (!Number.isSafeInteger(windowSize) || windowSize < 1) {
			throw new TypeError('windowSize must be a positive safe integer');
		}
		if (!Number.isSafeInteger(operationCap) || operationCap < 1 || operationCap > MAX_OPERATION_CAP) {
			throw new TypeError(`operationCap must be in [1, ${MAX_OPERATION_CAP}]`);
		}
		if (!Number.isSafeInteger(traceCap) || traceCap < 1 || traceCap > MAX_TRACE_CAP) {
			throw new TypeError(`traceCap must be in [1, ${MAX_TRACE_CAP}]`);
		}
		this.#windowSize = windowSize;
		this.#operationCap = operationCap;
		this.#traceCap = traceCap;
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

	/** Records one disjoint monotonic span in the fixed task trace. */
	recordTracePhase(traceIdValue, phaseValue, spanValue) {
		const traceId = requireTraceId(traceIdValue);
		const phase = requireTracePhase(phaseValue);
		if (!isPlainObject(spanValue)) throw new TypeError('trace phase span must be an object');
		const startMs = requireTimestamp(spanValue.startMs, 'startMs');
		const endMs = requireTimestamp(spanValue.endMs, 'endMs');
		if (endMs < startMs) throw new TypeError('trace phase timestamps must be monotonic');
		const outcome = requireOutcome(spanValue.outcome ?? 'completed');
		const retryReason = spanValue.retryReason === undefined || spanValue.retryReason === null
			? undefined
			: normalizeRetryReason(spanValue.retryReason);

		let trace = this.#traces.get(traceId);
		if (trace === undefined) {
			if (this.#traces.size >= this.#traceCap) {
				this.#traces.delete(this.#traces.keys().next().value);
			}
			trace = { phases: new Map(), lastPhaseIndex: -1, lastEndMs: null };
			this.#traces.set(traceId, trace);
		}
		if (trace.phases.has(phase)) throw new TypeError(`trace phase '${phase}' is duplicate`);
		const phaseIndex = TRACE_PHASE_INDEX.get(phase);
		if (phaseIndex <= trace.lastPhaseIndex) throw new TypeError(`trace phase '${phase}' is out of order`);
		if (trace.lastEndMs !== null && startMs < trace.lastEndMs) throw new TypeError('trace phases must not overlap or move backwards monotonically');

		const span = {
			phase,
			startMs,
			endMs,
			durationMs: endMs - startMs,
			outcome,
			...(retryReason === undefined ? {} : { retryReason }),
		};
		trace.phases.set(phase, Object.freeze(span));
		trace.lastPhaseIndex = phaseIndex;
		trace.lastEndMs = endMs;
		return span;
	}

	// Alias used by producers that already call ordinary spans "recordPhase".
	recordPhase(traceId, phase, span) {
		return this.recordTracePhase(traceId, phase, span);
	}

	/** Returns a fail-closed summary; incomplete traces have no total duration. */
	completeTrace(traceIdValue) {
		const traceId = requireTraceId(traceIdValue);
		const trace = this.#traces.get(traceId);
		if (trace === undefined) {
			return Object.freeze({ traceId, complete: false, totalMs: null, phases: [] });
		}
		const phases = orderedPhases(trace);
		const missing = TRACE_PHASES.some((phase) => !trace.phases.has(phase));
		const failed = REQUIRED_TRACE_PHASES.some((phase) => trace.phases.get(phase)?.outcome !== 'completed');
		const complete = !missing && !failed;
		return Object.freeze({
			traceId,
			complete,
			totalMs: complete ? phases.at(-1).endMs - phases[0].startMs : null,
			phases,
		});
	}

	traceSnapshot() {
		return [...this.#traces.keys()].map((traceId) => this.completeTrace(traceId));
	}

	traces() {
		return this.traceSnapshot();
	}

	snapshot() {
		return [...this.#samples.entries()]
			.sort(([left], [right]) => left.localeCompare(right))
			.map(([operation, values]) => {
				const sorted = [...values].sort((left, right) => left - right);
				return Object.freeze({
					operation,
					count: sorted.length,
					p50Ms: percentile(sorted, 0.5),
					p95Ms: percentile(sorted, 0.95),
				});
			});
	}
}

export function validateTraceId(value) {
	return requireTraceId(value);
}

export function normalizeRetryReason(value) {
	if (typeof value !== 'string') throw new TypeError('retryReason must be a string');
	let text = value.trim();
	if (text.length === 0) throw new TypeError('retryReason must be nonblank');
	// Keep only a stable error-code-like prefix. Never retain provider output,
	// exception text, prompt text, or credential-shaped material.
	text = text
		.replace(/\b(?:sk|key|token|secret|password|credential)[-_]?[A-Za-z0-9._~+/=-]{4,}/gi, ' ')
		.replace(/\b(?:prompt|output|exception|stack|traceback)\b[\s\S]*$/i, ' ');
	const normalized = text.toUpperCase()
		.replace(/[^A-Z0-9]+/g, '_')
		.replace(/^_+|_+$/g, '')
		.slice(0, MAX_RETRY_REASON_LENGTH);
	if (normalized.length === 0) throw new TypeError('retryReason must contain an error code');
	return normalized;
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

function requireTraceId(value) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError('traceId must be nonblank');
	if (Buffer.byteLength(value, 'utf8') > MAX_TRACE_ID_BYTES) throw new TypeError(`traceId must be at most ${MAX_TRACE_ID_BYTES} UTF-8 bytes`);
	if ([...value].some((character) => /[\u0000-\u001f\u007f]/u.test(character))) throw new TypeError('traceId contains control characters');
	return value;
}

function requireTracePhase(value) {
	if (typeof value !== 'string' || !TRACE_PHASE_INDEX.has(value)) throw new TypeError('trace phase must be one of the named latency phases');
	return value;
}

function requireOutcome(value) {
	if (typeof value !== 'string' || !TRACE_OUTCOME_SET.has(value)) throw new TypeError('trace outcome must be completed, failed, or skipped');
	return value;
}

function requireTimestamp(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError(`trace ${field} must be a non-negative finite number`);
	return value;
}

function requireDuration(value) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError('duration must be non-negative and finite');
	return value;
}

function orderedPhases(trace) {
	return TRACE_PHASES.filter((phase) => trace.phases.has(phase)).map((phase) => trace.phases.get(phase));
}

function percentile(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function isPlainObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}
