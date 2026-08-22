import { DEFAULT_AGENT_CAP, MAX_IDENTIFIER_LENGTH } from './constants.mjs';

const DEFAULT_MAX_CONCURRENT = 4;
const DEFAULT_URGENT_BURST = 3;
const DEFAULT_LANE = 'default';
const ORDINARY_PRIORITY = 'ordinary';
const URGENT_PRIORITY = 'urgent';
const PRIORITIES = new Set([ORDINARY_PRIORITY, URGENT_PRIORITY]);

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
	#maxUrgentBurst;
	#onPressure;
	#warning = false;
	#pending = new Map();
	#lanes = new Map();
	#laneOrder = [];
	#lastLane = null;
	#urgentStreak = 0;
	#active = new Map();
	#closed = false;
	#recorder;

	constructor({
		maxConcurrent = DEFAULT_MAX_CONCURRENT,
		maxPending = Math.max(0, DEFAULT_AGENT_CAP - maxConcurrent),
		maxUrgentBurst = DEFAULT_URGENT_BURST,
		urgentBurstLimit = maxUrgentBurst,
		onPressure = () => {},
		recorder = null,
		benchmarkRecorder = null,
	} = {}) {
		if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent <= 0) throw new TypeError('maxConcurrent must be a positive safe integer');
		if (!Number.isSafeInteger(maxPending) || maxPending < 0) throw new TypeError('maxPending must be a non-negative safe integer');
		if (maxConcurrent + maxPending > DEFAULT_AGENT_CAP) throw new TypeError(`planning capacity must not exceed ${DEFAULT_AGENT_CAP}`);
		if (!Number.isSafeInteger(urgentBurstLimit) || urgentBurstLimit <= 0) throw new TypeError('urgentBurstLimit must be a positive safe integer');
		if (typeof onPressure !== 'function') throw new TypeError('onPressure must be a function');
		const selectedRecorder = recorder ?? benchmarkRecorder;
		if (selectedRecorder !== null && typeof selectedRecorder.record !== 'function') throw new TypeError('recorder.record must be a function');
		this.#maxConcurrent = maxConcurrent;
		this.#maxPending = maxPending;
		this.#maxUrgentBurst = urgentBurstLimit;
		this.#onPressure = onPressure;
		this.#recorder = selectedRecorder;
	}

	get maxConcurrent() { return this.#maxConcurrent; }
	get maxPending() { return this.#maxPending; }
	get maxUrgentBurst() { return this.#maxUrgentBurst; }
	get urgentBurstLimit() { return this.#maxUrgentBurst; }
	get totalCapacity() { return this.#maxConcurrent + this.#maxPending; }
	get activeCount() { return this.#active.size; }
	get pendingCount() { return this.#pending.size; }
	get activeAgentIds() { return [...this.#active.keys()]; }
	get pendingAgentIds() { return [...this.#pending.keys()]; }
	get pressureSnapshot() { return this.#snapshot(); }
	hasScheduled(agentIdValue) {
		const agentId = requireAgentId(agentIdValue);
		return this.#pending.has(agentId) || this.#active.has(agentId);
	}

	schedule(agentIdValue, task, options = {}) {
		const agentId = requireAgentId(agentIdValue);
		if (typeof task !== 'function') throw new TypeError('planning task must be a function');
		const { lane, priority } = normalizeScheduleOptions(options);
		this.#record('scheduler_admission_requested', agentId, { lane, priority, ...this.#snapshot() });
		if (this.#closed) return this.#reject('SCHEDULER_CLOSED', 'Planning scheduler is closed', agentId, lane, priority);
		if (this.#pending.has(agentId)) return this.#reject('PLAN_ALREADY_QUEUED', `Agent '${agentId}' already has a queued planning turn`, agentId, lane, priority);
		if (this.#active.has(agentId)) return this.#reject('PLAN_ALREADY_ACTIVE', `Agent '${agentId}' already has an active planning turn`, agentId, lane, priority);
		if (this.#active.size + this.#pending.size >= this.totalCapacity) {
			return this.#reject('SCHEDULER_CAPACITY', `Planning scheduler capacity ${this.totalCapacity} is full`, agentId, lane, priority);
		}

		const promise = new Promise((resolve, reject) => {
			const entry = { agentId, task, lane, priority, resolve, reject };
			this.#pending.set(agentId, entry);
			this.#lane(lane)[priority].push(entry);
		});
		this.#record('scheduler_queued', agentId, { lane, priority, ...this.#snapshot() });
		this.#drain();
		this.#notifyPressure();
		return promise;
	}

	cancel(agentIdValue, reason = 'Planning turn cancelled') {
		const agentId = requireAgentId(agentIdValue);
		const pending = this.#pending.get(agentId);
		if (pending !== undefined) {
			this.#pending.delete(agentId);
			const queue = this.#lane(pending.lane)[pending.priority];
			const index = queue.indexOf(pending);
			if (index >= 0) queue.splice(index, 1);
			pending.reject(new PlanningSchedulerError('PLAN_CANCELLED', reason));
		}
		const active = this.#active.get(agentId);
		if (active !== undefined) active.controller.abort(new PlanningSchedulerError('PLAN_CANCELLED', reason));
		this.#drain();
		this.#notifyPressure();
		return pending !== undefined || active !== undefined;
	}

	close(reason = 'Planning scheduler closed') {
		if (this.#closed) return;
		this.#closed = true;
		for (const agentId of [...this.#pending.keys(), ...this.#active.keys()]) this.cancel(agentId, reason);
	}

	#lane(lane) {
		let queues = this.#lanes.get(lane);
		if (queues === undefined) {
			queues = { [URGENT_PRIORITY]: [], [ORDINARY_PRIORITY]: [] };
			this.#lanes.set(lane, queues);
			this.#laneOrder.push(lane);
		}
		return queues;
	}

	#drain() {
		while (!this.#closed && this.#active.size < this.#maxConcurrent && this.#pending.size > 0) {
			const entry = this.#nextEntry();
			if (entry === null) break;
			this.#pending.delete(entry.agentId);
			const controller = new AbortController();
			this.#active.set(entry.agentId, { ...entry, controller });
			this.#record('scheduler_admitted', entry.agentId, {
				lane: entry.lane,
				priority: entry.priority,
				...this.#snapshot(),
			});
			Promise.resolve()
				.then(() => entry.task({ agentId: entry.agentId, signal: controller.signal, lane: entry.lane, priority: entry.priority }))
				.then(
					(value) => { this.#release(entry.agentId, controller); entry.resolve(value); },
					(error) => { this.#release(entry.agentId, controller); entry.reject(error); },
				);
		}
		this.#notifyPressure();
	}

	#nextEntry() {
		const priority = this.#nextPriority();
		const lane = this.#nextLane(priority);
		if (lane === null) return null;
		const entry = this.#lanes.get(lane)[priority].shift() ?? null;
		if (entry !== null) {
			this.#lastLane = lane;
			if (priority === URGENT_PRIORITY) this.#urgentStreak += 1;
			else this.#urgentStreak = 0;
		}
		return entry;
	}

	#nextPriority() {
		const hasUrgent = this.#hasPendingPriority(URGENT_PRIORITY);
		const hasOrdinary = this.#hasPendingPriority(ORDINARY_PRIORITY);
		if (hasUrgent && (!hasOrdinary || this.#urgentStreak < this.#maxUrgentBurst)) return URGENT_PRIORITY;
		if (hasOrdinary) return ORDINARY_PRIORITY;
		return URGENT_PRIORITY;
	}

	#hasPendingPriority(priority) {
		return this.#laneOrder.some((lane) => this.#lanes.get(lane)[priority].length > 0);
	}

	#nextLane(priority) {
		if (this.#laneOrder.length === 0) return null;
		const lastIndex = this.#lastLane === null ? -1 : this.#laneOrder.indexOf(this.#lastLane);
		const start = (lastIndex + 1 + this.#laneOrder.length) % this.#laneOrder.length;
		for (let offset = 0; offset < this.#laneOrder.length; offset += 1) {
			const lane = this.#laneOrder[(start + offset) % this.#laneOrder.length];
			if (this.#lanes.get(lane)[priority].length > 0) return lane;
		}
		return null;
	}

	#release(agentId, controller) {
		const active = this.#active.get(agentId);
		if (active?.controller !== controller) return;
		this.#active.delete(agentId);
		this.#record('scheduler_released', agentId, {
			lane: active.lane,
			priority: active.priority,
			...this.#snapshot(),
		});
		this.#drain();
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
		this.#record('scheduler_pressure', null, snapshot);
		try { this.#onPressure(snapshot); } catch { /* monitoring cannot break scheduling */ }
	}

	#reject(code, message, agentId, lane, priority) {
		this.#record('scheduler_rejected', agentId, { lane, priority, errorCode: code, ...this.#snapshot() });
		return Promise.reject(new PlanningSchedulerError(code, message));
	}

	#record(stage, agentId, fields = {}) {
		if (this.#recorder === null) return;
		try { this.#recorder.record(stage, agentId === null ? {} : { agentId }, fields); }
		catch { /* benchmark telemetry cannot affect scheduling */ }
	}
}

function normalizeScheduleOptions(options) {
	if (options === null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('planning schedule options must be an object');
	return {
		lane: requireLane(options.lane ?? DEFAULT_LANE),
		priority: requirePriority(options.priority ?? ORDINARY_PRIORITY),
	};
}

function requireLane(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_IDENTIFIER_LENGTH) throw new TypeError(`planning lane must be nonblank and at most ${MAX_IDENTIFIER_LENGTH} characters`);
	return value;
}

function requirePriority(value) {
	if (typeof value !== 'string' || !PRIORITIES.has(value)) throw new TypeError(`planning priority must be '${URGENT_PRIORITY}' or '${ORDINARY_PRIORITY}'`);
	return value;
}

function requireAgentId(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_IDENTIFIER_LENGTH) throw new TypeError(`agentId must be nonblank and at most ${MAX_IDENTIFIER_LENGTH} characters`);
	return value;
}
