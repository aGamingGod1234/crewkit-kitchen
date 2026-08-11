import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { CodexService } from '../src/codex-service.mjs';

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

	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }

	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return { data: [MODEL], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: `thread-${++this.threadSequence}` } };
		if (method === 'turn/start') {
			const turnId = `turn-${++this.turnSequence}`;
			if (this.autoComplete) queueMicrotask(() => this.complete(params.threadId, turnId));
			return { turn: { id: turnId } };
		}
		if (method === 'turn/interrupt') return {};
		throw new Error(`Unexpected method ${method}`);
	}

	complete(threadId, turnId) {
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: '{"summary":"Done","goalStatus":"completed","action":{"type":"complete_goal","summary":"Done"}}' } } });
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
	assert.equal(firstDecision.goalStatus, 'completed');
	assert.equal(secondDecision.goalStatus, 'completed');
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
