import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { CONTROL_REFERENCE_TOPICS } from '../src/minecraft-control-reference.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, minecraftCapabilities, normalizeMinecraftToolCall, toolResultContent } from '../src/native-minecraft-tools.mjs';

const referenceUrl = new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/control-reference.md', import.meta.url);

test('complete control reference is lossless through bounded existing-tool pages', async () => {
	const source = await readFile(referenceUrl, 'utf8');
	const index = minecraftCapabilities({ section: 'control' });
	assert.equal(index.section, 'control');
	assert.deepEqual(index.completeReference.arguments, { section: 'control', topic: 'all', offset: 0 });
	assert.ok(index.topics.some(({ id }) => id === 'validation'));
	let text = '', offset = 0, pages = 0;
	for (;;) {
		const args = { section: 'control', topic: 'all', offset };
		assert.deepEqual(normalizeMinecraftToolCall('capabilities', args), { kind: 'capabilities', ...args });
		const page = minecraftCapabilities(args);
		const rendered = toolResultContent(page).contentItems[0].text;
		assert.ok(Buffer.byteLength(rendered, 'utf8') <= 16_384);
		assert.deepEqual(JSON.parse(rendered), page, 'transport must not truncate a reference page');
		assert.equal(page.offset, offset);
		assert.equal(page.coverage.returnedCharacters, Array.from(page.reference).length);
		text += page.reference;
		pages += 1;
		if (page.nextOffset === undefined) {
			assert.equal(page.coverage.complete, true);
			break;
		}
		assert.equal(page.coverage.complete, false);
		assert.ok(page.nextOffset > offset);
		offset = page.nextOffset;
	}
	assert.ok(pages > 1, 'full original documentation remains available beyond one transport page');
	assert.equal(text, source, 'every byte of the original instructions and examples is recoverable');
});

test('every native tool and action has its narrow original contract available', () => {
	const expected = [
		...MINECRAFT_DYNAMIC_TOOLS.map(({ name }) => `tool:${name}`),
		...MINECRAFT_DYNAMIC_TOOLS.find(({ name }) => name === 'act').inputSchema.properties.actionType.enum.map((name) => `action:${name}`),
	];
	for (const topic of expected) {
		assert.ok(CONTROL_REFERENCE_TOPICS.includes(topic), `missing reference topic ${topic}`);
		const reference = minecraftCapabilities({ section: 'control', topic });
		assert.ok(reference.reference.includes('```json executor-call'), `missing accepted syntax for ${topic}`);
		assert.equal(JSON.parse(toolResultContent(reference).contentItems[0].text).reference, reference.reference);
	}
});

test('compact instructions keep decision, danger, recovery and verification rules with strong references', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', import.meta.url), 'utf8');
	const original = await readFile(referenceUrl, 'utf8');
	assert.ok(Buffer.byteLength(skill) < Buffer.byteLength(original) * 0.35, 'always-loaded instructions should remove detailed example inflation');
	for (const rule of [
		/You choose every strategy, target, route, reaction and retry/,
		/freshness\.fresh:true postAction or program facts before another observe/,
		/Missing or coverage-omitted facts are unknown/,
		/choose dependent actions after their results/,
		/Damage takes priority over gathering/,
		/after:"reconsider"/,
		/Default unhandled damage\/fire\/lava\/suffocation\/hazardous-fall policy pauses unrelated work/,
		/Death interrupts the same project/,
		/Compare recovering earlier equipment with rebuilding/,
		/finish asks the server to verify goalSpec/,
		/AWAITING_OPERATOR_CONFIRMATION means report once with say, end the turn/,
		/Default minecraft:inventory\/containerId0 exists during ordinary gameplay and does not block it/,
		/Read topic:"tool:<name>" before an unfamiliar native tool/,
		/topic:"action:<actionType>" before an unfamiliar act action/,
		/Follow each returned nextOffset/,
		/references\/control-reference\.md/,
	]) assert.match(skill, rule);
});

test('reference fields stay scoped, bounded and explicitly read-only', () => {
	for (const args of [
		{ topic: 'all' }, { section: 'all', offset: 0 }, { section: 'program', topic: 'all' },
		{ section: 'control', topic: 'private-files' }, { section: 'control', offset: 0 },
		{ section: 'control', topic: 'all', offset: -1 }, { section: 'control', topic: 'all', offset: 0.5 },
		{ section: 'control', topic: 'all', path: '../auth.json' },
	]) assert.throws(() => normalizeMinecraftToolCall('capabilities', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	assert.throws(() => minecraftCapabilities({ section: 'control', topic: 'all', offset: 1_000_000 }), /exceeds this topic/);
	assert.match(minecraftCapabilities({ section: 'control' }).detail, /queries perform no gameplay/);
});
