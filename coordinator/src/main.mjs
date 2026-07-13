import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentRuntime } from './agent-runtime.mjs';
import { checkCodexModelProfile, CodexAgent } from './codex-app-server.mjs';
import { MinecraftBridge } from './protocol.mjs';
import { TraceWriter } from './trace-writer.mjs';

const SOURCE_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const COORDINATOR_DIRECTORY = path.resolve(SOURCE_DIRECTORY, '..');
const PROJECT_DIRECTORY = path.resolve(COORDINATOR_DIRECTORY, '..');
const CONFIG_PATH = path.join(COORDINATOR_DIRECTORY, 'config', 'agents.json');
const AGENT_FILTERS = new Set(['all', 'agent-55', 'agent-56']);

export function parseCliArguments(args) {
	if (!Array.isArray(args)) throw new TypeError('CLI arguments must be an array');
	const parsed = { configPath: null, agent: 'all', checkModels: false };
	const seen = new Set();
	for (let index = 0; index < args.length; index += 1) {
		const argument = args[index];
		if (!['--config', '--agent', '--check-models'].includes(argument)) throw new Error(`Unknown argument '${String(argument)}'`);
		if (seen.has(argument)) throw new Error(`${argument} may appear only once`);
		seen.add(argument);
		if (argument === '--check-models') {
			parsed.checkModels = true;
			continue;
		}
		const value = args[index + 1];
		if (typeof value !== 'string' || value.startsWith('--')) throw new Error(`${argument} requires a value`);
		index += 1;
		if (argument === '--config') {
			if (!path.isAbsolute(value)) throw new Error('--config must be an absolute path');
			parsed.configPath = value;
		} else {
			if (!AGENT_FILTERS.has(value)) throw new Error('--agent must be all, agent-55, or agent-56');
			parsed.agent = value;
		}
	}
	return parsed;
}

export function selectAgentConfigs(configs, agent) {
	if (!AGENT_FILTERS.has(agent)) throw new Error(`Unsupported agent '${String(agent)}'`);
	if (agent === 'all') return [...configs];
	const selected = configs.filter((config) => config.agentId === agent);
	if (selected.length !== 1) throw new Error(`Configuration must contain exactly one '${agent}' profile`);
	return selected;
}

export async function loadAgentConfigs(configPath = CONFIG_PATH) {
	const document = JSON.parse(await readFile(configPath, 'utf8'));
	if (!Array.isArray(document.agents) || document.agents.length !== 2) throw new Error('agents.json must define exactly two agents');
	return document.agents.map((agent) => ({ ...document.shared, ...agent, cwd: PROJECT_DIRECTORY }));
}

export async function checkModels(configs) {
	configs ??= await loadAgentConfigs();
	const checked = [];
	for (const config of configs) {
		const model = await checkCodexModelProfile(config);
		checked.push({ agentId: config.agentId, model: model.model, reasoningEffort: config.reasoningEffort, serviceTier: config.serviceTier });
	}
	return checked;
}

export async function startRuntimes(configs) {
	configs ??= await loadAgentConfigs();
	const runtimes = configs.map((config) => {
		const bridge = new MinecraftBridge({
			agentId: config.agentId,
			host: config.host,
			port: config.bridgePort,
			reconnectDelayMs: config.reconnectDelayMs,
			maxReconnectDelayMs: config.maxReconnectDelayMs,
		});
		const codex = new CodexAgent(config);
		const trace = new TraceWriter(path.join(PROJECT_DIRECTORY, config.runtimeDir, 'traces', `${config.agentId}.jsonl`));
		return new AgentRuntime({ config, bridge, codex, traceWriter: trace });
	});
	await Promise.all(runtimes.map((runtime) => runtime.start()));
	return runtimes;
}

async function runCli() {
	const cli = parseCliArguments(process.argv.slice(2));
	const configs = selectAgentConfigs(await loadAgentConfigs(cli.configPath ?? CONFIG_PATH), cli.agent);
	if (cli.checkModels) {
		for (const profile of await checkModels(configs)) process.stdout.write(`${JSON.stringify(profile)}\n`);
		return;
	}
	const runtimes = await startRuntimes(configs);
	const shutdown = async () => {
		await Promise.allSettled(runtimes.map((runtime) => runtime.stop()));
		process.exitCode = 0;
	};
	process.once('SIGINT', shutdown);
	process.once('SIGTERM', shutdown);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	runCli().catch((error) => {
		process.stderr.write(`Coordinator failed: ${error.message}\n`);
		process.exitCode = 1;
	});
}
