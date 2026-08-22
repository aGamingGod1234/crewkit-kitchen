import test from 'node:test';
import assert from 'node:assert/strict';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { completionContractFingerprint } from '../src/goal-contract.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';

const CONTRACT = { goalRevision: 1, predicates: [{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }] };

function record() {
	return { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'craft a wooden pickaxe', queue: [] };
}

function observation() {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [], tagCounts: {} }, items: [], entities: [], blocks: [] };
}

test('completion is a factual request and cannot enter COMPLETED before server verification', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		onCompletionRequested: (request) => requests.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Claimed pickaxe.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
		completionContract: CONTRACT,
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
	const request = requests[0];
	assert.equal(request.record.goalRevision, 1);
	assert.equal(request.contractHash, completionContractFingerprint(CONTRACT));
	assert.equal(manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1, traceId: request.traceId, contractHash: request.contractHash, verified: false, reasonCode: 'PREDICATE_FAILED',
	}), true);
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1, traceId: request.traceId, contractHash: request.contractHash, verified: true, reasonCode: 'COMPLETION_VERIFIED',
	}), true);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
});

test('completion contract cannot mutate within one goal revision', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const manager = new ProgramRuntimeManager({ registry, bridge: { send: async () => {} }, planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) } });
	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Start.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);', completionContract: CONTRACT,
	}, { observation: observation(), eventSequence: 1 });
	await assert.rejects(() => manager.installDecision(registry.get('agent-a'), {
		summary: 'Change proof.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
		completionContract: { goalRevision: 1, predicates: [{ type: 'position_within', x: 0, y: 64, z: 0, radius: 1 }] },
	}, { observation: observation(), eventSequence: 2 }), /cannot change/i);
});
