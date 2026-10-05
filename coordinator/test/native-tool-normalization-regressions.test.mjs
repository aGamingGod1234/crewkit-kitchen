import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_CHAT_LENGTH, MAX_DURATION_MS } from '../src/constants.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { validateAction } from '../src/schema.mjs';

const recipientId = '550e8400-e29b-41d4-a716-446655440000';
const wait = { actionType: 'wait', arguments: { durationMs: 1 } };
const invalid = { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' };

test('say rejects invalid recipients before dispatch and emits canonically valid chat', () => {
	for (const id of ['agent-b', 'nearest_player', '', recipientId.slice(1), `${recipientId}x`]) {
		assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', recipientId: id }), invalid);
	}
	for (const audience of ['public', 'proximity']) {
		assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience, recipientId }), invalid);
	}
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'direct' }), invalid);
	for (const args of [{ recipientId }, { audience: 'direct', recipientId }, {}, { audience: 'proximity' }]) {
		const result = normalizeMinecraftToolCall('say', { message: 'hi', ...args });
		assert.doesNotThrow(() => validateAction({ type: result.actionType, ...result.arguments }));
		assert.equal(result.arguments.audience, args.audience ?? (args.recipientId ? 'direct' : 'public'));
	}
	const schema = MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === 'say').inputSchema;
	assert.match(recipientId, new RegExp(schema.properties.recipientId.pattern));
	assert.doesNotMatch('agent-b', new RegExp(schema.properties.recipientId.pattern));
});

test('say measures its advertised limit in Unicode code points for every audience', () => {
	for (const args of [{}, { audience: 'direct', recipientId }, { audience: 'proximity' }]) {
		for (const message of ['😀'.repeat(200), '😀'.repeat(MAX_CHAT_LENGTH), `a${'😀'.repeat(MAX_CHAT_LENGTH - 1)}`]) {
			const result = normalizeMinecraftToolCall('say', { message, ...args });
			assert.equal(result.arguments.message, message);
			assert.doesNotThrow(() => validateAction({ type: 'chat', ...result.arguments }));
		}
		assert.throws(() => normalizeMinecraftToolCall('say', { message: '😀'.repeat(MAX_CHAT_LENGTH + 1), ...args }), invalid);
	}
	for (const message of ['', ' ', '\u00a0', '\u001c', 42, null]) {
		assert.throws(() => normalizeMinecraftToolCall('say', { message }), invalid);
	}
});

for (const [alias, actionType, base] of [
	['moveTo', 'navigate_to', { x: 1, y: 64, z: 2, tolerance: 1, sprint: true }],
	['mine', 'break_block', { x: 1, y: 64, z: 2, expectedBlockId: 'minecraft:stone' }],
]) {
	test(`${alias}, act, startAction, replaceAction and sequence use canonical duration bounds`, () => {
		const calls = args => [
			() => normalizeMinecraftToolCall(alias, args),
			() => normalizeMinecraftToolCall('act', { actionType, arguments: args }),
			() => normalizeMinecraftToolCall('startAction', { actionType, arguments: args }),
			() => normalizeMinecraftToolCall('replaceAction', { actionId: 'exact-handle', goalRevision: 3, actionType, arguments: args }),
			() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType, arguments: args }, wait] }).actions[0],
		];
		for (const timeoutMs of [1, 240_000, MAX_DURATION_MS]) {
			const args = { ...base, timeoutMs };
			const canonical = validateAction({ type: actionType, ...args });
			for (const call of calls(args)) {
				const result = call();
				assert.deepEqual({ type: result.actionType, ...result.arguments }, canonical);
			}
		}
		for (const timeoutMs of [0, -1, 0.5, MAX_DURATION_MS + 1, null, '240000']) {
			for (const call of calls({ ...base, timeoutMs })) assert.throws(call, invalid);
		}
		const schema = MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === alias).inputSchema;
		assert.equal(schema.properties.timeoutMs.maximum, MAX_DURATION_MS);
		assert.equal(schema.properties.timeoutMs.minimum, 1);
	});
}

test('action aliases retain defaults, exact fields, and explicit mine autoAim expansion', () => {
	const args = { x: 1, y: 64, z: 2 };
	const move = normalizeMinecraftToolCall('moveTo', args);
	assert.deepEqual(move.arguments, { ...args, tolerance: 1, sprint: true, timeoutMs: 30_000 });
	assert.deepEqual(normalizeMinecraftToolCall('act', { actionType: 'navigate_to', arguments: args }), move);
	const mining = { ...args, expectedBlockId: 'minecraft:stone', timeoutMs: MAX_DURATION_MS };
	const autoAim = normalizeMinecraftToolCall('mine', { ...mining, autoAim: true });
	assert.deepEqual(autoAim.actions.map(action => action.actionType), ['look_at', 'break_block']);
	assert.deepEqual(autoAim.actions[1].arguments, mining);
	for (const argumentsValue of [{ ...mining, autoAim: true }, { ...mining, type: 'wait' }]) {
		assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'break_block', arguments: argumentsValue }), invalid);
	}
});
