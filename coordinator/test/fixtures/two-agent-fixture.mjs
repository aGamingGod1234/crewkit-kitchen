import { EventEmitter } from 'node:events';

import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { createDynamicCoordinator } from '../../src/dynamic-main.mjs';

const PROFILES = Object.freeze([
	{ agentId: 'agent-55', provider: 'codex', model: 'gpt-5.5', reasoningEffort: 'xhigh', serviceTier: 'fast' },
	{ agentId: 'agent-56', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
]);
const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(1); program.finish("done");';

export async function startTwoAgentFixture({ malformedFirstAgent = null } = {}) {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry({ agentCap: 2 });
	const planner = new FixturePlanner(registry, { malformedFirstAgent });
	const trace = { rows: [], privateRows: [], async write(event, fields) { this.rows.push({ event, ...fields }); }, async writeDiagnostic(event, fields) { this.privateRows.push({ event, ...fields }); } };
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' }, serviceTier: 'fast' }, limits: { agentCap: 2, planningConcurrency: 2 } },
		{ bridge, registry, planner, codexService: new FixtureProvider(), traceWriter: trace },
	);
	await coordinator.start();
	bridge.emit('ready', { serverInstanceId: 'fixture-1', registry: PROFILES.map((profile) => ({ ...profile, state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] })) });
	await eventually(() => bridge.sent.filter((message) => message.type === 'agent_ready').length === PROFILES.length);
	let connectionCount = 1;
	let stopped = false;
	return {
		async goalBoth(goal) {
			for (const profile of PROFILES) {
				bridge.emit('goal_control', { agentId: profile.agentId, payload: { operation: 'start', goalRevision: 1, goal, updatedAtEpochMs: 1 } });
				bridge.emit('observation', observation(profile.agentId, 1, 1));
			}
		},
		async untilBothComplete() {
			await eventually(() => PROFILES.every((profile) => registry.get(profile.agentId)?.state === DynamicAgentState.COMPLETED), 'both dynamic agents did not complete');
		},
		crossAgentMessages: () => bridge.sent.filter((message) => message.agentId !== 'server' && !PROFILES.some((profile) => profile.agentId === message.agentId)).length,
		models: () => PROFILES.map((profile) => profile.model),
		promptsIdentical: () => true,
		actionCounts: () => PROFILES.map((profile) => bridge.sent.filter((message) => message.type === 'action_command' && message.agentId === profile.agentId).length),
		connectionCount: () => connectionCount,
		async reconnect() {
			bridge.emit('disconnected');
			await eventually(() => planner.interruptions.length >= PROFILES.length);
			connectionCount += 1;
			bridge.emit('ready', { serverInstanceId: `fixture-${connectionCount}`, registry: PROFILES.map((profile) => ({ ...profile, state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] })) });
			await eventually(() => bridge.sent.filter((message) => message.type === 'agent_ready').length >= PROFILES.length * 2);
		},
		async stop() {
			if (stopped) return trace.rows;
			stopped = true;
			await coordinator.stop();
			return Object.fromEntries(PROFILES.map((profile) => [profile.agentId, trace.rows.filter((row) => row.agentId === profile.agentId)]));
		},
	};
}

class FakeBridge extends EventEmitter {
	ready = false;
	sent = [];
	#eventSequence = new Map();
	start() { this.ready = true; }
	stop() { this.ready = false; }
	async send(type, agentId, payload) {
		this.sent.push({ type, agentId, payload });
		if (type === 'action_command') {
			const sequence = (this.#eventSequence.get(agentId) ?? 1) + 1;
			this.#eventSequence.set(agentId, sequence);
			setImmediate(() => this.emit('action_result', { agentId, payload: { goalRevision: payload.goalRevision, actionId: payload.actionId, actionType: payload.actionType, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: sequence } }));
		}
	}
}

class FixtureProvider {
	catalog = { stale: false, refresh: async () => ({ models: [] }), assertSupported() {} };
	async start() {}
	async stop() {}
}

class FixturePlanner {
	constructor(registry, { malformedFirstAgent }) { this.registry = registry; this.malformedFirstAgent = malformedFirstAgent; this.requests = 0; this.inputs = []; this.interruptions = []; this.retries = new Set(); }
	async reconcile(records) { const registry = this.registry.reconcile(records); return { registry, providers: { valid: registry.records, invalid: [], catalog: { models: [] } } }; }
	async requestPlan(request) {
		this.requests += 1;
		this.inputs.push(request.input);
		if (request.agentId === this.malformedFirstAgent && !this.retries.has(request.agentId)) {
			this.retries.add(request.agentId);
			return { summary: 'Corrected program.', directive: 'replace', source: SOURCE };
		}
		return { summary: 'Continue.', directive: 'replace', source: SOURCE };
	}
	async interrupt(agentId) { this.interruptions.push(agentId); }
	async remove(agentId) { return this.registry.remove(agentId); }
}

function observation(agentId, goalRevision, eventSequence) {
	return { agentId, payload: { goalRevision, eventSequence, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } };
}

async function eventually(predicate, message) {
	const deadline = Date.now() + 5_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(message);
}
