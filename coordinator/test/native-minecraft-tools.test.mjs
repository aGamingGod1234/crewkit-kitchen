import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
	MINECRAFT_DYNAMIC_TOOLS,
	NATIVE_AGENT_INSTRUCTIONS,
	normalizeMinecraftToolCall,
	toolResultContent,
} from '../src/native-minecraft-tools.mjs';

test('Minecraft control guidance examples are valid executor tool calls', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', import.meta.url), 'utf8');
	const turns = [...skill.matchAll(/```json executor-calls\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	const calls = [...skill.matchAll(/```json executor-call\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	assert.ok(calls.length >= 5, 'expected at least five executor-call examples');
	assert.ok(turns.some(({ calls: turnCalls }) => turnCalls.length >= 2), 'expected a multi-call turn example');

	const normalized = [...calls, ...turns.flatMap(({ calls: turnCalls }) => turnCalls)].map(({ tool, arguments: args }) => ({
		tool,
		result: normalizeMinecraftToolCall(tool, args),
	}));
	assert.ok(normalized.some(({ tool }) => tool === 'say'));
	assert.ok(normalized.some(({ tool }) => tool === 'mine'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'pick_up_item'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'craft_inventory'));
	assert.ok(normalized.some(({ tool }) => tool === 'finish'));
});

test('Minecraft control reference covers every executor tool and action with accepted and rejected examples', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', import.meta.url), 'utf8');
	const parseExamples = (label) => [...skill.matchAll(new RegExp('```json ' + label + '\\s+([\\s\\S]*?)```', 'g'))]
		.map((match) => JSON.parse(match[1]));
	const goodCalls = parseExamples('executor-call');
	const badCalls = parseExamples('executor-bad-call');
	const expectedTools = MINECRAFT_DYNAMIC_TOOLS.map(({ name }) => name);
	const expectedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum;

	assert.ok(goodCalls.length >= expectedTools.length + expectedActions.length, 'expected one direct good example per tool and action');
	assert.deepEqual([...new Set(goodCalls.map(({ tool }) => tool))].sort(), [...expectedTools].sort());
	assert.deepEqual(
		[...new Set(goodCalls.filter(({ tool }) => tool === 'act').map(({ arguments: args }) => args.actionType))].sort(),
		[...expectedActions].sort(),
	);
	for (const { tool, arguments: args } of goodCalls) normalizeMinecraftToolCall(tool, args);
	assert.ok(badCalls.length >= expectedTools.length + expectedActions.length, 'expected at least one rejected example per tool and action');
	for (const { tool, arguments: args } of badCalls) {
		assert.throws(() => normalizeMinecraftToolCall(tool, args), (error) => (
			error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS' || error?.code === 'UNKNOWN_MINECRAFT_TOOL'
		));
	}
});

test('native Minecraft tools expose the common fast path plus one validated advanced body operation', () => {
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS.map((tool) => tool.name), [
		'observe', 'lookAround', 'control', 'moveTo', 'exploreFrontier', 'mine', 'say', 'wait', 'act', 'sequence', 'finish',
	]);
	assert.ok(MINECRAFT_DYNAMIC_TOOLS.every((tool) => tool.type === 'function'));
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /Act as soon as it is safe/i);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'sequence').description, /Prefer sequence for safe 2\+ action chains/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /speech playback is asynchronous/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /exploreFrontier/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /death is the same goal/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /visible landmarks/i);
});

test('advertised native actions exactly match Java model-authored dispatch', async () => {
	const executor = await readFile(new URL('../../src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java', import.meta.url), 'utf8');
	const allowlist = executor.match(/ARENA_SCRIPT_PRIMITIVES\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1] ?? '';
	const javaActions = [...allowlist.matchAll(/ActionType\.([A-Z_]+)/g)]
		.map(([, name]) => name.toLowerCase())
		.sort();
	const advertisedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum
		.toSorted();

	assert.deepEqual(advertisedActions, javaActions);
	assert.ok(advertisedActions.includes('pick_up_item'), 'working Java pickup controller remains reachable');
	for (const unsupportedComposite of ['build_sequence', 'fight_target', 'flee_from', 'follow_entity']) {
		assert.ok(!advertisedActions.includes(unsupportedComposite), `${unsupportedComposite} is not advertised without native dispatch`);
	}
});

test('native Minecraft tool calls normalize to exact existing body actions', () => {
	assert.deepEqual(normalizeMinecraftToolCall('control', {
		forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true,
		attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20,
	}), {
		kind: 'action', actionType: 'control',
		arguments: { forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true, attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('lookAround', {
		centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	}), {
		kind: 'lookAround', centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	});
	assert.deepEqual(normalizeMinecraftToolCall('moveTo', { x: 1, y: 64, z: -2 }), {
		kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: -2, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', {}), {
		kind: 'explore_frontier', arguments: { seek: 'any', radius: 24, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', { seek: 'nether', radius: 24, timeoutMs: 15_000 }), {
		kind: 'explore_frontier', arguments: { seek: 'nether', radius: 24, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('mine', { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone' }), {
		kind: 'action', actionType: 'break_block', arguments: { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'hi', recipientId: 'agent-b' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'hi', audience: 'direct', recipientId: 'agent-b' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'On it.', audience: 'proximity' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'On it.', audience: 'proximity' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('finish', {
		summary: 'Stone acquired.',
	}), {
		kind: 'finish', summary: 'Stone acquired.',
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	}), {
		kind: 'action', actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	}), {
		kind: 'action', actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('sequence', {
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone' } },
		],
	}), {
		kind: 'sequence',
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
		],
	});
});

test('native Minecraft boundary rejects unknown, oversized, and malformed calls', () => {
	assert.throws(() => normalizeMinecraftToolCall('attack', {}), (error) => error?.code === 'UNKNOWN_MINECRAFT_TOOL');
	assert.throws(() => normalizeMinecraftToolCall('moveTo', { x: '1', y: 2, z: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('mine', { x: 1, y: 64, z: 2 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('control', { forward: 1 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 1, ticksPerStep: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'x'.repeat(257) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'direct' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'proximity', recipientId: 'agent-b' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('finish', { summary: 'done', completionContract: {} }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:oak_planks' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'pick_up_item', arguments: { targetSelector: 'nearest_item' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'fight_target', arguments: { targetSelector: 'zombie', desiredRange: 20, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'build_sequence', arguments: { placements: [], timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'flee_from', arguments: { targetSelector: 'target', distance: 8, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'follow_entity', arguments: { targetSelector: 'target', distance: 3, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }] }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: Array.from({ length: 9 }, () => ({ actionType: 'wait', arguments: { durationMs: 1 } })) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
});

test('advanced actions cannot override their discriminator through nested arguments', () => {
	for (const type of ['wait', 'attack']) {
		const action = { actionType: 'attack', arguments: { type, durationMs: 100 } };
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('all native mining paths reject air before dispatch', () => {
	for (const expectedBlockId of ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']) {
		const args = { x: 2, y: 63, z: 4, expectedBlockId, timeoutMs: 15_000 };
		const action = { actionType: 'break_block', arguments: args };
		assert.throws(() => normalizeMinecraftToolCall('mine', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('tool results are compact deterministic inputText content', () => {
	assert.deepEqual(toolResultContent({ state: 'SUCCEEDED', reasonCode: '' }), {
		success: true,
		contentItems: [{ type: 'inputText', text: '{"state":"SUCCEEDED","reasonCode":""}' }],
	});
	assert.match(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text, /TRUNCATED/);
	assert.equal(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text.length <= 16_384, true);
	const truncatedDeath = toolResultContent({
		observation: {
			player: { health: 0, dead: true },
			inventory: { items: Array.from({ length: 64 }, (_, index) => ({ itemId: `minecraft:filler_${index}`, count: 64 })) },
			death: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
			recovery: {
				lastDeath: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Current inventory is empty. Lost on death: minecraft:stone_pickaxe.',
			},
			failureClass: 'recover',
			world: { dimension: 'minecraft:overworld' },
			blocks: Array.from({ length: 400 }, (_, index) => ({ blockId: 'minecraft:stone', x: index, y: 64, z: 0 })),
		},
	});
	assert.match(truncatedDeath.contentItems[0].text, /lastDeath/);
	assert.match(truncatedDeath.contentItems[0].text, /alreadyHave/);
	assert.match(truncatedDeath.contentItems[0].text, /stone_pickaxe/);
	assert.doesNotMatch(truncatedDeath.contentItems[0].text, /"state":"TRUNCATED"/);
	const oversizedIds = {
		observation: {
			recovery: {
				lastLostInventory: Array.from({ length: 16 }, (_, index) => ({
					itemId: `minecraft:${'a'.repeat(240)}_${index}`,
					count: 64,
				})),
				alreadyHave: Array.from({ length: 32 }, (_, index) => `minecraft:${'b'.repeat(240)}_${index}`),
				doNotRedo: Array.from({ length: 24 }, (_, index) => `minecraft:${'c'.repeat(240)}_${index}`),
				facts: 'f'.repeat(8_000),
			},
		},
	};
	const bounded = toolResultContent(oversizedIds);
	assert.equal(bounded.contentItems[0].text.length <= 16_384, true);
	assert.ok(Buffer.byteLength(bounded.contentItems[0].text, 'utf8') <= 16_384);
});

test('tool result byte cap still applies when survival facts are oversized', () => {
	const text = toolResultContent({
		observation: {
			death: { cause: 'lava', x: 1, y: 64, z: 2, dimensionId: 'minecraft:overworld' },
			recovery: { facts: 'x'.repeat(40_000) },
		},
	}).contentItems[0].text;
	assert.equal(Buffer.byteLength(text, 'utf8') <= 16_384, true);
});

test('oversized sequence results retain every authoritative step status', () => {
	const content = toolResultContent({
		state: 'SUCCEEDED', completed: 8,
		results: Array.from({ length: 8 }, (_, index) => ({
			actionType: 'break_block', state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}`,
			actionObservation: { detail: 'x'.repeat(8_000), step: index + 1 },
		})),
	});
	assert.equal(content.contentItems[0].text.length <= 16_384, true);
	const result = JSON.parse(content.contentItems[0].text);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.results.length, 8);
	assert.deepEqual(result.results.map(({ state, reasonCode }) => ({ state, reasonCode })), Array.from({ length: 8 }, (_, index) => ({ state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}` })));
});
