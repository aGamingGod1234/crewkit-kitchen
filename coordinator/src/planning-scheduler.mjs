import { DEFAULT_AGENT_CAP, DEFAULT_PLANNING_CONCURRENCY, MAX_IDENTIFIER_LENGTH } from './constants.mjs';

export class PlanningSchedulerError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'PlanningSchedulerError';
		this.code = code;
	}
}

export class PlanningScheduler {
	#maxConcurrent;
	#maxPending;
	#onPressure;
	#warning = false;
	#pending = new Map();
	#order = [];
	#active = new Map();
	#closed = false;

	constructor({
		maxConcurrent = DEFAULT_PLANNING_CONCURRENCY,
		maxPending = Math.max(0, DEFAULT_AGENT_CAP - maxConcurrent),
		onPressure = () => {},
	} = {}) {
		if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent <= 0) throw new TypeError('maxConcurrent must be a positive safe integer');
		if (!Number.isSafeInteger(maxPending) || maxPending < 0) throw new TypeError('maxPending must be a non-negative safe integer');
		if (maxConcurrent + maxPending > DEFAULT_AGENT_CAP) throw new TypeError(`planning capacity must not exceed ${DEFAULT_AGENT_CAP}`);
		if (typeof onPressure !== 'function') throw new TypeError('onPressure must be a function');
		this.#maxConcurrent = maxConcurrent;
		this.#maxPending = maxPending;
		this.#onPressure = onPressure;
	}

	get maxConcurrent() { return this.#maxConcurrent; }
	get maxPending() { return this.#maxPending; }
	get totalCapacity() { return this.#maxConcurrent + this.#maxPending; }
	get activeCount() { return this.#active.size; }
	get pendingCount() { return this.#pending.size; }
	get activeAgentIds() { return [...this.#active.keys()]; }
	get pendingAgentIds() { return [...this.#order]; }
	get pressureSnapshot() { return this.#snapshot(); }

	schedule(agentIdValue, task) {
		const agentId = requireAgentId(agentIdValue);
		if (typeof task !== 'function') throw new TypeError('planning task must be a function');
		if (this.#closed) return Promise.reject(new PlanningSchedulerError('SCHEDULER_CLOSED', 'Planning scheduler is closed'));
		if (this.#pending.has(agentId)) return Promise.reject(new PlanningSchedulerError('PLAN_ALREADY_QUEUED', `Agent '${agentId}' already has a queued planning turn`));
		if (this.#active.has(agentId)) return Promise.reject(new PlanningSchedulerError('PLAN_ALREADY_ACTIVE', `Agent '${agentId}' already has an active planning turn`));
		if (this.#active.size + this.#pending.size >= this.totalCapacity) {
			return Promise.reject(new PlanningSchedulerError('SCHEDULER_CAPACITY', `Planning scheduler capacity ${this.totalCapacity} is full`));
		}
		const promise = new Promise((resolve, reject) => {
			this.#pending.set(agentId, { agentId, task, resolve, reject });
			this.#order.push(agentId);
		});
		this.#drain();
		this.#notifyPressure();
		return promise;
	}

	cancel(agentIdValue, reason = 'Planning turn cancelled') {
		const agentId = requireAgentId(agentIdValue);
		const pending = this.#pending.get(agentId);
		if (pending !== undefined) {
			this.#pending.delete(agentId);
			this.#order = this.#order.filter((value) => value !== agentId);
			pending.reject(new PlanningSchedulerError('PLAN_CANCELLED', reason));
		}
		const active = this.#active.get(agentId);
		if (active !== undefined) active.controller.abort(new PlanningSchedulerError('PLAN_CANCELLED', reason));
		this.#notifyPressure();
		return pending !== undefined || active !== undefined;
	}

	close(reason = 'Planning scheduler closed') {
		if (this.#closed) return;
		this.#closed = true;
		for (const agentId of [...this.#pending.keys(), ...this.#active.keys()]) this.cancel(agentId, reason);
	}

	#drain() {
		while (!this.#closed && this.#active.size < this.#maxConcurrent && this.#order.length > 0) {
			const agentId = this.#order.shift();
			const entry = this.#pending.get(agentId);
			if (entry === undefined) continue;
			this.#pending.delete(agentId);
			const controller = new AbortController();
			this.#active.set(agentId, { controller });
			Promise.resolve()
				.then(() => entry.task({ agentId, signal: controller.signal }))
				.then(entry.resolve, entry.reject)
				.finally(() => {
					const active = this.#active.get(agentId);
					if (active?.controller === controller) this.#active.delete(agentId);
					this.#drain();
				});
		}
		this.#notifyPressure();
	}

	#snapshot() {
		const used = this.#active.size + this.#pending.size;
		return Object.freeze({
			active: this.#active.size,
			pending: this.#pending.size,
			used,
			maxConcurrent: this.#maxConcurrent,
			maxPending: this.#maxPending,
			totalCapacity: this.totalCapacity,
			warning: used >= Math.ceil(this.totalCapacity * 0.75),
			full: used >= this.totalCapacity,
		});
	}

	#notifyPressure() {
		const snapshot = this.#snapshot();
		if (snapshot.warning === this.#warning) return;
		this.#warning = snapshot.warning;
		try { this.#onPressure(snapshot); } catch { /* monitoring cannot break scheduling */ }
	}
}

function requireAgentId(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_IDENTIFIER_LENGTH) throw new TypeError(`agentId must be nonblank and at most ${MAX_IDENTIFIER_LENGTH} characters`);
	return value;
}
