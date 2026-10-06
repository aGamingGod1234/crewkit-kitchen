import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { appendFile, mkdtemp, rename, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
	normalizeHeadlessMatrix,
	normalizeHeadlessScenario,
	selectHeadlessScenarios,
	scenarioReport,
	writeHeadlessCliFailure,
	runHeadlessScenario,
} from '../src/headless-matrix.mjs';

const validScenario = (overrides = {}) => ({
	id: 'codex-chat-completion', provider: 'codex', model: 'gpt-5.6-sol',
	reasoningEffort: 'high', serviceTier: 'fast', task: 'Send HEADLESS_PASS',
	timeoutMs: 180000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }], ...overrides,
});

const evidenceProfile = { provider: 'codex', model: 'fixture', reasoningEffort: 'low', serviceTier: 'priority' };
const evidenceTurn = (overrides = {}) => ({ ...evidenceProfile, agentId: 'fixture-agent', tokens: { input: 100, output: 1, reasoning: 0, cached: 0, cacheWrite: 0 }, ...overrides });
const evidenceJsonl = (rows) => rows.map((row) => JSON.stringify(row)).join('\n') + '\n';

async function runEvidenceFixture(options = {}) {
	const directory = await mkdtemp(path.join(tmpdir(), 'headless-evidence-'));
	try {
		return await runEvidenceFixtureInDirectory(directory, options);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function runEvidenceFixtureInDirectory(directory, { turns = [evidenceTurn()], durableCount = 0, rotate = false, corrupt = false, loseStart = false, captureLoss = false, mismatch = false, delayedResult = false, assertions = [], prior = '', gameplay = 'PASSED', requestedProfile = evidenceProfile }) {
	const protocolPath = path.join(directory, 'protocol.jsonl');
	const providerPath = path.join(directory, 'provider.jsonl');
	await writeFile(protocolPath, '');
	await writeFile(providerPath, prior);
	const scenario = normalizeHeadlessScenario({
		id: 'complete-evidence', ...requestedProfile, task: 'Obtain a diamond pickaxe', timeoutMs: 10_000,
		world: { mode: 'natural', seed: '1' }, requireFactualSuccess: true,
		assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'data get entity {agent} Inventory', match: 'diamond_pickaxe' }, ...assertions],
	});
	const worldManifest = { version: 1, scenarioId: scenario.id, worldId: 'headless-isolated-fixture', fresh: true, world: scenario.world,
		savedSpawn: { source: 'level.dat', dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
		spawnLoading: { operation: 'temporary_spawn_chunk_loading', x: 0, z: 0, ready: true, elapsedMs: 1, terrainModified: false, inventoryModified: false },
	};
	let started = false, time = 100;
	return runHeadlessScenario({ scenario, worldManifest, runDirectory: directory,
		providerTurnsPath: providerPath, protocolAudit: protocolPath, now: () => time, poll: async () => {},
		...(gameplay === 'TIMEOUT' ? { onCleanup: async () => 11100 } : {}),
		rcon: { close: async () => {}, command: async (command) => {
			if (command === 'seed') return { text: 'Seed: [1]' };
			if (command === 'difficulty') return { text: 'The difficulty is normal' };
			if (command.includes(' if loaded ')) return { text: 'The time is 1' };
			if (command.includes('summon-configured')) {
				const name = command.split(' ').at(-1);
				const envelope = (type, payload) => ({ runId: 'fixture-run', scenarioId: scenario.id, envelope: { type, agentId: 'fixture-agent', payload } });
				const rows = [envelope('agent_registered', { agentId: 'fixture-agent', name, ...requestedProfile }),
					envelope('chat', { message: 'EARLY_SUCCESS' }),
					envelope('action_command', { actionId: 'early-action', actionType: 'move', arguments: { x: 1 } }),
					...(delayedResult ? Array.from({ length: 300 }, (_, index) => envelope('action_command', { actionId: `pending-${index}`, actionType: 'move', arguments: { x: 1 } })) : [envelope('action_result', { actionId: 'early-action', state: 'SUCCEEDED' })]),
					...(mismatch ? [envelope('agent_snapshot', { agentId: 'fixture-agent', name, ...evidenceProfile, reasoningEffort: 'high' })] : []),
					...(captureLoss ? [envelope('coordinator_status', { components: [{ component: 'provider_audit', state: 'ready', incompleteCapture: true, droppedCount: 1 }] })] : []),
				];
				await appendFile(protocolPath, evidenceJsonl(rows));
				if (rotate) await rename(protocolPath, `${protocolPath}.1`);
				await appendFile(protocolPath, evidenceJsonl(Array.from({ length: durableCount }, (_, index) => envelope('action_result', { actionId: `later-${index}`, state: 'SUCCEEDED' }))));
				if (delayedResult) await appendFile(protocolPath, evidenceJsonl([envelope('action_result', { actionId: 'early-action', state: 'SUCCEEDED' })]));
				if (loseStart) await rename(providerPath, path.join(directory, 'unretained-provider-prefix'));
				if (rotate && !loseStart) {
					await appendFile(providerPath, evidenceJsonl(turns.slice(0, 1)));
					await rename(providerPath, `${providerPath}.1`);
					await writeFile(providerPath, evidenceJsonl(turns.slice(1)));
				} else await appendFile(providerPath, evidenceJsonl(turns));
				if (corrupt) await appendFile(providerPath, '{"tokens":');
				return { text: `Created ${name}. It is ready for a task.` };
			}
			if (command.startsWith('codex start ')) started = true;
			if (command.startsWith('codex status ')) {
				if (gameplay === 'TIMEOUT') { time = 10100; return { text: 'state=RUNNING' }; }
				return { text: gameplay === 'DEAD' ? 'state=DEAD' : 'state=COMPLETED' };
			}
			if (command.startsWith('codex stop ')) return { text: `Stopped ${command.split(' ').at(-1)}.` };
			if (command.endsWith(' Pos')) return { text: 'player has the following entity data: [0.5d, 64.0d, 0.5d]' };
			if (command.endsWith(' Inventory')) return { text: `player has the following entity data: ${started ? '[{id:"minecraft:diamond_pickaxe"}]' : '[]'}` };
			return { text: 'ok' };
		} },
	});
}

test('natural runner accepts Codex Fast attested as priority while retaining raw settings', async () => {
	const requestedProfile = { ...evidenceProfile, serviceTier: 'fast' };
	const report = await runEvidenceFixture({ requestedProfile, turns: [evidenceTurn({ ...requestedProfile,
		executionSettings: { effective: { ...requestedProfile, serviceTier: 'priority' }, evidence: { serviceTier: 'provider_reported' } },
	})] });
	assert.equal(report.classification, 'PASSED', report.diagnostics);
	assert.equal(report.settings.requested.serviceTier, 'fast');
	assert.equal(report.settings.effective.serviceTier, 'priority');
	assert.equal(report.factualSuccess, true);
	assert.equal(report.cleanup.status, 'CLEAN');
});

test('explicit provider contradiction invalidates every gameplay outcome without losing factual results', async () => {
	for (const gameplay of ['DEAD', 'TIMEOUT', 'PASSED']) for (const mismatch of [true, false]) {
		const turns = [evidenceTurn(mismatch ? { executionSettings: {
			effective: { ...evidenceProfile, model: 'different-effective-model' },
			evidence: { model: 'provider_reported', reasoningEffort: 'submitted', serviceTier: 'submitted' },
		} } : {})];
		const report = await runEvidenceFixture({ turns, gameplay });
		assert.equal(report.classification, mismatch ? 'PROFILE_MISMATCH' : gameplay, report.diagnostics);
		assert.equal(report.gameplayClassification, gameplay);
		assert.equal(report.factualSuccess, true);
		assert.equal(report.cleanup.status, 'CLEAN');
		assert.equal(report.settings.configuredVerified, true);
		assert.equal(report.settings.effective?.model ?? null, mismatch ? 'different-effective-model' : null);
		if (gameplay === 'DEAD') assert.equal(report.lifecycle, 'DEAD');
		if (gameplay === 'TIMEOUT') assert.equal(report.postRunEvidence.status, 'STOPPED');
	}
});

test('headless evidence retains exact identity and early assertion facts across event volume and rotation', async () => {
	for (const rotate of [false, true]) {
		const report = await runEvidenceFixture({ durableCount: 4200, rotate, assertions: [
			{ type: 'chat', message: 'EARLY_SUCCESS' }, { type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		] });
		assert.equal(report.classification, 'PASSED', report.diagnostics);
		assert.equal(report.settings.configuredVerified, true);
		assert.equal(report.metrics.tokens.input, 100);
		assert.equal(report.evidence.coverage.protocol.complete, true);
	}
	assert.equal((await runEvidenceFixture({ rotate: true, mismatch: true, durableCount: 4200 })).classification, 'PROFILE_MISMATCH');
});

test('headless usage totals include every recorded turn and large private records across rotation', async () => {
	for (const turns of [Array.from({ length: 300 }, () => evidenceTurn({ retry: true, compaction: true, rateLimited: true })),
		Array.from({ length: 3 }, () => evidenceTurn({ input: 'x'.repeat(65536), output: 'y'.repeat(65536) }))]) {
		const report = await runEvidenceFixture({ turns, rotate: true, prior: evidenceJsonl([evidenceTurn({ tokens: { input: 999999 } })]) });
		assert.equal(report.classification, 'PASSED');
		assert.equal(report.metrics.tokens.input, turns.length * 100);
		assert.equal(report.evidence.providerTurnsRows, turns.length);
		assert.equal(report.metrics.usageEvidence.complete, true);
		if (turns.length === 300) for (const field of ['retries', 'compactions', 'rateLimits']) assert.equal(report.metrics[field], 300);
	}
});

test('headless incomplete usage stays unknown for lost prefixes, partial records, and recorder loss', async () => {
	for (const options of [{ corrupt: true }, { loseStart: true }, { captureLoss: true, durableCount: 4200 }]) {
		const report = await runEvidenceFixture(options);
		assert.equal(report.classification, 'PASSED');
		assert.equal(report.metrics.usageEvidence.complete, false);
		assert.ok(report.metrics.usageEvidence.reasons.length > 0);
		assert.equal(report.metrics.tokens.input, null);
		assert.equal(report.metrics.retries, null);
	}
});

test('headless usage excludes other agents, scenarios and explicit profiles without inventing missing tokens', async () => {
	const report = await runEvidenceFixture({ turns: [evidenceTurn(), evidenceTurn({ agentId: 'unrelated' }),
		evidenceTurn({ scenarioId: 'other-run' }), evidenceTurn({ runId: 'unrelated-run' }), evidenceTurn({ reasoningEffort: 'high' }), evidenceTurn({ serviceTier: 'fast' }),
		evidenceTurn({ tokens: { input: null, output: 2 } }),
	] });
	assert.equal(report.classification, 'PASSED');
	assert.equal(report.metrics.usageEvidence.recordedTurns, 2);
	assert.equal(report.metrics.tokens.input, null);
	assert.equal(report.metrics.tokens.output, 3);
});

test('native observed counter intervals never become a complete run bill', async () => {
	const report = await runEvidenceFixture({ turns: [20, 30].map((input) => evidenceTurn({
		tokens: { input, output: 1, reasoning: 0, cached: 0, cacheWrite: 0 },
		usage: { scope: 'observed_thread_counter_delta', status: 'available', attributionComplete: false },
	})) });
	assert.equal(report.metrics.usageEvidence.complete, true);
	assert.equal(report.metrics.usageEvidence.attributionComplete, false);
	assert.equal(report.metrics.usageEvidence.billingComplete, null);
	assert.equal(report.metrics.usageEvidence.measurementScope, 'observed_thread_counter_delta');
	assert.equal(report.metrics.usageEvidence.measurementStatuses.available, 2);
	assert.equal(report.metrics.tokens.input, null);
	assert.equal(report.metrics.observedTokens.input, 50);
});

test('bounded assertion join rescans overflowed command history without losing an early successful pair', async () => {
	const report = await runEvidenceFixture({ delayedResult: true, durableCount: 4200, rotate: true,
		assertions: [{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' }] });
	assert.equal(report.classification, 'PASSED');
	assert.equal(report.evidence.coverage.protocol.complete, true);
});

test('headless CLI exception writer redacts and bounds the stack it emits', () => {
	const writes = [];
	const error = new Error('Authorization: Bearer cli-secret');
	error.stack = `Error: Authorization: Bearer cli-secret\n at C:\\private\\headless.mjs:1:2\n${'x'.repeat(8_000)}`;
	writeHeadlessCliFailure(error, (line) => writes.push(line));
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /cli-secret|private/);
	assert.ok(Buffer.byteLength(writes[0], 'utf8') <= 4_097);
});

test('normalizes one bounded real-provider scenario', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario({ setupBlocks: [{ x: 2, y: 201, z: 0, blockId: 'minecraft:oak_log' }] })] });
	assert.deepEqual(matrix.scenarios[0].assertions, [{ type: 'lifecycle', state: 'COMPLETED' }]);
	assert.deepEqual(matrix.scenarios[0].setupBlocks, [{ x: 2, y: 201, z: 0, blockId: 'minecraft:oak_log' }]);
	assert.equal(matrix.scenarios[0].rosterSize, 1);
	assert.equal(matrix.version, 1);
	assert.ok(Object.isFrozen(matrix));
	assert.ok(Object.isFrozen(matrix.scenarios[0]));
	assert.throws(() => normalizeHeadlessScenario(validScenario({ provider: 'gemini' })), /unsupported provider/i);
});

test('normalizes only supported concurrent roster sizes', () => {
	for (const rosterSize of [1, 8, 16]) {
		assert.equal(normalizeHeadlessScenario(validScenario({ rosterSize })).rosterSize, rosterSize);
	}
	for (const rosterSize of [0, 2, 7, 9, 17, '8']) {
		assert.throws(() => normalizeHeadlessScenario(validScenario({ rosterSize })), /rosterSize/i);
	}
});

test('accepts Claude scenarios through the same matrix schema', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [
		validScenario({ id: 'claude-opus', provider: 'claude', model: 'claude-opus-5-5', reasoningEffort: 'high', serviceTier: 'priority' }),
		validScenario({ id: 'claude-fable', provider: 'claude', model: 'claude-fable-5-1', reasoningEffort: 'low', serviceTier: 'priority' }),
	] });
	assert.deepEqual(matrix.scenarios.map((scenario) => scenario.provider), ['claude', 'claude']);
	assert.throws(() => normalizeHeadlessScenario(validScenario({ provider: 'cursor' }), 0), /unsupported provider 'cursor'/);
});

test('checked-in live matrix covers every native provider with real model and setting combinations', () => {
	const matrix = normalizeHeadlessMatrix(JSON.parse(readFileSync(new URL('../config/headless-provider-matrix.json', import.meta.url), 'utf8')));
	assert.equal(matrix.scenarios.length, 9);
	const codex = matrix.scenarios.filter((scenario) => scenario.provider === 'codex');
	for (const model of new Set(codex.map((scenario) => scenario.model))) {
		assert.ok(new Set(codex.filter((scenario) => scenario.model === model).map((scenario) => `${scenario.reasoningEffort}/${scenario.serviceTier}`)).size >= 2, `codex/${model} needs two settings`);
	}
	const claude = matrix.scenarios.filter((scenario) => scenario.provider === 'claude');
	assert.deepEqual(claude.map((scenario) => scenario.model), ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-fable-5-1']);
	assert.deepEqual(claude.map((scenario) => scenario.reasoningEffort).sort(), ['high', 'low', 'medium']);
	assert.ok(claude.every((scenario) => scenario.serviceTier === 'priority'));
	assert.ok(matrix.scenarios.every((scenario) => ['codex-luna-xhigh-fast-mine-oak-log', 'codex-luna-xhigh-fast-wooden-pickaxe'].includes(scenario.id)
		? scenario.requireFactualSuccess && scenario.assertions.some((assertion) => assertion.type === 'rcon')
		: scenario.id === 'codex-sol-low-priority'
			? scenario.assertions.some((assertion) => assertion.type === 'action' && assertion.actionType === 'navigate_to')
			: ['chat', 'action'].every((type) => scenario.assertions.some((assertion) => assertion.type === type))));
	assert.deepEqual([...new Set(matrix.scenarios.map((scenario) => scenario.rosterSize))].sort((left, right) => left - right), [1, 8, 16]);
});

test('rejects duplicate IDs, unknown assertion types, and unbounded timeouts', () => {
	assert.throws(() => normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario()] }), /duplicate/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: '../escape' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\\id' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\u0000id' }, 0), /id|control/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), assert: [{ type: 'unknown' }] }, 0), /assert/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 0 }, 0), /timeout/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 900001 }, 0), /timeout/i);
});

test('supports every bounded assertion shape and rejects unknown keys', () => {
	const assertions = [
		{ type: 'lifecycle', state: 'ERROR' },
		{ type: 'chat', message: 'marker' },
		{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'data get entity @s Pos', match: '1.0' },
	];
	const normalized = normalizeHeadlessScenario(validScenario({ assert: assertions }), 0);
	assert.deepEqual(normalized.assertions, assertions);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), extra: true }, 0), /unknown|key/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), setupBlocks: [{ x: 0, y: 201, z: 0, blockId: 'minecraft:air' }] }, 0), /non-air/i);
});

test('selects all scenarios or one exact ID', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario({ id: 'codex-move-chat' })] });
	assert.equal(selectHeadlessScenarios(matrix, null).length, 2);
	assert.equal(selectHeadlessScenarios(matrix, 'codex-move-chat')[0].id, 'codex-move-chat');
	assert.throws(() => selectHeadlessScenarios(matrix, 'missing'), /scenario|id/i);
});

test('creates immutable bounded serializable reports', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const report = scenarioReport('PASSED', scenario, {
		elapsedMs: 12, diagnostics: 'x'.repeat(10000), assertions: [{ type: 'lifecycle', passed: true }],
	});
	assert.equal(report.status, 'PASSED');
	assert.equal(report.scenarioId, scenario.id);
	assert.ok(report.diagnostics.length <= 4096);
	assert.ok(Object.isFrozen(report));
	assert.doesNotThrow(() => JSON.stringify(report));
});

test('replaces deeply nested report values at the depth bound', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const payload = {};
	let cursor = payload;
	for (let index = 0; index < 8; index += 1) {
		cursor.child = {};
		cursor = cursor.child;
	}
	cursor.secret = 'credential-shaped-' + 'x'.repeat(10000);
	const report = scenarioReport('PASSED', scenario, { payload });
	let bounded = report.payload;
	for (let index = 0; index < 7; index += 1) bounded = bounded.child;
	assert.equal(bounded, '[TRUNCATED]');
	assert.ok(JSON.stringify(report).length < 10000);
});

test('headless reports redact every launcher and account credential alias', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const aliases = ['launcherAccount', 'launcher_account', 'launcher-account', 'launcheraccount', 'accountData', 'account_data', 'account-data', 'accountdata'];
	for (const [index, alias] of aliases.entries()) {
		const secret = `headless-private-value-${index}`;
		const report = scenarioReport('FAILED', scenario, { evidence: { [alias]: secret } });
		assert.equal(report.evidence[alias], '[REDACTED]', `${alias} was not redacted`);
		assert.doesNotMatch(JSON.stringify(report), new RegExp(secret));
	}
});

test('headless diagnostics redact file URIs while preserving operational text', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	for (const location of [
		'file:///C:/Users/lucas/Arena%20Agents/secret.json',
		'file:///var/lib/arena%20agents/secret.json',
		'file://server/share/Arena%20Agents/secret.json',
	]) {
		const report = scenarioReport('FAILED', scenario, { diagnostics: `failure at ${location}` });
		assert.equal(report.diagnostics.includes(location), false, `${location} leaked`);
		assert.match(report.diagnostics, /\[location redacted\]/);
	}
	const operational = 'https://example.com/file:///docs http://127.0.0.1:8766/v1/tts relative/file.txt inputTokens=4';
	assert.equal(scenarioReport('FAILED', scenario, { diagnostics: operational }).diagnostics, operational);
});

test('headless structured reports retain bounded token metrics and sanitize nested text', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const metrics = {
		tokens: { input: 12, output: 3, reasoning: 1, cached: 2, cacheWrite: null },
		inputTokens: 12,
		output_token_count: 3,
		token_budget: 'credential-shaped-budget',
		token_latency_ms: -1,
		note: `file:///var/lib/private/${'x'.repeat(10_000)}`,
		detail: 'x'.repeat(10_000),
	};
	const report = scenarioReport('PASSED', scenario, { metrics });
	assert.deepEqual(report.metrics.tokens, metrics.tokens);
	assert.equal(report.metrics.inputTokens, 12);
	assert.equal(report.metrics.output_token_count, 3);
	assert.equal(report.metrics.token_budget, '[REDACTED]');
	assert.equal(report.metrics.token_latency_ms, '[REDACTED]');
	assert.match(report.metrics.note, /\[location redacted\]/);
	assert.ok(Buffer.byteLength(report.metrics.detail, 'utf8') <= 4096);
	assert.ok(Buffer.byteLength(JSON.stringify(report), 'utf8') < 16_384);
});
