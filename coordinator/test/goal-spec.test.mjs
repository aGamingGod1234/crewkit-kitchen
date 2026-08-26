import assert from 'node:assert/strict';
import test from 'node:test';

import {
	parseGoalSpec,
	parseGoalSpecProposal,
	parseGoalSpecRequest,
	goalSpecFingerprint,
} from '../src/goal-spec.mjs';

const REQUEST_ID = '00000000-0000-4000-8000-000000000001';

test('goal spec proposal accepts only the closed authoritative predicate schema', () => {
	const proposal = {
		requestId: REQUEST_ID,
		summary: 'Obtain one iron pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
	};
	assert.deepEqual(parseGoalSpecProposal(proposal), proposal);
	assert.throws(() => parseGoalSpecProposal({
		requestId: REQUEST_ID,
		summary: 'weak',
		predicate: { type: 'action_success_count', actionType: 'craft_inventory', count: 1 },
	}), error => error?.code === 'UNKNOWN_GOAL_PREDICATE');
	assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { ...proposal.predicate, count: 0 } }), /count/i);
	assert.throws(() => parseGoalSpecProposal({ ...proposal, extra: true }), /field/i);
});

test('goal spec request is exact, bounded, and deduplicates no candidate IDs', () => {
	const request = {
		requestId: REQUEST_ID,
		originalRequest: 'Get a good pickaxe',
		candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
	};
	assert.deepEqual(parseGoalSpecRequest(request), request);
	assert.throws(() => parseGoalSpecRequest({ ...request, candidateIds: [...request.candidateIds, request.candidateIds[0]] }), /duplicate/i);
	assert.throws(() => parseGoalSpecRequest({ ...request, candidateIds: Array.from({ length: 65 }, (_, i) => `test:item_${i}`) }), /candidateIds/i);
	assert.throws(() => parseGoalSpecRequest({ ...request, originalRequest: 'x'.repeat(2_049) }), /originalRequest/i);
});

test('wire goal spec retains immutable predicate, creation tick, and fingerprint', () => {
	const spec = {
		originalRequest: 'Get an iron pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		createdAtTick: 1_200,
		fingerprint: '',
	};
	spec.fingerprint = goalSpecFingerprint(spec);
	assert.deepEqual(parseGoalSpec(spec), spec);
	assert.throws(() => parseGoalSpec({ ...spec, fingerprint: 'a'.repeat(63) }), /fingerprint/i);
	assert.throws(() => parseGoalSpec({ ...spec, originalRequest: 'Get a stone pickaxe' }), error => error?.code === 'GOAL_FINGERPRINT_MISMATCH');
	assert.throws(() => parseGoalSpec({ ...spec, predicate: { type: 'operator_confirmed', extra: true } }), /field/i);
});

test('goal fingerprints match Java canonical double encoding', () => {
	assert.equal(goalSpecFingerprint({
		originalRequest: 'Go to 12',
		predicate: { type: 'position_within', x: 12, y: 64.5, z: -0, radius: 1, stableTicks: 20 },
		createdAtTick: 1_200,
	}), '8576d9a940ee8561be9dfe2c6230ab0e620a227246c214824b230f5561e6ee88');
});

test('server-canonical Unicode whitespace is preserved while checking fingerprints', () => {
	const fields = {
		originalRequest: 'Get\u00a0iron',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		createdAtTick: 1_200,
	};
	const spec = { ...fields, fingerprint: goalSpecFingerprint(fields) };
	assert.equal(parseGoalSpec(spec).originalRequest, fields.originalRequest);
	assert.notEqual(spec.fingerprint, goalSpecFingerprint({ ...fields, originalRequest: 'Get iron' }));
});

test('compound goal predicates are bounded by depth and leaf count', () => {
	const leaf = { type: 'operator_confirmed' };
	assert.throws(() => parseGoalSpecProposal({
		requestId: REQUEST_ID, summary: 'too many',
		predicate: { type: 'all_of', predicates: Array.from({ length: 17 }, () => leaf) },
	}), /leaf|predicate/i);
	let nested = leaf;
	for (let i = 0; i < 6; i += 1) nested = { type: 'all_of', predicates: [nested] };
	assert.throws(() => parseGoalSpecProposal({ requestId: REQUEST_ID, summary: 'too deep', predicate: nested }), /depth/i);
});
