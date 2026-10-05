import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';

import { createBridgeAuthenticationProof, MultiplexedServerBridge } from '../src/protocol-v2.mjs';

const SECRET = 's'.repeat(32);
const PROVENANCE = {
	provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
	programId: 'program-1', programVersion: 1, sourceStepId: 'step-1', eventSequence: 1,
};
let serverSequence = 0;
function serverEnvelope(type, agentId, payload) {
	return { protocolVersion: 2, serverInstanceId: 'server-instance', agentId, type, messageId: `server-${++serverSequence}`, payload };
}

class FakeSocket extends EventEmitter {
	writes = [];
	writable = true;
	destroyed = false;
	write(encoded) {
		const envelope = JSON.parse(encoded);
		this.writes.push(envelope);
		if (envelope.type === 'auth_challenge') {
			const clientNonce = envelope.payload.clientNonce;
			const serverNonce = Buffer.alloc(32, 7).toString('base64url');
			this.receive('auth_response', 'server', {
				replyTo: envelope.messageId, clientNonce, serverNonce,
				proof: createBridgeAuthenticationProof(SECRET, 'server', { clientNonce, serverNonce, serverInstanceId: 'server-instance' }),
			});
		}
		return this.writable;
	}
	receive(type, agentId, payload) { this.emit('data', `${JSON.stringify(serverEnvelope(type, agentId, payload))}\n`); }
	drain(writable = true) { this.writable = writable; this.emit('drain'); }
	destroy() { if (!this.destroyed) { this.destroyed = true; this.emit('close'); } }
}

async function setup(t, agentCount = 1, config = {}) {
	const socket = new FakeSocket();
	const agents = Array.from({ length: agentCount }, (_, index) => `agent-${index}`);
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, ...config }, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 1,
	});
	const errors = [];
	bridge.on('protocolError', (error) => errors.push(error));
	t.after(() => assert.deepEqual(errors, []));
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const ready = once(bridge, 'ready');
	socket.receive('hello_ack', 'server', {
		replyTo: socket.writes.find(({ type }) => type === 'hello').messageId, authenticated: true,
		registry: agents.map((agentId) => ({
			schemaVersion: 1, agentId, model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast',
			gameMode: 'survival', skinVariant: 'teal', state: 'IDLE', goalRevision: 0, queue: [], createdAtEpochMs: 1, updatedAtEpochMs: 1,
		})),
	});
	assert.deepEqual(errors, []);
	await ready;
	socket.writes = [];
	return { bridge, socket, agents };
}

function inspect(bridge, agentId, requestId, goalRevision = 1) {
	return bridge.send('inspection_request', agentId, { goalRevision, requestId, query: { section: 'events', afterSequence: 0, limit: 1 } });
}
function command(bridge, agentId, actionId, goalRevision = 1) {
	return bridge.send('action_command', agentId, { goalRevision, actionId, traceId: `trace-${actionId}`, actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE });
}
function cancel(bridge, agentId, actionId, goalRevision = 1) {
	return bridge.send('action_cancel', agentId, { goalRevision, actionId });
}
function block(bridge, socket) {
	socket.writable = false;
	return bridge.send('heartbeat', 'server', {});
}
const tick = () => new Promise((resolve) => setImmediate(resolve));

test('32 queued inspections cannot reject or delay command-before-cancel behind their backlog', async (t) => {
	const { bridge, socket, agents: [agent] } = await setup(t);
	const blocked = block(bridge, socket);
	const inspections = Array.from({ length: 32 }, (_, index) => inspect(bridge, agent, `inspect-${index}`));
	await assert.rejects(inspect(bridge, agent, 'overflow'), { code: 'AGENT_BACKPRESSURE' });
	const action = command(bridge, agent, 'action-1');
	const cancellation = cancel(bridge, agent, 'action-1');
	let cancellationSettled = false;
	void cancellation.then(() => { cancellationSettled = true; });
	assert.deepEqual(socket.writes.map(({ type }) => type), ['heartbeat']);
	// Each false write consumes the frame but must await the next drain.
	socket.drain(false);
	await blocked;
	assert.equal(socket.writes.at(-1).type, 'action_command');
	socket.drain(false);
	await action;
	assert.equal(socket.writes.at(-1).type, 'action_cancel');
	await tick();
	assert.equal(cancellationSettled, false);
	socket.drain();
	await Promise.all([cancellation, ...inspections]);
	assert.equal(cancellationSettled, true);
	assert.deepEqual(socket.writes.slice(0, 3).map(({ type }) => type), ['heartbeat', 'action_command', 'action_cancel']);
	assert.deepEqual(socket.writes.slice(3).map(({ payload }) => payload.requestId), Array.from({ length: 32 }, (_, index) => `inspect-${index}`));
});

for (const agentCount of [1, 8, 16]) {
	test(`${agentCount} agents get round-robin service and inspections progress during control traffic`, async (t) => {
		const { bridge, socket, agents } = await setup(t, agentCount);
		const pending = [block(bridge, socket)];
		for (const agent of agents) {
			for (let index = 0; index < 4; index += 1) pending.push(inspect(bridge, agent, `${agent}-${index}`));
			pending.push(bridge.send('agent_ready', agent, { goalRevision: 1 }));
			pending.push(command(bridge, agent, `action-${agent}`));
			pending.push(cancel(bridge, agent, `action-${agent}`));
			pending.push(bridge.send('planning_state', agent, { goalRevision: 1, state: 'PLANNING' }));
			pending.push(bridge.send('planning_state', agent, { goalRevision: 1, state: 'ACTING' }));
		}
		socket.drain();
		await Promise.all(pending);
		const sent = socket.writes.slice(1);
		assert.equal(sent[0].type, 'agent_ready');
		assert.equal(sent[4].type, 'inspection_request', 'an inspection gets service after four controls');
		for (const lane of [sent.filter(({ type }) => type !== 'inspection_request'), sent.filter(({ type }) => type === 'inspection_request')]) {
			for (let index = 0; index < lane.length; index += agentCount) {
				assert.deepEqual(lane.slice(index, index + agentCount).map(({ agentId }) => agentId), agents);
			}
		}
		for (const agent of agents) {
			assert.deepEqual(sent.filter(({ agentId, type }) => agentId === agent && type !== 'inspection_request').map(({ type }) => type),
				['agent_ready', 'action_command', 'action_cancel', 'planning_state', 'planning_state']);
			assert.deepEqual(sent.filter(({ agentId, type }) => agentId === agent && type === 'inspection_request').map(({ payload }) => payload.requestId),
				Array.from({ length: 4 }, (_, index) => `${agent}-${index}`));
		}
	});
}

test('per-agent control reserve is bounded and rejects without evicting accepted inspections', async (t) => {
	const { bridge, socket, agents: [agent] } = await setup(t);
	const pending = [block(bridge, socket), ...Array.from({ length: 32 }, (_, index) => inspect(bridge, agent, `inspect-${index}`))];
	for (let index = 0; index < 4; index += 1) pending.push(cancel(bridge, agent, `cancel-${index}`));
	await assert.rejects(cancel(bridge, agent, 'cancel-overflow'), { code: 'AGENT_BACKPRESSURE' });
	await assert.rejects(bridge.send('planning_state', agent, { goalRevision: 1, state: 'PLANNING' }), { code: 'AGENT_BACKPRESSURE' });
	socket.drain();
	await Promise.all(pending);
	assert.equal(socket.writes.length, 37);
	assert.equal(socket.writes.some(({ payload }) => payload.actionId === 'cancel-overflow'), false);
	// Draining releases both ordinary and reserved capacity.
	await inspect(bridge, agent, 'after-drain');
});

test('connection capacity plus reserve stays bounded across agents and disconnect rejects all admitted work', async (t) => {
	const { bridge, socket, agents } = await setup(t, 16, { connectionQueueCap: 64 });
	const pending = [block(bridge, socket)];
	for (const agent of agents) for (let index = 0; index < 4; index += 1) pending.push(inspect(bridge, agent, `${agent}-${index}`));
	await assert.rejects(inspect(bridge, agents[0], 'overflow'), { code: 'CONNECTION_BACKPRESSURE' });
	for (const agent of agents) for (let index = 0; index < 4; index += 1) pending.push(cancel(bridge, agent, `${agent}-${index}`));
	await assert.rejects(cancel(bridge, agents[0], 'overflow'), { code: 'CONNECTION_BACKPRESSURE' });
	const outcomes = Promise.allSettled(pending);
	socket.destroy();
	const results = await outcomes;
	assert.equal(results.length, 129, '64 ordinary + 64 reserved + one already-written frame');
	assert.equal(results.every(({ status, reason }) => status === 'rejected' && reason.code === 'BRIDGE_DISCONNECTED'), true);
	assert.deepEqual(socket.writes.map(({ type }) => type), ['heartbeat']);
});

test('revision replacement rejects both queued lanes without changing the frame already written', async (t) => {
	const { bridge, socket, agents: [agent] } = await setup(t);
	socket.writable = false;
	const written = inspect(bridge, agent, 'written-old');
	const stale = [inspect(bridge, agent, 'queued-old'), bridge.send('agent_ready', agent, { goalRevision: 1 }), command(bridge, agent, 'old'), cancel(bridge, agent, 'old')];
	const outcomes = Promise.allSettled(stale);
	socket.receive('goal_control', agent, { operation: 'stop', goalRevision: 2, updatedAtEpochMs: 10 });
	assert.equal((await outcomes).every(({ status, reason }) => status === 'rejected' && reason.code === 'STALE_GOAL_REVISION'), true);
	await assert.rejects(cancel(bridge, agent, 'old'), { code: 'STALE_GOAL_REVISION' });
	const fresh = [inspect(bridge, agent, 'fresh', 2), bridge.send('agent_ready', agent, { goalRevision: 2 }), command(bridge, agent, 'new', 2), cancel(bridge, agent, 'new', 2)];
	socket.drain();
	await Promise.all([written, ...fresh]);
	assert.deepEqual(socket.writes.map(({ type }) => type), ['inspection_request', 'agent_ready', 'action_command', 'action_cancel', 'inspection_request']);
	assert.equal(socket.writes[0].payload.goalRevision, 1);
	assert.equal(socket.writes.slice(1).every(({ payload }) => payload.goalRevision === 2), true);
});

test('terminal ACK uses reserved capacity only after explicit application acknowledgement and resolves on drain', async (t) => {
	const { bridge, socket, agents: [agent] } = await setup(t);
	await command(bridge, agent, 'terminal');
	const result = { traceId: 'trace-terminal', goalRevision: 1, actionId: 'terminal', commandId: 'terminal', actionType: 'wait', state: 'CANCELLED', reasonCode: 'CANCELLED', message: '', elapsedMs: 10, observedAtEpochMs: 20 };
	const delivered = once(bridge, 'action_result');
	socket.receive('action_result', agent, result);
	await delivered;
	const pending = [block(bridge, socket), ...Array.from({ length: 32 }, (_, index) => inspect(bridge, agent, `inspect-${index}`))];
	assert.equal(socket.writes.some(({ type }) => type === 'action_result_ack'), false);
	const acknowledged = bridge.acknowledgeActionResult(agent, result);
	let settled = false;
	void acknowledged.then(() => { settled = true; });
	socket.drain(false);
	assert.equal(socket.writes.at(-1).type, 'action_result_ack');
	await tick();
	assert.equal(settled, false);
	socket.drain();
	await Promise.all([...pending, acknowledged]);
	assert.equal(settled, true);
});
