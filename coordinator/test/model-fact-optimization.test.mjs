import assert from 'node:assert/strict';
import test from 'node:test';
import { presentNativeToolResult } from '../src/codex-service.mjs';
import { ModelObservationViews, decodeModelFacts, encodeModelFacts } from '../src/model-fact-encoding.mjs';
import { MAX_TOOL_RESULT_BYTES, toolResultContent } from '../src/native-minecraft-tools.mjs';

const sample = () => ({ goalRevision: 1, eventSequence: 7, freshness: { fresh: true }, observation: {
 world: { worldId: 'fixture', dimension: 'minecraft:overworld' }, player: { dead: false, health: 20 },
 blocks: Array.from({ length: 64 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone', state: {}, description: 'observed solid stone block with known collision shape', bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }], fluid: { type: 'minecraft:empty', level: 0 }, hardness: 1.5 })),
 coverage: { blocks: { complete: false, reason: 'outside_observed_area' } },
} });
const decoded = presented => decodeModelFacts(JSON.parse(presented.response.contentItems[0].text));
const deltaTool = id => ({ kind: 'observe', view: 'changes', afterObservationId: id });
const full = { kind: 'observe' };

for (const kind of ['observe', 'action', 'sequence']) test(`lossless full ${kind} facts that fit are preserved before legacy truncation`, () => {
 const views = new ModelObservationViews();
 const original = kind === 'observe' ? sample() : { state: 'SUCCEEDED', results: [{ actionId: 'exact' }], postAction: sample() };
 assert.ok(Buffer.byteLength(JSON.stringify(original)) > MAX_TOOL_RESULT_BYTES);
 const legacy = JSON.parse(toolResultContent(original).contentItems[0].text);
 assert.ok((kind === 'observe' ? legacy : legacy.postAction).observation.blocks.length < 64);
 const presented = presentNativeToolResult(original, { kind }, views);
 assert.ok(Buffer.byteLength(presented.response.contentItems[0].text) <= MAX_TOOL_RESULT_BYTES);
 const result = decoded(presented);
 const snapshot = kind === 'observe' ? result : result.postAction;
 const { observationView, ...facts } = snapshot;
 assert.equal(observationView.mode, 'full');
 assert.deepEqual(facts, sample());
 presented.commit();
 const next = decoded(presentNativeToolResult(sample(), deltaTool(observationView.id), views));
 assert.equal(next.observationView.mode, 'changes');
 assert.deepEqual(next.observationView.replace, {});
});

test('lossless delta preserves full known sections before the raw result cap', () => {
 const views = new ModelObservationViews();
 const initial = sample();
 const baseline = presentNativeToolResult(initial, full, views); baseline.commit();
 const current = structuredClone(initial); current.observation.player.health = 19;
 assert.ok(Buffer.byteLength(JSON.stringify(current)) > MAX_TOOL_RESULT_BYTES);
 const result = decoded(presentNativeToolResult(current, deltaTool(decoded(baseline).observationView.id), views));
 assert.equal(result.observationView.mode, 'changes');
 assert.deepEqual(result.observationView.replace, { player: current.observation.player });
 assert.equal(result.truncated, undefined);
});

test('sibling delivered views remain exact across reversed commits, repeat commits, eviction and reset', () => {
 const views = new ModelObservationViews();
 const first = views.prepare(sample(), full); first.commit();
 const id = first.value.observationView.id;
 const siblings = [18, 19].map(health => {
  const next = sample(); next.observation.player.health = health;
  return views.prepare(next, deltaTool(id));
 });
 siblings[1].commit(); siblings[0].commit(); siblings[0].commit();
 for (const prepared of [first, ...siblings]) {
  const result = views.prepare(sample(), deltaTool(prepared.value.observationView.id));
  assert.equal(result.value.observationView.mode, 'changes');
  assert.deepEqual(result.value.observationView.replace, prepared === first ? {} : { player: { dead: false, health: 20 } });
 }
 for (let index = 0; index < 8; index++) views.prepare(sample(), full).commit();
 assert.equal(views.prepare(sample(), deltaTool(id)).value.observationView.mode, 'full');
 const pending = views.prepare(sample(), full);
 views.reset(); pending.commit();
 assert.equal(views.prepare(sample(), deltaTool(pending.value.observationView.id)).value.observationView.mode, 'full');
});

test('metadata retention references only exact delivered equal content and matching goal/world revisions', () => {
 const views = new ModelObservationViews();
 const original = { ...sample(), taskMemory: { revision: 3, lessons: ['known '.repeat(200)] }, goal: 'find stone', goalSpec: null, executionSettings: { revision: 4, limit: 40 } };
 const baseline = views.prepare(original, full); baseline.commit();
 const tool = deltaTool(baseline.value.observationView.id);
 const next = views.prepare(original, tool);
 assert.deepEqual(next.value.observationView.retainMetadata, ['taskMemory', 'goal', 'goalSpec', 'executionSettings']);
 const { observationView, ...fields } = next.value;
 const restored = { ...Object.fromEntries(observationView.retainMetadata.map(key => [key, original[key]])), ...fields, observation: original.observation };
 assert.deepEqual(restored, original);
 const changed = structuredClone(original); changed.taskMemory.lessons = ['new content at the same revision']; delete changed.executionSettings;
 const changeView = views.prepare(changed, tool).value;
 assert.deepEqual(changeView.taskMemory, changed.taskMemory);
 assert.ok(!changeView.observationView.retainMetadata.includes('executionSettings'));
 for (const mutate of [value => value.goalRevision++, value => value.observation.world.worldId = 'other']) {
  const value = structuredClone(original); mutate(value);
  const fresh = views.prepare(value, tool).value;
  assert.equal(fresh.observationView.mode, 'full');
  assert.deepEqual(fresh.taskMemory, original.taskMemory);
 }
 views.reset(); next.commit();
 assert.equal(views.prepare(original, deltaTool(next.value.observationView.id)).value.observationView.mode, 'full');
});

test('a successful world change fences older pending deliveries even after returning to the old world', () => {
 const views = new ModelObservationViews();
 const old = views.prepare(sample(), full); old.commit();
 const pending = views.prepare(sample(), full);
 const changed = sample(); changed.observation.world.worldId = 'different';
 views.prepare(changed, full).commit(); pending.commit();
 assert.equal(views.prepare(sample(), deltaTool(old.value.observationView.id)).value.observationView.mode, 'full');
 assert.equal(views.prepare(sample(), deltaTool(pending.value.observationView.id)).value.observationView.mode, 'full');
});

test('nested homogeneous rows reuse packed children instead of recursively repacking subtrees', () => {
 let leafReads = 0;
 let value = { get leaf() { leafReads++; return 'long factual leaf value '.repeat(5); } };
 for (let depth = 0; depth < 14; depth++) value = [{ depth, child: value }, { depth, child: null }, { depth, child: false }];
 const original = JSON.parse(JSON.stringify(value));
 leafReads = 0;
 const packed = encodeModelFacts(value);
 assert.deepEqual(decodeModelFacts(JSON.parse(JSON.stringify(packed))), original);
 assert.ok(leafReads < 500, `nested leaf inspected ${leafReads} times`);
});


test('delivered history evicts by byte budget as well as view count', () => {
 const views = new ModelObservationViews();
 const large = sample(); large.observation.description = 'x'.repeat(330_000);
 const prepared = Array.from({ length: 7 }, () => { const view = views.prepare(large, full); view.commit(); return view; });
 assert.equal(views.prepare(large, deltaTool(prepared[0].value.observationView.id)).value.observationView.mode, 'full');
 assert.equal(views.prepare(large, deltaTool(prepared.at(-1).value.observationView.id)).value.observationView.mode, 'changes');
});

test('oversized lossless candidates fall back with bounded coverage and exact delivered baselines', () => {
 const views = new ModelObservationViews();
 const oversized = sample();
 oversized.observation.details = 'repeated factual data'.repeat(60_000);
 assert.ok(Buffer.byteLength(JSON.stringify(oversized)) > 1024 * 1024);
 const result = presentNativeToolResult(oversized, full, views);
 assert.ok(Buffer.byteLength(result.response.contentItems[0].text) <= MAX_TOOL_RESULT_BYTES);
 const { observationView, ...facts } = decoded(result);
 assert.deepEqual(facts, JSON.parse(toolResultContent(oversized).contentItems[0].text));
 result.commit();
 const next = decoded(presentNativeToolResult(oversized, deltaTool(observationView.id), views));
 assert.equal(next.observationView.mode, 'changes');
 assert.deepEqual(next.observationView.replace, {});
});
