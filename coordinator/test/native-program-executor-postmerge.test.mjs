import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';

const record = { agentId: 'postmerge-program', goalRevision: 1, provider: 'codex', model: 'selected-model', reasoningEffort: 'high', serviceTier: 'priority' };
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const observation = (health = 20) => ({ player: { x: 0, y: 64, z: 0, health }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } });
const turn = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function setup(t, source = `${prefix} await player.wait(1); await player.wait(2);`) {
	const timers = [], commands = [], actions = [], samples = [], cancels = [];
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms, cleared: false }; timers.push(timer); return timer; },
		clearTimeoutFn: timer => { timer.cleared = true; },
	});
	const context = { observation: observation(), eventSequence: 1,
		executeAction: command => { commands.push(command); const action = deferred(); actions.push(action); return action.promise; },
		cancelAction: async id => { cancels.push(id); actions.at(-1).resolve({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); },
		refreshObservation: () => { const sample = deferred(); samples.push(sample); return sample.promise; },
		onDecision: () => {},
	};
	const result = executor.run(record, { source, programId: 'postmerge', observationIntervalMs: 100 }, context);
	t.after(() => executor.cancel(record.agentId));
	const sampleTimer = () => timers.findLast(timer => timer.ms === 100 && !timer.cleared);
	return { executor, result, timers, commands, actions, samples, cancels, sampleTimer };
}

for (const obsolete of ['failure', 'success']) test(`post-result inspection supersedes a pending interval ${obsolete} without losing newer request ownership`, async t => {
	const run = setup(t);
	const interval = run.sampleTimer().callback();
	await turn();
	assert.equal(run.samples.length, 1);
	run.actions[0].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	await turn();
	assert.equal(run.samples.length, 2, 'terminal inspection starts before the old interval settles');
	if (obsolete === 'failure') run.samples[0].reject(Object.assign(new Error('obsolete'), { code: 'INSPECTION_TIMEOUT' }));
	else run.samples[0].resolve({ observation: observation(1), eventSequence: 99, attention: true, priority: 'urgent', trigger: 'damage' });
	await interval;
	assert.equal(run.commands.length, 1, 'fresh terminal facts are still required');
	assert.equal(run.executor.status(record).decision, undefined, 'obsolete facts cannot create attention');
	assert.deepEqual(run.cancels, []);
	assert.equal(run.timers.filter(timer => timer.ms === 100).length, 1, 'obsolete finally cannot schedule a competing sampler');
	run.samples[1].resolve({ observation: observation(), eventSequence: 3 });
	await turn();
	assert.equal(run.commands.length, 2);
	assert.equal(run.commands[1].provenance.eventSequence, 3);
	assert.equal(run.timers.filter(timer => timer.ms === 100).length, 2, 'current completion owns interval scheduling');
	run.actions[1].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: observation(), eventSequence: 4 });
	assert.equal((await run.result).reasonCode, 'PROGRAM_EXHAUSTED');
});

test('a newer pushed publication supersedes interval failure and preserves the replacement sampler', async t => {
	const run = setup(t);
	const oldInterval = run.sampleTimer().callback();
	await turn();
	run.executor.onObservation(record, { observation: observation(), eventSequence: 3 });
	const newInterval = run.sampleTimer().callback();
	await turn();
	assert.equal(run.samples.length, 2);
	run.samples[0].reject(Object.assign(new Error('obsolete'), { code: 'INSPECTION_TIMEOUT' }));
	await oldInterval;
	assert.ok(run.executor.status(record));
	assert.equal(run.timers.filter(timer => timer.ms === 100).length, 2);
	run.samples[1].resolve({ observation: observation(), eventSequence: 4 });
	await newInterval;
	assert.equal(run.timers.filter(timer => timer.ms === 100).length, 3);
	assert.deepEqual(run.cancels, []);
});

test('terminal result facts release the next action while an obsolete interval remains pending', async t => {
	const run = setup(t);
	const interval = run.sampleTimer().callback();
	await turn();
	run.actions[0].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: observation(), eventSequence: 2 });
	run.executor.onObservation(record, { observation: observation(19), eventSequence: 3 });
	await turn();
	assert.equal(run.commands.length, 2);
	assert.equal(run.samples.length, 1);
	assert.equal(run.commands[1].provenance.eventSequence, 3);
	run.samples[0].reject(Object.assign(new Error('obsolete'), { code: 'INSPECTION_TIMEOUT' }));
	await interval;
	assert.deepEqual(run.cancels, []);
	run.actions[1].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: observation(19), eventSequence: 4 });
	const result = await run.result;
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actionsSucceeded, 2);
});

test('obsolete inspection finally cannot clear a newer terminal refresh shared by the next interval', async t => {
	const run = setup(t);
	const oldInterval = run.sampleTimer().callback();
	await turn();
	run.executor.onObservation(record, { observation: observation(), eventSequence: 2 });
	run.actions[0].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	await turn();
	assert.equal(run.samples.length, 2);
	run.samples[0].reject(Object.assign(new Error('obsolete'), { code: 'INSPECTION_TIMEOUT' }));
	await oldInterval;
	const currentInterval = run.sampleTimer().callback();
	await turn();
	assert.equal(run.samples.length, 2, 'the timer joins the existing terminal refresh instead of replacing it');
	run.samples[1].resolve({ observation: observation(), eventSequence: 3 });
	await currentInterval;
	assert.equal(run.commands.length, 2);
	assert.deepEqual(run.cancels, []);
	run.actions[1].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: observation(), eventSequence: 4 });
	assert.equal((await run.result).reasonCode, 'PROGRAM_EXHAUSTED');
});

test('a current interval failure still stops the program and cancels its exact body', async t => {
	const run = setup(t);
	const interval = run.sampleTimer().callback();
	await turn();
	run.samples[0].reject(Object.assign(new Error('current'), { code: 'INSPECTION_TIMEOUT' }));
	await interval;
	const result = await run.result;
	assert.equal(result.state, 'FAILED');
	assert.equal(result.reasonCode, 'INSPECTION_TIMEOUT');
	assert.deepEqual(run.cancels, [run.commands[0].actionId]);
});

test('continue after the final action yields PROGRAM_EXHAUSTED with its successful receipt', async t => {
	const run = setup(t, `${prefix} await player.wait(1);`);
	run.executor.onObservation(record, { observation: observation(), eventSequence: 2, attention: true, trigger: 'conversation' });
	run.actions[0].resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: observation(), eventSequence: 3 });
	await turn();
	const decision = run.executor.status(record).decision;
	run.executor.respond(record, { programId: 'postmerge', decisionId: decision.decisionId, directive: 'continue' });
	const result = await run.result;
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actionsSucceeded, 1);
	assert.equal(result.actionsFailed, 0);
	assert.equal(run.commands.length, 1);
});

test('runtime post-result publication advances past an old interval request and ignores its later timeout', async t => {
	const timers = [], commands = [], requests = [];
	const current = { ...observation(), ready: true, world: { worldId: 'test-world', dimension: 'minecraft:overworld' } };
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; },
		clearTimeoutFn: () => {},
	});
	let gateSamples = false, callId = 0;
	const runtime = new NativeToolRuntime({
		programExecutor: executor,
		bridge: { send: async (type, agentId, payload) => {
			if (type === 'action_command') commands.push(payload);
			if (type === 'action_cancel') queueMicrotask(() => runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 1, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }));
		} },
		registry: { get: () => record },
		requestObservation: async (_record, { afterEventSequence }) => {
			if (!gateSamples) return { observation: current, eventSequence: afterEventSequence + 1 };
			const request = deferred(); requests.push(request); return request.promise;
		},
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, current, { eventSequence: 1 });
	const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'authored-turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const until = async predicate => { for (let i = 0; i < 50 && !predicate(); i++) await turn(); assert.ok(predicate()); };
	await call('runProgram', { source: `${prefix} await player.wait(1); await player.wait(2);`, background: true, observationIntervalMs: 100 });
	await until(() => commands.length === 1);
	gateSamples = true;
	const interval = timers.find(timer => timer.ms === 100).callback();
	await until(() => requests.length === 1);
	runtime.onActionResult(record, { actionId: commands[0].actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	runtime.updateObservation(record, current, { eventSequence: 10 });
	await until(() => commands.length === 2);
	requests[0].reject(Object.assign(new Error('obsolete interval'), { code: 'INSPECTION_TIMEOUT' }));
	await interval;
	assert.ok(executor.status(record));
	gateSamples = false;
	runtime.onActionResult(record, { actionId: commands[1].actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	runtime.updateObservation(record, current, { eventSequence: 11 });
	await until(() => executor.status(record) === null);
	await turn();
	assert.equal((await call('programStatus')).reasonCode, 'PROGRAM_EXHAUSTED');
});

test('runtime hands a valid queued successor the body after continue releases final-action exhaustion', async t => {
	const commands = [], events = [];
	const current = { ...observation(), ready: true, world: { worldId: 'test-world', dimension: 'minecraft:overworld' } };
	let callId = 0;
	const runtime = new NativeToolRuntime({
		bridge: { send: async (type, agentId, payload) => {
			if (type === 'action_command') commands.push(payload);
			if (type === 'action_cancel') queueMicrotask(() => runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 1, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }));
		} },
		registry: { get: () => record },
		onProgramEvent: (_record, event) => events.push(event),
		requestObservation: async (_record, { afterEventSequence }) => ({ observation: current, eventSequence: afterEventSequence + 1 }),
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, current, { eventSequence: 1 });
	const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'authored-turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const until = async predicate => { for (let i = 0; i < 50 && !predicate(); i++) await turn(); assert.ok(predicate()); };
	const handle = await call('runProgram', { source: `${prefix} await player.wait(1);`, background: true });
	await until(() => commands.length === 1);
	await call('queueProgram', { afterProgramId: handle.programId, goalRevision: 1, programVersion: 1,
		precondition: 'player.state().health > 0', source: `${prefix} await player.wait(2);` });
	runtime.updateObservation(record, current, { eventSequence: 10, attention: true, trigger: 'conversation' });
	runtime.onActionResult(record, { actionId: commands[0].actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	await turn(); await turn();
	const status = await call('programStatus');
	assert.ok(status.decision);
	assert.equal(commands.length, 1);
	await call('respondProgram', { programId: handle.programId, goalRevision: 1, decisionId: status.decision.decisionId, directive: 'continue' });
	await until(() => commands.length === 2);
	assert.equal(commands[1].arguments.durationMs, 2);
	assert.equal(commands[1].provenance.model, record.model);
	assert.equal(events.some(event => event.event === 'program_handoff_rejected'), false);
	runtime.onActionResult(record, { actionId: commands[1].actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	await until(() => events.some(event => event.event === 'program_ended'));
	assert.equal((await call('programStatus')).reasonCode, 'PROGRAM_EXHAUSTED');
});

test('runtime drains a boundary handler after final-action continue before handing off the queued successor', async t => {
	const commands = [], events = [], cancels = [];
	let current = { ...observation(), ready: true, world: { worldId: 'test-world', dimension: 'minecraft:overworld' } };
	let callId = 0;
	const runtime = new NativeToolRuntime({
		bridge: { send: async (type, agentId, payload) => {
			if (type === 'action_command') commands.push(payload);
			if (type === 'action_cancel') {
				cancels.push(payload.actionId);
				queueMicrotask(() => runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 1, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }));
			}
		} },
		registry: { get: () => record },
		onProgramEvent: (_record, event) => events.push(event),
		requestObservation: async (_record, { afterEventSequence }) => ({ observation: current, eventSequence: afterEventSequence + 1 }),
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, current, { eventSequence: 1 });
	const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'authored-turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const until = async predicate => { for (let i = 0; i < 50 && !predicate(); i++) await turn(); assert.ok(predicate()); };
	const succeed = command => runtime.onActionResult(record, { actionId: command.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	const handle = await call('runProgram', { source: `${prefix}
		program.watch(() => player.state().health < 10, {mode:"boundary"}, async () => { await player.wait(9); await player.wait(8); });
		await player.wait(1);`, background: true });
	await until(() => commands.length === 1);
	await call('queueProgram', { afterProgramId: handle.programId, goalRevision: 1, programVersion: 1,
		precondition: 'player.state().health > 0', source: `${prefix} await player.wait(2);` });
	runtime.updateObservation(record, current, { eventSequence: 10, attention: true, trigger: 'conversation' });
	succeed(commands[0]);
	await turn(); await turn();
	assert.equal(commands.length, 1);
	current = { ...current, player: { ...current.player, health: 9 } };
	runtime.updateObservation(record, current, { eventSequence: 20 });
	await until(() => commands.length === 2);
	const status = await call('programStatus');
	assert.equal(status.decision.trigger, 'conversation');
	await call('respondProgram', { programId: handle.programId, goalRevision: 1, decisionId: status.decision.decisionId, directive: 'continue' });
	assert.equal((await call('programStatus')).decision, undefined, 'continue must not declare exhaustion while the handler is active');
	succeed(commands[1]);
	await until(() => commands.length === 3);
	assert.deepEqual(commands.map(command => command.arguments.durationMs), [1, 9, 8]);
	assert.equal(commands[2].provenance.watcherId, 'watcher-0');
	succeed(commands[2]);
	await until(() => commands.length === 4);
	assert.deepEqual(commands.map(command => command.arguments.durationMs), [1, 9, 8, 2]);
	assert.deepEqual(cancels, []);
	assert.equal(events.some(event => event.event === 'program_handoff_rejected'), false);
	succeed(commands[3]);
	await until(() => events.some(event => event.event === 'program_ended'));
	assert.equal((await call('programStatus')).reasonCode, 'PROGRAM_EXHAUSTED');
});
