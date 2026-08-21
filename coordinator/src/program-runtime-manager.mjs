import { createHash } from 'node:crypto';

import { DynamicAgentState } from './agent-registry.mjs';
import { ArenaScriptError } from './arena-script/errors.mjs';
import { parseArenaScript } from './arena-script/parser.mjs';
import { ArenaScriptEngine } from './arena-script/program-engine.mjs';
import { buildPlannerInput } from './prompts.mjs';

/** Coordinates model-authored ArenaScript programs for independent agents. */
export class ProgramRuntimeManager {
	#registry;
	#bridge;
	#planner;
	#reportError;
	#states = new Map();
	#versions = new Map();
	#lifecycles = new Map();
	#compilerCorrectionLimit;
	#latencyRegistry;
	#clock;
	#lastClockReading = null;
	#trace;
	#onCompleted;
	#plannerContext;

	constructor({ registry, bridge, planner, reportError = () => {}, trace = () => {}, onCompleted = () => {}, plannerContext = () => ({}), compilerCorrectionLimit = 1, latencyRegistry = null, clock = performance.now.bind(performance) } = {}) {
		if (!registry || !bridge || !planner) throw new TypeError('registry, bridge, and planner are required');
		if (typeof bridge.send !== 'function' || typeof planner.requestPlan !== 'function') throw new TypeError('bridge.send and planner.requestPlan are required');
		if (typeof reportError !== 'function') throw new TypeError('reportError must be a function');
		if (typeof trace !== 'function') throw new TypeError('trace must be a function');
		if (typeof onCompleted !== 'function') throw new TypeError('onCompleted must be a function');
		if (typeof plannerContext !== 'function') throw new TypeError('plannerContext must be a function');
		this.#registry = registry;
		this.#bridge = bridge;
		this.#planner = planner;
		this.#reportError = reportError;
		this.#trace = trace;
		this.#onCompleted = onCompleted;
		this.#plannerContext = plannerContext;
		if (!Number.isSafeInteger(compilerCorrectionLimit) || compilerCorrectionLimit < 0) throw new TypeError('compilerCorrectionLimit must be a non-negative safe integer');
		this.#compilerCorrectionLimit = compilerCorrectionLimit;
		if (latencyRegistry !== null && typeof latencyRegistry.record !== 'function') throw new TypeError('latencyRegistry.record must be a function');
		if (typeof clock !== 'function') throw new TypeError('clock must be a function');
		this.#latencyRegistry = latencyRegistry;
		this.#clock = clock;
	}

	async installDecision(record, decision, { observation, eventSequence } = {}) {
		const state = this.#state(record, observation, eventSequence);
		if (decision?.directive === 'finish') {
			this.#setTerminalState(record, decision.status === 'completed' ? DynamicAgentState.COMPLETED : DynamicAgentState.ERROR);
			return state.engine?.snapshot() ?? null;
		}
		if (decision?.directive !== 'replace' || typeof decision.source !== 'string') {
			throw codedError('INVALID_PLANNER_DIRECTIVE', 'Initial model decision must replace with ArenaScript source');
		}
		return this.#installSource(state, record, decision.source, observation, eventSequence);
	}

	async onObservation(record, payload = {}) {
		const state = this.#states.get(record.agentId);
		if (!state || state.disposed || state.goalRevision !== record.goalRevision) return null;
		const eventSequence = this.#acceptServerEvent(state, payload.eventSequence);
		if (eventSequence === null) return state.engine.snapshot();
		const observation = payload.observation ?? payload;
		state.observation = observation;
		const receiptMonotonicMs = advancingTimestamp(state, 'lastReceiptMonotonicMs', payload.receiptMonotonicMs);
		const receiptEpochMs = advancingTimestamp(state, 'lastReceiptEpochMs', payload.receiptEpochMs);
		if (payload.attention === true) {
			state.branchReceipt = {
				eventSequence,
				receiptMonotonicMs,
			};
			this.#recordMinecraftPublication(receiptEpochMs, payload.observedAtEpochMs);
		}
		state.engine.ingestObservation({ observation, eventSequence, attention: payload.attention === true });
		this.#flushDeferredProgramTrace(state);
		this.#syncState(record, state);
		return state.engine.snapshot();
	}

	onActionProgress(record, payload = {}) {
		const state = this.#states.get(record.agentId);
		if (!state || state.disposed || state.goalRevision !== record.goalRevision) return false;
		const active = state.engine.snapshot().activeActionId;
		if (active === null || state.actionIds.get(payload.actionId) !== active) return false;
		const eventSequence = this.#actionEventSequence(state, payload.eventSequence);
		if (eventSequence === null) return false;
		const timing = state.actionTiming.get(payload.actionId);
		if (timing && timing.firstProgressAt === null) {
			timing.firstProgressAt = this.#safeNow();
			if (timing.firstProgressAt !== null && timing.bridgeSentAt !== null) this.#recordLatency('command_to_first_progress', timing.firstProgressAt - timing.bridgeSentAt);
		}
		return true;
	}

	async onActionResult(record, payload = {}) {
		const state = this.#states.get(record.agentId);
		if (!state || state.disposed || state.goalRevision !== record.goalRevision) return false;
		const active = state.engine.snapshot().activeActionId;
		const internalActionId = state.actionIds.get(payload.actionId);
		if (active === null || internalActionId !== active) return false;
		const eventSequence = this.#actionEventSequence(state, payload.eventSequence);
		if (eventSequence === null) return false;
		const timing = state.actionTiming.get(payload.actionId);
		const metadata = state.actionMetadata.get(payload.actionId);
		const completedAt = this.#safeNow();
		if (timing !== undefined) {
			if (completedAt !== null && timing.bridgeSentAt !== null) this.#recordLatency('action_completion', completedAt - timing.bridgeSentAt);
		}
		if (metadata !== undefined) {
			this.#traceState(state, 'program_step', {
				programId: metadata.command.provenance.programId,
				version: metadata.command.provenance.version,
				sourceStepId: metadata.command.provenance.stepId,
				eventSequence,
				authority: metadata.command.provenance,
				actionType: metadata.command.action.type,
				arguments: metadata.command.action.arguments,
				result: { state: payload.state, reasonCode: payload.reasonCode ?? '' },
				timing: {
					branchSelectedToBridgeSendMs: elapsedOrNull(metadata.branchSelectedAt, timing?.bridgeSentAt),
					bridgeSendToFirstProgressMs: elapsedOrNull(timing?.bridgeSentAt, timing?.firstProgressAt),
					bridgeSendToCompletionMs: elapsedOrNull(timing?.bridgeSentAt, completedAt),
				},
			});
		}
		state.engine.ingestActionResult({
			actionId: internalActionId,
			state: payload.state,
			reasonCode: payload.reasonCode ?? '',
			eventSequence,
		});
		this.#flushDeferredProgramTrace(state);
		state.actionIds.delete(payload.actionId);
		state.actionTiming.delete(payload.actionId);
		state.actionMetadata.delete(payload.actionId);
		if (state.observation !== null) state.engine.ingestObservation({ observation: state.observation, eventSequence, attention: false });
		this.#flushDeferredProgramTrace(state);
		this.#syncState(record, state);
		return true;
	}

	/** Returns true when a result belongs to an old/disposed action and must be ignored. */
	isActionResultStale(record, payload = {}) {
		const state = this.#states.get(record.agentId);
		if (!state || state.disposed || state.goalRevision !== record.goalRevision) return true;
		const active = state.engine.snapshot().activeActionId;
		return active === null || state.actionIds.get(payload.actionId) !== active;
	}

	onGoalControl(record, operation) {
		if (operation === 'queue') return;
		const state = this.#states.get(record.agentId);
		if (!state) return;
		state.disposed = true;
		state.engine.dispose();
		this.#states.delete(record.agentId);
	}

	dispose(agentId) {
		const state = this.#states.get(agentId);
		if (!state) return;
		state.disposed = true;
		state.engine.dispose();
		this.#states.delete(agentId);
	}

	disposeAll() {
		for (const agentId of this.#states.keys()) this.dispose(agentId);
	}

	hasCurrent(record) {
		const state = this.#states.get(record.agentId);
		return state !== undefined && !state.disposed && state.goalRevision === record.goalRevision;
	}

	#state(record, observation, eventSequence) {
		const existing = this.#states.get(record.agentId);
		if (existing && !existing.disposed && existing.goalRevision === record.goalRevision) {
			existing.observation = observation;
			this.#installationSequence(existing, eventSequence);
			return existing;
		}
		if (existing) this.dispose(record.agentId);
		const state = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			modelIdentity: record.model,
			provider: record.provider,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
			version: this.#versions.get(versionKey(record)) ?? 0,
			lifecycle: (this.#lifecycles.get(record.agentId) ?? 0) + 1,
			sequence: Number.isSafeInteger(eventSequence) && eventSequence >= 0 ? eventSequence : 0,
			lastServerEventSequence: Number.isSafeInteger(eventSequence) && eventSequence >= 1 ? eventSequence : null,
			observation,
			disposed: false,
			actionIds: new Map(),
			actionTiming: new Map(),
			actionMetadata: new Map(),
			commands: 0,
			corrections: new Map(),
			terminalStatus: null,
			branchReceipt: null,
			lastReceiptMonotonicMs: null,
			lastReceiptEpochMs: null,
			reactiveRequest: null,
			reactiveRequestActive: false,
			pendingReplacementTrace: null,
			engine: null,
		};
		state.engine = new ArenaScriptEngine({
			dispatch: (command) => { void this.#dispatch(state, command); },
			cancel: (actionId) => { void this.#cancel(state, actionId); },
			requestModel: (context) => { void this.#requestReactiveDecision(state, context); },
			trace: (event, fields) => this.#traceState(state, event, fields),
		});
		this.#states.set(record.agentId, state);
		this.#lifecycles.set(record.agentId, state.lifecycle);
		return state;
	}

	async #installSource(state, record, source, observation, eventSequence) {
		let compiled;
		try {
			compiled = parseArenaScript(source);
		} catch (error) {
			this.#traceState(state, 'program_sandbox_error', {
				result: { code: error?.code ?? 'ARENA_SCRIPT_COMPILE_ERROR', message: String(error?.message ?? error).slice(0, 512) },
				source,
			});
			if (error instanceof ArenaScriptError) return this.#requestCompilerCorrection(state, record, source, error, observation, eventSequence);
			throw error;
		}
		const version = state.version + 1;
		state.version = version;
		this.#versions.set(versionKey(record), version);
		const sequence = this.#installationSequence(state, eventSequence);
		state.observation = observation;
		const installed = state.engine.install({
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			modelIdentity: record.model,
			programId: `program-${record.goalRevision}-${version}`,
			version,
			compiled,
			observation,
			eventSequence: sequence,
		});
		this.#traceProgramInstall(state, record, source, version, sequence);
		this.#syncState(record, state);
		return installed;
	}

	async #requestCompilerCorrection(state, record, source, error, observation, eventSequence, context = null) {
		const correctionKey = context === null ? `initial:${eventSequence}` : requestKey(context);
		const attempts = state.corrections.get(correctionKey) ?? 0;
		if (attempts >= this.#compilerCorrectionLimit) {
			if (context !== null) state.engine.failDirectiveRequest(context);
			this.#reportError(record.agentId, codedError('ARENA_SCRIPT_COMPILER_EXHAUSTED', 'ArenaScript compiler correction budget is exhausted'));
			return null;
		}
		state.corrections.set(correctionKey, attempts + 1);
		try {
			const decision = await this.#planner.requestPlan({
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				preserveState: true,
				input: buildPlannerInput({
					decisionContext: 'arena_script_compiler_error',
					compilerError: { code: error.code, message: error.message, line: error.location?.line ?? 0, column: error.location?.column ?? 0 },
					rejectedSourceHash: `sha256:${createHash('sha256').update(source).digest('hex')}`,
					observation: observation ?? {},
				}),
			});
			if (state.disposed || this.#registry.get(record.agentId)?.goalRevision !== record.goalRevision) return null;
			if (decision?.directive !== 'replace') throw codedError('INVALID_COMPILER_CORRECTION', 'Compiler correction must replace with fresh ArenaScript source');
			if (context === null) return this.#installSource(state, record, decision.source, observation, eventSequence);
			if (!sameEngineRequest(state.engine.snapshot(), context)) {
				state.engine.failDirectiveRequest(context);
				this.#traceState(state, 'program_replacement_rejected', {
					programId: context.programId,
					version: context.version,
					eventSequence: context.eventSequence,
					result: { code: 'STALE_MODEL_REQUEST' },
				});
				return null;
			}
			let compiled;
			try { compiled = parseArenaScript(decision.source); }
			catch (nextError) {
				if (nextError instanceof ArenaScriptError) {
					this.#traceState(state, 'program_sandbox_error', {
						programId: context.programId,
						version: context.version,
						eventSequence: context.eventSequence,
						result: { code: nextError.code, message: String(nextError.message).slice(0, 512) },
						source: decision.source,
					});
					return this.#requestCompilerCorrection(state, record, decision.source, nextError, observation, eventSequence, context);
				}
				throw nextError;
			}
			const version = state.version + 1;
			state.version = version;
			this.#versions.set(versionKey(record), version);
			state.engine.applyDirective({ ...context, directive: 'replace', install: { programId: `program-${record.goalRevision}-${version}`, version, compiled } });
			this.#traceProgramInstall(state, record, decision.source, version, context.eventSequence);
			return state.engine.snapshot();
		} catch (requestError) {
			this.#reportError(record.agentId, requestError);
			return null;
		}
	}

	async #requestReactiveDecision(state, context) {
		state.reactiveRequest = context;
		if (state.reactiveRequestActive) return;
		state.reactiveRequestActive = true;
		try {
			while (!state.disposed && state.reactiveRequest !== null) {
				const request = state.reactiveRequest;
				state.reactiveRequest = null;
				await this.#runReactiveDecision(state, request);
				// Give planner cleanup a turn before starting the newest coalesced request.
				if (state.reactiveRequest !== null) await Promise.resolve();
			}
		} finally {
			const pending = state.disposed ? null : state.reactiveRequest;
			state.reactiveRequest = null;
			state.reactiveRequestActive = false;
			if (pending !== null) void this.#requestReactiveDecision(state, pending);
		}
	}

	async #runReactiveDecision(state, context) {
		const record = this.#registry.get(state.agentId);
		if (state.disposed || record === null || record.goalRevision !== state.goalRevision) return;
		try {
			const decision = await this.#planner.requestPlan({
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				preserveState: true,
				input: buildPlannerInput({
					agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
					goal: record.currentGoal,
					goalRevision: record.goalRevision,
					decisionContext: context.decisionContext ?? 'program_attention',
					programId: context.programId,
					programVersion: context.version,
					eventSequence: context.eventSequence,
					...(context.actionFailure === undefined ? {} : { actionFailure: context.actionFailure }),
					observation: context.observation,
				}, this.#plannerContext(record.agentId)),
			});
			if (state.disposed || this.#registry.get(record.agentId)?.goalRevision !== record.goalRevision) return;
			if (decision?.directive === 'replace') {
				if (!sameEngineRequest(state.engine.snapshot(), context)) {
					state.engine.failDirectiveRequest(context);
					this.#traceState(state, 'program_replacement_rejected', {
						programId: context.programId,
						version: context.version,
						eventSequence: context.eventSequence,
						result: { code: 'STALE_MODEL_REQUEST' },
					});
					return;
				}
				let compiled;
				try { compiled = parseArenaScript(decision.source); }
				catch (error) {
					if (error instanceof ArenaScriptError) {
						this.#traceState(state, 'program_sandbox_error', {
							programId: context.programId,
							version: context.version,
							eventSequence: context.eventSequence,
							result: { code: error.code, message: String(error.message).slice(0, 512) },
							source: decision.source,
						});
						await this.#requestCompilerCorrection(state, record, decision.source, error, state.observation, context.eventSequence, context);
						return;
					}
					throw error;
				}
				const version = state.version + 1;
				state.version = version;
				this.#versions.set(versionKey(record), version);
				state.engine.applyDirective({ ...context, directive: 'replace', install: { programId: `program-${record.goalRevision}-${version}`, version, compiled } });
				this.#traceProgramInstall(state, record, decision.source, version, context.eventSequence);
			} else {
				const accepted = sameEngineRequest(state.engine.snapshot(), context);
				state.engine.applyDirective({ ...context, directive: decision?.directive, status: decision?.status });
				if (accepted && decision?.directive === 'finish') state.terminalStatus = decision.status;
				else if (accepted && ['continue', 'replace'].includes(decision?.directive)) state.terminalStatus = null;
			}
			this.#syncState(record, state);
		} catch (error) {
			state.engine.failDirectiveRequest(context);
			this.#reportError(record.agentId, error);
		}
	}

	async #dispatch(state, command) {
		if (state.disposed) return;
		const record = this.#registry.get(state.agentId);
		if (record === null || record.goalRevision !== state.goalRevision) return;
		let actionId = null;
		try {
			const branchSelectedAt = this.#safeNow();
			this.#ensureActing(record);
			actionId = `${state.agentId}:${state.goalRevision}:${state.lifecycle}:${++state.commands}:${command.actionId}`;
			state.actionIds.set(actionId, command.actionId);
			state.actionMetadata.set(actionId, { command, branchSelectedAt });
			this.#traceState(state, 'program_step', {
				programId: command.provenance.programId,
				version: command.provenance.version,
				sourceStepId: command.provenance.stepId,
				eventSequence: command.provenance.eventSequence,
				authority: command.provenance,
				actionType: command.action.type,
				arguments: command.action.arguments,
				result: null,
				timing: {
					branchSelectedAt,
					branchSelectedToBridgeSendMs: null,
					bridgeSendToFirstProgressMs: null,
					bridgeSendToCompletionMs: null,
				},
			});
			await this.#bridge.send('action_command', state.agentId, wireActionCommand(record, actionId, command));
			const bridgeSentAt = this.#safeNow();
			state.actionTiming.set(actionId, { bridgeSentAt, firstProgressAt: null });
			const metadata = state.actionMetadata.get(actionId);
			if (metadata !== undefined) metadata.bridgeSentAt = bridgeSentAt;
			const receipt = command.provenance.authorizingEventSequence === null ? null : state.branchReceipt;
			if (receipt !== null && receipt.eventSequence === command.provenance.authorizingEventSequence) {
				if (branchSelectedAt !== null && receipt.receiptMonotonicMs !== null) this.#recordLatency('event_receipt_to_branch', branchSelectedAt - receipt.receiptMonotonicMs);
				if (bridgeSentAt !== null && branchSelectedAt !== null) this.#recordLatency('branch_to_bridge_send', bridgeSentAt - branchSelectedAt);
				state.branchReceipt = null;
			}
		} catch (error) {
			if (actionId !== null) this.#rejectDispatchedAction(state, record, actionId, command.actionId, error);
			this.#reportError(state.agentId, error);
		}
	}

	#rejectDispatchedAction(state, record, externalActionId, internalActionId, error) {
		if (state.disposed || state.actionIds.get(externalActionId) !== internalActionId) return;
		const active = state.engine.snapshot().activeActionId;
		if (active !== internalActionId) return;
		const eventSequence = this.#actionEventSequence(state);
		const metadata = state.actionMetadata.get(externalActionId);
		const timing = state.actionTiming.get(externalActionId);
		state.actionIds.delete(externalActionId);
		state.actionTiming.delete(externalActionId);
		state.actionMetadata.delete(externalActionId);
		if (metadata !== undefined) this.#traceState(state, 'program_step', {
			programId: metadata.command.provenance.programId,
			version: metadata.command.provenance.version,
			sourceStepId: metadata.command.provenance.stepId,
			eventSequence,
			authority: metadata.command.provenance,
			actionType: metadata.command.action.type,
			arguments: metadata.command.action.arguments,
			result: { state: 'FAILED', reasonCode: stableFailureCode(error) },
			timing: {
				branchSelectedAt: metadata.branchSelectedAt,
				branchSelectedToBridgeSendMs: elapsedOrNull(metadata.branchSelectedAt, timing?.bridgeSentAt),
				bridgeSendToFirstProgressMs: elapsedOrNull(timing?.bridgeSentAt, timing?.firstProgressAt),
				bridgeSendToCompletionMs: null,
			},
		});
		try {
			state.engine.ingestActionResult({
				actionId: internalActionId,
				state: 'FAILED',
				reasonCode: stableFailureCode(error),
				eventSequence,
			});
			this.#flushDeferredProgramTrace(state);
			if (state.observation !== null) state.engine.ingestObservation({ observation: state.observation, eventSequence, attention: false });
			this.#flushDeferredProgramTrace(state);
			this.#syncState(record, state);
		} catch (programError) {
			state.engine.suspend(`execution_error:${stableFailureCode(programError)}`);
			this.#syncState(record, state);
			this.#reportError(state.agentId, programError);
		}
	}

	async #cancel(state, actionId) {
		const externalActionId = [...state.actionIds].find(([, internal]) => internal === actionId)?.[0] ?? `${state.agentId}:${actionId}`;
		try { await this.#bridge.send('action_cancel', state.agentId, { goalRevision: state.goalRevision, actionId: externalActionId }); }
		catch (error) { this.#reportError(state.agentId, error); }
	}

	#acceptServerEvent(state, candidate) {
		if (!Number.isSafeInteger(candidate) || candidate < 1) return null;
		if (state.lastServerEventSequence !== null && candidate <= state.lastServerEventSequence) return null;
		state.lastServerEventSequence = candidate;
		state.sequence = Math.max(state.sequence, candidate);
		return candidate;
	}

	#actionEventSequence(state, candidate = undefined) {
		if (candidate === undefined || candidate === null) return ++state.sequence;
		if (!Number.isSafeInteger(candidate) || candidate <= state.sequence) return null;
		state.sequence = candidate;
		return candidate;
	}

	#installationSequence(state, candidate) {
		if (Number.isSafeInteger(candidate) && candidate >= state.sequence) state.sequence = candidate;
		return state.sequence;
	}

	#safeNow() {
		try {
			const now = this.#clock();
			if (!Number.isFinite(now) || now < 0 || (this.#lastClockReading !== null && now < this.#lastClockReading)) return null;
			this.#lastClockReading = now;
			return now;
		} catch {
			return null;
		}
	}

	#traceState(state, event, fields = {}) {
		const snapshot = state.engine?.snapshot?.() ?? {};
		try {
			this.#trace(event, {
				agentId: state.agentId,
				provider: state.provider,
				model: state.modelIdentity,
				reasoningEffort: state.reasoningEffort,
				serviceTier: state.serviceTier,
				goalRevision: state.goalRevision,
				programId: fields.programId ?? snapshot.programId,
				version: fields.version ?? snapshot.version,
				...fields,
			});
		} catch { /* diagnostics cannot interrupt agent control */ }
	}

	#traceProgramInstall(state, record, source, version, eventSequence) {
		const programId = `program-${record.goalRevision}-${version}`;
		this.#traceState(state, 'program_compiled', { programId, version, source, eventSequence });
		const snapshot = state.engine.snapshot();
		if (snapshot.programId === programId && snapshot.version === version) {
			state.pendingReplacementTrace = null;
			this.#traceState(state, 'program_replaced', { programId, version, source, eventSequence });
			return;
		}
		state.pendingReplacementTrace = { programId, version, source, eventSequence };
		this.#traceState(state, 'program_replacement_deferred', state.pendingReplacementTrace);
	}

	#flushDeferredProgramTrace(state) {
		const pending = state.pendingReplacementTrace;
		if (pending === null) return;
		const snapshot = state.engine.snapshot();
		if (snapshot.programId === pending.programId && snapshot.version === pending.version) {
			state.pendingReplacementTrace = null;
			this.#traceState(state, 'program_replaced', pending);
			return;
		}
		if (Number.isSafeInteger(snapshot.version) && snapshot.version >= pending.version) {
			state.pendingReplacementTrace = null;
			this.#traceState(state, 'program_replacement_rejected', {
				...pending,
				result: { code: 'REPLACEMENT_SUPERSEDED' },
			});
		}
	}

	#recordLatency(operation, duration) {
		if (this.#latencyRegistry === null) return;
		if (!Number.isFinite(duration) || duration <= 0) return;
		try { this.#latencyRegistry.record(operation, duration); }
		catch { /* local telemetry cannot interrupt agent control */ }
	}

	#recordMinecraftPublication(receiptEpochMs, observedAtEpochMs) {
		if (!Number.isFinite(receiptEpochMs) || receiptEpochMs < 0 || !Number.isFinite(observedAtEpochMs) || observedAtEpochMs < 0) return;
		const duration = receiptEpochMs - observedAtEpochMs;
		if (duration <= 0 || duration > 60_000) return;
		this.#recordLatency('minecraft_change_to_publication', duration);
	}

	#ensureActing(record) {
		const current = this.#registry.get(record.agentId);
		if (current?.goalRevision !== record.goalRevision || current.state === DynamicAgentState.ACTING) return;
		if (current.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
		if (this.#registry.get(record.agentId)?.state === DynamicAgentState.PLANNING) this.#registry.setState(record.agentId, DynamicAgentState.ACTING, { goalRevision: record.goalRevision });
	}

	#syncState(record, state) {
		const snapshot = state.engine.snapshot();
		if (this.#registry.get(record.agentId)?.state === DynamicAgentState.DEAD) return;
		if (snapshot.status === 'FINISHED') this.#setTerminalState(record, state.terminalStatus === 'impossible' ? DynamicAgentState.ERROR : DynamicAgentState.COMPLETED);
		if (snapshot.status === 'PAUSED' || snapshot.status === 'SUSPENDED') this.#setTerminalState(record, DynamicAgentState.PAUSED);
	}

	#setTerminalState(record, state) {
		const current = this.#registry.get(record.agentId);
		if (current === null || current.goalRevision !== record.goalRevision
			|| current.state === DynamicAgentState.DEAD || current.state === state) return;
		const updated = this.#registry.setState(record.agentId, state, { goalRevision: record.goalRevision });
		if (state === DynamicAgentState.COMPLETED) {
			try {
				Promise.resolve(this.#onCompleted(updated)).catch((error) => this.#reportError(record.agentId, error));
			} catch (error) {
				this.#reportError(record.agentId, error);
			}
		}
	}
}

function versionKey(record) { return `${record.agentId}\u0000${record.goalRevision}`; }
function advancingTimestamp(state, field, value) {
	const timestamp = monotonicTimestamp(value);
	if (timestamp === null || (state[field] !== null && timestamp < state[field])) return null;
	if (timestamp !== null) state[field] = timestamp;
	return timestamp;
}
function wireActionCommand(record, actionId, command) {
	const action = command?.action;
	const provenance = command?.provenance;
	if (!action || typeof action.type !== 'string') {
		throw codedError('INVALID_ARENA_SCRIPT_COMMAND', 'ArenaScript command has no exact action shape');
	}
	return Object.freeze({
		goalRevision: record.goalRevision,
		actionId,
		actionType: action.type,
		arguments: actionArguments(action.type, action.arguments),
		provenance: Object.freeze({
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
			programId: provenance.programId,
			programVersion: provenance.version,
			sourceStepId: provenance.stepId,
			eventSequence: provenance.authorizingEventSequence ?? provenance.eventSequence,
		}),
	});
}
function actionArguments(type, value) {
	if (value !== null && typeof value === 'object' && !Array.isArray(value)) return structuredClone(value);
	if (type === 'respawn' && (value === undefined || value === null || (Array.isArray(value) && value.length === 0))) return {};
	if (type === 'wait' || type === 'use_item' || type === 'block_with_shield') return { durationMs: value };
	throw codedError('INVALID_ARENA_SCRIPT_COMMAND', `ArenaScript primitive '${type}' requires an object argument`);
}
function requestKey(context) { return [context.programId, context.version, context.generation, context.lifecycleEpoch, context.continuationEpoch, context.activeActionId, context.eventSequence, context.factsSequence].join('\u0000'); }
function sameEngineRequest(snapshot, context) { return snapshot.programId === context.programId && snapshot.version === context.version && snapshot.generation === context.generation && snapshot.lifecycleEpoch === context.lifecycleEpoch && snapshot.continuationEpoch === context.continuationEpoch && snapshot.activeActionId === context.activeActionId && snapshot.eventSequence === context.eventSequence && snapshot.factsSequence === context.factsSequence; }

function codedError(code, message) {
	return Object.assign(new Error(message), { code });
}

function stableFailureCode(error) {
	return typeof error?.code === 'string' && /^[A-Z0-9_]{1,128}$/.test(error.code)
		? error.code
		: 'BRIDGE_SEND_REJECTED';
}

function monotonicTimestamp(value) {
	return Number.isFinite(value) && value >= 0 ? value : null;
}

function elapsedOrNull(start, end) {
	return Number.isFinite(start) && Number.isFinite(end) && end >= start ? Math.round(end - start) : null;
}
