import assert from 'node:assert/strict';
import test from 'node:test';
import {
	normalizeHeadlessMatrix,
	normalizeHeadlessScenario,
	selectHeadlessScenarios,
	scenarioReport,
} from '../src/headless-matrix.mjs';

const validScenario = (overrides = {}) => ({
	id: 'codex-chat-completion', provider: 'codex', model: 'gpt-5.6-sol',
	reasoningEffort: 'high', serviceTier: 'fast', task: 'Send HEADLESS_PASS',
	timeoutMs: 180000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }], ...overrides,
});

test('normalizes one bounded real-provider scenario', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario()] });
	assert.deepEqual(matrix.scenarios[0].assertions, [{ type: 'lifecycle', state: 'COMPLETED' }]);
	assert.equal(matrix.version, 1);
	assert.ok(Object.isFrozen(matrix));
	assert.ok(Object.isFrozen(matrix.scenarios[0]));
});

test('rejects duplicate IDs, unknown assertion types, and unbounded timeouts', () => {
	assert.throws(() => normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario()] }), /duplicate/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: '../escape' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\\id' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\u0000id' }, 0), /id|control/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), assert: [{ type: 'unknown' }] }, 0), /assert/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 0 }, 0), /timeout/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 900001 }, 0), /timeout/i);
});

test('supports every bounded assertion shape and rejects unknown keys', () => {
	const assertions = [
		{ type: 'lifecycle', state: 'ERROR' },
		{ type: 'chat', message: 'marker' },
		{ type: 'action', actionType: 'move', args: { x: 1 } },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'data get entity @s Pos', match: '1.0' },
	];
	const normalized = normalizeHeadlessScenario(validScenario({ assert: assertions }), 0);
	assert.deepEqual(normalized.assertions, assertions);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), extra: true }, 0), /unknown|key/i);
});

test('selects all scenarios or one exact ID', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario({ id: 'codex-move-chat' })] });
	assert.equal(selectHeadlessScenarios(matrix, null).length, 2);
	assert.equal(selectHeadlessScenarios(matrix, 'codex-move-chat')[0].id, 'codex-move-chat');
	assert.throws(() => selectHeadlessScenarios(matrix, 'missing'), /scenario|id/i);
});

test('creates immutable bounded serializable reports', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const report = scenarioReport('PASSED', scenario, {
		elapsedMs: 12, diagnostics: 'x'.repeat(10000), assertions: [{ type: 'lifecycle', passed: true }],
	});
	assert.equal(report.status, 'PASSED');
	assert.equal(report.scenarioId, scenario.id);
	assert.ok(report.diagnostics.length <= 4096);
	assert.ok(Object.isFrozen(report));
	assert.doesNotThrow(() => JSON.stringify(report));
});

test('replaces deeply nested report values at the depth bound', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const payload = {};
	let cursor = payload;
	for (let index = 0; index < 8; index += 1) {
		cursor.child = {};
		cursor = cursor.child;
	}
	cursor.secret = 'credential-shaped-' + 'x'.repeat(10000);
	const report = scenarioReport('PASSED', scenario, { payload });
	let bounded = report.payload;
	for (let index = 0; index < 7; index += 1) bounded = bounded.child;
	assert.equal(bounded, '[TRUNCATED]');
	assert.ok(JSON.stringify(report).length < 10000);
});
