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
	#epochClock;

	constructor({ registry, bridge, planner, reportError = () => {}, compilerCorrectionLimit = 1, latencyRegistry = null, clock = performance.now.bind(performance), epochClock = Date.now } = {}) {
		if (!registry || !bridge || !planner) throw new TypeError('registry, bridge, and planner are required');
		if (typeof bridge.send !== 'function' || typeof planner.requestPlan !== 'function') throw new TypeError('bridge.send and planner.requestPlan are required');
		if (typeof reportError !== 'function') throw new TypeError('reportError must be a function');
		this.#registry = registry;
		this.#bridge = bridge;
		this.#planner = planner;
		this.#reportError = reportError;
		if (!Number.isSafeInteger(compilerCorrectionLimit) || compilerCorrectionLimit < 0) throw new TypeError('compilerCorrectionLimit must be a non-negative safe integer');
		this.#compilerCorrectionLimit = compilerCorrectionLimit;
		if (latencyRegistry !== null && typeof latencyRegistry.record !== 'function') throw new TypeError('latencyRegistry.record must be a function');
		if (typeof clock !== 'function') throw new TypeError('clock must be a function');
		if (typeof epochClock !== 'function') throw new TypeError('epochClock must be a function');
		this.#latencyRegistry = latencyRegistry;
		this.#clock = clock;
		this.#epochClock = epochClock;
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
		const receivedAt = monotonicTimestamp(payload.receivedAtMonotonic) ?? this.#now();
		const observation = payload.observation ?? payload;
		state.observation = observation;
		state.engine.ingestObservation({ observation, eventSequence, attention: payload.attention === true });
		const branchSelectedAt = this.#now();
		this.#recordMinecraftPublication(payload.observedAtEpochMs);
		this.#recordLatency('event_receipt_to_branch', branchSelectedAt - receivedAt);
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
			timing.firstProgressAt = this.#now();
			this.#recordLatency('command_to_first_progress', timing.firstProgressAt - timing.bridgeSentAt);
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
		if (timing !== undefined) {
			this.#recordLatency('action_completion', this.#now() - timing.bridgeSentAt);
		}
		state.engine.ingestActionResult({
			actionId: internalActionId,
			state: payload.state,
			reasonCode: payload.reasonCode ?? '',
			eventSequence,
		});
		state.actionIds.delete(payload.actionId);
		state.actionTiming.delete(payload.actionId);
		if (state.observation !== null) state.engine.ingestObservation({ observation: state.observation, eventSequence, attention: false });
		this.#syncState(record, state);
		return true;
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
			version: this.#versions.get(versionKey(record)) ?? 0,
			lifecycle: (this.#lifecycles.get(record.agentId) ?? 0) + 1,
			sequence: Number.isSafeInteger(eventSequence) && eventSequence >= 0 ? eventSequence : 0,
			lastServerEventSequence: Number.isSafeInteger(eventSequence) && eventSequence >= 1 ? eventSequence : null,
			observation,
			disposed: false,
			actionIds: new Map(),
			actionTiming: new Map(),
			commands: 0,
			corrections: new Map(),
			terminalStatus: null,
			engine: null,
		};
		state.engine = new ArenaScriptEngine({
			dispatch: (command) => { void this.#dispatch(state, command); },
			cancel: (actionId) => { void this.#cancel(state, actionId); },
			requestModel: (context) => { void this.#requestReactiveDecision(state, context); },
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
			if (error instanceof ArenaScriptError) return this.#requestCompilerCorrection(state, record, source, error, observation, eventSequence);
			throw error;
		}
		const version = state.version + 1;
		state.version = version;
		this.#versions.set(versionKey(record), version);
		const sequence = this.#installationSequence(state, eventSequence);
		state.observation = observation;
		return state.engine.install({
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			modelIdentity: record.model,
			programId: `program-${record.goalRevision}-${version}`,
			version,
			compiled,
			observation,
			eventSequence: sequence,
		});
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
				return null;
			}
			let compiled;
			try { compiled = parseArenaScript(decision.source); }
			catch (nextError) {
				if (nextError instanceof ArenaScriptError) return this.#requestCompilerCorrection(state, record, decision.source, nextError, observation, eventSequence, context);
				throw nextError;
			}
			const version = state.version + 1;
			state.version = version;
			this.#versions.set(versionKey(record), version);
			state.engine.applyDirective({ ...context, directive: 'replace', install: { programId: `program-${record.goalRevision}-${version}`, version, compiled } });
			return state.engine.snapshot();
		} catch (requestError) {
			this.#reportError(record.agentId, requestError);
			return null;
		}
	}

	async #requestReactiveDecision(state, context) {
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
					decisionContext: 'program_attention',
					programId: context.programId,
					programVersion: context.version,
					eventSequence: context.eventSequence,
					observation: context.observation,
				}),
			});
			if (state.disposed || this.#registry.get(record.agentId)?.goalRevision !== record.goalRevision) return;
			if (decision?.directive === 'replace') {
				let compiled;
				try { compiled = parseArenaScript(decision.source); }
				catch (error) {
					if (error instanceof ArenaScriptError) { await this.#requestCompilerCorrection(state, record, decision.source, error, state.observation, context.eventSequence, context); return; }
					throw error;
				}
				const version = state.version + 1;
				state.version = version;
				this.#versions.set(versionKey(record), version);
				state.engine.applyDirective({ ...context, directive: 'replace', install: { programId: `program-${record.goalRevision}-${version}`, version, compiled } });
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
			const branchSelectedAt = this.#now();
			this.#ensureActing(record);
			actionId = `${state.agentId}:${state.goalRevision}:${state.lifecycle}:${++state.commands}:${command.actionId}`;
			state.actionIds.set(actionId, command.actionId);
			await this.#bridge.send('action_command', state.agentId, wireActionCommand(record, actionId, command));
			const bridgeSentAt = this.#now();
			state.actionTiming.set(actionId, { bridgeSentAt, firstProgressAt: null });
			if (command.provenance.authorizingEventSequence !== null) this.#recordLatency('branch_to_bridge_send', bridgeSentAt - branchSelectedAt);
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
		state.actionIds.delete(externalActionId);
		state.actionTiming.delete(externalActionId);
		state.engine.ingestActionResult({
			actionId: internalActionId,
			state: 'FAILED',
			reasonCode: stableFailureCode(error),
			eventSequence,
		});
		if (state.observation !== null) state.engine.ingestObservation({ observation: state.observation, eventSequence, attention: false });
		this.#syncState(record, state);
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

	#now() {
		const now = this.#clock();
		if (!Number.isFinite(now)) throw new TypeError('clock must return a finite number');
		return now;
	}

	#recordLatency(operation, duration) {
		if (this.#latencyRegistry === null) return;
		if (!Number.isFinite(duration) || duration <= 0) return;
		try { this.#latencyRegistry.record(operation, duration); }
		catch { /* local telemetry cannot interrupt agent control */ }
	}

	#recordMinecraftPublication(observedAtEpochMs) {
		if (!Number.isSafeInteger(observedAtEpochMs) || observedAtEpochMs < 0) return;
		const completedAtEpochMs = this.#epochClock();
		const duration = completedAtEpochMs - observedAtEpochMs;
		if (!Number.isFinite(completedAtEpochMs) || duration <= 0 || duration > 60_000) return;
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
		if (snapshot.status === 'FINISHED') this.#setTerminalState(record, state.terminalStatus === 'impossible' ? DynamicAgentState.ERROR : DynamicAgentState.COMPLETED);
		if (snapshot.status === 'PAUSED' || snapshot.status === 'SUSPENDED') this.#setTerminalState(record, DynamicAgentState.PAUSED);
	}

	#setTerminalState(record, state) {
		const current = this.#registry.get(record.agentId);
		if (current === null || current.goalRevision !== record.goalRevision || current.state === state) return;
		this.#registry.setState(record.agentId, state, { goalRevision: record.goalRevision });
	}
}

function versionKey(record) { return `${record.agentId}\u0000${record.goalRevision}`; }
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
