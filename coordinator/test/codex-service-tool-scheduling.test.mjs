import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService } from '../src/codex-service.mjs';
import { decodeModelFacts } from '../src/model-fact-encoding.mjs';

const flush = () => new Promise(resolve => setImmediate(resolve));
const gate = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const sample = () => ({ freshness: { fresh: true }, observation: { world: { worldId: 'fixture', dimension: 'minecraft:overworld' }, player: { dead: false, health: 20 }, blocks: Array.from({ length: 20 }, (_, x) => ({ x, blockId: 'minecraft:stone' })) } });
const decode = response => decodeModelFacts(JSON.parse(response.contentItems[0].text));
class Transport extends EventEmitter {
 responses = []; sequence = 0;
 async start() {} async stop() {} notify() {}
 async request(method) {
  if (method === 'initialize') return {};
  if (method === 'model/list') return { data: [{ id: 'fixture', model: 'fixture', supportedReasoningEfforts: ['high'], serviceTiers: ['fast'] }] };
  if (method === 'thread/start') return { thread: { id: 'thread' } };
  if (method === 'turn/start') return { turn: { id: `turn-${++this.sequence}` } };
  if (method === 'turn/interrupt') return {};
  throw new Error(method);
 }
 async respond(id, response) { this.responses.push({ id, response }); }
 call(id, tool, args = {}, turnId = `turn-${this.sequence}`) { this.emit('serverRequest', { id, method: 'item/tool/call', params: { threadId: 'thread', turnId, callId: id, tool, arguments: args } }); }
 complete(status = 'completed') { this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread', turn: { id: `turn-${this.sequence}`, status } } }); }
}
async function harness(t) {
 const transport = new Transport(); const timers = []; const progress = [];
 const service = new CodexService({ cwd: process.cwd(), schedule(callback) { const timer = { callback, cancelled: false }; timers.push(timer); return timer; }, cancelSchedule(timer) { timer.cancelled = true; } }, { transport });
 t.after(() => service.stop());
 const agent = await service.createAgent({ agentId: 'scheduling', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' }, { controlProtocol: 'native_tools' });
 await agent.setGoalRevision(1);
 const start = async executeTool => {
  const promise = agent.act('continue', { goalRevision: 1, executeTool, onProgress: event => progress.push(event) });
  void promise.catch(() => {}); await flush(); return { promise };
 };
 return { transport, timers, progress, agent, start };
}

test('pending observe permits sibling reads and exact cancellation; silence remains paused for remaining work', async t => {
 const h = await harness(t); const pending = gate(); t.after(() => pending.resolve()); const requests = [];
 const { promise } = await h.start(async request => { requests.push(request); if (request.callId === 'slow') await pending.promise; return sample(); });
 h.transport.call('slow', 'observe'); await flush();
 h.transport.call('sibling', 'inspect', { section: 'inventory' });
 h.transport.call('cancel', 'cancelAction', { actionId: 'exact-active', goalRevision: 1 });
 await flush();
 assert.deepEqual(requests.map(row => row.callId), ['slow', 'sibling', 'cancel']);
 assert.deepEqual(requests.at(-1).tool, { kind: 'cancel_action', actionId: 'exact-active', goalRevision: 1 });
 assert.ok(h.transport.responses.some(row => row.id === 'cancel'));
 assert.ok(!h.transport.responses.some(row => row.id === 'slow'));
 assert.equal(h.timers.filter(timer => !timer.cancelled).length, 0);
 for (const request of requests) {
  assert.ok(request.queueWaitMs >= 0);
  assert.equal(request.executionStartedAt - request.requestArrivedAt, request.queueWaitMs);
  const events = h.progress.filter(row => row.callId === request.callId);
  assert.ok(events.some(row => row.phase === 'tool_queued'));
  assert.ok(events.some(row => row.phase === 'tool_started' && row.queueWaitMs === request.queueWaitMs));
 }
 h.transport.complete(); let settled = false; void promise.then(() => { settled = true; }); await flush(); assert.equal(settled, false);
 pending.resolve(); assert.equal((await promise).toolCalls, 3);
});

test('body, writes, dependent reads and finish retain arrival ordering through delivery', async t => {
 const h = await harness(t); const body = gate(); const delivery = gate(); const started = [];
 t.after(() => { body.resolve(); delivery.resolve(); });
 const respond = h.transport.respond.bind(h.transport);
 h.transport.respond = async (id, response) => { if (id === 'body') await delivery.promise; return respond(id, response); };
 const { promise } = await h.start(async request => { started.push(request.callId); if (request.callId === 'body') await body.promise; return { state: 'SUCCEEDED' }; });
 h.transport.call('body', 'wait', { durationMs: 1 });
 h.transport.call('read', 'observe');
 h.transport.call('write', 'notebook', { key: 'note', text: 'exact authored note' });
 h.transport.call('query', 'queryMemory');
 h.transport.call('second-body', 'wait', { durationMs: 1 });
 h.transport.call('finish', 'finish', { summary: 'done' });
 await flush(); assert.deepEqual(started, ['body']);
 body.resolve(); await flush(); assert.deepEqual(started, ['body'], 'ordering includes response delivery');
 delivery.resolve(); await flush();
 assert.deepEqual(started, ['body', 'read', 'write', 'query', 'second-body', 'finish']);
 const times = h.progress.filter(row => row.phase === 'tool_started');
 assert.ok(times.find(row => row.callId === 'read').executionStartedAt >= times.find(row => row.callId === 'body').executionStartedAt);
 h.transport.complete(); assert.equal((await promise).toolCalls, 6);
});

test('exact program controls bypass pending tools, while invalid cancellation and replacement stay ordered', async t => {
 const h = await harness(t); const pending = gate(); const started = [];
 t.after(() => pending.resolve());
 const { promise } = await h.start(async request => { started.push(request.callId); if (request.callId === 'slow') await pending.promise; return { state: 'SUCCEEDED' }; });
 h.transport.call('slow', 'observe'); await flush();
 h.transport.call('invalid', 'cancelAction', { goalRevision: 1 });
 h.transport.call('cancel-program', 'cancelProgram', { programId: 'program', goalRevision: 1 });
 h.transport.call('cancel-queue', 'cancelQueuedProgram', { afterProgramId: 'program', queueId: 'queue', goalRevision: 1 });
 h.transport.call('respond', 'respondProgram', { programId: 'program', goalRevision: 1, decisionId: 'decision', directive: 'continue' });
 h.transport.call('replace', 'replaceAction', { actionId: 'exact', goalRevision: 1, actionType: 'wait', arguments: { durationMs: 1 } });
 await flush();
 assert.deepEqual(started, ['slow', 'cancel-program', 'cancel-queue', 'respond']);
 assert.ok(!h.transport.responses.some(row => row.id === 'invalid'));
 pending.resolve(); await flush();
 assert.equal(h.transport.responses.find(row => row.id === 'invalid').response.success, false);
 assert.equal(started.at(-1), 'replace');
 h.transport.complete(); await promise;
});

test('failed turn retires queued body calls and interruption drops overlapping late results', async t => {
 for (const interrupt of [false, true]) {
  const h = await harness(t); const pending = gate(); const started = [];
  t.after(() => pending.resolve());
  const { promise } = await h.start(async request => { started.push(request.callId); await pending.promise; return sample(); });
  h.transport.call('first', 'observe'); h.transport.call('second', 'observe'); h.transport.call('body', 'wait', { durationMs: 1 }); await flush();
  assert.deepEqual(started, ['first', 'second']);
  if (interrupt) await h.agent.interrupt(); else h.transport.complete('failed');
  pending.resolve(); await assert.rejects(promise); await flush();
  assert.deepEqual(started, ['first', 'second']);
  if (interrupt) assert.deepEqual(h.transport.responses, []);
 }
});

test('overlapping response delivery commits only delivered sibling views and reset fences held commits', async t => {
 for (const reset of [false, true]) {
  const h = await harness(t); const held = gate(); t.after(() => held.resolve());
  const originalRespond = h.transport.respond.bind(h.transport); let pendingView;
  h.transport.respond = async (id, response) => {
   if (id === 'held') { pendingView = decode(response).observationView.id; await held.promise; }
   return originalRespond(id, response);
  };
  const { promise } = await h.start(async () => sample());
  h.transport.call('held', 'observe'); h.transport.call('delivered', 'observe'); await flush();
  const deliveredView = decode(h.transport.responses.find(row => row.id === 'delivered').response).observationView.id;
  h.transport.call('undelivered-baseline', 'observe', { view: 'changes', afterObservationId: pendingView }); await flush();
  assert.equal(decode(h.transport.responses.find(row => row.id === 'undelivered-baseline').response).observationView.mode, 'full');
  if (reset) h.transport.emit('notification', { method: 'thread/compacted', params: { threadId: 'thread' } });
  held.resolve(); await flush();
  for (const [id, baseline] of [['old-sibling', pendingView], ['new-sibling', deliveredView]]) {
   h.transport.call(id, 'observe', { view: 'changes', afterObservationId: baseline }); await flush();
   assert.equal(decode(h.transport.responses.find(row => row.id === id).response).observationView.mode, reset ? 'full' : 'changes');
  }
  h.transport.complete(); await promise;
 }
});

test('one failed overlapping response invalidates views and prevents late sibling commits', async t => {
 const h = await harness(t); const held = gate(); t.after(() => held.resolve());
 const originalRespond = h.transport.respond.bind(h.transport); let attemptedId;
 h.transport.respond = async (id, response) => {
  if (id === 'held') { attemptedId = decode(response).observationView.id; await held.promise; }
  if (id === 'failed') throw new Error('delivery failed');
  return originalRespond(id, response);
 };
 const { promise } = await h.start(async () => sample());
 h.transport.call('held', 'observe'); h.transport.call('failed', 'observe');
 await assert.rejects(promise, error => error.code === 'TOOL_RESPONSE_DELIVERY_FAILED');
 held.resolve(); await flush(); h.transport.respond = originalRespond;
 const next = await h.start(async () => sample());
 h.transport.call('after-failure', 'observe', { view: 'changes', afterObservationId: attemptedId }); await flush();
 assert.equal(decode(h.transport.responses.find(row => row.id === 'after-failure').response).observationView.mode, 'full');
 h.transport.complete(); await next.promise;
});
