import assert from 'node:assert/strict';
import test from 'node:test';

import { generateReplayRecordings } from '../src/benchmark/replay-fixture-generator.mjs';
import { runLatencyMatrix, normalizeLatencyMatrix } from '../src/benchmark/latency-runner.mjs';
import { getSimulatorScenario } from '../src/simulator/simulator-scenarios.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';
import { createVirtualClock } from './fixtures/virtual-clock.mjs';

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

function activeActionHazardMatrix({ turnBudgetMs = 2_000, trialBudgetMs } = {}) {
	const base = matrix([4]);
	return normalizeLatencyMatrix({
		...base,
		trials: base.trials.map((trial) => ({
			...trial,
			id: 'active-action-lava-replay-4',
			scenarioId: 'active-action-lava-attention',
			turnBudgetMs,
			...(trialBudgetMs === undefined ? {} : { trialBudgetMs }),
		})),
	});
}

function activeActionHazardScenario() {
	return {
		...getSimulatorScenario('lava-damage-reaction'),
		id: 'active-action-lava-attention',
		success: (state) => ['lava-wait', 'lava-leave'].every((actionId) =>
			state.results?.some((result) => result.actionId === actionId && result.state === 'SUCCEEDED')),
	};
}

async function withLavaAttentionDuringActiveWait(callback, clock) {
	const errors = [];
	const virtualBridgeFactory = (options) => {
		const bridge = new VirtualMinecraftBridge(options);
		clock.observe(bridge);
		const waiting = new Set();
		const tick = options.world.tick;
		// Start hazard time after every initial action is ready, independent of provider timer jitter.
		options.world.tick = function () {
			if (waiting.size === options.agentRecords.length) return tick.call(this);
		};
		const onAccepted = (entry) => {
			if (entry?.envelope?.payload?.actionType === 'wait') waiting.add(entry.envelope.agentId);
		};
		bridge.on('accepted', onAccepted);
		const triggered = new Set();
		const onProgress = (entry) => {
			if (entry?.envelope?.payload?.actionType !== 'wait') return;
			const agentId = entry.envelope.agentId;
			if (triggered.has(agentId)) return;
			triggered.add(agentId);
			void bridge.publish(agentId, { attention: true, changedFacts: ['player.health'] }).catch((error) => errors.push(error));
		};
		bridge.on('progress', onProgress);
		const stop = bridge.stop.bind(bridge);
		bridge.stop = () => {
			options.world.tick = tick;
			bridge.off('accepted', onAccepted);
			bridge.off('progress', onProgress);
			return stop();
		};
		return bridge;
	};
	try {
		return await callback(virtualBridgeFactory);
	} finally {
		assert.deepEqual(errors, []);
	}
}

test('captures exact translated-agent decisions as redacted records for loads 1 and 4', { timeout: 120_000 }, async () => {
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

test('generated records replay successfully through the production runner at loads 1 and 4', { timeout: 120_000 }, async () => {
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

test('ordinary delayed stone records remain single-turn and replay without prompt drift', { timeout: 120_000 }, async () => {
	const delayedMatrix = matrix([4]);
	const fixture = await generateReplayRecordings({
		matrix: delayedMatrix,
		scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
		delayMs: 75,
	});
	assert.ok(fixture.recordings.every((record) => record.decisions.length === 1));

	const replay = await runLatencyMatrix({
		matrix: delayedMatrix,
		scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
		replayRecordings: fixture.recordings,
		artifactDirectory: null,
	});

	assert.equal(replay.status, 'PASSED');
	assert.equal(replay.trials[0].status, 'PASSED');
	assert.notEqual(replay.trials[0].error?.code, 'REPLAY_PROMPT_MISMATCH');
	assert.equal(replay.cleanup.ok, true);
});

test('records a cleanup-aborted continuation after hazard attention during an active action', { timeout: 120_000 }, async () => {
	// World ticks and provider delays share one virtual clock. Wall-clock timers made the tick count
	// inside a 500 ms delay depend on machine load, so capture and replay saw different prompts.
	const clock = createVirtualClock();
	// The turn budgets are real-time harness limits, not part of what this test measures.
	const delayedMatrix = activeActionHazardMatrix({ turnBudgetMs: 30_000, trialBudgetMs: 120_000 });
	let initialDelays = 0;
	const jitteredCaptureTimer = {
		setTimeout(callback, milliseconds) {
			// Reproduce one provider callback missing two world ticks under CI load.
			const late = milliseconds === 10 && ++initialDelays === 3;
			return clock.timer.setTimeout(callback, milliseconds + (late ? 100 : 0));
		},
		clearTimeout: clock.timer.clearTimeout,
	};
	await withLavaAttentionDuringActiveWait(async (virtualBridgeFactory) => {
		const fixture = await generateReplayRecordings({
			matrix: delayedMatrix,
			scenarioResolver: () => activeActionHazardScenario(),
			delayMs: ({ turnIndex }) => turnIndex === 0 ? 10 : 500,
			delayTimer: jitteredCaptureTimer,
			virtualBridgeFactory,
			...clock.trialOptions(),
		});
		assert.ok(fixture.recordings.every((record) => record.decisions.length >= 2));
		assert.equal(initialDelays, 4);

		const replay = await runLatencyMatrix({
			matrix: delayedMatrix,
			scenarioResolver: () => activeActionHazardScenario(),
			replayRecordings: fixture.recordings,
			artifactDirectory: null,
			virtualBridgeFactory,
			sleep: clock.sleep,
			...clock.trialOptions(),
		});

		assert.equal(replay.status, 'PASSED', JSON.stringify(replay.trials.map((trial) => trial.error)));
		assert.equal(replay.trials[0].status, 'PASSED');
		assert.notEqual(replay.trials[0].error?.code, 'REPLAY_EXHAUSTED');
		assert.notEqual(replay.trials[0].error?.code, 'REPLAY_PROMPT_MISMATCH');
		assert.equal(replay.cleanup.ok, true);
	}, clock);
});

test('cancels and clears a pending capture delay when the provider turn times out', { timeout: 120_000 }, async () => {
	const handles = new Set();
	let scheduled = 0;
	const delayTimer = {
		setTimeout(callback, milliseconds) {
			scheduled += 1;
			let handle;
			handle = setTimeout(() => { handles.delete(handle); callback(); }, milliseconds);
			handles.add(handle);
			return handle;
		},
		clearTimeout(handle) { handles.delete(handle); clearTimeout(handle); },
	};
	const timedOutMatrix = normalizeLatencyMatrix({
		...matrix([1]),
		// The 20 ms turn budget is under test; the trial budget only has to outlast a loaded runner's setup.
		trials: [{ ...matrix([1]).trials[0], turnBudgetMs: 20, trialBudgetMs: 30_000 }],
	});

	await assert.rejects(
		() => generateReplayRecordings({
			matrix: timedOutMatrix,
			scenarioResolver: () => getSimulatorScenario('stone-tool-gathering'),
			delayMs: 500,
			delayTimer,
		}),
		(error) => error.code === 'REPLAY_FIXTURE_CAPTURE_FAILED',
	);
	assert.ok(scheduled > 0);
	assert.equal(handles.size, 0);
});

test('bounds fixture generation and rejects non-replay matrices', { timeout: 120_000 }, async () => {
	await assert.rejects(() => generateReplayRecordings({ matrix: matrix(), maxRecords: 4 }), /record count/i);
	await assert.rejects(() => generateReplayRecordings({ matrix: { ...matrix(), trials: [{ ...matrix().trials[0], mode: 'instant', providerProfile: { provider: 'instant', model: 'fixture', reasoningEffort: 'fixed', serviceTier: 'local' } }] } }), /replay trials/i);
	await assert.rejects(
		() => generateReplayRecordings({ matrix: matrix([1]), maxDelayMs: 2, delayMs: 3, scenarioResolver: () => getSimulatorScenario('stone-tool-gathering') }),
		/delayMs/i,
	);
});
