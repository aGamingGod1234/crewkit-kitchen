import assert from 'node:assert/strict';
import test from 'node:test';
import { MEASURED_SESSION, estimateHourlyTokens, measureTokenBudget, representativeProgramWake, representativeRecord } from '../src/benchmark/token-budget.mjs';
import { EVENT_BLOCK_DEFAULTS, buildNativeEventInput } from '../src/dynamic-main.mjs';
import { ModelObservationViews, decodeModelFacts, encodeNativeEventInput } from '../src/model-fact-encoding.mjs';

const payload = (input) => decodeModelFacts(JSON.parse(input.slice(input.indexOf('\n') + 1)));

test('a representative program wake stays inside its byte budget for every provider', () => {
	const sizes = measureTokenBudget();
	// Before: 15,759 raw bytes sent to Claude on every wake (Codex: 12,685 encoded).
	assert.ok(sizes.eventRawBytes <= 11_000, `raw event ${sizes.eventRawBytes} bytes`);
	assert.ok(sizes.eventEncodedFirstBytes <= 9_000, `first encoded event ${sizes.eventEncodedFirstBytes} bytes`);
	assert.ok(sizes.eventEncodedRepeatBytes <= 7_500, `repeat encoded event ${sizes.eventEncodedRepeatBytes} bytes`);
	assert.ok(sizes.toolSchemaBytes <= 28_000, `tool schemas ${sizes.toolSchemaBytes} bytes`);
});

test('unchanged goal, goalSpec and taskMemory are named instead of repeated within one provider context', () => {
	const record = representativeRecord();
	const views = new ModelObservationViews();
	const first = payload(encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(1)), views));
	assert.ok(first.goalSpec && first.taskMemory);
	assert.equal(first.sameAsPreviousEvent, undefined);
	const second = payload(encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(2)), views));
	assert.deepEqual(second.sameAsPreviousEvent, ['goalSpec', 'taskMemory']);
	assert.equal(second.goalSpec, undefined);
	assert.equal(second.goal, 'Beat the game', 'short fields are always repeated');

	const changed = representativeProgramWake(3);
	changed.taskMemory = { ...changed.taskMemory, revision: 42 };
	const third = payload(encodeNativeEventInput(buildNativeEventInput(record, changed), views));
	assert.deepEqual(third.sameAsPreviousEvent, ['goalSpec']);
	assert.equal(third.taskMemory.revision, 42);

	views.reset();
	assert.equal(payload(encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(4)), views)).sameAsPreviousEvent, undefined, 'a new provider context gets everything again');
	const nether = representativeProgramWake(5);
	nether.observation.world = { ...nether.observation.world, dimension: 'minecraft:the_nether' };
	assert.equal(payload(encodeNativeEventInput(buildNativeEventInput(record, nether), views)).sameAsPreviousEvent, undefined, 'another world identity is a new baseline');

	const failed = new ModelObservationViews();
	encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(6)), failed);
	failed.forgetEventMetadata();
	assert.equal(payload(encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(7)), failed)).sameAsPreviousEvent, undefined, 'an undelivered turn is not a baseline');
});

test('event blocks omit only fields that follow from the row, and say so', () => {
	const wake = representativeProgramWake(1);
	wake.observation.blocks = [
		{ stableId: '1,64,0', x: 1, y: 64, z: 0, blockId: 'minecraft:stone', tags: ['minecraft:mineable/pickaxe'], state: {}, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }] },
		{ stableId: '2,64,0', x: 2, y: 64, z: 0, blockId: 'minecraft:oak_slab', tags: ['minecraft:slabs'], state: { type: 'bottom' }, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 0.5, maxZ: 1 }] },
		{ stableId: 'custom', x: 3, y: 64, z: 0, blockId: 'minecraft:dirt', tags: ['a'] },
		{ stableId: '4,64,0', x: 4, y: 64, z: 0, blockId: 'minecraft:dirt', tags: ['b'] },
	];
	const observation = payload(buildNativeEventInput(representativeRecord(), wake)).observation;
	assert.equal(observation.blockDefaults, EVENT_BLOCK_DEFAULTS);
	assert.deepEqual(observation.blockTags, { 'minecraft:stone': ['minecraft:mineable/pickaxe'], 'minecraft:oak_slab': ['minecraft:slabs'] });
	assert.deepEqual(observation.blocks[0], { x: 1, y: 64, z: 0, blockId: 'minecraft:stone' });
	assert.deepEqual(observation.blocks[1], { x: 2, y: 64, z: 0, blockId: 'minecraft:oak_slab', state: { type: 'bottom' }, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 0.5, maxZ: 1 }] });
	assert.equal(observation.blocks[2].stableId, 'custom', 'a stableId that is not x,y,z is kept');
	assert.deepEqual(observation.blocks[2].tags, ['a'], 'disagreeing tags for one blockId stay on the rows');
	assert.deepEqual(observation.blocks[3].tags, ['b']);
});

test('the hourly estimate counts the context each call re-reads', () => {
	const unbounded = estimateHourlyTokens({ calls: MEASURED_SESSION.modelCalls, turns: MEASURED_SESSION.turns, prefixTokens: 15_000, eventTokens: 4_000, toolResultTokens: 1_000, toolResults: 416, maxContextTokens: 160_000 });
	const rotated = estimateHourlyTokens({ calls: MEASURED_SESSION.modelCalls, turns: MEASURED_SESSION.turns, prefixTokens: 15_000, eventTokens: 4_000, toolResultTokens: 1_000, toolResults: 416, maxContextTokens: 64_000 });
	assert.ok(rotated.averageContextTokens < unbounded.averageContextTokens);
	assert.equal(rotated.averageContextTokens, 15_000 + 24_500);
});

test('model call and turn usage become flat trace fields', async () => {
	const { modelCallTraceFields, turnUsageTraceFields } = await import('../src/dynamic-main.mjs');
	assert.deepEqual(modelCallTraceFields({ last: { inputTokens: 70_205, cachedInputTokens: 70_000, cacheWriteInputTokens: 200, outputTokens: 40 },
		call: { provider: 'claude', turnId: '1:2', contextTokens: 70_205, rotations: 1, firstEventMs: 2_100, streamMs: 900, totalMs: 3_000, extra: 'ignored' } }),
	{ provider: 'claude', turnId: '1:2', contextTokens: 70_205, inputTokens: 70_205, cachedInputTokens: 70_000, cacheWriteInputTokens: 200, outputTokens: 40, rotations: 1, firstEventMs: 2_100, streamMs: 900, totalMs: 3_000 });
	assert.deepEqual(turnUsageTraceFields({ calls: 2, input: 5, cacheRead: 9, cacheWrite: 1, output: 7, costUsd: null, contextTokens: 15 }),
		{ modelCalls: 2, inputTokens: 5, cachedInputTokens: 9, cacheWriteInputTokens: 1, outputTokens: 7, contextTokens: 15 });
	assert.deepEqual(turnUsageTraceFields(undefined), {});
});
