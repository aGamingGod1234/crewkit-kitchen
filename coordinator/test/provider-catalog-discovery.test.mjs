import assert from 'node:assert/strict';
import test from 'node:test';

import {
	discoverAntigravityCatalog,
	parseAntigravityModelsOutput,
} from '../src/provider-catalog-discovery.mjs';

test('Antigravity parser derives stable slugs and preserves CLI effort order', () => {
	assert.deepEqual(parseAntigravityModelsOutput([
		'gemini-3.6-flash-high\tGemini 3.6 Flash (High)',
		'gemini-3.6-flash-medium\tGemini 3.6 Flash (Medium)',
		'gemini-3.6-flash-low\tGemini 3.6 Flash (Low)',
		'claude-sonnet-4-6-thinking\tClaude Sonnet 4.6 (Thinking)',
		'not model metadata',
	].join('\n')), [
		{ id: 'gemini-3.6-flash', model: 'gemini-3.6-flash', displayName: 'Gemini 3.6 Flash', reasoningEfforts: ['high', 'medium', 'low'], serviceTiers: [] },
		{ id: 'claude-sonnet-4-6', model: 'claude-sonnet-4-6', displayName: 'Claude Sonnet 4.6', reasoningEfforts: ['thinking'], serviceTiers: [] },
	]);
});

test('CLI discovery adapter passes only the catalog command, explicit environment, and parse stdout', async () => {
	const calls = [];
	const environment = { PATH: 'test', GEMINI_API_KEY: 'provider-key' };
	const execFile = (command, args, options, callback) => {
		calls.push({ command, args, options });
		callback(null, 'gemini-3.1-pro-low\tGemini 3.1 Pro (Low)\n', '');
	};
	assert.deepEqual(await discoverAntigravityCatalog({ execFile, environment }), [
		{ id: 'gemini-3.1-pro', model: 'gemini-3.1-pro', displayName: 'Gemini 3.1 Pro', reasoningEfforts: ['low'], serviceTiers: [] },
	]);
	assert.deepEqual(calls[0].args, ['models']);
	assert.equal(calls[0].options.env, environment);

});
