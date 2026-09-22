const DEFAULT_WINDOW_SIZE = 64;
const MAX_WINDOW_SIZE = 256;
const TIMING_OUTCOMES = new Set(['completed', 'failed', 'cancelled']);

/**
 * Bounded timing samples for native provider segments.
 *
 * A provider segment ends at a tool request or at the end of a native turn.
 * Tool execution time is never added to a segment sample. Failed and
 * cancelled segments remain visible in counters but do not enter latency
 * percentiles, which keeps planning-ahead estimates fail-closed.
 */
export class NativeDecisionTimingWindow {
	#windowSize;
	#providerSegments = [];
	#firstToolRequests = [];
	#firstUsableTools = [];
	#lifetimeProviderSegments = 0;
	#lifetimeCompletedSegments = 0;
	#lifetimeCompletedSamples = 0;
	#lifetimeFailedSegments = 0;
	#lifetimeCancelledSegments = 0;
	#lifetimeFirstToolRequests = 0;
	#lifetimeFirstUsableTools = 0;

	constructor({ windowSize = DEFAULT_WINDOW_SIZE } = {}) {
		if (!Number.isSafeInteger(windowSize) || windowSize < 1 || windowSize > MAX_WINDOW_SIZE) {
			throw new TypeError(`windowSize must be a safe integer in [1, ${MAX_WINDOW_SIZE}]`);
		}
		this.#windowSize = windowSize;
	}

	recordProviderSegment({ durationMs, outcome = 'completed', sample = true }) {
		const duration = requireDuration(durationMs, 'durationMs');
		const checkedOutcome = requireOutcome(outcome);
		this.#lifetimeProviderSegments += 1;
		if (checkedOutcome === 'completed') {
			this.#lifetimeCompletedSegments += 1;
			if (sample === true) {
				this.#lifetimeCompletedSamples += 1;
				pushBounded(this.#providerSegments, duration, this.#windowSize);
			}
		} else if (checkedOutcome === 'failed') {
			this.#lifetimeFailedSegments += 1;
		} else {
			this.#lifetimeCancelledSegments += 1;
		}
		return duration;
	}

	recordFirstToolRequest(durationMs) {
		const duration = requireDuration(durationMs, 'durationMs');
		this.#lifetimeFirstToolRequests += 1;
		pushBounded(this.#firstToolRequests, duration, this.#windowSize);
		return duration;
	}

	recordFirstUsableTool(durationMs) {
		const duration = requireDuration(durationMs, 'durationMs');
		this.#lifetimeFirstUsableTools += 1;
		pushBounded(this.#firstUsableTools, duration, this.#windowSize);
		return duration;
	}

	snapshot(identity = null) {
		return Object.freeze({
			identity: identity === null ? null : Object.freeze({ ...identity }),
			count: this.#providerSegments.length,
			lifetimeCount: this.#lifetimeCompletedSamples,
			completedSegmentCount: this.#lifetimeCompletedSegments,
			lifetimeSegmentCount: this.#lifetimeProviderSegments,
			failedSegmentCount: this.#lifetimeFailedSegments,
			cancelledSegmentCount: this.#lifetimeCancelledSegments,
			p50Ms: percentile(this.#providerSegments, 0.5),
			p95Ms: percentile(this.#providerSegments, 0.95),
			sampleWindowSize: this.#windowSize,
			firstToolRequestCount: this.#firstToolRequests.length,
			firstToolRequestLifetimeCount: this.#lifetimeFirstToolRequests,
			firstToolRequestP50Ms: percentile(this.#firstToolRequests, 0.5),
			firstToolRequestP95Ms: percentile(this.#firstToolRequests, 0.95),
			firstUsableToolCount: this.#firstUsableTools.length,
			firstUsableToolLifetimeCount: this.#lifetimeFirstUsableTools,
			firstUsableToolP50Ms: percentile(this.#firstUsableTools, 0.5),
			firstUsableToolP95Ms: percentile(this.#firstUsableTools, 0.95),
		});
	}
}

export const DEFAULT_NATIVE_DECISION_TIMING_WINDOW = DEFAULT_WINDOW_SIZE;

function pushBounded(samples, value, windowSize) {
	samples.push(value);
	if (samples.length > windowSize) samples.splice(0, samples.length - windowSize);
}

function percentile(samples, fraction) {
	if (samples.length === 0) return null;
	const sorted = [...samples].sort((left, right) => left - right);
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function requireDuration(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError(`${field} must be a non-negative finite number`);
	return Math.round(value);
}

function requireOutcome(value) {
	if (!TIMING_OUTCOMES.has(value)) throw new TypeError('outcome must be completed, failed, or cancelled');
	return value;
}
