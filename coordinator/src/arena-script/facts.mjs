const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);

/** Builds the immutable, observation-only factual surface available to ArenaScript. */
export function createFactView(observation) {
	const data = createInterpreterFacts(observation);
	const player = data.player;
	const items = data.world.items;
	const entities = data.world.entities;
	const blocks = data.world.blocks;
	const inventory = data.inventory;
	return freezeRecord({
		player,
		world: freezeRecord({
			items: (criteria = {}) => filterObserved(items, criteria),
			entities: (criteria = {}) => filterObserved(entities, criteria),
			blocks: (criteria = {}) => filterObserved(blocks, criteria),
			nearest: (candidates, origin = player) => nearest(candidates, origin),
		}),
		inventory: freezeRecord({
			count: (itemId) => countInventory(inventory.items, itemId),
			countTag: (tag) => inventory.tagCounts[tag] ?? 0,
		}),
	});
}

/** Returns the serializable counterpart used by the deterministic interpreter. */
export function createInterpreterFacts(observation = {}) {
	const source = safeRecord(observation);
	const player = freezeRecord(copyFields(safeRecord(source.player), ['x', 'y', 'z', 'health', 'hunger', 'air', 'fire', 'dead', 'yaw', 'pitch']));
	const world = freezeRecord({
		items: freezeList(source.items, copyCandidate),
		entities: freezeList(source.entities, copyCandidate),
		blocks: freezeList(source.blocks, copyCandidate),
	});
	const inventorySource = safeRecord(source.inventory);
	const tagCounts = Object.create(null);
	for (const [tag, count] of Object.entries(safeRecord(inventorySource.tagCounts))) {
		if (validKey(tag) && nonNegativeInteger(count)) tagCounts[tag] = count;
	}
	return freezeRecord({
		player,
		world,
		inventory: freezeRecord({ items: freezeList(inventorySource.items, copyInventoryItem), tagCounts: freezeRecord(tagCounts) }),
	});
}

export function filterObserved(candidates, criteria = {}) {
	if (!Array.isArray(candidates) || !isPlainRecord(criteria)) return Object.freeze([]);
	const entries = Object.entries(criteria).filter(([key]) => validKey(key));
	return Object.freeze(candidates.filter((candidate) => entries.every(([key, value]) => matches(candidate, key, value))));
}

export function nearest(candidates, origin = {}) {
	if (!Array.isArray(candidates) || candidates.length === 0) return null;
	const point = pointOf(origin);
	return [...candidates].sort((left, right) => distanceSquared(left, point) - distanceSquared(right, point)
		|| String(left.stableId).localeCompare(String(right.stableId)))[0] ?? null;
}

function copyCandidate(value) {
	const source = safeRecord(value);
	const copied = copyFields(source, ['stableId', 'entityId', 'type', 'itemId', 'blockId', 'count', 'x', 'y', 'z', 'reachable', 'visible', 'distance']);
	if (Array.isArray(source.tags)) copied.tags = Object.freeze(source.tags.filter((tag) => typeof tag === 'string').map((tag) => tag));
	copied.position = freezeRecord({ x: number(source.x), y: number(source.y), z: number(source.z) });
	return freezeRecord(copied);
}

function copyInventoryItem(value) {
	const source = safeRecord(value);
	return freezeRecord(copyFields(source, ['itemId', 'count', 'slot']));
}

function copyFields(source, names) {
	const copied = Object.create(null);
	for (const name of names) {
		if (typeof source[name] === 'string' || typeof source[name] === 'boolean' || Number.isFinite(source[name])) copied[name] = source[name];
	}
	return copied;
}

function countInventory(items, itemId) {
	if (typeof itemId !== 'string') return 0;
	return items.reduce((total, item) => total + (item.itemId === itemId && nonNegativeInteger(item.count) ? item.count : 0), 0);
}

function matches(candidate, key, value) {
	if (key === 'tag') return Array.isArray(candidate.tags) && candidate.tags.includes(value);
	return Object.hasOwn(candidate, key) && candidate[key] === value;
}

function distanceSquared(candidate, origin) {
	const point = pointOf(candidate);
	const dx = point.x - origin.x;
	const dy = point.y - origin.y;
	const dz = point.z - origin.z;
	return dx * dx + dy * dy + dz * dz;
}

function pointOf(value) {
	const safeValue = safeRecord(value);
	const source = safeRecord(safeValue.position ?? safeValue);
	return { x: number(source.x), y: number(source.y), z: number(source.z) };
}

function number(value) { return Number.isFinite(value) ? value : 0; }
function nonNegativeInteger(value) { return Number.isSafeInteger(value) && value >= 0; }
function validKey(key) { return !FORBIDDEN_KEYS.has(key); }
function safeRecord(value) { return isPlainRecord(value) ? value : Object.create(null); }
function isPlainRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [null, Object.prototype].includes(Object.getPrototypeOf(value)); }
function freezeList(values, mapper) { return Object.freeze(Array.isArray(values) ? values.map(mapper) : []); }
function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
