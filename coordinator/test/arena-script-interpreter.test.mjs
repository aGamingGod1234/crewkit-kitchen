import assert from 'node:assert/strict';
import test from 'node:test';

import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

const ACTION_BINDINGS = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		moveTo: Object.freeze(Object.assign(Object.create(null), { primitive: 'move_to' })),
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
		attack: Object.freeze(Object.assign(Object.create(null), { primitive: 'attack' })),
		useRanged: Object.freeze(Object.assign(Object.create(null), { primitive: 'use_ranged' })),
		craftInventory: Object.freeze(Object.assign(Object.create(null), { primitive: 'craft_inventory' })),
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

test('emits exact stable target ids and rejects selector fallbacks', () => {
	const targetId = '00000000-0000-0000-0000-000000000001';
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		const target = world.nearest(world.entities());
		await player.attack({ targetId: target.stableId, timeoutMs: 1 });
		await player.useRanged({ targetId: target.stableId, drawDurationMs: 1, timeoutMs: 1 });
	`);
	const first = vm.start(facts({ world: { entities: [{ stableId: targetId, type: 'minecraft:zombie', x: 1, y: 64, z: 0 }] } }));
	assert.deepEqual(Object.fromEntries(Object.entries(first.call.arguments)), { targetId, timeoutMs: 1 });
	const second = vm.resume(actionResult(first), facts({ world: { entities: [{ stableId: targetId, type: 'minecraft:zombie', x: 1, y: 64, z: 0 }] } }));
	assert.equal(second.call.primitive, 'use_ranged');
	assert.deepEqual(Object.fromEntries(Object.entries(second.call.arguments)), { targetId, drawDurationMs: 1, timeoutMs: 1 });
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); await player.attack({ targetSelector: "nearest_hostile", timeoutMs: 1 });',
		'program.onUnhandledAttention("continue_and_notify"); await player.useRanged({ targetSelector: "nearest_hostile", drawDurationMs: 1, timeoutMs: 1 });',
	]) {
		assert.throws(() => interpreter(source), (error) => error.code === 'UNSUPPORTED_SYNTAX');
	}
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
	assert.equal(vm.start(facts()).kind, 'idle');
	assert.equal(vm.runWatcher('watcher-0', facts()).kind, 'idle');
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

test('requests model recovery after the same deterministic failure twice', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
			await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
		});
	`);
	const first = vm.start(facts());
	const second = vm.resume(actionResult(first, 'FAILED', 'RECIPE_NOT_FOUND'), facts());
	assert.equal(second.kind, 'command', 'one failure remains available to authored fallback logic');
	const stopped = vm.resume(actionResult(second, 'FAILED', 'RECIPE_NOT_FOUND'), facts());
	assert.equal(stopped.kind, 'replan');
	assert.equal(stopped.reason, 'repeated_action_failure:RECIPE_NOT_FOUND');
	assert.deepEqual({ ...stopped.failure, arguments: { ...stopped.failure.arguments } }, {
		actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:planks', count: 1, timeoutMs: 5000 },
		state: 'FAILED',
		reasonCode: 'RECIPE_NOT_FOUND',
	});
});

test('requests model recovery for repeated malformed actions and timeouts', () => {
	for (const [state, reasonCode] of [['FAILED', 'INVALID_ACTION'], ['TIMED_OUT', 'ACTION_TIMED_OUT']]) {
		const vm = interpreter(`
			program.onUnhandledAttention("continue_and_notify");
			await program.repeatUntil(() => false, { maxIterations: 4 }, async () => {
				await player.wait({ durationMs: 50 });
			});
		`);
		const first = vm.start(facts());
		const second = vm.resume(actionResult(first, state, reasonCode), facts());
		const stopped = vm.resume(actionResult(second, state, reasonCode), facts());
		assert.equal(stopped.kind, 'replan', `${reasonCode} stops an unchanged retry loop`);
		assert.equal(stopped.failure.state, state);
		assert.equal(stopped.failure.reasonCode, reasonCode);
	}
});

test('does not conflate changed action arguments at one source step', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 4 }, async () => {
			const recipeId = inventory.countTag("#minecraft:sticks") === 0 ? "minecraft:jungle_planks" : "minecraft:planks";
			await player.craftInventory({ recipeId, count: 1, timeoutMs: 5000 });
		});
	`);
	const first = vm.start(facts());
	const changedFacts = facts({ inventory: { tagCounts: { '#minecraft:sticks': 1 } } });
	const changed = vm.resume(actionResult(first, 'FAILED', 'RECIPE_NOT_FOUND'), changedFacts);
	assert.equal(changed.kind, 'command');
	assert.equal(changed.call.arguments.recipeId, 'minecraft:planks');
	const stopped = vm.resume(actionResult(changed, 'FAILED', 'RECIPE_NOT_FOUND'), changedFacts);
	assert.equal(stopped.kind, 'command', 'a changed command signature starts a fresh failure streak');
});

test('a different intervening command resets the deterministic failure streak', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
		await player.wait({ durationMs: 50 });
		await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
		program.finish("done");
	`);
	const first = vm.start(facts());
	const wait = vm.resume(actionResult(first, 'FAILED', 'RECIPE_NOT_FOUND'), facts());
	const secondCraft = vm.resume(actionResult(wait, 'SUCCEEDED', 'WAIT_COMPLETE'), facts());
	const finished = vm.resume(actionResult(secondCraft, 'FAILED', 'RECIPE_NOT_FOUND'), facts());
	assert.equal(finished.kind, 'finish', 'non-consecutive identical failures remain available to authored recovery');
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

test('bounds canonical facts, results, and command output without native stack overflow', () => {
	let deep = 0;
	for (let index = 0; index < 15_000; index += 1) deep = { child: deep };
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify");').start(facts({ player: { deep } })), (error) => error.code === 'FACT_LIMIT');
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify");').start(facts({ player: { values: Array.from({ length: 257 }, () => 0) } })), (error) => error.code === 'FACT_LIMIT');

	const resultVm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const command = resultVm.start(facts());
	assert.throws(() => resultVm.resume({ stateToken: command.stateToken, state: 'SUCCEEDED', reasonCode: 'x'.repeat(4_097) }, facts()), (error) => error.code === 'RESULT_LIMIT');

	const outputVm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.moveTo(player.state());');
	assert.throws(() => outputVm.start(facts({ player: { blob: 'x'.repeat(8_193) } })), (error) => error.code === 'OUTPUT_LIMIT');
});

test('accepts a full bounded Minecraft block observation above the old 16 KiB fact ceiling', () => {
	const blocks = Array.from({ length: 128 }, (_, index) => ({
		stableId: `${index},64,0`,
		blockId: 'minecraft:chiseled_copper',
		tags: [
			`#minecraft:mineable/pickaxe_${'long_path_'.repeat(8)}${index}`,
			`#minecraft:needs_stone_tool_${'bounded_tag_'.repeat(8)}${index}`,
		],
		x: index,
		y: 64,
		z: 0,
	}));
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); program.finish("observed");');
	assert.equal(vm.start(facts({ world: { blocks } })).kind, 'finish');
});

test('pauses execution errors and blocks watcher activation afterward', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => true, { mode: "boundary" }, async () => { program.finish("watch"); });
		for (let index = 0; index < 2; index += 1) { }
	`, { loopIterationsPerYield: 1 });
	assert.throws(() => vm.start(facts()), (error) => error.code === 'LOOP_LIMIT');
	assert.throws(() => vm.runWatcher('watcher-0', facts()), (error) => error.code === 'INACTIVE_LIFECYCLE');
});

test('requires exact player binding primitive mappings', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify");');
	for (const [member, primitive] of [['moveTo', 'fight_target'], ['wait', 'move_to']]) {
		const bindings = Object.freeze(Object.assign(Object.create(null), {
			player: Object.freeze(Object.assign(Object.create(null), {
				[member]: Object.freeze(Object.assign(Object.create(null), { primitive })),
			})),
		}));
		assert.throws(() => new ArenaScriptInterpreter(compiled, bindings), (error) => error.code === 'INVALID_BINDINGS');
	}
});

test('rejects invalid arithmetic operands and hostile watcher identifiers with stable errors', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const box = {}; -box;',
		'program.onUnhandledAttention("continue_and_notify"); const box = {}; box + 1;',
		'program.onUnhandledAttention("continue_and_notify"); let value = "x"; value++;',
	]) assert.throws(() => interpreter(source).start(facts()), (error) => error.code === 'INVALID_OPERAND');
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify");');
	vm.start(facts());
	assert.throws(() => vm.runWatcher({ toString() { throw new Error('called'); } }, facts()), (error) => error.code === 'INVALID_WATCHER_ID');
});

test('returns one shared frozen null-prototype idle yield', () => {
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); program.watch(() => false, { mode: "boundary" }, async () => {});');
	const first = vm.start(facts());
	assert.equal(Object.getPrototypeOf(first), null);
	assert.ok(Object.isFrozen(first));
	assert.strictEqual(first, vm.runWatcher('watcher-0', facts()));
});

test('bounds model-created strings before exponential concatenation or terminal output allocation', () => {
	const doubling = (count) => Array.from({ length: count }, () => 'text = text + text;').join('\n');
	for (const count of [18, 128]) {
		const vm = interpreter(`program.onUnhandledAttention("continue_and_notify"); let text = "x"; ${doubling(count)} program.finish(text);`);
		assert.throws(() => vm.start(facts()), (error) => error.code === 'OUTPUT_LIMIT');
	}
	const terminal = interpreter(`program.onUnhandledAttention("continue_and_notify"); program.finish("${'x'.repeat(4_097)}");`);
	assert.throws(() => terminal.start(facts()), (error) => error.code === 'OUTPUT_LIMIT');
});
