import assert from 'node:assert/strict';
import test from 'node:test';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';
import { MODEL_FACT_FORMAT, ModelObservationViews, decodeModelFacts, encodeModelFacts, encodeNativeEventInput } from '../src/model-fact-encoding.mjs';

const json = value => JSON.parse(JSON.stringify(value));
const decoded = value => decodeModelFacts(json(encodeModelFacts(value)));
const observation = () => ({
	world: { worldId: 'fixture-world', dimension: 'minecraft:overworld' },
	freshness: { fresh: true, ageMs: 10 },
	player: { dead: false, health: 20 },
	blocks: Array.from({ length: 32 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone', state: {}, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }] })),
	coverage: { blocks: { complete: false, reason: 'outside_observed_area' } },
});
const reply = observation => ({ eventSequence: 7, freshness: { fresh: true }, observation });
const observe = { kind: 'observe' };
const changes = id => ({ kind: 'observe', view: 'changes', afterObservationId: id });

test('self-contained fact dictionaries and rows round-trip without mutating source facts', () => {
	const original = reply(observation());
	original.observation.routes = [original.observation.blocks, original.observation.blocks];
	const before = JSON.stringify(original);
	const packed = encodeModelFacts(original);
	assert.equal(packed.format, MODEL_FACT_FORMAT);
	assert.ok(Buffer.byteLength(JSON.stringify(packed)) < Buffer.byteLength(before));
	assert.equal(JSON.stringify(original), before);
	assert.deepEqual(decodeModelFacts(json(packed)), json(original));
	const firstDecode = decodeModelFacts(json(packed));
	firstDecode.observation.blocks[0].x = 99;
	assert.equal(decodeModelFacts(json(packed)).observation.blocks[0].x, 0, 'a consumer cannot mutate later decoded facts');
});

test('literal reserved keys, wrapper-shaped records and prototype names remain ordinary facts', () => {
	const literals = [
		{ $ref: 0 }, { $rows: { columns: ['x'], rows: [[1]] } }, { $object: [['x', 1]] },
		JSON.parse('{"__proto__":{"factual":true},"constructor":"field","prototype":"field"}'),
		{ format: MODEL_FACT_FORMAT, values: ['literal'], data: { $ref: 0 } },
	];
	for (const literal of literals) {
		assert.deepEqual(decoded(literal), literal);
		assert.deepEqual(decoded({ repeated: [literal, literal, literal], padding: 'x'.repeat(1_500) }).repeated, [literal, literal, literal]);
	}
	assert.equal(Object.prototype.factual, undefined);
});

test('null, absence, false, zero, empty lists and Unicode remain distinct after serialization', () => {
	const value = { rows: [{ known: null }, {}, { known: false }, { known: 0 }, { known: '' }, { known: [] }, { known: '石 🪨' }], padding: 'x'.repeat(1_500) };
	const result = decoded(value);
	assert.deepEqual(result, value);
	assert.equal(Object.hasOwn(result.rows[0], 'known'), true);
	assert.equal(Object.hasOwn(result.rows[1], 'known'), false);
});

test('500 seeded mixed JSON fixtures reconstruct exactly and never mutate the original', () => {
	let seed = 0x51ead;
	const next = () => (seed = (Math.imul(seed, 1_664_525) + 1_013_904_223) >>> 0);
	const keys = ['x', 'state', 'missing', '__proto__', 'constructor', '$ref', '$rows', '$object', 'data', 'values', 'format'];
	const sample = (depth = 0) => {
		const mode = next() % (depth >= 4 ? 6 : 10);
		if (mode < 6) return [null, false, next() % 100 - 50, '', 'minecraft:a_repeated_long_identifier_with_unicode_石', true][mode];
		if (mode === 6) return Array.from({ length: next() % 7 }, () => sample(depth + 1));
		const object = Object.create(null);
		for (let count = next() % 6; count > 0; count--) object[keys[next() % keys.length]] = sample(depth + 1);
		return object;
	};
	let packed = 0;
	for (let index = 0; index < 500; index++) {
		const value = json({ rows: Array.from({ length: 8 }, () => sample()), padding: 'x'.repeat(1_300) });
		const before = JSON.stringify(value);
		const encoded = encodeModelFacts(value);
		packed += encoded !== value;
		assert.equal(JSON.stringify(value), before, `mutation at fixture ${index}`);
		assert.deepEqual(decodeModelFacts(json(encoded)), value, `serialized reconstruction at fixture ${index}`);
	}
	assert.ok(packed > 0 && packed < 500, 'exercise both packed and unchanged representations');
});

test('decoder rejects invalid references, cyclic dictionaries and malformed tables', () => {
	const wrap = (data, values = []) => ({ format: MODEL_FACT_FORMAT, values, data });
	for (const index of [-1, 0.5, 2]) assert.throws(() => decodeModelFacts(wrap({ $ref: index }, ['fact'])), /Invalid fact reference/);
	assert.throws(() => decodeModelFacts(wrap({ $ref: 0 }, [{ $ref: 0 }])), /Invalid fact reference/);
	assert.throws(() => decodeModelFacts(wrap({ $rows: { columns: ['x', 'x'], rows: [[1, 2]] } })), /Invalid fact rows/);
	assert.throws(() => decodeModelFacts(wrap({ $rows: { columns: ['x'], rows: [[]] } })), /Invalid fact rows/);
});

test('observation changes replace whole sections and explicitly remove vanished facts', () => {
	const views = new ModelObservationViews();
	const initial = observation();
	initial.entities = null;
	const first = views.prepare(reply(initial), observe);
	first.commit();
	const next = structuredClone(initial);
	next.player.health = 0;
	next.blocks = [];
	next.empty = null;
	delete next.entities;
	const prepared = views.prepare(reply(next), changes(first.value.observationView.id));
	const delta = prepared.value.observationView;
	assert.equal(delta.mode, 'changes');
	const reconstructed = structuredClone(initial);
	for (const section of delta.remove) delete reconstructed[section];
	Object.assign(reconstructed, delta.replace);
	assert.deepEqual(reconstructed, next);
	assert.deepEqual(delta.remove, ['entities']);
	assert.equal(Object.hasOwn(delta.replace, 'coverage'), false, 'unchanged unknown coverage remains in the referenced baseline');
});

test('unknown IDs, stale facts, death and changed world, dimension or phase require full snapshots', () => {
	const cases = [
		{ name: 'unknown ID', args: changes('not-delivered') },
		{ name: 'stale', freshness: { fresh: false } },
		{ name: 'death', mutate: value => { value.player.dead = true; } },
		{ name: 'world', mutate: value => { value.world.worldId = 'another-world'; } },
		{ name: 'dimension', mutate: value => { value.world.dimension = 'minecraft:the_nether'; } },
		{ name: 'phase', mutate: value => { value.continuity = { phase: 'respawned' }; } },
		{ name: 'unknown identity', mutate: value => { delete value.world.worldId; } },
	];
	for (const scenario of cases) {
		const views = new ModelObservationViews();
		const initial = views.prepare(reply(observation()), observe);
		initial.commit();
		const current = observation();
		scenario.mutate?.(current);
		const result = reply(current);
		if (scenario.freshness) result.freshness = scenario.freshness;
		assert.equal(views.prepare(result, scenario.args ?? changes(initial.value.observationView.id)).value.observationView.mode, 'full', scenario.name);
	}
});

test('reset fences delayed commits and view state is isolated per provider thread', () => {
	const views = new ModelObservationViews();
	const pending = views.prepare(reply(observation()), observe);
	views.reset();
	pending.commit();
	assert.equal(views.prepare(reply(observation()), changes(pending.value.observationView.id)).value.observationView.mode, 'full');
	const first = views.prepare(reply(observation()), observe);
	first.commit();
	assert.equal(new ModelObservationViews().prepare(reply(observation()), changes(first.value.observationView.id)).value.observationView.mode, 'full');
});

test('native event packing retains the developer heading, retry footer and every JSON fact', () => {
	const original = buildNativeEventInput({ currentGoal: 'fixture task', goalRevision: 2 }, { event: 'program_attention', observation: observation() });
	const [heading, jsonLine] = original.split('\n');
	const value = JSON.parse(jsonLine);
	const footer = 'Retry instruction: inspect the obstruction before choosing another route.\nKeep explicit quantity limits.';
	const input = `${original}\n${footer}`;
	const result = encodeNativeEventInput(input);
	assert.equal(result.slice(0, result.indexOf('\n')), heading);
	const lineEnd = result.indexOf('\n', result.indexOf('\n') + 1);
	assert.equal(result.slice(lineEnd + 1), footer);
	assert.deepEqual(decodeModelFacts(JSON.parse(result.slice(result.indexOf('\n') + 1, lineEnd))), value);
	for (const malformed of ['plain text', 'heading\nnot JSON\nfooter', 'heading\n{}\nfooter']) assert.equal(encodeNativeEventInput(malformed), malformed);
});

test('wake deltas retain budget-trimmed observation sections instead of removing them', () => {
	const views = new ModelObservationViews();
	const first = views.prepare(reply(observation()), observe);
	first.commit();
	const event = { ...reply(observation()), contextTrimmed: { 'observation.blocks': 12_000 } };
	delete event.observation.blocks;
	const prepared = views.prepareEvent(event);
	assert.equal(prepared.observationView.mode, 'changes');
	assert.equal(prepared.observationView.remove.includes('blocks'), false);
	assert.deepEqual(prepared.observationView.replace, {});
	const later = views.prepareEvent({ ...reply({ ...observation(), blocks: [] }), contextTrimmed: {} });
	assert.deepEqual(later.observationView.replace.blocks, []);
	assert.equal(later.observationView.remove.includes('blocks'), false);
});

test('wake events send changed observation sections against the exact last event view', () => {
	const views = new ModelObservationViews();
	const input = (eventSequence, current) => `Wake event.\n${JSON.stringify({ event: 'observation', goalRevision: 3, eventSequence, observation: current })}`;
	const first = decodeModelFacts(JSON.parse(encodeNativeEventInput(input(1, observation()), views).split('\n')[1]));
	assert.equal(first.observationView.mode, 'full');

	const next = observation();
	next.player.health = 11;
	next.blocks = next.blocks.slice(1);
	const second = decodeModelFacts(JSON.parse(encodeNativeEventInput(input(2, next), views).split('\n')[1]));
	const view = second.observationView;
	assert.equal(view.mode, 'changes');
	assert.equal(view.baseId, first.observationView.id);
	assert.deepEqual(Object.keys(view.replace).sort(), ['blocks', 'player']);
	const reconstructed = structuredClone(first.observation);
	for (const section of view.remove) delete reconstructed[section];
	Object.assign(reconstructed, view.replace);
	assert.deepEqual(reconstructed, next);
});

test('a wake can delta against the latest exact tool observation view', () => {
	const views = new ModelObservationViews();
	const base = views.prepare({ ...reply(observation()), goalRevision: 3 }, observe);
	base.commit();
	const next = observation();
	next.player.health = 7;
	const input = `Wake event.\n${JSON.stringify({ event: 'observation', goalRevision: 3, observation: next })}`;
	const delta = decodeModelFacts(JSON.parse(encodeNativeEventInput(input, views).split('\n')[1]));
	assert.equal(delta.observationView.mode, 'changes');
	assert.equal(delta.observationView.baseId, base.value.observationView.id);
	assert.deepEqual(delta.observationView.replace.player, next.player);
});

test('wake event deltas fall back to full after world, dimension, death, goal or baseline resets', () => {
	for (const [name, mutate, nextGoalRevision] of [
		['world', value => { value.world.worldId = 'another-world'; }, 3],
		['dimension', value => { value.world.dimension = 'minecraft:the_nether'; }, 3],
		['death', value => { value.player.dead = true; }, 3],
		['goal revision', value => value, 4],
		['uncertain identity', value => { delete value.world.worldId; }, 3],
	]) {
		const views = new ModelObservationViews();
		const event = (sequence, current, goalRevision = 3) => `Wake event.\n${JSON.stringify({ event: 'observation', goalRevision, eventSequence: sequence, observation: current })}`;
		const first = decodeModelFacts(JSON.parse(encodeNativeEventInput(event(1, observation()), views).split('\n')[1]));
		const changed = observation();
		mutate(changed);
		const second = decodeModelFacts(JSON.parse(encodeNativeEventInput(event(2, changed, nextGoalRevision), views).split('\n')[1]));
		if (name === 'uncertain identity') {
			assert.equal(second.observationView, undefined, 'an unknown world identity sends the full facts without advertising a reusable baseline');
			assert.deepEqual(second.observation, changed);
		} else assert.equal(second.observationView.mode, 'full', `${name} reset`);
	}
});

test('an uncertain event delivery clears the wake baseline before the next event', () => {
	const views = new ModelObservationViews();
	const event = current => `Wake event.\n${JSON.stringify({ event: 'observation', goalRevision: 3, observation: current })}`;
	const first = decodeModelFacts(JSON.parse(encodeNativeEventInput(event(observation()), views).split('\n')[1]));
	assert.equal(first.observationView.mode, 'full');
	views.forgetEventView();
	const next = observation();
	next.player.health = 4;
	const second = decodeModelFacts(JSON.parse(encodeNativeEventInput(event(next), views).split('\n')[1]));
	assert.equal(second.observationView.mode, 'full');
});

test('native death events invalidate the prior observation even before a dead observe reply', () => {
	for (const mutate of [value => { value.event = 'player_death'; }, value => { value.observation.player.dead = true; }, value => { value.observation.continuity = { phase: 'dead' }; }]) {
		const views = new ModelObservationViews();
		const first = views.prepare(reply(observation()), observe);
		first.commit();
		const value = { event: 'task_continue', observation: observation() };
		mutate(value);
		encodeNativeEventInput(`heading\n${JSON.stringify(value)}\nfooter`, views);
		assert.equal(views.prepare(reply(observation()), changes(first.value.observationView.id)).value.observationView.mode, 'full');
	}
});
