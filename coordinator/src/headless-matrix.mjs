import path from 'node:path';
import { mkdir as defaultMkdir, open as defaultOpen, readFile as defaultReadFile, writeFile as defaultWriteFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { HeadlessRconClient } from './headless-rcon.mjs';
import { redact as redactTrace } from './trace-writer.mjs';

const PROVIDERS = new Set(['codex', 'gemini', 'kimi']);
const MAX_TIMEOUT_MS = 900_000;
const MAX_DIAGNOSTICS = 4096;
const MAX_TEXT = 4096;
const MAX_ASSERTION_ARGS = 8192;
const MAX_EVIDENCE_BYTES = 16_384;
const MAX_SCENARIOS = 16;
const MAX_MATRIX_REPORT_BYTES = 262_144;
const POLL_INTERVAL_MS = 50;
const SENSITIVE_REPORT_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|launcherAccount|accountData|token|credential|oauth)/i;
const REPORT_SECRET_TEXT = /((?:bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)\s*[:=]\s*)([^\s,;)}\]"']+)/gi;
const REPORT_BEARER_TEXT = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const REPORT_QUOTED_SECRET_KEY = /(["'])(?:[A-Za-z0-9_-]*(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]*)\1\s*:\s*(["'])/gi;
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
	if (typeof value?.id !== 'string' || /[\\/\u0000-\u001f\u007f]/.test(value.id) || value.id.includes('..')) throw new TypeError(`scenarios[${index}].id must be a safe path segment`);
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
		if (key === 'status') continue;
		if (key === 'diagnostics' || key === 'error') report[key] = redactReportText(value, MAX_DIAGNOSTICS);
		else report[key] = boundReportValue(value);
	}
	return freeze(report);
}

function boundReportValue(value, depth = 0) {
	if (depth > 6) return '[TRUNCATED]';
	if (value === null || typeof value !== 'object') return typeof value === 'string' ? redactReportText(value, MAX_DIAGNOSTICS) : value;
	if (Array.isArray(value)) return value.slice(0, 64).map((entry) => boundReportValue(entry, depth + 1));
	return Object.fromEntries(Object.entries(value).slice(0, 64).map(([key, entry]) => [key.slice(0, 128), SENSITIVE_REPORT_KEY.test(key) ? '[REDACTED]' : boundReportValue(entry, depth + 1)]));
}

/** Evaluate only evidence that was observed from the server/protocol path. */
export function evaluateHeadlessAssertions(assertions, evidence = {}) {
	if (!Array.isArray(assertions)) throw new TypeError('assertions must be an array');
	const results = assertions.map((assertion, index) => evaluateAssertion(assertion, evidence, index));
	return { passed: results.every((result) => result.passed), results };
}

/** Drive one summoned agent through the normal RCON and protocol lifecycle. */
export async function runHeadlessScenario({
	scenario,
	runDirectory,
	rcon,
	now = Date.now,
	readFile = defaultReadFile,
	readTail = null,
	writeFile = defaultWriteFile,
	protocolAudit = null,
	providerTurnsPath = null,
	providerTurnRecorder = null,
	poll = defaultPoll,
} = {}) {
	if (!scenario || typeof scenario !== 'object') throw new TypeError('scenario must be an object');
	if (!rcon || typeof rcon.command !== 'function') throw new TypeError('rcon.command must be a function');
	if (typeof now !== 'function' || typeof readFile !== 'function' || typeof writeFile !== 'function' || typeof poll !== 'function') throw new TypeError('runner dependencies must be functions');
	const directory = normalizeRunDirectory(runDirectory);
	const assertions = scenario.assertions ?? scenario.assert ?? [];
	validateRunnerScenario(scenario, assertions);
	const profile = {
		provider: boundedScalar(scenario.provider), model: boundedScalar(scenario.model),
		reasoningEffort: boundedScalar(scenario.reasoningEffort), serviceTier: boundedScalar(scenario.serviceTier),
	};
	const base = { status: 'FAILED', scenarioId: boundedScalar(scenario.id), profile };
	if (scenario.skip === true || scenario.skipped === true || scenario.profileAvailable === false) {
		let cleanup = { status: 'NOT_REQUIRED' };
		try { await closeResources(rcon, providerTurnRecorder); } catch (error) { cleanup = { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) }; }
		const report = scenarioReport(cleanup.status === 'FAILED' ? 'FAILED' : 'SKIPPED', scenario, { classification: cleanup.status === 'FAILED' ? 'CLEANUP_FAILURE' : 'SKIPPED_PROFILE', reason: scenario.skipReason ?? scenario.reason ?? 'provider profile unavailable', cleanup });
		return await persistReportOrFailure(scenario, report, directory, writeFile);
	}
	const generatedName = generatedAgentName(scenario, now);
	const commands = [];
	const rconEvidence = [];
	const startedAt = Number(now());
	if (!Number.isFinite(startedAt)) throw new TypeError('now must return a finite number');
	const timeoutMs = scenario.timeoutMs;
	const deadline = startedAt + timeoutMs;
	const tailReader = readTail ?? (readFile === defaultReadFile
		? defaultReadTail
		: async (file, limit) => boundedTailText(await readFile(file, 'utf8'), limit));
	let terminalState = null;
	let classification = null;
	let diagnostics = '';
	let closed = false;
	const command = async (value, { readOnly = false, deadlineMs = deadline, attempt = 0 } = {}) => {
		const commandText = String(value);
		if (readOnly && !isReadOnlyRcon(commandText)) throw new Error(`RCON assertion command is not read-only: ${commandText}`);
		commands.push(commandText);
		const result = await withDeadline(() => rcon.command(commandText), deadlineMs, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT');
		const textValue = boundedText(result?.text ?? result, MAX_EVIDENCE_BYTES);
		if (readOnly) rconEvidence.push({ command: commandText, text: textValue });
		return { result, text: textValue };
	};
	try {
		const summon = await command(`execute positioned 0 64 0 run codex summon-configured ${scenario.provider} ${scenario.model} ${scenario.reasoningEffort} ${scenario.serviceTier ?? 'priority'} survival ${generatedName}`);
		if (isSkippedResponse(summon.text)) classification = 'SKIPPED_PROFILE';
		if (classification === null && isFailedResponse(summon.text)) {
			classification = 'ERROR';
			diagnostics = summon.text;
		}
		if (classification === null) {
			if (!isAcceptedResponse(summon.text)) await withDeadline(() => poll({ phase: 'summon', attempt: 0, deadline, response: summon.text, now }), deadline, () => logicalNow(now, startedAt, 0), 'HEADLESS_TIMEOUT');
			await command(`codex start ${generatedName} ${scenario.task}`, { attempt: 0 });
			let attempts = 0;
			while (terminalState === null) {
				if (logicalNow(now, startedAt, attempts) >= deadline) { classification = 'TIMEOUT'; break; }
				let status;
				try { status = await command(`codex status ${generatedName}`, { attempt: attempts }); }
				catch (error) {
					if (error?.code === 'HEADLESS_TIMEOUT') { classification = 'TIMEOUT'; break; }
					throw error;
				}
				const parsedState = parseLifecycle(status.text);
				terminalState = LIFECYCLE_STATES.has(parsedState) ? parsedState : null;
				if (terminalState !== null) break;
				try {
					await withDeadline(() => poll({ phase: 'status', attempt: attempts, deadline, status: status.text, readStatus: () => command(`codex status ${generatedName}`, { attempt: attempts }), now }), deadline, () => logicalNow(now, startedAt, attempts), 'HEADLESS_TIMEOUT');
				} catch (error) {
					if (error?.code === 'HEADLESS_TIMEOUT') { classification = 'TIMEOUT'; break; }
					throw error;
				}
				attempts += 1;
			}
			if (terminalState === null && classification === null) classification = 'TIMEOUT';
		}
		if (classification === null && terminalState === 'ERROR') classification = 'ERROR';
		if (classification === null && terminalState === 'DEAD') classification = 'DEAD';
		if (classification === null && terminalState === null) classification = 'TIMEOUT';
		const evidenceResult = await collectHeadlessEvidence({
			directory, readFile, tailReader, protocolAudit, providerTurnsPath, assertions, terminalState, rconEvidence,
			readOnlyCommand: (value) => command(value, { readOnly: true, attempt: 0 }),
			deadline, now, startedAt, poll,
		});
		const { fileEvidence, evidence, assertionResult } = evidenceResult;
		if (classification === null && !assertionResult.passed) classification = 'ASSERTION_MISMATCH';
		if (classification === null) classification = 'PASSED';
		const status = classification === 'PASSED' ? 'PASSED' : classification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED';
		const report = scenarioReport(status, scenario, {
			classification, generatedName, lifecycle: terminalState, elapsedMs: Math.max(0, Number(now()) - startedAt),
			commands, assertions: assertionResult.results, evidence: evidenceSummary(directory, fileEvidence, protocolAudit),
			diagnostics,
			cleanup: { status: 'PENDING' },
		});
		try {
			await closeResources(rcon, providerTurnRecorder);
			closed = true;
		} catch (error) {
			return await finishReport(scenario, report, directory, writeFile, 'CLEANUP_FAILURE', error);
		}
		const finished = scenarioReport(report.status, scenario, { ...report, cleanup: { status: closed ? 'CLEAN' : 'FAILED' } });
		return await persistReportOrFailure(scenario, finished, directory, writeFile);
	} catch (error) {
		diagnostics = boundedText(error?.message ?? error, MAX_DIAGNOSTICS);
		let cleanupError = null;
		try { await closeResources(rcon, providerTurnRecorder); }
		catch (errorDuringCleanup) { cleanupError = errorDuringCleanup; }
		const failureClassification = cleanupError !== null ? 'CLEANUP_FAILURE' : classification ?? (error?.code === 'HEADLESS_TIMEOUT' ? 'TIMEOUT' : 'ERROR');
		const status = failureClassification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED';
		const report = scenarioReport(status, scenario, {
			classification: failureClassification, diagnostics: cleanupError === null ? diagnostics : boundedText(cleanupError?.message ?? cleanupError, MAX_DIAGNOSTICS),
			generatedName, commands, cleanup: cleanupError === null ? { status: 'CLEAN' } : { status: 'FAILED', diagnostics: cleanupError?.message ?? String(cleanupError) },
		});
		return await persistReportOrFailure(scenario, report, directory, writeFile);
	}
}

export async function writeHeadlessReport(runDirectory, report, writeFile = defaultWriteFile) {
	const directory = normalizeRunDirectory(runDirectory);
	if (typeof writeFile !== 'function') throw new TypeError('writeFile must be a function');
	const bounded = boundReportValue(report);
	if (writeFile === defaultWriteFile) await defaultMkdir(directory, { recursive: true });
	await writeFile(path.join(directory, 'report.json'), `${JSON.stringify(bounded, null, 2)}\n`, { encoding: 'utf8' });
}

const HEADLESS_CLI_USAGE = 'Usage: node src/headless-matrix.mjs --config <absolute-path> --run-directory <absolute-path> --rcon-host <host> --rcon-port <port> --rcon-password-file <absolute-path> [--scenario <id>] [--protocol-audit <absolute-path>] [--provider-turns <absolute-path>] [--require-all]';

export function parseHeadlessCliArguments(args) {
	if (!Array.isArray(args)) throw new TypeError('CLI arguments must be an array');
	const result = { configPath: null, scenarioId: null, runDirectory: null, rconHost: '127.0.0.1', rconPort: null, rconPasswordFile: null, protocolAuditPath: null, providerTurnsPath: null, requireAll: false };
	const valueFlags = new Map([
		['--config', 'configPath'], ['--scenario', 'scenarioId'], ['--run-directory', 'runDirectory'],
		['--rcon-host', 'rconHost'], ['--rcon-port', 'rconPort'], ['--rcon-password-file', 'rconPasswordFile'],
		['--protocol-audit', 'protocolAuditPath'], ['--provider-turns', 'providerTurnsPath'],
	]);
	for (let index = 0; index < args.length; index += 1) {
		const flag = args[index];
		if (flag === '--require-all') { result.requireAll = true; continue; }
		if (flag === '--help' || flag === '-h') return { help: true };
		const key = valueFlags.get(flag);
		if (!key || index + 1 >= args.length || String(args[index + 1]).startsWith('--')) throw new Error(HEADLESS_CLI_USAGE);
		const value = String(args[++index]);
		result[key] = key === 'rconPort' ? Number(value) : value;
	}
	for (const key of ['configPath', 'runDirectory', 'rconPasswordFile']) {
		if (typeof result[key] !== 'string' || !path.isAbsolute(result[key])) throw new Error(`${key} must be an absolute path\n${HEADLESS_CLI_USAGE}`);
	}
	for (const key of ['protocolAuditPath', 'providerTurnsPath']) {
		if (result[key] !== null && !path.isAbsolute(result[key])) throw new Error(`${key} must be an absolute path`);
	}
	if (!Number.isInteger(result.rconPort) || result.rconPort < 1 || result.rconPort > 65535) throw new Error(`rconPort must be a valid port\n${HEADLESS_CLI_USAGE}`);
	if (typeof result.rconHost !== 'string' || result.rconHost.trim() === '') throw new Error('rconHost must be nonblank');
	if (result.scenarioId !== null && (result.scenarioId.trim() === '' || /[\u0000-\u001f\u007f]/.test(result.scenarioId))) throw new Error('scenario must be bounded text without control characters');
	return result;
}

/** Render a small, secret-free handoff suitable for terminals and CI logs. */
export function formatHeadlessCliOutput(report) {
	if (!report || typeof report !== 'object') throw new TypeError('matrix report must be an object');
	const lines = [`Report: ${String(report.reportPath ?? '')}`, `Matrix: ${String(report.status ?? 'UNKNOWN')}`];
	for (const scenario of Array.isArray(report.scenarios) ? report.scenarios : []) {
		lines.push(`${String(scenario.status ?? 'UNKNOWN')} ${String(scenario.scenarioId ?? 'unknown')}`);
	}
	return `${lines.join('\n')}\n`;
}

export async function runHeadlessMatrix({
	configPath, scenarioId = null, runDirectory, rconHost = '127.0.0.1', rconPort, rconPasswordFile,
	protocolAuditPath = null, providerTurnsPath = null, requireAll = false,
	readFile = defaultReadFile, writeFile = defaultWriteFile, mkdir = defaultMkdir,
	rconFactory = (options) => new HeadlessRconClient(options),
} = {}) {
	if (typeof readFile !== 'function' || typeof writeFile !== 'function' || typeof mkdir !== 'function' || typeof rconFactory !== 'function') throw new TypeError('headless CLI dependencies must be functions');
	const matrix = normalizeHeadlessMatrix(JSON.parse(await readFile(configPath, 'utf8')));
	const scenarios = selectHeadlessScenarios(matrix, scenarioId);
	if (scenarios.length > MAX_SCENARIOS) throw new RangeError(`selected scenario count exceeds bounded maximum of ${MAX_SCENARIOS}`);
	const password = String(await readFile(rconPasswordFile, 'utf8')).trim();
	if (password.length === 0) throw new Error('RCON password file is empty');
	const runId = path.basename(path.resolve(runDirectory));
	const scenarioReports = [];
	for (const scenario of scenarios) {
		const scenarioDirectory = scenarioId === null ? path.join(path.resolve(runDirectory), scenario.id) : path.resolve(runDirectory);
		let rcon = null;
		try {
			rcon = rconFactory({ host: rconHost, port: rconPort, password });
			if (!rcon || typeof rcon.connect !== 'function' || typeof rcon.command !== 'function') throw new TypeError('rconFactory must return a HeadlessRconClient-compatible object');
			await rcon.connect();
			const report = await runHeadlessScenario({ scenario, runDirectory: scenarioDirectory, rcon, protocolAudit: protocolAuditPath, providerTurnsPath });
			scenarioReports.push(report);
		} catch (error) {
			let cleanupStatus = 'CLEAN';
			try { await rcon?.close?.(); } catch { cleanupStatus = 'FAILED'; }
			scenarioReports.push(scenarioReport('FAILED', scenario, { classification: 'ERROR', diagnostics: error?.message ?? String(error), cleanup: { status: cleanupStatus } }));
		}
	}
	const classifiedReports = scenarioReports.map((report) => {
		if (requireAll && report.status === 'SKIPPED') return { ...report, status: 'FAILED', classification: 'REQUIRED_PROFILE_UNAVAILABLE' };
		return report;
	});
	const failed = classifiedReports.filter((report) => report.status === 'FAILED');
	const passed = classifiedReports.filter((report) => report.status === 'PASSED');
	const status = failed.length > 0 ? 'FAILED' : passed.length > 0 ? 'PASSED' : 'SKIPPED';
	const report = {
		runId, status, requireAll: Boolean(requireAll), scenarios: classifiedReports,
		reportPath: path.join(path.resolve(runDirectory), 'matrix-report.json'),
	};
	await mkdir(path.resolve(runDirectory), { recursive: true });
	const encodedReport = `${JSON.stringify(report, null, 2)}\n`;
	if (Buffer.byteLength(encodedReport, 'utf8') > MAX_MATRIX_REPORT_BYTES) throw new RangeError(`matrix report exceeds bounded size of ${MAX_MATRIX_REPORT_BYTES} bytes`);
	await writeFile(report.reportPath, encodedReport, { encoding: 'utf8' });
	return { report, exitCode: failed.length > 0 ? 1 : 0 };
}

async function finishReport(scenario, report, directory, writeFile, classification, error) {
	const finished = scenarioReport('FAILED', scenario, { ...report, classification, cleanup: { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) } });
	return await persistReportOrFailure(scenario, finished, directory, writeFile);
}

async function persistReportOrFailure(scenario, report, directory, writeFile) {
	try {
		await writeHeadlessReport(directory, report, writeFile);
		return report;
	} catch (error) {
		return scenarioReport('FAILED', scenario, {
			...report,
			classification: 'CLEANUP_FAILURE',
			cleanup: { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) },
		});
	}
}

async function closeResources(rcon, providerTurnRecorder) {
	let failure = null;
	for (const resource of [rcon, providerTurnRecorder]) {
		if (!resource || typeof resource.close !== 'function') continue;
		try { await resource.close(); } catch (error) { failure ??= error; }
	}
	if (failure !== null) throw failure;
}

function evaluateAssertion(assertion, evidence, index) {
	const type = assertion?.type;
	let passed = false;
	let actual = null;
	if (type === 'lifecycle') { actual = evidence.lifecycle ?? evidence.terminalState ?? null; passed = actual === assertion.state; }
	else if (type === 'chat') { actual = evidence.chats ?? []; passed = actual.includes(assertion.message); }
	else if (type === 'action') {
		actual = evidence.actions ?? [];
		passed = actual.some((entry) => entry?.actionType === assertion.actionType && (assertion.args === undefined || objectSubset(assertion.args, entry.arguments ?? entry.args ?? {})));
	}
	else if (type === 'program') {
		actual = evidence.program ?? [];
		passed = actual.some((entry) => entry?.event === assertion.event && (assertion.status === undefined || entry.status === assertion.status));
	}
	else if (type === 'rcon') {
		actual = (evidence.rcon ?? []).filter((entry) => entry.command === assertion.command).map((entry) => entry.text);
		passed = actual.some((value) => value.includes(assertion.match));
	}
	return { index, type: boundedScalar(type), passed, expected: boundReportValue(assertion), actual: boundReportValue(actual) };
}

function objectSubset(expected, actual) {
	return Object.entries(expected).every(([key, value]) => {
		if (value && typeof value === 'object' && !Array.isArray(value)) return objectSubset(value, actual?.[key]);
		return Object.is(value, actual?.[key]);
	});
}

function makeEvidence({ terminalState, protocolAudit, protocolRows = [], traceRows = [], serverLog = '', rcon = [] }) {
	const rows = [...protocolRows, ...auditRows(protocolAudit)];
	const normalizedRows = rows.map(unwrapAuditRow).filter(Boolean);
	const actions = normalizedRows.filter((row) => row.type === 'action_command').map((row) => row.payload ?? row);
	const chats = [
		...traceRows.filter((row) => row.event === 'chat').map((row) => row.message ?? row.text),
		...normalizedRows.filter((row) => row.type === 'chat').map((row) => row.payload?.message ?? row.message),
		...actions.filter((row) => row.actionType === 'chat').map((row) => row.arguments?.message ?? row.args?.message),
		...extractChatMarkers(serverLog),
	].filter((value) => typeof value === 'string');
	const program = traceRows.filter((row) => typeof row.event === 'string' && row.event.startsWith('program_'));
	program.push(...normalizedRows.filter((row) => typeof row.type === 'string' && row.type.startsWith('program_')).map((row) => ({ event: row.type, ...(row.payload ?? {}) })));
	return { lifecycle: terminalState, terminalState, chats, actions, program, rcon };
}

async function collectHeadlessEvidence({ directory, readFile, tailReader, protocolAudit, providerTurnsPath, assertions, terminalState, rconEvidence, readOnlyCommand, deadline, now, startedAt, poll }) {
	for (const assertion of assertions) {
		if (assertion.type !== 'rcon' || logicalNow(now, startedAt, 0) >= deadline) continue;
		try { await withDeadline(() => readOnlyCommand(assertion.command), deadline, () => logicalNow(now, startedAt, 0), 'HEADLESS_TIMEOUT'); }
		catch (error) { if (error?.code !== 'HEADLESS_TIMEOUT') throw error; }
	}
	let attempt = 0;
	let fileEvidence = await readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath);
	let evidence = makeEvidence({ terminalState, protocolAudit, ...fileEvidence, rcon: rconEvidence });
	let assertionResult = evaluateHeadlessAssertions(assertions, evidence);
	const waitsForFiles = assertions.some((assertion) => assertion.type !== 'lifecycle' && assertion.type !== 'rcon');
	while (!assertionResult.passed && waitsForFiles && logicalNow(now, startedAt, attempt) < deadline) {
		try {
			await withDeadline(() => poll({ phase: 'evidence', attempt, deadline, evidence, now }), deadline, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT');
		} catch (error) { if (error?.code === 'HEADLESS_TIMEOUT') break; throw error; }
		attempt += 1;
		fileEvidence = await readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath);
		evidence = makeEvidence({ terminalState, protocolAudit, ...fileEvidence, rcon: rconEvidence });
		assertionResult = evaluateHeadlessAssertions(assertions, evidence);
	}
	return { fileEvidence, evidence, assertionResult };
}

async function readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath = null) {
	const protocolPath = typeof protocolAudit === 'string' ? protocolAudit : path.join(directory, 'protocol.jsonl');
	const coordinatorPath = path.join(directory, 'coordinator.jsonl');
	const serverPath = path.join(directory, 'server.log');
	const providerTurns = typeof providerTurnsPath === 'string' ? providerTurnsPath : null;
	const [protocolText, coordinatorText, serverLog, providerTurnsText] = await Promise.all([
		readBoundedTail(tailReader, protocolPath), readBoundedTail(tailReader, coordinatorPath), readBoundedTail(tailReader, serverPath),
		providerTurns === null ? '' : readBoundedTail(tailReader, providerTurns),
	]);
	return {
		protocolRows: parseJsonl(protocolText), traceRows: parseJsonl(coordinatorText), serverLog,
		paths: { protocol: protocolPath, coordinator: coordinatorPath, server: serverPath, ...(providerTurns === null ? {} : { providerTurns }) },
		providerTurnsRows: providerTurns === null ? 0 : parseJsonl(providerTurnsText).length,
	};
}

function evidenceSummary(directory, fileEvidence, protocolAudit) {
	const paths = fileEvidence.paths ?? { protocol: path.join(directory, 'protocol.jsonl'), coordinator: path.join(directory, 'coordinator.jsonl'), server: path.join(directory, 'server.log') };
	return { paths, excerpts: { server: boundedText(fileEvidence.serverLog ?? '', 1024) }, auditRows: auditRows(protocolAudit).length, providerTurnsRows: fileEvidence.providerTurnsRows ?? 0 };
}

async function readBoundedTail(readTail, file) {
	try { return boundedTailText(await readTail(file, MAX_EVIDENCE_BYTES), MAX_EVIDENCE_BYTES); } catch { return ''; }
}

function parseJsonl(value) {
	return String(value ?? '').split(/\r?\n/).filter(Boolean).slice(-256).flatMap((line) => { try { return [JSON.parse(line)]; } catch { return []; } });
}

function auditRows(source) {
	if (Array.isArray(source)) return source.slice(-256);
	if (source && Array.isArray(source.rows)) return source.rows.slice(-256);
	return [];
}

function unwrapAuditRow(row) {
	if (!row || typeof row !== 'object') return null;
	return row.envelope && typeof row.envelope === 'object' ? row.envelope : row;
}

function extractChatMarkers(value) {
	return String(value ?? '').split(/\r?\n/).flatMap((line) => {
		const match = line.match(/(?:chat|message)\s*[:=]\s*(.*)$/i);
		return match ? [match[1].trim()] : [];
	});
}

function normalizeRunDirectory(value) {
	if (typeof value !== 'string' || value.trim() === '') throw new TypeError('runDirectory must be a nonblank path');
	return path.resolve(value);
}

function generatedAgentName(scenario, timestamp) {
	const id = String(scenario.id ?? 'scenario').replace(/[^A-Za-z0-9_]/g, '_').slice(0, 32) || 'scenario';
	return `headless_${id}_${Math.abs(Number(timestamp) || 0).toString(36)}`.slice(0, 40);
}

function parseLifecycle(value) {
	const textValue = String(value ?? '').toUpperCase().trim();
	if (LIFECYCLE_STATES.has(textValue)) return textValue;
	if (/\|\s*TASK COMPLETE\s*\./i.test(textValue)) return 'COMPLETED';
	if (/\|\s*NEEDS ATTENTION\s*\./i.test(textValue)) return 'ERROR';
	if (/\|\s*DEAD\s*-\s*AWAITING MODEL\s*\./i.test(textValue)) return 'DEAD';
	const match = textValue.match(/(?:STATE|STATUS|LIFECYCLE)\s*[:=]\s*(COMPLETED|ERROR|DEAD|RUNNING|STARTING|IDLE|STOPPED)/);
	return match ? match[1] : null;
}

function isFailedResponse(value) { return /(?:\bERROR\b|\bFAILED\b|unknown agent|unable to|rejected)/i.test(String(value ?? '')); }
function isSkippedResponse(value) { return /(?:SKIP|unavailable|not logged in|not installed|profile unavailable|catalog unavailable)/i.test(String(value ?? '')); }
function isAcceptedResponse(value) { return String(value ?? '').trim().length > 0; }
function isReadOnlyRcon(value) {
	const source = String(value ?? '');
	if (/[\u0000-\u001f\u007f;&|`]/.test(source)) return false;
	const command = source.trim().replace(/^\/+/, '').replace(/\s+/g, ' ');
	return /^(?:list(?:\s+.*)?|seed|difficulty|data\s+get(?:\s+.*)?|time\s+query\s+(?:day|daytime|gametime)|weather\s+query|gamerule\s+[A-Za-z0-9_.-]+)$/.test(command);
}
function validateRunnerScenario(scenario, assertions) {
	for (const field of ['id', 'provider', 'model', 'reasoningEffort', 'serviceTier', 'task']) {
		const value = scenario[field];
		if (typeof value !== 'string' || value.trim() === '' || value.length > MAX_TEXT || /[\u0000-\u001f\u007f]/.test(value)) throw new TypeError(`scenario.${field} must be bounded text without control characters`);
	}
	if (!Number.isSafeInteger(scenario.timeoutMs) || scenario.timeoutMs <= 0 || scenario.timeoutMs > MAX_TIMEOUT_MS) throw new RangeError('scenario.timeoutMs is out of bounds');
	if (!Array.isArray(assertions) || assertions.length === 0) throw new TypeError('scenario requires assertions');
}
function boundedScalar(value) { return value === null || value === undefined ? null : String(value).slice(0, MAX_TEXT); }
function boundedText(value, limit) {
	const textValue = Buffer.isBuffer(value) ? value.toString('utf8') : String(value ?? '');
	if (Buffer.byteLength(textValue, 'utf8') <= limit) return textValue;
	let end = Math.min(textValue.length, limit);
	while (end > 0 && Buffer.byteLength(textValue.slice(0, end), 'utf8') > limit) end -= 1;
	return textValue.slice(0, end);
}

function boundedTailText(value, limit) {
	const textValue = Buffer.isBuffer(value) ? value.toString('utf8') : String(value ?? '');
	if (Buffer.byteLength(textValue, 'utf8') <= limit) return textValue;
	return Buffer.from(textValue, 'utf8').subarray(-limit).toString('utf8');
}

function redactReportText(value, limit) {
	let textValue = Buffer.isBuffer(value) ? value.toString('utf8') : String(value ?? '');
	try {
		const redacted = redactTrace(textValue);
		if (typeof redacted === 'string') textValue = redacted;
	} catch { /* fall through to the local bounded redactor */ }
	textValue = redactQuotedJsonSecrets(textValue)
		.replace(REPORT_BEARER_TEXT, 'Bearer [REDACTED]')
		.replace(REPORT_SECRET_TEXT, '$1[REDACTED]');
	return boundedText(textValue, limit);
}

function redactQuotedJsonSecrets(value) {
	let result = '';
	let cursor = 0;
	REPORT_QUOTED_SECRET_KEY.lastIndex = 0;
	let match;
	while ((match = REPORT_QUOTED_SECRET_KEY.exec(value)) !== null) {
		const valueStart = REPORT_QUOTED_SECRET_KEY.lastIndex;
		let valueEnd = valueStart;
		while (valueEnd < value.length) {
			if (value[valueEnd] === '\\') { valueEnd += 2; continue; }
			if (value[valueEnd] === match[2]) break;
			valueEnd += 1;
		}
		if (valueEnd >= value.length) break;
		result += value.slice(cursor, valueStart) + '[REDACTED]' + match[2];
		cursor = valueEnd + 1;
		REPORT_QUOTED_SECRET_KEY.lastIndex = cursor;
	}
	return result + value.slice(cursor);
}

function logicalNow(now, startedAt, attempt) {
	const observed = Number(now());
	return Math.max(Number.isFinite(observed) ? observed : startedAt, startedAt + attempt * POLL_INTERVAL_MS);
}

function headlessTimeout() {
	const error = new Error('headless scenario deadline exceeded');
	error.code = 'HEADLESS_TIMEOUT';
	return error;
}

async function withDeadline(operation, deadline, currentTime, code = 'HEADLESS_TIMEOUT') {
	const remaining = deadline - currentTime();
	if (!(remaining > 0)) throw Object.assign(headlessTimeout(), { code });
	let timer;
	const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(Object.assign(headlessTimeout(), { code })), remaining); });
	try { return await Promise.race([Promise.resolve().then(operation), timeout]); }
	finally { clearTimeout(timer); }
}

async function defaultReadTail(file, maxBytes) {
	const handle = await defaultOpen(file, 'r');
	try {
		const stats = await handle.stat();
		const size = Math.min(Number(stats.size), maxBytes);
		const buffer = Buffer.alloc(size);
		if (size > 0) await handle.read(buffer, 0, size, Number(stats.size) - size);
		return buffer.toString('utf8');
	} finally { await handle.close(); }
}

async function defaultPoll() { await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS)); }

async function runHeadlessCli() {
	const parsed = parseHeadlessCliArguments(process.argv.slice(2));
	if (parsed.help) { process.stdout.write(`${HEADLESS_CLI_USAGE}\n`); return; }
	const result = await runHeadlessMatrix(parsed);
	process.stdout.write(formatHeadlessCliOutput(result.report));
	process.exitCode = result.exitCode;
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	runHeadlessCli().catch((error) => {
		process.stderr.write(`${error?.stack ?? error}\n`);
		process.exitCode = 1;
	});
}
