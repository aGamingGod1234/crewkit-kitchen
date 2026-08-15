import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';

test('compiles a bounded program and records its model-owned policy', () => {
	const compiled = parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(
			() => inventory.countTag("#minecraft:logs") >= 8,
			{ maxIterations: 16 },
			async () => { await player.wait(1); }
		);
	`);
	assert.equal(compiled.unhandledPolicy, 'continue_and_notify');
	assert.equal(compiled.watcherCount, 0);
	assert.ok(compiled.nodeCount > 0);
	assert.ok(compiled.stepLocations.size > 0);
	assert.ok(Object.isFrozen(compiled));
	assert.ok(Object.isFrozen(compiled.ast));
	assert.ok(Object.isFrozen(compiled.stepLocations));
});

test('accepts model-authored locals, conditionals, bounded for loops, and watchers', () => {
	const compiled = parseArenaScript(`
		program.onUnhandledAttention("pause_and_notify");
		const limit = 3;
		let total = 0;
		program.watch(
			() => player.state().health < 10,
			{ mode: "interrupt" },
			async () => { await player.wait(1); }
		);
		for (let index = 0; index < 3; index += 1) {
			if (index > 1) total += 1;
		}
	`);
	assert.equal(compiled.unhandledPolicy, 'pause_and_notify');
	assert.equal(compiled.watcherCount, 1);
});

test('rejects source over the configured byte limit', () => {
	assert.throws(
		() => parseArenaScript('x'.repeat(20), { limits: { sourceBytes: 4 } }),
		(error) => error.code === 'SOURCE_TOO_LARGE' && error.name === 'ArenaScriptError',
	);
});

test('rejects malformed source with a stable syntax error', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify";'),
		(error) => error.code === 'SYNTAX_ERROR' && error.name === 'ArenaScriptError',
	);
});

test('rejects unsupported syntax and ASTs over the configured limit', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); class Secret {}'),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); 1 + 2;', { limits: { astNodes: 3 } }),
		(error) => error.code === 'AST_TOO_LARGE',
	);
});

test('requires exactly one supported unhandled-attention policy', () => {
	assert.throws(
		() => parseArenaScript('const value = 1;'),
		(error) => error.code === 'MISSING_UNHANDLED_POLICY',
	);
	assert.throws(
		() => parseArenaScript(`
			program.onUnhandledAttention("continue_and_notify");
			program.onUnhandledAttention("pause_and_notify");
		`),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("ignore");'),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
});

test('rejects computed and prototype member access', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); player["constructor"];',
		'program.onUnhandledAttention("continue_and_notify"); player.constructor;',
		'program.onUnhandledAttention("continue_and_notify"); globalThis.process.exit(0);',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});

test('rejects unbounded loops and repeatUntil without a literal bound', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); while (true) {}',
		'program.onUnhandledAttention("continue_and_notify"); for (;;) {}',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, {}, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, { maxIterations: limit }, async () => {});',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects recursive local function call graphs', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { again(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); const first = () => second(); const second = () => first(); first();',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'RECURSION_FORBIDDEN',
		);
	}
});

test('enforces watcher count and records source locations', () => {
	const watchers = Array.from({ length: 2 }, (_, index) => `program.watch(() => ${index === 0 ? 'true' : 'false'}, { mode: "boundary" }, async () => {});`).join('\n');
	const compiled = parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); ${watchers}`);
	assert.equal(compiled.watcherCount, 2);
	const [stepId, location] = compiled.stepLocations.entries().next().value;
	assert.match(stepId, /^step-\d+-\d+$/);
	assert.equal(typeof location.start, 'number');
	assert.equal(typeof location.end, 'number');
	assert.equal(typeof location.line, 'number');
	assert.equal(typeof location.column, 'number');

	const tooMany = Array.from({ length: 3 }, () => 'program.watch(() => true, { mode: "boundary" }, async () => {});').join('\n');
	assert.throws(
		() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); ${tooMany}`, { limits: { watchers: 2 } }),
		(error) => error.code === 'TOO_MANY_WATCHERS',
	);
});

for (const source of [
	'import fs from "node:fs";',
	'globalThis.process.exit(0);',
	'player["constructor"];',
	'while (true) {}',
	'function again() { again(); } again();',
]) {
	test(`rejects unsafe source: ${source}`, () => {
		assert.throws(() => parseArenaScript(source), /ArenaScript|policy|unsupported|unsafe|bounded|recursion/i);
	});
}
