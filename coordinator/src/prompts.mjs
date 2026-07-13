import { ACTION_FIELDS, BLOCK_FACES } from './constants.mjs';

const ACTION_DESCRIPTIONS = Object.freeze([
	'move_to(x, y, z, tolerance, sprint)',
	'look_at(x, y, z)',
	'attack(targetSelector, timeoutMs)',
	'select_item(itemId)',
	'use_item(durationMs)',
	'break_block(x, y, z, timeoutMs)',
	`place_block(x, y, z, face, itemId), where face is one of ${BLOCK_FACES.join(', ')}`,
	'chat(message)',
	'wait(durationMs)',
	'complete_goal(summary)',
]);

export const PLANNER_SYSTEM_PROMPT = `You are the strategic planner for one Minecraft player.
Advance the supplied goal proactively using only facts in the supplied state. Never invent blocks,
entities, inventory, coordinates, action results, or capabilities. Choose exactly one safe macro
action from this allowlist:
${ACTION_DESCRIPTIONS.map((description) => `- ${description}`).join('\n')}

Do not use shell, filesystem, browser, or computer tools. Do not ask for such tools. Do not emit
commands, source code, Markdown explanation, or prose outside the decision object. The runtime,
not you, executes the selected Minecraft action. On failure, use the latest result to recover or
choose a materially different action. Use complete_goal only when the goal is completed or is
impossible under the observed facts.

Return exactly one JSON object with these keys and no others:
{"summary":"short rationale","goalStatus":"in_progress|completed|impossible","action":{...}}
The action object must use exactly the fields of one allowlisted action.`;

export const PLANNER_OUTPUT_SCHEMA = Object.freeze({
	type: 'object',
	additionalProperties: false,
	required: ['summary', 'goalStatus', 'action'],
	properties: {
		summary: { type: 'string', minLength: 1, maxLength: 2_048 },
		goalStatus: { type: 'string', enum: ['in_progress', 'completed', 'impossible'] },
		action: {
			oneOf: Object.entries(ACTION_FIELDS).map(([type, fields]) => ({
				type: 'object',
				additionalProperties: false,
				required: ['type', ...fields],
				properties: actionProperties(type),
			})),
		},
	},
});

export function buildPlannerInput(state) {
	if (state === null || typeof state !== 'object' || Array.isArray(state)) throw new TypeError('planner state must be an object');
	return `Minecraft planner state (authoritative JSON):\n${JSON.stringify(state)}`;
}

function actionProperties(type) {
	const number = { type: 'number' };
	const integer = { type: 'integer' };
	const properties = { type: { const: type } };
	for (const field of ACTION_FIELDS[type]) {
		properties[field] = field === 'sprint'
			? { type: 'boolean' }
			: ['x', 'y', 'z'].includes(field)
				? (['break_block', 'place_block'].includes(type) ? integer : number)
				: ['timeoutMs', 'durationMs'].includes(field)
					? { type: 'integer', minimum: 1, maximum: 600_000 }
					: field === 'tolerance'
						? { type: 'number', minimum: 0.01, maximum: 16 }
						: field === 'face'
							? { type: 'string', enum: [...BLOCK_FACES] }
							: { type: 'string', minLength: 1 };
	}
	return properties;
}
