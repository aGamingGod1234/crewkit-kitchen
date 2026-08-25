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
		'observe', 'moveTo', 'mine', 'say', 'wait', 'act', 'sequence', 'finish',
	]);
	assert.ok(MINECRAFT_DYNAMIC_TOOLS.every((tool) => tool.type === 'function'));
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /Act as soon as it is safe/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /speech playback is asynchronous/i);
});

test('native Minecraft tool calls normalize to exact existing body actions', () => {
	assert.deepEqual(normalizeMinecraftToolCall('moveTo', { x: 1, y: 64, z: -2 }), {
		kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: -2, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('mine', { x: 2, y: 63, z: 4 }), {
		kind: 'action', actionType: 'break_block', arguments: { x: 2, y: 63, z: 4, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'hi', recipientId: 'agent-b' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'hi', audience: 'direct', recipientId: 'agent-b' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'On it.', audience: 'proximity' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'On it.', audience: 'proximity' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('finish', {
		status: 'completed', summary: 'Stone acquired.',
		completionContract: { goalRevision: 3, predicates: [{ type: 'inventory_min', itemId: 'minecraft:stone', count: 1 }] },
	}), {
		kind: 'finish', status: 'completed', summary: 'Stone acquired.',
		completionContract: { goalRevision: 3, predicates: [{ type: 'inventory_min', itemId: 'minecraft:stone', count: 1 }] },
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
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'fight_target',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000', desiredRange: 2, timeoutMs: 30_000 },
	}), {
		kind: 'action', actionType: 'fight_target',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000', desiredRange: 2, timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'build_sequence',
		arguments: { placements: [{ x: 1, y: 64, z: 2, face: 'up', itemId: 'minecraft:stone' }], timeoutMs: 30_000 },
	}), {
		kind: 'action', actionType: 'build_sequence',
		arguments: { placements: [{ x: 1, y: 64, z: 2, face: 'up', itemId: 'minecraft:stone' }], timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('sequence', {
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1 } },
		],
	}), {
		kind: 'sequence',
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, timeoutMs: 15_000 } },
		],
	});
});

test('native Minecraft boundary rejects unknown, oversized, and malformed calls', () => {
	assert.throws(() => normalizeMinecraftToolCall('attack', {}), (error) => error?.code === 'UNKNOWN_MINECRAFT_TOOL');
	assert.throws(() => normalizeMinecraftToolCall('moveTo', { x: '1', y: 2, z: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'x'.repeat(257) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'direct' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'proximity', recipientId: 'agent-b' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('finish', { status: 'completed', summary: 'done' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:oak_planks' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'pick_up_item', arguments: { targetSelector: 'nearest_item' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'fight_target', arguments: { targetSelector: 'zombie', desiredRange: 20, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'build_sequence', arguments: { placements: [], timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }] }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: Array.from({ length: 9 }, () => ({ actionType: 'wait', arguments: { durationMs: 1 } })) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
});

test('tool results are compact deterministic inputText content', () => {
	assert.deepEqual(toolResultContent({ state: 'SUCCEEDED', reasonCode: '' }), {
		success: true,
		contentItems: [{ type: 'inputText', text: '{"state":"SUCCEEDED","reasonCode":""}' }],
	});
	assert.match(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text, /TRUNCATED/);
	assert.equal(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text.length <= 16_384, true);
});
