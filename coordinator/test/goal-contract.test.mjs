import test from 'node:test';
import assert from 'node:assert/strict';

import {
	bindCompletionContract,
	completionContractFingerprint,
	parseCompletionContract,
} from '../src/goal-contract.mjs';

const PICKAXE = {
	goalRevision: 7,
	predicates: [{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }],
};

test('normalizes the factual wooden-pickaxe contract and binds revision/profile/trace', () => {
	const contract = parseCompletionContract(PICKAXE);
	assert.deepEqual(contract, PICKAXE);
	const bound = bindCompletionContract(contract, {
		goalRevision: 7,
		traceId: 'trace-agent-7',
		profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' },
	});
	assert.equal(bound.goalRevision, 7);
	assert.equal(bound.traceId, 'trace-agent-7');
	assert.deepEqual(bound.profile, { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' });
	assert.equal(bound.contractHash, completionContractFingerprint(contract));
});

test('rejects stale, empty, unknown, and malformed factual predicates', () => {
	assert.throws(() => parseCompletionContract({ ...PICKAXE, goalRevision: 6 }, { goalRevision: 7 }), /goalRevision/i);
	assert.throws(() => parseCompletionContract({ ...PICKAXE, predicates: [] }), /predicate/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'inventory_min', itemId: 'wooden_pickaxe', count: 1 }] }), /itemId/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'unknown', itemId: 'minecraft:stone', count: 1 }] }), /type/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'position_within', x: 0, y: 0, z: 0, radius: -1 }] }), /radius/i);
});

test('requires an exact binding and rejects profile or trace mutation', () => {
	const contract = parseCompletionContract(PICKAXE);
	assert.throws(() => bindCompletionContract(contract, { goalRevision: 6, traceId: 'trace-agent-7', profile: {} }), /goalRevision/i);
	assert.throws(() => bindCompletionContract(contract, { goalRevision: 7, traceId: '', profile: {} }), /traceId/i);
});
