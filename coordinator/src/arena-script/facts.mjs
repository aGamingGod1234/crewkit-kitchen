import { types as nodeTypes } from 'node:util';

const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
const OBSERVED_SETS = new WeakSet();
const CANDIDATE_ORIGINS = new WeakMap();
const PLAYER_FIELDS = ['x', 'y', 'z', 'health', 'hunger', 'air', 'fire', 'dead', 'yaw', 'pitch'];
const CANDIDATE_FIELDS = ['stableId', 'entityId', 'type', 'itemId', 'blockId', 'count', 'x', 'y', 'z', 'reachable', 'visible', 'distance', 'tags'];

/** Builds an immutable, observation-only fact view. */
export function createFactView(observation) {
	const data = createInterpreterFacts(observation);
	const candidates = new WeakSet([data.world.items, data.world.entities, data.world.blocks]);
	const query = (list, criteria = {}) => {
		const filtered = filterObserved(list, criteria);
		candidates.add(filtered);
		return filtered;
	};
	return freezeRecord({
		player: data.player,
		world: freezeRecord({
			items: (criteria = {}) => query(data.world.items, criteria),
			entities: (criteria = {}) => query(data.world.entities, criteria),
			blocks: (criteria = {}) => query(data.world.blocks, criteria),
			nearest: (list, origin = data.player) => {
				if (!candidates.has(list)) throw new TypeError('nearest requires an observed candidate set');
				return nearest(list, origin);
			},
		}),
		inventory: freezeRecord({ count: (itemId) => countInventory(data.inventory.items, itemId), countTag: (tag) => data.inventory.tagCounts[tag] ?? 0 }),
	});
}

/** Serializable frozen facts used by the interpreter. */
export function createInterpreterFacts(observation = {}) {
	const source = ownDataRecord(observation, 'observation');
	const player = freezeRecord(copyRecord(ownDataRecord(source.player ?? Object.create(null), 'observation.player'), PLAYER_FIELDS, 'observation.player'));
	const inventorySource = ownDataRecord(source.inventory ?? Object.create(null), 'observation.inventory');
	const tagCounts = Object.create(null);
	for (const [tag, count] of Object.entries(ownDataRecord(inventorySource.tagCounts ?? Object.create(null), 'observation.inventory.tagCounts'))) {
		if (validKey(tag) && nonNegativeInteger(count)) tagCounts[tag] = count;
	}
	return freezeRecord({
		player,
		world: freezeRecord({ items: copyCandidates(source.items, 'item'), entities: copyCandidates(source.entities, 'entity'), blocks: copyCandidates(source.blocks, 'block') }),
		inventory: freezeRecord({ items: copyInventory(inventorySource.items), tagCounts: freezeRecord(tagCounts) }),
	});
}

export function filterObserved(candidates, criteria = {}) {
	if (!OBSERVED_SETS.has(candidates)) throw new TypeError('candidate set is not an observed fact set');
	const record = ownDataRecord(criteria, 'criteria');
	const entries = Object.entries(record);
	if (entries.some(([key]) => !validKey(key))) throw new TypeError('criteria contains an unsafe key');
	return observedList(candidates.filter((candidate) => entries.every(([key, value]) => matches(candidate, key, value))), candidates);
}

export function nearest(candidates, origin) {
	if (!OBSERVED_SETS.has(candidates)) throw new TypeError('nearest requires an observed candidate set');
	if (candidates.length === 0) return null;
	const point = pointOf(origin, 'origin');
	return [...candidates].sort((left, right) => distanceSquared(left, point) - distanceSquared(right, point) || codePointCompare(left.stableId, right.stableId))[0] ?? null;
}

export function nearestFromCurrent(candidates, origin, currentSets) {
	if (!Array.isArray(currentSets) || !currentSets.includes(CANDIDATE_ORIGINS.get(candidates))) throw new TypeError('nearest candidates are not from the current observation');
	return nearest(candidates, origin);
}

export function markObservedCandidateSet(candidates) {
	if (!Array.isArray(candidates) || !Object.isFrozen(candidates)) throw new TypeError('observed candidate set must be a frozen array');
	OBSERVED_SETS.add(candidates);
	return candidates;
}

function copyCandidates(values, kind) {
	if (values === undefined) return observedList([]);
	return observedList(denseDataArray(values, `observation ${kind}s`).map((value) => copyCandidate(value, kind)));
}

function copyCandidate(value, kind) {
	const source = ownDataRecord(value, `observation ${kind}`);
	const required = kind === 'item' ? ['stableId', 'itemId', 'count', 'x', 'y', 'z'] : kind === 'entity' ? ['stableId', 'type', 'x', 'y', 'z'] : ['stableId', 'blockId', 'x', 'y', 'z'];
	if (Reflect.ownKeys(source).some((key) => typeof key !== 'string' || !CANDIDATE_FIELDS.includes(key)) || required.some((key) => !Object.hasOwn(source, key))) throw new TypeError(`observation ${kind} has an invalid schema`);
	if (!Number.isFinite(source.x) || !Number.isFinite(source.y) || !Number.isFinite(source.z)) return null;
	if (typeof source.stableId !== 'string' || source.stableId.length === 0) throw new TypeError(`observation ${kind} has invalid identity`);
	if (kind === 'item' && (typeof source.itemId !== 'string' || !nonNegativeInteger(source.count))) throw new TypeError('observation item has invalid item fields');
	if (kind === 'entity' && typeof source.type !== 'string') throw new TypeError('observation entity has invalid type');
	if (kind === 'block' && typeof source.blockId !== 'string') throw new TypeError('observation block has invalid block id');
	const copied = copyRecord(source, CANDIDATE_FIELDS, `observation ${kind}`);
	if (Object.hasOwn(source, 'tags')) {
		const tags = denseDataArray(source.tags, `observation ${kind} tags`);
		if (tags.some((tag) => typeof tag !== 'string')) throw new TypeError(`observation ${kind} has invalid tags`);
		copied.tags = Object.freeze([...tags]);
	}
	copied.position = freezeRecord({ x: source.x, y: source.y, z: source.z });
	return freezeRecord(copied);
}

function copyInventory(values) {
	if (values === undefined) return Object.freeze([]);
	return Object.freeze(denseDataArray(values, 'observation inventory items').map((value) => {
		const source = ownDataRecord(value, 'observation inventory item');
		if (Reflect.ownKeys(source).some((key) => !['itemId', 'count', 'slot'].includes(key)) || typeof source.itemId !== 'string' || !nonNegativeInteger(source.count)) throw new TypeError('observation inventory item has an invalid schema');
		return freezeRecord(copyRecord(source, ['itemId', 'count', 'slot'], 'observation inventory item'));
	}));
}

function denseDataArray(value, label) {
	if (!Array.isArray(value) || nodeTypes.isProxy(value) || Object.getPrototypeOf(value) !== Array.prototype) throw new TypeError(`${label} must be a plain array`);
	const descriptors = Object.getOwnPropertyDescriptors(value);
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key === 'symbol' || (key !== 'length' && !/^(0|[1-9]\d*)$/.test(key)))) throw new TypeError(`${label} has unsafe keys`);
	const length = descriptors.length;
	if (!length || !Object.hasOwn(length, 'value') || length.get || length.set || !Number.isSafeInteger(length.value)) throw new TypeError(`${label} has invalid length`);
	const copied = [];
	for (let index = 0; index < length.value; index += 1) {
		const descriptor = descriptors[String(index)];
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label} must be dense own data`);
		copied.push(descriptor.value);
	}
	if (keys.length !== length.value + 1) throw new TypeError(`${label} must not have holes or custom keys`);
	return copied;
}

function copyRecord(source, names, label) {
	const copied = Object.create(null);
	for (const name of names) {
		if (!Object.hasOwn(source, name)) continue;
		const value = source[name];
		if (typeof value === 'string' || typeof value === 'boolean' || Number.isFinite(value)) copied[name] = value;
	}
	return copied;
}

function ownDataRecord(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value) || ![null, Object.prototype].includes(Object.getPrototypeOf(value))) throw new TypeError(`${label} must be a plain data record`);
	const record = Object.create(null);
	for (const key of Reflect.ownKeys(value)) {
		if (typeof key !== 'string' || FORBIDDEN_KEYS.has(key)) throw new TypeError(`${label} contains an unsafe key`);
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label}.${key} must be own data`);
		record[key] = descriptor.value;
	}
	return record;
}

function countInventory(items, itemId) { return typeof itemId === 'string' ? items.reduce((total, item) => total + (item.itemId === itemId ? item.count : 0), 0) : 0; }
function matches(candidate, key, value) { return key === 'tag' ? Array.isArray(candidate.tags) && candidate.tags.includes(value) : Object.hasOwn(candidate, key) && candidate[key] === value; }
function distanceSquared(candidate, origin) { const point = pointOf(candidate, 'candidate'); return (point.x - origin.x) ** 2 + (point.y - origin.y) ** 2 + (point.z - origin.z) ** 2; }
function pointOf(value, label) { const source = ownDataRecord(value, label); const point = Object.hasOwn(source, 'position') ? ownDataRecord(source.position, `${label}.position`) : source; if (![point.x, point.y, point.z].every(Number.isFinite)) throw new TypeError(`${label} requires finite coordinates`); return { x: point.x, y: point.y, z: point.z }; }
function codePointCompare(left, right) { return left === right ? 0 : left < right ? -1 : 1; }
function nonNegativeInteger(value) { return Number.isSafeInteger(value) && value >= 0; }
function validKey(key) { return !FORBIDDEN_KEYS.has(key); }
function observedList(values, origin = null) { const frozen = Object.freeze(values.filter((value) => value !== null)); OBSERVED_SETS.add(frozen); if (origin !== null) CANDIDATE_ORIGINS.set(frozen, origin); return frozen; }
function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
