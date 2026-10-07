import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptEngine } from '../src/arena-script/program-engine.mjs';
import { classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { adaptObservation, threatFacts } from '../src/observation-adapter.mjs';
import { PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';

const ZOMBIE = '00000000-0000-0000-0000-0000000000aa';
const CREEPER = '00000000-0000-0000-0000-0000000000cc';

function threatEntry(uuid, type, distance, signals) {
	return { uuid, type, distance, bearing: 170, targeting: signals.includes('targeting'), swelling: signals.includes('swelling'), lineOfSight: true, signals };
}

function factsWith(threats = []) {
	const adapted = threatFacts(threats.length === 0 ? undefined : { entries: threats, bestWeapon: { slot: 0, itemId: 'minecraft:iron_sword' } });
	return {
		player: { x: 0, y: 64, z: 0, health: 20, ...adapted },
		items: [], entities: [], blocks: [],
		inventory: { items: [], tagCounts: {} },
	};
}

function engineFor(source) {
	const dispatched = [];
	const modelRequests = [];
	const cancelled = [];
	const engine = new ArenaScriptEngine({
		dispatch: (command) => dispatched.push(command),
		cancel: (actionId) => cancelled.push(actionId),
		requestModel: (context) => modelRequests.push(context),
	});
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1,
		compiled: parseArenaScript(source), observation: factsWith(), eventSequence: 1,
	});
	return { engine, dispatched, cancelled, modelRequests };
}

/** The exact watcher example the model is shown, extracted so the prompt and its behaviour cannot drift apart. */
function promptWatcherExample() {
	const start = PLANNER_SYSTEM_PROMPT.indexOf('program.watch(() => player.state().threat !== null');
	const end = PLANNER_SYSTEM_PROMPT.indexOf('}). Its guard fires', start);
	assert.ok(start > 0 && end > start, 'the prompt carries a threat watcher example');
	return `${PLANNER_SYSTEM_PROMPT.slice(start, end + 2)};`;
}

test('a server threat edge is urgent "threat" attention, after damage but before ordinary changes', () => {
	assert.deepEqual(classifyObservationTrigger({ changedFacts: [`threats.${ZOMBIE}.targeting`] }, {}),
		{ attention: true, priority: 'urgent', trigger: 'threat' });
	assert.equal(classifyObservationTrigger({ changedFacts: ['player.health', `threats.${ZOMBIE}.targeting`] }, {}).trigger, 'damage');
	assert.deepEqual(classifyObservationTrigger({ trigger: 'threat' }, {}), { attention: true, priority: 'urgent', trigger: 'threat' });
	assert.equal(classifyObservationTrigger({ changedFacts: [`entities.${ZOMBIE}`], attention: true }, {}).priority, 'ordinary');
});

test('threat facts are always present on the player and the most urgent threat wins over the nearest', () => {
	assert.deepEqual(threatFacts(undefined), { threats: [], threat: null, bestWeapon: null });
	const facts = threatFacts({ entries: [threatEntry(CREEPER, 'minecraft:creeper', 6, ['swelling']), threatEntry(ZOMBIE, 'minecraft:zombie', 3, ['targeting'])],
		bestWeapon: { slot: 1, itemId: 'minecraft:stone_sword' } });
	assert.deepEqual(facts.threats.map((threat) => threat.uuid), [ZOMBIE, CREEPER], 'threats are nearest first');
	assert.equal(facts.threat.uuid, CREEPER, 'a swelling creeper outranks a closer zombie');
	assert.equal(facts.threat.stableId, CREEPER, 'the threat id can be copied as an exact targetId');
	assert.deepEqual(facts.bestWeapon, { slot: 1, itemId: 'minecraft:stone_sword' });
	const wire = {
		ready: true, position: { x: 0, y: 64, z: 0 }, view: { yaw: 0, pitch: 0 }, player: { health: 20 },
		entities: [{ uuid: ZOMBIE, type: 'minecraft:zombie', name: 'Zombie', distance: 3, position: { x: 0, y: 64, z: -3 },
			hostile: true, alive: true, health: 20, maxHealth: 20, targetingAgent: true, perceivedBy: 'sound' }],
		blocks: [], inventory: { items: [] }, threats: { entries: [threatEntry(ZOMBIE, 'minecraft:zombie', 3, ['targeting'])] },
	};
	const adapted = adaptObservation(wire);
	assert.equal(adapted.player.threat.uuid, ZOMBIE);
	assert.equal(adapted.entities[0].targetingAgent, true);
	assert.equal(adapted.entities[0].perceivedBy, 'sound', 'mobs heard behind the agent keep their perception source');
});

test('fight and flee calls require exact observed target ids', () => {
	parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.fightTarget({ targetId: "${ZOMBIE}", timeoutMs: 15000, fleeAtHealth: 6 }); await player.fleeFrom({ targetId: "${CREEPER}", distance: 10, timeoutMs: 8000 });`);
	assert.throws(() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.fightTarget({ targetSelector: "nearest_hostile", timeoutMs: 1000 });'), /targetId/);
	assert.throws(() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.fleeFrom({ targetId: "nearest_hostile", distance: 8, timeoutMs: 1000 });'), /nearest/);
});

test('an unhandled threat pauses the routine and wakes the model before any damage', () => {
	const run = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1000); await player.wait(2);');
	run.engine.ingestObservation({ observation: factsWith([threatEntry(ZOMBIE, 'minecraft:zombie', 8, ['targeting'])]), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'threat' });
	assert.deepEqual(run.cancelled, [run.dispatched[0].actionId], 'unrelated work is released');
	assert.equal(run.modelRequests.length, 1);
	assert.equal(run.modelRequests.at(-1).trigger, 'threat');
});

test('the prompt watcher example flees a creeper and fights a zombie at observation speed, and the model is still notified', () => {
	const example = promptWatcherExample();
	for (const [uuid, type, signals, primitive, expected] of [
		[CREEPER, 'minecraft:creeper', ['swelling'], 'flee_from', { targetId: CREEPER, distance: 10, timeoutMs: 8000 }],
		[ZOMBIE, 'minecraft:zombie', ['targeting'], 'fight_target', { targetId: ZOMBIE, timeoutMs: 15000, fleeAtHealth: 6 }],
	]) {
		const run = engineFor(`program.onUnhandledAttention("continue_and_notify"); ${example} await player.wait(1000);`);
		assert.equal(run.dispatched.length, 1, 'mining work runs while nothing threatens');
		run.engine.ingestObservation({ observation: factsWith([threatEntry(uuid, type, 4, signals)]), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'threat' });
		assert.deepEqual(run.cancelled, [run.dispatched[0].actionId], 'the interrupt watcher releases the current action');
		run.engine.ingestActionResult({ actionId: run.dispatched[0].actionId, state: 'CANCELLED', reasonCode: 'INTERRUPTED', eventSequence: 2 });
		const reaction = run.dispatched[1];
		assert.equal(reaction.action.type ?? reaction.primitive ?? reaction.action.primitive, primitive);
		assert.deepEqual({ ...reaction.action.arguments }, expected);
		assert.equal(run.modelRequests.length, 1, 'the threat still reaches the model, like damage');
		assert.equal(run.modelRequests[0].trigger, 'threat');
		assert.deepEqual(run.cancelled, [run.dispatched[0].actionId], 'the notification never cancels the handler-owned reaction');
	}
});

test('native event projections keep combat entity fields and never trim a hunting mob out of the entity rows', async () => {
	const { buildNativeEventInput } = await import('../src/dynamic-main.mjs');
	for (const event of ['observation', 'program_planning_due']) {
		const passive = Array.from({ length: 30 }, (_, index) => ({ uuid: `passive-${index}`, type: 'minecraft:cow', distance: index + 1, alive: true, hostile: false }));
		const hunter = { uuid: ZOMBIE, type: 'minecraft:zombie', distance: 15, alive: true, hostile: true, health: 20, maxHealth: 20, targetingAgent: true, perceivedBy: 'sound' };
		const creeper = { uuid: CREEPER, type: 'minecraft:creeper', distance: 14, alive: true, hostile: true, swelling: true, fuse: 0.3, targetingAgent: true, perceivedBy: 'sight' };
		const observation = { player: { health: 20, ...threatFacts({ entries: [threatEntry(ZOMBIE, 'minecraft:zombie', 15, ['targeting'])] }) },
			inventory: { items: [] }, entities: [...passive, creeper, hunter], blocks: [] };
		const input = JSON.parse(buildNativeEventInput({ goalRevision: 1, currentGoal: 'Mine safely.' }, { event, observation, trigger: 'threat' }).split('\n')[1]);
		const rows = input.observation.entities;
		const zombie = rows.find((row) => row.uuid === ZOMBIE);
		const swelling = rows.find((row) => row.uuid === CREEPER);
		assert.ok(zombie && swelling, `${event}: hunting mobs are kept even beyond the nearest rows`);
		assert.equal(zombie.targetingAgent, true);
		assert.equal(zombie.perceivedBy, 'sound');
		assert.equal(swelling.swelling, true);
		assert.equal(swelling.fuse, 0.3);
		assert.equal(input.observation.player.threat.uuid, ZOMBIE, `${event}: threat facts ride on the player record`);
		assert.equal(input.trigger, 'threat');
	}
});

test('an unrelated watcher firing in the same observation cannot hide a threat from the model', () => {
	const run = engineFor(`program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(5); });
		await player.wait(1000);`);
	const hurtAndHunted = factsWith([threatEntry(ZOMBIE, 'minecraft:zombie', 6, ['targeting'])]);
	hurtAndHunted.player.health = 19;
	run.engine.ingestObservation({ observation: hurtAndHunted, eventSequence: 2, attention: true, priority: 'urgent', trigger: 'threat' });
	assert.equal(run.modelRequests.length, 1, 'the latched signal is raised once, so this is the only chance to notify');
	assert.equal(run.modelRequests[0].trigger, 'threat');
});
