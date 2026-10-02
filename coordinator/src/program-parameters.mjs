import { types as nodeTypes } from 'node:util';

export const MAX_PROGRAM_PARAMETER_BYTES = 4096;
export const PROGRAM_PARAMETER_LIMITS = Object.freeze({ bytes: MAX_PROGRAM_PARAMETER_BYTES, depth: 16, entries: 256 });
const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype', '__defineGetter__', '__defineSetter__', '__lookupGetter__', '__lookupSetter__']);
const ownedParameters = new WeakSet();
const EMPTY_PARAMETERS = Object.freeze(Object.create(null));
ownedParameters.add(EMPTY_PARAMETERS);

/** Accept data at a public boundary once, then reuse its detached immutable ownership. */
export function validateProgramParameters(value = EMPTY_PARAMETERS) {
	if (ownedParameters.has(value)) return value;
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) throw invalid('parameters must be a plain JSON object');
	const state = { bytes: 0, entries: 0, ancestors: new Set() };
	const output = cloneJson(value, state, 0);
	ownedParameters.add(output);
	return output;
}

export const normalizeProgramParameters = validateProgramParameters;

function cloneJson(value, state, depth) {
	if (depth > PROGRAM_PARAMETER_LIMITS.depth) throw invalid(`parameters exceed depth ${PROGRAM_PARAMETER_LIMITS.depth}`);
	if (value === null || typeof value === 'boolean' || typeof value === 'string' || typeof value === 'number') {
		if (typeof value === 'number' && !Number.isFinite(value)) throw invalid('parameters require finite JSON numbers');
		if (typeof value === 'string' && Buffer.byteLength(value, 'utf8') > PROGRAM_PARAMETER_LIMITS.bytes) throw invalid('parameters exceed 4096 UTF-8 bytes');
		addBytes(state, Buffer.byteLength(JSON.stringify(value), 'utf8'));
		return value;
	}
	if (typeof value !== 'object' || nodeTypes.isProxy(value) || state.ancestors.has(value)) throw invalid('parameters require acyclic JSON data without proxies');
	const array = Array.isArray(value);
	const prototype = Object.getPrototypeOf(value);
	if (array ? prototype !== Array.prototype : prototype !== null && prototype !== Object.prototype) throw invalid('parameters require plain objects and arrays');
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key !== 'string')) throw invalid('parameters may not contain symbol keys');
	const length = array ? Object.getOwnPropertyDescriptor(value, 'length').value : keys.length;
	if (array && keys.length !== length + 1) throw invalid('parameter arrays must contain only dense JSON entries');
	state.entries += length;
	if (state.entries > PROGRAM_PARAMETER_LIMITS.entries) throw invalid(`parameters exceed ${PROGRAM_PARAMETER_LIMITS.entries} entries`);
	addBytes(state, 2 + Math.max(0, length - 1));
	const output = array ? [] : Object.create(null);
	state.ancestors.add(value);
	for (let index = 0; index < length; index += 1) {
		const key = array ? String(index) : keys[index];
		if (FORBIDDEN_KEYS.has(key)) throw invalid('parameters may not contain prototype-related keys');
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !Object.hasOwn(descriptor, 'value') || !descriptor.enumerable) throw invalid('parameters require enumerable own data properties');
		if (!array) {
			if (Buffer.byteLength(key, 'utf8') > PROGRAM_PARAMETER_LIMITS.bytes) throw invalid('parameters exceed 4096 UTF-8 bytes');
			addBytes(state, Buffer.byteLength(JSON.stringify(key), 'utf8') + 1);
		}
		output[key] = cloneJson(descriptor.value, state, depth + 1);
	}
	state.ancestors.delete(value);
	return Object.freeze(output);
}

function addBytes(state, bytes) {
	state.bytes += bytes;
	if (state.bytes > PROGRAM_PARAMETER_LIMITS.bytes) throw invalid('parameters exceed 4096 UTF-8 bytes');
}
function invalid(message) { return Object.assign(new TypeError(message), { code: 'INVALID_PROGRAM_PARAMETERS' }); }
