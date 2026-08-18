import { createHash } from 'node:crypto';
import { appendFile as defaultAppendFile } from 'node:fs/promises';

const MAX_PRIVATE_TEXT_BYTES = 65_536;
const MAX_PUBLIC_EXCERPT_BYTES = 512;
const MAX_ROW_BYTES = 262_143;
const SENSITIVE_TEXT = /((?:bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)\s*[:=]\s*)([^\s,;)}\]"']+)/gi;
const SECRET_SHAPED_TEXT = /((?:[A-Za-z0-9_-]*(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]*)\s*[:=]\s*)([^\s,;)}\]"']+)/gi;
const BEARER_TEXT = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const QUOTED_SECRET_KEY = /(["'])(?:[A-Za-z0-9_-]*(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]*)\1\s*:\s*(["'])/gi;
const PATH_TEXT = /(?:[A-Za-z]:\\[^\s\]]+|(?:^|\s)\/[^\s]+)/g;

/** Bounded, serialized provider-turn capture with private source and public evidence. */
export class ProviderTurnRecorder {
	#runId;
	#scenarioId;
	#privatePath;
	#publicSink;
	#appendFile;
	#now;
	#queue = Promise.resolve();
	#closed = false;

	constructor({ runId, scenarioId, privatePath, publicSink = null, appendFile = defaultAppendFile, now = Date.now } = {}) {
		if (typeof runId !== 'string' || runId.trim() === '') throw new TypeError('runId must be nonblank');
		if (typeof scenarioId !== 'string' || scenarioId.trim() === '') throw new TypeError('scenarioId must be nonblank');
		if (privatePath !== null && privatePath !== undefined && (typeof privatePath !== 'string' || privatePath.trim() === '')) throw new TypeError('privatePath must be nonblank or null');
		if (typeof appendFile !== 'function') throw new TypeError('appendFile must be a function');
		if (publicSink !== null && typeof publicSink !== 'function') throw new TypeError('publicSink must be a function or null');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		this.#runId = runId;
		this.#scenarioId = scenarioId;
		this.#privatePath = privatePath ?? null;
		this.#publicSink = publicSink;
		this.#appendFile = appendFile;
		this.#now = now;
	}

	record(fields = {}) {
		if (this.#closed) return Promise.reject(new Error('provider turn recorder is closed'));
		const row = normalizeRecord(fields, this.#runId, this.#scenarioId, this.#now());
		const privateRow = privateRecord(row);
		const publicRow = publicRecord(row);
		if (this.#publicSink !== null) {
			try { Promise.resolve(this.#publicSink(publicRow)).catch(() => {}); }
			catch { /* public evidence is observational */ }
		}
		const encoded = `${JSON.stringify(privateRow)}\n`;
		const operation = this.#queue.catch(() => {}).then(async () => {
			if (this.#privatePath !== null) await this.#appendFile(this.#privatePath, encoded, { encoding: 'utf8', flag: 'a' });
		});
		this.#queue = operation;
		return operation;
	}

	async close() {
		this.#closed = true;
		await this.#queue;
	}
}

export async function recordProviderTurn(recorder, fields) {
	if (recorder === null || recorder === undefined) return;
	try { await recorder.record(fields); }
	catch { /* provider capture is observational and cannot affect control flow */ }
}

function normalizeRecord(fields, runId, scenarioId, timestamp) {
	const error = normalizeError(fields.error);
	return {
		runId,
		scenarioId,
		provider: boundedMeta(fields.provider),
		model: boundedMeta(fields.model),
		reasoningEffort: boundedMeta(fields.reasoningEffort),
		goalRevision: boundedInteger(fields.goalRevision),
		attempt: boundedInteger(fields.attempt),
		retry: fields.retry === true,
		timestamp,
		outcome: error === null ? 'success' : 'error',
		input: normalizeText(fields.input),
		output: normalizeText(fields.output),
		...(error === null ? {} : { error }),
	};
}

function privateRecord(row) {
	const result = { ...row };
	result.input = redactAndBound(row.input, MAX_PRIVATE_TEXT_BYTES);
	result.output = redactAndBound(row.output, MAX_PRIVATE_TEXT_BYTES);
	if (row.error !== undefined) result.error = { code: row.error.code, message: redactAndBound(row.error.message, 2_048) };
	return boundRow(result);
}

function publicRecord(row) {
	return {
		runId: row.runId,
		scenarioId: row.scenarioId,
		provider: row.provider,
		model: row.model,
		reasoningEffort: row.reasoningEffort,
		goalRevision: row.goalRevision,
		attempt: row.attempt,
		retry: row.retry,
		timestamp: row.timestamp,
		outcome: row.outcome,
		inputHash: hash(row.input),
		outputHash: hash(row.output),
		inputExcerpt: redactAndBound(row.input, MAX_PUBLIC_EXCERPT_BYTES),
		outputExcerpt: redactAndBound(row.output, MAX_PUBLIC_EXCERPT_BYTES),
		...(row.error === undefined ? {} : { error: { code: row.error.code, message: redactAndBound(row.error.message, MAX_PUBLIC_EXCERPT_BYTES) } }),
	};
}

function normalizeError(value) {
	if (value === null || value === undefined) return null;
	const code = boundedMeta(value?.code ?? 'PROVIDER_ERROR') ?? 'PROVIDER_ERROR';
	const message = normalizeText(value?.message ?? value);
	return { code, message: message.replace(PATH_TEXT, ' [PATH]') };
}

function normalizeText(value) {
	if (value === null || value === undefined) return '';
	return typeof value === 'string' ? value : String(value);
}

function boundedMeta(value) {
	if (value === null || value === undefined) return null;
	return redactAndBound(String(value), 512);
}

function redactAndBound(value, bytes) {
	const redacted = String(value ?? '');
	// Scan quoted JSON values so delimiters, spaces, and escaped quotes cannot terminate redaction early.
	const safelyRedacted = redactQuotedJsonSecrets(redacted)
		.replace(BEARER_TEXT, 'Bearer [REDACTED]')
		.replace(SENSITIVE_TEXT, '$1[REDACTED]')
		.replace(SECRET_SHAPED_TEXT, '$1[REDACTED]');
	return truncateUtf8(safelyRedacted, bytes);
}

function redactQuotedJsonSecrets(value) {
	let result = '';
	let cursor = 0;
	QUOTED_SECRET_KEY.lastIndex = 0;
	let match;
	while ((match = QUOTED_SECRET_KEY.exec(value)) !== null) {
		const valueStart = QUOTED_SECRET_KEY.lastIndex;
		let valueEnd = valueStart;
		while (valueEnd < value.length) {
			if (value[valueEnd] === '\\') {
				valueEnd += 2;
				continue;
			}
			if (value[valueEnd] === match[2]) break;
			valueEnd += 1;
		}
		if (valueEnd >= value.length) break;
		result += value.slice(cursor, valueStart) + '[REDACTED]' + match[2];
		cursor = valueEnd + 1;
		QUOTED_SECRET_KEY.lastIndex = cursor;
	}
	return result + value.slice(cursor);
}

function truncateUtf8(value, bytes) {
	if (Buffer.byteLength(value, 'utf8') <= bytes) return value;
	let end = Math.min(value.length, bytes);
	while (end > 0 && Buffer.byteLength(value.slice(0, end), 'utf8') > bytes) end -= 1;
	return value.slice(0, end);
}

function boundRow(row) {
	let encoded = JSON.stringify(row);
	if (Buffer.byteLength(encoded, 'utf8') <= MAX_ROW_BYTES) return row;
	const bounded = { ...row };
	for (const key of ['input', 'output']) {
		const current = bounded[key];
		bounded[key] = truncateUtf8(current, Math.max(0, MAX_PRIVATE_TEXT_BYTES - 4_096));
		encoded = JSON.stringify(bounded);
		if (Buffer.byteLength(encoded, 'utf8') <= MAX_ROW_BYTES) break;
	}
	return bounded;
}

function hash(value) {
	return `sha256:${createHash('sha256').update(String(value ?? ''), 'utf8').digest('hex')}`;
}

function boundedInteger(value) {
	return Number.isSafeInteger(value) ? value : null;
}
