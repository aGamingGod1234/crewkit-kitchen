import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';

import { MultiplexedServerBridge, ProtocolV2Error, validateProtocolV2Envelope, validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const SECRET = 's'.repeat(32);

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
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', payload), payload);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, prompt: 'secret' }), /field/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, supportedProfileCount: 2 }), /inconsistent/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, profiles: [{ ...payload.profiles[0], provider: 7 }] }), /nonblank/i);
});

function registeredRecord(agentId = 'agent-a') {
	return {
		schemaVersion: 1,
		agentId,
		model: 'gpt-5.6-sol',
		reasoningEffort: 'high',
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
			dangerousFall: false,
			lastAttacker: {
				uuid: '00000000-0000-0000-0000-000000000001',
				type: 'minecraft:zombie',
				distance: 3.25,
				health: 12,
			},
			effects: [{ effectId: 'minecraft:speed', amplifier: 1, duration: 120 }],
		},
		inventory: {
			items: [
				{ itemId: 'minecraft:iron_chestplate', count: 1, damage: 0, maxDamage: 240, slot: 'chest' },
				{ itemId: 'minecraft:bread', count: 4, damage: 0, maxDamage: 0, slot: 2, hotbar: true },
			],
			selectedItem: 'minecraft:bread',
		},
		entities: [{
			uuid: '00000000-0000-0000-0000-000000000001',
			type: 'minecraft:zombie',
			name: 'Zombie',
			distance: 3.25,
			position: { x: 12, y: 64, z: -2 },
			hostile: true,
			health: 12,
			maxHealth: 20,
		}, {
			uuid: '00000000-0000-0000-0000-000000000002',
			type: 'minecraft:player',
			name: 'Operator',
			distance: 5,
			position: { x: 15, y: 64, z: -3 },
			hostile: false,
			health: 20,
			maxHealth: 20,
			isPlayer: true,
			gameMode: 'creative',
			canBeHarmed: false,
		}],
		blocks: [{ x: 11, y: 64, z: -3, blockId: 'minecraft:oak_log' }],
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
	assert.equal(snapshot.registry[0].gameMode, 'survival');
	assert.deepEqual(bridge.knownAgentIds, ['agent-a']);
	await bridge.send('planning_state', 'agent-a', { goalRevision: 4, state: 'PLANNING' });
	assert.equal(JSON.parse(socket.writes.at(-1)).agentId, 'agent-a');
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
		goalRevision: 8,
		actionId: 'action-1',
		summary: 'Wait.',
		goalStatus: 'in_progress',
		action: { type: 'wait', durationMs: 25 },
	}), (error) => error.code === 'STALE_GOAL_REVISION');
	assert.equal(socket.writes.length, 1);
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

test('strict payload validators accept every current wire shape and reject unknown fields', () => {
	const catalog = { refreshedAtEpochMs: 1, models: [{ id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', displayName: 'GPT 5.6 Sol', reasoningEfforts: ['high'], serviceTiers: ['fast'] }] };
	const messages = [
		['hello', { secret: SECRET }],
		['hello_ack', { replyTo: 'coordinator-1', authenticated: true, registry: [registeredRecord()] }],
		['catalog_request', {}],
		['catalog_snapshot', catalog],
		['agent_registered', registeredRecord()],
		['agent_removed', { goalRevision: 1 }],
		['goal_control', { operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: 'Build shelter.' }],
		['observation', { goalRevision: 1, observedAtEpochMs: 2, ready: false, status: 'ENTITY_UNAVAILABLE' }],
		['action_progress', { goalRevision: 1, actionId: 'action-1', state: 'RUNNING', progress: 0.5 }],
		['action_result', actionResult('action-1', 1)],
		['agent_ready', { goalRevision: 1, reconciled: true }],
		['planning_state', { goalRevision: 1, state: 'PLANNING' }],
		['action_command', { goalRevision: 1, actionId: 'action-1', summary: 'Wait.', goalStatus: 'in_progress', action: { type: 'wait', durationMs: 25 } }],
		['agent_error', { goalRevision: 1, code: 'FAILED', message: 'Planner failed.' }],
		['heartbeat', {}],
		['shutdown', { reason: 'server_stopping' }],
	];
	for (const [type, payload] of messages) assert.doesNotThrow(() => validateProtocolV2Payload(type, payload), type);
	assert.deepEqual(validateProtocolV2Payload('agent_ready', { goalRevision: 2 }), { goalRevision: 2 });
	for (const [type, payload] of messages) assert.throws(() => validateProtocolV2Payload(type, { ...payload, unexpected: true }), (error) => error.code === 'INVALID_PAYLOAD_FIELD', type);
	assert.throws(() => validateProtocolV2Payload('hello_ack', { replyTo: 'x', authenticated: true, registry: Array(1_025).fill(registeredRecord()) }), /at most 1024/);
});

test('protocol v2 validates raw transaction arguments before normalizing action commands', () => {
	const validTransfer = {
		x: 1, y: 64, z: -2,
		sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
		count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
	};
	const normalized = validateProtocolV2Payload('action_command', {
		goalRevision: 4,
		actionId: 'action-transaction-1',
		actionType: 'transfer_container',
		arguments: validTransfer,
	});
	assert.deepEqual(normalized.arguments, validTransfer);
	assert.throws(
		() => validateProtocolV2Payload('action_command', {
			goalRevision: 4,
			actionId: 'action-transaction-2',
			actionType: 'transfer_container',
			arguments: { ...validTransfer, extra: true },
		}),
		(error) => error.code === 'INVALID_ACTION' && /Unknown/.test(error.message),
	);
});

test('accepts the exact rich ready observation emitted by ServerObservationCollector', () => {
	const payload = readyServerObservation();
	const normalized = validateProtocolV2Payload('observation', payload);
	assert.equal(normalized.player.foodLevel, 14);
	assert.equal(normalized.player.lastAttacker.type, 'minecraft:zombie');
	assert.equal(normalized.player.effects[0].duration, 120);
	assert.equal(normalized.inventory.items[0].slot, 'chest');
	assert.equal(normalized.inventory.items[1].slot, 2);
	assert.equal(normalized.inventory.selectedItem, 'minecraft:bread');
	assert.equal(normalized.entities[1].canBeHarmed, false);
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
