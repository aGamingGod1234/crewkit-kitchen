// Shared coordinator fixtures; importing this module registers no tests.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { createDynamicCoordinator as createProductionCoordinator } from '../../src/dynamic-main.mjs';
import { PlanningScheduler } from '../../src/planning-scheduler.mjs';
import { goalSpecFingerprint } from '../../src/goal-spec.mjs';
import { withCompletionContract } from './completion-contract.mjs';

const testPollTimeout = globalThis.setTimeout;

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
			message = { ...message, payload: factToWireObservation(payload.observation, payload.goalRevision, payload.eventSequence, payload.attention === true, payload.observedAtEpochMs ?? 1, payload.changedFacts) };
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

class DeferredCompletionBridge extends FakeBridge {
	async send(type, agentId, payload, options = {}) {
		if (type === 'goal_completed') this.sent.push({ type, agentId, payload, connectionEpoch: options.connectionEpoch });
		else await super.send(type, agentId, payload, options);
	}
}

class GatedActionCancelBridge extends FakeBridge {
	#releaseCancel;
	#cancelGate = new Promise((resolve) => { this.#releaseCancel = resolve; });
	cancelPending = false;

	async send(type, agentId, payload, options = {}) {
		await super.send(type, agentId, payload, options);
		if (type !== 'action_cancel') return;
		this.cancelPending = true;
		await this.#cancelGate;
	}

	releaseCancel() { this.#releaseCancel(); }
}

class GatedAgentReadyBridge extends FakeBridge {
	blocked = false;
	#release;
	#gate = new Promise((resolve) => { this.#release = resolve; });
	async send(type, agentId, payload) {
		await super.send(type, agentId, payload);
		if (type === 'agent_ready' && payload.goalRevision === 1) {
			this.blocked = true;
			await this.#gate;
		}
	}
	release() { this.#release(); }
}

class ThrowingPlanningRegistry extends AgentRegistry {
	rejectPlanning = false;
	setState(agentId, state, options) {
		if (this.rejectPlanning && state === DynamicAgentState.PLANNING) throw Object.assign(new Error('planning transition rejected'), { code: 'TEST_PLANNING_REJECTED' });
		return super.setState(agentId, state, options);
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

class RecordingGoalSupervisor {
	activations = [];
	terminations = [];
	observations = [];
	activate(key) { this.activations.push(key); }
	terminate(key) { this.terminations.push(key); }
	begin(key, kind) { return { ...key, kind, operationId: `operation-${kind}` }; }
	end() {}
	progress() {}
	observed(key) { this.observations.push(key); }
	recover() {}
	ensure() {}
	factualProgress() {}
	suspend() {}
	close() {}
}

function record(agentId = 'agent-a') {
	return { agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority', state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] };
}

function factToWireObservation(value, goalRevision, eventSequence, attention, observedAtEpochMs, changedFacts = undefined) {
	const player = value.player ?? {};
	const position = { x: player.x ?? 0, y: player.y ?? 64, z: player.z ?? 0 };
	return {
		goalRevision, observedAtEpochMs, ready: true, status: 'ready', eventSequence, attention,
		changedFacts: attention ? (changedFacts ?? ['player.health']) : [], position, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
		player: {
			health: player.health ?? 20, maxHealth: 20, armor: 0, foodLevel: player.hunger ?? 20, saturation: 5,
			gameMode: 'survival', onGround: true, inWater: false, onFire: player.fire === true,
			air: player.air ?? 300, maxAir: 300, suffocating: false, fallDistance: player.fallDistance ?? 0, effects: [],
			...(player.operatorControlled === true ? { operatorControlled: true } : {}),
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

function pickProfile(value) {
	return {
		provider: value.provider,
		model: value.model,
		reasoningEffort: value.reasoningEffort,
		serviceTier: value.serviceTier,
	};
}

function immutableGoalSpec(originalRequest, predicate = { type: 'operator_confirmed' }, createdAtTick = 1) {
	const fields = { originalRequest, predicate, createdAtTick };
	return { ...fields, fingerprint: goalSpecFingerprint(fields) };
}

async function eventually(predicate) {
	// Admission now performs real filesystem I/O, including in temporary-store tests.
	for (let index = 0; index < 1000; index += 1) {
		if (predicate()) return;
		await new Promise((resolve) => testPollTimeout(resolve, 5));
	}
	throw new Error('condition was not reached');
}

class ManualTimerQueue {
	#nextId = 0;
	#timers = new Map();
	history = [];

	schedule = (callback, delay) => {
		const handle = { id: ++this.#nextId };
		this.#timers.set(handle.id, { handle, callback, delay });
		this.history.push({ handle, callback, delay });
		return handle;
	};

	cancel = (handle) => this.#timers.delete(handle?.id);

	get pendingCount() {
		return this.#timers.size;
	}

	async runNext() {
		const timer = this.#timers.values().next().value;
		if (timer === undefined) throw new Error('no recovery timer is pending');
		this.#timers.delete(timer.handle.id);
		await timer.callback();
	}
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

function realPlannerProvider(decide) {
	const provider = new FakeProvider();
	provider.reconcile = async (records) => ({ valid: records, invalid: [], catalog: { models: [] } });
	provider.createAgent = async (record) => ({
		setGoalRevision: async () => {},
		decide: async (input, options) => withCompletionContract(await decide(input, options, record), options?.goalRevision ?? record.goalRevision),
	});
	provider.removeAgent = async () => {};
	return provider;
}

class NativeLifecycleClock {
	now = 0;
	sequence = 0;
	timers = new Map();
	schedule = (callback, delay) => { const id = ++this.sequence; this.timers.set(id, { callback, due: this.now + delay }); return id; };
	cancel = id => this.timers.delete(id);
	async advance(ms) {
		this.now += ms;
		for (const [id, timer] of [...this.timers]) if (timer.due <= this.now) { this.timers.delete(id); await timer.callback(); }
		for (let index = 0; index < 12; index++) await new Promise(resolve => setImmediate(resolve));
	}
	dependencies() { return { goalClock: () => this.now, goalSchedule: this.schedule, cancelGoalSchedule: this.cancel, goalStuckSchedule: this.schedule, cancelGoalStuckSchedule: this.cancel }; }
}

export { testPollTimeout, SOURCE, createDynamicCoordinator, FakeBridge, DeferredCompletionBridge, GatedActionCancelBridge, GatedAgentReadyBridge, ThrowingPlanningRegistry, FakeProvider, FakePlanner, RecordingGoalSupervisor, record, factToWireObservation, DEATH, pickProfile, immutableGoalSpec, eventually, ManualTimerQueue, start, realPlannerProvider, NativeLifecycleClock };
