import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService } from '../src/codex-service.mjs';
import { AgentPlanner } from '../src/agent-planner.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProviderTurnRecorder } from '../src/provider-turn-recorder.mjs';
import { decodeModelFacts } from '../src/model-fact-encoding.mjs';

const profile = { agentId: 'measurement-agent', provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast', goalRevision: 1 };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const decode = response => decodeModelFacts(JSON.parse(response.contentItems[0].text));
const sample = () => ({ freshness: { fresh: true }, observation: {
	world: { worldId: 'measurement-world', dimension: 'minecraft:overworld' },
	player: { dead: false, health: 20, note: 'private-payload 雪🌳' },
} });

class Transport extends EventEmitter {
	calls = []; responses = []; turns = 0; sequence = 0;
	deliver = () => undefined;
	async start() {}
	async stop() {}
	notify() {}
	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize' || method === 'turn/interrupt') return {};
		if (method === 'model/list') return { data: [{ ...profile, id: profile.model, supportedReasoningEfforts: [{ reasoningEffort: profile.reasoningEffort }], serviceTiers: [{ id: profile.serviceTier }] }], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: 'measurement-thread' } };
		if (method === 'turn/start') return { turn: { id: `turn-${++this.turns}` } };
		throw new Error(`Unexpected fixture method ${method}`);
	}
	respond(id, response) {
		this.responses.push({ id, response });
		return this.deliver(id, response);
	}
	tool(name = 'observe', args = {}) {
		const id = ++this.sequence;
		this.emit('serverRequest', { method: 'item/tool/call', id, params: {
			threadId: 'measurement-thread', turnId: `turn-${this.turns}`, callId: `private-call-${id}`, tool: name, arguments: args,
		} });
		return id;
	}
	complete(status = 'completed') {
		this.emit('notification', { method: 'turn/completed', params: { threadId: 'measurement-thread',
			turn: { id: `turn-${this.turns}`, status, error: { message: 'private-turn-error' } } } });
	}
	usage(inputTokens) {
		this.emit('notification', { method: 'thread/tokenUsage/updated', params: { threadId: 'measurement-thread', tokenUsage: { total: { inputTokens } } } });
	}
}

async function harness(t, { planner = false } = {}) {
	const transport = new Transport();
	const service = new CodexService({ cwd: process.cwd() }, { transport });
	const agent = await service.createAgent(profile, { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const publicRows = [], privateRows = [], telemetry = [];
	// Exercise the real queue and both persistence normalizers, with an in-memory
	// append sink so this offline test never creates a private artifact on disk.
	const recorder = new ProviderTurnRecorder({ runId: 'measurement', scenarioId: 'offline', privatePath: 'unused-measurement.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: row => publicRows.push(row) });
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1 });
	const controller = new AgentPlanner({ registry: { assertCurrentRevision: () => profile, setState() {} }, scheduler,
		codexService: service, turnRecorder: recorder, telemetrySink: row => telemetry.push(row) });
	t.after(async () => { scheduler.close(); await service.stop(); await recorder.close(); });
	return { transport, agent, recorder, publicRows, privateRows, telemetry,
		async start(executeTool = () => sample(), signal) {
			const promise = planner
				? controller.requestNativeTurn({ agentId: profile.agentId, goalRevision: 1, input: 'private-input', executeTool })
				: agent.act('private-input', { goalRevision: 1, executeTool, signal });
			void promise.catch(() => {});
			await tick();
			return { promise };
		},
	};
}

function assertPrivate(summary) {
	assert.equal(summary.receiptAcknowledged, false);
	assert.doesNotMatch(JSON.stringify(summary), /private-|雪|🌳|contentItems|arguments|reasonCode|message|"text"/);
	for (const row of summary.rows) {
		assert.equal(row.attempts, row.accepted + row.failed + row.pending);
		assert.equal(row.attemptedBytes, row.acceptedBytes + row.failedBytes + row.pendingBytes);
	}
}

test('native measurement reconciles exact presented UTF8 bytes, families and per-turn isolation', async t => {
	const h = await harness(t);
	let result = sample();
	const run = await h.start(() => result);
	const cases = [
		['observe', {}, sample(), 'observation'],
		['wait', { durationMs: 100 }, { state: 'SUCCEEDED', note: 'private-payload 雪🌳' }, 'action'],
		['wait', { durationMs: 100 }, { state: 'FAILED', postAction: sample() }, 'action_post_observation'],
		['sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 100 } }, { actionType: 'wait', arguments: { durationMs: 100 } }] }, { state: 'SUCCEEDED', postAction: sample() }, 'sequence'],
		['programStatus', {}, { state: 'READY' }, 'program'],
		['lookAround', { centerYaw: 0, pitch: 0, steps: 2, ticksPerStep: 1 }, { state: 'SUCCEEDED' }, 'camera'],
		['inspect', { section: 'item', slot: 0 }, { note: 'private-payload' }, 'inspection'],
		['notebook', { key: 'private-key', text: 'private-argument' }, { state: 'READY' }, 'memory'],
		['capabilities', {}, { state: 'READY' }, 'other'],
	];
	const expected = new Map();
	for (const [name, args, value, family] of cases) {
		result = value;
		h.transport.tool(name, args); await tick();
		const response = h.transport.responses.at(-1).response;
		assert.equal(response.success, true, name);
		expected.set(`${name}:${family}`, Buffer.byteLength(JSON.stringify(response), 'utf8'));
	}
	assert.ok(Buffer.byteLength(JSON.stringify(h.transport.responses[0].response)) > JSON.stringify(h.transport.responses[0].response).length);
	h.transport.complete();
	const evidence = (await run.promise).nativeTurn;
	assert.ok(evidence.toolResponses, 'collector must expose response-boundary measurements');
	assert.equal(evidence.toolResponses.rows.length, expected.size);
	for (const row of evidence.toolResponses.rows) {
		assert.equal(row.acceptedBytes, expected.get(`${row.tool}:${row.family}`));
		assert.equal(row.attempts, 1); assert.equal(row.accepted, 1);
		assert.equal(row.executionSamples, 1); assert.equal(row.respondSamples, 1);
	}
	assert.equal(evidence.toolResultBytes, [...expected.values()].reduce((a, b) => a + b, 0));
	assert.equal(evidence.usage.status, 'missing');
	assert.ok(Object.values(evidence.tokens).every(value => value === null));
	assertPrivate(evidence.toolResponses);
	const next = await h.start(); h.transport.complete();
	assert.deepEqual((await next.promise).nativeTurn.toolResponses.rows, []);
});

for (const failure of ['sync', 'async']) test(`native measurement separates accepted execution errors from ${failure} delivery failure`, async t => {
	const h = await harness(t);
	const run = await h.start(() => { throw new Error('private-execution-error'); });
	h.transport.tool(); await tick();
	assert.equal(h.transport.responses[0].response.success, false);
	h.transport.deliver = () => {
		if (failure === 'sync') throw new Error('private-delivery-error');
		return Promise.reject(new Error('private-delivery-error'));
	};
	h.transport.tool();
	let evidence;
	await assert.rejects(run.promise, error => { evidence = error.nativeTurn; return error.code === 'TOOL_RESPONSE_DELIVERY_FAILED'; });
	assert.equal(h.transport.responses.length, 2, 'never respond twice to a failed response ID');
	assert.equal(new Set(h.transport.responses.map(row => row.id)).size, 2);
	assert.ok(h.transport.calls.some(row => row.method === 'turn/interrupt'));
	const row = evidence.toolResponses.rows[0];
	assert.equal(row.family, 'error'); assert.equal(row.attempts, 2);
	assert.equal(row.accepted, 1); assert.equal(row.failed, 1); assert.equal(row.pending, 0);
	assert.equal(row.executionSamples, 2); assert.equal(row.respondSamples, 2);
	assert.equal(evidence.toolResultBytes, Buffer.byteLength(JSON.stringify(h.transport.responses[0].response)));
	assert.equal(row.acceptedBytes, evidence.toolResultBytes);
	assert.equal(row.failedBytes, Buffer.byteLength(JSON.stringify(h.transport.responses[1].response)));
	assertPrivate(evidence.toolResponses);
});

test('native measurement omits executor samples when argument normalization rejects', async t => {
	const h = await harness(t);
	const run = await h.start(() => assert.fail('invalid arguments must not execute'));
	h.transport.tool('observe', { invalid: 'private-argument' }); await tick();
	h.transport.tool('private-unknown-tool'); await tick(); h.transport.complete();
	const summary = (await run.promise).nativeTurn.toolResponses;
	assert.deepEqual(summary.rows.map(row => row.tool), ['observe', 'unknown']);
	for (const row of summary.rows) {
		assert.equal(row.family, 'error'); assert.equal(row.accepted, 1);
		assert.equal(row.executionSamples, 0); assert.equal(row.respondSamples, 1);
	}
	assertPrivate(summary);
});

for (const outcome of ['success', 'rejection']) test(`native measurement times actual async executor ${outcome} independently of presentation and respond`, async t => {
	const h = await harness(t);
	const execution = deferred(), delivery = deferred(), entered = deferred();
	let now = 100;
	t.mock.method(performance, 'now', () => now);
	h.transport.deliver = () => { entered.resolve(); return delivery.promise; };
	const run = await h.start(() => execution.promise);
	h.transport.tool(); await tick();
	// Presentation / metadata advances the clock only after executor settlement.
	now = 137;
	if (outcome === 'success') {
		const value = sample();
		Object.defineProperty(value, 'postAction', { get() { now = 200; return null; } });
		execution.resolve(value);
	} else {
		const error = new Error('private-async-execution-error');
		Object.defineProperty(error, 'message', { get() { now = 200; return 'private-async-execution-error'; } });
		execution.reject(error);
	}
	await entered.promise;
	now = 225; delivery.resolve(); await tick(); h.transport.complete();
	const row = (await run.promise).nativeTurn.toolResponses.rows[0];
	assert.equal(row.family, outcome === 'success' ? 'observation' : 'error');
	assert.equal(row.executionSamples, 1); assert.equal(row.respondSamples, 1);
	assert.equal(row.executionMs, 37); assert.equal(row.respondMs, 25);
});

for (const ending of ['cancel', 'failed']) test(`native measurement preserves pending at ${ending} snapshot despite late fulfillment`, async t => {
	const h = await harness(t, { planner: ending === 'failed' });
	const delivery = deferred(), entered = deferred(), abort = new AbortController();
	h.transport.deliver = () => { entered.resolve(); return delivery.promise; };
	const run = await h.start(() => sample(), abort.signal);
	h.transport.tool(); await entered.promise;
	if (ending === 'cancel') abort.abort(); else h.transport.complete('failed');
	let evidence;
	await assert.rejects(run.promise, error => { evidence = error.nativeTurn; return ['STALE_PLAN', 'TURN_FAILED'].includes(error.code); });
	const row = evidence.toolResponses.rows[0];
	assert.equal(row.pending, 1); assert.equal(row.accepted, 0); assert.equal(row.failed, 0);
	assert.equal(row.respondSamples, 0); assert.equal(evidence.toolResultBytes, 0);
	delivery.resolve(); await tick();
	assert.equal(row.pending, 1, 'published snapshot cannot be rewritten by late delivery');
	const id = decode(h.transport.responses[0].response).observationView.id;
	h.transport.deliver = () => undefined;
	const next = await h.start(); h.transport.tool('observe', { view: 'changes', afterObservationId: id }); await tick();
	assert.equal(decode(h.transport.responses.at(-1).response).observationView.mode, 'full', 'retired delivery must not commit');
	h.transport.complete(); await next.promise;
	if (ending === 'failed') {
		await h.recorder.close();
		assert.deepEqual(h.publicRows[0].toolResponses, evidence.toolResponses);
		assert.deepEqual(h.privateRows[0].toolResponses, evidence.toolResponses);
		assert.deepEqual(h.telemetry.find(row => row.operation === 'native_turn').toolResponses, evidence.toolResponses);
	}
});

for (const capture of ['serialization', 'metadata']) test(`native ${capture} capture failure is observational and still commits delivered views`, { concurrency: false }, async t => {
	const h = await harness(t, { planner: true });
	const value = sample();
	if (capture === 'metadata') Object.defineProperty(value, 'postAction', { get() { throw new Error('private-metadata-error'); } });
	const run = await h.start(() => value);
	const originalStringify = JSON.stringify;
	let traps = 0;
	try {
		if (capture === 'serialization') JSON.stringify = function(value, ...args) {
			if (value?.success === true && Array.isArray(value.contentItems)) { traps++; throw new Error('private-serialization-error'); }
			return originalStringify(value, ...args);
		};
		h.transport.tool(); await tick();
	} finally { JSON.stringify = originalStringify; }
	assert.equal(h.transport.responses.length, 1);
	assert.equal(h.transport.responses[0].response.success, true);
	if (capture === 'serialization') assert.equal(traps, 1);
	const id = decode(h.transport.responses[0].response).observationView.id;
	// The next ordinary result must be able to refer to the successfully delivered baseline.
	if (capture === 'metadata') {
		// Use a new executor on the next turn; keep this turn to one incomplete capture.
		h.transport.complete();
	} else {
		h.transport.tool('observe', { view: 'changes', afterObservationId: id }); await tick();
		assert.equal(decode(h.transport.responses.at(-1).response).observationView.mode, 'changes');
		h.transport.complete();
	}
	const evidence = (await run.promise).nativeTurn;
	assert.equal(evidence.toolResponses.captureFailures, 1);
	assert.equal(evidence.toolResponses.rows.length, capture === 'metadata' ? 0 : 1);
	assert.equal(evidence.toolResultBytes, capture === 'metadata' ? 0 : Buffer.byteLength(JSON.stringify(h.transport.responses[1].response)));
	if (capture === 'metadata') {
		const next = await h.start(); h.transport.tool('observe', { view: 'changes', afterObservationId: id }); await tick();
		assert.equal(decode(h.transport.responses.at(-1).response).observationView.mode, 'changes');
		h.transport.complete(); await next.promise;
	}
	await h.recorder.close();
	assert.deepEqual(h.publicRows[0].toolResponses, evidence.toolResponses);
	assert.deepEqual(h.privateRows[0].toolResponses, evidence.toolResponses);
	assert.deepEqual(h.telemetry.find(row => row.operation === 'native_turn').toolResponses, evidence.toolResponses);
	assert.equal(Object.hasOwn(h.publicRows[0], 'error'), false);
	assert.equal(h.transport.calls.some(row => row.method === 'turn/interrupt'), false);
	assertPrivate(evidence.toolResponses);
});

test('planner persists service summaries on success and delivery rejection without inventing token usage', async t => {
	const h = await harness(t, { planner: true });
	const evidence = [];
	for (const status of ['baseline_unknown', 'counter_reset', 'missing']) {
		const run = await h.start();
		if (status !== 'missing') h.transport.usage(status === 'baseline_unknown' ? 100 : 50);
		if (status === 'missing') h.transport.deliver = () => Promise.reject(new Error('private-delivery-error'));
		h.transport.tool(); await tick();
		if (status === 'missing') await assert.rejects(run.promise, error => { evidence.push(error.nativeTurn); return error.code === 'TOOL_RESPONSE_DELIVERY_FAILED'; });
		else { h.transport.complete(); evidence.push((await run.promise).nativeTurn); }
		assert.equal(evidence.at(-1).usage.status, status);
	}
	await h.recorder.close();
	const telemetry = h.telemetry.filter(row => row.operation === 'native_turn');
	assert.equal(h.privateRows.length, 3); assert.equal(h.publicRows.length, 3); assert.equal(telemetry.length, 3);
	for (let i = 0; i < evidence.length; i++) {
		assert.ok(evidence[i].toolResponses, 'real service evidence must reach all planner sinks');
		for (const row of [h.privateRows[i], h.publicRows[i], telemetry[i]]) {
			assert.deepEqual(row.toolResponses, evidence[i].toolResponses);
			assert.equal(row.usage.status, evidence[i].usage.status);
			assert.ok(Object.values(row.tokens).every(value => value === null));
			assertPrivate(row.toolResponses);
		}
	}
	assert.equal(telemetry[2].errorCode, 'TOOL_RESPONSE_DELIVERY_FAILED');
	assert.equal(h.publicRows[2].error.code, 'TOOL_RESPONSE_DELIVERY_FAILED');
});

test('planner leaves absent tool-response evidence absent through actual recorder and telemetry', async t => {
	const publicRows = [], telemetry = [];
	const recorder = new ProviderTurnRecorder({ runId: 'absence', scenarioId: 'offline', privatePath: null, publicSink: row => publicRows.push(row) });
	t.after(() => recorder.close());
	const planner = new AgentPlanner({ registry: { assertCurrentRevision: () => profile, setState() {} },
		scheduler: { schedule: (_id, operation) => operation({ signal: new AbortController().signal }) },
		codexService: { getAgent: () => null, createAgent: async () => ({ setGoalRevision() {}, act: async () => ({ status: 'completed' }) }) },
		turnRecorder: recorder, telemetrySink: row => telemetry.push(row) });
	await planner.requestNativeTurn({ agentId: profile.agentId, goalRevision: 1, input: 'private-input', executeTool() {} });
	await recorder.close();
	assert.equal(Object.hasOwn(publicRows[0], 'toolResponses'), false);
	assert.equal(Object.hasOwn(telemetry.at(-1), 'toolResponses'), false);
	assert.equal(publicRows[0].usage.status, 'missing');
});
