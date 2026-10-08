import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry } from '../src/agent-registry.mjs';
import { FakePlanner, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

const CONFIG = { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } };
const tick = () => new Promise((resolve) => setImmediate(resolve));
/** The wake event the model reads: the JSON line after the instruction line. */
const wake = (request) => JSON.parse(request.input.split('\n')[1]);

function observation(sequence, { attention = false, health = 20, changedFacts, trigger, priority } = {}) {
	return { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: sequence, attention, observedAtEpochMs: sequence, ...(changedFacts === undefined ? {} : { changedFacts }),
		...(trigger === undefined ? {} : { trigger, priority }),
		observation: { player: { x: sequence % 7, y: 64, z: 0, health }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } };
}

/** Hold-limit timers the test fires by hand; the coordinator never sees a real clock. */
function fakeTimers() {
	const timers = [];
	return {
		timers,
		setWakeHoldTimeout: (callback, delayMs) => { const timer = { callback, delayMs, cleared: false }; timers.push(timer); return timer; },
		clearWakeHoldTimeout: (timer) => { timer.cleared = true; },
	};
}

/** The first turn starts a 5 s wait itself and ends its turn, so a model-started action owns the body. */
async function startWithRunningAction({ turns = [], controlNow } = {}) {
	const { timers, ...timerHooks } = fakeTimers();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const rows = [];
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const turn = planner.requests.length;
		if (turn === 1) {
			await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'turn-1', callId: 'c1', tool: { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 5000 } } });
			return { toolCalls: 1 };
		}
		return turns[turn - 2]?.(request) ?? { toolCalls: 0 };
	};
	const run = await start({ registry, planner, ...timerHooks, ...(controlNow === undefined ? {} : { controlNow }), traceWriter: { write(event, fields) { rows.push({ event, ...fields }); } }, config: CONFIG });
	run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
	run.bridge.emit('observation', observation(1, { attention: true, changedFacts: ['blocks'] }));
	await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
	await eventually(() => rows.some(({ event }) => event === 'native_turn_completed'));
	const command = run.bridge.sent.find(({ type }) => type === 'action_command');
	const finish = () => run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'WAIT_DONE', actionType: 'wait', eventSequence: 50 } });
	return { run, planner, rows, finish, timers };
}

test('ordinary observations do not wake the model while its own action runs', async () => {
	const { run, planner, rows } = await startWithRunningAction();
	try {
		assert.equal(planner.requests.length, 1);
		run.bridge.emit('observation', observation(2));
		run.bridge.emit('observation', observation(3, { attention: true, changedFacts: ['blocks'] }));
		run.bridge.emit('observation', observation(4, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => rows.some(({ event }) => event === 'native_wake_deferred_for_action'));
		await tick();
		assert.equal(planner.requests.length, 1, 'no model call for waits it cannot act on');
	} finally { await run.coordinator.stop(); }
});

test('the completion observation wakes the model once and names the sighting it skipped', async () => {
	const { run, planner, rows, finish } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		run.bridge.emit('observation', observation(3));
		await eventually(() => rows.filter(({ event }) => event === 'native_wake_deferred_for_action').length === 1);
		finish();
		run.bridge.emit('observation', observation(51, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).event, 'observation');
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted', 'the skipped reason is not lost');
		assert.equal(wake(planner.requests[1]).observation.player.x, 51 % 7, 'the wake carries the post-result facts');
		const resumed = rows.find(({ event }) => event === 'native_wake_resumed_after_action');
		assert.equal(resumed.skippedWakes, 2);
		assert.equal(resumed.deferredTrigger, 'structure_sighted');
	} finally { await run.coordinator.stop(); }
});

test('a held wake is released by a real 15 second deadline, with no second observation needed', async () => {
	const { run, planner, rows, timers } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => rows.some(({ event }) => event === 'native_wake_deferred_for_action'));
		assert.equal(timers.length, 1, 'the first held wake arms one deadline');
		assert.equal(timers[0].delayMs, 15_000);
		run.bridge.emit('observation', observation(3));
		await tick();
		await tick();
		assert.equal(timers.length, 1, 'later held wakes share the deadline');
		assert.equal(planner.requests.length, 1, 'still inside the hold');
		timers[0].callback();
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted', 'the held sighting is named when the hold ends');
		assert.equal(wake(planner.requests[1]).observation.player.x, 3 % 7, 'the release carries the freshest held facts');
		const resumed = rows.find(({ event }) => event === 'native_wake_resumed_after_action');
		assert.equal(resumed.skippedWakes, 2);
		assert.equal(resumed.reason, 'hold_limit');
	} finally { await run.coordinator.stop(); }
});

test('a lone sighting during a long action is released at the deadline although nothing else is observed', async () => {
	const { run, planner, rows, timers } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => timers.length === 1);
		await tick();
		assert.equal(planner.requests.length, 1);
		timers[0].callback();
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted');
		assert.equal(rows.find(({ event }) => event === 'native_wake_resumed_after_action').skippedWakes, 1);
		// The action is still running, so the next ordinary wake is held afresh with its own deadline.
		run.bridge.emit('observation', observation(4, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => timers.length === 2);
		await tick();
		assert.equal(planner.requests.length, 2);
	} finally { await run.coordinator.stop(); }
});

test('the deadline is cancelled when the wake resumes and cannot fire a second wake', async () => {
	const { run, planner, finish, timers } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => timers.length === 1);
		finish();
		run.bridge.emit('observation', observation(51, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(timers[0].cleared, true);
		timers[0].callback();
		await tick();
		assert.equal(planner.requests.length, 2, 'a late timer callback does nothing');
	} finally { await run.coordinator.stop(); }
});

test('the deadline is cancelled when the action ends, and the completion observation still names the sighting', async () => {
	const { run, planner, finish, timers } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => timers.length === 1);
		finish();
		await eventually(() => timers[0].cleared);
		timers[0].callback();
		await tick();
		assert.equal(planner.requests.length, 1, 'the ended action leaves nothing to release on a timer');
		run.bridge.emit('observation', observation(51, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted');
	} finally { await run.coordinator.stop(); }
});

test('a goal change drops the held wake and its deadline', async () => {
	const { run, planner, timers } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => timers.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'replace', goalRevision: 2, goal: 'Something else.' } });
		await eventually(() => timers[0].cleared);
		const before = planner.requests.length;
		timers[0].callback();
		await tick();
		assert.equal(planner.requests.length, before, 'the old goal is never woken for a sighting it did not see');
		assert.equal(planner.requests.some((request, index) => index >= 1 && request.goalRevision === 1), false);
	} finally { await run.coordinator.stop(); }
});

test('an agent removal and a disconnect drop the held wake and its deadline', async () => {
	for (const lifecycleEnd of ['agent_removed', 'disconnected']) {
		const { run, planner, timers } = await startWithRunningAction();
		try {
			run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
			await eventually(() => timers.length === 1);
			if (lifecycleEnd === 'agent_removed') run.bridge.emit('agent_removed', { agentId: 'agent-a', payload: { goalRevision: 1 } });
			else run.bridge.emit('disconnected', {});
			await eventually(() => timers[0].cleared);
			timers[0].callback();
			await tick();
			assert.equal(planner.requests.length, 1, lifecycleEnd);
		} finally { await run.coordinator.stop(); }
	}
});

test('stopping the coordinator clears the held deadline', async () => {
	const { run, timers } = await startWithRunningAction();
	run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
	await eventually(() => timers.length === 1);
	await run.coordinator.stop();
	assert.equal(timers[0].cleared, true);
});

/** Turn 1 starts a wait, then pauses mid-turn so the test can hold a wake and let the turn cancel or replace the wait. */
async function startWithCancellingTurn(secondTool) {
	const { timers, ...timerHooks } = fakeTimers();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const rows = [];
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	let run;
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length > 1) return { toolCalls: 0 };
		await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'turn-1', callId: 'c1', tool: { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 5000 } } });
		await gate;
		const first = run.bridge.sent.find(({ type }) => type === 'action_command');
		const second = request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'turn-1', callId: 'c2',
			tool: { ...secondTool, actionId: first.payload.actionId, goalRevision: request.goalRevision } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_cancel'));
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'CANCELLED', reasonCode: 'CANCELLED', actionType: 'wait', eventSequence: 30 } });
		await second;
		return { toolCalls: 2 };
	};
	run = await start({ registry, planner, ...timerHooks, traceWriter: { write(event, fields) { rows.push({ event, ...fields }); } }, config: CONFIG });
	run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
	run.bridge.emit('observation', observation(1, { attention: true, changedFacts: ['blocks'] }));
	await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
	// Held while the turn that owns the wait is still running.
	run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
	await eventually(() => timers.length === 1);
	return { run, planner, rows, timers, release };
}

test('cancelling the action releases the held wake, named once, and a later generic wake is not relabelled', async () => {
	const { run, planner, rows, timers, release } = await startWithCancellingTurn({ kind: 'cancel_action' });
	try {
		release();
		await eventually(() => planner.requests.length === 2);
		assert.equal(timers[0].cleared, true);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted', 'the held sighting reaches the model after the turn');
		assert.equal(rows.find(({ event }) => event === 'native_wake_resumed_after_action').reason, 'action_cancelled');
		await eventually(() => rows.filter(({ event }) => event === 'native_turn_completed').length === 2);
		run.bridge.emit('observation', observation(60, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 3);
		assert.notEqual(wake(planner.requests[2]).trigger, 'structure_sighted', 'the old reason is gone');
	} finally { release(); await run.coordinator.stop(); }
});

test('replacing the action releases the held wake, and the new action starts a hold of its own', async () => {
	const { run, planner, rows, timers, release } = await startWithCancellingTurn({ kind: 'replace_action', actionType: 'wait', arguments: { durationMs: 4000 } });
	try {
		release();
		await eventually(() => run.bridge.sent.filter(({ type }) => type === 'action_command').length === 2);
		assert.equal(timers[0].cleared, true, 'the old action\'s deadline is gone');
		assert.equal(rows.find(({ event }) => event === 'native_wake_resumed_after_action').reason, 'action_cancelled');
		run.bridge.emit('observation', observation(3, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => timers.length === 2);
		const replacement = run.bridge.sent.filter(({ type }) => type === 'action_command')[1];
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: replacement.payload.actionId, state: 'SUCCEEDED', reasonCode: 'WAIT_DONE', actionType: 'wait', eventSequence: 50 } });
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted', 'the released sighting reaches the model once the turn ends');
		assert.equal(timers[1].cleared, true, 'the replacement hold ends with its action');
	} finally { release(); await run.coordinator.stop(); }
});

test('danger still wakes the model at once while its action runs', async () => {
	const { run, planner } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, health: 14, changedFacts: ['player.health'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(planner.requests[1].priority, 'urgent');
		assert.equal(wake(planner.requests[1]).trigger, 'damage');
	} finally { await run.coordinator.stop(); }
});

test('an urgent explicit trigger is never deferred and clears the skipped reason', async () => {
	const { run, planner, rows } = await startWithRunningAction();
	try {
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => rows.some(({ event }) => event === 'native_wake_deferred_for_action'));
		run.bridge.emit('observation', observation(3, { attention: true, trigger: 'threat', priority: 'urgent', changedFacts: ['threats.zombie'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'threat');
		assert.equal(rows.find(({ event }) => event === 'native_wake_resumed_after_action').skippedWakes, 1);
	} finally { await run.coordinator.stop(); }
});

test('an ordinary observation still wakes an idle model with no action running', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => { planner.requests.push(request); return { toolCalls: 0 }; };
	const run = await start({ registry, planner, config: CONFIG });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', observation(1, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['sighted'] }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(wake(planner.requests[1]).trigger, 'structure_sighted');
	} finally { await run.coordinator.stop(); }
});

test('an ordinary wake queued behind a turn that then starts its own action is dropped, not replayed', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const rows = [];
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length > 1) return { toolCalls: 0 };
		await gate;
		await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'turn-1', callId: 'c1', tool: { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 5000 } } });
		return { toolCalls: 1 };
	};
	const run = await start({ registry, planner, traceWriter: { write(event, fields) { rows.push({ event, ...fields }); } }, config: CONFIG });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', observation(1, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['blocks'] }));
		await tick();
		release();
		await eventually(() => rows.some(({ event }) => event === 'native_turn_completed'));
		await tick();
		assert.equal(planner.requests.length, 1, 'the queued ordinary wake would only have said wait');
		assert.equal(rows.some(({ event }) => event === 'native_wake_deferred_for_action'), true);
	} finally { release(); await run.coordinator.stop(); }
});

test('a wake queued behind a running turn reports how long it waited when the turn ends', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const rows = [];
	let now = 1000;
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) await gate;
		return { toolCalls: 0 };
	};
	const run = await start({ registry, planner, controlNow: () => now, traceWriter: { write(event, fields) { rows.push({ event, ...fields }); } }, config: CONFIG });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', observation(1, { attention: true, changedFacts: ['blocks'] }));
		await eventually(() => planner.requests.length === 1);
		now = 2000;
		run.bridge.emit('observation', observation(2, { attention: true, changedFacts: ['blocks'] }));
		await tick();
		now = 4500;
		release();
		await eventually(() => rows.filter(({ event }) => event === 'native_turn_completed').length === 2);
		const first = rows.find(({ event }) => event === 'native_turn_completed');
		assert.equal(first.pendingWaitMs, 2500, 'the wake arrived at 2000 and the turn ended at 4500');
		assert.equal(first.pendingTrigger, 'attention');
		assert.equal(rows.filter(({ event }) => event === 'native_turn_completed')[1].pendingWaitMs, undefined, 'a turn with nothing queued reports none');
	} finally { release(); await run.coordinator.stop(); }
});
