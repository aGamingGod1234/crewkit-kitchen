import {
	DEFAULT_AGENT_CAP,
	DEFAULT_GOAL_QUEUE_CAP,
	MAX_GOAL_LENGTH,
	MAX_IDENTIFIER_LENGTH,
	MAX_REASON_CODE_LENGTH,
	MAX_RESULT_MESSAGE_LENGTH,
} from './constants.mjs';

export const DynamicAgentState = Object.freeze({
	IDLE: 'IDLE',
	STARTING: 'STARTING',
	PLANNING: 'PLANNING',
	ACTING: 'ACTING',
	PAUSED: 'PAUSED',
	COMPLETED: 'COMPLETED',
	ERROR: 'ERROR',
	DEAD: 'DEAD',
	DISCONNECTED: 'DISCONNECTED',
});

const DYNAMIC_AGENT_STATES = new Set(Object.values(DynamicAgentState));
const ACTIVE_ON_RELOAD = new Set([
	DynamicAgentState.STARTING,
	DynamicAgentState.PLANNING,
	DynamicAgentState.ACTING,
	DynamicAgentState.DISCONNECTED,
]);
const PROMOTION_SOURCE_STATES = new Set([
	DynamicAgentState.STARTING,
	DynamicAgentState.PLANNING,
	DynamicAgentState.ACTING,
]);
const GOAL_OPERATIONS = new Set(['start', 'stop', 'queue', 'steer', 'resume', 'complete', 'fail', 'disconnect', 'dead', 'respawn']);
const ALLOWED_STATE_TRANSITIONS = Object.freeze({
	[DynamicAgentState.IDLE]: new Set([DynamicAgentState.STARTING, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.STARTING]: new Set([DynamicAgentState.PLANNING, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.PLANNING]: new Set([DynamicAgentState.ACTING, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.ACTING]: new Set([DynamicAgentState.PLANNING, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.PAUSED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.COMPLETED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.ERROR]: new Set([DynamicAgentState.STARTING, DynamicAgentState.PAUSED, DynamicAgentState.IDLE, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.DEAD]: new Set([DynamicAgentState.IDLE, DynamicAgentState.PAUSED, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.DISCONNECTED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.PAUSED, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD]),
});

export class AgentRegistryError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'AgentRegistryError';
		this.code = code;
	}
}

export class AgentRegistry {
	#agents = new Map();
	#agentCap;
	#queueCap;
	#now;

	constructor({ agentCap = DEFAULT_AGENT_CAP, queueCap = DEFAULT_GOAL_QUEUE_CAP, now = Date.now } = {}) {
		this.#agentCap = positiveInteger(agentCap, 'agentCap');
		this.#queueCap = positiveInteger(queueCap, 'queueCap');
		if (typeof now !== 'function') throw new TypeError('registry now dependency must be a function');
		this.#now = now;
	}

	get size() { return this.#agents.size; }
	get agentCap() { return this.#agentCap; }
	get queueCap() { return this.#queueCap; }

	has(agentId) {
		return this.#agents.has(agentId);
	}

	get(agentId) {
		const record = this.#agents.get(requireIdentifier(agentId, 'agentId'));
		return record === undefined ? null : clone(record);
	}

	list() {
		return [...this.#agents.values()].map(clone).sort((left, right) => left.agentId.localeCompare(right.agentId));
	}

	register(value) {
		const record = normalizeAgentRecord(value, { queueCap: this.#queueCap });
		const existing = this.#agents.get(record.agentId);
		if (existing === undefined && this.#agents.size >= this.#agentCap) {
			throw new AgentRegistryError('AGENT_CAP_REACHED', `Agent cap of ${this.#agentCap} has been reached`);
		}
		if (existing !== undefined && record.goalRevision < existing.goalRevision) {
			throw new AgentRegistryError('STALE_GOAL_REVISION', `Agent '${record.agentId}' registry revision moved backwards`);
		}
		this.#agents.set(record.agentId, record);
		return clone(record);
	}

	remove(agentId) {
		const id = requireIdentifier(agentId, 'agentId');
		const existing = this.#agents.get(id);
		if (existing === undefined) return null;
		this.#agents.delete(id);
		return clone(existing);
	}

	applyGoalControl(agentId, value) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		const updated = reduceGoalControl(current, value, { queueCap: this.#queueCap });
		this.#agents.set(id, updated);
		return clone(updated);
	}

	setState(agentId, state, { goalRevision, error = null } = {}) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		if (!DYNAMIC_AGENT_STATES.has(state)) throw new AgentRegistryError('INVALID_AGENT_STATE', `Unsupported state '${String(state)}'`);
		if (goalRevision !== undefined) assertCurrentGoalRevision(current, goalRevision);
		if (state !== current.state && !ALLOWED_STATE_TRANSITIONS[current.state].has(state)) throw new AgentRegistryError('ILLEGAL_STATE_TRANSITION', `Agent cannot transition from ${current.state} to ${state}`);
		const updated = {
			...current,
			state,
			lastError: normalizeError(error),
			updatedAtEpochMs: this.#now(),
		};
		this.#agents.set(id, updated);
		return clone(updated);
	}

	assertCurrentRevision(agentId, goalRevision) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		assertCurrentGoalRevision(current, goalRevision);
		return clone(current);
	}

	reconcile(snapshot) {
		if (!Array.isArray(snapshot)) throw new TypeError('registry snapshot must be an array');
		if (snapshot.length > this.#agentCap) throw new AgentRegistryError('AGENT_CAP_REACHED', `Registry snapshot exceeds agent cap of ${this.#agentCap}`);
		const next = new Map();
		for (const value of snapshot) {
			const record = normalizeAgentRecord(value, { queueCap: this.#queueCap, reload: true });
			if (next.has(record.agentId)) throw new AgentRegistryError('DUPLICATE_AGENT', `Duplicate agent '${record.agentId}' in registry snapshot`);
			next.set(record.agentId, record);
		}
		const removed = [...this.#agents.keys()].filter((agentId) => !next.has(agentId));
		const added = [...next.keys()].filter((agentId) => !this.#agents.has(agentId));
		const updated = [...next.keys()].filter((agentId) => this.#agents.has(agentId));
		this.#agents = next;
		return { added, updated, removed, records: this.list() };
	}

	snapshot() {
		return this.list();
	}
}

export function normalizeAgentRecord(value, { queueCap = DEFAULT_GOAL_QUEUE_CAP, reload = false } = {}) {
	if (!isPlainObject(value)) throw new TypeError('agent record must be an object');
	const state = requireState(value.state ?? DynamicAgentState.IDLE);
	const normalizedState = reload && ACTIVE_ON_RELOAD.has(state) ? DynamicAgentState.PAUSED : state;
	const goalRevision = nonnegativeInteger(value.goalRevision ?? 0, 'goalRevision');
	const queue = value.queue ?? [];
	if (!Array.isArray(queue)) throw new TypeError('agent queue must be an array');
	if (queue.length > queueCap) throw new AgentRegistryError('GOAL_QUEUE_FULL', `Agent goal queue exceeds ${queueCap} entries`);
	return {
		schemaVersion: positiveInteger(value.schemaVersion ?? 1, 'schemaVersion'),
		agentId: requireIdentifier(value.agentId, 'agentId'),
		entityUuid: optionalIdentifier(value.entityUuid, 'entityUuid'),
		name: optionalText(value.name, 'name', MAX_IDENTIFIER_LENGTH),
		provider: requireProvider(value.provider ?? 'codex'),
		model: requireIdentifier(value.model, 'model'),
		reasoningEffort: requireIdentifier(value.reasoningEffort, 'reasoningEffort'),
		skinVariant: requireIdentifier(value.skinVariant ?? 'default', 'skinVariant'),
		state: normalizedState,
		currentGoal: optionalGoal(value.currentGoal),
		goalRevision,
		queue: queue.map((entry, index) => normalizeQueuedGoal(entry, index)),
		lastSummary: optionalText(value.lastSummary, 'lastSummary', MAX_RESULT_MESSAGE_LENGTH),
		respawnPolicy: isPlainObject(value.respawnPolicy) ? clone(value.respawnPolicy) : {},
		createdAtEpochMs: nonnegativeInteger(value.createdAtEpochMs ?? 0, 'createdAtEpochMs'),
		updatedAtEpochMs: nonnegativeInteger(value.updatedAtEpochMs ?? 0, 'updatedAtEpochMs'),
		lastError: normalizeError(value.lastError),
	};
}

function requireProvider(value) {
	const provider = requireIdentifier(value, 'provider').toLowerCase();
	if (!['codex', 'gemini', 'kimi'].includes(provider)) throw new TypeError(`provider must be one of codex, gemini, or kimi`);
	return provider;
}

export function reduceGoalControl(recordValue, controlValue, { queueCap = DEFAULT_GOAL_QUEUE_CAP } = {}) {
	const record = normalizeAgentRecord(recordValue, { queueCap });
	if (!isPlainObject(controlValue)) throw new TypeError('goal control must be an object');
	const operation = controlValue.operation;
	if (!GOAL_OPERATIONS.has(operation)) throw new AgentRegistryError('INVALID_GOAL_OPERATION', `Unsupported goal operation '${String(operation)}'`);
	const revision = nonnegativeInteger(controlValue.goalRevision, 'goalRevision');
	if (operation === 'queue') {
		if (revision !== record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Queued goal revision ${revision} does not match current revision ${record.goalRevision}`);
		if (record.queue.length >= queueCap) throw new AgentRegistryError('GOAL_QUEUE_FULL', `Agent goal queue is limited to ${queueCap} entries`);
		return {
			...record,
			queue: [...record.queue, normalizeQueuedGoal({ goal: controlValue.goal, goalRevision: revision }, record.queue.length)],
			updatedAtEpochMs: nonnegativeInteger(controlValue.updatedAtEpochMs ?? Date.now(), 'updatedAtEpochMs'),
		};
	}
	if ((operation === 'stop' && record.state === DynamicAgentState.PAUSED && revision === record.goalRevision)
		|| (operation === 'disconnect' && record.state === DynamicAgentState.DISCONNECTED && revision === record.goalRevision)) return record;
	if (revision <= record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Goal revision ${revision} is not newer than ${record.goalRevision}`);
	const now = nonnegativeInteger(controlValue.updatedAtEpochMs ?? Date.now(), 'updatedAtEpochMs');
	const next = { ...record, goalRevision: revision, updatedAtEpochMs: now, lastError: null };
	if (operation === 'start' || operation === 'steer') {
		const nextGoal = requireGoal(controlValue.goal);
		if (operation === 'start' && PROMOTION_SOURCE_STATES.has(record.state)) {
			const promoted = record.queue[0];
			if (promoted === undefined || promoted.goal !== nextGoal) {
				throw new AgentRegistryError(
					'PROMOTED_GOAL_MISMATCH',
					`Promoted goal '${nextGoal}' does not match the queued head`,
				);
			}
			next.queue = record.queue.slice(1);
		}
		next.currentGoal = nextGoal;
		next.state = DynamicAgentState.STARTING;
		return next;
	}
	if (operation === 'stop') {
		next.state = DynamicAgentState.PAUSED;
		return next;
	}
	if (operation === 'resume') {
		if (next.currentGoal === null) throw new AgentRegistryError('NO_CURRENT_GOAL', 'Cannot resume an agent without a current goal');
		next.state = DynamicAgentState.STARTING;
		return next;
	}
	if (operation === 'disconnect') {
		next.state = DynamicAgentState.DISCONNECTED;
		return next;
	}
	if (operation === 'dead') {
		next.state = DynamicAgentState.DEAD;
		return next;
	}
	if (operation === 'respawn') {
		next.state = next.currentGoal === null ? DynamicAgentState.IDLE : DynamicAgentState.PAUSED;
		return next;
	}
	if (operation === 'fail') {
		next.state = DynamicAgentState.ERROR;
		next.lastError = normalizeError(controlValue.error ?? { code: 'AGENT_ERROR', message: 'Agent goal failed' });
		return next;
	}
	if (next.queue.length > 0) {
		const [promoted, ...remaining] = next.queue;
		next.currentGoal = promoted.goal;
		next.queue = remaining;
		next.state = DynamicAgentState.STARTING;
	} else {
		next.currentGoal = null;
		next.state = DynamicAgentState.IDLE;
	}
	return next;
}

export function encodeAgentRegistrySnapshot(records, { queueCap = DEFAULT_GOAL_QUEUE_CAP } = {}) {
	if (!Array.isArray(records)) throw new TypeError('registry records must be an array');
	const agents = records.map((record) => normalizeAgentRecord(record, { queueCap })).sort((left, right) => left.agentId.localeCompare(right.agentId));
	return JSON.stringify({ schemaVersion: 1, agents });
}

export function decodeAgentRegistrySnapshot(text, { agentCap = DEFAULT_AGENT_CAP, queueCap = DEFAULT_GOAL_QUEUE_CAP, reload = true } = {}) {
	if (typeof text !== 'string' || text.trim().length === 0) throw new TypeError('registry snapshot text must be nonblank');
	let document;
	try { document = JSON.parse(text); } catch (error) { throw new AgentRegistryError('INVALID_REGISTRY_SNAPSHOT', 'Registry snapshot is not valid JSON', { cause: error }); }
	if (!isPlainObject(document) || document.schemaVersion !== 1 || !Array.isArray(document.agents)) throw new AgentRegistryError('INVALID_REGISTRY_SNAPSHOT', 'Registry snapshot must contain schemaVersion 1 and an agents array');
	if (document.agents.length > positiveInteger(agentCap, 'agentCap')) throw new AgentRegistryError('AGENT_CAP_REACHED', `Registry snapshot exceeds agent cap of ${agentCap}`);
	const seen = new Set();
	return document.agents.map((value) => {
		const record = normalizeAgentRecord(value, { queueCap, reload });
		if (seen.has(record.agentId)) throw new AgentRegistryError('DUPLICATE_AGENT', `Duplicate agent '${record.agentId}' in registry snapshot`);
		seen.add(record.agentId);
		return record;
	});
}

function assertCurrentGoalRevision(record, revisionValue) {
	const revision = nonnegativeInteger(revisionValue, 'goalRevision');
	if (revision !== record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Goal revision ${revision} does not match current revision ${record.goalRevision}`);
}

function normalizeQueuedGoal(value, index) {
	if (typeof value === 'string') return { goal: requireGoal(value), goalRevision: index + 1 };
	if (!isPlainObject(value)) throw new TypeError('queued goal must be an object');
	return {
		goal: requireGoal(value.goal),
		goalRevision: nonnegativeInteger(value.goalRevision ?? index + 1, 'queued goalRevision'),
	};
}

function normalizeError(value) {
	if (value === null || value === undefined) return null;
	if (!isPlainObject(value)) throw new TypeError('lastError must be an object or null');
	return {
		code: requireText(value.code, 'lastError.code', MAX_REASON_CODE_LENGTH),
		message: requireText(value.message, 'lastError.message', MAX_RESULT_MESSAGE_LENGTH),
	};
}

function optionalGoal(value) {
	return value === null || value === undefined ? null : requireGoal(value);
}

function requireGoal(value) {
	return requireText(value, 'goal', MAX_GOAL_LENGTH);
}

function requireIdentifier(value, field) {
	return requireText(value, field, MAX_IDENTIFIER_LENGTH);
}

function optionalIdentifier(value, field) {
	return value === null || value === undefined ? null : requireIdentifier(value, field);
}

function optionalText(value, field, maximum) {
	return value === null || value === undefined ? null : requireText(value, field, maximum);
}

function requireText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new TypeError(`${field} must be nonblank and at most ${maximum} characters`);
	return value;
}

function requireState(value) {
	if (!DYNAMIC_AGENT_STATES.has(value)) throw new AgentRegistryError('INVALID_AGENT_STATE', `Unsupported state '${String(value)}'`);
	return value;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function nonnegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative safe integer`);
	return value;
}

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function clone(value) {
	return structuredClone(value);
}
