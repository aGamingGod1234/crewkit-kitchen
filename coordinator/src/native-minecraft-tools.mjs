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

const MAX_TOOL_RESULT_BYTES = 16_384;
const COORDINATE_LIMIT = 30_000_000;
const MAX_SEQUENCE_ACTIONS = 8;
const MAX_LOOK_AROUND_STEPS = 8;
const MAX_LOOK_AROUND_TICKS = 20;
const MAX_PROGRAM_SOURCE_BYTES = 65_536;
const MAX_SEQUENCE_FINISH_BYTES = 4_096;
const NATIVE_ACTION_TYPES = Object.freeze(Object.keys(ACTION_FIELDS));
export const INSPECTION_SECTIONS = Object.freeze(['observation', 'inventory', 'menu', 'entities', 'blocks', 'landmarks', 'nearby_containers', 'item', 'block', 'events', 'recipes', 'mechanics']);

export function minecraftCapabilities({ section = 'all' } = {}) {
	if (section === 'program') return { version: 1, section: 'program', engine: 'ArenaScript', reference: ARENA_SCRIPT_API_REFERENCE };
	return {
		version: 1,
		actions: Object.entries(ACTION_FIELDS).map(([actionType, fields]) => ({ actionType, fields: [...fields], requiredFields: fields.filter((field) => !(OPTIONAL_ACTION_FIELDS[actionType] ?? []).includes(field)), optionalFields: [...(OPTIONAL_ACTION_FIELDS[actionType] ?? [])] })),
		controlConditions: { ...CONTROL_BRANCH_CONDITIONS },
		inspectionSections: [...INSPECTION_SECTIONS],
		programReference: { tool: 'capabilities', arguments: { section: 'program' } },
		limits: { sequenceActions: MAX_SEQUENCE_ACTIONS, inspectionPage: 32, resultBytes: MAX_TOOL_RESULT_BYTES, actionArgumentBytes: MAX_ACTION_ARGUMENT_BYTES, programSourceBytes: MAX_PROGRAM_SOURCE_BYTES, programActions: 256, programTimeoutMs: 120_000 },
	};
}

export const NATIVE_AGENT_INSTRUCTIONS = `You control one live Minecraft player and choose every action.

Keep provider/model/effort/tier. Choose from fresh observations and the goal; death does not change the active goal. Use sequence for safe linear chains needing no new facts; optional finish:{summary} verifies after success. Use ArenaScript for conditional/repeated work; for longer work, background:true may send one measured-p95 advisory near timeout so you can prepare during its run. It never chooses or dispatches an action. Use startAction to reason while one chosen action runs; handle it exactly with actionStatus/cancelAction/replaceAction, and require fresh facts before dependent actions. Answer exact program attention decisions.

Use capabilities for fields, fresh observations, and focused inspections. Omitted or unobserved facts are unknown. Query notes and receipts with queryMemory (paginate nextOffset); reuse exact noteKey only when fresh prerequisites/current targets match. Notes are hypotheses; receipts historical. Keep source metadata in comments/separate notes; noteKey executes the entire note as source. exploreFrontier returns candidates; choose a moveTo target. Use control for precise inputs and act with control_sequence for bounded tick programs. Mine observed blocks with exact blockId. goalSpec is immutable; finish requests verification. Never claim effects without evidence. conversation_only uses say. Plain text is invisible; keep speech brief; speech playback is asynchronous.`;

export const MINECRAFT_DYNAMIC_TOOLS = Object.freeze([
	tool('observe', 'Request a fresh player observation. Read freshness and coverage; an unavailable freshness barrier returns explicitly stale cached facts.', objectSchema({})),
	tool('capabilities', 'List action fields, query sections, limits, and runtime support. Request section program for the shared ArenaScript language and API reference before writing a program.', objectSchema({ section: { type: 'string', enum: ['all', 'program'] } })),
	tool('inspect', 'Request a focused page of player-accessible facts. Item queries need a slot; block queries need visible x/y/z coordinates. Read coverage and freshness.', objectSchema({
		section: { type: 'string', enum: INSPECTION_SECTIONS }, offset: integerSchema(0, 4_096), limit: integerSchema(1, 32),
		slot: integerSchema(0, 255), x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT), y: integerSchema(-2_048, 2_048), z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		afterSequence: integerSchema(0, Number.MAX_SAFE_INTEGER),
		recipeId: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
		entityType: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
		outputItemId: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
	}, ['section'])),
	tool('actionStatus', 'Inspect the active action or a retained terminal receipt without changing the player.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('cancelAction', 'Cancel the exact active handle and wait for its authoritative terminal result. A stale handle cannot cancel another action.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER) }, ['actionId', 'goalRevision'])),
	tool('replaceAction', 'Cancel the exact active handle, wait for acknowledgement, then execute your replacement. No replacement runs after uncertain cancellation.', objectSchema({
		actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER),
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' },
	}, ['actionId', 'goalRevision', 'actionType', 'arguments'])),
	tool('startAction', 'Start one action you have already chosen and return its handle immediately so you can reason while it runs. Poll actionStatus for the factual result or cancel the exact handle. This does not authorize a dependent action without fresh facts.', objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments'])),
	tool('notebook', 'Save or replace one model-written note of up to 2048 characters in this agent and world. Prefer a stable exact key for reusable routines. Keep executable ArenaScript valid; put prerequisites, current targets, outcomes, and failure conditions in comments or a separate note, and only record outcomes supported by evidence. Notes are hypotheses or plans, never authoritative game evidence.', objectSchema({ key: { type: 'string', minLength: 1, maxLength: 128 }, text: { type: 'string', minLength: 1, maxLength: 2048 } }, ['key', 'text'])),
	tool('queryMemory', 'Read this agent and world\'s saved notes and action receipts, including unresolved dispatches. Start with notes to find reusable routines and receipts to check historical outcomes; continue every page with nextOffset. Historical receipts do not establish current world state.', objectSchema({ kind: { type: 'string', enum: ['all', 'notes', 'receipts', 'unresolved'] }, text: { type: 'string', minLength: 1, maxLength: 256 }, offset: integerSchema(0, Number.MAX_SAFE_INTEGER), limit: integerSchema(1, 64) })),
	tool('runProgram', 'Run bounded ArenaScript that you author. Supply source or an exact notebook noteKey; noteKey executes the entire note text as ArenaScript, so keep metadata in comments or a separate note. After a fresh observation, use a small safe background:true routine for conditional or repeated work that can run while you reason. One recent-p95 advisory may arrive near timeout so you can prepare the next intention; it never chooses or dispatches actions or waives fresh-fact requirements. Reuse noteKey only when fresh facts confirm its recorded prerequisites and current target assumptions; replace only when they no longer fit. Optional observationIntervalMs requests fresh samples. background:true returns a program handle while your routine continues reacting during model reasoning; otherwise wait for its result. One program owns the body until it ends or cancelProgram settles. Programs expire within timeoutMs and never restart themselves.', objectSchema({ source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES }, noteKey: { type: 'string', minLength: 1, maxLength: 128 }, background: { type: 'boolean' }, observationIntervalMs: integerSchema(100, 5000), maxActions: integerSchema(1, 256), timeoutMs: integerSchema(1, 120_000) })),
	tool('programStatus', 'Read the running program, pending decision, or latest terminal result. Does not wait or change the player. Use it to inspect a background handle or exact attention; ordinary progress needs no polling. Background completion does not mean the goal is complete.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('respondProgram', 'Answer the exact pending program decision. Continue preserves authored work. Replace installs new source after releasing the old action and retains the original deadline and action budget; use it when fresh facts invalidate prerequisites or current targets. Pause and finish stop the routine; finish still requires separate factual goal verification.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER), decisionId: { type: 'string', minLength: 1, maxLength: 256 }, directive: { type: 'string', enum: ['continue', 'pause', 'replace', 'finish'] }, source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES } }, ['programId', 'goalRevision', 'decisionId', 'directive'])),
	tool('cancelProgram', 'Cancel the exact program and wait for its result. New body actions remain blocked while cancellation is unconfirmed. Handles are scoped to this agent and goal revision.', objectSchema({ programId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER) }, ['programId', 'goalRevision'])),
	tool('lookAround', 'Turn through 2 to 8 camera steps, sampling fresh facts at each heading. Returns bounded historical sightings with timestamps and omitted counts; reacquire targets before acting.', objectSchema({
		centerYaw: numberSchema(-180, 180),
		pitch: numberSchema(-90, 90),
		steps: integerSchema(2, MAX_LOOK_AROUND_STEPS),
		ticksPerStep: integerSchema(1, MAX_LOOK_AROUND_TICKS),
	}, ['centerYaw', 'pitch', 'steps', 'ticksPerStep'])),
	tool('control', 'Hold one complete player input frame for 1 to 200 server ticks. Use for precise movement, jumps, attacks, item use, view, and hotbar control.', objectSchema({
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
	}, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks'])),
	tool('moveTo', 'Navigate toward one short, confirmed waypoint through bounded loaded safe waypoints; use control for ordinary exploration.', objectSchema({
		x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: numberSchema(-2_048, 2_048),
		z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		tolerance: numberSchema(0.01, 16),
		sprint: { type: 'boolean' },
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z'])),
	tool('exploreFrontier', 'List factual observed or unknown adjacent-space candidates. This tool never chooses or executes a destination; choose explicitly with moveTo.', objectSchema({
		radius: integerSchema(8, 32),
		limit: integerSchema(1, 64),
		blockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
	})),
	tool('mine', 'Mine one in-range block with its exact current blockId. First aim at its center using act with look_at; the block must be exactly under the crosshair, not merely visible.', objectSchema({
		x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: integerSchema(-2_048, 2_048),
		z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		expectedBlockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z', 'expectedBlockId'])),
	tool('say', 'Send public chat, a private message, or nearby proximity speech.', objectSchema({
		message: { type: 'string', minLength: 1, maxLength: MAX_CHAT_LENGTH },
		audience: { type: 'string', enum: ['public', 'direct', 'proximity'] },
		recipientId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
	}, ['message'])),
	tool('wait', 'Pause briefly and wait for the body result.', objectSchema({
		durationMs: integerSchema(MIN_DURATION_MS, MAX_DURATION_MS),
	}, ['durationMs'])),
	tool('act', 'Execute one supported advanced player action. Supply exactly the required fields. For interact_block omit optional hitX/hitY/hitZ to use the actual block shape. Before pick_up_item check current inventory and use a freshly observed target UUID; nearby drops may already be collected.', objectSchema({
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES },
		arguments: { type: 'object' },
	}, ['actionType', 'arguments'])),
	tool('sequence', 'Prefer sequence for safe 2+ action chains. Execute 2 to 8 exact model-authored actions in order, stopping on the first factual failure. Optionally supply finish:{summary} to request goal verification after every action succeeds and a fresh final sample is available; failure never runs finish. Movement, mining, or pickup chains return one postAction sample after the last attempted step. Use separate calls when a later step needs fresh facts.', objectSchema({
		actions: {
			type: 'array', minItems: 2, maxItems: MAX_SEQUENCE_ACTIONS,
			items: objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments']),
		},
		finish: objectSchema({ summary: { type: 'string', minLength: 1, maxLength: 512 } }, ['summary']),
	}, ['actions'])),
	tool('finish', 'Ask Minecraft to verify the immutable active goal. Read unmet facts on failure. If AWAITING_OPERATOR_CONFIRMATION, report once with say and end this turn until new input; do not repeat the work or verification.', objectSchema({
		summary: { type: 'string', minLength: 1, maxLength: 512 },
	}, ['summary'])),
]);

export function normalizeMinecraftToolCall(name, value) {
	const args = requireObject(value);
	switch (name) {
		case 'observe':
			requireExactKeys(args, []);
			return { kind: 'observe' };
		case 'capabilities':
			requireExactKeys(args, ['section']);
			if (args.section !== undefined && !['all', 'program'].includes(args.section)) invalid('capability section is not supported');
			return { kind: 'capabilities', ...(args.section === undefined ? {} : { section: args.section }) };
		case 'inspect': {
			requireExactKeys(args, ['section', 'offset', 'limit', 'slot', 'x', 'y', 'z', 'afterSequence', 'recipeId', 'entityType', 'outputItemId']);
			if (!INSPECTION_SECTIONS.includes(args.section)) invalid('section is not supported');
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
			const action = normalizeMinecraftToolCall('act', args);
			return { ...action, kind: 'start_action' };
		}
		case 'notebook':
			requireExactKeys(args, ['key', 'text']);
			return { kind: 'notebook', key: boundedText(args.key, 'key', 128), text: boundedText(args.text, 'text', 2048) };
		case 'queryMemory':
			requireExactKeys(args, ['kind', 'text', 'limit', 'offset']);
			if (args.kind !== undefined && !['all', 'notes', 'receipts', 'unresolved'].includes(args.kind)) invalid('kind is not supported');
			return { kind: 'query_memory', memoryKind: args.kind ?? 'all', offset: optionalInteger(args.offset, 0, 'offset', 0, Number.MAX_SAFE_INTEGER), limit: optionalInteger(args.limit, 20, 'limit', 1, 64), ...(args.text === undefined ? {} : { text: boundedText(args.text, 'text', 256) }) };
		case 'runProgram': {
			requireExactKeys(args, ['source', 'noteKey', 'background', 'observationIntervalMs', 'maxActions', 'timeoutMs']);
			if ((args.source === undefined) === (args.noteKey === undefined)) invalid('Supply exactly one of source or noteKey');
			const source = args.source === undefined ? undefined : boundedText(args.source, 'source', MAX_PROGRAM_SOURCE_BYTES);
			if (source !== undefined && Buffer.byteLength(source, 'utf8') > MAX_PROGRAM_SOURCE_BYTES) invalid('source must fit 65536 UTF-8 bytes');
			return { kind: 'run_program', ...(source === undefined ? { noteKey: boundedText(args.noteKey, 'noteKey', 128) } : { source }), ...(args.background === undefined ? {} : { background: optionalBoolean(args.background, false, 'background') }), ...(args.observationIntervalMs === undefined ? {} : { observationIntervalMs: integer(args.observationIntervalMs, 'observationIntervalMs', 100, 5000) }), maxActions: optionalInteger(args.maxActions, 64, 'maxActions', 1, 256), timeoutMs: optionalInteger(args.timeoutMs, 30_000, 'timeoutMs', 1, 120_000) };
		}
		case 'programStatus':
			requireExactKeys(args, ['programId']);
			return { kind: 'program_status', ...(args.programId === undefined ? {} : { programId: boundedText(args.programId, 'programId', 128) }) };
		case 'cancelProgram':
			requireExactKeys(args, ['programId', 'goalRevision']);
			return { kind: 'cancel_program', programId: boundedText(args.programId, 'programId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		case 'respondProgram': {
			requireExactKeys(args, ['programId', 'goalRevision', 'decisionId', 'directive', 'source']);
			if (!['continue', 'pause', 'replace', 'finish'].includes(args.directive)) invalid('directive is not supported');
			if ((args.directive === 'replace') !== (args.source !== undefined)) invalid('Only replace requires source');
			const source = args.source === undefined ? undefined : boundedText(args.source, 'source', MAX_PROGRAM_SOURCE_BYTES);
			if (source !== undefined && Buffer.byteLength(source, 'utf8') > MAX_PROGRAM_SOURCE_BYTES) invalid('source must fit 65536 UTF-8 bytes');
			return { kind: 'respond_program', programId: boundedText(args.programId, 'programId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER), decisionId: boundedText(args.decisionId, 'decisionId', 256), directive: args.directive, ...(source === undefined ? {} : { source }) };
		}
		case 'lookAround':
			requireExactKeys(args, ['centerYaw', 'pitch', 'steps', 'ticksPerStep']);
			return {
				kind: 'lookAround',
				centerYaw: finiteNumber(args.centerYaw, 'centerYaw', -180, 180),
				pitch: finiteNumber(args.pitch, 'pitch', -90, 90),
				steps: integer(args.steps, 'steps', 2, MAX_LOOK_AROUND_STEPS),
				ticksPerStep: integer(args.ticksPerStep, 'ticksPerStep', 1, MAX_LOOK_AROUND_TICKS),
			};
		case 'control':
			requireExactKeys(args, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks']);
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
			return {
				kind: 'action',
				actionType: 'navigate_to',
				arguments: {
					x: finiteNumber(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: finiteNumber(args.y, 'y', -2_048, 2_048),
					z: finiteNumber(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					tolerance: optionalNumber(args.tolerance, 1, 'tolerance', 0.01, 16),
					sprint: optionalBoolean(args.sprint, true, 'sprint'),
					timeoutMs: optionalInteger(args.timeoutMs, 30_000, 'timeoutMs', 1, 120_000),
				},
			};
		case 'exploreFrontier': {
			requireExactKeys(args, ['radius', 'limit', 'blockId']);
			return {
				kind: 'explore_frontier',
				arguments: {
					radius: optionalInteger(args.radius, 24, 'radius', 8, 32),
					limit: optionalInteger(args.limit, 32, 'limit', 1, 64),
					...(args.blockId === undefined ? {} : { blockId: boundedText(args.blockId, 'blockId', MAX_IDENTIFIER_LENGTH) }),
				},
			};
		}
		case 'mine':
			requireExactKeys(args, ['x', 'y', 'z', 'expectedBlockId', 'timeoutMs']);
			try {
				const action = validateAction({
					type: 'break_block',
					x: integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: integer(args.y, 'y', -2_048, 2_048),
					z: integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					expectedBlockId: boundedText(args.expectedBlockId, 'expectedBlockId', MAX_IDENTIFIER_LENGTH),
					timeoutMs: optionalInteger(args.timeoutMs, 15_000, 'timeoutMs', 1, 120_000),
				});
				return { kind: 'action', actionType: action.type, arguments: stripActionType(action) };
			} catch (error) {
				invalid(error?.message ?? 'invalid mining arguments');
			}
			break;
		case 'say': {
			requireExactKeys(args, ['message', 'audience', 'recipientId']);
			const message = boundedText(args.message, 'message', MAX_CHAT_LENGTH);
			const recipientId = args.recipientId === undefined ? undefined : boundedText(args.recipientId, 'recipientId', MAX_IDENTIFIER_LENGTH);
			const audience = args.audience === undefined
				? recipientId === undefined ? 'public' : 'direct'
				: args.audience;
			if (!['public', 'direct', 'proximity'].includes(audience)) invalid('audience is not supported');
			if (audience === 'direct' && recipientId === undefined) invalid('direct speech requires recipientId');
			if (audience !== 'direct' && recipientId !== undefined) invalid(`${audience} speech cannot use recipientId`);
			return { kind: 'action', actionType: 'chat', arguments: audience === 'direct'
				? { message, audience, recipientId }
				: { message, audience } };
		}
		case 'wait':
			requireExactKeys(args, ['durationMs']);
			return { kind: 'action', actionType: 'wait', arguments: { durationMs: integer(args.durationMs, 'durationMs', MIN_DURATION_MS, MAX_DURATION_MS) } };
		case 'act': {
			requireExactKeys(args, ['actionType', 'arguments']);
			if (typeof args.actionType !== 'string' || !NATIVE_ACTION_TYPES.includes(args.actionType)) invalid('actionType is not supported');
			const actionArguments = requireObject(args.arguments);
			if (Object.hasOwn(actionArguments, 'type')) invalid('arguments.type is reserved; use actionType');
			if (args.actionType === 'break_block') {
				const normalized = normalizeMinecraftToolCall('mine', actionArguments);
				return { kind: 'action', actionType: normalized.actionType, arguments: normalized.arguments };
			}
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

function normalizeSequenceAction(value) {
	const action = requireObject(value);
	requireExactKeys(action, ['actionType', 'arguments']);
	if (action.actionType === 'navigate_to') {
		const normalized = normalizeMinecraftToolCall('moveTo', action.arguments);
		return { actionType: normalized.actionType, arguments: normalized.arguments };
	}
	if (action.actionType === 'break_block') {
		const normalized = normalizeMinecraftToolCall('mine', action.arguments);
		return { actionType: normalized.actionType, arguments: normalized.arguments };
	}
	const normalized = normalizeMinecraftToolCall('act', action);
	return { actionType: normalized.actionType, arguments: normalized.arguments };
}

export function toolResultContent(value, success = true) {
	let text = JSON.stringify(value ?? null);
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES) {
		const candidates = [
			...(value?.postAction?.observation === undefined || isSequenceResult(value) ? [] : [compactActionFeedback(value)]),
			...(Array.isArray(value?.entries) ? [compactInspectionResult(value)] : []),
			...(isSequenceResult(value) ? [compactSequenceResult(value)] : []),
			...(Array.isArray(value?.receipts) && typeof value?.programId === 'string' ? [compactProgramResult(value)] : []),
			compactToolResult(value),
			{ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 8), detail: 'Details exceeded the result limit. Use inspect for focused pages.' },
			{ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 2), detail: 'Details exceeded the result limit. Use inspect for focused pages.' },
			{ state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Use inspect for focused facts; omitted data is unknown.' },
		];
		for (const candidate of candidates) {
			text = JSON.stringify(candidate);
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
		observation: { inventory: { items: [] }, resultCoverage: { inventory: { retained: 0, availableInSnapshot: items.length }, omittedSections: Object.keys(value.observation ?? {}).filter(key => key !== 'inventory') } },
	};
	for (const item of items) {
		const row = Object.fromEntries(['slot', 'itemId', 'count'].filter(key => item[key] !== undefined).map(key => [key, item[key]]));
		fallback.observation.inventory.items.push(row);
		if (Buffer.byteLength(JSON.stringify(fallback), 'utf8') > budget - 64) { fallback.observation.inventory.items.pop(); break; }
	}
	fallback.observation.resultCoverage.inventory.retained = fallback.observation.inventory.items.length;
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
			inventory: { items: asToolArray(observation.inventory?.items).slice(0, 16) },
			...(observation.position === undefined ? {} : { position: observation.position }),
			...(observation.velocity === undefined ? {} : { velocity: observation.velocity }),
			...(observation.view === undefined ? {} : { view: observation.view }),
			...(observation.interaction === undefined ? {} : { interaction: compactInteraction(observation.interaction) }),
			...(observation.perception === undefined ? {} : { perception: observation.perception }),
			resultCoverage: { inventory: { retained: Math.min(asToolArray(observation.inventory?.items).length, 16), availableInSnapshot: asToolArray(observation.inventory?.items).length }, omittedSections: ['blocks', 'landmarks', 'entities', 'nearbyContainers'].filter((section) => observation[section] !== undefined) },
			...(observation.death === undefined ? {} : { death: observation.death }),
			...(observation.recovery === undefined ? {} : { recovery: compactRecovery(observation.recovery, 8) }),
			...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
			...(observation.lastResult === undefined ? {} : { lastResult: compactLastResult(observation.lastResult) }),
			...(observation.world === undefined ? {} : { world: compactWorld(observation.world) }),
			...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
			...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
		},
		...survivalFacts(value),
	};
	const sections = ['entities', 'blocks', 'landmarks', 'nearbyContainers'];
	const sources = Object.fromEntries(sections.map((section) => [section, asToolArray(observation[section])]));
	for (const section of sections) {
		compact.observation[section] = [];
		compact.observation.resultCoverage[section] = { retained: 0, availableInSnapshot: sources[section].length, detailsOmitted: true };
	}
	// Share the remaining budget across kinds of visible facts before adding more of any one kind.
	for (let index = 0; index < 32; index++) for (const section of sections) {
		if (index >= sources[section].length || compact.observation[section].length !== index) continue;
		const entry = sources[section][index];
		const fields = ['uuid', 'stableId', 'type', 'name', 'position', 'x', 'y', 'z', 'distance', 'blockId', 'itemId', 'count', 'velocity', 'bounds', 'pickable', 'parentId', 'partName', 'state', 'bearing', 'elevation'];
		const row = Object.fromEntries(fields.filter((field) => entry[field] !== undefined).map((field) => [field, entry[field]]));
		compact.observation[section].push(row);
		if (Buffer.byteLength(JSON.stringify(compact), 'utf8') > budget - 256) compact.observation[section].pop();
		compact.observation.resultCoverage[section].retained = compact.observation[section].length;
	}
	compact.observation.resultCoverage.omittedSections = sections.filter((section) => sources[section].length > 0 && compact.observation[section].length === 0);
	return compact;
}

function resultMetadata(value) {
	if (value === null || typeof value !== 'object') return {};
	return Object.fromEntries(['actionId', 'goalRevision', 'eventSequence', 'freshness', 'coverage', 'revision', 'sectionRevisions', 'observedAtEpochMs', 'executionSettings', 'unresolvedActions'].filter((key) => value[key] !== undefined).map((key) => [key, value[key]]));
}

function compactInteraction(interaction) {
	if (interaction === null || typeof interaction !== 'object') return interaction;
	const menu = interaction.menu;
	return { ...interaction, ...(menu == null ? {} : { menu: { ...menu, ...(Array.isArray(menu.slots) ? { slots: menu.slots.slice(0, 8), resultCoverage: { retained: Math.min(menu.slots.length, 8), availableInSnapshot: menu.slots.length } } : {}) } }) };
}

function compactInspectionResult(value) {
	const result = { ...value, entries: [], truncated: true, detail: 'Inspection entries exceeded the result limit; continue at nextOffset.', coverage: { ...value.coverage, resultTruncated: true } };
	for (const entry of value.entries) {
		const candidate = { ...result, entries: [...result.entries, entry] };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) break;
		result.entries.push(entry);
	}
	const offset = Number.isSafeInteger(value.offset) ? value.offset : Number.isSafeInteger(value.coverage?.offset) ? value.coverage.offset : 0;
	result.nextOffset = offset + result.entries.length;
	result.coverage.returned = result.entries.length;
	result.coverage.nextOffset = result.nextOffset;
	result.coverage.complete = false;
	if (result.entries.length === 0) {
		result.reasonCode = 'ENTRY_EXCEEDS_RESULT_LIMIT';
		result.detail = 'One inspection entry exceeds the result limit. Its contents remain unknown.';
		result.nextOffset = null;
		result.coverage.nextOffset = null;
	}
	return result;
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
	const result = { state: boundedResultField(value.state, 64), reasonCode: boundedResultField(value.reasonCode, 128), programId: boundedResultField(value.programId, 256), actions: safeResultInteger(value.actions), eventSequence: safeResultInteger(value.eventSequence), ...(value.finishRequested === true ? { finishRequested: true } : {}), receipts: [], truncated: true, detail: 'Program observations were omitted. Query historical receipts by bodyActionId and observe for current facts.' };
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

function finiteNumber(value, field, minimum, maximum) {
	if (!Number.isFinite(value) || value < minimum || value > maximum) invalid(`${field} must be between ${minimum} and ${maximum}`);
	return value;
}

function integer(value, field, minimum, maximum) {
	if (!Number.isSafeInteger(value)) invalid(`${field} must be an integer`);
	return finiteNumber(value, field, minimum, maximum);
}

function optionalNumber(value, fallback, field, minimum, maximum) {
	return value === undefined ? fallback : finiteNumber(value, field, minimum, maximum);
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
