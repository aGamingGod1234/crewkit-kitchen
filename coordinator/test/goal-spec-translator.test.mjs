import assert from 'node:assert/strict';
import test from 'node:test';

import { GoalSpecTranslator, buildGoalSpecTranslatorPrompt } from '../src/goal-spec-translator.mjs';

const REQUEST = Object.freeze({
	requestId: '00000000-0000-4000-8000-000000000001',
	originalRequest: 'Get a good pickaxe',
	candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
});

test('translator prompt contains only the request, candidates, and allowlisted schema', () => {
	const prompt = buildGoalSpecTranslatorPrompt(REQUEST);
	assert.match(prompt, /Get a good pickaxe/);
	assert.match(prompt, /minecraft:iron_pickaxe/);
	assert.match(prompt, /inventory_contains/);
	assert.match(prompt, /dimensionId/);
	assert.doesNotMatch(prompt, /finish tool|completion tool/i);
});

test('translator receives bounded Minecraft rejection feedback without changing the authoritative request', async () => {
	const rejectedProposal = {
		requestId: REQUEST.requestId,
		summary: 'Stand at the requested position',
		predicate: { type: 'position_within', dimensionId: 'minecraft:the_nether', x: 0, y: 1_000, z: 0, radius: 1, stableTicks: 20 },
	};
	const translator = new GoalSpecTranslator({
		generate: async ({ request, prompt }) => {
			assert.deepEqual(request, REQUEST);
			assert.match(prompt, /Correction attempt 1/);
			assert.match(prompt, /INVALID_GOAL_PREDICATE/);
			assert.match(prompt, /"y":1000/);
			return { requestId: REQUEST.requestId, summary: 'Ask the operator', predicate: { type: 'operator_confirmed' } };
		},
	});
	await translator.translate(REQUEST, {
		correctiveFeedback: { attempt: 1, reasonCode: 'INVALID_GOAL_PREDICATE', rejectedProposal },
	});
	await assert.rejects(() => translator.translate(REQUEST, {
		correctiveFeedback: { attempt: 4, reasonCode: 'INVALID_GOAL_PREDICATE', rejectedProposal },
	}), /between 1 and 3/);
});

test('translator accepts a constrained proposal from the provider adapter', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-4000-8000-000000000001',
			summary: 'Obtain an iron or diamond pickaxe',
			predicate: {
				type: 'any_of',
				predicates: [
					{ type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
					{ type: 'inventory_contains', itemId: 'minecraft:diamond_pickaxe', count: 1 },
				],
			},
		}),
	});
	const proposal = await translator.translate(REQUEST);
	assert.equal(proposal.requestId, REQUEST.requestId);
	assert.equal(proposal.predicate.type, 'any_of');
});

test('translator rejects candidate IDs the server did not supply', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-4000-8000-000000000001',
			summary: 'Obtain a netherite pickaxe',
			predicate: { type: 'inventory_contains', itemId: 'minecraft:netherite_pickaxe', count: 1 },
		}),
	});
	await assert.rejects(() => translator.translate(REQUEST), error => error?.code === 'UNLISTED_GOAL_IDENTIFIER');
});

test('translator rejects mismatched request identities', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-0000-0000-000000000002',
			summary: 'wrong request',
			predicate: { type: 'operator_confirmed' },
		}),
	});
	await assert.rejects(() => translator.translate(REQUEST), error => error?.code === 'GOAL_SPEC_REQUEST_MISMATCH');
});

test('translator preserves compound item and kill requests as bounded all-of leaves', async () => {
	const request = {
		requestId: '00000000-0000-4000-8000-000000000011',
		originalRequest: 'Get an iron pickaxe and kill a zombie',
		candidateIds: ['minecraft:iron_pickaxe', 'minecraft:zombie'],
	};
	const translator = new GoalSpecTranslator({
		generate: async ({ prompt }) => {
			assert.match(prompt, /use all_of/i);
			assert.match(prompt, /at most 16 factual leaves/i);
			return {
				requestId: request.requestId,
				summary: 'Get the pickaxe and defeat the zombie',
				predicate: { type: 'all_of', predicates: [
					{ type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
					{ type: 'entity_killed_by_agent', entityType: 'minecraft:zombie', afterGoalStart: true },
				] },
			};
		},
	});
	const proposal = await translator.translate(request);
	assert.equal(proposal.predicate.type, 'all_of');
	assert.equal(proposal.predicate.predicates.length, 2);
});
