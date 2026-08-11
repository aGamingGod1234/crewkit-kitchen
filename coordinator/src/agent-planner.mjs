import { AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
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
	'INVALID_ACTION',
	'STATUS_ACTION_MISMATCH',
]);
const RETRYABLE_PROVIDER_ERRORS = new Set([
	'PLANNING_TIMEOUT',
	'PROVIDER_UNAVAILABLE',
	'SPAWN_FAILED',
]);

export class AgentPlanner {
	#registry;
	#scheduler;
	#codexService;
	#invalidDecisionRetries;
	#healthRegistry;
	#telemetrySink;
	#now;

	constructor({
		registry,
		scheduler,
		codexService,
		invalidDecisionRetries = DEFAULT_INVALID_DECISION_RETRIES,
		healthRegistry = new ProviderHealthRegistry(),
		telemetrySink = () => {},
		now = () => performance.now(),
	}) {
		if (registry === null || registry === undefined) throw new TypeError('registry is required');
		if (scheduler === null || scheduler === undefined) throw new TypeError('scheduler is required');
		if (codexService === null || codexService === undefined) throw new TypeError('codexService is required');
		if (!Number.isSafeInteger(invalidDecisionRetries) || invalidDecisionRetries < 0) {
			throw new TypeError('invalidDecisionRetries must be a non-negative safe integer');
		}
		if (typeof healthRegistry?.canAttempt !== 'function' || typeof healthRegistry?.record !== 'function') throw new TypeError('healthRegistry must provide canAttempt and record');
		if (typeof telemetrySink !== 'function') throw new TypeError('telemetrySink must be a function');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		this.#registry = registry;
		this.#scheduler = scheduler;
		this.#codexService = codexService;
		this.#invalidDecisionRetries = invalidDecisionRetries;
		this.#healthRegistry = healthRegistry;
		this.#telemetrySink = telemetrySink;
		this.#now = now;
	}

	get healthRegistry() { return this.#healthRegistry; }

	requestPlan({ agentId, input, goalRevision, recoverySummary = null }) {
		const record = this.#registry.assertCurrentRevision(agentId, goalRevision);
		const queuedAt = this.#now();
		return this.#scheduler.schedule(agentId, async ({ signal }) => {
			const queueWaitMs = elapsed(queuedAt, this.#now());
			this.#registry.assertCurrentRevision(agentId, goalRevision);
			this.#registry.setState(agentId, DynamicAgentState.PLANNING, { goalRevision });
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
							operation: 'decide', attempt, queueWaitMs, retry: attempt > 1,
						}, () => agent.decide(plannerInput, { goalRevision, signal }));
						this.#registry.assertCurrentRevision(agentId, goalRevision);
						return { ...decision, goalRevision };
					} catch (error) {
						if (
							RETRYABLE_DECISION_ERRORS.has(error?.code)
							&& retryCount < this.#invalidDecisionRetries
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							retryCount += 1;
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
				if (error?.code !== 'STALE_PLAN' && error?.code !== 'PLAN_CANCELLED' && this.#isCurrent(agentId, goalRevision)) {
					this.#registry.setState(agentId, DynamicAgentState.ERROR, {
						goalRevision,
						error: { code: String(error?.code ?? 'PLANNING_FAILED').slice(0, 128), message: String(error?.message ?? error).slice(0, 2_048) },
					});
				}
				throw error;
			}
		});
	}

	async #providerAttempt(record, fields, operation) {
		const healthIdentity = { provider: record.provider, model: record.model, operation: fields.operation };
		if (!this.#healthRegistry.canAttempt(healthIdentity)) {
			const error = new Error(`Provider circuit is open for '${record.provider}/${record.model}/${fields.operation}'`);
			error.code = 'PROVIDER_CIRCUIT_OPEN';
			throw error;
		}
		const startedAt = this.#now();
		try {
			const result = await operation();
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				durationMs: elapsed(startedAt, this.#now()),
				errorCode: null,
				timeout: false,
				restart: false,
			}));
			return result;
		} catch (error) {
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				durationMs: elapsed(startedAt, this.#now()),
				error,
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
		const result = this.#registry.reconcile(snapshot);
		for (const agentId of result.removed) this.#scheduler.cancel(agentId, 'Agent absent from reconciled server snapshot');
		const providers = await this.#codexService.reconcile(result.records);
		return { registry: result, providers, codex: providers };
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
