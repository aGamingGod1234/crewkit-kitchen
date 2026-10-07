import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { CLAUDE_REASONING_EFFORTS, ClaudeProviderService, buildClaudeLaunch } from '../src/claude-service.mjs';
import { ClaudeToolServer } from '../src/claude-tool-server.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, NATIVE_AGENT_INSTRUCTIONS } from '../src/native-minecraft-tools.mjs';

const AGENTS_TEXT = '# Your role\nYou are the selected model controlling one persistent Minecraft player.';
const SKILL_TEXT = '---\nname: minecraft-control\n---\n# Minecraft player control';

/** Emulates the Claude Code stream-json CLI, including real MCP calls to the coordinator's tool server. */
class FakeClaudeCode extends EventEmitter {
	constructor(command, args, options, script) {
		super();
		this.command = command;
		this.args = args;
		this.options = options;
		this.pid = 4_242;
		this.exitCode = null;
		this.signalCode = null;
		this.lines = [];
		this.stdout = new EventEmitter();
		this.stdout.setEncoding = () => {};
		this.stderr = new EventEmitter();
		this.stdin = new EventEmitter();
		this.stdin.destroyed = false;
		this.stdin.write = (text) => { for (const line of String(text).split('\n').filter(Boolean)) this.#onLine(JSON.parse(line)); return true; };
		this.stdin.end = (text) => { if (typeof text === 'string') this.oneShotPrompt = text; };
		this.script = script;
		const configIndex = args.indexOf('--mcp-config');
		this.mcp = configIndex < 0 ? null : JSON.parse(args[configIndex + 1]).mcpServers.minecraft;
		if (script.rejectPartialMessages === true && args.includes('--include-partial-messages')) {
			queueMicrotask(() => { this.stderr.emit('data', "error: unknown option '--include-partial-messages'"); this.exit(1); });
			return;
		}
		if (this.mcp !== null) queueMicrotask(() => { this.connected = this.#connect().catch(() => {}); });
		else queueMicrotask(() => script.oneShot?.(this));
	}

	async rpc(method, params = {}, id = Math.random()) {
		const response = await fetch(this.mcp.url, {
			method: 'POST',
			headers: { 'content-type': 'application/json', ...this.mcp.headers },
			body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
		});
		return response.json();
	}

	emitLine(value) { this.stdout.emit('data', `${JSON.stringify(value)}\n`); }

	exit(code = 0) {
		this.exitCode = code;
		this.emit('close', code, null);
	}

	async #connect() {
		await this.rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'claude-code' } });
		this.tools = (await this.rpc('tools/list')).result.tools;
		this.emitLine({ type: 'system', subtype: 'init', session_id: 'session-1', model: this.args[this.args.indexOf('--model') + 1] });
	}

	#onLine(message) {
		this.lines.push(message);
		if (message.type === 'user') void Promise.resolve(this.connected).then(() => this.script.onUser?.(this, message.message.content)).catch(() => {});
		if (message.type === 'control_request' && message.request.subtype === 'interrupt' && this.script.ignoreInterrupt !== true) {
			this.emitLine({ type: 'control_response', response: { subtype: 'success', request_id: message.request_id } });
			this.emitLine({ type: 'result', subtype: 'error_during_execution', is_error: true, session_id: 'session-1' });
		}
	}
}

async function harness(script = {}, overrides = {}) {
	const root = await mkdtemp(path.join(tmpdir(), 'claude-service-'));
	const children = [];
	const toolServer = new ClaudeToolServer();
	const service = new ClaudeProviderService({
		provider: 'claude',
		cwd: root,
		runtimeRoot: path.join(root, 'claude'),
		planningTimeoutMs: 2_000,
		interruptTimeoutMs: 500,
		environment: { PATH: 'test', ANTHROPIC_API_KEY: 'anthropic-key', OPENAI_API_KEY: 'openai-key', ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret' },
		bridgeSecretEnvironmentVariable: 'ARENA_AGENT_BRIDGE_SECRET',
		...overrides,
	}, {
		spawn(command, args, options) {
			const child = new FakeClaudeCode(command, args, options, script);
			children.push(child);
			return child;
		},
		terminate: async (child) => { if (child.exitCode === null) child.exit(0); },
		toolServer,
		minecraftWorkspace: {
			root,
			prepare: async () => ({ cwd: path.join(root, 'workspace'), instructions: AGENTS_TEXT, skillInstructions: SKILL_TEXT }),
		},
	});
	return {
		root, service, children, toolServer,
		async close() {
			await service.stop();
			await toolServer.stop();
			await rm(root, { recursive: true, force: true });
		},
	};
}

function profile(overrides = {}) {
	return { agentId: 'claude-a', provider: 'claude', model: 'claude-sonnet-5-5', reasoningEffort: 'medium', serviceTier: 'priority', ...overrides };
}

test('Claude catalog offers Opus 5.5, Sonnet 5.5, and Fable 5.1 at Low through High only', async () => {
	const { service, close } = await harness();
	try {
		const snapshot = await service.catalog.refresh();
		assert.deepEqual(snapshot.models.map(({ model, displayName }) => [model, displayName]), [
			['claude-opus-5-5', 'Claude Opus 5.5'],
			['claude-sonnet-5-5', 'Claude Sonnet 5.5'],
			['claude-fable-5-1', 'Claude Fable 5.1'],
		]);
		for (const model of snapshot.models) {
			assert.deepEqual(model.reasoningEfforts, ['low', 'medium', 'high']);
			assert.deepEqual(model.serviceTiers, ['priority']);
		}
		assert.deepEqual(CLAUDE_REASONING_EFFORTS, ['low', 'medium', 'high']);
		assert.throws(() => service.catalog.assertSupported('claude-opus-5-5', 'xhigh'), (error) => error.code === 'UNSUPPORTED_THINKING');
		assert.throws(() => service.catalog.assertSupported('claude-opus-5-5', 'high', 'fast'), (error) => error.code === 'UNSUPPORTED_SERVICE_TIER');
		assert.throws(() => new ClaudeProviderService({ cwd: 'C:\\w', reasoningEfforts: ['high', 'max'] }), /must be one of low, medium, high/);
	} finally { await close(); }
});

test('Claude launches in the shared Minecraft workspace with the same instructions and only the Minecraft tools', async () => {
	const { service, children, root, close } = await harness();
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await agent.prewarm();
		const [child] = children;
		assert.equal(child.command, 'claude');
		assert.equal(child.options.cwd, path.join(root, 'workspace'), 'same workspace directory as Codex');
		const flag = (name) => child.args[child.args.indexOf(name) + 1];
		assert.equal(flag('--model'), 'claude-sonnet-5-5');
		assert.equal(flag('--effort'), 'medium');
		assert.equal(flag('--setting-sources'), '');
		assert.equal(flag('--tools'), 'Read,Glob,Grep');
		assert.equal(flag('--allowedTools'), 'mcp__minecraft');
		assert.equal(flag('--permission-mode'), 'dontAsk');
		for (const required of ['--strict-mcp-config', '--restricted', '--no-session-persistence', '--disable-slash-commands']) assert.ok(child.args.includes(required), required);
		assert.equal(child.options.env.ANTHROPIC_API_KEY, 'anthropic-key');
		assert.equal(child.options.env.OPENAI_API_KEY, undefined);
		assert.equal(child.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined);

		const systemPrompt = await readFile(flag('--system-prompt-file'), 'utf8');
		assert.ok(systemPrompt.startsWith(NATIVE_AGENT_INSTRUCTIONS), 'same native base instructions as Codex');
		assert.ok(systemPrompt.includes(`Workspace instructions for this Minecraft body (authoritative):\n${AGENTS_TEXT}`));
		assert.ok(systemPrompt.includes(`Bundled minecraft-control skill (already loaded; no filesystem read needed):\n${SKILL_TEXT}`));
		assert.ok(systemPrompt.includes('mcp__minecraft__observe'));
		assert.deepEqual(child.tools.map((tool) => tool.name), MINECRAFT_DYNAMIC_TOOLS.map((tool) => tool.name));
		assert.equal(agent.executionSettings.effective.model, 'claude-sonnet-5-5');
		assert.equal(agent.executionSettings.evidence.model, 'provider_reported');
	} finally { await close(); }
});

test('a Claude native turn executes Minecraft tools through the coordinator and completes on the result line', async () => {
	const executed = [];
	const { service, close } = await harness({
		async onUser(child, content) {
			child.emitLine({ type: 'assistant', message: { content: [{ type: 'text', text: 'Looking around.' }] } });
			const response = await child.rpc('tools/call', { name: 'observe', arguments: {}, _meta: { 'claudecode/toolUseId': 'toolu_1' } });
			child.toolResponse = response.result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: `done: ${content}`, session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const verbose = [];
		const result = await agent.act('Goal: gather wood.', {
			goalRevision: 0,
			onVerbose: (stage, message) => verbose.push([stage, message]),
			executeTool: async (request) => { executed.push(request); return { state: 'OK', nearby: ['minecraft:oak_log'] }; },
		});
		assert.deepEqual(result, { status: 'completed', toolCalls: 1 });
		assert.equal(executed.length, 1);
		assert.equal(executed[0].tool.kind, 'observe');
		assert.equal(executed[0].agentId, 'claude-a');
		assert.equal(executed[0].callId, 'toolu_1');
		assert.deepEqual(verbose, [['agent_message', 'Looking around.']]);
		assert.equal(agent.sessionMetadata().sessionState, 'warm');
	} finally { await close(); }
});

test('tool results reach Claude as MCP content and stale or foreign calls are refused', async () => {
	let captured = null;
	const { service, children, close } = await harness({
		async onUser(child) {
			captured = (await child.rpc('tools/call', { name: 'observe', arguments: {} })).result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await agent.act('Observe.', { goalRevision: 0, executeTool: async () => ({ state: 'OK', health: 20 }) });
		assert.equal(captured.isError, false);
		assert.deepEqual(JSON.parse(captured.content[0].text), { state: 'OK', health: 20 });

		const idle = await children[0].rpc('tools/call', { name: 'observe', arguments: {} });
		assert.equal(idle.result.isError, true);
		assert.equal(JSON.parse(idle.result.content[0].text).reasonCode, 'TURN_NOT_ACTIVE');

		const foreign = await fetch(children[0].mcp.url, { method: 'POST', headers: { authorization: `Bearer ${'0'.repeat(64)}` }, body: '{}' });
		assert.equal(foreign.status, 401);
	} finally { await close(); }
});

test('steering rides along with the next tool result and resolves once delivered', async () => {
	let captured = null;
	let release;
	const { service, children, close } = await harness({
		async onUser(child) {
			await new Promise((resolve) => { release = resolve; });
			captured = (await child.rpc('tools/call', { name: 'observe', arguments: {} })).result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act('Goal: build a hut.', { goalRevision: 0, executeTool: async () => ({ state: 'OK' }) });
		await waitFor(() => release !== undefined);
		const steer = agent.steer('A zombie is attacking.', { goalRevision: 0 });
		release();
		assert.deepEqual(await steer, { turnId: '1:1' });
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
		assert.equal(captured.isError, false);
		assert.deepEqual(JSON.parse(captured.content[0].text), { state: 'OK' });
		assert.match(captured.content[1].text, /Newer coordinator events[\s\S]*A zombie is attacking\./);
		assert.equal(children[0].lines.filter((line) => line.type === 'user').length, 1, 'steers never become extra Claude Code turns');
		await assert.rejects(agent.steer('Too late.', { goalRevision: 0 }), (error) => error.code === 'TURN_NOT_ACTIVE');
	} finally { await close(); }
});

test('a steer that never reaches a tool boundary is rejected so the coordinator defers it', async () => {
	let release;
	const { service, close } = await harness({
		onUser(child) { release = () => child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' }); },
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act('Goal.', { goalRevision: 0, executeTool: async () => ({ state: 'OK' }) });
		await waitFor(() => release !== undefined);
		const steer = agent.steer('Late event.', { goalRevision: 0 });
		void steer.catch(() => {});
		release();
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 0 });
		await assert.rejects(steer, (error) => error.code === 'TURN_NOT_ACTIVE');
	} finally { await close(); }
});

test('a new goal revision interrupts the running Claude turn and the next turn waits for its closing result', async () => {
	let turns = 0;
	const { service, children, close } = await harness({
		onUser(child) {
			turns += 1;
			if (turns === 2) child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const first = agent.act('Old goal.', { goalRevision: 0, executeTool: async () => ({ state: 'OK' }) });
		await waitFor(() => turns === 1);
		await agent.setGoalRevision(1);
		await assert.rejects(first, (error) => error.code === 'STALE_PLAN');
		assert.ok(children[0].lines.some((line) => line.type === 'control_request' && line.request.subtype === 'interrupt'));
		assert.deepEqual(await agent.act('New goal.', { goalRevision: 1, executeTool: async () => ({ state: 'OK' }) }), { status: 'completed', toolCalls: 0 });
		assert.equal(children.length, 1, 'the warm process is reused after an acknowledged interrupt');
	} finally { await close(); }
});

test('an unexpected Claude Code exit invalidates the session and fails the active turn', async () => {
	const { service, children, close } = await harness({ onUser(child) { child.stderr.emit('data', 'auth expired'); child.exit(1); } });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await assert.rejects(agent.act('Goal.', { goalRevision: 0, executeTool: async () => ({}) }), (error) => error.code === 'SESSION_INVALIDATED');
		assert.equal(service.getAgent('claude-a'), null);
		assert.equal(children.length, 1);
	} finally { await close(); }
});

test('account-level refusals become non-retryable model errors', async () => {
	const { service, close } = await harness({
		onUser(child) {
			child.emitLine({ type: 'result', subtype: 'success', is_error: true, result: 'Fable 5.1 requires usage credits. Switch to another model, or manage usage credits.', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile({ model: 'claude-fable-5-1' }), { controlProtocol: 'native_tools' });
		await assert.rejects(agent.act('Goal.', { goalRevision: 0, executeTool: async () => ({}) }),
			(error) => error.code === 'MODEL_UNAVAILABLE' && /requires usage credits/.test(error.message));
	} finally { await close(); }
});

test('Claude goal translation is a one-shot JSON decision without tools', async () => {
	const { service, children, close } = await harness({
		oneShot(child) {
			child.stdout.emit('data', Buffer.from(JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: '```json\n{"ok":true}\n```', duration_ms: 12, duration_api_ms: 9, usage: { input_tokens: 5, output_tokens: 2 } })));
			child.exit(0);
		},
	});
	try {
		const agent = await service.createAgent(profile({ agentId: 'translator' }), { controlProtocol: 'goal_spec' });
		const decision = await agent.decide('Translate: get wood.', { goalRevision: 0, systemPrompt: '', outputSchema: { type: 'object' }, parseOutput: (text) => JSON.parse(text) });
		assert.deepEqual(decision, { ok: true });
		const [child] = children;
		assert.equal(child.args[child.args.indexOf('--output-format') + 1], 'json');
		assert.equal(child.args[child.args.indexOf('--tools') + 1], '');
		assert.equal(child.args.includes('--mcp-config'), false);
		assert.match(child.oneShotPrompt, /Translate: get wood\.[\s\S]*JSON Schema/);
	} finally { await close(); }
});

test('buildClaudeLaunch keeps the bridge secret out of the child and disables auto-memory', () => {
	const launch = buildClaudeLaunch(profile(), { executable: 'claude', environment: { PATH: 'p', CUSTOM_SECRET: 'x', ANTHROPIC_BASE_URL: 'https://example.invalid' }, bridgeSecretEnvironmentVariable: 'CUSTOM_SECRET' }, {
		cwd: 'C:\\workspace', systemPromptFile: 'C:\\prompt.md', mcpConfig: null, streaming: false,
	});
	assert.equal(launch.options.env.CUSTOM_SECRET, undefined);
	assert.equal(launch.options.env.ANTHROPIC_BASE_URL, 'https://example.invalid');
	assert.equal(launch.options.env.CLAUDE_CODE_DISABLE_AUTO_MEMORY, '1');
	assert.equal(launch.options.windowsHide, true);
});

async function waitFor(predicate, timeoutMs = 2_000) {
	const started = Date.now();
	while (!predicate()) {
		if (Date.now() - started > timeoutMs) throw new Error('condition was not reached');
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
}

test('danger steers before the first tool call never interrupt the reasoning turn; all arrive at the first tool boundary', async () => {
	let captured = null;
	let release;
	const { service, children, close } = await harness({
		async onUser(child) {
			await new Promise((resolve) => { release = resolve; });
			captured = (await child.rpc('tools/call', { name: 'observe', arguments: {} })).result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act('Goal: survive.', { goalRevision: 0, executeTool: async () => ({ state: 'OK' }) });
		await waitFor(() => release !== undefined);
		// The play-test: ~30 threat/damage events while the model was still deciding its first action.
		const steers = ['Threat: zombie targeting.', 'Damage: health 17.', 'Suffocation: air 150 of 300.']
			.map((text) => agent.steer(text, { goalRevision: 0 }));
		release();
		for (const steer of steers) assert.deepEqual(await steer, { turnId: '1:1' });
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
		assert.equal(children[0].lines.filter((line) => line.type === 'control_request' && line.request?.subtype === 'interrupt').length, 0,
			'a steer never interrupts the model mid-reasoning');
		assert.equal(children[0].lines.filter((line) => line.type === 'user').length, 1, 'no restarted or extra turn');
		assert.equal(children.length, 1, 'the warm process and its reasoning are kept');
		assert.equal(captured.content.length, 2, 'every queued steer rides on the one tool result');
		assert.match(captured.content[1].text, /zombie targeting[\s\S]*health 17[\s\S]*air 150/);
	} finally { await close(); }
});

// --- Token efficiency: usage, rotation, restarts and fact views ---
const { buildNativeEventInput } = await import('../src/dynamic-main.mjs');
const { representativeProgramWake, representativeRecord } = await import('../src/benchmark/token-budget.mjs');
const nativeEvent = (sequence, change = (wake) => wake) => {
	const wake = representativeProgramWake(sequence);
	wake.conversation = { mode: 'unread', baseSequence: 12, nextSequence: 13, entries: sequence === 1 ? [{ sequence: 13, kind: 'player_message', sourceId: '11111111-1111-4111-8111-111111111111', sourceName: 'Lucas', text: 'meet me at the village' }] : [] };
	return buildNativeEventInput(representativeRecord(), change(wake));
};
const sentPayload = (content) => JSON.parse(content.slice(content.lastIndexOf('\n{') + 1));
const usageLine = (child, id, context) => {
	child.emitLine({ type: 'stream_event', event: { type: 'message_start', message: { id, usage: { input_tokens: 5, cache_read_input_tokens: context, cache_creation_input_tokens: 200, output_tokens: 1 } } } });
	child.emitLine({ type: 'stream_event', event: { type: 'message_delta', usage: { output_tokens: 40 } } });
	child.emitLine({ type: 'stream_event', event: { type: 'message_stop' } });
};
const settle = (ms = 30) => new Promise((resolve) => setTimeout(resolve, ms));

test('Claude per-call usage and timing reach live usage; cost is a per-process running total and output comes from the turn result', async () => {
	let cost = 0;
	const { service, children, close } = await harness({
		async onUser(child) {
			usageLine(child, `msg_${child.lines.length}`, 20_000);
			cost += 0.01;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1', total_cost_usd: cost, usage: { output_tokens: 55 } });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const verbose = [];
		const first = await agent.act('One.', { goalRevision: 0, onVerbose: (stage, message) => verbose.push([stage, message]), executeTool: async () => ({}) });
		assert.deepEqual(first.usage, { calls: 1, input: 5, cacheRead: 20_000, cacheWrite: 200, output: 55, costUsd: 0.01, contextTokens: 20_205 });
		const second = await agent.act('Two.', { goalRevision: 0, executeTool: async () => ({}) });
		assert.equal(Math.round(second.usage.costUsd * 1000), 10, 'the running total is not counted twice');
		const live = JSON.parse(verbose.find(([stage]) => stage === 'live_usage')[1]);
		assert.equal(live.last.inputTokens, 20_205);
		assert.equal(live.cacheWriteInputTokens, 200);
		assert.ok(Number.isSafeInteger(live.call.firstEventMs) && Number.isSafeInteger(live.call.streamMs) && Number.isSafeInteger(live.call.totalMs));
		assert.ok(children[0].args.includes('--include-partial-messages'));
	} finally { await close(); }
});

test('streamed token events renew liveness at most once a second', async () => {
	const { service, close } = await harness({
		async onUser(child) {
			child.emitLine({ type: 'stream_event', event: { type: 'message_start', message: { id: 'msg_1', usage: {} } } });
			for (let index = 0; index < 50; index++) child.emitLine({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'x' } } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		let progress = 0;
		await agent.act('One.', { goalRevision: 0, onProgress: () => { progress += 1; }, executeTool: async () => ({}) });
		assert.ok(progress <= 3, `progress renewed ${progress} times for 51 stream events`);
	} finally { await close(); }
});

test('rotation waits for hysteresis, happens after a finished turn with a prewarmed session, and carries recent calls, conversation and program state', async () => {
	const { service, children, close } = await harness({
		async onUser(child) {
			usageLine(child, `msg_${child.lines.length}`, 70_000);
			if (children.length === 1 && child.lines.filter((line) => line.type === 'user').length === 1) {
				await child.rpc('tools/call', { name: 'observe', arguments: {}, _meta: { 'claudecode/toolUseId': 'toolu_1' } });
			}
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const run = (sequence) => agent.act(nativeEvent(sequence), { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED', reasonCode: 'OBSERVED' }) });
		await run(1);
		await run(2);
		await settle();
		assert.equal(children.length, 1, 'no rotation before the minimum number of turns');
		const second = sentPayload(children[0].lines.filter((line) => line.type === 'user')[1].message.content);
		assert.deepEqual(second.data.sameAsPreviousEvent, ['goalSpec', 'taskMemory']);
		await run(3);
		await settle();
		assert.equal(children.length, 2, 'the over-threshold context rotates right after the turn and prewarms');
		assert.notEqual(children[0].exitCode, null);
		await run(4);
		const content = children[1].lines.find((line) => line.type === 'user').message.content;
		assert.match(content, /^Session refreshed to keep context small/);
		assert.match(content, /observe \{\} -> SUCCEEDED OBSERVED/);
		assert.match(content, /Lucas: meet me at the village/);
		assert.match(content, /"programId":"native-program-fixture-12"/);
		assert.match(content, /"pendingDecisionId":"native-program-fixture-12:decision-3"/);
		assert.equal(sentPayload(content).data.sameAsPreviousEvent, undefined, 'a fresh session gets the goal and task memory again');
	} finally { await close(); }
});

test('an unacknowledged interrupt restarts Claude Code without omitted facts from the old session', async () => {
	let answer = true;
	const { service, children, close } = await harness({
		ignoreInterrupt: true,
		async onUser(child) {
			if (answer) child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await agent.act(nativeEvent(1), { goalRevision: 0, executeTool: async () => ({}) });
		answer = false;
		const stuck = agent.act(nativeEvent(2), { goalRevision: 0, executeTool: async () => ({}) });
		void stuck.catch(() => {});
		await settle();
		await agent.interrupt();
		await assert.rejects(stuck);
		answer = true;
		await agent.act(nativeEvent(3), { goalRevision: 0, executeTool: async () => ({}) });
		assert.equal(children.length, 2);
		const content = children[1].lines.find((line) => line.type === 'user').message.content;
		assert.match(content, /^Claude Code restarted/);
		const payload = sentPayload(content).data;
		assert.equal(payload.sameAsPreviousEvent, undefined);
		assert.ok(payload.goalSpec && payload.taskMemory);
	} finally { await close(); }
});

test('a tool result for a settled turn is not committed as an observation baseline', async () => {
	let release;
	let firstView = null;
	let secondView = null;
	const observed = { freshness: { fresh: true }, observation: { world: { worldId: 'w', dimension: 'minecraft:overworld' }, player: { health: 20, x: 1 } } };
	const { service, close } = await harness({
		async onUser(child, content) {
			if (content === 'One.') {
				const response = await child.rpc('tools/call', { name: 'observe', arguments: {}, _meta: { 'claudecode/toolUseId': 'toolu_1' } });
				firstView = JSON.parse(response.result.content[0].text).observationView;
				return;
			}
			const response = await child.rpc('tools/call', { name: 'observe', arguments: { view: 'changes', afterObservationId: firstView.id }, _meta: { 'claudecode/toolUseId': 'toolu_2' } });
			secondView = JSON.parse(response.result.content[0].text).observationView;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const first = agent.act('One.', { goalRevision: 0, executeTool: () => new Promise((resolve) => { release = () => resolve(observed); }) });
		void first.catch(() => {});
		await settle();
		await agent.interrupt();
		await assert.rejects(first);
		release();
		await settle();
		assert.equal(firstView.mode, 'full');
		await agent.act('Two.', { goalRevision: 0, executeTool: async () => observed });
		assert.equal(secondView.mode, 'full', 'a view the model never saw cannot be a changes baseline');
	} finally { await close(); }
});

test('a steer that never reached the model does not become the metadata baseline', async () => {
	let respond;
	const { service, children, close } = await harness({
		async onUser(child) { respond = () => child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' }); },
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act(nativeEvent(1), { goalRevision: 0, executeTool: async () => ({}) });
		await settle();
		const steer = agent.steer(nativeEvent(2, (wake) => ({ ...wake, taskMemory: { ...wake.taskMemory, revision: 99 } })), { goalRevision: 0 });
		void steer.catch(() => {});
		respond();
		await turn;
		await assert.rejects(steer, (error) => error.code === 'TURN_NOT_ACTIVE');
		const next = agent.act(nativeEvent(3), { goalRevision: 0, executeTool: async () => ({}) });
		await settle();
		respond();
		await next;
		const payload = sentPayload(children[0].lines.filter((line) => line.type === 'user')[1].message.content).data;
		assert.deepEqual(payload.sameAsPreviousEvent, ['goalSpec', 'taskMemory'], 'compared with the delivered turn, not the rejected steer');
	} finally { await close(); }
});

test('a Claude Code without --include-partial-messages is detected and relaunched without it', async () => {
	const { service, children, close } = await harness({
		rejectPartialMessages: true,
		async onUser(child) { child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' }); },
	}, { executable: 'claude-without-partial-messages', startupTimeoutMs: 500 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await assert.rejects(agent.prewarm());
		const replacement = await service.replaceAgent(profile(), { controlProtocol: 'native_tools' });
		await replacement.act('One.', { goalRevision: 0, executeTool: async () => ({}) });
		assert.equal(children.at(-1).args.includes('--include-partial-messages'), false);
		assert.ok(children.at(-1).args.includes('stream-json'));
	} finally { await close(); }
});

test('Claude context rotation can be disabled', async () => {
	const { service, children, close } = await harness({
		async onUser(child) {
			child.emitLine({ type: 'assistant', message: { id: `msg_${child.lines.length}`, content: [], usage: { input_tokens: 1, cache_read_input_tokens: 150_000, output_tokens: 3 } } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 0 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		for (const text of ['One.', 'Two.', 'Three.', 'Four.']) await agent.act(text, { goalRevision: 0, executeTool: async () => ({}) });
		await settle();
		assert.equal(children.length, 1);
	} finally { await close(); }
});
