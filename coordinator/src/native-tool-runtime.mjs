import { validateTraceId } from './control-latency-registry.mjs';
import { ExplorationOccupancy, extractDimension, extractPosition } from './explore-frontier.mjs';
import { hasDurableObservationFacts, RecoveryProgressStore } from './recovery-progress-wrap.mjs';
import { classifyBodyFailure, composeTwoCallView } from './two-call-llm-wrap.mjs';

const FORGET_REASONS = /agent_removed|server_replaced|coordinator_stopped/;
const FRONTIER_BLOCKING_REASONS = new Set([
	'PATH_BLOCKED',
	'DESTINATION_BLOCKED',
	'NO_PATH',
	'NO_STANDABLE_PATH',
	'PATH_LIMIT_REACHED',
]);

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
	#occupancy = new ExplorationOccupancy();
	#recovery = new RecoveryProgressStore();
	#decorateObservation;
	#resolveFrontier;
	#executionEpochs = new Map();
	#sequence = 0;

	constructor({
		bridge,
		registry = null,
		onFinish = async () => ({ state: 'FINISH_REQUESTED' }),
		trace = () => {},
		decorateObservation = null,
		resolveFrontier = null,
	} = {}) {
		if (typeof bridge?.send !== 'function') throw new TypeError('bridge.send must be a function');
		if (registry !== null && typeof registry?.get !== 'function') throw new TypeError('registry.get must be a function');
		if (typeof onFinish !== 'function') throw new TypeError('onFinish must be a function');
		if (typeof trace !== 'function') throw new TypeError('trace must be a function');
		if (decorateObservation !== null && typeof decorateObservation !== 'function') throw new TypeError('decorateObservation must be a function');
		if (resolveFrontier !== null && typeof resolveFrontier !== 'function') throw new TypeError('resolveFrontier must be a function');
		this.#bridge = bridge;
		this.#registry = registry;
		this.#onFinish = onFinish;
		this.#trace = trace;
		this.#decorateObservation = decorateObservation;
		this.#resolveFrontier = resolveFrontier;
	}

	updateObservation(record, observation, options = {}) {
		return this.#storeObservation(record, observation, options);
	}

	#storeObservation(record, observation, { eventSequence = 0, conversation = undefined, force = false } = {}, reuseWorldFacts = false) {
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
		if (hasDurableObservationFacts(raw) && raw.death == null && raw.status !== 'PLAYER_DEAD' && raw.player?.dead !== true) {
			this.#lastLive.set(record.agentId, {
				observation: storedObservation,
				eventSequence: storedSequence,
				goalRevision: record.goalRevision,
			});
		}
		if (!reuseWorldFacts) {
			this.#recovery.remember(record.agentId, record.goalRevision, raw);
			this.#occupancy.ingest(record.agentId, raw);
		}
		this.#observations.set(record.agentId, {
			goalRevision: record.goalRevision,
			eventSequence: storedSequence,
			goal: record.currentGoal ?? null,
			goalSpec: record.currentGoalSpec ?? null,
			observation: storedObservation,
			...(conversation === undefined ? {} : { conversation: structuredClone(conversation) }),
		});
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
		return this.#storeObservation(record, observation, {
			eventSequence,
			conversation: conversation ?? latest.conversation,
		}, true);
	}

	snapshotLive(agentId) {
		const live = this.#lastLive.get(agentId);
		return live === undefined ? null : structuredClone(live);
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
		if (request.tool.kind === 'observe') {
			const latest = this.#observations.get(record.agentId);
			if (latest?.goalRevision !== record.goalRevision) return {
				eventSequence: 0,
				goal: record.currentGoal ?? null,
				goalSpec: record.currentGoalSpec ?? null,
				observation: this.#decorate(record, {}),
			};
			const { goalRevision: _goalRevision, ...facts } = structuredClone(latest);
			facts.observation = this.#decorate(record, facts.observation ?? {});
			return facts;
		}
		if (request.tool.kind === 'finish') return this.#finish(request, record, lifecycleGeneration);
		if (request.tool.kind === 'explore_frontier') return this.#exploreFrontier(request, record);
		const tool = constrainGoalBoundNavigation(request.tool, record.currentGoalSpec);
		if (tool.kind === 'sequence') return this.#executeSequence({ ...request, tool }, record, this.#executionEpoch(record.agentId));
		if (tool.kind === 'lookAround') return this.#executeLookAround(request, record, tool, this.#executionEpoch(record.agentId));
		if (tool.kind !== 'action') throw codedError('INVALID_NATIVE_TOOL', 'Native tool did not normalize to an action');
		return this.#executeAction(request, record, tool);
	}

	#decorate(record, observation = {}) {
		if (this.#decorateObservation !== null) return this.#decorateObservation(record, observation);
		return this.decorateObservation(record, observation);
	}

	#ingestFrontierArrival(record, actionObservation, destination, dimension) {
		const arrival = frontierArrivalObservation(actionObservation, destination, dimension);
		this.#occupancy.ingest(record.agentId, arrival);
		const latest = this.#observations.get(record.agentId);
		if (latest === undefined || latest.goalRevision !== record.goalRevision) return;
		const position = extractPosition(arrival);
		if (position === null) return;
		const current = latest.observation ?? {};
		latest.observation = {
			...current,
			position,
			player: { ...(typeof current.player === 'object' && current.player !== null ? current.player : {}), ...position },
			world: { ...(typeof current.world === 'object' && current.world !== null ? current.world : {}), dimension: extractDimension(arrival) },
		};
	}

	async #exploreFrontier(request, record) {
		const latest = this.#observations.get(record.agentId);
		const args = request.tool.arguments ?? {};
		if (this.#resolveFrontier !== null) {
			const destination = this.#resolveFrontier(record, args);
			if (destination !== null && destination !== undefined && Number.isFinite(destination.x) && Number.isFinite(destination.z)) {
				const move = constrainGoalBoundNavigation({
					kind: 'action',
					actionType: 'navigate_to',
					arguments: {
						x: destination.x,
						y: Number.isFinite(destination.y) ? destination.y : 64,
						z: destination.z,
						tolerance: Number.isFinite(destination.tolerance) ? destination.tolerance : 1,
						sprint: destination.sprint !== false,
						timeoutMs: args.timeoutMs ?? destination.timeoutMs ?? 15_000,
					},
				}, record.currentGoalSpec);
				return this.#executeAction(request, record, move);
			}
		}
		if (latest?.goalRevision !== record.goalRevision) {
			return frontierResult('FAILED', 'NO_OBSERVATION', 'No frontier resolver or current observation is available.', {
				kind: 'no_observation', seek: args.seek ?? 'any', radius: args.radius ?? 24,
				dimension: 'minecraft:overworld', destination: null, cue: null, reason: 'NO_OBSERVATION',
			});
		}
		const observation = latest.observation ?? {};
		const selection = this.#occupancy.select(record.agentId, observation, {
			seek: args.seek ?? 'any',
			radius: args.radius ?? 24,
			heading: args.heading,
		});
		this.#trace('native_explore_frontier_selected', {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			kind: selection.kind,
			seek: selection.seek,
			reason: selection.reason,
		});
		if (selection.kind === 'no_observation') {
			return frontierResult('FAILED', 'NO_OBSERVATION', 'Latest observation has no player position for frontier selection.', selection);
		}
		if (selection.kind === 'no_frontier') {
			return frontierResult('FAILED', 'NO_FRONTIER', selection.reason, selection);
		}
		if (selection.kind === 'cue_in_view') {
			return frontierResult('SUCCEEDED', 'CUE_IN_VIEW', selection.reason, selection);
		}
		const destination = selection.destination;
		const move = constrainGoalBoundNavigation({
			kind: 'action',
			actionType: 'navigate_to',
			arguments: {
				x: destination.x,
				y: destination.y,
				z: destination.z,
				tolerance: 1,
				sprint: true,
				timeoutMs: args.timeoutMs ?? 15_000,
			},
		}, record.currentGoalSpec);
		const result = await this.#executeAction(request, record, move);
		if (result.state !== 'SUCCEEDED' && FRONTIER_BLOCKING_REASONS.has(result.reasonCode)) {
			this.#occupancy.markBlocked(record.agentId, selection.dimension, destination.x, destination.z);
		} else if (result.state === 'SUCCEEDED') {
			this.#ingestFrontierArrival(record, result.actionObservation, destination, selection.dimension);
		}
		return { ...result, frontier: compactFrontier(selection) };
	}

	async #executeLookAround(request, record, tool, executionEpoch) {
		const source = this.#observations.get(record.agentId)?.observation ?? {};
		const input = source.interaction?.input ?? {};
		const selectedSlot = Number.isSafeInteger(input.selectedSlot) && input.selectedSlot >= 0 && input.selectedSlot <= 8
			? input.selectedSlot : 0;
		const hand = input.hand === 'off_hand' ? 'off' : 'main';
		const results = [];
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
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results };
		}
		return { state: 'SUCCEEDED', completed: results.length, results };
	}

	async #executeSequence(request, record, executionEpoch) {
		const results = [];
		for (let index = 0; index < request.tool.actions.length; index += 1) {
			if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('NATIVE_ACTION_CANCELLED', 'Native sequence cancelled before its next action');
			const action = request.tool.actions[index];
			const result = await this.#executeAction(request, record, { kind: 'action', ...action }, index);
			results.push({ actionType: action.actionType, ...result });
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results };
		}
		return { state: 'SUCCEEDED', completed: results.length, results };
	}

	async #executeAction(request, record, tool, sequenceIndex = null) {
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');

		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-${safeSegment(record.agentId)}-${record.goalRevision}-${ordinal}`.slice(0, 128));
		const actionId = `native:${safeSegment(record.agentId)}:${record.goalRevision}:${ordinal}`.slice(0, 128);
		const eventSequence = this.#observations.get(record.agentId)?.eventSequence ?? 0;
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
		let resolveAction;
		let rejectAction;
		const result = new Promise((resolve, reject) => { resolveAction = resolve; rejectAction = reject; });
		this.#actions.set(record.agentId, { actionId, goalRevision: record.goalRevision, resolve: resolveAction, reject: rejectAction });
		this.#trace('native_tool_dispatch_started', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		try {
			const current = this.#registry?.get(record.agentId);
			if (this.#registry !== null && (current === null || current === undefined || current.goalRevision !== record.goalRevision)) {
				this.#actions.delete(record.agentId);
				rejectAction(codedError('STALE_PLAN', 'Native action became stale before bridge send'));
				return result;
			}
			await this.#bridge.send('action_command', record.agentId, payload);
			this.#trace('native_tool_command_sent', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		} catch (error) {
			this.#actions.delete(record.agentId);
			rejectAction(error);
		}
		return result;
	}

	onActionProgress(record, payload = {}) {
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		const observation = payload.actionObservation === undefined ? undefined : structuredClone(payload.actionObservation);
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
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		this.#actions.delete(record.agentId);
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
			...(payload.message === undefined ? {} : { message: String(payload.message).slice(0, 2_048) }),
			...(payload.executionStarted === undefined ? {} : { executionStarted: payload.executionStarted === true }),
			...(payload.physicalAttempted === undefined ? {} : { physicalAttempted: payload.physicalAttempted === true }),
			...(payload.actionObservation === undefined ? {} : { actionObservation: structuredClone(payload.actionObservation) }),
			...(recovery === null ? {} : { recovery }),
			...(failureClass === null ? {} : { failureClass }),
		};
		this.#trace('native_tool_action_completed', { agentId: record.agentId, goalRevision: record.goalRevision, actionId: active.actionId, ...result });
		active.resolve(result);
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
		active.resolve({
			state: payload.verified === true ? 'COMPLETED' : 'ACTIVE',
			verified: payload.verified === true,
			reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
			facts: structuredClone(Array.isArray(payload.facts) ? payload.facts : []),
		});
		return true;
	}

	async dispose(agentId, reason = 'disposed') {
		this.#executionEpochs.set(agentId, this.#executionEpoch(agentId) + 1);
		if (FORGET_REASONS.test(String(reason))) {
			this.#recovery.forget(agentId);
			this.#occupancy.clear(agentId);
			this.#lastLive.delete(agentId);
		}
		this.#observations.delete(agentId);
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
			try {
				await this.#bridge.send('action_cancel', agentId, { goalRevision: active.goalRevision, actionId: active.actionId });
			} catch {}
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
			...this.#lastLive.keys(),
		]);
		await Promise.allSettled([...agentIds].map((agentId) => this.dispose(agentId, reason)));
		if (FORGET_REASONS.test(String(reason))) this.#recovery.clear();
	}

	async #finish(request, record, lifecycleGeneration) {
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');
		const goalFingerprint = record.currentGoalSpec?.fingerprint;
		if (typeof goalFingerprint !== 'string' || !/^[0-9a-f]{64}$/.test(goalFingerprint)) {
			throw codedError('GOAL_SPEC_REQUIRED', 'Minecraft has not supplied an immutable goal specification');
		}
		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-complete-${safeSegment(record.agentId)}-${record.goalRevision}-${ordinal}`.slice(0, 128));
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
		};
		let resolveCompletion;
		let rejectCompletion;
		const completion = new Promise((resolve, reject) => { resolveCompletion = resolve; rejectCompletion = reject; });
		this.#completions.set(record.agentId, {
			goalRevision: record.goalRevision,
			traceId,
			goalFingerprint,
			resolve: resolveCompletion,
			reject: rejectCompletion,
		});
		try {
			await this.#bridge.send('goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				goalFingerprint,
				traceId,
				profile,
			});
		} catch (error) {
			this.#completions.delete(record.agentId);
			rejectCompletion(error);
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
		continuity: { sameGoal: true, phase: 'dead' },
		death: observation.death,
	};
}

function frontierResult(state, reasonCode, message, selection) {
	const failureClass = classifyBodyFailure(reasonCode, state);
	return {
		state,
		reasonCode,
		message,
		frontier: compactFrontier(selection),
		...(failureClass === null ? {} : { failureClass }),
	};
}

function compactFrontier(selection) {
	return {
		kind: selection.kind,
		seek: selection.seek,
		radius: selection.radius,
		dimension: selection.dimension,
		destination: selection.destination,
		cue: selection.cue,
		knownCells: selection.knownCells ?? 0,
		frontierCount: selection.frontierCount ?? 0,
		reason: selection.reason ?? '',
	};
}

function frontierArrivalObservation(actionObservation, destination, dimension) {
	const source = actionObservation !== null && typeof actionObservation === 'object' ? actionObservation : {};
	const position = extractPosition(source)
		?? (Number.isFinite(destination?.x) && Number.isFinite(destination?.z)
			? { x: destination.x, y: Number.isFinite(destination.y) ? destination.y : 64, z: destination.z }
			: null);
	if (position === null) {
		return { world: { dimension } };
	}
	return {
		...source,
		position,
		player: { ...(typeof source.player === 'object' && source.player !== null ? source.player : {}), ...position },
		world: { ...(typeof source.world === 'object' && source.world !== null ? source.world : {}), dimension },
	};
}

function safeSegment(value) { return String(value).replace(/[^A-Za-z0-9._:-]/g, '_') || 'item'; }
function wrapDegrees(value) {
	const wrapped = ((value + 180) % 360 + 360) % 360 - 180;
	return wrapped === -180 ? 180 : wrapped;
}
function actionResultKey(goalRevision, actionId) { return `${goalRevision}:${String(actionId ?? '')}`; }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
