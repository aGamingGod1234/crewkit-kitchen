import path from 'node:path';
import { mkdir as defaultMkdir, open as defaultOpen, readFile as defaultReadFile, writeFile as defaultWriteFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { HeadlessRconClient } from './headless-rcon.mjs';
import { redact as redactTrace } from './trace-writer.mjs';

const PROVIDERS = new Set(['codex', 'gemini', 'kimi', 'cursor']);
const MAX_TIMEOUT_MS = 900_000;
const MAX_DIAGNOSTICS = 4096;
const MAX_TEXT = 4096;
const MAX_ASSERTION_ARGS = 8192;
const MAX_EVIDENCE_BYTES = 16_384;
const MAX_EVIDENCE_TAIL_BYTES = 262_144;
const MAX_SCENARIOS = 24;
const MAX_MATRIX_REPORT_BYTES = 262_144;
const POLL_INTERVAL_MS = 50;
const SENSITIVE_REPORT_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|launcherAccount|accountData|token|credential|oauth)/i;
const REPORT_SECRET_TEXT = /((?:bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)\s*[:=]\s*)([^\s,;)}\]"']+)/gi;
const REPORT_BEARER_TEXT = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const REPORT_QUOTED_SECRET_KEY = /(["'])(?:[A-Za-z0-9_-]*(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]*)\1\s*:\s*(["'])/gi;
const SCENARIO_KEYS = new Set(['id', 'provider', 'model', 'reasoningEffort', 'serviceTier', 'task', 'timeoutMs', 'rosterSize', 'assert', 'assertions']);
const ASSERTION_KEYS = {
	lifecycle: new Set(['type', 'state']),
	chat: new Set(['type', 'message']),
	action: new Set(['type', 'actionType', 'args', 'resultState']),
	program: new Set(['type', 'event', 'status']),
	rcon: new Set(['type', 'command', 'match']),
};
const LIFECYCLE_STATES = new Set(['COMPLETED', 'ERROR', 'DEAD']);
const ACTION_RESULT_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);

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
		if (value.resultState !== undefined) {
			result.resultState = text(value.resultState, `${field}.resultState`).toUpperCase();
			if (!ACTION_RESULT_STATES.has(result.resultState)) throw new TypeError(`${field}.resultState is unsupported`);
		}
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
	const rosterSize = value.rosterSize ?? 1;
	if (![1, 8, 16].includes(rosterSize)) throw new RangeError('rosterSize must be one of 1, 8, or 16');
	const scenario = {
		id: text(value.id, `scenarios[${index}].id`), provider: text(value.provider, `scenarios[${index}].provider`),
		model: text(value.model, `scenarios[${index}].model`), reasoningEffort: text(value.reasoningEffort, `scenarios[${index}].reasoningEffort`),
		serviceTier: text(value.serviceTier ?? 'priority', `scenarios[${index}].serviceTier`), task: text(value.task, `scenarios[${index}].task`), timeoutMs, rosterSize,
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
	const report = { status: normalizedStatus, scenarioId: scenario.id, rosterSize: scenario.rosterSize ?? 1, profile: { provider: scenario.provider, model: scenario.model, reasoningEffort: scenario.reasoningEffort, serviceTier: scenario.serviceTier } };
	for (const [key, value] of Object.entries(fields)) {
		if (key === 'status') continue;
		if (key === 'diagnostics' || key === 'error') report[key] = redactReportText(value, MAX_DIAGNOSTICS);
		else if (key === 'commands') report[key] = publicCommandRecords(value);
		else report[key] = boundReportValue(value);
	}
	return freeze(report);
}

function boundReportValue(value, depth = 0) {
	if (depth > 6) return '[TRUNCATED]';
	if (value === null || typeof value !== 'object') return typeof value === 'string' ? redactReportText(value, MAX_DIAGNOSTICS) : value;
	if (Array.isArray(value)) return value.slice(0, 64).map((entry) => boundReportValue(entry, depth + 1));
	return Object.fromEntries(Object.entries(value).slice(0, 64).map(([key, entry]) => [key.slice(0, 128), SENSITIVE_REPORT_KEY.test(key) && !safeTokenCounts(key, entry) ? '[REDACTED]' : boundReportValue(entry, depth + 1)]));
}

function publicCommandRecords(value) {
	if (!Array.isArray(value)) return [];
	return value.slice(0, 64).map((command) => {
		if (command && typeof command === 'object' && /^[a-z0-9_]{1,64}$/.test(command.operation)) return { operation: command.operation };
		const source = String(command ?? '').trim();
		let operation = 'rcon_command';
		if (/\bforceload\s+add\b/i.test(source)) operation = 'arena_forceload_add';
		else if (/\bforceload\s+remove\b/i.test(source)) operation = 'arena_forceload_remove';
		else if (/\brun\s+fill\b/i.test(source)) operation = 'arena_prepare';
		else if (/\bcodex\s+summon-configured\b/i.test(source)) operation = 'agent_summon';
		else if (/^codex\s+start\b/i.test(source)) operation = 'agent_start';
		else if (/^codex\s+status\b/i.test(source)) operation = 'agent_status';
		else if (/^tick\s+query$/i.test(source)) operation = 'minecraft_tick_query';
		else if (isReadOnlyRcon(source)) operation = 'read_only_assertion';
		return { operation };
	});
}

function safeTokenCounts(key, value) {
	if (key !== 'tokens' || !value || typeof value !== 'object' || Array.isArray(value)) return false;
	const categories = new Set(['input', 'output', 'reasoning', 'cached', 'cacheWrite']);
	return Object.entries(value).every(([category, count]) => categories.has(category) && (count === null || Number.isSafeInteger(count) && count >= 0));
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
	fileSize = defaultFileSize,
	writeFile = defaultWriteFile,
	protocolAudit = null,
	providerTurnsPath = null,
	providerTurnRecorder = null,
	poll = defaultPoll,
} = {}) {
	if (!scenario || typeof scenario !== 'object') throw new TypeError('scenario must be an object');
	if (!rcon || typeof rcon.command !== 'function') throw new TypeError('rcon.command must be a function');
	if (typeof now !== 'function' || typeof readFile !== 'function' || typeof writeFile !== 'function' || typeof poll !== 'function' || typeof fileSize !== 'function') throw new TypeError('runner dependencies must be functions');
	const directory = normalizeRunDirectory(runDirectory);
	const assertions = scenario.assertions ?? scenario.assert ?? [];
	validateRunnerScenario(scenario, assertions);
	if ((scenario.rosterSize ?? 1) > 1) {
		return runConcurrentHeadlessScenario({
			scenario, directory, rcon, now, readFile, readTail, fileSize, writeFile, protocolAudit,
			providerTurnsPath, providerTurnRecorder, poll,
		});
	}
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
	const commands = [];
	const rconEvidence = [];
	const startedAt = Number(now());
	if (!Number.isFinite(startedAt)) throw new TypeError('now must return a finite number');
	const generatedName = generatedAgentName(scenario, startedAt);
	const timeoutMs = scenario.timeoutMs;
	const deadline = startedAt + timeoutMs;
	const tailReader = readTail ?? (readFile === defaultReadFile
		? defaultReadTail
		: async (file, limit, offset = 0) => boundedTailText(Buffer.from(await readFile(file, 'utf8')).subarray(offset), limit));
	const evidencePaths = resolveEvidencePaths(directory, protocolAudit, providerTurnsPath);
	const evidenceOffsets = await captureEvidenceOffsets(evidencePaths, fileSize);
	const protocolAuditOffset = auditRows(protocolAudit).length;
	let terminalState = null;
	let classification = null;
	let diagnostics = '';
	let minecraftMspt = null;
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
		await command('execute in minecraft:overworld run forceload add 0 0');
		let summon;
		try {
			const floor = await command('execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone');
			if (isFailedResponse(floor.text)) throw new Error('Could not prepare the headless arena floor');
			const clearance = await command('execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air');
			if (isFailedResponse(clearance.text)) throw new Error('Could not clear the headless arena spawn');
			summon = await command(`execute in minecraft:overworld positioned 0.5 201 0.5 run codex summon-configured ${scenario.provider} ${scenario.model} ${scenario.reasoningEffort} ${scenario.serviceTier ?? 'priority'} survival ${generatedName}`);
		} finally {
			const cleanupDeadline = Math.max(deadline, Number(now()) + 10_000);
			await command('execute in minecraft:overworld run forceload remove 0 0', { deadlineMs: cleanupDeadline });
		}
		if (isSkippedResponse(summon.text)) classification = 'SKIPPED_PROFILE';
		if (classification === null && isFailedResponse(summon.text)) {
			classification = 'ERROR';
			diagnostics = summon.text;
		}
		if (classification === null && !isAcceptedResponse(summon.text)) {
			classification = 'ERROR';
			diagnostics = summon.text;
		}
		if (classification === null) {
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
		try { minecraftMspt = parseMinecraftMspt((await command('tick query', { attempt: 0 })).text); } catch { /* optional server metric */ }
		const evidenceResult = await collectHeadlessEvidence({
			directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets, protocolAuditOffset, assertions, terminalState, rconEvidence,
			readOnlyCommand: (value) => command(value, { readOnly: true, attempt: 0 }),
			deadline, now, startedAt, poll, scenario, generatedName,
		});
		const { scopedEvidence, scopedAudit, identity, assertionResult } = evidenceResult;
		const resolvedAgentId = identity.agentId;
		if (identity.error !== null) {
			classification = 'ERROR';
			diagnostics = identity.error;
		}
		if (classification === null && !assertionResult.passed) classification = 'ASSERTION_MISMATCH';
		if (classification === null) classification = 'PASSED';
		const status = classification === 'PASSED' ? 'PASSED' : classification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED';
		const elapsedMs = Math.max(0, Number(now()) - startedAt);
		const report = scenarioReport(status, scenario, {
			classification, generatedName, lifecycle: terminalState, elapsedMs,
			commands, assertions: assertionResult.results, evidence: evidenceSummary(directory, scopedEvidence, scopedAudit),
			timings: timingSummary(profile, scopedEvidence, scopedAudit, elapsedMs),
			metrics: performanceMetrics(profile, scopedEvidence, scopedAudit, resolvedAgentId, { minecraftMspt }),
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

async function runConcurrentHeadlessScenario({
	scenario, directory, rcon, now, readFile, readTail, fileSize, writeFile, protocolAudit,
	providerTurnsPath, providerTurnRecorder, poll,
}) {
	const assertions = scenario.assertions ?? scenario.assert ?? [];
	const profile = {
		provider: boundedScalar(scenario.provider), model: boundedScalar(scenario.model),
		reasoningEffort: boundedScalar(scenario.reasoningEffort), serviceTier: boundedScalar(scenario.serviceTier),
	};
	if (scenario.skip === true || scenario.skipped === true || scenario.profileAvailable === false) {
		let cleanup = { status: 'NOT_REQUIRED' };
		try { await closeResources(rcon, providerTurnRecorder); } catch (error) { cleanup = { status: 'FAILED', diagnostics: boundedText(error?.message ?? error, MAX_DIAGNOSTICS) }; }
		return persistReportOrFailure(scenario, scenarioReport(cleanup.status === 'FAILED' ? 'FAILED' : 'SKIPPED', scenario, {
			classification: cleanup.status === 'FAILED' ? 'CLEANUP_FAILURE' : 'SKIPPED_PROFILE',
			reason: scenario.skipReason ?? scenario.reason ?? 'provider profile unavailable', cleanup,
		}), directory, writeFile);
	}
	const startedAt = Number(now());
	if (!Number.isFinite(startedAt)) throw new TypeError('now must return a finite number');
	const deadline = startedAt + scenario.timeoutMs;
	const tailReader = readTail ?? (readFile === defaultReadFile
		? defaultReadTail
		: async (file, limit, offset = 0) => boundedTailText(Buffer.from(await readFile(file, 'utf8')).subarray(offset), limit));
	const evidencePaths = resolveEvidencePaths(directory, protocolAudit, providerTurnsPath);
	const evidenceOffsets = await captureEvidenceOffsets(evidencePaths, fileSize);
	const protocolAuditOffset = auditRows(protocolAudit).length;
	const members = Array.from({ length: scenario.rosterSize }, (_, index) => ({
		index,
		generatedName: generatedAgentName(scenario, startedAt, index),
		agentId: null,
		lifecycle: null,
		classification: null,
		diagnostics: '',
		started: false,
	}));
	const commands = [];
	const rconEvidence = [];
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
		await command('execute in minecraft:overworld run forceload add 0 0');
		try {
			const floor = await command('execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone');
			if (isFailedResponse(floor.text)) throw new Error('Could not prepare the headless arena floor');
			const clearance = await command('execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air');
			if (isFailedResponse(clearance.text)) throw new Error('Could not clear the headless arena spawn');
			const summons = await Promise.allSettled(members.map((member) => {
				const position = rosterPosition(member.index);
				return command(`execute in minecraft:overworld positioned ${position.x} 201 ${position.z} run codex summon-configured ${scenario.provider} ${scenario.model} ${scenario.reasoningEffort} ${scenario.serviceTier ?? 'priority'} survival ${member.generatedName}`);
			}));
			for (let index = 0; index < summons.length; index += 1) {
				const result = summons[index];
				const member = members[index];
				if (result.status === 'rejected') {
					member.classification = result.reason?.code === 'HEADLESS_TIMEOUT' ? 'TIMEOUT' : 'ERROR';
					member.diagnostics = boundedText(result.reason?.message ?? result.reason, MAX_DIAGNOSTICS);
				} else if (isSkippedResponse(result.value.text)) {
					member.classification = 'SKIPPED_PROFILE';
					member.diagnostics = result.value.text;
				}
				else if (!isAcceptedResponse(result.value.text)) {
					member.classification = 'ERROR';
					member.diagnostics = result.value.text;
				}
			}
		} finally {
			const cleanupDeadline = Math.max(deadline, Number(now()) + 10_000);
			await command('execute in minecraft:overworld run forceload remove 0 0', { deadlineMs: cleanupDeadline });
		}

		await resolveConcurrentAgentIds({
			members: members.filter((member) => member.classification === null), scenario, directory, readFile, tailReader,
			protocolAudit, providerTurnsPath, evidenceOffsets, deadline, now, startedAt, poll,
		});
		const startable = members.filter((member) => member.classification === null && member.agentId !== null);
		const starts = await Promise.allSettled(startable.map((member) => command(`codex start ${member.generatedName} ${scenario.task}`)));
		for (let index = 0; index < starts.length; index += 1) {
			const member = startable[index];
			const result = starts[index];
			if (result.status === 'rejected' || isFailedResponse(result.value?.text)) {
				member.classification = result.status === 'rejected' && result.reason?.code === 'HEADLESS_TIMEOUT' ? 'TIMEOUT' : 'ERROR';
				member.diagnostics = boundedText(result.status === 'rejected' ? result.reason?.message ?? result.reason : result.value.text, MAX_DIAGNOSTICS);
			} else member.started = true;
		}
		let attempt = 0;
		while (members.some((member) => member.started && member.lifecycle === null && member.classification === null)) {
			if (logicalNow(now, startedAt, attempt) >= deadline) break;
			const active = members.filter((member) => member.started && member.lifecycle === null && member.classification === null);
			const statuses = await Promise.allSettled(active.map((member) => command(`codex status ${member.generatedName}`, { attempt })));
			for (let index = 0; index < statuses.length; index += 1) {
				const member = active[index];
				const result = statuses[index];
				if (result.status === 'rejected') {
					member.classification = result.reason?.code === 'HEADLESS_TIMEOUT' ? 'TIMEOUT' : 'ERROR';
					member.diagnostics = boundedText(result.reason?.message ?? result.reason, MAX_DIAGNOSTICS);
					continue;
				}
				const state = parseLifecycle(result.value.text);
				if (LIFECYCLE_STATES.has(state)) member.lifecycle = state;
			}
			if (!members.some((member) => member.started && member.lifecycle === null && member.classification === null)) break;
			try { await withDeadline(() => poll({ phase: 'status', attempt, deadline, now }), deadline, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT'); }
			catch (error) { if (error?.code !== 'HEADLESS_TIMEOUT') throw error; break; }
			attempt += 1;
		}
		for (const member of members) {
			if (member.classification !== null) continue;
			if (member.lifecycle === 'ERROR') member.classification = 'ERROR';
			else if (member.lifecycle === 'DEAD') member.classification = 'DEAD';
			else if (member.lifecycle === null) member.classification = 'TIMEOUT';
		}
		let minecraftMspt = null;
		try { minecraftMspt = parseMinecraftMspt((await command('tick query')).text); } catch { /* optional server metric */ }
		const evidenceResult = await collectConcurrentHeadlessEvidence({
			members, directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets, protocolAuditOffset,
			assertions, rconEvidence, readOnlyCommand: (value) => command(value, { readOnly: true }), deadline, now, startedAt, poll,
		});
		const exactAgentIds = members.map((member) => member.agentId).filter(Boolean);
		const aggregateEvidence = isolateRosterFileEvidence(evidenceResult.fileEvidence, members);
		const aggregateAudit = auditRows(protocolAudit).slice(protocolAuditOffset)
			.filter((row) => exactAgentIds.some((agentId) => rowMatchesAgent(row, agentId))).slice(-256 * Math.max(1, exactAgentIds.length));
		for (const member of members) {
			const isolated = evidenceResult.byAgent.get(member.agentId);
			if (member.classification === null && isolated !== undefined && !isolated.assertionResult.passed) member.classification = 'ASSERTION_MISMATCH';
			if (member.classification === null) member.classification = 'PASSED';
		}
		const agentReports = members.map((member) => {
			const isolated = evidenceResult.byAgent.get(member.agentId);
			const isolatedAudit = auditRows(protocolAudit).slice(protocolAuditOffset).filter((row) => rowMatchesAgent(row, member.agentId)).slice(-256);
			return boundReportValue({
				agentId: member.agentId,
				generatedName: member.generatedName,
				status: member.classification === 'PASSED' ? 'PASSED' : member.classification === 'SKIPPED_PROFILE' ? 'SKIPPED' : 'FAILED',
				classification: member.classification,
				lifecycle: member.lifecycle,
				assertions: isolated?.assertionResult.results ?? [],
				evidence: isolated === undefined ? null : evidenceSummary(directory, isolated.fileEvidence, protocolAudit),
				metrics: isolated === undefined ? null : performanceMetrics(profile, isolated.fileEvidence, isolatedAudit, member.agentId),
				diagnostics: member.diagnostics,
			});
		});
		const failed = agentReports.some((member) => member.status === 'FAILED');
		const skipped = agentReports.some((member) => member.status === 'SKIPPED');
		const passed = agentReports.some((member) => member.status === 'PASSED');
		const status = failed || skipped ? 'FAILED' : passed ? 'PASSED' : 'FAILED';
		const classification = failed
			? (agentReports.some((member) => member.classification === 'ASSERTION_MISMATCH') ? 'ASSERTION_MISMATCH' : 'ERROR')
			: skipped ? 'SKIPPED_PROFILE' : passed ? 'PASSED' : 'ERROR';
		const elapsedMs = Math.max(0, Number(now()) - startedAt);
		let report = scenarioReport(status, scenario, {
			classification, lifecycle: members.every((member) => member.lifecycle === 'COMPLETED') ? 'COMPLETED' : null,
			elapsedMs, commands, agents: agentReports,
			evidence: evidenceSummary(directory, evidenceResult.fileEvidence, protocolAudit),
			timings: timingSummary(profile, aggregateEvidence, aggregateAudit, elapsedMs),
			metrics: performanceMetrics(profile, aggregateEvidence, aggregateAudit, exactAgentIds, { minecraftMspt }),
			cleanup: { status: 'PENDING' },
		});
		try { await closeResources(rcon, providerTurnRecorder); }
		catch (error) { return finishReport(scenario, report, directory, writeFile, 'CLEANUP_FAILURE', error); }
		report = scenarioReport(report.status, scenario, { ...report, cleanup: { status: 'CLEAN' } });
		return persistReportOrFailure(scenario, report, directory, writeFile);
	} catch (error) {
		let cleanupError = null;
		try { await closeResources(rcon, providerTurnRecorder); } catch (failure) { cleanupError = failure; }
		return persistReportOrFailure(scenario, scenarioReport('FAILED', scenario, {
			classification: cleanupError === null ? (error?.code === 'HEADLESS_TIMEOUT' ? 'TIMEOUT' : 'ERROR') : 'CLEANUP_FAILURE',
			diagnostics: cleanupError?.message ?? error?.message ?? String(error), commands,
			agents: members.map((member) => ({ agentId: member.agentId, generatedName: member.generatedName, classification: member.classification ?? 'ERROR', lifecycle: member.lifecycle })),
			cleanup: cleanupError === null ? { status: 'CLEAN' } : { status: 'FAILED', diagnostics: cleanupError.message },
		}), directory, writeFile);
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
	readTail = null, fileSize = defaultFileSize,
	rconFactory = (options) => new HeadlessRconClient(options),
} = {}) {
	if (typeof readFile !== 'function' || typeof writeFile !== 'function' || typeof mkdir !== 'function' || typeof rconFactory !== 'function' || (readTail !== null && typeof readTail !== 'function') || typeof fileSize !== 'function') throw new TypeError('headless CLI dependencies must be functions');
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
			const report = await runHeadlessScenario({
				scenario,
				runDirectory: scenarioDirectory,
				rcon,
				readFile,
				readTail,
				fileSize,
				writeFile,
				protocolAudit: protocolAuditPath,
				providerTurnsPath,
			});
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
		passed = actual.some((entry) => entry?.actionType === assertion.actionType
			&& (assertion.args === undefined || objectSubset(assertion.args, entry.arguments ?? entry.args ?? {}))
			&& (assertion.resultState === undefined || entry.result?.state === assertion.resultState));
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
	const actions = [
		...normalizedRows.filter((row) => row.type === 'action_command').map((row) => row.payload ?? row),
		...traceRows.filter((row) => row.event === 'program_step' && typeof row.actionType === 'string')
			.map((row) => ({ actionType: row.actionType, arguments: row.arguments ?? {}, result: row.result ?? null })),
	];
	const chats = [...new Set([
		...traceRows.filter((row) => row.event === 'chat').map((row) => row.message ?? row.text),
		...normalizedRows.filter((row) => row.type === 'chat').map((row) => row.payload?.message ?? row.message),
		...actions.filter((row) => row.actionType === 'chat').map((row) => row.arguments?.message ?? row.args?.message),
		...extractChatMarkers(serverLog),
	].filter((value) => typeof value === 'string'))];
	const program = traceRows.filter((row) => typeof row.event === 'string' && row.event.startsWith('program_'));
	program.push(...normalizedRows.filter((row) => typeof row.type === 'string' && row.type.startsWith('program_')).map((row) => ({ event: row.type, ...(row.payload ?? {}) })));
	return { lifecycle: terminalState, terminalState, chats, actions, program, rcon };
}

async function collectHeadlessEvidence({ directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets, protocolAuditOffset, assertions, terminalState, rconEvidence, readOnlyCommand, deadline, now, startedAt, poll, scenario, generatedName }) {
	for (const assertion of assertions) {
		if (assertion.type !== 'rcon' || logicalNow(now, startedAt, 0) >= deadline) continue;
		try { await withDeadline(() => readOnlyCommand(assertion.command), deadline, () => logicalNow(now, startedAt, 0), 'HEADLESS_TIMEOUT'); }
		catch (error) { if (error?.code !== 'HEADLESS_TIMEOUT') throw error; }
	}
	let attempt = 0;
	const currentAudit = () => auditRows(protocolAudit).slice(protocolAuditOffset);
	let fileEvidence;
	let scopedEvidence;
	let scopedAudit;
	let identity;
	let evidence;
	let assertionResult;
	const evaluate = async () => {
		fileEvidence = await readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets);
		identity = resolveExactSnapshotAgentId(fileEvidence, currentAudit(), scenario, generatedName);
		if (identity.authoritative) {
			scopedEvidence = isolateFileEvidence(fileEvidence, identity.agentId, generatedName);
			scopedAudit = currentAudit().filter((row) => rowMatchesAgent(row, identity.agentId)).slice(-256);
		} else if (identity.legacy) {
			scopedEvidence = isolateLegacyFileEvidence(fileEvidence);
			scopedAudit = currentAudit().filter((row) => !rowHasAgentIdentity(row)).slice(-256);
		} else {
			scopedEvidence = emptyFileEvidence(fileEvidence);
			scopedAudit = [];
		}
		evidence = makeEvidence({ terminalState, protocolAudit: scopedAudit, ...scopedEvidence, rcon: rconEvidence });
		assertionResult = evaluateHeadlessAssertions(assertions, evidence);
	};
	await evaluate();
	const waitsForFiles = assertions.some((assertion) => assertion.type !== 'lifecycle' && assertion.type !== 'rcon');
	while (!assertionResult.passed && waitsForFiles && logicalNow(now, startedAt, attempt) < deadline) {
		try {
			await withDeadline(() => poll({ phase: 'evidence', attempt, deadline, evidence, now }), deadline, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT');
		} catch (error) { if (error?.code === 'HEADLESS_TIMEOUT') break; throw error; }
		attempt += 1;
		await evaluate();
	}
	return { fileEvidence, scopedEvidence, scopedAudit, identity, evidence, assertionResult };
}

async function resolveConcurrentAgentIds({ members, scenario, directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets, deadline, now, startedAt, poll }) {
	let attempt = 0;
	while (members.some((member) => member.agentId === null && member.classification === null)) {
		const fileEvidence = await readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets);
		const rows = [...fileEvidence.protocolRows, ...auditRows(protocolAudit)].map(unwrapAuditRow).filter(Boolean);
		for (const member of members) {
			if (member.agentId !== null || member.classification !== null) continue;
			const candidates = rows.filter((row) => row.type === 'agent_snapshot' && row.payload?.name === member.generatedName);
			if (candidates.length === 0) continue;
			const exact = candidates.filter((row) => row.payload?.provider === scenario.provider
				&& row.payload?.model === scenario.model
				&& row.payload?.reasoningEffort === scenario.reasoningEffort
				&& row.payload?.serviceTier === scenario.serviceTier);
			const ids = [...new Set(exact.map(authoritativeAgentId).filter(Boolean))];
			if (ids.length !== 1) {
				member.classification = 'ERROR';
				member.diagnostics = ids.length === 0 ? 'Authoritative agent snapshot did not preserve the exact requested profile' : 'Authoritative agent snapshot identity was ambiguous';
			} else member.agentId = ids[0];
		}
		const assigned = new Map();
		for (const member of members.filter((entry) => entry.agentId !== null)) {
			const prior = assigned.get(member.agentId);
			if (prior === undefined) assigned.set(member.agentId, member);
			else {
				prior.classification = 'ERROR';
				prior.diagnostics = 'Authoritative agent snapshot identity was duplicated';
				member.classification = 'ERROR';
				member.diagnostics = 'Authoritative agent snapshot identity was duplicated';
			}
		}
		if (!members.some((member) => member.agentId === null && member.classification === null)) break;
		if (logicalNow(now, startedAt, attempt) >= deadline) break;
		try { await withDeadline(() => poll({ phase: 'identity', attempt, deadline, now }), deadline, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT'); }
		catch (error) { if (error?.code !== 'HEADLESS_TIMEOUT') throw error; break; }
		attempt += 1;
	}
	for (const member of members) {
		if (member.agentId === null && member.classification === null) {
			member.classification = 'ERROR';
			member.diagnostics = 'Authoritative agent snapshot identity was unavailable before the deadline';
		}
	}
}

function authoritativeAgentId(row) {
	const envelopeId = boundedAgentId(row?.agentId);
	const payloadId = boundedAgentId(row?.payload?.agentId);
	return envelopeId !== null && envelopeId === payloadId ? envelopeId : null;
}

function resolveExactSnapshotAgentId(fileEvidence, protocolAudit, scenario, generatedName) {
	const rows = [...(fileEvidence.protocolRows ?? []), ...auditRows(protocolAudit)].map(unwrapAuditRow).filter(Boolean);
	const snapshots = rows.filter((row) => row.type === 'agent_snapshot');
	if (snapshots.length === 0) {
		const labelled = rows.some(rowHasAgentIdentity)
			|| (fileEvidence.traceRows ?? []).some(rowHasAgentIdentity)
			|| (fileEvidence.providerTurnSummaries ?? []).some(rowHasAgentIdentity);
		return labelled
			? { agentId: null, authoritative: false, legacy: false, error: 'Authoritative agent snapshot identity was unavailable for labelled evidence' }
			: { agentId: null, authoritative: false, legacy: true, error: null };
	}
	const ids = [...new Set(snapshots.filter((row) => row.payload?.name === generatedName
		&& row.payload?.provider === scenario.provider && row.payload?.model === scenario.model
		&& row.payload?.reasoningEffort === scenario.reasoningEffort && row.payload?.serviceTier === scenario.serviceTier)
		.map(authoritativeAgentId).filter(Boolean))];
	if (ids.length === 1) return { agentId: ids[0], authoritative: true, legacy: false, error: null };
	return {
		agentId: null,
		authoritative: false, legacy: false,
		error: ids.length > 1 ? 'Authoritative agent snapshot identity was ambiguous' : 'Authoritative agent snapshot identity was unavailable for the exact requested profile',
	};
}

function boundedAgentId(value) {
	return typeof value === 'string' && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value) ? value : null;
}

async function collectConcurrentHeadlessEvidence({ members, directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets, protocolAuditOffset, assertions, rconEvidence, readOnlyCommand, deadline, now, startedAt, poll }) {
	for (const assertion of assertions) {
		if (assertion.type !== 'rcon' || logicalNow(now, startedAt, 0) >= deadline) continue;
		try { await withDeadline(() => readOnlyCommand(assertion.command), deadline, () => logicalNow(now, startedAt, 0), 'HEADLESS_TIMEOUT'); }
		catch (error) { if (error?.code !== 'HEADLESS_TIMEOUT') throw error; }
	}
	let attempt = 0;
	const currentAudit = () => auditRows(protocolAudit).slice(protocolAuditOffset);
	let fileEvidence;
	let byAgent;
	const evaluate = async () => {
		fileEvidence = await readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath, evidenceOffsets);
		byAgent = new Map();
		for (const member of members.filter((entry) => entry.agentId !== null)) {
			const isolated = isolateFileEvidence(fileEvidence, member.agentId, member.generatedName);
			const audit = currentAudit().filter((row) => rowMatchesAgent(row, member.agentId));
			const evidence = makeEvidence({ terminalState: member.lifecycle, protocolAudit: audit, ...isolated, rcon: rconEvidence });
			byAgent.set(member.agentId, { fileEvidence: isolated, evidence, assertionResult: evaluateHeadlessAssertions(assertions, evidence) });
		}
	};
	await evaluate();
	const waitsForFiles = assertions.some((assertion) => assertion.type !== 'lifecycle' && assertion.type !== 'rcon');
	while (waitsForFiles && [...byAgent.values()].some((entry) => !entry.assertionResult.passed) && logicalNow(now, startedAt, attempt) < deadline) {
		try { await withDeadline(() => poll({ phase: 'evidence', attempt, deadline, now }), deadline, () => logicalNow(now, startedAt, attempt), 'HEADLESS_TIMEOUT'); }
		catch (error) { if (error?.code === 'HEADLESS_TIMEOUT') break; throw error; }
		attempt += 1;
		await evaluate();
	}
	return { fileEvidence, byAgent };
}

function isolateFileEvidence(fileEvidence, agentId, generatedName) {
	const providerTurnSummaries = (fileEvidence.providerTurnSummaries ?? []).filter((row) => row.agentId === agentId).slice(-256);
	const lines = String(fileEvidence.serverLog ?? '').split(/\r?\n/).filter((line) => line.includes(agentId) || line.includes(generatedName)).slice(-256);
	return {
		protocolRows: (fileEvidence.protocolRows ?? []).filter((row) => rowMatchesAgent(row, agentId)).slice(-256),
		traceRows: (fileEvidence.traceRows ?? []).filter((row) => rowMatchesAgent(row, agentId)).slice(-256),
		serverLog: lines.join('\n'), paths: fileEvidence.paths,
		providerTurnsRows: providerTurnSummaries.length, providerTurnSummaries,
	};
}

function isolateRosterFileEvidence(fileEvidence, members) {
	const ids = new Set(members.map((member) => member.agentId).filter(Boolean));
	const labels = [...ids, ...members.map((member) => member.generatedName)];
	const matches = (row) => [...ids].some((agentId) => rowMatchesAgent(row, agentId));
	const limit = 256 * Math.max(1, ids.size);
	const providerTurnSummaries = (fileEvidence.providerTurnSummaries ?? []).filter((row) => ids.has(row.agentId)).slice(-limit);
	const lines = String(fileEvidence.serverLog ?? '').split(/\r?\n/).filter((line) => labels.some((value) => line.includes(value))).slice(-limit);
	return {
		protocolRows: (fileEvidence.protocolRows ?? []).filter(matches).slice(-limit),
		traceRows: (fileEvidence.traceRows ?? []).filter(matches).slice(-limit),
		serverLog: lines.join('\n'), paths: fileEvidence.paths,
		providerTurnsRows: providerTurnSummaries.length, providerTurnSummaries,
	};
}

function isolateLegacyFileEvidence(fileEvidence) {
	const providerTurnSummaries = (fileEvidence.providerTurnSummaries ?? []).filter((row) => !rowHasAgentIdentity(row)).slice(-256);
	return {
		protocolRows: (fileEvidence.protocolRows ?? []).filter((row) => !rowHasAgentIdentity(row)).slice(-256),
		traceRows: (fileEvidence.traceRows ?? []).filter((row) => !rowHasAgentIdentity(row)).slice(-256),
		serverLog: fileEvidence.serverLog, paths: fileEvidence.paths,
		providerTurnsRows: providerTurnSummaries.length, providerTurnSummaries,
	};
}

function emptyFileEvidence(fileEvidence) {
	return { protocolRows: [], traceRows: [], serverLog: '', paths: fileEvidence.paths, providerTurnsRows: 0, providerTurnSummaries: [] };
}

function rowMatchesAgent(row, agentId) {
	if (!row || typeof row !== 'object') return false;
	return row.agentId === agentId || row.envelope?.agentId === agentId || row.payload?.agentId === agentId || row.envelope?.payload?.agentId === agentId;
}

function rowHasAgentIdentity(row) {
	return boundedAgentId(row?.agentId ?? row?.envelope?.agentId ?? row?.payload?.agentId ?? row?.envelope?.payload?.agentId) !== null;
}

async function readEvidence(directory, readFile, tailReader, protocolAudit, providerTurnsPath = null, offsets = {}) {
	const { protocol: protocolPath, coordinator: coordinatorPath, server: serverPath, providerTurns } = resolveEvidencePaths(directory, protocolAudit, providerTurnsPath);
	const [protocolText, coordinatorText, serverLog, providerTurnsText] = await Promise.all([
		readBoundedTail(tailReader, protocolPath, offsets.protocol), readBoundedTail(tailReader, coordinatorPath, offsets.coordinator), readBoundedTail(tailReader, serverPath, offsets.server),
		providerTurns === null ? '' : readBoundedTail(tailReader, providerTurns, offsets.providerTurns),
	]);
	const providerTurnRows = providerTurns === null ? [] : parseJsonl(providerTurnsText);
	return {
		protocolRows: parseJsonl(protocolText), traceRows: parseJsonl(coordinatorText), serverLog,
		paths: { protocol: protocolPath, coordinator: coordinatorPath, server: serverPath, ...(providerTurns === null ? {} : { providerTurns }) },
		providerTurnsRows: providerTurnRows.length,
		providerTurnSummaries: providerTurnRows.map(providerTurnSummary).filter(Boolean),
	};
}

function resolveEvidencePaths(directory, protocolAudit, providerTurnsPath) {
	return {
		protocol: typeof protocolAudit === 'string' ? protocolAudit : path.join(directory, 'protocol.jsonl'),
		coordinator: path.join(directory, 'coordinator.jsonl'),
		server: path.join(directory, 'server.log'),
		providerTurns: typeof providerTurnsPath === 'string' ? providerTurnsPath : null,
	};
}

async function captureEvidenceOffsets(paths, fileSize) {
	const entries = await Promise.all(Object.entries(paths).map(async ([key, file]) => {
		if (file === null) return [key, 0];
		try {
			const size = Number(await fileSize(file));
			return [key, Number.isSafeInteger(size) && size >= 0 ? size : 0];
		} catch { return [key, 0]; }
	}));
	return Object.fromEntries(entries);
}

function evidenceSummary(directory, fileEvidence, protocolAudit) {
	const paths = fileEvidence.paths ?? { protocol: path.join(directory, 'protocol.jsonl'), coordinator: path.join(directory, 'coordinator.jsonl'), server: path.join(directory, 'server.log') };
	return { paths, excerpts: { server: boundedText(fileEvidence.serverLog ?? '', 1024) }, auditRows: fileEvidence.protocolRows?.length ?? auditRows(protocolAudit).length, providerTurnsRows: fileEvidence.providerTurnsRows ?? 0 };
}

function timingSummary(profile, fileEvidence, protocolAudit, scenarioElapsedMs) {
	const rows = [...(fileEvidence.protocolRows ?? []), ...auditRows(protocolAudit)].map(unwrapAuditRow).filter(Boolean);
	const healthByOperation = new Map();
	let control = [];
	for (const row of rows) {
		if (row.type !== 'coordinator_status' || !row.payload || typeof row.payload !== 'object') continue;
		for (const circuit of Array.isArray(row.payload.circuits) ? row.payload.circuits : []) {
			if (circuit?.provider !== profile.provider || circuit?.model !== profile.model || typeof circuit.operation !== 'string') continue;
			healthByOperation.set(circuit.operation, metricSummary(circuit, ['operation', 'count', 'p50Ms', 'p95Ms', 'failureRate', 'circuit']));
		}
		if (Array.isArray(row.payload.latencies) && row.payload.latencies.length > 0) {
			control = row.payload.latencies.slice(0, 32).map((entry) => metricSummary(entry, ['operation', 'count', 'p50Ms', 'p95Ms']));
		}
	}
	return {
		scenarioElapsedMs: finiteMetric(scenarioElapsedMs),
		turns: (fileEvidence.providerTurnSummaries ?? []).filter((turn) => turn.provider === profile.provider && turn.model === profile.model).slice(0, 64),
		health: [...healthByOperation.values()].slice(0, 32),
		control,
	};
}

function performanceMetrics(profile, fileEvidence, protocolAudit, agentId = null, runtimeResources = {}) {
	const agentIds = Array.isArray(agentId) ? new Set(agentId) : null;
	const turns = (fileEvidence.providerTurnSummaries ?? []).filter((turn) => turn.provider === profile.provider && turn.model === profile.model
		&& (agentIds === null ? (agentId === null ? turn.agentId === undefined : turn.agentId === agentId) : agentIds.has(turn.agentId)));
	const rawRows = [...(fileEvidence.protocolRows ?? []), ...auditRows(protocolAudit)];
	const envelopes = rawRows.map(unwrapAuditRow).filter(Boolean);
	const queue = turns.map((turn) => turn.queueWaitMs).filter(Number.isFinite);
	const inference = turns.map((turn) => Number.isFinite(turn.apiDurationMs) ? turn.apiDurationMs : turn.durationMs).filter(Number.isFinite);
	const observation = envelopes.flatMap((row) => {
		const value = row.type === 'observation' ? row.payload?.metrics?.collectionMs ?? row.payload?.metrics?.observationMs : null;
		return Number.isFinite(value) && value >= 0 ? [value] : [];
	});
	const authoritativeResult = actionResultObservationLatencies(rawRows);
	const result = authoritativeResult.length > 0 ? authoritativeResult : (fileEvidence.traceRows ?? []).flatMap((row) => {
		const value = row?.event === 'program_step' && row?.result !== null ? row?.timing?.bridgeSendToCompletionMs : null;
		return Number.isFinite(value) && value >= 0 ? [value] : [];
	});
	const tokenRows = turns.map((turn) => turn.tokens ?? null);
	return {
		latencyMs: {
			queue: latencyPercentiles(queue), inference: latencyPercentiles(inference),
			observation: latencyPercentiles(observation), result: latencyPercentiles(result),
		},
		tokens: Object.fromEntries(['input', 'output', 'reasoning', 'cached', 'cacheWrite'].map((category) => [category, completeTokenTotal(tokenRows, category)])),
		retries: turns.filter((turn) => turn.retry).length,
		rateLimits: turns.filter((turn) => turn.rateLimited || /RATE.?LIMIT|\b429\b/i.test(turn.error?.code ?? '')).length,
		compactions: turns.filter((turn) => turn.compaction).length,
		resources: { ...resourceMetrics(envelopes),
			...(Number.isFinite(runtimeResources.minecraftMspt) && runtimeResources.minecraftMspt >= 0 ? { minecraftMspt: finiteMetric(runtimeResources.minecraftMspt) } : {}) },
	};
}

function latencyPercentiles(values) {
	const sorted = values.filter((value) => Number.isFinite(value) && value >= 0).map(finiteMetric).sort((left, right) => left - right);
	return {
		count: sorted.length,
		p50: sorted.length === 0 ? null : nearestRank(sorted, 0.5),
		p95: sorted.length === 0 ? null : nearestRank(sorted, 0.95),
		p99: sorted.length === 0 ? null : nearestRank(sorted, 0.99),
	};
}

function nearestRank(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function completeTokenTotal(rows, category) {
	if (rows.length === 0 || rows.some((row) => row === null || !Number.isSafeInteger(row[category]) || row[category] < 0)) return null;
	const total = rows.reduce((sum, row) => sum + row[category], 0);
	return Number.isSafeInteger(total) ? total : null;
}

function actionResultObservationLatencies(rows) {
	const events = rows.flatMap((row) => {
		const envelope = unwrapAuditRow(row);
		const timestamp = finiteTimestamp(row?.timestamp ?? envelope?.timestamp);
		const agentId = boundedAgentId(envelope?.agentId ?? envelope?.payload?.agentId);
		return timestamp === null || agentId === null || !['action_result', 'observation'].includes(envelope?.type) ? [] : [{ timestamp, agentId, type: envelope.type }];
	}).sort((left, right) => left.timestamp - right.timestamp);
	const values = [];
	for (let index = 0; index < events.length; index += 1) {
		const event = events[index];
		if (event.type !== 'action_result') continue;
		const next = events.slice(index + 1).find((candidate) => candidate.agentId === event.agentId && candidate.type === 'observation' && candidate.timestamp >= event.timestamp);
		if (next !== undefined) values.push(next.timestamp - event.timestamp);
	}
	return values;
}

function finiteTimestamp(value) {
	return Number.isFinite(value) && value >= 0 ? value : null;
}

function resourceMetrics(rows) {
	const candidates = rows.flatMap((row) => {
		const value = row.type === 'headless_resource_metrics' ? row.payload : row.payload?.resources;
		return value && typeof value === 'object' && !Array.isArray(value) ? [value] : [];
	});
	return {
		processCount: maximumMetric(candidates, 'processCount', true),
		peakRssBytes: maximumMetric(candidates, 'peakRssBytes', true),
		minecraftMspt: maximumMetric(candidates, 'minecraftMspt', false),
	};
}

function maximumMetric(rows, key, integer) {
	const values = rows.map((row) => row[key]).filter((value) => Number.isFinite(value) && value >= 0 && (!integer || Number.isSafeInteger(value)));
	return values.length === 0 ? null : finiteMetric(Math.max(...values));
}

function parseMinecraftMspt(value) {
	const match = String(value ?? '').match(/([0-9]+(?:\.[0-9]+)?)\s*(?:mspt|ms\s+per\s+tick)/i);
	return match === null ? null : finiteMetric(Number(match[1]));
}

function providerTurnSummary(row) {
	if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
	const summary = {
		provider: boundedScalar(row.provider), model: boundedScalar(row.model), reasoningEffort: boundedScalar(row.reasoningEffort),
		attempt: Number.isSafeInteger(row.attempt) ? row.attempt : null, retry: row.retry === true,
		timestamp: Number.isFinite(row.timestamp) ? Math.round(row.timestamp) : null, outcome: boundedScalar(row.outcome),
	};
	const agentId = boundedAgentId(row.agentId);
	if (agentId !== null) summary.agentId = agentId;
	if (row.timing && typeof row.timing === 'object' && !Array.isArray(row.timing)) {
		summary.durationMs = finiteMetric(row.timing.durationMs);
		summary.apiDurationMs = finiteMetric(row.timing.apiDurationMs);
		if (Number.isFinite(row.timing.queueWaitMs) && row.timing.queueWaitMs >= 0) summary.queueWaitMs = finiteMetric(row.timing.queueWaitMs);
	}
	if (row.tokens && typeof row.tokens === 'object' && !Array.isArray(row.tokens)) {
		summary.tokens = Object.fromEntries(['input', 'output', 'reasoning', 'cached', 'cacheWrite'].map((category) => [category,
			Number.isSafeInteger(row.tokens[category]) && row.tokens[category] >= 0 ? row.tokens[category] : null]));
	}
	if (row.rateLimited !== undefined) summary.rateLimited = row.rateLimited === true;
	if (row.compaction !== undefined) summary.compaction = row.compaction === true;
	if (row.error && typeof row.error === 'object' && !Array.isArray(row.error)) summary.error = safeProviderError(row.error);
	return summary;
}

const PROVIDER_ERROR_CATEGORIES = new Set(['decision_parse', 'rate_limit', 'timeout', 'cancelled', 'transport', 'provider']);

function safeProviderError(value) {
	const rawCode = typeof value?.code === 'string' && /^[A-Z0-9_]{1,128}$/.test(value.code) ? value.code : 'PROVIDER_ERROR';
	const category = PROVIDER_ERROR_CATEGORIES.has(value?.category) ? value.category : providerErrorCategory(rawCode);
	return { code: rawCode, category };
}

function providerErrorCategory(code) {
	if (/DECISION|PLANNER_OUTPUT/.test(code)) return 'decision_parse';
	if (/RATE|LIMIT/.test(code)) return 'rate_limit';
	if (/TIMEOUT/.test(code)) return 'timeout';
	if (/CANCEL|STALE/.test(code)) return 'cancelled';
	if (/RPC|TRANSPORT|PROCESS|SPAWN/.test(code)) return 'transport';
	return 'provider';
}

function metricSummary(value, keys) {
	const summary = {};
	for (const key of keys) {
		if (key === 'operation' || key === 'circuit') summary[key] = boundedScalar(value?.[key]);
		else summary[key] = finiteMetric(value?.[key]);
	}
	return summary;
}

function finiteMetric(value) {
	return Number.isFinite(value) && value >= 0 ? Math.round(value * 1000) / 1000 : null;
}

async function readBoundedTail(readTail, file, offset = 0) {
	try { return boundedTailText(await readTail(file, MAX_EVIDENCE_TAIL_BYTES, offset), MAX_EVIDENCE_TAIL_BYTES); } catch { return ''; }
}

function parseJsonl(value) {
	return String(value ?? '').split(/\r?\n/).filter(Boolean).flatMap((line) => { try { return [JSON.parse(line)]; } catch { return []; } });
}

function auditRows(source) {
	if (Array.isArray(source)) return source;
	if (source && Array.isArray(source.rows)) return source.rows;
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

function generatedAgentName(scenario, timestamp, rosterIndex = null) {
	const id = String(scenario.id ?? 'scenario').replace(/[^A-Za-z0-9_]/g, '_').slice(0, 32) || 'scenario';
	const suffix = rosterIndex === null ? '' : `_${String(rosterIndex + 1).padStart(2, '0')}`;
	const base = `headless_${id}_${Math.abs(Number(timestamp) || 0).toString(36)}`;
	return `${base.slice(0, 32 - suffix.length)}${suffix}`;
}

function rosterPosition(index) {
	const column = index % 4;
	const row = Math.floor(index / 4);
	return { x: -5.5 + column * 4, z: -5.5 + row * 4 };
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
function isSkippedResponse(value) { return /(?:SKIP|unavailable|not logged in|not installed|profile unavailable|catalog unavailable|catalog\s+rejected)/i.test(String(value ?? '')); }
function isAcceptedResponse(value) { return /^Created .+\. It is ready for a task\.$/i.test(String(value ?? '').trim()); }
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
	if (![1, 8, 16].includes(scenario.rosterSize ?? 1)) throw new RangeError('scenario.rosterSize is out of bounds');
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

async function defaultFileSize(file) {
	const handle = await defaultOpen(file, 'r');
	try { return Number((await handle.stat()).size); }
	finally { await handle.close(); }
}

async function defaultReadTail(file, maxBytes, startOffset = 0) {
	const handle = await defaultOpen(file, 'r');
	try {
		const stats = await handle.stat();
		const end = Number(stats.size);
		const requested = Number.isSafeInteger(startOffset) && startOffset >= 0 ? startOffset : 0;
		const minimum = requested <= end ? requested : 0;
		const position = Math.max(minimum, end - maxBytes);
		const size = end - position;
		const buffer = Buffer.alloc(size);
		if (size > 0) await handle.read(buffer, 0, size, position);
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
