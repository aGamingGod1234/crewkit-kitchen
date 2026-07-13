import assert from 'node:assert/strict';
import test from 'node:test';

import { parseDecision } from '../src/decision-parser.mjs';
import { buildPlannerInput, PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';

test('parses one bare or fenced planner decision', () => {
	const expected = { summary: 'Moving closer.', goalStatus: 'in_progress', action: { type: 'wait', durationMs: 25 } };
	assert.deepEqual(parseDecision(JSON.stringify(expected)), expected);
	assert.deepEqual(parseDecision(`\`\`\`json\n${JSON.stringify(expected)}\n\`\`\``), expected);
});

test('rejects prose, multiple objects, unknown keys, and mismatched terminal status', () => {
	const decision = { summary: 'Wait.', goalStatus: 'in_progress', action: { type: 'wait', durationMs: 25 } };
	assert.throws(() => parseDecision(`Here: ${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(`${JSON.stringify(decision)}\n${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(JSON.stringify({ ...decision, hidden: true })), /Unknown decision field/);
	assert.throws(() => parseDecision(JSON.stringify({ ...decision, goalStatus: 'completed' })), /complete_goal/);
});

test('uses one model-neutral planner contract for both agents', () => {
	assert.match(PLANNER_SYSTEM_PROMPT, /Do not use shell, filesystem, browser, or computer tools/);
	assert.doesNotMatch(PLANNER_SYSTEM_PROMPT, /gpt-5\.5|gpt-5\.6|agent-?55|agent-?56/i);
	const state = { goal: 'enter arena', trigger: 'goal_event', observation: { ready: true } };
	assert.equal(buildPlannerInput(state), buildPlannerInput(structuredClone(state)));
});
