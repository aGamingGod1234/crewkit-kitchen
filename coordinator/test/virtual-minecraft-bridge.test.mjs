import test from 'node:test';
import assert from 'node:assert/strict';

import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';

function scenario() {
	return {
		seed: 9,
		agents: { alice: { position: { x: 0, y: 1, z: 0 } } },
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }],
	};
}

function command(actionId, actionType = 'move_to', args = { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false }) {
	return {
		goalRevision: 1,
		actionId,
		actionType,
		arguments: args,
		provenance: {
			provider: 'test',
			model: 'test-model',
			reasoningEffort: 'low',
			serviceTier: 'fast',
			programId: 'program-1-1',
			programVersion: 1,
			sourceStepId: 'step-1',
			eventSequence: 1,
		},
	};
}

function managerEvents() {
	const events = [];
	return {
		events,
		onObservation(_record, payload) { events.push({ type: 'observation', payload }); },
		onActionProgress(_record, payload) { events.push({ type: 'progress', payload }); },
		onActionResult(_record, payload) { events.push({ type: 'result', payload }); },
	};
}

test('accepted command produces RUNNING, changed observation, then one terminal result', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('walk-1'));
	world.stepTicks(20);
	await bridge.flush();
	assert.deepEqual(bridge.events.map((event) => event.type), ['accepted', 'progress', 'observation', 'result']);
	assert.equal(manager.events.map((event) => event.type).join(','), 'progress,observation,result');
	assert.equal(manager.events.at(-1).payload.state, 'SUCCEEDED');
	assert.ok(manager.events[1].payload.observation.player.x > 0);
	assert.equal(bridge.validatedOutbound, 1);
	assert.equal(bridge.validatedInbound, 3);
});

test('cancellation emits one terminal cancellation and suppresses stale completion', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('walk-2', 'move_to', { x: 15, y: 1, z: 0, tolerance: 0.1, sprint: false }));
	world.stepTicks(1);
	await bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'walk-2' });
	world.stepTicks(100);
	await bridge.flush();
	const results = manager.events.filter((event) => event.type === 'result');
	assert.equal(results.length, 1);
	assert.equal(results[0].payload.state, 'CANCELLED');
	assert.equal(bridge.events.filter((event) => event.type === 'result').length, 1);
});

test('a synchronous progress listener cancelling an action fences the stale executor', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	manager.onActionProgress = (_record, payload) => {
		manager.events.push({ type: 'progress', payload });
		void bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'look-1' });
	};
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('look-1', 'look_at', { x: 2, y: 1, z: 0 }));
	world.stepTicks(1);
	await bridge.flush();
	const results = manager.events.filter((event) => event.type === 'result');
	assert.equal(results.length, 1);
	assert.equal(results[0].payload.state, 'CANCELLED');
	assert.equal(results[0].payload.reasonCode, 'CANCELLED');
	assert.equal(bridge.events.filter((event) => event.type === 'result').length, 1);
});

test('cancellation requires the exact goal revision of the active action', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 2 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', { ...command('walk-3'), goalRevision: 2 });
	await bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'walk-3' });
	world.stepTicks(1);
	assert.deepEqual(bridge.activeActionIds, ['alice']);
	assert.equal(manager.events.filter((event) => event.type === 'result').length, 0);
	await bridge.send('action_cancel', 'alice', { goalRevision: 2, actionId: 'walk-3' });
	await bridge.flush();
	assert.equal(manager.events.filter((event) => event.type === 'result').length, 1);
	assert.equal(manager.events.at(-1).payload.state, 'CANCELLED');
});

test('publish validates and adapts normalized observation-bound scenario data', async () => {
	const world = VirtualWorld.fromScenario({
		agents: {
			alice: {
				position: { x: 0, y: 1, z: 0 },
				effects: [{ effectId: 'minecraft:speed', amplifier: 1, durationTicks: 10 }],
			},
		},
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone', tags: ['#minecraft:mineable/pickaxe'] }],
		entities: [{ id: 'mob-1', type: 'minecraft:zombie', position: { x: 2, y: 1, z: 0 }, tags: ['#minecraft:hostile'] }],
	});
	world.damage('alice', 1, { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2 });
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await assert.doesNotReject(bridge.publish('alice'));
	await bridge.flush();
	assert.equal(bridge.validatedInbound, 1);
});

test('bridge returns a terminal typed failure for a protocol-valid action outside Task 3 scope', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('craft-1', 'craft_inventory', { recipeId: 'minecraft:stick', count: 1, timeoutMs: 1_000 }));
	world.stepTicks(1);
	await bridge.flush();
	const result = manager.events.find((event) => event.type === 'result');
	assert.equal(result.payload.state, 'FAILED');
	assert.equal(result.payload.reasonCode, 'SIMULATOR_UNSUPPORTED_ACTION');
});
