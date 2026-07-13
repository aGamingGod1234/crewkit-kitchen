import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { AgentRuntime } from '../../src/agent-runtime.mjs';
import { AgentState } from '../../src/agent-state.mjs';
import { CodexAgent } from '../../src/codex-app-server.mjs';
import { MinecraftBridge } from '../../src/protocol.mjs';
import { TraceWriter } from '../../src/trace-writer.mjs';
import { FakeCodexServer } from './fake-codex-server.mjs';
import { FakeMinecraftBridge } from './fake-minecraft-bridge.mjs';

const PROFILES = Object.freeze([
	{ agentId: 'agent-55', model: 'gpt-5.5', reasoningEffort: 'xhigh', serviceTier: 'fast' },
	{ agentId: 'agent-56', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
]);

export async function startTwoAgentFixture({ malformedFirstAgent = null } = {}) {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-agents-e2e-'));
	const entries = [];
	for (const profile of PROFILES) {
		const minecraft = new FakeMinecraftBridge(profile.agentId);
		await minecraft.start();
		const outputs = [
			JSON.stringify({ summary: 'Take one step.', goalStatus: 'in_progress', action: { type: 'wait', durationMs: 25 } }),
			JSON.stringify({ summary: 'Goal complete.', goalStatus: 'completed', action: { type: 'complete_goal', summary: 'Entered arena.' } }),
		];
		if (malformedFirstAgent === profile.agentId) outputs.unshift('malformed planner output');
		const codexServer = new FakeCodexServer(profile, outputs);
		const config = {
			...profile,
			cwd: root,
			planningTimeoutMs: 2_000,
			host: '127.0.0.1',
			bridgePort: minecraft.port,
		};
		const bridge = new MinecraftBridge({ agentId: profile.agentId, host: '127.0.0.1', port: minecraft.port, reconnectDelayMs: 10, maxReconnectDelayMs: 20 });
		const codex = new CodexAgent(config, codexServer);
		const tracePath = path.join(root, `${profile.agentId}.jsonl`);
		const runtime = new AgentRuntime({
			config,
			bridge,
			codex,
			traceWriter: new TraceWriter(tracePath),
			schedule: (callback, delay) => setTimeout(callback, Math.min(delay, 5)),
			cancelSchedule: clearTimeout,
		});
		entries.push({ profile, minecraft, codexServer, runtime, tracePath });
	}
	await Promise.all(entries.map((entry) => entry.runtime.start()));
	await Promise.all(entries.map((entry) => entry.minecraft.waitUntilAuthenticated()));
	let stopped = false;
	let evidence = null;

	return {
		async goalBoth(goal) {
			for (const entry of entries) entry.minecraft.sendGoal(goal);
		},
		async untilBothComplete() {
			await eventually(() => entries.every((entry) => entry.runtime.state === AgentState.COMPLETED), 'both runtimes did not complete');
		},
		crossAgentMessages: () => entries.reduce((total, entry) => total + entry.minecraft.crossAgentMessages, 0),
		models: () => entries.map((entry) => entry.profile.model),
		promptsIdentical: () => entries[0].codexServer.threadStart.baseInstructions === entries[1].codexServer.threadStart.baseInstructions,
		actionCounts: () => entries.map((entry) => entry.minecraft.actions.length),
		connectionCount: (agentId) => requireEntry(entries, agentId).minecraft.connectionCount,
		async reconnect(agentId) {
			const entry = requireEntry(entries, agentId);
			const previous = entry.minecraft.connectionCount;
			await entry.minecraft.disconnect();
			await entry.minecraft.waitUntilAuthenticated(previous + 1);
		},
		async stop() {
			if (stopped) return evidence;
			stopped = true;
			await Promise.allSettled(entries.map((entry) => entry.runtime.stop()));
			await Promise.allSettled(entries.map((entry) => entry.minecraft.stop()));
			evidence = Object.fromEntries(await Promise.all(entries.map(async (entry) => [entry.profile.agentId, await readRows(entry.tracePath)])));
			await rm(root, { recursive: true, force: true });
			return evidence;
		},
	};
}

function requireEntry(entries, agentId) {
	const entry = entries.find((candidate) => candidate.profile.agentId === agentId);
	if (entry === undefined) throw new Error(`unknown fixture agent '${agentId}'`);
	return entry;
}

async function readRows(filePath) {
	const text = await readFile(filePath, 'utf8');
	return text.trim().split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
}

async function eventually(predicate, message) {
	const deadline = Date.now() + 5_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(message);
}
