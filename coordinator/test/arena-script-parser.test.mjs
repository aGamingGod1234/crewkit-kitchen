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

test('rejects aliases of special program APIs', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const repeat = program.repeatUntil; await repeat(() => true, { maxIterations: 1 }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const watch = program.watch; watch(() => true, { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const setPolicy = program.onUnhandledAttention; setPolicy("pause_and_notify");',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects alias and parameter-mediated local function calls', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { const alias = again; alias(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again(fn) { fn(); } again(again);',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects unsafe or excessive literal for-loop bounds', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 9007199254740992; index <= 9007199254740992; index += 1) {}',
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 129; index += 1) {}',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects duplicate and forbidden loop option keys', () => {
	for (const options of [
		'{ maxIterations: 1, maxIterations: 2 }',
		'{ __proto__: 1, maxIterations: 1 }',
	]) {
		assert.throws(
			() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, ${options}, async () => {});`),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects a deeply nested AST with a stable parser error', () => {
	const source = `program.onUnhandledAttention("continue_and_notify"); ${'!'.repeat(300)}true;`;
	assert.throws(
		() => parseArenaScript(source),
		(error) => error.code === 'AST_TOO_LARGE' && error.name === 'ArenaScriptError',
	);
});

test('step locations cannot be mutated through Map.prototype', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify");');
	const size = compiled.stepLocations.size;
	assert.throws(() => Map.prototype.set.call(compiled.stepLocations, 'injected', {}), TypeError);
	assert.equal(compiled.stepLocations.size, size);
	assert.equal(compiled.stepLocations.get('injected'), undefined);
});

test('rejects callable indirection that bypasses direct recursion analysis', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { again.call(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again(box) { box.next(box); } const box = { next: again }; again(box);',
		'program.onUnhandledAttention("continue_and_notify"); function decoy() {} function again(decoy) { decoy(decoy); } again(again);',
		'program.onUnhandledAttention("continue_and_notify"); function decoy() {} function again(decoy) { return () => decoy(decoy); } again(again);',
		'program.onUnhandledAttention("continue_and_notify"); let safe = () => {}; const again = () => { safe = again; safe(); }; again();',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects watches that can execute more than once', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 17; index += 1) { program.watch(() => true, { mode: "boundary" }, async () => {}); }',
		'program.onUnhandledAttention("continue_and_notify"); function install() { program.watch(() => true, { mode: "boundary" }, async () => {}); } install();',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => false, { maxIterations: 1 }, async () => { program.watch(() => true, { mode: "boundary" }, async () => {}); });',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects forbidden keys in every object literal', () => {
	for (const key of ['__proto__', 'constructor', 'prototype']) {
		assert.throws(
			() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); const box = { ${key}: 1 };`),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});
