import { buildNativeEventInput } from '../dynamic-main.mjs';
import { ModelObservationViews, encodeNativeEventInput } from '../model-fact-encoding.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, NATIVE_AGENT_INSTRUCTIONS, minecraftCapabilities } from '../native-minecraft-tools.mjs';

/**
 * Offline token-budget model for one native agent. Byte sizes come from the real builders and
 * encoders; tokens use the rough 4 bytes/token ratio of JSON-heavy English. Call counts come from a
 * measured 58-minute Claude Opus 5.5 (low effort) session so before/after estimates share one workload.
 */
export const BYTES_PER_TOKEN = 4;

export const MEASURED_SESSION = Object.freeze({
	minutes: 58,
	modelCalls: 776,
	turns: 358,
	zeroToolTurns: 310,
	zeroToolTurnsWhileProgramRan: 305,
	statusPolls: 93,
	modelBusyMinutes: 52.4,
	averageCallMs: 4_100,
});

const block = (index) => ({ stableId: `${-440 + (index % 8)},${84 + Math.floor(index / 8)},127`, x: -440 + (index % 8), y: 84 + Math.floor(index / 8), z: 127,
	blockId: index % 5 === 0 ? 'minecraft:coal_ore' : 'minecraft:stone', tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'], state: {},
	bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }], distance: Number((2 + index * 0.17).toFixed(2)), visible: index % 3 !== 0 });

/** A mid-game mining event shaped like the session's trimmed event (blocks ~6 KB, interaction ~2 KB, perception ~2 KB). */
export function representativeObservation(sequence = 1) {
	return {
		observedAtEpochMs: 1_791_378_149_162 + sequence * 1_000,
		eventSequence: 4_000 + sequence,
		freshness: { fresh: true, ageMs: 40, source: 'publication' },
		coverage: { complete: false, sections: { blocks: { returned: 32, total: 211, complete: false, hasMore: true, nextOffset: 32 }, entities: { returned: 6, total: 6, complete: true } } },
		perception: { daylight: true, timeOfDay: 6_120 + sequence * 20, weather: 'clear', lightLevel: 11, biome: 'minecraft:windswept_hills',
			events: Array.from({ length: 8 }, (_, index) => ({ tick: 2_800 + index * 15, kind: index % 2 ? 'sound' : 'block_broken', id: index % 2 ? 'minecraft:entity.zombie.ambient' : 'minecraft:stone', x: -440 + index, y: 85, z: 126, distance: 4 + index })) },
		velocity: { x: 0, y: -0.0784, z: 0 },
		player: { x: -440.73, y: 87, z: 123.34, yaw: -21.28, pitch: -29.03, health: 18, food: 15, saturation: 2.4, air: 300, armor: 0, level: 3, onGround: true, inWater: false, inLava: false, dead: false, sprinting: false, sneaking: false, selectedSlot: 0 },
		inventory: {
			items: [
				{ slot: 0, itemId: 'minecraft:stone_pickaxe', count: 1, damage: 41, maxDamage: 131 },
				{ slot: 1, itemId: 'minecraft:wooden_axe', count: 1, damage: 12, maxDamage: 59 },
				{ slot: 2, itemId: 'minecraft:cobblestone', count: 47 },
				{ slot: 3, itemId: 'minecraft:coal', count: 9 },
				{ slot: 4, itemId: 'minecraft:oak_log', count: 6 },
				{ slot: 5, itemId: 'minecraft:oak_planks', count: 11 },
				{ slot: 6, itemId: 'minecraft:stick', count: 7 },
				{ slot: 7, itemId: 'minecraft:crafting_table', count: 1 },
				{ slot: 8, itemId: 'minecraft:bread', count: 3 },
				{ slot: 9, itemId: 'minecraft:raw_iron', count: 4 },
				{ slot: 10, itemId: 'minecraft:dirt', count: 22 },
				{ slot: 11, itemId: 'minecraft:andesite', count: 14 },
			],
			selectedItem: { slot: 0, itemId: 'minecraft:stone_pickaxe', count: 1 },
			tagCounts: { 'minecraft:logs': 6, 'minecraft:planks': 11, 'minecraft:coals': 9 },
		},
		items: [{ uuid: '6f1c9a52-6b5e-4a4e-9a35-0c4a2f6c7e11', itemId: 'minecraft:cobblestone', count: 1, x: -439.5, y: 86, z: 124.6, distance: 1.6 }],
		entities: Array.from({ length: 6 }, (_, index) => ({ uuid: `0b6d2c1e-3f4a-4b5c-8d9e-${String(index).padStart(12, '0')}`, type: index === 0 ? 'minecraft:zombie' : 'minecraft:sheep',
			hostile: index === 0, x: -430 + index * 3, y: 86, z: 118 + index, distance: 11 + index * 2.5, health: 10, targetingPlayer: false })),
		blocks: Array.from({ length: 32 }, (_, index) => block(index)),
		landmarks: [{ kind: 'crafting_table', x: -436, y: 86, z: 120, distance: 5.2 }, { kind: 'cave_entrance', x: -452, y: 80, z: 131, distance: 14.6 }],
		world: { worldId: 'fixture-world', dimension: 'minecraft:overworld', difficulty: 'normal', gameMode: 'survival' },
		interaction: {
			lookedAt: { type: 'block', position: { x: -440, y: 88, z: 127 }, id: 'minecraft:stone', face: 'north', hitDistance: 3.9 },
			reach: { distance: 4.87, blockReach: 4.5, entityReach: 3 },
			menu: null,
			mining: { active: false, progress: 0, lastBroken: { x: -440, y: 87, z: 127, id: 'minecraft:stone', tick: 2_822 } },
			hotbar: Array.from({ length: 9 }, (_, slot) => ({ slot, itemId: ['minecraft:stone_pickaxe', 'minecraft:wooden_axe', 'minecraft:cobblestone', 'minecraft:coal', 'minecraft:oak_log', 'minecraft:oak_planks', 'minecraft:stick', 'minecraft:crafting_table', 'minecraft:bread'][slot] })),
			cooldowns: [], usingItem: false,
		},
		lastResult: { actionId: `native:fixture:${sequence}`, state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', message: 'Block broken' },
	};
}

/** Mixed terrain: plain cubes beside slabs, fluids, torches and stateful blocks, so rows do not share one shape. */
export function mixedTerrainBlocks() {
	const kinds = [
		{ blockId: 'minecraft:stone', tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'] },
		{ blockId: 'minecraft:dirt', tags: ['minecraft:mineable/shovel', 'minecraft:dirt'] },
		{ blockId: 'minecraft:grass_block', tags: ['minecraft:mineable/shovel', 'minecraft:dirt'], state: { snowy: 'false' } },
		{ blockId: 'minecraft:oak_slab', tags: ['minecraft:slabs', 'minecraft:mineable/axe'], state: { type: 'bottom', waterlogged: 'false' }, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 0.5, maxZ: 1 }] },
		{ blockId: 'minecraft:water', tags: [], state: { level: '0' }, fluid: 'minecraft:water', replaceable: true, bounds: [] },
		{ blockId: 'minecraft:lava', tags: [], state: { level: '0' }, fluid: 'minecraft:lava', replaceable: true, bounds: [] },
		{ blockId: 'minecraft:iron_ore', tags: ['minecraft:mineable/pickaxe', 'minecraft:iron_ores'] },
		{ blockId: 'minecraft:wall_torch', tags: ['minecraft:wall_post_override'], state: { facing: 'north' }, bounds: [{ minX: 0.34, minY: 0.2, minZ: 0.62, maxX: 0.66, maxY: 0.8, maxZ: 1 }], replaceable: false },
	];
	return Array.from({ length: 32 }, (_, index) => {
		const kind = kinds[[0, 0, 1, 0, 2, 0, 3, 6, 0, 1, 4, 0, 7, 0, 5, 2][index % 16]];
		const x = -440 + (index % 6), y = 80 + Math.floor(index / 6), z = 127 - (index % 3);
		return { stableId: `${x},${y},${z}`, x, y, z, ...kind, state: kind.state ?? {}, bounds: kind.bounds ?? [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }],
			distance: Number((1.5 + index * 0.21).toFixed(2)), ...(index % 4 === 0 ? { placeableFaces: ['up', 'north'] } : {}) };
	});
}

export function representativeTaskMemory() {
	return { worldId: 'fixture-world', dimension: 'minecraft:overworld', revision: 41, currentGoalRevision: 1, historical: true,
		progress: [{ key: 'iron-tools', label: 'Iron tools', summary: 'Have stone pickaxe and 4 raw iron; need furnace, 3 iron ingots for pickaxe, then bucket and more iron for armor.', goalRevision: 1 }],
		routes: [{ key: 'base-to-cave', label: 'Base to cave', from: 'base', to: 'cave', status: 'active', waypointCount: 9 }],
		places: [
			{ key: 'base', label: 'Base', summary: 'Crafting table and chest near spawn hill.', position: { x: -436, y: 86, z: 120 } },
			{ key: 'cave', label: 'Cave entrance', summary: 'Cave with exposed coal and iron, zombies at night.', position: { x: -452, y: 80, z: 131 } },
			{ key: 'village', label: 'Village', summary: 'Plains village with farms and a blacksmith, about 300 blocks east.', position: { x: -130, y: 70, z: 88 } },
		],
		lessons: [{ key: 'night', label: 'Night', summary: 'Do not mine in the open cave at night without a shield or blocks to wall off.' }],
		assets: [{ kind: 'chest', x: -437, y: 86, z: 121, items: 5 }],
		deaths: [],
		totals: { entries: 7, assets: 1, deaths: 0 },
		query: 'Use taskMemory query for route waypoints, earlier records and pagination. Reobserve routes and drops before acting.' };
}

export function representativeRecord() {
	return { agentId: 'fixture-agent', goalRevision: 1, currentGoal: 'Beat the game',
		currentGoalSpec: { originalRequest: 'Beat the game', fingerprint: 'sha256:fixture', kind: 'advancement', target: { advancement: 'minecraft:end/kill_dragon' },
			successCriteria: ['Kill the ender dragon in The End'], constraints: ['Survival rules', 'No commands'], verification: 'Minecraft checks the advancement' } };
}

/** The program-attention wake that dominated the session: a background routine running with an ordinary decision pending. */
export function representativeProgramWake(sequence = 1) {
	return {
		event: 'program_attention', trigger: 'resource_discovery', programId: 'native-program-fixture-12',
		status: { state: 'RUNNING', engineState: 'ACTIVE', programVersion: 1, deadlineEpochMs: 1_791_378_200_000,
			decision: { decisionId: 'native-program-fixture-12:decision-3', programVersion: 1, trigger: 'resource_discovery', priority: 'ordinary', eventSequence: 4_000 + sequence } },
		eventSequence: 4_000 + sequence, taskMemory: representativeTaskMemory(), observation: representativeObservation(sequence),
		conversation: { mode: 'unread', baseSequence: 12, nextSequence: 12, entries: [] },
	};
}

const bytes = (text) => Buffer.byteLength(typeof text === 'string' ? text : JSON.stringify(text), 'utf8');

/** Measures the fixed per-call prefix and per-event input with the production builders and encoders. */
export function measureTokenBudget({ agentsMarkdown = '', skillMarkdown = '' } = {}) {
	const record = representativeRecord();
	const raw = buildNativeEventInput(record, representativeProgramWake(1));
	const views = new ModelObservationViews();
	const first = encodeNativeEventInput(raw, views);
	const second = encodeNativeEventInput(buildNativeEventInput(record, representativeProgramWake(2)), views);
	const tools = MINECRAFT_DYNAMIC_TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema }));
	return {
		instructionsBytes: bytes(NATIVE_AGENT_INSTRUCTIONS) + bytes(agentsMarkdown) + bytes(skillMarkdown),
		toolSchemaBytes: bytes(tools),
		capabilitiesAllBytes: bytes(minecraftCapabilities({ section: 'all' })),
		eventRawBytes: bytes(raw),
		eventEncodedFirstBytes: bytes(first),
		eventEncodedRepeatBytes: bytes(second),
		...measureMixedTerrain(record),
	};
}

function measureMixedTerrain(record) {
	const wake = representativeProgramWake(1);
	wake.observation.blocks = mixedTerrainBlocks();
	const raw = buildNativeEventInput(record, wake);
	return { mixedTerrainRawBytes: bytes(raw), mixedTerrainEncodedBytes: bytes(encodeNativeEventInput(raw)) };
}

/**
 * Per-hour model input under a simple context model: every call re-reads the fixed prefix plus the
 * conversation so far, which grows by each turn's event and tool results until it is compacted or rotated.
 */
export function estimateHourlyTokens({ calls, turns, prefixTokens, eventTokens, toolResultTokens, toolResults, maxContextTokens }) {
	const growthPerCall = (turns * eventTokens + toolResults * toolResultTokens) / calls;
	const conversationCap = Math.max(0, maxContextTokens - prefixTokens);
	// Conversation saw-tooths from 0 to the cap, so the average conversation is half the cap
	// (or half of everything ever added, when the session never reaches the cap).
	const averageConversation = Math.min(conversationCap, calls * growthPerCall) / 2;
	const averageContext = prefixTokens + averageConversation;
	return { averageContextTokens: Math.round(averageContext), inputTokens: Math.round(calls * averageContext), newTokens: Math.round(calls * growthPerCall) };
}

/**
 * Replays a recorded per-call context series under context rotation: after a finished turn whose last call reached
 * `thresholdTokens` (and at least `minTurns` turns on the current thread), the next call starts from the fixed prefix
 * plus a carry-over. Each call keeps its recorded growth (the new event and tool results). The first call on a fresh
 * thread is counted as wholly uncached; later calls keep their recorded uncached share.
 */
export function replayContextRotation({ calls, turnEnds, prefixTokens, carryOverTokens = 1_200, thresholdTokens = 64_000, minTurns = 3 }) {
	const ends = new Set(turnEnds);
	let previousRecorded = prefixTokens, context = 0, turnsOnThread = 0, freshThread = false, rotations = 0;
	let input = 0, uncached = 0;
	calls.forEach(({ context: recorded, cached }, index) => {
		const growth = index === 0 ? recorded : recorded - previousRecorded;
		previousRecorded = recorded;
		context = index === 0 ? recorded : context + growth;
		input += context;
		uncached += freshThread ? context : Math.max(0, recorded - cached);
		freshThread = false;
		if (!ends.has(index + 1)) return;
		turnsOnThread += 1;
		if (thresholdTokens > 0 && context >= thresholdTokens && turnsOnThread >= minTurns) {
			// The next call adds its own recorded growth on top of the fresh prefix and carry-over.
			context = prefixTokens + carryOverTokens;
			turnsOnThread = 0;
			freshThread = true;
			rotations += 1;
		}
	});
	return { calls: calls.length, inputTokens: input, averageContextTokens: Math.round(input / Math.max(1, calls.length)), uncachedInputTokens: uncached, rotations };
}
