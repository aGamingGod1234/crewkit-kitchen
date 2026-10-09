import assert from 'node:assert/strict';
import test from 'node:test';

import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { createInterpreterFacts } from '../src/arena-script/facts.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { SCRIPT_BINDINGS } from '../src/arena-script/minecraft-api.mjs';
import { validateAction } from '../src/schema.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';

const ACTION_BINDINGS = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		navigateTo: Object.freeze(Object.assign(Object.create(null), { primitive: 'navigate_to' })),
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
		attack: Object.freeze(Object.assign(Object.create(null), { primitive: 'attack' })),
		useRanged: Object.freeze(Object.assign(Object.create(null), { primitive: 'use_ranged' })),
		craftInventory: Object.freeze(Object.assign(Object.create(null), { primitive: 'craft_inventory' })),
		mine: Object.freeze(Object.assign(Object.create(null), { primitive: 'break_block' })),
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

test('logical assignments short circuit and preserve required assignments across a yielded action', () => {
	for (const [operator, skipped, assigned] of [['||=', 'true', 'false'], ['&&=', 'false', 'true'], ['??=', '5', 'null']]) {
		const prefix = 'program.onUnhandledAttention("continue_and_notify");';
		assert.equal(interpreter(`${prefix} let value = ${skipped}; value ${operator} await player.wait(1); program.finish("done");`).start(facts()).kind, 'finish');
		const plain = interpreter(`${prefix} let value = ${assigned}; value ${operator} 7; await player.wait(value);`).start(facts());
		assert.equal(plain.call.arguments, 7);
		const vm = interpreter(`${prefix} let value = ${assigned}; value ${operator} await player.wait(1); await player.wait(value.succeeded ? 7 : 8);`);
		const first = vm.start(facts());
		assert.equal(first.call.arguments, 1);
		assert.equal(vm.resume(actionResult(first), facts()).call.arguments, 7);
	}
});

test('callback records remain data for expression arrows and repeatUntil bodies', () => {
	for (const kind of ['normal', 'return', 'break', 'continue', 'success']) {
		const command = interpreter(`program.onUnhandledAttention("continue_and_notify"); const record = () => ({kind:"${kind}",value:7}); await player.wait(record().value);`).start(facts());
		assert.equal(command.call.arguments, 7);
	}
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => false, {maxIterations:3}, async () => {await player.wait(1); return {kind:"success"};}); program.finish("done");');
	let yielded = vm.start(facts());
	for (let count = 0; count < 3; count += 1) {
		assert.equal(yielded.kind, 'command');
		yielded = vm.resume(actionResult(yielded), facts());
	}
	assert.equal(yielded.kind, 'checkpoint');
	assert.equal(yielded.reason, 'repeat_until_exhausted');
});

test('shared immutable control frames serialize as independent bounded records', () => {
	const frame = '{forward:1,strafe:0,jump:false,sneak:false,sprint:false,attack:false,use:false,yaw:0,pitch:0,selectedSlot:0,hand:"main",ticks:2}';
	const vm = new ArenaScriptInterpreter(parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); const frame = ${frame}; await player.controlSequence({frames:[frame,frame],maxTicks:4});`), SCRIPT_BINDINGS);
	const command = vm.start(facts());
	assert.equal(validateAction({type: command.call.primitive, ...command.call.arguments}).frames.length, 2);
	assert.deepEqual(command.call.arguments.frames[0], command.call.arguments.frames[1]);
	assert.notEqual(command.call.arguments.frames[0], command.call.arguments.frames[1]);
	const cyclic = facts(); cyclic.player.self = cyclic.player;
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);').start(cyclic), error => error.code === 'INVALID_FACTS');
});

test('all 64 authored control frames fit the bounded compound payload without raising ordinary output limits', () => {
	const frame = { forward: 1, strafe: 0, jump: false, sneak: false, sprint: false, attack: false, use: false, yaw: 0, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 2 };
	const args = { frames: Array.from({ length: 64 }, () => ({ ...frame })), maxTicks: 128 };
	assert.ok(Buffer.byteLength(JSON.stringify(args)) > 4096);
	const vm = new ArenaScriptInterpreter(parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.controlSequence(${JSON.stringify(args)});`), SCRIPT_BINDINGS);
	const command = vm.start(facts());
	assert.equal(command.call.primitive, 'control_sequence');
	assert.equal(validateAction({ type: command.call.primitive, ...command.call.arguments }).frames.length, 64);
	assert.equal(vm.resume(actionResult(command), facts()).kind, 'idle');
	const tooLarge = { slot: 0, pages: Array.from({ length: 32 }, () => 'x'.repeat(1024)), expectedFingerprint: 'book' };
	assert.throws(() => new ArenaScriptInterpreter(parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.editBook(${JSON.stringify(tooLarge)});`), SCRIPT_BINDINGS).start(facts()), (error) => error.code === 'OUTPUT_LIMIT');
});

test('model code compares observed candidates and computes a precise input frame without a target heuristic', () => {
	const vm = new ArenaScriptInterpreter(parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		let target = null;
		for (const candidate of world.entities()) {
			if (candidate.velocity.x > 0 && (target === null || candidate.distance > target.distance)) target = candidate;
		}
		await player.control({ forward: 0, strafe: 1, jump: false, sneak: true, sprint: false, attack: false, use: false,
			yaw: math.atan2(target.z, target.x) * 180 / 3.141592653589793, pitch: 0, selectedSlot: 0, hand: "main", ticks: 2 });
	`), SCRIPT_BINDINGS);
	const command = vm.start(createInterpreterFacts({ player: { x: 0, y: 64, z: 0 }, entities: [
		{ stableId: 'near', type: 'minecraft:pig', x: 1, y: 64, z: 0, distance: 1, velocity: { x: -1, y: 0, z: 0 } },
		{ stableId: 'far', type: 'minecraft:pig', x: 3, y: 64, z: 3, distance: 4.2, velocity: { x: 1, y: 0, z: 0 } },
	] }));
	assert.equal(command.call.primitive, 'control');
	assert.equal(command.call.arguments.yaw, 45);
	assert.equal(validateAction({ type: command.call.primitive, ...command.call.arguments }).ticks, 2);
});

test('for-of preserves const isolation and obeys existing loop and operation limits', () => {
	const source = 'program.onUnhandledAttention("continue_and_notify"); let sum = 0; for (const count of [1, 2, 3]) sum += count; await player.wait(sum);';
	assert.equal(interpreter(source).start(facts()).call.arguments, 6);
	assert.throws(() => interpreter(source, { loopIterationsPerYield: 2 }).start(facts()), (error) => error.code === 'LOOP_LIMIT');
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify"); for (const count of [1]) count = 2;').start(facts()), (error) => error.code === 'CONST_ASSIGNMENT');
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify"); for (const count of player.state()) {}').start(facts()), (error) => error.code === 'INVALID_ITERABLE');
});

test('safe arithmetic excludes ambient globals, invalid operands and nonfinite results', () => {
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.wait(math.max(1, math.floor(math.hypot(3, 4))));');
	assert.equal(vm.start(facts()).call.arguments, 5);
	for (const call of ['math.sqrt(-1)', 'math.atan2(1)', 'math.abs("1")']) {
		assert.throws(() => interpreter(`program.onUnhandledAttention("continue_and_notify"); await player.wait(${call});`).start(facts()), (error) => ['INVALID_ARGUMENT', 'INVALID_OPERAND'].includes(error.code));
	}
	assert.throws(() => interpreter('program.onUnhandledAttention("continue_and_notify"); Math.random();'));
});

test('all zero-argument player controls emit object arguments accepted by the shared action schema', () => {
	for (const member of ['dismount', 'startFallFlying', 'wakeUp', 'respawn']) {
		const vm = new ArenaScriptInterpreter(parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.${member}();`), SCRIPT_BINDINGS);
		const command = vm.start(facts());
		assert.equal(Array.isArray(command.call.arguments), false);
		assert.doesNotThrow(() => validateAction({ type: command.call.primitive, ...command.call.arguments }));
	}
});

test('inspection yields a read request and resumes with the full immutable result', () => {
	const vm = interpreter(`program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "menu", offset: 0, limit: 16 });
		let selected = 0;
		for (const slot of page.menu.slots) { if (slot.itemId === "minecraft:diamond") selected = slot.slot; }
		await player.wait(selected);
	`);
	const query = vm.start(facts());
	assert.equal(query.kind, 'query');
	assert.equal(query.query.section, 'menu');
	assert.equal(query.call, undefined);
	const value = { state: 'SUCCEEDED', reasonCode: 'INSPECTED', menu: { slots: [{ slot: 7, itemId: 'minecraft:diamond' }] } };
	const next = vm.resume({ stateToken: query.stateToken, state: 'SUCCEEDED', reasonCode: 'INSPECTED', value }, facts());
	assert.equal(next.call.arguments, 7);
});

test('restoring an undispatched command or query preserves its token, local scope and real receipt after the watcher decision', () => {
	for (const kind of ['command', 'query']) {
		const body = kind === 'command'
			? 'const receipt = await tryResult(player.wait(1000)); await player.wait(before + player.state().health + (receipt.succeeded ? 1 : 2));'
			: 'const page = await world.inspect({section:"inventory",offset:0,limit:16}); await player.wait(before + player.state().health + page.entries.length);';
		const vm = interpreter(`program.onUnhandledAttention("continue_and_notify");
			program.watch(() => player.state().health < 19, {mode:"interrupt",after:"reconsider"}, async () => {await player.wait(9);});
			const before = player.state().health; ${body}`);
		const unsafe = facts({player:{health:17}}), safe = facts();
		const original = vm.start(unsafe);
		assert.equal(original.kind, kind);
		const defense = vm.runWatcherHandlerBeforeResume('watcher-0', unsafe);
		assert.equal(defense.call.arguments, 9);
		assert.throws(() => vm.restoreDeferredCommand(safe), error => error.code === 'NOT_IDLE');
		assert.equal(vm.resume(actionResult(defense), safe).kind, 'watcher_decision');
		assert.throws(() => vm.restoreDeferredCommand(safe), error => error.code === 'NOT_IDLE', 'defense completion is not permission to resume');
		assert.equal(vm.resumeWatcherDecision(safe).kind, 'idle');
		assert.throws(() => vm.restoreDeferredCommand({player:{health:NaN}}), error => error.code === 'INVALID_FACTS');
		vm.restoreDeferredCommand(safe);
		assert.throws(() => vm.restoreDeferredCommand(safe), error => error.code === 'NOT_WAITING');
		const staleReceipt = kind === 'command' ? actionResult(defense)
			: {...actionResult(defense), value:{state:'SUCCEEDED',reasonCode:'INSPECTED',entries:[]}};
		assert.throws(() => vm.resume(staleReceipt, safe), error => error.code === 'STALE_STATE_TOKEN', 'the defense receipt cannot settle the held body');
		const result = kind === 'command' ? actionResult(original, 'FAILED', 'BODY_FAILED')
			: {...actionResult(original), value:{state:'SUCCEEDED',reasonCode:'INSPECTED',entries:[1,2,3]}};
		const next = vm.resume(result, safe);
		assert.equal(next.call.arguments, kind === 'command' ? 39 : 40);
	}
});

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
		const moved = await tryResult(player.navigateTo({ x: 4, y: 64, z: 2, tolerance: 1, sprint: false, timeoutMs: 5_000 }));
		if (!moved.succeeded) program.checkpoint(moved.reason);
		program.finish("arrived");
	`);
	const first = vm.start(facts());
	assert.equal(first.kind, 'command');
	assert.equal(first.call.primitive, 'navigate_to');
	assert.deepEqual(Object.fromEntries(Object.entries(first.call.arguments)), { x: 4, y: 64, z: 2, tolerance: 1, sprint: false, timeoutMs: 5_000 });
	assert.equal(typeof first.stateToken, 'string');
	const second = vm.resume(actionResult(first, 'SUCCEEDED', 'ARRIVED'), facts({ player: { x: 4 } }));
	assert.equal(second.kind, 'finish');
	assert.equal(second.summary, 'arrived');
});

test('maps a model-authored act call to the same validated player primitive', () => {
	const source = 'for (let x = 10; x < 12; x += 1) await act({ actionType: "mine", arguments: { x, y: 64, z: 10, expectedBlockId: "minecraft:stone" } });';
	const vm = interpreter(source);
	const command = vm.start(facts());
	assert.equal(command.call.primitive, 'break_block');
	assert.deepEqual(Object.fromEntries(Object.entries(command.call.arguments)), {
		x: 10, y: 64, z: 10, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000,
	});
	assert.doesNotThrow(() => validateAction({ type: command.call.primitive, ...command.call.arguments }));
	const navigateVm = new ArenaScriptInterpreter(parseArenaScript('await act({actionType:"navigate_to",arguments:{x:1,y:64,z:2}});'), SCRIPT_BINDINGS);
	const navigate = navigateVm.start(facts());
	assert.equal(navigate.call.primitive, 'navigate_to');
	assert.deepEqual(Object.fromEntries(Object.entries(navigate.call.arguments)), { x: 1, y: 64, z: 2, tolerance: 1, sprint: true, timeoutMs: 30_000 });
	assert.doesNotThrow(() => validateAction({ type: navigate.call.primitive, ...navigate.call.arguments }));
	const moveToVm = new ArenaScriptInterpreter(parseArenaScript('await act("move_to",{x:1,y:64,z:2});'), SCRIPT_BINDINGS);
	const moveTo = moveToVm.start(facts());
	assert.equal(moveTo.call.primitive, 'move_to');
	assert.deepEqual(Object.fromEntries(Object.entries(moveTo.call.arguments)), { x: 1, y: 64, z: 2, tolerance: 1, sprint: true });
	assert.doesNotThrow(() => validateAction({ type: moveTo.call.primitive, ...moveTo.call.arguments }));
	const nativeMove = normalizeMinecraftToolCall('moveTo', { x: 1, y: 64, z: 2 });
	assert.deepEqual({ tolerance: moveTo.call.arguments.tolerance, sprint: moveTo.call.arguments.sprint }, { tolerance: nativeMove.arguments.tolerance, sprint: nativeMove.arguments.sprint });
	const nativeLegacyMove = normalizeMinecraftToolCall('act', { actionType: 'move_to', arguments: { x: 1, y: 64, z: 2, tolerance: moveTo.call.arguments.tolerance, sprint: moveTo.call.arguments.sprint } });
	assert.deepEqual({ kind: 'action', actionType: moveTo.call.primitive, arguments: { ...moveTo.call.arguments } }, nativeLegacyMove);
	const lookAtVm = new ArenaScriptInterpreter(parseArenaScript('await act("look_at", { x: 10.5, y: 64.5, z: 10.5 });'), SCRIPT_BINDINGS);
	const lookAt = lookAtVm.start(facts());
	assert.equal(lookAt.call.primitive, 'look_at');
	assert.deepEqual(Object.fromEntries(Object.entries(lookAt.call.arguments)), { x: 10.5, y: 64.5, z: 10.5 });
	assert.doesNotThrow(() => validateAction({ type: lookAt.call.primitive, ...lookAt.call.arguments }));
	const directMineVm = new ArenaScriptInterpreter(parseArenaScript('await mine({x:10,y:64,z:10,expectedBlockId:"minecraft:stone"});'), SCRIPT_BINDINGS);
	const directMine = directMineVm.start(facts());
	assert.equal(directMine.call.primitive, 'break_block');
	assert.deepEqual(Object.fromEntries(Object.entries(directMine.call.arguments)), { x: 10, y: 64, z: 10, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 });
	assert.doesNotThrow(() => validateAction({ type: directMine.call.primitive, ...directMine.call.arguments }));
	assert.deepEqual({ kind: 'action', actionType: directMine.call.primitive, arguments: { ...directMine.call.arguments } }, normalizeMinecraftToolCall('mine', { x: 10, y: 64, z: 10, expectedBlockId: 'minecraft:stone' }));
	const autoAimSource = 'await mine({x:10,y:64,z:10,expectedBlockId:"minecraft:stone",autoAim:true});';
	const autoAimVm = new ArenaScriptInterpreter(parseArenaScript(autoAimSource), SCRIPT_BINDINGS);
	const autoAimFirst = autoAimVm.start(facts());
	const nativeMine = normalizeMinecraftToolCall('mine', { x: 10, y: 64, z: 10, expectedBlockId: 'minecraft:stone', autoAim: true });
	assert.equal(nativeMine.kind, 'sequence');
	assert.deepEqual({ primitive: autoAimFirst.call.primitive, arguments: { ...autoAimFirst.call.arguments } }, {
		primitive: nativeMine.actions[0].actionType, arguments: nativeMine.actions[0].arguments,
	});
	const autoAimSecond = autoAimVm.resume(actionResult(autoAimFirst), facts());
	assert.deepEqual({ primitive: autoAimSecond.call.primitive, arguments: { ...autoAimSecond.call.arguments } }, {
		primitive: nativeMine.actions[1].actionType, arguments: nativeMine.actions[1].arguments,
	});
	const failedAimVm = new ArenaScriptInterpreter(parseArenaScript(autoAimSource), SCRIPT_BINDINGS);
	const failedAimFirst = failedAimVm.start(facts());
	assert.equal(failedAimVm.resume(actionResult(failedAimFirst, 'FAILED', 'LOOK_FAILED'), facts()).kind, 'idle');
	const caughtVm = new ArenaScriptInterpreter(parseArenaScript('await act("look_at", {x: 10.5, y: 64.5, z: 10.5}).catch(() => {});'), SCRIPT_BINDINGS);
	const caught = caughtVm.start(facts());
	assert.equal(caught.call.primitive, 'look_at');
	const caughtMineVm = new ArenaScriptInterpreter(parseArenaScript('await mine({x:10,y:64,z:10,expectedBlockId:"minecraft:stone"}).catch(() => {});'), SCRIPT_BINDINGS);
	assert.equal(caughtMineVm.start(facts()).call.primitive, 'break_block');
	assert.throws(() => interpreter('await act({ actionType: "mine", arguments: { x: 1, y: 64, z: 10 } });'), error => error.code === 'INVALID_ARENA_SCRIPT_COMMAND' && /expectedBlockId/.test(error.message));
	assert.throws(() => interpreter('await act({ actionType: "mine" });'), error => error.code === 'UNSUPPORTED_SYNTAX' && /x,y,z,expectedBlockId.*world\.blocks.*act\("mine"/.test(error.message));
	assert.throws(() => interpreter('await act({ actionType: action, arguments: {} });'), error => error.code === 'UNSUPPORTED_SYNTAX' && /actionType must be a literal/.test(error.message));
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
		const moved = await tryResult(player.navigateTo({ x: 4, y: 64, z: 2, tolerance: 1, sprint: false, timeoutMs: 5_000 }));
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
			async () => { await player.wait(1); }
		);
	`);
	assert.equal(vm.start(facts()).kind, 'idle');
	assert.equal(vm.runWatcher('watcher-0', facts()).kind, 'idle');
	const fired = vm.runWatcher('watcher-0', facts({ player: { health: 8 } }));
	assert.equal(fired.kind, 'command');
	assert.equal(fired.call.primitive, 'wait');
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
	const vm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.navigateTo({ x: 1, nested: { y: 2 } });');
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
		assert.throws(() => interpreter(`program.onUnhandledAttention("continue_and_notify"); await player.navigateTo({ ${key}: 1 });`), (error) => error.code === 'UNSAFE_MEMBER_ACCESS');
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
			program.watch(() => true, { mode: "boundary" }, async () => { await player.wait(1); });
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

test('a loop alternating failed mines on cleared blocks yields to the model', () => {
	// Seen live: a program mined (-3,17,-8) and (-3,16,-8) in turn after both were air,
	// several times a second, and nothing stopped it.
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 64 }, async () => {
			await player.mine({ x: -3, y: 17, z: -8, expectedBlockId: "minecraft:stone" });
			await player.mine({ x: -3, y: 16, z: -8, expectedBlockId: "minecraft:stone" });
		});
	`);
	let step = vm.start(facts());
	let failures = 0;
	while (step.kind === 'command' && failures < 64) {
		step = vm.resume(actionResult(step, 'FAILED', 'TARGET_INVALID_FOR_TEST'), facts());
		failures += 1;
	}
	assert.equal(step.kind, 'replan', 'a no-progress loop stops instead of spinning');
	assert.equal(failures, 8, 'eight failures in a row with no success hand control back');
	assert.equal(step.reason, 'repeated_action_failure:TARGET_INVALID_FOR_TEST');

	const air = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 64 }, async () => {
			await player.mine({ x: -3, y: 17, z: -8, expectedBlockId: "minecraft:stone" });
		});
	`);
	const first = air.start(facts());
	const second = air.resume(actionResult(first, 'FAILED', 'TARGET_AIR'), facts());
	assert.equal(second.kind, 'command');
	assert.equal(air.resume(actionResult(second, 'FAILED', 'TARGET_AIR'), facts()).reason, 'repeated_action_failure:TARGET_AIR',
		'mining air twice at the same step is deterministic');
});

test('successes between failures keep a working loop running', () => {
	const vm = interpreter(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 40 }, async () => {
			await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:stone" });
		});
	`);
	let step = vm.start(facts());
	for (let index = 0; index < 30; index += 1) {
		step = vm.resume(actionResult(step, index % 4 === 3 ? 'SUCCEEDED' : 'FAILED', index % 4 === 3 ? 'BLOCK_BROKEN' : 'TARGET_TOO_FAR'), facts());
		assert.equal(step.kind, 'command', `iteration ${index} keeps running`);
	}
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

test('a successful wait cannot hide repeated pickup path failures', () => {
	for (const recovery of ['await player.wait(50);', 'await player.lookAt({ x: 1, y: 64, z: 0 });']) {
		const vm = new ArenaScriptInterpreter(parseArenaScript(`
			program.onUnhandledAttention("continue_and_notify");
			await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
				await tryResult(player.pickUpItem({ targetSelector: "00000000-0000-4000-8000-000000000001" }));
				${recovery}
			});
		`), SCRIPT_BINDINGS);
		const first = vm.start(facts());
		const intermediate = vm.resume(actionResult(first, 'FAILED', 'NO_STANDABLE_PATH'), facts());
		const retry = vm.resume(actionResult(intermediate, 'SUCCEEDED', 'DONE'), facts());
		const result = vm.resume(actionResult(retry, 'FAILED', 'NO_STANDABLE_PATH'), facts());
		assert.equal(result.kind, recovery.includes('player.wait') ? 'replan' : 'command');
		if (result.kind === 'replan') assert.equal(result.failure.reasonCode, 'NO_STANDABLE_PATH');
	}
});

test('a different source step remains available to authored recovery', () => {
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

	const outputVm = interpreter('program.onUnhandledAttention("continue_and_notify"); await player.navigateTo(player.state());');
	assert.throws(() => outputVm.start(facts({ player: { blob: 'x'.repeat(8_193) } })), (error) => error.code === 'OUTPUT_LIMIT');
});

test('factory-normalized facts outside canonical limits stay on the rejecting validation path', () => {
	const oversized = createInterpreterFacts({
		player: { x: 0, y: 64, z: 0, health: 20 },
		items: Array.from({ length: 257 }, (_unused, index) => ({
			stableId: `item-${index}`, itemId: 'minecraft:stone', count: 1, x: index, y: 64, z: 0,
		})),
		entities: [], blocks: [], inventory: { items: [], tagCounts: {} },
	});
	assert.throws(
		() => interpreter('program.onUnhandledAttention("continue_and_notify");').start(oversized),
		(error) => error.code === 'FACT_LIMIT',
	);
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
		program.watch(() => true, { mode: "boundary" }, async () => { await player.wait(1); });
		for (let index = 0; index < 2; index += 1) { }
	`, { loopIterationsPerYield: 1 });
	assert.throws(() => vm.start(facts()), (error) => error.code === 'LOOP_LIMIT');
	assert.throws(() => vm.runWatcher('watcher-0', facts()), (error) => error.code === 'INACTIVE_LIFECYCLE');
});

test('requires exact player binding primitive mappings', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify");');
	for (const [member, primitive] of [['navigateTo', 'fight_target'], ['wait', 'move_to']]) {
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
