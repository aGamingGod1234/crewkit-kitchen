import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { AgentWorkspaceManager } from '../src/agent-workspace.mjs';

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
