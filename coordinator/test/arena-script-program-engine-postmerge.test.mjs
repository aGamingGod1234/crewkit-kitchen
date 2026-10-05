import assert from 'node:assert/strict';
import test from 'node:test';
import { ArenaScriptEngine } from '../src/arena-script/program-engine.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const observation = (air = 61, x = 0) => ({ player: { x, y: 64, z: 0, health: 20, air }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } });
function setup(source) {
	const commands = [], cancels = [], requests = [];
	const engine = new ArenaScriptEngine({ dispatch: command => commands.push(command), cancel: id => cancels.push(id), requestModel: request => requests.push(request) });
	engine.install({ agentId: 'a', goalRevision: 1, modelIdentity: 'selected-model', programId: 'p', version: 1,
		compiled: parseArenaScript(source), observation: observation(), eventSequence: 1 });
	return { engine, commands, cancels, requests };
}

test('air 61 to 60 requests survival attention despite an unrelated boundary edge', () => {
	const run = setup(`${prefix}
		program.watch(() => player.state().x > 0, {mode:"boundary"}, async () => { await player.wait(9); });
		await player.wait(100); await player.wait(2);`);
	run.engine.ingestObservation({ observation: observation(60, 1), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'suffocation' });
	assert.deepEqual(run.cancels, [run.commands[0].actionId]);
	assert.equal(run.requests.length, 1);
	assert.equal(run.requests[0].trigger, 'suffocation');
	run.engine.ingestActionResult({ actionId: run.commands[0].actionId, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED', eventSequence: 2 });
	run.engine.ingestObservation({ observation: observation(59, 1), eventSequence: 3 });
	assert.equal(run.engine.snapshot().status, 'SUSPENDED');
	assert.equal(run.commands.length, 1, 'neither the unrelated watcher nor the next leg bypasses the decision');
	assert.equal(run.requests.length, 1, 'the server need not repeat the threshold warning');
});

test('survival notification preserves an authored interrupt through its exact cancellation acknowledgement', () => {
	const run = setup(`${prefix}
		program.watch(() => player.state().air <= 60, {mode:"interrupt",after:"reconsider"}, async () => { await player.wait(9); });
		await player.wait(100); await player.wait(2);`);
	run.engine.ingestObservation({ observation: observation(60), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'suffocation' });
	assert.deepEqual(run.cancels, [run.commands[0].actionId]);
	assert.equal(run.commands.length, 1);
	run.engine.ingestActionResult({ actionId: 'wrong-action', state: 'CANCELLED', reasonCode: 'INPUT_RELEASED', eventSequence: 2 });
	assert.equal(run.commands.length, 1);
	run.engine.ingestActionResult({ actionId: run.commands[0].actionId, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED', eventSequence: 2 });
	assert.equal(run.commands[1].action.arguments, 9);
	assert.equal(run.commands[1].provenance.watcherId, 'watcher-0');
	run.engine.ingestObservation({ observation: observation(59), eventSequence: 3, attention: true, priority: 'urgent', trigger: 'suffocation' });
	assert.equal(run.cancels.length, 1, 'ongoing authored defense keeps its authority');
	run.engine.ingestActionResult({ actionId: run.commands[1].actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	assert.equal(run.engine.snapshot().status, 'SUSPENDED');
	assert.equal(run.commands.length, 2);
	assert.equal(run.engine.refreshDirectiveRequest().trigger, 'defensive_handler_completed');
});

test('continue releases exhaustion deferred behind final-action attention exactly once', () => {
	const run = setup(`${prefix} await player.wait(1);`);
	run.engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true, trigger: 'conversation' });
	const oldRequest = run.requests[0];
	run.engine.ingestActionResult({ actionId: run.commands[0].actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	assert.equal(run.requests.length, 1);
	const request = run.engine.refreshDirectiveRequest();
	run.engine.applyDirective({ ...oldRequest, directive: 'continue' });
	assert.equal(run.requests.length, 1, 'the pre-result decision remains stale');
	run.engine.applyDirective({ ...request, directive: 'continue' });
	assert.equal(run.requests.length, 2);
	assert.equal(run.requests[1].trigger, 'program_exhausted');
	run.engine.applyDirective({ ...request, directive: 'continue' });
	assert.equal(run.requests.length, 2);
	assert.equal(run.commands.length, 1);
});

for (const awaitingFacts of [false, true]) test(`continue defers exhaustion through boundary handlers${awaitingFacts ? ' awaiting receipt facts' : ' in flight'}`, () => {
	const run = setup(`${prefix}
		program.watch(() => player.state().x > 0, {mode:"boundary"}, async () => { await player.wait(9); await player.wait(8); });
		program.watch(() => player.state().air <= 60, {mode:"boundary"}, async () => { await player.wait(7); });
		await player.wait(1);`);
	const succeed = (index, eventSequence) => run.engine.ingestActionResult({ actionId: run.commands[index].actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence });
	run.engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true, trigger: 'conversation' });
	succeed(0, 2);
	run.engine.ingestObservation({ observation: observation(60, 1), eventSequence: 3 });
	assert.deepEqual(run.commands.map(command => command.action.arguments), [1, 9]);
	if (awaitingFacts) succeed(1, 4);
	run.engine.applyDirective({ ...run.engine.refreshDirectiveRequest(), directive: 'continue' });
	assert.equal(run.requests.length, 1, 'active handlers and their receipt barriers prevent exhaustion');
	if (!awaitingFacts) succeed(1, 4);
	assert.equal(run.commands.length, 2, 'the next handler action still needs fresh receipt facts');
	run.engine.ingestObservation({ observation: observation(60, 1), eventSequence: 4 });
	assert.deepEqual(run.commands.map(command => command.action.arguments), [1, 9, 8]);
	succeed(2, 4);
	assert.deepEqual(run.commands.map(command => command.action.arguments), [1, 9, 8, 7]);
	assert.equal(run.requests.length, 1, 'the second latched handler must also drain');
	succeed(3, 4);
	assert.equal(run.requests.length, 2);
	assert.equal(run.requests[1].trigger, 'program_exhausted');
	assert.deepEqual(run.cancels, []);
});
