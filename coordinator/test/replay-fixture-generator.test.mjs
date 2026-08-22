import assert from 'node:assert/strict';
import test from 'node:test';

import { generateReplayRecordings } from '../src/benchmark/replay-fixture-generator.mjs';
import { runLatencyMatrix, normalizeLatencyMatrix } from '../src/benchmark/latency-runner.mjs';
import { getSimulatorScenario } from '../src/simulator/simulator-scenarios.mjs';

const PROFILE = Object.freeze({ provider: 'replay', model: 'capture-v1', reasoningEffort: 'fixed', serviceTier: 'local' });
const SEED = 20260821;

function matrix(loads = [1, 4]) {
	return normalizeLatencyMatrix({
		version: 1,
		benchmarkVersion: 'latency-ab-v1',
		protocolVersion: 2,
		fixedSeeds: [SEED],
		agentLoads: [1, 4, 8, 16],
		trials: loads.map((agentLoad) => ({
			id: `stone-replay-${agentLoad}`,
			mode: 'replay',
			scenarioId: 'stone-tool-gathering',
			seed: SEED,
			agentLoad,
			providerProfile: PROFILE,
			repetitions: 1,
			turnBudgetMs: 1_000,
			trialBudgetMs: 10_000,
			turnCap: 4,
			providerAvailabilityRequired: true,
		})),
	});
}

test('captures exact translated-agent decisions as redacted records for loads 1 and 4', async () => {
	const generated = await generateReplayRecordings({
		matrix: matrix(),
		scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
		delayMs: ({ turnIndex }) => turnIndex + 3,
	});

	assert.equal(generated.trials.length, 2);
	assert.ok(generated.trials.every((trial) => trial.status === 'PASSED'));
	assert.equal(generated.recordings.length, 5);
	assert.deepEqual(generated.recordings.map((record) => record.agentLoad), [1, 4, 4, 4, 4]);
	assert.ok(generated.recordings.every((record) => record.agentId && record.promptHashes.length === record.decisions.length));
	assert.ok(generated.recordings.every((record) => record.delaysMs[0] === 3 && record.delaysMs.every((delay, index) => delay === index + 3)));
	const serialized = JSON.stringify(generated);
	assert.doesNotMatch(serialized, /Minecraft planner state/);
	assert.doesNotMatch(serialized, /Gather stone and craft a stone pickaxe/);
	assert.doesNotMatch(serialized, /secret prompt/);
});

test('generated records replay successfully through the production runner at loads 1 and 4', async () => {
	const fixture = await generateReplayRecordings({
		matrix: matrix(),
		scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
	});
	const replay = await runLatencyMatrix({
		matrix: matrix(),
		scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
		replayRecordings: fixture.recordings,
		artifactDirectory: null,
	});

	assert.equal(replay.status, 'PASSED');
	assert.deepEqual(replay.trials.map((trial) => trial.status), ['PASSED', 'PASSED']);
	assert.equal(replay.cleanup.ok, true);
});

test('bounds fixture generation and rejects non-replay matrices', async () => {
	await assert.rejects(() => generateReplayRecordings({ matrix: matrix(), maxRecords: 4 }), /record count/i);
	await assert.rejects(() => generateReplayRecordings({ matrix: { ...matrix(), trials: [{ ...matrix().trials[0], mode: 'instant', providerProfile: { provider: 'instant', model: 'fixture', reasoningEffort: 'fixed', serviceTier: 'local' } }] } }), /replay trials/i);
	await assert.rejects(
		() => generateReplayRecordings({ matrix: matrix([1]), maxDelayMs: 2, delayMs: 3, scenarioResolver: () => getSimulatorScenario('stone-tool-gathering') }),
		/delayMs/i,
	);
});
