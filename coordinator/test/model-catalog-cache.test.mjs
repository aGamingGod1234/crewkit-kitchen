import assert from 'node:assert/strict';
import test from 'node:test';

import { ModelCatalogCache } from '../src/model-catalog-cache.mjs';

const MODEL = {
	id: 'gpt-5.6-sol',
	model: 'gpt-5.6-sol',
	supportedReasoningEfforts: [{ reasoningEffort: 'high' }, { reasoningEffort: 'xhigh' }],
	serviceTiers: [{ id: 'priority' }],
};

test('catalog refreshes once, normalizes capabilities, and reconciles profiles', async () => {
	let calls = 0;
	let now = 100;
	const cache = new ModelCatalogCache(async () => { calls += 1; return [MODEL]; }, { ttlMs: 50, now: () => now });
	await Promise.all([cache.refresh(), cache.refresh()]);
	assert.equal(calls, 1);
	assert.deepEqual(cache.find('gpt-5.6-sol').reasoningEfforts, ['high', 'xhigh']);
	const result = cache.reconcileProfiles([
		{ agentId: 'valid', model: 'gpt-5.6-sol', reasoningEffort: 'high' },
		{ agentId: 'invalid', model: 'missing', reasoningEffort: 'high' },
	]);
	assert.deepEqual(result.valid.map((entry) => entry.agentId), ['valid']);
	assert.deepEqual(result.invalid.map((entry) => entry.agentId), ['invalid']);
	now = 151;
	assert.equal(cache.stale, true);
});

test('catalog preserves Codex speed tiers and raw catalog naming variants', async () => {
	const cache = new ModelCatalogCache(async () => [{
		slug: 'gpt-5.6-sol',
		display_name: 'GPT-5.6-Sol',
		supported_reasoning_levels: [{ effort: 'low' }, { effort: 'ultra' }],
		service_tiers: [{ id: 'priority' }],
		additional_speed_tiers: ['fast'],
	}]);
	await cache.refresh();
	assert.deepEqual(cache.find('gpt-5.6-sol'), {
		id: 'gpt-5.6-sol',
		model: 'gpt-5.6-sol',
		displayName: 'GPT-5.6-Sol',
		reasoningEfforts: ['low', 'ultra'],
		serviceTiers: ['priority', 'fast'],
	});
	assert.equal(cache.assertSupported('gpt-5.6-sol', 'ultra', 'fast').id, 'gpt-5.6-sol');
});
