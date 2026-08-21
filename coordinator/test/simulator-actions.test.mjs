import test from 'node:test';
import assert from 'node:assert/strict';

import { ACTION_FIELDS } from '../src/constants.mjs';
import { ActionRuntime } from '../src/simulator/action-runtime.mjs';
import { VirtualWorld } from '../src/simulator/virtual-world.mjs';

const PLAYER = 'alice';
const MOB = '00000000-0000-4000-8000-000000000001';

function command(actionId, actionType, argumentsValue, overrides = {}) {
	return {
		agentId: PLAYER,
		goalRevision: 1,
		actionId,
		actionType,
		arguments: argumentsValue,
		provenance: {
			provider: 'simulator',
			model: 'fixture',
			reasoningEffort: 'low',
			serviceTier: 'fast',
			programId: 'simulator',
			programVersion: 1,
			sourceStepId: actionId,
			eventSequence: 1,
		},
		...overrides,
	};
}

function world(overrides = {}) {
	return VirtualWorld.fromScenario({
		seed: 7,
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true } },
		blocks: [0, 1, 2].map((x) => ({ x, y: 0, z: 0, blockId: 'minecraft:stone' })),
		items: [],
		entities: [],
		...overrides,
	});
}

function run(runtime, simulationWorld, actionId, limit = 200) {
	let result;
	for (let index = 0; index < limit && !result; index += 1) {
		runtime.tick(simulationWorld);
		result = runtime.resultFor(actionId);
	}
	return result;
}

test('action runtime validates exact production action fields and rejects unknown fields', () => {
	assert.deepEqual(ACTION_FIELDS.move_to, ['x', 'y', 'z', 'tolerance', 'sprint']);
	const runtime = new ActionRuntime();
	assert.throws(() => runtime.accept(command('bad', 'move_to', { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false, extra: true })), /UNKNOWN_FIELD|INVALID_ACTION/);
});

test('movement is a multi-tick action with displacement and a bounded yaw rate', () => {
	const simulationWorld = world();
	const runtime = new ActionRuntime();
	runtime.accept(command('walk', 'move_to', { x: 2, y: 1, z: 0, tolerance: 0.1, sprint: true }));
	const start = simulationWorld.playerState(PLAYER);
	const first = runtime.tick(simulationWorld);
	const afterFirst = simulationWorld.playerState(PLAYER);
	assert.deepEqual(first, []);
	assert.equal(runtime.snapshot().active[0].actionType, 'move_to');
	assert.ok(afterFirst.position.x >= start.position.x, 'movement must displace over ticks');
	assert.ok(Math.abs(afterFirst.yaw - start.yaw) <= 12, 'yaw must be rate limited');
	const result = run(runtime, simulationWorld, 'walk');
	assert.equal(result.state, 'SUCCEEDED');
	assert.ok(simulationWorld.playerState(PLAYER).position.x >= 1.8);
});

test('mining takes simulated time, then removes the block and creates the declared drop', () => {
	const simulationWorld = world({ blocks: [{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' }] });
	const runtime = new ActionRuntime();
	runtime.accept(command('mine', 'break_block', { x: 1, y: 1, z: 0, timeoutMs: 1_000 }));
	runtime.tick(simulationWorld);
	assert.ok(simulationWorld.blockAt(1, 1, 0), 'target must remain until mining completes');
	assert.equal(runtime.resultFor('mine'), undefined);
	const result = run(runtime, simulationWorld, 'mine');
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(simulationWorld.blockAt(1, 1, 0), null);
	assert.equal(simulationWorld.observation(PLAYER).inventory.items.find((item) => item.itemId === 'minecraft:cobblestone')?.count, 1);
});

test('crafting consumes exact ingredients and emits exact requested output count', () => {
	const simulationWorld = world({ agents: { [PLAYER]: {
		position: { x: 0, y: 1, z: 0 },
		onGround: true,
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0 }] },
	} } });
	const runtime = new ActionRuntime();
	runtime.accept(command('craft', 'craft_inventory', { recipeId: 'minecraft:planks', count: 8, timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'craft');
	assert.equal(result.state, 'SUCCEEDED');
	const items = simulationWorld.observation(PLAYER).inventory.items;
	assert.equal(items.find((item) => item.itemId === 'minecraft:oak_log')?.count ?? 0, 0);
	assert.equal(items.find((item) => item.itemId === 'minecraft:oak_planks')?.count, 8);
});

test('placement consumes the item and verifies the final block postcondition', () => {
	const simulationWorld = world({ agents: { [PLAYER]: {
		position: { x: 0, y: 1, z: 0 },
		onGround: true,
		inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] },
	} } });
	const runtime = new ActionRuntime();
	runtime.accept(command('place', 'place_block', { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone' }));
	const result = run(runtime, simulationWorld, 'place');
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(simulationWorld.blockAt(1, 1, 0).blockId, 'minecraft:cobblestone');
	assert.equal(simulationWorld.observation(PLAYER).inventory.items.find((item) => item.itemId === 'minecraft:cobblestone')?.count ?? 0, 0);
});

test('combat uses cooldown-limited hits and shield mitigation', () => {
	const simulationWorld = world({
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true } },
		entities: [{ id: MOB, type: 'minecraft:zombie', position: { x: 1, y: 1, z: 0 }, health: 10, maxHealth: 10 }],
	});
	const runtime = new ActionRuntime();
	runtime.accept(command('shield', 'block_with_shield', { durationMs: 200 }));
	runtime.tick(simulationWorld);
	const before = simulationWorld.playerState(PLAYER).health;
	simulationWorld.damage(PLAYER, 4, { uuid: MOB, type: 'minecraft:zombie', distance: 1 });
	assert.ok(simulationWorld.playerState(PLAYER).health >= before - 2, 'shield must mitigate damage');
	runtime.cancel('shield');
	runtime.accept(command('attack', 'attack', { targetId: MOB, timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'attack', 100);
	assert.equal(result.state, 'SUCCEEDED');
	assert.ok(simulationWorld.entityState(MOB).health < 10);
});

test('death and respawn use the recorded checkpoint, and timeout is measured in virtual ticks', () => {
	const simulationWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, health: 1 } } });
	simulationWorld.recordCheckpoint(PLAYER, { x: 8, y: 1, z: 8 });
	simulationWorld.damage(PLAYER, 5);
	const runtime = new ActionRuntime();
	runtime.accept(command('respawn', 'respawn', {}));
	const respawnResult = run(runtime, simulationWorld, 'respawn');
	assert.equal(respawnResult.state, 'SUCCEEDED');
	assert.deepEqual(simulationWorld.playerState(PLAYER).position, { x: 8, y: 1, z: 8 });

	const stalled = new ActionRuntime();
	stalled.accept(command('timeout', 'navigate_to', { x: 50, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1 }));
	const timedOut = run(stalled, simulationWorld, 'timeout', 10);
	assert.equal(timedOut.state, 'TIMED_OUT');
	assert.equal(timedOut.reasonCode, 'ACTION_TIMEOUT');
});

test('cancellation fences stale completion and unsupported actions fail explicitly', () => {
	const simulationWorld = world();
	const runtime = new ActionRuntime();
	runtime.accept(command('cancel-me', 'navigate_to', { x: 30, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1_000 }));
	runtime.tick(simulationWorld);
	assert.equal(runtime.cancel('cancel-me').state, 'CANCELLED');
	runtime.tick(simulationWorld);
	assert.equal(runtime.resultFor('cancel-me').state, 'CANCELLED');
	runtime.accept(command('unknown', 'set_door', { x: 1, y: 1, z: 0, open: true }));
	runtime.tick(simulationWorld);
	assert.equal(runtime.resultFor('unknown').reasonCode, 'SIMULATOR_UNSUPPORTED_ACTION');
});
