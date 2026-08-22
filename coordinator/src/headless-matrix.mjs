import path from 'node:path';
import { mkdir as defaultMkdir, readFile as defaultReadFile, writeFile as defaultWriteFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import { HeadlessRconClient } from './headless-rcon.mjs';

const PROVIDERS = new Set(['codex', 'gemini', 'kimi']);
const MAX_TIMEOUT_MS = 900_000;
const MAX_DIAGNOSTICS = 4096;
const MAX_TEXT = 4096;
const MAX_ASSERTION_ARGS = 8192;
const MAX_EVIDENCE_BYTES = 16_384;
const MAX_POLL_ATTEMPTS = 256;
const POLL_INTERVAL_MS = 50;
const SCENARIO_KEYS = new Set(['id', 'provider', 'model', 'reasoningEffort', 'serviceTier', 'task', 'timeoutMs', 'assert', 'assertions', 'repetitions', 'planningTimeoutMs', 'scenarioTimeoutMs', 'requireFactualSuccess']);
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
		repetitions: value.repetitions === undefined ? 1 : boundedPositiveInteger(value.repetitions, `scenarios[${index}].repetitions`),
		planningTimeoutMs: value.planningTimeoutMs === undefined ? null : boundedPositiveInteger(value.planningTimeoutMs, `scenarios[${index}].planningTimeoutMs`),
		scenarioTimeoutMs: value.scenarioTimeoutMs === undefined ? timeoutMs : boundedPositiveInteger(value.scenarioTimeoutMs, `scenarios[${index}].scenarioTimeoutMs`),
		requireFactualSuccess: value.requireFactualSuccess === true,
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
	if (depth > 6 || value === null || typeof value !== 'object') return typeof value === 'string' ? value.slice(0, MAX_DIAGNOSTICS) : value;
	if (Array.isArray(value)) return value.slice(0, 64).map((entry) => boundReportValue(entry, depth + 1));
	return Object.fromEntries(Object.entries(value).slice(0, 64).map(([key, entry]) => [key.slice(0, 128), boundReportValue(entry, depth + 1)]));
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
	writeFile = defaultWriteFile,
	protocolAudit = null,
	providerTurnRecorder = null,
	poll = defaultPoll,
} = {}) {
	if (!scenario || typeof scenario !== 'object') throw new TypeError('scenario must be an object');
	if (!rcon || typeof rcon.command !== 'function') throw new TypeError('rcon.command must be a function');
	if (typeof now !== 'function' || typeof readFile !== 'function' || typeof writeFile !== 'function' || typeof poll !== 'function') throw new TypeError('runner dependencies must be functions');
	const directory = normalizeRunDirectory(runDirectory);
	const assertions = scenario.assertions ?? scenario.assert ?? [];
	const profile = {
		provider: boundedScalar(scenario.provider), model: boundedScalar(scenario.model),
		reasoningEffort: boundedScalar(scenario.reasoningEffort), serviceTier: boundedScalar(scenario.serviceTier),
	};
	const base = { status: 'FAILED', scenarioId: boundedScalar(scenario.id), profile };
	if (scenario.skip === true || scenario.skipped === true || scenario.profileAvailable === false) {
		let cleanup = { status: 'NOT_REQUIRED' };
		try { await closeResources(rcon, providerTurnRecorder); } catch (error) { cleanup = { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) }; }
		const report = scenarioReport(cleanup.status === 'FAILED' ? 'FAILED' : 'SKIPPED', scenario, { classification: cleanup.status === 'FAILED' ? 'CLEANUP_FAILURE' : 'SKIPPED_PROFILE', reason: scenario.skipReason ?? scenario.reason ?? 'provider profile unavailable', cleanup });
		await writeHeadlessReport(directory, report, writeFile);
		return report;
	}
	const generatedName = generatedAgentName(scenario, now);
	const commands = [];
	const rconEvidence = [];
	const startedAt = Number(now());
	const timeoutMs = Number.isSafeInteger(scenario.timeoutMs) && scenario.timeoutMs > 0 ? scenario.timeoutMs : MAX_TIMEOUT_MS;
	let terminalState = null;
	let classification = null;
	let diagnostics = '';
	let closed = false;
	const command = async (value, { readOnly = false, template = null } = {}) => {
		const commandText = String(value);
		if (readOnly && !isReadOnlyRcon(commandText)) throw new Error(`RCON assertion command is not read-only: ${commandText}`);
		commands.push(commandText);
		const result = await rcon.command(commandText);
		const textValue = boundedText(result?.text ?? result, MAX_EVIDENCE_BYTES);
		if (readOnly) rconEvidence.push({ command: template ?? commandText, executedCommand: commandText, text: textValue });
		return { result, text: textValue };
	};
	try {
		const summon = await command(`codex summon-configured ${scenario.provider} ${scenario.model} ${scenario.reasoningEffort} ${scenario.serviceTier ?? 'priority'} survival ${generatedName}`);
		if (isSkippedResponse(summon.text)) classification = 'SKIPPED_PROFILE';
		if (classification === null && isFailedResponse(summon.text)) {
			classification = 'ERROR';
			diagnostics = summon.text;
		}
		if (classification === null) {
			if (!isAcceptedResponse(summon.text)) await poll({ phase: 'summon', attempt: 0, deadline: startedAt + timeoutMs, response: summon.text, now });
			await command(`codex start ${generatedName} ${scenario.task}`);
			let attempts = 0;
			while (terminalState === null && attempts < MAX_POLL_ATTEMPTS) {
				const status = await command(`codex status ${generatedName}`);
				const parsedState = parseLifecycle(status.text);
				terminalState = LIFECYCLE_STATES.has(parsedState) ? parsedState : null;
				if (terminalState !== null) break;
				const elapsed = Math.max(Number(now()) - startedAt, attempts * POLL_INTERVAL_MS);
				if (elapsed >= timeoutMs) { classification = 'TIMEOUT'; break; }
				await poll({ phase: 'status', attempt: attempts, deadline: startedAt + timeoutMs, status: status.text, readStatus: () => command(`codex status ${generatedName}`), now });
				attempts += 1;
			}
			if (terminalState === null && classification === null) classification = 'TIMEOUT';
		}
		if (classification === null && terminalState === 'ERROR') classification = 'ERROR';
		if (classification === null && terminalState === 'DEAD') classification = 'DEAD';
		if (classification === null && terminalState === null) classification = 'TIMEOUT';
		const fileEvidence = await readEvidence(directory, readFile, protocolAudit);
		for (const assertion of assertions) {
			if (assertion.type === 'rcon') {
				const executedCommand = assertion.command.replaceAll('{agent}', generatedName);
				await command(executedCommand, { readOnly: true, template: assertion.command });
			}
		}
		const evidence = makeEvidence({ terminalState, protocolAudit, ...fileEvidence, rcon: rconEvidence });
		const assertionResult = evaluateHeadlessAssertions(assertions, evidence);
		const factualAssertions = assertions.filter((assertion) => assertion.type === 'rcon');
		const factualSuccess = factualAssertions.length > 0 && assertionResult.results.filter((result) => result.type === 'rcon').every((result) => result.passed);
		if (classification === null && !assertionResult.passed) classification = 'ASSERTION_MISMATCH';
		if (classification === null && scenario.requireFactualSuccess && !factualSuccess) classification = 'FAILED_USER_OBJECTIVE';
		if (classification === null) classification = 'PASSED';
		const status = classification === 'PASSED' ? 'PASSED' : classification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED';
		const report = scenarioReport(status, scenario, {
			classification, generatedName, lifecycle: terminalState, elapsedMs: Math.max(0, Number(now()) - startedAt),
			commands, assertions: assertionResult.results, factualSuccess, evidence: evidenceSummary(directory, fileEvidence, protocolAudit),
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
		await writeHeadlessReport(directory, finished, writeFile);
		return finished;
	} catch (error) {
		diagnostics = boundedText(error?.message ?? error, MAX_DIAGNOSTICS);
		try { await closeResources(rcon, providerTurnRecorder); }
		catch (cleanupError) { return await finishReport(scenario, base, directory, writeFile, 'CLEANUP_FAILURE', cleanupError); }
		const status = classification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED';
		const report = scenarioReport(status, scenario, { classification: classification ?? 'ERROR', diagnostics, generatedName, commands, cleanup: { status: 'CLEAN' } });
		await writeHeadlessReport(directory, report, writeFile);
		return report;
	}
}

export async function writeHeadlessReport(runDirectory, report, writeFile = defaultWriteFile) {
	const directory = normalizeRunDirectory(runDirectory);
	if (typeof writeFile !== 'function') throw new TypeError('writeFile must be a function');
	const bounded = boundReportValue(report);
	if (writeFile === defaultWriteFile) await defaultMkdir(directory, { recursive: true });
	await writeFile(path.join(directory, 'report.json'), `${JSON.stringify(bounded, null, 2)}\n`, { encoding: 'utf8' });
}

async function finishReport(scenario, report, directory, writeFile, classification, error) {
	const finished = scenarioReport('FAILED', scenario, { ...report, classification, cleanup: { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) } });
	await writeHeadlessReport(directory, finished, writeFile);
	return finished;
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
		actual = (evidence.rcon ?? []).filter((entry) => entry.command === assertion.command || entry.template === assertion.command).map((entry) => entry.text);
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

async function readEvidence(directory, readFile, protocolAudit) {
	const protocolPath = typeof protocolAudit === 'string' ? protocolAudit : path.join(directory, 'protocol.jsonl');
	const coordinatorPath = path.join(directory, 'coordinator.jsonl');
	const serverPath = path.join(directory, 'server.log');
	const [protocolText, coordinatorText, serverLog] = await Promise.all([
		readBounded(readFile, protocolPath), readBounded(readFile, coordinatorPath), readBounded(readFile, serverPath),
	]);
	return {
		protocolRows: parseJsonl(protocolText), traceRows: parseJsonl(coordinatorText), serverLog,
		paths: { protocol: protocolPath, coordinator: coordinatorPath, server: serverPath },
	};
}

function evidenceSummary(directory, fileEvidence, protocolAudit) {
	const paths = fileEvidence.paths ?? { protocol: path.join(directory, 'protocol.jsonl'), coordinator: path.join(directory, 'coordinator.jsonl'), server: path.join(directory, 'server.log') };
	return { paths, excerpts: { server: boundedText(fileEvidence.serverLog ?? '', 1024) }, auditRows: auditRows(protocolAudit).length };
}

async function readBounded(readFile, file) {
	try { return boundedText(await readFile(file, 'utf8'), MAX_EVIDENCE_BYTES); } catch { return ''; }
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

export async function runHeadlessMatrix({ config, configPath, scenarioId = null, runDirectory, rconHost = '127.0.0.1', rconPort, rconPasswordFile, protocolAudit = null, requireAll = false, readFile = defaultReadFile } = {}) {
	const source = config ?? JSON.parse(await readFile(configPath, 'utf8'));
	const matrix = normalizeHeadlessMatrix(source);
	const scenarios = selectHeadlessScenarios(matrix, scenarioId);
	if (!Number.isInteger(rconPort) || rconPort < 1 || rconPort > 65535) throw new TypeError('rconPort must be a valid port');
	if (typeof rconPasswordFile !== 'string' || rconPasswordFile.trim() === '') throw new TypeError('rconPasswordFile is required');
	const password = String(await readFile(rconPasswordFile, 'utf8')).trim();
	if (password.length === 0 || password.length > 512) throw new TypeError('RCON password file is empty or oversized');
	const reports = [];
	for (const scenario of scenarios) {
		const repetitions = scenario.repetitions ?? 1;
		for (let repetition = 1; repetition <= repetitions; repetition += 1) {
			const repetitionDirectory = path.join(path.resolve(runDirectory), scenario.id, `repetition-${repetition}`);
			const rcon = new HeadlessRconClient({ host: rconHost, port: rconPort, password });
			try {
				await rcon.connect();
				const report = await runHeadlessScenario({ scenario, runDirectory: repetitionDirectory, rcon, protocolAudit });
				reports.push({ ...report, repetition });
			} catch (error) {
				reports.push(scenarioReport('FAILED', scenario, { repetition, classification: 'RUNNER_ERROR', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS), cleanup: { status: 'FAILED' } }));
			} finally {
				try { await rcon.close(); } catch { /* scenario report records cleanup; socket closure is best effort */ }
			}
		}
	}
	const failed = reports.some((report) => report.status === 'FAILED');
	const skipped = reports.length > 0 && reports.every((report) => report.status === 'SKIPPED');
	const status = failed || (requireAll && skipped) ? 'FAILED' : skipped ? 'SKIPPED' : reports.length > 0 && reports.every((report) => report.status === 'PASSED') ? 'PASSED' : 'FAILED';
	const report = { schemaVersion: 1, status, requireAll: requireAll === true, scenarioCount: reports.length, scenarios: reports };
	await defaultMkdir(path.resolve(runDirectory), { recursive: true });
	await defaultWriteFile(path.join(path.resolve(runDirectory), 'matrix-report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
	return Object.freeze({ report, exitCode: status === 'PASSED' || status === 'SKIPPED' ? 0 : 1 });
}

export function parseHeadlessCliArguments(argv) {
	const values = {};
	for (let index = 0; index < argv.length; index += 1) {
		const flag = argv[index];
		if (flag === '--require-all') { values.requireAll = true; continue; }
		if (!flag.startsWith('--') || index + 1 >= argv.length) throw new TypeError(`invalid headless CLI argument '${flag}'`);
		const key = { '--config': 'configPath', '--scenario': 'scenarioId', '--run-directory': 'runDirectory', '--rcon-host': 'rconHost', '--rcon-port': 'rconPort', '--rcon-password-file': 'rconPasswordFile', '--protocol-audit': 'protocolAudit', '--provider-turns': 'providerTurns' }[flag];
		if (!key) throw new TypeError(`unknown headless CLI argument '${flag}'`);
		values[key] = argv[++index];
	}
	for (const field of ['configPath', 'runDirectory', 'rconPasswordFile']) if (typeof values[field] !== 'string' || values[field].trim() === '') throw new TypeError(`--${field} is required`);
	values.rconPort = Number(values.rconPort);
	return values;
}

function boundedPositiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 1 || value > MAX_TIMEOUT_MS) throw new RangeError(`${field} must be between 1 and ${MAX_TIMEOUT_MS}`);
	return value;
}

function generatedAgentName(scenario, timestamp) {
	const id = String(scenario.id ?? 'scenario').replace(/[^A-Za-z0-9_]/g, '_').slice(0, 32) || 'scenario';
	return `headless_${id}_${Math.abs(Number(timestamp) || 0).toString(36)}`.slice(0, 40);
}

function parseLifecycle(value) {
	const textValue = String(value ?? '').toUpperCase().trim();
	if (LIFECYCLE_STATES.has(textValue)) return textValue;
	const match = textValue.match(/(?:STATE|STATUS|LIFECYCLE)\s*[:=]\s*(COMPLETED|ERROR|DEAD|RUNNING|STARTING|IDLE|STOPPED)/);
	return match ? match[1] : null;
}

function isFailedResponse(value) { return /(?:\bERROR\b|\bFAILED\b|unknown agent|unable to|rejected)/i.test(String(value ?? '')); }
function isSkippedResponse(value) { return /(?:SKIP|unavailable|not logged in|not installed|profile unavailable|catalog unavailable)/i.test(String(value ?? '')); }
function isAcceptedResponse(value) { return String(value ?? '').trim().length > 0; }
function isReadOnlyRcon(value) { return !/(?:^|\s)(?:summon|start|stop|kill|setblock|fill|clone|give|tp|teleport|data\s+(?:merge|modify|remove)|execute\s+.*\b(?:run|summon|setblock|give)\b)/i.test(value); }
function boundedScalar(value) { return value === null || value === undefined ? null : String(value).slice(0, MAX_TEXT); }
function boundedText(value, limit) {
	const textValue = Buffer.isBuffer(value) ? value.toString('utf8') : String(value ?? '');
	if (Buffer.byteLength(textValue, 'utf8') <= limit) return textValue;
	let end = Math.min(textValue.length, limit);
	while (end > 0 && Buffer.byteLength(textValue.slice(0, end), 'utf8') > limit) end -= 1;
	return textValue.slice(0, end);
}

async function defaultPoll() { await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS)); }

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	try {
		const options = parseHeadlessCliArguments(process.argv.slice(2));
		const result = await runHeadlessMatrix(options);
		process.stdout.write(`${JSON.stringify(result.report)}\n`);
		process.exitCode = result.exitCode;
	} catch (error) {
		process.stderr.write(`${boundedText(error?.message ?? error, MAX_DIAGNOSTICS)}\n`);
		process.exitCode = 1;
	}
}
