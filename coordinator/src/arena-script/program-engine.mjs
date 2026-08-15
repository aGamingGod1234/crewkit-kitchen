import { ArenaScriptInterpreter } from './interpreter.mjs';
import { createInterpreterFacts } from './facts.mjs';
import { SCRIPT_BINDINGS } from './minecraft-api.mjs';

/** Coordinates one model-authored ArenaScript program and its factual event stream. */
export class ArenaScriptEngine {
	#callbacks;
	#vm = null;
	#program = null;
	#facts = null;
	#eventSequence = -1;
	#active = null;
	#pendingResult = null;
	#boundaryWatchers = [];
	#watcherStates = new Map();
	#cancelling = null;
	#modelContext = null;
	#status = 'IDLE';

	constructor({ dispatch, cancel, requestModel } = {}) {
		if (typeof dispatch !== 'function' || typeof cancel !== 'function' || typeof requestModel !== 'function') throw new TypeError('ArenaScriptEngine callbacks dispatch, cancel, and requestModel are required');
		this.#callbacks = { dispatch, cancel, requestModel };
	}

	install({ agentId, goalRevision, modelIdentity, programId, version, compiled, observation, eventSequence }) {
		if (!compiled?.ast || !Number.isSafeInteger(eventSequence)) throw new TypeError('ArenaScriptEngine install requires compiled source and an event sequence');
		this.dispose();
		this.#program = Object.freeze({ agentId, goalRevision, modelIdentity, programId, version, compiled });
		this.#facts = createInterpreterFacts(observation);
		this.#eventSequence = eventSequence;
		this.#vm = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS);
		this.#status = 'ACTIVE';
		this.#handleYield(this.#vm.start(this.#facts), 'step');
		for (let index = 0; index < compiled.watcherCount && this.#current(); index += 1) this.#watcherStates.set(`watcher-${index}`, this.#vm.evaluateWatcher(`watcher-${index}`, this.#facts));
		return this.snapshot();
	}

	ingestObservation({ observation, eventSequence, attention = false }) {
		if (!this.#current() || !Number.isSafeInteger(eventSequence) || eventSequence <= this.#eventSequence) return this.snapshot();
		this.#facts = createInterpreterFacts(observation);
		this.#eventSequence = eventSequence;
		if (this.#pendingResult && !this.#cancelling) {
			const result = this.#pendingResult;
			this.#pendingResult = null;
			this.#handleYield(this.#vm.resume(result, this.#facts), 'step');
		}
		if (attention && this.#current()) this.#evaluateAttention();
		if (!this.#active && !this.#cancelling && this.#boundaryWatchers.length > 0 && this.#current()) this.#runBoundaryWatcher();
		return this.snapshot();
	}

	ingestActionResult({ actionId, state, reasonCode, message = '', eventSequence }) {
		if (!this.#current() || !this.#active || this.#active.actionId !== actionId || !Number.isSafeInteger(eventSequence) || eventSequence < this.#eventSequence) return this.snapshot();
		const active = this.#active;
		this.#active = null;
		const result = { stateToken: active.stateToken, state, reasonCode };
		if (this.#cancelling?.actionId === actionId) {
			if (state !== 'CANCELLED') return this.suspend('cancellation_not_acknowledged');
			const pending = this.#cancelling;
			this.#cancelling = null;
			if (pending.kind === 'watcher') {
				this.#vm.abortPendingCommand(result.stateToken);
				this.#handleYield(this.#vm.runWatcher(pending.watcherId, this.#facts), `watcher:${pending.watcherId}`);
			}
			else this.suspend('unhandled_attention');
			return this.snapshot();
		}
		this.#pendingResult = result;
		return this.snapshot();
	}

	applyDirective({ directive, goalRevision = this.#program?.goalRevision, version = this.#program?.version } = {}) {
		if (!this.#current() || goalRevision !== this.#program.goalRevision || version !== this.#program.version) return this.snapshot();
		this.#modelContext = null;
		if (directive === 'pause') return this.suspend('model_paused');
		if (directive === 'finish') this.#status = 'FINISHED';
		return this.snapshot();
	}

	suspend(reason = 'suspended') {
		if (!this.#current()) return this.snapshot();
		if (this.#active && !this.#cancelling) {
			this.#status = 'SUSPENDING';
			this.#cancelling = { kind: 'suspend', actionId: this.#active.actionId, reason };
			this.#callbacks.cancel(this.#active.actionId);
			return this.snapshot();
		}
		this.#status = 'SUSPENDED';
		return this.snapshot();
	}

	dispose() {
		this.#vm = null;
		this.#program = null;
		this.#facts = null;
		this.#active = null;
		this.#pendingResult = null;
		this.#boundaryWatchers = [];
		this.#watcherStates.clear();
		this.#cancelling = null;
		this.#modelContext = null;
		this.#status = 'IDLE';
	}

	snapshot() {
		return Object.freeze({ status: this.#status, eventSequence: this.#eventSequence, activeActionId: this.#active?.actionId ?? null, programId: this.#program?.programId ?? null, version: this.#program?.version ?? null });
	}

	#current() { return this.#vm !== null && ['ACTIVE', 'SUSPENDING'].includes(this.#status); }

	#evaluateAttention() {
		let matched = false;
		for (let index = 0; index < this.#program.compiled.watcherCount; index += 1) {
			const watcherId = `watcher-${index}`;
			const trueNow = this.#vm.evaluateWatcher(watcherId, this.#facts);
			const wasTrue = this.#watcherStates.get(watcherId) === true;
			this.#watcherStates.set(watcherId, trueNow);
			if (!trueNow) continue;
			matched = true;
			if (wasTrue) continue;
			const mode = watcherMode(this.#program.compiled, index);
			if (this.#active) {
				if (mode === 'interrupt' && !this.#cancelling) {
					this.#cancelling = { kind: 'watcher', watcherId, actionId: this.#active.actionId };
					this.#callbacks.cancel(this.#active.actionId);
				} else this.#boundaryWatchers.push(watcherId);
			} else this.#handleYield(this.#vm.runWatcher(watcherId, this.#facts), `watcher:${watcherId}`);
		}
		if (!matched) this.#unhandledAttention();
	}

	#unhandledAttention() {
		const context = this.#modelContext ?? {
			agentId: this.#program.agentId, goalRevision: this.#program.goalRevision, modelIdentity: this.#program.modelIdentity,
			programId: this.#program.programId, version: this.#program.version, eventSequence: this.#eventSequence, observation: this.#facts,
		};
		context.eventSequence = this.#eventSequence;
		context.observation = this.#facts;
		if (!this.#modelContext) {
			this.#modelContext = context;
			this.#callbacks.requestModel(context);
		}
		if (this.#program.compiled.unhandledPolicy === 'pause_and_notify') this.suspend('unhandled_attention');
	}

	#runBoundaryWatcher() {
		const watcherId = this.#boundaryWatchers.shift();
		this.#handleYield(this.#vm.runWatcher(watcherId, this.#facts), `watcher:${watcherId}`);
	}

	#handleYield(yielded, source) {
		if (yielded.kind === 'command') {
			const actionId = `${this.#program.programId}:${this.#program.version}:${yielded.stateToken}`;
			const command = Object.freeze({
				actionId,
				action: Object.freeze({ type: yielded.call.primitive, arguments: yielded.call.arguments }),
				provenance: Object.freeze({ agentId: this.#program.agentId, goalRevision: this.#program.goalRevision, modelIdentity: this.#program.modelIdentity, programId: this.#program.programId, version: this.#program.version, source, stepId: yielded.stepId, eventSequence: this.#eventSequence }),
			});
			this.#active = { actionId, stateToken: yielded.stateToken };
			this.#callbacks.dispatch(command);
			return;
		}
		if (yielded.kind === 'finish') this.#status = 'FINISHED';
		if (yielded.kind === 'checkpoint') this.#status = 'PAUSED';
	}
}

function watcherMode(compiled, index) {
	const watches = [];
	for (const statement of compiled.ast.body) {
		const call = statement.type === 'ExpressionStatement' ? statement.expression : null;
		if (call?.type === 'CallExpression' && call.callee.type === 'MemberExpression' && call.callee.object.name === 'program' && call.callee.property.name === 'watch') watches.push(call);
	}
	return watches[index]?.arguments[1]?.properties?.find((property) => property.key.name === 'mode')?.value?.value ?? 'boundary';
}
