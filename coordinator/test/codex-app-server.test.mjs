import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { buildCodexArgs, checkCodexModelProfile, CodexAgent, CodexProtocolError, CodexStdioTransport, resolveCodexLaunch } from '../src/codex-app-server.mjs';
import { finishDecisionJson } from './provider-decision-fixtures.mjs';

const model = {
	id: 'gpt-5.5',
	model: 'gpt-5.5',
	supportedReasoningEfforts: [{ reasoningEffort: 'xhigh', description: 'deep' }],
	serviceTiers: [{ id: 'fast', name: 'Fast', description: 'priority' }],
};

class FakeCodexTransport extends EventEmitter {
	calls = [];
	models = [model];
	autoComplete = true;

	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }
	methods() { return this.calls.filter((call) => !call.method.startsWith('$')).map((call) => call.method); }

	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return { data: this.models, nextCursor: null };
		if (method === 'thread/start') return { thread: { id: 'thread-1' } };
		if (method === 'turn/start') {
			if (this.autoComplete) queueMicrotask(() => {
				this.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-1', completedAtMs: 1, item: { id: 'item-1', type: 'agentMessage', text: finishDecisionJson() } } });
				this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } } });
			});
			return { turn: { id: 'turn-1', status: 'inProgress', items: [], error: null } };
		}
		if (method === 'turn/interrupt') return {};
		throw new Error(`Unexpected method ${method}`);
	}
}

class FakeStdioChild extends EventEmitter {
	constructor() {
		super();
		this.stdout = new EventEmitter();
		this.stderr = new EventEmitter();
		this.writes = [];
		this.stdin = { write: (line) => this.writes.push(JSON.parse(String(line).trim())) };
		this.killed = false;
		this.exitCode = null;
		this.signalCode = null;
	}

	kill() {
		this.killed = true;
		return true;
	}
}

const config = {
	agentId: 'agent-55',
	model: 'gpt-5.5',
	reasoningEffort: 'xhigh',
	serviceTier: 'fast',
	planningTimeoutMs: 2_000,
	cwd: 'C:\\arena-runtime',
};

const desktopRuntimeFiles = [
	'codex.exe',
	'codex-code-mode-host.exe',
	'codex-command-runner.exe',
	'codex-windows-sandbox-setup.exe',
];

function writeDesktopRuntime(resources, prefix) {
	mkdirSync(resources, { recursive: true });
	for (const name of desktopRuntimeFiles) writeFileSync(path.join(resources, name), `${prefix}-${name}`);
}

test('builds an isolated app-server process command with exact model profile', () => {
	assert.deepEqual(buildCodexArgs(config), [
		'app-server', '--stdio',
		'-c', 'model="gpt-5.5"',
		'-c', 'model_reasoning_effort="xhigh"',
		'-c', 'service_tier="fast"',
		'-c', 'features.fast_mode=true',
		'-c', 'mcp_servers={}',
		'-c', 'features.apps=false',
		'-c', 'features.browser_use=false',
		'-c', 'features.computer_use=false',
		'-c', 'features.goals=false',
		'-c', 'features.hooks=false',
		'-c', 'features.image_generation=false',
		'-c', 'features.multi_agent=false',
		'-c', 'features.plugins=false',
		'-c', 'features.skill_search=false',
		'-c', 'features.shell_tool=false',
		'-c', 'features.unified_exec=false',
		'-c', 'features.view_image=false',
	]);
});

test('launches the npm Codex JavaScript entrypoint directly on Windows', () => {
	const launch = resolveCodexLaunch(config, { platform: 'win32', env: { APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming' }, execPath: 'C:\\node.exe', existsSync: () => true });
	assert.equal(launch.command, 'C:\\node.exe');
	assert.match(launch.args[0], /@openai[\\/]codex[\\/]bin[\\/]codex\.js$/);
	assert.deepEqual(launch.args.slice(1), buildCodexArgs(config));
});

test('copies the complete installed Codex desktop runtime to a runnable private cache on Windows', () => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-desktop-'));
	try {
		const programFiles = path.join(root, 'Program Files');
		const packageName = 'OpenAI.Codex_26.818.8289.0_x64__2p2nqsd0c76g0';
		const resources = path.join(programFiles, 'WindowsApps', packageName, 'app', 'resources');
		const localAppData = path.join(root, 'Local');
		writeDesktopRuntime(resources, 'desktop-codex-fixture');

		const launch = resolveCodexLaunch(config, {
			platform: 'win32',
			env: {
				appdata: path.join(root, 'Roaming'),
				systemdrive: root,
			},
			windowsPackageLocations: [],
			execPath: 'C:\\node.exe',
		});

		const cachedCli = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.818.8289.0', 'codex.exe');
		assert.equal(launch.command, cachedCli);
		assert.deepEqual(launch.args, buildCodexArgs(config));
		for (const name of desktopRuntimeFiles) {
			assert.equal(readFileSync(path.join(path.dirname(cachedCli), name), 'utf8'), `desktop-codex-fixture-${name}`);
		}
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('uses the registered Codex Appx location when WindowsApps cannot be enumerated', () => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-appx-'));
	try {
		const packageRoot = path.join(root, 'OpenAI.Codex_26.818.8289.0_x64__2p2nqsd0c76g0');
		const resources = path.join(packageRoot, 'app', 'resources');
		const localAppData = path.join(root, 'Local');
		writeDesktopRuntime(resources, 'registered-appx-codex-fixture');

		const launch = resolveCodexLaunch(config, {
			platform: 'win32',
			env: { LOCALAPPDATA: localAppData },
			windowsPackageLocations: [packageRoot],
		});

		const cachedCli = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.818.8289.0', 'codex.exe');
		assert.equal(launch.command, cachedCli);
		for (const name of desktopRuntimeFiles) {
			assert.equal(readFileSync(path.join(path.dirname(cachedCli), name), 'utf8'), `registered-appx-codex-fixture-${name}`);
		}
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('reuses a complete private desktop runtime before any Windows package discovery', () => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-cache-'));
	try {
		const localAppData = path.join(root, 'Local');
		const cachedRuntime = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.818.8289.0');
		writeDesktopRuntime(cachedRuntime, 'cached-desktop-runtime');
		let discoveryCalls = 0;

		const launch = resolveCodexLaunch(config, {
			platform: 'win32',
			env: { LOCALAPPDATA: localAppData },
			spawnSync: () => {
				discoveryCalls += 1;
				throw new Error('package discovery must not run when the complete cache is ready');
			},
		});

		assert.equal(launch.command, path.join(cachedRuntime, 'codex.exe'));
		assert.equal(discoveryCalls, 0);
		assert.deepEqual(launch.args, buildCodexArgs(config));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('desktop runtime selection observes cache upgrades and rejects incomplete versions', () => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-cache-generation-'));
	try {
		const localAppData = path.join(root, 'Local');
		const firstRuntime = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.818.8289.0');
		const upgradedRuntime = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.819.1.0');
		writeDesktopRuntime(firstRuntime, 'first-runtime');
		const dependencies = {
			platform: 'win32',
			env: { LOCALAPPDATA: localAppData },
			spawnSync: () => { throw new Error('complete cache must avoid package discovery'); },
		};

		assert.equal(resolveCodexLaunch(config, dependencies).command, path.join(firstRuntime, 'codex.exe'));
		writeDesktopRuntime(upgradedRuntime, 'upgraded-runtime');
		assert.equal(resolveCodexLaunch(config, dependencies).command, path.join(upgradedRuntime, 'codex.exe'));

		unlinkSync(path.join(upgradedRuntime, 'codex-command-runner.exe'));
		assert.equal(resolveCodexLaunch(config, dependencies).command, path.join(firstRuntime, 'codex.exe'));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('reuses the user profile cache when a packaged launcher redirects LOCALAPPDATA', () => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-packaged-env-'));
	try {
		const userProfile = path.join(root, 'Users', 'lucas');
		const localAppData = path.join(userProfile, 'AppData', 'Local');
		const redirectedLocalAppData = path.join(localAppData, 'Packages', 'Minecraft', 'LocalCache', 'Local');
		const cachedRuntime = path.join(localAppData, 'ArenaAgents', 'codex-runtime', '26.818.8289.0');
		writeDesktopRuntime(cachedRuntime, 'user-profile-desktop-runtime');
		let discoveryCalls = 0;

		const launch = resolveCodexLaunch(config, {
			platform: 'win32',
			env: { LOCALAPPDATA: redirectedLocalAppData, USERPROFILE: userProfile },
			spawnSync: () => {
				discoveryCalls += 1;
				throw new Error('package discovery must not run when the user profile cache is ready');
			},
		});

		assert.equal(launch.command, path.join(cachedRuntime, 'codex.exe'));
		assert.equal(discoveryCalls, 0);
		assert.deepEqual(launch.args, buildCodexArgs(config));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('Codex launch retains provider configuration but strips bridge credentials', () => {
	const launch = resolveCodexLaunch(config, {
		platform: 'win32',
		env: {
			APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming',
			PATH: 'C:\\Windows\\System32',
			ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret',
			ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\bridge.secret',
		},
		execPath: 'C:\\node.exe',
		existsSync: () => true,
	});
	assert.equal(launch.environment.PATH, 'C:\\Windows\\System32');
	assert.equal(launch.environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(launch.environment.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined);
});

test('a stopped child late spawn error cannot orphan its running replacement transport', async () => {
	const first = new FakeStdioChild();
	const replacement = new FakeStdioChild();
	let spawnCalls = 0;
	const transport = new CodexStdioTransport(config, {
		spawn: () => {
			spawnCalls += 1;
			if (spawnCalls === 1) return first;
			queueMicrotask(() => replacement.emit('spawn'));
			return replacement;
		},
		stopTimeoutMs: 1,
	});
	const obsoleteStart = transport.start();
	const obsoleteFailure = assert.rejects(obsoleteStart, (error) => error?.code === 'SPAWN_FAILED');
	try {
		await transport.stop();
		await transport.start();

		first.emit('error', new Error('old child failed after replacement started'));
		await obsoleteFailure;
		assert.doesNotThrow(() => transport.notify('replacement/alive'));
		assert.deepEqual(replacement.writes, [{ method: 'replacement/alive', params: {} }]);
	} finally {
		await transport.stop();
	}
});

test('Codex child stderr crosses the shared bounded diagnostic sanitizer', async () => {
	const child = new FakeStdioChild();
	const diagnostics = [];
	const transport = new CodexStdioTransport(config, { spawn: () => child, stopTimeoutMs: 1 });
	transport.on('diagnostic', (message) => diagnostics.push(message));
	const started = transport.start();
	child.emit('spawn');
	await started;
	try {
		child.stderr.emit('data', Buffer.from('Authorization: Bearer child-secret at C:\\private\\codex.log ' + 'x'.repeat(8_000)));
		assert.equal(diagnostics.length, 1);
		assert.doesNotMatch(diagnostics[0], /child-secret|private/);
		assert.ok(Buffer.byteLength(diagnostics[0], 'utf8') <= 4_096);
	} finally {
		await transport.stop();
	}
});

test('Codex desktop discovery writes a shared-sanitized bounded failure', (t) => {
	const root = mkdtempSync(path.join(tmpdir(), 'arena-codex-secret-path-'));
	const diagnostics = [];
	t.mock.method(console, 'error', (...values) => diagnostics.push(values.join(' ')));
	try {
		resolveCodexLaunch(config, {
			platform: 'win32',
			env: { ProgramFiles: path.join(root, 'Program Files'), LOCALAPPDATA: path.join(root, 'Local') },
			windowsPackageLocations: [],
		});
		assert.equal(diagnostics.length, 1);
		assert.doesNotMatch(diagnostics[0], /arena-codex-secret-path/i);
		assert.ok(Buffer.byteLength(diagnostics[0], 'utf8') <= 4_096);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

test('initializes before catalog validation and thread start', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	assert.deepEqual(transport.methods(), ['initialize', 'initialized', 'model/list', 'thread/start']);
	const initialize = transport.calls.find((call) => call.method === 'initialize').params;
	assert.deepEqual(initialize.capabilities, { experimentalApi: true, requestAttestation: false });
	assert.equal(transport.calls.find((call) => call.method === 'model/list').params.includeHidden, false);
	const thread = transport.calls.find((call) => call.method === 'thread/start').params;
	assert.deepEqual({ model: thread.model, serviceTier: thread.serviceTier, approvalPolicy: thread.approvalPolicy, sandbox: thread.sandbox, dynamicTools: thread.dynamicTools, environments: thread.environments }, {
		model: 'gpt-5.5', serviceTier: 'fast', approvalPolicy: 'never', sandbox: 'read-only', dynamicTools: [], environments: [],
	});
	await agent.stop();
});

test('runs a persistent-thread turn with exact effort and extracts the final agent message', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	const decision = await agent.decide('compact state');
	assert.equal(decision.directive, 'finish');
	const turn = transport.calls.find((call) => call.method === 'turn/start').params;
	assert.equal(turn.threadId, 'thread-1');
	assert.equal(turn.model, 'gpt-5.5');
	assert.equal(turn.effort, 'xhigh');
	assert.equal(turn.serviceTier, 'fast');
	assert.deepEqual(turn.environments, []);
	await agent.stop();
});

test('uses streamed agent-message deltas when a completed message item is absent', async () => {
	const transport = new FakeCodexTransport();
	transport.autoComplete = false;
	const agent = new CodexAgent(config, transport);
	await agent.start();
	const decisionPromise = agent.decide('compact state');
	await Promise.resolve();
	const text = finishDecisionJson();
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: text } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } } });
	assert.equal((await decisionPromise).directive, 'finish');
	await agent.stop();
});

test('rejects an over-budget streamed Codex decision before parsing it', async () => {
	const transport = new FakeCodexTransport();
	transport.autoComplete = false;
	const agent = new CodexAgent({ ...config, maxDecisionBytes: 32 }, transport);
	await agent.start();
	const decision = agent.decide('compact state');
	await Promise.resolve();
	transport.emit('notification', {
		method: 'item/agentMessage/delta',
		params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: 'x'.repeat(33) },
	});
	transport.emit('notification', {
		method: 'turn/completed',
		params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed', items: [], error: null } },
	});
	await assert.rejects(decision, (error) => error?.code === 'TURN_OUTPUT_LIMIT');
	await agent.stop();
});

test('fails closed when model effort or Fast tier is absent', async () => {
	const transport = new FakeCodexTransport();
	transport.models = [{ ...model, supportedReasoningEfforts: [{ reasoningEffort: 'high' }] }];
	await assert.rejects(() => new CodexAgent(config, transport).start(), (error) => error instanceof CodexProtocolError && error.code === 'MODEL_PROFILE_UNAVAILABLE');
});

test('checks a live catalog profile without starting a planner thread', async () => {
	const transport = new FakeCodexTransport();
	const checked = await checkCodexModelProfile(config, transport);
	assert.equal(checked.model, 'gpt-5.5');
	assert.deepEqual(transport.methods(), ['initialize', 'initialized', 'model/list']);
	assert.equal(transport.calls.find((call) => call.method === 'model/list').params.includeHidden, false);
});

test('restarts a failed app-server into a fresh persistent thread', async () => {
	const transport = new FakeCodexTransport();
	const agent = new CodexAgent(config, transport);
	await agent.start();
	await agent.restart();
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 2);
	assert.equal(transport.calls.filter((call) => call.method === '$start').length, 2);
	assert.equal(transport.calls.filter((call) => call.method === '$stop').length, 1);
	await agent.stop();
});
