import { createHash, randomUUID } from 'node:crypto';
import { ArenaScriptEngine } from './arena-script/program-engine.mjs';
import { parseArenaScript } from './arena-script/parser.mjs';
import { freezeQueryResult } from './arena-script/interpreter.mjs';
import { adaptObservation } from './observation-adapter.mjs';
import { normalizeMinecraftToolCall } from './native-minecraft-tools.mjs';
import { validateAction } from './schema.mjs';
import { validateProgramParameters } from './program-parameters.mjs';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
// Session trace: ordinary sightings re-woke the model every ~3 s while a program ran (305 of 358 turns ended with no tool call).
export const ORDINARY_ATTENTION_INTERVAL_MS = 30_000;

/** Runs selected-model ArenaScript through native tools, returning every new decision to its caller. */
export class NativeProgramExecutor {
	#runs = new Map(); #setTimeout; #clearTimeout; #cancellationTimeoutMs; #sessionId; #sequence = 0; #now; #ordinaryAttentionIntervalMs;
	constructor({ setTimeoutFn = setTimeout, clearTimeoutFn = clearTimeout, cancellationTimeoutMs = 5_000, sessionId = randomUUID(), now = Date.now, ordinaryAttentionIntervalMs = ORDINARY_ATTENTION_INTERVAL_MS } = {}) {
		if (typeof setTimeoutFn !== 'function' || typeof clearTimeoutFn !== 'function') throw new TypeError('timer callbacks are required');
		if (typeof sessionId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(sessionId)) throw new TypeError('sessionId must be a bounded identifier');
		this.#sessionId = createHash('sha256').update(sessionId).digest('hex').slice(0, 32);
		integer(cancellationTimeoutMs, 'cancellationTimeoutMs', 1, 10_000);
		this.#setTimeout = setTimeoutFn; this.#clearTimeout = clearTimeoutFn; this.#cancellationTimeoutMs = cancellationTimeoutMs;
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		integer(ordinaryAttentionIntervalMs, 'ordinaryAttentionIntervalMs', 0, 600_000);
		this.#now = now; this.#ordinaryAttentionIntervalMs = ordinaryAttentionIntervalMs;
	}

	run(record, { source, parameters, maxActions = 64, timeoutMs = 30_000, expectedDurationMs, planningLeadMs, observationIntervalMs, programId: suppliedProgramId, provenance = {} } = {}, context = {}) {
		validateRecord(record);
		integer(maxActions, 'maxActions', 1, 256); integer(timeoutMs, 'timeoutMs', 1, 120_000);
		if (expectedDurationMs !== undefined) integer(expectedDurationMs, 'expectedDurationMs', 1, timeoutMs);
		parameters = validateProgramParameters(parameters);
		if (planningLeadMs !== undefined && planningLeadMs !== null) integer(planningLeadMs, 'planningLeadMs', 0, Number.MAX_SAFE_INTEGER);
		if (observationIntervalMs !== undefined) {
			integer(observationIntervalMs, 'observationIntervalMs', 100, 5000);
			if (typeof context.refreshObservation !== 'function') throw codedError('INSPECTION_UNAVAILABLE', 'Requested sampling requires fresh observations');
		}
		if (this.#runs.has(record.agentId)) throw codedError('PROGRAM_BUSY', 'Cancel the active program before starting another');
		if (typeof context.executeAction !== 'function' || typeof context.cancelAction !== 'function') throw new TypeError('executeAction and cancelAction callbacks are required');
		integer(context.eventSequence, 'eventSequence', 1, Number.MAX_SAFE_INTEGER);
		const compiled = parseArenaScript(source);
		const programId = suppliedProgramId ?? `native-program-${this.#sessionId}-${++this.#sequence}`;
		if (typeof programId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(programId)) throw new TypeError('programId must be a bounded identifier');
		let resolve;
		const result = new Promise((done) => { resolve = done; });
		const run = { record: { ...record }, context, programId, maxActions, timeoutMs, expectedDurationMs, planningLeadMs: planningLeadMs ?? null, actions: 0, actionsSucceeded: 0, actionsFailed: 0, receipts: [], resolve, result,
			settled: false, bodyPending: false, pendingActionId: null, stopping: null, timer: null, planningDueTimer: null, planningDueNotified: false, planningDueVersion: null, cancellationTimer: null,
			observation: programObservation(context.observation), eventSequence: context.eventSequence, engine: null,
			observationIntervalMs, observationTimer: null, refresh: null, refreshGeneration: 0, decision: null, decisionSequence: 0, notifiedDecisionId: null, lastOrdinaryNotificationAt: Number.NEGATIVE_INFINITY, ordinaryNotificationTimer: null, unseenAttention: false };
		run.engine = new ArenaScriptEngine({
			dispatch: (command) => { void this.#dispatch(run, command); },
			cancel: (actionId) => { void this.#cancelBody(run, actionId); },
			inspect: (request) => { void this.#query(run, request); },
			requestModel: (request) => this.#requestDecision(run, request),
		});
		this.#runs.set(record.agentId, run);
		run.timer = this.#setTimeout(() => this.#return(run, { state: 'TIMED_OUT', reasonCode: 'PROGRAM_DEADLINE' }, true), timeoutMs);
		run.timer?.unref?.();
		try {
			run.engine.install({ agentId: record.agentId, provider: record.provider, modelIdentity: record.model, reasoningEffort: record.reasoningEffort,
				serviceTier: record.serviceTier ?? 'priority', goalRevision: record.goalRevision, programId, version: 1, compiled,
				traceId: provenance.traceId ?? programId, parameters, observation: run.observation, eventSequence: run.eventSequence });
			this.#schedulePlanningDue(run);
			this.#check(run);
			this.#scheduleObservation(run);
		} catch (error) { this.#return(run, failure(error, 'PROGRAM_EXECUTION_FAILED'), true); }
		return result;
	}

	#refresh(run, purpose = 'interval') {
		if (run.refresh === null) {
			const refresh = { generation: ++run.refreshGeneration, purpose, promise: null };
			run.refresh = refresh;
			refresh.promise = Promise.resolve().then(() => run.context.refreshObservation?.()).finally(() => {
				if (run.refresh === refresh) run.refresh = null;
			});
		}
		return run.refresh;
	}

	#scheduleObservation(run) {
		if (run.settled || run.stopping !== null || run.observationIntervalMs === undefined || run.observationTimer !== null) return;
		const timer = this.#setTimeout(async () => {
			if (run.observationTimer !== timer || run.settled || run.stopping !== null) return;
			run.observationTimer = null;
			const refresh = this.#refresh(run);
			try {
				const fresh = await refresh.promise;
				if (run.refreshGeneration === refresh.generation && !run.settled && run.stopping === null) this.onObservation(run.record, fresh);
			} catch (error) {
				if (run.refreshGeneration === refresh.generation && !run.settled && run.stopping === null) this.#return(run, failure(error, 'FRESH_OBSERVATION_REQUIRED'), true);
			} finally { if (run.refreshGeneration === refresh.generation) this.#scheduleObservation(run); }
		}, run.observationIntervalMs);
		run.observationTimer = timer;
		run.observationTimer?.unref?.();
	}

	onObservation(record, payload = {}) {
		const run = this.#runs.get(record.agentId);
		if (!run || run.record.goalRevision !== record.goalRevision || run.settled) return false;
		if (!Number.isSafeInteger(payload.eventSequence) || payload.eventSequence <= run.eventSequence) return false;
		try {
			const previousObservation = run.observation;
			run.observation = programObservation(payload.observation ?? payload);
			run.eventSequence = payload.eventSequence;
			// A new hazard in view, lost health or air, or a changed dimension/readiness is never held back.
			if (hazardEdge(previousObservation, run.observation)) run.lastOrdinaryNotificationAt = Number.NEGATIVE_INFINITY;
			if (run.refresh?.purpose === 'interval') {
				// A newer publication replaces background sampling, but cannot replace
				// the terminal-effect barrier owned by a completed action.
				run.refreshGeneration++;
				run.refresh = null;
				this.#scheduleObservation(run);
			}
			const decisionSequence = run.decisionSequence;
			run.engine.ingestObservation({ ...payload, observation: run.observation });
			// A new attention event invalidates an older decision. Ordinary progress
			// refreshes facts without starving a model response on every physics tick.
			if (payload.attention === true && run.decision !== null && run.decisionSequence === decisionSequence) {
				const priority = payload.priority ?? 'ordinary';
				const request = run.engine.refreshDirectiveRequest();
				// Only escalation (urgent attention or a changed action failure) needs a new handle. Ordinary sightings keep
				// the pending decision (so an in-flight respondProgram stays valid) and only refresh its facts.
				if (priority === 'urgent' || this.#escalates(run, request)) this.#requestDecision(run, request, priority);
				else {
					this.#foldOrdinary(run, request);
					this.#notifyDecision(run, priority);
				}
			}
			this.#check(run);
			return true;
		} catch (error) { this.#return(run, failure(error, 'INVALID_OBSERVATION'), true); return false; }
	}

	#schedulePlanningDue(run) {
		if (run.settled || run.stopping !== null || run.planningDueNotified || run.planningLeadMs === null
			|| run.planningLeadMs <= 0 || typeof run.context.onPlanningDue !== 'function') return;
		const snapshot = run.engine.snapshot();
		run.planningDueVersion = snapshot.version;
		const delay = Math.max(0, (run.expectedDurationMs ?? run.timeoutMs) - run.planningLeadMs);
		run.planningDueTimer = this.#setTimeout(() => {
			run.planningDueTimer = null;
			// The advisory belongs to the version for which the deadline timer was
			// armed. A replacement invalidates the old timer without interrupting
			// the body or manufacturing a new decision.
			if (run.settled || run.stopping !== null || run.planningDueNotified) return;
			run.planningDueNotified = true;
			const snapshot = run.engine.snapshot();
			if (run.decision !== null || snapshot.status !== 'ACTIVE' || snapshot.version !== run.planningDueVersion) return;
			try { run.context.onPlanningDue(this.status(run.record), { planningLeadMs: run.planningLeadMs }); }
			catch { /* planning-ahead is advisory and cannot interrupt body execution */ }
		}, delay);
		run.planningDueTimer?.unref?.();
	}

	#requestDecision(run, request, notificationPriority = request?.priority) {
		if (run.settled || run.stopping !== null || request === null) return;
		if (request.trigger === 'program_exhausted' || typeof run.context.onDecision !== 'function') {
			this.#return(run, { state: 'YIELDED', reasonCode: request.trigger === 'program_exhausted' ? 'PROGRAM_EXHAUSTED' : 'MODEL_DECISION_REQUIRED',
				trigger: request.trigger, ...(request.actionFailure === undefined ? {} : { actionFailure: request.actionFailure }) });
			return;
		}
		run.decision = { decisionId: `${run.programId}:decision-${++run.decisionSequence}`, programVersion: request.version,
			trigger: request.trigger, priority: request.priority, eventSequence: request.eventSequence,
			...(request.actionFailure === undefined ? {} : { actionFailure: request.actionFailure }) };
		run.unseenAttention = true;
		// The engine must finish applying the authored attention policy first.
		const decision = run.decision;
		queueMicrotask(() => {
			if (run.settled || run.decision !== decision) return;
			this.#notifyDecision(run, notificationPriority);
		});
	}

	/**
	 * Wakes the model for the pending decision. Ordinary attention on a routine that keeps running
	 * (nothing is paused, no action failed) is not urgent: one notification per window carries the
	 * latest facts, instead of a fresh model call for every sighting while the authored work continues.
	 * Urgent, failed or paused decisions always notify at once.
	 */
	#notifyDecision(run, notificationPriority) {
		if (run.settled || run.decision === null) return;
		// A folded sighting on an idle or paused body: the model already holds this handle and answers it with the
		// newest facts, so it is told once, not once per observation.
		if (!this.#deferrableOrdinary(run, notificationPriority) && notificationPriority !== 'urgent'
			&& run.notifiedDecisionId === run.decision.decisionId && run.ordinaryNotificationTimer === null) return;
		if (this.#deferrableOrdinary(run, notificationPriority)) {
			const dueAt = run.lastOrdinaryNotificationAt + this.#ordinaryAttentionIntervalMs;
			const now = this.#now();
			if (now < dueAt) {
				if (run.ordinaryNotificationTimer === null) {
					run.ordinaryNotificationTimer = this.#setTimeout(() => {
						run.ordinaryNotificationTimer = null;
						this.#notifyDecision(run, run.decision?.priority ?? 'ordinary');
					}, dueAt - now);
					run.ordinaryNotificationTimer?.unref?.();
				}
				return;
			}
			run.lastOrdinaryNotificationAt = now;
		}
		this.#clearOrdinaryNotification(run);
		run.unseenAttention = false;
		run.notifiedDecisionId = run.decision.decisionId;
		// The pending request retains its highest urgency, but later ordinary
		// discoveries must not repeatedly interrupt that same reconsideration.
		try { run.context.onDecision(this.status(run.record), { priority: notificationPriority }); }
		catch (error) { this.#return(run, failure(error, 'PROGRAM_NOTIFICATION_FAILED'), true); }
	}

	/** A new decision handle is owed only for a higher urgency, a changed action failure or a new urgent trigger. */
	#escalates(run, request) {
		if (!request) return false;
		return request.priority !== run.decision.priority
			|| JSON.stringify(request.actionFailure) !== JSON.stringify(run.decision.actionFailure)
			|| (request.priority === 'urgent' && request.trigger !== run.decision.trigger);
	}

	#deferrableOrdinary(run, notificationPriority) {
		if (notificationPriority === 'urgent' || run.decision?.priority === 'urgent' || run.decision?.actionFailure !== undefined) return false;
		const snapshot = run.engine.snapshot();
		// Deferring only helps while authored work keeps the body busy. A routine whose source ran out holds the
		// body until the model answers the pending decision, so that wait must not also sit out the window.
		return snapshot.status === 'ACTIVE' && (snapshot.activeActionId !== null || snapshot.activeQueryId !== null);
	}

	/** Newer ordinary attention folded into the pending decision: same handle, latest trigger and facts, not yet seen. */
	#foldOrdinary(run, request) {
		if (run.decision === null || request === null || request === undefined) return;
		run.decision.trigger = request.trigger;
		run.decision.eventSequence = request.eventSequence;
		run.unseenAttention = true;
	}

	#clearOrdinaryNotification(run) {
		if (run.ordinaryNotificationTimer === null || run.ordinaryNotificationTimer === undefined) return;
		this.#clearTimeout(run.ordinaryNotificationTimer);
		run.ordinaryNotificationTimer = null;
	}

	status(record) {
		const run = this.#runs.get(record.agentId);
		if (!run || run.record.goalRevision !== record.goalRevision || run.settled) return null;
		const snapshot = run.engine.snapshot();
		return { programVersion: snapshot.version, engineState: snapshot.status,
			...(run.decision === null ? {} : { decision: structuredClone(run.decision) }) };
	}

	respond(record, { programId, decisionId, directive, source }) {
		const run = this.#runs.get(record.agentId);
		if (!run || run.record.goalRevision !== record.goalRevision || run.programId !== programId || run.settled || run.stopping !== null
			|| run.decision?.decisionId !== decisionId) throw codedError('STALE_PROGRAM_DECISION', 'Read the current program decision before responding');
		if (!['continue', 'pause', 'replace', 'finish'].includes(directive)) throw codedError('INVALID_PROGRAM_DIRECTIVE', 'Unsupported program directive');
		const compiled = directive === 'replace' ? parseArenaScript(source) : null;
		const request = run.engine.refreshDirectiveRequest();
		if (request === null) throw codedError('STALE_PROGRAM_DECISION', 'The program no longer needs this decision');
		if (directive === 'continue' && request.actionFailure !== undefined) throw codedError('PROGRAM_REPLACEMENT_REQUIRED', 'A halted routine after repeated action failure requires replacement or an explicit stop');
		run.decision = null;
		if (directive === 'replace') {
			// Replacements may wait for the current body action to acknowledge before
			// installing their next engine version. Fence the old advisory now so a
			// queued deadline callback cannot notify against obsolete authority.
			run.planningDueNotified = true;
			this.#clearPlanningDue(run);
		}
		if (directive === 'pause' || directive === 'finish') {
			this.#return(run, { state: 'YIELDED', reasonCode: directive === 'pause' ? 'MODEL_PAUSED' : 'PROGRAM_FINISH_REQUESTED',
				...(directive === 'finish' ? { finishRequested: true } : {}) }, true);
			return run.result;
		}
		// The model answered what it was shown; attention folded in after that notification is still owed to it.
		const unseen = directive === 'continue' && run.unseenAttention === true ? { trigger: request.trigger } : null;
		run.unseenAttention = false;
		run.engine.applyDirective({ ...request, directive, ...(compiled === null ? {} : { install: { programId, version: request.version + 1, compiled } }) });
		if (unseen !== null && run.decision === null && run.engine.snapshot().status === 'ACTIVE') run.engine.notifyAttention({ priority: 'ordinary', trigger: unseen.trigger });
		this.#check(run);
		return this.status(record) ?? { state: 'ENDED' };
	}

	cancel(agentId, reason = 'PROGRAM_CANCELLED') {
		const run = this.#runs.get(agentId);
		if (!run) return Promise.resolve({ state: 'CANCELLED', reasonCode: 'NO_ACTIVE_PROGRAM' });
		this.#return(run, { state: 'CANCELLED', reasonCode: boundedReason(reason, 'PROGRAM_CANCELLED') }, true);
		return run.result;
	}

	expire(record, programId) {
		const run = this.#runs.get(record.agentId);
		if (!run || run.record.goalRevision !== record.goalRevision || run.programId !== programId) return Promise.resolve(null);
		// Do not overwrite cancellation or a deadline already being acknowledged.
		if (run.stopping === null) this.#return(run, { state: 'TIMED_OUT', reasonCode: 'PROGRAM_DEADLINE' }, true);
		return run.result;
	}

	async #dispatch(run, suppliedCommand) {
		if (run.settled) return;
		if (run.stopping !== null || run.actions >= run.maxActions) {
			this.#return(run, run.stopping ?? { state: 'YIELDED', reasonCode: 'PROGRAM_ACTION_LIMIT' }, true);
			return;
		}
		let command;
		try { command = canonicalCommand(suppliedCommand); }
		catch (error) { this.#return(run, failure(error, 'INVALID_PROGRAM_ACTION'), true); return; }
		run.actions++;
		run.bodyPending = true;
		run.pendingActionId = command.actionId;
		let result;
		try { result = freezeQueryResult(await run.context.executeAction(command)); }
		catch (error) {
			if (run.settled) return;
			run.bodyPending = false;
			void Promise.resolve().then(() => run.context.cancelAction(command.actionId, 'PROGRAM_ACTION_UNCERTAIN')).catch(() => {});
			this.#return(run, { state: 'UNKNOWN', reasonCode: boundedReason(error?.code, 'PROGRAM_ACTION_UNCERTAIN') }, true);
			return;
		}
		if (run.settled) return;
		run.bodyPending = false;
		if (!TERMINAL.has(result.state) || typeof result.reasonCode !== 'string' || result.reasonCode.length > 128) {
			void Promise.resolve().then(() => run.context.cancelAction(command.actionId, 'INVALID_ACTION_RESULT')).catch(() => {});
			this.#return(run, { state: 'UNKNOWN', reasonCode: 'INVALID_ACTION_RESULT' }, true); return;
		}
		if (result.state === 'SUCCEEDED') run.actionsSucceeded++;
		else run.actionsFailed++;
		run.receipts.push({ actionId: command.actionId, actionType: command.action.type, sourceStepId: command.provenance.stepId,
			state: result.state, reasonCode: result.reasonCode,
			...(typeof result.actionId === 'string' ? { bodyActionId: result.actionId } : {}),
			...(typeof result.executionStarted === 'boolean' ? { executionStarted: result.executionStarted } : {}),
			...(typeof result.physicalAttempted === 'boolean' ? { physicalAttempted: result.physicalAttempted } : {}) });
		if (run.receipts.length > 64) run.receipts.shift();
		if (run.stopping !== null) {
			run.engine.suspend(run.stopping.reasonCode);
			run.engine.ingestActionResult({ actionId: command.actionId, state: result.state, reasonCode: result.reasonCode, eventSequence: run.eventSequence });
			this.#check(run); return;
		}
		let fresh;
		try {
			// A sample requested during the action cannot prove its terminal effects.
			// Supersede its result, failure and cleanup instead of waiting on it.
			run.refreshGeneration++;
			run.refresh = null;
			fresh = result.observation !== undefined && Number.isSafeInteger(result.eventSequence) ? result : await this.#refresh(run, 'terminal').promise;
			if (!fresh || !Number.isSafeInteger(fresh.eventSequence) || fresh.eventSequence <= command.provenance.eventSequence) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Observe action effects before continuing the program');
			if (run.settled) return;
			if (fresh.eventSequence > run.eventSequence) this.onObservation(run.record, fresh);
			if (run.settled) return;
			if (run.stopping !== null) run.engine.suspend(run.stopping.reasonCode);
			run.engine.ingestActionResult({ actionId: command.actionId, state: result.state, reasonCode: result.reasonCode, eventSequence: fresh.eventSequence });
			this.#check(run);
		} catch (error) { this.#return(run, { state: 'YIELDED', reasonCode: boundedReason(error?.code, 'FRESH_OBSERVATION_REQUIRED') }, true); }
		finally { this.#scheduleObservation(run); }
	}

	async #query(run, { queryId, operation, query, authorship }) {
		let value;
		try {
			if (operation === 'inspect') value = await run.context.inspect?.(query) ?? { state: 'FAILED', reasonCode: 'INSPECTION_UNAVAILABLE' };
			else if (operation === 'taskMemory') {
				const { kind, ...args } = normalizeMinecraftToolCall('taskMemory', query);
				value = await run.context.memoryOperation?.({ operation: 'task', arguments: args, provenance: authorship }) ?? { state: 'FAILED', reasonCode: 'MEMORY_UNAVAILABLE' };
			}
			else {
				const normalized = normalizeMinecraftToolCall(operation === 'remember' ? 'notebook' : 'queryMemory', query);
				const args = operation === 'remember' ? { key: normalized.key, text: normalized.text }
					: { kind: normalized.memoryKind, limit: normalized.limit, ...(normalized.offset === undefined ? {} : { offset: normalized.offset }), ...(normalized.text === undefined ? {} : { text: normalized.text }) };
				value = await run.context.memoryOperation?.({ operation: operation === 'remember' ? 'write' : 'query', arguments: args, provenance: authorship })
					?? { state: 'FAILED', reasonCode: 'MEMORY_UNAVAILABLE' };
			}
		} catch (error) { value = failure(error, 'QUERY_FAILED'); }
		if (run.settled || run.engine.snapshot().activeQueryId !== queryId) return;
		try { run.engine.ingestQueryResult({ queryId, value }); this.#check(run); }
		catch (error) { this.#return(run, failure(error, 'INVALID_QUERY_RESULT'), true); }
	}

	async #cancelBody(run, actionId) {
		if (run.settled || !run.bodyPending || run.pendingActionId !== actionId) return;
		try { await run.context.cancelAction(actionId, run.stopping?.reasonCode ?? 'MODEL_AUTHORED_INTERRUPT'); }
		catch (error) {
			// The terminal action receipt can arrive before the cancel transport fails.
			// Once acknowledged, that old failure cannot invalidate a newer body action.
			if (run.settled || !run.bodyPending || run.pendingActionId !== actionId) return;
			this.#finish(run, { state: 'UNKNOWN', reasonCode: boundedReason(error?.code, 'PROGRAM_CANCEL_UNCERTAIN') });
		}
	}

	#return(run, outcome, cancel = false) {
		if (run.settled) return;
		run.stopping = run.stopping ?? outcome;
		if (cancel) run.stopping = outcome;
		this.#clearPlanningDue(run);
		if (cancel || !run.bodyPending) run.engine.suspend(run.stopping.reasonCode);
		if (run.bodyPending && cancel && run.cancellationTimer === null) {
			run.cancellationTimer = this.#setTimeout(() => this.#finish(run, { state: 'UNKNOWN', reasonCode: 'PROGRAM_CANCEL_ACK_TIMEOUT' }), this.#cancellationTimeoutMs);
			run.cancellationTimer?.unref?.();
		}
		this.#check(run);
	}

	#check(run) {
		if (run.settled) return;
		if (run.stopping === null && run.decision !== null) {
			const request = run.engine.refreshDirectiveRequest();
			// The shared engine coalesces failures behind an outstanding model request.
			// Publish that changed decision without treating ordinary progress as one.
			// A different ordinary trigger is new facts for the same decision, not a new handle.
			if (this.#escalates(run, request)) this.#requestDecision(run, request);
			else if (request && request.trigger !== run.decision.trigger) this.#foldOrdinary(run, request);
			// The body went idle behind a deferred ordinary notification: tell the model now, once.
			if (run.decision !== null && run.ordinaryNotificationTimer !== null && !this.#deferrableOrdinary(run, run.decision.priority)) this.#notifyDecision(run, run.decision.priority);
		}
		const snapshot = run.engine.snapshot();
		if (run.stopping !== null && !run.bodyPending) this.#finish(run, run.stopping);
		else if (snapshot.status === 'PAUSED') this.#finish(run, { state: 'YIELDED', reasonCode: 'PROGRAM_CHECKPOINT' });
		else if (snapshot.status === 'FINISHED') this.#finish(run, { state: 'YIELDED', reasonCode: 'PROGRAM_FINISH_REQUESTED', finishRequested: true });
		else if (snapshot.status === 'ACTIVE' && snapshot.activeActionId === null && snapshot.activeQueryId === null && snapshot.pendingRequestTrigger === null) {
			this.#finish(run, { state: 'YIELDED', reasonCode: 'PROGRAM_IDLE' });
		}
	}

	#finish(run, outcome) {
		if (run.settled) return;
		run.settled = true;
		this.#clearTimeout(run.timer);
		this.#clearPlanningDue(run);
		this.#clearOrdinaryNotification(run);
		if (run.observationTimer !== null) this.#clearTimeout(run.observationTimer);
		if (run.cancellationTimer !== null) this.#clearTimeout(run.cancellationTimer);
		this.#runs.delete(run.record.agentId);
		const snapshot = run.engine.snapshot();
		run.engine.dispose();
		run.resolve({ ...outcome, programId: run.programId, programVersion: snapshot.version, actions: run.actions, actionsSucceeded: run.actionsSucceeded, actionsFailed: run.actionsFailed, receipts: run.receipts, eventSequence: snapshot.eventSequence,
			observation: run.observation, ...(run.decision === null ? {} : { decision: structuredClone(run.decision) }), ...(run.actions > 64 ? { omittedReceipts: run.actions - run.receipts.length } : {}) });
	}

	#clearPlanningDue(run) {
		if (run.planningDueTimer === null) return;
		this.#clearTimeout(run.planningDueTimer);
		run.planningDueTimer = null;
	}
}

function canonicalCommand(command) {
	const type = command.action.type;
	const supplied = command.action.arguments;
	const args = supplied !== null && typeof supplied === 'object' && !Array.isArray(supplied) ? supplied
		: ['wait', 'use_item', 'block_with_shield'].includes(type) ? { durationMs: supplied } : {};
	if (Object.hasOwn(args, 'type')) throw codedError('INVALID_ACTION', 'Action arguments cannot override action type');
	const { type: validatedType, ...normalized } = validateAction({ ...args, type });
	return Object.freeze({ ...command, action: Object.freeze({ type: validatedType, arguments: Object.freeze(normalized) }) });
}
const HAZARD_BLOCK = /lava|fire|magma/i;
function hazardKeys(observation) {
	const keys = new Set();
	for (const block of Array.isArray(observation?.blocks) ? observation.blocks : []) {
		const id = block?.blockId ?? block?.id;
		if (typeof id === 'string' && HAZARD_BLOCK.test(id)) keys.add(`${id}@${block.x},${block.y},${block.z}`);
	}
	return keys;
}
const finiteValue = (value) => (Number.isFinite(value) ? value : null);
/** Facts that must reach the model at once even when the routine itself keeps running. */
export function hazardEdge(previous, next) {
	if (previous === null || typeof previous !== 'object' || next === null || typeof next !== 'object') return false;
	const before = hazardKeys(previous);
	for (const key of hazardKeys(next)) if (!before.has(key)) return true;
	const health = [finiteValue(previous.player?.health), finiteValue(next.player?.health)];
	if (health[0] !== null && health[1] !== null && health[1] < health[0]) return true;
	const air = [finiteValue(previous.player?.air ?? previous.player?.airSupply), finiteValue(next.player?.air ?? next.player?.airSupply)];
	if (air[0] !== null && air[1] !== null && air[1] < air[0]) return true;
	if ((previous.world?.dimension ?? null) !== (next.world?.dimension ?? null)) return true;
	if (previous.ready !== next.ready || JSON.stringify(previous.status ?? null) !== JSON.stringify(next.status ?? null)) return true;
	return false;
}

function programObservation(observation) {
	return observation && Object.hasOwn(observation, 'ready') && (Object.hasOwn(observation, 'position') || observation.ready === false)
		? adaptObservation(observation) : observation;
}
function validateRecord(record) {
	for (const key of ['agentId', 'provider', 'model', 'reasoningEffort']) if (typeof record?.[key] !== 'string' || record[key].trim().length === 0 || record[key].length > 256) throw new TypeError(`record.${key} is required`);
	integer(record.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER);
}
function integer(value, field, minimum, maximum) { if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new TypeError(`${field} must be ${minimum}..${maximum}`); }
function boundedReason(value, fallback) { return typeof value === 'string' && /^[A-Z0-9_]{1,128}$/.test(value) ? value : fallback; }
function failure(error, fallback) { return { state: 'FAILED', reasonCode: boundedReason(error?.code, fallback) }; }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
