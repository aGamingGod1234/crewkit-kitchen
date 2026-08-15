import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { createDynamicCoordinator } from '../src/dynamic-main.mjs';

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';

class FakeBridge extends EventEmitter {
	ready = false;
	sent = [];
	start() { this.ready = true; }
	stop() { this.ready = false; }
	async send(type, agentId, payload) { this.sent.push({ type, agentId, payload }); }
}

class FakeProvider {
	catalog = { stale: false, refresh: async () => ({ models: [] }), assertSupported() {} };
	async start() {}
	async stop() {}
}

class FakePlanner {
	constructor(registry) { this.registry = registry; this.requests = []; this.interruptions = []; }
	async reconcile(records) { const registry = this.registry.reconcile(records); return { registry, providers: { valid: registry.records, invalid: [], catalog: { models: [] } } }; }
	async requestPlan(request) { this.requests.push(request); return { summary: 'Wait twice.', directive: 'replace', source: SOURCE }; }
	async interrupt(agentId) { this.interruptions.push(agentId); }
	async remove(agentId) { return this.registry.remove(agentId); }
}

function record(agentId = 'agent-a') {
	return { agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] };
}

async function eventually(predicate) {
	for (let index = 0; index < 100; index += 1) {
		if (predicate()) return;
		await new Promise((resolve) => setImmediate(resolve));
	}
	throw new Error('condition was not reached');
}

async function start() {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } }, { bridge, registry, planner, codexService: new FakeProvider() });
	await coordinator.start();
	bridge.emit('ready', { serverInstanceId: 'test', registry: [record()] });
	await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready'));
	return { bridge, registry, planner, coordinator };
}

test('installs a selected-model program and continues its next primitive without another provider turn', async () => {
	const run = await start();
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 1);
		const first = run.bridge.sent.find((message) => message.type === 'action_command');
		assert.equal(run.planner.requests[0].agentId, 'agent-a');
		assert.equal(first.payload.provenance.programId, 'program-1-1');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 2);
		assert.equal(run.planner.requests.length, 1);
	} finally { await run.coordinator.stop(); }
});

test('steering and death dispose programs so stale action results are rejected', async () => {
	const run = await start();
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'Stop waiting.' } });
		await eventually(() => run.registry.get('agent-a')?.goalRevision === 2);
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_cancel' && message.payload.actionId === command.payload.actionId), true);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'dead', goalRevision: 3 } });
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.DEAD);
		assert.equal(run.planner.interruptions.includes('agent-a'), true);
	} finally { await run.coordinator.stop(); }
});
