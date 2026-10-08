import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { remainingTopLevelCommands, straightLineCommands } from '../src/arena-script/remaining-work.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const record = { agentId: 'survivor', provider: 'codex', model: 'test', reasoningEffort: 'high', goalRevision: 1 };
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const observation = () => ({ ready: true, player: { x: 0, y: 64, z: 0, health: 20, dead: false }, world: { worldId: 'fixture-world', dimension: 'minecraft:overworld' },
	inventory: { items: [], tagCounts: {} }, items: [], entities: [], blocks: [] });
const tick = () => new Promise((resolve) => setImmediate(resolve));
const body = (source) => parseArenaScript(source).ast.body;
const remainingAfter = (source, index) => remainingTopLevelCommands(body(source), index);

test('straight-line source reports the commands it has left', () => {
	const source = `${prefix}
		await player.lookAt({ x: 1, y: 64, z: 1 });
		await player.mine({ x: 1, y: 64, z: 1, expectedBlockId: "minecraft:dirt" });
		const result = tryResult(await player.wait(5));
		if (inventory.count("minecraft:dirt") > 0) { await player.wait(1); await player.wait(2); } else await player.wait(3);
		await player.chat({ message: "done" });`;
	assert.equal(remainingAfter(source, 1), 5, 'the running statement is excluded; an if counts its longer branch');
	assert.equal(remainingAfter(source, 3), 3, 'the longer branch of the if (2) and the chat');
	assert.equal(remainingAfter(source, 4), null, 'a statement with several possible commands is not the one running');
	assert.equal(remainingAfter(source, 5), 0);
	assert.equal(straightLineCommands(body(`${prefix} await player.wait(1); const h = player.state().health;`)), 1);
});

test('loops, user functions, early exits and callbacks make the remainder unknowable', () => {
	const tail = 'await player.wait(1);';
	for (const rest of [
		'for (let i = 0; i < 3; i++) { await player.wait(1); }',
		'for (const item of [1, 2]) { await player.wait(item); }',
		'await program.repeatUntil(() => true, { maxIterations: 2 }, async () => { await player.wait(1); });',
		'async function step() { await player.wait(1); } await step();',
		'program.finish("done");',
	]) assert.equal(remainingAfter(`${prefix} ${tail} ${rest}`, 1), null, rest);
	assert.equal(remainingAfter(`${prefix} for (let i = 0; i < 3; i++) { await player.wait(1); } await player.wait(2);`, 1), null, 'a half-run loop is not one command');
	assert.equal(remainingAfter(`${prefix} program.watch(() => player.state().health < 5, { mode: "interrupt" }, async () => { await player.wait(9); }); ${tail} await player.wait(2);`, 2), 1,
		'registering a watcher issues nothing');
});

function clockedExecutor() {
	const clock = { now: 0 };
	const executor = new NativeProgramExecutor({ now: () => clock.now });
	return { clock, executor };
}

/** Each action takes 1000 ms on the fake clock; returns the advisories the model would have received. */
async function runWaits({ count, planningLeadMs = 5000, planningFloorMs = 2000, expectedDurationMs, allow, source }) {
	const { clock, executor } = clockedExecutor();
	const advisories = [];
	let sequence = 1;
	const dispatched = [];
	const result = await executor.run(record, {
		source: source ?? `${prefix} ${Array.from({ length: count }, () => 'await player.wait(1);').join(' ')}`,
		planningLeadMs, planningFloorMs, ...(expectedDurationMs === undefined ? {} : { expectedDurationMs }),
	}, {
		observation: observation(), eventSequence: sequence,
		executeAction: async (command) => { dispatched.push(command); await Promise.resolve(); clock.now += 1000; return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
		cancelAction: async () => {},
		refreshObservation: async () => ({ observation: observation(), eventSequence: ++sequence }),
		onPlanningDue: (_status, details) => advisories.push({ at: dispatched.length, details }),
		...(allow === undefined ? {} : { allowRemainingWorkAdvisory: allow }),
	});
	return { advisories, result };
}

test('a short straight-line program advises once, when its remaining work fits inside the decision time', async () => {
	const { advisories, result } = await runWaits({ count: 10 });
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(advisories.length, 1);
	assert.equal(advisories[0].at, 6, 'after five results the sixth is in flight: five commands (5 s) left equals the 5 s lead, and earlier boundaries are outside it');
	assert.deepEqual(advisories[0].details, { planningLeadMs: 5000, trigger: 'remaining_work', commands: 5, estimatedMs: 5000 });
});

test('no advisory when the remainder is already shorter than a typical decision', async () => {
	const { advisories } = await runWaits({ count: 3 });
	assert.deepEqual(advisories, [], 'two commands (2 s) left is inside the floor: the exhaustion wake covers it');
	const late = await runWaits({ count: 10, planningFloorMs: 6000 });
	assert.deepEqual(late.advisories, [], 'a floor above the lead leaves no window');
});

test('no remaining-work advisory without a measured lead, for loops, or when the model gave its own estimate', async () => {
	assert.deepEqual((await runWaits({ count: 10, planningLeadMs: 0 })).advisories, []);
	assert.deepEqual((await runWaits({ count: 10, expectedDurationMs: 9000 })).advisories, [], 'expectedDurationMs keeps the model-timed advisory');
	const loop = await runWaits({ source: `${prefix} for (let i = 0; i < 10; i++) { await player.wait(1); }` });
	assert.deepEqual(loop.advisories, []);
	assert.deepEqual((await runWaits({ count: 10, allow: () => false })).advisories, [], 'the caller can withhold it');
});

function setup(t, overrides = {}) {
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload: validateProtocolV2Payload(type, payload) }) },
		requestObservation: async (_record, { afterEventSequence }) => ({ observation: observation(), eventSequence: afterEventSequence + 1 }),
		...overrides,
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, observation(), { eventSequence: 1 });
	let callId = 0;
	const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision, turnId: 'turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const commands = () => sent.filter((entry) => entry.type === 'action_command');
	const finish = (command) => runtime.onActionResult(record, { actionId: command.payload.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE' });
	return { runtime, call, commands, finish };
}

function chainingRuntime(t) {
	const events = [];
	const clock = { now: 0 };
	const run = setup(t, { programExecutor: new NativeProgramExecutor({ now: () => clock.now }), planningLeadTime: () => 5000, planningFloorTime: () => 2000,
		onProgramEvent: (_record, event) => events.push(event) });
	const due = () => events.filter((event) => event.event === 'program_planning_due').length;
	const queueSuccessor = (handle) => run.call('queueProgram', { afterProgramId: handle.programId, goalRevision: 1, programVersion: handle.programVersion, source: `${prefix} await player.wait(2);`, precondition: 'true' });
	const finishNext = async () => { await tick(); clock.now += 1000; run.finish(run.commands().at(-1)); };
	/** Runs a program of `count` one-second waits to exhaustion and returns how many early advisories it got. */
	const exhaust = async (count, { queue = false } = {}) => {
		const before = due();
		const handle = await run.call('runProgram', { background: true, source: `${prefix} ${'await player.wait(1); '.repeat(count)}` });
		if (queue) { await tick(); await queueSuccessor(handle); }
		for (let index = 0; index < count; index += 1) await finishNext();
		await tick(); await tick(); await tick();
		if (queue) { await finishNext(); await tick(); await tick(); }
		return due() - before;
	};
	return { run, events, clock, due, exhaust, queueSuccessor, finishNext };
}

test('an advisory lets the model queue a successor that then starts without a wake', async (t) => {
	const { run, events, due, exhaust, queueSuccessor, finishNext } = chainingRuntime(t);
	assert.equal(await exhaust(8, { queue: true }), 0, 'queueing unaided proves the model chains');
	const endedBefore = events.filter((event) => event.event === 'program_ended').length;
	const handle = await run.call('runProgram', { background: true, source: `${prefix} ${'await player.wait(1); '.repeat(8)}` });
	for (let index = 0; index < 3; index += 1) await finishNext();
	await tick(); await tick();
	assert.equal(due(), 1, 'fires once, with five commands (5 s) left');
	const advisory = events.findLast((event) => event.event === 'program_planning_due');
	assert.equal(advisory.status.programId, handle.programId);
	assert.equal((await queueSuccessor(handle)).state, 'QUEUED');
	const commandsBefore = run.commands().length;
	for (let index = 0; index < 5; index += 1) await finishNext();
	await tick(); await tick(); await tick();
	assert.equal(run.commands().length, commandsBefore + 5, 'the four remaining actions ran, then the queued successor started its first');
	assert.equal(events.filter((event) => event.event === 'program_ended').length, endedBefore, 'a handed-off predecessor does not wake the model');
	assert.equal(due(), 1);
});

test('early advisories stay off until the model queues a successor itself, and stop after two misses', async (t) => {
	const { exhaust } = chainingRuntime(t);
	assert.equal(await exhaust(8), 0, 'a model that has never queued a successor gets no early advisory, so the advisory cannot add a call');
	assert.equal(await exhaust(8, { queue: true }), 0, 'queueing on its own proves it chains');
	assert.equal(await exhaust(8), 1);
	assert.equal(await exhaust(8), 1);
	assert.equal(await exhaust(8), 0, 'two misses in a row close the gate');
	assert.equal(await exhaust(8), 0);
	assert.equal(await exhaust(8, { queue: true }), 0);
	assert.equal(await exhaust(8), 1, 'the next own queue opens it again');
});
