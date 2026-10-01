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
		cwd: path.join(root, 'runtime', 'minecraft-agent', 'workspace'),
		codexHome: path.join(root, 'runtime', 'minecraft-agent', '.codex-home'),
		permissionProfile: 'minecraft',
		instructions: '# verified completion\n',
		skillInstructions: '# native Minecraft control\n',
		selectedCapabilityRoots: [{
			id: 'minecraft-control',
			location: {
				type: 'environment',
				environmentId: 'local',
				path: path.join(root, 'runtime', 'minecraft-agent', 'workspace', '.codex', 'skills', 'minecraft-control'),
			},
		}],
	});
	assert.ok(path.relative(first.cwd, first.codexHome).startsWith('..'), 'provider credentials must be outside the model workspace');
	assert.match(await readFile(path.join(first.codexHome, 'config.toml'), 'utf8'), /^default_permissions = "minecraft"\n\n\[permissions\.minecraft\.filesystem\]/);
	assert.equal(await readFile(path.join(first.cwd, 'AGENTS.md'), 'utf8'), '# verified completion\n');
	assert.equal(
		await readFile(path.join(first.cwd, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), 'utf8'),
		'# native Minecraft control\n',
	);

	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# refreshed verified completion\n', 'utf8');
	const second = await workspace.prepare();
	assert.equal(second.cwd, first.cwd);
	assert.deepEqual(second.selectedCapabilityRoots, first.selectedCapabilityRoots);
	assert.notEqual(second.instructions, first.instructions);
	assert.equal(await readFile(path.join(second.cwd, 'AGENTS.md'), 'utf8'), '# refreshed verified completion\n');
	assert.equal(second.instructions, '# refreshed verified completion\n');
});

test('does not rewrite unchanged shared Minecraft templates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-workspace-cache-'));
	const templateRoot = path.join(root, 'templates');
	t.after(() => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# agents\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# skill\n', 'utf8');

	let writes = 0;
	const workspace = new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot }, {
		sourceCodexHome: path.join(root, 'missing-user-codex-home'),
		fs: {
			async writeFile(...args) {
				writes += 1;
				return writeFile(...args);
			},
		},
	});
	await workspace.prepare();
	assert.equal(writes, 3);
	await workspace.prepare();
	assert.equal(writes, 3);

	await writeFile(path.join(workspace.root, 'workspace', 'AGENTS.md'), '# externally changed\n', 'utf8');
	await workspace.prepare();
	assert.equal(writes, 4);
	assert.equal(await readFile(path.join(workspace.root, 'workspace', 'AGENTS.md'), 'utf8'), '# agents\n');
});

test('synchronizes only auth into the isolated Codex home', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'user-codex-home');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"preserve"}\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'AGENTS.md'), '# Lucas identity must not cross the boundary\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'config.toml'), 'model = "user-model"\n', 'utf8');
	const staleCodexHome = path.join(root, 'runtime', 'minecraft-agent', '.codex-home');
	await mkdir(path.join(staleCodexHome, 'memories'), { recursive: true });
	await writeFile(path.join(staleCodexHome, 'AGENTS.md'), '# stale identity\n', 'utf8');
	await writeFile(path.join(staleCodexHome, 'config.toml'), 'model = "stale-model"\n', 'utf8');
	await writeFile(path.join(staleCodexHome, 'memories', 'old.md'), 'stale\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	}, { sourceCodexHome });
	const prepared = await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"preserve"}\n');
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'AGENTS.md'), 'utf8'), { code: 'ENOENT' });
	assert.match(await readFile(path.join(prepared.codexHome, 'config.toml'), 'utf8'), /":root" = "deny"/);
	assert.equal(prepared.permissionProfile, 'minecraft');
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'memories', 'old.md'), 'utf8'), { code: 'ENOENT' });
	await writeFile(path.join(prepared.codexHome, 'state.sqlite'), 'runtime state\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'state.sqlite'), 'utf8'), 'runtime state\n');
	assert.equal(await readFile(path.join(prepared.cwd, 'AGENTS.md'), 'utf8'), '# Minecraft instructions\n');
});

test('preserves auth when the configured source is the isolated Codex home', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-same-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const workspaceRoot = path.join(root, 'runtime', 'minecraft-agent');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	await mkdir(path.join(workspaceRoot, '.codex-home'), { recursive: true });
	await writeFile(path.join(workspaceRoot, '.codex-home', 'auth.json'), '{"tokens":"keep"}\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({ root: workspaceRoot, templateRoot }, {
		sourceCodexHome: path.join(workspaceRoot, '.codex-home'),
	});
	const prepared = await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"keep"}\n');
});

test('re-syncs Codex auth when isolated credentials are missing or source rotates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-auth-resync-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'user-codex-home');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	}, { sourceCodexHome });
	const prepared = await workspace.prepare();
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), { code: 'ENOENT' });

	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"first"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"first"}\n');

	await rm(path.join(prepared.codexHome, 'auth.json'));
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"first"}\n');

	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"rotated"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"rotated"}\n');

	await writeFile(path.join(prepared.codexHome, 'auth.json'), '{"tokens":"isolated-refresh"}\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"source-again"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(
		await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'),
		'{"tokens":"isolated-refresh"}\n',
	);
});

test('uses a newer refresh of the same Codex login without replacing newer or different-account auth', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-auth-refresh-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'source');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n');
	const auth = (accountId, lastRefresh) => `${JSON.stringify({
		auth_mode: 'chatgpt', last_refresh: lastRefresh, tokens: { account_id: accountId, access_token: 'fixture' },
	})}\n`;
	const sourceAuth = path.join(sourceCodexHome, 'auth.json');
	const sourceContent = auth('same-account', '2026-09-25T00:00:00Z');
	await writeFile(sourceAuth, sourceContent);
	const workspace = new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot }, { sourceCodexHome });
	const prepared = await workspace.prepare();
	const isolatedAuth = path.join(prepared.codexHome, 'auth.json');
	await writeFile(isolatedAuth, auth('same-account', '2026-09-05T00:00:00Z'));
	await workspace.prepare();
	assert.equal(await readFile(isolatedAuth, 'utf8'), sourceContent, 'unchanged source beats stale isolated refresh');
	await writeFile(isolatedAuth, auth('same-account', '2026-09-05T00:00:00Z'));
	await rm(path.join(root, 'runtime', '.auth-source.sha256'));
	await new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot }, { sourceCodexHome }).prepare();
	assert.equal(await readFile(isolatedAuth, 'utf8'), sourceContent, 'legacy install with no baseline also recovers stale auth');

	const newerIsolated = auth('same-account', '2026-09-26T00:00:00Z');
	await writeFile(isolatedAuth, newerIsolated);
	await workspace.prepare();
	assert.equal(await readFile(isolatedAuth, 'utf8'), newerIsolated, 'newer isolated refresh remains usable');

	await writeFile(sourceAuth, auth('other-account', '2026-09-27T00:00:00Z'));
	await workspace.prepare();
	assert.equal(await readFile(isolatedAuth, 'utf8'), newerIsolated, 'another account cannot replace independent auth');
});

test('uses the platform Codex home for auth when CODEX_HOME is unset', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-default-codex-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	const previousCodexHome = process.env.CODEX_HOME;
	delete process.env.CODEX_HOME;
	try {
		const workspace = new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot });
		const prepared = await workspace.prepare();
		const sourceAuth = path.join(os.homedir(), '.codex', 'auth.json');
		try {
			assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), await readFile(sourceAuth, 'utf8'));
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
		}
		assert.equal(await readFile(path.join(prepared.cwd, 'AGENTS.md'), 'utf8'), '# Minecraft instructions\n');
	} finally {
		if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
		else process.env.CODEX_HOME = previousCodexHome;
	}
});

for (const isolatedRefresh of [false, true]) test(`workspace restart preserves independently refreshed auth: ${isolatedRefresh}`, async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-auth-restart-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'source');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n');
	const options = { root: path.join(root, 'runtime'), templateRoot };
	const sourceAuth = path.join(sourceCodexHome, 'auth.json');
	await writeFile(sourceAuth, '{"tokens":"original-fixture"}\n');
	const first = await new MinecraftAgentWorkspace(options, { sourceCodexHome }).prepare();
	const isolatedAuth = path.join(first.codexHome, 'auth.json');
	if (isolatedRefresh) await writeFile(isolatedAuth, '{"tokens":"provider-refreshed-fixture"}\n');
	else await writeFile(sourceAuth, '{"tokens":"new-source-fixture"}\n');
	const restarted = new MinecraftAgentWorkspace(options, { sourceCodexHome });
	await restarted.prepare();
	assert.equal(await readFile(isolatedAuth, 'utf8'), isolatedRefresh ? '{"tokens":"provider-refreshed-fixture"}\n' : '{"tokens":"new-source-fixture"}\n');
	assert.equal(await readFile(sourceAuth, 'utf8'), isolatedRefresh ? '{"tokens":"original-fixture"}\n' : '{"tokens":"new-source-fixture"}\n');
});
