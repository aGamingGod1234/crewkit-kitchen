import os from 'node:os';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

import { runNativeToolCli } from './native-tool-cli-boundary.mjs';

async function main() {
const [serviceModulePath, variant, trialText, model = 'gpt-5.6-luna', reasoningEffort = 'xhigh', serviceTier = 'fast'] = process.argv.slice(2);
if (!serviceModulePath || !variant || !trialText) throw new TypeError('service module, variant, and trial are required');
const trial = Number.parseInt(trialText, 10);
if (!Number.isSafeInteger(trial) || trial < 1) throw new TypeError('trial must be a positive integer');

const [{ AgentWorkspaceManager }, { CodexService }] = await Promise.all([
	import('./agent-workspace.mjs'),
	import(pathToFileURL(path.resolve(serviceModulePath)).href),
]);
const probeRoot = path.join(os.tmpdir(), 'arena-native-ab', `trial-${trial}`);
await mkdir(probeRoot, { recursive: true });
const profile = { agentId: `native-ab-trial-${trial}`, provider: 'codex', model, reasoningEffort, serviceTier };
const startedAt = performance.now();
const service = new CodexService({
	cwd: probeRoot,
	planningTimeoutMs: 90_000,
	serviceTier,
	launchProfile: { model, reasoningEffort, serviceTier, cwd: probeRoot },
}, {
	workspaceManager: new AgentWorkspaceManager(path.join(probeRoot, 'workspaces')),
});

const runTurn = async (agent, input, expected) => {
	const turnStartedAt = performance.now();
	let firstToolAt = null;
	let lastToolAt = null;
	const calls = [];
	const requests = [];
	const result = await agent.act(input, {
		goalRevision: 1,
		executeTool: async (request) => {
			const now = performance.now();
			firstToolAt ??= now;
			lastToolAt = now;
			calls.push(request.tool.actionType ?? request.tool.kind);
			requests.push(structuredClone(request.tool));
			return { state: 'SUCCEEDED', reasonCode: '', executionStarted: true, delivered: true };
		},
	});
	const valid = result?.status === 'completed' && requests.length === expected.length && requests.every((request, index) => validRequest(request, expected[index]));
	return {
		valid,
		requests,
		firstToolMs: firstToolAt === null ? null : Math.round(firstToolAt - turnStartedAt),
		lastToolMs: lastToolAt === null ? null : Math.round(lastToolAt - turnStartedAt),
		totalMs: Math.round(performance.now() - turnStartedAt),
		calls,
		result,
	};
};

try {
	await service.start();
	const agent = await service.createAgent(profile, { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const readyAt = performance.now();
	const coldDm = await runTurn(agent, 'event: Lucas sent a DM saying "hi". Call say exactly once with a short friendly reply, then end this turn.', ['chat']);
	const warmDm = await runTurn(agent, 'event: Lucas sent a DM saying "how are you?". Call say exactly once with a short friendly reply, then end this turn.', ['chat']);
	const moveMine = await runTurn(agent, 'event: active goal is to mine the known stone block at x=2,y=64,z=1 (minecraft:stone). You are at x=0,y=64,z=0. Call moveTo with an endpoint at y=64 within one horizontal block of x=2,z=1 (distance <= 1), use the successful result, then call mine on that exact block with expectedBlockId minecraft:stone. You may use autoAim:true for the center aim and mining sequence. Make only these two tool calls. Do not finish this goal in this probe.', ['navigate_to', 'break_block']);
	const craft = await runTurn(agent, 'event: probe only. Call act exactly once with actionType craft_inventory and arguments recipeId minecraft:oak_planks, count 4, timeoutMs 15000. End this turn after the successful result.', ['craft_inventory']);
	return {
		status: [coldDm, warmDm, moveMine, craft].every((turn) => turn.valid) ? 'PASSED' : 'FAILED', variant, trial, profile: { model, reasoningEffort, serviceTier },
		initializationMs: Math.round(readyAt - startedAt),
		totalMs: Math.round(performance.now() - startedAt),
		coldDm, warmDm, moveMine, craft,
	};
} finally {
	await service.stop();
}
}

function validRequest(tool, actionType) {
	if (actionType === 'break_block' && tool?.kind === 'sequence') {
		// Accept only mine(autoAim:true)'s exact expansion, with no finish or extra work.
		const actions = tool.actions;
		return tool.finish === undefined && Array.isArray(actions) && actions.length === 2
			&& actions[0]?.actionType === 'look_at' && matchesPosition(actions[0].arguments, 2.5, 64.5, 1.5)
			&& actions[1]?.actionType === 'break_block' && validMiningArguments(actions[1].arguments);
	}
	if (tool?.kind !== 'action' || tool.actionType !== actionType) return false;
	const args = tool.arguments;
	if (!args || typeof args !== 'object') return false;
	if (actionType === 'chat') return typeof args.message === 'string' && args.message.trim().length > 0;
	if (actionType === 'craft_inventory') return args.recipeId === 'minecraft:oak_planks' && args.count === 4 && args.timeoutMs === 15000;
	if (actionType === 'break_block') return validMiningArguments(args);
	// This is the probe's endpoint region, not a change to gameplay reach or tolerance.
	return args.y === 64 && Math.hypot(args.x - 2, args.z - 1) <= 1;
}

function matchesPosition(args, x, y, z) {
	return args?.x === x && args.y === y && args.z === z;
}

function validMiningArguments(args) {
	return matchesPosition(args, 2, 64, 1) && args.expectedBlockId === 'minecraft:stone';
}

process.exitCode = await runNativeToolCli(main);
