import test from 'node:test';
import assert from 'node:assert/strict';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { createInterpreterFacts } from '../src/arena-script/facts.mjs';
import { normalizeMinecraftToolCall, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { validateAction } from '../src/schema.mjs';

const wire = () => ({ ready: true, position: { x: 0, y: 64, z: 0 }, velocity: { x: 0, y: -0.7, z: 0 }, view: { yaw: 0, pitch: 0 },
	player: { health: 20, foodLevel: 20 }, inventory: { items: [], tagCounts: {} },
	entities: [{ uuid: '11111111-1111-1111-1111-111111111111', type: 'minecraft:ender_dragon', position: { x: 2, y: 64, z: 0 }, parentId: '22222222-2222-2222-2222-222222222222', partName: 'head', pickable: true }],
	blocks: [{ x: 1, y: 63, z: 0, blockId: 'minecraft:stone' }],
	landmarks: [{ x: 40, y: 70, z: 1, blockId: 'minecraft:obsidian', distance: 41, bearing: 90, elevation: 4 }],
	world: { dimension: 'minecraft:the_end', gameTime: 10, dayTime: 10, raining: false, thundering: false }, perception: { bossBars: [{ barId: 'dragon', progress: 0.5 }] } });

test('live program facts preserve motion, distant landmarks and multipart identity through both adapters', () => {
	const input = wire();
	const facts = createInterpreterFacts(adaptObservation(input));
	assert.deepEqual({ ...facts.player.velocity }, input.velocity);
	assert.equal(facts.world.state.landmarks[0].blockId, 'minecraft:obsidian');
	assert.equal(facts.world.entities[0].partName, 'head');
	assert.equal(facts.world.entities[0].parentId, input.entities[0].parentId);
	assert.equal(facts.world.state.perception.bossBars[0].progress, 0.5);
});

test('large observations retain a useful share of each visible fact type within the output budget', () => {
	const input = wire();
	for (const section of ['entities', 'blocks', 'landmarks']) input[section] = Array.from({ length: 32 }, (_, i) => ({ ...input[section][0], distance: i, tags: Array(32).fill('minecraft:long_tag_name'.repeat(4)) }));
	const text = toolResultContent({ observation: input }).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16384);
	const result = JSON.parse(text).observation;
	for (const section of ['entities', 'blocks', 'landmarks']) {
		assert.ok(result[section].length > 0, section);
		assert.equal(result.resultCoverage[section].retained, result[section].length);
	}
	assert.equal(result.entities[0].partName, 'head');
	assert.equal(result.perception.bossBars[0].progress, 0.5);
});

test('new precision and query options reject ambiguous combinations', () => {
	assert.equal(validateAction({ type: 'use_item', durationMs: 1000, mode: 'once' }).mode, 'once');
	const shot = { type: 'use_ranged', targetId: wire().entities[0].uuid, drawDurationMs: 1000, timeoutMs: 3000 };
	assert.equal(validateAction({ ...shot, aimX: 1, aimY: 70, aimZ: 2 }).aimY, 70);
	assert.throws(() => validateAction({ ...shot, aimX: 1 }));
	assert.throws(() => validateAction({ ...shot, aimX: 1, aimY: 2, aimZ: 3, trackTarget: true }));
	assert.equal(normalizeMinecraftToolCall('inspect', { section: 'entities', entityType: 'minecraft:eye_of_ender' }).entityType, 'minecraft:eye_of_ender');
	assert.equal(normalizeMinecraftToolCall('inspect', { section: 'recipes', outputItemId: 'minecraft:blaze_powder' }).outputItemId, 'minecraft:blaze_powder');
	assert.throws(() => normalizeMinecraftToolCall('inspect', { section: 'inventory', entityType: 'minecraft:pig' }));
	assert.throws(() => normalizeMinecraftToolCall('inspect', { section: 'recipes', outputItemId: 'minecraft:stick', recipeId: 'minecraft:stick' }));
	assert.equal(normalizeMinecraftToolCall('runProgram', { noteKey: 'routine', observationIntervalMs: 100 }).noteKey, 'routine');
	assert.throws(() => normalizeMinecraftToolCall('runProgram', { source: 'x', noteKey: 'routine' }));
	assert.throws(() => normalizeMinecraftToolCall('runProgram', { source: 'x', observationIntervalMs: 1 }));
});

test('requested sampling refreshes facts and stops when the bounded program ends', async () => {
	const timers = new Map(); let serial = 0, sampled = 0, complete;
	const executor = new NativeProgramExecutor({ setTimeoutFn: (callback, ms) => { const id = ++serial; timers.set(id, { callback, ms }); return id; }, clearTimeoutFn: (id) => timers.delete(id) });
	const record = { agentId: 'a', goalRevision: 1, provider: 'codex', model: 'test', reasoningEffort: 'low' };
	const result = executor.run(record, { source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1000);', observationIntervalMs: 100 }, {
		observation: wire(), eventSequence: 1,
		executeAction: () => new Promise((resolve) => { complete = resolve; }), cancelAction: async () => {},
		refreshObservation: async () => ({ observation: wire(), eventSequence: 2 + sampled++ }),
	});
	const [sampleId, timer] = [...timers].find(([, entry]) => entry.ms === 100);
	timers.delete(sampleId); await timer.callback();
	assert.equal(sampled, 1);
	complete({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	assert.equal((await result).reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(timers.size, 0);
});

test('an in-flight sample cannot substitute for observing terminal action effects', async () => {
	const timers = new Map(); let serial = 0, reads = 0, releaseSample, completeAction;
	const executor = new NativeProgramExecutor({ setTimeoutFn: (callback, ms) => { const id = ++serial; timers.set(id, { callback, ms }); return id; }, clearTimeoutFn: (id) => timers.delete(id) });
	const record = { agentId: 'a', goalRevision: 1, provider: 'codex', model: 'test', reasoningEffort: 'low' };
	const pending = executor.run(record, { source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1000);', observationIntervalMs: 100 }, {
		observation: wire(), eventSequence: 1,
		executeAction: () => new Promise((resolve) => { completeAction = resolve; }), cancelAction: async () => {},
		refreshObservation: () => ++reads === 1 ? new Promise((resolve) => { releaseSample = resolve; }) : Promise.resolve({ observation: wire(), eventSequence: 3 }),
	});
	const [id, timer] = [...timers].find(([, entry]) => entry.ms === 100);
	timers.delete(id); const sample = timer.callback();
	await Promise.resolve();
	completeAction({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	await Promise.resolve();
	releaseSample({ observation: wire(), eventSequence: 2 });
	await sample;
	assert.equal((await pending).eventSequence, 3);
	assert.equal(reads, 2);
	assert.equal(timers.size, 0);
});
