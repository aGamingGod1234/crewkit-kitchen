import { createHash } from 'node:crypto';
import { appendFile as defaultAppendFile } from 'node:fs/promises';

import { BestEffortDiagnosticQueue } from './best-effort-diagnostic-queue.mjs';
import { sanitizeDiagnosticText, truncateDiagnosticUtf8 } from './diagnostic-sanitizer.mjs';
import { preparePrivateArtifact } from './private-artifact-permissions.mjs';
import { RotatingJsonlSink } from './rotating-jsonl-sink.mjs';
import { normalizeToolResponseSummary } from './tool-response-summary.mjs';

const MAX_PRIVATE_TEXT_BYTES = 65_536;
const MAX_PUBLIC_EXCERPT_BYTES = 512;
const MAX_ROW_BYTES = 262_143;

/** Bounded, serialized provider-turn capture with private source and public evidence. */
export class ProviderTurnRecorder {
	#runId;
	#scenarioId;
	#privatePath;
	#publicSink;
	#sink;
	#preparePrivateArtifact;
	#ready;
	#now;
	#queue;
	#closed = false;
	#closePromise = null;

	constructor({ runId, scenarioId, privatePath, publicSink = null, appendFile = defaultAppendFile, preparePrivateArtifact: prepareArtifact = null, now = Date.now, rotation = {}, ...queueOptions } = {}) {
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
		this.#sink = this.#privatePath === null ? null : new RotatingJsonlSink(this.#privatePath, {
			appendFile, now, inspect: appendFile === defaultAppendFile || Object.keys(rotation).length > 0, ...rotation,
		});
		this.#preparePrivateArtifact = prepareArtifact ?? (appendFile === defaultAppendFile ? preparePrivateArtifact : async () => {});
		this.#now = now;
		this.#ready = this.#privatePath === null ? Promise.resolve(true)
			: Promise.resolve().then(() => this.#preparePrivateArtifact(this.#privatePath)).then(() => true, () => {
				this.#queue.reportFailure();
				return false;
			});
		this.#queue = new BestEffortDiagnosticQueue({ ...queueOptions, ready: this.#ready });
	}

	record(fields = {}) {
		if (this.#closed) return Promise.resolve();
		try {
			const row = normalizeRecord(fields, this.#runId, this.#scenarioId, this.#now());
			const privateRow = privateRecord(row);
			const publicRow = this.#publicSink === null ? null : publicRecord(row);
			const encoded = `${JSON.stringify(privateRow)}\n`;
			this.#queue.submit(async () => {
				const writes = [];
				if (this.#publicSink !== null) {
					writes.push(Promise.resolve().then(() => this.#publicSink(publicRow)));
				}
				if (this.#privatePath !== null) {
					writes.push((async () => {
						if (!await this.#ready) throw new Error('provider audit artifact is unavailable');
						await this.#sink.append(encoded, { encoding: 'utf8', flag: 'a', mode: 0o600 });
					})());
				}
				// Attempt both sinks, but do not turn missing evidence into queue success.
				const results = await Promise.allSettled(writes);
				if (results.some((result) => result.status === 'rejected')) throw new Error('provider audit write failed');
			});
		} catch {
			// Normalization lost a record too. Do not inspect the hostile input/error
			// again while reporting that capture is incomplete.
			this.#queue.reportFailure();
		}
		return Promise.resolve();
	}

	close() {
		if (this.#closePromise !== null) return this.#closePromise;
		this.#closed = true;
		this.#closePromise = this.#queue.close();
		return this.#closePromise;
	}

	statusSnapshot() {
		return Object.freeze({ ...this.#queue.statusSnapshot('provider_audit'), droppedCount: this.#queue.droppedCount });
	}
}

export function recordProviderTurn(recorder, fields) {
	if (recorder === null || recorder === undefined) return;
	try { void Promise.resolve(recorder.record(fields)).catch(() => {}); }
	catch { /* provider capture is observational and cannot affect control flow */ }
}

function normalizeRecord(fields, runId, scenarioId, timestamp) {
	const error = normalizeError(fields.error);
	return {
		runId,
		scenarioId,
		...(fields.agentId === null || fields.agentId === undefined ? {} : { agentId: boundedMeta(fields.agentId) }),
		provider: boundedMeta(fields.provider),
		model: boundedMeta(fields.model),
		reasoningEffort: boundedMeta(fields.reasoningEffort),
		...(fields.executionSettings == null ? {} : { executionSettings: normalizeExecutionSettings(fields.executionSettings) }),
		...normalizeNativeTurnEvidence(fields),
		goalRevision: boundedInteger(fields.goalRevision),
		attempt: boundedInteger(fields.attempt),
		retry: fields.retry === true,
		timestamp,
		outcome: error === null ? 'success' : 'error',
		...(fields.timing === null || fields.timing === undefined ? {} : { timing: normalizeTiming(fields.timing) }),
		...(fields.tokens === null || fields.tokens === undefined ? {} : { tokens: normalizeTokens(fields.tokens) }),
		...(fields.rateLimited === undefined ? {} : { rateLimited: fields.rateLimited === true }),
		...(fields.compaction === undefined ? {} : { compaction: fields.compaction === true }),
		input: normalizeText(fields.input),
		output: normalizeText(fields.output),
		...(error === null ? {} : { error }),
	};
}

function privateRecord(row) {
	const result = { ...row };
	result.input = redactAndBound(row.input, MAX_PRIVATE_TEXT_BYTES);
	result.output = redactAndBound(row.output, MAX_PRIVATE_TEXT_BYTES);
	return boundRow(result);
}

function publicRecord(row) {
	return {
		runId: row.runId,
		scenarioId: row.scenarioId,
		...(row.agentId === undefined ? {} : { agentId: row.agentId }),
		provider: row.provider,
		model: row.model,
		reasoningEffort: row.reasoningEffort,
		...(row.executionSettings === undefined ? {} : { executionSettings: row.executionSettings }),
		...normalizeNativeTurnEvidence(row),
		goalRevision: row.goalRevision,
		attempt: row.attempt,
		retry: row.retry,
		timestamp: row.timestamp,
		outcome: row.outcome,
		...(row.timing === undefined ? {} : { timing: row.timing }),
		...(row.tokens === undefined ? {} : { tokens: row.tokens }),
		...(row.rateLimited === undefined ? {} : { rateLimited: row.rateLimited }),
		...(row.compaction === undefined ? {} : { compaction: row.compaction }),
		inputHash: hash(row.input),
		outputHash: hash(row.output),
		inputExcerpt: redactAndBound(row.input, MAX_PUBLIC_EXCERPT_BYTES),
		outputExcerpt: redactAndBound(row.output, MAX_PUBLIC_EXCERPT_BYTES),
		...(row.error === undefined ? {} : { error: row.error }),
	};
}

function normalizeExecutionSettings(value) {
	if (typeof value !== 'object' || Array.isArray(value)) throw new TypeError('executionSettings must be an object');
	const fields = ['provider', 'model', 'reasoningEffort', 'serviceTier'];
	const pick = (source, keys) => Object.fromEntries(keys.map((key) => [key, boundedMeta(source?.[key])]));
	return {
		requested: pick(value.requested, fields), effective: pick(value.effective, [...fields, 'thinkingMode']),
		...pick(value, ['transport', 'controlProtocol', 'modelSelector']),
		evidence: pick(value.evidence, ['model', 'reasoningEffort', 'serviceTier']),
		limitations: Array.isArray(value.limitations) ? value.limitations.slice(0, 16).map(boundedMeta) : [],
		...(value.limits == null ? {} : { limits: Object.fromEntries(['planningLeaseTimeoutMs', 'nativeTurnBudgetMs']
			.filter((key) => Number.isSafeInteger(value.limits[key]) && value.limits[key] > 0).map((key) => [key, value.limits[key]])) }),
	};
}

function normalizeTiming(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('provider turn timing must be an object');
	const durationMs = boundedDuration(value.durationMs, 'durationMs');
	const apiDurationMs = value.apiDurationMs === null || value.apiDurationMs === undefined ? null : boundedDuration(value.apiDurationMs, 'apiDurationMs');
	const queueWaitMs = value.queueWaitMs === null || value.queueWaitMs === undefined ? undefined : boundedDuration(value.queueWaitMs, 'queueWaitMs');
	return { durationMs, apiDurationMs, ...(queueWaitMs === undefined ? {} : { queueWaitMs }) };
}

const TOKEN_CATEGORIES = ['input', 'output', 'reasoning', 'cached', 'cacheWrite'];

function normalizeNativeTurnEvidence(fields) {
	const result = {};
	if (fields.toolResponses !== undefined) result.toolResponses = normalizeToolResponseSummary(fields.toolResponses);
	for (const key of ['traceId', 'threadId', 'turnId']) {
		if (fields[key] !== undefined) result[key] = boundedMeta(fields[key]);
	}
	for (const key of ['sessionGeneration', 'toolCalls', 'toolResultBytes', 'inputBytes', 'inputCount']) {
		if (fields[key] !== undefined) result[key] = Number.isSafeInteger(fields[key]) && fields[key] >= 0 ? fields[key] : null;
	}
	if (fields.usage != null) {
		const usage = fields.usage;
		result.usage = {
			scope: usage.scope === 'observed_thread_counter_delta' ? usage.scope : null,
			status: ['available', 'missing', 'baseline_unknown', 'counter_reset'].includes(usage.status) ? usage.status : null,
			start: usage.start == null ? null : normalizeTokens(usage.start),
			end: usage.end == null ? null : normalizeTokens(usage.end),
			gapBefore: usage.gapBefore == null ? null : normalizeTokens(usage.gapBefore),
			attributionComplete: typeof usage.attributionComplete === 'boolean' ? usage.attributionComplete : null,
			updates: Number.isSafeInteger(usage.updates) && usage.updates >= 0 ? usage.updates : null,
			counterReset: usage.counterReset === true,
		};
	}
	return result;
}

function normalizeTokens(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('provider turn tokens must be an object');
	const result = {};
	for (const category of TOKEN_CATEGORIES) {
		const count = value[category];
		if (count === null || count === undefined) result[category] = null;
		else if (!Number.isSafeInteger(count) || count < 0) throw new TypeError(`${category} tokens must be a nonnegative safe integer or null`);
		else result[category] = count;
	}
	return result;
}

function boundedDuration(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new TypeError(`${field} must be a nonnegative finite number`);
	return Math.round(value);
}

function normalizeError(value) {
	if (value === null || value === undefined) return null;
	const code = typeof value?.code === 'string' && /^[A-Z0-9_]{1,128}$/.test(value.code) ? value.code : 'PROVIDER_ERROR';
	const category = ['decision_parse', 'rate_limit', 'timeout', 'cancelled', 'transport', 'provider'].includes(value?.category)
		? value.category : providerErrorCategory(code);
	return { code, category };
}

function providerErrorCategory(code) {
	if (/DECISION|PLANNER_OUTPUT/.test(code)) return 'decision_parse';
	if (/RATE|LIMIT/.test(code)) return 'rate_limit';
	if (/TIMEOUT/.test(code)) return 'timeout';
	if (/CANCEL|STALE/.test(code)) return 'cancelled';
	if (/RPC|TRANSPORT|PROCESS|SPAWN/.test(code)) return 'transport';
	return 'provider';
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
	return sanitizeDiagnosticText(value, { maxBytes: bytes });
}

function truncateUtf8(value, bytes) {
	return truncateDiagnosticUtf8(value, bytes);
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
