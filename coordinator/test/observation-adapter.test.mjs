import assert from 'node:assert/strict';
import test from 'node:test';

import { adaptObservation } from '../src/observation-adapter.mjs';

function wireObservation(overrides = {}) {
	return {
		ready: true,
		position: { x: 0, y: 64, z: 0 },
		view: { yaw: 10, pitch: -2 },
		player: { health: 20, foodLevel: 18, onFire: false, air: 300, fallDistance: 0 },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0 }] },
		entities: [{
			uuid: '00000000-0000-0000-0000-000000000001', type: 'minecraft:item',
			position: { x: 2, y: 64, z: 0 }, itemId: 'minecraft:oak_log', count: 1,
		}],
		blocks: [{ x: 4, y: 64, z: 0, blockId: 'minecraft:oak_log' }],
		...overrides,
	};
}

test('adapts protocol entities and blocks into bounded factual candidate records', () => {
	const adapted = adaptObservation(wireObservation());
	assert.deepEqual(adapted.player, { x: 0, y: 64, z: 0, yaw: 10, pitch: -2, health: 20, hunger: 18, air: 300, fire: false, fallDistance: 0 });
	assert.deepEqual(adapted.items, [{ stableId: '00000000-0000-0000-0000-000000000001', itemId: 'minecraft:oak_log', count: 1, x: 2, y: 64, z: 0 }]);
	assert.deepEqual(adapted.entities, [{ stableId: '00000000-0000-0000-0000-000000000001', type: 'minecraft:item', x: 2, y: 64, z: 0, itemId: 'minecraft:oak_log', count: 1 }]);
	assert.deepEqual(adapted.blocks, [{ stableId: '4,64,0', blockId: 'minecraft:oak_log', x: 4, y: 64, z: 0 }]);
	assert.deepEqual(adapted.inventory, { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0 }] });
	assert.equal(Object.hasOwn(adapted.items[0], 'reachable'), false);
	assert.equal(Object.hasOwn(adapted.items[0], 'tags'), false);
});

test('rejects accessors, inherited data, and proxies before reading observation facts', () => {
	let accessed = false;
	const accessorObservation = wireObservation();
	Object.defineProperty(accessorObservation, 'entities', { enumerable: true, get() { accessed = true; return []; } });
	assert.throws(() => adaptObservation(accessorObservation), /own data/);
	assert.equal(accessed, false);
	assert.throws(() => adaptObservation(Object.create(wireObservation())), /plain data record/);
	assert.throws(() => adaptObservation(new Proxy(wireObservation(), {})), /plain data record/);
});

test('rejects duplicate immutable IDs and duplicate block coordinates', () => {
	const duplicateEntity = wireObservation({ entities: [
		{ uuid: 'same', type: 'minecraft:zombie', position: { x: 1, y: 64, z: 0 } },
		{ uuid: 'same', type: 'minecraft:zombie', position: { x: 2, y: 64, z: 0 } },
	] });
	assert.throws(() => adaptObservation(duplicateEntity), /duplicate entity identity/);
	assert.throws(() => adaptObservation(wireObservation({ blocks: [
		{ x: 1, y: 64, z: 0, blockId: 'minecraft:stone' },
		{ x: 1, y: 64, z: 0, blockId: 'minecraft:dirt' },
	] })), /duplicate block identity/);
});

test('rejects non-finite coordinates and observations over protocol bounds', () => {
	assert.throws(() => adaptObservation(wireObservation({ position: { x: Number.NaN, y: 64, z: 0 } })), /finite number/);
	assert.throws(() => adaptObservation(wireObservation({ entities: Array.from({ length: 65 }, (_, index) => ({ uuid: String(index), type: 'minecraft:zombie', position: { x: index, y: 64, z: 0 } })) })), /entities exceeds bound/);
	assert.throws(() => adaptObservation(wireObservation({ blocks: Array.from({ length: 129 }, (_, index) => ({ x: index, y: 64, z: 0, blockId: 'minecraft:stone' })) })), /blocks exceeds bound/);
});
