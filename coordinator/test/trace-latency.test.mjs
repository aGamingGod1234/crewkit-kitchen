import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { TraceWriter } from '../src/trace-writer.mjs';
import { bodyIdle, buildChains, parseTrace, renderReport, stats, summarize } from '../scripts/trace-latency.mjs';

const SCRIPT = fileURLToPath(new URL('../scripts/trace-latency.mjs', import.meta.url));
const T = 1_760_000_000_000;
const AGENT = 'agent-a';

/** Rows of one turn: observe, then two actions, with every stage at a known offset (ms after T). */
function syntheticRows({ mono = true, restartAt = null } = {}) {
	const clock = (offset) => ({ at: T + offset, ...(mono ? { mono: (restartAt !== null && offset >= restartAt ? 10 + offset - restartAt : 500 + offset) } : {}) });
	const row = (offset, event, fields = {}) => ({ event, ...clock(offset), agentId: AGENT, ...fields });
	return [
		row(0, 'native_event_ready', { traceId: 't1', mode: 'turn', trigger: 'attention', eventName: 'observation', priority: 'ordinary' }),
		row(12, 'native_input_built', { traceId: 't1', inputBytes: 9000, buildMs: 12 }),
		row(30, 'native_provider_turn_sent', { turnId: '1:1' }),
		row(3030, 'native_model_call', { turnId: '1:1', firstEventMs: 2200, streamMs: 800, totalMs: 3000, toolNames: ['mcp__minecraft__observe'] }),
		row(3040, 'native_tool_queue_timing', { traceId: 't1', callId: 'c1', turnId: '1:1', toolKind: 'observe', queueWaitMs: null }),
		row(3045, 'native_tool_result_returned', { traceId: 't1', callId: 'c1', toolKind: 'observe', executeMs: 5, state: null }),
		row(5045, 'native_model_call', { turnId: '1:1', firstEventMs: 1500, streamMs: 500, totalMs: 2000 }),
		row(5060, 'native_tool_queue_timing', { traceId: 't1', callId: 'c2', turnId: '1:1', toolKind: 'action', queueWaitMs: null }),
		row(5065, 'native_tool_dispatch_started', { traceId: 't1', actionId: 'native:s:1:agent:1', actionType: 'navigate_to', callId: 'c2' }),
		row(5069, 'native_tool_journal_written', { actionId: 'native:s:1:agent:1', callId: 'c2', journalMs: 4 }),
		row(5071, 'native_tool_command_sent', { actionId: 'native:s:1:agent:1', callId: 'c2', dispatchMs: 6, sendMs: 2 }),
		row(5400, 'native_tool_action_completed', { actionId: 'native:s:1:agent:1', state: 'SUCCEEDED', javaElapsedMs: 300, javaEndedAtEpochMs: T + 5390,
			javaAcceptedAtEpochMs: T + 5080, javaStartedAtEpochMs: T + 5100, javaStartedTick: 100, javaEndedTick: 107 }),
		row(5408, 'native_tool_post_action_sampled', { actionId: 'native:s:1:agent:1', callId: 'c2', waitMs: 8 }),
		row(5420, 'native_tool_result_returned', { traceId: 't1', callId: 'c2', toolKind: 'action', executeMs: 360, state: 'SUCCEEDED' }),
		row(8420, 'native_tool_queue_timing', { traceId: 't1', callId: 'c3', turnId: '1:1', toolKind: 'action', queueWaitMs: null }),
		row(8430, 'native_tool_dispatch_started', { traceId: 't1', actionId: 'native:s:2:agent:1', actionType: 'break_block', callId: 'c3' }),
		row(8435, 'native_tool_command_sent', { actionId: 'native:s:2:agent:1', callId: 'c3', dispatchMs: 5, sendMs: 3 }),
		row(8710, 'native_tool_action_completed', { actionId: 'native:s:2:agent:1', state: 'SUCCEEDED', javaElapsedMs: 200, javaEndedAtEpochMs: T + 8700,
			javaAcceptedAtEpochMs: T + 8440, javaStartedAtEpochMs: T + 8500, javaStartedTick: 170, javaEndedTick: 174 }),
		row(8720, 'native_tool_result_returned', { traceId: 't1', callId: 'c3', toolKind: 'action', executeMs: 290, state: 'SUCCEEDED' }),
	];
}

test('a timestamped trace becomes the per-action chain with exact stage durations', () => {
	const { chains, timestamped } = buildChains(syntheticRows());
	assert.equal(timestamped, true);
	assert.equal(chains.length, 2);
	const first = chains[0].stages;
	assert.deepEqual(
		{ eventToTurn: first.eventToTurn, inputBuild: first.inputBuild, model: first.model, modelToTool: first.modelToTool, requestToDispatch: first.requestToDispatch,
			journal: first.journal, dispatchToSent: first.dispatchToSent, send: first.send, sentToAccept: first.sentToAccept, acceptToStart: first.acceptToStart,
			run: first.run, endToCoordinator: first.endToCoordinator, postAction: first.postAction, resultToModel: first.resultToModel, nextThink: first.nextThink },
		{ eventToTurn: undefined, inputBuild: undefined, model: 2015, modelToTool: 15, requestToDispatch: 5,
			journal: 4, dispatchToSent: 6, send: 2, sentToAccept: 9, acceptToStart: 20,
			run: 290, endToCoordinator: 10, postAction: 8, resultToModel: 20, nextThink: 3000 },
	);
	assert.deepEqual([first.prefill, first.generate], [1500, 500], 'the model call just before the request is the one that decided the action');
	assert.deepEqual([chains[0].ticks.started, chains[0].ticks.ended], [100, 107]);
	assert.equal(chains[1].stages.model, 3000);
	assert.equal(chains[1].stages.nextThink, null, 'the last action has no next request');
});

test('the first request of a turn also reports event-to-turn and input build', () => {
	const rows = syntheticRows();
	// Make the first tool request an action so the turn lead is attached to a dispatched action.
	const firstAction = syntheticRows().filter((row) => !['c1'].includes(row.callId) && !(row.event === 'native_model_call' && row.totalMs === 3000));
	const { chains } = buildChains(firstAction);
	assert.equal(chains[0].stages.eventToTurn, 30);
	assert.equal(chains[0].stages.inputBuild, 12);
	assert.equal(chains[0].stages.model, 5060 - 30);
	assert.ok(rows.length > firstAction.length);
});

test('body idle share is the time no action ran between the first start and the last end', () => {
	const idle = bodyIdle(buildChains(syntheticRows()).chains);
	assert.equal(idle.length, 1);
	assert.equal(idle[0].windowMs, 8700 - 5100);
	assert.equal(idle[0].busyMs, 290 + 200);
	assert.ok(Math.abs(idle[0].idleShare - (1 - 490 / 3600)) < 1e-9);
});

test('a coordinator restart (monotonic clock reset) falls back to the wall clock', () => {
	const { chains } = buildChains(syntheticRows({ restartAt: 5000 }));
	assert.equal(chains[0].stages.requestToDispatch, 5);
	assert.equal(chains[0].stages.dispatchToSent, 6);
	assert.equal(chains[0].stages.nextThink, 3000);
});

test('rows without monotonic time still chain on the wall clock', () => {
	const { chains } = buildChains(syntheticRows({ mono: false }));
	assert.equal(chains[0].stages.model, 2015);
	assert.equal(chains[0].stages.nextThink, 3000);
});

test('a trace from before timestamps degrades to recorded durations and says so', () => {
	const legacy = [
		{ event: 'native_decision_timing', agentId: AGENT, segmentDurationMs: 4000, boundary: 'tool_request' },
		{ event: 'native_decision_timing', agentId: AGENT, segmentDurationMs: 6000, boundary: 'tool_request' },
		{ event: 'native_decision_timing', agentId: AGENT, segmentDurationMs: 100, boundary: 'turn_completed' },
		{ event: 'native_model_call', agentId: AGENT, firstEventMs: 2000, streamMs: 1000, totalMs: 3000 },
		{ event: 'native_tool_queue_timing', agentId: AGENT, callId: 'c1', toolKind: 'action', queueWaitMs: null },
		{ event: 'native_tool_dispatch_started', agentId: AGENT, actionId: 'native:s:1:agent:1', actionType: 'wait' },
		{ event: 'native_tool_command_sent', agentId: AGENT, actionId: 'native:s:1:agent:1' },
		{ event: 'native_tool_action_completed', agentId: AGENT, actionId: 'native:s:1:agent:1', state: 'SUCCEEDED' },
	];
	const summary = summarize(legacy);
	assert.equal(summary.timestamped, false);
	assert.equal(summary.actions, 1);
	const decision = summary.recorded.find((entry) => entry.label.startsWith('decision segment'));
	assert.deepEqual([decision.n, decision.median, decision.p90], [2, 4000, 6000]);
	// Only the fields a model call always carried survive without timestamps.
	assert.ok(summary.stages.filter((stage) => !['prefill', 'generate'].includes(stage.key)).every((stage) => stage.n === 0));
	assert.deepEqual(summary.idle, []);
	const text = renderReport(summary);
	assert.match(text, /timestamps: NO/);
	assert.match(text, /decision segment: provider start -> tool request\s+2\s+4000\s+6000/);
});

test('summary medians and p90 use nearest rank', () => {
	assert.deepEqual(stats([5, 1, 3, 2, 4, 10, 7, 8, 9, 6]), { n: 10, median: 5, p90: 9, mean: 5.5 });
	assert.deepEqual(stats([]), { n: 0, median: null, p90: null, mean: null });
	const summary = summarize(syntheticRows());
	assert.equal(summary.stages.find((stage) => stage.key === 'run').median, 200);
	assert.equal(summary.stages.find((stage) => stage.key === 'run').p90, 290);
	assert.match(renderReport(summary), /Body idle share[\s\S]*86%/);
});

test('rows written by the real TraceWriter feed the analyzer end to end', async () => {
	const lines = [];
	let wall = T;
	let tick = 100;
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {}, appendFile: async (_path, value) => lines.push(value),
		epochNow: () => wall, monotonicNow: () => tick,
	});
	const at = (offset) => { wall = T + offset; tick = 100 + offset; };
	at(0); writer.write('native_tool_queue_timing', { agentId: AGENT, callId: 'c1', toolKind: 'action', queueWaitMs: null });
	at(4); writer.write('native_tool_dispatch_started', { agentId: AGENT, actionId: 'a1', actionType: 'wait', callId: 'c1' });
	at(9); writer.write('native_tool_command_sent', { agentId: AGENT, actionId: 'a1', callId: 'c1', dispatchMs: 5, sendMs: 4 });
	await writer.close();
	const rows = parseTrace(lines.join(''));
	assert.equal(rows.length, 3);
	const [chain] = buildChains(rows).chains;
	assert.equal(chain.stages.requestToDispatch, 4);
	assert.equal(chain.stages.dispatchToSent, 5);
});

test('the command line prints the report and tolerates a torn last line', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'trace-latency-'));
	try {
		const file = path.join(directory, 'coordinator.jsonl');
		await writeFile(file, `${syntheticRows().map((row) => JSON.stringify(row)).join('\n')}\n{"event":"native_tool_que`);
		const run = spawnSync(process.execPath, [SCRIPT, file, '--rows', '2'], { encoding: 'utf8' });
		assert.equal(run.status, 0, run.stderr);
		assert.match(run.stdout, /timestamps: yes/);
		assert.match(run.stdout, /action runs \(Java start -> end\)\s+2\s+200\s+290/);
		const json = spawnSync(process.execPath, [SCRIPT, file, '--json'], { encoding: 'utf8' });
		assert.equal(JSON.parse(json.stdout).actions, 2);
		assert.equal(spawnSync(process.execPath, [SCRIPT], { encoding: 'utf8' }).status, 2);
	} finally { await rm(directory, { recursive: true, force: true }); }
});
