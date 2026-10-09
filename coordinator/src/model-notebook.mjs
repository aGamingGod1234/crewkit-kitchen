import { NotebookActionJournal, emptyNotebook } from './notebook-action-journal.mjs';
import { normalizeProviderId, assertProviderServiceTier } from './provider-identity.mjs';
import { MAX_ACTION_ARGUMENT_BYTES, validateAction } from './schema.mjs';
import { isDeepStrictEqual, types as nodeTypes } from 'node:util';
const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const MAX_NOTEBOOK_BYTES = 4_000_000;
const MAX_ACTION_OBSERVATION_BYTES = 16_384;
const RECEIPT_TEXT_LIMITS = { worldId: 256, actionId: 256, actionType: 128, dimension: 128, reasonCode: 128 };
// A serialized string can use six bytes per UTF-16 code unit (JSON escapes).
// Reserve a COMPLETE terminal, including fields omitted by an older dispatch.
const MAX_TERMINAL_BYTES = Buffer.byteLength(JSON.stringify({
	kind: 'receipt', source: 'server_action_result', state: 'TIMED_OUT',
	...Object.fromEntries(Object.entries(RECEIPT_TEXT_LIMITS).map(([key, size]) => [key, '\0'.repeat(size)])),
	goalRevision: Number.MAX_SAFE_INTEGER, tick: Number.MAX_SAFE_INTEGER, revision: Number.MAX_SAFE_INTEGER,
	executionStarted: false, physicalAttempted: false, arguments: {}, actionObservation: {},
})) + MAX_ACTION_ARGUMENT_BYTES - 2 + MAX_ACTION_OBSERVATION_BYTES - 2;
// One comma plus growth of revision/eviction counters. This slot stays below
// AtomicAgentStore's 4 MiB ceiling, including for legacy 4 MB pending snapshots.
const TERMINAL_RESERVE_BYTES = MAX_TERMINAL_BYTES + 1 + 3 * String(Number.MAX_SAFE_INTEGER).length;

/** Model notes and server receipts remain distinct records; neither can assert current world truth. */
export class ModelNotebook {
	#disk; #agents = new Map(); #pending = new Map(); #loads = new Map(); #maximumNotes; #maximumReceipts; #maximumBytes;
	constructor({ directory = null, maximumNotes = 64, maximumReceipts = 128, maximumBytes = 3_500_000 } = {}) {
		for (const value of [maximumNotes, maximumReceipts]) if (!Number.isSafeInteger(value) || value < 1 || value > 1024) throw new TypeError('notebook bounds must be 1..1024');
		if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 4096 || maximumBytes > MAX_NOTEBOOK_BYTES) throw new TypeError('notebook byte budget must be 4096..4000000');
		this.#maximumNotes = maximumNotes; this.#maximumReceipts = maximumReceipts;
		this.#maximumBytes = maximumBytes;
		this.#disk = new NotebookActionJournal({ directory });
	}
	writeNote(agentId, note) {
		const record = { kind: 'note', source: 'model_authored', worldId: text(note.worldId, 'worldId', 256), key: text(note.key, 'key', 128), text: text(note.text, 'text', 2048), ...optionalInteger(note, 'goalRevision'), ...noteProvenance(note) };
		return this.#mutate(agentId, (state) => {
			const index = state.notes.findIndex((entry) => entry.worldId === record.worldId && entry.key === record.key);
			if (index !== -1 && samePayload(state.notes[index], record)) return state.notes[index];
			const next = { ...record, revision: ++state.revision };
			if (index !== -1) state.notes.splice(index, 1);
			state.notes.push(next);
			return next;
		});
	}
	recordReceipt(agentId, receipt) {
		if (!TERMINAL_STATES.has(receipt.state)) throw new TypeError('Authoritative receipts require a terminal server action state');
		return this.#recordAction(agentId, actionRecord(receipt, 'server_action_result', receipt.state, receipt.reasonCode));
	}
	recordDispatch(agentId, receipt) {
		return this.#recordAction(agentId, actionRecord(receipt, 'coordinator_dispatch', 'DISPATCHED', 'AWAITING_AUTHORITATIVE_RESULT'));
	}
	recordUnknown(agentId, receipt) {
		return this.#recordAction(agentId, actionRecord(receipt, 'coordinator_uncertain', 'UNKNOWN', receipt.reasonCode ?? 'AUTHORITATIVE_RESULT_UNKNOWN'));
	}
	async findReceipt(agentId, { actionId, worldId } = {}) {
		actionId = text(actionId, 'actionId', 256);
		if (worldId !== undefined) worldId = text(worldId, 'worldId', 256);
		await this.#pending.get(agentId);
		const state = await this.#load(agentId);
		const matches = [...state.receipts, ...state.recovery].filter((entry) => entry.actionId === actionId && (worldId === undefined || entry.worldId === worldId));
		if (matches.length > 1) throw new Error('RECEIPT_WORLD_REQUIRED');
		return matches.length === 0 ? null : structuredClone(matches[0]);
	}
	async findNote(agentId, { worldId, key } = {}) {
		worldId = text(worldId, 'worldId', 256); key = text(key, 'key', 128);
		await this.#pending.get(agentId);
		const state = await this.#load(agentId);
		return structuredClone(state.notes.find(entry => entry.worldId === worldId && entry.key === key) ?? null);
	}
	listUnresolved(agentId, options) { return this.query(agentId, { ...options, kind: 'unresolved' }); }
	#recordAction(agentId, record) {
		return this.#mutate(agentId, (state) => {
			const index = state.receipts.findIndex((entry) => entry.worldId === record.worldId && entry.actionId === record.actionId);
			const recoveryIndex = state.recovery.findIndex((entry) => entry.worldId === record.worldId && entry.actionId === record.actionId);
			const existing = state.receipts[index] ?? state.recovery[recoveryIndex];
			if (existing) {
				for (const field of ['actionType', 'goalRevision', 'dimension']) if (existing[field] !== undefined && record[field] !== undefined && existing[field] !== record[field]) throw new Error('RECEIPT_CONFLICT');
				if (existing.arguments !== undefined && record.arguments !== undefined && !isDeepStrictEqual(existing.arguments, record.arguments)) throw new Error('RECEIPT_CONFLICT');
				if (existing.source === 'server_action_result') {
					if (record.source !== 'server_action_result') return existing;
					if (existing.state !== record.state || existing.reasonCode !== record.reasonCode) throw new Error('RECEIPT_CONFLICT');
					for (const field of ['executionStarted', 'physicalAttempted', 'actionObservation']) if (existing[field] !== undefined && record[field] !== undefined && !isDeepStrictEqual(existing[field], record[field])) throw new Error('RECEIPT_CONFLICT');
					if (['arguments', 'executionStarted', 'physicalAttempted', 'actionObservation'].every((field) => existing[field] !== undefined || record[field] === undefined)) return existing;
				}
				if (existing.source === 'coordinator_uncertain' && record.source === 'coordinator_dispatch') return existing;
				const { revision: _revision, ...merged } = { ...existing, ...record };
				if (samePayload(existing, merged)) return existing;
			}
			const next = { ...existing, ...record, revision: ++state.revision };
			if (index !== -1) state.receipts.splice(index, 1);
			if (recoveryIndex !== -1) state.recovery.splice(recoveryIndex, 1);
			state.receipts.push(next);
			return next;
		}, { action: true });
	}
	async query(agentId, { worldId, kind = 'all', text: search = '', limit = 20, offset = 0 } = {}) {
		worldId = text(worldId, 'worldId', 256);
		if (!['all', 'note', 'receipt', 'notes', 'receipts', 'unresolved'].includes(kind)) throw new TypeError('kind must be all, note, receipt or unresolved');
		if (typeof search !== 'string' || search.length > 256) throw new TypeError('query text must be at most 256 characters');
		if (!Number.isSafeInteger(limit) || limit < 1 || limit > 64 || !Number.isSafeInteger(offset) || offset < 0) throw new TypeError('invalid query page');
		await this.#pending.get(agentId);
		const state = await this.#load(agentId);
		const normalizedKind = kind.replace(/s$/, '');
		const matchesKind = (entry) => kind === 'all' || (kind === 'unresolved'
			? entry.kind === 'receipt' && entry.source !== 'server_action_result'
			: entry.kind === normalizedKind);
		const searchText = search.toLowerCase();
		const entries = [...state.notes, ...state.receipts, ...(kind === 'unresolved' ? state.recovery : [])].filter((entry) => entry.worldId === worldId && matchesKind(entry) && JSON.stringify(entry).toLowerCase().includes(searchText)).sort((left, right) => right.revision - left.revision);
		return { worldId, revision: state.revision, offset, total: entries.length, entries: structuredClone(entries.slice(offset, offset + limit)), nextOffset: offset + limit < entries.length ? offset + limit : null, evictedReceipts: state.evictedReceipts, evictedNotes: state.evictedNotes };
	}
	clear(agentId, { worldId, key } = {}) {
		worldId = text(worldId, 'worldId', 256);
		if (key !== undefined) key = text(key, 'key', 128);
		return this.#mutate(agentId, (state) => {
			const before = state.notes.length;
			state.notes = state.notes.filter((entry) => entry.worldId !== worldId || key !== undefined && entry.key !== key);
			const removed = before - state.notes.length;
			if (removed > 0) state.revision++;
			return { removed, revision: state.revision };
		});
	}
	#mutate(agentId, operation, { action = false } = {}) {
		const previous = this.#pending.get(agentId) ?? Promise.resolve();
		const pending = previous.catch(() => {}).then(async () => {
			const committed = await this.#load(agentId);
			// Records are immutable; only collection membership and counters change.
			const state = { ...committed, notes: [...committed.notes], receipts: [...committed.receipts], recovery: [...committed.recovery] };
			const result = operation(state);
			// Every accepted mutation advances the revision. Retry-only operations
			// must still run in this queue, but need no bounding or durable rewrite.
			if (state.revision === committed.revision) return structuredClone(result);
			this.#bound(state, result);
			if (action) await this.#disk.append(agentId, committed, state);
			else await this.#disk.checkpoint(agentId, state);
			this.#agents.set(agentId, state);
			return structuredClone(result);
		});
		this.#pending.set(agentId, pending);
		return pending.finally(() => { if (this.#pending.get(agentId) === pending) this.#pending.delete(agentId); });
	}
	async #load(agentId) {
		if (this.#agents.has(agentId)) return this.#agents.get(agentId);
		let pending = this.#loads.get(agentId);
		if (!pending) {
			pending = this.#disk.read(agentId).then(async (saved) => {
				const state = saved === null ? emptyNotebook() : validateSaved(saved);
				const previousEvictions = state.evictedNotes + state.evictedReceipts;
				this.#bound(state);
				// A changed retention setting is a materialized migration, not an
				// unlogged baseline for subsequent deltas.
				if (state.evictedNotes + state.evictedReceipts !== previousEvictions) await this.#disk.checkpoint(agentId, state);
				this.#agents.set(agentId, state);
				return state;
			});
			this.#loads.set(agentId, pending);
		}
		try { return await pending; } finally { this.#loads.delete(agentId); }
	}
	#bound(state, retained = state.receipts.at(-1)?.source === 'server_action_result' ? state.receipts.at(-1) : null) {
		const evictReceipt = receipt => {
			state.receipts.splice(state.receipts.indexOf(receipt), 1); state.evictedReceipts++;
			// History retention must never erase an obligation to reconcile a send.
			if (receipt.source !== 'server_action_result') state.recovery.push(receipt);
		};
		while (state.notes.length > this.#maximumNotes) { state.notes.shift(); state.evictedNotes++; }
		while (state.receipts.length > this.#maximumReceipts) evictReceipt(state.receipts[0]);
		while (serializedBytes(state, false) > this.#maximumBytes) {
			const receipt = state.receipts.find((entry) => entry !== retained);
			const note = state.notes.find((entry) => entry !== retained);
			if (receipt && (!note || receipt.revision <= note.revision)) {
				evictReceipt(receipt);
			} else if (note) {
				state.notes.splice(state.notes.indexOf(note), 1); state.evictedNotes++;
			} else if (retained?.source === 'server_action_result') {
				// An admitted action must remain reconcilable even when its full
				// terminal evidence exceeds the configured history retention budget.
				break;
			} else throw new Error('RECORD_EXCEEDS_NOTEBOOK_BUDGET');
		}
		// Admission and notes cannot consume the reconciliation slot. One slot
		// suffices: mutations are serialized and older terminal history is evictable.
		// Pending records (including legacy states at exactly 4 MB) are never dropped.
		const maximumBytes = MAX_NOTEBOOK_BYTES + (retained === null || retained.source === 'server_action_result' ? TERMINAL_RESERVE_BYTES : 0);
		while (serializedBytes(state, true) > maximumBytes) {
			const receipt = state.receipts.find(entry => entry !== retained && entry.source === 'server_action_result');
			const note = state.notes.find(entry => entry !== retained);
			if (receipt && (!note || receipt.revision <= note.revision)) evictReceipt(receipt);
			else if (note) { state.notes.splice(state.notes.indexOf(note), 1); state.evictedNotes++; }
			else throw recoveryLimit();
		}
		if (state.recovery.length + state.receipts.filter(entry => entry.source !== 'server_action_result').length > 1024) throw recoveryLimit();
	}
}

// Entries are immutable, so each is measured once; a whole-state stringify per mutation is the only other way to know the size.
const ENTRY_BYTES = new WeakMap();
function entryBytes(entry) {
	let bytes = ENTRY_BYTES.get(entry);
	if (bytes === undefined) { bytes = Buffer.byteLength(JSON.stringify(entry), 'utf8'); ENTRY_BYTES.set(entry, bytes); }
	return bytes;
}
/** Exactly Buffer.byteLength(JSON.stringify(state)) (recovery counted only when asked), without serializing the entries again. */
export function serializedBytes(state, includeRecovery) {
	let total = Buffer.byteLength(JSON.stringify({ ...state, notes: [], receipts: [], recovery: [] }), 'utf8');
	for (const list of includeRecovery ? [state.notes, state.receipts, state.recovery] : [state.notes, state.receipts]) {
		if (list.length === 0) continue;
		total += list.length - 1;
		for (const entry of list) total += entryBytes(entry);
	}
	return total;
}
function recoveryLimit() { return Object.assign(new Error('RECEIPT_RECOVERY_LIMIT'), { code: 'RECEIPT_RECOVERY_LIMIT' }); }
function samePayload(existing, next) { const { revision: _revision, ...payload } = existing; return isDeepStrictEqual(payload, next); }
function text(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new TypeError(`${field} must be nonblank text up to ${maximum} characters`);
	return value;
}
function optionalText(source, field, maximum) { return source[field] === undefined ? {} : { [field]: text(source[field], field, maximum) }; }
function optionalInteger(source, field) {
	if (source[field] === undefined) return {};
	if (!Number.isSafeInteger(source[field]) || source[field] < 0) throw new TypeError(`${field} must be a nonnegative integer`);
	return { [field]: source[field] };
}
function actionRecord(receipt, source, state, reasonCode) {
	return {
		kind: 'receipt', source, worldId: text(receipt.worldId, 'worldId', RECEIPT_TEXT_LIMITS.worldId),
		actionId: text(receipt.actionId, 'actionId', RECEIPT_TEXT_LIMITS.actionId), state: text(state, 'state', 64), reasonCode: reasonText(reasonCode),
		...optionalText(receipt, 'actionType', RECEIPT_TEXT_LIMITS.actionType), ...optionalText(receipt, 'dimension', RECEIPT_TEXT_LIMITS.dimension),
		...optionalInteger(receipt, 'goalRevision'), ...optionalInteger(receipt, 'tick'),
		...actionEvidence(receipt, source),
	};
}
function actionEvidence(receipt, source) {
	const evidence = {};
	if (receipt.arguments !== undefined) {
		const args = boundedOwnJson(receipt.arguments, MAX_ACTION_ARGUMENT_BYTES);
		if (args === null || typeof args !== 'object' || Array.isArray(args) || Object.hasOwn(args, 'type')) throw new TypeError('Action arguments must be a record without type');
		const { type: _type, ...validated } = validateAction({ ...args, type: receipt.actionType });
		evidence.arguments = validated;
	}
	for (const field of ['executionStarted', 'physicalAttempted']) if (receipt[field] !== undefined) {
		if (source !== 'server_action_result') throw new TypeError('Only server results carry execution flags');
		if (typeof receipt[field] !== 'boolean') throw new TypeError(`${field} must be a boolean`);
		evidence[field] = receipt[field];
	}
	if (evidence.physicalAttempted === true && evidence.executionStarted !== true) throw new TypeError('physicalAttempted requires executionStarted');
	if (receipt.actionObservation !== undefined) {
		if (source !== 'server_action_result') throw new TypeError('Only server results carry action observations');
		evidence.actionObservation = actionObservation(receipt.actionObservation);
	}
	return evidence;
}
function actionObservation(value) {
	const source = boundedOwnJson(value, MAX_ACTION_OBSERVATION_BYTES);
	if (source === null || typeof source !== 'object' || Array.isArray(source)) throw new TypeError('Action observation must be an object');
	const result = {};
	for (const field of ['worldTick', 'observedAtEpochMs', 'yaw', 'pitch']) if (source[field] !== undefined) {
		if (!Number.isFinite(source[field]) || ['worldTick', 'observedAtEpochMs'].includes(field) && (!Number.isSafeInteger(source[field]) || source[field] < 0)) throw new TypeError(`Invalid action observation ${field}`);
		result[field] = source[field];
	}
	const shapes = {
		position: { x: 'number', y: 'number', z: 'number' }, velocity: { x: 'number', y: 'number', z: 'number' },
		collision: { horizontal: 'boolean', vertical: 'boolean', inWall: 'boolean' },
		lookedAt: { type: 'string', id: 'string', face: 'string', hitDistance: 'number', position: 'vector' },
		reach: { distance: 'number', max: 'number', within: 'boolean' },
		target: { kind: 'string', position: 'vector', expectedId: 'string', currentId: 'string', beforeId: 'string', afterId: 'string', worldChanged: 'boolean', distanceRemaining: 'number', tolerance: 'number', standable: 'boolean' },
		progress: { value: 'number', basis: 'string', verified: 'boolean' },
	};
	for (const [field, shape] of Object.entries(shapes)) if (source[field] !== undefined) result[field] = projectEvidence(source[field], shape);
	return result;
}
function projectEvidence(source, shape) {
	if (source === null || typeof source !== 'object' || Array.isArray(source)) throw new TypeError('Action evidence must be an object');
	const result = {};
	for (const [field, type] of Object.entries(shape)) if (source[field] !== undefined) {
		if (type === 'vector') { result[field] = projectEvidence(source[field], { x: 'number', y: 'number', z: 'number' }); continue; }
		if (typeof source[field] !== type || type === 'number' && !Number.isFinite(source[field]) || type === 'string' && source[field].length > 256) throw new TypeError(`Invalid action evidence ${field}`);
		result[field] = source[field];
	}
	return result;
}
function boundedOwnJson(value, maximumBytes) {
	const visit = (item, depth) => {
		if (depth > 12) throw new TypeError('Receipt data is too deeply nested');
		if (item === null || typeof item === 'string' || typeof item === 'boolean' || typeof item === 'number' && Number.isFinite(item)) return;
		if (typeof item !== 'object' || nodeTypes.isProxy(item) || ![Object.prototype, Array.prototype, null].includes(Object.getPrototypeOf(item))) throw new TypeError('Receipt data must contain only JSON values');
		for (const key of Reflect.ownKeys(item)) {
			if (Array.isArray(item) && key === 'length') continue;
			const descriptor = Object.getOwnPropertyDescriptor(item, key);
			if (typeof key !== 'string' || ['__proto__', 'prototype', 'constructor'].includes(key) || !descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) throw new TypeError('Receipt data must contain only own data');
			visit(descriptor.value, depth + 1);
		}
	};
	visit(value, 0);
	const encoded = JSON.stringify(value);
	if (Buffer.byteLength(encoded, 'utf8') > maximumBytes) throw new TypeError('Receipt data exceeds byte limit');
	return JSON.parse(encoded);
}
function reasonText(value) {
	if (typeof value !== 'string' || value.length > RECEIPT_TEXT_LIMITS.reasonCode) throw new TypeError('reasonCode must be text up to 128 characters');
	return value;
}
function noteProvenance(note) {
	if (note.provenance === undefined) return {};
	const source = boundedOwnJson(note.provenance, 8192);
	const required = ['provider', 'model', 'reasoningEffort', 'serviceTier', 'goalRevision'];
	const allowed = [...required, 'programId', 'programVersion', 'sourceStepId', 'turnId', 'callId'];
	if (source === null || typeof source !== 'object' || Array.isArray(source) || Object.keys(source).some((field) => !allowed.includes(field)) || required.some((field) => !Object.hasOwn(source, field))) throw new TypeError('Invalid note provenance fields');
	const provider = normalizeProviderId(source.provider);
	const provenance = { provider, model: text(source.model, 'model', 256), reasoningEffort: text(source.reasoningEffort, 'reasoningEffort', 64), serviceTier: assertProviderServiceTier(provider, source.serviceTier), ...optionalInteger(source, 'goalRevision') };
	if (note.goalRevision !== undefined && source.goalRevision !== note.goalRevision) throw new TypeError('Note provenance goalRevision must match the note');
	for (const field of ['programId', 'sourceStepId', 'turnId', 'callId']) Object.assign(provenance, optionalText(source, field, 256));
	Object.assign(provenance, optionalInteger(source, 'programVersion'));
	return { provenance };
}
function validateSaved(saved) {
	if (saved.version !== 1 || !Number.isSafeInteger(saved.revision) || saved.revision < 0 || !Array.isArray(saved.notes) || !Array.isArray(saved.receipts)) throw new Error('INVALID_NOTEBOOK');
	const notes = saved.notes.map((note) => ({ kind: 'note', source: 'model_authored', worldId: text(note.worldId, 'worldId', 256), key: text(note.key, 'key', 128), text: text(note.text, 'text', 2048), ...optionalInteger(note, 'goalRevision'), ...noteProvenance(note), ...optionalInteger(note, 'revision') }));
	const validateReceipt = (receipt) => {
		const valid = receipt.source === 'server_action_result' && TERMINAL_STATES.has(receipt.state)
			|| receipt.source === 'coordinator_dispatch' && receipt.state === 'DISPATCHED'
			|| receipt.source === 'coordinator_uncertain' && receipt.state === 'UNKNOWN';
		if (!valid) throw new Error('INVALID_NOTEBOOK_RECEIPT');
		return { ...actionRecord(receipt, receipt.source, receipt.state, receipt.reasonCode), ...optionalInteger(receipt, 'revision') };
	};
	const receipts = saved.receipts.map(validateReceipt);
	if (saved.recovery !== undefined && !Array.isArray(saved.recovery)) throw new Error('INVALID_NOTEBOOK');
	const recovery = (saved.recovery ?? []).map(validateReceipt);
	if (recovery.some(entry => entry.source === 'server_action_result')) throw new Error('INVALID_NOTEBOOK_RECOVERY');
	return { version: 1, revision: saved.revision, notes, receipts, recovery, evictedReceipts: Number.isSafeInteger(saved.evictedReceipts) && saved.evictedReceipts >= 0 ? saved.evictedReceipts : 0, evictedNotes: Number.isSafeInteger(saved.evictedNotes) && saved.evictedNotes >= 0 ? saved.evictedNotes : 0 };
}
