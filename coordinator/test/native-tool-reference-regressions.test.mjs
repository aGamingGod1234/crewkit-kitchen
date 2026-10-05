import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { MINECRAFT_DYNAMIC_TOOLS, minecraftCapabilities, normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { ARENA_SCRIPT_API_REFERENCE, SCRIPT_ACTION_REFERENCE } from '../src/prompts.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { createInterpreterFacts } from '../src/arena-script/facts.mjs';
import { SCRIPT_BINDINGS } from '../src/arena-script/minecraft-api.mjs';
import { validateAction } from '../src/schema.mjs';

const reference = readFileSync(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/control-reference.md', import.meta.url), 'utf8');

test('every advertised native reference example passes the normalization boundary', () => {
	const examples = [...reference.matchAll(/```json executor-call\r?\n([\s\S]*?)```/g)].map(match => JSON.parse(match[1]));
	assert.ok(examples.length > 40);
	for (const example of examples) {
		const result = normalizeMinecraftToolCall(example.tool, example.arguments);
		if (result.kind === 'action') validateAction({ type: result.actionType, ...result.arguments });
		if (result.kind === 'sequence') for (const action of result.actions) validateAction({ type: action.actionType, ...action.arguments });
	}
});

test('focused sequence reference advertises finish with an accepted executable call', () => {
	const { reference: topic } = minecraftCapabilities({ section: 'control', topic: 'tool:sequence' });
	assert.match(topic, /after every step succeeds and fresh final facts are available/);
	assert.match(topic, /failed step skips finish/);
	assert.match(topic, /AWAITING_OPERATOR_CONFIRMATION/);
	const example = [...topic.matchAll(/```json executor-call\s*([\s\S]*?)```/g)].map(match => JSON.parse(match[1])).find(call => call.arguments.finish);
	assert.ok(example);
	const result = normalizeMinecraftToolCall(example.tool, example.arguments);
	assert.deepEqual(result.finish, example.arguments.finish);
	assert.equal(result.kind, 'sequence');
});

test('ArenaScript movement documentation distinguishes moveTo from timed navigateTo', () => {
	assert.match(ARENA_SCRIPT_API_REFERENCE, /moveTo and navigateTo require tolerance 0\.01\.\.16 and sprint boolean; only navigateTo accepts timeoutMs \(required, 1\.\.600000\)/);
	assert.match(SCRIPT_ACTION_REFERENCE, /player\.moveTo\(\{ x, y, z, tolerance, sprint \}\)/);
	assert.match(SCRIPT_ACTION_REFERENCE, /player\.navigateTo\(\{ x, y, z, tolerance, sprint, timeoutMs \}\)/);
	for (const [member, primitive, args] of [
		['moveTo', 'move_to', { x: 1, y: 64, z: 2, tolerance: 1, sprint: true }],
		['navigateTo', 'navigate_to', { x: 1, y: 64, z: 2, tolerance: 1, sprint: true, timeoutMs: 240_000 }],
	]) {
		const vm = new ArenaScriptInterpreter(parseArenaScript(`program.onUnhandledAttention("pause_and_notify"); await player.${member}(${JSON.stringify(args)});`), SCRIPT_BINDINGS);
		const step = vm.start(createInterpreterFacts({ player: { health: 20 }, inventory: { items: [] }, blocks: [], entities: [], items: [] }));
		assert.equal(step.kind, 'command');
		assert.equal(step.call.primitive, primitive);
		assert.deepEqual({ ...step.call.arguments }, args);
		assert.doesNotThrow(() => validateAction({ type: primitive, ...step.call.arguments }));
	}
});

test('postAction tools point to one complete observation-view contract without changing schemas', () => {
	const observe = MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === 'observe');
	for (const phrase of ['observationView.id', 'afterObservationId', 'exact delivered ID', 'observationView.replace and remove', 'stale or different-world', 'view:"full"']) assert.ok(observe.description.includes(phrase), phrase);
	for (const name of ['moveTo', 'mine', 'act', 'sequence']) {
		const tool = MINECRAFT_DYNAMIC_TOOLS.find(tool => tool.name === name);
		assert.match(tool.description, /shared observation-view contract described by observe/);
		assert.match(tool.description, /receipt\/history coverage and omission markers are unchanged/);
		assert.doesNotMatch(tool.description, /unknown baselines return full/);
		assert.deepEqual(tool.inputSchema.properties.view, observe.inputSchema.properties.view);
		assert.deepEqual(tool.inputSchema.properties.afterObservationId, observe.inputSchema.properties.afterObservationId);
	}
});
