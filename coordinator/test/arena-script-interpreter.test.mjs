import assert from 'node:assert/strict';
import test from 'node:test';

import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

const ACTION_BINDINGS = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		moveTo: Object.freeze(Object.assign(Object.create(null), { primitive: 'move_to' })),
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
	})),
}));

function interpreter(source, limits = undefined) {
	return new ArenaScriptInterpreter(parseArenaScript(source), ACTION_BINDINGS, { limits });
}

function facts(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20, ...overrides.player },
		world: { ...overrides.world },
		inventory: { tagCounts: { ...overrides.inventory?.tagCounts } },
	};
}

function actionResult(command, state = 'SUCCEEDED', reasonCode = 'DONE') {
	return { stateToken: command.stateToken, state, reasonCode };
}

function boundedCounterSource() {
	return `
		program.onUnhandledAttention("continue_and_notify");
		let total = 0;
		for (let index = 0; index < 4; index += 1) total += index;
		program.finish("counted");
	`;
}

test('yields a command and resumes from its typed result', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		const moved = await tryResult(player.moveTo({ x: 4, y: 64, z: 2 }));
		if (!moved.succeeded) program.checkpoint(moved.reason);
		program.finish("arrived");
	`);
	const first = vm.start(facts());
	assert.equal(first.kind, 'command');
	assert.equal(first.call.primitive, 'move_to');
	assert.deepEqual(Object.fromEntries(Object.entries(first.call.arguments)), { x: 4, y: 64, z: 2 });
	assert.equal(typeof first.stateToken, 'string');
	const second = vm.resume(actionResult(first, 'SUCCEEDED', 'ARRIVED'), facts({ player: { x: 4 } }));
	assert.equal(second.kind, 'finish');
	assert.equal(second.summary, 'arrived');
});

test('failed typed action results checkpoint with their stable reason', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		const moved = await tryResult(player.moveTo({ x: 4, y: 64, z: 2 }));
		if (!moved.succeeded) program.checkpoint(moved.reason);
		program.finish("unreachable");
	`);
	const first = vm.start(facts());
	const result = vm.resume(actionResult(first, 'FAILED', 'BLOCKED'), facts());
	assert.equal(result.kind, 'checkpoint');
	assert.equal(result.reason, 'BLOCKED');
});

test('operation exhaustion checkpoints instead of ending the goal', () => {
	const vm = interpreter(boundedCounterSource(), { operationsPerResume: 4 });
	assert.throws(() => vm.start(facts()), (error) => error.code === 'OPERATION_LIMIT');
});

test('runs a registered watcher only when its condition is true', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(
			() => player.state().health < 10,
			{ mode: "boundary" },
			async () => { program.finish("heal"); }
		);
	`);
	assert.deepEqual(vm.start(facts()), { kind: 'idle' });
	assert.deepEqual(vm.runWatcher('watcher-0', facts()), { kind: 'idle' });
	const fired = vm.runWatcher('watcher-0', facts({ player: { health: 8 } }));
	assert.equal(fired.kind, 'finish');
	assert.equal(fired.summary, 'heal');
});

test('executes local functions, safe local records, bounded iteration, and repeatUntil', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		function offset(point) { return point.x + 1; }
		const next = offset({ x: 3 });
		let total = 0;
		for (let value = 1; value <= 3; value += 1) total += value;
		await program.repeatUntil(
			() => total >= 8,
			{ maxIterations: 2 },
			async () => { total += 1; }
		);
		if (next === 4 && total === 8) program.finish("complete");
	`);
	const result = vm.start(facts());
	assert.equal(result.kind, 'finish');
	assert.equal(result.summary, 'complete');
});

test('enforces command and loop execution bounds', () => {
	const commandVm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await player.wait(1);
		await player.wait(1);
		program.finish("later");
	`, { commandsPerProgram: 1 });
	const command = commandVm.start(facts());
	assert.equal(command.kind, 'command');
	assert.throws(() => commandVm.resume(actionResult(command), facts()), (error) => error.code === 'COMMAND_LIMIT');

	const loopVm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		for (let index = 0; index < 128; index += 1) { }
		program.finish("done");
	`, { loopIterationsPerYield: 4 });
	assert.throws(() => loopVm.start(facts()), (error) => error.code === 'LOOP_LIMIT');
});

test('does not execute syntax or object escape hatches outside the compiled contract', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); this;',
		'program.onUnhandledAttention("continue_and_notify"); new Date();',
		'program.onUnhandledAttention("continue_and_notify"); player.state().prototype;',
		'program.onUnhandledAttention("continue_and_notify"); unknownGlobal;',
		'program.onUnhandledAttention("continue_and_notify"); player.state()["health"];',
		'program.onUnhandledAttention("continue_and_notify"); player.state().health = 0;',
		'program.onUnhandledAttention("continue_and_notify"); function again() { again(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 129; index += 1) {}',
	]) {
		assert.throws(
			() => interpreter(source),
			(error) => ['UNSUPPORTED_SYNTAX', 'UNSAFE_MEMBER_ACCESS', 'RECURSION_FORBIDDEN', 'UNBOUNDED_LOOP'].includes(error.code),
		);
	}
});

test('rejects stale, duplicate, and out-of-order state tokens without losing the waiting continuation', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await player.wait(1);
		await player.wait(1);
		program.finish("done");
	`);
	const first = vm.start(facts());
	let staleFactReads = 0;
	const staleFacts = Object.defineProperty({}, 'player', { enumerable: true, get() { staleFactReads += 1; return {}; } });
	assert.throws(() => vm.resume({ ...actionResult(first), stateToken: 'arena-state-stale' }, staleFacts), (error) => error.code === 'STALE_STATE_TOKEN');
	assert.equal(staleFactReads, 0);
	const second = vm.resume(actionResult(first), facts());
	assert.equal(second.kind, 'command');
	assert.throws(() => vm.resume(actionResult(first), facts()), (error) => error.code === 'STALE_STATE_TOKEN');
	const finished = vm.resume(actionResult(second), facts());
	assert.equal(finished.kind, 'finish', 'the current continuation remains resumable after stale input');
	assert.equal(finished.summary, 'done');
});

test('yields deeply frozen null-prototype command records and rejects forbidden command keys', () => {
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.moveTo({ x: 1, nested: { y: 2 } });');
	const command = vm.start(facts());
	assert.equal(Object.getPrototypeOf(command), null);
	assert.equal(Object.getPrototypeOf(command.call), null);
	assert.equal(Object.getPrototypeOf(command.call.arguments), null);
	assert.ok(Object.isFrozen(command));
	assert.ok(Object.isFrozen(command.call));
	assert.ok(Object.isFrozen(command.call.arguments));
	assert.ok(Object.isFrozen(command.call.arguments.nested));
	assert.throws(() => { command.call.primitive = 'escaped'; }, TypeError);
	assert.throws(() => { command.call.arguments.nested.y = 9; }, TypeError);
	for (const key of ['__proto__', 'constructor', 'prototype']) {
		assert.throws(() => interpreter(`program.onUnhandledAttention("continue_and_notify"); await player.moveTo({ ${key}: 1 });`), (error) => error.code === 'UNSAFE_MEMBER_ACCESS');
	}
});

test('rejects accessor, inherited, symbol, proxy, and non-schema action bindings without invoking getters', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify");');
	let reads = 0;
	const accessor = Object.freeze(Object.defineProperty(Object.create(null), 'player', { enumerable: true, get() { reads += 1; return ACTION_BINDINGS.player; } }));
	assert.throws(() => new ArenaScriptInterpreter(compiled, accessor), (error) => error.code === 'INVALID_BINDINGS');
	assert.equal(reads, 0);

	const inherited = Object.freeze(Object.create(ACTION_BINDINGS));
	assert.throws(() => new ArenaScriptInterpreter(compiled, inherited), (error) => error.code === 'INVALID_BINDINGS');
	const symbolic = Object.freeze(Object.assign(Object.create(null), { ...ACTION_BINDINGS, [Symbol('binding')]: 1 }));
	assert.throws(() => new ArenaScriptInterpreter(compiled, symbolic), (error) => error.code === 'INVALID_BINDINGS');
	const proxy = new Proxy(ACTION_BINDINGS, {});
	assert.throws(() => new ArenaScriptInterpreter(compiled, proxy), (error) => error.code === 'INVALID_BINDINGS');
});

test('normalizes only exact own-data facts and action results without executing accessors', () => {
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	let factReads = 0;
	const badFacts = Object.defineProperty({}, 'player', { enumerable: true, get() { factReads += 1; return {}; } });
	assert.throws(() => vm.start(badFacts), (error) => error.code === 'INVALID_FACTS');
	assert.equal(factReads, 0);
	const command = vm.start(facts());
	let resultReads = 0;
	const accessorResult = Object.defineProperty({}, 'stateToken', { enumerable: true, get() { resultReads += 1; return command.stateToken; } });
	assert.throws(() => vm.resume(accessorResult, facts()), (error) => error.code === 'INVALID_ACTION_RESULT');
	assert.equal(resultReads, 0);
	assert.throws(() => vm.resume({ state: 'SUCCEEDED', reasonCode: 'DONE' }, facts()), (error) => error.code === 'INVALID_ACTION_RESULT');
	for (const result of [
		{ stateToken: command.stateToken, state: 'SUCCEEDED' },
		{ stateToken: command.stateToken, state: 'UNKNOWN', reasonCode: 'NOPE' },
		{ stateToken: command.stateToken, state: 'SUCCEEDED', reasonCode: 'DONE', extra: true },
	]) assert.throws(() => vm.resume(result, facts()), (error) => error.code === 'INVALID_ACTION_RESULT');
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify"); program.finish({});').start(facts()), (error) => error.code === 'INVALID_TERMINAL_VALUE');
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify");').start({ player: {}, world: {}, inventory: { tagCounts: {} }, extra: true }), (error) => error.code === 'INVALID_FACTS');
});

test('keeps finished and checkpointed programs inactive for watcher execution', () => {
	for (const terminal of ['program.finish("done")', 'program.checkpoint("paused")']) {
		const vm = interpreter(`
			program.onUnhandledAttention("continue_and_notify");
			program.watch(() => true, { mode: "boundary" }, async () => { program.finish("watch"); });
			${terminal};
		`);
		vm.start(facts());
		assert.throws(() => vm.runWatcher('watcher-0', facts()), (error) => error.code === 'INACTIVE_LIFECYCLE');
	}
});

test('resets per-slice loop budget after each valid command result while retaining repeatUntil maximum', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 3 }, async () => { await player.wait(1); });
	`, { loopIterationsPerYield: 1 });
	const first = vm.start(facts());
	const second = vm.resume(actionResult(first), facts());
	const third = vm.resume(actionResult(second), facts());
	const exhausted = vm.resume(actionResult(third), facts());
	assert.equal(exhausted.kind, 'checkpoint');
	assert.equal(exhausted.reason, 'repeat_until_exhausted');
});

test('does not poison lifecycle when start rejects invalid facts', () => {
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	assert.throws(() => vm.start(null), (error) => error.code === 'INVALID_FACTS');
	assert.equal(vm.start(facts()).kind, 'command');
});

test('rejects assignment and updates of initialized const bindings', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const total = 1; total += 1;',
		'program.onUnhandledAttention("continue_and_notify"); const total = 1; total++;',
	]) assert.throws(() => interpreter(source).start(facts()), (error) => error.code === 'CONST_ASSIGNMENT');
});
