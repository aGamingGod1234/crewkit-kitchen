import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';

import { MultiplexedServerBridge, ProtocolV2Error, validateProtocolV2Envelope, validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { completionContractFingerprint } from '../src/goal-contract.mjs';

const SECRET = 's'.repeat(32);
const LAUNCH_ID = '00000000-0000-0000-0000-000000000123';
const TRACE_ID = 'trace-wire-1';
const DESIRED_OAK_STAIRS_STATE = 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const PROVENANCE = Object.freeze({
	provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
	programId: 'program-1-1', programVersion: 1, sourceStepId: 'step-80-126', eventSequence: 4,
});

class FakeSocket extends EventEmitter {
	writes = [];
	destroyed = false;
	writable = true;

	write(value) {
		this.writes.push(String(value));
		return this.writable;
	}

	setNoDelay() {}

	destroy() {
		if (this.destroyed) return;
		this.destroyed = true;
		this.emit('close');
	}
}

function serverEnvelope(type, agentId, messageId, payload = {}) {
	return { protocolVersion: 2, serverInstanceId: 'server-instance', agentId, type, messageId, payload };
}

test('optional launch identity fences supervised coordinator authentication', async () => {
	assert.deepEqual(validateProtocolV2Payload('hello', { secret: SECRET }), { secret: SECRET });
	assert.deepEqual(validateProtocolV2Payload('hello', { secret: SECRET, launchId: LAUNCH_ID }), {
		secret: SECRET,
		launchId: LAUNCH_ID,
	});

	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, launchId: LAUNCH_ID }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	assert.equal(hello.payload.launchId, LAUNCH_ID);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-launch-ack', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [],
		launchId: LAUNCH_ID,
	}))}\n`);
	assert.equal((await ready)[0].launchId, LAUNCH_ID);
	bridge.stop();

	const staleSocket = new FakeSocket();
	const staleBridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, launchId: LAUNCH_ID }, {
		socketFactory: () => staleSocket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	staleBridge.start();
	staleSocket.emit('connect');
	const staleHello = JSON.parse(staleSocket.writes[0]);
	const rejected = once(staleBridge, 'protocolError');
	staleSocket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-stale-launch-ack', {
		replyTo: staleHello.messageId,
		authenticated: true,
		registry: [],
		launchId: '00000000-0000-0000-0000-000000000999',
	}))}\n`);
	assert.equal((await rejected)[0].code, 'LAUNCH_ID_MISMATCH');
	staleBridge.stop();
});

test('coordinator status is strict, bounded, and excludes private planner data', () => {
	const payload = {
		reconciled: true,
		profiles: [{ agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' }],
		supportedProfileCount: 1,
		rosterReadyCount: 1,
		rosterCount: 1,
		scheduler: { active: 1, pending: 0, maxConcurrent: 4, maxPending: 12, warning: false },
		circuits: [{ provider: 'codex', model: 'gpt-5.6-sol', operation: 'decide', count: 2, p50Ms: 100, p95Ms: 200, failureRate: 0, circuit: 'closed' }],
	};
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', payload), { ...payload, latencies: [] });
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: { ...payload.scheduler, active: 3, target: 2 },
	}).scheduler, { ...payload.scheduler, active: 3, target: 2 });
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: {
			...payload.scheduler,
			active: 5,
			mode: 'adaptive',
			configuredTarget: 4,
			target: 5,
			minConcurrency: 4,
			maxConcurrency: 16,
		},
	}).scheduler.active, 5);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: { ...payload.scheduler, active: 5 },
	}), /counts exceed capacity/i);
	const latency = { operation: 'observation_to_plan', count: 8, p50Ms: 25.25, p95Ms: 80.75 };
	assert.deepEqual(
		validateProtocolV2Payload('coordinator_status', { ...payload, latencies: [latency] }).latencies,
		[latency],
	);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, prompt: 'secret' }), /field/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, supportedProfileCount: 2 }), /inconsistent/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, profiles: [{ ...payload.profiles[0], provider: 7 }] }), /nonblank/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', {
		...payload,
		latencies: [{ ...latency, privatePrompt: 'secret' }],
	}), /field/i);
});

test('protocol v2 carries a revision/profile/trace-bound factual goal completion request', () => {
	const completionContract = { goalRevision: 4, predicates: [{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }] };
	const payload = {
		goalRevision: 4,
		completionContract,
		traceId: TRACE_ID,
		profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' },
		contractHash: completionContractFingerprint(completionContract),
	};
	assert.deepEqual(validateProtocolV2Payload('goal_completed', payload), payload);
	assert.throws(
		() => validateProtocolV2Payload('goal_completed', { goalRevision: 4 }),
		error => error.code === 'CONTRACT_REQUIRED',
		'goal completion cannot fall back to a revision-only proof',
	);
	assert.throws(() => validateProtocolV2Payload('goal_completed', { ...payload, contractHash: 'sha256:wrong' }), /contractHash/i);
	const completionResult = {
		goalRevision: 4, traceId: TRACE_ID, contractHash: payload.contractHash, verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [
			{ predicateIndex: 0, type: 'inventory_min', satisfied: false, observedValue: '0' },
			{ predicateIndex: 1, type: 'position_within', satisfied: true, observedValue: '1.25' },
		],
	};
	assert.deepEqual(validateProtocolV2Payload('goal_completion_result', completionResult), completionResult);
	assert.throws(
		() => validateProtocolV2Payload('goal_completion_result', { ...completionResult, facts: Array.from({ length: 17 }, () => completionResult.facts[0]) }),
		/facts/i,
	);
});

test('protocol v2 carries one acknowledged conversation wake transaction', () => {
	const event = {
		sequence: 7, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: 'Can you respond?', goalRevision: 3, observedAtEpochMs: 20,
	};
	const control = { operation: 'start', goalRevision: 4, updatedAtEpochMs: 21, goal: 'Respond to the player.' };
	const payload = { transactionId: 'wake-00000001', event, control };
	assert.deepEqual(validateProtocolV2Payload('conversation_wake', payload), payload);
	assert.deepEqual(
		validateProtocolV2Payload('conversation_wake_ack', { transactionId: payload.transactionId, goalRevision: 4 }),
		{ transactionId: payload.transactionId, goalRevision: 4 },
	);
	assert.throws(
		() => validateProtocolV2Payload('conversation_wake', { ...payload, control: { ...control, goalRevision: 5 } }),
		/revision/i,
		'composite wake revisions must be consecutive',
	);
	assert.throws(
		() => validateProtocolV2Envelope(serverEnvelope('conversation_wake', 'agent-b', 'wake-1', payload), { direction: 'server_to_coordinator' }),
		/recipientId|scope/i,
		'the nested conversation recipient must match the envelope agent',
	);
});

test('protocol v2 requires immutable provenance on every action command form', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	};
	const normalized = validateProtocolV2Payload('action_command', payload);
	assert.deepEqual(normalized.provenance, PROVENANCE);
	assert.throws(() => { normalized.provenance.programId = 'forged'; }, TypeError);
	assert.equal(PROVENANCE.programId, 'program-1-1');
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...PROVENANCE, traceId: 'trace-other' } }),
		(error) => error.code === 'INVALID_PAYLOAD' && /traceId/.test(error.message),
		'provenance trace IDs cannot diverge from the command trace',
	);
	assert.throws(() => validateProtocolV2Payload('action_command', { ...payload, provenance: undefined }), /provenance/);
	assert.throws(() => validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 },
	}), /provenance/);
	for (const alias of ['commandId', 'command', 'type', 'action']) {
		assert.throws(
			() => validateProtocolV2Payload('action_command', { ...payload, [alias]: alias === 'action' ? { type: 'wait' } : 'forged' }),
			(error) => error.code === 'INVALID_PAYLOAD_FIELD',
			`${alias} is not a canonical action command field`,
		);
	}
	const inheritedProvenance = Object.create(PROVENANCE);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: inheritedProvenance }),
		(error) => error.code === 'INVALID_FIELD',
		'custom/inherited provenance objects are rejected',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { durationMs: 25, type: 'fight_target' } }),
		(error) => error.code === 'INVALID_PAYLOAD_FIELD',
		'arguments cannot override the outer action type',
	);
	const sparse = []; sparse.length = 1;
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { durationMs: 25, extra: sparse } }),
		(error) => error.code === 'INVALID_PAYLOAD' || error.code === 'INVALID_ACTION',
		'sparse/custom arrays are rejected before schema normalization',
	);
});

test('protocol v2 carries a bounded watcher identity with the selected trace', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-watcher', actionType: 'wait', arguments: { durationMs: 25 },
		provenance: { ...PROVENANCE, traceId: TRACE_ID, watcherId: 'watcher-0' },
	};
	assert.equal(validateProtocolV2Payload('action_command', payload).provenance.watcherId, 'watcher-0');
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...payload.provenance, traceId: undefined } }),
		/traceId/i,
		'watcher provenance must select a trace',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...payload.provenance, watcherId: 'x'.repeat(129) } }),
		/watcherId/i,
	);
});

test('traced action commands, progress, and results round-trip one bounded trace ID', () => {
	const traceId = 'trace-wire-1';
	const command = validateProtocolV2Payload('action_command', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	});
	const progress = validateProtocolV2Payload('action_progress', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'RUNNING', progress: 0.5,
	});
	const result = validateProtocolV2Payload('action_result', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 10, observedAtEpochMs: 20,
	});
	assert.equal(command.traceId, traceId);
	assert.equal(progress.traceId, traceId);
	assert.equal(result.traceId, traceId);
	for (const type of ['action_command', 'action_progress', 'action_result']) {
		const payload = type === 'action_command' ? { traceId, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE }
			: type === 'action_progress' ? { traceId, goalRevision: 1, actionId: 'action-trace-1', state: 'RUNNING' }
			: { traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 10, observedAtEpochMs: 20 };
		assert.throws(() => validateProtocolV2Payload(type, { ...payload, traceId: '' }), /traceId/i, `${type} rejects blank trace IDs`);
		assert.throws(() => validateProtocolV2Payload(type, { ...payload, traceId: '🙂'.repeat(40) }), /traceId/i, `${type} rejects overlong UTF-8 trace IDs`);
	}
	assert.throws(() => validateProtocolV2Payload('action_command', {
		traceId: undefined, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	}), /traceId/i);
});

test('protocol v2 accepts only coordinate-free respawn arguments', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 7, actionId: 'respawn-1', actionType: 'respawn', arguments: {}, provenance: PROVENANCE,
	};
	assert.deepEqual(validateProtocolV2Payload('action_command', payload).arguments, {});
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { x: 1, y: 64, z: 1 } }),
		(error) => error.code === 'INVALID_ACTION',
		'respawn never accepts a model supplied position',
	);
});

test('protocol v2 accepts exact death facts only on dead lifecycle control', () => {
	const death = {
		cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 12.5, y: 64, z: -4.25,
		respawnDimensionId: 'minecraft:the_nether', respawnX: 4.5, respawnY: 31, respawnZ: -8.5,
		respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 17,
	};
	assert.deepEqual(
		validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18, death }).death,
		death,
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', {
			operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18,
			death: { ...death, respawnPitch: undefined },
		}),
		/missing|respawnPitch/i,
		'death facts require every captured respawn field in the bounded wire shape',
	);
	const noConfiguredRespawn = {
		...death,
		respawnDimensionId: null, respawnX: null, respawnY: null, respawnZ: null,
		respawnYaw: null, respawnPitch: null, respawnForced: null,
	};
	assert.deepEqual(
		validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18, death: noConfiguredRespawn }).death,
		noConfiguredRespawn,
		'absence of a configured vanilla respawn remains explicit without inventing a target',
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', {
			operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18,
			death: { ...death, respawnX: null },
		}),
		/present together/,
		'partial respawn facts are rejected instead of being completed locally',
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18 }),
		/requires death facts/,
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { operation: 'start', goalRevision: 8, updatedAtEpochMs: 18, goal: 'run', death }),
		/must not include death/,
	);
});

test('hello acknowledgement retains death facts for restart reconciliation', () => {
	const death = {
		cause: 'burned in lava', dimensionId: 'minecraft:the_nether', x: 4.5, y: 31, z: -8.5,
		respawnDimensionId: 'minecraft:overworld', respawnX: 10.5, respawnY: 65, respawnZ: -2.5,
		respawnYaw: 90, respawnPitch: 0, respawnForced: false, gameMode: 'survival', diedAtEpochMs: 23,
	};
	const dead = { ...registeredRecord(), state: 'DEAD', currentGoal: 'Escape the Nether.', goalRevision: 7, death };
	const payload = validateProtocolV2Payload('hello_ack', {
		replyTo: 'coordinator-1', authenticated: true, registry: [dead],
	});
	assert.deepEqual(payload.registry[0].death, death);
	assert.throws(
		() => validateProtocolV2Payload('hello_ack', {
			replyTo: 'coordinator-1', authenticated: true,
			registry: [{ ...dead, death: undefined }],
		}),
		/death facts/,
	);
});

function registeredRecord(agentId = 'agent-a') {
	return {
		schemaVersion: 1,
		agentId,
		model: 'gpt-5.6-sol',
		reasoningEffort: 'high',
		serviceTier: 'fast',
		gameMode: 'survival',
		skinVariant: 'teal',
		state: 'IDLE',
		goalRevision: 0,
		queue: [],
		createdAtEpochMs: 1,
		updatedAtEpochMs: 1,
	};
}

function actionResult(actionId, goalRevision = 4) {
	return {
		traceId: `trace-${actionId}`,
		goalRevision,
		actionId,
		commandId: actionId,
		actionType: 'wait',
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		message: '',
		elapsedMs: 10,
		observedAtEpochMs: 20,
	};
}

function readyServerObservation(goalRevision = 4) {
	return {
		goalRevision,
		observedAtEpochMs: 20,
		eventSequence: 7,
		attention: false,
		changedFacts: [],
		ready: true,
		status: 'PLANNING',
		position: { x: 10.5, y: 64, z: -3.5 },
		velocity: { x: 0, y: 0, z: 0 },
		view: { yaw: 90, pitch: 0 },
		player: {
			health: 18,
			maxHealth: 20,
			armor: 6,
			foodLevel: 14,
			saturation: 2.5,
			gameMode: 'survival',
			onGround: true,
			inWater: false,
			onFire: false,
			air: 300,
			maxAir: 300,
			suffocating: false,
			fallDistance: 0,
			lastAttacker: {
				uuid: '00000000-0000-0000-0000-000000000001',
				type: 'minecraft:zombie',
				distance: 3.25,
			},
			effects: [{ effectId: 'minecraft:speed', amplifier: 1, duration: 120 }],
		},
		interaction: {
			mainHandItemId: 'minecraft:bread',
			offHandItemId: 'minecraft:shield',
			usingItem: false,
			activeHand: 'none',
			useRemainingTicks: 0,
			attackCooldown: 1,
			input: {
				active: true, forward: 1, strafe: 0, jump: false, sneak: false, sprint: true,
				attack: false, use: false, yaw: 90, pitch: 0, selectedSlot: 2, hand: 'main_hand',
			},
			menu: {
				type: 'minecraft:inventory',
				cursor: { itemId: 'minecraft:air', count: 0 },
				slots: [{ slot: 0, itemId: 'minecraft:air', count: 0 }],
				capabilities: [],
			},
			rayTarget: { type: 'block', x: 11, y: 64, z: -3, face: 'north', blockId: 'minecraft:oak_log' },
		},
		inventory: {
			items: [
				{ itemId: 'minecraft:iron_chestplate', count: 1, damage: 0, maxDamage: 240, slot: 'chest', tags: ['#minecraft:trimmable_armor'] },
				{ itemId: 'minecraft:bread', count: 4, damage: 0, maxDamage: 0, slot: 2, hotbar: true, tags: ['#minecraft:food'] },
			],
			tagCounts: { '#minecraft:trimmable_armor': 1, '#minecraft:food': 4 },
			selectedItem: 'minecraft:bread',
		},
		entities: [{
			uuid: '00000000-0000-0000-0000-000000000001',
			type: 'minecraft:zombie',
			name: 'Zombie',
			distance: 3.25,
			tags: ['#minecraft:hostile'],
			position: { x: 12, y: 64, z: -2 },
		}, {
			uuid: '00000000-0000-0000-0000-000000000002',
			type: 'minecraft:player',
			name: 'Operator',
			distance: 5,
			position: { x: 15, y: 64, z: -3 },
			isPlayer: true,
		}, {
			uuid: '00000000-0000-0000-0000-000000000003',
			type: 'minecraft:item',
			name: 'Oak Log',
			distance: 1.5,
			position: { x: 10.5, y: 64, z: -2.5 },
			itemId: 'minecraft:oak_log',
			count: 1,
			tags: ['#minecraft:item'],
		}],
		blocks: [{
			x: 11, y: 64, z: -3, blockId: 'minecraft:oak_log', placeableFaces: ['up', 'north'], tags: ['#minecraft:logs'],
		}],
		nearbyContainers: [{
			x: 12,
			y: 64,
			z: -4,
			blockId: 'minecraft:chest',
			distance: 2.5,
			withinInteractionRange: true,
			capabilities: ['transfer_container'],
		}],
		world: {
			dimension: 'minecraft:overworld',
			gameTime: 200,
			dayTime: 200,
			raining: false,
			thundering: false,
		},
		currentAction: { active: false },
		lastResult: { present: false },
	};
}

test('protocol v2 envelope is strict and directional', () => {
	const message = serverEnvelope('catalog_request', 'server', 'server-1');
	assert.equal(validateProtocolV2Envelope(message, { direction: 'server_to_coordinator' }).protocolVersion, 2);
	assert.throws(
		() => validateProtocolV2Envelope({ ...message, unexpected: true }),
		(error) => error instanceof ProtocolV2Error && error.code === 'INVALID_FIELD',
	);
	assert.throws(
		() => validateProtocolV2Envelope(message, { direction: 'coordinator_to_server' }),
		(error) => error.code === 'INVALID_MESSAGE_TYPE',
	);
});

test('multiplexed bridge authenticates once and learns the complete registry snapshot', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	assert.equal(hello.type, 'hello');
	assert.equal(hello.payload.secret, SECRET);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [registeredRecord()],
	}))}\n`);
	const [snapshot] = await ready;
	assert.equal(snapshot.registry[0].reasoningEffort, 'high');
	assert.equal(snapshot.registry[0].serviceTier, 'fast');
	assert.equal(snapshot.registry[0].gameMode, 'survival');
	assert.deepEqual(bridge.knownAgentIds, ['agent-a']);
	await bridge.send('planning_state', 'agent-a', { goalRevision: 4, state: 'PLANNING' });
	assert.equal(JSON.parse(socket.writes.at(-1)).agentId, 'agent-a');
	bridge.stop();
});

test('unused coordinator wake requests are not part of protocol v2', () => {
	assert.throws(
		() => validateProtocolV2Payload('conversation_wake_request', { goalRevision: 1, kind: 'player_message' }),
		/unsupported protocol v2 payload type/i,
	);
});

test('multiplexed bridge audits validated detached inbound and outbound envelopes', async () => {
	const socket = new FakeSocket();
	const audit = [];
	let receivedObservation;
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		audit: (direction, envelope) => audit.push({ direction, envelope }),
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	bridge.start();
	bridge.on('observation', (envelope) => { receivedObservation = envelope; });
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	socket.emit('data', `${JSON.stringify(serverEnvelope('observation', 'agent-a', 'server-2', readyServerObservation(4)))}\n`);
	await bridge.send('agent_ready', 'agent-a', { goalRevision: 4 });
	await bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 4, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	});
	assert.deepEqual(audit.map(({ direction, envelope }) => [direction, envelope.messageId, envelope.type, envelope.agentId]), [
		['coordinator_to_server', 'coordinator-v2-1', 'hello', 'server'],
		['server_to_coordinator', 'server-1', 'hello_ack', 'server'],
		['server_to_coordinator', 'server-2', 'observation', 'agent-a'],
		['coordinator_to_server', 'coordinator-v2-2', 'agent_ready', 'agent-a'],
		['coordinator_to_server', 'coordinator-v2-3', 'action_command', 'agent-a'],
	]);
	assert.equal(JSON.parse(socket.writes[0]).payload.secret, SECRET);
	assert.equal(audit[0].envelope.payload.secret, '[REDACTED]');
	assert.doesNotMatch(JSON.stringify(audit), new RegExp(SECRET));
	audit[2].envelope.payload.position.x = 999;
	assert.equal(receivedObservation.payload.position.x, 10.5);
	bridge.stop();
});

test('audit callback failures never interrupt bridge delivery', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		audit: async () => { throw new Error('audit unavailable'); },
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	await bridge.send('agent_ready', 'agent-a', { goalRevision: 4 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'agent_ready');
	bridge.stop();
});

test('multiplexed bridge rejects stale revisions before writing', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 9,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	await assert.rejects(bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 8,
		actionId: 'action-1',
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	}), (error) => error.code === 'STALE_GOAL_REVISION');
	assert.equal(socket.writes.length, 1);
	bridge.stop();
});

test('an inbound lifecycle revision orders the following observation before async registry work', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 3,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [{ ...registeredRecord(), state: 'PAUSED', currentGoal: 'Walk east.', goalRevision: 3 }],
	}))}\n`);
	await ready;
	const received = [];
	bridge.on('goal_control', (message) => received.push(message.type));
	bridge.on('observation', (message) => received.push(message.type));
	const lifecycle = serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'resume', goalRevision: 4, updatedAtEpochMs: 10,
	});
	const observation = serverEnvelope('observation', 'agent-a', 'server-3', {
		goalRevision: 4, observedAtEpochMs: 11, ready: false, status: 'PLAYER_UNAVAILABLE',
	});
	socket.emit('data', `${JSON.stringify(lifecycle)}\n${JSON.stringify(observation)}\n`);
	assert.deepEqual(received, ['goal_control', 'observation']);
	assert.equal(socket.destroyed, false);
	bridge.stop();
});

test('multiplexed bridge bounds queued messages per agent while socket is backpressured', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, connectionQueueCap: 2, agentQueueCap: 1 }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	socket.writable = false;
	await bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const queued = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	await assert.rejects(bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' }), (error) => error.code === 'AGENT_BACKPRESSURE');
	socket.writable = true;
	socket.emit('drain');
	await queued;
	bridge.stop();
});

test('a newer lifecycle revision removes queued stale action commands under backpressure', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	t.after(() => bridge.stop());
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;

	socket.writable = false;
	await bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const staleCommand = bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 1,
		actionId: 'action-stale',
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	});
	let staleError = null;
	void staleCommand.catch((error) => { staleError = error; });
	socket.emit('data', `${JSON.stringify(serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'stop', goalRevision: 2, updatedAtEpochMs: 10,
	}))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(staleError?.code, 'STALE_GOAL_REVISION');
	socket.writable = true;
	socket.emit('drain');
	assert.equal(socket.writes.some((wire) => JSON.parse(wire).type === 'action_command'), false);
	bridge.stop();
});

test('a newer lifecycle revision removes queued stale agent readiness under backpressure', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	t.after(() => bridge.stop());
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;

	socket.writable = false;
	await bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const staleReady = bridge.send('agent_ready', 'agent-a', { goalRevision: 1 });
	let staleError = null;
	void staleReady.catch((error) => { staleError = error; });
	socket.emit('data', `${JSON.stringify(serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'stop', goalRevision: 2, updatedAtEpochMs: 10,
	}))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(staleError?.code, 'STALE_GOAL_REVISION');
	socket.writable = true;
	socket.emit('drain');
	assert.equal(socket.writes.some((wire) => JSON.parse(wire).type === 'agent_ready'), false);
});

test('strict payload validators accept every current wire shape and reject unknown fields', () => {
	const catalog = { refreshedAtEpochMs: 1, models: [{ id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', displayName: 'GPT 5.6 Sol', reasoningEfforts: ['high'], serviceTiers: ['fast'] }] };
	const completionContract = { goalRevision: 1, predicates: [{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }] };
	const messages = [
		['hello', { secret: SECRET }],
		['hello_ack', { replyTo: 'coordinator-1', authenticated: true, registry: [registeredRecord()] }],
		['catalog_request', {}],
		['catalog_snapshot', catalog],
		['agent_registered', registeredRecord()],
		['agent_removed', { goalRevision: 1 }],
		['goal_control', { operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: 'Build shelter.' }],
		['observation', { goalRevision: 1, observedAtEpochMs: 2, ready: false, status: 'ENTITY_UNAVAILABLE' }],
		['action_progress', { traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', state: 'RUNNING', progress: 0.5 }],
		['action_result', actionResult('action-1', 1)],
		['agent_ready', { goalRevision: 1, reconciled: true }],
		['planning_state', { goalRevision: 1, state: 'PLANNING' }],
		['goal_completed', { goalRevision: 1, completionContract, traceId: TRACE_ID, profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' }, contractHash: completionContractFingerprint(completionContract) }],
		['goal_completion_result', { goalRevision: 1, traceId: TRACE_ID, contractHash: completionContractFingerprint(completionContract), verified: false, reasonCode: 'PREDICATE_FAILED', facts: [] }],
		['action_command', { traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE }],
		['action_cancel', { goalRevision: 1, actionId: 'action-1' }],
		['agent_error', { goalRevision: 1, code: 'FAILED', message: 'Planner failed.' }],
		['heartbeat', {}],
		['shutdown', { reason: 'server_stopping' }],
	];
	for (const [type, payload] of messages) assert.doesNotThrow(() => validateProtocolV2Payload(type, payload), type);
	assert.deepEqual(validateProtocolV2Payload('agent_ready', { goalRevision: 2 }), { goalRevision: 2 });
	for (const [type, payload] of messages) assert.throws(() => validateProtocolV2Payload(type, { ...payload, unexpected: true }), (error) => error.code === 'INVALID_PAYLOAD_FIELD', type);
	assert.throws(() => validateProtocolV2Payload('hello_ack', { replyTo: 'x', authenticated: true, registry: Array(1_025).fill(registeredRecord()) }), /at most 1024/);
});

test('action cancellation requires an exact goal revision and action identity', () => {
	assert.deepEqual(
		validateProtocolV2Payload('action_cancel', { goalRevision: 4, actionId: 'action-9' }),
		{ goalRevision: 4, actionId: 'action-9' },
	);
	assert.throws(
		() => validateProtocolV2Payload('action_cancel', { goalRevision: 4 }),
		(error) => error.code === 'MISSING_FIELD',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_cancel', { goalRevision: 4, actionId: 'action-9', reason: 'danger' }),
		(error) => error.code === 'INVALID_PAYLOAD_FIELD',
	);
});

test('protocol v2 validates raw transaction arguments before normalizing action commands', () => {
	const validTransfer = {
		x: 1, y: 64, z: -2,
		sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
		count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
	};
	const normalized = validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 4,
		actionId: 'action-transaction-1',
		actionType: 'transfer_container',
		arguments: validTransfer,
		provenance: PROVENANCE,
	});
	assert.deepEqual(normalized.arguments, validTransfer);
	assert.throws(
		() => validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4,
			actionId: 'action-transaction-2',
			actionType: 'transfer_container',
			arguments: { ...validTransfer, extra: true },
			provenance: PROVENANCE,
		}),
		(error) => error.code === 'INVALID_ACTION' && /Unknown/.test(error.message),
	);
});

test('protocol v2 preserves nullable desired block state and defers block-id matching to execution', () => {
	const placeArguments = {
		x: 1, y: 64, z: -2, face: 'up', itemId: 'minecraft:oak_stairs', desiredState: DESIRED_OAK_STAIRS_STATE,
	};
	const normalized = validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 4,
		actionId: 'action-place-1',
		actionType: 'place_block',
		arguments: placeArguments,
		provenance: PROVENANCE,
	});
	assert.deepEqual(normalized.arguments, placeArguments);
	assert.equal(
		validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-2', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: null },
			provenance: PROVENANCE,
		}).arguments.desiredState,
		null,
	);
	assert.equal(
		validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-3', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: 'minecraft:stone[facing=north]' },
			provenance: PROVENANCE,
		}).arguments.desiredState,
		'minecraft:stone[facing=north]',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-4', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: 'x'.repeat(513) },
			provenance: PROVENANCE,
		}),
		(error) => error.code === 'INVALID_ACTION' && /512/.test(error.message),
	);
});

test('protocol v2 rejects retired high-level controller action types', () => {
	for (const actionType of ['build_sequence', 'pick_up_item', 'fight_target', 'flee_from', 'follow_entity', 'complete_goal']) {
		assert.throws(
			() => validateProtocolV2Payload('action_command', {
				traceId: TRACE_ID,
				goalRevision: 4, actionId: `retired-${actionType}`, actionType, arguments: {}, provenance: PROVENANCE,
			}),
			(error) => error.code === 'INVALID_ACTION' && /Unsupported action/.test(error.message),
		);
	}
});

test('accepts the exact rich ready observation emitted by ServerObservationCollector', () => {
	const payload = readyServerObservation();
	const normalized = validateProtocolV2Payload('observation', payload);
	assert.equal(normalized.eventSequence, 7);
	assert.equal(normalized.attention, false);
	assert.deepEqual(normalized.changedFacts, []);
	assert.equal(Object.hasOwn(normalized.player, 'dangerousFall'), false);
	assert.equal(normalized.player.foodLevel, 14);
	assert.equal(normalized.player.lastAttacker.type, 'minecraft:zombie');
	assert.equal(normalized.player.effects[0].duration, 120);
	assert.equal(normalized.interaction.input.sprint, true);
	assert.equal(normalized.interaction.rayTarget.blockId, 'minecraft:oak_log');
	assert.equal(normalized.inventory.items[0].slot, 'chest');
	assert.equal(normalized.inventory.items[1].slot, 2);
	assert.equal(normalized.inventory.selectedItem, 'minecraft:bread');
	assert.deepEqual(
		Object.keys(normalized.entities[1]).sort(),
		['distance', 'isPlayer', 'name', 'position', 'type', 'uuid'],
		'entity observations expose only visually available identity and position facts',
	);
	assert.equal(normalized.entities[2].itemId, 'minecraft:oak_log');
	assert.equal(normalized.entities[2].count, 1);
	assert.deepEqual(normalized.blocks[0].placeableFaces, ['up', 'north']);
	assert.deepEqual(normalized.nearbyContainers[0].capabilities, ['transfer_container']);
});

test('ready observation requires factual delta metadata and rejects decision labels', () => {
	const payload = readyServerObservation();
	const { eventSequence: _eventSequence, ...withoutEventSequence } = payload;
	assert.throws(() => validateProtocolV2Payload('observation', withoutEventSequence), /eventSequence/i);
	assert.throws(() => validateProtocolV2Payload('observation', { ...payload, changedFacts: ['danger'] }), /changedFacts/i);
	assert.deepEqual(
		validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts: ['player.health', 'entities.00000000-0000-0000-0000-000000000001'] }).changedFacts,
		['player.health', 'entities.00000000-0000-0000-0000-000000000001'],
	);
});

test('ready observation accepts bounded factual aggregate paths at maximum entity and block churn', () => {
	const payload = readyServerObservation();
	const changedFacts = [
		...Array.from({ length: 64 }, (_value, index) => `entities.00000000-0000-0000-0000-${String(index).padStart(12, '0')}`),
		...Array.from({ length: 128 }, (_value, index) => `blocks.${index},64,0`),
	];
	assert.equal(validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts }).changedFacts.length, 192);
	assert.deepEqual(
		validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts: ['entities', 'blocks'] }).changedFacts,
		['entities', 'blocks'],
	);
});

test('delivers the rich server observation without tearing down the authenticated bridge', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [registeredRecord()],
	}))}\n`);
	await ready;
	const delivered = once(bridge, 'observation');
	socket.emit('data', `${JSON.stringify(serverEnvelope(
		'observation',
		'agent-a',
		'server-observation-1',
		readyServerObservation(),
	))}\n`);
	const [message] = await delivered;
	assert.equal(message.payload.player.foodLevel, 14);
	assert.equal(socket.destroyed, false);
	bridge.stop();
});

test('valid agent_removed is delivered before its identity is removed from the bridge registry', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, { socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 });
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	const removed = once(bridge, 'agent_removed');
	socket.emit('data', `${JSON.stringify(serverEnvelope('agent_removed', 'agent-a', 'server-2', { goalRevision: 4 }))}\n`);
	const [message] = await removed;
	assert.equal(message.agentId, 'agent-a');
	assert.deepEqual(bridge.knownAgentIds, []);
	bridge.stop();
});

test('malformed action results fail before terminal-result tracking or delivery', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, { socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 });
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	let delivered = false;
	bridge.on('action_result', () => { delivered = true; });
	const failed = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-2', { ...actionResult('action-1'), unexpected: true }))}\n`);
	const [error] = await failed;
	assert.equal(error.code, 'INVALID_PAYLOAD_FIELD');
	assert.equal(delivered, false);
});

test('validates strict targeted conversation events with Unicode code-point limits', () => {
	const payload = {
		sequence: 18,
		kind: 'agent_message',
		sourceId: 'agent-source',
		recipientId: 'agent-a',
		scope: 'direct',
		text: '\ud83d\ude80'.repeat(512),
		goalRevision: 4,
		observedAtEpochMs: 1_787_184_000_000,
	};
	assert.deepEqual(validateProtocolV2Payload('conversation_event', payload), payload);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, text: '\ud83d\ude80'.repeat(513) }), /512 code points/);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, extra: true }), /Unknown/);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, sequence: -1 }), /sequence/);
	assert.throws(() => validateProtocolV2Envelope(serverEnvelope('conversation_event', 'agent-a', 'server-2', { ...payload, recipientId: 'agent-b' }), { direction: 'server_to_coordinator' }), /recipientId/);
});

test('delivers a conversation event once through an authenticated bridge', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge(
		{ port: 25570, secret: SECRET },
		{ socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 },
	);
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	const payload = {
		sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: 'Meet at spawn.', goalRevision: 4, observedAtEpochMs: 1_787_184_000_000,
	};
	const delivered = once(bridge, 'conversation_event');
	socket.emit('data', `${JSON.stringify(serverEnvelope('conversation_event', 'agent-a', 'server-2', payload))}\n`);
	const [message] = await delivered;
	assert.deepEqual(message.payload, payload);
	bridge.stop();
});

test('authenticated bridge accepts same-revision conversation wake replay and its acknowledgement', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [{ ...registeredRecord(), state: 'STARTING', currentGoal: 'Respond.', goalRevision: 1 }],
	}))}\n`);
	await ready;
	const payload = {
		transactionId: 'wake-replay-1',
		event: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Hello?', goalRevision: 0, observedAtEpochMs: 10,
		},
		control: { operation: 'start', goalRevision: 1, updatedAtEpochMs: 11, goal: 'Respond.' },
	};
	const delivered = once(bridge, 'conversation_wake');
	socket.emit('data', `${JSON.stringify(serverEnvelope('conversation_wake', 'agent-a', 'server-2', payload))}\n`);
	assert.deepEqual((await delivered)[0].payload, payload);
	await bridge.send('conversation_wake_ack', 'agent-a', { transactionId: payload.transactionId, goalRevision: 1 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'conversation_wake_ack');
	assert.equal(socket.destroyed, false);
	bridge.stop();
});


test('catalog snapshots carry Cursor Composer and Grok profiles', () => {
	const model = {
		provider: 'cursor', id: 'cursor:composer-2.5', model: 'composer-2.5', displayName: 'Composer 2.5',
		reasoningEfforts: ['low', 'high'], serviceTiers: ['priority', 'fast'],
	};
	assert.deepEqual(
		validateProtocolV2Payload('catalog_snapshot', { refreshedAtEpochMs: 1, models: [model] }).models,
		[model],
	);
});
