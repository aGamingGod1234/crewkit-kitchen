import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { validateAction } from '../src/schema.mjs';

const record = { agentId: 'agent-a', goalRevision: 1, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
function observation(health = 20) { return { player: { x: 0, y: 64, z: 0, health }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } }; }
function turn() { return new Promise((resolve) => setImmediate(resolve)); }
function setup(overrides = {}, options = {}) {
	let sequence = 1;
	const commands = [], cancels = [];
	const executor = new NativeProgramExecutor(options);
	const context = { observation: observation(), eventSequence: sequence,
		executeAction: async (command) => { commands.push(command); return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
		cancelAction: async (actionId, reason) => { cancels.push({ actionId, reason }); },
		refreshObservation: async () => ({ observation: observation(), eventSequence: ++sequence }), ...overrides };
	return { executor, context, commands, cancels };
}

test('explicit ranged options survive ArenaScript execution', async () => {
	const run = setup();
	const result = await run.executor.run(record, { source: `${prefix}
		await player.useRanged({ targetId: "11111111-1111-1111-1111-111111111111", drawDurationMs: 1000, timeoutMs: 3000, aimX: 2, aimY: 65, aimZ: 3 });
		await player.useItem({ durationMs: 1000, mode: "once" });
	` }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(run.commands[0].action.arguments.aimY, 65);
	assert.equal(run.commands[1].action.arguments.mode, 'once');
});

test('a reusable authored routine takes detached coordinates and counts while observations and identity stay authoritative', async () => {
	const source = `${prefix}
		const args = program.parameters();
		for (const target of args.targets) await player.navigateTo({ x: target.x, y: target.y, z: target.z, tolerance: 1, sprint: false, timeoutMs: 5000 });
		await player.craftInventory({ recipeId: args.recipeId, count: args.count, timeoutMs: 5000 });
		await player.wait(player.state().health);
	`;
	const original = { targets: [{ x: 2, y: 64, z: 3 }], recipeId: 'minecraft:planks', count: 1,
		observation: observation(99), agentId: 'other-agent', goalRevision: 999, model: 'other-model' };
	const first = setup({ refreshObservation: async () => ({ observation: observation(18), eventSequence: ++sequence }) });
	let sequence = 1;
	const pending = first.executor.run(record, { source, parameters: original }, first.context);
	original.targets[0].x = 99; original.count = 99;
	const one = await pending;
	assert.equal(first.commands[0].action.arguments.x, 2);
	assert.equal(first.commands[1].action.arguments.count, 1);
	assert.equal(first.commands[2].action.arguments.durationMs, 18);
	for (const command of first.commands) {
		assert.equal(command.provenance.agentId, record.agentId);
		assert.equal(command.provenance.goalRevision, record.goalRevision);
		assert.equal(command.provenance.model, record.model);
	}
	assert.equal(one.actionsSucceeded, 3); assert.equal(one.actionsFailed, 0); assert.equal(one.programVersion, 1);
	const second = setup();
	const two = await second.executor.run(record, { source, parameters: { targets: [{ x: 5, y: 65, z: 6 }, { x: 7, y: 66, z: 8 }], recipeId: 'minecraft:planks', count: 2 } }, second.context);
	assert.deepEqual(second.commands.filter((command) => command.action.type === 'navigate_to').map((command) => command.action.arguments.x), [5, 7]);
	assert.equal(second.commands[2].action.arguments.count, 2);
	assert.equal(two.actionsSucceeded, 4); assert.equal(two.actionsFailed, 0);
	assert.equal(one.reasonCode, 'PROGRAM_EXHAUSTED'); assert.equal(two.reasonCode, 'PROGRAM_EXHAUSTED');
});

test('executor rejects invalid parameter data before installing timers or dispatching actions', () => {
	const timers = [];
	const run = setup({}, { setTimeoutFn: (...args) => { timers.push(args); } });
	for (const parameters of [{ count: Infinity }, JSON.parse('{"__proto__":{}}'), { value: 'x'.repeat(4096) }]) {
		assert.throws(() => run.executor.run(record, { source: `${prefix} await player.wait(1);`, parameters }, run.context), (error) => error.code === 'INVALID_PROGRAM_PARAMETERS');
	}
	assert.equal(timers.length, 0);
	assert.equal(run.commands.length, 0);
	assert.equal(run.executor.status(record), null);
});

test('full-run terminal action counts preserve handled failures outside the receipt ring', async () => {
	let index = 0;
	const run = setup({ executeAction: async (command) => {
		run.commands.push(command);
		return index++ === 0 ? { state: 'FAILED', reasonCode: 'BLOCKED' } : { state: 'SUCCEEDED', reasonCode: 'DONE' };
	} });
	const result = await run.executor.run(record, { source: `${prefix} await tryResult(player.wait(1)); for (let i = 0; i < 64; i++) await player.wait(2);`, maxActions: 65 }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 65);
	assert.equal(result.actionsFailed, 1);
	assert.equal(result.actionsSucceeded, 64);
	assert.equal(result.omittedReceipts, 1);
	assert.equal(result.receipts.length, 64);
	assert.ok(result.receipts.every((receipt) => receipt.state === 'SUCCEEDED'));
});

test('expected duration schedules one advisory while preserving the original hard deadline', async () => {
	const timers = [], advisories = [];
	let release;
	const run = setup({ executeAction: () => new Promise((resolve) => { release = resolve; }), onPlanningDue: (status, details) => advisories.push({ status, details }),
		cancelAction: async () => release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }) },
		{ setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; }, clearTimeoutFn: () => {} });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(1);`, timeoutMs: 100, expectedDurationMs: 60, planningLeadMs: 25 }, run.context);
	assert.equal(timers[0].ms, 100);
	const advisory = timers.find((timer) => timer.ms === 35);
	assert.ok(advisory);
	advisory.callback(); advisory.callback();
	assert.equal(advisories.length, 1);
	assert.equal(run.cancels.length, 0);
	timers[0].callback();
	const result = await pending;
	assert.equal(result.reasonCode, 'PROGRAM_DEADLINE');
	assert.equal(result.actionsFailed, 1);
	assert.equal(result.actionsSucceeded, 0);
	assert.throws(() => run.executor.run(record, { source: prefix, timeoutMs: 100, expectedDurationMs: 101 }, run.context));
});

test('an unresolved decision prevents natural exhaustion and remains visible in a deadline outcome', async () => {
	const timers = [];
	const run = setup({ onDecision() {} }, { setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; }, clearTimeoutFn: () => {} });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(1);` }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	await turn();
	assert.equal(run.executor.status(record).decision.trigger, 'health_changed');
	timers[0].callback();
	const result = await pending;
	assert.equal(result.reasonCode, 'PROGRAM_DEADLINE');
	assert.equal(result.actionsSucceeded, 1);
	assert.equal(result.decision.priority, 'urgent');
	assert.equal(result.decision.trigger, 'health_changed');
});

test('source replacement keeps the run parameters and reports its final version and complete action totals', async () => {
	let release;
	const commands = [];
	const run = setup({
		onDecision() {},
		executeAction: (command) => {
			commands.push(command);
			return commands.length === 1 ? new Promise((resolve) => { release = resolve; }) : Promise.resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
		},
		cancelAction: async () => release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }),
	});
	const pending = run.executor.run(record, { programId: 'parameter-replacement', source: `${prefix} await player.wait(1);`, parameters: { count: 9 } }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true });
	const decision = run.executor.status(record).decision;
	run.executor.respond(record, { programId: 'parameter-replacement', decisionId: decision.decisionId, directive: 'replace', source: `${prefix} const args = program.parameters(); await player.wait(args.count);` });
	const result = await pending;
	assert.deepEqual(commands.map((command) => command.action.arguments.durationMs), [1, 9]);
	assert.equal(result.programVersion, 2);
	assert.equal(result.actionsSucceeded, 1);
	assert.equal(result.actionsFailed, 1);
	assert.equal(result.decision, undefined);
});

test('native programs use the same candidate arithmetic and exact authored commands, returning to their caller at exhaustion', async () => {
	const run = setup({ observation: { ...observation(), entities: [
		{ stableId: '11111111-1111-1111-1111-111111111111', type: 'minecraft:pig', x: 2, y: 64, z: 2, velocity: { x: 1, y: 0, z: 0 } },
	] } });
	const result = await run.executor.run(record, { source: `${prefix}
		let target = null;
		for (const candidate of world.entities()) if (candidate.velocity.x > 0) target = candidate;
		await player.control({ forward: 0, strafe: 1, jump: false, sneak: true, sprint: false, attack: false, use: false,
			yaw: math.atan2(target.z, target.x) * 180 / 3.141592653589793, pitch: 0, selectedSlot: 0, hand: "main", ticks: 2 });
		await player.wait(2);
	` }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 2);
	assert.equal(run.commands[0].action.arguments.yaw, 45);
	assert.deepEqual(run.commands[1].action, { type: 'wait', arguments: { durationMs: 2 } });
	for (const command of run.commands) {
		validateAction({ type: command.action.type, ...command.action.arguments });
		assert.equal(command.provenance.model, record.model);
		assert.equal(command.provenance.reasoningEffort, record.reasoningEffort);
		assert.match(command.provenance.programId, /^native-program-/);
		assert.match(command.provenance.stepId, /^step-/);
	}
	assert.equal(result.receipts[0].state, 'SUCCEEDED');
	assert.equal(run.cancels.length, 0);
});

test('queries and notes retain model authorship without acquiring physical action identity', async () => {
	const queries = [], memories = [];
	const run = setup({ inspect: async (query) => { queries.push(query); return { state: 'SUCCEEDED', menu: { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 } }; },
		memoryOperation: async (request) => { memories.push(request); return { state: 'SUCCEEDED', entries: [] }; } });
	const result = await run.executor.run(record, { source: `${prefix}
		const page = await world.inspect({ section: "menu" });
		await world.remember({ key: "container", text: "Inspected the nearby container." });
		await world.queryMemory({ kind: "notes", offset: 2, limit: 4 });
		await player.menuClose({ menuId: page.menu.menuId, containerId: page.menu.containerId, stateId: page.menu.stateId });
	` }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(run.commands.length, 1);
	assert.equal(run.commands[0].action.arguments.stateId, 9);
	assert.equal(queries.length, 1);
	assert.equal(memories[0].provenance.model, record.model);
	assert.match(memories[0].provenance.sourceStepId, /^step-/);
	assert.equal(memories[0].provenance.actionId, undefined);
	assert.equal(memories[1].arguments.offset, 2);
});

test('an authored interrupt watcher cancels the exact body command before issuing its authored response', async () => {
	let release;
	let sequence = 2;
	const commands = [], cancelled = [];
	const run = setup({ executeAction: (command) => {
		commands.push(command);
		return commands.length === 1 ? new Promise((resolve) => { release = resolve; }) : Promise.resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	}, cancelAction: async (actionId) => { cancelled.push(actionId); release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); },
	refreshObservation: async () => ({ observation: observation(4), eventSequence: ++sequence }) });
	const pending = run.executor.run(record, { source: `${prefix}
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(100);
	` }, run.context);
	run.executor.onObservation(record, { observation: observation(4), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	const result = await pending;
	assert.deepEqual(commands.map((command) => command.action.arguments.durationMs), [100, 9]);
	assert.deepEqual(cancelled, [commands[0].actionId]);
	assert.equal(result.receipts[0].state, 'CANCELLED');
	assert.equal(result.reasonCode, 'PROGRAM_IDLE');
});

test('continue attention waits for the current action and returns before any further command', async () => {
	let release;
	const commands = [];
	const run = setup({ executeAction: (command) => { commands.push(command); return new Promise((resolve) => { release = resolve; }); } });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(100); await player.wait(2);` }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	assert.equal(run.cancels.length, 0);
	release({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	const result = await pending;
	assert.equal(result.reasonCode, 'MODEL_DECISION_REQUIRED');
	assert.equal(result.trigger, 'health_changed');
	assert.equal(commands.length, 1);
	assert.equal(run.cancels.length, 0);
});

test('pause attention honors the model-authored cancellation policy', async () => {
	let release;
	const run = setup({ executeAction: () => new Promise((resolve) => { release = resolve; }),
		cancelAction: async () => { release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); } });
	const pending = run.executor.run(record, { source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(100); await player.wait(2);' }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	const result = await pending;
	assert.equal(result.reasonCode, 'MODEL_DECISION_REQUIRED');
	assert.equal(result.actions, 1);
	assert.equal(result.receipts[0].state, 'CANCELLED');
});

test('action budget and missing fresh facts stop execution without adding a strategy or retry', async () => {
	const bounded = setup();
	const limited = await bounded.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);`, maxActions: 1 }, bounded.context);
	assert.equal(limited.reasonCode, 'PROGRAM_ACTION_LIMIT');
	assert.equal(bounded.commands.length, 1);
	const stale = setup({ refreshObservation: async () => ({ observation: observation(), eventSequence: 1 }) });
	const stopped = await stale.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, stale.context);
	assert.equal(stopped.reasonCode, 'FRESH_OBSERVATION_REQUIRED');
	assert.equal(stale.commands.length, 1);
	assert.equal(stopped.receipts[0].state, 'SUCCEEDED');
});

test('deadlines cancel exact inputs and unresolved acknowledgements are reported as unknown', async () => {
	const timers = [];
	const cancelled = [];
	const run = setup({ executeAction: () => new Promise(() => {}), cancelAction: async (actionId) => { cancelled.push(actionId); } },
		{ setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; }, clearTimeoutFn: () => {} });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(100);`, timeoutMs: 10 }, run.context);
	timers[0].callback();
	assert.equal(cancelled.length, 1);
	assert.equal(timers[1].ms, 5000);
	timers[1].callback();
	const result = await pending;
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'PROGRAM_CANCEL_ACK_TIMEOUT');
	assert.equal(result.receipts.length, 0);
});

test('a late cancellation failure cannot stop the interrupt handler after the old action acknowledges', async () => {
	const commands = [], releases = [];
	let rejectCancel;
	let sequence = 2;
	const run = setup({
		executeAction: (command) => {
			commands.push(command);
			return new Promise((resolve) => { releases.push(resolve); });
		},
		cancelAction: () => new Promise((resolve, reject) => { rejectCancel = reject; }),
		refreshObservation: async () => ({ observation: observation(4), eventSequence: ++sequence }),
	});
	const pending = run.executor.run(record, { source: `${prefix}
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(100);
	` }, run.context);
	run.executor.onObservation(record, { observation: observation(4), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	releases[0]({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' });
	await turn();
	assert.deepEqual(commands.map((command) => command.action.arguments.durationMs), [100, 9]);
	rejectCancel(Object.assign(new Error('late cancellation transport failure'), { code: 'BRIDGE_DISCONNECTED' }));
	await turn();
	assert.notEqual(run.executor.status(record), null, 'the acknowledged cancellation must not invalidate the running handler');
	releases[1]({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	const result = await pending;
	assert.equal(result.reasonCode, 'PROGRAM_IDLE');
	assert.deepEqual(result.receipts.map((receipt) => receipt.state), ['CANCELLED', 'SUCCEEDED']);
});

test('a cancellation transport failure remains unknown while its original action is still pending', async () => {
	const commands = [], cancelled = [];
	const run = setup({
		executeAction: (command) => { commands.push(command); return new Promise(() => {}); },
		cancelAction: async (actionId) => {
			cancelled.push(actionId);
			throw Object.assign(new Error('cancellation transport failed'), { code: 'BRIDGE_DISCONNECTED' });
		},
	});
	const pending = run.executor.run(record, { source: `${prefix}
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(100);
	` }, run.context);
	run.executor.onObservation(record, { observation: observation(4), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	const result = await pending;
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'BRIDGE_DISCONNECTED');
	assert.deepEqual(cancelled, [commands[0].actionId]);
	assert.equal(commands.length, 1, 'the handler must not dispatch without cancellation acknowledgement');
	assert.deepEqual(result.receipts, []);
});

test('planning-ahead sends one advisory before the deadline without interrupting the body', async () => {
	const timers = [];
	const advisories = [];
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; },
		clearTimeoutFn: () => {},
	});
	const run = setup({ onPlanningDue: (status, details) => advisories.push({ status, details }) });
	const pending = executor.run(record, {
		source: `${prefix} await player.wait(1);`, timeoutMs: 100, planningLeadMs: 25,
	}, { ...run.context, onPlanningDue: (status, details) => advisories.push({ status, details }) });
	const due = timers.find((timer) => timer.ms === 75);
	assert.ok(due, 'the advisory is scheduled one measured lead time before the deadline');
	due.callback();
	due.callback();
	assert.equal(advisories.length, 1);
	assert.equal(advisories[0].status.programVersion, 1);
	assert.equal(advisories[0].status.engineState, 'ACTIVE');
	assert.deepEqual(advisories[0].details, { planningLeadMs: 25 });
	assert.equal(run.cancels.length, 0);
	assert.equal((await pending).reasonCode, 'PROGRAM_EXHAUSTED');
});

test('planning-ahead does not replace a real hazard decision and stale version timers are fenced', async () => {
	const timers = [];
	const advisories = [];
	let release;
	let calls = 0;
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; },
		clearTimeoutFn: () => {},
	});
	const run = setup({
		executeAction: () => ++calls === 1
			? new Promise((resolve) => { release = resolve; })
			: Promise.resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' }),
		onDecision: () => {},
		onPlanningDue: (status, details) => advisories.push({ status, details }),
	});
	const pending = executor.run(record, {
		source: `${prefix} await player.wait(100); await player.wait(2);`, timeoutMs: 100, planningLeadMs: 25, programId: 'planning-ahead-test',
	}, { ...run.context, onPlanningDue: (status, details) => advisories.push({ status, details }) });
	const due = timers.find((timer) => timer.ms === 75);
	executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	await turn();
	const decision = executor.status(record).decision;
	assert.equal(decision.trigger, 'health_changed');
	executor.respond(record, { programId: 'planning-ahead-test', decisionId: decision.decisionId, directive: 'replace', source: `${prefix} await player.wait(3);` });
	// The timer was armed for version 1. Replaying it after replacement must
	// not send an advisory carrying stale program-version authority.
	due.callback();
	assert.equal(advisories.length, 0);
	release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' });
	await pending;
});

test('cancelled query results cannot release later body actions and finish remains a model request', async () => {
	let release;
	const run = setup({ inspect: () => new Promise((resolve) => { release = resolve; }) });
	const pending = run.executor.run(record, { source: `${prefix} await world.inspect({ section: "menu" }); await player.wait(1);` }, run.context);
	await run.executor.cancel(record.agentId);
	release({ state: 'SUCCEEDED', menu: null });
	await turn();
	assert.equal((await pending).state, 'CANCELLED');
	assert.equal(run.commands.length, 0);
	assert.equal(run.cancels.length, 0);
	const finished = await run.executor.run(record, { source: `${prefix} program.finish("ready for verification");` }, run.context);
	assert.equal(finished.finishRequested, true);
	assert.equal(finished.state, 'YIELDED');
	assert.equal(finished.reasonCode, 'PROGRAM_FINISH_REQUESTED');
});

test('sandbox errors and action callback failures never invoke a replacement provider or fabricate completion', async () => {
	const run = setup({ executeAction: async () => { throw Object.assign(new Error('transport unavailable'), { code: 'BRIDGE_DISCONNECTED' }); } });
	assert.throws(() => run.executor.run(record, { source: `${prefix} globalThis.fetch("invalid");` }, run.context));
	const result = await run.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, run.context);
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'BRIDGE_DISCONNECTED');
	assert.equal(result.actions, 1);
	assert.equal(result.receipts.length, 0);
});

test('nonterminal body callback results release the exact inputs and remain unknown', async () => {
	const run = setup({ executeAction: async () => ({ state: 'RUNNING', reasonCode: 'STARTED' }) });
	const result = await run.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, run.context);
	await turn();
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'INVALID_ACTION_RESULT');
	assert.equal(result.receipts.length, 0);
	assert.equal(run.cancels.length, 1);
	assert.equal(run.cancels[0].reason, 'INVALID_ACTION_RESULT');
});

test('program identities are unique in production and reproducible only with an injected replay session', async () => {
	const source = `${prefix} program.checkpoint("ready");`;
	const first = setup(), second = setup();
	const one = await first.executor.run(record, { source }, first.context);
	const two = await second.executor.run(record, { source }, second.context);
	assert.notEqual(one.programId, two.programId);
	const replayOne = setup({}, { sessionId: 'synthetic-replay' });
	const replayTwo = setup({}, { sessionId: 'synthetic-replay' });
	assert.equal((await replayOne.executor.run(record, { source }, replayOne.context)).programId,
		(await replayTwo.executor.run(record, { source }, replayTwo.context)).programId);
});
