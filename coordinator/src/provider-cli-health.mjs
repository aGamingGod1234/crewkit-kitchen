import { execFile as nodeExecFile } from 'node:child_process';
import { existsSync as nodeExistsSync } from 'node:fs';
import path from 'node:path';

import { resolveAntigravityLaunch } from './antigravity-service.mjs';
import { resolveClaudeLaunch } from './claude-service.mjs';
import { buildCodexArgs, codexNpmEntrypoint, discoverCodexWindowsPackageLocations, resolveCodexLaunch } from './codex-app-server.mjs';
import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';
import { createProviderChildEnvironment, environmentValue, findExecutableOnPath } from './provider-environment.mjs';
import { PROVIDER_IDS } from './provider-identity.mjs';

export const PROVIDER_CLI_STATUSES = Object.freeze(['ok', 'missing', 'broken', 'unauthenticated', 'unknown']);
export const PROVIDER_CLI_CODES = Object.freeze({
	missing: 'PROVIDER_CLI_MISSING',
	broken: 'PROVIDER_CLI_BROKEN',
	unauthenticated: 'PROVIDER_CLI_UNAUTHENTICATED',
});
export const DEFAULT_PROVIDER_CLI_TIMEOUT_MS = 15_000;
export const MAX_PROVIDER_CLI_MESSAGE_CHARS = 900;
const AUTH_TIMEOUT_MS = Object.freeze({ gemini: 25_000 });
const MAX_OUTPUT_BYTES = 256 * 1_024;
const MAX_DETAIL_BYTES = 1_024;
const EXCERPT_BYTES = 200;
const DEFAULT_TTL_MS = 20_000;
const DEFAULT_OK_TTL_MS = 60_000;
const CLOSING_LINE = ' This agent cannot think until that is fixed.';
const UNKNOWN_SUBCOMMAND = /unknown (?:command|option|argument)|unrecognized|too many arguments|invalid (?:command|subcommand)|not a (?:valid )?command/i;
const ANSI_ESCAPES = /\u001b\[[0-?]*[ -/]*[@-~]/g;
// npm shims; Node spawns them only through a shell, which the agent launch never uses.
const SCRIPT_SHIM_EXTENSIONS = Object.freeze(['.cmd', '.bat']);

/** Player-facing names, login hints and credential variables per provider. Never include credential values. */
export const PROVIDER_CLI = Object.freeze({
	claude: Object.freeze({
		display: 'Claude Code CLI',
		command: 'claude',
		installStep: "sign in with 'claude auth login'",
		signInStep: "Run 'claude auth login'",
		shimStep: "Install Claude Code with its native installer, or run 'npm install -g @anthropic-ai/claude-code' with the default npm prefix",
		credentialVariables: Object.freeze(['ANTHROPIC_API_KEY', 'CLAUDE_CODE_OAUTH_TOKEN']),
		credentialHint: 'ANTHROPIC_API_KEY',
		authArgs: Object.freeze(['auth', 'status', '--json']),
	}),
	codex: Object.freeze({
		display: 'Codex CLI',
		command: 'codex',
		installStep: "sign in with 'codex login'",
		signInStep: "Run 'codex login'",
		shimStep: "Install Codex with 'npm install -g @openai/codex' using the default npm prefix, or install the Codex desktop app",
		credentialVariables: Object.freeze(['OPENAI_API_KEY']),
		credentialHint: 'OPENAI_API_KEY',
		authArgs: Object.freeze(['login', 'status']),
	}),
	gemini: Object.freeze({
		display: 'Antigravity CLI',
		command: 'agy',
		installStep: "run 'agy' once to sign in",
		signInStep: "Run 'agy' once and sign in",
		shimStep: "Install the Antigravity CLI with its installer so agy.exe is on PATH",
		credentialVariables: Object.freeze(['GEMINI_API_KEY', 'GOOGLE_API_KEY']),
		credentialHint: 'GEMINI_API_KEY',
		authArgs: Object.freeze(['models']),
	}),
});

/**
 * Checks, in order, that a provider CLI is installed, starts (`--version`) and is signed in,
 * using the same launch resolution and child environment as the agent processes so PATH,
 * npm entrypoints and credentials match what an agent would see.
 */
export async function probeProviderCli(provider, options = {}) {
	const spec = PROVIDER_CLI[provider];
	if (spec === undefined) throw new TypeError(`provider must be one of ${Object.keys(PROVIDER_CLI).join(', ')}`);
	const {
		config = {},
		now = Date.now,
		timeoutMs = DEFAULT_PROVIDER_CLI_TIMEOUT_MS,
		platform = process.platform,
		execFile = nodeExecFile,
		existsSync = nodeExistsSync,
		execPath = process.execPath,
	} = options;
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be a positive safe integer');
	const environment = options.environment
		?? createProviderChildEnvironment(provider, config.environment ?? process.env, config.bridgeSecretEnvironmentVariable);
	const checkedAtEpochMs = now();
	const launch = await resolveLaunch(provider, config, { platform, env: environment, existsSync, execPath, execFile, windowsPackageLocations: options.windowsPackageLocations, spawnSync: options.spawnSync });
	const base = { provider, executable: launch.entrypoint ?? launch.command, version: null, checkedAtEpochMs };

	// (a) installed: the resolved command must be a real file (bare names are searched on PATH).
	const located = findExecutableOnPath(launch.command, environment, { platform, existsSync });
	if (located === null) {
		const explicit = path.isAbsolute(launch.command) || /[\\/]/.test(launch.command);
		return missingHealth(base, spec, explicit ? `configured path '${launch.command}' does not exist` : `no '${launch.command}' found on PATH`);
	}
	if (platform === 'win32' && SCRIPT_SHIM_EXTENSIONS.includes(path.extname(located).toLowerCase())) {
		// Reinstalling would not help here: the package beside the shim was not found, so say what would.
		return health({
			...base,
			executable: located,
			status: 'broken',
			message: `${spec.display} was found only as the script shim ${located}, which the coordinator cannot start without a shell. ${spec.shimStep}, then restart Minecraft and relaunch this agent.`,
			details: `script shim ${located}`,
		});
	}
	// A Node-run npm entrypoint is more useful to show than node.exe itself.
	base.executable = launch.entrypoint ?? located;

	// (b) working: the CLI must at least report its version within the deadline.
	const versionRun = await runCommand(execFile, located, [...launch.args, '--version'], { environment, timeoutMs });
	if (!versionRun.ok) {
		// The file was there a moment ago; ENOENT now means nothing runnable is installed after all.
		if (versionRun.spawnError === 'ENOENT') return missingHealth(base, spec, `'${spec.command}' could not be started (spawn ENOENT)`);
		return health({ ...base, status: 'broken', message: brokenMessage(spec, base.executable, `${spec.command} --version`, versionRun, timeoutMs), details: runDetails(versionRun) });
	}
	base.version = parseVersion(versionRun.stdout) ?? parseVersion(versionRun.stderr);

	// (c) authenticated: an API credential in the child environment counts as signed in.
	const credential = spec.credentialVariables.find((name) => environmentValue(environment, name) !== null);
	if (credential !== undefined) {
		return health({ ...base, status: 'ok', message: null, details: `authenticated through ${credential}` });
	}
	const authTimeoutMs = AUTH_TIMEOUT_MS[provider] ?? timeoutMs;
	const authRun = await runCommand(execFile, located, [...launch.args, ...spec.authArgs], { environment, timeoutMs: authTimeoutMs });
	const verdict = classifyAuthentication(provider, authRun);
	if (verdict === 'ok') return health({ ...base, status: 'ok', message: null, details: `signed in (${spec.command} ${spec.authArgs.join(' ')})` });
	if (verdict === 'unauthenticated') {
		return health({
			...base,
			status: 'unauthenticated',
			message: `${spec.display} is not signed in. ${spec.signInStep} on the server machine, or set ${spec.credentialHint}, then relaunch this agent.`,
			details: runDetails(authRun),
		});
	}
	if (verdict === 'broken') {
		return health({ ...base, status: 'broken', message: brokenMessage(spec, base.executable, `${spec.command} ${spec.authArgs.join(' ')}`, authRun, authTimeoutMs), details: runDetails(authRun) });
	}
	return health({ ...base, status: 'unknown', message: null, details: runDetails(authRun) });
}

async function resolveLaunch(provider, config, dependencies) {
	switch (provider) {
		case 'claude': {
			const launch = resolveClaudeLaunch(config, dependencies);
			return { ...launch, entrypoint: launch.args.length > 0 ? launch.args[0] : null };
		}
		case 'gemini': return { ...resolveAntigravityLaunch(config, dependencies), entrypoint: null };
		case 'codex': {
			// Only the entrypoint prefix matters here; the app-server arguments are stripped again below.
			const launchConfig = { ...config, model: 'probe', reasoningEffort: 'probe', serviceTier: 'probe' };
			// Without an npm entrypoint resolveCodexLaunch discovers the desktop app through a synchronous
			// PowerShell call; run that lookup asynchronously first so the probe never blocks the event loop.
			const { platform, env, existsSync, execFile } = dependencies;
			const windowsPackageLocations = dependencies.windowsPackageLocations
				?? (platform === 'win32' && codexNpmEntrypoint(env, { platform, existsSync }) === null
					? await discoverCodexWindowsPackageLocations(env, { execFile })
					: undefined);
			const launch = resolveCodexLaunch(launchConfig, { ...dependencies, windowsPackageLocations });
			const appServerArgs = buildCodexArgs(launchConfig);
			const args = launch.args.slice(0, launch.args.length - appServerArgs.length);
			return { command: launch.command, args, environment: launch.environment, entrypoint: args.length > 0 ? args[0] : null };
		}
		default: throw new TypeError(`Unsupported provider '${provider}'`);
	}
}

function runCommand(execFile, command, args, { environment, timeoutMs }) {
	return new Promise((resolve) => {
		let settled = false;
		const finish = (result) => { if (!settled) { settled = true; resolve(result); } };
		let child;
		try {
			child = execFile(command, args, { env: environment, timeout: timeoutMs, windowsHide: true, maxBuffer: MAX_OUTPUT_BYTES, encoding: 'utf8' }, (error, stdout, stderr) => {
				const output = { stdout: cleanOutput(stdout), stderr: cleanOutput(stderr) };
				if (error === null || error === undefined) { finish({ ok: true, exitCode: 0, signal: null, timedOut: false, spawnError: null, ...output }); return; }
				if (typeof error.code === 'string') { finish({ ok: false, exitCode: null, signal: null, timedOut: false, spawnError: error.code, ...output }); return; }
				const timedOut = error.killed === true && (error.code === null || error.code === undefined);
				finish({ ok: false, exitCode: Number.isSafeInteger(error.code) ? error.code : null, signal: error.signal ?? null, timedOut, spawnError: null, ...output });
			});
		} catch (error) {
			// execFile throws synchronously for shims Node cannot spawn (EINVAL for .cmd on Windows).
			finish({ ok: false, exitCode: null, signal: null, timedOut: false, spawnError: sanitizeDiagnosticErrorCode(error, { fallback: 'SPAWN_FAILED' }), stdout: '', stderr: '' });
			return;
		}
		try { child?.stdin?.end?.(); } catch { /* the probe never writes to the child */ }
	});
}

function classifyAuthentication(provider, run) {
	const text = `${run.stdout}\n${run.stderr}`;
	if (provider === 'claude') {
		const document = parseJsonObject(run.stdout) ?? parseJsonObject(run.stderr);
		if (document !== null && typeof document.loggedIn === 'boolean') return document.loggedIn ? 'ok' : 'unauthenticated';
		if (/not logged in|"loggedIn"\s*:\s*false/i.test(text)) return 'unauthenticated';
		// Older CLIs without `auth status` must not produce a false alarm.
		return 'unknown';
	}
	if (provider === 'codex') {
		if (/not logged in/i.test(text)) return 'unauthenticated';
		if (run.ok && /logged in/i.test(text)) return 'ok';
		if (run.spawnError !== null || run.timedOut) return 'broken';
		if (!run.ok) return 'unauthenticated';
		return 'unknown';
	}
	if (provider === 'gemini') {
		if (run.ok) return 'ok';
		if (/sign in|log ?in|authenticat/i.test(text)) return 'unauthenticated';
		return 'broken';
	}
	return 'unknown';
}

function missingHealth(base, spec, where) {
	return health({
		...base,
		status: 'missing',
		message: `${spec.display} is not installed on the server machine (${where}). Install it, ${spec.installStep}, then restart Minecraft and relaunch this agent.`,
		details: where,
	});
}

function brokenMessage(spec, installedAt, probeCommand, run, timeoutMs) {
	let failure;
	if (run.timedOut) failure = `timed out after ${Math.round(timeoutMs / 1_000)}s`;
	else if (run.spawnError !== null) failure = `could not start (${run.spawnError})`;
	else if (run.exitCode !== null) failure = `failed with exit code ${run.exitCode}`;
	else failure = `was stopped by ${run.signal ?? 'a signal'}`;
	const excerpt = sanitizeDiagnosticText(run.stderr.length > 0 ? run.stderr : run.stdout, { maxBytes: EXCERPT_BYTES });
	return `${spec.display} is installed at ${installedAt} but is not working: '${probeCommand}' ${failure}${excerpt.length > 0 ? `: ${excerpt}` : ''}. Reinstall or update it, then relaunch this agent.`;
}

function runDetails(run) {
	const parts = [];
	if (run.timedOut) parts.push('timed out');
	if (run.spawnError !== null) parts.push(`spawn ${run.spawnError}`);
	if (run.exitCode !== null) parts.push(`exit ${run.exitCode}`);
	if (run.signal !== null && !run.timedOut) parts.push(`signal ${run.signal}`);
	const output = run.stderr.length > 0 ? run.stderr : run.stdout;
	if (output.length > 0) parts.push(sanitizeDiagnosticText(output, { maxBytes: EXCERPT_BYTES }));
	return parts.join('; ');
}

function parseVersion(output) {
	const text = cleanOutput(output);
	return text.match(/\d+\.\d+(?:\.\d+)*(?:[-+][0-9A-Za-z.-]+)?/)?.[0] ?? (text.split(/\r?\n/)[0]?.trim() || null);
}

function parseJsonObject(output) {
	const text = cleanOutput(output);
	const start = text.indexOf('{');
	const end = text.lastIndexOf('}');
	if (start < 0 || end <= start) return null;
	try {
		const parsed = JSON.parse(text.slice(start, end + 1));
		return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
	} catch {
		return null;
	}
}

function cleanOutput(value) {
	return String(value ?? '').replace(ANSI_ESCAPES, '').trim();
}

function health({ provider, status, message, executable, version, checkedAtEpochMs, details }) {
	const code = PROVIDER_CLI_CODES[status] ?? null;
	let text = message === null ? null : `${message}${CLOSING_LINE}`;
	if (text !== null && text.length > MAX_PROVIDER_CLI_MESSAGE_CHARS) text = `${text.slice(0, MAX_PROVIDER_CLI_MESSAGE_CHARS - 3)}...`;
	return Object.freeze({
		provider,
		status,
		code,
		message: text,
		executable: executable ?? null,
		version: version ?? null,
		checkedAtEpochMs,
		details: sanitizeDiagnosticText(details ?? '', { maxBytes: MAX_DETAIL_BYTES, redactPaths: false }),
	});
}

function unknownHealth(provider, checkedAtEpochMs, details) {
	return health({ provider, status: 'unknown', message: null, executable: null, version: null, checkedAtEpochMs, details });
}

/**
 * Caches probe results per provider and shares in-flight probes so a burst of agent
 * launches (or a reconnect) runs each CLI at most once per window. Never throws.
 */
export class ProviderCliHealthMonitor {
	#probe;
	#now;
	#ttlMs;
	#okTtlMs;
	#enabled;
	#probeOptions;
	#configs = new Map();
	#cache = new Map();
	#inFlight = new Map();

	constructor({ probe = probeProviderCli, now = Date.now, ttlMs = DEFAULT_TTL_MS, okTtlMs = DEFAULT_OK_TTL_MS, configs = {}, enabled = true, ...probeOptions } = {}) {
		if (typeof probe !== 'function') throw new TypeError('probe must be a function');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		if (!Number.isSafeInteger(ttlMs) || ttlMs < 0) throw new TypeError('ttlMs must be a nonnegative safe integer');
		if (!Number.isSafeInteger(okTtlMs) || okTtlMs < 0) throw new TypeError('okTtlMs must be a nonnegative safe integer');
		this.#probe = probe;
		this.#now = now;
		this.#ttlMs = ttlMs;
		this.#okTtlMs = Math.max(ttlMs, okTtlMs);
		this.#enabled = enabled === true;
		this.#probeOptions = probeOptions;
		this.configure(configs);
	}

	get enabled() { return this.#enabled; }

	/** Installs the provider -> service config map used when check() is called without a config. */
	configure(configs) {
		if (configs === null || typeof configs !== 'object' || Array.isArray(configs)) throw new TypeError('provider CLI configs must be an object');
		for (const [provider, config] of Object.entries(configs)) {
			if (!PROVIDER_IDS.includes(provider) || config === null || typeof config !== 'object') continue;
			this.#configs.set(provider, config);
			this.#cache.delete(provider);
		}
	}

	async check(provider, config = undefined) {
		let checkedAt;
		try { checkedAt = this.#now(); } catch { checkedAt = Date.now(); }
		if (!this.#enabled || !Object.hasOwn(PROVIDER_CLI, provider)) return unknownHealth(provider, checkedAt, this.#enabled ? 'unsupported provider' : 'provider CLI checks disabled');
		const cached = this.#cache.get(provider);
		if (cached !== undefined && checkedAt - cached.checkedAtEpochMs < (cached.status === 'ok' ? this.#okTtlMs : this.#ttlMs)) return cached;
		const inFlight = this.#inFlight.get(provider);
		if (inFlight !== undefined) return inFlight;
		const promise = Promise.resolve()
			.then(() => this.#probe(provider, { ...this.#probeOptions, config: config ?? this.#configs.get(provider) ?? {}, now: this.#now }))
			.then((result) => validateHealth(provider, result, checkedAt))
			.catch((error) => unknownHealth(provider, checkedAt, `probe failed: ${sanitizeDiagnosticErrorCode(error, { fallback: 'PROBE_FAILED' })}`))
			.then((result) => { this.#cache.set(provider, result); return result; })
			.finally(() => { if (this.#inFlight.get(provider) === promise) this.#inFlight.delete(provider); });
		this.#inFlight.set(provider, promise);
		return promise;
	}

	invalidate(provider = undefined) {
		if (provider === undefined) { this.#cache.clear(); return; }
		this.#cache.delete(provider);
	}

	snapshot() {
		return Object.freeze(Object.fromEntries(this.#cache));
	}
}

export function createDisabledProviderCliHealthMonitor() {
	return new ProviderCliHealthMonitor({ enabled: false, probe: () => { throw new Error('provider CLI checks are disabled'); } });
}

function validateHealth(provider, result, checkedAt) {
	if (result === null || typeof result !== 'object' || result.provider !== provider || !PROVIDER_CLI_STATUSES.includes(result.status)) {
		return unknownHealth(provider, checkedAt, 'probe returned an invalid result');
	}
	return Object.isFrozen(result) ? result : Object.freeze({ ...result });
}
