import { createHash } from 'node:crypto';
import { appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

const REDACTED = '[REDACTED]';
const SENSITIVE_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|launcherAccount|accountData)/i;

export class TraceWriter {
	#filePath;
	#appendFile;
	#mkdir;
	#ready;
	#queue = Promise.resolve();
	#closed = false;

	constructor(filePath, dependencies = {}) {
		if (typeof filePath !== 'string' || filePath.trim().length === 0) throw new TypeError('trace file path must be nonblank');
		this.#filePath = path.resolve(filePath);
		this.#appendFile = dependencies.appendFile ?? appendFile;
		this.#mkdir = dependencies.mkdir ?? mkdir;
		this.#ready = this.#mkdir(path.dirname(this.#filePath), { recursive: true });
	}

	write(row) {
		if (this.#closed) return Promise.reject(new Error('trace writer is closed'));
		const encoded = `${JSON.stringify(redact(row))}\n`;
		this.#queue = this.#queue.then(async () => {
			await this.#ready;
			await this.#appendFile(this.#filePath, encoded, { encoding: 'utf8', flag: 'a' });
		});
		return this.#queue;
	}

	async close() {
		this.#closed = true;
		await this.#queue;
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
