/** Bounded occupancy and SayCan-style frontier selection for native exploreFrontier. */

export const CELL_SIZE = 8;
export const DEFAULT_RADIUS = 24;
export const MIN_RADIUS = 8;
export const MAX_RADIUS = 32;
export const MAX_KNOWN_CELLS = 256;
export const CUE_IN_VIEW_DISTANCE = 2.5;
export const SEEK_VALUES = Object.freeze(['any', 'nether', 'cave', 'village', 'structure']);

const CARDINAL = Object.freeze([
	[1, 0],
	[0, 1],
	[-1, 0],
	[0, -1],
]);
const HEADING_VECTORS = Object.freeze({
	east: { dx: 1, dz: 0 },
	west: { dx: -1, dz: 0 },
	south: { dx: 0, dz: 1 },
	north: { dx: 0, dz: -1 },
});

const OVERWORLD = Object.freeze(['minecraft:overworld']);
const NETHER = Object.freeze(['minecraft:the_nether']);

const CUE_WEIGHTS = Object.freeze({
	nether_portal: { class: 'portal', weight: 100, seek: Object.freeze(['any', 'nether', 'structure']) },
	obsidian: { class: 'portal', weight: 80, seek: Object.freeze(['any', 'nether', 'structure']) },
	crying_obsidian: { class: 'portal', weight: 90, seek: Object.freeze(['any', 'nether', 'structure']) },
	netherrack: { class: 'ruined_portal', weight: 70, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: OVERWORLD },
	magma_block: { class: 'lava', weight: 55, seek: Object.freeze(['any', 'nether', 'cave']), dimensions: OVERWORLD },
	blackstone: { class: 'ruined_portal', weight: 60, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: OVERWORLD },
	gold_block: { class: 'ruined_portal', weight: 65, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: OVERWORLD },
	lava: { class: 'lava', weight: 50, seek: Object.freeze(['any', 'nether', 'cave']), dimensions: OVERWORLD },
	nether_bricks: { class: 'fortress', weight: 85, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	cracked_nether_bricks: { class: 'fortress', weight: 80, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	nether_brick_fence: { class: 'fortress', weight: 88, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	nether_brick_stairs: { class: 'fortress', weight: 82, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	nether_wart: { class: 'fortress', weight: 70, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	gilded_blackstone: { class: 'bastion', weight: 85, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	polished_blackstone: { class: 'bastion', weight: 60, seek: Object.freeze(['any', 'nether', 'structure']), dimensions: NETHER },
	deepslate: { class: 'cave', weight: 30, seek: Object.freeze(['any', 'cave']) },
	dripstone_block: { class: 'cave', weight: 35, seek: Object.freeze(['any', 'cave']) },
	pointed_dripstone: { class: 'cave', weight: 35, seek: Object.freeze(['any', 'cave']) },
	moss_block: { class: 'cave', weight: 28, seek: Object.freeze(['any', 'cave']) },
	sculk: { class: 'cave', weight: 40, seek: Object.freeze(['any', 'cave', 'structure']) },
	amethyst_block: { class: 'cave', weight: 38, seek: Object.freeze(['any', 'cave']) },
	tuff: { class: 'cave', weight: 25, seek: Object.freeze(['any', 'cave']) },
	spawner: { class: 'dungeon', weight: 85, seek: Object.freeze(['any', 'cave', 'structure']) },
	bell: { class: 'village', weight: 80, seek: Object.freeze(['any', 'village', 'structure']) },
	hay_block: { class: 'village', weight: 50, seek: Object.freeze(['any', 'village']) },
	composter: { class: 'village', weight: 45, seek: Object.freeze(['any', 'village']) },
	dirt_path: { class: 'village', weight: 40, seek: Object.freeze(['any', 'village']) },
	white_bed: { class: 'village', weight: 40, seek: Object.freeze(['any', 'village']) },
	mossy_cobblestone: { class: 'ruins', weight: 42, seek: Object.freeze(['any', 'structure', 'cave']) },
	cracked_stone_bricks: { class: 'ruins', weight: 40, seek: Object.freeze(['any', 'structure']) },
	rail: { class: 'mineshaft', weight: 48, seek: Object.freeze(['any', 'cave', 'structure']) },
	cobweb: { class: 'mineshaft', weight: 32, seek: Object.freeze(['any', 'cave', 'structure']) },
	kelp: { class: 'ocean', weight: 30, seek: Object.freeze(['any']) },
	prismarine: { class: 'ocean', weight: 55, seek: Object.freeze(['any', 'structure']) },
	sandstone: { class: 'desert', weight: 22, seek: Object.freeze(['any']) },
	cactus: { class: 'desert', weight: 28, seek: Object.freeze(['any']) },
	terracotta: { class: 'desert', weight: 24, seek: Object.freeze(['any']) },
});

export class ExplorationOccupancy {
	#agents = new Map();

	ingest(agentId, observation) {
		const map = this.#mapFor(agentId);
		const position = extractPosition(observation);
		const dimension = extractDimension(observation);
		if (position === null) return map;
		if (map.dimension !== dimension) {
			map.dimension = dimension;
			map.cells.clear();
			map.order.length = 0;
			map.heading = null;
		}
		this.#remember(map, cellKey(position.x, position.z), false);
		for (const block of visibleBlocks(observation)) {
			if (Number.isFinite(block.x) && Number.isFinite(block.z)) this.#remember(map, cellKey(block.x, block.z), false);
		}
		return map;
	}

	markBlocked(agentId, dimension, x, z) {
		const map = this.#mapFor(agentId);
		if (map.dimension !== dimension) return;
		this.#remember(map, cellKey(x, z), true);
	}

	rememberHeading(agentId, from, to) {
		const map = this.#mapFor(agentId);
		const dx = to.x - from.x;
		const dz = to.z - from.z;
		if (dx === 0 && dz === 0) return;
		map.heading = { dx, dz };
	}

	snapshot(agentId, dimension = null) {
		const map = this.#agents.get(agentId);
		if (map === undefined) return { dimension: dimension ?? 'minecraft:overworld', knownCells: 0, blockedCells: 0 };
		if (dimension !== null && map.dimension !== dimension) {
			return { dimension, knownCells: 0, blockedCells: 0 };
		}
		let blockedCells = 0;
		for (const cell of map.cells.values()) if (cell.blocked) blockedCells += 1;
		return { dimension: map.dimension, knownCells: map.cells.size, blockedCells };
	}

	select(agentId, observation, options = {}) {
		const seek = options.seek ?? 'any';
		const radius = options.radius ?? DEFAULT_RADIUS;
		const position = extractPosition(observation);
		if (position === null) {
			return { kind: 'no_observation', seek, radius, dimension: extractDimension(observation), destination: null, cue: null };
		}
		const map = this.ingest(agentId, observation);
		if (typeof options.heading === 'string') {
			const vector = HEADING_VECTORS[options.heading];
			if (vector !== undefined) map.heading = vector;
		}
		const cues = matchingCues(observation, seek);
		const nearestCue = cues[0] ?? null;
		if (nearestCue !== null && nearestCue.distance <= CUE_IN_VIEW_DISTANCE) {
			return compactSelection({
				kind: 'cue_in_view',
				seek,
				radius,
				dimension: map.dimension,
				destination: { x: nearestCue.x, y: nearestCue.y, z: nearestCue.z },
				cue: nearestCue,
				reason: 'matching cue is already in interaction range',
			}, map);
		}
		if (nearestCue !== null) {
			const destination = hopToward(position, nearestCue, radius);
			this.rememberHeading(agentId, position, destination);
			return compactSelection({
				kind: 'cue',
				seek,
				radius,
				dimension: map.dimension,
				destination,
				cue: nearestCue,
				reason: 'walk toward a matching visible biome or structure cue',
			}, map);
		}
		const frontier = bestFrontierCell(map, position, radius);
		if (frontier === null) {
			return compactSelection({
				kind: 'no_frontier',
				seek,
				radius,
				dimension: map.dimension,
				destination: null,
				cue: null,
				reason: 'no unknown adjacent cell remains inside the bounded radius',
			}, map);
		}
		const destination = {
			x: frontier.cx * CELL_SIZE + CELL_SIZE / 2 + 0.5,
			y: position.y,
			z: frontier.cz * CELL_SIZE + CELL_SIZE / 2 + 0.5,
		};
		this.rememberHeading(agentId, position, destination);
		return compactSelection({
			kind: 'frontier',
			seek,
			radius,
			dimension: map.dimension,
			destination,
			cue: null,
			reason: 'walk into unknown space adjacent to mapped occupancy',
		}, map);
	}

	clear(agentId) {
		if (agentId === undefined) this.#agents.clear();
		else this.#agents.delete(agentId);
	}

	#mapFor(agentId) {
		let map = this.#agents.get(agentId);
		if (map === undefined) {
			map = { dimension: 'minecraft:overworld', cells: new Map(), order: [], heading: null };
			this.#agents.set(agentId, map);
		}
		return map;
	}

	#remember(map, key, blocked) {
		const existing = map.cells.get(key);
		if (existing !== undefined) {
			existing.visits += 1;
			if (blocked) existing.blocked = true;
			const index = map.order.indexOf(key);
			if (index >= 0) map.order.splice(index, 1);
			map.order.push(key);
			return;
		}
		map.cells.set(key, { visits: 1, blocked });
		map.order.push(key);
		while (map.cells.size > MAX_KNOWN_CELLS) {
			const oldest = map.order.shift();
			if (oldest !== undefined) map.cells.delete(oldest);
		}
	}
}

export function extractPosition(observation) {
	const source = observation?.position
		?? observation?.player?.position
		?? observation?.player;
	if (source === null || typeof source !== 'object') return null;
	const x = source.x;
	const y = source.y;
	const z = source.z;
	if (![x, y, z].every(Number.isFinite)) return null;
	return { x, y, z };
}

export function extractDimension(observation) {
	const dimension = observation?.world?.dimension ?? observation?.world?.dimensionId;
	return typeof dimension === 'string' && dimension.length > 0 ? dimension : 'minecraft:overworld';
}

export function cellKey(x, z) {
	return `${Math.floor(x / CELL_SIZE)},${Math.floor(z / CELL_SIZE)}`;
}

export function cueClassFor(blockId) {
	return CUE_WEIGHTS[bareBlockName(blockId)] ?? null;
}

function matchingCues(observation, seek) {
	const position = extractPosition(observation);
	const cues = [];
	for (const block of visibleBlocks(observation)) {
		const spec = CUE_WEIGHTS[bareBlockName(block.blockId)];
		if (spec === undefined || !spec.seek.includes(seek)) continue;
		if (!cueMatchesDimension(spec, extractDimension(observation))) continue;
		if (![block.x, block.y, block.z].every(Number.isFinite)) continue;
		const distance = position === null
			? Number.POSITIVE_INFINITY
			: Math.hypot(block.x + 0.5 - position.x, block.z + 0.5 - position.z);
		cues.push({
			blockId: block.blockId,
			class: spec.class,
			weight: spec.weight,
			x: block.x + 0.5,
			y: Number.isFinite(block.y) ? block.y : position?.y ?? 64,
			z: block.z + 0.5,
			distance,
		});
	}
	cues.sort((left, right) => right.weight - left.weight
		|| left.distance - right.distance
		|| left.x - right.x
		|| left.z - right.z
		|| left.blockId.localeCompare(right.blockId));
	return cues;
}

function cueMatchesDimension(spec, dimension) {
	if (!Array.isArray(spec.dimensions) || spec.dimensions.length === 0) return true;
	return spec.dimensions.includes(dimension);
}

function visibleBlocks(observation) {
	const blocks = Array.isArray(observation?.blocks) ? observation.blocks : [];
	const containers = Array.isArray(observation?.nearbyContainers) ? observation.nearbyContainers : [];
	return [...blocks, ...containers];
}

function bestFrontierCell(map, position, radius) {
	const originCx = Math.floor(position.x / CELL_SIZE);
	const originCz = Math.floor(position.z / CELL_SIZE);
	const candidates = [];
	for (const key of map.cells.keys()) {
		const known = map.cells.get(key);
		if (known?.blocked) continue;
		const [cx, cz] = key.split(',').map(Number);
		for (const [dx, dz] of CARDINAL) {
			const nx = cx + dx;
			const nz = cz + dz;
			const neighborKey = `${nx},${nz}`;
			const neighbor = map.cells.get(neighborKey);
			if (neighbor !== undefined) continue;
			const centerX = nx * CELL_SIZE + CELL_SIZE / 2 + 0.5;
			const centerZ = nz * CELL_SIZE + CELL_SIZE / 2 + 0.5;
			const distance = Math.hypot(centerX - position.x, centerZ - position.z);
			if (distance > radius) continue;
			const headingBonus = headingAlignment(map.heading, centerX - position.x, centerZ - position.z);
			candidates.push({
				cx: nx,
				cz: nz,
				originCx,
				originCz,
				distance,
				score: -distance + headingBonus,
			});
		}
	}
	if (candidates.length === 0) return null;
	candidates.sort((left, right) => right.score - left.score
		|| left.cx - right.cx
		|| left.cz - right.cz);
	return candidates[0];
}

function headingAlignment(heading, dx, dz) {
	if (heading === null || (dx === 0 && dz === 0)) return 0;
	const length = Math.hypot(dx, dz) * Math.hypot(heading.dx, heading.dz);
	if (length === 0) return 0;
	return (dx * heading.dx + dz * heading.dz) / length;
}

function hopToward(from, to, radius) {
	const dx = to.x - from.x;
	const dz = to.z - from.z;
	const distance = Math.hypot(dx, dz);
	if (distance <= radius) return { x: to.x, y: from.y, z: to.z };
	const scale = radius / distance;
	return { x: from.x + dx * scale, y: from.y, z: from.z + dz * scale };
}

function compactSelection(selection, map) {
	let frontierCount = 0;
	for (const key of map.cells.keys()) {
		if (map.cells.get(key)?.blocked) continue;
		const [cx, cz] = key.split(',').map(Number);
		for (const [dx, dz] of CARDINAL) {
			if (!map.cells.has(`${cx + dx},${cz + dz}`)) frontierCount += 1;
		}
	}
	return {
		...selection,
		knownCells: map.cells.size,
		frontierCount,
		cue: selection.cue === null ? null : {
			blockId: selection.cue.blockId,
			class: selection.cue.class,
			x: selection.cue.x,
			y: selection.cue.y,
			z: selection.cue.z,
			distance: round1(selection.cue.distance),
		},
		destination: selection.destination === null ? null : {
			x: round1(selection.destination.x),
			y: round1(selection.destination.y),
			z: round1(selection.destination.z),
		},
	};
}

function bareBlockName(blockId) {
	if (typeof blockId !== 'string' || blockId.length === 0) return '';
	const slash = blockId.lastIndexOf(':');
	return slash >= 0 ? blockId.slice(slash + 1) : blockId;
}

function round1(value) {
	return Math.round(value * 10) / 10;
}
