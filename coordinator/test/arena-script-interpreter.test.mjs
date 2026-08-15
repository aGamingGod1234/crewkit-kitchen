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
	assert.deepEqual(first.call, { primitive: 'move_to', arguments: { x: 4, y: 64, z: 2 } });
	assert.equal(typeof first.stateToken, 'string');
	const second = vm.resume({ state: 'SUCCEEDED', reasonCode: 'ARRIVED' }, facts({ player: { x: 4 } }));
	assert.deepEqual(second, { kind: 'finish', stepId: second.stepId, summary: 'arrived' });
});

test('failed typed action results checkpoint with their stable reason', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		const moved = await tryResult(player.moveTo({ x: 4, y: 64, z: 2 }));
		if (!moved.succeeded) program.checkpoint(moved.reason);
		program.finish("unreachable");
	`);
	vm.start(facts());
	const result = vm.resume({ state: 'FAILED', reasonCode: 'BLOCKED' }, facts());
	assert.deepEqual(result, { kind: 'checkpoint', stepId: result.stepId, reason: 'BLOCKED' });
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
	assert.deepEqual(fired, { kind: 'finish', stepId: fired.stepId, summary: 'heal' });
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
	assert.deepEqual(result, { kind: 'finish', stepId: result.stepId, summary: 'complete' });
});

test('enforces command and loop execution bounds', () => {
	const commandVm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await player.wait(1);
		await player.wait(1);
		program.finish("later");
	`, { commandsPerProgram: 1 });
	assert.equal(commandVm.start(facts()).kind, 'command');
	assert.throws(() => commandVm.resume({ state: 'SUCCEEDED' }, facts()), (error) => error.code === 'COMMAND_LIMIT');

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
