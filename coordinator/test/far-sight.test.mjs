import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { NativeToolRuntime, mergeSurveys } from '../src/native-tool-runtime.mjs';
import { normalizeInspectionQuery, validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const FAR = {
	structures: [
		{ structure: 'village', size: 40, x: 30, y: 64, z: 10, distance: 32, bearing: -18, new: true },
		{ blocks: ['minecraft:oak_planks', 'minecraft:cobblestone'], size: 52, x: 0, y: 66, z: 160, distance: 160, bearing: 0 },
	],
	built: [{ blocks: ['minecraft:oak_planks', 'minecraft:glass', 'minecraft:torch'], size: 5, x: -100, y: 64, z: 100, distance: 141, bearing: 45, new: true }],
	biomes: [{ biome: 'minecraft:desert', x: 64, y: 63, z: 64, distance: 90, bearing: -45, new: true }],
	poi: [{ blockId: 'minecraft:nether_portal', x: 10, y: 64, z: 120, distance: 120, bearing: -5 }],
	blocks: [{ blockId: 'minecraft:lava', count: 12, x: 5, y: 60, z: 20, distance: 21, bearing: -14 }],
};

function wireObservation(sighted) {
	return {
		goalRevision: 1, observedAtEpochMs: 1, ready: true, status: 'ready', eventSequence: 1, attention: false, changedFacts: [],
		position: { x: 0.5, y: 64, z: 0.5 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
		player: { health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5, gameMode: 'survival', onGround: true, inWater: false, onFire: false,
			air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [] },
		inventory: { items: [], selectedItem: 'minecraft:air' }, entities: [], blocks: [], nearbyContainers: [],
		world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
		currentAction: { active: false }, lastResult: { present: false }, sighted,
	};
}

test('far-sight rows pass wire validation with bounded, exact rows', () => {
	assert.deepEqual(validateProtocolV2Payload('observation', wireObservation(FAR)).sighted, FAR);
	for (const mutate of [
		(value) => { value.structures[1].structure = 'village'; },
		(value) => { delete value.structures[0].structure; },
		(value) => { value.built[0].blocks = []; },
		(value) => { value.built[0].blocks = ['a:b', 'c:d', 'e:f', 'g:h', 'i:j']; },
		(value) => { delete value.built[0].size; },
		(value) => { value.biomes = Array.from({ length: 5 }, () => value.biomes[0]); },
		(value) => { value.poi[0].hidden = true; },
		(value) => { value.blocks[0].count = 0; },
		(value) => { value.built[0].bearing = 200; },
	]) {
		const bad = structuredClone(FAR);
		mutate(bad);
		assert.throws(() => validateProtocolV2Payload('observation', wireObservation(bad)));
	}
});

test('survey inspection queries are strict on the coordinator side of the protocol', () => {
	assert.deepEqual(normalizeInspectionQuery({ section: 'survey' }), { section: 'survey', limit: 4 });
	assert.deepEqual(normalizeInspectionQuery({ section: 'survey', include: ['structures', 'blocks:minecraft:diamond_ore'], exclude: ['caves'], limit: 8 }),
		{ section: 'survey', limit: 8, include: ['structures', 'blocks:minecraft:diamond_ore'], exclude: ['caves'] });
	for (const bad of [{ limit: 9 }, { include: ['mineshafts'] }, { exclude: ['blocks:minecraft:stone'] }, { offset: 0 }, { include: Array(9).fill('poi') }]) {
		assert.throws(() => normalizeInspectionQuery({ section: 'survey', ...bad }), undefined, JSON.stringify(bad));
	}
	assert.throws(() => normalizeInspectionQuery({ section: 'blocks', include: ['poi'] }), 'include is survey only');
});

test('survey and lookAround survey normalize strictly; threats cannot be excluded because they are not a section', () => {
	assert.deepEqual(normalizeMinecraftToolCall('survey', {}), { kind: 'inspect', section: 'survey', limit: 4 });
	assert.deepEqual(normalizeMinecraftToolCall('survey', { include: ['built', 'built', 'blocks:minecraft:spawner'], limit: 2 }),
		{ kind: 'inspect', section: 'survey', limit: 2, include: ['built', 'blocks:minecraft:spawner'] });
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'survey', exclude: ['biomes'] }), { kind: 'inspect', section: 'survey', limit: 4, exclude: ['biomes'] });
	assert.deepEqual(normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 4, ticksPerStep: 2, survey: { include: ['structures'] } }).survey,
		{ section: 'survey', limit: 4, include: ['structures'] });
	for (const bad of [{ include: ['threats'] }, { exclude: ['threats'] }, { include: 'poi' }, { limit: 0 }, { offset: 1 },
		{ include: ['blocks:a:b', 'blocks:c:d', 'blocks:e:f', 'blocks:g:h', 'blocks:i:j'] }]) {
		assert.throws(() => normalizeMinecraftToolCall('survey', bad), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' }, JSON.stringify(bad));
	}
	assert.throws(() => normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 4, ticksPerStep: 2, survey: { radius: 3 } }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	assert.throws(() => normalizeMinecraftToolCall('inspect', { section: 'landmarks', include: ['poi'] }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
});

test('merged sweep surveys keep the nearest sighting of each thing with bearings from the final heading', () => {
	const merged = mergeSurveys([
		{ yaw: 90, result: { survey: { structures: [{ structure: 'village', x: -60, y: 64, z: 0, distance: 60, bearing: 0 }], biomes: [{ biome: 'minecraft:desert', x: -90, y: 63, z: 0, distance: 90, bearing: 0 }] }, coverage: { complete: true } } },
		{ yaw: 180, result: { survey: { structures: [{ structure: 'village', x: -40, y: 64, z: -10, distance: 41, bearing: -60 }, { structure: 'shipwreck', x: 0, y: 60, z: -50, distance: 50, bearing: 0 }],
			biomes: [{ biome: 'minecraft:desert', x: -80, y: 63, z: -80, distance: 113, bearing: -45 }] }, standingIn: 'minecraft:plains', threats: { entries: [] }, coverage: { complete: false } } },
	], 180, 4);
	assert.deepEqual(merged.structures.map((row) => [row.structure, row.distance, row.bearing]), [['village', 41, -60], ['shipwreck', 50, 0]],
		'one village row (the nearer sighting), nearest first');
	assert.deepEqual(merged.biomes.map((row) => [row.biome, row.distance, row.bearing]), [['minecraft:desert', 90, -90]],
		'the first heading saw the desert nearer; its bearing is turned to the final heading');
	assert.equal(merged.standingIn, 'minecraft:plains');
	assert.deepEqual(merged.threats, { entries: [] }, 'threats from the last heading always come along');
	assert.equal(merged.headings, 2);
	assert.equal(merged.complete, false);
	assert.equal(mergeSurveys([{ yaw: 0, result: { survey: { built: Array.from({ length: 6 }, (_, index) => ({ blocks: ['minecraft:torch'], size: 1, x: index * 64, y: 64, z: 0, distance: index * 64, bearing: 0 })) } } }], 0, 2).built.length, 2,
		'rows per section are capped by limit');
});

function record() {
	const fields = { originalRequest: 'explore', predicate: { type: 'operator_confirmed' }, createdAtTick: 10 };
	return { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'low', serviceTier: 'fast', goalRevision: 3,
		currentGoal: 'explore', currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) } };
}

test('lookAround with survey surveys each heading only after the body has turned to it', async () => {
	const sent = [];
	const order = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => { sent.push(args); order.push(`turn:${args[2].arguments.yaw}`); } },
		requestObservation: async (_record, { afterEventSequence }) => ({ eventSequence: afterEventSequence + 1, observation: { world: { dimension: 'minecraft:overworld' } } }),
		inspectObservation: async (_record, query) => {
			order.push(`survey:${query.section}`);
			const yaw = sent.at(-1)[2].arguments.yaw;
			return { section: 'survey', survey: yaw === 180 ? { built: [{ blocks: ['minecraft:oak_planks'], size: 5, x: 0, y: 64, z: -140, distance: 140, bearing: 0, new: true }] } : {}, coverage: { complete: true } };
		},
	});
	const tool = normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 4, ticksPerStep: 1, survey: { include: ['built'] } });
	const pending = runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 't', callId: 'c', tool }, record());
	for (let index = 0; index < 4; index += 1) {
		await new Promise((resolve) => setImmediate(resolve));
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	const result = await pending;
	assert.deepEqual(order, ['turn:90', 'survey:survey', 'turn:180', 'survey:survey', 'turn:-90', 'survey:survey', 'turn:0', 'survey:survey'],
		'every survey follows its own turn');
	assert.equal(result.survey.built.length, 1);
	assert.equal(result.survey.built[0].bearing, 180, 'the hut seen while facing north is behind the final (south) heading');
	assert.equal(result.survey.headings, 4);
});
