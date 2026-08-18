import assert from 'node:assert/strict';
import test from 'node:test';
import {
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
