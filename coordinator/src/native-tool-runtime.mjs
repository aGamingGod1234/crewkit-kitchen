import { createHash, randomUUID } from 'node:crypto';
import { validateTraceId } from './control-latency-registry.mjs';
import { ExplorationOccupancy, observationWorldId } from './explore-frontier.mjs';
import { hasDurableObservationFacts, RecoveryProgressStore } from './recovery-progress-wrap.mjs';
import { classifyBodyFailure, composeTwoCallView } from './two-call-llm-wrap.mjs';
import { minecraftCapabilities, normalizeMinecraftToolCall, toolResultContent } from './native-minecraft-tools.mjs';
import { NativeProgramExecutor } from './native-program-executor.mjs';
import { parseArenaScript } from './arena-script/parser.mjs';
import { compileProgramPrecondition, evaluateProgramPrecondition } from './program-precondition.mjs';

const FORGET_REASONS = /agent_removed|server_replaced|coordinator_stopped/;
const TERMINAL_ACTION_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const POST_ACTION_OBSERVATION_TYPES = new Set(['pick_up_item', 'break_block', 'navigate_to']);


export class NativeToolRuntime {
	#bridge;
	#registry;
	#onFinish;
	#trace;
	#observations = new Map();
	#lastLive = new Map();
	#actions = new Map();
	#completions = new Map();
	#staleActions = new Map();
	#occupancy;
	#memoryLoads = new Map();
	#memoryReady = new Set();
	#pendingSpatial = new Map();
	#recovery = new RecoveryProgressStore();
	#decorateObservation;
	#requestObservation;
	#inspectObservation;
	#inspectedTargets = new Map();
	#notebook;
	#executionSettings;
	#memoryOperation;
	#programExecutor;
	#programRuns = new Map();
	#programResults = new Map();
	#onProgramEvent;
	#planningLeadTime;
	#sweeps = new Map();
	#sessionId;
	#receipts = new Map();
	#executionEpochs = new Map();
	#postResultSamples = new Map();
	#sequenceFinishReservations = new Map();
	#sequence = 0;

	constructor({
		bridge,
		registry = null,
		onFinish = async () => ({ state: 'FINISH_REQUESTED' }),
		trace = () => {},
		decorateObservation = null,
		requestObservation = null,
		inspectObservation = null,
		notebook = null,
		executionSettings = null,
		memoryOperation = null,
		programExecutor = null,
		onProgramEvent = () => {},
		planningLeadTime = () => null,
		sessionId = randomUUID(),
		occupancy = new ExplorationOccupancy(),
	} = {}) {
		if (typeof bridge?.send !== 'function') throw new TypeError('bridge.send must be a function');
		if (registry !== null && typeof registry?.get !== 'function') throw new TypeError('registry.get must be a function');
		if (typeof onFinish !== 'function') throw new TypeError('onFinish must be a function');
		if (typeof trace !== 'function') throw new TypeError('trace must be a function');
		if (decorateObservation !== null && typeof decorateObservation !== 'function') throw new TypeError('decorateObservation must be a function');
		if (requestObservation !== null && typeof requestObservation !== 'function') throw new TypeError('requestObservation must be a function');
		if (inspectObservation !== null && typeof inspectObservation !== 'function') throw new TypeError('inspectObservation must be a function');
		if (notebook !== null && ['writeNote', 'query', 'recordReceipt'].some((method) => typeof notebook[method] !== 'function')) throw new TypeError('notebook must support writeNote, query, and recordReceipt');
		if (executionSettings !== null && typeof executionSettings !== 'function') throw new TypeError('executionSettings must be a function');
		if (memoryOperation !== null && typeof memoryOperation !== 'function') throw new TypeError('memoryOperation must be a function');
		if (programExecutor !== null && ['run', 'onObservation', 'cancel'].some((method) => typeof programExecutor?.[method] !== 'function')) throw new TypeError('programExecutor must support run, onObservation, and cancel');
		if (typeof sessionId !== 'string' || !/^[a-zA-Z0-9._-]{1,128}$/.test(sessionId)) throw new TypeError('sessionId must be 1..128 safe identifier characters');
		if (['ingest', 'candidates', 'load', 'flush', 'clear'].some((method) => typeof occupancy?.[method] !== 'function')) throw new TypeError('occupancy must support observed-memory lifecycle and candidate queries');
		this.#bridge = bridge;
		this.#registry = registry;
		this.#onFinish = onFinish;
		this.#trace = (event, fields) => {
			try {
				const completion = trace(event, fields);
				if (completion !== undefined) Promise.resolve(completion).catch(() => {});
			} catch { /* diagnostics cannot interrupt gameplay */ }
		};
		this.#decorateObservation = decorateObservation;
		this.#requestObservation = requestObservation;
		this.#inspectObservation = inspectObservation;
		this.#notebook = notebook;
		this.#executionSettings = executionSettings;
		this.#memoryOperation = memoryOperation;
		this.#programExecutor = programExecutor ?? new NativeProgramExecutor({ sessionId });
		if (typeof onProgramEvent !== 'function') throw new TypeError('onProgramEvent must be a function');
		this.#onProgramEvent = onProgramEvent;
		if (typeof planningLeadTime !== 'function') throw new TypeError('planningLeadTime must be a function');
		this.#planningLeadTime = planningLeadTime;
		this.#sessionId = sessionId.length <= 36 ? sessionId : createHash('sha256').update(sessionId).digest('hex').slice(0, 32);
		this.#occupancy = occupancy;
	}

	async initializeMemory(agentId) {
		if (this.#memoryReady.has(agentId)) return;
		let pending = this.#memoryLoads.get(agentId);
		if (pending === undefined) {
			pending = Promise.resolve().then(() => this.#occupancy.load(agentId)).then(() => {
				for (const observation of this.#pendingSpatial.get(agentId) ?? []) this.#occupancy.ingest(agentId, observation);
				this.#pendingSpatial.delete(agentId);
				this.#memoryReady.add(agentId);
			});
			this.#memoryLoads.set(agentId, pending);
		}
		try { await pending; }
		finally { if (this.#memoryLoads.get(agentId) === pending) this.#memoryLoads.delete(agentId); }
	}

	#rememberSpatial(agentId, observation) {
		if (this.#memoryReady.has(agentId)) { this.#occupancy.ingest(agentId, observation); return; }
		const queued = this.#pendingSpatial.get(agentId) ?? [];
		queued.push(observation);
		this.#pendingSpatial.set(agentId, queued.slice(-32));
		this.initializeMemory(agentId).catch((error) => this.#trace('native_spatial_memory_failed', { agentId, reasonCode: error?.code ?? 'MEMORY_LOAD_FAILED' }));
	}

	async #flushSpatial(agentId) {
		await this.initializeMemory(agentId);
		await this.#occupancy.flush(agentId);
	}

	updateObservation(record, observation, options = {}) {
		const stored = this.#storeObservation(record, observation, options);
		if (stored) this.#notePublishedSample(record);
		return stored;
	}

	/**
	 * The server publishes an attention observation after every action result, in
	 * the same tick and after physics. Only pushes delivered in wire order after the
	 * result count, so a requested sample taken before the result cannot satisfy it.
	 */
	#notePublishedSample(record) {
		const pending = this.#postResultSamples.get(record.agentId);
		if (pending === undefined || pending.goalRevision !== record.goalRevision || pending.published) return;
		pending.published = true;
		pending.wake?.();
	}

	#storeObservation(record, observation, { eventSequence = 0, conversation = undefined, force = false, attention = false, priority, trigger, changedFacts } = {}, reuseWorldFacts = false) {
		validateRecord(record);
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) throw new TypeError('eventSequence must be a nonnegative safe integer');
		if (force !== true && force !== false) throw new TypeError('force must be a boolean');
		const latest = this.#observations.get(record.agentId);
		if (!force && latest?.goalRevision === record.goalRevision && eventSequence <= latest.eventSequence) return false;
		const storedSequence = force && latest?.goalRevision === record.goalRevision
			? Math.max(eventSequence, latest.eventSequence)
			: eventSequence;
		const raw = mergeDeathObservation(observation ?? {}, this.#lastLive.get(record.agentId));
		// Keep one owned raw snapshot for both internal consumers. Public snapshot
		// methods still clone at their boundaries, so sharing here does not expose
		// mutable coordinator state while avoiding a duplicate deep copy per update.
		const storedObservation = structuredClone(raw);
		const program = this.#programRuns.get(record.agentId);
		const successor = program?.pendingSuccessor ?? program?.handoff;
		const queueWorld = successor?.world ?? program?.queueWorld;
		if (queueWorld != null && (record.goalRevision !== program.goalRevision
			|| !sameProgramWorld(storedObservation, queueWorld)
			|| (program.handoff && attention && priority === 'urgent'))) {
			program.queueAdmission = (program.queueAdmission ?? 0) + 1;
			program.pendingSuccessor = null;
			program.handoffInvalidated = 'SUCCESSOR_CONTEXT_CHANGED';
		}
		if (hasDurableObservationFacts(raw) && raw.ready !== false && raw.death == null && raw.status !== 'PLAYER_DEAD' && raw.player?.dead !== true) {
			this.#lastLive.set(record.agentId, {
				observation: storedObservation,
				eventSequence: storedSequence,
				goalRevision: record.goalRevision,
			});
		}
		if (!reuseWorldFacts) {
			this.#recovery.remember(record.agentId, record.goalRevision, raw);
			this.#rememberSpatial(record.agentId, storedObservation);
		}
		this.#observations.set(record.agentId, {
			goalRevision: record.goalRevision,
			eventSequence: storedSequence,
			goal: record.currentGoal ?? null,
			goalSpec: record.currentGoalSpec ?? null,
			observation: storedObservation,
			...(conversation === undefined ? {} : { conversation: structuredClone(conversation) }),
		});
		// The bridge also wakes the planner after a receipt or an explicit sample.
		// Those empty deltas must not interrupt the routine that requested them.
		// Preserve real fact changes, explicit triggers, and urgent notifications.
		const administrativeWake = Array.isArray(changedFacts) && changedFacts.length === 0
			&& priority !== 'urgent' && (trigger === undefined || trigger === 'attention');
		this.#programExecutor.onObservation(record, { observation: storedObservation, eventSequence: storedSequence, attention: attention && !administrativeWake, ...(priority === undefined ? {} : { priority }), ...(trigger === undefined ? {} : { trigger }) });
		return true;
	}

	/**
	 * Reuses durable facts when the actionable signature matches, but refreshes raw
	 * observations because clocks, effect durations, and cooldowns are excluded from it.
	 */
	refreshObservation(record, observation, { eventSequence = 0, conversation = undefined } = {}) {
		validateRecord(record);
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) throw new TypeError('eventSequence must be a nonnegative safe integer');
		const latest = this.#observations.get(record.agentId);
		if (latest === undefined || latest.goalRevision !== record.goalRevision || eventSequence <= latest.eventSequence) return false;
		const stored = this.#storeObservation(record, observation, {
			eventSequence,
			conversation: conversation ?? latest.conversation,
		}, true);
		if (stored) this.#notePublishedSample(record);
		return stored;
	}

	snapshotLive(agentId) {
		const live = this.#lastLive.get(agentId);
		return live === undefined ? null : structuredClone(live);
	}

	hasProgram(record, programId) {
		const run = this.#programRuns.get(record.agentId);
		return run?.goalRevision === record.goalRevision && (programId === undefined || run.programId === programId);
	}

	canPrepareProgram(record, programId, programVersion) {
		if (!this.hasProgram(record, programId)) return false;
		const status = this.#programStatus(record, programId);
		return status.state === 'RUNNING' && status.engineState === 'ACTIVE'
			&& status.programVersion === programVersion && status.decision == null;
	}

	decorateObservation(record, observation = {}) {
		validateRecord(record);
		const latest = this.#observations.get(record.agentId);
		const cached = latest?.goalRevision === record.goalRevision ? latest.observation : undefined;
		const live = this.#lastLive.get(record.agentId)?.observation;
		const source = resolveDecorateSource(observation, cached, live);
		// Cached and live snapshots share one owned raw object. Give callers an
		// isolated view when decoration falls back to either store (including death
		// merging), while the normal durable-observation path remains allocation-free.
		const ownedSource = source === cached || source === live || isSparseDeathObservation(observation)
			? structuredClone(source)
			: source;
		if (hasDurableObservationFacts(observation) && !isSparseDeathObservation(observation)) {
			this.#recovery.remember(record.agentId, record.goalRevision, observation);
		} else if (isSparseDeathObservation(observation) && hasDurableObservationFacts(source)) {
			this.#recovery.remember(record.agentId, record.goalRevision, source);
		}
		return composeTwoCallView(ownedSource, this.#recovery.snapshot(record.agentId, ownedSource), {
			occupancy: this.#occupancy,
			agentId: record.agentId,
			goal: record.currentGoal ?? record.currentGoalSpec?.originalRequest ?? null,
		});
	}

	hasCurrent(record) {
		const latest = this.#observations.get(record.agentId);
		return latest?.goalRevision === record.goalRevision;
	}

	async execute(request, record, { lifecycleGeneration = null } = {}) {
		validateRecord(record);
		validateRequest(request, record);
		if (lifecycleGeneration !== null && (!Number.isSafeInteger(lifecycleGeneration) || lifecycleGeneration < 0)) throw new TypeError('lifecycleGeneration must be a nonnegative safe integer or null');
		if (request.tool.kind === 'observe') return this.#observe(record);
		if (request.tool.kind === 'inspect') return this.#inspect(request.tool, record);
		if (request.tool.kind === 'capabilities') {
			if (request.tool.section === 'program') return { ...minecraftCapabilities({ section: 'program' }), ...await this.#executionMetadata(record) };
			return { ...minecraftCapabilities(), ...await this.#executionMetadata(record), ...await this.#memorySummary(record), runtime: { freshObservations: this.#requestObservation !== null, focusedInspection: this.#inspectObservation !== null, notebook: this.#notebook !== null || this.#memoryOperation !== null, asynchronousActions: true, cancellation: true, reactivePrograms: { available: true, background: true, engine: 'ArenaScript', modelAuthored: true, plannerCalls: false } } };
		}
		if (request.tool.kind === 'action_status') return this.#actionStatus(record, request.tool.actionId);
		if (request.tool.kind === 'program_status') return this.#programStatus(record, request.tool.programId);
		if (request.tool.kind === 'queue_program') return this.#queueProgram(request, record);
		if (request.tool.kind === 'cancel_queued_program') return this.#cancelQueuedProgram(record, request.tool);
		if (request.tool.kind === 'cancel_program') return this.#cancelProgram(record, request.tool);
		if (request.tool.kind === 'respond_program') {
			if (request.tool.goalRevision !== record.goalRevision) throw codedError('STALE_PROGRAM_DECISION', 'Decision belongs to an older goal');
			const run = this.#programRuns.get(record.agentId);
			await this.#programExecutor.respond(record, request.tool);
			if (run && ['replace', 'pause', 'finish'].includes(request.tool.directive)) run.pendingSuccessor = null;
			if (['pause', 'finish'].includes(request.tool.directive)) await run.result;
			return this.#programStatus(record, request.tool.programId);
		}
		if (request.tool.kind === 'cancel_action') return this.#cancelAction(record, request.tool);
		if (request.tool.kind === 'notebook' || request.tool.kind === 'query_memory') return this.#memory(request, record);
		if (this.#sequenceFinishReservations.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'A finishing native sequence owns the player until its fresh sample and goal verification settle');
		if (this.#sweeps.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'A camera sweep owns this player until sampling completes');
		if (this.#programRuns.has(record.agentId)) throw codedError('NATIVE_PROGRAM_IN_PROGRESS', 'A model-authored program owns this player; use programStatus and cancelProgram before issuing another body operation');
		if (request.tool.kind === 'run_program') return this.#runProgram(request, record);
		if (request.tool.kind === 'replace_action') {
			const epoch = this.#executionEpoch(record.agentId);
			const cancelled = await this.#cancelAction(record, request.tool);
			if (this.#executionEpoch(record.agentId) !== epoch + 1) throw codedError('STALE_NATIVE_TOOL', 'Lifecycle changed while cancelling the replaced action');
			if (cancelled.state !== 'CANCELLED') return { state: 'REPLACEMENT_NOT_STARTED', reasonCode: 'ACTION_FINISHED_BEFORE_CANCEL', previous: cancelled };
			return this.#executeAction(request, record, { ...request.tool, kind: 'action' });
		}
		if (request.tool.kind === 'start_action') return this.#executeAction(request, record, { ...request.tool, kind: 'action' }, null, false);
		if (request.tool.kind === 'finish') return this.#finish(request, record, lifecycleGeneration);
		if (request.tool.kind === 'explore_frontier') return this.#exploreFrontier(request, record);
		const tool = constrainGoalBoundNavigation(request.tool, record.currentGoalSpec);
		if (tool.kind === 'sequence') return this.#executeSequence({ ...request, tool }, record, this.#executionEpoch(record.agentId), lifecycleGeneration);
		if (tool.kind === 'lookAround') {
			const sweep = {};
			this.#sweeps.set(record.agentId, sweep);
			try { return await this.#executeLookAround(request, record, tool, this.#executionEpoch(record.agentId)); }
			finally { if (this.#sweeps.get(record.agentId) === sweep) this.#sweeps.delete(record.agentId); }
		}
		if (tool.kind !== 'action') throw codedError('INVALID_NATIVE_TOOL', 'Native tool did not normalize to an action');
		return this.#executeAction(request, record, tool);
	}

	async #observe(record, { includeMetadata = true, afterResult = null } = {}) {
		const epoch = this.#executionEpoch(record.agentId);
		await this.initializeMemory(record.agentId);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
		const previous = this.#observations.get(record.agentId);
		const afterEventSequence = previous?.goalRevision === record.goalRevision ? previous.eventSequence : 0;
		let freshness = { fresh: false, reasonCode: 'FRESH_OBSERVATION_UNAVAILABLE' };
		if (this.#requestObservation !== null) {
			const pending = afterResult === null ? undefined : this.#postResultSamples.get(record.agentId);
			if (pending?.result === afterResult && pending.goalRevision === record.goalRevision) {
				await this.#awaitPostResultSample(record, epoch, afterEventSequence, pending);
				freshness = { fresh: true, afterEventSequence: pending.eventSequence };
			} else {
				await this.#requestSample(record, epoch, afterEventSequence);
				freshness = { fresh: true, afterEventSequence };
			}
			this.#assertCurrent(record);
			if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
		}
		const latest = this.#observations.get(record.agentId);
		const facts = latest?.goalRevision === record.goalRevision
			? structuredClone(latest)
			: { eventSequence: 0, goal: record.currentGoal ?? null, goalSpec: record.currentGoalSpec ?? null, observation: {} };
		delete facts.goalRevision;
		facts.observation = this.#decorate(record, facts.observation ?? {});
		// Internal continuations need fresh world facts, not model-facing metadata.
		const metadata = includeMetadata ? { ...await this.#executionMetadata(record), ...await this.#memorySummary(record) } : {};
		this.#assertCurrent(record);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
		return { ...facts, ...metadata, freshness: { ...freshness, eventSequence: facts.eventSequence, observedAtEpochMs: facts.observation.observedAtEpochMs ?? null, ...(facts.observation.continuity?.rememberedSections === undefined ? {} : { rememberedSections: [...facts.observation.continuity.rememberedSections] }) } };
	}

	async #requestSample(record, epoch, afterEventSequence) {
		const sampled = await this.#requestObservation(record, { afterEventSequence });
		this.#assertCurrent(record);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
		if (!Number.isSafeInteger(sampled?.eventSequence) || sampled.eventSequence <= afterEventSequence || sampled.observation === null || typeof sampled.observation !== 'object' || Array.isArray(sampled.observation)) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Observation callback did not return a newer server sample');
		this.#storeObservation(record, sampled.observation, { eventSequence: sampled.eventSequence, conversation: sampled.conversation });
	}

	/**
	 * Uses the server's post-result publication when it is already here or arrives
	 * first. The explicit request remains the guarantee; answering it costs the
	 * server one more tick, which previously delayed every authored continuation.
	 */
	async #awaitPostResultSample(record, epoch, afterEventSequence, pending) {
		if (pending.published) return;
		const published = new Promise((resolve) => { pending.wake = resolve; });
		const requested = this.#requestSample(record, epoch, afterEventSequence);
		// A superseded request still stores its newer sample under the same lifecycle checks.
		requested.catch(() => {});
		try {
			try { await Promise.race([published, requested]); }
			catch (error) {
				// An observation already on the wire may still be queued behind the
				// action-result handler. Give that queued publication one event-loop
				// turn to arrive before treating an inspection error as authoritative.
				if (!pending.published) {
					await new Promise((resolve) => setImmediate(resolve));
				}
				if (!pending.published) throw error;
			}
		}
		finally { pending.wake = null; }
	}

	async #memorySummary(record) {
		const worldId = this.#worldId(record);
		if (worldId === null || typeof this.#notebook?.listUnresolved !== 'function') return {};
		const page = await this.#notebook.listUnresolved(record.agentId, { worldId, offset: 0, limit: 4 });
		this.#assertCurrent(record);
		return { unresolvedActions: { worldId, total: page.total, nextOffset: page.nextOffset, ...(page.evictedReceipts === undefined ? {} : { evictedReceipts: page.evictedReceipts }), entries: (page.entries ?? []).map(({ actionId, actionType, goalRevision, state, reasonCode }) => ({ actionId, actionType, goalRevision, state, reasonCode })), historical: true, query: { kind: 'unresolved', offset: 0, limit: 20 } } };
	}

	async #executionMetadata(record) {
		if (this.#executionSettings === null) return {};
		const executionSettings = await this.#executionSettings(record);
		this.#assertCurrent(record);
		return { executionSettings: structuredClone(executionSettings) };
	}

	#createProgramRun(request, record) {
		const epoch = this.#executionEpoch(record.agentId);
		let resolve, reject;
		const result = new Promise((done, fail) => { resolve = done; reject = fail; });
		let detach;
		const attentionResult = new Promise(done => { detach = done; });
		const run = { epoch, request, goalRevision: record.goalRevision, programId: `native-program-${this.#sessionId}-${++this.#sequence}`,
			state: 'PREPARING', settled: false, result, resolve, reject, detach, detached: request.tool.background === true, record, deadlineEpochMs: null };
		return { run, attentionResult };
	}

	async #runProgram(request, record) {
		const { run, attentionResult } = this.#createProgramRun(request, record);
		this.#programRuns.set(record.agentId, run);
		// Own the body before any asynchronous notebook lookup. The returned handle
		// has the same lifetime and cancellation rules as a foreground program.
		void this.#executeProgram(request, record, run).then(
			(outcome) => this.#settleProgram(record.agentId, run, outcome),
			(error) => this.#settleProgram(record.agentId, run, { state: 'FAILED', reasonCode: error?.code ?? 'PROGRAM_EXECUTION_FAILED', message: String(error?.message ?? error).slice(0, 512) }, error),
		);
		return request.tool.background === true ? this.#programStatus(record, run.programId) : Promise.race([run.result, attentionResult]);
	}

	#assertQueueAuthority(record, tool, run) {
		this.#assertCurrent(record);
		if (tool.goalRevision !== record.goalRevision || this.#programRuns.get(record.agentId) !== run
			|| run?.epoch !== this.#executionEpoch(record.agentId)
			|| !this.canPrepareProgram(record, tool.afterProgramId, tool.programVersion)) {
			throw codedError('STALE_PROGRAM', 'Successor needs the exact running program version with no pending decision');
		}
		const latest = this.#observations.get(record.agentId);
		const world = latest?.goalRevision === record.goalRevision ? liveProgramWorld(latest.observation) : null;
		if (world === null) throw codedError('LIVE_PROGRAM_CONTEXT_REQUIRED', 'Successor needs a live player and explicit world and dimension');
		return world;
	}

	async #queueProgram(request, record) {
		const tool = request.tool;
		const run = this.#programRuns.get(record.agentId);
		const world = this.#assertQueueAuthority(record, tool, run);
		const precondition = compileProgramPrecondition(tool.precondition);
		const admission = run.queueAdmission = (run.queueAdmission ?? 0) + 1;
		run.queueWorld = world;
		let source = tool.source;
		if (tool.noteKey !== undefined) {
			let offset = 0;
			for (;;) {
				const page = await this.#programMemory(request, record, { operation: 'query', arguments: { kind: 'notes', text: tool.noteKey, offset, limit: 64 } });
				this.#assertQueueAuthority(record, tool, run);
				const note = page.entries?.find(entry => entry.key === tool.noteKey);
				if (note) { source = note.text; break; }
				if (!Number.isSafeInteger(page.nextOffset) || page.nextOffset <= offset) throw codedError('PROGRAM_NOTE_NOT_FOUND', 'No saved program exists at that exact notebook key');
				offset = page.nextOffset;
			}
		}
		// Compile before replacing an existing queue, and freeze the resolved source.
		parseArenaScript(source);
		const currentWorld = this.#assertQueueAuthority(record, tool, run);
		if (run.queueAdmission !== admission || currentWorld.worldId !== world.worldId || currentWorld.dimension !== world.dimension) throw codedError('STALE_PROGRAM', 'Successor preparation was superseded or its world changed');
		const successor = Object.freeze({ queueId: `native-queue-${this.#sessionId}-${++this.#sequence}`,
			afterProgramId: tool.afterProgramId, goalRevision: tool.goalRevision, programVersion: tool.programVersion,
			world: Object.freeze(world), precondition, sourceOrigin: tool.noteKey === undefined ? 'source' : 'note',
			request: { ...request, tool: { kind: 'run_program', source, background: true, parameters: tool.parameters,
				maxActions: tool.maxActions, timeoutMs: tool.timeoutMs, observationIntervalMs: tool.observationIntervalMs,
				expectedDurationMs: tool.expectedDurationMs } } });
		run.pendingSuccessor = successor;
		this.#trace('native_program_successor_queued', { agentId: record.agentId, ...successorSummary(successor) });
		return { state: 'QUEUED', programId: run.programId, goalRevision: run.goalRevision, pendingSuccessor: successorSummary(successor) };
	}

	#cancelQueuedProgram(record, tool) {
		this.#assertCurrent(record);
		const run = this.#programRuns.get(record.agentId);
		const successor = run?.pendingSuccessor;
		if (tool.goalRevision !== record.goalRevision || successor?.queueId !== tool.queueId || run.programId !== tool.afterProgramId) throw codedError('STALE_PROGRAM_QUEUE', 'Cancellation does not match the exact pending successor');
		run.pendingSuccessor = null;
		run.queueAdmission = (run.queueAdmission ?? 0) + 1;
		return { state: 'CANCELLED', queueId: tool.queueId, afterProgramId: tool.afterProgramId, goalRevision: tool.goalRevision };
	}

	#settleProgram(agentId, run, outcome, error = null) {
		if (run.settled) return;
		run.settled = true;
		const result = { ...outcome, programId: run.programId, goalRevision: run.goalRevision };
		const successor = run.pendingSuccessor;
		run.pendingSuccessor = null;
		const handoff = successor != null && error === null && outcome.state === 'YIELDED'
			&& outcome.reasonCode === 'PROGRAM_EXHAUSTED' && outcome.decision == null
			&& outcome.actionsFailed === 0 && outcome.actionsSucceeded === outcome.actions
			&& outcome.programVersion === successor.programVersion && this.#executionEpoch(agentId) === run.epoch
			&& this.#programRuns.get(agentId) === run;
		if (successor != null && !handoff) result.discardedSuccessor = { ...successorSummary(successor), reasonCode: 'PREDECESSOR_NOT_SUCCESSFULLY_EXHAUSTED' };
		if (handoff) {
			// Reserve the body synchronously before any fresh-sample await or callback.
			const next = this.#createProgramRun(successor.request, run.record).run;
			next.handoff = successor;
			this.#programRuns.set(agentId, next);
			result.successorProgramId = next.programId;
			this.#programResults.set(agentId, result);
			void this.#startSuccessor(run, next, successor).then(
				outcome => this.#settleProgram(agentId, next, outcome),
				error => this.#settleProgram(agentId, next, { state: 'FAILED', reasonCode: error?.code ?? 'SUCCESSOR_EXECUTION_FAILED' }),
			);
		}
		if (this.#programRuns.get(agentId) === run) {
			this.#programRuns.delete(agentId);
			if (this.#executionEpoch(agentId) === run.epoch) this.#programResults.set(agentId, result);
		}
		if (error !== null && run.request.tool.background !== true) run.reject(error);
		else run.resolve(result);
		if (!handoff && run.detached && this.#executionEpoch(agentId) === run.epoch) this.#programEvent(run, { event: 'program_ended', result });
	}

	async #startSuccessor(previous, run, successor) {
		const record = run.record;
		try {
			const before = this.#observations.get(record.agentId)?.eventSequence ?? 0;
			const facts = await this.#observe(record, { includeMetadata: false });
			const latest = this.#observations.get(record.agentId);
			this.#assertCurrent(record);
			if (run.settled || this.#programRuns.get(record.agentId) !== run || run.epoch !== this.#executionEpoch(record.agentId)) throw codedError('STALE_PROGRAM', 'Successor lost execution authority');
			if (facts.freshness.fresh !== true || latest?.goalRevision !== record.goalRevision || latest.eventSequence <= before) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Successor needs a new authoritative sample after predecessor completion');
			if (run.handoffInvalidated || !sameProgramWorld(latest.observation, successor.world)) throw codedError('SUCCESSOR_CONTEXT_CHANGED', 'Successor world or live player changed');
			if ((latest.observation.continuity?.rememberedSections?.length ?? 0) > 0) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Successor prerequisites cannot use remembered sections');
			// Use the raw authoritative snapshot, never decorated remembered facts.
			if (!evaluateProgramPrecondition(successor.precondition, { observation: latest.observation, parameters: successor.request.tool.parameters })) throw codedError('SUCCESSOR_PRECONDITION_FALSE', 'The agent-authored successor prerequisite is not true');
			run.handoff = null;
			const execution = this.#executeProgram(successor.request, record, run);
			this.#programEvent(run, { event: 'program_handoff_started', predecessorProgramId: previous.programId, queueId: successor.queueId, status: this.#programStatus(record, run.programId) });
			return await execution;
		} catch (error) {
			if (run.handoff != null) {
				// Cancellation may have released this reservation while sampling. A late
				// callback must not wake the planner over a replacement program.
				if (run.settled || this.#programRuns.get(record.agentId) !== run || run.epoch !== this.#executionEpoch(record.agentId)) return { state: 'CANCELLED', reasonCode: 'STALE_PROGRAM' };
				run.detached = false; // One rejection event, rather than a duplicate terminal wake.
				const result = { state: 'YIELDED', reasonCode: error?.code ?? 'SUCCESSOR_PRECONDITION_FAILED', queueId: successor.queueId, predecessorProgramId: previous.programId };
				this.#settleProgram(record.agentId, run, result);
				this.#programEvent(run, { event: 'program_handoff_rejected', result });
				return result;
			}
			throw error;
		}
	}

	#programEvent(run, event) {
		const latest = this.#observations.get(run.record.agentId);
		if (this.#executionEpoch(run.record.agentId) !== run.epoch || latest?.goalRevision !== run.goalRevision) return;
		try {
			Promise.resolve(this.#onProgramEvent(run.record, { ...event, programId: run.programId, goalRevision: run.goalRevision,
				observation: structuredClone(latest.observation), eventSequence: latest.eventSequence })).catch(error => this.#trace('program_notification_failed', { code: error?.code ?? 'PROGRAM_NOTIFICATION_FAILED' }));
		} catch (error) { this.#trace('program_notification_failed', { code: error?.code ?? 'PROGRAM_NOTIFICATION_FAILED' }); }
	}

	#programStatus(record, programId) {
		this.#assertCurrent(record);
		const run = this.#programRuns.get(record.agentId);
		if (run?.goalRevision === record.goalRevision && (programId === undefined || programId === run.programId)) {
			return { programId: run.programId, goalRevision: run.goalRevision, state: run.state,
				deadlineEpochMs: run.deadlineEpochMs, maxActions: run.request.tool.maxActions ?? 64,
				action: this.#actionStatus(record), ...this.#programExecutor.status?.(record),
				...(run.pendingSuccessor == null ? {} : { pendingSuccessor: successorSummary(run.pendingSuccessor) }) };
		}
		const result = this.#programResults.get(record.agentId);
		if (result?.goalRevision === record.goalRevision && (programId === undefined || programId === result.programId)) return structuredClone(result);
		return { state: programId === undefined ? 'IDLE' : 'UNKNOWN_PROGRAM', goalRevision: record.goalRevision, ...(programId === undefined ? {} : { programId }) };
	}

	async #cancelProgram(record, tool) {
		this.#assertCurrent(record);
		const run = this.#programRuns.get(record.agentId);
		if (tool.goalRevision !== record.goalRevision || run?.goalRevision !== record.goalRevision || run.programId !== tool.programId) throw codedError('STALE_PROGRAM', 'Cancellation handle does not match the active program');
		const preparing = run.state === 'PREPARING';
		run.pendingSuccessor = null;
		run.state = 'CANCELLING';
		if (preparing) this.#settleProgram(record.agentId, run, { state: 'CANCELLED', reasonCode: 'MODEL_CANCELLED' });
		else await this.#programExecutor.cancel(record.agentId, 'MODEL_CANCELLED');
		return run.result;
	}

	async #executeProgram(request, record, run) {
		const { epoch } = run;
		let source = request.tool.source;
		if (request.tool.noteKey !== undefined) {
			let note;
			let offset = 0;
			do {
				const page = await this.#programMemory(request, record, { operation: 'query', arguments: { kind: 'notes', text: request.tool.noteKey, offset, limit: 64 } });
				this.#assertCurrent(record);
				if (epoch !== this.#executionEpoch(record.agentId) || this.#programRuns.get(record.agentId) !== run) throw codedError('NATIVE_PROGRAM_CANCELLED', 'Saved program lookup outlived its execution authority');
				note = page.entries?.find((entry) => entry.key === request.tool.noteKey);
				const nextOffset = page.nextOffset;
				if (note || nextOffset === null || !Number.isSafeInteger(nextOffset) || nextOffset <= offset) break;
				offset = nextOffset;
			} while (note === undefined);
			if (!note) throw codedError('PROGRAM_NOTE_NOT_FOUND', 'No saved program exists at that exact notebook key');
			source = note.text;
		}
		this.#assertCurrent(record);
		if (epoch !== this.#executionEpoch(record.agentId) || this.#programRuns.get(record.agentId) !== run) throw codedError('NATIVE_PROGRAM_CANCELLED', 'Saved program lookup outlived its execution authority');
		if (this.#actions.has(record.agentId) || this.#completions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'An action or completion verification already owns this player');
		const latest = this.#observations.get(record.agentId);
		if (latest?.goalRevision !== record.goalRevision) throw codedError('CURRENT_OBSERVATION_REQUIRED', 'A current player observation is required before running a program');
		run.state = 'RUNNING';
		run.deadlineEpochMs = Date.now() + (request.tool.timeoutMs ?? 30_000);
		// Missing measurements leave preparation disabled rather than guessing a delay.
		let planningLeadMs;
		try {
			const measured = this.#planningLeadTime(record);
			if (Number.isFinite(measured) && measured > 0) planningLeadMs = Math.ceil(measured);
		} catch { /* Telemetry must not prevent authorised work. */ }
		// The executor samples right after each action returns; that one sample may use
		// the server's post-result publication. Later interval samples still request.
		let lastActionResult = null;
		return await this.#programExecutor.run(record, { source, parameters: request.tool.parameters, expectedDurationMs: request.tool.expectedDurationMs, programId: run.programId, maxActions: request.tool.maxActions, timeoutMs: request.tool.timeoutMs, observationIntervalMs: request.tool.observationIntervalMs, planningLeadMs, provenance: nativeMemoryProvenance(request, record) }, {
			onPlanningDue: (_status, { planningLeadMs } = {}) => {
				if (this.#programRuns.get(record.agentId) !== run || this.#executionEpoch(record.agentId) !== run.epoch) return;
				const status = { ...this.#programStatus(record, run.programId), planningLeadMs };
				if (run.pendingSuccessor != null || !this.canPrepareProgram(record, run.programId, status.programVersion)) return;
				const wasDetached = run.detached;
				run.detached = true;
				run.detach({ ...status, advisory: 'program_planning_due' });
				this.#trace('native_program_planning_due', { agentId: record.agentId, goalRevision: run.goalRevision,
					programId: run.programId, programVersion: status.programVersion, planningLeadMs });
				if (wasDetached) this.#programEvent(run, { event: 'program_planning_due', status, priority: 'ordinary' });
			},
			onDecision: (_status, { priority } = {}) => {
				if (this.#programRuns.get(record.agentId) !== run || this.#executionEpoch(record.agentId) !== run.epoch) return;
				const status = this.#programStatus(record, run.programId);
				const wasDetached = run.detached;
				run.detached = true;
				run.detach(status);
				if (wasDetached) this.#programEvent(run, { event: 'program_attention', status, priority });
			},
			observation: structuredClone(latest.observation), eventSequence: latest.eventSequence,
			executeAction: async (command) => {
				if (this.#programRuns.get(record.agentId) !== run || this.#executionEpoch(record.agentId) !== run.epoch) throw codedError('NATIVE_PROGRAM_CANCELLED', 'Program no longer has execution authority');
				const result = await this.#executeAction(request, record, { kind: 'action', actionType: command.action.type, arguments: command.action.arguments }, null, true, command);
				lastActionResult = result;
				const receipt = this.#receipts.get(record.agentId)?.findLast((entry) => entry.engineActionId === command.actionId);
				return { ...result, ...(receipt === undefined ? {} : { actionId: receipt.actionId }) };
			},
			cancelAction: (commandId) => {
				const active = this.#actions.get(record.agentId);
				if (active?.engineActionId !== commandId) return { state: 'NO_MATCHING_ACTION' };
				if (active.cancelling) return active.result;
				return this.#cancelAction(record, { actionId: active.actionId, goalRevision: active.goalRevision }, { invalidateProgram: false });
			},
			inspect: async (query) => ({ state: 'SUCCEEDED', reasonCode: 'INSPECTED', ...await this.#inspect(normalizeMinecraftToolCall('inspect', query), record) }),
			refreshObservation: async () => {
				const afterResult = lastActionResult;
				lastActionResult = null;
				const facts = await this.#observe(record, { includeMetadata: false, afterResult });
				if (facts.freshness.fresh !== true) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Program continuation needs a new authoritative player observation');
				return { observation: facts.observation, eventSequence: facts.eventSequence };
			},
			memoryOperation: (operation) => this.#programMemory(request, record, operation),
		});
	}

	async #programMemory(request, record, operation) {
		if (this.#memoryOperation !== null) return this.#memoryOperation(record, operation);
		if (this.#notebook === null) throw codedError('MEMORY_UNAVAILABLE', 'Durable agent memory is unavailable');
		const worldId = this.#worldId(record);
		if (worldId === null) throw codedError('WORLD_ID_REQUIRED', 'A current observed world identity is required for durable memory');
		const tool = normalizeMinecraftToolCall(operation.operation === 'write' ? 'notebook' : 'queryMemory', operation.arguments);
		if (tool.kind === 'notebook') return { state: 'SUCCEEDED', reasonCode: 'NOTE_WRITTEN', note: await this.#notebook.writeNote(record.agentId, { worldId, key: tool.key, text: tool.text, goalRevision: record.goalRevision, provenance: operation.provenance ?? nativeMemoryProvenance(request, record) }) };
		return { state: 'SUCCEEDED', reasonCode: 'MEMORY_QUERIED', ...await this.#notebook.query(record.agentId, { worldId, kind: tool.memoryKind, offset: tool.offset, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) }) };
	}

	async #inspect(tool, record) {
		if (this.#inspectObservation === null) throw codedError('INSPECTION_UNAVAILABLE', 'Focused server inspection is unavailable; observe returns its explicit coverage limits');
		const epoch = this.#executionEpoch(record.agentId);
		const { kind: _kind, ...query } = tool;
		const result = JSON.parse(toolResultContent(await this.#inspectObservation(record, query)).contentItems[0].text);
		this.#assertCurrent(record);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Inspection request outlived its lifecycle');
		if (Number.isSafeInteger(result?.eventSequence) && result.eventSequence >= 0 && tool.section === 'entities') {
			const targets = this.#inspectedTargets.get(record.agentId) ?? new Map();
			for (const entry of result.entries ?? []) {
				const targetId = entry.uuid ?? entry.stableId;
				if (typeof targetId !== 'string') continue;
				targets.delete(targetId);
				targets.set(targetId, { goalRevision: record.goalRevision, eventSequence: result.eventSequence });
			}
			while (targets.size > 128) targets.delete(targets.keys().next().value);
			this.#inspectedTargets.set(record.agentId, targets);
		}
		return structuredClone(result);
	}

	#actionStatus(record, actionId) {
		const active = this.#actions.get(record.agentId);
		if (active?.goalRevision === record.goalRevision && (actionId === undefined || active.actionId === actionId)) {
			return { actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, state: !active.dispatched ? 'PREPARING' : active.cancelling ? 'CANCELLING' : active.cancellationUncertain ? 'CANCELLATION_UNCONFIRMED' : 'RUNNING', ...(active.progress === undefined ? {} : { progress: structuredClone(active.progress) }) };
		}
		if (actionId !== undefined) {
			const receipt = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === actionId && entry.goalRevision === record.goalRevision);
			return receipt === undefined ? { state: 'UNKNOWN_ACTION', actionId, goalRevision: record.goalRevision } : structuredClone(receipt);
		}
		const lastResult = this.#receipts.get(record.agentId)?.findLast((entry) => entry.goalRevision === record.goalRevision);
		return { state: 'IDLE', goalRevision: record.goalRevision, ...(lastResult === undefined ? {} : { lastResult: structuredClone(lastResult) }) };
	}

	#retainReceipt(record, active, result) {
		const existing = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === active.actionId);
		if (existing?.source === 'server_action_result' && result.source !== 'server_action_result') return existing;
		const receipt = { actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, ...(active.engineActionId === undefined ? {} : { engineActionId: active.engineActionId }), ...result };
		const receipts = (this.#receipts.get(record.agentId) ?? []).filter((entry) => entry.actionId !== active.actionId);
		receipts.push(receipt);
		this.#receipts.set(record.agentId, receipts.slice(-64));
		return receipt;
	}

	async #journal(method, agentId, active, details = {}) {
		if (active.worldId == null || typeof this.#notebook?.[method] !== 'function') return;
		return this.#notebook[method](agentId, { worldId: active.worldId, actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, ...details });
	}

	async #cancelAction(record, tool, { invalidateProgram = true } = {}) {
		const active = this.#actions.get(record.agentId);
		if (tool.goalRevision !== record.goalRevision || active?.goalRevision !== tool.goalRevision || active.actionId !== tool.actionId) throw codedError('STALE_ACTION', 'Cancellation handle does not match the active action');
		if (active.cancelling) throw codedError('CANCELLATION_IN_PROGRESS', 'The exact action is already being cancelled');
		active.cancelling = true;
		active.cancellationUncertain = false;
		if (invalidateProgram) {
			this.#executionEpochs.set(record.agentId, this.#executionEpoch(record.agentId) + 1);
			if (this.#programRuns.has(record.agentId)) Promise.resolve(this.#programExecutor.cancel(record.agentId, 'MODEL_CANCELLED')).catch((error) => this.#trace('native_program_cancel_failed', { agentId: record.agentId, reasonCode: error?.code ?? 'PROGRAM_CANCEL_FAILED' }));
		}
		if (!active.dispatched) {
			active.cancelledBeforeDispatch = true;
			this.#actions.delete(record.agentId);
			const result = { state: 'CANCELLED', reasonCode: 'CANCELLED_BEFORE_DISPATCH', executionStarted: false, physicalAttempted: false, source: 'coordinator_before_dispatch' };
			this.#retainReceipt(record, active, result);
			active.resolve(result);
			await this.#journal('recordUnknown', record.agentId, active, { reasonCode: 'CANCELLED_BEFORE_DISPATCH' });
			return result;
		}
		try {
			await this.#bridge.send('action_cancel', record.agentId, { goalRevision: active.goalRevision, actionId: active.actionId });
		} catch (error) {
			active.cancelling = false;
			throw error;
		}
		try {
			return await withDeadline(active.result, 10_000, 'CANCEL_ACK_TIMEOUT', 'Cancellation has no authoritative acknowledgement; the action may still be running');
		} catch (error) {
			active.cancelling = false;
			active.cancellationUncertain = error?.code === 'CANCEL_ACK_TIMEOUT';
			throw error;
		}
	}

	async #memory(request, record) {
		const tool = request.tool;
		if (this.#memoryOperation !== null) {
			const operation = tool.kind === 'notebook' ? 'write' : 'query';
			const args = operation === 'write' ? { key: tool.key, text: tool.text } : { kind: tool.memoryKind, offset: tool.offset ?? 0, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) };
			return this.#memoryOperation(record, { operation, arguments: args, provenance: nativeMemoryProvenance(request, record) });
		}
		if (this.#notebook === null) throw codedError('MEMORY_UNAVAILABLE', 'Durable agent memory is unavailable');
		const worldId = this.#worldId(record);
		if (worldId === null) throw codedError('WORLD_ID_REQUIRED', 'A current observed world identity is required for durable memory');
		if (tool.kind === 'notebook') return this.#notebook.writeNote(record.agentId, { worldId, key: tool.key, text: tool.text, goalRevision: record.goalRevision, provenance: nativeMemoryProvenance(request, record) });
		return this.#notebook.query(record.agentId, { worldId, kind: tool.memoryKind, offset: tool.offset ?? 0, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) });
	}

	#worldId(record) {
		const latest = this.#observations.get(record.agentId);
		const worldId = latest?.goalRevision === record.goalRevision ? observationWorldId(latest.observation) : null;
		return typeof worldId === 'string' && worldId.length > 0 ? worldId : null;
	}

	#assertCurrent(record) {
		const current = this.#registry?.get(record.agentId);
		if (this.#registry !== null && (current == null || current.goalRevision !== record.goalRevision)) throw codedError('STALE_NATIVE_TOOL', 'Agent goal changed while waiting for tool data');
	}

	#decorate(record, observation = {}) {
		if (this.#decorateObservation !== null) return this.#decorateObservation(record, observation);
		return this.decorateObservation(record, observation);
	}

	async #exploreFrontier(request, record) {
		const facts = await this.#observe(record, { includeMetadata: false });
		await this.#flushSpatial(record.agentId);
		return { ...this.#occupancy.candidates(record.agentId, facts.observation, request.tool.arguments ?? {}), freshness: facts.freshness };
	}

	async #executeLookAround(request, record, tool, executionEpoch) {
		const source = this.#observations.get(record.agentId)?.observation ?? {};
		const input = source.interaction?.input ?? {};
		const selectedSlot = Number.isSafeInteger(input.selectedSlot) && input.selectedSlot >= 0 && input.selectedSlot <= 8
			? input.selectedSlot : 0;
		const hand = input.hand === 'off_hand' ? 'off' : 'main';
		const results = [];
		const samples = [];
		for (let index = 0; index < tool.steps; index += 1) {
			if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('NATIVE_ACTION_CANCELLED', 'Camera sweep cancelled before its next step');
			const action = {
				kind: 'action',
				actionType: 'control',
				arguments: {
					forward: 0, strafe: 0, jump: false, sneak: false, sprint: false,
					attack: false, use: false,
					yaw: wrapDegrees(tool.centerYaw + ((index + 1) * 360) / tool.steps),
					pitch: tool.pitch, selectedSlot, hand, ticks: tool.ticksPerStep,
				},
			};
			const result = await this.#executeAction(request, record, action, index);
			results.push({ actionType: action.actionType, ...result });
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results, samples };
			const facts = await this.#observe(record, { includeMetadata: false, afterResult: result });
			if (facts.freshness.fresh !== true) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Camera sweep needs a fresh observation at each heading');
			samples.push(sweepSample(facts, action.arguments));
		}
		return { state: 'SUCCEEDED', completed: results.length, results, samples };
	}

	async #executeSequence(request, record, executionEpoch, lifecycleGeneration = null) {
		const finishToken = request.tool.finish === undefined ? null : {};
		if (finishToken !== null) {
			if (this.#sequenceFinishReservations.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'A finishing native sequence already owns this player');
			this.#sequenceFinishReservations.set(record.agentId, finishToken);
		}
		try {
			const startingEventSequence = this.#observations.get(record.agentId)?.goalRevision === record.goalRevision
				? this.#observations.get(record.agentId).eventSequence : 0;
			const results = [];
			let failedAt;
			let lastResult = null;
			for (let index = 0; index < request.tool.actions.length; index += 1) {
				if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('NATIVE_ACTION_CANCELLED', 'Native sequence cancelled before its next action');
				const action = request.tool.actions[index];
				lastResult = await this.#executeAction(request, record, { kind: 'action', ...action }, index, true, null, finishToken);
				results.push({ actionType: action.actionType, ...lastResult });
				if (lastResult.state !== 'SUCCEEDED') { failedAt = index; break; }
			}
			const outcome = { state: failedAt === undefined ? 'SUCCEEDED' : results.at(-1).state, completed: results.length, ...(failedAt === undefined ? {} : { failedAt }), results };
			const hasPostActionFacts = results.some(step => POST_ACTION_OBSERVATION_TYPES.has(step.actionType));
			if (finishToken === null) return hasPostActionFacts
				? this.#withPostAction(record, outcome, executionEpoch, lastResult) : outcome;

			if (failedAt !== undefined) {
				const withFacts = hasPostActionFacts ? await this.#withPostAction(record, outcome, executionEpoch, lastResult) : outcome;
				return { ...withFacts, finish: { state: 'SKIPPED', reasonCode: 'ACTION_FAILED' } };
			}

			const withFreshFacts = await this.#withPostAction(record, outcome, executionEpoch, lastResult);
			const facts = withFreshFacts.postAction;
			if (facts?.freshness?.fresh !== true || !Number.isSafeInteger(facts.eventSequence) || facts.eventSequence <= startingEventSequence) {
				return { ...withFreshFacts, finish: { state: 'SKIPPED', reasonCode: 'FRESH_OBSERVATION_REQUIRED' } };
			}
			try {
				this.#assertCurrent(record);
				if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('STALE_NATIVE_TOOL', 'Sequence lifecycle changed before goal verification');
			} catch (error) {
				return { ...withFreshFacts, finish: { state: 'SKIPPED', reasonCode: error?.code ?? 'STALE_NATIVE_TOOL' } };
			}
			const finishRequest = { ...request, tool: { kind: 'finish', summary: request.tool.finish.summary } };
			const finish = await this.#finish(finishRequest, record, lifecycleGeneration, finishToken);
			return { ...withFreshFacts, finish };
		} finally {
			if (finishToken !== null && this.#sequenceFinishReservations.get(record.agentId) === finishToken) this.#sequenceFinishReservations.delete(record.agentId);
		}
	}

	async #executeAction(request, record, tool, sequenceIndex = null, waitForCompletion = true, programCommand = null, sequenceFinishToken = null) {
		const finishReservation = this.#sequenceFinishReservations.get(record.agentId);
		if (finishReservation !== undefined && finishReservation !== sequenceFinishToken) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'A finishing native sequence owns the player');
		const executionEpoch = this.#executionEpoch(record.agentId);
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');

		const ordinal = ++this.#sequence;
		const identity = `${this.#sessionId}:${ordinal}:${safeSegment(record.agentId).slice(0, 32)}:${record.goalRevision}`;
		// The envelope and authorship must identify the same trace at the wire boundary.
		const traceId = validateTraceId(programCommand?.provenance.traceId ?? `native-${identity.replaceAll(':', '-')}`);
		const actionId = `native:${identity}`;
		const targetId = tool.arguments?.targetId ?? tool.arguments?.targetSelector;
		const latest = this.#observations.get(record.agentId);
		const currentTarget = latest?.goalRevision === record.goalRevision
			&& !latest.observation.continuity?.rememberedSections?.includes('entities')
			&& latest.observation.entities?.some((entry) => (entry.uuid ?? entry.stableId) === targetId) === true;
		const inspected = currentTarget ? undefined : this.#inspectedTargets.get(record.agentId)?.get(targetId);
		const eventSequence = inspected?.goalRevision === record.goalRevision ? inspected.eventSequence : latest?.eventSequence ?? 0;
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
		if (programCommand !== null) {
			const provenance = programCommand.provenance;
			payload.provenance = { provider: provenance.provider, model: provenance.model ?? provenance.modelIdentity, reasoningEffort: provenance.reasoningEffort, serviceTier: provenance.serviceTier, traceId: provenance.traceId, programId: provenance.programId, programVersion: provenance.version, sourceStepId: provenance.stepId, eventSequence: inspected?.goalRevision === record.goalRevision ? inspected.eventSequence : provenance.authorizingEventSequence ?? provenance.eventSequence, ...(provenance.watcherId == null ? {} : { watcherId: provenance.watcherId }) };
		}
		let resolveAction;
		let rejectAction;
		const result = new Promise((resolve, reject) => { resolveAction = resolve; rejectAction = reject; });
		result.catch(() => {});
		const active = { actionId, goalRevision: record.goalRevision, actionType: tool.actionType, arguments: structuredClone(tool.arguments), worldId: this.#worldId(record), result, resolve: resolveAction, reject: rejectAction, dispatched: false, ...(programCommand === null ? {} : { engineActionId: programCommand.actionId }) };
		this.#actions.set(record.agentId, active);
		const failPublication = async (error) => {
			if (this.#actions.get(record.agentId) === active) this.#actions.delete(record.agentId);
			const completed = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === actionId && entry.source === 'server_action_result');
			if (completed !== undefined) return;
			const reasonCode = active.dispatched ? 'DISPATCH_RESULT_UNKNOWN' : 'DISPATCH_NOT_SENT';
			this.#retainReceipt(record, active, { state: 'UNKNOWN', reasonCode, source: 'coordinator_uncertain', worldId: active.worldId });
			try { await this.#journal('recordUnknown', record.agentId, active, { reasonCode }); }
			catch (journalError) { this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId, reasonCode: journalError?.code ?? 'MEMORY_WRITE_FAILED' }); }
			Object.assign(error, { actionId, goalRevision: record.goalRevision });
			active.publicationError = error;
			rejectAction(error);
		};
		let publication = Promise.resolve();
		this.#trace('native_tool_dispatch_started', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		try {
			if (typeof this.#notebook?.recordDispatch === 'function' && active.worldId !== null) await this.#journal('recordDispatch', record.agentId, active, { arguments: active.arguments });
			if (this.#actions.get(record.agentId) !== active) return waitForCompletion ? result : this.#actionStatus(record, actionId);
			const current = this.#registry?.get(record.agentId);
			if (this.#registry !== null && (current === null || current === undefined || current.goalRevision !== record.goalRevision)) {
				throw codedError('STALE_PLAN', 'Native action became stale before bridge send');
			}
			active.dispatched = true;
			publication = Promise.resolve(this.#bridge.send('action_command', record.agentId, payload)).then(() => {
				this.#trace('native_tool_command_sent', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
			}, failPublication);
		} catch (error) {
			await failPublication(error);
		}
		if (!waitForCompletion) {
			await Promise.race([publication, result]);
			if (active.publicationError !== undefined) throw active.publicationError;
		}
		if (!waitForCompletion) return this.#actionStatus(record, actionId);
		const outcome = await result;
		// Sequences sample after their final step; the program executor samples before each authored continuation.
		return sequenceIndex === null && programCommand === null && POST_ACTION_OBSERVATION_TYPES.has(tool.actionType)
			? this.#withPostAction(record, outcome, executionEpoch, outcome) : outcome;
	}

	async #withPostAction(record, outcome, executionEpoch, afterResult = null) {
		if (this.#requestObservation === null) return outcome;
		try {
			this.#assertCurrent(record);
			if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('STALE_NATIVE_TOOL', 'Action lifecycle has ended');
			return { ...outcome, postAction: await this.#observe(record, { afterResult }) };
		} catch (error) {
			return { ...outcome, postAction: { freshness: { fresh: false }, reasonCode: error?.code ?? 'OBSERVATION_UNAVAILABLE',
				message: 'The action result above is authoritative. Fresh follow-up facts are unavailable; observe before choosing a dependent action.' } };
		}
	}

	onActionProgress(record, payload = {}) {
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		if (payload.goalRevision !== undefined && payload.goalRevision !== active.goalRevision) return false;
		const observation = payload.actionObservation === undefined ? undefined : structuredClone(payload.actionObservation);
		active.progress = { ...(payload.progress === undefined ? {} : { value: payload.progress }), ...(payload.elapsedMs === undefined ? {} : { elapsedMs: payload.elapsedMs }), ...(observation === undefined ? {} : { actionObservation: observation }) };
		this.#trace('native_tool_action_progress', {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			actionId: active.actionId,
			...(payload.progress === undefined ? {} : { progress: payload.progress }),
			...(payload.elapsedMs === undefined ? {} : { elapsedMs: payload.elapsedMs }),
			...(observation === undefined ? {} : { actionObservation: observation }),
		});
		return true;
	}

	onActionResult(record, payload = {}) {
		if (!TERMINAL_ACTION_STATES.has(payload.state)) return false;
		let active = this.#actions.get(record.agentId);
		if (active === undefined || active.actionId !== payload.actionId) {
			const unresolved = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === payload.actionId && entry.goalRevision === record.goalRevision && entry.state === 'UNKNOWN');
			active = unresolved === undefined ? undefined : { ...unresolved, resolve: () => {} };
		}
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		if (payload.goalRevision !== undefined && payload.goalRevision !== active.goalRevision) return false;
		if (this.#actions.get(record.agentId) === active) this.#actions.delete(record.agentId);
		const observation = payload.actionObservation;
		const recovery = hasAuthoritativeActionObservation(observation)
			? this.#recovery.snapshot(record.agentId, observation)
			: null;
		const state = String(payload.state ?? 'FAILED').slice(0, 64);
		const reasonCode = String(payload.reasonCode ?? '').slice(0, 128);
		const failureClass = classifyBodyFailure(reasonCode, state);
		const result = {
			state,
			reasonCode,
			...(reasonCode === 'ITEM_NOT_FOUND' && active.actionType === 'pick_up_item' ? {
				recoveryHint: 'The selected drop is no longer available. Inspect current inventory before retrying; nearby drops may be collected automatically. If more items are needed, observe and select a fresh UUID. Missing target alone does not prove collection.',
			} : {}),
			...(payload.message === undefined ? {} : { message: String(payload.message).slice(0, 2_048) }),
			...(payload.executionStarted === undefined ? {} : { executionStarted: payload.executionStarted === true }),
			...(payload.physicalAttempted === undefined ? {} : { physicalAttempted: payload.physicalAttempted === true }),
			...(payload.actionObservation === undefined ? {} : { actionObservation: structuredClone(payload.actionObservation) }),
			...(recovery === null ? {} : { recovery }),
			...(failureClass === null ? {} : { failureClass }),
		};
		this.#retainReceipt(record, active, { ...result, source: 'server_action_result' });
		if (this.#notebook !== null && active.worldId != null) {
			try {
				Promise.resolve(this.#notebook.recordReceipt(record.agentId, terminalReceipt(active, payload))).catch((error) => this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }));
			} catch (error) { this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		}
		this.#flushSpatial(record.agentId).catch((error) => this.#trace('native_spatial_memory_failed', { agentId: record.agentId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }));
		this.#trace('native_tool_action_completed', { agentId: record.agentId, goalRevision: record.goalRevision, actionId: active.actionId, ...result });
		const latest = this.#observations.get(record.agentId);
		this.#postResultSamples.set(record.agentId, { result, goalRevision: record.goalRevision, published: false, wake: null,
			eventSequence: latest?.goalRevision === record.goalRevision ? latest.eventSequence : 0 });
		active.resolve(result);
		return true;
	}

	async reconcileActionReceipt(agentId, payload = {}) {
		if (typeof payload.actionId !== 'string' || !payload.actionId.startsWith('native:') || !TERMINAL_ACTION_STATES.has(payload.state) || typeof this.#notebook?.findReceipt !== 'function') return false;
		const existing = await this.#notebook.findReceipt(agentId, { actionId: payload.actionId });
		if (existing === null || existing.goalRevision !== payload.goalRevision || payload.actionType !== undefined && existing.actionType !== payload.actionType) return false;
		await this.#notebook.recordReceipt(agentId, terminalReceipt(existing, payload));
		return true;
	}

	isActionResultStale(record, payload = {}) {
		const stale = this.#staleActions.get(record.agentId);
		return stale?.has(actionResultKey(payload.goalRevision, payload.actionId)) === true;
	}

	onCompletionResult(record, payload = {}) {
		const active = this.#completions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision) return false;
		if (payload.traceId !== active.traceId || payload.goalFingerprint !== active.goalFingerprint) return false;
		this.#completions.delete(record.agentId);
		const facts = structuredClone(Array.isArray(payload.facts) ? payload.facts : []);
		const unmet = facts.filter((fact) => fact.satisfied === false);
		const awaitingConfirmation = payload.verified !== true && payload.reasonCode === 'PREDICATE_FAILED'
			&& unmet.length > 0 && unmet.every((fact) => fact.type === 'operator_confirmed');
		active.resolve({
			state: payload.verified === true ? 'COMPLETED' : awaitingConfirmation ? 'AWAITING_OPERATOR_CONFIRMATION' : 'ACTIVE',
			verified: payload.verified === true,
			reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
			facts,
			...(awaitingConfirmation ? { message: 'The remaining condition requires operator confirmation. Report completion once, then end this turn. Do not repeat the physical work or finish checks while waiting.' } : {}),
		});
		return true;
	}

	async dispose(agentId, reason = 'disposed') {
		this.#executionEpochs.set(agentId, this.#executionEpoch(agentId) + 1);
		const program = this.#programRuns.get(agentId);
		if (program?.state === 'PREPARING') this.#settleProgram(agentId, program, { state: 'CANCELLED', reasonCode: 'NATIVE_PROGRAM_CANCELLED' }, codedError('NATIVE_PROGRAM_CANCELLED', 'Program preparation outlived its lifecycle'));
		this.#programRuns.delete(agentId);
		this.#programResults.delete(agentId);
		this.#sweeps.delete(agentId);
		Promise.resolve(this.#programExecutor.cancel(agentId, reason)).catch((error) => this.#trace('native_program_cancel_failed', { agentId, reasonCode: error?.code ?? 'PROGRAM_CANCEL_FAILED' }));
		// Physical authority is released before waiting on persistence below.
		if (FORGET_REASONS.test(String(reason))) {
			this.#recovery.forget(agentId);
			this.#lastLive.delete(agentId);
			this.#receipts.delete(agentId);
		}
		this.#observations.delete(agentId);
		this.#inspectedTargets.delete(agentId);
		this.#postResultSamples.delete(agentId);
		const active = this.#actions.get(agentId);
		const completion = this.#completions.get(agentId);
		if (completion !== undefined) {
			this.#completions.delete(agentId);
			completion.reject(codedError('NATIVE_COMPLETION_CANCELLED', `Native completion cancelled: ${String(reason).slice(0, 128)}`));
		}
		if (active !== undefined) {
			this.#actions.delete(agentId);
			this.#rememberStaleAction(agentId, active);
			active.reject(codedError('NATIVE_ACTION_CANCELLED', `Native action cancelled: ${String(reason).slice(0, 128)}`));
			if (active.dispatched && !active.cancelling) {
				try { await this.#bridge.send('action_cancel', agentId, { goalRevision: active.goalRevision, actionId: active.actionId }); }
				catch {}
			}
			try { await this.#journal('recordUnknown', agentId, active, { reasonCode: active.dispatched ? 'LIFECYCLE_ENDED_BEFORE_RESULT' : 'CANCELLED_BEFORE_DISPATCH' }); }
			catch (error) { this.#trace('native_receipt_persistence_failed', { agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		}
		try { await this.#flushSpatial(agentId); }
		catch (error) { this.#trace('native_spatial_memory_failed', { agentId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		if (FORGET_REASONS.test(String(reason))) {
			this.#occupancy.clear(agentId);
			this.#memoryReady.delete(agentId);
			this.#pendingSpatial.delete(agentId);
		}
		return active !== undefined || completion !== undefined;
	}

	#executionEpoch(agentId) { return this.#executionEpochs.get(agentId) ?? 0; }

	#rememberStaleAction(agentId, active) {
		let stale = this.#staleActions.get(agentId);
		if (stale === undefined) {
			stale = new Set();
			this.#staleActions.set(agentId, stale);
		}
		stale.add(actionResultKey(active.goalRevision, active.actionId));
		while (stale.size > 32) stale.delete(stale.values().next().value);
	}

	async disposeAll(reason = 'coordinator_stopped') {
		const agentIds = new Set([
			...this.#observations.keys(),
			...this.#actions.keys(),
			...this.#completions.keys(),
			...this.#programRuns.keys(),
			...this.#sweeps.keys(),
			...this.#lastLive.keys(),
		]);
		await Promise.allSettled([...agentIds].map((agentId) => this.dispose(agentId, reason)));
		if (FORGET_REASONS.test(String(reason))) this.#recovery.clear();
	}

	async #finish(request, record, lifecycleGeneration, sequenceFinishToken = null) {
		const finishReservation = this.#sequenceFinishReservations.get(record.agentId);
		if (finishReservation !== undefined && finishReservation !== sequenceFinishToken) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'A finishing native sequence owns the player');
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');
		const goalFingerprint = record.currentGoalSpec?.fingerprint;
		if (typeof goalFingerprint !== 'string' || !/^[0-9a-f]{64}$/.test(goalFingerprint)) {
			throw codedError('GOAL_SPEC_REQUIRED', 'Minecraft has not supplied an immutable goal specification');
		}
		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-complete-${this.#sessionId}-${ordinal}-${safeSegment(record.agentId).slice(0, 32)}-${record.goalRevision}`);
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
		};
		let resolveCompletion;
		let rejectCompletion;
		const completion = new Promise((resolve, reject) => { resolveCompletion = resolve; rejectCompletion = reject; });
		const pending = {
			goalRevision: record.goalRevision,
			traceId,
			goalFingerprint,
			resolve: resolveCompletion,
			reject: rejectCompletion,
		};
		this.#completions.set(record.agentId, pending);
		const failPublication = (error) => {
			if (this.#completions.get(record.agentId) === pending) this.#completions.delete(record.agentId);
			rejectCompletion(error);
		};
		try {
			Promise.resolve(this.#bridge.send('goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				goalFingerprint,
				traceId,
				profile,
			})).catch(failPublication);
		} catch (error) {
			failPublication(error);
		}
		const result = await completion;
		if (result.verified) await this.#onFinish({ record, request, result, lifecycleGeneration });
		return result;
	}
}

export function constrainGoalBoundNavigation(tool, goalSpec) {
	if (tool?.kind === 'sequence') {
		return { ...tool, actions: tool.actions.map((action) => constrainNavigationAction(action, goalSpec)) };
	}
	if (tool?.kind === 'action') return constrainNavigationAction(tool, goalSpec);
	return tool;
}

function constrainNavigationAction(action, goalSpec) {
	if (action?.actionType !== 'navigate_to') return action;
	const args = action.arguments ?? {};
	const radius = matchingPositionRadius(goalSpec?.predicate, args);
	if (radius === undefined) return action;
	if (radius < 0.01) {
		throw codedError('GOAL_TOLERANCE_UNREPRESENTABLE', 'The active position goal radius is below the navigation tool minimum');
	}
	if (args.tolerance <= radius) return action;
	return { ...action, arguments: { ...args, tolerance: radius } };
}

function matchingPositionRadius(predicate, args) {
	if (predicate?.type === 'position_within') {
		return args.x === predicate.x && args.y === predicate.y && args.z === predicate.z
			? predicate.radius
			: undefined;
	}
	let radius;
	for (const child of predicate?.predicates ?? []) {
		const childRadius = matchingPositionRadius(child, args);
		if (childRadius !== undefined) radius = radius === undefined ? childRadius : Math.min(radius, childRadius);
	}
	return radius;
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

function hasAuthoritativeActionObservation(observation) {
	return observation !== null && typeof observation === 'object'
		&& (observation.inventory !== undefined || observation.death != null);
}

function resolveDecorateSource(observation, cached, live) {
	if (isSparseDeathObservation(observation) && hasDurableObservationFacts(cached ?? {})) return cached;
	if (isSparseDeathObservation(observation) && hasDurableObservationFacts(live ?? {})) {
		return mergeDeathObservation(observation, { observation: live });
	}
	if (hasDurableObservationFacts(observation)) return observation;
	return cached ?? live ?? observation;
}

function isSparseDeathObservation(observation) {
	return observation?.death != null
		&& observation.inventory === undefined
		&& observation.player === undefined
		&& observation.world === undefined;
}

function mergeDeathObservation(observation, lastLive) {
	if (observation?.death == null || lastLive == null) return observation;
	const live = lastLive.observation ?? lastLive;
	if (live === null || typeof live !== 'object') return observation;
	return {
		...live,
		...observation,
		ready: false,
		status: 'PLAYER_DEAD',
		player: {
			...(typeof live.player === 'object' && live.player !== null ? live.player : {}),
			...(typeof observation.player === 'object' && observation.player !== null ? observation.player : {}),
			dead: true,
			health: 0,
			x: observation.death.x,
			y: observation.death.y,
			z: observation.death.z,
		},
		inventory: observation.inventory ?? { items: [] },
		lastLiveInventory: live.inventory ?? null,
		continuity: { sameGoal: true, phase: 'dead', rememberedSections: ['position', 'velocity', 'view', 'blocks', 'landmarks', 'entities', 'nearbyContainers', 'interaction', 'world'].filter((section) => live[section] !== undefined && observation[section] === undefined) },
		death: observation.death,
	};
}

function safeSegment(value) { return String(value).replace(/[^A-Za-z0-9._:-]/g, '_') || 'item'; }
function terminalReceipt(active, payload) {
	return {
		worldId: active.worldId, actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType,
		state: payload.state, reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
		...(typeof payload.executionStarted === 'boolean' ? { executionStarted: payload.executionStarted } : {}),
		...(typeof payload.physicalAttempted === 'boolean' ? { physicalAttempted: payload.physicalAttempted } : {}),
		...(payload.actionObservation === undefined ? {} : { actionObservation: structuredClone(payload.actionObservation) }),
		...(Number.isSafeInteger(payload.actionObservation?.worldTick) ? { tick: payload.actionObservation.worldTick } : {}),
	};
}
function nativeMemoryProvenance(request, record) { return { provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort, serviceTier: record.serviceTier ?? 'priority', goalRevision: record.goalRevision, turnId: request.turnId, callId: request.callId }; }
function wrapDegrees(value) {
	const wrapped = ((value + 180) % 360 + 360) % 360 - 180;
	return wrapped === -180 ? 180 : wrapped;
}
function actionResultKey(goalRevision, actionId) { return `${goalRevision}:${String(actionId ?? '')}`; }
function liveProgramWorld(observation) {
	if (observation?.ready !== true || observation.death != null || observation.status === 'PLAYER_DEAD' || observation.player?.dead === true) return null;
	const worldId = observation.world?.worldId ?? observation.worldId;
	const dimension = observation.world?.dimension;
	if (typeof worldId !== 'string' || worldId.length === 0 || typeof dimension !== 'string' || dimension.length === 0) return null;
	return { worldId, dimension };
}

function sameProgramWorld(observation, expected) {
	const world = liveProgramWorld(observation);
	return world !== null && world.worldId === expected.worldId && world.dimension === expected.dimension;
}

function successorSummary(successor) {
	return { queueId: successor.queueId, afterProgramId: successor.afterProgramId, goalRevision: successor.goalRevision,
		programVersion: successor.programVersion, state: 'QUEUED', maxActions: successor.request.tool.maxActions ?? 64,
		timeoutMs: successor.request.tool.timeoutMs ?? 30_000, sourceOrigin: successor.sourceOrigin };
}

function codedError(code, message) { return Object.assign(new Error(message), { code }); }

async function withDeadline(promise, timeoutMs, code, message) {
	let timer;
	try {
		return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(codedError(code, message)), timeoutMs); timer.unref?.(); })]);
	} finally {
		clearTimeout(timer);
	}
}

// Historical sightings preserve each heading without presenting earlier targets as current facts.
function sweepSample(facts, { yaw, pitch }) {
	const observation = facts.observation;
	const sample = { historical: true, yaw, pitch, eventSequence: facts.eventSequence,
		observedAtEpochMs: observation.observedAtEpochMs ?? null, dimension: observation.world?.dimension ?? null,
		entities: [], blocks: [], items: [], landmarks: [], omitted: {} };
	const sections = ['entities', 'blocks', 'items', 'landmarks'];
	const sources = sections.map((section) => observation.continuity?.rememberedSections?.includes(section) ? []
		: (section === 'landmarks' ? observation.world?.landmarks ?? observation.landmarks : observation[section]) ?? []);
	const representativeFields = {
		entities: ['entityType', 'type', 'kind', 'stableId', 'uuid'],
		blocks: ['blockId', 'type', 'kind', 'stableId', 'uuid'],
		items: ['itemId', 'type', 'kind', 'stableId', 'uuid'],
		landmarks: ['blockId', 'itemId', 'type', 'kind', 'stableId', 'uuid'],
	};
	// Semantic representatives are interleaved across sections so one dense view cannot crowd out another;
	// every admission is checked with the final omission counts because those counts consume the same byte budget.
	// Keep distinct observed types across every section before repeated terrain or mobs
	// consume the scan budget. These remain historical sightings, not chosen targets.
	const candidatesByPass = [sections.map(() => []), sections.map(() => [])];
	for (const [sectionIndex, section] of sections.entries()) {
		const representatives = [];
		const duplicates = [];
		const seen = new Set();
		for (const [entryIndex, entry] of sources[sectionIndex].entries()) {
			if (!entry) continue;
			const compact = Object.fromEntries(['uuid', 'stableId', 'type', 'entityType', 'itemId', 'blockId', 'kind', 'position', 'x', 'y', 'z', 'distance', 'parentId', 'partName']
				.filter((field) => entry[field] !== undefined).map((field) => [field, entry[field]]));
			const representativeField = representativeFields[section].find((field) => compact[field] !== undefined);
			const identity = representativeField === undefined ? `entry:${entryIndex}` : `${representativeField}:${JSON.stringify(compact[representativeField])}`;
			if (seen.has(identity)) duplicates.push(compact);
			else { seen.add(identity); representatives.push(compact); }
		}
		candidatesByPass[0][sectionIndex] = representatives;
		candidatesByPass[1][sectionIndex] = duplicates;
	}
	const omitted = () => Object.fromEntries(sections.map((section, index) => [section, sources[index].length - sample[section].length]));
	const fitsBudget = () => {
		sample.omitted = omitted();
		return Buffer.byteLength(JSON.stringify(sample), 'utf8') <= 1400;
	};
	for (const candidates of candidatesByPass) {
		for (let index = 0; index < Math.max(...candidates.map((entries) => entries.length)); index += 1) {
			for (const [sectionIndex, section] of sections.entries()) {
				const entry = candidates[sectionIndex][index];
				if (entry === undefined) continue;
				sample[section].push(entry);
				if (!fitsBudget()) sample[section].pop();
			}
		}
	}
	sample.omitted = omitted();
	return sample;
}
