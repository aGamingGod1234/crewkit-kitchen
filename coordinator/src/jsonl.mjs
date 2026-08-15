import { TextDecoder } from 'node:util';

import { MAX_LINE_BYTES } from './constants.mjs';

const NEWLINE = 0x0a;
const CARRIAGE_RETURN = 0x0d;

export class JsonlError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'JsonlError';
		this.code = code;
	}
}

export class JsonlDecoder {
	#buffer = Buffer.alloc(0);
	#maxBytes;

	constructor({ maxBytes = MAX_LINE_BYTES } = {}) {
		if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
			throw new TypeError('maxBytes must be a positive safe integer');
		}
		this.#maxBytes = maxBytes;
	}

	push(chunk) {
		if (typeof chunk !== 'string' && !ArrayBuffer.isView(chunk)) {
			throw new TypeError('JSONL chunk must be a string or byte view');
		}
		const bytes = typeof chunk === 'string' ? Buffer.from(chunk, 'utf8') : Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
		this.#buffer = this.#buffer.length === 0 ? Buffer.from(bytes) : Buffer.concat([this.#buffer, bytes]);
		const messages = [];
		while (true) {
			const newlineIndex = this.#buffer.indexOf(NEWLINE);
			if (newlineIndex === -1) break;
			if (newlineIndex > this.#maxBytes) this.#throwOversized(newlineIndex);
			let line = this.#buffer.subarray(0, newlineIndex);
			this.#buffer = this.#buffer.subarray(newlineIndex + 1);
			if (line.at(-1) === CARRIAGE_RETURN) line = line.subarray(0, -1);
			messages.push(parseObject(line));
		}
		if (this.#buffer.length > this.#maxBytes) this.#throwOversized(this.#buffer.length);
		return messages;
	}

	finish() {
		if (this.#buffer.length !== 0) {
			throw new JsonlError('INCOMPLETE_FRAME', 'Incomplete JSONL frame at end of stream');
		}
	}

	#throwOversized(actualBytes) {
		this.#buffer = Buffer.alloc(0);
		throw new JsonlError('LINE_TOO_LARGE', `JSONL frame is ${actualBytes} UTF-8 bytes and exceeds ${this.#maxBytes} UTF-8 bytes`);
	}
}

export function encodeJsonLine(value, { maxBytes = MAX_LINE_BYTES } = {}) {
	if (!isPlainObject(value)) throw new JsonlError('INVALID_FRAME', 'JSONL value must be an object');
	let encoded;
	try {
		encoded = JSON.stringify(value);
	} catch (error) {
		throw new JsonlError('ENCODING_FAILED', `Could not encode JSONL object: ${error.message}`, { cause: error });
	}
	if (encoded === undefined) throw new JsonlError('ENCODING_FAILED', 'Could not encode JSONL object');
	const bytes = Buffer.byteLength(encoded, 'utf8');
	if (bytes > maxBytes) throw new JsonlError('LINE_TOO_LARGE', `JSONL frame is ${bytes} UTF-8 bytes and exceeds ${maxBytes} UTF-8 bytes`);
	return `${encoded}\n`;
}

function parseObject(bytes) {
	if (bytes.length === 0) throw new JsonlError('MALFORMED_JSON', 'JSONL frame must not be blank');
	let text;
	try {
		text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
	} catch (error) {
		throw new JsonlError('INVALID_ENCODING', 'JSONL frame must be valid UTF-8', { cause: error });
	}
	let value;
	try {
		assertNoDuplicateKeys(text);
		value = JSON.parse(text);
	} catch (error) {
		throw new JsonlError('MALFORMED_JSON', `Malformed JSONL frame: ${error.message}`, { cause: error });
	}
	if (!isPlainObject(value)) throw new JsonlError('INVALID_FRAME', 'JSONL frame must be an object');
	return value;
}

function isPlainObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}

// JSON.parse deliberately accepts duplicate names by retaining the last one. The
// bridge treats those as ambiguous identities, so scan the already-valid JSON
// grammar before materialising the object.
function assertNoDuplicateKeys(text) {
	let index = 0;
	const whitespace = () => { while (/\s/u.test(text[index] ?? '')) index += 1; };
	const string = () => {
		const start = index;
		if (text[index++] !== '"') throw new SyntaxError('Expected JSON string');
		let escaped = false;
		while (index < text.length) {
			const character = text[index++];
			if (escaped) { escaped = false; continue; }
			if (character === '\\') { escaped = true; continue; }
			if (character === '"') return JSON.parse(text.slice(start, index));
		}
		throw new SyntaxError('Unterminated JSON string');
	};
	const literal = () => {
		while (index < text.length && !/[\s,\]}]/u.test(text[index])) index += 1;
	};
	const value = () => {
		whitespace();
		if (text[index] === '{') {
			index += 1;
			const names = new Set();
			whitespace();
			if (text[index] === '}') { index += 1; return; }
			while (true) {
				whitespace();
				const name = string();
				if (names.has(name)) throw new SyntaxError(`Duplicate JSON object key '${name}'`);
				names.add(name);
				whitespace();
				if (text[index++] !== ':') throw new SyntaxError('Expected colon');
				value();
				whitespace();
				if (text[index] === '}') { index += 1; return; }
				if (text[index++] !== ',') throw new SyntaxError('Expected comma');
			}
		}
		if (text[index] === '[') {
			index += 1;
			whitespace();
			if (text[index] === ']') { index += 1; return; }
			while (true) {
				value();
				whitespace();
				if (text[index] === ']') { index += 1; return; }
				if (text[index++] !== ',') throw new SyntaxError('Expected comma');
			}
		}
		if (text[index] === '"') { string(); return; }
		literal();
	};
	value();
	whitespace();
	if (index !== text.length) throw new SyntaxError('Unexpected trailing JSON content');
}
