import assert from 'node:assert/strict';
import test from 'node:test';

import { LiveTaskViews } from '../src/live-task-view.mjs';
import { STRATEGY_REFERENCE_TOPICS, strategyHints } from '../src/minecraft-strategy-reference.mjs';
import { NATIVE_AGENT_INSTRUCTIONS, minecraftCapabilities, normalizeMinecraftToolCall, toolResultContent } from '../src/native-minecraft-tools.mjs';

test('strategy knowledge is an on-demand capabilities section with bounded whole topics', () => {
	const index = minecraftCapabilities({ section: 'strategy' });
	assert.equal(index.section, 'strategy');
	assert.match(index.detail, /queries perform no gameplay/i);
	assert.deepEqual(index.topics.map(({ id }) => id), [...STRATEGY_REFERENCE_TOPICS]);
	assert.deepEqual(minecraftCapabilities().strategyReference, { tool: 'capabilities', arguments: { section: 'strategy' } });
	for (const topic of STRATEGY_REFERENCE_TOPICS) {
		assert.deepEqual(normalizeMinecraftToolCall('capabilities', { section: 'strategy', topic }), { kind: 'capabilities', section: 'strategy', topic });
		const page = minecraftCapabilities({ section: 'strategy', topic });
		const rendered = toolResultContent(page).contentItems[0].text;
		assert.ok(Buffer.byteLength(rendered, 'utf8') <= 4_096, `${topic} stays a small single read`);
		assert.deepEqual(JSON.parse(rendered), page, 'transport keeps the whole topic');
	}
	for (const args of [{ section: 'strategy', topic: 'cheats' }, { section: 'strategy', topic: 'water', offset: 0 }, { section: 'program', topic: 'water' }]) {
		assert.throws(() => normalizeMinecraftToolCall('capabilities', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	assert.ok(!NATIVE_AGENT_INSTRUCTIONS.includes('strategy'), 'always-loaded instructions do not grow');
});

test('strategy topics carry the facts the play-test missed, as options', () => {
	const read = (topic) => minecraftCapabilities({ section: 'strategy', topic }).reference;
	assert.match(read('resources'), /Exposed ore[\s\S]*Structure loot[\s\S]*strip mining/i);
	assert.match(read('resources'), /Iron[^\n]*Y 16[^\n]*232/);
	assert.match(read('resources'), /Diamond[^\n]*-59/);
	assert.match(read('resources'), /below about Y -54[^\n]*lava/i);
	assert.match(read('water'), /Water is not a hazard like lava/);
	assert.match(read('water'), /airSecondsLeft/);
	assert.match(read('structures'), /Shipwreck[\s\S]*Ruined portal[\s\S]*Mineshaft/);
	assert.match(read('beat-the-game'), /water bucket over a lava pool/i);
	assert.match(read('end'), /end crystals/i);
});

test('task plans point to relevant strategy topics for unfinished steps only', async () => {
	assert.equal(strategyHints({ goal: 'say hello', plan: null }), null);
	const iron = strategyHints({ goal: 'get full iron armor', plan: null });
	assert.deepEqual(iron.topics, ['resources', 'structures']);
	assert.deepEqual(iron.read, { tool: 'capabilities', arguments: { section: 'strategy', topic: 'resources' } });
	const dragon = strategyHints({ goal: 'beat the game', plan: { steps: [
		{ label: 'Mine iron for bucket', detail: 'caves first', status: 'pending' },
		{ label: 'Swim the flooded cave', detail: '', status: 'complete' },
		{ label: 'Enter the Nether', detail: 'cast obsidian portal', status: 'pending' },
	] } });
	assert.ok(dragon.topics.includes('beat-the-game') && dragon.topics.includes('nether') && dragon.topics.includes('resources'));
	assert.ok(!dragon.topics.includes('water'), 'completed steps add no pointers');

	const record = { agentId: 'agent-s', currentGoal: 'Get iron armor', goalRevision: 1, provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium' };
	const views = new LiveTaskViews();
	await views.observe(record, { ready: true, observedAtEpochMs: 1000, player: { dead: false }, world: { worldId: 'world-s', dimension: 'minecraft:overworld' }, inventory: { items: [] }, blocks: [] });
	const read = await views.operate(record, { operation: 'read' });
	assert.deepEqual(read.strategy.topics, ['resources', 'structures']);
	assert.equal(read.advisory, true);
});
