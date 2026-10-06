import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';

import { resolveAntigravityLaunch } from '../src/antigravity-service.mjs';
import { resolveClaudeLaunch } from '../src/claude-service.mjs';
import { ProviderCliHealthMonitor, createDisabledProviderCliHealthMonitor, probeProviderCli } from '../src/provider-cli-health.mjs';
import { findExecutableOnPath } from '../src/provider-environment.mjs';

const CLAUDE_VERSION = '2.1.288 (Claude Code)';
const CODEX_VERSION = 'codex-cli 0.160.0';
const AGY_VERSION = '1.2.8';
const CLAUDE_LOGGED_IN = '{\n  "loggedIn": true,\n  "authMethod": "claude.ai",\n  "email": "player@example.invalid"\n}';
const CLAUDE_LOGGED_OUT = '{\n  "loggedIn": false\n}';
const AGY_SIGNED_OUT = 'Fetching available models...\nError: Please sign in to view available models. Launch the CLI without arguments to sign in.';
const TOOLS = 'C:\\tools';
// Codex launch resolution scans real Windows install locations, so Codex probes use a POSIX fixture.
const POSIX_BIN = '/usr/bin';
const POSIX_ENVIRONMENT = Object.freeze({ PATH: POSIX_BIN });
const WINDOWS_ENVIRONMENT = Object.freeze({ PATH: TOOLS, PATHEXT: '.COM;.EXE;.BAT;.CMD', APPDATA: 'C:\\Users\\player\\AppData\\Roaming', LOCALAPPDATA: 'C:\\Users\\player\\AppData\\Local', USERPROFILE: 'C:\\Users\\player' });

function normalize(value) { return String(value).replace(/\\/g, '/').toLowerCase(); }
function existsIn(...files) {
	const known = new Set(files.map(normalize));
	return (candidate) => known.has(normalize(candidate));
}
function exit(code, stdout = '', stderr = '') {
	return { error: Object.assign(new Error(`Command failed with exit code ${code}`), { code, killed: false, signal: null }), stdout, stderr };
}
function timedOut() { return { error: Object.assign(new Error('Command timed out'), { code: null, killed: true, signal: 'SIGTERM' }), stdout: '', stderr: '' }; }
function spawnFailure(code) { return { error: Object.assign(new Error(`spawn ${code}`), { code, syscall: 'spawn' }), stdout: '', stderr: '' }; }
function fakeExecFile(script) {
	const calls = [];
	const execFile = (command, args, options, callback) => {
		calls.push({ command, args, options });
		const result = script(command, args, options, calls.length);
		queueMicrotask(() => callback(result.error ?? null, result.stdout ?? '', result.stderr ?? ''));
		return { stdin: { end() {} } };
	};
	execFile.calls = calls;
	return execFile;
}
function lastArgs(args) { return args.slice(-2).join(' '); }
function probe(provider, { files, script, environment = WINDOWS_ENVIRONMENT, config = {}, platform = 'win32', ...rest } = {}) {
	const execFile = fakeExecFile(script ?? (() => ({ stdout: '' })));
	return probeProviderCli(provider, { config, environment, platform, existsSync: existsIn(...(files ?? [])), execFile, execPath: 'C:\\node\\node.exe', now: () => 1_000, ...rest }).then((health) => ({ health, calls: execFile.calls }));
}

test('reports a missing Claude CLI without spawning anything', async () => {
	const { health, calls } = await probe('claude', { files: [] });
	assert.equal(health.status, 'missing');
	assert.equal(health.code, 'PROVIDER_CLI_MISSING');
	assert.equal(health.executable, 'claude');
	assert.equal(health.version, null);
	assert.equal(health.checkedAtEpochMs, 1_000);
	assert.match(health.message, /^Claude Code CLI is not installed on the server machine \(no 'claude' found on PATH\)\. Install it, sign in with 'claude auth login', then restart Minecraft and relaunch this agent\. This agent cannot think until that is fixed\.$/);
	assert.equal(calls.length, 0);
	assert.ok(Object.isFrozen(health));
});

test('reports a missing Codex and Antigravity CLI with provider-specific install hints', async () => {
	const codex = await probe('codex', { files: [], platform: 'linux', environment: POSIX_ENVIRONMENT });
	assert.equal(codex.health.code, 'PROVIDER_CLI_MISSING');
	assert.match(codex.health.message, /Codex CLI is not installed .*no 'codex' found on PATH.*sign in with 'codex login'/);
	const gemini = await probe('gemini', { files: [] });
	assert.equal(gemini.health.code, 'PROVIDER_CLI_MISSING');
	assert.match(gemini.health.message, /Antigravity CLI is not installed .*no 'agy' found on PATH.*run 'agy' once to sign in/);
});

test('reports a configured executable path that does not exist', async () => {
	const { health } = await probe('claude', { files: [], config: { executable: 'D:\\apps\\claude.exe' } });
	assert.equal(health.status, 'missing');
	assert.match(health.message, /configured path 'D:\\apps\\claude\.exe' does not exist/);
});

test('reports a broken CLI when --version exits non-zero, including a sanitized stderr excerpt', async () => {
	const claudeExe = path.join(TOOLS, 'claude.exe');
	const { health, calls } = await probe('claude', { files: [claudeExe], script: () => exit(1, '', 'Error: Cannot find module C:\\Users\\player\\secret\\cli.js\nToken: Bearer sk-ant-private') });
	assert.equal(health.status, 'broken');
	assert.equal(health.code, 'PROVIDER_CLI_BROKEN');
	assert.equal(health.executable, claudeExe);
	assert.equal(calls.length, 1, 'authentication is not checked for a broken CLI');
	assert.equal(calls[0].command, claudeExe);
	assert.deepEqual(calls[0].args, ['--version']);
	assert.equal(calls[0].options.windowsHide, true);
	assert.equal(calls[0].options.timeout, 15_000);
	assert.match(health.message, /^Claude Code CLI is installed at C:\\tools\\claude\.exe but is not working: 'claude --version' failed with exit code 1: Error: Cannot find module \[location redacted\]/);
	assert.match(health.message, /Reinstall or update it, then relaunch this agent\. This agent cannot think until that is fixed\.$/);
	assert.doesNotMatch(health.message, /sk-ant-private/);
	assert.doesNotMatch(health.details, /sk-ant-private/);
	assert.ok(health.message.length <= 900);
});

test('reports a broken CLI when --version times out or cannot start', async () => {
	const codexExe = path.join(POSIX_BIN, 'codex');
	const slow = await probe('codex', { files: [codexExe], platform: 'linux', environment: POSIX_ENVIRONMENT, script: () => timedOut(), timeoutMs: 10_000 });
	assert.equal(slow.health.code, 'PROVIDER_CLI_BROKEN');
	assert.match(slow.health.message, /'codex --version' timed out after 10s\./);
	assert.equal(slow.calls[0].options.timeout, 10_000);

	const agyExe = path.join(TOOLS, 'agy.exe');
	const cannotStart = await probe('gemini', { files: [agyExe], script: () => spawnFailure('EACCES') });
	assert.equal(cannotStart.health.code, 'PROVIDER_CLI_BROKEN');
	assert.match(cannotStart.health.message, /Antigravity CLI is installed at C:\\tools\\agy\.exe but is not working: 'agy --version' could not start \(EACCES\)\./);

	const throwing = fakeExecFile(() => ({}));
	const shim = await probeProviderCli('claude', {
		environment: WINDOWS_ENVIRONMENT, platform: 'win32', existsSync: existsIn(path.join(TOOLS, 'claude.exe')),
		execFile: () => { throw Object.assign(new Error('spawn EINVAL'), { code: 'EINVAL' }); },
	});
	assert.equal(shim.health?.code ?? shim.code, 'PROVIDER_CLI_BROKEN');
	assert.match(shim.message, /could not start \(EINVAL\)/);
	assert.equal(throwing.calls.length, 0);
});

test('an ENOENT spawn failure is reported as missing, not broken', async () => {
	const { health } = await probe('claude', { files: [path.join(TOOLS, 'claude.exe')], script: () => spawnFailure('ENOENT') });
	assert.equal(health.status, 'missing');
	assert.equal(health.code, 'PROVIDER_CLI_MISSING');
	assert.equal(health.message, "Claude Code CLI is not installed on the server machine ('claude' could not be started (spawn ENOENT)). Install it, sign in with 'claude auth login', then restart Minecraft and relaunch this agent. This agent cannot think until that is fixed.");
});

test('an npm .cmd shim without the package beside it gets install advice instead of "reinstall"', async () => {
	const shim = path.join(TOOLS, 'claude.cmd');
	const { health, calls } = await probe('claude', { files: [shim] });
	assert.equal(health.code, 'PROVIDER_CLI_BROKEN');
	assert.equal(health.executable, shim);
	assert.equal(calls.length, 0, 'the shim is never spawned');
	assert.equal(health.message, "Claude Code CLI was found only as the script shim C:\\tools\\claude.cmd, which the coordinator cannot start without a shell. Install Claude Code with its native installer, or run 'npm install -g @anthropic-ai/claude-code' with the default npm prefix, then restart Minecraft and relaunch this agent. This agent cannot think until that is fixed.");
	// The PowerShell stub names a package that does not exist so no real WindowsApps scan or copy happens.
	const codex = await probe('codex', { files: [path.join(TOOLS, 'codex.cmd')], spawnSync: () => { throw new Error('must not run'); }, script: () => ({ stdout: 'C:\\fake\\WindowsApps\\OpenAI.Codex_1.2.3.4_x64__2p2nqsd0c76g0' }) });
	assert.equal(codex.health.code, 'PROVIDER_CLI_BROKEN');
	assert.match(codex.health.message, /^Codex CLI was found only as the script shim C:\\tools\\codex\.cmd, .* Install Codex with 'npm install -g @openai\/codex' using the default npm prefix, or install the Codex desktop app, then restart Minecraft/);
});

test('custom npm prefixes: the package beside a .cmd shim is run through Node for Claude and Codex', async () => {
	const claudeEntrypoint = path.join(TOOLS, 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
	const launch = resolveClaudeLaunch({ environment: WINDOWS_ENVIRONMENT }, { platform: 'win32', execPath: 'C:\\node\\node.exe', existsSync: existsIn(path.join(TOOLS, 'claude.cmd'), claudeEntrypoint) });
	assert.deepEqual([launch.command, launch.args, launch.source], ['C:\\node\\node.exe', [claudeEntrypoint], 'npm-shim']);
	const claude = await probe('claude', {
		files: [path.join(TOOLS, 'claude.cmd'), claudeEntrypoint, 'C:\\node\\node.exe'],
		script: (_c, args) => args[1] === 'auth' ? { stdout: CLAUDE_LOGGED_IN } : { stdout: CLAUDE_VERSION },
	});
	assert.equal(claude.health.status, 'ok');
	assert.equal(claude.health.executable, claudeEntrypoint);
	assert.deepEqual(claude.calls[0].args, [claudeEntrypoint, '--version']);

	const codexEntrypoint = path.join(TOOLS, 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
	const codex = await probe('codex', {
		files: [path.join(TOOLS, 'codex.cmd'), codexEntrypoint, 'C:\\node\\node.exe'],
		spawnSync: () => { throw new Error('synchronous discovery must not run'); },
		script: (_c, args) => args[1] === 'login' ? { stdout: 'Logged in using ChatGPT' } : { stdout: CODEX_VERSION },
	});
	assert.equal(codex.health.status, 'ok');
	assert.equal(codex.health.executable, codexEntrypoint);
	assert.deepEqual(codex.calls.map((call) => call.command), ['C:\\node\\node.exe', 'C:\\node\\node.exe'], 'no PowerShell discovery when an npm entrypoint exists');
});

test('Codex desktop discovery on Windows goes through the asynchronous execFile, never spawnSync', async () => {
	const codexExe = path.join(TOOLS, 'codex.exe');
	let synchronousCalls = 0;
	const { health, calls } = await probe('codex', {
		files: [codexExe],
		spawnSync: () => { synchronousCalls += 1; return { status: 1, stdout: '' }; },
		script: (command, args) => {
			if (command === 'powershell.exe') return { stdout: 'C:\\fake\\WindowsApps\\OpenAI.Codex_1.2.3.4_x64__2p2nqsd0c76g0' };
			return args.at(-1) === 'status' ? { stdout: 'Logged in using ChatGPT' } : { stdout: CODEX_VERSION };
		},
	});
	assert.equal(synchronousCalls, 0);
	assert.equal(calls[0].command, 'powershell.exe');
	assert.ok(calls[0].args.join(' ').includes('Get-AppxPackage -Name OpenAI.Codex'));
	assert.equal(calls[0].options.windowsHide, true);
	assert.equal(health.status, 'ok');
	assert.equal(health.executable, codexExe);
	assert.deepEqual(calls.slice(1).map((call) => call.command), [codexExe, codexExe]);
});

test('detects a signed-out Claude CLI from auth status JSON', async () => {
	const claudeExe = path.join(TOOLS, 'claude.exe');
	const { health, calls } = await probe('claude', {
		files: [claudeExe],
		script: (_command, args) => lastArgs(args) === 'status --json' ? exit(1, CLAUDE_LOGGED_OUT) : { stdout: CLAUDE_VERSION },
	});
	assert.equal(health.status, 'unauthenticated');
	assert.equal(health.code, 'PROVIDER_CLI_UNAUTHENTICATED');
	assert.equal(health.version, '2.1.288');
	assert.deepEqual(calls.map((call) => call.args), [['--version'], ['auth', 'status', '--json']]);
	assert.equal(health.message, "Claude Code CLI is not signed in. Run 'claude auth login' on the server machine, or set ANTHROPIC_API_KEY, then relaunch this agent. This agent cannot think until that is fixed.");
});

test('detects a signed-out Codex CLI from login status', async () => {
	const codexExe = path.join(POSIX_BIN, 'codex');
	const { health, calls } = await probe('codex', {
		files: [codexExe],
		platform: 'linux',
		environment: POSIX_ENVIRONMENT,
		script: (_command, args) => lastArgs(args) === 'login status' ? exit(1, 'Not logged in') : { stdout: CODEX_VERSION },
	});
	assert.equal(health.executable, codexExe);
	assert.equal(health.code, 'PROVIDER_CLI_UNAUTHENTICATED');
	assert.equal(health.version, '0.160.0');
	assert.deepEqual(calls[1].args, ['login', 'status']);
	assert.equal(health.message, "Codex CLI is not signed in. Run 'codex login' on the server machine, or set OPENAI_API_KEY, then relaunch this agent. This agent cannot think until that is fixed.");
});

test('detects a signed-out Antigravity CLI from the models command with its longer deadline', async () => {
	const agyExe = path.join(TOOLS, 'agy.exe');
	const { health, calls } = await probe('gemini', {
		files: [agyExe],
		script: (_command, args) => args[0] === 'models' ? exit(1, AGY_SIGNED_OUT) : { stdout: AGY_VERSION },
	});
	assert.equal(health.code, 'PROVIDER_CLI_UNAUTHENTICATED');
	assert.equal(health.version, '1.2.8');
	assert.deepEqual(calls[1].args, ['models']);
	assert.equal(calls[1].options.timeout, 25_000);
	assert.equal(health.message, "Antigravity CLI is not signed in. Run 'agy' once and sign in on the server machine, or set GEMINI_API_KEY, then relaunch this agent. This agent cannot think until that is fixed.");
	assert.match(health.details, /Please sign in/);
});

test('an Antigravity models failure that is not a sign-in problem is reported as broken', async () => {
	const agyExe = path.join(TOOLS, 'agy.exe');
	const { health } = await probe('gemini', {
		files: [agyExe],
		script: (_command, args) => args[0] === 'models' ? exit(2, '', 'panic: network unreachable') : { stdout: AGY_VERSION },
	});
	assert.equal(health.code, 'PROVIDER_CLI_BROKEN');
	assert.match(health.message, /'agy models' failed with exit code 2: panic: network unreachable/);
});

test('reports ok for each signed-in provider with the parsed version', async () => {
	const claude = await probe('claude', { files: [path.join(TOOLS, 'claude.exe')], script: (_c, args) => args[0] === 'auth' ? { stdout: CLAUDE_LOGGED_IN } : { stdout: CLAUDE_VERSION } });
	assert.deepEqual([claude.health.status, claude.health.code, claude.health.message, claude.health.version], ['ok', null, null, '2.1.288']);
	assert.doesNotMatch(claude.health.details, /player@example/);
	const codex = await probe('codex', { files: [path.join(POSIX_BIN, 'codex')], platform: 'linux', environment: POSIX_ENVIRONMENT, script: (_c, args) => args[0] === 'login' ? { stdout: 'Logged in using ChatGPT' } : { stdout: CODEX_VERSION } });
	assert.deepEqual([codex.health.status, codex.health.version], ['ok', '0.160.0']);
	const gemini = await probe('gemini', { files: [path.join(TOOLS, 'agy.exe')], script: (_c, args) => args[0] === 'models' ? { stdout: 'Gemini 3.6 Flash (High)\n' } : { stdout: AGY_VERSION } });
	assert.deepEqual([gemini.health.status, gemini.health.version], ['ok', '1.2.8']);
});

test('an older Claude CLI without auth status yields unknown rather than a false alarm', async () => {
	const { health } = await probe('claude', {
		files: [path.join(TOOLS, 'claude.exe')],
		script: (_c, args) => args[0] === 'auth' ? exit(1, '', "error: unknown command 'auth'") : { stdout: CLAUDE_VERSION },
	});
	assert.equal(health.status, 'unknown');
	assert.equal(health.code, null);
	assert.equal(health.message, null);
	assert.equal(health.version, '2.1.288');
});

test('an API credential in the child environment skips the sign-in command and is never echoed', async () => {
	const { health, calls } = await probe('claude', {
		files: [path.join(TOOLS, 'claude.exe')],
		environment: { ...WINDOWS_ENVIRONMENT, CLAUDE_CODE_OAUTH_TOKEN: 'oauth-secret-value' },
		script: () => ({ stdout: CLAUDE_VERSION }),
	});
	assert.equal(health.status, 'ok');
	assert.equal(calls.length, 1);
	assert.equal(health.details, 'authenticated through CLAUDE_CODE_OAUTH_TOKEN');
	assert.doesNotMatch(JSON.stringify(health), /oauth-secret-value/);
	const codex = await probe('codex', { files: [path.join(POSIX_BIN, 'codex')], platform: 'linux', environment: { ...POSIX_ENVIRONMENT, OPENAI_API_KEY: 'sk-value' }, script: () => ({ stdout: CODEX_VERSION }) });
	assert.equal(codex.calls.length, 1);
	assert.equal(codex.health.details, 'authenticated through OPENAI_API_KEY');
});

test('probes an npm-installed Codex through the Node entrypoint exactly like the launch path', async () => {
	const entrypoint = path.join(WINDOWS_ENVIRONMENT.APPDATA, 'npm', 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
	const { health, calls } = await probe('codex', {
		files: [entrypoint, 'C:\\node\\node.exe'],
		script: (_c, args) => args[1] === 'login' ? { stdout: 'Logged in using ChatGPT' } : { stdout: CODEX_VERSION },
	});
	assert.equal(health.status, 'ok');
	assert.equal(health.executable, entrypoint);
	assert.deepEqual(calls.map((call) => [call.command, ...call.args]), [
		['C:\\node\\node.exe', entrypoint, '--version'],
		['C:\\node\\node.exe', entrypoint, 'login', 'status'],
	], 'an npm entrypoint skips the desktop-app discovery entirely');
});

test('probes an npm-installed Claude through the Node entrypoint', async () => {
	const entrypoint = path.join(WINDOWS_ENVIRONMENT.APPDATA, 'npm', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
	const { health, calls } = await probe('claude', {
		files: [entrypoint, 'C:\\node\\node.exe'],
		script: (_c, args) => args[1] === 'auth' ? { stdout: CLAUDE_LOGGED_IN } : { stdout: CLAUDE_VERSION },
	});
	assert.equal(health.status, 'ok');
	assert.equal(health.executable, entrypoint);
	assert.deepEqual(calls[0].args, [entrypoint, '--version']);
	assert.deepEqual(calls[1].args, [entrypoint, 'auth', 'status', '--json']);
});

test('the probe environment never carries the bridge secret and keeps the provider credential allow-list', async () => {
	const { calls } = await probe('claude', {
		files: [path.join(TOOLS, 'claude.exe')],
		environment: undefined,
		config: { environment: { ...WINDOWS_ENVIRONMENT, ARENA_AGENT_BRIDGE_SECRET: 'bridge', OPENAI_API_KEY: 'other-provider' }, bridgeSecretEnvironmentVariable: 'ARENA_AGENT_BRIDGE_SECRET' },
		script: () => ({ stdout: CLAUDE_VERSION }),
	});
	assert.equal(calls[0].options.env.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(calls[0].options.env.OPENAI_API_KEY, undefined);
	assert.equal(calls[0].options.env.PATH, TOOLS);
});

test('monitor caches results, shares in-flight probes, honours the ttl and never throws', async () => {
	let now = 0;
	let probes = 0;
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const monitor = new ProviderCliHealthMonitor({
		now: () => now,
		ttlMs: 1_000,
		okTtlMs: 5_000,
		configs: { claude: { executable: 'claude' } },
		probe: async (provider, options) => {
			probes += 1;
			assert.equal(options.config.executable, 'claude');
			await gate;
			return Object.freeze({ provider, status: 'missing', code: 'PROVIDER_CLI_MISSING', message: 'm', executable: null, version: null, checkedAtEpochMs: options.now(), details: '' });
		},
	});
	const [first, second] = [monitor.check('claude'), monitor.check('claude')];
	release();
	const results = await Promise.all([first, second]);
	assert.equal(results[0], results[1], 'concurrent checks share one probe');
	assert.equal(probes, 1);
	now = 500;
	assert.equal(await monitor.check('claude'), results[0], 'fresh results are cached');
	assert.equal(probes, 1);
	now = 1_500;
	await monitor.check('claude');
	assert.equal(probes, 2, 'stale results are probed again');
	monitor.invalidate('claude');
	await monitor.check('claude');
	assert.equal(probes, 3, 'invalidation forces a new probe');
	assert.deepEqual(Object.keys(monitor.snapshot()), ['claude']);

	const failing = new ProviderCliHealthMonitor({ probe: async () => { throw new Error('boom'); } });
	const unknown = await failing.check('codex');
	assert.equal(unknown.status, 'unknown');
	assert.equal(unknown.code, null);
	const invalid = new ProviderCliHealthMonitor({ probe: async () => ({ provider: 'codex', status: 'weird' }) });
	assert.equal((await invalid.check('codex')).status, 'unknown');
	assert.equal((await invalid.check('not-a-provider')).status, 'unknown');
});

test('a successful result is cached longer than a failure', async () => {
	let now = 0;
	let probes = 0;
	const monitor = new ProviderCliHealthMonitor({
		now: () => now,
		ttlMs: 1_000,
		okTtlMs: 10_000,
		probe: async (provider, options) => {
			probes += 1;
			return { provider, status: 'ok', code: null, message: null, executable: 'x', version: '1', checkedAtEpochMs: options.now(), details: '' };
		},
	});
	await monitor.check('codex');
	now = 5_000;
	await monitor.check('codex');
	assert.equal(probes, 1);
	now = 11_000;
	await monitor.check('codex');
	assert.equal(probes, 2);
});

test('the disabled monitor never probes', async () => {
	const monitor = createDisabledProviderCliHealthMonitor();
	assert.equal(monitor.enabled, false);
	const health = await monitor.check('claude');
	assert.equal(health.status, 'unknown');
	assert.equal(health.code, null);
	assert.deepEqual(monitor.snapshot(), {});
});

test('resolveClaudeLaunch prefers a native claude.exe, then the npm entrypoint, then the installer location on Windows', () => {
	const environment = { ...WINDOWS_ENVIRONMENT, ARENA_AGENT_BRIDGE_SECRET: 'secret' };
	const config = { environment, bridgeSecretEnvironmentVariable: 'ARENA_AGENT_BRIDGE_SECRET' };
	const dependencies = { platform: 'win32', execPath: 'C:\\node\\node.exe' };
	const native = resolveClaudeLaunch(config, { ...dependencies, existsSync: existsIn(path.join(TOOLS, 'claude.exe'), path.join(TOOLS, 'claude.cmd')) });
	assert.deepEqual([native.command, native.args, native.source], [path.join(TOOLS, 'claude.exe'), [], 'path']);
	assert.equal(native.environment.ARENA_AGENT_BRIDGE_SECRET, undefined);

	const entrypoint = path.join(WINDOWS_ENVIRONMENT.APPDATA, 'npm', 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
	const npm = resolveClaudeLaunch(config, { ...dependencies, existsSync: existsIn(path.join(TOOLS, 'claude.cmd'), entrypoint) });
	assert.deepEqual([npm.command, npm.args, npm.source], ['C:\\node\\node.exe', [entrypoint], 'npm']);

	const installer = path.join(WINDOWS_ENVIRONMENT.USERPROFILE, '.local', 'bin', 'claude.exe');
	const local = resolveClaudeLaunch(config, { ...dependencies, existsSync: existsIn(installer) });
	assert.deepEqual([local.command, local.source], [installer, 'native']);

	const bare = resolveClaudeLaunch(config, { ...dependencies, existsSync: () => false });
	assert.deepEqual([bare.command, bare.args, bare.source], ['claude', [], 'bare']);

	const configured = resolveClaudeLaunch({ ...config, executable: 'D:\\apps\\claude.exe' }, { ...dependencies, existsSync: existsIn('D:\\apps\\claude.exe') });
	assert.deepEqual([configured.command, configured.source], ['D:\\apps\\claude.exe', 'configured']);

	const posix = resolveClaudeLaunch(config, { platform: 'linux', existsSync: () => { throw new Error('PATH lookup is left to the OS outside Windows'); } });
	assert.deepEqual([posix.command, posix.source], ['claude', 'bare']);
});

test('resolveAntigravityLaunch prefers agy.exe on PATH, then the installer location on Windows', () => {
	const config = { environment: WINDOWS_ENVIRONMENT };
	const onPath = resolveAntigravityLaunch(config, { platform: 'win32', existsSync: existsIn(path.join(TOOLS, 'agy.exe')) });
	assert.deepEqual([onPath.command, onPath.source], [path.join(TOOLS, 'agy.exe'), 'path']);
	const installer = path.join(WINDOWS_ENVIRONMENT.LOCALAPPDATA, 'agy', 'bin', 'agy.exe');
	const installed = resolveAntigravityLaunch(config, { platform: 'win32', existsSync: existsIn(installer) });
	assert.deepEqual([installed.command, installed.source], [installer, 'installer']);
	const bare = resolveAntigravityLaunch(config, { platform: 'win32', existsSync: () => false });
	assert.deepEqual([bare.command, bare.source], ['agy', 'bare']);
	const configured = resolveAntigravityLaunch({ ...config, executable: 'D:\\agy\\agy.exe' }, { platform: 'win32', existsSync: existsIn('D:\\agy\\agy.exe') });
	assert.equal(configured.source, 'configured');
});

test('findExecutableOnPath honours PATHEXT on Windows and plain names elsewhere', () => {
	const environment = { Path: 'C:\\a;C:\\b', PATHEXT: '.COM;.EXE;.CMD' };
	assert.equal(findExecutableOnPath('claude', environment, { platform: 'win32', existsSync: existsIn('C:\\b\\claude.cmd') }), path.join('C:\\b', 'claude.cmd'));
	assert.equal(findExecutableOnPath('claude', environment, { platform: 'win32', existsSync: existsIn('C:\\b\\claude.cmd'), extensions: ['.exe'] }), null);
	assert.equal(findExecutableOnPath('claude.exe', environment, { platform: 'win32', existsSync: existsIn('C:\\a\\claude.exe') }), path.join('C:\\a', 'claude.exe'));
	assert.equal(findExecutableOnPath('claude', { PATH: '/usr/bin:/opt/bin' }, { platform: 'linux', existsSync: (candidate) => normalize(candidate) === '/opt/bin/claude' }), path.join('/opt/bin', 'claude'));
	assert.equal(findExecutableOnPath('claude', {}, { platform: 'linux', existsSync: () => true }), null);
	assert.equal(findExecutableOnPath('', environment, { platform: 'win32', existsSync: () => true }), null);
});
