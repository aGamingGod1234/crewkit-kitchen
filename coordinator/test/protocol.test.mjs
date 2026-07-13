import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';

import { MinecraftBridge, MessageIdGenerator } from '../src/protocol.mjs';

class FakeSocket extends EventEmitter {
	writes = [];
	destroyed = false;

	write(value) {
		this.writes.push(String(value));
		return true;
	}

	setNoDelay() {}

	destroy() {
		if (this.destroyed) return;
		this.destroyed = true;
		this.emit('close');
	}
}

test('message IDs are monotonic and bounded', () => {
	const ids = new MessageIdGenerator('coordinator');
	assert.equal(ids.next(), 'coordinator-1');
	assert.equal(ids.next(), 'coordinator-2');
	assert.throws(() => new MessageIdGenerator('x'.repeat(128)), /prefix/);
});

test('bridge authenticates, generates commands, and reconnects without resetting IDs', async () => {
	const sockets = [];
	const scheduled = [];
	const bridge = new MinecraftBridge({ agentId: 'agent-55', host: '127.0.0.1', port: 25571 }, {
		socketFactory: () => {
			const socket = new FakeSocket();
			sockets.push(socket);
			return socket;
		},
		schedule: (callback) => { scheduled.push(callback); return callback; },
		cancelSchedule: () => {},
		now: () => 1_750_000_000_000,
	});
	bridge.start();
	sockets[0].emit('connect');
	const hello = JSON.parse(sockets[0].writes[0]);
	assert.equal(hello.messageId, 'coordinator-1');
	const firstReady = once(bridge, 'ready');
	sockets[0].emit('data', `${JSON.stringify({ protocolVersion: 1, agentId: 'agent-55', type: 'hello_ack', messageId: 'server-1', replyTo: hello.messageId })}\n`);
	await firstReady;
	bridge.sendAction({ type: 'wait', durationMs: 25 });
	assert.equal(JSON.parse(sockets[0].writes[1]).messageId, 'coordinator-2');
	sockets[0].emit('close');
	assert.equal(scheduled.length, 1);
	scheduled.shift()();
	sockets[1].emit('connect');
	assert.equal(JSON.parse(sockets[1].writes[0]).messageId, 'coordinator-3');
	bridge.stop();
});

test('bridge rejects duplicate terminal action results', async () => {
	const socket = new FakeSocket();
	const bridge = new MinecraftBridge({ agentId: 'agent-55', host: '127.0.0.1', port: 25571 }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify({ protocolVersion: 1, agentId: 'agent-55', type: 'hello_ack', messageId: 'server-1', replyTo: hello.messageId })}\n`);
	await ready;
	const result = { protocolVersion: 1, agentId: 'agent-55', type: 'action_result', messageId: 'server-2', commandId: 'command-1', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', completedAtEpochMs: 1_750_000_001_000 };
	const errors = [];
	bridge.on('protocolError', (error) => errors.push(error));
	socket.emit('data', `${JSON.stringify(result)}\n`);
	socket.emit('data', `${JSON.stringify({ ...result, messageId: 'server-3' })}\n`);
	assert.equal(errors.at(-1).code, 'DUPLICATE_TERMINAL_RESULT');
	bridge.stop();
});

test('bridge fails closed for non-loopback hosts', () => {
	assert.throws(() => new MinecraftBridge({ agentId: 'agent-55', host: '0.0.0.0', port: 25571 }), /127\.0\.0\.1/);
});
