import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { buildNativeEventInput, classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { strategyHints } from '../src/minecraft-strategy-reference.mjs';
import { minecraftCapabilities, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { PlacedWorkstations, withToolWear } from '../src/resource-facts.mjs';
import { decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { presentNativeToolResult } from '../src/codex-service.mjs';

const SIGHTED = {
	structures: [{ structure: 'minecraft:shipwreck_beached', x: 40, y: 62, z: -12, distance: 41, bearing: -20, new: true }],
	caves: [{ x: 6, y: 58, z: 3, distance: 9, bearing: 35, air: 61 }],
	veins: [{ blockId: 'minecraft:iron_ore', x: 2, y: 60, z: 1, distance: 3, bearing: 10, visible: 5 }],
};
const LEFT_BEHIND = [{ blockId: 'minecraft:furnace', x: 30, y: 64, z: 0, distance: 30 }];

function observation(extra = {}) {
	return {
		ready: true, status: 'ACTING', position: { x: 0.5, y: 64, z: 0.5 }, view: { yaw: 0, pitch: 0 },
		player: { health: 20, maxHealth: 20, foodLevel: 20 }, world: { dimension: 'minecraft:overworld', worldId: 'w', gameTime: 100 },
		inventory: { items: [
			{ slot: 0, itemId: 'minecraft:stone_pickaxe', count: 1, damage: 120, maxDamage: 131 },
			{ slot: 1, itemId: 'minecraft:stone_pickaxe', count: 1, damage: 3, maxDamage: 131 },
			{ slot: 2, itemId: 'minecraft:cobblestone', count: 40, damage: 0, maxDamage: 0 },
		] },
		entities: [], blocks: [], ...extra,
	};
}

const payload = (input) => decodeModelFacts(JSON.parse(input.slice(input.indexOf('\n') + 1)));

test('tool rows show uses left so the most worn tool is obvious, and non-tools are untouched', () => {
	const inventory = withToolWear(observation().inventory);
	assert.deepEqual(inventory.items.map((item) => item.usesLeft), [11, 128, undefined]);
	assert.deepEqual(inventory.items[0], { slot: 0, itemId: 'minecraft:stone_pickaxe', count: 1, maxDamage: 131, usesLeft: 11 }, 'usesLeft replaces damage');
	assert.deepEqual(inventory.items[2], { slot: 2, itemId: 'minecraft:cobblestone', count: 40 }, 'blocks drop the always-zero damage pair');
	const empty = { items: [] };
	assert.equal(withToolWear(empty), empty, 'nothing to add keeps the same object');
});

test('placed workstations are reported once the agent walks away, until broken or seen gone', () => {
	const placed = new PlacedWorkstations();
	const here = (x, extra = {}) => observation({ position: { x, y: 64, z: 0.5 }, ...extra });
	placed.onActionResult('a', { actionType: 'place_block', arguments: { x: 0, y: 64, z: 0, itemId: 'minecraft:crafting_table' }, state: 'SUCCEEDED' }, here(0));
	placed.onActionResult('a', { actionType: 'place_block', arguments: { x: 1, y: 64, z: 0, itemId: 'minecraft:cobblestone' }, state: 'SUCCEEDED' }, here(0));
	placed.onActionResult('a', { actionType: 'place_block', arguments: { x: 2, y: 64, z: 0, itemId: 'minecraft:furnace' }, state: 'FAILED' }, here(0));
	assert.deepEqual(placed.leftBehind('a', here(3)), [], 'still next to it');
	assert.deepEqual(placed.leftBehind('a', here(20)), [{ blockId: 'minecraft:crafting_table', x: 0, y: 64, z: 0, distance: 20 }]);
	assert.deepEqual(placed.leftBehind('a', here(200)), [], 'far away it is history, not a reminder');
	assert.deepEqual(placed.leftBehind('b', here(20)), [], 'per agent');
	assert.deepEqual(placed.leftBehind('a', here(20, { world: { dimension: 'minecraft:the_nether' } })), [], 'other dimension');
	placed.onActionResult('a', { actionType: 'break_block', arguments: { x: 0, y: 64, z: 0 }, state: 'SUCCEEDED' }, here(1));
	assert.deepEqual(placed.leftBehind('a', here(20)), [], 'collected');

	placed.onActionResult('a', { actionType: 'place_block', arguments: { x: 0, y: 64, z: 0, itemId: 'minecraft:furnace' }, state: 'SUCCEEDED' }, here(0));
	assert.equal(placed.leftBehind('a', here(20, { blocks: [{ x: 0, y: 64, z: 0, blockId: 'minecraft:furnace' }] })).length, 1, 'seen still there');
	assert.deepEqual(placed.leftBehind('a', here(20, { landmarks: [{ x: 0, y: 64, z: 0, blockId: 'minecraft:air' }] })), [], 'seen gone');
});

test('the runtime records workstations from confirmed place_block results and decorates observations', async () => {
	const fields = { originalRequest: 'beat the game', predicate: { type: 'inventory_contains', itemId: 'minecraft:stone', count: 1 }, createdAtTick: 10 };
	const record = { agentId: 'agent-w', provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'low', serviceTier: 'priority', goalRevision: 3,
		currentGoal: 'beat the game', currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) } };
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: { get: () => record }, bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({ agentId: record.agentId, goalRevision: 3, turnId: 't', callId: 'c',
		tool: { kind: 'action', actionType: 'place_block', arguments: { x: 0, y: 64, z: 0, face: 'up', itemId: 'minecraft:crafting_table' } } }, record);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(runtime.onActionResult(record, { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'BLOCK_PLACED' }), true);
	await pending;
	const view = runtime.decorateObservation(record, observation({ position: { x: 16.5, y: 64, z: 0.5 } }));
	assert.deepEqual(view.leftBehind, [{ blockId: 'minecraft:crafting_table', x: 0, y: 64, z: 0, distance: 16 }]);
	assert.equal(view.inventory.items[0].damage, 120, 'programs keep raw damage facts');
	assert.equal(view.inventory.items[0].usesLeft, undefined);
});

test('tool results show usesLeft to the model, also after an action', () => {
	const presented = presentNativeToolResult({ observation: observation(), postAction: { observation: observation() } }, { kind: 'observe' });
	const text = presented.response.contentItems[0].text;
	const value = decodeModelFacts(JSON.parse(text));
	assert.equal(value.observation.inventory.items[0].usesLeft, 11);
	assert.equal(value.observation.inventory.items[0].damage, undefined);
	assert.equal(value.postAction.observation.inventory.items[1].usesLeft, 128);
});

test('sight and workstation facts reach the model in every event projection, including compaction', () => {
	const big = observation({
		sighted: SIGHTED, leftBehind: LEFT_BEHIND,
		blocks: Array.from({ length: 128 }, (_, index) => ({ x: index, y: 60, z: 0, blockId: `minecraft:block_${index}`, tags: [`#minecraft:tag_${index}`, 'x'.repeat(400)] })),
	});
	for (const event of ['observation', 'program_planning_due']) {
		const input = payload(buildNativeEventInput({ goalRevision: 1, currentGoal: 'beat the game' }, { event, observation: big }));
		assert.deepEqual(input.observation.sighted, SIGHTED, `${event}: sighted survives`);
		assert.deepEqual(input.observation.leftBehind, LEFT_BEHIND, `${event}: leftBehind survives`);
		assert.equal(input.observation.inventory.items[0].usesLeft, 11, `${event}: usesLeft survives row compaction`);
		assert.ok(input.observation.blocks.length <= 12, `${event}: the event was compacted`);
	}
	const small = payload(buildNativeEventInput({ goalRevision: 1, currentGoal: 'beat the game' }, { observation: observation() }));
	assert.equal(small.observation.sighted, undefined, 'nothing seen adds nothing');
	assert.equal(small.observation.leftBehind, undefined);
});

test('oversized tool results keep sight and workstation facts', () => {
	const big = observation({
		sighted: SIGHTED, leftBehind: LEFT_BEHIND,
		entities: Array.from({ length: 64 }, (_, index) => ({ uuid: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, type: 'minecraft:cow', name: 'x'.repeat(200), distance: index, position: { x: index, y: 64, z: 0 } })),
		blocks: Array.from({ length: 128 }, (_, index) => ({ x: index, y: 60, z: 0, blockId: 'minecraft:stone', tags: Array.from({ length: 32 }, (_, tag) => `#minecraft:tag_${tag}_${'x'.repeat(40)}`) })),
	});
	const result = JSON.parse(toolResultContent({ observation: big }).contentItems[0].text);
	assert.equal(result.truncated, true, 'the fixture exceeds the tool result limit');
	assert.deepEqual(result.observation.sighted, SIGHTED);
	assert.deepEqual(result.observation.leftBehind, LEFT_BEHIND);
});

test('a newly seen structure wakes the model with a named ordinary trigger', () => {
	assert.deepEqual(classifyObservationTrigger({ attention: true, changedFacts: ['sighted'] }, { player: { health: 20 } }),
		{ attention: true, priority: 'ordinary', trigger: 'structure_sighted' });
});

test('taskPlan says what the strategy read is for, and the guide covers resourcefulness', () => {
	const hints = strategyHints({ goal: 'beat the game', plan: { steps: [{ label: 'Mine iron for a pickaxe', status: 'pending' }] } });
	assert.equal(hints.read.arguments.topic, 'resources');
	assert.match(hints.advice, /Read capabilities strategy:resources before mining/);
	assert.match(hints.advice, /caves, structures and whole ore veins/);
	const resources = minecraftCapabilities({ section: 'strategy', topic: 'resources' }).reference;
	for (const pattern of [/sighted\.caves/, /sighted\.structures/, /Mine the whole vein/, /sighted\.veins/, /leftBehind/, /furnace needs a pickaxe/, /usesLeft/, /most worn tool/]) {
		assert.match(resources, pattern);
	}
	assert.ok(Buffer.byteLength(JSON.stringify(minecraftCapabilities({ section: 'strategy', topic: 'resources' }))) <= 4_096, 'still one small read');
});


test('ingest path: sight, threat trend, healing, sound and wear facts survive the bridge, adapter and event projection', async () => {
	const { FakePlanner, eventually, start } = await import('./fixtures/dynamic-main-fixture.mjs');
	const { AgentRegistry } = await import('../src/agent-registry.mjs');
	const { validateProtocolV2Payload } = await import('../src/protocol-v2.mjs');
	const zombie = '00000000-0000-4000-8000-0000000000aa';
	// The wire observation exactly as the mod sends it, normalized by the same protocol check the live bridge runs.
	const wire = validateProtocolV2Payload('observation', {
		goalRevision: 1, observedAtEpochMs: 1, ready: true, status: 'ready', eventSequence: 1, attention: true, changedFacts: ['sighted'],
		position: { x: 0.5, y: 64, z: 0.5 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
		player: { health: 7, maxHealth: 20, armor: 0, foodLevel: 15, saturation: 0, gameMode: 'survival', onGround: true, inWater: false, onFire: false,
			air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [] },
		inventory: { items: [{ slot: 0, itemId: 'minecraft:stone_pickaxe', count: 1, damage: 120, maxDamage: 131 }], selectedItem: 'minecraft:stone_pickaxe' },
		entities: [], blocks: [], nearbyContainers: [], world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
		currentAction: { active: false }, lastResult: { present: false },
		threats: { entries: [{ uuid: zombie, type: 'minecraft:zombie', distance: 9, bearing: 40, targeting: true, swelling: false, lineOfSight: true, signals: ['targeting'],
			risk: 6, expectedHitDamage: 3, closingSpeed: 2, approaching: true, etaSeconds: 3.5, contactRisk: 40 }] },
		survival: { safe: false, canHealNow: false, signals: [], bestFood: { slot: 4, itemId: 'minecraft:bread', nutrition: 5 } },
		heard: [{ sound: 'zombie groan', source: 'minecraft:zombie', direction: 'right', elevation: 'level', distance: 9 }],
		sighted: SIGHTED,
	});
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let toolResult;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) toolResult = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 't-1', callId: 'c-1', tool: { kind: 'observe' } });
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({ registry, planner, config: { bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Mine iron.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: wire });
		await eventually(() => toolResult !== undefined);
		const event = payload(planner.requests[0].input);
		assert.deepEqual(event.observation.sighted, SIGHTED, 'events carry what the agent sees');
		const threat = event.observation.player.threats[0];
		assert.deepEqual([threat.closingSpeed, threat.approaching, threat.etaSeconds, threat.contactRisk], [2, true, 3.5, 40], 'threat trend survives');
		assert.equal(event.observation.player.bestFood.itemId, 'minecraft:bread', 'healing facts survive');
		assert.equal(event.observation.player.safe, false);
		assert.equal(event.observation.player.heard[0].sound, 'zombie groan', 'heard survives');
		assert.equal(event.observation.inventory.items[0].usesLeft, 11, 'wear reaches the model as usesLeft');
		assert.deepEqual(toolResult.observation.sighted, SIGHTED, 'observe tool results carry what the agent sees');
	} finally { await run.coordinator.stop(); }
});
