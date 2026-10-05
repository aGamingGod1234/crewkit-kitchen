import assert from 'node:assert/strict';
import test from 'node:test';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';

const agent = { agentId: 'saved-note-test', goalRevision: 1, provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const worldId = 'saved-world';
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const savedSource = `${prefix}\n// Keep all source and comments intact.\nprogram.finish("exact saved source");`;
const lookup = key => ({ operation: 'find_note', arguments: { kind: 'notes', text: key, offset: 0, limit: 64 } });
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
async function until(predicate) {
	for (let i = 0; i < 100; i++) { if (predicate()) return; await tick(); }
	assert.ok(predicate(), 'expected runtime transition');
}
function setup(t, { notebook = new ModelNotebook(), shared = false, memoryOperation, findNote } = {}) {
	const exact = [], queries = [], operations = [], starts = [], commands = [];
	const originalFind = notebook.findNote?.bind(notebook), originalQuery = notebook.query.bind(notebook);
	if (originalFind) notebook.findNote = async (...args) => { exact.push(args); return findNote ? findNote(...args) : originalFind(...args); };
	notebook.query = (...args) => { queries.push(args); return originalQuery(...args); };
	const memory = shared ? new RuntimeMemoryContext({ notebook }) : null;
	const executor = new NativeProgramExecutor();
	const originalRun = executor.run.bind(executor);
	executor.run = (record, options, context) => { starts.push(options); return originalRun(record, options, context); };
	let record = { ...agent }, sequence = 0, callId = 0;
	let observation = { ready: true, world: { worldId, dimension: 'minecraft:overworld' }, player: { health: 20, dead: false, x: 0, y: 64, z: 0 }, entities: [], blocks: [], items: [], inventory: { items: [] } };
	const runtime = new NativeToolRuntime({ notebook, programExecutor: executor, registry: { get: () => record },
		bridge: { send: async (type, _agentId, payload) => {
			if (type === 'action_command') commands.push(payload);
			if (type === 'action_cancel') queueMicrotask(() => runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: record.goalRevision, state: 'CANCELLED', reasonCode: 'CANCELLED' }));
		} },
		requestObservation: async (_record, { afterEventSequence }) => ({ eventSequence: sequence = Math.max(sequence, afterEventSequence) + 1, observation }),
		...(memory ? { memoryObservation: (record, value) => memory.observe(record, value) } : {}),
		...(memory || memoryOperation ? { memoryOperation: (record, operation) => {
			operations.push(operation); return memoryOperation ? memoryOperation(record, operation) : memory.execute(record, operation);
		} } : {}),
	});
	const publish = (patch = {}) => { observation = { ...observation, ...patch }; runtime.updateObservation(record, observation, { eventSequence: ++sequence }); };
	publish();
	t.after(async () => { await runtime.disposeAll(); await memory?.flush(); });
	const call = (name, args) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision, turnId: 'turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const queue = handle => call('queueProgram', { afterProgramId: handle.programId, goalRevision: record.goalRevision, programVersion: 1, noteKey: 'routine', precondition: 'player.state().health > 0' });
	return { notebook, memory, runtime, exact, queries, operations, starts, commands, call, queue, publish,
		changeGoal: () => { record = { ...record, goalRevision: record.goalRevision + 1 }; },
		finish: () => runtime.onActionResult(record, { actionId: commands.at(-1).actionId, goalRevision: record.goalRevision, state: 'SUCCEEDED', reasonCode: 'DONE' }),
	};
}

for (const shared of [false, true]) {
	const mode = shared ? 'shared memoryOperation' : 'standalone notebook';
	test(`${mode}: exact lookup skips substring pages and runs the entire saved source`, async t => {
		const run = setup(t, { shared, notebook: new ModelNotebook({ maximumNotes: 128 }) });
		await run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: savedSource });
		for (let i = 0; i < 80; i++) await run.notebook.writeNote(agent.agentId, { worldId, key: `routine-${i}`, text: `${prefix} program.finish("substring");` });
		const result = await run.call('runProgram', { noteKey: 'routine' });
		assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
		assert.equal(run.starts[0].source, savedSource);
		assert.deepEqual(run.exact, [[agent.agentId, { worldId, key: 'routine' }]]);
		assert.equal(run.queries.length, 0);
		if (shared) assert.deepEqual(run.operations, [lookup('routine')]);
	});

	test(`${mode}: wrong world, agent, case and substring cannot select a saved program`, async t => {
		const run = setup(t, { shared });
		for (const [id, world, key] of [[agent.agentId, 'other-world', 'routine'], ['other-agent', worldId, 'routine'], [agent.agentId, worldId, 'Routine'], [agent.agentId, worldId, 'routine-other']]) {
			await run.notebook.writeNote(id, { worldId: world, key, text: savedSource });
		}
		await assert.rejects(run.call('runProgram', { noteKey: 'routine' }), { code: 'PROGRAM_NOTE_NOT_FOUND' });
		assert.equal(run.starts.length, 0);
		assert.equal(run.queries.length, 0, 'an exact miss must not fall back to substring queries');
	});

	test(`${mode}: queued note resolves once and freezes the exact source through handoff`, async t => {
		const run = setup(t, { shared });
		await run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: savedSource });
		const handle = await run.call('runProgram', { source: `${prefix} await player.wait(1);`, background: true });
		await until(() => run.commands.length === 1);
		assert.equal((await run.queue(handle)).state, 'QUEUED');
		await run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: `${prefix} program.finish("changed");` });
		run.finish();
		await until(() => run.starts.length === 2);
		assert.equal(run.starts[1].source, savedSource);
		assert.equal(run.exact.length, 1);
		assert.equal(run.queries.length, 0);
	});

	for (const change of ['lifecycle', 'goal', 'world-roundtrip', 'dimension-roundtrip', 'death', 'cancel', 'deadline']) {
		test(`${mode}: ${change} during exact lookup cannot start execution`, async t => {
			const gate = deferred();
			const run = setup(t, { shared, findNote: () => gate.promise });
			const pending = run.call('runProgram', { noteKey: 'routine' });
			// Cancellation and expiry settle their foreground handle with a result.
			const rejected = change === 'cancel' || change === 'deadline' ? null
				: assert.rejects(pending, error => ['NATIVE_PROGRAM_CANCELLED', 'STALE_NATIVE_TOOL', 'STALE_GOAL'].includes(error.code));
			await until(() => run.exact.length === 1);
			if (change === 'lifecycle') await run.runtime.dispose(agent.agentId, 'goal_changed');
			if (change === 'goal') run.changeGoal();
			if (change === 'world-roundtrip') {
				run.publish({ world: { worldId: 'other-world', dimension: 'minecraft:overworld' } });
				run.publish({ world: { worldId, dimension: 'minecraft:overworld' } });
			}
			if (change === 'dimension-roundtrip') {
				run.publish({ world: { worldId, dimension: 'minecraft:the_nether' } });
				run.publish({ world: { worldId, dimension: 'minecraft:overworld' } });
			}
			if (change === 'death') run.publish({ player: { health: 0, dead: true } });
			if (change === 'cancel' || change === 'deadline') {
				const handle = await run.call('programStatus', {});
				if (change === 'cancel') await run.call('cancelProgram', { programId: handle.programId, goalRevision: 1 });
				else await run.runtime.expireProgram(agent, handle.programId);
			}
			gate.resolve({ key: 'routine', worldId, text: savedSource });
			if (change === 'cancel' || change === 'deadline') assert.equal((await pending).state, change === 'cancel' ? 'CANCELLED' : 'TIMED_OUT');
			else await rejected;
			await tick();
			assert.equal(run.starts.length, 0);
			assert.equal(run.queries.length, 0);
		});
	}

	test(`${mode}: successor loses admission during delayed exact lookup`, async t => {
		const gate = deferred();
		const run = setup(t, { shared, findNote: () => gate.promise });
		const handle = await run.call('runProgram', { source: `${prefix} await player.wait(1);`, background: true });
		await until(() => run.commands.length === 1);
		const admission = run.queue(handle);
		const rejected = assert.rejects(admission, { code: 'STALE_PROGRAM' });
		await until(() => run.exact.length === 1);
		run.publish({ world: { worldId, dimension: 'minecraft:the_nether' } });
		run.publish({ world: { worldId, dimension: 'minecraft:overworld' } });
		gate.resolve({ key: 'routine', worldId, text: savedSource });
		await rejected;
		assert.equal((await run.call('programStatus', {})).pendingSuccessor, undefined);
	});
}

for (const shared of [false, true]) test(`legacy notebook fallback remains scoped and paginates (shared=${shared})`, async t => {
	const notebook = new ModelNotebook({ maximumNotes: 128 });
	notebook.findNote = undefined;
	const run = setup(t, { notebook, shared });
	await notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: savedSource });
	for (let i = 0; i < 70; i++) await notebook.writeNote(agent.agentId, { worldId, key: `routine-${i}`, text: savedSource });
	await run.call('runProgram', { noteKey: 'routine' });
	assert.deepEqual(run.queries.map(([id, query]) => [id, query.worldId, query.offset]), [[agent.agentId, worldId, 0], [agent.agentId, worldId, 64]]);
	assert.equal(run.starts[0].source, savedSource);
});

test('legacy memory adapters may explicitly reject the internal operation; storage errors do not fall back', async t => {
	for (const code of ['INVALID_MEMORY_OPERATION', 'DISK_READ_FAILED']) {
		const run = setup(t, { memoryOperation: async (_record, operation) => {
			if (operation.operation === 'find_note') throw Object.assign(new Error(code), { code });
			return { entries: [{ key: 'routine', worldId, text: savedSource }], nextOffset: null };
		} });
		if (code === 'INVALID_MEMORY_OPERATION') {
			await run.call('runProgram', { noteKey: 'routine' });
			assert.deepEqual(run.operations.map(operation => operation.operation), ['find_note', 'query']);
		} else {
			await assert.rejects(run.call('runProgram', { noteKey: 'routine' }), { code });
			assert.equal(run.operations.length, 1);
			assert.equal(run.starts.length, 0);
		}
	}
});

test('shared exact lookup rejects scope overrides and forget/reobserve cannot resurrect a lookup', async () => {
	const notebook = new ModelNotebook();
	const gate = deferred();
	notebook.findNote = () => gate.promise;
	const memory = new RuntimeMemoryContext({ notebook });
	const observed = { world: { worldId } };
	memory.observe(agent, observed);
	for (const override of [{ worldId: 'other' }, { agentId: 'other' }, { goalRevision: 999 }]) {
		await assert.rejects(memory.execute(agent, { ...lookup('routine'), arguments: { ...lookup('routine').arguments, ...override } }));
	}
	const pending = memory.execute(agent, lookup('routine'));
	memory.forget(agent.agentId);
	memory.observe(agent, observed);
	gate.resolve({ worldId, key: 'routine', text: savedSource });
	await assert.rejects(pending, { code: 'STALE_GOAL' });
});

test('ordinary same-world samples keep a pending exact lookup valid', async t => {
	for (const shared of [false, true]) {
		const gate = deferred();
		const run = setup(t, { shared, findNote: () => gate.promise });
		const pending = run.call('runProgram', { noteKey: 'routine' });
		await until(() => run.exact.length === 1);
		run.publish({ worldTick: 10 });
		run.publish({ worldTick: 11 });
		gate.resolve({ key: 'routine', worldId, text: savedSource });
		assert.equal((await pending).reasonCode, 'PROGRAM_FINISH_REQUESTED');
		assert.equal(run.starts[0].source, savedSource);
	}
});

test('a scoped adapter cannot substitute another key or world in an exact result', async t => {
	for (const shared of [false, true]) for (const note of [{ key: 'routine-other', worldId, text: savedSource }, { key: 'routine', worldId: 'other-world', text: savedSource }]) {
		const run = setup(t, { shared, findNote: async () => note });
		await assert.rejects(run.call('runProgram', { noteKey: 'routine' }), { code: 'PROGRAM_NOTE_NOT_FOUND' });
		assert.equal(run.starts.length, 0);
		assert.equal(run.queries.length, 0);
	}
});

test('the existing 2048-character note boundary and full source validation remain in force', async t => {
	const run = setup(t, { shared: true });
	const source = savedSource + '\n//' + 'x'.repeat(2048 - savedSource.length - 3);
	await run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: source });
	await run.call('runProgram', { noteKey: 'routine' });
	assert.equal(run.starts[0].source, source);
	assert.throws(() => run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: source + 'x' }));
	await run.notebook.writeNote(agent.agentId, { worldId, key: 'routine', text: `Metadata outside comments\n${savedSource}` });
	await assert.rejects(run.call('runProgram', { noteKey: 'routine' }));
});
