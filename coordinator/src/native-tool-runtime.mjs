import { validateTraceId } from './control-latency-registry.mjs';
import { bindCompletionContract } from './goal-contract.mjs';

export class NativeToolRuntime {
	#bridge;
	#onFinish;
	#trace;
	#observations = new Map();
	#actions = new Map();
	#completions = new Map();
	#sequence = 0;

	constructor({ bridge, onFinish = async () => ({ state: 'FINISH_REQUESTED' }), trace = () => {} } = {}) {
		if (typeof bridge?.send !== 'function') throw new TypeError('bridge.send must be a function');
		if (typeof onFinish !== 'function') throw new TypeError('onFinish must be a function');
		if (typeof trace !== 'function') throw new TypeError('trace must be a function');
		this.#bridge = bridge;
		this.#onFinish = onFinish;
		this.#trace = trace;
	}

	updateObservation(record, observation, { eventSequence = 0, conversation = undefined } = {}) {
		validateRecord(record);
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) throw new TypeError('eventSequence must be a nonnegative safe integer');
		this.#observations.set(record.agentId, {
			goalRevision: record.goalRevision,
			eventSequence,
			goal: record.currentGoal ?? null,
			observation: structuredClone(observation ?? {}),
			...(conversation === undefined ? {} : { conversation: structuredClone(conversation) }),
		});
	}

	hasCurrent(record) {
		const latest = this.#observations.get(record.agentId);
		return latest?.goalRevision === record.goalRevision;
	}

	async execute(request, record) {
		validateRecord(record);
		validateRequest(request, record);
		if (request.tool.kind === 'observe') {
			const latest = this.#observations.get(record.agentId);
			if (latest?.goalRevision !== record.goalRevision) return { eventSequence: 0, goal: record.currentGoal ?? null, observation: {} };
			const { goalRevision: _goalRevision, ...facts } = latest;
			return structuredClone(facts);
		}
		if (request.tool.kind === 'finish') return this.#finish(request, record);
		if (request.tool.kind === 'sequence') return this.#executeSequence(request, record);
		if (request.tool.kind !== 'action') throw codedError('INVALID_NATIVE_TOOL', 'Native tool did not normalize to an action');
		return this.#executeAction(request, record, request.tool);
	}

	async #executeSequence(request, record) {
		const results = [];
		for (let index = 0; index < request.tool.actions.length; index += 1) {
			const action = request.tool.actions[index];
			const result = await this.#executeAction(request, record, { kind: 'action', ...action }, index);
			results.push({ actionType: action.actionType, ...result });
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results };
		}
		return { state: 'SUCCEEDED', completed: results.length, results };
	}

	async #executeAction(request, record, tool, sequenceIndex = null) {
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');

		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-${safeSegment(record.agentId)}-${record.goalRevision}-${ordinal}`.slice(0, 128));
		const actionId = `native:${safeSegment(record.agentId)}:${record.goalRevision}:${ordinal}`.slice(0, 128);
		const eventSequence = this.#observations.get(record.agentId)?.eventSequence ?? 0;
		const payload = {
			traceId,
			goalRevision: record.goalRevision,
			actionId,
			actionType: tool.actionType,
			arguments: structuredClone(tool.arguments),
			provenance: {
				provider: record.provider,
				model: record.model,
				reasoningEffort: record.reasoningEffort,
				serviceTier: record.serviceTier ?? 'priority',
				traceId,
				programId: `native-${safeSegment(request.turnId)}`.slice(0, 256),
				programVersion: 1,
				sourceStepId: `${safeSegment(request.callId)}${sequenceIndex === null ? '' : `:${sequenceIndex + 1}`}`.slice(0, 256),
				eventSequence,
			},
		};
		let resolveAction;
		let rejectAction;
		const result = new Promise((resolve, reject) => { resolveAction = resolve; rejectAction = reject; });
		this.#actions.set(record.agentId, { actionId, goalRevision: record.goalRevision, resolve: resolveAction, reject: rejectAction });
		this.#trace('native_tool_dispatch_started', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		try {
			await this.#bridge.send('action_command', record.agentId, payload);
			this.#trace('native_tool_command_sent', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		} catch (error) {
			this.#actions.delete(record.agentId);
			rejectAction(error);
		}
		return result;
	}

	onActionProgress(record, payload = {}) {
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		this.#trace('native_tool_action_progress', { agentId: record.agentId, goalRevision: record.goalRevision, actionId: active.actionId });
		return true;
	}

	onActionResult(record, payload = {}) {
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		this.#actions.delete(record.agentId);
		const result = {
			state: String(payload.state ?? 'FAILED').slice(0, 64),
			reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
			...(payload.executionStarted === undefined ? {} : { executionStarted: payload.executionStarted === true }),
		};
		this.#trace('native_tool_action_completed', { agentId: record.agentId, goalRevision: record.goalRevision, actionId: active.actionId, ...result });
		active.resolve(result);
		return true;
	}

	onCompletionResult(record, payload = {}) {
		const active = this.#completions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision) return false;
		if (payload.traceId !== active.traceId || payload.contractHash !== active.contractHash) return false;
		this.#completions.delete(record.agentId);
		active.resolve({
			state: payload.verified === true ? 'COMPLETED' : 'FAILED',
			verified: payload.verified === true,
			reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
		});
		return true;
	}

	async dispose(agentId, reason = 'disposed') {
		this.#observations.delete(agentId);
		const active = this.#actions.get(agentId);
		const completion = this.#completions.get(agentId);
		if (completion !== undefined) {
			this.#completions.delete(agentId);
			completion.reject(codedError('NATIVE_COMPLETION_CANCELLED', `Native completion cancelled: ${String(reason).slice(0, 128)}`));
		}
		if (active !== undefined) {
			this.#actions.delete(agentId);
			active.reject(codedError('NATIVE_ACTION_CANCELLED', `Native action cancelled: ${String(reason).slice(0, 128)}`));
			try {
				await this.#bridge.send('action_cancel', agentId, { goalRevision: active.goalRevision, actionId: active.actionId });
			} catch {}
		}
		return active !== undefined || completion !== undefined;
	}

	async disposeAll(reason = 'coordinator_stopped') {
		await Promise.allSettled([...new Set([...this.#observations.keys(), ...this.#actions.keys(), ...this.#completions.keys()])].map((agentId) => this.dispose(agentId, reason)));
	}

	async #finish(request, record) {
		if (request.tool.status === 'impossible') {
			const result = { state: 'IMPOSSIBLE', verified: false, reasonCode: 'MODEL_REPORTED_IMPOSSIBLE' };
			await this.#onFinish({ record, request, result });
			return result;
		}
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');
		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-complete-${safeSegment(record.agentId)}-${record.goalRevision}-${ordinal}`.slice(0, 128));
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
		};
		const bound = bindCompletionContract(request.tool.completionContract, { goalRevision: record.goalRevision, traceId, profile });
		const completionContract = { goalRevision: bound.goalRevision, predicates: bound.predicates };
		let resolveCompletion;
		let rejectCompletion;
		const completion = new Promise((resolve, reject) => { resolveCompletion = resolve; rejectCompletion = reject; });
		this.#completions.set(record.agentId, {
			goalRevision: record.goalRevision,
			traceId,
			contractHash: bound.contractHash,
			resolve: resolveCompletion,
			reject: rejectCompletion,
		});
		try {
			await this.#bridge.send('goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				completionContract,
				traceId,
				profile,
				contractHash: bound.contractHash,
			});
		} catch (error) {
			this.#completions.delete(record.agentId);
			rejectCompletion(error);
		}
		const result = await completion;
		await this.#onFinish({ record, request, result });
		return result;
	}
}

function validateRecord(record) {
	if (record === null || typeof record !== 'object') throw new TypeError('record must be an object');
	for (const field of ['agentId', 'provider', 'model', 'reasoningEffort']) {
		if (typeof record[field] !== 'string' || record[field].length === 0) throw new TypeError(`record.${field} must be nonblank`);
	}
	if (!Number.isSafeInteger(record.goalRevision) || record.goalRevision < 0) throw new TypeError('record.goalRevision must be a nonnegative safe integer');
}

function validateRequest(request, record) {
	if (request === null || typeof request !== 'object') throw new TypeError('native tool request must be an object');
	if (request.agentId !== record.agentId || request.goalRevision !== record.goalRevision) throw codedError('STALE_NATIVE_TOOL', 'Native tool request does not match the active agent goal');
	if (typeof request.turnId !== 'string' || request.turnId.length === 0) throw new TypeError('native tool turnId must be nonblank');
	if (typeof request.callId !== 'string' || request.callId.length === 0) throw new TypeError('native tool callId must be nonblank');
	if (request.tool === null || typeof request.tool !== 'object') throw new TypeError('native tool must be normalized');
}

function safeSegment(value) { return String(value).replace(/[^A-Za-z0-9._:-]/g, '_') || 'item'; }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
