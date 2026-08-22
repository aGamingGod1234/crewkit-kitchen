import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
	EXIT_CODES,
	parseLatencyRunnerArgs,
	runLatencyRunnerCli,
} from '../src/benchmark/latency-runner-cli.mjs';

const MATRIX = JSON.stringify({ version: 1, trials: [] });
const RECORDINGS = JSON.stringify([{ trialId: 'fixture', promptHash: 'sha256:' + 'a'.repeat(64) }]);

async function fixtureFiles() {
	const root = await mkdtemp(path.join(os.tmpdir(), 'latency-runner-cli-'));
	const matrix = path.join(root, 'matrix.json');
	const recordings = path.join(root, 'recordings.json');
	const prompt = path.join(root, 'prompt.txt');
	const artifacts = path.join(root, 'artifacts');
	await writeFile(matrix, MATRIX, 'utf8');
	await writeFile(recordings, RECORDINGS, 'utf8');
	await writeFile(prompt, 'private replay prompt', 'utf8');
	return { root, matrix, recordings, prompt, artifacts };
}

function capture() {
	const stdout = [];
	const stderr = [];
	return { stdout, stderr, out: (value) => stdout.push(String(value)), err: (value) => stderr.push(String(value)) };
}

test('rejects missing, relative, duplicate, unknown, and unbounded CLI arguments', async () => {
	const files = await fixtureFiles();
	try {
		assert.throws(() => parseLatencyRunnerArgs([]), /matrix/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', 'matrix.json', '--artifact-directory', files.artifacts]), /absolute/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--matrix', files.matrix, '--artifact-directory', files.artifacts]), /duplicate/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--unknown', 'x']), /unknown/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--planning-concurrency', '17']), /planning-concurrency/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--arm', 'x'.repeat(257)]), /bounded|length/i);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('loads private matrix, replay, and prompt files and passes bounded metadata to the runner', async () => {
	const files = await fixtureFiles();
	const seen = [];
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli([
			'--matrix', files.matrix,
			'--artifact-directory', files.artifacts,
			'--replay-recordings', files.recordings,
			'--replay-prompt-file', files.prompt,
			'--arm', 'baseline',
			'--runId', 'run-123',
			'--sourceHash', 'source-v1',
			'--configHash', 'config-v1',
			'--pairingKey', 'private-pairing-key',
			'--planning-concurrency', '4',
		], {
			runLatencyMatrix: async (options) => {
				seen.push(options);
				return { status: 'PASSED', matrix: { version: 1 }, trials: [], cleanup: { ok: true }, summary: 0 };
			},
			stdout: io.out,
			stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(seen.length, 1);
		assert.deepEqual(seen[0].matrix, JSON.parse(MATRIX));
		assert.deepEqual(seen[0].replayRecordings, JSON.parse(RECORDINGS));
		assert.equal(seen[0].replayPrompt, 'private replay prompt');
		assert.equal(seen[0].artifactDirectory, files.artifacts);
		assert.equal(seen[0].arm, 'baseline');
		assert.equal(seen[0].runId, 'run-123');
		assert.equal(seen[0].sourceHash, 'source-v1');
		assert.equal(seen[0].configHash, 'config-v1');
		assert.equal(seen[0].pairingKey, 'private-pairing-key');
		assert.equal(seen[0].planningConcurrency, 4);
		assert.equal(io.stdout.length, 1);
		const result = JSON.parse(io.stdout[0]);
		assert.equal(result.status, 'PASSED');
		assert.equal(result.metadata.arm, 'baseline');
		assert.equal(result.metadata.planningConcurrency, 4);
		assert.equal(JSON.stringify(result).includes('private replay prompt'), false);
		assert.equal(JSON.stringify(result).includes('private-pairing-key'), false);
		assert.equal(io.stderr.length, 0);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('accepts a bounded inline replay prompt without exposing it in result or diagnostics', async () => {
	const files = await fixtureFiles();
	const io = capture();
	const prompt = 'prompt-that-must-stay-private';
	try {
		const exitCode = await runLatencyRunnerCli([
			'--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-prompt', prompt,
		], {
			runLatencyMatrix: async (options) => {
				assert.equal(options.replayPrompt, prompt);
				return { status: 'PASSED', trials: [], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes(prompt), false);
		assert.equal(io.stderr.length, 0);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('treats an absolute --replay-prompt value naming a file as private prompt input', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		await runLatencyRunnerCli([
			'--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-prompt', files.prompt,
		], {
			runLatencyMatrix: async (options) => {
				assert.equal(options.replayPrompt, 'private replay prompt');
				return { status: 'PASSED', trials: [], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(io.stdout.length, 1);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes('private replay prompt'), false);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('returns skipped success for optional unavailable trials', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({ status: 'PASSED', trials: [{ trialId: 'optional', status: 'SKIPPED' }], cleanup: { ok: true } }),
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.SKIPPED);
		assert.equal(JSON.parse(io.stdout[0]).status, 'SKIPPED');
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('returns failed exit status for a completed runner failure', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({ status: 'FAILED', trials: [{ trialId: 'failed', status: 'FAILED', error: { code: 'SCENARIO_ASSERTION_FAILED', message: 'private response' } }], cleanup: { ok: true } }),
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.FAILED);
		assert.equal(JSON.parse(io.stdout[0]).status, 'FAILED');
		assert.deepEqual(io.stderr, ['latency-runner: SCENARIO_ASSERTION_FAILED\n']);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('maps an invalid injected runner dependency to the internal-error exit code', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: 'not-a-function', stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.INTERNAL);
		assert.equal(JSON.parse(io.stdout[0]).error.code, 'CLI_INTERNAL');
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('preserves runner result on required-provider failure and maps its exit code', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const error = Object.assign(new Error('provider unavailable: token=must-not-leak'), {
			code: 'PROVIDER_UNAVAILABLE',
			result: { status: 'FAILED', trials: [{ trialId: 'required', status: 'FAILED', error: { code: 'PROVIDER_UNAVAILABLE', message: 'token=must-not-leak' } }], cleanup: { ok: true } },
		});
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => { throw error; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.REQUIRED_PROVIDER);
		assert.equal(io.stdout.length, 1);
		const result = JSON.parse(io.stdout[0]);
		assert.equal(result.status, 'FAILED');
		assert.equal(result.trials[0].error.code, 'PROVIDER_UNAVAILABLE');
		assert.equal(JSON.stringify(result).includes('must-not-leak'), false);
		assert.deepEqual(io.stderr, ['latency-runner: PROVIDER_UNAVAILABLE\n']);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('rejects oversized replay files and malformed JSON before invoking the runner', async () => {
	const files = await fixtureFiles();
	const io = capture();
	let calls = 0;
	try {
		await writeFile(files.recordings, '{"not": "an array"}', 'utf8');
		const malformedExit = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-recordings', files.recordings], {
			runLatencyMatrix: async () => { calls += 1; return { status: 'PASSED' }; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(malformedExit, EXIT_CODES.USAGE);
		assert.equal(calls, 0);
		await writeFile(files.recordings, 'x'.repeat(1_048_577), 'utf8');
		const oversizedExit = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-recordings', files.recordings], {
			runLatencyMatrix: async () => { calls += 1; return { status: 'PASSED' }; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(oversizedExit, EXIT_CODES.USAGE);
		assert.equal(calls, 0);
		assert.equal(io.stdout.length, 2);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});
