import test from 'node:test';
import assert from 'node:assert/strict';

import {
	SIMULATOR_SCENARIOS,
	getSimulatorScenario,
	listSimulatorScenarios,
	runScenarioSuccess,
} from '../src/simulator/simulator-scenarios.mjs';

test('scenario manifests are fixed, immutable, and cover the complete benchmark surface', () => {
	const expected = [
		'stone-tool-gathering', 'obstacle-navigation', 'inventory-crafting',
		'block-placement', 'hostile-mob-combat', 'lava-damage-reaction',
		'checkpoint-respawn', 'direct-message-wake', 'stalled-action',
		'invalid-decision-correction',
	];
	assert.deepEqual(listSimulatorScenarios(), expected);
	assert.ok(Object.isFrozen(SIMULATOR_SCENARIOS));
	assert.ok(Object.isFrozen(getSimulatorScenario('stone-tool-gathering')));
	assert.throws(() => { getSimulatorScenario('stone-tool-gathering').id = 'mutated'; }, TypeError);
});

test('scenario lookup rejects unknown manifests and success is authoritative', () => {
	assert.equal(getSimulatorScenario('missing'), undefined);
	const manifest = getSimulatorScenario('stalled-action');
	assert.equal(typeof manifest.success, 'function');
	assert.equal(runScenarioSuccess(manifest, { timeout: { state: 'TIMED_OUT' } }), true);
	assert.equal(runScenarioSuccess(manifest, { timeout: { state: 'SUCCEEDED' } }), false);
	const correction = getSimulatorScenario('invalid-decision-correction');
	assert.equal(runScenarioSuccess(correction, { correctionCount: 1 }), true);
});
