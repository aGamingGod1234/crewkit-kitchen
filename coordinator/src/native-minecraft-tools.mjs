import {
	ACTION_FIELDS,
	OPTIONAL_ACTION_FIELDS,
	CONTROL_BRANCH_CONDITIONS,
	MAX_CHAT_LENGTH,
	MAX_DURATION_MS,
	MAX_IDENTIFIER_LENGTH,
	MIN_DURATION_MS,
} from './constants.mjs';
import { MAX_ACTION_ARGUMENT_BYTES, validateAction } from './schema.mjs';
import { ARENA_SCRIPT_API_REFERENCE } from './prompts.mjs';
import { validateProgramParameters, MAX_PROGRAM_PARAMETER_BYTES } from './program-parameters.mjs';
import { validateTaskEntry } from './task-memory-store.mjs';
import { TASK_PLAN_SCHEMA, validateTaskPlan } from './live-task-view.mjs';
import { MODEL_FACT_INSTRUCTIONS } from './model-fact-encoding.mjs';
import { CONTROL_REFERENCE_TOPICS, minecraftControlReference } from './minecraft-control-reference.mjs';
import { STRATEGY_REFERENCE_TOPICS, minecraftStrategyReference } from './minecraft-strategy-reference.mjs';

export const MAX_TOOL_RESULT_BYTES = 16_384;
const COORDINATE_LIMIT = 30_000_000;
const MAX_SEQUENCE_ACTIONS = 8;
const MAX_LOOK_AROUND_STEPS = 8;
const MAX_LOOK_AROUND_TICKS = 20;
const MAX_PROGRAM_SOURCE_BYTES = 65_536;
const MAX_PROGRAM_PRECONDITION_BYTES = 4_096;
const MAX_SEQUENCE_FINISH_BYTES = 4_096;
const INVENTORY_FACT_FIELDS = ['selectedSlot', 'selectedItem', 'selectedItemId', 'selectedItemCount', 'tagCounts'];
const COMPACT_ROW_FIELDS = ['uuid', 'stableId', 'type', 'typeId', 'name', 'slot', 'position', 'x', 'y', 'z', 'distance', 'distanceSquared', 'blockId', 'itemId', 'count', 'damage', 'maxDamage', 'usesLeft', 'tags', 'fingerprint', 'hotbar', 'displayName', 'maxStackSize', 'health', 'maxHealth', 'hostile', 'alive', 'targetingAgent', 'swelling', 'fuse', 'perceivedBy', 'potentialRisk', 'risk', 'expectedHitDamage', 'withinInteractionRange', 'capabilities', 'velocity', 'bounds', 'pickable', 'parentId', 'partName', 'state', 'bearing', 'elevation', 'omittedFields'];
const NATIVE_ACTION_TYPES = Object.freeze(Object.keys(ACTION_FIELDS));
const POST_ACTION_VIEW_TOOLS = new Set(['moveTo', 'mine', 'act', 'sequence']);
const OBSERVATION_VIEW_PROPERTIES = {
	view: { type: 'string', enum: ['full', 'changes'] },
	afterObservationId: { type: 'string', minLength: 1, maxLength: 128 },
};
export const INSPECTION_SECTIONS = Object.freeze(['observation', 'inventory', 'menu', 'entities', 'blocks', 'landmarks', 'nearby_containers', 'item', 'block', 'events', 'recipes', 'mechanics', 'survey']);
export const SURVEY_SECTIONS = Object.freeze(['structures', 'poi', 'biomes', 'built', 'blocks', 'caves', 'veins']);
const MAX_SURVEY_ROWS = 8;
const SURVEY_INCLUDE_PATTERN = /^blocks:[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const SURVEY_PROPERTIES = {
	include: { type: 'array', maxItems: 8, items: { type: 'string', maxLength: 263, pattern: '^(structures|poi|biomes|built|blocks(:[a-z0-9_.-]+:[a-z0-9_./-]+)?|caves|veins)$' } },
	exclude: { type: 'array', maxItems: 7, items: { type: 'string', enum: [...SURVEY_SECTIONS] } },
	limit: integerSchema(1, MAX_SURVEY_ROWS),
};

export function minecraftCapabilities({ section = 'all', topic, offset } = {}) {
	if (section === 'program') return { version: 1, section: 'program', engine: 'ArenaScript', reference: ARENA_SCRIPT_API_REFERENCE };
	if (section === 'control') return minecraftControlReference({ topic, offset });
	if (section === 'strategy') return minecraftStrategyReference({ topic });
	return {
		version: 1,
		actions: Object.entries(ACTION_FIELDS).map(([actionType, fields]) => ({ actionType, fields: [...fields], requiredFields: fields.filter((field) => !(OPTIONAL_ACTION_FIELDS[actionType] ?? []).includes(field)), optionalFields: [...(OPTIONAL_ACTION_FIELDS[actionType] ?? [])] })),
		controlConditions: { ...CONTROL_BRANCH_CONDITIONS },
		inspectionSections: [...INSPECTION_SECTIONS],
		programReference: { tool: 'capabilities', arguments: { section: 'program' } },
		controlReference: { tool: 'capabilities', arguments: { section: 'control' } },
		strategyReference: { tool: 'capabilities', arguments: { section: 'strategy' } },
		limits: { sequenceActions: MAX_SEQUENCE_ACTIONS, inspectionPage: 32, resultBytes: MAX_TOOL_RESULT_BYTES, actionArgumentBytes: MAX_ACTION_ARGUMENT_BYTES, programSourceBytes: MAX_PROGRAM_SOURCE_BYTES, programParameterBytes: MAX_PROGRAM_PARAMETER_BYTES, programPreconditionBytes: MAX_PROGRAM_PRECONDITION_BYTES, pendingProgramSuccessors: 1, programActions: 256, programTimeoutMs: 120_000 },
	};
}

export const NATIVE_AGENT_INSTRUCTIONS = `You control one live Minecraft player and choose every action.

Read taskPlan once; revise it with stable IDs.

Death does not change the active goal. Batch known independent reads and reuse fresh result facts before observe/inspect. Never poll; results arrive as events. Use sequence for safe linear chains; ArenaScript for conditional work; repeat work in one looping background:true program (repeatUntil a count), not per 2-3 blocks: exhaustion costs a decision. queueProgram needs a fresh precondition; only natural exhaustion starts it; queue a known next program right after starting it. Use startAction to reason while one chosen action runs. End a turn by stopping, no closing text.

Survival is part of the goal. Threat attention precedes damage: act fight_target/flee_from, not moveTo; creepers flee. Eat when safe below 70% health; no food under threat: flee. Guard mining with threat and heardLava watches (after:"reconsider"). Danger-paused programs let you act; respond later.

Omitted or unobserved facts are unknown. queryMemory pages nextOffset; reuse exact noteKey with fresh prerequisites/current targets and program.parameters(). noteKey executes the entire note as source. exploreFrontier lists moveTo candidates. Claim effects from evidence. No task: takeTask a player's request, end turn; else say. Plain text is invisible; speech playback is asynchronous.`;

export const MINECRAFT_DYNAMIC_TOOLS = Object.freeze([
	tool('taskMemory', 'Remember places, routes, task progress and lessons across deaths. Death sites, outbound trails and workstations are recorded automatically. Entries are your own historical notes, never current world truth: reobserve before recovery. shared:true shares an entry with agents in this world and dimension. Query pages route waypoints and earlier deaths. Routes use from/to place keys and 2..64 waypoints. You choose recovery or rebuilding.', objectSchema({
		operation: { type: 'string', enum: ['remember', 'query'] },
		entry: objectSchema({ kind: { type: 'string', enum: ['place', 'route', 'progress', 'lesson'] }, key: { type: 'string', minLength: 1, maxLength: 128 }, label: { type: 'string', minLength: 1, maxLength: 128 }, summary: { type: 'string', minLength: 1, maxLength: 1024 },
			position: objectSchema({ x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT), y: numberSchema(-2048, 2048), z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT) }, ['x', 'y', 'z']),
			from: { type: 'string', minLength: 1, maxLength: 128 }, to: { type: 'string', minLength: 1, maxLength: 128 },
			waypoints: { type: 'array', minItems: 2, maxItems: 64, items: objectSchema({ x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT), y: numberSchema(-2048, 2048), z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT) }, ['x', 'y', 'z']) },
			status: { type: 'string', enum: ['active', 'retired'] }, shared: { type: 'boolean' } }, ['kind', 'key', 'label', 'summary']),
		query: objectSchema({ kind: { type: 'string', enum: ['all', 'place', 'route', 'progress', 'lesson', 'deaths', 'assets', 'trail'] }, dimension: { type: 'string', minLength: 1, maxLength: 128 }, text: { type: 'string', maxLength: 256 }, offset: integerSchema(0, Number.MAX_SAFE_INTEGER), limit: integerSchema(1, 64) }),
	}, ['operation'])),
	tool('observe', `${MODEL_FACT_INSTRUCTIONS} Request fresh player facts; skip it when a fresh postAction sample has them. Read coverage/freshness; an unavailable freshness barrier returns explicitly stale cached facts.`, objectSchema({ view: { type: 'string', enum: ['full', 'changes'] }, afterObservationId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('capabilities', 'List action fields, query sections, limits and runtime support; no gameplay. Read each topic once. Section program: read before writing ArenaScript. Section control lists control-reference topics: read tool:<name> or action:<actionType> before unfamiliar calls, follow nextOffset, topic all is the complete reference. Section strategy holds optional progression knowledge by topic (ores, structures, water, Nether, End, beating the game) that taskPlan names.', objectSchema({ section: { type: 'string', enum: ['all', 'program', 'control', 'strategy'] }, topic: { type: 'string', minLength: 1, maxLength: 128 }, offset: integerSchema(0, Number.MAX_SAFE_INTEGER) })),
	tool('inspect', 'Request a focused page of player-accessible facts that no fresh result already holds. Item queries need a slot; block queries need visible x/y/z coordinates. For several visible-world reads use survey. Read coverage and freshness.', objectSchema({
		section: { type: 'string', enum: INSPECTION_SECTIONS }, offset: integerSchema(0, 4_096), limit: integerSchema(1, 32),
		slot: integerSchema(0, 255), x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT), y: integerSchema(-2_048, 2_048), z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		afterSequence: integerSchema(0, Number.MAX_SAFE_INTEGER),
		recipeId: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
		entityType: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
		outputItemId: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
	}, ['section'])),
	tool('actionStatus', 'Read the active action or a retained terminal receipt; changes nothing, returns at once. Never poll: end your turn while work runs; its result, program decisions and danger arrive as events.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('cancelAction', 'Cancel the exact active handle and wait for its authoritative terminal result. A stale handle cannot cancel another action.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER) }, ['actionId', 'goalRevision'])),
	tool('replaceAction', 'Cancel the exact active handle, wait for acknowledgement, then execute your replacement. No replacement runs after uncertain cancellation.', objectSchema({
		actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER),
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' },
	}, ['actionId', 'goalRevision', 'actionType', 'arguments'])),
	tool('startAction', 'Start one action you have already chosen and return its handle immediately so you can reason while it runs. Its factual result arrives as an event after you end your turn; cancel the exact handle to stop it. This does not authorize a dependent action without fresh facts.', objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments'])),
	tool('notebook', 'Save or replace one note of up to 2048 characters in this agent and world. Prefer a stable exact key for reusable routines. Keep executable ArenaScript valid; put prerequisites, current targets, outcomes, and failure conditions in comments or a separate note, and only record outcomes supported by evidence. Notes are hypotheses or plans, never authoritative game evidence.', objectSchema({ key: { type: 'string', minLength: 1, maxLength: 128 }, text: { type: 'string', minLength: 1, maxLength: 2048 } }, ['key', 'text'])),
	tool('queryMemory', 'Read this agent and world\'s saved notes and action receipts, including unresolved dispatches. Start with notes to find reusable routines and receipts to check historical outcomes; continue every page with nextOffset. Historical receipts do not establish current world state.', objectSchema({ kind: { type: 'string', enum: ['all', 'notes', 'receipts', 'unresolved'] }, text: { type: 'string', minLength: 1, maxLength: 256 }, offset: integerSchema(0, Number.MAX_SAFE_INTEGER), limit: integerSchema(1, 64) })),
	tool('runProgram', 'Run bounded ArenaScript that you author. Supply source or an exact notebook noteKey (noteKey executes the entire note text as ArenaScript). Optional parameters: a pure JSON object up to 4096 UTF-8 bytes read by program.parameters(). Reuse noteKey when fresh prerequisites and targets match. After fresh facts, use a safe background:true routine while you reason. A bulk task (many blocks, items or trips) is one program looping with repeatUntil, not one program per block. One recent-p95 advisory may arrive near timeout or your optional expectedDurationMs (at most timeoutMs, never extends it) so you can author the next intention; it never chooses or dispatches actions or waives fresh-fact requirements. Optional observationIntervalMs requests fresh samples. background:true returns a handle; otherwise wait for the result. One program owns the body until it ends or cancellation settles. Each run expires within timeoutMs.', objectSchema({ source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES }, noteKey: { type: 'string', minLength: 1, maxLength: 128 }, parameters: { type: 'object', description: 'Pure JSON data, at most 4096 UTF-8 bytes, depth 16 and 256 total entries.' }, background: { type: 'boolean' }, observationIntervalMs: integerSchema(100, 5000), maxActions: integerSchema(1, 256), timeoutMs: integerSchema(1, 120_000), expectedDurationMs: integerSchema(1, 120_000) })),
	tool('queueProgram', 'Queue exactly one authored successor for the exact running afterProgramId, goalRevision and programVersion; do it right after starting the background program when the next routine is already known. Supply source XOR noteKey and a required side-effect-free ArenaScript precondition expression, evaluated against an authoritative fresh sample at handoff. Optional parameters are pure JSON read by program.parameters(). It replaces any pending successor for that predecessor; the runtime chooses no gameplay. Start requires successful natural PROGRAM_EXHAUSTED, no pending decision, valid lifecycle, the same world/dimension and a precondition exactly true. Failure, death, cancellation, manual finish and deadlines discard it. Its timeout starts at handoff and never extends the predecessor; optional expectedDurationMs must fit it. programStatus shows the pending successor; cancelQueuedProgram withdraws only that exact queue.', objectSchema({ afterProgramId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER), programVersion: integerSchema(1, Number.MAX_SAFE_INTEGER), source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES }, noteKey: { type: 'string', minLength: 1, maxLength: 128 }, precondition: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_PRECONDITION_BYTES }, parameters: { type: 'object', description: 'Pure JSON data, at most 4096 UTF-8 bytes, depth 16 and 256 total entries.' }, observationIntervalMs: integerSchema(100, 5000), maxActions: integerSchema(1, 256), timeoutMs: integerSchema(1, 120_000), expectedDurationMs: integerSchema(1, 120_000) }, ['afterProgramId', 'goalRevision', 'programVersion', 'precondition'])),
	tool('cancelQueuedProgram', 'Withdraw the exact pending successor using afterProgramId, goalRevision and queueId. Leaves the predecessor running; a stale queue handle cannot cancel its replacement.', objectSchema({ afterProgramId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER), queueId: { type: 'string', minLength: 1, maxLength: 128 } }, ['afterProgramId', 'goalRevision', 'queueId'])),
	tool('programStatus', 'Read the running program, pending decision, pendingSuccessor summary, or latest terminal result; changes nothing, returns at once. Use it for a background handle or exact attention. Never poll: end your turn; program end and decisions arrive as events. Background completion does not mean the goal is complete.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('respondProgram', 'Answer pending decision: continue preserves routine; replace with new source when fresh facts invalidate prerequisites or targets; pause stops it; finish needs factual goal verification. Use eventSequence for refreshed facts.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER), decisionId: { type: 'string', minLength: 1, maxLength: 256 }, eventSequence: integerSchema(0, Number.MAX_SAFE_INTEGER), directive: { type: 'string', enum: ['continue', 'pause', 'replace', 'finish'] }, source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES } }, ['programId', 'goalRevision', 'decisionId', 'directive'])),
	tool('cancelProgram', 'Cancel the exact program and wait for its result. New body actions remain blocked while cancellation is unconfirmed. Handles are scoped to this agent and goal revision.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER) }, ['programId', 'goalRevision'])),
	tool('lookAround', 'Turn through 2 to 8 camera steps, sampling fresh facts at each heading. Returns bounded historical sightings with timestamps and omitted counts; reacquire targets before acting. Optional survey (survey tool fields) surveys each heading and merges the rows.', objectSchema({
		centerYaw: numberSchema(-180, 180),
		pitch: numberSchema(-90, 90),
		steps: integerSchema(2, MAX_LOOK_AROUND_STEPS),
		ticksPerStep: integerSchema(1, MAX_LOOK_AROUND_TICKS),
		survey: { type: 'object' },
	}, ['centerYaw', 'pitch', 'steps', 'ticksPerStep'])),
	tool('survey', 'What you see now from your eye in your view cone (turn or lookAround to see more): structures (named within 64 blocks, else built blocks seen and size), poi (portals, bells, beds, workstations), biomes (nearest visible ground), built (unnatural blocks outside generated structures: possibly player-built, a hint), blocks (lava pools, spawners, chests; blocks:<id> searches within 24), caves, veins. Big things count to 256 blocks, single blocks within 24. Threats always included. Passive updates show only new sightings. One call batches several of these reads. Prefer seen caves and structures to strip mining.', objectSchema(SURVEY_PROPERTIES)),
	tool('control', 'Hold one complete player input frame for 1 to 200 server ticks. Use for precise movement, jumps, attacks, item use, view, and hotbar control. The view turns to yaw/pitch at player speed first (about 6 ticks for 180 degrees) with movement keys held and attack/use waiting for the aim; ticks count from arrival. instantLook:true writes the look at once. In water jump is a held swim-up key (rise, stay afloat, climb out at a shore); without it the body sinks. Sprint while underWater swims along the view pitch.', objectSchema({
		forward: numberSchema(-1, 1),
		strafe: numberSchema(-1, 1),
		jump: { type: 'boolean' },
		sneak: { type: 'boolean' },
		sprint: { type: 'boolean' },
		attack: { type: 'boolean' },
		use: { type: 'boolean' },
		yaw: numberSchema(-180, 180),
		pitch: numberSchema(-90, 90),
		selectedSlot: integerSchema(0, 8),
		hand: { type: 'string', enum: ['main', 'off'] },
		ticks: integerSchema(1, 200),
		instantLook: { type: 'boolean' },
	}, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks'])),
	tool('moveTo', 'Navigate to an endpoint you choose through bounded loaded waypoints. When the route is observed safe, give the far target: the planner routes about 30 blocks per leg and continues on its own; unknown ground still needs observation and a new route decision.', objectSchema({
		x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: numberSchema(-2_048, 2_048),
		z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		tolerance: numberSchema(0.01, 16),
		sprint: { type: 'boolean' },
		timeoutMs: integerSchema(MIN_DURATION_MS, MAX_DURATION_MS),
	}, ['x', 'y', 'z'])),
	tool('exploreFrontier', 'List factual observed or unknown adjacent-space candidates. This tool never chooses or executes a destination; choose explicitly with moveTo.', objectSchema({
		kind: { type: 'string', enum: ['all', 'observed_block', 'unknown_cell'] },
		radius: integerSchema(8, 32),
		limit: integerSchema(1, 64),
		blockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
	})),
	tool('mine', 'Mine one chosen in-range block with its exact current blockId. It turns to the block\'s center itself at player speed; use look_at first only for a specific aim point (autoAim:true just adds that look_at). It needs a clear line of sight. Prefer whole veins and worn tools first. Drops landing within about a block are collected while you keep mining; pick up stragglers once at the end. Failure chooses no alternate target.', objectSchema({
		x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: integerSchema(-2_048, 2_048),
		z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		expectedBlockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
		timeoutMs: integerSchema(MIN_DURATION_MS, MAX_DURATION_MS),
		autoAim: { type: 'boolean' },
	}, ['x', 'y', 'z', 'expectedBlockId'])),
	tool('say', 'Send public chat, a private message, or nearby proximity speech. Message limit: 256 Unicode code points. Direct recipients must be observed player UUIDs.', objectSchema({
		message: { type: 'string', minLength: 1, maxLength: MAX_CHAT_LENGTH },
		audience: { type: 'string', enum: ['public', 'direct', 'proximity'] },
		recipientId: { type: 'string', minLength: 36, maxLength: 36, pattern: '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' },
	}, ['message'])),
	tool('wait', 'Pause briefly and wait for the body result.', objectSchema({
		durationMs: integerSchema(MIN_DURATION_MS, MAX_DURATION_MS),
	}, ['durationMs'])),
	tool('act', 'Execute one supported advanced player action. Supply exactly the required fields. For interact_block omit optional hitX/hitY/hitZ to use the actual block shape. Before pick_up_item check current inventory and use a freshly observed target UUID; drops within a block may already be collected. fight_target takes optional targetPolicy: named (default), highest_risk or nearest_attacker (live switching among attacking mobs with hysteresis, never creepers); follow-through and policies skip players unless includePlayers:true. Threats sort by risk; replaceAction with a new fight_target retargets keeping weapon and swing timing.', objectSchema({
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES },
		arguments: { type: 'object' },
	}, ['actionType', 'arguments'])),
	tool('sequence', 'Prefer sequence for safe 2+ action chains: 2 to 8 exact model-authored actions in order, stopping on the first factual failure. Optional finish:{summary} requests goal verification after every action succeeds and a fresh final sample exists; failure never runs finish. Movement, mining or pickup chains return one fresh postAction sample after the last attempted step: use it instead of observe/inspect. Use separate calls when a later step needs facts an earlier result will change. Repeating one step many times belongs in a looping program.', objectSchema({
		actions: {
			type: 'array', minItems: 2, maxItems: MAX_SEQUENCE_ACTIONS,
			items: objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments']),
		},
		finish: objectSchema({ summary: { type: 'string', minLength: 1, maxLength: 512 } }, ['summary']),
	}, ['actions'])),
	tool('taskPlan', 'Read or replace your advisory dependency plan. This does not change the immutable goal or issue game actions. Inventory/world completion is reconciled with fresh game evidence; milestones/manual completion is agent reported. For replace include the complete plan, stable IDs and dependency references. Read once at task start; replace at meaningful revisions.', objectSchema({ operation: { type: 'string', enum: ['read', 'replace'] }, plan: TASK_PLAN_SCHEMA }, ['operation'])),
	tool('takeTask', 'Only when you have no active task: adopt what a player asked you to do in this conversation. request defaults to their latest words; rewrite it as the concrete task if clearer. Use resume:true when they want a paused task continued. Pass requesterId only if several players messaged you. Minecraft starts it, validates it first (PENDING), or refuses; on success end this turn and your task turn starts with every tool. On refusal tell the player why with say.', objectSchema({
		request: { type: 'string', minLength: 1, maxLength: 512 },
		resume: { type: 'boolean' },
		requesterId: { type: 'string', minLength: 36, maxLength: 36, pattern: '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' },
	})),
	tool('finish', 'Ask Minecraft to verify the immutable active goal. Read unmet facts on failure. If AWAITING_OPERATOR_CONFIRMATION, report once with say and end this turn until new input; do not repeat the work or verification. Waiting never blocks new player requests: act on them at once, then finish again.', objectSchema({
		summary: { type: 'string', minLength: 1, maxLength: 512 },
	}, ['summary'])),
]);

export function normalizeMinecraftToolCall(name, value) {
	if (POST_ACTION_VIEW_TOOLS.has(name)) {
		const { view, afterObservationId, ...action } = requireObject(value);
		// Presentation options belong to the tool call, never the body command.
		const { kind: _kind, ...presentation } = normalizeMinecraftToolCall('observe', {
			...(view === undefined ? {} : { view }),
			...(afterObservationId === undefined ? {} : { afterObservationId }),
		});
		return { ...normalizeMinecraftToolArguments(name, action), ...presentation };
	}
	return normalizeMinecraftToolArguments(name, value);
}

function normalizeMinecraftToolArguments(name, value) {
	const args = requireObject(value);
	switch (name) {
		case 'taskPlan':
			requireExactKeys(args, ['operation', 'plan']);
			if (args.operation === 'read' && args.plan === undefined) return { kind: 'task_plan', operation: 'read' };
			if (args.operation !== 'replace') invalid('taskPlan requires read or replace with plan');
			try { return { kind: 'task_plan', operation: 'replace', plan: validateTaskPlan(args.plan) }; }
			catch (error) { invalid(error.message); }
		case 'observe':
			requireExactKeys(args, ['view', 'afterObservationId']);
			if (args.view !== undefined && !['full', 'changes'].includes(args.view)) invalid('observation view is not supported');
			if (args.afterObservationId !== undefined && args.view !== 'changes') invalid('afterObservationId requires changes view');
			return { kind: 'observe', ...(args.view === undefined ? {} : { view: args.view }), ...(args.afterObservationId === undefined ? {} : { afterObservationId: boundedText(args.afterObservationId, 'afterObservationId', 128) }) };
		case 'capabilities':
			requireExactKeys(args, ['section', 'topic', 'offset']);
			if (args.section !== undefined && !['all', 'program', 'control', 'strategy'].includes(args.section)) invalid('capability section is not supported');
			if (args.section === 'strategy') {
				if (args.offset !== undefined) invalid('strategy topics are returned whole');
				if (args.topic !== undefined && !STRATEGY_REFERENCE_TOPICS.includes(args.topic)) invalid('strategy topic is not supported');
			} else {
				if ((args.topic !== undefined || args.offset !== undefined) && args.section !== 'control') invalid('topic and offset require the control reference section');
				if (args.topic !== undefined && !CONTROL_REFERENCE_TOPICS.includes(args.topic)) invalid('control reference topic is not supported');
			}
			if (args.offset !== undefined && args.topic === undefined) invalid('control reference offset requires a topic');
			return { kind: 'capabilities', ...(args.section === undefined ? {} : { section: args.section }), ...(args.topic === undefined ? {} : { topic: args.topic }), ...(args.offset === undefined ? {} : { offset: integer(args.offset, 'offset', 0, Number.MAX_SAFE_INTEGER) }) };
		case 'survey':
			requireExactKeys(args, ['include', 'exclude', 'limit']);
			return normalizeSurvey({ ...args, section: 'survey' });
		case 'inspect': {
			requireExactKeys(args, ['section', 'offset', 'limit', 'slot', 'x', 'y', 'z', 'afterSequence', 'recipeId', 'entityType', 'outputItemId', 'include', 'exclude']);
			if (!INSPECTION_SECTIONS.includes(args.section)) invalid('section is not supported');
			if (args.section === 'survey') return normalizeSurvey(args);
			if (args.include !== undefined || args.exclude !== undefined) invalid('include and exclude are only valid for survey');
			const query = { kind: 'inspect', section: args.section, offset: optionalInteger(args.offset, 0, 'offset', 0, 4_096), limit: optionalInteger(args.limit, 32, 'limit', 1, 32) };
			if (args.section === 'item') query.slot = integer(args.slot, 'slot', 0, 255);
			else if (args.slot !== undefined) invalid('slot is only valid for item inspection');
			if (args.section === 'block') {
				query.x = integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT);
				query.y = integer(args.y, 'y', -2_048, 2_048);
				query.z = integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT);
			} else if (args.x !== undefined || args.y !== undefined || args.z !== undefined) invalid('coordinates are only valid for block inspection');
			if (args.afterSequence !== undefined) {
				if (args.section !== 'events') invalid('afterSequence is only valid for event inspection');
				query.afterSequence = integer(args.afterSequence, 'afterSequence', 0, Number.MAX_SAFE_INTEGER);
			}
			if (args.recipeId !== undefined) {
				if (args.section !== 'recipes' || typeof args.recipeId !== 'string' || args.recipeId.length > 256 || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(args.recipeId)) invalid('recipeId must be a namespaced recipe identifier for recipe inspection');
				query.recipeId = args.recipeId;
			}
			for (const [field, section] of [['entityType', 'entities'], ['outputItemId', 'recipes']]) {
				if (args[field] === undefined) continue;
				if (args.section !== section || typeof args[field] !== 'string' || args[field].length > 256 || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(args[field])) invalid(`${field} requires a namespaced identifier in ${section}`);
				query[field] = args[field];
			}
			if (args.recipeId !== undefined && args.outputItemId !== undefined) invalid('Use recipeId or outputItemId, not both');
			return query;
		}
		case 'actionStatus':
			requireExactKeys(args, ['actionId']);
			return { kind: 'action_status', ...(args.actionId === undefined ? {} : { actionId: boundedText(args.actionId, 'actionId', 128) }) };
		case 'cancelAction':
			requireExactKeys(args, ['actionId', 'goalRevision']);
			return { kind: 'cancel_action', actionId: boundedText(args.actionId, 'actionId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		case 'replaceAction': {
			requireExactKeys(args, ['actionId', 'goalRevision', 'actionType', 'arguments']);
			const action = normalizeMinecraftToolCall('act', { actionType: args.actionType, arguments: args.arguments });
			return { ...action, kind: 'replace_action', actionId: boundedText(args.actionId, 'actionId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		}
		case 'startAction': {
			const action = normalizeMinecraftToolArguments('act', args);
			return { ...action, kind: 'start_action' };
		}
		case 'taskMemory': {
			requireExactKeys(args, ['operation', 'entry', 'query']);
			if (args.operation === 'remember' && args.query === undefined) {
				try { return { kind: 'task_memory', operation: 'remember', entry: validateTaskEntry(args.entry) }; }
				catch (error) { invalid(error.message); }
			}
			if (args.operation !== 'query' || args.entry !== undefined) invalid('taskMemory requires remember/entry or query/query');
			const query = args.query === undefined ? {} : requireObject(args.query);
			requireExactKeys(query, ['kind', 'text', 'offset', 'limit', 'dimension']);
			if (query.kind !== undefined && !['all', 'place', 'route', 'progress', 'lesson', 'deaths', 'assets', 'trail'].includes(query.kind)) invalid('Unknown task memory kind');
			return { kind: 'task_memory', operation: 'query', query: { kind: query.kind ?? 'all', offset: optionalInteger(query.offset, 0, 'offset', 0, Number.MAX_SAFE_INTEGER), limit: optionalInteger(query.limit, 20, 'limit', 1, 64), ...(query.dimension === undefined ? {} : { dimension: boundedText(query.dimension, 'dimension', 128) }), ...(query.text === undefined || query.text === '' ? {} : { text: boundedText(query.text, 'text', 256) }) } };
		}
		case 'notebook':
			requireExactKeys(args, ['key', 'text']);
			return { kind: 'notebook', key: boundedText(args.key, 'key', 128), text: boundedText(args.text, 'text', 2048) };
		case 'queryMemory':
			requireExactKeys(args, ['kind', 'text', 'limit', 'offset']);
			if (args.kind !== undefined && !['all', 'notes', 'receipts', 'unresolved'].includes(args.kind)) invalid('kind is not supported');
			return { kind: 'query_memory', memoryKind: args.kind ?? 'all', offset: optionalInteger(args.offset, 0, 'offset', 0, Number.MAX_SAFE_INTEGER), limit: optionalInteger(args.limit, 20, 'limit', 1, 64), ...(args.text === undefined ? {} : { text: boundedText(args.text, 'text', 256) }) };
		case 'runProgram': {
			requireExactKeys(args, ['source', 'noteKey', 'parameters', 'background', 'observationIntervalMs', 'maxActions', 'timeoutMs', 'expectedDurationMs']);
			return { kind: 'run_program', ...normalizeProgramArguments(args), ...(args.background === undefined ? {} : { background: optionalBoolean(args.background, false, 'background') }) };
		}
		case 'queueProgram': {
			requireExactKeys(args, ['afterProgramId', 'goalRevision', 'programVersion', 'source', 'noteKey', 'parameters', 'precondition', 'observationIntervalMs', 'maxActions', 'timeoutMs', 'expectedDurationMs']);
			return { kind: 'queue_program', afterProgramId: boundedText(args.afterProgramId, 'afterProgramId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER), programVersion: integer(args.programVersion, 'programVersion', 1, Number.MAX_SAFE_INTEGER), precondition: boundedUtf8Text(args.precondition, 'precondition', MAX_PROGRAM_PRECONDITION_BYTES), ...normalizeProgramArguments(args) };
		}
		case 'cancelQueuedProgram':
			requireExactKeys(args, ['afterProgramId', 'goalRevision', 'queueId']);
			return { kind: 'cancel_queued_program', afterProgramId: boundedText(args.afterProgramId, 'afterProgramId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER), queueId: boundedText(args.queueId, 'queueId', 128) };
		case 'programStatus':
			requireExactKeys(args, ['programId']);
			return { kind: 'program_status', ...(args.programId === undefined ? {} : { programId: boundedText(args.programId, 'programId', 128) }) };
		case 'cancelProgram':
			requireExactKeys(args, ['programId', 'goalRevision']);
			return { kind: 'cancel_program', programId: boundedText(args.programId, 'programId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		case 'respondProgram': {
			requireExactKeys(args, ['programId', 'goalRevision', 'decisionId', 'eventSequence', 'directive', 'source']);
			if (!['continue', 'pause', 'replace', 'finish'].includes(args.directive)) invalid('directive is not supported');
			if ((args.directive === 'replace') !== (args.source !== undefined)) invalid('Only replace requires source');
			const source = args.source === undefined ? undefined : boundedText(args.source, 'source', MAX_PROGRAM_SOURCE_BYTES);
			if (source !== undefined && Buffer.byteLength(source, 'utf8') > MAX_PROGRAM_SOURCE_BYTES) invalid('source must fit 65536 UTF-8 bytes');
			return { kind: 'respond_program', programId: boundedText(args.programId, 'programId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER), decisionId: boundedText(args.decisionId, 'decisionId', 256),
				...(args.eventSequence === undefined ? {} : { eventSequence: integer(args.eventSequence, 'eventSequence', 0, Number.MAX_SAFE_INTEGER) }), directive: args.directive, ...(source === undefined ? {} : { source }) };
		}
		case 'lookAround': {
			requireExactKeys(args, ['centerYaw', 'pitch', 'steps', 'ticksPerStep', 'survey']);
			let survey;
			if (args.survey !== undefined) {
				requireExactKeys(requireObject(args.survey), ['include', 'exclude', 'limit']);
				const { kind: _kind, ...query } = normalizeSurvey({ ...args.survey, section: 'survey' });
				survey = query;
			}
			return {
				kind: 'lookAround',
				centerYaw: finiteNumber(args.centerYaw, 'centerYaw', -180, 180),
				pitch: finiteNumber(args.pitch, 'pitch', -90, 90),
				steps: integer(args.steps, 'steps', 2, MAX_LOOK_AROUND_STEPS),
				ticksPerStep: integer(args.ticksPerStep, 'ticksPerStep', 1, MAX_LOOK_AROUND_TICKS),
				...(survey === undefined ? {} : { survey }),
			};
		}
		case 'control':
			requireExactKeys(args, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks', 'instantLook']);
			try {
				return {
					kind: 'action',
					actionType: 'control',
					arguments: stripActionType(validateAction({ type: 'control', ...args })),
				};
			} catch (error) {
				invalid(error?.message ?? 'invalid control arguments');
			}
			break;
		case 'moveTo':
			requireExactKeys(args, ['x', 'y', 'z', 'tolerance', 'sprint', 'timeoutMs']);
			return normalizeMinecraftToolArguments('act', { actionType: 'navigate_to', arguments: args });
		case 'exploreFrontier': {
			requireExactKeys(args, ['radius', 'limit', 'blockId', 'kind']);
			if (args.kind !== undefined && !['all', 'observed_block', 'unknown_cell'].includes(args.kind)) invalid('Unknown frontier candidate kind');
			return {
				kind: 'explore_frontier',
				arguments: {
					...(args.kind === undefined ? {} : { kind: args.kind }),
					radius: optionalInteger(args.radius, 24, 'radius', 8, 32),
					limit: optionalInteger(args.limit, 32, 'limit', 1, 64),
					...(args.blockId === undefined ? {} : { blockId: boundedText(args.blockId, 'blockId', MAX_IDENTIFIER_LENGTH) }),
				},
			};
		}
		case 'mine':
			requireExactKeys(args, ['x', 'y', 'z', 'expectedBlockId', 'timeoutMs', 'autoAim']);
			try {
				const autoAim = optionalBoolean(args.autoAim, false, 'autoAim');
				const { autoAim: _autoAim, ...miningArguments } = args;
				const normalized = normalizeMinecraftToolArguments('act', { actionType: 'break_block', arguments: miningArguments });
				const action = { type: normalized.actionType, ...normalized.arguments };
				if (autoAim) {
					// Expand only the caller's chosen target; the sequence executor stops on failed aim.
					const aim = validateAction({ type: 'look_at', x: action.x + 0.5, y: action.y + 0.5, z: action.z + 0.5 });
					return { kind: 'sequence', actions: [{ actionType: aim.type, arguments: stripActionType(aim) }, { actionType: action.type, arguments: stripActionType(action) }] };
				}
				return { kind: 'action', actionType: action.type, arguments: stripActionType(action) };
			} catch (error) {
				invalid(error?.message ?? 'invalid mining arguments');
			}
			break;
		case 'say': {
			requireExactKeys(args, ['message', 'audience', 'recipientId']);
			if (typeof args.message !== 'string' || [...args.message].length > MAX_CHAT_LENGTH) invalid(`message must be 1 to ${MAX_CHAT_LENGTH} Unicode code points`);
			const message = args.message;
			const recipientId = args.recipientId === undefined ? undefined : boundedText(args.recipientId, 'recipientId', MAX_IDENTIFIER_LENGTH);
			const audience = args.audience === undefined
				? recipientId === undefined ? 'public' : 'direct'
				: args.audience;
			if (!['public', 'direct', 'proximity'].includes(audience)) invalid('audience is not supported');
			if (audience === 'direct' && recipientId === undefined) invalid('direct speech requires recipientId');
			if (audience !== 'direct' && recipientId !== undefined) invalid(`${audience} speech cannot use recipientId`);
			return normalizeMinecraftToolArguments('act', { actionType: 'chat', arguments: audience === 'direct'
				? { message, audience, recipientId }
				: { message, audience } });
		}
		case 'wait':
			requireExactKeys(args, ['durationMs']);
			return { kind: 'action', actionType: 'wait', arguments: { durationMs: integer(args.durationMs, 'durationMs', MIN_DURATION_MS, MAX_DURATION_MS) } };
		case 'act': {
			requireExactKeys(args, ['actionType', 'arguments']);
			if (typeof args.actionType !== 'string' || !NATIVE_ACTION_TYPES.includes(args.actionType)) invalid('actionType is not supported');
			let actionArguments = requireObject(args.arguments);
			if (Object.hasOwn(actionArguments, 'type')) invalid('arguments.type is reserved; use actionType');
			// Aliases and sequence steps share defaults, then the canonical action bounds.
			if (args.actionType === 'navigate_to') actionArguments = { tolerance: 1, sprint: true, timeoutMs: 30_000, ...actionArguments };
			if (args.actionType === 'break_block') actionArguments = { timeoutMs: 15_000, ...actionArguments };
			try {
				const normalizedArguments = stripActionType(validateAction({ type: args.actionType, ...actionArguments }));
				return { kind: 'action', actionType: args.actionType, arguments: normalizedArguments };
			} catch (error) {
				throw Object.assign(codedError('INVALID_MINECRAFT_TOOL_ARGUMENTS', error?.message ?? 'invalid action arguments'), {
					actionContract: { actionType: args.actionType,
						requiredFields: ACTION_FIELDS[args.actionType].filter((field) => !(OPTIONAL_ACTION_FIELDS[args.actionType] ?? []).includes(field)),
						optionalFields: [...(OPTIONAL_ACTION_FIELDS[args.actionType] ?? [])],
						...(args.actionType === 'menu_close' ? { hint: 'menuId is the observed namespaced string, not containerId. The default minecraft:inventory menu with containerId 0 does not block gameplay and need not be closed.' } : {}),
					},
				});
			}
			break;
		}
		case 'sequence': {
			requireExactKeys(args, ['actions', 'finish']);
			if (!Array.isArray(args.actions) || args.actions.length < 2 || args.actions.length > MAX_SEQUENCE_ACTIONS) {
				invalid(`actions must contain 2 to ${MAX_SEQUENCE_ACTIONS} entries`);
			}
			const finish = args.finish === undefined ? undefined : normalizeMinecraftToolCall('finish', args.finish);
			return {
				kind: 'sequence',
				actions: args.actions.map(normalizeSequenceAction),
				...(finish === undefined ? {} : { finish: { summary: finish.summary } }),
			};
		}
		case 'takeTask': {
			requireExactKeys(args, ['request', 'resume', 'requesterId']);
			if (args.requesterId !== undefined && (typeof args.requesterId !== 'string' || !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(args.requesterId))) invalid('requesterId must be a player UUID');
			return {
				kind: 'take_task',
				...(args.request === undefined ? {} : { request: boundedText(args.request, 'request', 512) }),
				resume: optionalBoolean(args.resume, false, 'resume'),
				...(args.requesterId === undefined ? {} : { requesterId: args.requesterId.toLowerCase() }),
			};
		}
		case 'finish':
			requireExactKeys(args, ['summary']);
			return {
				kind: 'finish',
				summary: boundedText(args.summary, 'summary', 512),
			};
		default:
			throw codedError('UNKNOWN_MINECRAFT_TOOL', `Unknown Minecraft tool '${String(name)}'`);
	}
}

/** A survey inspection: sections to include (plus blocks:<id> searches) or exclude, and rows per section. */
function normalizeSurvey(args) {
	const { section: _section, include, exclude, limit, ...rest } = args;
	if (Object.keys(rest).length > 0) invalid(`survey does not accept ${Object.keys(rest).join(', ')}`);
	const list = (value, field, allowSearch, maximum) => {
		if (value === undefined) return undefined;
		if (!Array.isArray(value) || value.length > maximum) invalid(`${field} must be an array of at most ${maximum} entries`);
		return [...new Set(value.map((entry) => {
			if (typeof entry !== 'string' || !(SURVEY_SECTIONS.includes(entry) || (allowSearch && entry.length <= 263 && SURVEY_INCLUDE_PATTERN.test(entry)))) {
				invalid(`${field} entries are ${SURVEY_SECTIONS.join(', ')}${allowSearch ? ' or blocks:<namespaced block id>' : ''}`);
			}
			return entry;
		}))];
	};
	const included = list(include, 'include', true, 8);
	const excluded = list(exclude, 'exclude', false, 7);
	if (included !== undefined && included.filter((entry) => entry.startsWith('blocks:')).length > 4) invalid('at most 4 blocks:<id> searches');
	return { kind: 'inspect', section: 'survey', limit: optionalInteger(limit, 4, 'limit', 1, MAX_SURVEY_ROWS),
		...(included === undefined ? {} : { include: included }), ...(excluded === undefined ? {} : { exclude: excluded }) };
}

function normalizeSequenceAction(value) {
	const action = requireObject(value);
	requireExactKeys(action, ['actionType', 'arguments']);
	const normalized = normalizeMinecraftToolCall('act', action);
	return { actionType: normalized.actionType, arguments: normalized.arguments };
}

export function toolResultContent(value, success = true) {
	let text = JSON.stringify(value ?? null);
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES) {
		// Keep fallback order stable, but construct only candidates actually needed.
		const candidates = [
			...(value?.postAction?.observation === undefined || isSequenceResult(value) ? [] : [() => compactActionFeedback(value)]),
			...(Array.isArray(value?.entries) ? [() => compactInspectionResult(value), () => compactInspectionResult(value, true)] : []),
			...(value?.section === 'item' && ['pages', 'tooltipPage'].some((key) => Array.isArray(value[key]?.entries)) ? [() => compactItemInspection(value)] : []),
			...(isSequenceResult(value) ? [() => compactSequenceResult(value)] : []),
			...(Array.isArray(value?.receipts) && typeof value?.programId === 'string' ? [() => compactProgramResult(value)] : []),
			() => compactToolResult(value),
			() => ({ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 8), detail: 'Details exceeded the result limit. Use inspect for focused pages.' }),
			() => ({ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 2), detail: 'Details exceeded the result limit. Use inspect for focused pages.' }),
			() => ({ state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Use inspect for focused facts; omitted data is unknown.' }),
		];
		for (const candidate of candidates) {
			text = JSON.stringify(candidate());
			if (Buffer.byteLength(text, 'utf8') <= MAX_TOOL_RESULT_BYTES) break;
		}
	}
	return { success, contentItems: [{ type: 'inputText', text }] };
}

function compactActionFeedback(value) {
	const result = {
		...resultMetadata(value),
		state: boundedResultField(value.state, 64),
		reasonCode: boundedResultField(value.reasonCode, 128),
		...Object.fromEntries(['executionStarted', 'physicalAttempted'].filter(key => typeof value[key] === 'boolean').map(key => [key, value[key]])),
		...(value.message === undefined ? {} : { message: boundedResultField(value.message, 2_048) }),
		...(value.recoveryHint === undefined ? {} : { recoveryHint: boundedResultField(value.recoveryHint, 2_048) }),
		...survivalFacts(value, 8),
		truncated: true,
		detail: 'Detailed action observations were omitted. postAction contains the follow-up facts; inspect omitted details.',
	};
	result.postAction = compactPostAction(value.postAction, MAX_TOOL_RESULT_BYTES - Buffer.byteLength(JSON.stringify(result), 'utf8') - 64);
	return result;
}

function compactPostAction(value, budget) {
	if (Buffer.byteLength(JSON.stringify(value), 'utf8') <= budget) return value;
	const compact = compactToolResult(value, budget);
	if (Buffer.byteLength(JSON.stringify(compact), 'utf8') <= budget) return compact;
	const items = asToolArray(value.observation?.inventory?.items);
	const fallback = {
		truncated: true,
		detail: 'Goal and observation details exceeded the result limit. Inventory entries below are partial; inspect omitted facts.',
		eventSequence: safeResultInteger(value.eventSequence),
		freshness: { fresh: value.freshness?.fresh === true, ...(value.freshness?.reasonCode === undefined ? {} : { reasonCode: boundedResultField(value.freshness.reasonCode, 128) }) },
		observation: { inventory: { items: [] }, resultCoverage: { ...value.observation?.resultCoverage, inventory: { retained: 0, availableInSnapshot: items.length }, omittedSections: [...new Set([
			...(value.observation?.resultCoverage?.omittedSections ?? []),
			...Object.keys(value.observation ?? {}).filter(key => key !== 'inventory' && key !== 'resultCoverage'),
		])] } },
	};
	// Metadata is optional too: large tag maps must not displace authoritative
	// receipts. Keep whole fields when they fit and explicitly mark rejected ones.
	for (const [key, field] of Object.entries(selectResultFields(value.observation?.inventory, INVENTORY_FACT_FIELDS))) {
		fallback.observation.inventory[key] = field;
		updateObservationOmissions(value.observation, fallback.observation);
		if (Buffer.byteLength(JSON.stringify(fallback), 'utf8') > budget - 64) delete fallback.observation.inventory[key];
	}
	updateObservationOmissions(value.observation, fallback.observation);
	for (const item of items) {
		const row = selectResultFields(item, COMPACT_ROW_FIELDS);
		fallback.observation.inventory.items.push(row);
		updateObservationOmissions(value.observation, fallback.observation);
		if (Buffer.byteLength(JSON.stringify(fallback), 'utf8') > budget - 64) { fallback.observation.inventory.items.pop(); break; }
	}
	fallback.observation.resultCoverage.inventory.retained = fallback.observation.inventory.items.length;
	updateObservationOmissions(value.observation, fallback.observation);
	return fallback;
}

function compactToolResult(value, budget = MAX_TOOL_RESULT_BYTES) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) {
		return { state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' };
	}
	const observation = value.observation !== null && typeof value.observation === 'object' ? value.observation : value;
	const hasMinecraftFacts = observation.player !== undefined
		|| observation.inventory !== undefined
		|| observation.death !== undefined
		|| observation.recovery !== undefined
		|| value.state !== undefined
		|| value.reasonCode !== undefined;
	if (!hasMinecraftFacts) {
		return { state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' };
	}
	const compact = {
		...resultMetadata(value),
		truncated: true,
		detail: 'Observation details were omitted by the result limit. Use inspect for focused pages.',
		...(value.state === undefined ? {} : { state: value.state }),
		...(value.reasonCode === undefined ? {} : { reasonCode: value.reasonCode }),
		...(value.eventSequence === undefined ? {} : { eventSequence: value.eventSequence }),
		...(value.goal === undefined && value.goalSpec?.originalRequest === undefined ? {} : { omittedGoalText: true }),
		...(value.goalSpec === undefined ? {} : { goalSpec: value.goalSpec === null ? null : Object.fromEntries(Object.entries(value.goalSpec).filter(([key]) => key !== 'originalRequest')) }),
		observation: {
			...resultMetadata(observation),
			player: observation.player ?? {},
			inventory: { ...selectResultFields(observation.inventory, INVENTORY_FACT_FIELDS), items: asToolArray(observation.inventory?.items).slice(0, 16) },
			...(observation.position === undefined ? {} : { position: observation.position }),
			...(observation.velocity === undefined ? {} : { velocity: observation.velocity }),
			...(observation.view === undefined ? {} : { view: observation.view }),
			...(observation.interaction === undefined ? {} : { interaction: compactInteraction(observation.interaction) }),
			...(observation.perception === undefined ? {} : { perception: observation.perception }),
			resultCoverage: { ...observation.resultCoverage, inventory: { retained: Math.min(asToolArray(observation.inventory?.items).length, 16), availableInSnapshot: asToolArray(observation.inventory?.items).length }, omittedSections: [] },
			...(observation.death === undefined ? {} : { death: observation.death }),
			...(observation.recovery === undefined ? {} : { recovery: compactRecovery(observation.recovery, 8) }),
			...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
			...(observation.lastResult === undefined ? {} : { lastResult: compactLastResult(observation.lastResult) }),
			...(observation.world === undefined ? {} : { world: compactWorld(observation.world) }),
			...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
			...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
			...(observation.sighted === undefined ? {} : { sighted: observation.sighted }),
			...(observation.leftBehind === undefined ? {} : { leftBehind: observation.leftBehind }),
		},
		...survivalFacts(value),
	};
	const sections = ['entities', 'items', 'blocks', 'landmarks', 'nearbyContainers'].filter(section => section !== 'items' || Array.isArray(observation.items));
	const sources = Object.fromEntries(sections.map((section) => [section, asToolArray(observation[section])]));
	for (const section of sections) {
		compact.observation[section] = [];
		compact.observation.resultCoverage[section] = { retained: 0, availableInSnapshot: sources[section].length, detailsOmitted: true };
	}
	updateObservationOmissions(observation, compact.observation);
	// Share the remaining budget across kinds of visible facts before adding more of any one kind.
	for (let index = 0; index < 32; index++) for (const section of sections) {
		if (index >= sources[section].length || compact.observation[section].length !== index) continue;
		const entry = sources[section][index];
		const row = selectResultFields(entry, COMPACT_ROW_FIELDS);
		compact.observation[section].push(row);
		updateObservationOmissions(observation, compact.observation);
		if (Buffer.byteLength(JSON.stringify(compact), 'utf8') > budget - 256) compact.observation[section].pop();
		compact.observation.resultCoverage[section].retained = compact.observation[section].length;
	}
	updateObservationOmissions(observation, compact.observation);
	for (const section of sections) {
		compact.observation.resultCoverage[section].detailsOmitted = compact.observation[section].length < sources[section].length
			|| compact.observation.resultCoverage.omittedFields.some(path => path.startsWith(`${section}[]`));
	}
	compact.observation.resultCoverage.omittedSections = [...new Set([
		...(observation.resultCoverage?.omittedSections ?? []),
		...Object.keys(observation).filter((key) => !Object.hasOwn(compact.observation, key)),
		...sections.filter((section) => sources[section].length > 0 && compact.observation[section].length === 0),
	])];
	return compact;
}

function selectResultFields(value, fields) {
	return Object.fromEntries(fields.filter((key) => value?.[key] !== undefined).map((key) => [key, value[key]]));
}

function updateObservationOmissions(source, retained) {
	// Row counts describe only retained rows. Paths also identify shortened arrays
	// without row coverage; [] means at least one retained row lost that field.
	const omitted = new Set(source?.resultCoverage?.omittedFields ?? []);
	const visit = (original, compact, path) => {
		if (original === compact) return;
		if (Array.isArray(original) && Array.isArray(compact)) {
			const coverage = retained.resultCoverage[path === 'inventory.items' ? 'inventory' : path];
			if (original.length > compact.length && !(coverage?.availableInSnapshot === original.length && coverage?.retained === compact.length)) omitted.add(path);
			for (let index = 0; index < Math.min(original.length, compact.length); index++) visit(original[index], compact[index], `${path}[]`);
		} else if (original !== null && typeof original === 'object' && compact !== null && typeof compact === 'object') {
			for (const key of Object.keys(original)) {
				if (key === 'resultCoverage') continue;
				const field = path ? `${path}.${key}` : key;
				if (!Object.hasOwn(compact, key)) omitted.add(field);
				else visit(original[key], compact[key], field);
			}
		} else if (original !== compact) omitted.add(path);
	};
	visit(source ?? {}, retained, '');
	retained.resultCoverage.omittedFields = [...omitted].sort();
}

function resultMetadata(value) {
	if (value === null || typeof value !== 'object') return {};
	return {
		...Object.fromEntries(['actionId', 'programId', 'programVersion', 'queueId', 'afterProgramId', 'goalRevision', 'eventSequence', 'freshness', 'coverage', 'revision', 'sectionRevisions', 'observedAtEpochMs', 'executionSettings', 'unresolvedActions'].filter((key) => value[key] !== undefined).map((key) => [key, value[key]])),
		...Object.fromEntries(['actionsSucceeded', 'actionsFailed'].filter((key) => value[key] !== undefined).map((key) => [key, Number.isSafeInteger(value[key]) && value[key] >= 0 ? value[key] : null])),
		...(typeof value.successorProgramId === 'string' ? { successorProgramId: boundedResultField(value.successorProgramId, 128) } : {}),
		...Object.fromEntries(['pendingSuccessor', 'discardedSuccessor'].filter((key) => value[key] !== undefined).map((key) => [key, compactSuccessorSummary(value[key])])),
	};
}

function compactSuccessorSummary(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
	return {
		...Object.fromEntries(['queueId', 'afterProgramId', 'state', 'sourceOrigin', 'reasonCode'].filter((key) => typeof value[key] === 'string').map((key) => [key, boundedResultField(value[key], key === 'state' || key === 'sourceOrigin' ? 64 : 128)])),
		...Object.fromEntries(['goalRevision', 'programVersion', 'maxActions', 'timeoutMs'].filter((key) => value[key] !== undefined).map((key) => [key, Number.isSafeInteger(value[key]) && value[key] >= 0 ? value[key] : null])),
	};
}

function compactInteraction(interaction) {
	if (interaction === null || typeof interaction !== 'object') return interaction;
	const menu = interaction.menu;
	return { ...interaction, ...(menu == null ? {} : { menu: { ...menu, ...(Array.isArray(menu.slots) ? { slots: menu.slots.slice(0, 8), resultCoverage: { retained: Math.min(menu.slots.length, 8), availableInSnapshot: menu.slots.length } } : {}) } }) };
}

function compactInspectionResult(value, trimMetadata = false) {
	const result = { ...(trimMetadata ? compactInspectionMetadata(value) : value), entries: [], truncated: true, detail: 'Inspection entries exceeded the result limit; continue at nextOffset.', coverage: { ...value.coverage, resultTruncated: true } };
	for (let entry of value.entries) {
		const candidate = { ...result, entries: [...result.entries, entry] };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) {
			// Preserve whole rows when they can fit on the next page. An individually
			// oversized receipt still needs a factual identity and a way past it.
			if (result.entries.length > 0 || entry?.kind !== 'receipt') break;
			entry = compactMemoryReceipt(entry);
			if (Buffer.byteLength(JSON.stringify({ ...result, entries: [entry] }), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) break;
			result.coverage.complete = false;
		}
		result.entries.push(entry);
	}
	const offset = Number.isSafeInteger(value.offset) ? value.offset : Number.isSafeInteger(value.coverage?.offset) ? value.coverage.offset : 0;
	const removedEntries = result.entries.length < value.entries.length;
	// Server pages can deliberately contain no rows while advancing over one
	// explicitly omitted entry. Preserve that coverage rather than restarting it.
	result.nextOffset = removedEntries ? offset + result.entries.length : Object.hasOwn(value, 'nextOffset') ? value.nextOffset : value.coverage?.nextOffset;
	result.coverage.returned = result.entries.length;
	result.coverage.nextOffset = result.nextOffset;
	if (removedEntries) {
		result.coverage.complete = false;
		if (Object.hasOwn(result.coverage, 'hasMore')) result.coverage.hasMore = true;
	}
	if (value.entries.length === 0) result.detail = 'Inspection metadata exceeded the result limit. Entry coverage and continuation are preserved; omitted content remains unknown.';
	if (removedEntries && result.entries.length === 0) {
		result.reasonCode = 'ENTRY_EXCEEDS_RESULT_LIMIT';
		result.detail = 'One inspection entry exceeds the result limit. Its contents remain unknown.';
		result.nextOffset = null;
		result.coverage.nextOffset = null;
	}
	return result;
}

function compactMemoryReceipt(entry) {
	const fields = ['kind', 'source', 'worldId', 'actionId', 'actionType', 'state', 'reasonCode', 'dimension', 'goalRevision', 'tick', 'revision', 'executionStarted', 'physicalAttempted'];
	const summary = Object.fromEntries(fields.filter((key) => entry[key] !== undefined).map((key) => [key, entry[key]]));
	return { ...summary, truncated: true, omittedFields: Object.keys(entry).filter((key) => !fields.includes(key)), detail: 'Historical receipt summary; omitted arguments and evidence remain unknown.' };
}

function compactInspectionMetadata(value) {
	const result = { ...resultMetadata(value), ...Object.fromEntries(['section', 'worldId', 'dimension', 'gameTime', 'offset', 'total', 'nextOffset'].filter((key) => value[key] !== undefined).map((key) => [key, value[key]])) };
	for (const key of ['item', 'menu']) if (value[key] && typeof value[key] === 'object') {
		// Keep concrete slot/menu identity for the follow-up query, not bulky details.
		result[key] = Object.fromEntries(Object.entries(value[key]).filter(([, field]) => typeof field === 'number' || typeof field === 'boolean' || typeof field === 'string' && field.length <= 256));
	}
	const omittedFields = Object.keys(value).filter((key) => !Object.hasOwn(result, key) && !['entries', 'coverage', 'pages', 'tooltipPage'].includes(key));
	return { ...result, ...(omittedFields.length === 0 ? {} : { omittedFields }) };
}

function compactItemInspection(value) {
	return {
		...compactInspectionMetadata(value), truncated: true,
		detail: 'Item details exceeded the result limit. Paged coverage and continuation are preserved; omitted content remains unknown.',
		...Object.fromEntries(['pages', 'tooltipPage'].filter((key) => Array.isArray(value[key]?.entries)).map((key) => [key, value[key]])),
	};
}

function asToolArray(value) {
	return Array.isArray(value) ? value : [];
}

function survivalFacts(value, maxStacks = 16) {
	const observation = value?.observation !== null && typeof value?.observation === 'object' ? value.observation : value;
	return {
		...(observation?.death === undefined ? {} : { death: observation.death }),
		...(observation?.recovery === undefined ? {} : { recovery: compactRecovery(observation.recovery, maxStacks) }),
		...(value?.recovery === undefined ? {} : { recovery: compactRecovery(value.recovery, maxStacks) }),
		...(observation?.failureClass === undefined && value?.failureClass === undefined ? {} : { failureClass: observation?.failureClass ?? value.failureClass }),
	};
}

function compactRecovery(recovery, maxStacks = 16) {
	if (recovery === null || typeof recovery !== 'object') return recovery;
	const lostCap = Math.min(16, maxStacks);
	const haveCap = Math.min(32, Math.max(2, maxStacks * 2));
	return {
		...(recovery.lastDeath === undefined ? {} : { lastDeath: recovery.lastDeath }),
		...(Array.isArray(recovery.lastLostInventory) ? { lastLostInventory: recovery.lastLostInventory.slice(0, lostCap) } : {}),
		...(Array.isArray(recovery.alreadyHave) ? { alreadyHave: recovery.alreadyHave.slice(-haveCap) } : {}),
		...(Array.isArray(recovery.alreadyHaveFacts) ? { alreadyHaveFacts: recovery.alreadyHaveFacts.slice(-haveCap) } : {}),
		...(Array.isArray(recovery.alreadyHaveFacts) && recovery.alreadyHaveFacts.length > haveCap ? { omittedAlreadyHaveFacts: recovery.alreadyHaveFacts.length - haveCap } : {}),
		...(typeof recovery.facts === 'string' ? { facts: recovery.facts.slice(0, maxStacks <= 2 ? 160 : 512) } : {}),
	};
}

function compactLastResult(lastResult) {
	if (lastResult === null || typeof lastResult !== 'object') return lastResult;
	return {
		...(lastResult.state === undefined ? {} : { state: lastResult.state }),
		...(lastResult.reasonCode === undefined ? {} : { reasonCode: lastResult.reasonCode }),
	};
}

function compactWorld(world) {
	if (world === null || typeof world !== 'object') return world;
	return {
		...(world.worldId === undefined ? {} : { worldId: world.worldId }),
		...(world.gameTime === undefined ? {} : { gameTime: world.gameTime }),
		...(world.dimension === undefined ? {} : { dimension: world.dimension }),
		...(world.dimensionId === undefined ? {} : { dimensionId: world.dimensionId }),
	};
}
function isSequenceResult(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value) && Array.isArray(value.results);
}

function compactProgramResult(value) {
	const result = { ...resultMetadata(value), state: boundedResultField(value.state, 64), reasonCode: boundedResultField(value.reasonCode, 128), programId: boundedResultField(value.programId, 256), actions: safeResultInteger(value.actions), eventSequence: safeResultInteger(value.eventSequence), ...(value.finishRequested === true ? { finishRequested: true } : {}), receipts: [], truncated: true, detail: 'Program observations were omitted. Query historical receipts by bodyActionId and observe for current facts.' };
	if (value.observation) {
		const compact = compactToolResult({ observation: value.observation }, MAX_TOOL_RESULT_BYTES - 2048);
		if (Buffer.byteLength(JSON.stringify(compact), 'utf8') < MAX_TOOL_RESULT_BYTES - 2048) {
			result.observation = compact.observation;
			result.detail = 'Compact current facts retained. Query historical receipts by bodyActionId; inspect omitted details.';
		}
	}
	for (const receipt of [...value.receipts].reverse()) {
		const next = Object.fromEntries(['actionId', 'bodyActionId', 'actionType', 'sourceStepId', 'state', 'reasonCode'].filter((field) => receipt[field] !== undefined).map((field) => [field, boundedResultField(receipt[field], field === 'reasonCode' ? 128 : 256)]));
		for (const flag of ['executionStarted', 'physicalAttempted']) if (typeof receipt[flag] === 'boolean') next[flag] = receipt[flag];
		if (Buffer.byteLength(JSON.stringify({ ...result, receipts: [next, ...result.receipts] }), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) break;
		result.receipts.unshift(next);
	}
	result.omittedReceipts = Math.max(0, value.omittedReceipts ?? 0) + value.receipts.length - result.receipts.length;
	return result;
}

function compactSequenceResult(value) {
	const sourceResults = value.results.slice(0, MAX_SEQUENCE_ACTIONS);
	const result = {
		state: boundedResultField(value.state, 64),
		completed: safeResultInteger(value.completed),
		...(safeResultInteger(value.failedAt) === null ? {} : { failedAt: safeResultInteger(value.failedAt) }),
		results: sourceResults.map((step) => ({
			...Object.fromEntries(['actionId', 'bodyActionId'].filter(field => step?.[field] !== undefined).map(field => [field, boundedResultField(step[field], 256)])),
			actionType: boundedResultField(step?.actionType, 64),
			state: boundedResultField(step?.state, 64),
			reasonCode: boundedResultField(step?.reasonCode, 128),
			...(step?.executionStarted === undefined ? {} : { executionStarted: step.executionStarted === true }),
			...(step?.physicalAttempted === undefined ? {} : { physicalAttempted: step.physicalAttempted === true }),
		})),
	};
	if (value.finish !== undefined) result.finish = compactSequenceFinish(value.finish);
	if (Array.isArray(value.samples)) {
		const samples = value.samples.slice(0, 8);
		if (Buffer.byteLength(JSON.stringify({ ...result, samples }), 'utf8') <= MAX_TOOL_RESULT_BYTES - 2_048) result.samples = samples;
		else result.omittedSamples = value.samples.length;
	}
	if (value.postAction !== undefined) {
		result.postAction = compactPostAction(value.postAction, MAX_TOOL_RESULT_BYTES - Buffer.byteLength(JSON.stringify(result), 'utf8') - 512);
	}
	let omittedObservations = sourceResults.length !== value.results.length;
	for (let index = 0; index < sourceResults.length; index += 1) {
		const observation = sourceResults[index]?.actionObservation;
		if (observation === undefined) continue;
		const candidate = { ...result, results: result.results.map((step, stepIndex) => stepIndex === index ? { ...step, actionObservation: observation } : step) };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') <= MAX_TOOL_RESULT_BYTES - 512) result.results[index] = candidate.results[index];
		else omittedObservations = true;
	}
	if (omittedObservations) result.detail = value.postAction === undefined
		? 'Some per-action observations were omitted by the coordinator result limit; call observe for fresh compact facts.'
		: 'Some per-action observations were omitted by the coordinator result limit. postAction contains facts after the last attempted step.';
	return result;
}

function compactSequenceFinish(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return { state: 'UNKNOWN', reasonCode: 'INVALID_FINISH_RESULT' };
	const finish = {
		...(typeof value.state === 'string' ? { state: boundedResultField(value.state, 64) } : {}),
		...(typeof value.verified === 'boolean' ? { verified: value.verified } : {}),
		...(typeof value.reasonCode === 'string' ? { reasonCode: boundedResultField(value.reasonCode, 128) } : {}),
		...(typeof value.message === 'string' ? { message: boundedResultField(value.message, 512) } : {}),
	};
	if (!Array.isArray(value.facts)) return finish;
	const facts = value.facts.map((fact) => ({
		...(typeof fact?.type === 'string' ? { type: boundedResultField(fact.type, 128) } : {}),
		...(typeof fact?.satisfied === 'boolean' ? { satisfied: fact.satisfied } : {}),
		...(typeof fact?.expectedValue === 'string' ? { expectedValue: boundedResultField(fact.expectedValue, 512) } : {}),
		...(typeof fact?.observedValue === 'string' ? { observedValue: boundedResultField(fact.observedValue, 512) } : {}),
	}));
	const allFacts = { ...finish, facts };
	if (Buffer.byteLength(JSON.stringify(allFacts), 'utf8') <= MAX_SEQUENCE_FINISH_BYTES) return allFacts;
	const ordered = [...facts.filter((fact) => fact.satisfied === false), ...facts.filter((fact) => fact.satisfied !== false)];
	const retained = [];
	for (const fact of ordered) {
		const omittedFacts = facts.length - retained.length - 1;
		const candidate = { ...finish, facts: [...retained, fact], ...(omittedFacts > 0 ? { omittedFacts, factsTruncated: true } : {}) };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') > MAX_SEQUENCE_FINISH_BYTES - 64) break;
		retained.push(fact);
	}
	return { ...finish, facts: retained, omittedFacts: facts.length - retained.length, factsTruncated: true };
}

function boundedResultField(value, maximum) { return String(value ?? '').slice(0, maximum); }
function safeResultInteger(value) { return Number.isSafeInteger(value) ? value : null; }

function tool(name, description, inputSchema) {
	if (POST_ACTION_VIEW_TOOLS.has(name)) {
		inputSchema = { ...inputSchema, properties: { ...inputSchema.properties, ...OBSERVATION_VIEW_PROPERTIES } };
		description += " postAction facts use observe's view contract.";
	}
	return Object.freeze({ type: 'function', name, description, inputSchema: Object.freeze(inputSchema) });
}

function objectSchema(properties, required = []) {
	return { type: 'object', properties, required, additionalProperties: false };
}

function numberSchema(minimum, maximum) { return { type: 'number', minimum, maximum }; }
function integerSchema(minimum, maximum) { return { type: 'integer', minimum, maximum }; }

function requireObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid('arguments must be an object');
	return value;
}

function requireExactKeys(value, allowed) {
	const allowedSet = new Set(allowed);
	for (const key of Object.keys(value)) if (!allowedSet.has(key)) invalid(`unexpected argument '${key}'`);
}

function boundedText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) invalid(`${field} must be 1 to ${maximum} characters`);
	return value;
}

function boundedUtf8Text(value, field, maximum) {
	boundedText(value, field, maximum);
	if (Buffer.byteLength(value, 'utf8') > maximum) invalid(`${field} must fit ${maximum} UTF-8 bytes`);
	return value;
}

function normalizeProgramArguments(args) {
	if ((args.source === undefined) === (args.noteKey === undefined)) invalid('Supply exactly one of source or noteKey');
	const timeoutMs = optionalInteger(args.timeoutMs, 30_000, 'timeoutMs', 1, 120_000);
	let parameters;
	if (args.parameters !== undefined) {
		try { parameters = validateProgramParameters(args.parameters); }
		catch (error) { invalid(error?.message ?? 'parameters must be bounded pure JSON data'); }
	}
	return {
		...(args.source === undefined ? { noteKey: boundedText(args.noteKey, 'noteKey', 128) } : { source: boundedUtf8Text(args.source, 'source', MAX_PROGRAM_SOURCE_BYTES) }),
		...(parameters === undefined ? {} : { parameters }),
		...(args.observationIntervalMs === undefined ? {} : { observationIntervalMs: integer(args.observationIntervalMs, 'observationIntervalMs', 100, 5000) }),
		maxActions: optionalInteger(args.maxActions, 64, 'maxActions', 1, 256),
		timeoutMs,
		...(args.expectedDurationMs === undefined ? {} : { expectedDurationMs: integer(args.expectedDurationMs, 'expectedDurationMs', 1, timeoutMs) }),
	};
}

function finiteNumber(value, field, minimum, maximum) {
	if (!Number.isFinite(value) || value < minimum || value > maximum) invalid(`${field} must be between ${minimum} and ${maximum}`);
	return value;
}

function integer(value, field, minimum, maximum) {
	if (!Number.isSafeInteger(value)) invalid(`${field} must be an integer`);
	return finiteNumber(value, field, minimum, maximum);
}

function optionalInteger(value, fallback, field, minimum, maximum) {
	return value === undefined ? fallback : integer(value, field, minimum, maximum);
}

function optionalBoolean(value, fallback, field) {
	if (value === undefined) return fallback;
	if (typeof value !== 'boolean') invalid(`${field} must be a boolean`);
	return value;
}

function stripActionType(action) {
	const { type: _type, ...argumentsValue } = action;
	return argumentsValue;
}

function invalid(message) { throw codedError('INVALID_MINECRAFT_TOOL_ARGUMENTS', message); }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
