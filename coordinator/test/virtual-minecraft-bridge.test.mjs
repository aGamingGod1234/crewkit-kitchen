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
