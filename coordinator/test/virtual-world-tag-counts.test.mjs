import test from 'node:test';
import assert from 'node:assert/strict';

import { MAX_OBSERVATION_TAGS, MAX_TAG_COUNT_ENTRIES } from '../src/constants.mjs';
import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';

function tags(group) {
	return Array.from({ length: MAX_OBSERVATION_TAGS }, (_, index) => `#fixture:g${group}_tag${index}`);
}

function stack(group, shared = false) {
	return { itemId: `fixture:item${group}`, count: group + 1, slot: group, tags: tags(shared ? 0 : group) };
}

function worldWith(inventory) {
	return VirtualWorld.fromScenario({
		seed: 16,
		agents: { alice: { position: { x: 0, y: 1, z: 0 }, inventory } },
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }],
	});
}

async function publish(world, bridge) {
	const observation = await bridge.publish('alice');
	await bridge.flush();
	assert.deepEqual(observation.inventory.tagCounts ?? {}, world.inventories('alice').tagCounts);
	assert.ok(Object.keys(observation.inventory.tagCounts ?? {}).length <= MAX_TAG_COUNT_ENTRIES);
	return observation.inventory.tagCounts ?? {};
}

test('initial aggregate overflow publishes a deterministic positive projection without losing inventory', async (t) => {
	const items = Array.from({ length: 5 }, (_, group) => stack(group));
	const world = worldWith({ items });
	const bridge = new VirtualMinecraftBridge({ world });
	t.after(() => bridge.stop());
	const counts = await publish(world, bridge);
	const expected = Object.fromEntries(items.flatMap(item => item.tags.map(tag => [tag, item.count]))
		.sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
		.slice(0, MAX_TAG_COUNT_ENTRIES));
	assert.deepEqual(counts, expected);
	assert.deepEqual(world.inventories('alice').items.map(({ itemId, count, tags }) => ({ itemId, count, tags })),
		items.map(({ itemId, count, tags }) => ({ itemId, count, tags })));
	assert.equal(Object.hasOwn(counts, tags(4)[0]), false, 'omitted positive facts remain unknown');
	const reversed = worldWith({ items: items.toReversed().map(item => ({ ...item, tags: item.tags.toReversed() })) });
	assert.deepEqual(reversed.inventories('alice').tagCounts, counts, 'selection is independent of fixture ordering');

	assert.equal(world.removeInventoryItem('alice', 'fixture:item0', 1), true);
	const afterRemoval = await publish(world, bridge);
	for (const tag of tags(4)) assert.equal(afterRemoval[tag], 5, 'previously omitted positive facts can reenter the projection');
	for (const tag of tags(0)) assert.equal(Object.hasOwn(afterRemoval, tag), false, 'positive facts displace zero history');
	assert.equal(world.addInventoryItem('alice', 'fixture:item0', 2), 2);
	const afterAddition = await publish(world, bridge);
	for (const tag of tags(0)) assert.equal(afterAddition[tag], 2);
	assert.equal(world.countInventoryItem('alice', 'fixture:item4'), 5, 'projection does not discard inventory stacks');
});

test('exact aggregate limit and shared-tag controls publish complete counts', async (t) => {
	for (const [name, size, shared] of [['exact limit', 4, false], ['shared tags', 5, true]]) {
		await t.test(name, async (t) => {
			const world = worldWith({ items: Array.from({ length: size }, (_, group) => stack(group, shared)) });
			const bridge = new VirtualMinecraftBridge({ world });
			t.after(() => bridge.stop());
			const counts = await publish(world, bridge);
			assert.equal(Object.keys(counts).length, shared ? MAX_OBSERVATION_TAGS : MAX_TAG_COUNT_ENTRIES);
			for (let group = 0; group < size; group += 1) {
				for (const tag of tags(shared ? 0 : group)) assert.equal(counts[tag], shared ? 15 : group + 1);
			}
		});
	}
});

test('positive facts take precedence over supplied known-zero history; omitted keys stay unknown', async (t) => {
	const history = Array.from({ length: MAX_TAG_COUNT_ENTRIES }, (_, index) => `#fixture:a_history${String(index).padStart(3, '0')}`);
	const positive = Array.from({ length: MAX_OBSERVATION_TAGS }, (_, index) => `#fixture:z_live${index}`);
	const world = worldWith({
		items: [{ itemId: 'fixture:live', count: 2, tags: positive }],
		tagCounts: Object.fromEntries(history.toReversed().map(tag => [tag, 0])),
	});
	const bridge = new VirtualMinecraftBridge({ world });
	t.after(() => bridge.stop());
	const counts = await publish(world, bridge);
	for (const tag of positive) assert.equal(counts[tag], 2);
	for (const tag of history.slice(0, MAX_TAG_COUNT_ENTRIES - positive.length)) assert.equal(counts[tag], 0);
	for (const tag of history.slice(MAX_TAG_COUNT_ENTRIES - positive.length)) assert.equal(Object.hasOwn(counts, tag), false);
	assert.equal(Object.hasOwn(counts, '#fixture:never_known'), false);
	assert.equal(world.removeInventoryItem('alice', 'fixture:live', 2), true);
	const empty = await publish(world, bridge);
	assert.equal(world.inventories('alice').items.length, 0);
	for (const tag of positive) assert.equal(empty[tag], 0);
	assert.equal(Object.hasOwn(empty, history.at(-1)), false, 'discarded history is not reconstructed as known zero');
});

test('supplied counts are validated against full item facts before projection', async (t) => {
	const items = Array.from({ length: 5 }, (_, group) => stack(group));
	const omittedPositive = tags(4)[0];
	const world = worldWith({ items, tagCounts: { [omittedPositive]: 5, '#fixture:z_known_zero': 0 } });
	const bridge = new VirtualMinecraftBridge({ world });
	t.after(() => bridge.stop());
	const counts = await publish(world, bridge);
	assert.equal(Object.hasOwn(counts, omittedPositive), false);
	assert.equal(Object.hasOwn(counts, '#fixture:z_known_zero'), false);
	assert.throws(() => worldWith({ items, tagCounts: { [omittedPositive]: 4 } }), /must match explicit item tags/);
	assert.throws(() => worldWith({ items, tagCounts: { '#fixture:z_known_zero': 1 } }), /must match explicit item tags/);
	assert.throws(() => worldWith({ items: [], tagCounts: Object.fromEntries(Array.from({ length: MAX_TAG_COUNT_ENTRIES + 1 }, (_, index) => [`#fixture:tag${index}`, 0])) }),
		(error) => error.code === 'WORLD_CAPACITY_EXCEEDED');
	const knownZero = worldWith({ items: [], tagCounts: { '#fixture:known_zero': 0 } });
	assert.deepEqual(knownZero.inventories('alice').tagCounts, { '#fixture:known_zero': 0 });
});

test('sequential tick pickups and consumption publish bounded positive and zero facts', async (t) => {
	for (const shared of [false, true]) {
		await t.test(shared ? 'shared tags' : 'distinct tags exceed historical capacity', async (t) => {
			const world = worldWith({ items: [] });
			const bridge = new VirtualMinecraftBridge({ world });
			t.after(() => bridge.stop());
			for (let group = 0; group < 5; group += 1) {
				const currentTags = tags(shared ? 0 : group);
				world.addItem({ id: `drop-${group}`, itemId: `fixture:item${group}`, count: 2, position: world.playerState('alice').position, tags: currentTags });
				world.stepTicks(1);
				assert.equal(world.inventories('alice').items.length, 1);
				assert.equal(world.countInventoryItem('alice', `fixture:item${group}`), 2);
				assert.equal(world.observation('alice').entities.some(entity => entity.uuid === `drop-${group}`), false, 'pickup collects the entire world drop');
				const pickup = await publish(world, bridge);
				assert.equal(Object.keys(pickup).length, shared ? MAX_OBSERVATION_TAGS : Math.min((group + 1) * MAX_OBSERVATION_TAGS, MAX_TAG_COUNT_ENTRIES));
				for (const tag of currentTags) assert.equal(pickup[tag], 2);
				assert.equal(world.removeInventoryItem('alice', `fixture:item${group}`, 1), true);
				const partial = await publish(world, bridge);
				for (const tag of currentTags) assert.equal(partial[tag], 1);
				assert.equal(world.removeInventoryItem('alice', `fixture:item${group}`, 1), true);
				assert.equal(world.inventories('alice').items.length, 0);
				const consumed = await publish(world, bridge);
				for (const tag of currentTags) assert.equal(consumed[tag], 0);
				assert.ok(Object.values(consumed).every(count => count === 0));
				assert.equal(Object.hasOwn(consumed, '#fixture:never_known'), false);
			}
		});
	}
});
