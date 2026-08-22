import { AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
import { normalizeRetryReason, validateTraceId } from './control-latency-registry.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { createProviderTurnTelemetry } from './provider-turn-telemetry.mjs';

const DEFAULT_INVALID_DECISION_RETRIES = 1;
const MAX_RETRY_ERROR_LENGTH = 512;
const RETRYABLE_DECISION_ERRORS = new Set([
	'EMPTY_DECISION',
	'MALFORMED_DECISION',
	'INVALID_DECISION',
	'UNKNOWN_DECISION_FIELD',
	'MISSING_DECISION_FIELD',
	'DECISION_FIELD_MISMATCH',
	'DUPLICATE_DECISION_FIELD',
]);
const RETRYABLE_PROVIDER_ERRORS = new Set([
	'PLANNING_TIMEOUT',
	'PROVIDER_UNAVAILABLE',
	'SPAWN_FAILED',
	// Codex app-server can complete a turn without emitting an agent-message
	// item. A single clean retry is safer than permanently erroring the agent.
	'MISSING_AGENT_MESSAGE',
	'MISSING_FINAL_MESSAGE',
]);
const QUIET_RETRYABLE_PROVIDER_ERRORS = new Set([
	'MISSING_AGENT_MESSAGE',
	'MISSING_FINAL_MESSAGE',
]);

export class AgentPlanner {
	#registry;
	#scheduler;
	#codexService;
	#invalidDecisionRetries;
	#healthRegistry;
	#latencyRegistry;
	#telemetrySink;
	#now;
	#recorder;

	constructor({
		registry,
		scheduler,
		codexService,
		invalidDecisionRetries = DEFAULT_INVALID_DECISION_RETRIES,
		healthRegistry = new ProviderHealthRegistry(),
		latencyRegistry = null,
		telemetrySink = () => {},
		now = () => performance.now(),
		recorder = null,
		benchmarkRecorder = null,
	}) {
		if (registry === null || registry === undefined) throw new TypeError('registry is required');
		if (scheduler === null || scheduler === undefined) throw new TypeError('scheduler is required');
		if (codexService === null || codexService === undefined) throw new TypeError('codexService is required');
		if (!Number.isSafeInteger(invalidDecisionRetries) || invalidDecisionRetries < 0) {
			throw new TypeError('invalidDecisionRetries must be a non-negative safe integer');
		}
		if (typeof healthRegistry?.canAttempt !== 'function' || typeof healthRegistry?.record !== 'function') throw new TypeError('healthRegistry must provide canAttempt and record');
		if (latencyRegistry !== null && typeof latencyRegistry.recordTracePhase !== 'function') throw new TypeError('latencyRegistry.recordTracePhase must be a function');
		if (typeof telemetrySink !== 'function') throw new TypeError('telemetrySink must be a function');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		const selectedRecorder = recorder ?? benchmarkRecorder;
		if (selectedRecorder !== null && typeof selectedRecorder.record !== 'function') throw new TypeError('recorder.record must be a function');
		this.#registry = registry;
		this.#scheduler = scheduler;
		this.#codexService = codexService;
		this.#invalidDecisionRetries = invalidDecisionRetries;
		this.#healthRegistry = healthRegistry;
		this.#latencyRegistry = latencyRegistry;
		this.#telemetrySink = telemetrySink;
		this.#now = now;
		this.#recorder = selectedRecorder;
	}

	get healthRegistry() { return this.#healthRegistry; }

	requestPlan({ agentId, input, goalRevision, recoverySummary = null, preserveState = false, priority = null, planningPriority = null, traceId: requestedTraceId = null }) {
		const record = this.#registry.assertCurrentRevision(agentId, goalRevision);
		const traceIdProvided = requestedTraceId !== null;
		const traceId = traceIdProvided ? validateTraceId(requestedTraceId) : defaultTraceId(agentId, goalRevision);
		const selectedPriority = planningPriority ?? priority ?? record.planningPriority ?? record.priority ?? 'ordinary';
		const queuedAt = this.#now();
		const trace = { retryReason: null, phasesRecorded: false };
		this.#record('planner_requested', record, { operation: 'plan', preserveState, retry: false, lane: record.provider, priority: selectedPriority, traceId });
		return this.#scheduler.schedule(agentId, async ({ signal }) => {
			const admittedAt = this.#now();
			const queueWaitMs = elapsed(queuedAt, admittedAt);
			this.#record('planner_admitted', record, { operation: 'plan', queueWaitMs, preserveState, lane: record.provider, priority: selectedPriority, traceId });
			this.#recordTracePhase(record, traceId, 'queue_wait', queuedAt, admittedAt, 'completed');
			this.#registry.assertCurrentRevision(agentId, goalRevision);
			if (!preserveState) this.#registry.setState(agentId, DynamicAgentState.PLANNING, { goalRevision });
			try {
				let agent;
				let initializationRetryCount = 0;
				while (true) {
					try {
						agent = await this.#providerAttempt(record, {
							operation: 'create_agent',
							attempt: initializationRetryCount + 1,
							queueWaitMs,
							retry: initializationRetryCount > 0,
							traceId,
						}, async () => {
							const created = await this.#codexService.createAgent(record, { recoverySummary });
							await created.setGoalRevision(goalRevision);
							return created;
						});
						break;
					} catch (error) {
						if (
							RETRYABLE_PROVIDER_ERRORS.has(error?.code)
							&& initializationRetryCount < 1
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							initializationRetryCount += 1;
							continue;
						}
						throw error;
					}
				}

				let retryCount = 0;
				let providerRetryCount = 0;
				let plannerInput = input;
				while (true) {
					try {
						const attempt = retryCount + providerRetryCount + 1;
						const decision = await this.#providerAttempt(record, {
							operation: 'decide', attempt, queueWaitMs, retry: attempt > 1, traceId,
						}, () => agent.decide(plannerInput, { goalRevision, signal }));
						this.#registry.assertCurrentRevision(agentId, goalRevision);
						const parseBoundary = this.#now();
						if (!trace.phasesRecorded) {
							this.#recordTracePhase(record, traceId, 'provider_first_byte', parseBoundary, parseBoundary, 'completed');
							this.#recordTracePhase(record, traceId, 'provider_final_byte', parseBoundary, parseBoundary, 'completed');
							this.#recordTracePhase(record, traceId, 'parse', parseBoundary, parseBoundary, 'completed', trace.retryReason);
							trace.phasesRecorded = true;
						}
						this.#record('planner_decision_completed', record, { operation: 'decide', attempt, queueWaitMs, directive: decision?.directive ?? null, traceId });
						return { ...decision, goalRevision, ...(traceIdProvided ? { traceId } : {}) };
					} catch (error) {
						if (
							RETRYABLE_DECISION_ERRORS.has(error?.code)
							&& retryCount < this.#invalidDecisionRetries
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							retryCount += 1;
							trace.retryReason = normalizeRetryReason(error?.code ?? 'INVALID_DECISION');
							plannerInput = buildCorrectiveRetryInput(input, error, retryCount);
							continue;
						}
						if (
							RETRYABLE_PROVIDER_ERRORS.has(error?.code)
							&& providerRetryCount < 1
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							providerRetryCount += 1;
							plannerInput = input;
							continue;
						}
						throw error;
					}
				}
			} catch (error) {
				this.#record('planner_failed', record, { operation: 'plan', errorCode: error?.code ?? 'PLANNING_FAILED', retry: true, traceId });
				if (
					error?.code !== 'STALE_PLAN'
					&& error?.code !== 'PLAN_CANCELLED'
					&& !QUIET_RETRYABLE_PROVIDER_ERRORS.has(error?.code)
					&& this.#isCurrent(agentId, goalRevision)
					&& !preserveState
				) {
					this.#registry.setState(agentId, DynamicAgentState.ERROR, {
						goalRevision,
						error: { code: String(error?.code ?? 'PLANNING_FAILED').slice(0, 128), message: String(error?.message ?? error).slice(0, 2_048) },
					});
				}
				throw error;
			}
		}, { lane: record.provider, priority: selectedPriority });
	}

	async #providerAttempt(record, fields, operation) {
		const healthIdentity = { provider: record.provider, model: record.model, operation: fields.operation };
		if (!this.#healthRegistry.canAttempt(healthIdentity)) {
			const error = new Error(`Provider circuit is open for '${record.provider}/${record.model}/${fields.operation}'`);
			error.code = 'PROVIDER_CIRCUIT_OPEN';
			this.#record('provider_attempt_rejected', record, { ...fields, operation: fields.operation, errorCode: error.code });
			throw error;
		}
		const startedAt = this.#now();
		this.#record('provider_request_started', record, { ...fields, operation: fields.operation });
		try {
			const result = await operation();
			const durationMs = elapsed(startedAt, this.#now());
			this.#record('provider_response_completed', record, { ...fields, operation: fields.operation, durationMs, errorCode: null });
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				durationMs,
				errorCode: null,
				retryReason: fields.retryReason,
				timeout: false,
				restart: false,
			}));
			return result;
		} catch (error) {
			const durationMs = elapsed(startedAt, this.#now());
			this.#record('provider_response_failed', record, { ...fields, operation: fields.operation, durationMs, errorCode: error?.code ?? 'ERROR' });
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				durationMs,
				error,
				retryReason: error?.code ?? 'ERROR',
				timeout: error?.code === 'PLANNING_TIMEOUT',
				restart: false,
			}));
			throw error;
		}
	}

	#publishTelemetry(telemetry) {
		this.#healthRegistry.record(telemetry);
		try { this.#telemetrySink(telemetry); } catch { /* telemetry consumers cannot fail planning */ }
	}

	#recordTracePhase(record, traceId, phase, startMs, endMs, outcome, retryReason = null) {
		if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) return;
		const fields = {
			traceId,
			phase,
			startMonotonicMs: startMs,
			endMonotonicMs: endMs,
			durationMs: Math.max(0, endMs - startMs),
			outcome,
			...(retryReason === null ? {} : { retryReason: normalizeRetryReason(retryReason) }),
		};
		this.#record(phase, record, fields);
		if (this.#latencyRegistry === null) return;
		try { this.#latencyRegistry.recordTracePhase(traceId, phase, { startMs, endMs, outcome, ...(retryReason === null ? {} : { retryReason }) }); }
		catch { /* a bounded telemetry sink cannot interrupt planning */ }
	}

	#record(stage, record, fields = {}) {
		if (this.#recorder === null) return;
		try {
			const context = {
				agentId: record.agentId,
				provider: record.provider,
				model: record.model,
				reasoningEffort: record.reasoningEffort,
				serviceTier: record.serviceTier ?? 'priority',
				goalRevision: record.goalRevision,
				...(fields.traceId === undefined ? {} : { traceId: fields.traceId }),
			};
			this.#recorder.record(stage, context, fields);
		} catch { /* benchmark telemetry cannot affect planning */ }
	}

	async interrupt(agentId, reason = 'Agent planning interrupted') {
		const scheduled = this.#scheduler.cancel(agentId, reason);
		if (scheduled) return;
		const agent = this.#codexService.getAgent(agentId);
		if (agent !== null) await agent.interrupt();
	}

	async remove(agentId) {
		this.#scheduler.cancel(agentId, 'Agent removed');
		await this.#codexService.removeAgent(agentId);
		return this.#registry.remove(agentId);
	}

	async reconcile(snapshot) {
		return (await this.beginReconcile(snapshot).complete);
	}

	beginReconcile(snapshot) {
		const result = this.#registry.reconcile(snapshot);
		for (const agentId of result.removed) this.#scheduler.cancel(agentId, 'Agent absent from reconciled server snapshot');
		const complete = Promise.resolve(this.#codexService.reconcile(result.records)).then((providers) =>
			({ registry: result, providers, codex: providers }));
		return { registry: result, complete };
	}

	#isCurrent(agentId, goalRevision) {
		try {
			this.#registry.assertCurrentRevision(agentId, goalRevision);
			return true;
		} catch (error) {
			if (error instanceof AgentRegistryError && (error.code === 'STALE_GOAL_REVISION' || error.code === 'UNKNOWN_AGENT')) return false;
			throw error;
		}
	}
}

function defaultTraceId(agentId, goalRevision) {
	return `trace-${String(agentId).replace(/[^A-Za-z0-9._:-]/g, '_')}-${goalRevision}`.slice(0, 128);
}

function elapsed(startedAt, finishedAt) {
	if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt)) throw new TypeError('planner clock must return finite values');
	return Math.max(0, Math.round(finishedAt - startedAt));
}

function buildCorrectiveRetryInput(input, error, retryCount) {
	const code = String(error?.code ?? 'INVALID_DECISION').slice(0, 128);
	const message = String(error?.message ?? error).replace(/\s+/g, ' ').slice(0, MAX_RETRY_ERROR_LENGTH);
	return `${input}\n\nThe previous planner response was rejected by the trusted runtime validator `
		+ `(corrective retry ${retryCount}). Error code: ${code}. Validation message: ${message}. `
		+ 'Return a fresh decision that exactly matches the required JSON schema. Do not repeat or discuss the invalid response.';
}
