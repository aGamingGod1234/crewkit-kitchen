import {
	ACTION_FIELDS,
	MAX_BUILD_SEQUENCE_PLACEMENTS,
	MAX_CHAT_LENGTH,
	MAX_DURATION_MS,
	MAX_IDENTIFIER_LENGTH,
	MAX_TARGET_SELECTOR_LENGTH,
	MIN_DURATION_MS,
} from './constants.mjs';
import { validateAction } from './schema.mjs';

const MAX_TOOL_RESULT_BYTES = 16_384;
const COORDINATE_LIMIT = 30_000_000;
const MAX_SEQUENCE_ACTIONS = 8;
const COMPOSITE_ACTION_FIELDS = Object.freeze({
	build_sequence: Object.freeze(['placements', 'timeoutMs']),
	fight_target: Object.freeze(['targetSelector', 'desiredRange', 'timeoutMs']),
	flee_from: Object.freeze(['targetSelector', 'distance', 'timeoutMs']),
	follow_entity: Object.freeze(['targetSelector', 'distance', 'timeoutMs']),
});
const NATIVE_ACTION_TYPES = Object.freeze([...Object.keys(ACTION_FIELDS), ...Object.keys(COMPOSITE_ACTION_FIELDS)]);

export const NATIVE_AGENT_INSTRUCTIONS = `You control one live Minecraft player. You are the only brain choosing what it does.

Act as soon as it is safe. Do not wait to solve the whole goal and do not narrate a plan. Call the smallest useful Minecraft tool now, inspect its factual result, then choose the next tool. Keep each decision local and brief even when your configured reasoning effort is high.

Use observe only when the latest event and tool results lack needed facts. Use moveTo, mine, say, and wait for common operations. Use act for another supported player action. Use sequence for a short exact chain you can choose now; it stops on the first failed action. Call finish only to ask Minecraft to verify the immutable active goal. Minecraft decides whether the goal is complete and returns expected and observed facts. If verification fails, use those facts and continue working. Never claim an action happened unless its tool result confirms it. Plain assistant text is not visible in Minecraft, so communicate through say. For nearby voice, say at most 12 words with audience proximity, then immediately call the first physical tool because speech playback is asynchronous.`;

export const MINECRAFT_DYNAMIC_TOOLS = Object.freeze([
	tool('observe', 'Return the latest compact player, inventory, nearby block, entity, goal, and conversation facts.', objectSchema({})),
	tool('moveTo', 'Navigate the player to one coordinate and wait for the body result.', objectSchema({
		x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: numberSchema(-2_048, 2_048),
		z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		tolerance: numberSchema(0.01, 16),
		sprint: { type: 'boolean' },
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z'])),
	tool('mine', 'Mine one known block coordinate and wait for the body result.', objectSchema({
		x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: integerSchema(-2_048, 2_048),
		z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z'])),
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
	tool('sequence', 'Execute 2 to 8 exact actions in order, stopping on the first factual failure.', objectSchema({
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
			requireExactKeys(args, ['x', 'y', 'z', 'timeoutMs']);
			return {
				kind: 'action',
				actionType: 'break_block',
				arguments: {
					x: integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: integer(args.y, 'y', -2_048, 2_048),
					z: integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
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
			try {
				const normalizedArguments = Object.hasOwn(ACTION_FIELDS, args.actionType)
					? stripActionType(validateAction({ type: args.actionType, ...actionArguments }))
					: validateCompositeAction(args.actionType, actionArguments);
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
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES) {
		text = JSON.stringify({ state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' });
	}
	return { success, contentItems: [{ type: 'inputText', text }] };
}

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

function validateCompositeAction(type, value) {
	requireExactKeys(value, COMPOSITE_ACTION_FIELDS[type]);
	switch (type) {
		case 'build_sequence': {
			if (!Array.isArray(value.placements) || value.placements.length < 1 || value.placements.length > MAX_BUILD_SEQUENCE_PLACEMENTS) {
				invalid(`placements must contain 1 to ${MAX_BUILD_SEQUENCE_PLACEMENTS} entries`);
			}
			const placements = value.placements.map((placement) => stripActionType(validateAction({ type: 'place_block', ...requireObject(placement) })));
			return { placements, timeoutMs: integer(value.timeoutMs, 'timeoutMs', MIN_DURATION_MS, MAX_DURATION_MS) };
		}
		case 'fight_target':
			return {
				targetSelector: boundedText(value.targetSelector, 'targetSelector', MAX_TARGET_SELECTOR_LENGTH),
				desiredRange: finiteNumber(value.desiredRange, 'desiredRange', 1, 6),
				timeoutMs: integer(value.timeoutMs, 'timeoutMs', MIN_DURATION_MS, MAX_DURATION_MS),
			};
		case 'flee_from':
		case 'follow_entity':
			return {
				targetSelector: boundedText(value.targetSelector, 'targetSelector', MAX_TARGET_SELECTOR_LENGTH),
				distance: finiteNumber(value.distance, 'distance', 1, 64),
				timeoutMs: integer(value.timeoutMs, 'timeoutMs', MIN_DURATION_MS, MAX_DURATION_MS),
			};
		default:
			invalid('actionType is not supported');
	}
}

function stripActionType(action) {
	const { type: _type, ...argumentsValue } = action;
	return argumentsValue;
}

function invalid(message) { throw codedError('INVALID_MINECRAFT_TOOL_ARGUMENTS', message); }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
