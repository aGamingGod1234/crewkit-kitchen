import assert from 'node:assert/strict';
import test from 'node:test';

import { parseDecision } from '../src/decision-parser.mjs';
import { buildPlannerInput, PLANNER_OUTPUT_SCHEMA, PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';

test('parses a replace envelope containing ArenaScript source', () => {
	const source = 'program.onUnhandledAttention("continue_and_notify");';
	const wire = { summary: 'Gather logs', directive: 'replace', source };
	assert.deepEqual(parseDecision(JSON.stringify(wire)), wire);
	assert.deepEqual(parseDecision(`\`\`\`json\n${JSON.stringify(wire)}\n\`\`\``), wire);
});

test('parses non-replacement envelopes without unused fields', () => {
	assert.deepEqual(parseDecision('{"summary":"Keep running","directive":"continue"}'), {
		summary: 'Keep running', directive: 'continue',
	});
	assert.deepEqual(parseDecision('{"summary":"Awaiting a selected-model turn","directive":"pause"}'), {
		summary: 'Awaiting a selected-model turn', directive: 'pause',
	});
	assert.deepEqual(parseDecision('{"summary":"Goal complete","directive":"finish","status":"completed"}'), {
		summary: 'Goal complete', directive: 'finish', status: 'completed',
	});
	assert.deepEqual(parseDecision('{"summary":"No observed route","directive":"finish","status":"impossible"}'), {
		summary: 'No observed route', directive: 'finish', status: 'impossible',
	});
});

test('enforces discriminated envelope fields and summary bounds', () => {
	assert.throws(() => parseDecision('{"summary":"x","directive":"continue","source":"bad"}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"pause","status":"completed"}'), /status/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"replace"}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"replace","source":"","status":"completed"}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"finish"}'), /status/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"finish","status":"unknown"}'), /status/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"cancel"}'), /directive/);
	assert.throws(() => parseDecision('{"summary":"","directive":"continue"}'), /summary/);
	assert.throws(() => parseDecision(JSON.stringify({ summary: 'x'.repeat(2_049), directive: 'continue' })), /summary/);
});

test('rejects prose, multiple objects, unknown keys, and old action-list fields', () => {
	const decision = { summary: 'Keep running', directive: 'continue' };
	assert.throws(() => parseDecision(`Here: ${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(`${JSON.stringify(decision)}\n${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(JSON.stringify({ ...decision, hidden: true })), /Unknown decision field/);
	assert.throws(() => parseDecision('{"summary":"old","goalStatus":"in_progress","directive":"replace","actions":[]}'), /goalStatus/);
});

test('uses one selected-model ArenaScript contract and envelope schema', () => {
	assert.match(PLANNER_SYSTEM_PROMPT, /only the user-selected provider, model, reasoning effort, and service tier/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /ArenaScript source inside the JSON envelope/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /exactly one.*onUnhandledAttention/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /observed facts only/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /player\.moveTo\(\{ x, y, z \}\)/);
	assert.match(PLANNER_SYSTEM_PROMPT, /multi-tree and pickup example/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /watcher example/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /compiler diagnostics.*correct/i);
	assert.doesNotMatch(PLANNER_SYSTEM_PROMPT, /default priority framework|preserve life before|prefer cooked food/i);
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.required, ['summary', 'directive']);
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.properties.directive, {
		type: 'string', enum: ['replace', 'continue', 'pause', 'finish'],
	});
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.properties.source, { type: 'string', minLength: 1, maxLength: 65_536 });
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.properties.status, { type: 'string', enum: ['completed', 'impossible'] });
});

test('builds compiler correction input from diagnostics and a source hash without source text', () => {
	const input = buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'unexpected token', line: 4, column: 12 },
		rejectedSourceHash: 'sha256:abc123',
		observation: { player: { health: 20 }, source: 'program.onUnhandledAttention("pause_and_notify");' },
	});
	assert.match(input, /arena_script_compiler_error/);
	assert.match(input, /SYNTAX_ERROR/);
	assert.match(input, /"line":4/);
	assert.match(input, /sha256:abc123/);
	assert.match(input, /"health":20/);
	assert.doesNotMatch(input, /program\.onUnhandledAttention/);
	assert.throws(() => buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'bad', line: -1, column: 0 },
		rejectedSourceHash: 'sha256:abc123', observation: {},
	}), /line/);
});
