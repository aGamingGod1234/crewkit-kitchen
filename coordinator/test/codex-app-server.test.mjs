import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { buildCodexArgs, checkCodexModelProfile, CodexAgent, CodexProtocolError, resolveCodexLaunch } from '../src/codex-app-server.mjs';

const model = {
	id: 'gpt-5.5',
	model: 'gpt-5.5',
	supportedReasoningEfforts: [{ reasoningEffort: 'xhigh', description: 'deep' }],
	serviceTiers: [{ id: 'fast', name: 'Fast', description: 'priority' }],
};

class FakeCodexTransport extends EventEmitter {
	calls = [];
	models = [model];
	autoComplete = true;

	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }
	methods() { return this.calls.filter((call) => !call.method.startsWith('$')).map((call) => call.method); }

	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return { data: this.models, nextCursor: null };
		if (method === 'thread/start') return { thread: { id: 'thread-1' } };
		if (method === 'turn/start') {
			if (this.autoComplete) queueMicrotask(() => {
				this.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-1', completedAtMs: 1, item: { id: 'item-1', type: 'agentMessage', text: '{"summary":"Done","directive":"finish","status":"completed"}' } } });
				this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } } });
			});
			return { turn: { id: 'turn-1', status: 'inProgress', items: [], error: null } };
		}
		if (method === 'turn/interrupt') return {};
		throw new Error(`Unexpected method ${method}`);
	}
}

const config = {
	agentId: 'agent-55',
	model: 'gpt-5.5',
	reasoningEffort: 'xhigh',
	serviceTier: 'fast',
	planningTimeoutMs: 2_000,
	cwd: 'C:\\arena-runtime',
};

test('builds an isolated app-server process command with exact model profile', () => {
	assert.deepEqual(buildCodexArgs(config), [
		'app-server', '--stdio',
		'-c', 'model="gpt-5.5"',
		'-c', 'model_reasoning_effort="xhigh"',
		'-c', 'service_tier="fast"',
		'-c', 'features.fast_mode=true',
	]);
});

test('launches the npm Codex JavaScript entrypoint directly on Windows', () => {
	const launch = resolveCodexLaunch(config, { platform: 'win32', env: { APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming' }, execPath: 'C:\\node.exe', existsSync: () => true });
	assert.equal(launch.command, 'C:\\node.exe');
	assert.match(launch.args[0], /@openai[\\/]codex[\\/]bin[\\/]codex\.js$/);
	assert.deepEqual(launch.args.slice(1), buildCodexArgs(config));
});

test('Codex launch retains provider configuration but strips bridge credentials', () => {
	const launch = resolveCodexLaunch(config, {
		platform: 'win32',
		env: {
			APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming',
			PATH: 'C:\\Windows\\System32',
			ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret',
			ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\bridge.secret',
		},
		execPath: 'C:\\node.exe',
		existsSync: () => true,
	});
	assert.equal(launch.environment.PATH, 'C:\\Windows\\System32');
	assert.equal(launch.environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(launch.environment.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined);
});

test('initializes before catalog validation and thread start', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	assert.deepEqual(transport.methods(), ['initialize', 'initialized', 'model/list', 'thread/start']);
	const initialize = transport.calls.find((call) => call.method === 'initialize').params;
	assert.deepEqual(initialize.capabilities, { experimentalApi: true, requestAttestation: false });
	const thread = transport.calls.find((call) => call.method === 'thread/start').params;
	assert.deepEqual({ model: thread.model, serviceTier: thread.serviceTier, approvalPolicy: thread.approvalPolicy, sandbox: thread.sandbox, dynamicTools: thread.dynamicTools, environments: thread.environments }, {
		model: 'gpt-5.5', serviceTier: 'fast', approvalPolicy: 'never', sandbox: 'read-only', dynamicTools: [], environments: [],
	});
	await agent.stop();
});

test('runs a persistent-thread turn with exact effort and extracts the final agent message', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	const decision = await agent.decide('compact state');
	assert.equal(decision.directive, 'finish');
	const turn = transport.calls.find((call) => call.method === 'turn/start').params;
	assert.equal(turn.threadId, 'thread-1');
	assert.equal(turn.model, 'gpt-5.5');
	assert.equal(turn.effort, 'xhigh');
	assert.equal(turn.serviceTier, 'fast');
	assert.deepEqual(turn.environments, []);
	await agent.stop();
});

test('uses streamed agent-message deltas when a completed message item is absent', async () => {
	const transport = new FakeCodexTransport();
	transport.autoComplete = false;
	const agent = new CodexAgent(config, transport);
	await agent.start();
	const decisionPromise = agent.decide('compact state');
	await Promise.resolve();
	const text = '{"summary":"Done","directive":"finish","status":"completed"}';
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: text } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } } });
	assert.equal((await decisionPromise).status, 'completed');
	await agent.stop();
});

test('rejects an over-budget streamed Codex decision before parsing it', async () => {
	const transport = new FakeCodexTransport();
	transport.autoComplete = false;
	const agent = new CodexAgent({ ...config, maxDecisionBytes: 32 }, transport);
	await agent.start();
	const decision = agent.decide('compact state');
	await Promise.resolve();
	transport.emit('notification', {
		method: 'item/agentMessage/delta',
		params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: 'x'.repeat(33) },
	});
	transport.emit('notification', {
		method: 'turn/completed',
		params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } },
	});
	await assert.rejects(decision, (error) => error?.code === 'TURN_OUTPUT_LIMIT');
	await agent.stop();
});

test('fails closed when model effort or Fast tier is absent', async () => {
	const transport = new FakeCodexTransport();
	transport.models = [{ ...model, supportedReasoningEfforts: [{ reasoningEffort: 'high' }] }];
	await assert.rejects(() => new CodexAgent(config, transport).start(), (error) => error instanceof CodexProtocolError && error.code === 'MODEL_PROFILE_UNAVAILABLE');
});

test('checks a live catalog profile without starting a planner thread', async () => {
	const transport = new FakeCodexTransport();
	const checked = await checkCodexModelProfile(config, transport);
	assert.equal(checked.model, 'gpt-5.5');
	assert.deepEqual(transport.methods(), ['initialize', 'initialized', 'model/list']);
});

test('restarts a failed app-server into a fresh persistent thread', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	await agent.restart();
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 2);
	assert.equal(transport.calls.filter((call) => call.method === '$start').length, 2);
	assert.equal(transport.calls.filter((call) => call.method === '$stop').length, 1);
	await agent.stop();
});
