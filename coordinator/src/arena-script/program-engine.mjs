import { ArenaScriptInterpreter } from './interpreter.mjs';
import { createInterpreterFacts } from './facts.mjs';
import { SCRIPT_BINDINGS } from './minecraft-api.mjs';

/** Runs one provenanced ArenaScript program without adding gameplay decisions. */
export class ArenaScriptEngine {
	#callbacks; #vm = null; #program = null; #facts = null; #eventSequence = -1; #factsSequence = -1; #generation = 0; #lifecycleEpoch = 0;
	#active = null; #pendingResult = null; #boundary = []; #watcherTruth = new Map(); #cancelling = null;
	#transition = null; #pendingRequest = null; #coalescedRequest = null; #pendingReplacement = null; #suspendedResult = null; #resumableUnhandled = false; #requestUpdate = null; #completed = new Map(); #deferredBase = null; #status = 'IDLE';

	constructor({ dispatch, cancel, requestModel } = {}) {
		if (typeof dispatch !== 'function' || typeof cancel !== 'function' || typeof requestModel !== 'function') throw new TypeError('ArenaScriptEngine callbacks dispatch, cancel, and requestModel are required');
		this.#callbacks = { dispatch, cancel, requestModel };
	}

	install(input) {
		const target = normalizeInstall(input);
		const baseline = this.#transition?.kind === 'install' ? this.#transition.target : this.#program;
		const relation = baseline ? installationRelation(target, baseline) : 1;
		if (relation < 0) return this.snapshot();
		this.#invalidateLifecycleRequests();
		if (relation === 0) return this.#refreshInstalledFacts(target);
		if (this.#active) {
			this.#transition = { kind: 'install', target };
			this.#cancelActive('replace');
			return this.snapshot();
		}
		return this.#activate(target);
	}

	ingestObservation({ observation, eventSequence, attention = false } = {}) {
		if (!this.#isLive() || !Number.isSafeInteger(eventSequence) || eventSequence < this.#eventSequence) return this.snapshot();
		const mayResume = this.#pendingResult && eventSequence >= this.#pendingResult.eventSequence;
		if (eventSequence === this.#eventSequence && !mayResume && this.#factsSequence >= eventSequence) return this.snapshot();
		this.#facts = createInterpreterFacts(observation);
		this.#factsSequence = eventSequence;
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		this.#fencePendingRequest();
		this.#tryPendingReplacement();
		const edges = this.#updateWatchers();
		if (this.#pendingResult && !this.#cancelling && eventSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
		else if (!this.#active && !this.#cancelling && this.#boundary.length > 0) this.#runBoundary();
		if (attention && edges === 0) this.#requestModel();
		return this.snapshot();
	}

	ingestActionResult({ actionId, state, reasonCode, eventSequence } = {}) {
		if (!Number.isSafeInteger(eventSequence) || typeof actionId !== 'string' || typeof state !== 'string' || typeof reasonCode !== 'string') return this.snapshot();
		const signature = `${state}\u0000${reasonCode}\u0000${eventSequence}`;
		if (!this.#active || actionId !== this.#active.actionId || eventSequence < this.#eventSequence) {
			if (this.#completed.has(actionId) && this.#completed.get(actionId) !== signature) return this.snapshot();
			return this.snapshot();
		}
		const active = this.#active;
		this.#active = null;
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		this.#fencePendingRequest();
		this.#completed.set(actionId, signature);
		const result = Object.freeze({ stateToken: active.stateToken, state, reasonCode });
		if (this.#transition || this.#cancelling) {
			const cancelling = this.#cancelling;
			this.#cancelling = null;
			if (this.#transition) { this.#transition.result = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence }); return this.#completeTransition(); }
			if (cancelling?.kind === 'watcher') {
				if (state === 'CANCELLED') {
					this.#vm.abortPendingCommand(result.stateToken);
					this.#handleYield(this.#vm.runWatcherHandler(cancelling.latch.watcherId, cancelling.latch.facts), watcherExecution(cancelling.latch));
					return this.snapshot();
				}
				this.#boundary.unshift(cancelling.latch);
				this.#pendingResult = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence });
				if (this.#factsSequence >= eventSequence) this.#resumeOrRunBoundary();
				return this.snapshot();
			}
			this.#status = 'SUSPENDED';
			return this.snapshot();
		}
		this.#pendingResult = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence });
		if (this.#factsSequence >= eventSequence) this.#resumeOrRunBoundary();
		return this.snapshot();
	}

	applyDirective(directive = {}) {
		const request = this.#pendingRequest;
		if (!request || !sameRequest(directive, request)) return this.snapshot();
		if (this.#coalescedRequest && (this.#coalescedRequest.eventSequence > request.eventSequence || this.#coalescedRequest.factsSequence > request.factsSequence || this.#coalescedRequest.lifecycleEpoch !== request.lifecycleEpoch)) {
			this.#pendingRequest = this.#coalescedRequest;
			this.#callbacks.requestModel(this.#pendingRequest);
			return this.snapshot();
		}
		this.#pendingRequest = null;
		this.#coalescedRequest = null;
		this.#requestUpdate = null;
		if (directive.directive === 'continue') {
			if (this.#transition?.kind === 'terminal' && this.#transition.reason === 'unhandled_attention') this.#transition = { kind: 'resume' };
			else if (this.#status === 'SUSPENDED' && (this.#suspendedResult || this.#resumableUnhandled)) {
				this.#pendingResult = this.#suspendedResult; this.#suspendedResult = null; this.#resumableUnhandled = false; this.#status = 'ACTIVE';
				if (this.#pendingResult && this.#factsSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
			}
			return this.snapshot();
		}
		if (directive.directive === 'replace') {
			try {
				const replacement = normalizeDirectiveReplacement(directive.install, this.#program);
				if (!isNewer(replacement, this.#program)) return this.snapshot();
				const requiredSequence = Math.max(request.eventSequence, this.#eventSequence);
				if (this.#factsSequence < requiredSequence) { this.#pendingReplacement = freezeRecord({ replacement, requiredSequence, lifecycleEpoch: this.#lifecycleEpoch }); return this.snapshot(); }
				this.#beginReplacement(replacement);
				return this.snapshot();
			} catch { return this.snapshot(); }
		} else if (directive.directive === 'pause' || directive.directive === 'finish') {
			this.#transition = { kind: 'terminal', status: directive.directive === 'pause' ? 'SUSPENDED' : 'FINISHED' };
		} else return this.snapshot();
		if (this.#active) this.#cancelActive(`directive:${directive.directive}`);
		else this.#completeTransition();
		return this.snapshot();
	}

	suspend(reason = 'suspended') {
		this.#invalidateLifecycleRequests();
		if (!this.#isLive()) return this.snapshot();
		this.#transition = { kind: 'terminal', status: 'SUSPENDED', reason };
		if (this.#active) this.#cancelActive(reason); else this.#completeTransition();
		return this.snapshot();
	}

	dispose() {
		this.#invalidateLifecycleRequests();
		if (this.#active) { this.#transition = { kind: 'dispose' }; this.#cancelActive('dispose'); return; }
		this.#clear();
	}

	snapshot() { return Object.freeze({ status: this.#status, eventSequence: this.#eventSequence, factsSequence: this.#factsSequence, generation: this.#generation, lifecycleEpoch: this.#lifecycleEpoch, activeActionId: this.#active?.actionId ?? null, programId: this.#program?.programId ?? null, version: this.#program?.version ?? null }); }

	#activate(target) {
		const latestFacts = this.#facts && this.#factsSequence > target.factsSequence ? this.#facts : target.facts;
		const latestFactsSequence = Math.max(this.#factsSequence, target.factsSequence);
		const latestSequence = Math.max(this.#eventSequence, target.eventSequence);
		this.#clear(false);
		this.#generation += 1;
		this.#program = freezeRecord({ agentId: target.agentId, goalRevision: target.goalRevision, modelIdentity: target.modelIdentity, programId: target.programId, version: target.version, compiled: target.compiled, eventSequence: target.eventSequence, factsSequence: target.factsSequence });
		this.#facts = latestFacts;
		this.#factsSequence = latestFactsSequence;
		this.#eventSequence = latestSequence;
		this.#vm = new ArenaScriptInterpreter(target.compiled, SCRIPT_BINDINGS);
		this.#status = 'ACTIVE';
		this.#handleYield(this.#vm.start(this.#facts), 'step');
		for (let index = 0; index < target.compiled.watcherCount && this.#isLive(); index += 1) this.#watcherTruth.set(`watcher-${index}`, this.#vm.evaluateWatcher(`watcher-${index}`, this.#facts));
		return this.snapshot();
	}

	#clear(resetGeneration = true) {
		this.#vm = null; this.#program = null; this.#facts = null; this.#eventSequence = -1; this.#factsSequence = -1; this.#active = null; this.#pendingResult = null;
		this.#boundary = []; this.#watcherTruth.clear(); this.#cancelling = null; this.#transition = null; this.#pendingRequest = null; this.#coalescedRequest = null; this.#pendingReplacement = null; this.#suspendedResult = null; this.#resumableUnhandled = false; this.#requestUpdate = null; this.#completed.clear(); this.#deferredBase = null; this.#status = 'IDLE';
		if (resetGeneration) this.#generation += 1;
	}

	#isLive() { return this.#vm !== null && ['ACTIVE', 'SUSPENDING', 'REPLACING', 'FINISHING'].includes(this.#status); }
	#cancelActive(reason) { if (!this.#active || this.#cancelling) return; this.#cancelling = { kind: 'transition', actionId: this.#active.actionId, reason }; this.#status = reason === 'replace' ? 'REPLACING' : 'SUSPENDING'; this.#callbacks.cancel(this.#active.actionId); }
	#completeTransition() {
		const transition = this.#transition; this.#transition = null;
		if (!transition) return this.snapshot();
		if (transition.kind === 'install') return this.#activate(transition.target);
		if (transition.kind === 'dispose') { this.#clear(); return this.snapshot(); }
		if (transition.kind === 'resume') {
			this.#status = 'ACTIVE'; this.#pendingResult = transition.result;
			if (this.#pendingResult && this.#factsSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
			return this.snapshot();
		}
		if (transition.status === 'SUSPENDED' && transition.reason === 'unhandled_attention') { this.#suspendedResult = transition.result ?? null; this.#resumableUnhandled = true; }
		this.#status = transition.status;
		return this.snapshot();
	}

	#updateWatchers() {
		let edges = 0;
		for (let index = 0; index < this.#program.compiled.watcherCount; index += 1) {
			const watcherId = `watcher-${index}`;
			const trueNow = this.#vm.evaluateWatcher(watcherId, this.#facts);
			const wasTrue = this.#watcherTruth.get(watcherId) === true;
			this.#watcherTruth.set(watcherId, trueNow);
			if (!trueNow || wasTrue) continue;
			edges += 1;
			const latch = freezeRecord({ watcherId, mode: watcherMode(this.#program.compiled, index), eventSequence: this.#eventSequence, generation: this.#generation, facts: this.#facts });
			if (latch.mode === 'interrupt' && this.#active && !this.#cancelling) {
				this.#cancelling = { kind: 'watcher', actionId: this.#active.actionId, latch };
				this.#callbacks.cancel(this.#active.actionId);
			} else this.#boundary.push(latch);
		}
		return edges;
	}

	#resumeOrRunBoundary() {
		const pending = this.#pendingResult; this.#pendingResult = null;
		if (!pending.authority && this.#boundary.length > 0) { this.#pendingResult = pending; return this.#runBoundary(); }
		const yielded = this.#vm.resume(pending.result, this.#facts);
		this.#handleYield(yielded, pending.authority ? watcherExecution(pending.authority, this.#factsSequence) : 'step');
	}

	#runBoundary() {
		if (this.#active || this.#boundary.length === 0) return;
		const latch = this.#boundary.shift();
		if (this.#deferredBase) {
			this.#handleYield(this.#vm.runWatcherHandler(latch.watcherId, latch.facts), watcherExecution(latch));
		} else if (this.#pendingResult) {
			this.#deferredBase = this.#pendingResult;
			this.#pendingResult = null;
			this.#handleYield(this.#vm.runWatcherHandlerBeforeResume(latch.watcherId, latch.facts), watcherExecution(latch));
		} else this.#handleYield(this.#vm.runWatcherHandler(latch.watcherId, latch.facts), watcherExecution(latch));
	}

	#requestModel() {
		const context = requestContext(this.#program, this.#generation, this.#lifecycleEpoch, this.#eventSequence, this.#factsSequence, this.#facts);
		if (this.#pendingRequest) { this.#coalescedRequest = context; return; }
		this.#pendingRequest = context;
		this.#coalescedRequest = context;
		this.#callbacks.requestModel(context);
		if (this.#program.compiled.unhandledPolicy === 'pause_and_notify') this.#suspendUnhandledAttention();
	}

	#invalidateLifecycleRequests() { this.#lifecycleEpoch += 1; this.#pendingRequest = null; this.#coalescedRequest = null; this.#pendingReplacement = null; this.#suspendedResult = null; this.#resumableUnhandled = false; }
	#suspendUnhandledAttention() {
		this.#transition = { kind: 'terminal', status: 'SUSPENDED', reason: 'unhandled_attention' };
		if (this.#active) this.#cancelActive('unhandled_attention'); else this.#completeTransition();
	}
	#fencePendingRequest() { if (this.#pendingRequest) this.#coalescedRequest = requestContext(this.#program, this.#generation, this.#lifecycleEpoch, this.#eventSequence, this.#factsSequence, this.#facts); }
	#refreshInstalledFacts(target) {
		if (target.factsSequence >= this.#factsSequence) { this.#facts = target.facts; this.#factsSequence = target.factsSequence; }
		this.#eventSequence = Math.max(this.#eventSequence, target.eventSequence);
		this.#program = freezeRecord({ ...this.#program, eventSequence: target.eventSequence, factsSequence: target.factsSequence });
		return this.snapshot();
	}
	#beginReplacement(replacement) {
		const target = freezeRecord({ ...replacement, facts: this.#facts, factsSequence: this.#factsSequence, eventSequence: this.#eventSequence });
		this.#transition = { kind: 'install', target };
		if (this.#active) this.#cancelActive('directive:replace'); else this.#completeTransition();
	}
	#tryPendingReplacement() {
		const pending = this.#pendingReplacement;
		if (!pending || pending.lifecycleEpoch !== this.#lifecycleEpoch || this.#factsSequence < Math.max(pending.requiredSequence, this.#eventSequence)) return;
		this.#pendingReplacement = null;
		if (this.#program && isNewer(pending.replacement, this.#program)) this.#beginReplacement(pending.replacement);
	}

	#handleYield(yielded, source) {
		if (yielded.kind === 'command') {
			const actionId = `${this.#program.programId}:${this.#program.version}:${this.#generation}:${yielded.stateToken}`;
			const authority = source?.authority ?? (source && typeof source === 'object' && typeof source.watcherId === 'string' ? source : null);
			const executionFactsSequence = source?.executionFactsSequence ?? this.#factsSequence;
			const command = freezeRecord({ actionId, action: freezeRecord({ type: yielded.call.primitive, arguments: yielded.call.arguments }), provenance: freezeRecord({ agentId: this.#program.agentId, goalRevision: this.#program.goalRevision, modelIdentity: this.#program.modelIdentity, programId: this.#program.programId, version: this.#program.version, generation: this.#generation, source: authority ? `watcher:${authority.watcherId}` : source, watcherId: authority?.watcherId ?? null, authorizingEventSequence: authority?.eventSequence ?? null, executionFactsSequence, factsEventSequence: executionFactsSequence, stepId: yielded.stepId, eventSequence: this.#eventSequence }) });
			this.#active = { actionId, stateToken: yielded.stateToken, generation: this.#generation, source, authority, executionFactsSequence };
			this.#callbacks.dispatch(command);
			return;
		}
		if (yielded.kind === 'finish' || yielded.kind === 'checkpoint') {
			if (this.#deferredBase) { this.#vm.discardDeferredCommand(); this.#deferredBase = null; }
			this.#status = yielded.kind === 'finish' ? 'FINISHED' : 'PAUSED';
			return;
		}
		if (yielded.kind === 'idle' && !this.#active && this.#boundary.length > 0) return this.#runBoundary();
		if (yielded.kind === 'idle' && this.#deferredBase) {
			if (this.#boundary.length > 0) return this.#runBoundary();
			const deferred = this.#deferredBase; this.#deferredBase = null;
			this.#handleYield(this.#vm.resumeDeferredCommand(deferred.result, this.#facts), 'step');
		}
	}
}

function normalizeInstall(input) {
	if (!input || typeof input !== 'object' || !input.compiled?.ast || !Object.isFrozen(input.compiled)) throw new TypeError('ArenaScriptEngine requires a frozen compiled program');
	const { agentId, modelIdentity, goalRevision } = input;
	for (const [field, value] of [['agentId', agentId], ['modelIdentity', modelIdentity], ['programId', input.programId]]) if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonempty string`);
	for (const [field, value] of [['goalRevision', goalRevision], ['version', input.version], ['eventSequence', input.eventSequence]]) if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonnegative safe integer`);
	const facts = createInterpreterFacts(input.observation);
	return freezeRecord({ agentId: agentId.trim(), goalRevision, modelIdentity: modelIdentity.trim(), programId: input.programId.trim(), version: input.version, compiled: input.compiled, facts, factsSequence: input.eventSequence, eventSequence: input.eventSequence });
}
function normalizeDirectiveReplacement(input, current) {
	if (!current || !input || typeof input !== 'object') throw new TypeError('ArenaScriptEngine replacement requires an active authenticated program');
	for (const field of ['agentId', 'goalRevision', 'modelIdentity', 'observation', 'eventSequence']) if (Object.hasOwn(input, field)) throw new TypeError(`ArenaScriptEngine directive install may not supply ${field}`);
	if (!input.compiled?.ast || !Object.isFrozen(input.compiled)) throw new TypeError('ArenaScriptEngine requires a frozen compiled program');
	for (const [field, value] of [['programId', input.programId]]) if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonempty string`);
	if (!Number.isSafeInteger(input.version) || input.version < 0) throw new TypeError('ArenaScriptEngine version must be a nonnegative safe integer');
	return freezeRecord({ agentId: current.agentId, goalRevision: current.goalRevision, modelIdentity: current.modelIdentity, programId: input.programId.trim(), version: input.version, compiled: input.compiled });
}
function isNewer(next, current) { return next.goalRevision > current.goalRevision || (next.goalRevision === current.goalRevision && next.version > current.version); }
function installationRelation(next, current) {
	if (next.goalRevision !== current.goalRevision) return next.goalRevision > current.goalRevision ? 1 : -1;
	if (next.version !== current.version) return next.version > current.version ? 1 : -1;
	if (!sameImmutableProgram(next, current)) return -1;
	return next.eventSequence > current.eventSequence ? 0 : -1;
}
function sameImmutableProgram(next, current) { return next.agentId === current.agentId && next.modelIdentity === current.modelIdentity && next.programId === current.programId && next.compiled === current.compiled && next.compiled.source === current.compiled.source; }
function requestContext(program, generation, lifecycleEpoch, eventSequence, factsSequence, observation) { return freezeRecord({ agentId: program.agentId, goalRevision: program.goalRevision, modelIdentity: program.modelIdentity, programId: program.programId, version: program.version, generation, lifecycleEpoch, eventSequence, factsSequence, observation }); }
function sameRequest(value, request) { return value && ['agentId', 'goalRevision', 'modelIdentity', 'programId', 'version', 'generation', 'lifecycleEpoch', 'eventSequence', 'factsSequence'].every((key) => value[key] === request[key]); }
function watcherExecution(authority, executionFactsSequence = authority.eventSequence) { return freezeRecord({ authority, executionFactsSequence }); }
function watcherMode(compiled, index) { const watches = []; for (const statement of compiled.ast.body) { const call = statement.type === 'ExpressionStatement' ? statement.expression : null; if (call?.type === 'CallExpression' && call.callee.type === 'MemberExpression' && call.callee.object.name === 'program' && call.callee.property.name === 'watch') watches.push(call); } return watches[index]?.arguments[1]?.properties?.find((property) => property.key.name === 'mode')?.value?.value ?? 'boundary'; }
function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
