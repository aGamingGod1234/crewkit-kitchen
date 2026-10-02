import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService, presentNativeToolResult } from '../src/codex-service.mjs';
import { ModelObservationViews, decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { MAX_TOOL_RESULT_BYTES, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

const profile = agentId => ({ agentId, model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' });
const observation = () => ({
	world: { worldId: 'fixture-world', dimension: 'minecraft:overworld' }, player: { dead: false, health: 20 },
	blocks: Array.from({ length: 32 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone', state: {}, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }] })),
	coverage: { blocks: { complete: false, reason: 'outside_observed_area' } },
});
const reply = () => ({ eventSequence: 7, freshness: { fresh: true }, observation: observation() });
const decode = response => decodeModelFacts(JSON.parse(response.contentItems[0].text));
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await new Promise(resolve => setImmediate(resolve)); };

class FixtureTransport extends EventEmitter {
	calls = []; responses = []; attempts = []; threadSequence = 0; turnSequence = 0; callSequence = 0; failNext = false;
	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }
	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize') return { userAgent: 'fixture' };
		if (method === 'model/list') return { data: [{ id: 'gpt-6.1-sol', model: 'gpt-6.1-sol', supportedReasoningEfforts: [{ reasoningEffort: 'medium' }], serviceTiers: [{ id: 'fast' }] }], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: `thread-${++this.threadSequence}` } };
		if (method === 'turn/start') return { turn: { id: `turn-${++this.turnSequence}` } };
		if (method === 'turn/steer') return { turnId: params.expectedTurnId };
		if (method === 'turn/interrupt') return {};
		throw new Error(`Unexpected fixture request ${method}`);
	}
	respond(id, response) {
		this.attempts.push({ id, response });
		if (this.failNext) { this.failNext = false; throw new Error('fixture delivery failure'); }
		this.responses.push({ id, response });
	}
}

async function harness(t) {
	const transport = new FixtureTransport();
	const service = new CodexService({ cwd: process.cwd(), planningTimeoutMs: 2_000 }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('fact-review'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	return { transport, service, agent, result: reply(), executed: [] };
}

async function startTurn(h, input = `event heading\n${JSON.stringify({ event: 'task_continue', observation: h.result.observation })}\nretry footer`, executeTool = request => {
	h.executed.push(request.tool);
	return structuredClone(h.result);
}) {
	const promise = h.agent.act(input, { goalRevision: h.agent.goalRevision, executeTool });
	void promise.catch(() => {});
	await flush();
	const call = h.transport.calls.filter(row => row.method === 'turn/start').at(-1);
	return { promise, threadId: call.params.threadId, turnId: `turn-${h.transport.turnSequence}` };
}

async function observe(h, turn, args = {}) {
	const id = `tool-${++h.transport.callSequence}`;
	h.transport.emit('serverRequest', { id, method: 'item/tool/call', params: { threadId: turn.threadId, turnId: turn.turnId, tool: 'observe', arguments: args, callId: id } });
	await flush();
	const delivered = h.transport.responses.find(row => row.id === id);
	assert.ok(delivered, `tool ${id} must settle`);
	return decode(delivered.response);
}

async function complete(h, turn) {
	h.transport.emit('notification', { method: 'turn/completed', params: { threadId: turn.threadId, turn: { id: turn.turnId, status: 'completed' } } });
	await turn.promise;
}

test('provider presentation preserves precisely the old bounded facts and coverage as its delta baseline', () => {
	const views = new ModelObservationViews();
	const value = reply();
	value.observation.blocks = Array.from({ length: 300 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone', details: 'outside the legacy result budget'.repeat(30) }));
	const old = JSON.parse(toolResultContent(value).contentItems[0].text);
	assert.equal(old.truncated, true);
	const presented = presentNativeToolResult(value, { kind: 'observe' }, views);
	assert.ok(Buffer.byteLength(presented.response.contentItems[0].text) <= MAX_TOOL_RESULT_BYTES);
	const { observationView, ...facts } = decode(presented.response);
	assert.deepEqual(facts, old);
	presented.commit();
	const next = decode(presentNativeToolResult(value, { kind: 'observe', view: 'changes', afterObservationId: observationView.id }, views).response);
	assert.equal(next.observationView.mode, 'changes');
	assert.deepEqual(next.observationView.replace, {});
	assert.deepEqual(next.observationView.remove, []);
});

test('action receipts remain self-contained and do not advance an observe baseline', () => {
	const views = new ModelObservationViews();
	const first = presentNativeToolResult(reply(), { kind: 'observe' }, views);
	first.commit();
	const receipt = { state: 'FAILED', reasonCode: 'BLOCKED', actionId: 'fixture-action', postAction: reply() };
	const presented = presentNativeToolResult(receipt, { kind: 'action' }, views);
	presented.commit();
	assert.deepEqual(decode(presented.response), JSON.parse(toolResultContent(receipt).contentItems[0].text));
	assert.equal(decode(presentNativeToolResult(reply(), { kind: 'observe', view: 'changes', afterObservationId: decode(first.response).observationView.id }, views).response).observationView.mode, 'changes');
});

test('adding view metadata cannot exceed the legacy result limit or leave a phantom baseline', () => {
	const views = new ModelObservationViews();
	const first = presentNativeToolResult(reply(), { kind: 'observe' }, views);
	first.commit();
	const near = { freshness: { fresh: true }, observation: { world: observation().world, player: { dead: false, health: 20, note: '' } } };
	near.observation.player.note = 'x'.repeat(MAX_TOOL_RESULT_BYTES - Buffer.byteLength(JSON.stringify(near)) - 2);
	const presented = presentNativeToolResult(near, { kind: 'observe' }, views);
	assert.equal(presented.response.contentItems[0].text, toolResultContent(near).contentItems[0].text);
	assert.ok(Buffer.byteLength(presented.response.contentItems[0].text) <= MAX_TOOL_RESULT_BYTES);
	assert.equal(decode(presented.response).observationView, undefined);
	presented.commit();
	assert.equal(decode(presentNativeToolResult(reply(), { kind: 'observe', view: 'changes', afterObservationId: decode(first.response).observationView.id }, views).response).observationView.mode, 'full');
});

test('a failed transport response cannot establish the failed candidate observation ID', async t => {
	const h = await harness(t);
	const turn = await startTurn(h);
	h.transport.failNext = true;
	assert.equal((await observe(h, turn)).state, 'FAILED');
	const attempted = decode(h.transport.attempts[0].response);
	const next = await observe(h, turn, { view: 'changes', afterObservationId: attempted.observationView.id });
	assert.equal(next.observationView.mode, 'full');
	const known = await observe(h, turn, { view: 'changes', afterObservationId: next.observationView.id });
	assert.equal(known.observationView.mode, 'changes');
	h.result.observation.player.health = 18;
	h.transport.failNext = true;
	assert.equal((await observe(h, turn, { view: 'changes', afterObservationId: known.observationView.id })).state, 'FAILED');
	const recovered = await observe(h, turn, { view: 'changes', afterObservationId: known.observationView.id });
	assert.equal(recovered.observationView.mode, 'changes', 'failure must leave the previously delivered baseline intact');
	assert.equal(recovered.observationView.replace.player.health, 18);
	await complete(h, turn);
	assert.deepEqual(h.executed.map(tool => tool.kind), Array(5).fill('observe'));
});

test('compaction reset is scoped to the thread even when its notification has another turn ID', async t => {
	const h = await harness(t);
	const turn = await startTurn(h);
	const first = await observe(h, turn);
	h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: 'other-thread' } });
	const unchanged = await observe(h, turn, { view: 'changes', afterObservationId: first.observationView.id });
	assert.equal(unchanged.observationView.mode, 'changes');
	h.transport.emit('notification', { method: 'item/started', params: { threadId: turn.threadId, turnId: 'different-turn', item: { type: 'contextCompaction' } } });
	const reset = await observe(h, turn, { view: 'changes', afterObservationId: unchanged.observationView.id });
	assert.equal(reset.observationView.mode, 'full');
	await complete(h, turn);
});

test('native death input resets the view during both a new turn and active-turn steering', async t => {
	const h = await harness(t);
	const firstTurn = await startTurn(h);
	const initial = await observe(h, firstTurn);
	await complete(h, firstTurn);
	const death = `heading\n${JSON.stringify({ event: 'player_death', observation: { ...observation(), player: { dead: true, health: 0 } } })}\nfooter`;
	const turn = await startTurn(h, death);
	const afterDeath = await observe(h, turn, { view: 'changes', afterObservationId: initial.observationView.id });
	assert.equal(afterDeath.observationView.mode, 'full');
	await h.agent.steer(death, { goalRevision: 1 });
	assert.equal((await observe(h, turn, { view: 'changes', afterObservationId: afterDeath.observationView.id })).observationView.mode, 'full');
	await complete(h, turn);
	assert.equal(h.transport.calls.filter(row => row.method === 'turn/start').length, 2);
	assert.equal(h.transport.calls.filter(row => row.method === 'turn/steer').length, 1);
});

test('goal changes and interruption reset views while preserving the selected model and sole tool executor', async t => {
	const h = await harness(t);
	const firstTurn = await startTurn(h);
	const initial = await observe(h, firstTurn);
	await complete(h, firstTurn);
	await h.agent.setGoalRevision(2);
	const turn = await startTurn(h);
	const changedGoal = await observe(h, turn, { view: 'changes', afterObservationId: initial.observationView.id });
	assert.equal(changedGoal.observationView.mode, 'full');
	await h.agent.interrupt();
	await assert.rejects(turn.promise, error => error.code === 'STALE_PLAN');
	const nextTurn = await startTurn(h);
	assert.equal((await observe(h, nextTurn, { view: 'changes', afterObservationId: changedGoal.observationView.id })).observationView.mode, 'full');
	await complete(h, nextTurn);
	assert.ok(h.transport.calls.filter(row => row.method === 'turn/start').every(row => row.params.model === 'gpt-6.1-sol' && row.params.effort === 'medium' && row.params.serviceTier === 'fast'));
	assert.ok(h.executed.every(tool => tool.kind === 'observe'));
});

test('provider replacement starts a new thread without resurrecting the previous view', async t => {
	const h = await harness(t);
	const firstTurn = await startTurn(h);
	const previous = await observe(h, firstTurn);
	await complete(h, firstTurn);
	h.transport.emit('exit', Object.assign(new Error('fixture reconnect'), { code: 'PROCESS_EXITED' }));
	h.agent = await h.service.replaceAgent(profile('fact-review'), { controlProtocol: 'native_tools' });
	await h.agent.setGoalRevision(1);
	const nextTurn = await startTurn(h);
	assert.notEqual(nextTurn.threadId, firstTurn.threadId);
	assert.equal((await observe(h, nextTurn, { view: 'changes', afterObservationId: previous.observationView.id })).observationView.mode, 'full');
	await complete(h, nextTurn);
});

test('real native observe freshness barriers run once per requested tool and send no body actions', async t => {
	const h = await harness(t);
	const record = { ...profile('fact-review'), provider: 'codex', goalRevision: 1, currentGoal: 'fixture task', currentGoalSpec: null };
	let samples = 0;
	const bodyCommands = [];
	const runtime = new NativeToolRuntime({ registry: { get: () => record }, bridge: { send: async (...args) => bodyCommands.push(args) }, requestObservation: async (_record, { afterEventSequence }) => {
		samples++;
		return { observation: observation(), eventSequence: afterEventSequence + 1 };
	} });
	t.after(() => runtime.dispose(record.agentId));
	runtime.updateObservation(record, observation(), { eventSequence: 7 });
	const turn = await startTurn(h, undefined, request => runtime.execute(request, record));
	const first = await observe(h, turn);
	const second = await observe(h, turn, { view: 'changes', afterObservationId: first.observationView.id });
	assert.equal(first.freshness.fresh, true);
	assert.equal(second.freshness.fresh, true);
	assert.ok(second.freshness.eventSequence > first.freshness.eventSequence);
	assert.equal(second.observationView.mode, 'changes');
	assert.equal(samples, 2);
	assert.deepEqual(bodyCommands, []);
	await complete(h, turn);
	assert.equal(h.transport.calls.filter(row => row.method === 'turn/start').length, 1);
});
