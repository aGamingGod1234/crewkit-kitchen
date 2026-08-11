import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AcpProviderService, buildAcpLaunch } from '../src/acp-service.mjs';

const DECISION = JSON.stringify({
	summary: 'Wait safely.',
	goalStatus: 'in_progress',
	action: {
		type: 'wait', x: null, y: null, z: null, tolerance: null, sprint: null,
		targetSelector: null, timeoutMs: null, itemId: null, durationMs: 25,
		face: null, message: null, open: null, slot: null, count: null, summary: null,
	},
});

class FakeAcpTransport extends EventEmitter {
	constructor(configOptions) {
		super();
		this.configOptions = configOptions;
		this.calls = [];
		this.started = false;
	}

	async start() { this.started = true; }
	async stop() { this.started = false; }
	notify(method, params) { this.calls.push({ kind: 'notification', method, params }); }
	async request(method, params) {
		this.calls.push({ kind: 'request', method, params });
		if (method === 'initialize') return { protocolVersion: 1, agentInfo: { name: 'fake-acp', version: '1' } };
		if (method === 'session/new') return { sessionId: 'session-1', configOptions: this.configOptions };
		if (method === 'session/set_config_option') return { configOptions: this.configOptions };
		if (method === 'session/prompt') {
			queueMicrotask(() => this.emit('notification', {
				method: 'session/update',
				params: { sessionId: 'session-1', update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: DECISION } } },
			}));
			await new Promise((resolve) => setImmediate(resolve));
			return { stopReason: 'end_turn' };
		}
		throw new Error(`Unexpected ACP method ${method}`);
	}
}

function options({ thinkingValues = ['low', 'medium', 'high'] } = {}) {
	return [
		{ id: 'model', category: 'model', type: 'select', currentValue: 'auto', options: [{ value: 'auto', name: 'Auto' }, { value: 'gemini-pro', name: 'Gemini Pro' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: thinkingValues[0], options: thinkingValues.map((value) => ({ value, name: value })) },
	];
}

test('Gemini ACP sessions apply the exact model and thinking level and parse planner output', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent(
		{ agentId: 'gemini-a', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' },
		{ recoverySummary: 'Previous movement timed out.' },
	);
	await agent.setGoalRevision(2);
	const decision = await agent.decide('authoritative state', { goalRevision: 2 });
	assert.equal(decision.action.type, 'wait');
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').map((call) => call.params), [
		{ sessionId: 'session-1', configId: 'model', value: 'gemini-pro' },
		{ sessionId: 'session-1', configId: 'thinking', value: 'high' },
	]);
	const prompt = transport.calls.find((call) => call.method === 'session/prompt').params.prompt[0].text;
	assert.match(prompt, /strategic planner for one Minecraft player/i);
	assert.match(prompt, /authoritative state/);
	assert.match(prompt, /Previous movement timed out/);
	await service.stop();
});

test('ACP processes and sessions use the same per-agent workspace', async () => {
	const transport = new FakeAcpTransport(options());
	let launchProfile = null;
	const workspaceManager = {
		async prepare(provider, agentId) {
			assert.equal(provider, 'gemini');
			assert.equal(agentId, 'gemini-a');
			return 'C:\\\\agents\\\\gemini\\\\gemini-a';
		},
	};
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\\\workspace', models: ['auto', 'gemini-pro'] },
		{
			workspaceManager,
			transportFactory(profile) {
				launchProfile = profile;
				return transport;
			},
		},
	);
	await service.createAgent({ agentId: 'gemini-a', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });

	assert.equal(launchProfile.cwd, 'C:\\\\agents\\\\gemini\\\\gemini-a');
	assert.equal(transport.calls.find((call) => call.method === 'session/new').params.cwd, 'C:\\\\agents\\\\gemini\\\\gemini-a');
	await service.stop();
});

test('Kimi launches one effort-isolated process and applies the exact ACP thinking level', async () => {
	const launch = buildAcpLaunch('kimi', { reasoningEffort: 'max' }, { env: { PATH: 'test' } });
	assert.equal(launch.command, 'kimi');
	assert.deepEqual(launch.args, ['acp']);
	assert.equal(launch.options.env.KIMI_MODEL_THINKING_EFFORT, 'max');
	assert.equal(launch.options.env.PATH, 'test');

	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'low', options: ['low', 'high', 'max'].map((value) => ({ value, name: value })) },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-a', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max' });
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').at(-1).params, {
		sessionId: 'session-1', configId: 'thinking', value: 'max',
	});
	await service.stop();
});

test('ACP cancellation is a notification and unsupported profile values fail closed', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace' }, { transportFactory: () => transport });
	await assert.rejects(
		service.createAgent({ agentId: 'bad', provider: 'gemini', model: 'missing', reasoningEffort: 'high' }),
		(error) => error.code === 'UNSUPPORTED_MODEL',
	);
	const agent = await service.createAgent({ agentId: 'good', provider: 'gemini', model: 'auto', reasoningEffort: 'high' });
	agent.interrupt();
	assert.equal(transport.calls.at(-1).method, 'session/cancel');
	await service.stop();
});
