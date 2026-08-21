import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AcpProviderService, buildAcpLaunch } from '../src/acp-service.mjs';

const DECISION = JSON.stringify({
	summary: 'Wait safely.',
	directive: 'replace',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(25);',
});

class FakeAcpTransport extends EventEmitter {
	constructor(configOptions, { configOptionsAfterModel = null } = {}) {
		super();
		this.configOptions = configOptions;
		this.configOptionsAfterModel = configOptionsAfterModel;
		this.calls = [];
		this.started = false;
		this.message = DECISION;
	}

	async start() { this.started = true; }
	async stop() { this.started = false; }
	notify(method, params) { this.calls.push({ kind: 'notification', method, params }); }
	async request(method, params) {
		this.calls.push({ kind: 'request', method, params });
		if (method === 'initialize') return { protocolVersion: 1, agentInfo: { name: 'fake-acp', version: '1' } };
		if (method === 'session/new') return { sessionId: 'session-1', configOptions: this.configOptions };
		if (method === 'session/set_config_option') {
			if (params.configId === 'model' && this.configOptionsAfterModel !== null) {
				this.configOptions = this.configOptionsAfterModel;
			}
			return { configOptions: this.configOptions };
		}
		if (method === 'session/prompt') {
			queueMicrotask(() => this.emit('notification', {
				method: 'session/update',
				params: { sessionId: 'session-1', update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: this.message } } },
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
	assert.equal(decision.directive, 'replace');
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').map((call) => call.params), [
		{ sessionId: 'session-1', configId: 'model', value: 'gemini-pro' },
		{ sessionId: 'session-1', configId: 'thinking', value: 'high' },
	]);
	const prompt = transport.calls.find((call) => call.method === 'session/prompt').params.prompt[0].text;
	assert.match(prompt, /strategic author for one Minecraft player/i);
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
	const launch = buildAcpLaunch('kimi', { reasoningEffort: 'max' }, {
		env: {
			PATH: 'test',
			ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret',
			ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\bridge.secret',
		},
	});
	assert.equal(launch.command, 'kimi');
	assert.deepEqual(launch.args, ['acp']);
	assert.equal(launch.options.env.KIMI_MODEL_THINKING_EFFORT, 'max');
	assert.equal(launch.options.env.PATH, 'test');
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined, 'provider child cannot inherit the bridge secret');
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined, 'provider child cannot inherit the bridge secret file path');

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

test('ACP rejects a streamed planner decision once its aggregate byte budget is exceeded', async () => {
	const transport = new FakeAcpTransport(options());
	transport.message = 'x'.repeat(33);
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'], maxDecisionBytes: 32 },
		{ transportFactory: () => transport },
	);
	const agent = await service.createAgent({ agentId: 'gemini-bounded', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(1);
	await assert.rejects(
		agent.decide('authoritative state', { goalRevision: 1 }),
		(error) => error?.code === 'PLANNER_OUTPUT_LIMIT',
	);
	assert.equal(transport.calls.some((call) => call.kind === 'notification' && call.method === 'session/cancel'), true);
	await service.stop();
});

test('Kimi ACP treats its boolean thinking switch as enabled while the exact effort stays process-scoped', async () => {
	const launch = buildAcpLaunch('kimi', { reasoningEffort: 'low' }, { env: {} });
	assert.equal(launch.options.env.KIMI_MODEL_THINKING_EFFORT, 'low');
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'on', options: [{ value: 'on', name: 'On' }] },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-low', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.equal(transport.calls.some((call) => call.params?.configId === 'thinking' && call.params.value === 'low'), false);
	await service.stop();
});

test('Kimi ACP accepts sessions that expose no thinking control because effort is process-scoped', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-no-thinking-option', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.equal(transport.calls.some((call) => call.params?.configId === 'thinking'), false);
	await service.stop();
});

test('Kimi K3 uses the API-key-backed Moonshot alias when it is available', async () => {
	const transport = new FakeAcpTransport([
		{
			id: 'model', category: 'model', type: 'select', currentValue: 'moonshot-ai/kimi-k3',
			options: [
				{ value: 'kimi-code/k3', name: 'K3 OAuth' },
				{ value: 'moonshot-ai/kimi-k3', name: 'K3 API' },
			],
		},
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-api-k3', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'high' });
	assert.equal(transport.calls.some((call) => call.params?.configId === 'model'), false);
	await service.stop();
});

test('Kimi K2.7 coding aliases use their API-key-backed Moonshot equivalents', async () => {
	for (const [requested, routed] of [
		['kimi-code/kimi-for-coding', 'moonshot-ai/kimi-k2.7-code'],
		['kimi-code/kimi-for-coding-highspeed', 'moonshot-ai/kimi-k2.7-code-highspeed'],
	]) {
		const transport = new FakeAcpTransport([{
			id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3',
			options: [{ value: requested, name: 'OAuth' }, { value: routed, name: 'API' }],
		}]);
		const service = new AcpProviderService({
			provider: 'kimi', cwd: 'C:\\workspace', models: [requested], reasoningEfforts: ['high'],
			modelReasoningEfforts: { [requested]: ['high'] },
		}, { transportFactory: () => transport });
		await service.createAgent({ agentId: `route-${requested}`, provider: 'kimi', model: requested, reasoningEffort: 'high' });
		assert.equal(transport.calls.find((call) => call.params?.configId === 'model')?.params.value, routed);
		await service.stop();
	}
});

test('Kimi empty turns surface provider availability instead of a misleading planner parse error', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
	]);
	transport.message = '';
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'kimi-empty', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'high' });
	await agent.setGoalRevision(1);
	await assert.rejects(
		agent.decide('authoritative state', { goalRevision: 1 }),
		(error) => error?.code === 'PROVIDER_UNAVAILABLE' && /login and membership entitlement/i.test(error.message),
	);
	await service.stop();
});

test('ACP refreshes dependent capabilities after changing the model', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'auto', options: [{ value: 'auto', name: 'Auto' }, { value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'high', options: [{ value: 'high', name: 'High' }] },
	], { configOptionsAfterModel: [
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'auto', name: 'Auto' }, { value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'high', options: ['low', 'high', 'max'].map((value) => ({ value, name: value })) },
	] });
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-low', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').map((call) => call.params.value), ['kimi-code/k3', 'low']);
	await service.stop();
});

test('Kimi catalog retains the last discovered display names when a later CLI refresh fails', async () => {
	let fail = false;
	const discovered = [{
		id: 'kimi-code/kimi-for-coding',
		model: 'kimi-code/kimi-for-coding',
		displayName: 'K2.7 Coding',
		reasoningEfforts: ['high'],
		serviceTiers: [],
	}];
	const service = new AcpProviderService(
		{ provider: 'kimi', cwd: 'C:\\workspace', catalogDiscovery: true },
		{ discoverCatalog: async () => { if (fail) throw new Error('offline'); return discovered; } },
	);
	const first = await service.catalog.refresh({ force: true });
	fail = true;
	const retained = await service.catalog.refresh({ force: true });
	assert.deepEqual(retained, first);
	assert.equal(retained.models[0].displayName, 'K2.7 Coding');
	assert.equal(service.catalog.stale, true);
});
