import { MAX_SUMMARY_LENGTH } from './constants.mjs';

const DIRECTIVES = new Set(['replace', 'continue', 'pause', 'finish']);
const FINISH_STATUSES = new Set(['completed', 'impossible']);
const DECISION_KEYS = new Set(['summary', 'directive', 'source', 'status']);
const MAX_SOURCE_LENGTH = 65_536;

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
	for (const key of ['summary', 'directive']) if (!Object.hasOwn(value, key)) throw new DecisionError('MISSING_DECISION_FIELD', `Decision field '${key}' is required`);
	if (typeof value.summary !== 'string' || value.summary.trim().length === 0 || value.summary.length > MAX_SUMMARY_LENGTH) throw new DecisionError('INVALID_DECISION', `Decision summary must be nonblank and at most ${MAX_SUMMARY_LENGTH} characters`);
	if (!DIRECTIVES.has(value.directive)) throw new DecisionError('INVALID_DECISION', `Unsupported directive '${String(value.directive)}'`);

	if (value.directive === 'replace') {
		if (!Object.hasOwn(value, 'source') || typeof value.source !== 'string' || value.source.trim().length === 0 || value.source.length > MAX_SOURCE_LENGTH) {
			throw new DecisionError('DECISION_FIELD_MISMATCH', `replace directive requires nonblank source of at most ${MAX_SOURCE_LENGTH} characters`);
		}
		if (Object.hasOwn(value, 'status')) throw new DecisionError('DECISION_FIELD_MISMATCH', 'replace directive must not include status');
		return { summary: value.summary, directive: 'replace', source: value.source };
	}

	if (value.directive === 'finish') {
		if (!Object.hasOwn(value, 'status') || !FINISH_STATUSES.has(value.status)) throw new DecisionError('DECISION_FIELD_MISMATCH', 'finish directive requires status completed or impossible');
		if (Object.hasOwn(value, 'source')) throw new DecisionError('DECISION_FIELD_MISMATCH', 'finish directive must not include source');
		return { summary: value.summary, directive: 'finish', status: value.status };
	}

	if (Object.hasOwn(value, 'source')) throw new DecisionError('DECISION_FIELD_MISMATCH', `${value.directive} directive must not include source`);
	if (Object.hasOwn(value, 'status')) throw new DecisionError('DECISION_FIELD_MISMATCH', `${value.directive} directive must not include status`);
	return { summary: value.summary, directive: value.directive };
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
