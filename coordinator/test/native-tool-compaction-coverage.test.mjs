import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_TOOL_RESULT_BYTES, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { presentNativeToolResult } from '../src/codex-service.mjs';
import { decodeModelFacts } from '../src/model-fact-encoding.mjs';

const item = { slot: 0, itemId: 'minecraft:iron_pickaxe', count: 1, damage: 240, maxDamage: 250, tags: ['#minecraft:pickaxes'] };
const inventory = { items: [item], selectedSlot: 0, selectedItem: item, tagCounts: { '#minecraft:pickaxes': 1 } };
const encode = value => {
	const text = toolResultContent(value).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= MAX_TOOL_RESULT_BYTES);
	return JSON.parse(text);
};

test('native compaction preserves selected item, tag totals, durability, targets and threats', () => {
	const observation = {
		player: { health: 8, air: 60 }, inventory,
		entities: [{ stableId: '550e8400-e29b-41d4-a716-446655440000', typeId: 'minecraft:zombie', hostile: true, alive: true, health: 12, maxHealth: 20, x: 2, y: 64, z: 1, distanceSquared: 5 }],
		items: [{ stableId: '550e8400-e29b-41d4-a716-446655440001', itemId: 'minecraft:oak_log', count: 2, x: 1, y: 64, z: 1 }],
		blocks: [{ blockId: 'minecraft:oak_log', x: 1, y: 64, z: 2, tags: ['#minecraft:logs'] }],
	};
	const original = structuredClone(observation);
	const result = encode({ observation, detail: 'x'.repeat(20_000) }).observation;
	assert.deepEqual(result.inventory, inventory);
	assert.deepEqual(result.entities, observation.entities);
	assert.deepEqual(result.items, observation.items);
	assert.deepEqual(result.blocks, observation.blocks);
	assert.deepEqual(result.resultCoverage.omittedFields, []);
	assert.deepEqual(result.resultCoverage.omittedSections, []);
	assert.equal(result.resultCoverage.inventory.retained, 1);
	assert.equal(result.resultCoverage.entities.detailsOmitted, false);
	assert.deepEqual(observation, original, 'presentation must not mutate authoritative facts');
});

test('field omissions remain explicit even when every observation row is retained', () => {
	const observation = {
		inventory: { ...inventory, extra: { recipeHints: ['bulky'] } },
		entities: [{ stableId: 'entity-1', hostile: false, alive: true, omittedFields: ['upstreamDetail'], equipment: { description: 'x'.repeat(20_000) } }],
		blocks: [{ blockId: 'minecraft:stone', x: 1, y: 64, z: 1, properties: { lit: false } }],
		world: { worldId: 'world-1', dimension: 'minecraft:overworld', weather: 'rain' },
		lastResult: { state: 'FAILED', reasonCode: 'BLOCKED', message: 'details' },
		options: { extraAction: true },
		coverage: { inventory: { complete: true } },
		resultCoverage: { omittedFields: ['player.priorField'], omittedSections: ['previousSection'] },
	};
	const result = encode({ observation }).observation;
	assert.equal(result.resultCoverage.entities.retained, 1);
	assert.equal(result.resultCoverage.entities.availableInSnapshot, 1);
	assert.equal(result.resultCoverage.entities.detailsOmitted, true);
	assert.deepEqual(result.entities[0].omittedFields, ['upstreamDetail'], 'retain earlier projection omissions through further compaction');
	assert.equal(Object.hasOwn(result, 'items'), false, 'compaction must not invent empty dropped-item facts');
	assert.equal(result.resultCoverage.inventory.retained, 1);
	assert.deepEqual(result.coverage, observation.coverage, 'server row coverage keeps its original meaning');
	assert.deepEqual(result.resultCoverage.omittedFields, [
		'blocks[].properties', 'entities[].equipment', 'inventory.extra', 'lastResult.message', 'options', 'player.priorField', 'world.weather',
	]);
	assert.deepEqual(result.resultCoverage.omittedSections.sort(), ['options', 'previousSection']);
});

test('row omissions and retained-row field omissions are independently reported', () => {
	const observation = {
		inventory: { ...inventory, items: Array.from({ length: 20 }, (_, slot) => ({ ...item, slot })) },
		entities: Array.from({ length: 40 }, (_, index) => ({ stableId: `entity-${index}`, hostile: true, alive: true, extra: 'x'.repeat(1000) })),
	};
	const result = encode({ observation }).observation;
	assert.equal(result.resultCoverage.inventory.availableInSnapshot, 20);
	assert.equal(result.resultCoverage.inventory.retained, 16);
	assert.equal(result.resultCoverage.entities.availableInSnapshot, 40);
	assert.equal(result.resultCoverage.entities.retained, 32);
	assert.deepEqual(result.resultCoverage.omittedFields, ['entities[].extra']);
	assert.deepEqual(result.resultCoverage.omittedSections, []);
});

test('postAction inventory fallback preserves cheap facts and labels projected item fields', () => {
	const value = {
		state: 'SUCCEEDED', completed: 2,
		results: [{ actionType: 'look_at', state: 'SUCCEEDED' }, { actionType: 'break_block', state: 'SUCCEEDED' }],
		postAction: {
			eventSequence: 7, freshness: { fresh: true },
			goalSpec: { predicate: { type: 'inventory_contains_any', count: 1, itemIds: Array.from({ length: 64 }, (_, i) => `minecraft:item_${i}_${'x'.repeat(238)}`) } },
			observation: { inventory: { ...inventory, items: [{ ...item, details: { lore: 'bulky' } }] }, player: { health: 8 } },
		},
	};
	const result = encode(value);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.results.length, 2);
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.eventSequence, 7);
	assert.deepEqual(result.postAction.observation.inventory, inventory);
	assert.deepEqual(result.postAction.observation.resultCoverage.omittedFields, ['inventory.items[].details', 'player']);
	assert.equal(result.postAction.observation.resultCoverage.inventory.retained, 1);
});

test('in-budget results retain their exact representation and do not acquire omission warnings', () => {
	const value = { observation: { inventory, entities: [] } };
	assert.deepEqual(encode(value), value);
});

const present = (value, kind) => {
	const { response } = presentNativeToolResult(value, { kind });
	assert.equal(response.success, true);
	const text = response.contentItems[0].text;
	assert.ok(Buffer.byteLength(text, 'utf8') <= MAX_TOOL_RESULT_BYTES);
	return decodeModelFacts(JSON.parse(text));
};

for (const kind of ['sequence', 'action']) test(`provider presentation budgets large tag totals without losing ${kind} receipts`, () => {
	const tagCounts = Object.fromEntries(Array.from({ length: 128 }, (_, i) => [`minecraft:tag_${i}_${'x'.repeat(170)}`, i + 1]));
	const receipt = index => ({ actionId: `native-action-${index}`, actionType: 'break_block',
		state: index === 7 ? 'FAILED' : 'SUCCEEDED', reasonCode: index === 7 ? 'TARGET_OBSTRUCTED' : 'BLOCK_BROKEN',
		executionStarted: true, physicalAttempted: index !== 7 });
	const { actionType, ...actionReceipt } = receipt(7);
	const value = {
		...(kind === 'sequence' ? { state: 'FAILED', completed: 8, failedAt: 7, results: Array.from({ length: 8 }, (_, i) => receipt(i)) } : actionReceipt),
		postAction: { eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { ...inventory, tagCounts } } },
	};
	const original = structuredClone(value);
	const result = present(value, kind);
	assert.equal(result.state, 'FAILED');
	if (kind === 'sequence') {
		assert.equal(result.completed, 8);
		assert.equal(result.failedAt, 7);
		assert.deepEqual(result.results, value.results, 'every authoritative receipt keeps its identity and status');
	} else {
		for (const [key, expected] of Object.entries(actionReceipt)) assert.equal(result[key], expected);
	}
	assert.equal(result.postAction.eventSequence, 42);
	assert.equal(result.postAction.freshness.fresh, true);
	assert.deepEqual(result.postAction.observation.inventory.items, inventory.items);
	assert.equal(result.postAction.observation.inventory.selectedSlot, inventory.selectedSlot);
	assert.deepEqual(result.postAction.observation.inventory.selectedItem, inventory.selectedItem);
	assert.equal(Object.hasOwn(result.postAction.observation.inventory, 'tagCounts'), false);
	assert.deepEqual(result.postAction.observation.resultCoverage.omittedFields, ['inventory.tagCounts']);
	assert.deepEqual(result.postAction.observation.resultCoverage.inventory, { retained: 1, availableInSnapshot: 1 });
	assert.deepEqual(value, original, 'presentation must not mutate the original receipt or inventory');
});

test('provider presentation marks truncated recovery arrays even when retained entries have identical fields', () => {
	const value = { detail: 'x'.repeat(20_000), observation: { inventory, recovery: {
		lastLostInventory: Array.from({ length: 20 }, (_, slot) => ({ slot, itemId: 'minecraft:oak_log', count: 1 })),
		alreadyHave: Array.from({ length: 20 }, (_, i) => `minecraft:item_${i}`),
		alreadyHaveFacts: Array.from({ length: 20 }, (_, i) => ({ itemId: `minecraft:item_${i}`, provenance: 'remembered' })),
	} } };
	const result = present(value, 'observe').observation;
	assert.equal(result.recovery.lastLostInventory.length, 8);
	assert.deepEqual(result.recovery.lastLostInventory, value.observation.recovery.lastLostInventory.slice(0, 8));
	assert.deepEqual(result.recovery.alreadyHave, value.observation.recovery.alreadyHave.slice(-16));
	assert.deepEqual(result.recovery.alreadyHaveFacts, value.observation.recovery.alreadyHaveFacts.slice(-16));
	for (const path of ['recovery.lastLostInventory', 'recovery.alreadyHave', 'recovery.alreadyHaveFacts']) {
		assert.ok(result.resultCoverage.omittedFields.includes(path), `${path} must report missing entries`);
	}
	assert.deepEqual(result.resultCoverage.omittedSections, [], 'partly retained recovery is not an absent section');
});

test('provider postAction fallback unions prior section omissions with newly omitted sections', () => {
	const value = { state: 'SUCCEEDED', completed: 2,
		results: [{ actionType: 'look_at', state: 'SUCCEEDED' }, { actionType: 'break_block', state: 'SUCCEEDED' }],
		postAction: { eventSequence: 7, freshness: { fresh: true },
			goalSpec: { predicate: { type: 'inventory_contains_any', count: 1, itemIds: Array.from({ length: 64 }, (_, i) => `minecraft:item_${i}_${'x'.repeat(238)}`) } },
			observation: { inventory, player: { health: 8 }, resultCoverage: {
				omittedFields: ['earlier.field'], omittedSections: ['earlierSection', 'player'],
			} },
		},
	};
	const original = structuredClone(value);
	const result = present(value, 'sequence');
	assert.equal(result.completed, 2);
	assert.equal(result.postAction.truncated, true);
	assert.deepEqual(result.postAction.observation.inventory, inventory);
	assert.deepEqual(result.postAction.observation.resultCoverage.omittedSections.sort(), ['earlierSection', 'player']);
	assert.deepEqual(result.postAction.observation.resultCoverage.omittedFields, ['earlier.field', 'player']);
	assert.deepEqual(value, original);
});
