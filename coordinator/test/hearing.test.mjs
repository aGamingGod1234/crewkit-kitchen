import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptEngine } from '../src/arena-script/program-engine.mjs';
import { buildNativeEventInput, classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { NATIVE_AGENT_INSTRUCTIONS, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { adaptObservation, heardFacts } from '../src/observation-adapter.mjs';
import { ARENA_SCRIPT_API_REFERENCE } from '../src/prompts.mjs';

const LAVA_NEAR = { sound: 'block.lava.pop', direction: 'front', elevation: 'below', distance: 3, count: 2 };
const LAVA_FAR = { sound: 'block.lava.ambient', direction: 'left', elevation: 'below', distance: 9 };
const ZOMBIE = { sound: 'entity.zombie.ambient', direction: 'back_left', elevation: 'level', distance: 9, count: 3 };

function wireObservation(heard) {
	return {
		ready: true, status: 'ACTING', position: { x: 0.5, y: 64, z: 0.5 }, view: { yaw: 0, pitch: 0 },
		player: { health: 20, maxHealth: 20, foodLevel: 20 },
		inventory: { items: [] }, entities: [], blocks: [],
		...(heard === undefined ? {} : { heard }),
	};
}

test('heard sounds become player facts with the nearest heard lava for watch conditions', () => {
	const player = adaptObservation(wireObservation([ZOMBIE, LAVA_FAR, LAVA_NEAR])).player;
	assert.deepEqual(player.heard, [ZOMBIE, LAVA_FAR, LAVA_NEAR]);
	assert.deepEqual(player.heardLava, LAVA_NEAR, 'the nearest lava wins, whatever its salience rank');
	const silent = adaptObservation(wireObservation()).player;
	assert.deepEqual(silent.heard, [], 'heard is always present so programs never read undefined');
	assert.equal(silent.heardLava, null);
	assert.throws(() => heardFacts(Array.from({ length: 7 }, () => ZOMBIE)), /heard/);
	assert.throws(() => heardFacts([{ ...ZOMBIE, distance: -1 }]), /distance/);
});

test('native event inputs carry heard on the player record, including the planning-due and over-budget projections', () => {
	const observation = adaptObservation({
		...wireObservation([LAVA_NEAR, ZOMBIE]),
		blocks: Array.from({ length: 128 }, (_, index) => ({ x: index, y: 60, z: 0, blockId: 'minecraft:stone', tags: Array.from({ length: 32 }, (_, tag) => `#minecraft:tag_${tag}_${'x'.repeat(40)}`) })),
	});
	for (const event of ['observation', 'program_planning_due']) {
		const input = JSON.parse(buildNativeEventInput({ goalRevision: 1, currentGoal: 'Strip mine for diamonds.' }, { event, observation }).split('\n')[1]);
		assert.deepEqual(input.observation.player.heard, [LAVA_NEAR, ZOMBIE], `${event}: heard survives event compaction`);
		assert.deepEqual(input.observation.player.heardLava, LAVA_NEAR);
	}
});

test('oversized tool results keep heard sounds with the player facts', () => {
	const observation = adaptObservation({
		...wireObservation([LAVA_NEAR]),
		entities: Array.from({ length: 64 }, (_, index) => ({ uuid: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, type: 'minecraft:cow', name: 'x'.repeat(200), distance: index, position: { x: index, y: 64, z: 0 } })),
		blocks: Array.from({ length: 128 }, (_, index) => ({ x: index, y: 60, z: 0, blockId: 'minecraft:stone', tags: Array.from({ length: 32 }, (_, tag) => `#minecraft:tag_${tag}_${'x'.repeat(40)}`) })),
	});
	const encoded = toolResultContent({ observation }).contentItems[0].text;
	const result = JSON.parse(encoded);
	assert.equal(result.truncated, true, 'the fixture exceeds the tool result limit');
	assert.deepEqual(result.observation.player.heard, [LAVA_NEAR]);
});

test('first heard lava is ordinary attention, never the urgent in-lava or fire trigger', () => {
	assert.deepEqual(classifyObservationTrigger({ attention: true, changedFacts: ['heard'] }, { player: { health: 20, inLava: false, onFire: false } }),
		{ attention: true, priority: 'ordinary', trigger: 'attention' });
});

test('a heardLava watcher interrupts strip mining when lava is first heard', () => {
	const dispatched = [];
	const cancelled = [];
	const modelRequests = [];
	const facts = (heard) => {
		const player = adaptObservation(wireObservation(heard)).player;
		return { player, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } };
	};
	const engine = new ArenaScriptEngine({
		dispatch: (command) => dispatched.push(command),
		cancel: (actionId) => cancelled.push(actionId),
		requestModel: (context) => modelRequests.push(context),
	});
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, eventSequence: 1, observation: facts([ZOMBIE]),
		compiled: parseArenaScript(`program.onUnhandledAttention("continue_and_notify");
program.watch(() => player.state().heardLava !== null && player.state().heardLava.distance <= 4, { mode: "interrupt", after: "reconsider" }, async () => { await player.wait(1); });
await player.wait(1000);`),
	});
	assert.equal(dispatched.length, 1, 'mining work runs while only a zombie is heard');
	engine.ingestObservation({ observation: facts([LAVA_FAR]), eventSequence: 2, attention: false });
	assert.deepEqual(cancelled, [], 'lava 9 blocks away does not trip a 4-block guard');
	engine.ingestObservation({ observation: facts([LAVA_NEAR, LAVA_FAR]), eventSequence: 3, attention: true, priority: 'ordinary', trigger: 'attention', changedFacts: ['heard'] });
	assert.deepEqual(cancelled, [dispatched[0].actionId], 'the guard releases the current action');
});

test('the model is told it can hear, within the instruction budgets', () => {
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /heardLava/);
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(ARENA_SCRIPT_API_REFERENCE, /player\.state\(\)\.velocity\/\.heard\/\.heardLava/);
	assert.ok(Buffer.byteLength(ARENA_SCRIPT_API_REFERENCE) < 14_500);
});
