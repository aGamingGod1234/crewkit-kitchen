import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, unlink, rm, opendir } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { AtomicAgentStore } from './observed-memory-store.mjs';
import { canonicalConversationEntry } from './conversation-memory.mjs';
import { MAX_IDENTIFIER_LENGTH } from './constants.mjs';

const PREFIX_ENTRIES = 32;
const hash = (value) => createHash('sha256').update(value).digest('hex');
const failure = (code) => Object.assign(new Error(code), { code });
const position = (value) => Number.isSafeInteger(value) && value >= 0;
const digest = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const range = (value) => value && typeof value.generation === 'string' && value.generation.length > 0
	&& position(value.head) && position(value.tail) && value.head <= value.tail;
function validIndex(saved) {
	return saved?.version === 1 && range(saved)
		&& (saved.scope === null || typeof saved.scope === 'string')
		&& (saved.tail === 0 ? saved.last === null : saved.last && position(saved.last.sequence) && digest(saved.last.digest))
		&& (saved.wake == null || typeof saved.wake.transactionId === 'string' && saved.wake.transactionId.length > 0
			&& saved.wake.transactionId.length <= MAX_IDENTIFIER_LENGTH && position(saved.wake.sequence) && digest(saved.wake.digest)
			&& digest(saved.wake.eventDigest) && saved.last && saved.wake.sequence <= saved.last.sequence)
		&& (saved.staged == null || position(saved.staged.position) && saved.staged.position === saved.tail
			&& position(saved.staged.sequence) && (!saved.last || saved.staged.sequence > saved.last.sequence))
		&& (saved.garbage === null || range(saved.garbage)
			&& (saved.garbage.generation !== saved.generation || saved.garbage.tail <= saved.head));
}

/** Single-owner paged mailbox. One bounded message per page; no backlog-sized index.
 * AtomicAgentStore syncs files before replacement, but does not sync directories.
 * This is restart recovery, not a guarantee against filesystem/power loss.
 * A null directory uses isolated temporary disk pages, deleted on close.
 */
export class PendingConversationInbox {
	#directory; #temporary; #disk; #index; #lane = Promise.resolve();
	#epoch = 0; #reservations = new Map(); #scan = 0; #closed = false;
	constructor({ directory = null, agentId, storeFactory = (options) => new AtomicAgentStore(options) }) {
		this.#directory = directory === null ? null : join(directory, `pending-${hash(agentId)}`);
		this.#temporary = directory === null;
		this.storeFactory = storeFactory;
	}
	#run(operation) {
		const pending = this.#lane.then(operation).catch((error) => {
			if (/^(E[A-Z]+|INVALID_PENDING|MEMORY_FILE)/.test(error?.code ?? error?.message ?? '')) {
				throw Object.assign(failure('CONVERSATION_STORAGE_FAILED'), { cause: error });
			}
			throw error;
		});
		this.#lane = pending.catch(() => {});
		return pending;
	}
	async #load() {
		if (this.#closed) throw failure('INBOX_CLOSED');
		if (!this.#disk) {
			this.#directory ??= await mkdtemp(join(tmpdir(), 'arena-pending-'));
			this.#disk = this.storeFactory({ directory: this.#directory, namespace: 'pending' });
		}
		if (this.#index) return;
		const saved = await this.#disk.read('index');
		if (saved === null) {
			// Missing and JSON-null reads are indistinguishable in AtomicAgentStore.
			// Existing records are evidence of a damaged mailbox, never a fresh scope.
			const directory = await opendir(this.#directory).catch((error) => {
				if (error.code !== 'ENOENT') throw error;
				return null;
			});
			if (directory) for await (const file of directory) {
				if (file.name.startsWith('pending-') && file.name.endsWith('.json')) throw failure('INVALID_PENDING_INBOX');
			}
		} else if (!validIndex(saved)) throw failure('INVALID_PENDING_INBOX');
		this.#index = saved;
		this.#scan = saved?.head ?? 0;
	}
	#key(position, generation = this.#index.generation) { return `${generation}:${position}`; }
	async #save(next) {
		try { await this.#disk.write('index', next); this.#index = next; }
		catch (error) { this.#index = null; throw error; }
	}
	async #delete(key) {
		await unlink(join(this.#directory, `pending-${hash(key)}.json`)).catch((error) => { if (error.code !== 'ENOENT') throw error; });
	}
	async #garbage() {
		// The durable cursor is advanced only after deletion; repeating a delete is safe.
		while (this.#index?.garbage) {
			const garbage = this.#index.garbage;
			if (garbage.head === garbage.tail) { await this.#save({ ...this.#index, garbage: null }); break; }
			const key = this.#key(garbage.head, garbage.generation);
			const page = await this.#disk.read(key);
			if (page) await this.#delete(`${garbage.generation}:sequence:${page.entry.sequence}`);
			await this.#delete(key);
			await this.#save({ ...this.#index, garbage: { ...garbage, head: garbage.head + 1 } });
		}
	}
	async #staged() {
		const staged = this.#index?.staged;
		if (!staged) return;
		await this.#delete(`${this.#index.generation}:sequence:${staged.sequence}`);
		await this.#delete(this.#key(staged.position));
		await this.#save({ ...this.#index, staged: null });
	}
	async #scope(scope) {
		await this.#load();
		await this.#staged();
		await this.#garbage();
		if (this.#index?.scope === scope) return;
		this.fence();
		const old = this.#index;
		await this.#save({ version: 1, scope, generation: randomUUID(), head: 0, tail: 0, last: null,
			garbage: old ? { generation: old.generation, head: old.head, tail: old.tail } : null });
		this.#scan = 0;
		await this.#garbage();
	}
	open(scope) { return this.#run(() => this.#scope(scope)); }
	#matchWake(entry, wake) {
		const retained = this.#index.wake;
		if (!wake || !retained || (retained.transactionId !== wake.transactionId && retained.sequence !== entry.sequence)) return false;
		if (retained.eventDigest !== hash(JSON.stringify(entry))) throw failure('CONVERSATION_COLLISION');
		if (retained.transactionId !== wake.transactionId || retained.digest !== hash(wake.fingerprint)) throw failure('TRANSACTION_COLLISION');
		return true;
	}
	checkWake(scope, value, wake) {
		const entry = { ...canonicalConversationEntry(value), text: value.text };
		return this.#run(async () => { await this.#scope(scope); return this.#matchWake(entry, wake); });
	}
	append(scope, value, wake = null) {
		// Validate before queuing; retain admitted text exactly, including whitespace.
		const entry = { ...canonicalConversationEntry(value), text: value.text };
		const digest = hash(JSON.stringify(entry));
		// Java retains one replayable wake per agent. Keep its exact transaction
		// independently of page reclamation, bounded by that same ownership rule.
		const wakeReceipt = wake === null ? null : { transactionId: wake.transactionId, sequence: entry.sequence,
			digest: hash(wake.fingerprint), eventDigest: digest };
		return this.#run(async () => {
			await this.#scope(scope);
			if (this.#matchWake(entry, wake)) return false;
			const last = this.#index.last;
			if (last && entry.sequence <= last.sequence) {
				if (entry.sequence === last.sequence && digest === last.digest) return false;
				const receipt = await this.#disk.read(`${this.#index.generation}:sequence:${entry.sequence}`);
				if (receipt && receipt.position >= this.#index.head && receipt.position < this.#index.tail) {
					const page = await this.#page(receipt.position);
					if (receipt.digest === digest && hash(JSON.stringify(page.entry)) === digest) return false;
					throw failure('CONVERSATION_COLLISION');
				}
				throw failure(entry.sequence === last.sequence ? 'CONVERSATION_COLLISION' : 'CONVERSATION_OUT_OF_ORDER');
			}
			if (!Number.isSafeInteger(this.#index.tail + 1)) throw failure('INBOX_POSITION_EXHAUSTED');
			// A bounded staging receipt lets recovery remove a partially appended page/index.
			await this.#save({ ...this.#index, staged: { position: this.#index.tail, sequence: entry.sequence } });
			await this.#disk.write(this.#key(this.#index.tail), { entry, delivered: false });
			await this.#disk.write(`${this.#index.generation}:sequence:${entry.sequence}`, { position: this.#index.tail, digest });
			await this.#save({ ...this.#index, tail: this.#index.tail + 1, staged: null, last: { sequence: entry.sequence, digest },
				...(wakeReceipt === null ? {} : { wake: wakeReceipt }) });
			return true;
		});
	}
	needsDelivery(sequence) {
		return this.#run(async () => {
			await this.#load();
			const receipt = await this.#disk.read(`${this.#index.generation}:sequence:${sequence}`);
			if (!receipt) {
				// The final admitted page is still retained until head reaches tail.
				// Its missing receipt cannot be interpreted as successful consumption.
				if (sequence === this.#index.last?.sequence && this.#index.head < this.#index.tail) throw failure('INVALID_PENDING_RECEIPT');
				return false;
			}
			if (receipt.position < this.#index.head || receipt.position >= this.#index.tail) return false;
			if ([...this.#reservations.values()].some((token) => token.positions.includes(receipt.position))) return false;
			return !(await this.#page(receipt.position)).delivered;
		});
	}
	async #page(at) {
		const page = await this.#disk.read(this.#key(at));
		if (!page || typeof page.delivered !== 'boolean') throw failure('INVALID_PENDING_PAGE');
		canonicalConversationEntry(page.entry);
		return page;
	}
	reserve() {
		const epoch = this.#epoch;
		return this.#run(async () => {
			await this.#load();
			if (epoch !== this.#epoch) throw failure('STALE_PLAN');
			if (this.#reservations.size >= 2) throw failure('INBOX_RESERVATION_BUSY');
			// A fence may reset scan before an accepted commit advances the head.
			const token = { id: randomUUID(), epoch, positions: [], entries: [], next: Math.max(this.#scan, this.#index.head) };
			this.#reservations.set(token.id, token);
			try {
				let inspected = 0;
				for (let at = Math.max(this.#scan, this.#index.head); at < this.#index.tail && inspected < PREFIX_ENTRIES; at++, inspected++) {
					token.next = at + 1;
					if ([...this.#reservations.values()].some((other) => other !== token && other.positions.includes(at))) continue;
					const page = await this.#page(at);
					if (!page.delivered) { token.positions.push(at); token.entries.push(page.entry); }
				}
				if (epoch !== this.#epoch) throw failure('STALE_PLAN');
				this.#scan = token.next;
				return { token, conversation: { mode: 'unread', baseSequence: null,
					nextSequence: token.entries.at(-1)?.sequence ?? -1, entries: structuredClone(token.entries) },
					more: token.next < this.#index.tail };
			} catch (error) { this.rollback(token); throw error; }
		});
	}
	/** Release the suffix removed by the actual event serializer, before provider delivery. */
	trim(token, count) {
		if (this.#reservations.get(token?.id) !== token) return;
		if (!Number.isInteger(count) || count < 0 || count > token.positions.length) throw failure('INVALID_INBOX_PREFIX');
		if (count < token.positions.length) this.#scan = Math.min(this.#scan, token.positions[count]);
		token.positions.length = count; token.entries.length = count;
	}
	rollback(token) {
		if (this.#reservations.get(token?.id) !== token) return;
		this.#reservations.delete(token.id);
		if (token.positions.length) this.#scan = Math.min(this.#scan, token.positions[0]);
	}
	fence() { this.#epoch++; this.#reservations.clear(); this.#scan = this.#index?.head ?? 0; }
	commit(token) {
		return this.#run(async () => {
			if (this.#reservations.get(token?.id) !== token) return false;
			await this.#load();
			try {
				for (let i = 0; i < token.positions.length; i++) {
					if (token.epoch !== this.#epoch) return false;
					await this.#disk.write(this.#key(token.positions[i]), { entry: token.entries[i], delivered: true });
				}
				this.#reservations.delete(token.id);
				await this.#reclaim();
				return true;
			} catch (error) { this.rollback(token); throw error; }
		});
	}
	async #reclaim() {
		await this.#garbage();
		// Work per successful prefix is bounded even when a long-held start left a large gap.
		for (let n = 0; n < PREFIX_ENTRIES && this.#index.head < this.#index.tail; n++) {
			const at = this.#index.head;
			if (!(await this.#page(at)).delivered) break;
			// Retain a cleanup receipt in the index across a failed unlink or interruption.
			await this.#save({ ...this.#index, head: at + 1,
				garbage: { generation: this.#index.generation, head: at, tail: at + 1 } });
			await this.#garbage();
		}
	}
	remove(isCurrent = () => true) {
		this.fence();
		return this.#run(async () => {
			if (!isCurrent()) return;
			await this.#load();
			if (isCurrent() && this.#index) await this.#scope(null);
		});
	}
	async close() {
		this.fence();
		await this.#lane;
		try {
			if (!this.#temporary) {
				await this.#load();
				if (this.#index) {
					await this.#staged(); await this.#garbage();
					let previous;
					do { previous = this.#index.head; await this.#reclaim(); } while (this.#index.head > previous);
				}
			}
		} finally {
			this.#closed = true;
			// This path was created exclusively by mkdtemp above, never supplied by a caller.
			if (this.#temporary && this.#directory) await rm(this.#directory, { recursive: true, force: true });
		}
	}
}
