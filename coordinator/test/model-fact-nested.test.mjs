import assert from 'node:assert/strict';
import test from 'node:test';
import { ModelObservationViews, decodeModelFacts, encodeNativeEventInput } from '../src/model-fact-encoding.mjs';
import { presentNativeToolResult } from '../src/codex-service.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, normalizeMinecraftToolCall, toolResultContent } from '../src/native-minecraft-tools.mjs';

const sample = () => ({ eventSequence: 3, freshness: { fresh: true, afterEventSequence: 2 }, observation: {
 world: { worldId: 'fixture', dimension: 'minecraft:overworld' }, player: { dead: false, health: 20 },
 blocks: Array.from({ length: 30 }, (_, x) => ({ x, y: 64, blockId: 'minecraft:stone', state: {} })),
 coverage: { blocks: { complete: false, omitted: 10 } }, inventory: { items: [] }, entities: null,
} });
const decode = result => decodeModelFacts(JSON.parse(result.response.contentItems[0].text));
const restore = (baseline, result) => {
 const { observationView: view, ...metadata } = result;
 if (view.mode === 'full') return metadata;
 const sections = new Map(Object.entries(baseline));
 for (const key of view.remove) sections.delete(key);
 for (const [key, value] of Object.entries(view.replace)) sections.set(key, value);
 return { ...metadata, observation: Object.fromEntries(sections) };
};

for (const kind of ['action', 'sequence']) test(`${kind} final sample reconstructs exactly while receipts and source remain intact`, () => {
 const views = new ModelObservationViews();
 const first = presentNativeToolResult(sample(), { kind: 'observe' }, views); first.commit();
 const base = decode(first);
 const current = sample(); current.eventSequence++;
 current.observation.player.health = 18;
 current.observation.blocks = [];
 delete current.observation.entities;
 current.observation.inventory = null;
 const receipt = { state: 'FAILED', reasonCode: 'BLOCKED', actionId: 'action-7', completed: 1, failedAt: 0,
  results: [{ actionId: 'action-7', actionObservation: { worldTick: 1, player: { health: 20 } } }],
  finish: { state: 'SKIPPED', reasonCode: 'ACTION_FAILED' }, postAction: current };
 const before = structuredClone(receipt);
 const next = presentNativeToolResult(receipt, { kind, view: 'changes', afterObservationId: base.observationView.id }, views);
 const result = decode(next);
 assert.equal(result.postAction.observationView.mode, 'changes');
 assert.deepEqual({ ...result, postAction: restore(base.observation, result.postAction) }, before);
 assert.deepEqual(receipt, before);
 assert.equal(result.postAction.freshness.fresh, true);
 next.commit();
 const cross = decode(presentNativeToolResult(current, { kind: 'observe', view: 'changes', afterObservationId: result.postAction.observationView.id }, views));
 assert.equal(cross.observationView.mode, 'changes');
 assert.deepEqual(cross.observationView.replace, {});
});

test('nested views keep default/full and invalid baselines self-contained', () => {
 for (const scenario of ['default', 'full', 'unknown', 'stale', 'dead-phase', 'dead-player', 'world', 'dimension']) {
  const views = new ModelObservationViews();
  const first = presentNativeToolResult(sample(), { kind: 'observe' }, views); first.commit();
  const current = sample();
  if (scenario === 'stale') current.freshness.fresh = false;
  if (scenario === 'dead-phase') current.observation.continuity = { phase: 'dead' };
  if (scenario === 'dead-player') current.observation.player.dead = true;
  if (scenario === 'world') current.observation.world.worldId = 'other';
  if (scenario === 'dimension') current.observation.world.dimension = 'other';
  const tool = { kind: 'sequence', view: 'changes', afterObservationId: decode(first).observationView.id };
  if (scenario === 'default') delete tool.view;
  if (scenario === 'full') tool.view = 'full';
  if (scenario === 'unknown') tool.afterObservationId = 'unknown';
  const result = decode(presentNativeToolResult({ state: 'SUCCEEDED', postAction: current }, tool, views));
  assert.equal(result.postAction.observationView.mode, 'full', scenario);
  assert.deepEqual(restore(null, result.postAction), current);
 }
});

test('missing nested observations and per-step history never become views', () => {
 const value = { results: [{ observation: sample().observation }], postAction: { freshness: { fresh: false }, reasonCode: 'UNAVAILABLE' } };
 const result = presentNativeToolResult(value, { kind: 'sequence', view: 'changes', afterObservationId: 'old' });
 assert.deepEqual(decode(result), JSON.parse(toolResultContent(value).contentItems[0].text));
});

test('late and repeated commits cannot replace a later delivered nested baseline', () => {
 const views = new ModelObservationViews();
 const older = views.prepare({ postAction: sample() }, { kind: 'action' });
 const newer = views.prepare({ postAction: sample() }, { kind: 'sequence' });
 newer.commit(); older.commit(); newer.commit();
 const current = views.prepare(sample(), { kind: 'observe', view: 'changes', afterObservationId: newer.value.postAction.observationView.id });
 assert.equal(current.value.observationView.mode, 'changes');
 views.reset(); current.commit();
 assert.equal(views.prepare(sample(), { kind: 'observe', view: 'changes', afterObservationId: current.value.observationView.id }).value.observationView.mode, 'full');
});

test('replacement sessions cannot alias an older exact baseline ID', () => {
 const old = new ModelObservationViews().prepare(sample(), { kind: 'observe' });
 const replacement = new ModelObservationViews();
 const fresh = replacement.prepare(sample(), { kind: 'observe' }); fresh.commit();
 assert.notEqual(old.value.observationView.id, fresh.value.observationView.id);
 assert.equal(replacement.prepare(sample(), { kind: 'observe', view: 'changes', afterObservationId: old.value.observationView.id }).value.observationView.mode, 'full');
});

test('world-change events invalidate nested baselines even after returning to the original world', () => {
 const views = new ModelObservationViews();
 const first = views.prepare({ postAction: sample() }, { kind: 'action' }); first.commit();
 const event = sample().observation; event.world.worldId = 'other';
 encodeNativeEventInput(`heading\n${JSON.stringify({ event: 'task_continue', observation: event })}`, views);
 assert.equal(views.prepare(sample(), { kind: 'observe', view: 'changes', afterObservationId: first.value.postAction.observationView.id }).value.observationView.mode, 'full');
});

test('postAction presentation options are discoverable and never enter physical arguments', () => {
 const calls = [
  ['moveTo', { x: 1, y: 64, z: 2 }],
  ['mine', { x: 1, y: 64, z: 2, expectedBlockId: 'minecraft:stone', autoAim: true }],
  ['act', { actionType: 'pick_up_item', arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' } }],
  ['sequence', { actions: [1, 2].map(x => ({ actionType: 'navigate_to', arguments: { x, y: 64, z: 2 } })) }],
 ];
 for (const [name, args] of calls) {
  const schema = MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === name);
  assert.deepEqual(schema.inputSchema.properties.view.enum, ['full', 'changes']);
  const base = normalizeMinecraftToolCall(name, args);
  const current = normalizeMinecraftToolCall(name, { ...args, view: 'changes', afterObservationId: 'exact-id' });
  const { view, afterObservationId, ...body } = current;
  assert.equal(view, 'changes'); assert.equal(afterObservationId, 'exact-id'); assert.deepEqual(body, base);
  assert.throws(() => normalizeMinecraftToolCall(name, { ...args, view: 'full', afterObservationId: 'exact-id' }));
  assert.throws(() => normalizeMinecraftToolCall(name, { ...args, view: 'automatic' }));
 }
 assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [1, 2].map(x => ({ actionType: 'navigate_to', arguments: { x, y: 64, z: 2, view: 'changes' } })) }));
});

test('preparation owns nested metadata, receipts and facts independently of its source', () => {
 const views = new ModelObservationViews();
 const input = { state: 'SUCCEEDED', results: [{ actionId: 'historical', observation: { player: { health: 20 } } }], postAction: sample() };
 const before = structuredClone(input);
 const prepared = views.prepare(input, { kind: 'sequence' });
 input.results[0].actionId = 'changed';
 input.postAction.freshness.fresh = false;
 input.postAction.observation.player.health = 0;
 assert.deepEqual({ ...prepared.value, postAction: restore(null, prepared.value.postAction) }, before);
 prepared.commit();
 const delta = views.prepare(before.postAction, { kind: 'observe', view: 'changes', afterObservationId: prepared.value.postAction.observationView.id });
 assert.equal(delta.value.observationView.mode, 'changes');
 assert.deepEqual(delta.value.observationView.replace, {});
});

test('older delivered or stale callbacks cannot replace the newest observe baseline', () => {
 const views = new ModelObservationViews();
 const older = views.prepare(sample(), { kind: 'observe' });
 const stale = views.prepare({ ...sample(), freshness: { fresh: false } }, { kind: 'observe' });
 const newer = views.prepare(sample(), { kind: 'observe' });
 newer.commit(); older.commit(); stale.commit();
 assert.equal(views.prepare(sample(), { kind: 'observe', view: 'changes', afterObservationId: newer.value.observationView.id }).value.observationView.mode, 'changes');
});

test('presentation options reject on startAction and nested physical steps that do not advertise views', () => {
 const action = { actionType: 'pick_up_item', arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' } };
 assert.equal(MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === 'startAction').inputSchema.properties.view, undefined);
 assert.throws(() => normalizeMinecraftToolCall('startAction', { ...action, view: 'changes', afterObservationId: 'id' }));
 assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ ...action, view: 'changes' }, action] }));
});
