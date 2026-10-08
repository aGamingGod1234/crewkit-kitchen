import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry } from '../src/agent-registry.mjs';
import { DANGER_STEER_INTERVAL_MS, DangerSteerCoalescer, dangerSteerFacts } from '../src/danger-steer-coalescer.mjs';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { validateAction } from '../src/schema.mjs';
import { FakePlanner, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

const ZOMBIE_A = '00000000-0000-0000-0000-0000000000a1';
const ZOMBIE_B = '00000000-0000-0000-0000-0000000000a2';
const CREEPER = '00000000-0000-0000-0000-0000000000cc';
const tick = () => new Promise(resolve => setImmediate(resolve));

function threat(uuid, type, distance, { swelling = false } = {}) {
	return { uuid, type, distance, bearing: 0, targeting: true, swelling, lineOfSight: true, signals: swelling ? ['swelling'] : ['targeting'] };
}

/** An adapted observation of the agent being hit, as dynamic-main hands it to steering. */
function hurt(health, threats = [threat(ZOMBIE_B, 'minecraft:zombie', 2)]) {
	return { player: { x: 0, y: 64, z: 0, health, lastAttacker: { uuid: ZOMBIE_B, type: 'minecraft:zombie', distance: 2 }, threats, threat: threats[0] ?? null },
		items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } };
}

const damageSteer = (health, threats) => ({ priority: 'urgent', trigger: 'damage', nativeEvent: { event: 'observation', trigger: 'damage', observation: hurt(health, threats) } });

test('fight_target accepts the model opt-out continueWithAttackers and rejects a non-boolean', () => {
	const action = validateAction({ type: 'fight_target', targetId: ZOMBIE_A, timeoutMs: 15_000, continueWithAttackers: false });
	assert.equal(action.continueWithAttackers, false);
	assert.throws(() => validateAction({ type: 'fight_target', targetId: ZOMBIE_A, timeoutMs: 15_000, continueWithAttackers: 'yes' }));
	const tool = normalizeMinecraftToolCall('act', { actionType: 'fight_target', arguments: { targetId: ZOMBIE_A, timeoutMs: 15_000, continueWithAttackers: false } });
	assert.equal(tool.arguments.continueWithAttackers, false);
});

test('danger steering: the first hit steers at once, repeats fold into one timed summary', () => {
	const coalescer = new DangerSteerCoalescer();
	assert.equal(coalescer.offer(damageSteer(18), 0).action, 'deliver');
	const folded = coalescer.offer(damageSteer(16), 600);
	assert.equal(folded.action, 'fold');
	assert.equal(folded.dueInMs, DANGER_STEER_INTERVAL_MS - 600);
	assert.equal(coalescer.offer(damageSteer(15), 1200).action, 'fold');
	const flushed = coalescer.flush(DANGER_STEER_INTERVAL_MS);
	assert.deepEqual(flushed.dangerSummary, { foldedEvents: 2, hitsSinceLastUpdate: 2, healthAtLastUpdate: 18, healthNow: 15, attackers: ['minecraft:zombie'] });
	assert.equal(flushed.nativeEvent.observation.player.health, 15, 'the latest facts are delivered, not the first folded ones');
	assert.equal(coalescer.flush(DANGER_STEER_INTERVAL_MS + 1), null);
	assert.equal(coalescer.offer(damageSteer(14), DANGER_STEER_INTERVAL_MS * 2 + 1).action, 'deliver', 'after the interval the next hit steers again');
});

test('danger steering: materially new facts are never held back', () => {
	const coalescer = new DangerSteerCoalescer();
	coalescer.noteDelivered(damageSteer(18), 0);
	assert.equal(coalescer.offer(damageSteer(17), 100).action, 'fold', 'the turn input already carried the first hit');
	const creeper = coalescer.offer(damageSteer(17, [threat(ZOMBIE_B, 'minecraft:zombie', 2), threat(CREEPER, 'minecraft:creeper', 6)]), 200);
	assert.equal(creeper.action, 'deliver', 'a new threat type steers at once');
	assert.equal(creeper.request.dangerSummary.foldedEvents, 1, 'and carries the hits folded before it');
	const swelling = coalescer.offer(damageSteer(17, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]), 300);
	assert.equal(swelling.action, 'deliver', 'a creeper starting to swell steers at once');
	assert.equal(coalescer.offer(damageSteer(13, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]), 350).action, 'deliver', 'crossing 70% health steers at once');
	assert.equal(coalescer.offer(damageSteer(11, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]), 400).action, 'fold');
	assert.equal(coalescer.offer(damageSteer(10, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]), 500).action, 'deliver', 'crossing half health steers at once');
	assert.equal(coalescer.offer({ priority: 'urgent', trigger: 'threat', nativeEvent: { event: 'observation', observation: hurt(10, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]) } }, 600).action, 'deliver', 'a new trigger kind steers at once');
	assert.equal(coalescer.offer(damageSteer(9, [threat(CREEPER, 'minecraft:creeper', 5, { swelling: true })]), 700).action, 'fold');
	const chat = coalescer.offer({ priority: 'urgent', trigger: 'conversation', nativeEvent: { event: 'conversation' } }, 800);
	assert.equal(chat.action, 'deliver', 'non-danger steers are never delayed');
	assert.equal(chat.request.dangerSummary.hitsSinceLastUpdate, 1, 'and carry the folded hits');
	assert.equal(dangerSteerFacts({ trigger: 'lava' }), null, 'only damage and threat steers are coalesced');
	assert.equal(dangerSteerFacts({ trigger: 'defensive_handler_completed', nativeEvent: { event: 'program_attention', status: { decision: { trigger: 'damage' } } } }).trigger, 'damage',
		'program attention is classified by its decision trigger');
});

test('a damage-paused program shows the one-line danger instruction; ordinary decisions keep theirs', () => {
	const record = { agentId: 'agent-a', goalRevision: 1, currentGoal: 'Survive the night.' };
	const paused = buildNativeEventInput(record, { event: 'program_attention', trigger: 'damage', programId: 'p1',
		status: { state: 'RUNNING', engineState: 'SUSPENDED', programVersion: 1, decision: { decisionId: 'p1:decision-2', trigger: 'damage', priority: 'urgent' } },
		observation: hurt(12), dangerSummary: { foldedEvents: 3, hitsSinceLastUpdate: 3, healthAtLastUpdate: 18, healthNow: 12, attackers: ['minecraft:zombie'] } });
	assert.equal(paused.split('\n')[0], 'Live Minecraft event. Danger needs attention: call fight_target or flee_from now; respond to the program later.');
	assert.equal(JSON.parse(paused.slice(paused.indexOf('\n') + 1)).dangerSinceLastUpdate.hitsSinceLastUpdate, 3);
	const ordinary = buildNativeEventInput(record, { event: 'program_attention', trigger: 'action_failure', programId: 'p1',
		status: { state: 'RUNNING', engineState: 'SUSPENDED', programVersion: 1, decision: { decisionId: 'p1:decision-1', trigger: 'action_failure' } }, observation: hurt(20) });
	assert.match(ordinary.split('\n')[0], /respond explicitly to pending program decisions/);
});

test('urgent program hints match the actual danger trigger', () => {
	for (const trigger of ['damage', 'threat', 'lava', 'fire']) {
		const input = buildNativeEventInput({ agentId: 'agent-a', goalRevision: 1, currentGoal: 'Survive the night.' }, {
			event: 'program_attention', trigger: 'program_attention', programId: 'p1',
			status: { state: 'RUNNING', engineState: 'ACTIVE', programVersion: 1,
				decision: { decisionId: 'p1:decision-2', trigger, priority: 'urgent' } },
			observation: hurt(12),
		});
		assert.match(input.split('\n')[0], /call fight_target or flee_from now/, `${trigger} supports a fight or flee response`);
	}
	for (const [trigger, expected] of [
		['suffocation', /Suffocation needs attention/],
		['fall', /Fall danger needs attention/],
		['defensive_handler_completed', /defensive handler finished/i],
	]) {
		const input = buildNativeEventInput({ agentId: 'agent-a', goalRevision: 1, currentGoal: 'Survive the night.' }, {
			event: 'program_attention', trigger: 'program_attention', programId: 'p1',
			status: { state: 'RUNNING', engineState: 'ACTIVE', programVersion: 1,
				decision: { decisionId: 'p1:decision-2', trigger, priority: 'urgent' } },
			observation: hurt(12),
		});
		assert.match(input.split('\n')[0], expected, `${trigger} should have a matching short hint`);
		assert.doesNotMatch(input.split('\n')[0], /call fight_target or flee_from now/, `${trigger} must not suggest an unrelated action`);
	}
});

// ---- Direct fight/flee while a program is paused for danger ----

const record = { agentId: 'survivor', provider: 'codex', model: 'test', reasoningEffort: 'high', goalRevision: 1 };

function runtimeHarness(t) {
	const sent = [];
	const events = [];
	let current = hurt(20);
	const runtime = new NativeToolRuntime({
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload: validateProtocolV2Payload(type, payload) }) },
		requestObservation: async (_record, { afterEventSequence }) => ({ observation: current, eventSequence: afterEventSequence + 1 }),
		programExecutor: new NativeProgramExecutor(),
		onProgramEvent: (_record, event) => events.push(event),
	});
	t.after(() => runtime.disposeAll());
	runtime.updateObservation(record, current, { eventSequence: 1 });
	let callId = 0;
	const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'turn', callId: String(++callId), tool: normalizeMinecraftToolCall(name, args) }, record);
	const commands = () => sent.filter(entry => entry.type === 'action_command');
	const finish = (command, state = 'SUCCEEDED', reasonCode = state) => runtime.onActionResult(record, { actionId: command.payload.actionId, goalRevision: 1, state, reasonCode });
	const hit = (health, eventSequence) => { current = hurt(health); runtime.updateObservation(record, current, { eventSequence, attention: true, priority: 'urgent', trigger: 'damage' }); };
	return { runtime, sent, events, call, commands, finish, hit };
}

async function pausedByDamage(h) {
	const handle = await h.call('runProgram', { background: true, timeoutMs: 60_000, source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1000); await player.wait(2);' });
	await tick();
	h.hit(17, 10);
	await tick();
	h.finish(h.commands()[0], 'CANCELLED');
	await tick();
	const status = await h.call('programStatus');
	assert.equal(status.engineState, 'SUSPENDED');
	assert.equal(status.decision.trigger, 'damage');
	return handle;
}

test('a damage-paused program lets the model fight directly and resumes later with its decision intact', async t => {
	const h = runtimeHarness(t);
	const handle = await pausedByDamage(h);
	await assert.rejects(h.call('act', { actionType: 'wait', arguments: { durationMs: 5 } }), { code: 'NATIVE_PROGRAM_IN_PROGRESS' },
		'only fight_target/flee_from bypass the paused program');
	const fight = h.call('act', { actionType: 'fight_target', arguments: { targetId: ZOMBIE_B, timeoutMs: 15_000 } });
	await tick();
	assert.equal(h.commands().length, 2, 'fight_target dispatches without respondProgram or programStatus first');
	assert.equal(h.commands()[1].payload.actionType, 'fight_target');
	const during = await h.call('programStatus');
	assert.equal(during.engineState, 'SUSPENDED', 'the program stays paused, not cancelled');
	await assert.rejects(h.call('respondProgram', { programId: handle.programId, goalRevision: 1, decisionId: during.decision.decisionId, directive: 'continue' }),
		{ code: 'NATIVE_ACTION_IN_PROGRESS' }, 'the program cannot resume under the running fight');
	h.finish(h.commands()[1], 'SUCCEEDED', 'TARGET_KILLED');
	assert.equal((await fight).reasonCode, 'TARGET_KILLED');
	const after = await h.call('programStatus');
	await h.call('respondProgram', { programId: handle.programId, goalRevision: 1, decisionId: after.decision.decisionId, directive: 'continue' });
	await tick();
	assert.equal(h.commands().length, 3, 'the paused routine resumes after the model responds');
	assert.equal(h.commands()[2].payload.actionType, 'wait');
});

test('replaceAction for danger starts as a new action when the program body handle already ended', async t => {
	const h = runtimeHarness(t);
	const handle = await pausedByDamage(h);
	const previous = h.commands()[0];
	const decisionBefore = (await h.call('programStatus')).decision.decisionId;
	const replacement = await h.call('replaceAction', { actionId: previous.payload.actionId, goalRevision: 1,
		actionType: 'fight_target', arguments: { targetId: ZOMBIE_B, timeoutMs: 15_000 } });
	assert.equal(replacement.state, 'RUNNING');
	assert.equal(replacement.startedAs, 'start_action');
	assert.deepEqual(replacement.replacedAction, { actionId: previous.payload.actionId, state: 'CANCELLED' });
	assert.equal(h.commands().length, 2);
	assert.equal(h.commands()[1].payload.actionType, 'fight_target');
	assert.equal((await h.call('programStatus', { programId: handle.programId })).decision.decisionId, decisionBefore,
		'the program decision stays live for a later continue, pause or finish');
	h.finish(h.commands()[1], 'SUCCEEDED', 'TARGET_KILLED');
});

test('replaceAction does not start from a successful old receipt even while danger-paused', async t => {
	const h = runtimeHarness(t);
	const handle = await h.call('runProgram', { background: true, timeoutMs: 60_000,
		source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1000); await player.wait(2);' });
	await tick();
	const completed = h.commands()[0];
	h.finish(completed, 'SUCCEEDED', 'ACTION_COMPLETED');
	await tick();
	const active = h.commands()[1];
	h.hit(17, 10);
	await tick();
	h.finish(active, 'CANCELLED');
	await tick();
	assert.equal((await h.call('programStatus')).engineState, 'SUSPENDED');
	const replacement = await h.call('replaceAction', { actionId: completed.payload.actionId, goalRevision: 1,
		actionType: 'fight_target', arguments: { targetId: ZOMBIE_B, timeoutMs: 15_000 } });
	assert.equal(replacement.state, 'REPLACEMENT_NOT_STARTED');
	assert.equal(replacement.reasonCode, 'ACTION_FINISHED_BEFORE_CANCEL');
	assert.equal(h.commands().length, 2, 'the stale receipt cannot start an unrequested replacement');
	assert.equal((await h.call('programStatus', { programId: handle.programId })).engineState, 'SUSPENDED');
});

test('cancelling a direct flee taken during the pause leaves the paused program for the model', async t => {
	const h = runtimeHarness(t);
	await pausedByDamage(h);
	const flee = await h.call('startAction', { actionType: 'flee_from', arguments: { targetId: ZOMBIE_B, distance: 12, timeoutMs: 8_000 } });
	assert.equal(h.commands().at(-1).payload.actionType, 'flee_from');
	const cancelling = h.call('cancelAction', { actionId: flee.actionId, goalRevision: 1 });
	await tick();
	h.finish(h.commands().at(-1), 'CANCELLED');
	await cancelling;
	const status = await h.call('programStatus');
	assert.equal(status.state, 'RUNNING');
	assert.equal(status.engineState, 'SUSPENDED');
	assert.ok(status.decision.decisionId);
});

test('fight/flee are still refused while the program owns the body (not paused)', async t => {
	const h = runtimeHarness(t);
	await h.call('runProgram', { background: true, timeoutMs: 60_000, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1000);' });
	await tick();
	await assert.rejects(h.call('act', { actionType: 'fight_target', arguments: { targetId: ZOMBIE_B, timeoutMs: 15_000 } }), { code: 'NATIVE_PROGRAM_IN_PROGRESS' });
});

// ---- The live trace, replayed through the coordinator ----

test('trace replay: kill, then a second zombie hitting every ~1.1 s no longer steers the turn 13 times', async () => {
	let release;
	const gate = new Promise(resolve => { release = resolve; });
	const clock = { now: 0, timers: new Map(), id: 0 };
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	const traces = [];
	planner.requestNativeTurn = async request => { planner.requests.push(request); if (planner.requests.length === 1) await gate; return { status: 'completed', toolCalls: 1 }; };
	planner.steerNativeTurn = async request => { steers.push({ at: clock.now, input: request.input }); return { turnId: 'deciding' }; };
	const run = await start({ registry, planner, controlNow: () => clock.now,
		setSteerTimeout: (callback, delay) => { const id = ++clock.id; clock.timers.set(id, { callback, due: clock.now + delay }); return id; },
		clearSteerTimeout: id => clock.timers.delete(id),
		traceWriter: { write(event, details) { traces.push({ event, ...details }); } },
		config: { bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } } });
	const advance = async ms => {
		clock.now += ms;
		for (const [id, timer] of [...clock.timers]) if (timer.due <= clock.now) { clock.timers.delete(id); timer.callback(); }
		for (let index = 0; index < 6; index++) await tick();
	};
	let sequence = 0;
	const wire = (health, extraThreats = []) => ({ player: { x: 0, y: 64, z: 0, health, lastAttacker: { uuid: ZOMBIE_B, type: 'minecraft:zombie', distance: 2 } },
		threats: { entries: [threat(ZOMBIE_B, 'minecraft:zombie', 2), ...extraThreats] },
		items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Survive the night.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, observation: wire(20) } });
		await eventually(() => planner.requests.length === 1);
		// Zombie 1 is dead; zombie 2 lands 13 hits about 1.1 s apart while the model is still deciding (trace lines 238-260).
		let currentHealth = 20;
		for (let hitIndex = 0; hitIndex < 13; hitIndex++) {
			currentHealth -= 1;
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, changedFacts: ['player.health'], observation: wire(currentHealth) } });
			await advance(1_100);
		}
		const health = (steer) => JSON.parse(steer.input.slice(steer.input.indexOf('\n') + 1)).observation.player.health;
		assert.ok(steers.length < 13, `per-hit steering must be coalesced (got ${steers.length})`);
		assert.ok(steers.length <= Math.ceil((13 * 1_100) / DANGER_STEER_INTERVAL_MS) + 2, `about one steer per ${DANGER_STEER_INTERVAL_MS} ms (got ${steers.length})`);
		for (let index = 1; index < steers.length; index++) {
			const gap = steers[index].at - steers[index - 1].at;
			const crossedHalf = [14, 10, 6].some((threshold) => health(steers[index - 1]) > threshold && health(steers[index]) <= threshold);
			assert.ok(gap >= DANGER_STEER_INTERVAL_MS || crossedHalf, `steers ${index - 1}->${index} only ${gap} ms apart without new facts`);
		}
		// A creeper appears: materially new, delivered immediately even inside the interval.
		const before = steers.length;
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, changedFacts: ['player.health'], observation: wire(currentHealth - 1, [threat(CREEPER, 'minecraft:creeper', 6)]) } });
		await advance(10);
		assert.equal(steers.length, before + 1, 'a new threat type is not held back');
		const folded = traces.filter(row => row.event === 'native_turn_steer_coalesced').length;
		const reported = steers.map(steer => JSON.parse(steer.input.slice(steer.input.indexOf('\n') + 1)).dangerSinceLastUpdate?.foldedEvents ?? 0)
			.reduce((total, count) => total + count, 0);
		assert.ok(folded > 0);
		assert.equal(reported, folded, 'every folded hit is reported in a later steer summary');
		assert.equal(health(steers.at(-1)), currentHealth - 1, 'the model always ends up with the latest health');
	} finally { release(); await run.coordinator.stop(); }
});

test('danger and drowning attention during a deciding native turn steer it and never interrupt it', async () => {
	let release;
	const gate = new Promise(resolve => { release = resolve; });
	const clock = { now: 0, timers: new Map(), id: 0 };
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	planner.requestNativeTurn = async request => { planner.requests.push(request); if (planner.requests.length === 1) await gate; return { status: 'completed', toolCalls: 1 }; };
	planner.steerNativeTurn = async request => { steers.push({ at: clock.now, input: request.input }); return { turnId: 'deciding' }; };
	const run = await start({ registry, planner, controlNow: () => clock.now,
		setSteerTimeout: (callback, delay) => { const id = ++clock.id; clock.timers.set(id, { callback, due: clock.now + delay }); return id; },
		clearSteerTimeout: id => clock.timers.delete(id),
		config: { bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } } });
	const advance = async ms => {
		clock.now += ms;
		for (const [id, timer] of [...clock.timers]) if (timer.due <= clock.now) { clock.timers.delete(id); timer.callback(); }
		for (let index = 0; index < 6; index++) await tick();
	};
	let sequence = 0;
	const wire = (health, air = 300) => ({ player: { x: 0, y: 64, z: 0, health, air, lastAttacker: { uuid: ZOMBIE_B, type: 'minecraft:zombie', distance: 2 } },
		threats: { entries: [threat(ZOMBIE_B, 'minecraft:zombie', 2)] },
		items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Survive the night.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, observation: wire(20) } });
		await eventually(() => planner.requests.length === 1);
		let health = 20;
		for (let hit = 0; hit < 8; hit++) {
			health -= 1;
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, changedFacts: ['player.health'], observation: wire(health) } });
			await advance(400);
		}
		const before = steers.length;
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, attention: true, changedFacts: ['player.air'], observation: wire(health, 150) } });
		await advance(10);
		await eventually(() => steers.length > before);
		assert.deepEqual(planner.interruptions, [], 'urgent attention never cancels the turn the model is reasoning in');
		assert.equal(planner.requests.length, 1, 'no replacement turn was started');
		assert.ok(steers.length > 0 && steers.length < 9, `danger hits are steered and coalesced (got ${steers.length})`);
		assert.equal(steers.length, before + 1, 'the half-air drowning warning is delivered at once, not folded');
		assert.match(steers.at(-1).input, /"air":150/);
	} finally { release(); await run.coordinator.stop(); }
});
