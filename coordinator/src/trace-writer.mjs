import { createHash } from 'node:crypto';
import { appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

const REDACTED = '[REDACTED]';
const MAX_TRACE_STRING = 2_048;
const MAX_PRIVATE_SOURCE = 65_536;
const MAX_TRACE_ENTRIES = 64;
const SENSITIVE_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|launcherAccount|accountData|token|credential|oauth)/i;

/** Append-only bounded traces. Public rows never contain source text or credentials. */
export class TraceWriter {
	#filePath;
	#diagnosticFilePath;
	#appendFile;
	#mkdir;
	#ready;
	#queue = Promise.resolve();
	#closed = false;

	constructor(filePath, dependencies = {}) {
		if (typeof filePath !== 'string' || filePath.trim().length === 0) throw new TypeError('trace file path must be nonblank');
		this.#filePath = path.resolve(filePath);
		const privatePath = dependencies.diagnosticFilePath ?? dependencies.privateFilePath ?? null;
		if (privatePath !== null && (typeof privatePath !== 'string' || privatePath.trim().length === 0)) throw new TypeError('diagnostic trace file path must be nonblank');
		this.#diagnosticFilePath = privatePath === null ? null : path.resolve(privatePath);
		this.#appendFile = dependencies.appendFile ?? appendFile;
		this.#mkdir = dependencies.mkdir ?? mkdir;
		const directories = [path.dirname(this.#filePath), this.#diagnosticFilePath === null ? null : path.dirname(this.#diagnosticFilePath)].filter(Boolean);
		this.#ready = Promise.all([...new Set(directories)].map((directory) => this.#mkdir(directory, { recursive: true })));
	}

	write(eventOrRow, fields = {}) {
		if (this.#closed) return Promise.reject(new Error('trace writer is closed'));
		const row = normalizeRow(eventOrRow, fields);
		return this.#enqueue(this.#filePath, publicTraceRow(row));
	}

	/** Writes bounded source for the agent-private diagnostic trace only. */
	writeDiagnostic(eventOrRow, fields = {}) {
		if (this.#closed) return Promise.reject(new Error('trace writer is closed'));
		if (this.#diagnosticFilePath === null) return Promise.resolve();
		const row = normalizeRow(eventOrRow, fields);
		return this.#enqueue(this.#diagnosticFilePath, privateTraceRow(row));
	}

	async close() {
		this.#closed = true;
		await this.#queue;
	}

	#enqueue(filePath, row) {
		const encoded = `${JSON.stringify(row)}\n`;
		this.#queue = this.#queue.then(async () => {
			await this.#ready;
			await this.#appendFile(filePath, encoded, { encoding: 'utf8', flag: 'a' });
		});
		return this.#queue;
	}
}

export function observationHash(observation) {
	if (observation === null || observation === undefined) return null;
	return createHash('sha256').update(JSON.stringify(observation), 'utf8').digest('hex');
}

export function redact(value, seen = new WeakSet()) {
	if (typeof value === 'string') return value.replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, `Bearer ${REDACTED}`);
	if (value === null || typeof value !== 'object') return value;
	if (seen.has(value)) return '[CIRCULAR]';
	seen.add(value);
	if (Array.isArray(value)) return value.map((entry) => redact(entry, seen));
	const result = {};
	for (const [key, entry] of Object.entries(value)) result[key] = SENSITIVE_KEY.test(key) ? REDACTED : redact(entry, seen);
	return result;
}

function normalizeRow(eventOrRow, fields) {
	if (typeof eventOrRow === 'string') {
		if (eventOrRow.trim().length === 0) throw new TypeError('trace event must be nonblank');
		if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw new TypeError('trace fields must be an object');
		return { event: eventOrRow, ...fields };
	}
	if (eventOrRow === null || typeof eventOrRow !== 'object' || Array.isArray(eventOrRow)) throw new TypeError('trace row must be an object or event name');
	return { ...eventOrRow };
}

function publicTraceRow(row) {
	const sourceHash = typeof row.source === 'string' ? `sha256:${hashSource(row.source)}` : row.sourceHash ?? null;
	const withoutSource = redact({ ...row });
	delete withoutSource.source;
	if (sourceHash !== null) withoutSource.sourceHash = sourceHash;
	return boundTraceRow({ timestampEpochMs: Date.now(), ...withoutSource }, MAX_TRACE_STRING);
}

function privateTraceRow(row) {
	const sourceHash = typeof row.source === 'string' ? `sha256:${hashSource(row.source)}` : row.sourceHash ?? null;
	return boundTraceRow({ timestampEpochMs: Date.now(), ...redact(row), ...(sourceHash === null ? {} : { sourceHash }) }, MAX_TRACE_STRING, true);
}

function boundTraceRow(value, stringLimit, allowSource = false, seen = new WeakSet(), depth = 0, key = null) {
	if (typeof value === 'string') {
		const limit = allowSource && key === 'source' ? MAX_PRIVATE_SOURCE : stringLimit;
		return value.length <= limit ? value : `${value.slice(0, limit - 3)}...`;
	}
	if (value === null || typeof value !== 'object') return value;
	if (depth > 8) return '[BOUNDED]';
	if (seen.has(value)) return '[CIRCULAR]';
	seen.add(value);
	if (Array.isArray(value)) return value.slice(0, MAX_TRACE_ENTRIES).map((entry) => boundTraceRow(entry, stringLimit, allowSource, seen, depth + 1));
	const result = {};
	for (const [key, entry] of Object.entries(value).slice(0, MAX_TRACE_ENTRIES)) {
		if (SENSITIVE_KEY.test(key)) result[key] = REDACTED;
		else if (key === 'source' && !allowSource) continue;
		else result[key] = boundTraceRow(entry, stringLimit, allowSource, seen, depth + 1, key);
	}
	return result;
}

function hashSource(source) {
	return createHash('sha256').update(source, 'utf8').digest('hex');
}
