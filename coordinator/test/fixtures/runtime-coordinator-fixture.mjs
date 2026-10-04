// Fixture definitions copied from pinned coordinator/test/dynamic-main.test.mjs; no tests imported.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { createDynamicCoordinator as createProductionCoordinator } from '../../src/dynamic-main.mjs';
import { PlanningScheduler } from '../../src/planning-scheduler.mjs';
import { validateProtocolV2Payload } from '../../src/protocol-v2.mjs';
import { withCompletionContract } from './completion-contract.mjs';
const testPollTimeout=globalThis.setTimeout;
const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';

function createDynamicCoordinator(config, dependencies = {}) {
	return createProductionCoordinator(config, { memoryDirectory: null, ...dependencies });
}

class FakeBridge extends EventEmitter {
	ready = false;
	sent = [];
	connectionEpoch = 0;
	connected = false;
	serverInstanceId = null;
	automaticInspections = true;
	latestObservations = new Map();
	latestSequences = new Map();
	start() { this.ready = true; }
	stop() { this.ready = false; }
	async send(type, agentId, payload, options = {}) {
		const message = { type, agentId, payload, connectionEpoch: options.connectionEpoch };
		this.sent.push(message);
		if (type === 'inspection_request' && this.automaticInspections) {
			queueMicrotask(() => this.sampleInspection(message));
		}
		if (type === 'goal_completed') {
			queueMicrotask(() => this.emit('goal_completion_result', {
				agentId,
				payload: {
					goalRevision: payload.goalRevision,
					traceId: payload.traceId,
					goalFingerprint: payload.goalFingerprint,
					verified: true,
					reasonCode: 'COMPLETION_VERIFIED',
					facts: [],
				},
			}));
		}
	}
	emit(event, message) {
		if (event === 'ready') {
			const epoch = message.connectionEpoch ?? (this.connected && this.serverInstanceId === message.serverInstanceId ? this.connectionEpoch : this.connectionEpoch + 1);
			if (epoch > this.connectionEpoch) {
				this.connectionEpoch = epoch;
				this.connected = true;
				this.serverInstanceId = message.serverInstanceId;
				this.latestObservations.clear();
				this.latestSequences.clear();
			}
		}
		if (event === 'disconnected' && (message?.connectionEpoch ?? this.connectionEpoch) === this.connectionEpoch) this.connected = false;
		if (event === 'observation' && message?.payload?.observation !== undefined) {
			const payload = message.payload;
			message = { ...message, payload: factToWireObservation(payload.observation, payload.goalRevision, payload.eventSequence, payload.attention === true, payload.observedAtEpochMs ?? 1) };
		}
		if ((message?.connectionEpoch ?? this.connectionEpoch) === this.connectionEpoch) {
			if (Number.isSafeInteger(message?.payload?.eventSequence)) {
				this.latestSequences.set(message.agentId, Math.max(this.latestSequences.get(message.agentId) ?? 0, message.payload.eventSequence));
			}
			if (event === 'observation') this.latestObservations.set(message.agentId, structuredClone(message.payload));
		}
		return super.emit(event, message);
	}

	replyInspection(request, result, error = undefined) {
		this.emit('inspection_result', {
			connectionEpoch: request.connectionEpoch,
			agentId: request.agentId,
			payload: { requestId: request.payload.requestId, goalRevision: request.payload.goalRevision, ...(error === undefined ? { result } : { error }) },
		});
	}

	sampleInspection(request) {
		const previous = this.latestObservations.get(request.agentId);
		if (previous?.goalRevision !== request.payload.goalRevision || request.connectionEpoch !== this.connectionEpoch) {
			this.replyInspection(request, undefined, { code: 'STALE_REVISION', message: 'No current player sample is available' });
			return;
		}
		assert.equal(request.payload.query.section, 'observation', 'focused fixture queries must provide their explicit response');
		const observation = structuredClone(previous);
		observation.eventSequence = (this.latestSequences.get(request.agentId) ?? previous.eventSequence) + 1;
		observation.observedAtEpochMs += 1;
		observation.attention = true;
		observation.changedFacts = [];
		this.emit('observation', { agentId: request.agentId, connectionEpoch: request.connectionEpoch, payload: observation });
		this.replyInspection(request, { observation, eventSequence: observation.eventSequence });
	}
}

class FakeProvider {
	catalog = { stale: false, refresh: async () => ({ models: [] }), assertSupported() {} };
	async start() {}
	async stop() {}
}

class FakePlanner {
	constructor(registry) { this.registry = registry; this.requests = []; this.goalSpecRequests = []; this.goalSpecCancellations = []; this.interruptions = []; }
	beginReconcile(records, options = undefined) {
		const registry = this.registry.reconcile(records, options);
		return { registry, complete: Promise.resolve({ registry, providers: { valid: registry.records, invalid: [], catalog: { models: [] } } }) };
	}
	async reconcile(records) { return this.beginReconcile(records).complete; }
	async requestPlan(request) { this.requests.push(request); return withCompletionContract({ summary: 'Wait twice.', directive: 'replace', source: SOURCE }, request.goalRevision); }
	async requestGoalSpec(request) {
		this.goalSpecRequests.push(request);
		return { requestId: request.request.requestId, summary: 'Obtain an iron pickaxe.', predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 } };
	}
	cancelGoalSpec(agentId, requestId) { this.goalSpecCancellations.push({ agentId, requestId }); return true; }
	async interrupt(agentId) { this.interruptions.push(agentId); }
	async remove(agentId) { return this.registry.remove(agentId); }
}

function record(agentId = 'agent-a') {
	return { agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority', state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] };
}

function factToWireObservation(value, goalRevision, eventSequence, attention, observedAtEpochMs) {
	const player = value.player ?? {};
	const position = { x: player.x ?? 0, y: player.y ?? 64, z: player.z ?? 0 };
	return {
		goalRevision, observedAtEpochMs, ready: true, status: 'ready', eventSequence, attention,
		changedFacts: attention ? ['player.health'] : [], position, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
		player: {
			health: player.health ?? 20, maxHealth: 20, armor: 0, foodLevel: player.hunger ?? 20, saturation: 5,
			gameMode: 'survival', onGround: true, inWater: false, onFire: player.fire === true,
			air: player.air ?? 300, maxAir: 300, suffocating: false, fallDistance: player.fallDistance ?? 0, effects: [],
		},
		inventory: { items: (value.inventory?.items ?? []).map((item, index) => ({ itemId: item.itemId, count: item.count, damage: 0, maxDamage: 0, slot: item.slot ?? index })), selectedItem: 'minecraft:air' },
		entities: (value.items ?? []).map((item) => ({ uuid: item.stableId, type: 'minecraft:item', name: 'drop', distance: Math.hypot(item.x - position.x, item.y - position.y, item.z - position.z), position: { x: item.x, y: item.y, z: item.z }, itemId: item.itemId, count: item.count })),
		blocks: (value.blocks ?? []).map((block) => ({ x: block.x, y: block.y, z: block.z, blockId: block.blockId, placeableFaces: ['up'] })),
		nearbyContainers: [], world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
		currentAction: { active: false }, lastResult: { present: false },
	};
}

const DEATH = Object.freeze({
	cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 0, y: 64, z: 0,
	respawnDimensionId: 'minecraft:overworld', respawnX: 100.5, respawnY: 70, respawnZ: -20.5,
	respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 2,
});


async function eventually(predicate) {
	// Admission now performs real filesystem I/O, including in temporary-store tests.
	for (let index = 0; index < 1000; index += 1) {
		if (predicate()) return;
		await new Promise((resolve) => testPollTimeout(resolve, 5));
	}
	throw new Error('condition was not reached');
}


async function start(dependencies = {}) {
	const bridge = dependencies.bridge ?? new FakeBridge();
	const registry = dependencies.registry ?? new AgentRegistry();
	const planner = dependencies.planner ?? new FakePlanner(registry);
	const scheduler = dependencies.scheduler ?? new PlanningScheduler();
	const codexService = dependencies.codexService ?? new FakeProvider();
	const config = dependencies.config ?? { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } };
	const coordinator = createDynamicCoordinator(config, { bridge, registry, planner, scheduler, codexService, ...dependencies });
	await coordinator.start();
	bridge.emit('ready', { serverInstanceId: 'test', registry: dependencies.initialRegistry ?? [record()] });
	await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready'));
	return { bridge, registry, planner, scheduler, coordinator };
}


export { createDynamicCoordinator, FakeBridge, FakeProvider, FakePlanner, AgentRegistry, DynamicAgentState, record, factToWireObservation, DEATH, eventually, start, withCompletionContract };
