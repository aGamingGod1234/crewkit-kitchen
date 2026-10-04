import test from 'node:test';
import assert from 'node:assert/strict';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { classifyObservationTrigger, nativeObservationSignature } from '../src/dynamic-main.mjs';

function observation() {
  return {
    ready: true, position: { x: 0, y: 64, z: 0 }, view: { yaw: 0, pitch: 0 },
    entities: [], blocks: [], inventory: { items: [] },
    player: { health: 20, air: 299, onFire: false },
    world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
  };
}

test('quiet safe-air updates still change the actionable observation signature', () => {
  const before = observation();
  const after = structuredClone(before);
  after.player.air = 298;
  const adapted = adaptObservation(after);
  assert.equal(adapted.player.air, 298);
  assert.notEqual(nativeObservationSignature(adaptObservation(before)), nativeObservationSignature(adapted));
  assert.deepEqual(classifyObservationTrigger({ attention: false, changedFacts: [] }, adapted), {
    attention: false, priority: 'ordinary', trigger: 'observation',
  });
});

test('clock-only updates remain eligible for signature reuse', () => {
  const before = observation();
  const after = structuredClone(before);
  after.world.gameTime += 1;
  assert.equal(nativeObservationSignature(adaptObservation(before)), nativeObservationSignature(adaptObservation(after)));
});

test('critical air and explicit attention retain their wake semantics', () => {
  const critical = observation();
  critical.player.air = 60;
  assert.deepEqual(classifyObservationTrigger({ attention: true, changedFacts: ['player.air'] }, adaptObservation(critical)), {
    attention: true, priority: 'urgent', trigger: 'suffocation',
  });
  assert.equal(classifyObservationTrigger({ attention: true, changedFacts: [], trigger: 'requested_replan' }, observation()).attention, true);
});
