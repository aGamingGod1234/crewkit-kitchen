import { execFile as nodeExecFile, spawnSync } from 'node:child_process';

const DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000;

/**
 * Parse the human-readable `agy models` output without trusting display names
 * as model IDs. Antigravity currently prints one line per model/effort pair,
 * e.g. `Gemini 3.6 Flash (High)`.
 */
export function parseAntigravityModelsOutput(output) {
	if (typeof output !== 'string') throw new TypeError('Antigravity model output must be text');
	const models = new Map();
	for (const rawLine of output.replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, '').split(/\r?\n/)) {
		const line = rawLine.trim();
		const match = /^(?:(?<wireId>[^\s]+)\s+)?(?<name>.+?)\s+\((?<effort>[^()]+)\)$/.exec(line);
		if (match === null) continue;
		const name = match.groups.name.trim();
		const effort = normalizeEffort(match.groups.effort);
		if (name.length === 0 || effort === null) continue;
		const id = baseModelId(match.groups.wireId, effort, name);
		const current = models.get(id) ?? {
			id,
			model: id,
			displayName: name,
			reasoningEfforts: [],
			serviceTiers: [],
		};
		if (!current.reasoningEfforts.includes(effort)) current.reasoningEfforts.push(effort);
		models.set(id, current);
	}
	return [...models.values()];
}

export async function discoverAntigravityCatalog({ executable = 'agy', execFile = nodeExecFile, environment, timeoutMs = DEFAULT_DISCOVERY_TIMEOUT_MS } = {}) {
	const childEnvironment = requireEnvironment(environment);
	if (execFile === nodeExecFile) {
		const result = spawnSync(executable, ['models'], { encoding: 'utf8', timeout: timeoutMs, windowsHide: true, maxBuffer: 1_024 * 1_024, env: childEnvironment });
		if (result.error !== undefined) throw new Error(`Provider model discovery failed (${result.error.code ?? 'command_error'})`);
		if (result.status !== 0) throw new Error(`Provider model discovery failed (exit_${result.status})`);
		return parseAntigravityModelsOutput(result.stdout);
	}
	const result = await runExecFile(execFile, executable, ['models'], timeoutMs, childEnvironment);
	return parseAntigravityModelsOutput(result.stdout);
}

function normalizeEffort(value) {
	const normalized = value.trim().toLowerCase().replace(/\s+/g, '_');
	return ['low', 'medium', 'high', 'xhigh', 'max', 'ultra', 'thinking'].includes(normalized) ? normalized : null;
}

function slugifyModelName(value) {
	return value.toLowerCase().replace(/[^a-z0-9.]+/g, '-').replace(/^-+|-+$/g, '');
}

function baseModelId(wireId, effort, displayName) {
	if (typeof wireId !== 'string' || wireId.length === 0) return slugifyModelName(displayName);
	const suffix = `-${effort}`;
	return wireId.endsWith(suffix) ? wireId.slice(0, -suffix.length) : wireId;
}

function runExecFile(execFile, command, args, timeoutMs, environment) {
	return new Promise((resolve, reject) => {
		try {
			execFile(command, args, { timeout: timeoutMs, windowsHide: true, maxBuffer: 1_024 * 1_024, env: environment }, (error, stdout) => {
				if (error !== null && error !== undefined) {
					const wrapped = new Error(`Provider model discovery failed (${error.code ?? 'command_error'})`);
					wrapped.code = error.code ?? 'DISCOVERY_FAILED';
					reject(wrapped);
					return;
				}
				resolve({ stdout: String(stdout ?? '') });
			});
		} catch (error) {
			reject(error);
		}
	});
}

function requireEnvironment(environment) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('provider catalog environment must be an object');
	}
	return environment;
}
