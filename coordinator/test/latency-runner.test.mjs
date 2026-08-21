import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { runLatencyMatrix, normalizeLatencyMatrix } from '../src/benchmark/latency-runner.mjs';
import { createReplayProvider, createReplayRecord } from '../src/benchmark/provider-replay.mjs';

const PROFILE = Object.freeze({ provider: 'instant', model: 'deterministic-v1', reasoningEffort: 'fixed', serviceTier: 'local' });
const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");';

function matrix(overrides = {}) {
	return {
		version: 1,
		benchmarkVersion: 'latency-ab-v1',
		protocolVersion: 2,
		fixedSeeds: [42],
		agentLoads: [1, 4, 8, 16],
		trials: [1, 4, 8, 16].map((agentLoad) => ({
			id: `instant-${agentLoad}`, mode: 'instant', scenarioId: 'fixture-wait', seed: 42, agentLoad,
			providerProfile: PROFILE, repetitions: 1, turnBudgetMs: 100, trialBudgetMs: 1_000, turnCap: 2,
			providerAvailabilityRequired: false,
		})),
		...overrides,
	};
}

function instantProvider() {
	return {
		available: true,
		async start() {},
		async stop() {},
		async createAgent() {
			return { async setGoalRevision() {}, async decide() { return { summary: 'done', directive: 'replace', source: SOURCE }; } };
		},
	};
}

function fixtureScenario() {
	return {
		id: 'fixture-wait', seed: 42, agentId: 'agent-a', goal: 'Wait.',
		world: { seed: 42, agents: { 'agent-a': { position: { x: 0, y: 1, z: 0 }, onGround: true } }, blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }] },
		commands: [], events: [], expected: {},
	};
}

test('strictly normalizes matrix identity, budgets, loads, and unique trial IDs', () => {
	const normalized = normalizeLatencyMatrix(matrix());
	assert.deepEqual(normalized.agentLoads, [1, 4, 8, 16]);
	assert.deepEqual(normalized.fixedSeeds, [42]);
	assert.equal(normalized.trials.length, 4);
	assert.ok(Object.isFrozen(normalized));
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [matrix().trials[0], { ...matrix().trials[0], id: 'instant-1' }] })), /unique/i);
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [{ ...matrix().trials[0], turnBudgetMs: Infinity }] })), /finite/i);
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [{ ...matrix().trials[0], agentLoad: 2 }] })), /1, 4, 8, 16/);
});

test('runs deterministic instant full-path trials at every declared load', async () => {
	const result = await runLatencyMatrix({
		matrix: matrix(),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => instantProvider() },
		artifactDirectory: null,
	});
	assert.equal(result.status, 'PASSED');
	assert.deepEqual(result.trials.map((trial) => trial.agentLoad), [1, 4, 8, 16]);
	assert.ok(result.trials.every((trial) => trial.status === 'PASSED'));
	assert.ok(result.trials.every((trial) => trial.outcomeHash.startsWith('sha256:')));
	assert.equal(result.cleanup.ok, true);
});

test('shipped default matrix proves physical stone-tool success for every isolated load', async () => {
	const result = await runLatencyMatrix({ artifactDirectory: null });
	assert.equal(result.status, 'PASSED');
	assert.deepEqual(result.trials.map((trial) => trial.status), ['PASSED', 'PASSED', 'PASSED', 'PASSED']);
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioPassed === true));
	assert.ok(result.trials.every((trial) => trial.debug?.turnCount <= trial.agentLoad * 2));
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioEvidence?.every((agent) => agent.actionResults === 3 && agent.succeeded === 3 && agent.cancelled === 0 && agent.failed === 0)));
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioDigest?.startsWith('sha256:')));
});

test('skips optional unavailable providers, fails required providers, and never substitutes', async () => {
	const unavailable = () => ({ available: false, reason: 'fixture unavailable' });
	const optional = await runLatencyMatrix({ matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'optional', mode: 'live', providerProfile: { provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' }, providerAvailabilityRequired: false }] }), scenarioResolver: () => fixtureScenario(), providerFactories: { codex: unavailable }, artifactDirectory: null });
	assert.equal(optional.trials[0].status, 'SKIPPED');
	assert.equal(optional.trials[0].providerProfile.provider, 'codex');
	await assert.rejects(() => runLatencyMatrix({ matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'required', mode: 'live', providerProfile: { provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' }, providerAvailabilityRequired: true }] }), scenarioResolver: () => fixtureScenario(), providerFactories: { codex: unavailable }, artifactDirectory: null }), (error) => error.code === 'PROVIDER_UNAVAILABLE');
});

test('turn and trial timeouts produce typed bounded failures and clean provider lifecycle', async () => {
	let stopped = 0;
	const hanging = () => ({
		async start() {}, async stop() { stopped += 1; },
		async createAgent() { return { async setGoalRevision() {}, decide() { return new Promise(() => {}); } }; },
	});
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'timeout', turnBudgetMs: 5, trialBudgetMs: 25 }] }),
		scenarioResolver: () => fixtureScenario(), providerFactories: { instant: hanging }, artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'TIMED_OUT');
	assert.equal(result.trials[0].error.code, 'TURN_TIMEOUT');
	assert.equal(stopped, 1);
	assert.equal(result.cleanup.ok, true);
});

test('provider process exit is typed and leaves the coordinator path clean', async () => {
	let stopped = 0;
	const providerFactory = () => ({
		available: true,
		async stop() { stopped += 1; },
		async createAgent() { throw Object.assign(new Error('provider child exited'), { code: 'PROVIDER_EXIT' }); },
	});
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'provider-exit', providerAvailabilityRequired: true }] }),
		scenarioResolver: () => fixtureScenario(), providerFactories: { instant: providerFactory }, artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'FAILED');
	assert.equal(result.trials[0].error.code, 'PROVIDER_EXIT');
	assert.equal(result.trials[0].cleanup.ok, true);
	assert.equal(stopped, 1);
});

test('startup failure detaches virtual relays and stops a provider exactly once', async () => {
	let stopped = 0;
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'startup-failure' }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => ({ available: true, async stop() { stopped += 1; }, async createAgent() { return { async setGoalRevision() {}, async decide() { return { summary: 'done', directive: 'replace', source: SOURCE }; } }; } }) },
		systemSamplerFactory: () => ({ active: false, start() { throw Object.assign(new Error('sampler startup failed'), { code: 'SAMPLER_START_FAILED' }); }, stop() {} }),
		artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'FAILED');
	assert.equal(result.trials[0].error.code, 'SAMPLER_START_FAILED');
	assert.equal(result.trials[0].cleanup.ok, true);
	assert.equal(result.trials[0].cleanup.relays, 0);
	assert.equal(result.trials[0].cleanup.listeners, 0);
	assert.equal(stopped, 1);
});

test('artifact output is staged, bounded, and redacted on provider failure', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'latency-artifacts-'));
	try {
		const secret = 'fixture-secret-token-123456';
		const result = await runLatencyMatrix({
			matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'redacted', providerAvailabilityRequired: true }] }),
			scenarioResolver: () => fixtureScenario(),
			providerFactories: { instant: () => ({ available: true, async start() { throw new Error(`authorization token=${secret}`); }, async stop() {} }) },
			artifactDirectory: directory,
		});
		assert.equal(result.trials[0].status, 'FAILED');
		const manifest = await readFile(path.join(directory, 'latency-manifest.json'), 'utf8');
		assert.equal(manifest.includes(secret), false);
		assert.ok(manifest.length < 100_000);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

test('artifact rollback preserves the only prior copy when restoration fails', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'latency-rollback-'));
	const directory = path.join(root, 'artifacts');
	await mkdir(directory, { recursive: true });
	await writeFile(path.join(directory, 'old.txt'), 'prior artifact');
	let backupPath = null;
	let renameCount = 0;
	const artifactFs = {
		mkdir,
		writeFile,
		rm,
		async rename(source, target) {
			renameCount += 1;
			if (renameCount === 1) { backupPath = target; return rename(source, target); }
			if (renameCount === 2) throw Object.assign(new Error('publish swap failed'), { code: 'SWAP_FAILED' });
			throw Object.assign(new Error('restore failed'), { code: 'RESTORE_FAILED' });
		},
	};
	try {
		await assert.rejects(() => runLatencyMatrix({ matrix: matrix(), scenarioResolver: () => fixtureScenario(), artifactDirectory: directory, artifactFs }), (error) => error.code === 'ARTIFACT_ROLLBACK_FAILED');
		assert.ok(backupPath);
		assert.equal(await readFile(path.join(backupPath, 'old.txt'), 'utf8'), 'prior artifact');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('replay mode uses the same full coordinator path and rejects prompt drift', async () => {
	const profile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const source = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");';
	let prompt = null;
	const scenario = fixtureScenario();
	const liveMatrix = matrix({ trials: [{ ...matrix().trials[0], id: 'replay-path', mode: 'live', providerProfile: profile }] });
	const providerFactory = () => ({ available: true, async createAgent() { return { async setGoalRevision() {}, async decide(input) { prompt = input; return { summary: 'done', directive: 'replace', source }; } }; }, async stop() {} });
	const first = await runLatencyMatrix({ matrix: liveMatrix, scenarioResolver: () => scenario, providerFactories: { codex: providerFactory }, artifactDirectory: null });
	assert.equal(first.trials[0].status, 'PASSED');
	const recording = createReplayRecord({ trialId: 'replay-path', prompt, providerProfile: profile, scenario, protocolVersion: 2, decision: { summary: 'done', directive: 'replace', source } });
	const replayMatrix = matrix({ trials: [{ ...liveMatrix.trials[0], mode: 'replay' }] });
	const replay = await runLatencyMatrix({
		matrix: replayMatrix, scenarioResolver: () => scenario,
		providerFactories: { codex: () => createReplayProvider({ recording, trialId: 'replay-path', prompt, providerProfile: profile, scenario, protocolVersion: 2 }) }, artifactDirectory: null,
	});
	assert.equal(replay.trials[0].status, 'PASSED');
	const drift = await runLatencyMatrix({
		matrix: replayMatrix, scenarioResolver: () => scenario,
		providerFactories: { codex: () => createReplayProvider({ recording, trialId: 'replay-path', prompt: `${prompt}-drift`, providerProfile: profile, scenario, protocolVersion: 2 }) }, artifactDirectory: null,
	});
	assert.equal(drift.trials[0].error.code, 'REPLAY_IDENTITY_MISMATCH');
});
