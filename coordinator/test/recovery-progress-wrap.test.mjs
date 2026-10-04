import assert from 'node:assert/strict';
import test from 'node:test';

import { RecoveryProgressStore } from '../src/recovery-progress-wrap.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

const DEATH = {
	cause: 'Lucas fell from a high place',
	dimensionId: 'minecraft:overworld',
	x: 120.5,
	y: 12,
	z: -40.25,
	diedAtEpochMs: 1_700_000_000_000,
};

function liveObservation(overrides = {}) {
	return {
		ready: true,
		player: { health: 18, dead: false, x: 10, y: 64, z: 12 },
		inventory: {
			items: [
				{ itemId: 'minecraft:stone_pickaxe', count: 1, slot: 0 },
				{ itemId: 'minecraft:cobblestone', count: 12, slot: 1 },
			],
		},
		blocks: [{ blockId: 'minecraft:crafting_table', x: 11, y: 64, z: 12 }],
		nearbyContainers: [{ blockId: 'minecraft:chest', x: 12, y: 64, z: 12, distance: 1, withinInteractionRange: true, capabilities: ['transfer'] }],
		items: [],
		...overrides,
	};
}

test('empty observations stay unwrapped so ordinary turns do not grow a planner', () => {
	const store = new RecoveryProgressStore();
	const observation = { player: { health: 20 }, inventory: { items: [] }, blocks: [] };
	assert.equal(store.remember('agent-a', 1, observation), null);
	assert.deepEqual(store.wrap('agent-a', 1, observation), observation);
});

test('death preserves last live inventory as lastLost, not as currently held alreadyHave', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 4, liveObservation());
	store.remember('lucas', 4, { death: DEATH });

	const dead = store.wrap('lucas', 4, { death: DEATH, player: { dead: true }, inventory: { items: [] } });
	assert.equal(dead.recovery.lastDeath.cause, DEATH.cause);
	assert.equal(dead.recovery.lastDeath.x, DEATH.x);
	assert.deepEqual(dead.recovery.lastLostInventory, [
		{ itemId: 'minecraft:stone_pickaxe', count: 1 },
		{ itemId: 'minecraft:cobblestone', count: 12 },
	]);
	assert.equal(dead.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false,
		'lost corpse stacks are not currently evidenced inventory');
	assert.equal(dead.recovery.alreadyHaveFacts.some((entry) => entry.kind === 'lost_on_death'), false);
	assert.ok(dead.recovery.alreadyHaveFacts.some((entry) => entry.kind === 'placed' && entry.blockId === 'minecraft:crafting_table'));
	assert.deepEqual(dead.recovery.doNotRedo, []);
	assert.equal(dead.recovery.doNotRedo.includes('minecraft:stone_pickaxe'), false,
		'the player may recraft if the corpse is gone');
	assert.equal(dead.recovery.everPossessed, undefined);
});

test('post-respawn empty observe still surfaces last death and lastLost stacks', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 4, liveObservation());
	store.remember('lucas', 4, { death: DEATH });

	const afterRespawn = store.wrap('lucas', 4, { ready: true, player: { dead: false, health: 20 }, inventory: { items: [] }, blocks: [] });
	assert.equal(afterRespawn.recovery.lastDeath.z, DEATH.z);
	assert.equal(afterRespawn.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.ok(afterRespawn.recovery.alreadyHaveFacts.some((entry) => entry.blockId === 'minecraft:crafting_table' && entry.remembered === true));
	assert.equal(afterRespawn.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
});

test('current tools are facts and never suppress model crafting choices', () => {
	const store = new RecoveryProgressStore();
	const recovery = store.remember('agent-a', 2, {
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1, slot: 0 }] },
		player: { dead: false },
	});
	assert.deepEqual(recovery.doNotRedo, []);
	assert.deepEqual(recovery.doNotRedo, []);
});

test('tool families do not create a system recipe policy', () => {
	const store = new RecoveryProgressStore();
	const recovery = store.remember('agent-a', 2, {
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1, slot: 0 }] },
		player: { dead: false },
	});
	assert.deepEqual(recovery.doNotRedo, []);
	assert.deepEqual(recovery.doNotRedo, []);
	assert.equal(recovery.doNotRedo.includes('minecraft:wooden_axe'), false);
	assert.equal(recovery.doNotRedo.includes('minecraft:stone_sword'), false);
});

test('nearby death drops are labeled as observed drops without a retrieval decision', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation());
	store.remember('lucas', 1, { death: DEATH });
	const wrapped = store.wrap('lucas', 1, {
		player: { dead: false },
		inventory: { items: [] },
		items: [{ itemId: 'minecraft:stone_pickaxe', count: 1, stableId: 'drop-1', x: DEATH.x, y: DEATH.y, z: DEATH.z }],
	});
	assert.ok(wrapped.recovery.alreadyHaveFacts.some((entry) => entry.kind === 'dropped' && entry.itemId === 'minecraft:stone_pickaxe'));
	assert.deepEqual(wrapped.recovery.doNotRedo, []);
});

test('goal revision replacement keeps lastDeath but drops prior-goal inventory memory', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation());
	store.remember('lucas', 1, { death: DEATH });
	const nextGoal = store.remember('lucas', 2, { player: { dead: false }, inventory: { items: [] }, blocks: [] });
	assert.equal(nextGoal.lastDeath.cause, DEATH.cause);
	assert.equal(nextGoal.lastLostInventory, undefined);
	assert.equal(nextGoal.alreadyHave.includes('minecraft:stone_pickaxe'), false);
});

test('forget removes one agent without touching another', () => {
	const store = new RecoveryProgressStore();
	store.remember('a', 1, liveObservation());
	store.remember('b', 1, liveObservation({ inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] } }));
	store.forget('a');
	assert.equal(store.snapshot('a'), null);
	assert.deepEqual(store.snapshot('b').doNotRedo, []);
});

test('iron ingot is not a completed tool tier and does not skip wooden or stone recipes', () => {
	const store = new RecoveryProgressStore();
	const recovery = store.remember('agent-a', 1, {
		inventory: { items: [{ itemId: 'minecraft:iron_ingot', count: 8, slot: 0 }] },
		player: { dead: false },
	});
	assert.equal(recovery.doNotRedo.includes('minecraft:wooden_pickaxe'), false);
	assert.equal(recovery.doNotRedo.includes('minecraft:stone_pickaxe'), false);
	assert.ok(recovery.alreadyHave.includes('minecraft:iron_ingot'));
});

test('diamond blocks and stone bricks are not completed tool tiers', () => {
	const store = new RecoveryProgressStore();
	const recovery = store.remember('agent-a', 1, {
		inventory: { items: [
			{ itemId: 'minecraft:diamond_block', count: 1, slot: 0 },
			{ itemId: 'minecraft:stone_bricks', count: 8, slot: 1 },
		] },
		player: { dead: false },
	});
	assert.equal(recovery.doNotRedo.includes('minecraft:wooden_pickaxe'), false);
	assert.equal(recovery.doNotRedo.includes('minecraft:stone_pickaxe'), false);
	assert.equal(recovery.doNotRedo.includes('minecraft:iron_pickaxe'), false);
});

test('a broken iron pickaxe is not alreadyHave so wooden and stone recipes can be recrafted', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, {
		player: { dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
	});
	const afterBreak = store.remember('lucas', 1, {
		player: { dead: false },
		inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 4 }] },
	});
	assert.equal(afterBreak.alreadyHave.includes('minecraft:iron_pickaxe'), false);
	assert.equal(afterBreak.doNotRedo.includes('minecraft:wooden_pickaxe'), false);
	assert.equal(afterBreak.doNotRedo.includes('minecraft:stone_pickaxe'), false);
});

test('partial corpse pickup keeps the remaining lastLostInventory stacks', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 4, liveObservation());
	store.remember('lucas', 4, { death: DEATH });
	const partial = store.remember('lucas', 4, {
		ready: true,
		player: { dead: false, health: 20 },
		inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
		blocks: [],
	});
	assert.deepEqual(partial.lastLostInventory, [{ itemId: 'minecraft:cobblestone', count: 12 }]);
	assert.ok(partial.alreadyHave.includes('minecraft:stone_pickaxe'));
	assert.equal(partial.alreadyHave.includes('minecraft:cobblestone'), false);
});

test('ordinary inventory depletion does not replace a prior corpse, but a new death does', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 4, liveObservation());
	store.remember('lucas', 4, { death: DEATH });
	store.remember('lucas', 4, {
		ready: true,
		player: { dead: false, health: 20 },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 3 }] },
		blocks: [],
	});
	const depleted = store.remember('lucas', 4, {
		ready: true,
		player: { dead: false, health: 20 },
		inventory: { items: [] },
		blocks: [],
	});
	assert.deepEqual(depleted.lastLostInventory, [
		{ itemId: 'minecraft:stone_pickaxe', count: 1 },
		{ itemId: 'minecraft:cobblestone', count: 12 },
	]);
	store.remember('lucas', 4, {
		ready: true,
		player: { dead: false, health: 20 },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 3 }] },
		blocks: [],
	});
	const secondDeath = { ...DEATH, x: 200, diedAtEpochMs: DEATH.diedAtEpochMs + 1_000 };
	const afterSecondDeath = store.remember('lucas', 4, { death: secondDeath });
	assert.deepEqual(afterSecondDeath.lastLostInventory, [{ itemId: 'minecraft:oak_log', count: 3 }]);
});

test('remembered workstations are scoped to the observed dimension', () => {
	const store = new RecoveryProgressStore();
	store.remember('agent-a', 1, {
		world: { dimension: 'minecraft:overworld' },
		player: { dead: false },
		blocks: [{ blockId: 'minecraft:crafting_table', x: 4, y: 64, z: 4 }],
		inventory: { items: [] },
	});
	const nether = store.wrap('agent-a', 1, {
		world: { dimension: 'minecraft:the_nether' },
		player: { dead: false },
		blocks: [],
		inventory: { items: [] },
	});
	assert.equal(nether.recovery?.alreadyHaveFacts?.some((entry) => entry.remembered === true) === true, false);
});

test('a second death replaces lastLost with that death inventory', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation());
	store.remember('lucas', 1, { death: DEATH });
	store.remember('lucas', 1, {
		player: { dead: false, x: 20, y: 64, z: 20 },
		inventory: { items: [{ itemId: 'minecraft:wooden_sword', count: 1 }] },
		blocks: [],
		world: { dimension: 'minecraft:overworld' },
	});
	const secondDeath = {
		cause: 'zombie',
		dimensionId: 'minecraft:overworld',
		x: 20, y: 64, z: 20, diedAtEpochMs: 1_700_000_000_100,
	};
	const dead = store.remember('lucas', 1, { death: secondDeath });
	assert.equal(dead.lastDeath.x, 20);
	assert.deepEqual(dead.lastLostInventory, [{ itemId: 'minecraft:wooden_sword', count: 1 }]);
	assert.equal(dead.lastLostInventory.some((item) => item.itemId === 'minecraft:stone_pickaxe'), false);
});

test('empty inventory while alive does not rewrite lastLost as another death', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation());
	store.remember('lucas', 1, { death: DEATH });
	store.remember('lucas', 1, {
		player: { dead: false },
		inventory: { items: [{ itemId: 'minecraft:wooden_pickaxe', count: 1 }] },
		blocks: [],
	});
	const afterBreak = store.remember('lucas', 1, {
		player: { dead: false },
		inventory: { items: [] },
		blocks: [],
	});
	assert.deepEqual(afterBreak.lastLostInventory, [
		{ itemId: 'minecraft:stone_pickaxe', count: 1 },
		{ itemId: 'minecraft:cobblestone', count: 12 },
	]);
	assert.equal(afterBreak.alreadyHave.includes('minecraft:wooden_pickaxe'), false);
});

test('an iron sword does not prescribe any crafting exclusions', () => {
	const store = new RecoveryProgressStore();
	const recovery = store.remember('agent-a', 1, {
		inventory: { items: [{ itemId: 'minecraft:iron_sword', count: 1 }] },
		player: { dead: false },
	});
	assert.deepEqual(recovery.doNotRedo, []);
	assert.deepEqual(recovery.doNotRedo, []);
	assert.equal(recovery.doNotRedo.includes('minecraft:wooden_pickaxe'), false);
	assert.equal(recovery.doNotRedo.includes('minecraft:stone_pickaxe'), false);
});

test('remembered overworld stations are not currently evidenced in the Nether', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation({
		world: { dimension: 'minecraft:overworld' },
	}));
	const nether = store.remember('lucas', 1, {
		player: { dead: false, x: 0, y: 64, z: 0 },
		inventory: { items: [] },
		blocks: [],
		world: { dimension: 'minecraft:the_nether' },
	});
	assert.equal(nether?.alreadyHave?.includes('minecraft:crafting_table') === true, false);
	assert.equal(nether?.doNotRedo?.includes('minecraft:crafting_table') === true, false);
});

test('empty conversation placeholders do not wipe live inventory memory', () => {
	const store = new RecoveryProgressStore();
	store.remember('lucas', 1, liveObservation());
	store.remember('lucas', 1, {});
	store.remember('lucas', 1, { death: DEATH });
	const dead = store.snapshot('lucas', { death: DEATH, inventory: { items: [] } });
	assert.deepEqual(dead.lastLostInventory, [
		{ itemId: 'minecraft:stone_pickaxe', count: 1 },
		{ itemId: 'minecraft:cobblestone', count: 12 },
	]);
	assert.ok(dead.alreadyHave.includes('minecraft:crafting_table'));
});

const diamonds = (count) => ({ player: { dead: false }, inventory: { items: count ? [{ itemId: 'minecraft:diamond', count }] : [] } });

test('repeated snapshots reconcile only new inventory gains, including gains after spending items', () => {
	const store = new RecoveryProgressStore();
	store.remember('a', 1, diamonds(6));
	store.remember('a', 1, { death: DEATH });
	for (const [held, lost] of [[2, 4], [2, 4], [3, 3], [1, 3], [2, 2], [2, 2]]) {
		const snapshot = store.remember('a', 1, diamonds(held));
		assert.deepEqual(snapshot.lastLostInventory, [{ itemId: 'minecraft:diamond', count: lost }]);
		assert.deepEqual(store.wrap('a', 1, diamonds(held)).recovery.lastLostInventory, snapshot.lastLostInventory);
	}
	store.remember('a', 1, { player: { x: 7 } });
	assert.deepEqual(store.remember('a', 1, diamonds(2)).lastLostInventory, [{ itemId: 'minecraft:diamond', count: 2 }]);
	store.remember('a', 1, { death: DEATH, lastLiveInventory: diamonds(6).inventory });
	assert.deepEqual(store.remember('a', 1, diamonds(2)).lastLostInventory, [{ itemId: 'minecraft:diamond', count: 2 }], 'duplicate death delivery cannot reset recovery');
	const second = store.remember('a', 1, { death: { ...DEATH, dimensionId: 'minecraft:the_nether', diedAtEpochMs: DEATH.diedAtEpochMs + 1 } });
	assert.deepEqual(second.lastLostInventory, [{ itemId: 'minecraft:diamond', count: 2 }]);
	assert.equal(second.lastDeath.dimensionId, 'minecraft:the_nether');
	assert.equal(store.remember('a', 1, diamonds(2)).lastLostInventory, undefined);
});

test('native update and repeated decoration cannot recover the same held diamond twice', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send() { throw new Error('No bridge commands expected'); } } });
	const record = { agentId: 'a', goalRevision: 1, provider: 'fixture', model: 'fixture', reasoningEffort: 'high' };
	try {
		runtime.updateObservation(record, diamonds(3), { eventSequence: 1 });
		runtime.updateObservation(record, { death: DEATH }, { eventSequence: 2 });
		runtime.updateObservation(record, diamonds(1), { eventSequence: 3 });
		for (let index = 0; index < 3; index += 1) {
			const decorated = runtime.decorateObservation(record, diamonds(1));
			assert.deepEqual(decorated.recovery.lastLostInventory, [{ itemId: 'minecraft:diamond', count: 2 }]);
		}
	} finally { await runtime.dispose('a', 'agent_removed'); }
});

test('all inventory slots and duplicate stack counts survive death and slot rearrangement', () => {
	const store = new RecoveryProgressStore();
	const filler = Array.from({ length: 35 }, (_, index) => ({ itemId: `fixture:item_${index}`, count: 1 }));
	store.remember('a', 1, { inventory: { items: [...filler, { itemId: 'minecraft:diamond', count: 4 }, { itemId: 'minecraft:diamond', count: 3 }, { itemId: 'minecraft:air', count: 1 }, { itemId: 'minecraft:stone', count: 0 }] } });
	const dead = store.remember('a', 1, { death: DEATH });
	assert.equal(dead.lastLostInventory.length, 36);
	assert.deepEqual(dead.lastLostInventory.at(-1), { itemId: 'minecraft:diamond', count: 7 });
	store.remember('a', 1, { inventory: { items: [{ itemId: 'minecraft:diamond', count: 1 }, { itemId: 'minecraft:diamond', count: 2 }] } });
	const rearranged = store.remember('a', 1, diamonds(3));
	assert.deepEqual(rearranged.lastLostInventory.at(-1), { itemId: 'minecraft:diamond', count: 4 });
	assert.deepEqual(rearranged.alreadyHaveFacts.find((entry) => entry.itemId === 'minecraft:diamond'), { kind: 'inventory', itemId: 'minecraft:diamond', count: 3 });
});

test('only explicit contradictory blocks in the same world and dimension invalidate stations', () => {
	const store = new RecoveryProgressStore();
	const station = { blockId: 'minecraft:crafting_table', x: 4, y: 64, z: 4 };
	const observe = (worldId, dimension, blocks) => ({ world: { worldId, dimension }, blocks });
	store.remember('a', 1, observe('one', 'minecraft:overworld', [station]));
	const absent = store.remember('a', 1, observe('one', 'minecraft:overworld', []));
	assert.equal(absent.alreadyHaveFacts[0].remembered, true);
	const replacement = { ...station, blockId: 'minecraft:stone' };
	assert.equal(store.remember('a', 1, observe('two', 'minecraft:overworld', [replacement])), null);
	assert.equal(store.remember('a', 1, observe('one', 'minecraft:the_nether', [replacement])), null);
	assert.equal(store.snapshot('a', observe('one', 'minecraft:overworld', [])).alreadyHaveFacts[0].worldId, 'one');
	assert.equal(store.remember('a', 1, { ...observe('one', 'minecraft:overworld', [replacement]), continuity: { rememberedSections: ['blocks'] } }).alreadyHaveFacts[0].remembered, true);
	assert.equal(store.remember('a', 1, observe('one', 'minecraft:overworld', [replacement])), null);
	assert.equal(store.snapshot('a', observe('one', 'minecraft:overworld', [])), null);
	store.remember('a', 1, observe('one', 'minecraft:overworld', [station]));
	const changed = store.remember('a', 1, observe('one', 'minecraft:overworld', [{ ...station, blockId: 'minecraft:furnace' }]));
	assert.deepEqual(changed.alreadyHave, ['minecraft:furnace']);
});
