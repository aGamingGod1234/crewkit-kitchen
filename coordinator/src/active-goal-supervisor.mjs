const ACTIVE_KINDS = new Set(['provider', 'action', 'completion']);
const MIN_DELAY_MS = 250;
const MAX_DELAY_MS = 5_000;

/** Keeps one unfinished goal fenced, active, and recoverable at a time. */
export class ActiveGoalSupervisor {
	#entries = new Map();
	#sequence = 0;
	#schedule;
	#cancelSchedule;
	#requestObservation;
	#closed = false;

	constructor({ requestObservation = () => {}, schedule = setTimeout, cancelSchedule = clearTimeout } = {}) {
		if (typeof requestObservation !== 'function') throw new TypeError('requestObservation must be a function');
		if (typeof schedule !== 'function') throw new TypeError('schedule must be a function');
		if (typeof cancelSchedule !== 'function') throw new TypeError('cancelSchedule must be a function');
		this.#requestObservation = requestObservation;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
	}

	activate(key) {
		if (this.#closed) return false;
		const normalized = normalizeKey(key);
		const existing = this.#entries.get(normalized.agentId);
		if (existing !== undefined) {
			if (sameKey(existing.key, normalized)) {
				if (existing.state === 'active') this.ensure(existing.key, 'goal_activated');
				return existing.state === 'active';
			}
			if (!isNewer(normalized, existing.key)) return false;
			this.#discard(existing);
		}
		const entry = createEntry(normalized);
		this.#entries.set(normalized.agentId, entry);
		this.#ensureEntry(entry, 'goal_activated');
		return true;
	}

	begin(key, kind) {
		if (!ACTIVE_KINDS.has(kind)) throw new TypeError(`Unsupported active work kind '${kind}'`);
		const entry = this.#requireCurrent(key);
		this.#cancelRecovery(entry);
		entry.pendingObservation = null;
		const token = Object.freeze({ ...entry.key, kind, operationId: ++this.#sequence });
		entry.tokens.set(token.operationId, token);
		return token;
	}

	end(token, { progress = false, scheduleRecovery = true } = {}) {
		if (this.#closed || token === null || typeof token !== 'object') return false;
		const entry = this.#current(token);
		if (entry === null || !entry.tokens.delete(token.operationId)) return false;
		if (progress) {
			entry.failures = 0;
			entry.progressVersion += 1;
		}
		if (scheduleRecovery) this.#ensureEntry(entry, progress ? 'progress_settled' : 'work_settled');
		return true;
	}

	observed(key) {
		if (this.#closed) return false;
		const entry = this.#current(key);
		if (entry === null) return false;
		entry.failures = 0;
		entry.progressVersion += 1;
		entry.pendingObservation = null;
		this.#cancelRecovery(entry);
		this.#ensureEntry(entry, 'observation_received');
		return true;
	}

	recover(key, _details = undefined) {
		if (this.#closed) return false;
		const entry = this.#current(key);
		if (entry === null) return false;
		entry.pendingObservation = null;
		this.#ensureEntry(entry, 'recoverable_failure');
		return true;
	}

	suspend(key) {
		if (this.#closed) return false;
		const entry = this.#current(key);
		if (entry === null) return false;
		this.#cancelRecovery(entry);
		entry.pendingObservation = null;
		entry.tokens.clear();
		entry.state = 'suspended';
		return true;
	}

	terminate(key) {
		if (this.#closed) return false;
		const entry = this.#current(key);
		if (entry === null) return false;
		this.#discard(entry);
		entry.state = 'terminated';
		return true;
	}

	ensure(key, _reason = undefined) {
		if (this.#closed) return false;
		const entry = this.#current(key);
		if (entry === null) return false;
		return this.#ensureEntry(entry, _reason);
	}

	close() {
		if (this.#closed) return;
		this.#closed = true;
		for (const entry of this.#entries.values()) this.#discard(entry);
		this.#entries.clear();
	}

	#ensureEntry(entry, _reason) {
		if (entry.state !== 'active' || entry.tokens.size > 0 || entry.pendingObservation !== null || entry.recovery !== null) return false;
		const recovery = { sequence: ++this.#sequence, handle: null };
		entry.recovery = recovery;
		const delay = recoveryDelay(entry.failures);
		try {
			recovery.handle = this.#schedule(() => this.#fireRecovery(entry, recovery), delay);
		} catch (error) {
			if (entry.recovery === recovery) entry.recovery = null;
			throw error;
		}
		return true;
	}

	#fireRecovery(entry, recovery) {
		if (!this.#isCurrentEntry(entry) || entry.recovery !== recovery || entry.state !== 'active') return;
		entry.recovery = null;
		const pending = { sequence: recovery.sequence, progressVersion: entry.progressVersion };
		entry.pendingObservation = pending;
		const requested = entry.key;
		let result;
		try {
			result = this.#requestObservation(requested);
		} catch {
			result = undefined;
		}
		void Promise.resolve(result).catch(() => undefined);
		if (!this.#isCurrentEntry(entry) || entry.pendingObservation !== pending || entry.state !== 'active') return;
		entry.pendingObservation = null;
		if (entry.progressVersion === pending.progressVersion) entry.failures = Math.min(entry.failures + 1, MAX_FAILURES);
		else entry.failures = 0;
		this.#ensureEntry(entry, 'observation_lease_expired');
	}

	#cancelRecovery(entry) {
		if (entry.recovery === null) return;
		const recovery = entry.recovery;
		entry.recovery = null;
		this.#cancelSchedule(recovery.handle);
	}

	#discard(entry) {
		this.#cancelRecovery(entry);
		entry.pendingObservation = null;
		entry.tokens.clear();
	}

	#requireCurrent(key) {
		if (this.#closed) throw new Error('Active goal supervisor is closed');
		const normalized = normalizeKey(key);
		const entry = this.#current(normalized);
		if (entry === null || entry.state !== 'active') throw new Error(`Active goal key is not current for agent '${normalized.agentId}'`);
		return entry;
	}

	#current(key) {
		let normalized;
		try {
			normalized = normalizeKey(key);
		} catch {
			return null;
		}
		const entry = this.#entries.get(normalized.agentId);
		return entry !== undefined && sameKey(entry.key, normalized) ? entry : null;
	}

	#isCurrentEntry(entry) {
		return !this.#closed && this.#entries.get(entry.key.agentId) === entry && entry.state === 'active';
	}
}

const MAX_FAILURES = Math.ceil(Math.log2(MAX_DELAY_MS / MIN_DELAY_MS));

function createEntry(key) {
	return {
		key,
		state: 'active',
		tokens: new Map(),
		recovery: null,
		pendingObservation: null,
		failures: 0,
		progressVersion: 0,
	};
}

function recoveryDelay(failures) {
	return Math.min(MAX_DELAY_MS, MIN_DELAY_MS * (2 ** failures));
}

function normalizeKey(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('active goal key must be an object');
	const { agentId, goalRevision, lifecycleGeneration } = value;
	if (typeof agentId !== 'string' || agentId.length === 0) throw new TypeError('active goal key agentId must be nonblank text');
	if (!Number.isSafeInteger(goalRevision) || goalRevision < 0) throw new TypeError('active goal key goalRevision must be a nonnegative safe integer');
	if (!Number.isSafeInteger(lifecycleGeneration) || lifecycleGeneration < 0) throw new TypeError('active goal key lifecycleGeneration must be a nonnegative safe integer');
	return Object.freeze({ agentId, goalRevision, lifecycleGeneration });
}

function sameKey(left, right) {
	return left.agentId === right.agentId
		&& left.goalRevision === right.goalRevision
		&& left.lifecycleGeneration === right.lifecycleGeneration;
}

function isNewer(next, current) {
	if (next.goalRevision !== current.goalRevision) return next.goalRevision > current.goalRevision;
	return next.lifecycleGeneration > current.lifecycleGeneration;
}
