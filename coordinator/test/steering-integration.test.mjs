import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AgentRegistry } from '../src/agent-registry.mjs';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';
import { createBridgeAuthenticationProof, MultiplexedServerBridge, validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { parseGoalSpecRequest } from '../src/goal-spec.mjs';
import { JsonlDecoder } from '../src/jsonl.mjs';

const plannerLimit = 4096 + 128 + 64 * (4096 + 8);
const registered = {
	schemaVersion: 1, agentId: 'agent-steering', provider: 'codex', model: 'gpt-6-astra',
	reasoningEffort: 'high', serviceTier: 'priority', skinVariant: 'teal', state: 'PAUSED',
	currentGoal: 'Collect stone', goalRevision: 1, queue: [], createdAtEpochMs: 1, updatedAtEpochMs: 1,
};
const envelope = (type, messageId, payload, agentId = registered.agentId) => ({
	protocolVersion: 2, serverInstanceId: 'steering-server', agentId, type, messageId, payload,
});
const control = (operation, goal, goalRevision = 2) => ({ operation, goal, goalRevision, updatedAtEpochMs: 2 });
const payloadOf = input => JSON.parse(input.slice(input.indexOf('\n') + 1));
function maximumHistory() {
	const original = 'P'.repeat(4096);
	const instructions = Array.from({ length: 64 }, (_, i) => `${i}:` + '\u6751"\\\n'.repeat(1024).slice(0, 4096 - `${i}:`.length));
	return original + '\n\nSteering instructions (oldest first; later instructions supersede conflicting earlier instructions):'
		+ instructions.map((instruction, i) => `\n${i + 1}. ${instruction}`).join('');
}
class FakeSocket extends EventEmitter {
	writes = []; destroyed = false;
	write(data) { this.writes.push(JSON.parse(String(data))); return true; }
	pause() {} resume() {} setNoDelay() {}
	destroy() { if (!this.destroyed) { this.destroyed = true; this.emit('close'); } }
}
function fixture(t) {
	const secret = 's'.repeat(32), socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret }, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule() {},
	});
	t.after(() => bridge.stop());
	const errors = [];
	bridge.on('protocolError', error => errors.push(error.code));
	bridge.on('listenerError', error => errors.push(error.message));
	const emit = frame => socket.emit('data', Buffer.from(JSON.stringify(frame) + '\n'));
	bridge.start(); socket.emit('connect');
	const challenge = socket.writes.at(-1), clientNonce = challenge.payload.clientNonce;
	const serverNonce = Buffer.alloc(32, 7).toString('base64url');
	emit(envelope('auth_response', 'auth', { replyTo: challenge.messageId, clientNonce, serverNonce,
		proof: createBridgeAuthenticationProof(secret, 'server', { clientNonce, serverNonce, serverInstanceId: 'steering-server' }) }, 'server'));
	emit(envelope('hello_ack', 'hello', { replyTo: socket.writes.at(-1).messageId, authenticated: true, registry: [registered] }, 'server'));
	assert.equal(bridge.ready, true);
	return { socket, bridge, emit, errors };
}
function fragments(type, messageId, payload) {
	const raw = Buffer.from(JSON.stringify(payload)), result = [];
	for (let offset = 0, index = 0; offset < raw.length; offset += 24 * 1024, index++) {
		result.push(envelope('registry_fragment', `${messageId}-${index}`, {
			messageId, type, index, totalBytes: raw.length, data: raw.subarray(offset, offset + 24 * 1024).toString('base64'),
		}));
	}
	return result;
}

test('maximum persisted history crosses authenticated fragmented registration and control intact', t => {
	const f = fixture(t), registry = new AgentRegistry(), goal = maximumHistory();
	const received = [];
	f.bridge.on('agent_registered', event => received.push(registry.register(event.payload)));
	f.bridge.on('goal_control', event => received.push(registry.applyGoalControl(event.agentId, event.payload)));
	for (const [type, payload] of [['agent_registered', { ...registered, currentGoal: goal }], ['goal_control', control('steer', goal)]]) {
		const parts = fragments(type, type, payload), prior = received.length;
		assert.ok(parts.length > 1);
		for (const [index, part] of parts.entries()) {
			const bytes = Buffer.from(JSON.stringify(part) + '\n');
			assert.ok(bytes.length - 1 <= 65_536);
			for (let offset = 0; offset < bytes.length; offset += 811) f.socket.emit('data', bytes.subarray(offset, offset + 811));
			assert.equal(received.length, prior + (index === parts.length - 1 ? 1 : 0));
		}
		assert.equal(received.at(-1).currentGoal, goal);
	}
	assert.deepEqual(f.errors, []);
	assert.equal(f.socket.destroyed, false);
	assert.equal(registry.get(registered.agentId).goalRevision, 2);
});

test('planner bounds preserve raw queue, original request, revision and wake collision controls', () => {
	const registry = new AgentRegistry();
	const goal = 'x'.repeat(plannerLimit);
	registry.register(validateProtocolV2Payload('agent_registered', { ...registered, currentGoal: goal }));
	assert.equal(registry.applyConversationWake(registered.agentId, control('start', goal, 1)).currentGoal, goal);
	assert.throws(() => registry.applyConversationWake(registered.agentId, control('start', goal.slice(1), 1)), { code: 'GOAL_REVISION_COLLISION' });
	assert.throws(() => registry.applyGoalControl(registered.agentId, control('steer', goal, 1)), { code: 'STALE_GOAL_REVISION' });
	for (const operation of ['start', 'replace', 'steer']) {
		assert.equal(validateProtocolV2Payload('goal_control', control(operation, goal)).goal, goal);
		assert.throws(() => validateProtocolV2Payload('goal_control', control(operation, goal + 'x')));
		const fresh = new AgentRegistry();
		fresh.register(registered);
		assert.equal(fresh.applyGoalControl(registered.agentId, control(operation, goal)).currentGoal, goal);
	}
	assert.throws(() => registry.register({ ...registered, currentGoal: goal + 'x' }));
	const supplementaryGoal = '\u{1f642}'.repeat(plannerLimit / 2);
	assert.equal(validateProtocolV2Payload('agent_registered', { ...registered, currentGoal: supplementaryGoal }).currentGoal.length, plannerLimit);
	assert.equal(new AgentRegistry().register({ ...registered, currentGoal: supplementaryGoal }).currentGoal, supplementaryGoal);
	assert.throws(() => validateProtocolV2Payload('agent_registered', { ...registered, currentGoal: supplementaryGoal + '\u{1f642}' }));
	assert.throws(() => new AgentRegistry().register({ ...registered, currentGoal: supplementaryGoal + '\u{1f642}' }));
	for (const operation of ['queue', 'dequeue']) assert.throws(() => validateProtocolV2Payload('goal_control', control(operation, 'x'.repeat(4097))));
	assert.throws(() => registry.applyGoalControl(registered.agentId, control('queue', 'x'.repeat(4097), 1)));
	assert.throws(() => registry.register({ ...registered, queue: ['x'.repeat(4097)] }));
	assert.throws(() => validateProtocolV2Payload('agent_registered', { ...registered, queue: ['x'.repeat(4097)] }));
	const rawBoundary = '\u{1f642}'.repeat(2048);
	assert.equal(validateProtocolV2Payload('goal_control', control('queue', rawBoundary, 1)).goal.length, 4096);
	assert.equal(new AgentRegistry().register({ ...registered, queue: [rawBoundary] }).queue[0].goal, rawBoundary);
	assert.throws(() => validateProtocolV2Payload('goal_control', control('queue', rawBoundary + '\u{1f642}', 1)));
	const request = { requestId: '00000000-0000-4000-8000-000000000001', originalRequest: 'x'.repeat(4096), candidateIds: [] };
	assert.equal(parseGoalSpecRequest(request).originalRequest.length, 4096);
	assert.throws(() => parseGoalSpecRequest({ ...request, originalRequest: request.originalRequest + 'x' }), /originalRequest/);
});

test('encoded authoritative history does not reduce the optional context and oldest unread budget', () => {
	const conversation = { mode: 'unread', baseSequence: 0, nextSequence: 64,
		entries: Array.from({ length: 64 }, (_, index) => ({ sequence: index + 1, kind: 'player_message', text: `Keep constraint ${index + 1}: ` + 'y'.repeat(480) })) };
	const options = { conversation, observation: { observedAtEpochMs: 123, freshness: { fresh: true }, player: { health: 20 } } };
	const withoutGoal = payloadOf(buildNativeEventInput({ ...registered, currentGoal: null }, options));
	const goal = maximumHistory();
	const input = buildNativeEventInput({ ...registered, currentGoal: goal }, options), withGoal = payloadOf(input);
	assert.equal(withGoal.goal, goal);
	assert.equal(input.split(JSON.stringify(goal)).length - 1, 1, 'one authoritative copy per input');
	assert.deepEqual(withGoal.conversation, withoutGoal.conversation);
	assert.deepEqual(withGoal.observation, withoutGoal.observation);
	assert.equal(withGoal.conversation.entries[0].sequence, 1);
	assert.ok(withGoal.conversation.omittedEntries > 0);
	assert.equal(withGoal.conversation.nextSequence, withGoal.conversation.entries.at(-1).sequence);
	const optionalBytes = Buffer.byteLength(JSON.stringify(withGoal)) - Buffer.byteLength(JSON.stringify(goal));
	assert.ok(optionalBytes <= 16_384);
	assert.equal(conversation.entries.length, 64, 'input source remains unread and unmodified');
	const trimmed = payloadOf(buildNativeEventInput({ ...registered, currentGoal: goal }, { ...options, taskMemory: { summary: 'z'.repeat(20_000) } }));
	assert.equal(trimmed.conversation.entries[0].sequence, 1, 'oversized task memory is trimmed before the oldest unread message is dropped');
	assert.equal(trimmed.taskMemory.truncated, true);
	assert.equal(trimmed.goal, goal);
});

test('large planner allowance does not relax physical frames or logical fragment limits', t => {
	const unfragmented = Buffer.from(JSON.stringify(envelope('agent_registered', 'large', { ...registered, currentGoal: maximumHistory() })) + '\n');
	assert.throws(() => new JsonlDecoder().push(unfragmented), { code: 'LINE_TOO_LARGE' });
	const f = fixture(t);
	f.emit(envelope('registry_fragment', 'oversized', { messageId: 'logical-oversized', type: 'agent_registered', index: 0, totalBytes: 128 * 1024 * 1024 + 1, data: Buffer.alloc(24 * 1024).toString('base64') }));
	assert.deepEqual(f.errors, ['INVALID_FRAGMENT']);
	assert.equal(f.socket.destroyed, true);
});
