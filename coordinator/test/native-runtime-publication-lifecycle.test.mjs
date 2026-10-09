import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

const record = { agentId: 'runtime-races', goalRevision: 1, provider: 'codex', model: 'gpt-6-astra', reasoningEffort: 'high',
	currentGoalSpec: { fingerprint: 'a'.repeat(64) } };
const observation = { ready: true, player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] },
	world: { worldId: 'test-world', dimension: 'minecraft:overworld' } };
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; }
async function until(predicate) {
	for (let i = 0; i < 100 && !predicate(); i++) await tick();
	assert.ok(predicate(), 'expected runtime transition');
}
function fixture(options = {}) {
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: (type, agentId, payload) => { sent.push({ type, agentId, payload }); return options.send?.(type, payload); } },
		requestObservation: async (_record, { afterEventSequence }) => ({ observation, eventSequence: afterEventSequence + 1 }),
		...options.runtime,
	});

	runtime.updateObservation(record, observation, { eventSequence: 1 });
	const call = tool => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision, turnId: 'turn', callId: 'call', tool }, record);
	const commands = () => sent.filter(frame => frame.type === 'action_command');
	const terminal = (actionId, state = 'CANCELLED') => runtime.onActionResult(record, { actionId, goalRevision: 1, state, reasonCode: state });
	return { runtime, call, commands, sent, terminal };
}
const start = { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 10 } };
const sequence = { kind: 'sequence', actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }], finish: { summary: 'Verify the goal.' } };

test('exact cancellation receipt releases its caller before send drain and late rejection cannot touch replacement', { timeout: 60000 }, async () => {
	const publication = deferred();
	const run = fixture({ send: type => type === 'action_cancel' ? publication.promise : undefined });
	const handle = await run.call(start);
	let settled = false;
	const cancellation = run.call({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 1 }).then(result => { settled = true; return result; });
	assert.equal(run.runtime.onActionResult(record, { actionId: handle.actionId, goalRevision: 2, state: 'CANCELLED' }), false);
	assert.equal(run.terminal('wrong-action'), false);
	await tick();
	assert.equal(settled, false);
	assert.equal((await run.call({ kind: 'action_status' })).state, 'CANCELLING');
	run.terminal(handle.actionId);
	await until(() => settled);
	assert.equal((await cancellation).state, 'CANCELLED');
	const replacement = await run.call(start);
	publication.reject(Object.assign(new Error('late drain failure'), { code: 'SEND_FAILED' }));
	await tick();
	assert.equal((await run.call({ kind: 'action_status', actionId: handle.actionId })).state, 'CANCELLED');
	assert.equal((await run.call({ kind: 'action_status' })).actionId, replacement.actionId);
	assert.equal((await run.call({ kind: 'action_status' })).state, 'RUNNING');
	run.terminal(replacement.actionId, 'SUCCEEDED');
	await run.runtime.disposeAll();
});

test('replaceAction admits only exact CANCELLED while cancellation publication remains pending', { timeout: 60000 }, async () => {
	for (const terminalState of ['CANCELLED', 'SUCCEEDED']) {
		const publication = deferred();
		const run = fixture({ send: type => type === 'action_cancel' ? publication.promise : undefined });
		const handle = await run.call(start);
		let outcome;
		const replacement = run.call({ ...start, kind: 'replace_action', actionId: handle.actionId, goalRevision: 1 }).then(result => { outcome = result; });
		await tick();
		assert.equal(run.commands().length, 1);
		run.terminal(handle.actionId, terminalState);
		if (terminalState === 'CANCELLED') {
			await until(() => run.commands().length === 2);
			run.terminal(run.commands()[1].payload.actionId, 'SUCCEEDED');
		} else await until(() => outcome !== undefined);
		await replacement;
		assert.equal(outcome.state, terminalState === 'CANCELLED' ? 'SUCCEEDED' : 'REPLACEMENT_NOT_STARTED');
		assert.equal(run.commands().length, terminalState === 'CANCELLED' ? 2 : 1);
		publication.resolve();
		await run.runtime.disposeAll();
	}
});

test('cancellation deadline covers undrained publication and retains physical ownership until terminal receipt', async t => {
	t.mock.timers.enable({ apis: ['setTimeout'] });
	const publication = deferred();
	const run = fixture({ send: type => type === 'action_cancel' ? publication.promise : undefined });
	const handle = await run.call(start);
	const cancellation = run.call({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 1 });
	const rejected = assert.rejects(cancellation, { code: 'CANCEL_ACK_TIMEOUT' });
	t.mock.timers.tick(10_000);
	await rejected;
	assert.equal((await run.call({ kind: 'action_status' })).state, 'CANCELLATION_UNCONFIRMED');
	await assert.rejects(run.call(start), { code: 'NATIVE_ACTION_IN_PROGRESS' });
	run.terminal(handle.actionId);
	publication.reject(new Error('late failure after timeout'));
	await tick();
	assert.equal((await run.call({ kind: 'action_status', actionId: handle.actionId })).state, 'CANCELLED');
	await run.runtime.disposeAll();
});

test('send completion is not cancellation acknowledgement, and send failures allow an exact retry', async () => {
	let failed = true;
	const run = fixture({ send: type => { if (type === 'action_cancel' && failed) throw Object.assign(new Error('offline'), { code: 'OFFLINE' }); } });
	const handle = await run.call(start);
	const cancel = { kind: 'cancel_action', actionId: handle.actionId, goalRevision: 1 };
	await assert.rejects(run.call(cancel), { code: 'OFFLINE' });
	assert.equal((await run.call({ kind: 'action_status' })).state, 'RUNNING');
	failed = false;
	let settled = false;
	const retry = run.call(cancel).then(result => { settled = true; return result; });
	await tick();
	assert.equal(settled, false);
	await assert.rejects(run.call(cancel), { code: 'CANCELLATION_IN_PROGRESS' });
	run.terminal(handle.actionId);
	await retry;
	await run.runtime.disposeAll();
});

test('disposal during undrained cancellation fences replacement and permits the next lifecycle', { timeout: 60000 }, async () => {
	const publication = deferred();
	const run = fixture({ send: type => type === 'action_cancel' ? publication.promise : undefined });
	const handle = await run.call(start);
	const pending = run.call({ ...start, kind: 'replace_action', actionId: handle.actionId, goalRevision: 1 });
	const rejected = assert.rejects(pending, { code: 'NATIVE_ACTION_CANCELLED' });
	await run.runtime.dispose(record.agentId, 'goal_stopped');
	await rejected;
	const next = await run.call(start);
	assert.equal(run.terminal(handle.actionId), false);
	publication.resolve();
	await tick();
	assert.equal(run.commands().length, 2);
	assert.equal((await run.call({ kind: 'action_status' })).actionId, next.actionId);
	run.terminal(next.actionId);
	await run.runtime.disposeAll();
});

test('disposed finishing sequence releases ownership during metadata and old finally preserves new reservation', { timeout: 60000 }, async () => {
	const reads = [];
	const run = fixture({ runtime: { taskContext: () => { const read = deferred(); reads.push(read); return read.promise; } } });
	const old = run.call(sequence);
	await until(() => run.commands().length === 1);
	run.terminal(run.commands()[0].payload.actionId, 'SUCCEEDED');
	await until(() => reads.length === 1);
	await run.runtime.disposeAll('goal_stopped');
	run.runtime.updateObservation(record, observation, { eventSequence: 10 });
	const next = run.call(sequence);
	await until(() => run.commands().length === 2);
	run.terminal(run.commands()[1].payload.actionId, 'SUCCEEDED');
	await until(() => reads.length === 2);
	reads[0].resolve({});
	assert.equal((await old).finish.state, 'SKIPPED');
	await assert.rejects(run.call(start), { code: 'NATIVE_ACTION_IN_PROGRESS' });
	assert.equal(run.sent.filter(frame => frame.type === 'goal_completed').length, 0);
	reads[1].resolve({});
	await until(() => run.sent.some(frame => frame.type === 'goal_completed'));
	const completion = run.sent.find(frame => frame.type === 'goal_completed').payload;
	run.runtime.onCompletionResult(record, { ...completion, verified: true, reasonCode: 'COMPLETION_VERIFIED' });
	assert.equal((await next).finish.verified, true);
	await run.runtime.disposeAll();
});

test('frontier waits for hydration and current facts but returns before optional checkpoint settles', { timeout: 60000 }, async () => {
	const hydration = deferred(), checkpoint = deferred(), traces = [], ingested = [];
	let hydrated = false, flushes = 0, candidates = 0;
	const occupancy = {
		load: async () => { await hydration.promise; hydrated = true; },
		ingest: (_agent, facts) => ingested.push(facts),
		flush: () => { flushes++; return checkpoint.promise; }, clear() {},
		candidates: (_agent, facts) => {
			candidates++;
			assert.ok(hydrated);
			assert.equal(ingested.length, 2, 'hydration replays the initial facts and observe ingests the fresh sample');
			assert.equal(facts.player.x, 0);
			return { candidates: [{ id: 'hydrated-cell' }], destination: null };
		},
	};
	const run = fixture({ runtime: { occupancy, decorateObservation: (_record, facts) => facts, trace: (event, fields) => traces.push({ event, fields }) } });
	let result;
	const pending = run.call({ kind: 'explore_frontier', arguments: {} }).then(value => { result = value; });
	await tick();
	assert.equal(candidates, 0);
	hydration.resolve();
	await until(() => result !== undefined);
	assert.deepEqual(result.candidates, [{ id: 'hydrated-cell' }]);
	assert.equal(result.freshness.fresh, true);
	assert.equal(flushes, 1);
	checkpoint.reject(Object.assign(new Error('disk busy'), { code: 'CHECKPOINT_FAILED' }));
	await tick();
	assert.ok(traces.some(entry => entry.event === 'native_spatial_memory_failed' && entry.fields.reasonCode === 'CHECKPOINT_FAILED'));
	assert.equal(run.commands().length, 0);
	await pending;
	await run.runtime.disposeAll();
});
