const PROVIDERS = new Set(['codex', 'gemini', 'kimi']);
const MAX_TIMEOUT_MS = 900_000;
const MAX_DIAGNOSTICS = 4096;
const MAX_TEXT = 4096;
const MAX_ASSERTION_ARGS = 8192;
const SCENARIO_KEYS = new Set(['id', 'provider', 'model', 'reasoningEffort', 'serviceTier', 'task', 'timeoutMs', 'assert', 'assertions']);
const ASSERTION_KEYS = {
	lifecycle: new Set(['type', 'state']),
	chat: new Set(['type', 'message']),
	action: new Set(['type', 'actionType', 'args']),
	program: new Set(['type', 'event', 'status']),
	rcon: new Set(['type', 'command', 'match']),
};
const LIFECYCLE_STATES = new Set(['COMPLETED', 'ERROR', 'DEAD']);

function freeze(value) {
	if (value && typeof value === 'object' && !Object.isFrozen(value)) {
		Object.freeze(value);
		for (const child of Object.values(value)) freeze(child);
	}
	return value;
}

function text(value, field) {
	if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${field} must be nonblank text`);
	if (value.length > MAX_TEXT) throw new RangeError(`${field} exceeds bounded length`);
	return value.trim();
}

function exactKeys(value, allowed, field) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${field} must be an object`);
	for (const key of Object.keys(value)) if (!allowed.has(key)) throw new TypeError(`${field} has unknown key '${key}'`);
}

function normalizeAssertion(value, index) {
	const field = `assert[${index}]`;
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${field} must be an object`);
	const type = text(value.type, `${field}.type`);
	const allowed = ASSERTION_KEYS[type];
	if (!allowed) throw new TypeError(`${field} has unknown assertion type`);
	exactKeys(value, allowed, field);
	const result = { type };
	if (type === 'lifecycle') {
		const state = text(value.state, `${field}.state`).toUpperCase();
		if (!LIFECYCLE_STATES.has(state)) throw new TypeError(`${field}.state is unsupported`);
		result.state = state;
	} else if (type === 'chat') result.message = text(value.message, `${field}.message`);
	else if (type === 'action') {
		result.actionType = text(value.actionType, `${field}.actionType`);
		if (value.args !== undefined) {
			if (!value.args || typeof value.args !== 'object' || Array.isArray(value.args)) throw new TypeError(`${field}.args must be an object`);
			result.args = structuredClone(value.args);
			if (JSON.stringify(result.args).length > MAX_ASSERTION_ARGS) throw new RangeError(`${field}.args exceeds bounded length`);
		}
	} else if (type === 'program') {
		result.event = text(value.event, `${field}.event`);
		if (value.status !== undefined) result.status = text(value.status, `${field}.status`);
	} else {
		result.command = text(value.command, `${field}.command`);
		result.match = text(value.match, `${field}.match`);
	}
	return freeze(result);
}

export function normalizeHeadlessScenario(value, index = 0) {
	exactKeys(value, SCENARIO_KEYS, `scenarios[${index}]`);
	const assertions = value.assert ?? value.assertions;
	if (value.assert !== undefined && value.assertions !== undefined) throw new TypeError('use only assert or assertions');
	if (!Array.isArray(assertions) || assertions.length === 0) throw new TypeError('scenario requires one or more assertions');
	const timeoutMs = value.timeoutMs;
	if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) throw new RangeError('timeoutMs must be between 1 and 900000');
	const scenario = {
		id: text(value.id, `scenarios[${index}].id`), provider: text(value.provider, `scenarios[${index}].provider`),
		model: text(value.model, `scenarios[${index}].model`), reasoningEffort: text(value.reasoningEffort, `scenarios[${index}].reasoningEffort`),
		serviceTier: text(value.serviceTier ?? 'priority', `scenarios[${index}].serviceTier`), task: text(value.task, `scenarios[${index}].task`), timeoutMs,
		assertions: assertions.map(normalizeAssertion),
	};
	if (!PROVIDERS.has(scenario.provider)) throw new TypeError(`unsupported provider '${scenario.provider}'`);
	return freeze(scenario);
}

export function normalizeHeadlessMatrix(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('matrix must be an object');
	exactKeys(value, new Set(['version', 'scenarios']), 'matrix');
	if (value.version !== 1) throw new TypeError('matrix version must be 1');
	if (!Array.isArray(value.scenarios) || value.scenarios.length === 0) throw new TypeError('matrix requires scenarios');
	const scenarios = value.scenarios.map(normalizeHeadlessScenario);
	const ids = new Set();
	for (const scenario of scenarios) { if (ids.has(scenario.id)) throw new TypeError(`duplicate scenario ID '${scenario.id}'`); ids.add(scenario.id); }
	return freeze({ version: 1, scenarios });
}

export function selectHeadlessScenarios(matrix, selector) {
	if (selector === null || selector === undefined) return matrix.scenarios.slice();
	text(selector, 'selector');
	const scenario = matrix.scenarios.find((entry) => entry.id === selector);
	if (!scenario) throw new RangeError(`unknown scenario ID '${selector}'`);
	return [scenario];
}

export function scenarioReport(status, scenario, fields = {}) {
	const normalizedStatus = text(status, 'status').toUpperCase();
	const report = { status: normalizedStatus, scenarioId: scenario.id, profile: { provider: scenario.provider, model: scenario.model, reasoningEffort: scenario.reasoningEffort, serviceTier: scenario.serviceTier } };
	for (const [key, value] of Object.entries(fields)) {
		if (key === 'diagnostics' || key === 'error') report[key] = String(value).slice(0, MAX_DIAGNOSTICS);
		else report[key] = boundReportValue(value);
	}
	return freeze(report);
}

function boundReportValue(value, depth = 0) {
	if (depth > 6) return '[TRUNCATED]';
	if (value === null || typeof value !== 'object') return typeof value === 'string' ? value.slice(0, MAX_DIAGNOSTICS) : value;
	if (Array.isArray(value)) return value.slice(0, 64).map((entry) => boundReportValue(entry, depth + 1));
	return Object.fromEntries(Object.entries(value).slice(0, 64).map(([key, entry]) => [key.slice(0, 128), boundReportValue(entry, depth + 1)]));
}
