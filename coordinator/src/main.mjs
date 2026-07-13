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
	if (process.argv.includes('--check-models')) {
		for (const profile of await checkModels()) process.stdout.write(`${JSON.stringify(profile)}\n`);
		return;
	}
	const runtimes = await startRuntimes();
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
