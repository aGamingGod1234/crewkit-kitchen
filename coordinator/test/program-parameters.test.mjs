import assert from 'node:assert/strict';
import test from 'node:test';
import { validateProgramParameters, PROGRAM_PARAMETER_LIMITS } from '../src/program-parameters.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { SCRIPT_BINDINGS } from '../src/arena-script/minecraft-api.mjs';

const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const facts = { player: { x: 1, y: 64, z: 2 }, world: {}, inventory: { tagCounts: {} } };

test('parameters are detached frozen JSON data and owned values cross downstream boundaries once', () => {
	const original = { targets: [{ x: 1, y: 64, z: 2 }], count: 3, flag: true, empty: null };
	const parameters = validateProgramParameters(original);
	assert.notEqual(parameters, original);
	assert.notEqual(parameters.targets, original.targets);
	assert.notEqual(parameters.targets[0], original.targets[0]);
	assert.equal(Object.getPrototypeOf(parameters), null);
	assert.equal(Object.getPrototypeOf(parameters.targets[0]), null);
	assert.ok(Object.isFrozen(parameters) && Object.isFrozen(parameters.targets) && Object.isFrozen(parameters.targets[0]));
	original.targets[0].x = 9;
	assert.equal(parameters.targets[0].x, 1);
	assert.throws(() => { parameters.targets[0].x = 9; }, TypeError);
	assert.equal(validateProgramParameters(parameters), parameters);
	assert.equal(JSON.stringify(parameters), '{"targets":[{"x":1,"y":64,"z":2}],"count":3,"flag":true,"empty":null}');
});

test('parameter size, depth and entry bounds reject unsafe data without executing object behavior', () => {
	let touched = 0;
	const getter = Object.defineProperty({}, 'value', { enumerable: true, get() { touched++; return 1; } });
	const proxy = new Proxy({}, { ownKeys() { touched++; return []; }, getPrototypeOf() { touched++; return Object.prototype; } });
	const cycle = {}; cycle.self = cycle;
	let deep = 0;
	for (let depth = 0; depth <= PROGRAM_PARAMETER_LIMITS.depth; depth++) deep = { nested: deep };
	const invalid = [
		null, [], 1, '1', false, getter, proxy, cycle, deep,
		Object.create({ inherited: 1 }), new Date(), { nested: new Map() },
		{ value: undefined }, { value: () => {} }, { value: 1n }, { value: Symbol('value') },
		{ value: NaN }, { value: Infinity }, { value: -Infinity },
		{ [Symbol('key')]: 1 }, Object.defineProperty({}, 'hidden', { value: 1 }),
		JSON.parse('{"__proto__":{"polluted":true}}'), { nested: { constructor: 1 } }, { nested: { prototype: 1 } },
		{ values: Array(2) }, { values: Object.assign([1], { extra: 1 }) },
		{ values: Array(PROGRAM_PARAMETER_LIMITS.entries).fill(1) },
		{ value: '😀'.repeat(1024) }, { value: '\n'.repeat(2048) },
	];
	for (const value of invalid) assert.throws(() => validateProgramParameters(value), (error) => error.code === 'INVALID_PROGRAM_PARAMETERS');
	assert.equal(touched, 0);
	const atLimit = { value: 'x'.repeat(4084) };
	assert.equal(Buffer.byteLength(JSON.stringify(atLimit)), 4096);
	assert.doesNotThrow(() => validateProgramParameters(atLimit));
	assert.throws(() => validateProgramParameters({ value: atLimit.value + 'x' }), (error) => error.code === 'INVALID_PROGRAM_PARAMETERS');
});

test('the same cached compiler artifact can consume different parameters without changing its source', () => {
	const source = `${prefix} const args = program.parameters(); await player.wait(args.count);`;
	const compiled = parseArenaScript(source);
	const one = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS, { parameters: { count: 2 } });
	const two = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS, { parameters: { count: 7 } });
	assert.equal(one.start(facts).call.arguments, 2);
	assert.equal(two.start(facts).call.arguments, 7);
	assert.equal(compiled.source, source);
	assert.ok(Object.isFrozen(compiled.ast));
	assert.throws(() => parseArenaScript(`${prefix} program.parameters({ count: 1 });`));
	assert.throws(() => parseArenaScript(`${prefix} const read = program.parameters; read();`));
	assert.throws(() => parseArenaScript(`${prefix} const args = program.parameters(); args.count = 8;`));
});

test('source-like parameter strings stay literal data and cannot add actions to the routine', () => {
	const source = `${prefix} const args = program.parameters(); await player.chat(args.message);`;
	const message = '"); await player.wait(99); //';
	const compiled = parseArenaScript(source);
	const vm = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS, { parameters: { message } });
	const command = vm.start(facts);
	assert.equal(command.call.primitive, 'chat');
	assert.equal(command.call.arguments, message);
	assert.equal(vm.resume({ stateToken: command.stateToken, state: 'SUCCEEDED', reasonCode: 'DONE' }, facts).kind, 'idle');
	assert.equal(compiled.source, source);
});
