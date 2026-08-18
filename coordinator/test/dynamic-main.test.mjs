import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { createDynamicCoordinator } from '../src/dynamic-main.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';

class FakeBridge extends EventEmitter {
	ready = false;
	sent = [];
	start() { this.ready = true; }
	stop() { this.ready = false; }
	async send(type, agentId, payload) { this.sent.push({ type, agentId, payload }); }
	emit(event, message) {
		if (event === 'observation' && message?.payload?.observation !== undefined) {
			const payload = message.payload;
			return super.emit(event, { ...message, payload: factToWireObservation(payload.observation, payload.goalRevision, payload.eventSequence, payload.attention === true, payload.observedAtEpochMs ?? 1) });
		}
		return super.emit(event, message);
	}
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
	for (let index = 0; index < 100; index += 1) {
		if (predicate()) return;
		await new Promise((resolve) => setImmediate(resolve));
	}
	throw new Error('condition was not reached');
}

async function start(dependencies = {}) {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const scheduler = dependencies.scheduler ?? new PlanningScheduler();
	const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } }, { bridge, registry, planner, scheduler, codexService: new FakeProvider(), ...dependencies });
	await coordinator.start();
	bridge.emit('ready', { serverInstanceId: 'test', registry: [record()] });
	await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready'));
	return { bridge, registry, planner, scheduler, coordinator };
}

test('dynamic coordinator forwards protocol audit to its constructed bridge', () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	assert.throws(() => createDynamicCoordinator({
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
	}, {
		registry, planner, scheduler: new PlanningScheduler(), codexService: new FakeProvider(),
		protocolAudit: 'invalid audit callback',
	}), /audit must be a function or null/);
});

test('does not submit a duplicate initial plan while the agent already has a scheduled turn', async () => {
	let release;
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	const blocker = new Promise((resolve) => { release = resolve; });
	const run = await start({ scheduler });
	try {
		scheduler.schedule('agent-a', async () => blocker);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.planner.requests.length, 0);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

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

test('publishes completed program state back to the server registry', async () => {
	const run = await start();
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		return {
			summary: 'Wait, then finish.',
			directive: 'replace',
			source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");',
		};
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait, then finish.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'goal_completed'));
		const stateMessages = run.bridge.sent.filter((message) => message.type === 'goal_completed');
		assert.deepEqual(stateMessages.at(-1), {
			type: 'goal_completed',
			agentId: 'agent-a',
			payload: { goalRevision: 1 },
		});
	} finally { await run.coordinator.stop(); }
});

test('real protocol-v2 observations adapt before ArenaScript facts normalization', async () => {
	const run = await start();
	const runtimeErrors = [];
	run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Inspect the nearby drop.' } });
		const wireObservation = validateProtocolV2Payload('observation', {
			goalRevision: 1,
			observedAtEpochMs: 10,
			ready: true,
			status: 'ready',
			eventSequence: 1,
			attention: false,
			changedFacts: [],
			position: { x: 0, y: 64, z: 0 },
			velocity: { x: 0, y: 0, z: 0 },
			view: { yaw: 0, pitch: 0 },
			player: {
				health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5,
				gameMode: 'survival', onGround: true, inWater: false, onFire: false,
				air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [],
			},
			inventory: { items: [], selectedItem: 'minecraft:air' },
			entities: [{
				uuid: '00000000-0000-0000-0000-000000000001', type: 'minecraft:item', name: 'Oak Log',
				distance: 2, position: { x: 2, y: 64, z: 0 }, itemId: 'minecraft:oak_log', count: 1,
			}],
			blocks: [{ x: 4, y: 64, z: 0, blockId: 'minecraft:oak_log', placeableFaces: ['up'] }],
			nearbyContainers: [],
			world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
			currentAction: { active: false },
			lastResult: { present: false },
		});
		run.bridge.emit('observation', { agentId: 'agent-a', payload: wireObservation });
		await eventually(() => runtimeErrors.length > 0 || run.bridge.sent.some((message) => message.type === 'action_command'));
		assert.deepEqual(runtimeErrors, [], `wire observation must be adapted before facts normalization: ${runtimeErrors[0]?.message ?? 'unknown error'}`);
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_command'), true);
	} finally { await run.coordinator.stop(); }
});

test('records program authority and typed command diagnostics for the selected model', async () => {
	const rows = [];
	const diagnostics = [];
	const run = await start({ traceWriter: {
		write: async (event, fields) => rows.push({ event, ...fields }),
		writeDiagnostic: async (event, fields) => diagnostics.push({ event, ...fields }),
	} });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => rows.some((row) => row.event === 'program_compiled'));
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		assert.ok(command);
		const step = rows.find((row) => row.event === 'program_step');
		assert.equal(step.provider, 'codex');
		assert.equal(step.model, 'gpt-5.6-sol');
		assert.equal(step.reasoningEffort, 'high');
		assert.equal(step.serviceTier, 'priority');
		assert.equal(step.goalRevision, 1);
		assert.equal(step.programId, command.payload.provenance.programId);
		assert.equal(step.sourceStepId, command.payload.provenance.sourceStepId);
		assert.equal(step.result, null);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'FAILED', reasonCode: 'PATH_BLOCKED', eventSequence: 2 } });
		await eventually(() => rows.some((row) => row.event === 'program_step' && row.result?.reasonCode === 'PATH_BLOCKED'));
		assert.equal(diagnostics.some((row) => row.event === 'program_compiled'), true);
	} finally { await run.coordinator.stop(); }
});

test('injects coordinator latency telemetry into program reaction timing', async () => {
	let now = 10;
	let publishStatus = null;
	const latencyRegistry = new ControlLatencyRegistry();
	const run = await start({
		latencyRegistry, controlNow: () => now++, epochNow: () => 100,
		setStatusInterval: (callback) => { publishStatus = callback; return 1; }, clearStatusInterval: () => {},
	});
	try {
		run.planner.requestPlan = async (request) => {
			run.planner.requests.push(request);
			return { summary: 'Watch health.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);' };
		};
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, observedAtEpochMs: 10, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const first = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, observedAtEpochMs: 11, eventSequence: 2, attention: false, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		assert.equal(latencyRegistry.snapshot().some((entry) => entry.operation === 'event_receipt_to_branch'), false, 'heartbeats never create reaction timing');
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, observedAtEpochMs: 12, eventSequence: 3, attention: true, observation: { player: { x: 0, y: 64, z: 0, health: 19 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 4 } });
		await eventually(() => latencyRegistry.snapshot().some((entry) => entry.operation === 'event_receipt_to_branch'));
		publishStatus();
		await eventually(() => run.bridge.sent.some((message) => message.type === 'coordinator_status' && message.payload.latencies.some((entry) => entry.operation === 'event_receipt_to_branch')));
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
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'dead', goalRevision: 3, updatedAtEpochMs: 3, death: DEATH } });
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.DEAD);
		assert.equal(run.planner.interruptions.includes('agent-a'), true);
	} finally { await run.coordinator.stop(); }
});

test('reconciliation reissues one dead-state turn to the selected session and preserves DEAD across disconnect', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return { summary: 'Respawn.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();' };
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	await coordinator.start();
	try {
		const dead = { ...record(), state: DynamicAgentState.DEAD, currentGoal: 'Survive.', goalRevision: 4, death: DEATH };
		bridge.emit('ready', { serverInstanceId: 'first', registry: [dead] });
		await eventually(() => bridge.sent.some((message) => message.payload?.actionType === 'respawn'));
		assert.equal(planner.requests.length, 1);
		assert.equal(planner.requests[0].preserveState, true);
		assert.match(planner.requests[0].input, /fell from a high place/);
		assert.equal(bridge.sent.find((message) => message.payload?.actionType === 'respawn').payload.provenance.model, 'gpt-5.6-sol');
		bridge.emit('ready', { serverInstanceId: 'first', registry: [dead] });
		await eventually(() => bridge.sent.filter((message) => message.type === 'agent_ready').length >= 2);
		assert.equal(planner.requests.length, 1, 'duplicate reconciliation does not create a second dead turn');
		bridge.emit('disconnected');
		await eventually(() => planner.interruptions.includes('agent-a'));
		assert.equal(registry.get('agent-a').state, DynamicAgentState.DEAD, 'transport loss cannot erase persisted DEAD state');
		bridge.emit('ready', { serverInstanceId: 'first', registry: [dead] });
		await eventually(() => planner.requests.length === 2);
		assert.equal(planner.requests.length, 2, 'reconnect reissues exactly one replacement dead turn');
	} finally { await coordinator.stop(); }
});

test('disconnect fences the old dead respawn result before reconnect installs a new program', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return { summary: 'Respawn.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();' };
	};
	const errors = [];
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	coordinator.on('runtimeError', (error) => errors.push(error));
	await coordinator.start();
	try {
		const dead = { ...record(), state: DynamicAgentState.DEAD, currentGoal: 'Survive.', goalRevision: 4, death: DEATH };
		bridge.emit('ready', { serverInstanceId: 'first', registry: [dead] });
		await eventually(() => bridge.sent.some((message) => message.payload?.actionType === 'respawn'));
		const oldCommand = bridge.sent.find((message) => message.payload?.actionType === 'respawn');
		bridge.emit('disconnected');
		await eventually(() => planner.interruptions.includes('agent-a'));
		bridge.emit('ready', { serverInstanceId: 'second', registry: [dead] });
		await eventually(() => bridge.sent.filter((message) => message.payload?.actionType === 'respawn').length >= 2);
		const newCommand = bridge.sent.filter((message) => message.payload?.actionType === 'respawn').at(-1);
		assert.notEqual(oldCommand.payload.actionId, newCommand.payload.actionId);
		bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 4, actionId: oldCommand.payload.actionId, actionType: 'respawn', state: 'SUCCEEDED', reasonCode: 'VANILLA_RESPAWNED', eventSequence: 5,
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.deepEqual(errors, [], 'old physical completion cannot error the replacement dead turn');
		assert.equal(registry.get('agent-a')?.state, DynamicAgentState.DEAD);
	} finally { await coordinator.stop(); }
});

test('death suspends the active program and asks the same selected model for a coordinate-free respawn program', async () => {
	const run = await start();
	try {
		run.planner.requestPlan = async (request) => {
			run.planner.requests.push(request);
			return request.input.includes('fell from a high place')
				? { summary: 'Respawn.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();' }
				: { summary: 'Wait.', directive: 'replace', source: SOURCE };
		};
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1,
		} });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const stale = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'dead', goalRevision: 2, updatedAtEpochMs: 2,
			death: DEATH,
		} });
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.DEAD);
		await eventually(() => run.bridge.sent.some((message) => message.payload.actionType === 'respawn'));
		const respawn = run.bridge.sent.find((message) => message.payload.actionType === 'respawn');
		assert.deepEqual(respawn.payload.arguments, {});
		assert.equal(respawn.payload.provenance.model, 'gpt-5.6-sol');
		assert.equal(run.planner.requests.at(-1).agentId, 'agent-a');
		assert.match(run.planner.requests.at(-1).input, /fell from a high place/);
		assert.match(run.planner.requests.at(-1).input, /"respawnDimensionId":"minecraft:overworld"/);
		assert.match(run.planner.requests.at(-1).input, /"respawnYaw":37\.5/);
		assert.match(run.planner.requests.at(-1).input, /"respawnForced":true/);
		assert.match(run.planner.requests.at(-1).input, /"gameMode":"spectator"/);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 1, actionId: stale.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2,
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.registry.get('agent-a')?.state, DynamicAgentState.DEAD, 'stale pre-death action cannot resume the suspended program');
	} finally { await run.coordinator.stop(); }
});

test('respawn success is consumed before lifecycle control without a stale-result error', async () => {
	const run = await start();
	const errors = [];
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	try {
		run.planner.requestPlan = async (request) => {
			run.planner.requests.push(request);
			return request.input.includes('player_death')
				? { summary: 'Respawn.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();' }
				: { summary: 'Wait.', directive: 'replace', source: SOURCE };
		};
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'dead', goalRevision: 2, updatedAtEpochMs: 2, death: DEATH } });
		await eventually(() => run.bridge.sent.some((message) => message.payload?.actionType === 'respawn'));
		const command = run.bridge.sent.find((message) => message.payload?.actionType === 'respawn');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 2, actionId: command.payload.actionId, commandId: command.payload.actionId,
			actionType: 'respawn', state: 'SUCCEEDED', reasonCode: 'VANILLA_RESPAWNED', message: '', elapsedMs: 1, observedAtEpochMs: 3,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'respawn', goalRevision: 3, updatedAtEpochMs: 3 } });
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.PAUSED);
		assert.deepEqual(errors, []);
	} finally { await run.coordinator.stop(); }
});

test('throwing telemetry clocks cannot block action results or disconnect cleanup', async () => {
	const run = await start({ controlNow: () => { throw new Error('clock unavailable'); } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 1);
		const first = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 2);
		run.bridge.emit('disconnected');
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.DISCONNECTED);
		assert.equal(run.planner.interruptions.includes('agent-a'), true, 'disconnect still interrupts the live agent');
	} finally { await run.coordinator.stop(); }
});

test('throwing quiet-provider retry clocks leave the next observation eligible', async () => {
	const run = await start({ controlNow: () => { throw new Error('clock unavailable'); } });
	const runtimeErrors = [];
	run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	try {
		run.planner.requestPlan = async (request) => {
			run.planner.requests.push(request);
			throw Object.assign(new Error('provider emitted no final message'), { code: 'MISSING_FINAL_MESSAGE' });
		};
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Retry.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 2);
		assert.deepEqual(runtimeErrors, [], 'quiet provider handling stays contained when its retry clock is unavailable');
	} finally { await run.coordinator.stop(); }
});
