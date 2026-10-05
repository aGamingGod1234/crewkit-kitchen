import { createHash } from 'node:crypto';
import { mkdir, open, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { AtomicAgentStore } from './observed-memory-store.mjs';

const CHECKPOINT_RECORDS = 128;
const CHECKPOINT_BYTES = 1_048_576;
const MAX_RECORD_BYTES = 262_144;
const COLLECTIONS = ['notes', 'receipts', 'recovery'];
const persistent = entry => !entry.worldId.startsWith('session:');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

/** Single notebook owner, serialized by ModelNotebook. No shared-store semantics change.
 * A synced newline commits one checksummed delta. Checkpoint publication precedes
 * log truncation, so either side of a crash replays the same committed revisions.
 */
export class NotebookActionJournal {
	#directory; #disk; #logs = new Map();
	constructor({ directory = null } = {}) {
		this.#directory = directory === null ? null : resolve(directory);
		this.#disk = new AtomicAgentStore({ directory, namespace: 'notebook' });
	}
	async read(agentId) {
		let state = await this.#disk.read(agentId);
		if (this.#directory === null) return state;
		let bytes;
		try { bytes = await readFile(this.#path(agentId)); }
		catch (error) { if (error.code !== 'ENOENT') throw error; bytes = Buffer.alloc(0); }
		if (bytes.length > CHECKPOINT_BYTES + MAX_RECORD_BYTES) throw new Error('ACTION_JOURNAL_TOO_LARGE');
		const checkpointRevision = state?.revision ?? 0;
		let offset = 0, records = 0, previousRevision = -1;
		while (offset < bytes.length) {
			const end = bytes.indexOf(10, offset);
			if (end === -1) break; // Only an unterminated final record is a torn tail.
			if (end - offset > MAX_RECORD_BYTES) throw new Error('INVALID_ACTION_JOURNAL');
			const line = bytes.subarray(offset, end).toString('utf8');
			const separator = line.indexOf(' ');
			const encoded = line.slice(separator + 1);
			if (separator !== 64 || digest(encoded) !== line.slice(0, separator)) throw new Error('INVALID_ACTION_JOURNAL');
			const event = JSON.parse(encoded);
			if (event.version !== 1 || !Number.isSafeInteger(event.revision) || event.revision <= previousRevision) throw new Error('INVALID_ACTION_JOURNAL');
			previousRevision = event.revision;
			if (event.revision > checkpointRevision) {
				state ??= emptyNotebook();
				if (event.revision !== state.revision + 1) throw new Error('ACTION_JOURNAL_REVISION_GAP');
				state = applyDelta(state, event);
			}
			offset = end + 1; records++;
		}
		// A previous owner's sync/rollback may have failed after a complete write.
		// Re-sync recovery before any idempotent retry can release a send or ACK.
		if (bytes.length > 0) await this.#truncate(agentId, offset);
		this.#logs.set(agentId, { bytes: offset, records, poisoned: false });
		return state;
	}
	// Notes/explicit clear remain infrequent full checkpoints. Old log records are
	// harmless: replay skips revisions covered by this atomically published file.
	checkpoint(agentId, state) { return this.#disk.write(agentId, persistentSnapshot(state)); }
	async append(agentId, previous, state) {
		if (this.#directory === null) return;
		const log = this.#logs.get(agentId);
		if (!log || log.poisoned) throw new Error('ACTION_JOURNAL_RELOAD_REQUIRED');
		const encoded = JSON.stringify(delta(previous, state));
		const bytes = Buffer.from(`${digest(encoded)} ${encoded}\n`);
		if (bytes.length > MAX_RECORD_BYTES) throw new Error('ACTION_JOURNAL_RECORD_TOO_LARGE');
		await mkdir(this.#directory, { recursive: true });
		if (log.records >= CHECKPOINT_RECORDS || log.bytes + bytes.length > CHECKPOINT_BYTES) {
			// Checkpoint the OLD committed state. Failure here cannot commit the new
			// action or cause a retry to mistake it for an already durable dispatch.
			await this.checkpoint(agentId, previous);
			await this.#truncate(agentId, 0);
			log.bytes = 0; log.records = 0;
		}
		const file = await open(this.#path(agentId), 'a', 0o600);
		try {
			await file.writeFile(bytes);
			await file.sync();
		} catch (error) {
			// Roll back a partial write before another operation can reuse its revision.
			// If rollback itself fails, fail closed until a new owner reloads the log.
			// Windows append handles cannot truncate; use a separate r+ handle.
			try { await this.#truncate(agentId, log.bytes); }
			catch { log.poisoned = true; }
			throw error;
		} finally {
			try { await file.close(); }
			catch (error) { log.poisoned = true; throw error; }
		}
		log.bytes += bytes.length; log.records++;
	}
	async #truncate(agentId, size) {
		const file = await open(this.#path(agentId), 'r+');
		try { await file.truncate(size); await file.sync(); } finally { await file.close(); }
	}
	#path(agentId) {
		if (typeof agentId !== 'string' || agentId.length === 0 || agentId.length > 256) throw new TypeError('agentId must be bounded text');
		return join(this.#directory, `notebook-actions-${digest(agentId)}.jsonl`);
	}
}

export function emptyNotebook() {
	return { version: 1, revision: 0, notes: [], receipts: [], recovery: [], evictedReceipts: 0, evictedNotes: 0 };
}
function persistentSnapshot(state) {
	return { ...state, ...Object.fromEntries(COLLECTIONS.map(key => [key, state[key].filter(persistent)])) };
}
function delta(previous, state) {
	const event = { version: 1, revision: state.revision, evictedReceipts: state.evictedReceipts, evictedNotes: state.evictedNotes };
	for (const key of COLLECTIONS) {
		const before = new Set(previous[key].map(entry => entry.revision));
		const after = new Set(state[key].map(entry => entry.revision));
		event[key] = { remove: previous[key].filter(entry => !after.has(entry.revision) && persistent(entry)).map(entry => entry.revision),
			put: state[key].filter(entry => !before.has(entry.revision) && persistent(entry)) };
	}
	return event;
}
function applyDelta(state, event) {
	const next = { ...state, revision: event.revision, evictedReceipts: event.evictedReceipts, evictedNotes: event.evictedNotes };
	for (const key of COLLECTIONS) {
		const patch = event[key];
		if (!patch || !Array.isArray(patch.remove) || !Array.isArray(patch.put)) throw new Error('INVALID_ACTION_JOURNAL');
		const removed = new Set(patch.remove);
		next[key] = [...(state[key] ?? []).filter(entry => !removed.has(entry.revision)), ...patch.put];
	}
	return next;
}
