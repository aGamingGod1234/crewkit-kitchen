const MAX_IDENTITY_LENGTH = 128;

export function createProviderTurnTelemetry(value) {
	const input = requireObject(value, 'provider telemetry');
	const errorCode = normalizeErrorCode(input.errorCode ?? input.error?.code ?? (input.error == null ? null : 'ERROR'));
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
	});
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

function requirePositiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function requireDuration(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError(`${field} must be a non-negative finite number`);
	return Math.round(value);
}
