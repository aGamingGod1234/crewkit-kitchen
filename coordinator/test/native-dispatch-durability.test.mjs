import assert from 'node:assert/strict';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { ModelNotebook } from '../src/model-notebook.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

const record = { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'low', serviceTier: 'priority', goalRevision: 3, currentGoal: 'wait' };
const call = (tool, callId) => ({ agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId, tool });
const wait = { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 50 } };
const world = { world: { worldId: 'world-a', dimension: 'minecraft:overworld' } };

async function directory(t) {
	const path = await mkdtemp(join(tmpdir(), 'native-dispatch-'));
	t.after(() => rm(path, { recursive: true, force: true }));
	return path;
}

// The command may only leave the process once its dispatch record is durable: a result for an action the
// journal never saw cannot be matched after a restart, and the model would not learn the action was in flight.
test('the dispatch record is on disk before the command reaches the bridge', async (t) => {
	const path = await directory(t);
	const seenAtSend = [];
	const runtime = new NativeToolRuntime({
		notebook: new ModelNotebook({ directory: path }),
		bridge: { send: async (type, agentId, payload) => {
			if (type !== 'action_command') return;
			// A restarted coordinator reads the same directory with a fresh notebook.
			seenAtSend.push(await new ModelNotebook({ directory: path }).findReceipt(agentId, { actionId: payload.actionId }));
		} },
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, world, { eventSequence: 1 });
	const handle = await runtime.execute(call(wait, 'call-1'), record);
	assert.equal(seenAtSend.length, 1);
	assert.equal(seenAtSend[0]?.actionId, handle.actionId);
	assert.equal(seenAtSend[0]?.state, 'DISPATCHED');
	assert.equal(seenAtSend[0]?.reasonCode, 'AWAITING_AUTHORITATIVE_RESULT');
});

test('a stalled journal write holds the command back instead of racing it', async (t) => {
	const notebook = new ModelNotebook({ directory: await directory(t) });
	let release;
	const stalled = new Promise((resolve) => { release = resolve; });
	const original = notebook.recordDispatch.bind(notebook);
	notebook.recordDispatch = async (...args) => { await stalled; return original(...args); };
	const sent = [];
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async (type) => { sent.push(type); } } });
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, world, { eventSequence: 1 });
	const pending = runtime.execute(call(wait, 'call-1'), record);
	for (let index = 0; index < 20; index += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent, [], 'nothing is sent while the dispatch record is not durable');
	release();
	await pending;
	assert.deepEqual(sent, ['action_command']);
});

test('after a crash between send and result, the restarted notebook reports the action as unresolved', async (t) => {
	const path = await directory(t);
	const sent = [];
	const runtime = new NativeToolRuntime({ notebook: new ModelNotebook({ directory: path }), bridge: { send: async (type, _agent, payload) => { if (type === 'action_command') sent.push(payload); } } });
	runtime.updateObservation(record, world, { eventSequence: 1 });
	const handle = await runtime.execute(call(wait, 'call-1'), record);
	// The coordinator dies here: no result, no unknown marker, runtime state is simply gone.
	const restarted = new ModelNotebook({ directory: path });
	const unresolved = await restarted.listUnresolved('agent-a', { worldId: 'world-a', limit: 8 });
	assert.deepEqual(unresolved.entries.map(({ actionId, state, source }) => ({ actionId, state, source })), [{ actionId: handle.actionId, state: 'DISPATCHED', source: 'coordinator_dispatch' }]);
	// The server's retained result still correlates with the durable dispatch and settles it.
	const stored = await restarted.recordReceipt('agent-a', { worldId: 'world-a', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'WAIT_COMPLETED', executionStarted: true, physicalAttempted: false });
	assert.equal(stored.state, 'SUCCEEDED');
	assert.equal((await restarted.listUnresolved('agent-a', { worldId: 'world-a', limit: 8 })).total, 0);
	assert.equal(sent.length, 1);
	await runtime.disposeAll();
});

test('the journal still appends after its directory was removed underneath it', async (t) => {
	const path = await directory(t);
	const notebook = new ModelNotebook({ directory: path });
	const identity = (actionId) => ({ worldId: 'world-a', actionId, actionType: 'wait', goalRevision: 1, arguments: { durationMs: 1 } });
	await notebook.recordDispatch('agent-a', identity('first'));
	await rm(path, { recursive: true, force: true });
	// The removed history is gone for good; the point is that a dispatch is not refused over a missing directory.
	assert.equal((await notebook.recordDispatch('agent-a', identity('second'))).state, 'DISPATCHED');
	assert.ok((await readdir(path)).some((name) => name.endsWith('.jsonl')), 'the journal file was written again');
});
