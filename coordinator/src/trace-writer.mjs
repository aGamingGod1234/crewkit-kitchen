import { createHash } from 'node:crypto';
import { types as nodeTypes } from 'node:util';
import { appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

const REDACTED = '[REDACTED]';
const UNSAFE = '[UNSAFE_OBJECT]';
const BOUNDED = '[BOUNDED]';
const MAX_TRACE_STRING = 2_048;
const MAX_PRIVATE_SOURCE = 65_536;
const MAX_TRACE_ENTRIES = 64;
const MAX_TRACE_DEPTH = 8;
const MAX_TRACE_NODES = 512;
const MAX_TRACE_BYTES = 262_144;
const SENSITIVE_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|launcherAccount|accountData|token|credential|oauth)/i;
const SENSITIVE_TEXT = /((?:bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)\s*[:=]\s*)([^\s,;)}\]"']+)/gi;

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
	return createHash('sha256').update(JSON.stringify(sanitizeValue(observation, context(false))), 'utf8').digest('hex');
}

/** Returns a safe, null-prototype, own-data-only redacted copy. */
export function redact(value) {
	return sanitizeValue(value, context(false));
}

function normalizeRow(eventOrRow, fields) {
	if (typeof eventOrRow === 'string') {
		if (eventOrRow.trim().length === 0) throw new TypeError('trace event must be nonblank');
		if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw new TypeError('trace fields must be an object');
		return Object.assign(Object.create(null), { event: eventOrRow }, ownData(fields));
	}
	if (eventOrRow === null || typeof eventOrRow !== 'object' || Array.isArray(eventOrRow)) throw new TypeError('trace row must be an object or event name');
	return ownData(eventOrRow);
}

function ownData(value) {
	if (nodeTypes.isProxy(value)) return Object.assign(Object.create(null), { event: UNSAFE });
	const result = Object.create(null);
	let keys;
	try { keys = Reflect.ownKeys(value); } catch { return Object.assign(result, { event: UNSAFE }); }
	for (const key of keys) {
		if (typeof key !== 'string') continue;
		let descriptor;
		try { descriptor = Object.getOwnPropertyDescriptor(value, key); } catch { continue; }
		if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
		result[key] = descriptor.value;
		if (Object.keys(result).length >= MAX_TRACE_ENTRIES) break;
	}
	return result;
}

function publicTraceRow(row) {
	const source = ownData(row).source;
	const sourceHash = typeof source === 'string' ? `sha256:${hashSource(source)}` : null;
	const result = sanitizeValue(row, context(false));
	if (result && typeof result === 'object' && !Array.isArray(result)) {
		delete result.source;
		if (sourceHash !== null) result.sourceHash = sourceHash;
	}
	return result;
}

function privateTraceRow(row) {
	const source = ownData(row).source;
	const sourceHash = typeof source === 'string' ? `sha256:${hashSource(source)}` : null;
	const result = sanitizeValue(row, context(true));
	if (result && typeof result === 'object' && !Array.isArray(result) && sourceHash !== null) result.sourceHash = sourceHash;
	return result;
}

function context(allowSource) {
	return { allowSource, seen: new WeakSet(), nodes: 0, bytes: 0 };
}

function sanitizeValue(value, state, depth = 0, key = null) {
	const root = { value: undefined };
	const work = [{ value, assign: (result) => { root.value = result; }, depth, key }];
	while (work.length > 0) {
		const task = work.pop();
		const input = task.value;
		if (typeof input === 'string') {
			task.assign(sanitizeString(input, state, task.key === 'source' && state.allowSource));
			continue;
		}
		if (input === null || typeof input !== 'object') {
			task.assign(input);
			continue;
		}
		if (task.depth > MAX_TRACE_DEPTH || state.nodes++ >= MAX_TRACE_NODES) {
			task.assign(BOUNDED);
			continue;
		}
		if (nodeTypes.isProxy(input)) {
			task.assign(UNSAFE);
			continue;
		}
		if (state.seen.has(input)) {
			task.assign('[CIRCULAR]');
			continue;
		}
		state.seen.add(input);
		let keys;
		try { keys = Reflect.ownKeys(input); } catch { task.assign(UNSAFE); continue; }
		const output = Array.isArray(input) ? [] : Object.create(null);
		task.assign(output);
		const children = [];
		let entries = 0;
		for (const property of keys) {
			if (typeof property !== 'string' || entries >= MAX_TRACE_ENTRIES) continue;
			let descriptor;
			try { descriptor = Object.getOwnPropertyDescriptor(input, property); } catch { continue; }
			if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
			entries += 1;
			if (SENSITIVE_KEY.test(property)) output[property] = REDACTED;
			else if (property === 'source' && !state.allowSource) continue;
			else children.push({ value: descriptor.value, assign: (result) => { Object.defineProperty(output, property, { enumerable: true, configurable: true, writable: true, value: result }); }, depth: task.depth + 1, key: property });
		}
		for (let index = children.length - 1; index >= 0; index -= 1) {
			work.push(children[index]);
		}
	}
	return root.value;
}

function sanitizeString(value, state, allowLongSource) {
	const redacted = value.replace(SENSITIVE_TEXT, `$1${REDACTED}`).replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, `Bearer ${REDACTED}`);
	const limit = allowLongSource ? MAX_PRIVATE_SOURCE : MAX_TRACE_STRING;
	const remaining = Math.max(0, MAX_TRACE_BYTES - state.bytes);
	const boundedLimit = Math.min(limit, remaining);
	if (boundedLimit <= 3) return '';
	const bounded = truncateUtf8(redacted, boundedLimit);
	const candidate = bounded.length < redacted.length ? `${bounded.slice(0, Math.max(0, bounded.length - 3))}...` : bounded;
	const result = truncateUtf8(candidate, remaining);
	state.bytes += Buffer.byteLength(result, 'utf8');
	return result;
}

function truncateUtf8(value, limit) {
	if (Buffer.byteLength(value, 'utf8') <= limit) return value;
	let end = Math.min(value.length, limit);
	while (end > 0 && Buffer.byteLength(value.slice(0, end), 'utf8') > limit) end -= 1;
	return value.slice(0, end);
}

function hashSource(source) {
	return createHash('sha256').update(source, 'utf8').digest('hex');
}
