import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { AgentWorkspaceManager } from '../src/agent-workspace.mjs';
import { MinecraftAgentWorkspace } from '../src/minecraft-agent-workspace.mjs';

test('creates a stable provider-scoped directory for each agent', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-agent-workspaces-'));
	try {
		const manager = new AgentWorkspaceManager(root);
		const first = await manager.prepare('codex', 'agent-1');
		const second = await manager.prepare('codex', 'agent-2');
		const otherProvider = await manager.prepare('kimi', 'agent-1');

		assert.equal(first, path.join(root, 'codex', 'agent-1'));
		assert.notEqual(first, second);
		assert.notEqual(first, otherProvider);
		assert.equal((await stat(first)).isDirectory(), true);
		assert.equal(await manager.prepare('codex', 'agent-1'), first);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('rejects traversal and unsafe workspace segments', async () => {
	const manager = new AgentWorkspaceManager(path.join(os.tmpdir(), 'arena-agent-workspaces-safe'));
	await assert.rejects(() => manager.prepare('../codex', 'agent-1'), /safe path segment/);
	await assert.rejects(() => manager.prepare('codex', '..'), /safe path segment/);
	await assert.rejects(() => manager.prepare('codex', 'agent/escape'), /safe path segment/);
});

test('refreshes one shared native Minecraft workspace from bundled templates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-workspace-'));
	const templateRoot = path.join(root, 'templates');
	t.after(() => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# verified completion\n', 'utf8');
	await writeFile(
		path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'),
		'# native Minecraft control\n',
		'utf8',
	);

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	});
	const first = await workspace.prepare();
	assert.deepEqual(first, {
		cwd: path.join(root, 'runtime', 'minecraft-agent'),
		selectedCapabilityRoots: [path.join(root, 'runtime', 'minecraft-agent', '.codex', 'skills', 'minecraft-control')],
	});
	assert.equal(await readFile(path.join(first.cwd, 'AGENTS.md'), 'utf8'), '# verified completion\n');
	assert.equal(
		await readFile(path.join(first.cwd, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), 'utf8'),
		'# native Minecraft control\n',
	);

	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# refreshed verified completion\n', 'utf8');
	const second = await workspace.prepare();
	assert.deepEqual(second, first);
	assert.equal(await readFile(path.join(second.cwd, 'AGENTS.md'), 'utf8'), '# refreshed verified completion\n');
});
