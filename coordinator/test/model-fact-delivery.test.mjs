import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService, presentNativeToolResult } from '../src/codex-service.mjs';
import { ModelObservationViews, decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { MAX_TOOL_RESULT_BYTES, normalizeMinecraftToolCall, toolResultContent } from '../src/native-minecraft-tools.mjs';
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

for (const kind of ['observe', 'action', 'sequence']) test(`changes without a baseline ID fall back to full after initialization and reset: ${kind}`, () => {
	const views = new ModelObservationViews();
	const raw = kind === 'observe' ? reply() : { state: 'SUCCEEDED', postAction: reply() };
	const sample = result => kind === 'observe' ? result : result.postAction;
	const action = { actionType: 'wait', arguments: { durationMs: 100 } };
	const tool = kind === 'observe' ? normalizeMinecraftToolCall('observe', { view: 'changes' })
		: kind === 'action' ? normalizeMinecraftToolCall('act', { ...action, view: 'changes' })
			: normalizeMinecraftToolCall('sequence', { actions: [action, action], view: 'changes' });
	const present = () => presentNativeToolResult(raw, tool, views);
	const first = present();
	assert.equal(sample(decode(first.response)).observationView.mode, 'full');
	first.commit();
	assert.equal(sample(decode(present().response)).observationView.mode, 'full');
	views.reset();
	const reset = present();
	const { observationView, ...facts } = sample(decode(reset.response));
	assert.equal(observationView.mode, 'full');
	assert.deepEqual(facts, reply());
});

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

async function observe(h, turn, args = {}, name = 'observe') {
	const id = `tool-${++h.transport.callSequence}`;
	h.transport.emit('serverRequest', { id, method: 'item/tool/call', params: { threadId: turn.threadId, turnId: turn.turnId, tool: name, arguments: args, callId: id } });
	await flush();
	const delivered = h.transport.responses.find(row => row.id === id);
	assert.ok(delivered, `tool ${id} must settle`);
	return decode(delivered.response);
}

async function complete(h, turn) {
	h.transport.emit('notification', { method: 'turn/completed', params: { threadId: turn.threadId, turn: { id: turn.turnId, status: 'completed' } } });
	await turn.promise;
}

test('CodexService delivers full observations for changes without an ID before and after compaction', async t => {
	const h = await harness(t);
	const turn = await startTurn(h);
	const first = await observe(h, turn, { view: 'changes' });
	assert.equal(first.observationView.mode, 'full');
	h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: turn.threadId } });
	const next = await observe(h, turn, { view: 'changes' });
	assert.equal(next.observationView.mode, 'full');
	assert.deepEqual(next.observation, h.result.observation);
	assert.ok(h.transport.responses.every(({ response }) => response.success === true));
	await complete(h, turn);
});

test('oversized incompressible presentation preserves bounded facts and coverage as its delta baseline', () => {
	const views = new ModelObservationViews();
	const value = reply();
	value.observation.blocks = Array.from({ length: 300 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone', details: 'outside the legacy result budget'.repeat(30) + x }));
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

test('default action receipts retain full facts and establish the delivered final sample', () => {
 const views = new ModelObservationViews();
 const first = presentNativeToolResult(reply(), { kind: 'observe' }, views); first.commit();
 const receipt = { state: 'FAILED', reasonCode: 'BLOCKED', actionId: 'fixture-action', postAction: reply() };
 const presented = presentNativeToolResult(receipt, { kind: 'action' }, views);
 const actual = decode(presented.response);
 const { observationView, ...postAction } = actual.postAction;
 assert.equal(observationView.mode, 'full');
 assert.deepEqual({ ...actual, postAction }, JSON.parse(toolResultContent(receipt).contentItems[0].text));
 presented.commit();
 assert.equal(decode(presentNativeToolResult(reply(), { kind: 'observe', view: 'changes', afterObservationId: observationView.id }, views).response).observationView.mode, 'changes');
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

for (const asynchronous of [false, true]) test(`failed ${asynchronous ? 'async' : 'sync'} delivery rejects the turn without a duplicate response or baseline`, async t => {
 const h = await harness(t);
 const turn = await startTurn(h);
 const known = await observe(h, turn);
 const originalRespond = h.transport.respond.bind(h.transport);
 h.transport.respond = (id, response) => {
  h.transport.attempts.push({ id, response });
  if (asynchronous) return Promise.reject(new Error('fixture async delivery failure'));
  throw new Error('fixture sync delivery failure');
 };
 const failedId = `tool-${++h.transport.callSequence}`;
 h.transport.emit('serverRequest', { id: failedId, method: 'item/tool/call', params: { threadId: turn.threadId, turnId: turn.turnId, tool: 'observe', arguments: { view: 'changes', afterObservationId: known.observationView.id }, callId: failedId } });
 await assert.rejects(turn.promise, error => error.code === 'TOOL_RESPONSE_DELIVERY_FAILED');
 const attempts = h.transport.attempts.filter(row => row.id === failedId);
 assert.equal(attempts.length, 1);
 assert.equal(h.transport.responses.filter(row => row.id === failedId).length, 0);
 assert.ok(h.transport.calls.some(row => row.method === 'turn/interrupt' && row.params.turnId === turn.turnId));
 const attempted = decode(attempts[0].response);
 h.transport.respond = originalRespond;
 const nextTurn = await startTurn(h);
 const missing = await observe(h, nextTurn, { view: 'changes', afterObservationId: attempted.observationView.id });
 assert.equal(missing.observationView.mode, 'full');
 const old = await observe(h, nextTurn, { view: 'changes', afterObservationId: known.observationView.id });
 assert.equal(old.observationView.mode, 'full');
 assert.equal((await observe(h, nextTurn, { view: 'changes', afterObservationId: old.observationView.id })).observationView.mode, 'changes');
 await complete(h, nextTurn);
});

test('compaction reset is scoped to the thread even when its notification has another turn ID', async t => {
	const h = await harness(t);
	const turn = await startTurn(h);
	const first = await observe(h, turn);
	h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: 'other-thread' } });
	const unchanged = await observe(h, turn, { view: 'changes', afterObservationId: first.observationView.id });
	assert.equal(unchanged.observationView.mode, 'changes');
	let baseline = unchanged;
	for (const method of ['thread/compacted', 'item/started', 'item/completed']) {
		h.transport.emit('notification', { method, params: { threadId: turn.threadId, turnId: 'different-turn', item: { type: 'contextCompaction' } } });
		const reset = await observe(h, turn, { view: 'changes', afterObservationId: baseline.observationView.id });
		assert.equal(reset.observationView.mode, 'full', `${method} invalidates the thread's prior views`);
		baseline = await observe(h, turn, { view: 'changes', afterObservationId: reset.observationView.id });
		assert.equal(baseline.observationView.mode, 'changes', 'newly delivered views remain usable');
	}
	await complete(h, turn);
	assert.equal((await turn.promise).nativeTurn.compaction, true);
});

test('pre-start old-turn compaction invalidates the thread view without accepting old-turn usage', async t => {
	const h = await harness(t);
	let previousTurn = await startTurn(h);
	let baseline = await observe(h, previousTurn);
	await complete(h, previousTurn);
	for (const method of ['thread/compacted', 'item/started', 'item/completed']) {
		// startTurn enters act synchronously, then waits for the ID continuation.
		const starting = startTurn(h);
		h.transport.emit('notification', { method, params: { threadId: previousTurn.threadId, turnId: previousTurn.turnId, item: { type: 'contextCompaction' } } });
		h.transport.emit('notification', { method: 'thread/tokenUsage/updated', params: { threadId: previousTurn.threadId, turnId: previousTurn.turnId, tokenUsage: { total: { inputTokens: 999 } } } });
		const turn = await starting;
		baseline = await observe(h, turn, { view: 'changes', afterObservationId: baseline.observationView.id });
		assert.equal(baseline.observationView.mode, 'full', `${method} keeps thread scope during pre-ID replay`);
		await complete(h, turn);
		const evidence = (await turn.promise).nativeTurn;
		assert.equal(evidence.compaction, true);
		assert.equal(evidence.usage.status, 'missing');
		assert.equal(evidence.tokens.input, null);
		previousTurn = turn;
	}
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

test('idle compaction invalidates only the matching thread between completed native turns', async t => {
 const h = await harness(t);
 let turn = await startTurn(h);
 let baseline = await observe(h, turn);
 await complete(h, turn);
 h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: 'other-thread' } });
 turn = await startTurn(h);
 baseline = await observe(h, turn, { view: 'changes', afterObservationId: baseline.observationView.id });
 assert.equal(baseline.observationView.mode, 'changes');
 await complete(h, turn);
 for (const method of ['thread/compacted', 'item/started', 'item/completed']) {
  assert.equal(h.agent.planning, false);
  h.transport.emit('notification', { method, params: { threadId: turn.threadId, turnId: turn.turnId, item: { type: 'contextCompaction' } } });
  turn = await startTurn(h);
  baseline = await observe(h, turn, { view: 'changes', afterObservationId: baseline.observationView.id });
  assert.equal(baseline.observationView.mode, 'full', method);
  await complete(h, turn);
 }
});

test('fallback preparation cannot invalidate the delivered baseline until successful delivery', () => {
 const views = new ModelObservationViews();
 const first = presentNativeToolResult(reply(), { kind: 'observe' }, views); first.commit();
 const id = decode(first.response).observationView.id;
 const near = { freshness: { fresh: true }, observation: { world: observation().world, player: { dead: false, note: '' } } };
 near.observation.player.note = 'x'.repeat(MAX_TOOL_RESULT_BYTES - Buffer.byteLength(JSON.stringify(near)) - 2);
 const fallback = presentNativeToolResult(near, { kind: 'observe' }, views);
 assert.equal(decode(fallback.response).observationView, undefined);
 const newer = presentNativeToolResult(reply(), { kind: 'observe', view: 'changes', afterObservationId: id }, views);
 assert.equal(decode(newer.response).observationView.mode, 'changes', 'preparation leaves the delivered baseline intact');
 newer.commit(); fallback.commit();
 assert.equal(decode(presentNativeToolResult(reply(), { kind: 'observe', view: 'changes', afterObservationId: decode(newer.response).observationView.id }, views).response).observationView.mode, 'changes', 'late fallback callback cannot invalidate a newer delivery');
});

for (const invalidate of ['none', 'compaction', 'interrupt']) test(`awaited delivery and ${invalidate} fence pending view commits through CodexService`, async t => {
 const h = await harness(t);
 const turn = await startTurn(h);
 const originalRespond = h.transport.respond.bind(h.transport);
 let release;
 let attempted;
 h.transport.respond = (id, response) => {
  attempted = decode(response);
  return new Promise(resolve => { release = () => { originalRespond(id, response); resolve(); }; });
 };
 const id = `tool-${++h.transport.callSequence}`;
 h.transport.emit('serverRequest', { id, method: 'item/tool/call', params: { threadId: turn.threadId, turnId: turn.turnId, tool: 'observe', arguments: {}, callId: id } });
 await flush();
 assert.equal(h.transport.responses.length, 0);
 assert.equal(typeof release, 'function');
 if (invalidate === 'compaction') h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: turn.threadId } });
 if (invalidate === 'interrupt') {
  await h.agent.interrupt();
  await assert.rejects(turn.promise, error => error.code === 'STALE_PLAN');
 }
 release();
 await flush();
 h.transport.respond = originalRespond;
 const current = invalidate === 'interrupt' ? await startTurn(h) : turn;
 const next = await observe(h, current, { view: 'changes', afterObservationId: attempted.observationView.id });
 assert.equal(next.observationView.mode, invalidate === 'none' ? 'changes' : 'full');
 await complete(h, current);
});

for (const name of ['act', 'sequence']) test(`CodexService ${name} uses real runtime freshness and reconstructs receipts without changing body commands`, async t => {
 const h = await harness(t);
 const record = { ...profile('fact-review'), provider: 'codex', goalRevision: 1, currentGoal: 'fixture task', currentGoalSpec: null };
 let samples = 0;
 const commands = [];
 let runtime;
 runtime = new NativeToolRuntime({ registry: { get: () => record }, bridge: { send: async (type, agentId, payload) => {
  assert.equal(type, 'action_command');
  commands.push(payload);
  assert.equal(Object.hasOwn(payload.arguments, 'view'), false);
  assert.equal(Object.hasOwn(payload.arguments, 'afterObservationId'), false);
  queueMicrotask(() => runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'ITEM_PICKED_UP', actionObservation: { player: { health: 20 } } }));
 } }, requestObservation: async (_record, { afterEventSequence }) => {
  samples++;
  return { eventSequence: afterEventSequence + 1, observation: observation() };
 } });
 t.after(() => runtime.dispose(record.agentId));
 runtime.updateObservation(record, observation(), { eventSequence: 7 });
 const originals = [];
 const turn = await startTurn(h, undefined, async request => {
  h.executed.push(request.tool);
  const result = await runtime.execute(request, record);
  originals.push(structuredClone(result));
  return result;
 });
 const base = await observe(h, turn);
 const action = { actionType: 'pick_up_item', arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' } };
 const args = name === 'act' ? action : { actions: [action, action] };
 const result = await observe(h, turn, { ...args, view: 'changes', afterObservationId: base.observationView.id }, name);
 const { observationView, ...facts } = result.postAction;
 assert.equal(observationView.mode, 'changes');
 const sections = new Map(Object.entries(base.observation));
 for (const key of observationView.remove) sections.delete(key);
 for (const [key, value] of Object.entries(observationView.replace)) sections.set(key, value);
 const retainedMetadata = Object.fromEntries((observationView.retainMetadata ?? []).map(key => [key, base[key]]));
 const reconstructed = { ...result, postAction: { ...retainedMetadata, ...facts, observation: Object.fromEntries(sections) } };
 assert.deepEqual(reconstructed, JSON.parse(toolResultContent(originals.at(-1)).contentItems[0].text));
 assert.equal(samples, 2, 'one initial sample and one final sample, without extra per-step sampling');
 assert.equal(commands.length, name === 'act' ? 1 : 2);
 assert.ok(commands.every(command => command.actionType === action.actionType && command.arguments.targetSelector === action.arguments.targetSelector));
 assert.equal(result.postAction.freshness.fresh, true);
 const next = await observe(h, turn, { view: 'changes', afterObservationId: observationView.id });
 assert.equal(next.observationView.mode, 'changes');
 await complete(h, turn);
 assert.ok(h.transport.calls.filter(row => row.method === 'turn/start').every(row => row.params.model === 'gpt-6.1-sol' && row.params.effort === 'medium' && row.params.serviceTier === 'fast'));
});
