import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { DangerSteerCoalescer } from '../src/danger-steer-coalescer.mjs';
import { NO_TASK_HEAL_INSTRUCTION, TASK_HEAL_INSTRUCTION, buildNativeEventInput, classifyObservationTrigger, healNudgeVerdict, healWakeVerdict,
	healingFacts, threatOutlook } from '../src/dynamic-main.mjs';
import { threatFacts } from '../src/observation-adapter.mjs';
import { FakeBridge, FakePlanner, immutableGoalSpec, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

// Play-test 2026-10-08 (play-session-3-trace.jsonl). Sonnet, with no task, was targeted by a creeper. The first threat
// edge said risk 4 at 16 blocks; risk only reached 100-200 at about 3 blocks, and the model's first move came then
// (5.5 s decision). Later, at 6.1 HP and foodLevel 17 with a cow beside it, its one heal turn made no call; the raw
// beef Lucas then dropped never woke it again, and it died without eating.
const CREEPER = '00000000-0000-0000-0000-0000000000cc';
const COW = '00000000-0000-0000-0000-0000000000c1';
const BEEF = '00000000-0000-0000-0000-0000000000d1';
const NATIVE_CONFIG = {
	bridge: { port: 25570, secret: 's'.repeat(32) },
	codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
};
const settle = async () => { for (let index = 0; index < 8; index += 1) await new Promise((resolve) => setImmediate(resolve)); };

/** The creeper's wire row as ThreatPerception now reports it at a given distance, closing at 2.6 blocks per second. */
function creeperRow(distance, signals, { risk, contactRisk } = {}) {
	const eta = Math.round(Math.max(0, distance - 3) / 2.6 * 10) / 10;
	return { uuid: CREEPER, type: 'minecraft:creeper', distance, bearing: 0, targeting: true, swelling: signals.includes('swelling'), lineOfSight: true, signals,
		risk, expectedHitDamage: 0, closingSpeed: 2.6, approaching: true, etaSeconds: eta, ...(contactRisk === undefined ? {} : { contactRisk }) };
}

const creeperObservation = (row) => ({ player: { x: 0, y: 64, z: 0, health: 20, maxHealth: 20, ...threatFacts({ entries: [row] }) },
	items: [], entities: [], blocks: [], inventory: { items: [] } });

test('creeper timeline: the first edge already says when it arrives and how bad it gets; imminent escalates at once', () => {
	const first = creeperObservation(creeperRow(16, ['targeting'], { risk: 4.3, contactRisk: 145 }));
	assert.equal(first.player.threats[0].etaSeconds, 5, 'trend facts survive the adapter');
	assert.equal(first.player.threats[0].contactRisk, 145);
	assert.deepEqual(classifyObservationTrigger({ changedFacts: [`threats.${CREEPER}.targeting`] }, first), { attention: true, priority: 'urgent', trigger: 'threat' },
		'a hunter is urgent at first sight, whatever its risk number');
	const input = buildNativeEventInput({ goalRevision: 0 }, { event: 'observation', trigger: 'threat', observation: first, conversationOnly: true, dangerWake: true });
	const event = JSON.parse(input.slice(input.indexOf('\n') + 1));
	assert.equal(event.threatOutlook, `minecraft:creeper ${CREEPER} is 16 blocks away closing at 2.6 blocks/s: contact range in 5 s (risk 4.3 now, about 145 there).`);
	assert.equal(Object.keys(event).indexOf('threatOutlook'), 2, 'the outlook leads the event, right after event and trigger');

	// The imminent edge (contact in under 3 s) arrives while the model is still deciding: it must not wait 2 s in the fold.
	const steer = (observation) => ({ priority: 'urgent', trigger: 'threat', nativeEvent: { event: 'observation', trigger: 'threat', observation } });
	const coalescer = new DangerSteerCoalescer();
	coalescer.noteDelivered(steer(first), 0);
	assert.equal(coalescer.offer(steer(creeperObservation(creeperRow(14, ['targeting'], { risk: 5 }))), 500).action, 'fold', 'the same edge repeated folds');
	const imminent = coalescer.offer(steer(creeperObservation(creeperRow(10, ['imminent', 'targeting'], { risk: 7, contactRisk: 145 }))), 900);
	assert.equal(imminent.action, 'deliver', 'becoming imminent steers at once');
	assert.equal(coalescer.offer(steer(creeperObservation(creeperRow(4.5, ['creeper_close', 'imminent', 'targeting'], { risk: 80 }))), 1300).action, 'deliver',
		'closing within 5 blocks steers at once too');
	assert.equal(threatOutlook(creeperObservation({ ...creeperRow(10, ['targeting'], { risk: 7 }), approaching: false, closingSpeed: 0, etaSeconds: undefined })), null,
		'a creeper that stopped closing has no outlook line');
});

test('dropped beef: healing facts list it with the exact call, and say health will not regenerate at foodLevel 17', () => {
	const observation = { player: { x: 0, y: 64, z: 0, health: 6.13, maxHealth: 20, foodLevel: 17, safe: true, bestFood: null, threats: [] },
		entities: [{ stableId: COW, type: 'minecraft:cow', x: 3, y: 64, z: 0, distance: 3 }],
		items: [{ stableId: BEEF, itemId: 'minecraft:beef', count: 1, x: 1.5, y: 64, z: 0, distance: 1.5 }], blocks: [] };
	const facts = healingFacts(observation);
	assert.equal(facts.naturalRegen, false, 'vanilla regenerates only at foodLevel 18+');
	assert.deepEqual(facts.options, [
		`pick up 1x minecraft:beef 1.5 blocks away: pick_up_item targetSelector ${BEEF}, then eat it`,
		`hunt minecraft:cow 3 blocks away: fight_target targetId ${COW}, pick_up_item its drop, eat it`,
	]);
	assert.equal(healingFacts({ player: { health: 6, foodLevel: 18 } }).naturalRegen, true);
	assert.match(NO_TASK_HEAL_INSTRUCTION, /regenerates only while foodLevel is 18 or more/);
	assert.doesNotMatch(NO_TASK_HEAL_INSTRUCTION, /A full food bar regenerates health/, 'the vague line that let 17 read as "nearly full" is gone');
});

test('heal wake: new food in reach wakes a no-task agent again, once per source, while it is still low', () => {
	const low = (extra = {}) => ({ player: { x: 0, y: 64, z: 0, health: 6.13, maxHealth: 20, foodLevel: 17, ...extra },
		entities: [{ stableId: COW, type: 'minecraft:cow', x: 3, y: 64, z: 0 }], items: [], blocks: [] });
	const first = healWakeVerdict(low());
	assert.equal(first.wake, true);
	assert.deepEqual(first.latch.seenFood, [`animal:${COW}`]);
	const same = healWakeVerdict(low(), first.latch);
	assert.equal(same.wake, false, 'the same cow does not nag');
	const withBeef = { ...low(), items: [{ stableId: BEEF, itemId: 'minecraft:beef', count: 1, x: 1, y: 64, z: 0 }] };
	const dropped = healWakeVerdict(withBeef, same.latch);
	assert.equal(dropped.wake, true, 'beef thrown to it is a new chance (before: no wake until 2 more HP were lost)');
	assert.equal(healWakeVerdict(withBeef, dropped.latch).wake, false, 'and only once');
	assert.equal(healWakeVerdict({ ...withBeef, player: { ...withBeef.player, health: 12 } }, dropped.latch).wake, false, 'not when no longer low');
	assert.equal(healWakeVerdict({ ...withBeef, player: { ...withBeef.player, foodLevel: 20 } }, same.latch).wake, false, 'not when it cannot eat');
	assert.equal(healWakeVerdict({ ...withBeef, player: { ...withBeef.player, health: 15 } }, dropped.latch).latch, null, 'recovering to 70% clears it');
});

test('heal nudge with a task: 4 hearts and food in reach is one urgent edge, debounced', () => {
	const at = (health, extra = {}, items = []) => ({ player: { x: 0, y: 64, z: 0, health, maxHealth: 20, foodLevel: 15, safe: true, ...extra },
		entities: [{ stableId: COW, type: 'minecraft:cow', x: 3, y: 64, z: 0 }], items, blocks: [] });
	assert.equal(healNudgeVerdict(at(9), null, 0).nudge, false, 'above 4 hearts the ordinary heal_opportunity is enough');
	const first = healNudgeVerdict(at(8), null, 0);
	assert.equal(first.nudge, true);
	assert.equal(healNudgeVerdict(at(7), first.latch, 1_000).nudge, false, 'one point lower does not repeat it');
	assert.equal(healNudgeVerdict(at(6), first.latch, 5_000).nudge, false, '2 points lower, but within 10 s');
	assert.equal(healNudgeVerdict(at(6), first.latch, 10_000).nudge, true, '2 points lower after 10 s');
	const beef = [{ stableId: BEEF, itemId: 'minecraft:beef', count: 1, x: 1, y: 64, z: 0 }];
	assert.equal(healNudgeVerdict(at(8, {}, beef), first.latch, 12_000).nudge, true, 'new food in reach');
	assert.equal(healNudgeVerdict(at(8, { safe: false }), null, 0).nudge, false, 'not while a threat is near; danger edges cover that');
	assert.equal(healNudgeVerdict({ player: { health: 5, maxHealth: 20, foodLevel: 10, safe: true } }, null, 0).nudge, false, 'not with nothing in reach');
	assert.equal(healNudgeVerdict(at(8, { foodLevel: 20 }), null, 0).nudge, false, 'not when it cannot eat');
	assert.equal(healNudgeVerdict(at(8, { foodLevel: 20, bestFood: { slot: 1, itemId: 'minecraft:golden_apple', nutrition: 4 } }), null, 0).nudge, true,
		'a golden apple is always edible');
	assert.equal(healNudgeVerdict(at(14), first.latch, 20_000).latch, null, '70% resets it');
	assert.equal(healNudgeVerdict(at(5, { gameMode: 'creative' }), null, 0).nudge, false);
	assert.equal(healNudgeVerdict(at(5, { operatorControlled: true }), null, 0).nudge, false);

	const input = buildNativeEventInput({ goalRevision: 3, currentGoal: 'Mine iron.' }, { event: 'program_attention', trigger: 'program_attention',
		status: { state: 'RUNNING', engineState: 'ACTIVE', decision: { trigger: 'low_health_food', priority: 'urgent' } }, programId: 'p-1', observation: at(8) });
	assert.equal(input.split('\n')[0], TASK_HEAL_INSTRUCTION, 'a program notified of the nudge says what it is');
	const event = JSON.parse(input.slice(input.indexOf('\n') + 1));
	assert.equal(event.healing.options[0], `hunt minecraft:cow 3 blocks away: fight_target targetId ${COW}, pick_up_item its drop, eat it`);
	assert.doesNotMatch(TASK_HEAL_INSTRUCTION, /\b(must|now:)\b/, 'informs; the model decides');
});

function scriptedPlanner(registry) {
	const planner = new FakePlanner(registry);
	planner.outcomes = [];
	planner.steerNativeTurn = async (request) => { planner.steers = [...(planner.steers ?? []), request]; };
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) {
			await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'turn-1', callId: 'call-1', tool: { kind: 'finish', summary: 'Done.' } })
				.then((result) => planner.outcomes.push(result), (error) => planner.outcomes.push(error));
		}
		return { status: 'completed', toolCalls: planner.requests.length === 1 ? 1 : 0 };
	};
	return planner;
}

test('trace replay: after the heal turn makes no call, the dropped beef wakes the agent again with the exact pickup', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry);
	const traces = [];
	const run = await start({ bridge, registry, planner, config: NATIVE_CONFIG, traceWriter: { write(event, details) { traces.push({ event, ...details }); } } });
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Craft a table.', goalSpec: immutableGoalSpec('Craft a table.') } });
		bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 } } } });
		await eventually(() => planner.outcomes.length === 1);
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'complete', goalRevision: 2 } });
		await eventually(() => registry.get('agent-a').state === DynamicAgentState.COMPLETED);
		const low = (eventSequence, items = []) => ({ agentId: 'agent-a', payload: { goalRevision: 2, eventSequence, observation: {
			// The fixture carries drops only (no living entities), and foodLevel as hunger.
			player: { x: 0, y: 64, z: 0, health: 6.13, maxHealth: 20, hunger: 17 }, blocks: [], items } } });
		bridge.emit('observation', low(2));
		await eventually(() => planner.requests.length === 2);
		bridge.emit('observation', low(3));
		await settle();
		assert.equal(planner.requests.length, 2, 'the heal turn made no call; the same facts do not wake it again');
		bridge.emit('observation', low(4, [{ stableId: BEEF, itemId: 'minecraft:beef', count: 1, x: 1, y: 64, z: 0 }]));
		await eventually(() => planner.requests.length === 3);
		const event = JSON.parse(planner.requests[2].input.slice(planner.requests[2].input.indexOf('\n') + 1));
		assert.equal(event.trigger, 'low_health_idle');
		assert.equal(event.healing.naturalRegen, false);
		assert.match(event.healing.options[0], new RegExp(`pick_up_item targetSelector ${BEEF}`));
		assert.equal(traces.filter(({ event: name }) => name === 'native_heal_wake').length, 2);
	} finally {
		await run.coordinator.stop();
	}
});

test('heal nudge with a task steers the deciding turn once, with the food options, and never interrupts it', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	const traces = [];
	planner.requestNativeTurn = async (request) => { planner.requests.push(request); if (planner.requests.length === 1) await gate; return { status: 'completed', toolCalls: 1 }; };
	const all = [];
	// Health drops are damage steers of their own; only the heal nudges are counted in steers.
	planner.steerNativeTurn = async (request) => {
		all.push(request.input);
		if (request.input.startsWith(TASK_HEAL_INSTRUCTION)) steers.push(request.input);
		return { turnId: 'deciding' };
	};
	const run = await start({ registry, planner, config: NATIVE_CONFIG, traceWriter: { write(event, details) { traces.push({ event, ...details }); } } });
	let sequence = 0;
	const observe = (health, items = []) => run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence,
		observation: { player: { x: 0, y: 64, z: 0, health, hunger: 15 }, items, entities: [], blocks: [], inventory: { items: [] } } } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Mine iron.' } });
		observe(20);
		await eventually(() => planner.requests.length === 1);
		const beef = [{ stableId: BEEF, itemId: 'minecraft:beef', count: 1, x: 2, y: 64, z: 0 }];
		observe(9, beef);
		await settle();
		assert.equal(steers.length, 0, 'above 4 hearts nothing is raised beyond the ordinary heal facts');
		assert.ok(all.length > 0, 'the drop to 9 was a damage steer');
		observe(8, beef);
		await settle();
		assert.equal(steers.length, 0, 'a hit is damage attention first; the nudge never rides on top of it');
		observe(8, beef);
		await eventually(() => steers.length === 1);
		const event = JSON.parse(steers[0].slice(steers[0].indexOf('\n') + 1));
		assert.equal(event.trigger, 'low_health_food');
		assert.equal(event.healing.foodLevel, 15);
		assert.match(event.healing.options[0], new RegExp(`pick_up_item targetSelector ${BEEF}`));
		observe(7.5, beef);
		observe(7.5, beef);
		await settle();
		assert.equal(steers.length, 1, 'debounced: the same food at about the same health is not repeated');
		assert.deepEqual(planner.interruptions, [], 'the model keeps its turn');
		assert.equal(traces.filter(({ event: name }) => name === 'native_heal_nudge').length, 1);
	} finally { release(); await run.coordinator.stop(); }
});
