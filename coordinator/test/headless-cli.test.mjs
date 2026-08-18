import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';
import {
	formatHeadlessCliOutput,
	parseHeadlessCliArguments,
	runHeadlessMatrix,
} from '../src/headless-matrix.mjs';

test('parses the headless matrix CLI contract and rejects missing flags', () => {
	const parsed = parseHeadlessCliArguments([
		'--config', 'C:/matrix.json', '--scenario', 'case', '--run-directory', 'C:/runs/case',
		'--rcon-host', '127.0.0.1', '--rcon-port', '25575', '--rcon-password-file', 'C:/runs/pw.txt',
		'--protocol-audit', 'C:/runs/protocol.jsonl', '--provider-turns', 'C:/runs/provider.jsonl', '--require-all',
	]);
	assert.equal(parsed.configPath, 'C:/matrix.json');
	assert.equal(parsed.scenarioId, 'case');
	assert.equal(parsed.rconPort, 25575);
	assert.equal(parsed.requireAll, true);
	assert.throws(() => parseHeadlessCliArguments(['--config', 'relative.json']), /absolute|run-directory|usage/i);
});

test('rejects unknown flags and relative optional artifact paths', () => {
	assert.throws(() => parseHeadlessCliArguments([
		'--config', 'C:/matrix.json', '--run-directory', 'C:/runs/case', '--rcon-port', '25575',
		'--rcon-password-file', 'C:/runs/pw.txt', '--unknown', 'value',
	]), /usage|unknown/i);
	assert.throws(() => parseHeadlessCliArguments([
		'--config', 'C:/matrix.json', '--run-directory', 'C:/runs/case', '--rcon-port', '25575',
		'--rcon-password-file', 'C:/runs/pw.txt', '--protocol-audit', 'protocol.jsonl',
	]), /absolute/i);
});

test('does not construct a provider or RCON client when the password file is absent', async () => {
	let rconConstructed = false;
	await assert.rejects(() => runHeadlessMatrix({
		configPath: 'C:/matrix.json', runDirectory: 'C:/runs/case', rconPort: 25575,
		rconPasswordFile: 'C:/runs/missing-password.txt',
		readFile: async (file) => {
			if (file.endsWith('matrix.json')) return JSON.stringify({ version: 1, scenarios: [{
				id: 'case', provider: 'codex', model: 'm', reasoningEffort: 'low', task: 't', timeoutMs: 1000,
				assert: [{ type: 'lifecycle', state: 'COMPLETED' }],
			}] });
			throw Object.assign(new Error('missing password'), { code: 'ENOENT' });
		},
		rconFactory: () => { rconConstructed = true; return {}; },
	}), /missing password|ENOENT/i);
	assert.equal(rconConstructed, false);
});

test('runs a selected matrix scenario through the injected RCON client and forwards failure status', async () => {
	const writes = [];
	const commands = [];
	const fakeRcon = {
		connect: async () => fakeRcon,
		command: async (command) => {
			commands.push(command);
			if (command.includes('summon-configured')) return { text: 'Created agent. ready' };
			if (command.startsWith('codex start')) return { text: 'started' };
			return { text: command.startsWith('codex status') ? 'state=ERROR' : 'ok' };
		},
		close: async () => {},
	};
	const result = await runHeadlessMatrix({
		configPath: 'C:/matrix.json', scenarioId: 'case', runDirectory: 'C:/runs/case',
		rconHost: '127.0.0.1', rconPort: 25575, rconPasswordFile: 'C:/runs/password.txt',
		readFile: async (file) => file.endsWith('matrix.json')
			? JSON.stringify({ version: 1, scenarios: [{ id: 'case', provider: 'codex', model: 'm', reasoningEffort: 'low', task: 't', timeoutMs: 1000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }] })
			: 'password',
		writeFile: async (file, text) => writes.push({ file, text }),
		mkdir: async () => {},
		rconFactory: () => fakeRcon,
	});
	assert.equal(result.exitCode, 1);
	assert.equal(result.report.status, 'FAILED');
	assert.equal(commands.some((command) => command.includes('summon-configured')), true);
	assert.equal(writes.some(({ file }) => file.endsWith('matrix-report.json')), true);
});

test('runs only the selected scenario', async () => {
	const summoned = [];
	const fakeRcon = {
		connect: async () => fakeRcon,
		command: async (command) => {
			if (command.includes('summon-configured')) summoned.push(command);
			if (command.includes('summon-configured')) return { text: 'Created agent. ready' };
			if (command.startsWith('codex status')) return { text: 'state=COMPLETED' };
			return { text: 'started' };
		},
		close: async () => {},
	};
	const writes = [];
	const result = await runHeadlessMatrix({
		configPath: 'C:/matrix.json', scenarioId: 'selected', runDirectory: 'C:/runs/selected', rconPort: 25575,
		rconPasswordFile: 'C:/runs/password.txt',
		readFile: async (file) => file.endsWith('matrix.json') ? JSON.stringify({ version: 1, scenarios: [
			{ id: 'ignored', provider: 'codex', model: 'm', reasoningEffort: 'low', task: 't', timeoutMs: 1000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] },
			{ id: 'selected', provider: 'codex', model: 'm', reasoningEffort: 'low', task: 't', timeoutMs: 1000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] },
		] }) : 'password',
		writeFile: async (file, text) => writes.push({ file, text }), mkdir: async () => {}, rconFactory: () => fakeRcon,
	});
	assert.equal(result.report.status, 'PASSED');
	assert.equal(summoned.length, 1);
	assert.match(summoned[0], /selected/);
	assert.equal(writes.some(({ file }) => file.endsWith('matrix-report.json')), true);
});

test('classifies skipped profiles as failures only with --require-all', async () => {
	const run = (requireAll) => runHeadlessMatrix({
		configPath: 'C:/matrix.json', runDirectory: 'C:/runs/skip', rconPort: 25575, requireAll,
		rconPasswordFile: 'C:/runs/password.txt',
		readFile: async (file) => file.endsWith('matrix.json') ? JSON.stringify({ version: 1, scenarios: [{
			id: 'skip', provider: 'codex', model: 'm', reasoningEffort: 'low', task: 't', timeoutMs: 1000,
			assert: [{ type: 'lifecycle', state: 'COMPLETED' }],
		}] }) : 'password',
		writeFile: async () => {}, mkdir: async () => {}, rconFactory: () => ({
			connect: async () => {}, command: async (command) => command.includes('summon-configured') ? { text: 'profile unavailable' } : { text: 'ok' }, close: async () => {},
		}),
	});
	const optional = await run(false);
	assert.equal(optional.report.status, 'SKIPPED');
	assert.equal(optional.exitCode, 0);
	const required = await run(true);
	assert.equal(required.report.status, 'FAILED');
	assert.equal(required.report.scenarios[0].classification, 'REQUIRED_PROFILE_UNAVAILABLE');
	assert.equal(required.exitCode, 1);
});

test('help is secret-free and importing the module has no execution side effects', () => {
	const modulePath = path.resolve('src/headless-matrix.mjs');
	const help = spawnSync(process.execPath, [modulePath, '--help'], { encoding: 'utf8' });
	assert.equal(help.status, 0);
	assert.doesNotMatch(help.stdout, /HEADLESS_SECRET|super-secret-value/i);
	assert.match(help.stdout, /--config/);
	const imported = spawnSync(process.execPath, ['--input-type=module', '-e', `import ${JSON.stringify(pathToFileURL(modulePath).href)};`], { encoding: 'utf8' });
	assert.equal(imported.status, 0);
	assert.equal(imported.stdout, '');
	assert.equal(imported.stderr, '');
});

test('formats the report path and one-line summary for every scenario', () => {
	const output = formatHeadlessCliOutput({
		reportPath: 'C:/runs/matrix-report.json', status: 'FAILED', scenarios: [
			{ scenarioId: 'codex-case', status: 'PASSED' },
			{ scenarioId: 'gemini-case', status: 'SKIPPED' },
		],
	});
	assert.match(output, /Report: C:\/runs\/matrix-report\.json/);
	assert.match(output, /^PASSED codex-case$/m);
	assert.match(output, /^SKIPPED gemini-case$/m);
	assert.doesNotMatch(output, /password|token|secret-value/i);
});
