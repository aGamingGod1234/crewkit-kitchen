import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';
import { getSimulatorScenario } from '../src/simulator/simulator-scenarios.mjs';
import { runAuthoritativeScenarioSuccess } from '../src/benchmark/scenario-program.mjs';
import { createNativeGoalHarness } from './fixtures/native-goal-harness.mjs';

function fixture(t, { inventory = {}, blocks = [], randomEvents = [] } = {}) {
	const world = new VirtualWorld({ agents: { alice: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory } }, blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, ...blocks], randomEvents });
	const bridge = new VirtualMinecraftBridge({ world });
	t.after(async () => { bridge.stop(); await bridge.flush(); assert.equal(world.listenerCount('tick'), 0); assert.equal(bridge.activeActionIds.length, 0); });
	return { world, bridge };
}
let sequence = 0;
async function send(bridge, agentId, actionType, args) {
	const actionId = `g10-${++sequence}`;
	await bridge.send('action_command', agentId, { traceId: actionId, goalRevision: 1, actionId, actionType, arguments: args, provenance: { provider: 'fixture', model: 'fixture', reasoningEffort: 'none', serviceTier: 'local', programId: 'g10', programVersion: 1, sourceStepId: actionId, eventSequence: 1, traceId: actionId } });
	return actionId;
}
async function act({ world, bridge }, type, args, agentId = 'alice') {
	const id = await send(bridge, agentId, type, args);
	for (let tick = 0; tick < 100 && bridge.activeActionIds.length; tick++) { world.tick(); await bridge.flush(); }
	return bridge.events.find(e => e.type === 'result' && e.envelope.payload.actionId === id)?.envelope.payload;
}

test('active hazard observation arrives before completion and can cancel; routine wait stays quiet', async t => {
	for (const damage of [0, 1]) {
		const f = fixture(t, { randomEvents: damage ? [{ tick: 1, type: 'damage', amount: damage, agentIds: ['alice'] }] : [] });
		const observations = [];
		let actionId;
		f.bridge.attach({ async onObservation(_r, p) { observations.push(p); if (p.attention) await f.bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId }); }, onActionProgress() {}, onActionResult() {} });
		await f.bridge.publish('alice'); await f.bridge.flush();
		actionId = await send(f.bridge, 'alice', 'wait', { durationMs: 1000 });
		f.world.tick(); await f.bridge.flush(); await f.bridge.flush();
		if (damage) {
			assert.equal(observations[1]?.observation.player.health, 19);
			assert.equal(observations[1]?.attention, true);
			assert.equal(f.bridge.events.find(e => e.type === 'result')?.envelope.payload.state, 'CANCELLED');
		} else { assert.equal(observations.length, 1); assert.equal(f.bridge.activeActionIds.length, 1); }
	}
});

test('mining rejects mismatched and distant blocks without mutation; matching nearby target succeeds', async t => {
	for (const [x, expected, reason] of [[1, 'minecraft:dirt', 'TARGET_CHANGED'], [100, 'minecraft:stone', 'TARGET_TOO_FAR'], [1, 'minecraft:stone', 'BROKEN']]) {
		const f = fixture(t, { blocks: [{ x, y: 1, z: 0, blockId: 'minecraft:stone' }] });
		const before = f.world.inventories('alice');
		const result = await act(f, 'break_block', { x, y: 1, z: 0, expectedBlockId: expected, timeoutMs: 1000 });
		assert.equal(result.reasonCode, reason);
		if (reason !== 'BROKEN') { assert.deepEqual(f.world.inventories('alice'), before); assert.ok(f.world.blockAt(x, 1, 0)); }
		else assert.equal(f.world.countInventoryItem('alice', 'minecraft:cobblestone'), 1);
	}
});

test('crafting uses one full batch and rejects too many outputs and table-only inventory recipes', async t => {
	for (const count of [1, 4, 8]) {
		const f = fixture(t, { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 2 }] } });
		const before = f.world.inventories('alice');
		const result = await act(f, 'craft_inventory', { recipeId: 'minecraft:planks', count, timeoutMs: 1000 });
		if (count === 8) { assert.equal(result.reasonCode, 'CRAFT_COUNT_UNSUPPORTED'); assert.deepEqual(f.world.inventories('alice'), before); }
		else { assert.equal(result.state, 'SUCCEEDED'); assert.equal(f.world.countInventoryItem('alice', 'minecraft:oak_planks'), 4); assert.equal(f.world.countInventoryItem('alice', 'minecraft:oak_log'), 1); }
	}
	const f = fixture(t, { inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 3 }, { itemId: 'minecraft:stick', count: 2 }] } });
	const before = f.world.inventories('alice');
	assert.equal((await act(f, 'craft_inventory', { recipeId: 'minecraft:stone_pickaxe', count: 1, timeoutMs: 1000 })).state, 'FAILED');
	assert.deepEqual(f.world.inventories('alice'), before);
});

test('tag aggregates follow crafting, pickup and drop and retain membership after last stack removal', async t => {
	const tag = '#minecraft:logs';
	const f = fixture(t, { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 1, slot: 0, tags: [tag] }], tagCounts: { [tag]: 1 } } });
	await act(f, 'craft_inventory', { recipeId: 'minecraft:planks', count: 1, timeoutMs: 1000 });
	assert.equal(f.world.observation('alice').inventory.tagCounts[tag], 0);
	f.world.addItem({ id: 'logs', itemId: 'minecraft:oak_log', count: 3, position: { x: 0, y: 1, z: 0 } });
	await act(f, 'wait', { durationMs: 50 });
	assert.equal(f.world.observation('alice').inventory.tagCounts[tag], 3);
	const slot = f.world.inventories('alice').items.find(i => i.itemId === 'minecraft:oak_log').slot;
	await act(f, 'drop_item', { slot, count: 1 });
	assert.equal(f.world.observation('alice').inventory.tagCounts[tag], 2);
});

test('new item pickup preserves explicit tags and failed crafting restores tagged inventory exactly', async t => {
	const f = fixture(t);
	f.world.addItem({ id: 'new-logs', itemId: 'minecraft:oak_log', count: 2, tags: ['#minecraft:logs'], position: { x: 0, y: 1, z: 0 } });
	await act(f, 'wait', { durationMs: 50 });
	const before = f.world.inventories('alice');
	assert.equal(before.tagCounts['#minecraft:logs'], 2);
	const add = f.world.addInventoryItem.bind(f.world);
	f.world.addInventoryItem = () => { throw Object.assign(new Error('owned output-capacity fixture'), { code: 'WORLD_CAPACITY_EXCEEDED' }); };
	assert.equal((await act(f, 'craft_inventory', { recipeId: 'minecraft:oak_planks', count: 1, timeoutMs: 1000 })).reasonCode, 'WORLD_CAPACITY_EXCEEDED');
	assert.deepEqual(f.world.inventories('alice'), before);
	f.world.addInventoryItem = add;
	assert.equal((await act(f, 'craft_inventory', { recipeId: 'minecraft:oak_planks', count: 1, timeoutMs: 1000 })).state, 'SUCCEEDED');
	assert.equal(f.world.observation('alice').inventory.tagCounts['#minecraft:logs'], 1);
});

test('sender-only direct message cannot certify recipient wake or processing, including missing recipient', async t => {
	const manifest = getSimulatorScenario('direct-message-wake');
	for (const missing of [false, true]) {
		const setup = structuredClone(manifest.world);
		if (missing) delete setup.agents[manifest.expected.recipientId];
		const world = new VirtualWorld(setup), bridge = new VirtualMinecraftBridge({ world });
		t.after(async () => { bridge.stop(); await bridge.flush(); assert.equal(world.listenerCount('tick'), 0); });
		await act({ world, bridge }, 'chat', manifest.commands[0].arguments, manifest.agentId);
		assert.equal(runAuthoritativeScenarioSuccess({ manifest, world, bridge }), false);
		assert.ok(world.conversationEvents().every(e => e.wakeAcknowledged !== true && e.processed !== true));
	}
});

test('native fixture rejects impossible inventory crafting with and without ingredients', async () => {
	for (const initialInventory of [{}, { 'minecraft:oak_planks': 3, 'minecraft:stick': 2 }]) {
		const result = await createNativeGoalHarness({ initialInventory, turns: [['craft_inventory'], ['finish']] }).run();
		assert.notEqual(result.finalState, 'COMPLETED');
		assert.deepEqual(Object.fromEntries(result.inventory), initialInventory);
		assert.equal(result.actionEffects.length, 0);
		assert.equal(result.activeWork, 0); assert.equal(result.recoveryHandles, 0);
	}
});

test('native fixture rejects missing, mismatched and ingredient-free table work without effects', async () => {
	const action = (actionType, args) => ({ kind: 'action', actionType, arguments: args });
	const cases = [
		[action('break_block', { x: 0, y: 64, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1000 }), {}, 'TARGET_CHANGED'],
		[action('break_block', { x: 9, y: 64, z: 0, expectedBlockId: 'minecraft:oak_log', timeoutMs: 1000 }), {}, 'BLOCK_NOT_FOUND'],
		[action('craft_table', { x: 1, y: 64, z: 1, recipeId: 'minecraft:wooden_pickaxe', count: 1, timeoutMs: 1000 }), {}, 'CRAFTING_TABLE_NOT_FOUND'],
		[action('craft_table', { x: 1, y: 64, z: 1, recipeId: 'minecraft:wooden_pickaxe', count: 1, timeoutMs: 1000 }), { initialBlocks: { '1,64,1': 'minecraft:crafting_table' } }, 'INGREDIENTS_MISSING'],
	];
	for (const [command, setup, reason] of cases) {
		const result = await createNativeGoalHarness({ ...setup, turns: [[command], ['finish']] }).run();
		assert.equal(result.finalState, 'PLANNING');
		assert.ok(result.recoveries.includes(reason));
		assert.equal(result.actionEffects.length, 0);
		assert.equal(result.inventory.size, 0);
	}
	const control = await createNativeGoalHarness({ initialInventory: { 'minecraft:wooden_pickaxe': 1 }, turns: [['finish']] }).run();
	assert.equal(control.finalState, 'COMPLETED');
	assert.equal(control.actionCount, 0);
});

test('Task9 names the actual timeout and decision-correction evidence without claiming other faults', async () => {
	const matrix = JSON.parse(await readFile(new URL('../config/task9-performance-matrix.json', import.meta.url), 'utf8'));
	assert.equal(matrix.trials.find(t => t.id === 'action-timeout-load-1')?.scenarioId, 'stalled-action');
	assert.equal(matrix.trials.find(t => t.id === 'decision-correction-load-1')?.scenarioId, 'invalid-decision-correction');
	assert.equal(matrix.trials.some(t => /watcher-reaction|disconnect-recovery/.test(t.id)), false);
	const timeout = getSimulatorScenario('stalled-action');
	assert.equal(timeout.success({ results: [] }), false);
	assert.equal(timeout.success({ results: [{ actionId: 'stall-navigation', state: 'TIMED_OUT', reasonCode: 'ACTION_TIMEOUT' }] }), true);
	const correction = getSimulatorScenario('invalid-decision-correction');
	assert.equal(correction.success({ events: [] }), false);
	assert.equal(correction.success({ events: [{ invalidDecisionId: 'invalid-decision-1', correctedDecisionId: 'corrected-decision-1', accepted: true }] }), true);
});
