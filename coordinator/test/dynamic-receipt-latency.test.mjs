import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { AgentRegistry } from '../src/agent-registry.mjs';
import { FactLedger } from '../src/fact-ledger.mjs';
import { FakePlanner, RecordingGoalSupervisor, factToWireObservation, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

const config = { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } };
function gate() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }

async function fixture({ blocking = false, memoryDirectory = null } = {}) {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const turn = gate();
	planner.requestNativeTurn = async (request) => { planner.requests.push(request); await turn.promise; return { status: 'completed', toolCalls: 1 }; };
	const run = await start({ registry, planner, goalSupervisor: new RecordingGoalSupervisor(), config, memoryDirectory });
	const errors = [];
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	const acks = [];
	run.bridge.acknowledgeActionResult = async (agentId, payload, options) => { acks.push({ agentId, payload, ...options }); };
	run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait under exact model control.' } });
	const observation = factToWireObservation({ player: { x: 0, y: 64, z: 0 } }, 1, 1, false, 1);
	observation.world.worldId = 'receipt-test-world';
	run.bridge.emit('observation', { agentId: 'agent-a', payload: observation });
	await eventually(() => planner.requests.length === 1);
	let calls = 0;
	const execute = (tool) => planner.requests[0].executeTool({ agentId: 'agent-a', goalRevision: 1, turnId: 'receipt-turn', callId: `call-${++calls}`, tool });
	const actionPromise = execute({ kind: blocking ? 'action' : 'start_action', actionType: 'wait', arguments: { durationMs: 1000 } });
	await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
	const handle = blocking ? { actionId: run.bridge.sent.find(({ type }) => type === 'action_command').payload.actionId } : await actionPromise;
	const terminal = { goalRevision: 1, actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 };
	return { ...run, errors, acks, execute, handle, actionPromise, terminal, close: async () => { turn.resolve(); await run.coordinator.stop(); } };
}

test('live terminal and later observations pass a delayed durable receipt, but ACK waits', async (t) => {
	const run = await fixture({ blocking: true });
	const storage = gate();
	const original = ModelNotebook.prototype.recordReceipt;
	let writes = 0;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) { writes++; await storage.promise; return original.apply(this, args); });
	let delivered = 0;
	run.coordinator.on('actionResult', () => delivered++);
	try {
		let ingressDone = false;
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal, waitUntil(promise) { promise.then(() => { ingressDone = true; }); } });
		await eventually(() => writes === 1 && delivered === 1);
		assert.equal((await run.actionPromise).state, 'SUCCEEDED', 'the waiting body tool receives the authoritative result before storage');
		assert.equal(ingressDone, true, 'storage cannot retain protocol gameplay ingress capacity');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		assert.equal((await run.execute({ kind: 'action_status', actionId: run.handle.actionId, goalRevision: 1 })).state, 'SUCCEEDED');
		const observed = await run.execute({ kind: 'observe' });
		assert.ok(observed.eventSequence > 2, 'inspection and its pushed observation traverse gameplay ingress during storage');
		assert.equal(run.acks.length, 0);
		storage.resolve();
		await eventually(() => run.acks.length === 1);
		assert.equal(writes, 1, 'coordinator owns one durable write, without runtime best-effort duplication');
		assert.equal(delivered, 1, 'a duplicate does not redeliver live completion');
		assert.deepEqual(run.errors, []);
	} finally { storage.resolve(); await run.close(); }
});

test('wrong-type terminal cannot release a native wait, mutate facts, or cancel valid work', async (t) => {
	const run = await fixture({ blocking: true });
	const ingest = t.mock.method(FactLedger.prototype, 'ingest');
	const recordReceipt = t.mock.method(ModelNotebook.prototype, 'recordReceipt');
	let settled = false, delivered = 0;
	run.actionPromise.then(() => { settled = true; }, () => { settled = true; });
	run.coordinator.on('actionResult', () => delivered++);
	try {
		let ingress;
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...run.terminal, actionType: 'chat', reasonCode: 'CHAT_SENT' }, waitUntil(promise) { ingress = promise; } });
		await ingress;
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(settled, false, 'a chat receipt cannot complete the pending wait');
		assert.equal((await run.execute({ kind: 'action_status', actionId: run.handle.actionId, goalRevision: 1 })).state, 'RUNNING');
		assert.equal(ingest.mock.calls.filter(({ arguments: args }) => args[0] === 'action_result').length, 0);
		assert.equal(recordReceipt.mock.callCount(), 0);
		assert.equal(delivered, 0);
		assert.equal(run.acks.length, 0);
		assert.equal(run.bridge.sent.some(({ type }) => ['action_cancel', 'agent_error'].includes(type)), false);
		assert.deepEqual(run.errors.map(({ code }) => code), ['UNCORRELATED_ACTION_RECEIPT']);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...run.terminal, actionType: 'wait' } });
		assert.equal((await run.actionPromise).state, 'SUCCEEDED');
		await eventually(() => run.acks.length === 1);
		assert.equal(delivered, 1);
	} finally { await run.close(); }
});

test('ACK publication failure retries the exact durable receipt without another storage write', async (t) => {
	const run = await fixture();
	const original = ModelNotebook.prototype.recordReceipt;
	let writes = 0, attempts = 0;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) { writes++; return original.apply(this, args); });
	run.bridge.acknowledgeActionResult = async (_agentId, payload) => {
		assert.equal(writes, 1);
		assert.equal(payload.actionId, run.handle.actionId);
		if (++attempts === 1) throw Object.assign(new Error('ACK publication failed'), { code: 'TEST_ACK_SEND' });
		run.acks.push(payload);
	};
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => run.acks.length === 1);
		assert.equal(attempts, 2);
		assert.equal(writes, 1);
		assert.ok(run.errors.some((error) => error.code === 'TEST_ACK_SEND'));
	} finally { await run.close(); }
});

test('failed authoritative storage retries without losing terminal evidence or failing gameplay', async (t) => {
	const run = await fixture();
	const original = ModelNotebook.prototype.recordReceipt;
	let writes = 0;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) {
		if (++writes === 1) throw Object.assign(new Error('disk unavailable'), { code: 'TEST_RECEIPT_DISK' });
		return original.apply(this, args);
	});
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => run.errors.some((error) => error.code === 'TEST_RECEIPT_DISK'));
		assert.equal(run.acks.length, 0);
		assert.equal((await run.execute({ kind: 'action_status', actionId: run.handle.actionId, goalRevision: 1 })).state, 'SUCCEEDED');
		await eventually(() => run.acks.length === 1);
		assert.equal(writes, 2);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false);
	} finally { await run.close(); }
});

test('delayed durable completion after disconnect cannot ACK the replacement connection', async (t) => {
	const run = await fixture();
	const storage = gate();
	const original = ModelNotebook.prototype.recordReceipt;
	let started = false;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) { started = true; await storage.promise; return original.apply(this, args); });
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => started);
		run.bridge.emit('disconnected', { connectionEpoch: 1 });
		storage.resolve();
		await new Promise((resolve) => setTimeout(resolve, 30));
		assert.equal(run.acks.length, 0);
	} finally { storage.resolve(); await run.close(); }
});

test('uncorrelated stale terminal withholds ACK without blocking the next valid receipt', async () => {
	const run = await fixture();
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...run.terminal, goalRevision: 0, actionId: 'native:unknown' } });
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => run.errors.some((error) => error.code === 'UNCORRELATED_ACTION_RECEIPT'));
		await eventually(() => run.acks.length === 1);
		assert.equal(run.acks[0].payload.actionId, run.handle.actionId);
		await run.close();
		assert.deepEqual(run.errors.map(({ code }) => code), ['UNCORRELATED_ACTION_RECEIPT']);
	} finally { await run.close(); }
});

test('a conflicting terminal cannot block a later correlated action receipt', async () => {
	const run = await fixture();
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => run.acks.length === 1);
		const next = await run.execute({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } });
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...run.terminal, state: 'FAILED', reasonCode: 'CONFLICT' } });
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...run.terminal, actionId: next.actionId, eventSequence: 3 } });
		await eventually(() => run.acks.length === 2);
		assert.deepEqual(run.acks.map(({ payload }) => payload.actionId), [run.handle.actionId, next.actionId]);
		await run.close();
		assert.deepEqual(run.errors.map(({ message }) => message), ['RECEIPT_CONFLICT']);
	} finally { await run.close(); }
});

for (const retirement of [null, 'disconnect', 'action_lease']) {
	test(`cold conversation dispatch is durable before send and lifecycle fenced: ${retirement ?? 'current'}`, async (t) => {
		const directory = await mkdtemp(join(tmpdir(), 'arena-cold-dispatch-'));
		t.after(() => rm(directory, { recursive: true, force: true }));
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		const turn = gate(), storage = gate();
		planner.requestNativeTurn = async (request) => { planner.requests.push(request); await turn.promise; return { status: 'completed', toolCalls: 1 }; };
		const supervisor = new RecordingGoalSupervisor(), leases = [];
		supervisor.begin = (key, kind) => { const token = { ...key, kind, operationId: `lease-${leases.length}` }; leases.push({ key, lease: token }); return token; };
		const run = await start({ registry, planner, goalSupervisor: supervisor, config, memoryDirectory: directory });
		const original = ModelNotebook.prototype.recordDispatch;
		let notebook, dispatch, action;
		const errors = [], acks = [];
		run.coordinator.on('runtimeError', (error) => errors.push(error));
		run.bridge.acknowledgeActionResult = async (_agentId, payload) => {
			const stored = await notebook.findReceipt('agent-a', { actionId: payload.actionId });
			assert.equal(stored.source, 'server_action_result');
			assert.equal(stored.state, 'SUCCEEDED');
			assert.equal((await new ModelNotebook({ directory }).findReceipt('agent-a', { actionId: payload.actionId })).state, 'SUCCEEDED', 'ACK requires a terminal that survives reload');
			acks.push(payload);
		};
		t.mock.method(ModelNotebook.prototype, 'recordDispatch', async function (agentId, receipt) {
			notebook = this; dispatch = receipt;
			await storage.promise;
			return original.call(this, agentId, receipt);
		});
		try {
			run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
				sequence: 1, kind: 'player_message', sourceId: '11111111-1111-4111-8111-111111111111', recipientId: 'agent-a', scope: 'direct',
				text: 'Hello.', goalRevision: 0, observedAtEpochMs: 1,
			} });
			await eventually(() => planner.requests.length === 1);
			action = planner.requests[0].executeTool({ agentId: 'agent-a', goalRevision: 0, turnId: 'cold', callId: 'reply',
				tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Hello.', audience: 'public' } } });
			action.catch(() => {});
			await eventually(() => dispatch !== undefined);
			assert.match(dispatch.worldId, /^dispatch-session:/);
			assert.equal(dispatch.actionType, 'chat');
			assert.equal(run.bridge.sent.some(({ type }) => type === 'action_command'), false);
			if (retirement === 'disconnect') run.bridge.emit('disconnected', { connectionEpoch: 1 });
			if (retirement === 'action_lease') run.coordinator.handleLeaseExpired(leases.find(({ lease }) => lease.kind === 'action'));
			storage.resolve();
			if (retirement !== null) {
				await action.catch(() => {});
				await notebook.findReceipt('agent-a', { actionId: dispatch.actionId });
				await new Promise((resolve) => setImmediate(resolve));
				assert.equal(run.bridge.sent.some(({ type }) => type === 'action_command'), false);
				assert.equal(acks.length, 0);
			} else {
				await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
				const stored = await notebook.findReceipt('agent-a', { actionId: dispatch.actionId });
				assert.equal(stored.source, 'coordinator_dispatch');
				assert.equal((await new ModelNotebook({ directory }).findReceipt('agent-a', { actionId: dispatch.actionId })).source, 'coordinator_dispatch', 'send requires a dispatch that survives reload');
				run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 0, actionId: dispatch.actionId, actionType: 'chat', state: 'SUCCEEDED', reasonCode: 'CHAT_SENT', eventSequence: 1 } });
				assert.equal((await action).state, 'SUCCEEDED');
				await eventually(() => acks.length === 1);
				assert.deepEqual(errors, []);
			}
		} finally { storage.resolve(); turn.resolve(); await run.coordinator.stop(); await Promise.allSettled([action]); }
	});
}

test('shutdown is bounded with stuck receipt storage and leaves the result unacknowledged', async (t) => {
	const run = await fixture();
	const storage = gate();
	const original = ModelNotebook.prototype.recordReceipt;
	let started = false;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) { started = true; await storage.promise; return original.apply(this, args); });
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => started);
		await run.close();
		assert.equal(run.acks.length, 0);
		assert.ok(run.errors.some((error) => error.code === 'RECEIPT_SHUTDOWN_PENDING'));
	} finally { storage.resolve(); await run.close(); }
});

test('shutdown releases a second blocking action before stuck terminal storage settles', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'arena-stop-receipt-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const unknown = t.mock.method(ModelNotebook.prototype, 'recordUnknown');
	const run = await fixture({ memoryDirectory: directory });
	const storage = gate();
	const original = ModelNotebook.prototype.recordReceipt;
	let started = false, completed = false, outcome;
	t.mock.method(ModelNotebook.prototype, 'recordReceipt', async function (...args) { started = true; await storage.promise; const result = await original.apply(this, args); completed = true; return result; });
	let second;
	try {
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: run.terminal });
		await eventually(() => started);
		second = run.execute({ kind: 'action', actionType: 'wait', arguments: { durationMs: 1000 } });
		second.then((value) => { outcome = { value }; }, (error) => { outcome = { error }; });
		await eventually(() => run.bridge.sent.filter(({ type }) => type === 'action_command').length === 2);
		const nextId = run.bridge.sent.filter(({ type }) => type === 'action_command')[1].payload.actionId;
		await run.close();
		assert.equal(completed, false);
		assert.equal(outcome?.error?.code, 'NATIVE_ACTION_CANCELLED', 'stop must settle the tool even while receipt storage is stuck');
		assert.equal(run.acks.length, 0);
		assert.ok(run.errors.some((error) => error.code === 'RECEIPT_SHUTDOWN_PENDING'));
		storage.resolve();
		await eventually(() => completed && unknown.mock.calls.some(({ arguments: args }) => args[1].actionId === nextId));
		await unknown.mock.calls.find(({ arguments: args }) => args[1].actionId === nextId).result;
		const notebook = new ModelNotebook({ directory });
		assert.equal((await notebook.findReceipt('agent-a', { actionId: run.handle.actionId })).state, 'SUCCEEDED');
		assert.equal((await notebook.findReceipt('agent-a', { actionId: nextId })).state, 'UNKNOWN');
		assert.equal(run.acks.length, 0, 'late storage completion cannot ACK the stopped session');
	} finally { storage.resolve(); await run.close(); await Promise.allSettled([second]); }
});
