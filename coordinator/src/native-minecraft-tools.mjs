import {
	ACTION_FIELDS,
	MAX_CHAT_LENGTH,
	MAX_DURATION_MS,
	MAX_IDENTIFIER_LENGTH,
	MIN_DURATION_MS,
} from './constants.mjs';
import { validateAction } from './schema.mjs';

const MAX_TOOL_RESULT_BYTES = 16_384;
const COORDINATE_LIMIT = 30_000_000;
const MAX_SEQUENCE_ACTIONS = 8;
const MAX_LOOK_AROUND_STEPS = 8;
const MAX_LOOK_AROUND_TICKS = 20;
const NATIVE_ACTION_TYPES = Object.freeze(Object.keys(ACTION_FIELDS));

export const NATIVE_AGENT_INSTRUCTIONS = `You control one live Minecraft player and choose every action.

Act as soon as it is safe. Do not wait to solve the whole goal or narrate a plan. Call the smallest useful tool, inspect its factual result, then choose the next. Keep decisions brief even with high reasoning.

Use observe when facts are missing or stale. goalSpec is Minecraft's immutable completion contract. observe includes close-up interactable blocks and sparse first-visible landmarks out to the loaded view distance; landmarks are guidance, so walk/look at them and re-observe before mining. Use lookAround for a bounded camera sweep when the current view misses useful terrain. Use control for normal exploration and traversal; use moveTo only for a short confirmed waypoint. Mine only an observed, visible, in-range block: copy exact rayTarget coordinates and non-air blockId into expectedBlockId. If it is not under the crosshair, use act/look_at, observe, then mine. Use sequence for 2+ safe actions with factual arguments, keep it short, split when later arguments depend on results, and never batch speculative navigation or combat. It stops on the first failure. Use act for supported actions. finish only asks Minecraft to verify the goal. Never claim an action unless its result confirms it. conversation_only: use say, with no physical action or finish. Plain text is not visible. For nearby voice, say at most 12 words with proximity, then call the first physical tool because speech playback is asynchronous.`;

export const MINECRAFT_DYNAMIC_TOOLS = Object.freeze([
	tool('observe', 'Return the latest compact player, inventory, close-up block, farther visible landmark, entity, goal, and conversation facts.', objectSchema({})),
	tool('lookAround', 'Turn the player through 2 to 8 short camera steps; call observe afterward to inspect the newly visible landmarks.', objectSchema({
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
	tool('mine', 'Mine one observed, visible, in-range block coordinate with its exact current blockId.', objectSchema({
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
	tool('act', 'Execute one supported advanced player action. Supply exactly the fields required by that actionType.', objectSchema({
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES },
		arguments: { type: 'object' },
	}, ['actionType', 'arguments'])),
	tool('sequence', 'Prefer sequence for safe 2+ action chains. Execute 2 to 8 exact model-authored actions in order, stopping on the first factual failure; use separate calls when a later step needs fresh facts.', objectSchema({
		actions: {
			type: 'array', minItems: 2, maxItems: MAX_SEQUENCE_ACTIONS,
			items: objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments']),
		},
	}, ['actions'])),
	tool('finish', 'Ask Minecraft to verify the immutable active goal. A failed check keeps the goal active.', objectSchema({
		summary: { type: 'string', minLength: 1, maxLength: 512 },
	}, ['summary'])),
]);

export function normalizeMinecraftToolCall(name, value) {
	const args = requireObject(value);
	switch (name) {
		case 'observe':
			requireExactKeys(args, []);
			return { kind: 'observe' };
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
		case 'mine':
			requireExactKeys(args, ['x', 'y', 'z', 'expectedBlockId', 'timeoutMs']);
			return {
				kind: 'action',
				actionType: 'break_block',
				arguments: {
					x: integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: integer(args.y, 'y', -2_048, 2_048),
					z: integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					expectedBlockId: boundedText(args.expectedBlockId, 'expectedBlockId', MAX_IDENTIFIER_LENGTH),
					timeoutMs: optionalInteger(args.timeoutMs, 15_000, 'timeoutMs', 1, 120_000),
				},
			};
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
			if (args.actionType === 'break_block') {
				const normalized = normalizeMinecraftToolCall('mine', actionArguments);
				return { kind: 'action', actionType: normalized.actionType, arguments: normalized.arguments };
			}
			try {
				const normalizedArguments = stripActionType(validateAction({ type: args.actionType, ...actionArguments }));
				return { kind: 'action', actionType: args.actionType, arguments: normalizedArguments };
			} catch (error) {
				invalid(error?.message ?? 'invalid action arguments');
			}
			break;
		}
		case 'sequence': {
			requireExactKeys(args, ['actions']);
			if (!Array.isArray(args.actions) || args.actions.length < 2 || args.actions.length > MAX_SEQUENCE_ACTIONS) {
				invalid(`actions must contain 2 to ${MAX_SEQUENCE_ACTIONS} entries`);
			}
			return {
				kind: 'sequence',
				actions: args.actions.map(normalizeSequenceAction),
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
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES && isSequenceResult(value)) {
		text = JSON.stringify(compactSequenceResult(value));
	}
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES) {
		text = JSON.stringify({ state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' });
	}
	return { success, contentItems: [{ type: 'inputText', text }] };
}

function isSequenceResult(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value) && Array.isArray(value.results);
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
	let omittedObservations = sourceResults.length !== value.results.length;
	for (let index = 0; index < sourceResults.length; index += 1) {
		const observation = sourceResults[index]?.actionObservation;
		if (observation === undefined) continue;
		const candidate = { ...result, results: result.results.map((step, stepIndex) => stepIndex === index ? { ...step, actionObservation: observation } : step) };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') <= MAX_TOOL_RESULT_BYTES) result.results[index] = candidate.results[index];
		else omittedObservations = true;
	}
	if (omittedObservations) result.detail = 'Some per-action observations were omitted by the coordinator result limit; call observe for fresh compact facts.';
	return result;
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
