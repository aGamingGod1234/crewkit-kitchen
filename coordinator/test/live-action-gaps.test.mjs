import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { summarize } from '../src/benchmark/live-action-gaps.mjs';

async function makeRun(t, { protocol = [], coordinator = [], coordinatorPrivate = [], report = {} } = {}) {
	const root = await mkdtemp(path.join(os.tmpdir(), 'live-action-gaps-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const directory = path.join(root, 'scenario');
	await mkdir(directory);
	const jsonl = (rows) => rows.map((row) => JSON.stringify(row)).join('\n');
	await Promise.all([
		writeFile(path.join(directory, 'protocol.jsonl'), jsonl(protocol)),
		writeFile(path.join(directory, 'coordinator.jsonl'), jsonl(coordinator)),
		writeFile(path.join(directory, 'coordinator-private.jsonl'), jsonl(coordinatorPrivate)),
		writeFile(path.join(directory, 'report.json'), JSON.stringify({ status: 'PASSED', lifecycle: 'COMPLETED', elapsedMs: 1_150, ...report })),
	]);
	return directory;
}

test('attributes only evidenced goal, action, handoff, and completion intervals', async (t) => {
	const protocol = [
		{ envelope: { type: 'goal_control', payload: { operation: 'start', updatedAtEpochMs: 1_000 } } },
		{ envelope: { type: 'action_command', payload: { actionId: 'look-1', actionType: 'control', provenance: { programId: 'look-program' } } } },
		{ envelope: { type: 'action_result', payload: { actionId: 'look-1', actionType: 'control', elapsedMs: 100, observedAtEpochMs: 1_350 } } },
		{ envelope: { type: 'action_command', payload: { actionId: 'look-2', actionType: 'control', provenance: { programId: 'look-program' } } } },
		{ envelope: { type: 'action_result', payload: { actionId: 'look-2', actionType: 'control', elapsedMs: 100, observedAtEpochMs: 1_500 } } },
		{ envelope: { type: 'action_command', payload: { actionId: 'break-1', actionType: 'break_block', provenance: { programId: 'work-program' } } } },
		{ envelope: { type: 'action_result', payload: { actionId: 'break-1', actionType: 'break_block', elapsedMs: 200, observedAtEpochMs: 1_900 } } },
		{ envelope: { type: 'goal_control', payload: { operation: 'complete', updatedAtEpochMs: 2_000 } } },
		{ envelope: { type: 'observation', payload: { status: 'COMPLETED', observedAtEpochMs: 2_150 } } },
	];
	const directory = await makeRun(t, {
		protocol,
		coordinator: [{ event: 'native_decision_timing', fields: { segmentDurationMs: 120 } }],
		coordinatorPrivate: [{ type: 'native_decision_timing', payload: { segmentDurationMs: 50 } }],
	});
	const result = await summarize(directory);

	assert.equal(result.phaseAttribution.firstActionWait.durationMs, 250);
	assert.equal(result.phaseAttribution.firstModelSegment.durationMs, 120);
	assert.equal(result.actionBusyMs, 400);
	assert.equal(result.phaseAttribution.runtimeProgramHandoff.durationMs, 50);
	assert.equal(result.phaseAttribution.betweenActionWait.durationMs, 200);
	assert.equal(result.phaseAttribution.completionTail.durationMs, 250);
	assert.equal(result.phaseAttribution.observedGoalWindow.durationMs, 1_150);
	assert.equal(result.phaseAttribution.observedGoalWindow.accountedDurationMs, 1_150);
	assert.equal(result.phaseAttribution.goalTranslation.durationMs, null);
	assert.equal(result.phaseAttribution.completionVerification.durationMs, null);
	assert.equal(result.modelDecisionSegments, 2);
	assert.equal(result.modelSegmentMsTotal, 170);
	assert.equal(result.modelDecisionTimingAvailable, true);
});

test('leaves missing decision timing and unobserved phases unknown instead of reporting zero', async (t) => {
	const directory = await makeRun(t, { protocol: [], coordinator: [], coordinatorPrivate: [] });
	const result = await summarize(directory);

	assert.equal(result.modelDecisionSegments, 0);
	assert.equal(result.modelSegmentMsTotal, null);
	assert.equal(result.modelDecisionTimingAvailable, false);
	assert.equal(result.phaseAttribution.firstModelSegment.durationMs, null);
	assert.equal(result.phaseAttribution.goalTranslation.durationMs, null);
	assert.equal(result.phaseAttribution.firstActionWait.durationMs, null);
	assert.equal(result.phaseAttribution.completionTail.durationMs, null);
});

test('does not turn overlapping or invalid clocks into negative phase durations', async (t) => {
	const protocol = [
		{ envelope: { type: 'goal_control', payload: { operation: 'start', updatedAtEpochMs: 2_000 } } },
		{ envelope: { type: 'action_command', payload: { actionId: 'a', actionType: 'move' } } },
		{ envelope: { type: 'action_result', payload: { actionId: 'a', actionType: 'move', elapsedMs: 200, observedAtEpochMs: 1_900 } } },
	];
	const directory = await makeRun(t, { protocol });
	const result = await summarize(directory);

	assert.equal(result.invalidGapCount, 0);
	assert.equal(result.phaseAttribution.firstActionWait.durationMs, null);
	assert.equal(result.phaseAttribution.completionTail.durationMs, null);
	assert.equal(result.phaseAttribution.observedGoalWindow.durationMs, null);
});
