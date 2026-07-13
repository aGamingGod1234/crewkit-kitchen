import { MAX_SUMMARY_LENGTH } from './constants.mjs';
import { validateAction, ValidationError } from './schema.mjs';

const GOAL_STATUSES = new Set(['in_progress', 'completed', 'impossible']);
const DECISION_KEYS = new Set(['summary', 'goalStatus', 'action']);

export class DecisionError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'DecisionError';
		this.code = code;
	}
}

export function parseDecision(text) {
	if (typeof text !== 'string' || text.trim().length === 0) throw new DecisionError('EMPTY_DECISION', 'Planner decision must not be empty');
	const json = unwrapExactJson(text.trim());
	let value;
	try {
		value = JSON.parse(json);
	} catch (error) {
		throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object', { cause: error });
	}
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new DecisionError('INVALID_DECISION', 'Planner decision must be a JSON object');
	for (const key of Object.keys(value)) if (!DECISION_KEYS.has(key)) throw new DecisionError('UNKNOWN_DECISION_FIELD', `Unknown decision field '${key}'`);
	for (const key of DECISION_KEYS) if (!Object.hasOwn(value, key)) throw new DecisionError('MISSING_DECISION_FIELD', `Decision field '${key}' is required`);
	if (typeof value.summary !== 'string' || value.summary.trim().length === 0 || value.summary.length > MAX_SUMMARY_LENGTH) throw new DecisionError('INVALID_DECISION', `Decision summary must be nonblank and at most ${MAX_SUMMARY_LENGTH} characters`);
	if (!GOAL_STATUSES.has(value.goalStatus)) throw new DecisionError('INVALID_DECISION', `Unsupported goalStatus '${String(value.goalStatus)}'`);
	let action;
	try {
		action = validateAction(value.action);
	} catch (error) {
		if (error instanceof ValidationError) throw new DecisionError('INVALID_ACTION', error.message, { cause: error });
		throw error;
	}
	const terminal = value.goalStatus !== 'in_progress';
	if (terminal !== (action.type === 'complete_goal')) throw new DecisionError('STATUS_ACTION_MISMATCH', 'completed or impossible status must use complete_goal, and in_progress must not');
	return { summary: value.summary, goalStatus: value.goalStatus, action };
}

function unwrapExactJson(text) {
	if (text.startsWith('```')) {
		const match = /^```(?:json)?\s*\r?\n([\s\S]*?)\r?\n```$/i.exec(text);
		if (!match) throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object in an optional JSON fence');
		return match[1].trim();
	}
	if (!text.startsWith('{') || !text.endsWith('}')) throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object');
	return text;
}
