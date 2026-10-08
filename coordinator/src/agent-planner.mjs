import { createHash } from 'node:crypto';

import { AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
import { normalizeRetryReason, validateTraceId } from './control-latency-registry.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { NATIVE_TOOL_PROVIDERS } from './provider-identity.mjs';
import { profileFingerprint } from './provider-session.mjs';
import { createProviderTurnTelemetry } from './provider-turn-telemetry.mjs';
import { recordProviderTurn } from './provider-turn-recorder.mjs';
import { DEFAULT_NATIVE_DECISION_TIMING_WINDOW, NativeDecisionTimingWindow } from './native-decision-timing.mjs';
import { reportVisibleOutput } from './verbose-output.mjs';
import { parseGoalSpecRequest } from './goal-spec.mjs';
import { GoalSpecTranslator } from './goal-spec-translator.mjs';
import { classifyRecoveryFailure } from './recovery-policy.mjs';
import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage } from './diagnostic-sanitizer.mjs';

const DEFAULT_INVALID_DECISION_RETRIES = 1;
const MAX_RETRY_ERROR_LENGTH = 512;
const MAX_VERBOSE_MESSAGE_LENGTH = 256;
const DEFAULT_PLANNING_LEASE_TIMEOUT_MS = 125_000;
const DEFAULT_NATIVE_TURN_BUDGET_MS = 900_000;
const TOOL_SETTLEMENT_GRACE_MS = 5_000;
const RETRYABLE_DECISION_ERRORS = new Set([
	'EMPTY_DECISION',
	'MALFORMED_DECISION',
	'INVALID_DECISION',
	'UNKNOWN_DECISION_FIELD',
	'MISSING_DECISION_FIELD',
	'DECISION_FIELD_MISMATCH',
	'DUPLICATE_DECISION_FIELD',
]);
const REFERENCE_KEY = /^[a-z0-9:_.-]{1,64}$/;

/**
 * Argument metadata for reference reads, so a trace answers whether the model read a
 * strategy topic and which topics taskPlan pointed at. Identifiers only; no content.
 */
export function nativeToolReferenceTraceFields(tool, result) {
	const key = (value) => typeof value === 'string' && REFERENCE_KEY.test(value) ? value : (value === undefined ? undefined : 'other');
	if (tool?.kind === 'capabilities') {
		const topic = key(tool.topic);
		return { toolKind: 'capabilities', section: key(tool.section) ?? 'all', ...(topic === undefined ? {} : { topic }),
			...(Number.isSafeInteger(tool.offset) ? { offset: tool.offset } : {}) };
	}
	if (tool?.kind === 'task_plan') {
		const strategy = result?.strategy;
		const topics = Array.isArray(strategy?.topics) ? strategy.topics.map(key).filter((topic) => topic !== undefined).slice(0, 8) : [];
		const steps = Array.isArray(result?.plan?.steps) ? result.plan.steps : [];
		return { toolKind: 'task_plan', operation: key(tool.operation) ?? 'read', planSteps: steps.length,
			openPlanSteps: steps.filter((step) => step?.status !== 'complete').length, strategyTopics: topics,
			...(key(strategy?.read?.arguments?.topic) === undefined ? {} : { strategyRead: key(strategy.read.arguments.topic) }) };
	}
	return null;
}

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
	#turnRecorder;
	#planningLeaseTimeoutMs;
	#nativeTurnBudgetMs;
	#nativeDecisionTimingWindowSize;
	#nativeDecisionTiming = new Map();
	#nativeTimingSink;
	#goalSpecHealth = new Map();

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
		turnRecorder = null,
		planningLeaseTimeoutMs = DEFAULT_PLANNING_LEASE_TIMEOUT_MS,
		nativeTurnBudgetMs = Math.max(DEFAULT_NATIVE_TURN_BUDGET_MS, planningLeaseTimeoutMs),
		nativeDecisionTimingWindowSize = DEFAULT_NATIVE_DECISION_TIMING_WINDOW,
		nativeTimingSink = null,
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
		if (turnRecorder !== null && (typeof turnRecorder !== 'object' || typeof turnRecorder.record !== 'function')) throw new TypeError('turnRecorder must provide record or be null');
		if (!Number.isSafeInteger(planningLeaseTimeoutMs) || planningLeaseTimeoutMs <= 0) throw new TypeError('planningLeaseTimeoutMs must be a positive safe integer');
		if (!Number.isSafeInteger(nativeTurnBudgetMs) || nativeTurnBudgetMs < planningLeaseTimeoutMs) throw new TypeError('nativeTurnBudgetMs must be a safe integer at least planningLeaseTimeoutMs');
		if (!Number.isSafeInteger(nativeDecisionTimingWindowSize) || nativeDecisionTimingWindowSize < 1 || nativeDecisionTimingWindowSize > DEFAULT_NATIVE_DECISION_TIMING_WINDOW * 4) {
			throw new TypeError(`nativeDecisionTimingWindowSize must be a safe integer in [1, ${DEFAULT_NATIVE_DECISION_TIMING_WINDOW * 4}]`);
		}
		if (nativeTimingSink !== null && typeof nativeTimingSink !== 'function') throw new TypeError('nativeTimingSink must be a function or null');
		this.#registry = registry;
		this.#scheduler = scheduler;
		this.#codexService = codexService;
		this.#invalidDecisionRetries = invalidDecisionRetries;
		this.#healthRegistry = healthRegistry;
		this.#latencyRegistry = latencyRegistry;
		this.#telemetrySink = telemetrySink;
		this.#now = now;
		this.#recorder = selectedRecorder;
		this.#turnRecorder = turnRecorder;
		this.#planningLeaseTimeoutMs = planningLeaseTimeoutMs;
		this.#nativeTurnBudgetMs = nativeTurnBudgetMs;
		this.#nativeDecisionTimingWindowSize = nativeDecisionTimingWindowSize;
		this.#nativeTimingSink = nativeTimingSink;
	}

	get healthRegistry() { return this.#healthRegistry; }
	getExecutionSettings(agentId) {
		const settings = typeof this.#codexService.getExecutionSettings === 'function'
			? this.#codexService.getExecutionSettings(agentId)
			: currentProviderAgent(this.#codexService, agentId)?.executionSettings;
		if (settings === undefined || settings === null) return null;
		return { ...structuredClone(settings), limits: { planningLeaseTimeoutMs: this.#planningLeaseTimeoutMs,
			...(settings.controlProtocol === 'native_tools' ? { nativeTurnBudgetMs: this.#nativeTurnBudgetMs } : {}) } };
	}

	/**
	 * Returns recent provider-only native decision timing for one agent.
	 * Segment percentiles exclude time spent awaiting a tool result. The
	 * first-tool fields are separate because usable-result elapsed time includes
	 * tool execution and is not a provider-wait estimate.
	 */
	getNativeDecisionTiming(agentId) {
		const entry = this.#nativeDecisionTiming.get(agentId);
		if (entry === undefined) return emptyNativeDecisionTiming();
		const identity = currentNativeTimingIdentity(this.#registry, this.#codexService, agentId);
		const key = identity === null ? entry.activeKey : nativeTimingIdentityKey(identity);
		const window = key === null ? null : entry.byIdentity.get(key);
		return window === undefined ? emptyNativeDecisionTiming(identity) : window.window.snapshot(window.identity);
	}

	requestGoalSpec({ agentId, request, correctiveFeedback = null }) {
		const record = this.#registry.get(agentId);
		if (record === null || record === undefined) throw codedError('UNKNOWN_AGENT', `Agent '${agentId}' is not registered`);
		const checkedRequest = parseGoalSpecRequest(request);
		const translatorId = goalSpecTranslatorId(agentId, checkedRequest.requestId);
		// Goal translation is an auxiliary advisory session. The registered agent
		// keeps its own provider, model, context, and sole control of game actions.
		const translatorProfile = goalSpecTranslatorProfile(record, translatorId);
		let health = this.#goalSpecHealth.get(translatorId);
		if (health === undefined) {
			health = { profiles: new Map(), inFlight: 0, executing: 0, retired: false };
			this.#goalSpecHealth.set(translatorId, health);
		}
		health.profiles.set(profileFingerprint(translatorProfile), translatorProfile);
		health.inFlight += 1;
		const queuedAt = this.#now();
		const translator = new GoalSpecTranslator({
			generate: ({ prompt, schema }) => this.#scheduler.schedule(translatorId, async ({ signal }) => {
				const queueWaitMs = elapsed(queuedAt, this.#now());
				health.executing += 1;
				let agent = null;
				try {
					agent = await this.#providerAttempt(translatorProfile, {
						operation: 'goal_spec_create', attempt: 1, queueWaitMs, retry: false, traceId: checkedRequest.requestId,
					}, () => this.#codexService.createAgent(translatorProfile, { recoverySummary: null, controlProtocol: 'goal_spec' }));
					await agent.setGoalRevision(0);
					return await this.#providerAttempt(translatorProfile, {
						operation: 'goal_spec', attempt: 1, queueWaitMs, retry: false, traceId: checkedRequest.requestId,
					}, () => agent.decide(prompt, {
						goalRevision: 0,
						signal,
						outputSchema: schema,
						parseOutput: parseGoalSpecJson,
						systemPrompt: '',
						...(this.#turnRecorder === null ? {} : { turnRecorder: this.#turnRecorder,
							attempt: (correctiveFeedback?.attempt ?? 0) + 1, retry: correctiveFeedback !== null,
							queueWaitMs }),
					}));
				} finally {
					try { await this.#codexService.removeAgent(translatorId); } catch { /* transient cleanup is best effort */ }
					health.executing -= 1;
					this.#releaseGoalSpecHealth(translatorId, health);
				}
			}, { lane: translatorProfile.provider, priority: 'ordinary', capacityClass: 'auxiliary' }),
		});
		return translator.translate(checkedRequest, { correctiveFeedback }).then((result) => {
			health.retired = true;
			return result;
		}).finally(() => {
			health.inFlight -= 1;
			this.#releaseGoalSpecHealth(translatorId, health);
		});
	}

	cancelGoalSpec(agentId, requestId) {
		const translatorId = goalSpecTranslatorId(agentId, requestId);
		const health = this.#goalSpecHealth.get(translatorId);
		if (health !== undefined) health.retired = true;
		try { return this.#scheduler.cancel(translatorId, 'Goal translation was cancelled'); }
		finally { if (health !== undefined) this.#releaseGoalSpecHealth(translatorId, health); }
	}

	#releaseGoalSpecHealth(translatorId, health) {
		// Failed attempts retain their circuit until success or explicit request
		// retirement. Wait for settlement so late telemetry cannot recreate it.
		if (!health.retired || health.inFlight !== 0 || health.executing !== 0) return;
		for (const [fingerprint, profile] of health.profiles) {
			for (const operation of ['goal_spec_create', 'goal_spec']) {
				this.#healthRegistry.forget?.({ provider: profile.provider, model: profile.model, profileFingerprint: fingerprint, operation });
			}
		}
		if (this.#goalSpecHealth.get(translatorId) === health) this.#goalSpecHealth.delete(translatorId);
	}

	requestNativeTurn({ agentId, input, goalRevision, executeTool, recoverySummary = null, preserveState = false, priority = 'ordinary', traceId: requestedTraceId = null, onVerbose = null, onProgress = null }) {
		const record = this.#registry.assertCurrentRevision(agentId, goalRevision);
		if (!NATIVE_TOOL_PROVIDERS.includes(record.provider)) throw codedError('NATIVE_TOOLS_UNAVAILABLE', 'Native Minecraft tools are currently available for Codex and Claude agents only');
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('native turn input must be nonblank');
		if (typeof executeTool !== 'function') throw new TypeError('executeTool must be a function');
		if (onProgress !== null && typeof onProgress !== 'function') throw new TypeError('onProgress must be a function or null');
		const traceId = requestedTraceId === null ? defaultTraceId(agentId, goalRevision) : validateTraceId(requestedTraceId);
		const queuedAt = this.#now();
		let leaseAgent = null;
		safeVerbose(onVerbose, 'planner', `Native turn queued with ${priority} priority.`);
		this.#record('planner_requested', record, { operation: 'native_turn', preserveState, retry: false, lane: record.provider, priority, traceId });
		return this.#scheduler.schedule(agentId, async ({ signal, renewLease = () => false }) => {
			const admittedAt = this.#now();
			const queueWaitMs = elapsed(queuedAt, admittedAt);
			safeVerbose(onVerbose, 'planner', 'Native turn admitted by the planning scheduler.');
			this.#traceNativeTiming(record, 'native_turn_admitted', { traceId, queueWaitMs });
			this.#recordTracePhase(record, traceId, 'queue_wait', queuedAt, admittedAt, 'completed');
			this.#registry.assertCurrentRevision(agentId, goalRevision);
			if (!preserveState) this.#registry.setState(agentId, DynamicAgentState.PLANNING, { goalRevision });
			leaseAgent = currentProviderAgent(this.#codexService, agentId);
			let acceptsProgress = true;
			try {
				const agent = await this.#providerAttempt(record, {
					operation: 'create_agent', attempt: 1, queueWaitMs, retry: false, traceId,
				}, async () => {
					const created = await this.#codexService.createAgent(record, { recoverySummary, controlProtocol: 'native_tools' });
					await created.setGoalRevision(goalRevision);
					return created;
				}, null, onVerbose);
				leaseAgent = agent;
				const timingIdentity = nativeTimingIdentity(record, agent);
				const timingWindow = this.#nativeTimingWindow(agentId, timingIdentity);
				let firstToolAt = null;
				let firstUsableToolRecorded = false;
				const pendingTools = new Set();
				const renewToolLease = () => {
					// Concurrent reads/control calls own separate deadlines. Keep the
					// earliest pending deadline so neither starts nor settlements grant
					// fresh time to older work, and remove settled tools from the lease.
					let deadline = Infinity;
					for (const pending of pendingTools) deadline = Math.min(deadline, pending.deadline);
					renewLease({ phase: 'tool', timeoutMs: Math.max(1, Math.ceil(deadline - this.#now())) });
				};
				let providerSegmentStartedAt = null;
				let providerSegmentIndex = 0;
				let nativeTurnStartedAt = null;
				const beginProviderSegment = (startMs) => {
					if (!Number.isFinite(startMs)) return;
					providerSegmentStartedAt = startMs;
					providerSegmentIndex += 1;
				};
				const finishProviderSegment = (endMs, outcome, boundary) => {
					if (providerSegmentStartedAt === null || !Number.isFinite(endMs) || endMs < providerSegmentStartedAt) return null;
					const startedAt = providerSegmentStartedAt;
					const durationMs = endMs - startedAt;
					providerSegmentStartedAt = null;
					timingWindow.window.recordProviderSegment({ durationMs, outcome, sample: outcome === 'completed' && boundary === 'tool_request' });
					const summary = timingWindow.window.snapshot(timingIdentity);
					this.#recordNativeTiming(record, 'native_decision_timing', {
						traceId, segmentIndex: providerSegmentIndex, segmentDurationMs: Math.max(0, durationMs), outcome, boundary,
						...timingIdentity,
						timingCount: summary.count, timingLifetimeCount: summary.lifetimeCount, timingP50Ms: summary.p50Ms, timingP95Ms: summary.p95Ms,
						timingSampleWindowSize: summary.sampleWindowSize, timingLifetimeSegmentCount: summary.lifetimeSegmentCount,
						failedSegmentCount: summary.failedSegmentCount, cancelledSegmentCount: summary.cancelledSegmentCount,
					});
					return durationMs;
				};
				const recordToolRequest = (request, requestedAt) => {
					const segmentDurationMs = finishProviderSegment(requestedAt, 'completed', 'tool_request');
					if (firstToolAt !== null) return;
					firstToolAt = requestedAt;
					const elapsedMs = elapsed(nativeTurnStartedAt, requestedAt);
					timingWindow.window.recordFirstToolRequest(elapsedMs);
					this.#recordNativeTiming(record, 'native_first_tool_requested', {
						traceId, elapsedMs, providerOnlyMs: elapsedMs, segmentDurationMs,
						toolKind: request?.tool?.kind ?? request?.toolName ?? 'unknown', ...timingIdentity,
					});
				};
				renewLease({ phase: 'provider' });
				const result = await this.#providerAttempt(record, {
					operation: 'native_turn', attempt: 1, queueWaitMs, retry: false, traceId,
				}, async () => {
					nativeTurnStartedAt = this.#now();
					beginProviderSegment(nativeTurnStartedAt);
					this.#traceNativeTiming(record, 'native_provider_turn_started', { traceId, agentReadyMs: elapsed(admittedAt, nativeTurnStartedAt) });
					try {
						const turnResult = await agent.act(input, {
							goalRevision,
							signal,
							onVerbose: (stage, message) => safeVerbose(onVerbose, stage, message),
							onProgress: (metadata) => {
								if (!acceptsProgress || signal.aborted || !this.#isCurrent(agentId, goalRevision)) return;
								if (metadata?.phase === 'tool_queued' && Number.isFinite(metadata.requestArrivedAt)) {
									recordToolRequest(metadata, metadata.requestArrivedAt);
								}
								if (pendingTools.size === 0) renewLease({ phase: 'provider' });
								try { Promise.resolve(onProgress?.({ phase: 'provider' })).catch(() => {}); } catch { /* reporting cannot fail provider work */ }
							},
							executeTool: async (request) => {
								const executorEnteredAt = this.#now();
								const hasArrival = Number.isFinite(request?.requestArrivedAt);
								recordToolRequest(request, hasArrival ? request.requestArrivedAt : executorEnteredAt);
								this.#recordNativeTiming(record, 'native_tool_queue_timing', {
									traceId, callId: request?.callId ?? null, turnId: request?.turnId ?? null, toolKind: request?.tool?.kind ?? 'unknown',
									queueWaitMs: Number.isFinite(request?.queueWaitMs) ? request.queueWaitMs : null,
									arrivalObserved: hasArrival, ...timingIdentity,
								});
								const pending = { deadline: executorEnteredAt + nativeToolLeaseMs(request.tool, this.#planningLeaseTimeoutMs) };
								pendingTools.add(pending);
								renewToolLease();
								try {
									if (signal.aborted) throw signal.reason;
									const toolResult = await executeTool(request);
									this.#traceNativeTiming(record, 'native_tool_executed', {
										traceId, callId: request?.callId ?? null, toolKind: request?.tool?.kind ?? 'unknown',
										executeMs: elapsed(executorEnteredAt, this.#now()), state: typeof toolResult?.state === 'string' ? toolResult.state.slice(0, 64) : null,
									});
									const reference = nativeToolReferenceTraceFields(request?.tool, toolResult);
									if (reference !== null) this.#recordNativeTiming(record, 'native_tool_reference', { traceId, callId: request?.callId ?? null, ...reference, ...timingIdentity });
									if (!firstUsableToolRecorded && isUsableNativeToolResult(toolResult)) {
										firstUsableToolRecorded = true;
										const usableAt = this.#now();
										const elapsedMs = elapsed(nativeTurnStartedAt, usableAt);
										timingWindow.window.recordFirstUsableTool(elapsedMs);
										this.#recordNativeTiming(record, 'native_first_tool_result', {
											traceId, elapsedMs, elapsedIncludesToolExecution: true,
											toolKind: request?.tool?.kind ?? 'unknown', ...timingIdentity,
										});
									}
									return toolResult;
								} finally {
									pendingTools.delete(pending);
									if (pendingTools.size === 0) {
										renewLease({ phase: 'provider' });
										if (!signal.aborted) beginProviderSegment(this.#now());
									} else {
										renewToolLease();
									}
								}
							},
						});
						finishProviderSegment(this.#now(), 'completed', 'turn_completed');
						return turnResult;
					} catch (error) {
						finishProviderSegment(this.#now(), signal.aborted || isNativeCancellation(error) ? 'cancelled' : 'failed', signal.aborted || isNativeCancellation(error) ? 'cancelled' : 'turn_failed');
						throw error;
					}
				}, agent, onVerbose);
				this.#record('planner_decision_completed', record, { operation: 'native_turn', attempt: 1, queueWaitMs, directive: 'native_tools', traceId });
				this.#recordNativeTurn(record, leaseAgent, admittedAt, queueWaitMs, traceId, result?.nativeTurn);
				safeVerbose(onVerbose, 'decision', 'Native provider turn completed.');
				return result;
			} catch (error) {
				this.#recordNativeTurn(record, leaseAgent, admittedAt, queueWaitMs, traceId, error?.nativeTurn, error);
				this.#record('planner_failed', record, { operation: 'native_turn', errorCode: error?.code ?? 'NATIVE_TURN_FAILED', retry: false, traceId });
				safeVerbose(onVerbose, 'error', verboseErrorMessage('Native turn failed', error));
				throw error;
			} finally {
				acceptsProgress = false;
			}
		}, {
			lane: record.provider,
			priority,
			leaseTimeoutMs: this.#planningLeaseTimeoutMs,
			maxLeaseDurationMs: this.#nativeTurnBudgetMs,
			onLeaseExpired: () => this.#replaceExactSession(record, leaseAgent, 'native_tools', 'planning_lease_expired'),
		});
	}

	#recordNativeTurn(record, agent, startedAt, queueWaitMs, traceId, nativeTurn = null, error = null) {
		recordProviderTurn(this.#turnRecorder, {
			agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort,
			goalRevision: record.goalRevision, attempt: 1, retry: false, traceId, output: '', error,
			...readSessionFields(agent), ...nativeTurnEvidence(nativeTurn ?? {
				threadId: null, turnId: null,
				tokens: { input: null, output: null, reasoning: null, cached: null, cacheWrite: null },
				usage: { scope: 'observed_thread_counter_delta', status: 'missing', start: null, end: null, updates: 0, counterReset: false, attributionComplete: false, gapBefore: null },
			}), input: nativeTurn?.input ?? '',
			...readExecutionSettings(agent), timing: { durationMs: elapsed(startedAt, this.#now()), apiDurationMs: null, queueWaitMs },
		});
	}

	async steerNativeTurn({ agentId, input, goalRevision, onInterrupt = null }) {
		const record = this.#registry.assertCurrentRevision(agentId, goalRevision);
		if (!NATIVE_TOOL_PROVIDERS.includes(record.provider)) throw codedError('NATIVE_TOOLS_UNAVAILABLE', 'Native Minecraft tools are currently available for Codex and Claude agents only');
		if (!(typeof input === 'function' || typeof input === 'string' && input.trim().length > 0)) throw new TypeError('native steer input must be nonblank or a builder');
		if (onInterrupt !== null && typeof onInterrupt !== 'function') throw new TypeError('onInterrupt must be a function or null');
		const agent = this.#codexService.getAgent(agentId);
		if (agent === null) throw codedError('TURN_NOT_ACTIVE', `Agent '${agentId}' has no active native turn`);
		return agent.steer(input, onInterrupt === null ? { goalRevision } : { goalRevision, onInterrupt });
	}

	requestPlan({ agentId, input, prepareInput = null, goalRevision, recoverySummary = null, preserveState = false, priority = null, planningPriority = null, traceId: requestedTraceId = null, onVerbose = null }) {
		const record = this.#registry.assertCurrentRevision(agentId, goalRevision);
		const traceIdProvided = requestedTraceId !== null;
		const traceId = traceIdProvided ? validateTraceId(requestedTraceId) : defaultTraceId(agentId, goalRevision);
		const selectedPriority = planningPriority ?? priority ?? record.planningPriority ?? record.priority ?? 'ordinary';
		const queuedAt = this.#now();
		const trace = { retryReason: null, phasesRecorded: false };
		let leaseAgent = null;
		safeVerbose(onVerbose, 'planner', `Planning request queued with ${selectedPriority} priority.`);
		this.#record('planner_requested', record, { operation: 'plan', preserveState, retry: false, lane: record.provider, priority: selectedPriority, traceId });
		return this.#scheduler.schedule(agentId, async ({ signal }) => {
			const admittedAt = this.#now();
			const queueWaitMs = elapsed(queuedAt, admittedAt);
			safeVerbose(onVerbose, 'planner', 'Planning request admitted by the scheduler.');
			this.#record('planner_admitted', record, { operation: 'plan', queueWaitMs, preserveState, lane: record.provider, priority: selectedPriority, traceId });
			this.#recordTracePhase(record, traceId, 'queue_wait', queuedAt, admittedAt, 'completed');
			this.#registry.assertCurrentRevision(agentId, goalRevision);
			if (!preserveState) this.#registry.setState(agentId, DynamicAgentState.PLANNING, { goalRevision });
			leaseAgent = currentProviderAgent(this.#codexService, agentId);
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
							const created = await this.#codexService.createAgent(record, { recoverySummary, controlProtocol: 'arena_script' });
							await created.setGoalRevision(goalRevision);
							leaseAgent = created;
							return created;
						}, null, onVerbose);
						break;
					} catch (error) {
						const recovery = classifyRecoveryFailure(error);
						if (
							recovery.immediateRetry
							&& initializationRetryCount < 1
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							initializationRetryCount += 1;
							safeVerbose(onVerbose, 'retry', verboseErrorMessage('Retrying provider initialization', error));
							const replacement = await this.#replaceExactSession(record, leaseAgent, 'arena_script', 'provider_initialization_retry');
							if (replacement !== null) {
								agent = replacement;
								leaseAgent = replacement;
								await replacement.setGoalRevision(goalRevision);
								break;
							}
							continue;
						}
						throw error;
					}
				}

				let retryCount = 0;
				let providerRetryCount = 0;
				let prepared = prepareInput === null ? { input, contextReceipt: null } : prepareInput(agent);
				let plannerInput = prepared.input;
				while (true) {
					try {
						const attempt = retryCount + providerRetryCount + 1;
						const decision = await this.#providerAttempt(record, {
							operation: 'decide', attempt, queueWaitMs, retry: attempt > 1, traceId,
						}, () => agent.decide(plannerInput, {
							goalRevision,
							signal,
							onVerbose: (stage, message) => safeVerbose(onVerbose, stage, message),
							...(this.#turnRecorder === null ? {} : { turnRecorder: this.#turnRecorder, attempt, retry: attempt > 1, queueWaitMs }),
						}), agent, onVerbose);
						safeVerbose(onVerbose, 'output', 'Provider returned a planner response.');
						this.#registry.assertCurrentRevision(agentId, goalRevision);
						const parseBoundary = this.#now();
						if (!trace.phasesRecorded) {
							this.#recordTracePhase(record, traceId, 'provider_first_byte', parseBoundary, parseBoundary, 'completed');
							this.#recordTracePhase(record, traceId, 'provider_final_byte', parseBoundary, parseBoundary, 'completed');
							this.#recordTracePhase(record, traceId, 'parse', parseBoundary, parseBoundary, 'completed', trace.retryReason);
							trace.phasesRecorded = true;
						}
						this.#record('planner_decision_completed', record, { operation: 'decide', attempt, queueWaitMs, directive: decision?.directive ?? null, traceId });
						safeVerbose(onVerbose, 'decision', `Planner decision accepted with directive '${decision?.directive ?? 'unknown'}'.`);
						return { ...decision, goalRevision, ...(prepared.contextReceipt === null ? {} : { contextReceipt: prepared.contextReceipt }), ...(traceIdProvided ? { traceId } : {}) };
					} catch (error) {
						const recovery = classifyRecoveryFailure(error);
						if (
							RETRYABLE_DECISION_ERRORS.has(error?.code)
							&& retryCount < this.#invalidDecisionRetries
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							retryCount += 1;
							trace.retryReason = normalizeRetryReason(error?.code ?? 'INVALID_DECISION');
							safeVerbose(onVerbose, 'retry', verboseErrorMessage(`Corrective decision retry ${retryCount}`, error));
							plannerInput = buildCorrectiveRetryInput(prepared.input, error, retryCount);
							continue;
						}
						if (
							recovery.immediateRetry
							&& providerRetryCount < 1
							&& this.#isCurrent(agentId, goalRevision)
							&& !signal.aborted
						) {
							providerRetryCount += 1;
							safeVerbose(onVerbose, 'retry', verboseErrorMessage(`Provider retry ${providerRetryCount}`, error));
							const replacement = await this.#replaceExactSession(record, agent, 'arena_script', 'provider_turn_retry');
							if (replacement !== null) {
								agent = replacement;
								leaseAgent = replacement;
								await replacement.setGoalRevision(goalRevision);
							}
							prepared = prepareInput === null ? { input, contextReceipt: null } : prepareInput(agent);
							plannerInput = prepared.input;
							continue;
						}
						throw error;
					}
				}
			} catch (error) {
				const recovery = classifyRecoveryFailure(error);
				this.#record('planner_failed', record, { operation: 'plan', errorCode: error?.code ?? 'PLANNING_FAILED', retry: true, traceId });
				safeVerbose(onVerbose, 'error', verboseErrorMessage('Planning failed', error));
				if (
					error?.code !== 'STALE_PLAN'
					&& error?.code !== 'PLAN_CANCELLED'
					&& !recovery.quiet
					&& !recovery.retryable
					&& this.#isCurrent(agentId, goalRevision)
					&& !preserveState
				) {
					this.#registry.setState(agentId, DynamicAgentState.ERROR, {
						goalRevision,
						error: { code: sanitizeDiagnosticErrorCode(error, { fallback: 'PLANNING_FAILED' }), message: sanitizeDiagnosticErrorMessage(error, { maxBytes: 2_048 }) },
					});
				}
				throw error;
			}
		}, {
			lane: record.provider,
			priority: selectedPriority,
			leaseTimeoutMs: this.#planningLeaseTimeoutMs,
			onLeaseExpired: () => this.#replaceExactSession(record, leaseAgent, 'arena_script', 'planning_lease_expired'),
		});
	}

	async #providerAttempt(record, fields, operation, sessionAgent = null, onVerbose = null) {
		const healthIdentity = { provider: record.provider, model: record.model, operation: fields.operation, profileFingerprint: profileFingerprint(record) };
		if (!this.#healthRegistry.canAttempt(healthIdentity)) {
			const error = new Error(`Provider circuit is open for '${record.provider}/${record.model}/${fields.operation}'`);
			error.code = 'PROVIDER_CIRCUIT_OPEN';
			const deadline = this.#healthRegistry.snapshot?.(healthIdentity)?.nextProbeAtEpochMs;
			if (Number.isFinite(deadline)) error.nextProbeAtEpochMs = deadline;
			this.#record('provider_attempt_rejected', record, { ...fields, operation: fields.operation, errorCode: error.code });
			safeVerbose(onVerbose, 'error', `Provider circuit rejected ${fields.operation}.`);
			throw error;
		}
		const startedAt = this.#now();
		this.#record('provider_request_started', record, { ...fields, ...readExecutionSettings(sessionAgent), operation: fields.operation });
		safeVerbose(onVerbose, 'provider', `Provider ${fields.operation} request started (attempt ${fields.attempt}).`);
		try {
			const result = await operation();
			const sessionFields = { profileFingerprint: healthIdentity.profileFingerprint, ...readSessionFields(sessionAgent ?? result) };
			const durationMs = elapsed(startedAt, this.#now());
			this.#record('provider_response_completed', record, { ...fields, ...sessionFields, ...nativeTurnEvidence(result?.nativeTurn), ...readExecutionSettings(sessionAgent ?? result), operation: fields.operation, durationMs, errorCode: null });
			safeVerbose(onVerbose, 'provider', `Provider ${fields.operation} request completed.`);
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				...sessionFields,
				...nativeTurnEvidence(result?.nativeTurn),
				durationMs,
				errorCode: null,
				retryReason: fields.retryReason,
				timeout: false,
				restart: false,
			}));
			return result;
		} catch (error) {
			const sessionFields = { ...readSessionFields(sessionAgent), profileFingerprint: healthIdentity.profileFingerprint };
			const durationMs = elapsed(startedAt, this.#now());
			this.#record('provider_response_failed', record, { ...fields, ...sessionFields, ...nativeTurnEvidence(error?.nativeTurn), ...readExecutionSettings(sessionAgent), operation: fields.operation, durationMs, errorCode: error?.code ?? 'ERROR' });
			safeVerbose(onVerbose, 'provider', verboseErrorMessage(`Provider ${fields.operation} request failed`, error));
			this.#publishTelemetry(createProviderTurnTelemetry({
				provider: record.provider,
				model: record.model,
				...fields,
				...sessionFields,
				...nativeTurnEvidence(error?.nativeTurn),
				durationMs,
				error,
				retryReason: safeRetryReason(error?.code),
				timeout: error?.code === 'PLANNING_TIMEOUT',
				restart: false,
			}));
			throw error;
		}
	}

	async #replaceExactSession(record, expectedAgent, controlProtocol, resetReason) {
		if (typeof this.#codexService.replaceAgent !== 'function') return null;
		const capturedFingerprint = profileFingerprint(record);
		const latestRecord = this.#registry.assertCurrentRevision(record.agentId, record.goalRevision);
		if (profileFingerprint(latestRecord) !== capturedFingerprint) {
			throw codedError('SESSION_PROFILE_MISMATCH', 'The selected agent profile changed before recovery');
		}
		const expectedFingerprint = sessionProfileFingerprint(expectedAgent);
		if (expectedFingerprint !== null && expectedFingerprint !== capturedFingerprint) {
			throw codedError('SESSION_PROFILE_MISMATCH', 'The failed provider session does not own the selected profile');
		}
		const current = typeof this.#codexService.getAgent === 'function' ? this.#codexService.getAgent(record.agentId) : null;
		if (expectedAgent === null && current !== null) return current;
		if (current !== null && expectedAgent !== null && current !== expectedAgent) {
			if (sessionProfileFingerprint(current) !== capturedFingerprint) {
				throw codedError('SESSION_PROFILE_MISMATCH', 'The current provider session does not own the selected profile');
			}
			return current;
		}
		const owned = expectedAgent ?? current;
		return this.#codexService.replaceAgent(record, {
			recoverySummary: latestRecord.lastSummary ?? null,
			resetReason,
			controlProtocol,
			...(Number.isSafeInteger(owned?.sessionGeneration) ? { expectedSessionGeneration: owned.sessionGeneration } : {}),
		});
	}

	#publishTelemetry(telemetry) {
		this.#healthRegistry.record(telemetry);
		try {
			if (typeof this.#scheduler.observeProviderTelemetry === 'function') {
				this.#scheduler.observeProviderTelemetry(telemetry, this.#scheduler.pressureSnapshot);
			}
		} catch { /* adaptive admission feedback cannot fail planning */ }
		try { this.#telemetrySink(telemetry); } catch { /* telemetry consumers cannot fail planning */ }
	}

	#recordTracePhase(record, traceId, phase, startMs, endMs, outcome, retryReason = null) {
		if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs < startMs) {
			this.#invalidateTrace(traceId);
			return;
		}
		let normalizedRetryReason = null;
		if (retryReason !== null) {
			try { normalizedRetryReason = normalizeRetryReason(retryReason); }
			catch { this.#invalidateTrace(traceId); return; }
		}
		const fields = {
			traceId,
			phase,
			startMonotonicMs: startMs,
			endMonotonicMs: endMs,
			durationMs: Math.max(0, endMs - startMs),
			outcome,
			...(normalizedRetryReason === null ? {} : { retryReason: normalizedRetryReason }),
		};
		if (this.#latencyRegistry !== null) {
			try {
				this.#latencyRegistry.recordTracePhase(traceId, phase, { startMs, endMs, outcome, ...(normalizedRetryReason === null ? {} : { retryReason: normalizedRetryReason }) });
			} catch { return; }
		}
		this.#record(phase, record, fields);
	}

	#invalidateTrace(traceId) {
		try { this.#latencyRegistry?.invalidateTrace?.(traceId); } catch { /* telemetry cannot interrupt planning */ }
	}

	#nativeTimingWindow(agentId, identity) {
		let entry = this.#nativeDecisionTiming.get(agentId);
		if (entry === undefined) {
			entry = { activeKey: null, byIdentity: new Map() };
			this.#nativeDecisionTiming.set(agentId, entry);
		}
		const key = nativeTimingIdentityKey(identity);
		let window = entry.byIdentity.get(key);
		if (window === undefined) {
			window = { identity: Object.freeze({ ...identity }), window: new NativeDecisionTimingWindow({ windowSize: this.#nativeDecisionTimingWindowSize }) };
			entry.byIdentity.set(key, window);
		}
		entry.activeKey = key;
		return window;
	}

	#recordNativeTiming(record, event, fields) {
		this.#record(event, record, fields);
		this.#traceNativeTiming(record, event, fields);
	}

	/** Trace-file only: stage timestamps for latency analysis that benchmark recorders do not consume. */
	#traceNativeTiming(record, event, fields) {
		if (this.#nativeTimingSink === null) return;
		try {
			const result = this.#nativeTimingSink(event, {
				agentId: record.agentId,
				provider: record.provider,
				model: record.model,
				reasoningEffort: record.reasoningEffort,
				serviceTier: record.serviceTier ?? 'priority',
				goalRevision: record.goalRevision,
				...fields,
			});
			if (result !== null && result !== undefined && typeof result.then === 'function') Promise.resolve(result).catch(() => {});
		} catch { /* timing diagnostics cannot affect native control */ }
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

	beginReconcile(snapshot, options = undefined) {
		const result = this.#registry.reconcile(snapshot, options);
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

// Only metadata crosses public trace/health boundaries; encoded input belongs
// exclusively to the provider recorder's existing private/redacted text path.
function nativeTurnEvidence(value) {
	if (value === null || value === undefined) return {};
	const { threadId, turnId, tokens, usage, toolCalls, toolResultBytes, toolResponses, inputBytes, inputCount, compaction } = value;
	return { threadId, turnId, tokens, usage, toolCalls, toolResultBytes, inputBytes, inputCount, compaction,
		...(toolResponses === undefined ? {} : { toolResponses }) };
}

function readExecutionSettings(agent) {
	try { return agent?.executionSettings == null ? {} : { executionSettings: structuredClone(agent.executionSettings) }; }
	catch { return {}; }
}

function readSessionFields(agent) {
	if (agent === null || agent === undefined || typeof agent.sessionMetadata !== 'function') return {};
	try {
		const metadata = agent.sessionMetadata();
		return {
			...(metadata?.profileFingerprint === undefined ? {} : { profileFingerprint: metadata.profileFingerprint }),
			...(metadata?.sessionGeneration === undefined ? {} : { sessionGeneration: metadata.sessionGeneration }),
			...(metadata?.sessionReuse === undefined ? {} : { sessionReuse: metadata.sessionReuse }),
			...(metadata?.sessionState === undefined ? {} : { sessionState: metadata.sessionState }),
			...(metadata?.continuation === undefined ? {} : { continuation: metadata.continuation }),
			...(metadata?.resetReason === undefined ? {} : { resetReason: metadata.resetReason }),
		};
	} catch { return {}; }
}

function defaultTraceId(agentId, goalRevision) {
	return `trace-${String(agentId).replace(/[^A-Za-z0-9._:-]/g, '_')}-${goalRevision}`.slice(0, 128);
}

function sessionProfileFingerprint(agent) {
	if (agent === null || agent === undefined) return null;
	if (typeof agent.profileFingerprint === 'string') return agent.profileFingerprint;
	if (typeof agent.sessionMetadata !== 'function') return null;
	try {
		const fingerprint = agent.sessionMetadata()?.profileFingerprint;
		return typeof fingerprint === 'string' ? fingerprint : null;
	} catch { return null; }
}

function currentProviderAgent(service, agentId) {
	if (typeof service.getAgent !== 'function') return null;
	try { return service.getAgent(agentId); }
	catch { return null; }
}

function nativeTimingIdentity(record, agent) {
	const settings = agent?.executionSettings;
	const effective = settings?.effective ?? {};
	const requested = settings?.requested ?? {};
	return Object.freeze({
		provider: firstIdentityValue(effective.provider, requested.provider, record.provider),
		model: firstIdentityValue(effective.model, requested.model, record.model),
		reasoningEffort: firstIdentityValue(effective.reasoningEffort, requested.reasoningEffort, record.reasoningEffort),
		serviceTier: firstIdentityValue(effective.serviceTier, requested.serviceTier, record.serviceTier ?? 'priority'),
		profileFingerprint: profileFingerprint(record),
	});
}

function currentNativeTimingIdentity(registry, service, agentId) {
	let record = null;
	try { record = typeof registry.get === 'function' ? registry.get(agentId) : null; } catch { record = null; }
	const agent = currentProviderAgent(service, agentId);
	if (record === null || record === undefined) return null;
	return nativeTimingIdentity(record, agent);
}

function nativeTimingIdentityKey(identity) {
	return [identity.provider, identity.model, identity.reasoningEffort, identity.serviceTier, identity.profileFingerprint].map((value) => String(value ?? '')).join('\u001f');
}

function emptyNativeDecisionTiming(identity = null) {
	return new NativeDecisionTimingWindow().snapshot(identity);
}

function firstIdentityValue(...values) {
	return values.find((value) => typeof value === 'string' && value.trim().length > 0) ?? null;
}

function isUsableNativeToolResult(result) {
	if (result === null || result === undefined || typeof result !== 'object') return false;
	const state = typeof result.state === 'string' ? result.state.toUpperCase() : '';
	return !['FAILED', 'CANCELLED', 'ERROR'].includes(state);
}

function isNativeCancellation(error) {
	return error?.name === 'AbortError' || ['STALE_PLAN', 'PLAN_CANCELLED', 'TURN_INTERRUPTED', 'AGENT_DISPOSED'].includes(error?.code);
}

function safeRetryReason(value) {
	try { return normalizeRetryReason(value ?? 'ERROR'); }
	catch { return 'ERROR'; }
}

// Goal translation is a small structured task: Claude agents translate on Sonnet 5.5 and every other
// agent on Luna, both at medium effort regardless of the agent's own model and effort.
export function goalSpecTranslatorProfile(record, translatorId) {
	if (record.provider === 'claude') {
		return { ...record, agentId: translatorId, provider: 'claude', model: 'claude-sonnet-5-5', reasoningEffort: 'medium', serviceTier: 'priority' };
	}
	return { ...record, agentId: translatorId, provider: 'codex', model: 'gpt-6-luna', reasoningEffort: 'medium', serviceTier: 'fast' };
}

function goalSpecTranslatorId(agentId, requestId) {
	if (typeof agentId !== 'string' || agentId.trim().length === 0) throw new TypeError('agentId must be nonblank');
	if (typeof requestId !== 'string' || requestId.trim().length === 0) throw new TypeError('requestId must be nonblank');
	const digest = createHash('sha256').update(`${agentId}\0${requestId}`).digest('hex').slice(0, 32);
	return `goal-spec-${digest}`;
}

function parseGoalSpecJson(value) {
	if (typeof value !== 'string') return value;
	try { return JSON.parse(value); }
	catch (error) { throw Object.assign(new Error('Goal translator output was not one JSON object', { cause: error }), { code: 'MALFORMED_GOAL_SPEC_PROPOSAL' }); }
}

function codedError(code, message) { return Object.assign(new Error(message), { code }); }

function elapsed(startedAt, finishedAt) {
	if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt)) throw new TypeError('planner clock must return finite values');
	return Math.max(0, Math.round(finishedAt - startedAt));
}

function nativeToolLeaseMs(tool, fallbackMs) {
	const actions = tool?.kind === 'sequence' ? tool.actions : [tool];
	const durationMs = actions.reduce((total, action) => {
		const args = action?.arguments ?? {};
		const declared = args.timeoutMs ?? args.durationMs ?? (Number.isSafeInteger(args.ticks) ? args.ticks * 50 : fallbackMs);
		return total + (Number.isSafeInteger(declared) && declared > 0 ? declared : fallbackMs);
	}, 0);
	return durationMs + TOOL_SETTLEMENT_GRACE_MS;
}

function buildCorrectiveRetryInput(input, error, retryCount) {
	const code = String(error?.code ?? 'INVALID_DECISION').slice(0, 128);
	const message = String(error?.message ?? error).replace(/\s+/g, ' ').slice(0, MAX_RETRY_ERROR_LENGTH);
	return `${input}\n\nThe previous planner response was rejected by the trusted runtime validator `
		+ `(corrective retry ${retryCount}). Error code: ${code}. Validation message: ${message}. `
		+ 'Return a fresh decision that exactly matches the required JSON schema. Do not repeat or discuss the invalid response.';
}

function safeVerbose(callback, stage, message) {
	if (typeof callback !== 'function') return;
	if (stage.startsWith('live_') || stage === 'provider_event') {
		try { Promise.resolve(callback(stage, String(message ?? '').slice(0, 2048))).catch(() => {}); } catch { /* view output cannot affect planning */ }
		return;
	}
	if (stage === 'output') {
		reportVisibleOutput(callback, String(message ?? ''));
		return;
	}
	try {
		const normalized = String(message ?? '').replace(/\s+/g, ' ').trim().slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
		if (normalized.length === 0) return;
		Promise.resolve(callback(stage, normalized)).catch(() => {});
	}
	catch { /* verbose reporting is observational and cannot affect planning */ }
}

function verboseErrorMessage(prefix, error) {
	const code = String(error?.code ?? 'ERROR').slice(0, 128);
	return `${prefix} (${code}).`;
}
