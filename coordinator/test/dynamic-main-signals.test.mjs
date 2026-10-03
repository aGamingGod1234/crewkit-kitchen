import assert from 'node:assert/strict';
import test from 'node:test';

import { classifyObservationTrigger, detectMovementLoop } from '../src/dynamic-main.mjs';

const observation = { player: { fire: false }, blocks: [] };

test('actual injury outranks ordinary work, while healing and visible lava are not urgent danger', () => {
	assert.deepEqual(classifyObservationTrigger({ attention: false }, { player: { health: 18 }, blocks: [] }, { previousPlayer: { health: 20 } }), { attention: true, priority: 'urgent', trigger: 'damage' });
	assert.equal(classifyObservationTrigger({ attention: true, changedFacts: ['player.health'] }, { player: { health: 19 } }, { previousPlayer: { health: 18 } }).priority, 'ordinary');
	assert.equal(classifyObservationTrigger({ attention: false }, { player: { inLava: false }, blocks: [{ blockId: 'minecraft:lava' }] }).attention, false);
	assert.equal(classifyObservationTrigger({ attention: false }, { player: { inLava: true } }).trigger, 'lava');
	assert.equal(classifyObservationTrigger({ trigger: 'damage' }, observation).priority, 'urgent');
});

test('movement loop signal requires two repeated positions', () => {
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '0,64,0', '1,64,0']), true);
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '2,64,0', '3,64,0']), false);
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '0,64,0']), false);
});

test('movement loop is promoted to urgent native attention', () => {
	assert.deepEqual(classifyObservationTrigger(
		{ attention: false },
		observation,
		{ movementLoop: true },
	), { attention: true, priority: 'urgent', trigger: 'movement_loop' });
});

test('new observed resource is promoted without misclassifying ordinary heartbeats', () => {
	assert.deepEqual(classifyObservationTrigger(
		{ attention: false },
		observation,
		{ resourceDiscovery: true },
	), { attention: true, priority: 'ordinary', trigger: 'resource_discovery' });
	assert.deepEqual(classifyObservationTrigger({ attention: false }, observation), {
		attention: false,
		priority: 'ordinary',
		trigger: 'observation',
	});
});
