import { createHash } from 'node:crypto';
import { MINECRAFT_DYNAMIC_TOOLS } from './native-minecraft-tools.mjs';

const TOOLS = new Set(MINECRAFT_DYNAMIC_TOOLS.map(tool => tool.name));
const FAMILIES = new Set(['observation', 'sequence', 'action', 'action_post_observation', 'program', 'camera', 'inspection', 'memory', 'other', 'error']);
const PROGRAM_KINDS = new Set(['run_program', 'queue_program', 'cancel_queued_program', 'program_status', 'cancel_program', 'respond_program']);
const COUNTERS = ['attempts', 'accepted', 'failed', 'pending', 'attemptedBytes', 'acceptedBytes', 'failedBytes', 'pendingBytes', 'executionSamples', 'respondSamples'];
const digest = value => `sha256:${createHash('sha256').update(value, 'utf8').digest('hex')}`;
const chain = (previous, value) => digest(`${previous ?? ''}\n${value}`);
const measured = value => Number.isFinite(value) && value >= 0;

function familyOf(kind, hasPostAction, success) {
	if (success === false) return 'error';
	if (kind === 'sequence') return 'sequence';
	if (kind === 'observe') return 'observation';
	if (['action', 'start_action', 'replace_action', 'action_status', 'cancel_action'].includes(kind)) return hasPostAction === true ? 'action_post_observation' : 'action';
	if (PROGRAM_KINDS.has(kind)) return 'program';
	if (kind === 'lookAround') return 'camera';
	if (kind === 'inspect') return 'inspection';
	if (['task_memory', 'task_plan', 'notebook', 'query_memory'].includes(kind)) return 'memory';
	return 'other';
}

/** Per-turn, fixed-vocabulary aggregates. Supply JSON of the presented response,
 * excluding its RPC envelope. No raw objects are serialized or retained here.
 * Accepted means respond returned/resolved, never provider receipt or billing.
 * Timings are sums of known samples; zero samples means unknown, not zero time.
 * Hash chains bind attempt/completion order within each tool/family, not duplicates.
 */
export class ToolResponseSummary {
	#rows = new Map();
	#captureFailures = 0;

	begin(fields) {
		try {
			const { name, kind, hasPostAction, success, serializedResponse, executionMs } = fields;
			if (typeof serializedResponse !== 'string') throw new TypeError('serialized response must be a string');
			const bytes = Buffer.byteLength(serializedResponse, 'utf8');
			const hash = digest(serializedResponse);
			const tool = TOOLS.has(name) ? name : 'unknown';
			const family = familyOf(kind, hasPostAction, success);
			const key = `${tool}:${family}`;
			const previous = this.#rows.get(key) ?? { tool, family,
				...Object.fromEntries(COUNTERS.map(field => [field, 0])), executionMs: 0, respondMs: 0,
				responseHashChain: null, completionHashChain: null };
			const row = { ...previous, attempts: previous.attempts + 1, pending: previous.pending + 1,
				attemptedBytes: previous.attemptedBytes + bytes, pendingBytes: previous.pendingBytes + bytes,
				executionMs: previous.executionMs + (measured(executionMs) ? executionMs : 0),
				executionSamples: previous.executionSamples + (measured(executionMs) ? 1 : 0),
				responseHashChain: chain(previous.responseHashChain, hash) };
			validateTotals(row);
			this.#rows.set(key, row);
			// The completion closure captures only measured primitives, never payloads.
			return this.#completion(key, bytes, hash);
		} catch { this.captureFailure(); return null; }
	}

	#completion(key, bytes, hash) {
		let completed = false;
		return Object.freeze({ bytes, finish: (accepted, respondMs) => {
			if (completed) return;
			try {
				if (typeof accepted !== 'boolean') throw new TypeError('delivery outcome must be boolean');
				const status = accepted ? 'accepted' : 'failed';
				const previous = this.#rows.get(key);
				const row = { ...previous, pending: previous.pending - 1, pendingBytes: previous.pendingBytes - bytes,
					[status]: previous[status] + 1, [`${status}Bytes`]: previous[`${status}Bytes`] + bytes,
					respondMs: previous.respondMs + (measured(respondMs) ? respondMs : 0),
					respondSamples: previous.respondSamples + (measured(respondMs) ? 1 : 0),
					completionHashChain: chain(previous.completionHashChain, `${status}:${hash}`) };
				validateTotals(row);
				this.#rows.set(key, row);
				completed = true;
			} catch { this.captureFailure(); }
		} });
	}

	captureFailure() { this.#captureFailures += 1; }

	snapshot() {
		return { version: 1, scope: 'serialized_tool_response_transport_boundary', receiptAcknowledged: false,
			captureFailures: this.#captureFailures, rows: [...this.#rows.values()].map(row => ({ ...row })) };
	}
}

function counter(value) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError('invalid tool summary counter');
	return value;
}

function hash(value) {
	if (value !== null && (typeof value !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value))) throw new TypeError('invalid tool summary hash');
	return value;
}

function validateTotals(row) {
	for (const key of COUNTERS) counter(row[key]);
	if (row.attempts !== row.accepted + row.failed + row.pending || row.attemptedBytes !== row.acceptedBytes + row.failedBytes + row.pendingBytes
		|| row.executionSamples > row.attempts || row.respondSamples > row.accepted + row.failed) throw new TypeError('inconsistent tool summary totals');
	for (const [timing, samples] of [['executionMs', 'executionSamples'], ['respondMs', 'respondSamples']]) {
		if (!measured(row[timing]) || row[samples] === 0 && row[timing] !== 0) throw new TypeError('invalid tool summary timing');
	}
	for (const status of ['accepted', 'failed', 'pending']) {
		if (row[status] === 0 && row[`${status}Bytes`] !== 0) throw new TypeError('inconsistent tool summary bytes');
	}
}

/** Rebuild the allowlist before both public and private persistence. */
export function normalizeToolResponseSummary(value) {
	const { version, scope, rows: sourceRows, captureFailures } = value ?? {};
	if (version !== 1 || scope !== 'serialized_tool_response_transport_boundary' || !Array.isArray(sourceRows)) throw new TypeError('invalid tool response summary');
	const length = counter(sourceRows.length);
	if (length > (TOOLS.size + 1) * FAMILIES.size) throw new TypeError('invalid tool response summary');
	const keys = new Set();
	const rows = [];
	for (let index = 0; index < length; index++) {
		const row = sourceRows[index];
		// Read each untrusted field once, then validate exactly what gets persisted.
		const { tool, family } = row;
		if (!(TOOLS.has(tool) || tool === 'unknown') || !FAMILIES.has(family) || keys.has(`${tool}:${family}`)) throw new TypeError('invalid tool summary family');
		keys.add(`${tool}:${family}`);
		const output = { tool, family, ...Object.fromEntries(COUNTERS.map(key => [key, counter(row[key])])),
			executionMs: row.executionMs, respondMs: row.respondMs,
			responseHashChain: hash(row.responseHashChain), completionHashChain: hash(row.completionHashChain) };
		validateTotals(output);
		if ((output.attempts === 0) !== (output.responseHashChain === null)
			|| (output.accepted + output.failed === 0) !== (output.completionHashChain === null)) throw new TypeError('inconsistent tool summary hashes');
		rows.push(Object.freeze(output));
	}
	return Object.freeze({ version: 1, scope, receiptAcknowledged: false,
		captureFailures: counter(captureFailures), rows: Object.freeze(rows) });
}
