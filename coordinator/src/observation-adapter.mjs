import { types as nodeTypes } from 'node:util';

import { MAX_BLOCKS, MAX_ENTITIES, MAX_INVENTORY_SUMMARIES, MAX_OBSERVATION_TAGS, MAX_TAG_COUNT_ENTRIES } from './constants.mjs';

const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
const COORDINATE_FIELDS = ['x', 'y', 'z'];

/** Converts one validated protocol-v2 observation into the narrow ArenaScript fact shape. */
export function adaptObservation(value) {
	const source = ownDataRecord(value, 'wire observation');
	if (source.ready === false) return emptyFacts(source.status === 'PLAYER_DEAD');
	if (source.ready !== true) throw new TypeError('wire observation.ready must be boolean');

	const position = vector(source.position, 'wire observation.position');
	const view = vector(source.view, 'wire observation.view', ['yaw', 'pitch']);
	const playerSource = ownDataRecord(source.player, 'wire observation.player');
	const player = {
		x: position.x,
		y: position.y,
		z: position.z,
		yaw: view.yaw,
		pitch: view.pitch,
	};
	copyNumber(playerSource, player, 'health');
	copyNumber(playerSource, player, 'foodLevel', 'hunger');
	copyNumber(playerSource, player, 'air');
	copyBoolean(playerSource, player, 'onFire', 'fire');
	copyNumber(playerSource, player, 'fallDistance');

	const entities = boundedDataArray(source.entities, 'entities', MAX_ENTITIES)
		.map((value, index) => entityFacts(value, index));
	const entityIds = new Set();
	for (const entity of entities) {
		if (entityIds.has(entity.stableId)) throw new TypeError(`duplicate entity identity '${entity.stableId}'`);
		entityIds.add(entity.stableId);
	}
	const items = entities
		.filter((entity) => entity.type === 'minecraft:item')
		.map(({ stableId, itemId, count, distance, tags, x, y, z }) => ({ stableId, itemId, count, ...(distance === undefined ? {} : { distance }), ...(tags === undefined ? {} : { tags }), x, y, z }));

	const blocks = boundedDataArray(source.blocks, 'blocks', MAX_BLOCKS)
		.map((value, index) => blockFacts(value, index));
	const blockIds = new Set();
	for (const block of blocks) {
		if (blockIds.has(block.stableId)) throw new TypeError(`duplicate block identity '${block.stableId}'`);
		blockIds.add(block.stableId);
	}

	const inventorySource = ownDataRecord(source.inventory, 'wire observation.inventory');
	const inventoryItems = boundedDataArray(inventorySource.items, 'inventory.items', MAX_INVENTORY_SUMMARIES)
		.map((value, index) => inventoryFacts(value, index));

	const tagCounts = tagCountsFacts(inventorySource.tagCounts);
	return {
		player,
		items,
		entities,
		blocks,
		inventory: { items: inventoryItems, ...(tagCounts === undefined ? {} : { tagCounts }) },
	};
}

/** Alias kept explicit for callers that want to document the protocol boundary. */
export const adaptWireObservation = adaptObservation;

function emptyFacts(dead = false) {
	return { player: dead ? { dead: true } : {}, items: [], entities: [], blocks: [], inventory: { items: [] } };
}

function entityFacts(value, index) {
	const source = ownDataRecord(value, `entities[${index}]`);
	const stableId = immutableIdentity(source, `entities[${index}]`);
	const point = coordinateSource(source, `entities[${index}]`);
	const type = identifier(source.type, `entities[${index}].type`);
	const result = { stableId, type, x: point.x, y: point.y, z: point.z };
	if (Object.hasOwn(source, 'distance')) result.distance = finiteNumber(source.distance, `entities[${index}].distance`);
	if (Object.hasOwn(source, 'tags')) result.tags = tags(source.tags, `entities[${index}].tags`);
	if (type === 'minecraft:item') {
		result.itemId = identifier(source.itemId, `entities[${index}].itemId`);
		result.count = positiveInteger(source.count, `entities[${index}].count`);
	}
	return result;
}

function blockFacts(value, index) {
	const source = ownDataRecord(value, `blocks[${index}]`);
	const point = coordinateSource(source, `blocks[${index}]`);
	const stableId = `${point.x},${point.y},${point.z}`;
	return { stableId, blockId: identifier(source.blockId, `blocks[${index}].blockId`), ...(Object.hasOwn(source, 'tags') ? { tags: tags(source.tags, `blocks[${index}].tags`) } : {}), x: point.x, y: point.y, z: point.z };
}

function inventoryFacts(value, index) {
	const source = ownDataRecord(value, `inventory.items[${index}]`);
	const slot = source.slot;
	if (!(Number.isSafeInteger(slot) && slot >= 0) && typeof slot !== 'string') throw new TypeError(`inventory.items[${index}].slot must be a slot identifier`);
	return {
		itemId: identifier(source.itemId, `inventory.items[${index}].itemId`),
		count: nonNegativeInteger(source.count, `inventory.items[${index}].count`),
		slot,
		...(Object.hasOwn(source, 'tags') ? { tags: tags(source.tags, `inventory.items[${index}].tags`) } : {}),
	};
}

function tags(value, label) {
	const values = boundedDataArray(value, label, MAX_OBSERVATION_TAGS).map((tag, index) => {
		if (typeof tag !== 'string' || !tag.startsWith('#') || tag.length < 2 || tag.length > 256) throw new TypeError(`${label}[${index}] must be a tag identifier`);
		return tag;
	});
	if (new Set(values).size !== values.length) throw new TypeError(`${label} must be unique`);
	return values;
}

function tagCountsFacts(value) {
	if (value === undefined) return undefined;
	const source = ownDataRecord(value, 'wire observation.inventory.tagCounts');
	const keys = Object.keys(source);
	if (keys.length > MAX_TAG_COUNT_ENTRIES) throw new TypeError(`tagCounts exceeds bound of ${MAX_TAG_COUNT_ENTRIES}`);
	const result = {};
	for (const key of keys) {
		if (!key.startsWith('#') || key.length < 2 || key.length > 256) throw new TypeError(`tagCounts key must be a tag identifier`);
		result[key] = nonNegativeInteger(source[key], `tagCounts.${key}`);
	}
	return result;
}

function coordinateSource(source, label) {
	if (Object.hasOwn(source, 'position')) return vector(source.position, `${label}.position`);
	const point = {};
	for (const field of COORDINATE_FIELDS) point[field] = finiteNumber(source[field], `${label}.${field}`);
	return point;
}

function vector(value, label, fields = COORDINATE_FIELDS) {
	const source = ownDataRecord(value, label);
	const result = {};
	for (const field of fields) result[field] = finiteNumber(source[field], `${label}.${field}`);
	return result;
}

function immutableIdentity(source, label) {
	const field = Object.hasOwn(source, 'uuid') ? 'uuid' : Object.hasOwn(source, 'id') ? 'id' : null;
	if (field === null) throw new TypeError(`${label} requires an immutable uuid or id`);
	return identifier(source[field], `${label}.${field}`);
}

function copyNumber(source, target, sourceField, targetField = sourceField) {
	if (Object.hasOwn(source, sourceField)) target[targetField] = finiteNumber(source[sourceField], `player.${sourceField}`);
}

function copyBoolean(source, target, sourceField, targetField = sourceField) {
	if (Object.hasOwn(source, sourceField)) {
		if (typeof source[sourceField] !== 'boolean') throw new TypeError(`player.${sourceField} must be boolean`);
		target[targetField] = source[sourceField];
	}
}

function identifier(value, label) {
	if (typeof value !== 'string' || value.length === 0 || value.length > 256) throw new TypeError(`${label} must be a nonblank identifier`);
	return value;
}

function finiteNumber(value, label) {
	if (typeof value !== 'number' || !Number.isFinite(value)) throw new TypeError(`${label} must be a finite number`);
	return value;
}

function positiveInteger(value, label) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${label} must be a positive integer`);
	return value;
}

function nonNegativeInteger(value, label) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${label} must be a non-negative integer`);
	return value;
}

function boundedDataArray(value, label, maximum) {
	if (!Array.isArray(value) || nodeTypes.isProxy(value) || Object.getPrototypeOf(value) !== Array.prototype) throw new TypeError(`${label} must be a plain array`);
	const descriptors = Object.getOwnPropertyDescriptors(value);
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key === 'symbol' || (key !== 'length' && !/^(0|[1-9]\d*)$/.test(key)))) throw new TypeError(`${label} has unsafe keys`);
	const length = descriptors.length;
	if (!length || length.get || length.set || !Number.isSafeInteger(length.value)) throw new TypeError(`${label} has invalid length`);
	if (length.value > maximum) throw new TypeError(`${label} exceeds bound of ${maximum}`);
	const copied = [];
	for (let index = 0; index < length.value; index += 1) {
		const descriptor = descriptors[String(index)];
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label} must be dense own data`);
		copied.push(descriptor.value);
	}
	if (keys.length !== length.value + 1) throw new TypeError(`${label} must not have holes or custom keys`);
	return copied;
}

function ownDataRecord(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) throw new TypeError(`${label} must be a plain data record`);
	const prototype = Object.getPrototypeOf(value);
	if (prototype !== null && prototype !== Object.prototype) throw new TypeError(`${label} must be a plain data record`);
	const record = Object.create(null);
	for (const key of Reflect.ownKeys(value)) {
		if (typeof key !== 'string' || FORBIDDEN_KEYS.has(key)) throw new TypeError(`${label} contains an unsafe key`);
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label}.${key} must be own data`);
		record[key] = descriptor.value;
	}
	return record;
}
