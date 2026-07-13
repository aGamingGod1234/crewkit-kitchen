import { AgentState } from './agent-state.mjs';
import { buildPlannerInput } from './prompts.mjs';
import { RetryPolicy } from './retry-policy.mjs';
import { observationHash } from './trace-writer.mjs';

const STUCK_FAILURE_THRESHOLD = 3;
const RESTARTABLE_CODEX_FAILURES = new Set([
	'PROCESS_EXITED',
	'SPAWN_FAILED',
	'TRANSPORT_NOT_RUNNING',
	'TRANSPORT_STOPPED',
	'INVALID_RESPONSE',
]);

export class AgentRuntime {
	#config;
	#bridge;
	#codex;
	#traceWriter;
	#retryPolicy;
	#schedule;
	#cancelSchedule;
	#queue = Promise.resolve();
	#listeners = [];
	#started = false;
	#stopping = false;
	#state = AgentState.IDLE;
	#goal = null;
	#goalRevisionSequence = 0;
	#observation = null;
	#observationHash = null;
	#activeCommandId = null;
	#pendingTrigger = null;
	#planningRevision = 0;
	#retryHandle = null;
	#stuckFailures = 0;

	constructor({ config, bridge, codex, traceWriter, retryPolicy, schedule, cancelSchedule }) {
		this.#config = requireConfig(config);
		this.#bridge = requireDependency(bridge, 'bridge');
		this.#codex = requireDependency(codex, 'codex');
		this.#traceWriter = requireDependency(traceWriter, 'traceWriter');
		this.#retryPolicy = retryPolicy ?? new RetryPolicy();
		this.#schedule = schedule ?? ((callback, delay) => setTimeout(callback, delay));
		this.#cancelSchedule = cancelSchedule ?? clearTimeout;
	}

	get state() { return this.#state; }
	get agentId() { return this.#config.agentId; }

	async start() {
		if (this.#started) return;
		this.#stopping = false;
		await this.#codex.start();
		this.#bindBridgeEvents();
		this.#bridge.start();
		this.#started = true;
		await this.#trace('runtime_started');
	}

	async stop() {
		if (this.#stopping) return this.#queue;
		this.#stopping = true;
		if (this.#retryHandle !== null) {
			this.#cancelSchedule(this.#retryHandle);
			this.#retryHandle = null;
		}
		if (this.#activeCommandId !== null) {
			try { this.#bridge.cancelAction(this.#activeCommandId); } catch { /* bridge may already be gone */ }
			this.#activeCommandId = null;
		}
		for (const [event, listener] of this.#listeners) this.#bridge.off(event, listener);
		this.#listeners = [];
		try { await this.#codex.interrupt(); } catch { /* teardown continues */ }
		await this.#codex.stop();
		this.#bridge.stop();
		this.#state = AgentState.STOPPED;
		await this.#trace('runtime_stopped');
		await this.#queue;
		await this.#traceWriter.close();
		this.#started = false;
	}

	#bindBridgeEvents() {
		for (const event of ['ready', 'disconnect', 'goal_event', 'observation', 'action_result', 'significant_event', 'error']) {
			const listener = (payload) => this.#enqueue(() => this.#handleBridgeEvent(event, payload));
			this.#bridge.on(event, listener);
			this.#listeners.push([event, listener]);
		}
	}

	#enqueue(operation) {
		this.#queue = this.#queue.then(async () => {
			if (!this.#stopping) await operation();
		}).catch(async (error) => {
			this.#state = AgentState.ERROR;
			await this.#trace('runtime_error', { result: safeError(error) });
		});
		return this.#queue;
	}

	async #handleBridgeEvent(event, payload) {
		switch (event) {
			case 'ready':
				this.#state = this.#goal === null ? AgentState.IDLE : AgentState.RECOVERING;
				this.#bridge.requestObservation();
				await this.#trace('bridge_ready');
				break;
			case 'disconnect':
				this.#state = AgentState.RECOVERING;
				this.#activeCommandId = null;
				this.#pendingTrigger = 'bridge_reconnect';
				await this.#trace('bridge_disconnected');
				break;
			case 'goal_event':
				await this.#handleGoal(payload);
				break;
			case 'observation':
				this.#observation = payload;
				this.#observationHash = observationHash(payload);
				this.#pendingTrigger ??= 'observation';
				await this.#maybePlan();
				break;
			case 'action_result':
				await this.#handleActionResult(payload);
				break;
			case 'significant_event':
				this.#pendingTrigger = 'significant_event';
				await this.#trace('significant_event', { result: payload });
				await this.#maybePlan();
				break;
			case 'error':
				await this.#trace('bridge_error', { result: payload });
				break;
		}
	}

	async #handleGoal(event) {
		if (event.operation === 'stop') {
			await this.#stopGoal('goal_stop');
			return;
		}
		if (event.operation !== 'set' || typeof event.goal !== 'string' || event.goal.trim().length === 0) throw new Error('goal event is invalid');
		await this.#stopActiveAction();
		this.#planningRevision += 1;
		try { await this.#codex.interrupt(); } catch { /* stale turn completion is revision-gated */ }
		this.#goalRevisionSequence += 1;
		this.#goal = { revision: this.#goalRevisionSequence, text: event.goal };
		this.#observation = null;
		this.#observationHash = null;
		this.#stuckFailures = 0;
		this.#retryPolicy.reset();
		this.#state = AgentState.IDLE;
		this.#pendingTrigger = 'goal_event';
		this.#bridge.requestObservation();
		await this.#trace('goal_received');
	}

	async #stopGoal(eventName) {
		await this.#stopActiveAction();
		this.#planningRevision += 1;
		try { await this.#codex.interrupt(); } catch { /* stop remains authoritative */ }
		this.#goal = null;
		this.#pendingTrigger = null;
		this.#state = AgentState.STOPPED;
		await this.#trace(eventName);
	}

	async #stopActiveAction() {
		if (this.#activeCommandId === null) return;
		const commandId = this.#activeCommandId;
		this.#activeCommandId = null;
		try { this.#bridge.cancelAction(commandId); } catch (error) { await this.#trace('cancel_failed', { result: safeError(error) }); }
	}

	async #handleActionResult(result) {
		if (this.#activeCommandId === null || result.commandId !== this.#activeCommandId) {
			await this.#trace('unexpected_action_result', { result });
			return;
		}
		this.#activeCommandId = null;
		if (result.state === 'SUCCEEDED') {
			this.#stuckFailures = 0;
			this.#retryPolicy.reset();
		} else if (typeof result.reasonCode === 'string' && /STUCK|NO_PROGRESS/i.test(result.reasonCode)) {
			this.#stuckFailures += 1;
		}
		this.#state = this.#stuckFailures >= STUCK_FAILURE_THRESHOLD ? AgentState.RECOVERING : AgentState.IDLE;
		this.#pendingTrigger = 'action_result';
		await this.#trace('action_result', { result });
		await this.#maybePlan();
	}

	async #maybePlan() {
		if (this.#goal === null || this.#observation === null) return;
		if ([AgentState.PLANNING, AgentState.ACTING, AgentState.COMPLETED, AgentState.STOPPED, AgentState.ERROR].includes(this.#state)) return;
		const trigger = this.#pendingTrigger ?? 'planning_timeout';
		this.#pendingTrigger = null;
		this.#state = AgentState.PLANNING;
		const revision = ++this.#planningRevision;
		const input = buildPlannerInput({
			goal: { revision: this.#goal.revision, text: this.#goal.text },
			trigger,
			observation: this.#observation,
			recovery: { stuckFailures: this.#stuckFailures, active: this.#stuckFailures >= STUCK_FAILURE_THRESHOLD },
		});
		await this.#trace('planning_started');
		this.#codex.decide(input).then(
			(decision) => this.#enqueue(() => this.#handleDecision(revision, decision)),
			(error) => this.#enqueue(() => this.#handlePlanningFailure(revision, error)),
		);
	}

	async #handleDecision(revision, decision) {
		if (revision !== this.#planningRevision || this.#goal === null) return;
		this.#retryPolicy.reset();
		if (decision.action.type === 'complete_goal') {
			this.#state = AgentState.COMPLETED;
			this.#pendingTrigger = null;
			await this.#trace('goal_completed', { action: decision.action, result: { goalStatus: decision.goalStatus, summary: decision.summary } });
			return;
		}
		try {
			this.#activeCommandId = this.#bridge.sendAction(decision.action);
			this.#state = AgentState.ACTING;
			await this.#trace('action_dispatched', { action: decision.action, result: { commandId: this.#activeCommandId } });
		} catch (error) {
			await this.#handlePlanningFailure(revision, error);
		}
	}

	async #handlePlanningFailure(revision, error) {
		if (revision !== this.#planningRevision || this.#goal === null) return;
		this.#state = AgentState.RECOVERING;
		if (RESTARTABLE_CODEX_FAILURES.has(error?.code) && typeof this.#codex.restart === 'function') {
			try {
				await this.#codex.restart();
				await this.#trace('codex_restarted');
			} catch (restartError) {
				await this.#trace('codex_restart_failed', { result: safeError(restartError) });
			}
		}
		const delayMs = this.#retryPolicy.nextDelay();
		await this.#trace('planning_retry_scheduled', { result: { ...safeError(error), delayMs } });
		if (this.#retryHandle !== null) this.#cancelSchedule(this.#retryHandle);
		this.#retryHandle = this.#schedule(() => {
			this.#retryHandle = null;
			this.#enqueue(async () => {
				if (this.#goal === null) return;
				this.#state = AgentState.IDLE;
				this.#pendingTrigger = 'planning_timeout';
				await this.#maybePlan();
			});
		}, delayMs);
	}

	#trace(event, { action = null, result = null } = {}) {
		return this.#traceWriter.write({
			timestamp: new Date().toISOString(),
			agentId: this.#config.agentId,
			goalRevision: this.#goal?.revision ?? null,
			state: this.#state,
			event,
			observationHash: this.#observationHash,
			action,
			result,
			model: this.#config.model,
			effort: this.#config.reasoningEffort,
			serviceTier: this.#config.serviceTier,
		});
	}
}

function requireConfig(config) {
	if (config === null || typeof config !== 'object') throw new TypeError('runtime config must be an object');
	for (const field of ['agentId', 'model', 'reasoningEffort', 'serviceTier']) if (typeof config[field] !== 'string' || config[field].length === 0) throw new TypeError(`runtime config ${field} must be nonblank`);
	return { ...config };
}

function requireDependency(value, name) {
	if (value === null || value === undefined) throw new TypeError(`${name} is required`);
	return value;
}

function safeError(error) {
	return { code: typeof error?.code === 'string' ? error.code : 'ERROR', message: typeof error?.message === 'string' ? error.message : String(error) };
}
