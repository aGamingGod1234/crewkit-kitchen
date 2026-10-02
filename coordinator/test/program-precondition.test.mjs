import assert from 'node:assert/strict';
import test from 'node:test';
import { compileProgramPrecondition, evaluateProgramPrecondition } from '../src/program-precondition.mjs';

function observation(health, count = 0) {
	return { player: { x: 0, y: 64, z: 0, health }, entities: [], items: [], blocks: [], inventory: { items: [{ itemId: 'minecraft:oak_log', count }], tagCounts: {} } };
}

test('one compiled read-only guard uses current observations and independently owned parameters', () => {
	const condition = compileProgramPrecondition('player.state().health >= program.parameters().minimum && inventory.count("minecraft:oak_log") >= 2');
	const parameters = { minimum: 10, observation: observation(20, 9), goalRevision: 999 };
	assert.equal(evaluateProgramPrecondition(condition, { observation: observation(20, 2), parameters }), true);
	assert.equal(evaluateProgramPrecondition(condition, { observation: observation(9, 2), parameters }), false);
	assert.equal(evaluateProgramPrecondition(condition, { observation: observation(20, 1), parameters }), false);
	assert.equal(evaluateProgramPrecondition(condition, { observation: observation(20, 2), parameters: { minimum: 21 } }), false);
	assert.equal(evaluateProgramPrecondition('math.abs(player.state().x) === 0 && world.entities().length === 0', { observation: observation(20) }), true);
	assert.ok(Object.isFrozen(condition));
});

test('queue guards require the exact boolean true rather than a truthy read or literal', () => {
	for (const source of ['player.state().health', '20', '"ready"', '"false"', '({ ready: true })', '[true]', 'false', 'null']) {
		assert.equal(evaluateProgramPrecondition(source, { observation: observation(20) }), false, source);
	}
	assert.equal(evaluateProgramPrecondition('true', { observation: observation(20) }), true);
	assert.equal(evaluateProgramPrecondition('player.state().health === 20', { observation: observation(20) }), true);
});

test('guards reject statements, effects, ambient code and wrapper escapes before execution', () => {
	for (const source of [
		'', 'true; false', 'let okay = true;', 'return true;', 'while (true) {}',
		'player.wait(1)', 'world.inspect({ section: "menu" })', 'world.remember({ key: "x", text: "y" })',
		'program.finish("done")', 'program.parameters(1)', 'player.state().health = 20', '++player.state().health',
		'process.exit(0)', 'globalThis.fetch("https://example.com")', 'eval("true")', '(() => true)()',
		'true), { mode: "boundary" }, async () => {}); await player.wait(1); program.watch(() => (true',
		'true' + ' '.repeat(4096),
	]) assert.throws(() => compileProgramPrecondition(source), (error) => error.code === 'INVALID_PROGRAM_PRECONDITION');
	assert.throws(() => evaluateProgramPrecondition(Object.freeze({ ast: {} }), { observation: observation(20) }), (error) => error.code === 'INVALID_PROGRAM_PRECONDITION');
});
