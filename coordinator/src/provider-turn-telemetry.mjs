import { normalizeRetryReason, validateTraceId } from './control-latency-registry.mjs';
import { normalizeToolResponseSummary } from './tool-response-summary.mjs';

const MAX_IDENTITY_LENGTH = 128;

export function createProviderTurnTelemetry(value) {
	const input = requireObject(value, 'provider telemetry');
	const errorCode = normalizeErrorCode(input.errorCode ?? input.error?.code ?? (input.error == null ? null : 'ERROR'));
	const tokens = input.tokens === null || input.tokens === undefined ? null : normalizeTokens(input.tokens);
	return Object.freeze({
		provider: requireIdentity(input.provider, 'provider'),
		model: requireIdentity(input.model, 'model'),
		operation: requireIdentity(input.operation, 'operation'),
		attempt: requirePositiveInteger(input.attempt ?? 1, 'attempt'),
		queueWaitMs: requireDuration(input.queueWaitMs ?? 0, 'queueWaitMs'),
		durationMs: requireDuration(input.durationMs ?? 0, 'durationMs'),
		errorCode,
		timeout: Boolean(input.timeout),
		retry: Boolean(input.retry),
		restart: Boolean(input.restart),
		...(input.profileFingerprint === undefined ? {} : { profileFingerprint: requireFingerprint(input.profileFingerprint) }),
		...(input.sessionGeneration === undefined ? {} : { sessionGeneration: requirePositiveInteger(input.sessionGeneration, 'sessionGeneration') }),
		...(input.sessionReuse === undefined ? {} : { sessionReuse: Boolean(input.sessionReuse) }),
		...(input.sessionState === undefined ? {} : { sessionState: requireIdentity(input.sessionState, 'sessionState') }),
		...(input.continuation === undefined ? {} : { continuation: requireIdentity(input.continuation, 'continuation') }),
		...(input.resetReason === undefined ? {} : { resetReason: input.resetReason === null ? null : requireIdentity(input.resetReason, 'resetReason') }),
		...(input.traceId === undefined ? {} : { traceId: validateTraceId(input.traceId) }),
		...(input.retryReason === undefined || input.retryReason === null
			? {}
			: { retryReason: normalizeRetryReason(input.retryReason) }),
		...(tokens === null ? {} : { tokens }),
		...(input.usage == null ? {} : { usage: normalizeNativeUsage(input.usage) }),
		...(input.toolResponses === undefined ? {} : { toolResponses: normalizeToolResponseSummary(input.toolResponses) }),
		...Object.fromEntries(['threadId', 'turnId'].filter((key) => input[key] !== undefined).map((key) => [key, input[key] === null ? null : requireIdentity(input[key], key)])),
		...Object.fromEntries(['toolCalls', 'toolResultBytes', 'inputBytes', 'inputCount'].filter((key) => input[key] !== undefined).map((key) => [key, requireCounter(input[key], key)])),
		...(input.rateLimited === undefined ? {} : { rateLimited: Boolean(input.rateLimited) }),
		...(input.compaction === undefined ? {} : { compaction: Boolean(input.compaction) }),
	});
}

function requireCounter(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative safe integer`);
	return value;
}

function normalizeNativeUsage(value) {
	const usage = requireObject(value, 'usage');
	if (usage.scope !== 'observed_thread_counter_delta') throw new TypeError('unsupported native usage scope');
	if (!['available', 'missing', 'baseline_unknown', 'counter_reset'].includes(usage.status)) throw new TypeError('unsupported native usage status');
	return Object.freeze({ scope: usage.scope, status: usage.status,
		start: usage.start == null ? null : normalizeTokens(usage.start),
		end: usage.end == null ? null : normalizeTokens(usage.end),
		updates: requireCounter(usage.updates, 'usage.updates'), counterReset: usage.counterReset === true,
		attributionComplete: false, gapBefore: usage.gapBefore == null ? null : normalizeTokens(usage.gapBefore) });
}

function normalizeTokens(value) {
	const input = requireObject(value, 'tokens');
	return Object.freeze(Object.fromEntries(['input', 'output', 'reasoning', 'cached', 'cacheWrite'].map((category) => {
		const count = input[category];
		if (count === null || count === undefined) return [category, null];
		if (!Number.isSafeInteger(count) || count < 0) throw new TypeError(`${category} tokens must be a nonnegative safe integer or null`);
		return [category, count];
	})));
}

export function normalizeErrorCode(value) {
	if (value === null || value === undefined || String(value).trim().length === 0) return null;
	return String(value).trim().toUpperCase()
		.replace(/[^A-Z0-9]+/g, '_')
		.replace(/^_+|_+$/g, '')
		.slice(0, 64) || 'ERROR';
}

function requireObject(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${label} must be an object`);
	return value;
}

function requireIdentity(value, field) {
	if (typeof value !== 'string') throw new TypeError(`${field} must be a string`);
	const normalized = value.trim();
	if (normalized.length === 0 || normalized.length > MAX_IDENTITY_LENGTH) throw new TypeError(`${field} must be nonblank and at most ${MAX_IDENTITY_LENGTH} characters`);
	return normalized;
}

function requireFingerprint(value) {
	if (typeof value !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(value)) throw new TypeError('profileFingerprint must be a sha256 fingerprint');
	return value;
}

function requirePositiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function requireDuration(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError(`${field} must be a non-negative finite number`);
	return Math.round(value);
}
