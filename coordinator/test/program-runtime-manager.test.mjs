import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';

function record() {
	return { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'wait', queue: [] };
}

function observation(overrides = {}) {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} }, ...overrides };
}

function harness() {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => { requests.push(request); return { summary: 'Continue.', directive: 'continue' }; } },
	});
	return { manager, registry, sent, requests };
}

test('installs a model-authored program and dispatches its next primitive without a provider turn', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait twice.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	assert.equal(run.sent.length, 1);
	assert.equal(run.sent[0].payload.actionType, 'wait');
	assert.deepEqual(run.sent[0].payload.arguments, { durationMs: 1 });
	assert.equal(run.sent[0].payload.provenance.programId, 'program-1-1');
	const wire = validateProtocolV2Payload('action_command', run.sent[0].payload);
	assert.deepEqual(wire.provenance, { ...run.sent[0].payload.provenance });
	assert.match(wire.provenance.sourceStepId, /^step-\d+-\d+$/);
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: run.sent[0].payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	assert.equal(run.sent.length, 2);
	assert.equal(run.requests.length, 0, 'pre-authored continuation must not call the provider');
});

test('routes invalid source correction to the selected agent planner without a local replacement', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'not valid ArenaScript {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 0);
	assert.equal(run.requests.length, 1);
	assert.equal(run.requests[0].agentId, 'agent-a');
	assert.equal(run.requests[0].preserveState, true);
	assert.match(run.requests[0].input, /ArenaScript compiler correction/);
});

test('uses an authored watcher before asking the provider for unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Watch health.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), attention: true });
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	assert.equal(run.sent.at(-1).payload.arguments.durationMs, 9);
	assert.equal(run.requests.length, 0);
});

test('disposes an old goal program so late action results cannot advance it', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	run.manager.onGoalControl(run.registry.get('agent-a'), 'steer');
	assert.equal(run.sent.at(-1).type, 'action_cancel');
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 1);
});

test('does not turn an ordinary active-action observation into unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), attention: false });
	assert.equal(run.requests.length, 0);
});

test('keeps versions and external action identities monotonic across remove and recreate', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const old = run.sent[0].payload;
	run.manager.dispose('agent-a');
	run.registry.remove('agent-a');
	run.registry.register(record());
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const replacement = run.sent.at(-1).payload;
	assert.equal(replacement.provenance.programId, 'program-1-2');
	assert.notEqual(replacement.actionId, old.actionId);
	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: old.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' }), false);
});

test('caps recursive compiler correction and reports exhaustion without a fallback action', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = []; let requests = 0;
	const manager = new ProgramRuntimeManager({ registry, bridge: { send: async () => assert.fail('must not dispatch') }, planner: { requestPlan: async () => { requests += 1; return { summary: 'Still bad.', directive: 'replace', source: 'broken {' }; } }, reportError: (_agentId, error) => errors.push(error), compilerCorrectionLimit: 1 });
	await manager.installDecision(registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'broken {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(requests, 1);
	assert.equal(errors.at(-1).code, 'ARENA_SCRIPT_COMPILER_EXHAUSTED');
});
