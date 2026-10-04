import assert from 'node:assert/strict';
import test from 'node:test';

import {
	createNativeGoalHarness,
	woodenPickaxeFaultScenario,
} from './fixtures/native-goal-harness.mjs';

test('wooden pickaxe goal survives injected faults across native turns and verifies completion', async () => {
	const result = await createNativeGoalHarness(woodenPickaxeFaultScenario()).run();
	assert.equal(result.finalState, 'COMPLETED');
	assert.equal(result.inventory.get('minecraft:wooden_pickaxe'), 1);
	assert.equal(result.inventory.get('minecraft:oak_log') ?? 0, 0);
	assert.equal(result.inventory.get('minecraft:oak_planks'), 3);
	assert.equal(result.inventory.get('minecraft:stick'), 2);
	assert.equal(result.world.blocks.get('1,64,1'), 'minecraft:crafting_table');
	assert.ok(result.actionDispatches.some(({ payload }) => payload.actionType === 'craft_table'));
	assert.ok(result.providerTurns >= 5);
	assert.ok(result.recoveries.includes('PATH_BLOCKED'));
	assert.ok(result.recoveries.includes('PLANNING_TIMEOUT'));
	assert.ok(result.recoveries.includes('BRIDGE_DISCONNECTED'));
	assert.equal(result.maxRecoveryHandles, 1);
	assert.equal(result.states.includes('ERROR'), false);
	assert.equal(result.states.includes('PAUSED'), false);
});
