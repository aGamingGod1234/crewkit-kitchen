import assert from 'node:assert/strict';
import test from 'node:test';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';

for (const event of ['program_planning_due', 'observation']) {
	test(`${event} preserves action fields and identifies remaining field omissions`, () => {
		const item = { itemId: 'minecraft:iron_pickaxe', count: 1, slot: 2, hotbar: true, damage: 190, maxDamage: 250, fingerprint: 'stack-42', maxStackSize: 1, tooltip: ['extra description'] };
		const entity = { uuid: 'entity-42', type: 'minecraft:zombie', hostile: true, alive: true, health: 14, bounds: { width: 0.6, height: 1.8 }, velocity: { x: 0, y: 0, z: 0 }, customDetail: 'discarded' };
		const observation = { inventory: { items: [item], selectedItem: 'minecraft:iron_pickaxe', tagCounts: { 'minecraft:pickaxes': 1 } }, entities: [entity],
			blocks: event === 'observation' ? Array.from({ length: 32 }, (_, x) => ({ blockId: 'minecraft:stone', x, y: 64, z: 0, largeDetail: 'x'.repeat(1000) })) : [] };
		const input = JSON.parse(buildNativeEventInput({ goalRevision: 1, currentGoal: 'Mine safely.' }, { event, observation }).split('\n')[1]);
		const projected = input.observation;
		for (const field of ['maxDamage', 'fingerprint', 'hotbar', 'maxStackSize']) assert.equal(projected.inventory.items[0][field], item[field]);
		assert.equal(projected.inventory.items[0].usesLeft, item.maxDamage - item.damage, 'wear reaches the model as usesLeft');
		assert.deepEqual(projected.inventory.items[0].omittedFields, ['tooltip']);
		for (const field of ['hostile', 'alive', 'health', 'bounds', 'velocity']) assert.deepEqual(projected.entities[0][field], entity[field]);
		assert.deepEqual(projected.entities[0].omittedFields, ['customDetail']);
		assert.equal(projected.inventory.selectedItem, observation.inventory.selectedItem);
		assert.deepEqual(projected.inventory.tagCounts, observation.inventory.tagCounts);
		assert.equal(projected.resultCoverage.inventory.omitted, 0, 'row coverage stays separate from explicit field omissions');
	});
}
