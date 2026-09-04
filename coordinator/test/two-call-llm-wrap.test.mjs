import test from 'node:test';
import assert from 'node:assert/strict';

import { ExplorationOccupancy } from '../src/explore-frontier.mjs';
import {
	TwoCallLlmWrap,
	classifyBodyFailure,
	composeTwoCallView,
	inferFrontierSeek,
	wrapTwoCallObservation,
} from '../src/two-call-llm-wrap.mjs';

const DEATH = {
	cause: 'lava',
	dimensionId: 'minecraft:overworld',
	x: 12,
	y: 64,
	z: -8,
};

function deathRecovery() {
	return {
		lastDeath: DEATH,
		lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
		alreadyHave: ['minecraft:crafting_table'],
		doNotRedo: ['minecraft:crafting_table'],
	};
}

test('recovery facts name the corpse without listing lost stacks as alreadyHave', () => {
	const view = composeTwoCallView({
		continuity: { phase: 'dead', cause: 'lava' },
		death: DEATH,
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
	}, deathRecovery());
	assert.equal(view.failureClass, 'recover');
	assert.match(view.recovery.facts, /Last death: lava at x=12,y=64,z=-8/i);
	assert.match(view.recovery.facts, /Current inventory is empty/i);
	assert.match(view.recovery.facts, /Lost on death: minecraft:stone_pickaxe/i);
	assert.match(view.recovery.facts, /Currently evidenced: minecraft:crafting_table/i);
	assert.ok(view.options.some((option) => option.id === 'recover_corpse' && option.action === undefined));
	assert.equal(view.nextUseful, undefined);
	assert.equal(view.instruction, undefined);
	assert.deepEqual(view.recovery.alreadyHave, ['minecraft:crafting_table']);
	assert.equal(view.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
});

test('same-dimension death offers recover_corpse as a hint, not a command', () => {
	const view = composeTwoCallView({
		world: { dimension: 'overworld' },
		inventory: { items: [] },
	}, {
		lastDeath: { x: 1, y: 64, z: 1, dimensionId: 'minecraft:overworld' },
		lastLostInventory: [{ itemId: 'minecraft:iron_ingot', count: 8 }],
		alreadyHave: [],
		doNotRedo: [],
	});
	const option = view.options.find((entry) => entry.id === 'recover_corpse');
	assert.equal(option.moveTo.x, 1);
	assert.equal(option.feasible, true);
	assert.match(option.reason, /Go back or recraft/i);
});

test('other-dimension death does not offer recover_corpse', () => {
	const view = composeTwoCallView({
		world: { dimension: 'minecraft:overworld' },
		inventory: { items: [] },
	}, {
		lastDeath: { x: 1, y: 64, z: 1, dimensionId: 'minecraft:the_nether' },
		lastLostInventory: [{ itemId: 'minecraft:obsidian', count: 3 }],
		alreadyHave: [],
		doNotRedo: [],
	});
	assert.equal(view.options, undefined);
});

test('PATH_BLOCKED routes to explore_frontier', () => {
	const view = composeTwoCallView({
		lastResult: { state: 'FAILED', reasonCode: 'PATH_BLOCKED' },
		world: { dimension: 'minecraft:overworld' },
	}, null, { goal: 'Craft a stone pickaxe' });
	assert.equal(view.failureClass, 'explore');
	assert.ok(view.options.some((option) => option.id === 'explore_frontier'));
});

test('Beat Minecraft is an exploration goal', () => {
	const view = wrapTwoCallObservation(
		{ world: { dimension: 'minecraft:overworld' } },
		{},
		{ goal: 'Beat Minecraft' },
	);
	assert.ok(view.options.some((option) => option.id === 'explore_frontier'));
});

test('beat-the-game goals offer explore_frontier even with no lastResult', () => {
	const view = wrapTwoCallObservation(
		{ world: { dimension: 'minecraft:overworld' } },
		{},
		{ goal: 'Beat Minecraft: enter the Nether and find a fortress' },
	);
	assert.equal(view.failureClass, undefined);
	assert.ok(view.options.some((option) => option.id === 'explore_frontier' && option.seek === 'nether'));
});

test('local craft goals do not offer explore_frontier just because tools exist', () => {
	const view = composeTwoCallView({
		world: { dimension: 'minecraft:overworld' },
		inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
	}, {
		alreadyHave: ['minecraft:stone_pickaxe'],
		doNotRedo: ['minecraft:wooden_pickaxe'],
	}, { goal: 'Craft a stone pickaxe' });
	assert.equal(view.options?.some((option) => option.id === 'explore_frontier') === true, false);
});

test('NO_FRONTIER lastResult does not keep offering explore_frontier', () => {
	const view = composeTwoCallView({
		lastResult: { state: 'FAILED', reasonCode: 'NO_FRONTIER' },
		world: { dimension: 'minecraft:overworld' },
	}, null, { goal: 'Beat Minecraft' });
	assert.equal(view.failureClass, 'explore');
	assert.equal(view.options?.some((option) => option.id === 'explore_frontier') === true, false);
});

test('visible nether cue offers interact_cue and names the cue in facts', () => {
	const occupancy = new ExplorationOccupancy();
	const observation = {
		world: { dimension: 'minecraft:overworld' },
		position: { x: 0, y: 64, z: 0 },
		player: { x: 0, y: 64, z: 0 },
		blocks: [{ blockId: 'minecraft:netherrack', x: 1, y: 64, z: 0 }],
	};
	const view = composeTwoCallView(observation, null, {
		occupancy,
		agentId: 'lucas',
		goal: 'Beat Minecraft',
	});
	assert.ok(view.options.some((option) => option.id === 'interact_cue'));
	assert.match(view.recovery.facts, /Visible cue: minecraft:netherrack/i);
});

test('decorate after death keeps recovery on the next observe', () => {
	const wrap = new TwoCallLlmWrap();
	wrap.ingest('lucas', {
		player: { dead: false, x: 12, y: 64, z: -8 },
		inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
		blocks: [{ blockId: 'minecraft:crafting_table', x: 11, y: 64, z: -8 }],
		world: { dimension: 'minecraft:overworld' },
	}, { goalRevision: 1, goal: 'Beat Minecraft' });
	wrap.ingest('lucas', {
		death: DEATH,
		player: { dead: true },
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
	}, { goalRevision: 1, goal: 'Beat Minecraft' });
	const next = wrap.decorate('lucas', {
		inventory: { items: [] },
		player: { dead: false, health: 20, x: 2, y: 64, z: 2 },
		world: { dimension: 'minecraft:overworld' },
	}, { goal: 'Beat Minecraft' });
	assert.equal(next.recovery.lastDeath.x, 12);
	assert.equal(next.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.equal(next.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
	assert.ok(next.options.some((option) => option.id === 'recover_corpse'));
});

test('wrap never dispatches a Minecraft command itself', () => {
	const wrap = new TwoCallLlmWrap();
	const view = wrap.ingestAndDecorate('lucas', {
		lastResult: { state: 'FAILED', reasonCode: 'PATH_BLOCKED' },
		death: DEATH,
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
		position: { x: 0, y: 64, z: 0 },
		player: { x: 0, y: 64, z: 0 },
		blocks: [{ blockId: 'minecraft:obsidian', x: 8, y: 64, z: 0 }],
	}, { goalRevision: 1, goal: 'Beat Minecraft' });
	assert.ok(Array.isArray(view.options));
	assert.equal(Object.getOwnPropertyNames(wrap).some((name) => name.toLowerCase().includes('send')), false);
	assert.equal(typeof wrap.bridge, 'undefined');
});

test('classifyBodyFailure keeps success codes out of failureClass', () => {
	assert.equal(classifyBodyFailure('PATH_BLOCKED', 'FAILED'), 'explore');
	assert.equal(classifyBodyFailure('PLAYER_DEAD', 'FAILED'), 'recover');
	assert.equal(classifyBodyFailure('AGENT_DEAD', 'FAILED'), 'recover');
	assert.equal(classifyBodyFailure('TARGET_NOT_VISIBLE', 'FAILED'), 'replan');
	assert.equal(classifyBodyFailure('RECIPE_NOT_FOUND', 'FAILED'), 'skip');
	assert.equal(classifyBodyFailure('BLOCK_BROKEN', 'SUCCEEDED'), null);
	assert.equal(classifyBodyFailure('CUE_IN_VIEW', 'SUCCEEDED'), null);
});

test('End portal goals prefer structure exploration over Nether exploration', () => {
	assert.equal(inferFrontierSeek('Find the End portal'), 'structure');
	assert.equal(inferFrontierSeek('Find a Nether portal'), 'nether');
	assert.equal(inferFrontierSeek('locate a stronghold and End portal'), 'structure');
	assert.equal(inferFrontierSeek('Beat Minecraft: enter the Nether and find a fortress'), 'nether');
});
