import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { CodexService } from '../src/codex-service.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';

const MODEL = {
	id: 'gpt-5.6-sol',
	model: 'gpt-5.6-sol',
	supportedReasoningEfforts: [{ reasoningEffort: 'high' }],
	serviceTiers: [{ id: 'fast' }, { id: 'priority' }],
};

class FakeSharedTransport extends EventEmitter {
	calls = [];
	threadSequence = 0;
	turnSequence = 0;
	autoComplete = true;
	rejectInterrupt = false;
	holdTurnStart = false;
	turnStartResolvers = [];

	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }

	async request(method, params, options) {
		this.calls.push({ method, params, options });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return { data: [MODEL], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: `thread-${++this.threadSequence}` } };
		if (method === 'turn/start') {
			if (this.holdTurnStart) {
				return new Promise((resolve) => this.turnStartResolvers.push(resolve));
			}
			const turnId = `turn-${++this.turnSequence}`;
			if (this.autoComplete) queueMicrotask(() => this.complete(params.threadId, turnId));
			return { turn: { id: turnId } };
		}
		if (method === 'turn/interrupt') {
			if (this.rejectInterrupt) throw Object.assign(new Error('turn already completed'), { code: 'RPC_ERROR' });
			return {};
		}
		throw new Error(`Unexpected method ${method}`);
	}

	complete(threadId, turnId) {
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: '{"summary":"Done","directive":"finish","status":"completed"}' } } });
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'completed' } } });
	}
}

function profile(agentId) {
	return { agentId, model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' };
}

test('Codex service defaults dynamic profiles to the priority app-server tier', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	await service.createAgent({ agentId: 'agent-default', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	const threadStart = transport.calls.find((call) => call.method === 'thread/start');
	assert.equal(threadStart.params.serviceTier, 'priority');
	await service.stop();
});

test('Codex service shares one initialized transport across isolated agent threads', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const first = await service.createAgent(profile('agent-a'));
	const second = await service.createAgent(profile('agent-b'));
	assert.equal(transport.calls.filter((call) => call.method === 'initialize').length, 1);
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 2);
	await first.setGoalRevision(1);
	await second.setGoalRevision(3);
	const [firstDecision, secondDecision] = await Promise.all([
		first.decide('First observation.', { goalRevision: 1 }),
		second.decide('Second observation.', { goalRevision: 3 }),
	]);
	assert.equal(firstDecision.status, 'completed');
	assert.equal(secondDecision.status, 'completed');
	await service.stop();
});

test('Codex recovery reuses one exact profile and session and rejects profile mutation', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-profile');
	const agent = await service.createAgent(selected, { recoverySummary: 'recover through the same session' });
	assert.equal(agent.sessionGeneration, 1);
	assert.equal(agent.profileFingerprint, profileFingerprint({ ...selected, provider: 'codex' }));
	assert.equal(await service.createAgent(selected, { recoverySummary: 'same profile retry' }), agent);
	for (const mutation of [
		{ model: 'gpt-5.6-other' },
		{ reasoningEffort: 'low' },
		{ serviceTier: 'priority' },
	]) {
		await assert.rejects(
			service.createAgent({ ...selected, ...mutation }),
			(error) => error?.code === 'AGENT_PROFILE_CONFLICT'
				&& error?.message.length <= 256
				&& !error?.message.includes('secret'),
		);
	}
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 1);
	await service.stop();
});

test('Codex session metadata stays warm on the same exact profile across sequential turns', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-session');
	const agent = await service.createAgent(selected);
	await agent.setGoalRevision(1);
	await agent.decide('first authoritative state', { goalRevision: 1 });
	const first = agent.sessionMetadata();
	await agent.decide('second authoritative state', { goalRevision: 1 });
	const second = agent.sessionMetadata();
	assert.equal(first.sessionGeneration, 1);
	assert.equal(first.sessionState, 'warm');
	assert.equal(first.continuation, 'durable');
	assert.deepEqual(second, first);
	await service.stop();
});

test('Codex session replacement increments generation and reports a reset reason without changing profile', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-reset');
	const first = await service.createAgent(selected);
	await service.removeAgent(selected.agentId);
	const replacement = await service.createAgent(selected);
	assert.equal(replacement.sessionGeneration, 2);
	assert.equal(replacement.profileFingerprint, first.profileFingerprint);
	assert.equal(replacement.sessionMetadata().resetReason, 'session_replaced');
	await service.stop();
});

test('Codex service gives concurrent thread creation enough time for a full arena roster', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	await Promise.all(Array.from({ length: 16 }, (_, index) => service.createAgent(profile(`agent-${index + 1}`))));
	const starts = transport.calls.filter((call) => call.method === 'thread/start');
	assert.equal(starts.length, 16);
	assert.deepEqual(starts.map((call) => call.options), Array(16).fill({ timeoutMs: 60_000 }));
	await service.stop();
});

test('Codex service accepts the streamed agent-message contract when no completed message item arrives', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-streamed'));
	await agent.setGoalRevision(1);
	const decisionPromise = agent.decide('Observation.', { goalRevision: 1 });
	await Promise.resolve();
	const text = '{"summary":"Done","directive":"finish","status":"completed"}';
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: text } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.equal((await decisionPromise).status, 'completed');
	await service.stop();
});

test('Codex service cancels an over-budget streamed planner decision before parsing it', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace', maxDecisionBytes: 32 }, { transport });
	const agent = await service.createAgent(profile('agent-bounded'));
	await agent.setGoalRevision(1);
	const decisionPromise = agent.decide('Observation.', { goalRevision: 1 });
	await Promise.resolve();
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: 'x'.repeat(33) } });
	await assert.rejects(decisionPromise, (error) => error?.code === 'TURN_OUTPUT_LIMIT');
	assert.ok(transport.calls.some((call) => call.method === 'turn/interrupt' && call.params.turnId === 'turn-1'));
	await service.stop();
});

test('Codex threads use provider-scoped per-agent workspaces', async () => {
	const transport = new FakeSharedTransport();
	const prepared = [];
	const workspaceManager = {
		async prepare(provider, agentId) {
			prepared.push({ provider, agentId });
			return `C:\\\\agents\\\\${provider}\\\\${agentId}`;
		},
	};
	const service = new CodexService({ cwd: 'C:\\\\workspace' }, { transport, workspaceManager });
	await service.createAgent(profile('agent-a'));
	await service.createAgent(profile('agent-b'));

	assert.deepEqual(prepared, [
		{ provider: 'codex', agentId: 'agent-a' },
		{ provider: 'codex', agentId: 'agent-b' },
	]);
	assert.deepEqual(
		transport.calls.filter((call) => call.method === 'thread/start').map((call) => call.params.cwd),
		['C:\\\\agents\\\\codex\\\\agent-a', 'C:\\\\agents\\\\codex\\\\agent-b'],
	);
	await service.stop();
});

test('concurrent stop paths interrupt a Codex turn exactly once and reject its late result', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-a'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await Promise.resolve();
	await Promise.all([agent.interrupt(), agent.interrupt(), agent.setGoalRevision(2)]);
	transport.complete('thread-1', 'turn-1');
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt').length, 1);
	await service.stop();
});

test('an abort-time stale interrupt rejection cannot escape as an unhandled process error', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	transport.rejectInterrupt = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-abort-race'));
	await agent.setGoalRevision(1);
	const controller = new AbortController();
	const decision = agent.decide('Observation.', { goalRevision: 1, signal: controller.signal });
	await Promise.resolve();
	controller.abort();
	transport.complete('thread-1', 'turn-1');
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	await new Promise((resolve) => setImmediate(resolve));
	transport.rejectInterrupt = false;
	await service.stop();
});

test('Codex turn start is bounded by the planning timeout', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const scheduled = [];
	const service = new CodexService({
		cwd: 'C:\\workspace',
		planningTimeoutMs: 25,
		schedule(callback, timeoutMs) {
			scheduled.push({ callback, timeoutMs });
			return callback;
		},
		cancelSchedule() {},
	}, { transport });
	const agent = await service.createAgent(profile('agent-turn-start-timeout'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(transport.calls.find((call) => call.method === 'turn/start').options, { timeoutMs: 25 });
	assert.equal(scheduled.length, 1);
	assert.equal(scheduled[0].timeoutMs, 25);
	scheduled[0].callback();
	await assert.rejects(decision, (error) => error.code === 'PLANNING_TIMEOUT');
	await service.stop();
});

test('aborting before turn start settles the decision promptly', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-abort-before-turn-id'));
	await agent.setGoalRevision(1);
	const controller = new AbortController();
	const decision = agent.decide('Observation.', { goalRevision: 1, signal: controller.signal });
	await new Promise((resolve) => setImmediate(resolve));
	controller.abort();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'STALE_PLAN');
	await service.stop();
});

test('disposing before turn start settles the decision promptly', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-dispose-before-turn-id'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await agent.dispose();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'AGENT_DISPOSED');
	await service.stop();
});

test('a completed notification cannot be mistaken for a delayed turn-start response', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-notification-before-start-response'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.complete('thread-1', 'turn-delayed');
	transport.turnStartResolvers[0]({ turn: { id: 'turn-delayed' } });
	assert.equal((await decision).status, 'completed');
	await service.stop();
});

test('a late prior-turn completion cannot satisfy a new turn before its ID is known', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-stale-notification'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	const staleText = '{"summary":"Stale prior turn","directive":"finish","status":"completed"}';
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-old', item: { type: 'agentMessage', text: staleText } } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turnId: 'turn-old', turn: { id: 'turn-old', status: 'completed' } } });
	transport.turnStartResolvers[0]({ turn: { id: 'turn-new' } });
	await new Promise((resolve) => setImmediate(resolve));
	const currentText = '{"summary":"Current turn","directive":"finish","status":"completed"}';
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-new', item: { type: 'agentMessage', text: currentText } } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turnId: 'turn-new', turn: { id: 'turn-new', status: 'completed' } } });
	assert.equal((await decision).summary, 'Current turn');
	await service.stop();
});

test('an empty completion before the turn-start response rejects without an unhandled process error', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-empty-before-start-response'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', {
		method: 'turn/completed',
		params: { threadId: 'thread-1', turnId: 'turn-delayed', turn: { id: 'turn-delayed', status: 'completed' } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.turnStartResolvers[0]({ turn: { id: 'turn-delayed' } });
	await assert.rejects(decision, (error) => error.code === 'MISSING_AGENT_MESSAGE');
});

test('interrupting before turn start settles the decision promptly', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-interrupt-before-turn-id'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await agent.interrupt();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'STALE_PLAN');
	await service.stop();
});

test('interrupting as turn-start resolves still cleans up the late provider turn', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-interrupt-at-turn-start'));
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.turnStartResolvers[0]({ turn: { id: 'turn-late' } });
	await agent.interrupt();
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt').length, 1);
});
