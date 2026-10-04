import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fixture, gate, event, record, until, flush } from './fixtures/runtime-inbox-fixture.mjs';
import { ActiveGoalSupervisor } from '../src/active-goal-supervisor.mjs';
import { AtomicAgentStore } from '../src/observed-memory-store.mjs';

async function directory(t) {
	const path = await mkdtemp(join(tmpdir(), 'runtime-conversation-'));
	t.after(() => rm(path, { recursive: true, force: true }));
	return path;
}

for (const laterMessage of [false, true]) test(`consumed wake replay after restart (${laterMessage ? 'later message' : 'latest wake'})`, async t => {
	const memoryDirectory = await directory(t);
	const payload = { transactionId: '00000000-0000-4000-8000-000000000017', event: event(1, false),
		control: { operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: 'Respond to the player.' } };
	let first, second;
	try {
		first = await fixture({ memoryDirectory });
		await first.bridge.deliver('conversation_wake', payload);
		await until(() => first.traces.some(t => t.event === 'native_turn_completed'), 'wake committed');
		if (laterMessage) {
			await first.bridge.deliver('conversation_event', event(2, false, 1));
			await until(() => first.traces.filter(t => t.event === 'native_turn_completed').length === 2, 'later message committed');
		}
		const current = first.registry.get('agent-a');
		await first.coordinator.stop();
		second = await fixture({ memoryDirectory, record: current });
		// Collision checks precede lifecycle replacement, including changed control.
		await assert.rejects(second.bridge.deliver('conversation_wake', { ...payload, event: { ...payload.event, text: 'changed' } }), { code: 'CONVERSATION_COLLISION' });
		await assert.rejects(second.bridge.deliver('conversation_wake', { ...payload, control: { ...payload.control, goal: 'Changed task.' } }), { code: 'TRANSACTION_COLLISION' });
		await assert.rejects(second.bridge.deliver('conversation_wake', { ...payload, transactionId: '00000000-0000-4000-8000-000000000018' }), { code: 'TRANSACTION_COLLISION' });
		assert.equal(second.registry.get('agent-a').goalRevision, 1);
		assert.equal(second.bridge.sent.filter(m => m.type === 'conversation_wake_ack').length, 0);
		await second.bridge.deliver('conversation_wake', structuredClone(payload));
		await until(() => second.traces.some(t => t.event === 'native_turn_completed'), 'recovery turn committed');
		assert.equal(second.bridge.sent.filter(m => m.type === 'conversation_wake_ack').length, 1);
		assert.equal(second.calls.length, 1);
		assert.deepEqual(second.calls.flatMap(c => c.conversation.entries), [], 'consumed wake text stays consumed');
	} finally { await first?.coordinator.stop(); await second?.coordinator.stop(); }
});

for (const state of ['IDLE', 'PAUSED', 'COMPLETED']) test(`reconciliation delivers persisted unread ${state} conversation without new input`, async t => {
	const memoryDirectory = await directory(t), held = gate();
	const restored = { ...record(), state, ...(state === 'IDLE' ? {} : { currentGoal: 'Existing task.', goalRevision: 1 }) };
	const message = sequence => event(sequence, false, restored.goalRevision);
	let first, second;
	try {
		first = await fixture({ memoryDirectory, record: restored, start: () => held.promise });
		await first.bridge.deliver('conversation_event', message(1));
		await until(() => first.calls.length === 1, 'held provider turn');
		await first.coordinator.stop();
		second = await fixture({ memoryDirectory, record: restored });
		await until(() => second.traces.filter(t => t.event === 'native_turn_completed').length === 2, 'autonomous recovered delivery and visible-reply correction');
		assert.deepEqual(second.calls.flatMap(c => c.conversation.entries), [message(1)]);
		assert.equal(second.registry.get('agent-a').state, state);
		assert.deepEqual(second.errors, []);
		// Consumed recovery stays suppressed when another message arrives.
		await second.bridge.deliver('conversation_event', message(2));
		await until(() => second.traces.filter(t => t.event === 'native_turn_completed').length === 4, 'new-event control and visible-reply correction');
		assert.deepEqual(second.calls.flatMap(c => c.conversation.entries), [message(1), message(2)]);
	} finally { held.resolve(); await first?.coordinator.stop(); await second?.coordinator.stop(); }
});

class TrackingSupervisor extends ActiveGoalSupervisor {
	keys = []; recoveries = [];
	activate(key) { this.keys.push(key); return super.activate(key); }
	recover(key, details) { const accepted = super.recover(key, details); this.recoveries.push({ key, details, accepted }); return accepted; }
}

test('reconciliation cannot duplicate fresh intake already owned by the current provider turn', async t => {
	const memoryDirectory = await directory(t), loading = gate(), releaseLoad = gate(), held = gate();
	const original = AtomicAgentStore.prototype.read;
	let armed = true, f, admission;
	AtomicAgentStore.prototype.read = async function(key) {
		const value = await original.call(this, key);
		if (key === 'index' && armed) { armed = false; loading.resolve(); await releaseLoad.promise; }
		return value;
	};
	try {
		f = await fixture({ memoryDirectory, waitForReconciliation: false, start: () => held.promise });
		await loading.promise;
		admission = f.bridge.deliver('conversation_event', event(1, false));
		releaseLoad.resolve(); await admission;
		await until(() => f.calls.length > 0, 'current provider intake');
		await until(() => f.bridge.sent.some(m => m.type === 'coordinator_status' && m.payload.reconciled), 'reconciliation complete');
		assert.equal(f.calls.length, 1, 'recovery cannot steer a second copy over current intake');
		held.resolve();
		await until(() => f.traces.filter(t => t.event === 'native_turn_completed').length === 2, 'delivery and visible-reply correction');
		assert.deepEqual(f.calls.flatMap(c => c.conversation.entries), [event(1, false)]);
		assert.deepEqual(f.errors, []);
	} finally { releaseLoad.resolve(); held.resolve(); AtomicAgentStore.prototype.read = original; await admission; await f?.coordinator.stop(); }
});

test('mailbox recovery opened by an obsolete connection only delivers under the replacement lifecycle', async t => {
	const memoryDirectory = await directory(t), held = gate(), loading = gate(), releaseLoad = gate();
	const original = AtomicAgentStore.prototype.read;
	let first, second, armed = true;
	try {
		first = await fixture({ memoryDirectory, start: () => held.promise });
		await first.bridge.deliver('conversation_event', event(1, false));
		await until(() => first.calls.length === 1, 'unread original intake'); await first.coordinator.stop();
		AtomicAgentStore.prototype.read = async function(key) {
			const value = await original.call(this, key);
			if (key === 'index' && armed) { armed = false; loading.resolve(); await releaseLoad.promise; }
			return value;
		};
		second = await fixture({ memoryDirectory, waitForReconciliation: false }); await loading.promise;
		second.bridge.emit('disconnected', { connectionEpoch: 1 }); await flush();
		second.bridge.emit('ready', { serverInstanceId: 'r17-server', connectionEpoch: 2,
			registry: [{ ...record(), state: 'PAUSED', currentGoal: 'Replacement task.', goalRevision: 1 }] });
		await until(() => second.bridge.sent.some(m => m.type === 'agent_ready' && m.connectionEpoch === 2), 'replacement ready');
		assert.equal(second.calls.length, 0);
		releaseLoad.resolve();
		await until(() => second.traces.filter(t => t.event === 'native_turn_completed').length === 2, 'current recovered delivery');
		assert.ok(second.calls.every(call => call.goalRevision === 1));
		assert.deepEqual(second.calls.flatMap(c => c.conversation.entries), [event(1, false)]);
		assert.equal(second.registry.get('agent-a').state, 'PAUSED');
		assert.deepEqual(second.errors, []);
	} finally { releaseLoad.resolve(); held.resolve(); AtomicAgentStore.prototype.read = original; await first?.coordinator.stop(); await second?.coordinator.stop(); }
});

for (const inject of [false, true]) test(`conversation delivery commit ${inject ? 'EIO retains autonomous recovery' : 'healthy visible-reply control'}`, async t => {
	const memoryDirectory = await directory(t), timers = new Map();
	let sequence = 0, injected = 0, f;
	const schedule = (cb, ms) => { const id = ++sequence; timers.set(id, { cb, ms }); return id; };
	const supervisor = new TrackingSupervisor({ clock: () => 1, schedule, cancelSchedule: id => timers.delete(id),
		stuckSchedule: schedule, cancelStuckSchedule: id => timers.delete(id), requestObservation: key => f.coordinator.requestSupervisedObservation(key) });
	const original = AtomicAgentStore.prototype.write;
	AtomicAgentStore.prototype.write = async function(key, value) {
		if (inject && !injected && value?.delivered === true) { injected++; throw Object.assign(new Error('controlled delivery EIO'), { code: 'EIO' }); }
		return original.call(this, key, value);
	};
	try {
		f = await fixture({ memoryDirectory, supervisor, toolCalls: 0 });
		await f.bridge.deliver('conversation_event', event(1, false));
		if (inject) {
			await until(() => supervisor.recoveries.length === 1, 'commit recovery');
			assert.equal(injected, 1);
			assert.equal(supervisor.recoveries[0].accepted, true, 'current supervision survives commit failure');
			assert.ok(supervisor.snapshot(supervisor.keys.at(-1)));
			assert.equal(f.calls.length, 1);
			const recovery = [...timers.entries()].find(([, timer]) => timer.ms !== 30_000);
			assert.ok(recovery, 'a recovery callback remains armed');
			timers.delete(recovery[0]); recovery[1].cb();
		}
		await until(() => f.traces.filter(t => t.event === 'native_turn_completed').length === 2, 'delivery and visible-reply correction');
		assert.equal(f.calls.length, inject ? 3 : 2);
		assert.deepEqual(f.calls.slice(inject ? 1 : 0).flatMap(c => c.conversation.entries), [event(1, false)]);
		assert.equal(f.registry.get('agent-a').state, 'IDLE');
		assert.equal(supervisor.snapshot(supervisor.keys.at(-1)), null, 'healthy completion releases conversation supervision');
	} finally { AtomicAgentStore.prototype.write = original; await f?.coordinator.stop(); }
});
