import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const coordinatorRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const privateMissingModule = 'C:\\private\\api-token-secret.mjs';
const maximumDiagnosticBytes = 8 * 1_024;

function runCli(script, args, options = {}) {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, [path.join(coordinatorRoot, 'src', script), ...args], {
			cwd: coordinatorRoot,
			env: { ...process.env, ...options.env },
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true,
		});
		let stdout = '';
		let stderr = '';
		const timeout = setTimeout(() => child.kill(), 10_000);
		child.stdout.on('data', (chunk) => { stdout += chunk; });
		child.stderr.on('data', (chunk) => { stderr += chunk; });
		child.on('error', reject);
		child.on('close', (code, signal) => {
			clearTimeout(timeout);
			resolve({ code, signal, stdout, stderr });
		});
	});
}

function assertBoundedSanitizedFailure(result, expectedCode = null) {
	assert.equal(result.signal, null);
	assert.equal(result.code, 1);
	const output = `${result.stdout}${result.stderr}`;
	assert.ok(Buffer.byteLength(output, 'utf8') <= maximumDiagnosticBytes, `diagnostic was ${Buffer.byteLength(output, 'utf8')} bytes`);
	assert.doesNotMatch(output, /C:\\private|api-token-secret/i);
	assert.doesNotMatch(output, /Users\\lucas|\.worktrees|coordinator\\src/i);
	assert.equal(result.stderr, '');
	const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean);
	assert.equal(lines.length, 1);
	const failure = JSON.parse(lines[0]);
	assert.equal(failure.status, 'FAILED');
	assert.match(failure.code, /^[A-Z][A-Z0-9_:-]{0,63}$/);
	if (expectedCode !== null) assert.equal(failure.code, expectedCode);
	assert.equal(typeof failure.message, 'string');
	assert.equal(typeof failure.stack, 'string');
}

test('native A/B trial contains missing service-module failures at the CLI root', async () => {
	const first = await runCli('native-tool-ab-trial.mjs', [privateMissingModule, 'baseline', '1']);
	const second = await runCli('native-tool-ab-trial.mjs', [privateMissingModule, 'baseline', '1']);
	assertBoundedSanitizedFailure(first, 'ERR_MODULE_NOT_FOUND');
	assertBoundedSanitizedFailure(second, 'ERR_MODULE_NOT_FOUND');
});

test('native A/B runner contains child module failures at the CLI root', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-ab-cli-failure-'));
	try {
		const outputPath = path.join(root, 'private-result.json');
		const first = await runCli('native-tool-ab-runner.mjs', [privateMissingModule, privateMissingModule, outputPath, '2']);
		const second = await runCli('native-tool-ab-runner.mjs', [privateMissingModule, privateMissingModule, outputPath, '2']);
		for (const result of [first, second]) {
			assert.equal(result.signal, null);
			assert.equal(result.code, 1);
			assert.equal(result.stderr, '');
			assert.ok(Buffer.byteLength(result.stdout, 'utf8') <= maximumDiagnosticBytes);
			assert.doesNotMatch(result.stdout, /C:\\private|api-token-secret/i);
			assert.doesNotMatch(result.stdout, /Users\\lucas|\.worktrees|coordinator\\src/i);
			const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
			assert.equal(lines.length, 5);
			assert.equal(lines.at(-1).status, 'FAILED');
		}
		const artifact = JSON.parse(await readFile(outputPath, 'utf8'));
		assert.equal(artifact.failed, 4);
		assert.ok(artifact.rows.every((row) => row.code === 'ERR_MODULE_NOT_FOUND'));
		assert.doesNotMatch(JSON.stringify(artifact), /C:\\private|api-token-secret/i);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native load probe contains setup validation failures at the CLI root', async () => {
	const first = await runCli('native-tool-load-probe.mjs', [privateMissingModule, 'low', 'fast', '0']);
	const second = await runCli('native-tool-load-probe.mjs', [privateMissingModule, 'low', 'fast', '0']);
	assertBoundedSanitizedFailure(first, 'ERROR');
	assertBoundedSanitizedFailure(second, 'ERROR');
});

test('native tool probe contains setup failures at the CLI root', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-probe-cli-failure-'));
	try {
		const blockedTemp = path.join(root, 'api-token-secret.mjs');
		await writeFile(blockedTemp, 'not a directory', 'utf8');
		const first = await runCli('native-tool-probe.mjs', ['fixture-model', 'low', 'fast'], {
			env: { TEMP: blockedTemp, TMP: blockedTemp, TMPDIR: blockedTemp },
		});
		assertBoundedSanitizedFailure(first, 'ENOTDIR');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native A/B runner success reports only the artifact basename', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-ab-cli-success-'));
	try {
		const fixture = path.join(root, 'fixture-service.mjs');
		const outputPath = path.join(root, 'private-artifacts', 'ab-result.json');
		await writeFile(fixture, `
export class CodexService {
 async start() {}
 async stop() {}
 async createAgent(profile) {
  return {
   async setGoalRevision() {},
   async act(input, context) {
    const call = async (actionType) => context.executeTool({ tool: { kind: 'action', actionType } });
    if (input.includes('mine the known')) { await call('navigate_to'); await call('break_block'); return { toolCalls: 2 }; }
    if (input.includes('craft_inventory')) { await call('craft_inventory'); return { toolCalls: 1 }; }
    await call('chat'); return { toolCalls: 1 };
   },
  };
 }
}
`, 'utf8');
		const result = await runCli('native-tool-ab-runner.mjs', [fixture, fixture, outputPath, '2']);
		assert.equal(result.code, 0, result.stderr || result.stdout);
		assert.equal(result.stderr, '');
		const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean);
		const summary = JSON.parse(lines.at(-1));
		assert.equal(summary.status, 'PASSED');
		assert.equal(summary.outputPath, 'ab-result.json');
		assert.doesNotMatch(result.stdout, new RegExp(root.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
		assert.doesNotMatch(result.stdout, /private-artifacts/i);
		assert.equal(JSON.parse(await readFile(outputPath, 'utf8')).failed, 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
