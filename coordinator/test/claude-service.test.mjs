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
		await this.script.beforeToolsListed?.(this);
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

async function harness(script = {}, overrides = {}, dependencies = {}) {
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
		terminate: dependencies.terminate ?? (async (child) => { if (child.exitCode === null) child.exit(0); }),
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
		assert.deepEqual(verbose.filter(([stage]) => stage !== 'provider_event'), [['agent_message', 'Looking around.']]);
		// Stamped once the MCP response has left the tool server, so the trace does not count formatting as free.
		assert.deepEqual(providerEvents(verbose).filter((entry) => entry.event === 'native_provider_tool_result_sent'), [{ event: 'native_provider_tool_result_sent', callId: 'toolu_1' }]);
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

test('Claude returns a blocking body result early and attaches the latest lazily-built steer', async () => {
	let captured = null;
	let releaseExecution;
	let toolStarted;
	const started = new Promise((resolve) => { toolStarted = resolve; });
	const execution = new Promise((resolve) => { releaseExecution = resolve; });
	const { service, close } = await harness({
		async onUser(child) {
			captured = (await child.rpc('tools/call', { name: 'wait', arguments: { durationMs: 30_000 } })).result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act('Goal.', { goalRevision: 0, executeTool: async () => { toolStarted(); return execution; } });
		void turn.catch(() => {});
		await started;
		let currentFacts = 'stale zombie facts';
		const steer = agent.steer(() => currentFacts, {
			goalRevision: 0,
			onInterrupt: () => releaseExecution({ state: 'RUNNING', actionId: 'body-action-1', interruptedBy: 'danger' }),
		});
		currentFacts = 'fresh zombie and health facts';
		assert.deepEqual(await Promise.race([steer, new Promise((resolve) => setTimeout(() => resolve('BLOCKED'), 100))]), { turnId: '1:1' });
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
		assert.equal(JSON.parse(captured.content[0].text).actionId, 'body-action-1');
		assert.equal(JSON.parse(captured.content[0].text).state, 'RUNNING');
		assert.match(captured.content[1].text, /fresh zombie and health facts/);
	} finally { releaseExecution({ state: 'RUNNING', actionId: 'body-action-1', interruptedBy: 'danger' }); await close(); }
});

test('Claude releases a superseded steer builder reservation', async () => {
	let captured = null;
	let releaseExecution;
	let finishFirstBuilder;
	let firstBuilderStarted;
	let discarded = 0;
	let signalToolStarted;
	const toolStarted = new Promise((resolve) => { signalToolStarted = resolve; });
	const builderStarted = new Promise((resolve) => { firstBuilderStarted = resolve; });
	const firstBuilder = new Promise((resolve) => { finishFirstBuilder = resolve; });
	const execution = new Promise((resolve) => { releaseExecution = resolve; });
	const { service, close } = await harness({
		async onUser(child) {
			captured = (await child.rpc('tools/call', { name: 'wait', arguments: { durationMs: 30_000 } })).result;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act('Goal.', { goalRevision: 0, executeTool: async () => { signalToolStarted(); return execution; } });
		void turn.catch(() => {});
		await toolStarted;
		const first = agent.steer(async () => { firstBuilderStarted(); return firstBuilder; }, { goalRevision: 0, onDiscard: () => { discarded += 1; } });
		releaseExecution({ state: 'RUNNING', actionId: 'body-action-1', interruptedBy: 'danger' });
		await builderStarted;
		const latest = agent.steer(async () => 'newest facts', { goalRevision: 0 });
		finishFirstBuilder('obsolete reservation input');
		assert.deepEqual(await first, { turnId: '1:1' });
		assert.deepEqual(await latest, { turnId: '1:1' });
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
		assert.equal(discarded, 1);
		assert.match(captured.content[1].text, /newest facts/);
		assert.doesNotMatch(captured.content[1].text, /obsolete reservation input/);
	} finally { releaseExecution?.({ state: 'RUNNING', actionId: 'body-action-1', interruptedBy: 'danger' }); finishFirstBuilder?.('cleanup'); await close(); }
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

test('pending Claude steers are superseded and built at the next tool boundary', async () => {
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
		const steers = [
			agent.steer('obsolete threat snapshot', { goalRevision: 0 }),
			agent.steer('obsolete damage snapshot', { goalRevision: 0 }),
			agent.steer('fresh suffocation facts', { goalRevision: 0 }),
		];
		release();
		for (const steer of steers) assert.deepEqual(await steer, { turnId: '1:1' });
		assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
		assert.equal(children[0].lines.filter((line) => line.type === 'control_request' && line.request?.subtype === 'interrupt').length, 0,
			'a steer never interrupts the model mid-reasoning');
		assert.equal(children[0].lines.filter((line) => line.type === 'user').length, 1, 'no restarted or extra turn');
		assert.equal(children.length, 1, 'the warm process and its reasoning are kept');
		assert.equal(captured.content.length, 2, 'the latest pending steer rides on the one tool result');
		assert.match(captured.content[1].text, /fresh suffocation facts/);
		assert.doesNotMatch(captured.content[1].text, /obsolete threat|obsolete damage/);
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

const modelCall = (verbose) => verbose.filter(([stage]) => stage === 'live_usage').map(([, message]) => JSON.parse(message).call);
const providerEvents = (verbose) => verbose.filter(([stage]) => stage === 'provider_event').map(([, message]) => JSON.parse(message));

test('model call trace names every tool the model starts, CLI-local ones included, and sends the turn-sent stage', async () => {
	const { service, close } = await harness({
		async onUser(child) {
			child.emitLine({ type: 'stream_event', event: { type: 'message_start', message: { id: 'msg_a', usage: { input_tokens: 5, cache_read_input_tokens: 1000, cache_creation_input_tokens: 10, output_tokens: 1 } } } });
			child.emitLine({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'toolu_1', name: 'Read', input: {} } } });
			child.emitLine({ type: 'stream_event', event: { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'toolu_2', name: 'mcp__minecraft__observe', input: {} } } });
			child.emitLine({ type: 'stream_event', event: { type: 'message_delta', usage: { output_tokens: 9 } } });
			child.emitLine({ type: 'stream_event', event: { type: 'message_stop' } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const verbose = [];
		await agent.act('One.', { goalRevision: 0, onVerbose: (stage, message) => verbose.push([stage, message]), executeTool: async () => ({}) });
		const [call] = modelCall(verbose);
		assert.deepEqual(call.toolNames, ['Read', 'mcp__minecraft__observe']);
		assert.equal(call.toolStartMs.length, 2);
		assert.equal(call.firstEventSource, 'stream');
		assert.equal(call.streamEvents, 5);
		assert.ok(Number.isSafeInteger(call.requestAt) && Number.isSafeInteger(call.firstEventAt) && call.firstEventAt >= call.requestAt);
		assert.ok(Number.isSafeInteger(call.startMs));
		assert.deepEqual(providerEvents(verbose).map((entry) => entry.event), ['native_provider_turn_sent']);
	} finally { await close(); }
});

test('model call timing starts at the first stream event of any kind when message_start is missing', async () => {
	const { service, close } = await harness({
		async onUser(child) {
			child.emitLine({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } } });
			await settle(40);
			child.emitLine({ type: 'assistant', message: { id: 'msg_b', content: [{ type: 'text', text: 'done' }], usage: { input_tokens: 5, cache_read_input_tokens: 1000, cache_creation_input_tokens: 10, output_tokens: 3 } } });
			child.emitLine({ type: 'stream_event', event: { type: 'message_stop' } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const verbose = [];
		await agent.act('One.', { goalRevision: 0, onVerbose: (stage, message) => verbose.push([stage, message]), executeTool: async () => ({}) });
		const [call] = modelCall(verbose);
		assert.equal(call.firstEventSource, 'stream');
		assert.equal(call.startMs, undefined, 'no message_start was seen');
		assert.ok(call.streamMs >= 30, 'generation time is kept instead of collapsing to the assistant message');
		assert.equal(call.totalMs, call.firstEventMs + call.streamMs);
	} finally { await close(); }
});

test('model call without stream events reports that its first event is the assistant message', async () => {
	const { service, close } = await harness({
		async onUser(child) {
			child.emitLine({ type: 'assistant', message: { id: 'msg_c', content: [{ type: 'text', text: 'done' }], usage: { input_tokens: 5, cache_read_input_tokens: 1000, cache_creation_input_tokens: 10, output_tokens: 3 } } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const verbose = [];
		await agent.act('One.', { goalRevision: 0, onVerbose: (stage, message) => verbose.push([stage, message]), executeTool: async () => ({}) });
		const [call] = modelCall(verbose);
		assert.equal(call.firstEventSource, 'assistant');
		assert.equal(call.streamEvents, 0);
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
			const userTurns = child.lines.filter((line) => line.type === 'user').length;
			usageLine(child, `msg_${child.lines.length}`, userTurns < 3 ? 70_000 : 90_000);
			if (children.length === 1 && child.lines.filter((line) => line.type === 'user').length === 1) {
				await child.rpc('tools/call', { name: 'observe', arguments: {}, _meta: { 'claudecode/toolUseId': 'toolu_1' } });
			}
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const run = (sequence) => agent.act(nativeEvent(sequence), { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED', reasonCode: 'OBSERVED' }) });
		await run(1);
		await run(2);
		await settle();
		assert.equal(children.length, 2, 'the standby is warm while the current process still owns the conversation');
		assert.equal(children[0].exitCode, null, 'hysteresis keeps the current process active until three finished turns');
		const second = sentPayload(children[0].lines.filter((line) => line.type === 'user')[1].message.content);
		assert.deepEqual(second.data.sameAsPreviousEvent, ['goalSpec', 'taskMemory']);
		await run(3);
		await settle();
		assert.equal(children.length, 2, 'the warmed standby becomes the new session without another spawn');
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

test('Claude rotates at a tool boundary and delivers the executed tool result once in the same active turn', async () => {
	let toolExecutions = 0;
	let standbyWarmDuringTool = false;
	let resumedInput = null;
	const { service, children, close } = await harness({
		async onUser(child, content) {
			const index = children.indexOf(child);
			if (index === 0) {
				usageLine(child, 'long-turn-over-threshold', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'one execution' }, _meta: { 'claudecode/toolUseId': 'toolu_rotation' } });
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'old process ended', session_id: 'session-1' });
				return;
			}
			resumedInput = content;
			usageLine(child, 'resumed-turn', 30_000);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: 'session-2' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act(nativeEvent(2), {
			goalRevision: 0,
			executeTool: async () => {
				toolExecutions += 1;
				await settle(100);
				standbyWarmDuringTool = children.length === 2 && !children[1].lines.some((line) => line.type === 'user');
				return { state: 'SUCCEEDED', reasonCode: 'TOOL_RAN' };
			},
		});
		assert.equal(result.status, 'completed');
		assert.equal(result.toolCalls, 1, 'one active turn spans the process handoff');
		assert.equal(toolExecutions, 1, 'the game tool runs once');
		assert.equal(children.length, 2, 'the standby process becomes the active session at the tool boundary');
		assert.equal(standbyWarmDuringTool, true, 'standby startup overlaps execution of the current tool');
		await waitFor(() => children[0].exitCode !== null, 1_000);
		assert.notEqual(children[0].exitCode, null, 'the old process retires after its final result drains in the background');
		assert.match(resumedInput, /^Mid-turn continuation:/);
		assert.match(resumedInput, /Current turn input to continue:[\s\S]*program_attention/);
		assert.match(resumedInput, /Tool result for Minecraft tool "say"/);
		assert.equal((resumedInput.match(/"reasonCode":"TOOL_RAN"/g) ?? []).length, 1, 'the encoded result reaches the replacement model exactly once');
	} finally { await close(); }
});

test('a steer queued before a rotation handoff moves to the replacement session once, with the RUNNING early return', async () => {
	let resumed = null;
	let steerSettled = 0;
	let builds = 0;
	let older = null;
	const { service, children, close } = await harness({
		async onUser(child, content) {
			const index = children.indexOf(child);
			if (index === 0) {
				usageLine(child, 'long-turn-over-threshold', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'long action' }, _meta: { 'claudecode/toolUseId': 'toolu_running' } });
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'old process ended', session_id: 'session-1' });
				return;
			}
			resumed = content;
			usageLine(child, 'resumed-turn', 30_000);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: 'session-2' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act(nativeEvent(2), {
			goalRevision: 0,
			executeTool: async () => {
				// The body is still busy: the tool returns a RUNNING early result while a newer event waits for this boundary.
				older = agent.steer(async () => { builds += 1; return 'OLDER-SUPERSEDED-EVENT'; }, { goalRevision: 0 });
				void agent.steer(async () => { builds += 1; return 'DANGER-EVENT-NEWEST'; }, { goalRevision: 0 }).then(() => { steerSettled += 1; }, () => {});
				await settle(50);
				return { state: 'RUNNING', reasonCode: 'ACTION_RUNNING' };
			},
		});
		assert.equal(result.status, 'completed');
		await older;
		assert.equal(children.length, 2, 'the handoff happened at the RUNNING boundary');
		assert.equal((resumed.match(/DANGER-EVENT-NEWEST/g) ?? []).length, 1, 'the pending steer reaches the replacement exactly once');
		assert.equal((resumed.match(/"reasonCode":"ACTION_RUNNING"/g) ?? []).length, 1, 'the RUNNING result reaches the replacement exactly once');
		assert.doesNotMatch(resumed, /Newer coordinator events/, 'the steer is the current input, not a second copy');
		assert.equal(steerSettled, 1, 'the steer waiter resolves once');
		assert.doesNotMatch(resumed, /OLDER-SUPERSEDED-EVENT/);
		assert.equal(builds, 1, 'only the newest builder runs');
	} finally { await close(); }
});

test('Claude carries a failed tool result across a first-turn boundary rotation exactly once', async () => {
	let toolExecutions = 0;
	let resumedInput = null;
	const { service, children, close } = await harness({
		async onUser(child) {
			const index = children.indexOf(child);
			if (index === 0) {
				usageLine(child, 'first-turn-over-threshold', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'will fail' }, _meta: { 'claudecode/toolUseId': 'toolu_failed_rotation' } });
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'old process ended', session_id: 'session-1' });
				return;
			}
			resumedInput = child.lines.find((line) => line.type === 'user').message.content;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: 'session-2' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act(nativeEvent(1), {
			goalRevision: 0,
			executeTool: async () => {
				toolExecutions += 1;
				throw Object.assign(new Error('fixture body failure'), { code: 'FIXTURE_BODY_FAILED' });
			},
		});
		assert.equal(result.status, 'completed');
		assert.equal(toolExecutions, 1);
		assert.equal(children.length, 2, 'the first long turn can hand off without waiting for an earlier completed turn');
		assert.match(resumedInput, /Tool result for Minecraft tool "say"/);
		assert.equal((resumedInput.match(/"reasonCode":"FIXTURE_BODY_FAILED"/g) ?? []).length, 1);
	} finally { await close(); }
});

test('interrupting while a standby is pending cancels handoff before it writes a ghost turn', async () => {
	let agent;
	let releaseStandby;
	let startedStandby;
	const standbyStarted = new Promise((resolve) => { startedStandby = resolve; });
	const { service, children, close } = await harness({
		async beforeToolsListed(child) {
			if (children.indexOf(child) === 1) {
				startedStandby();
				await new Promise((resolve) => { releaseStandby = resolve; });
			}
		},
		async onUser(child) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'interrupt-during-handoff', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'one execution' }, _meta: { 'claudecode/toolUseId': 'toolu_interrupt_handoff' } });
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'interrupted', session_id: 'session-1' });
			}
		},
	}, { contextRotationTokens: 80_000 });
	try {
		agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const first = agent.act('Long turn.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED', reasonCode: 'DONE' }) });
		await standbyStarted;
		await agent.interrupt();
		await assert.rejects(first);
		releaseStandby();
		assert.equal(children.filter((child) => child.lines.some((line) => line.type === 'user')).length, 1,
			'the standby never receives the canceled turn handoff');
		assert.equal(children[0].exitCode, null, 'the old session remains current after canceled promotion');
	} finally { releaseStandby?.(); await close(); }
});

test('a failed standby promotion returns the completed tool result to the live session', async () => {
	let executions = 0;
	const { service, children, close } = await harness({
		async beforeToolsListed(child) { if (children.indexOf(child) === 1) child.exit(1); },
		async onUser(child) {
			if (children.indexOf(child) !== 0) return;
			usageLine(child, 'failed-promote', 85_000);
			const response = await child.rpc('tools/call', { name: 'say', arguments: { message: 'already ran' }, _meta: { 'claudecode/toolUseId': 'toolu_failed_promote' } });
			assert.equal(JSON.parse(response.result.content[0].text).reasonCode, 'DONE');
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued on old session', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Long turn.', { goalRevision: 0, executeTool: async () => { executions += 1; return { state: 'SUCCEEDED', reasonCode: 'DONE' }; } });
		assert.equal(result.status, 'completed');
		assert.equal(executions, 1);
		assert.equal(children[0].exitCode, null, 'the running process receives the result after standby startup fails');
		assert.equal(children[1].lines.some((line) => line.type === 'user'), false);
	} finally { await close(); }
});

test('a goal change during old-process retirement cannot overwrite the promoted Claude session', async () => {
	let agent;
	let goalChangeStarted = false;
	let releaseGoalChange;
	const goalChanged = new Promise((resolve) => { releaseGoalChange = resolve; });
	const { service, children, close } = await harness({
		async onUser(child, content) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'before-goal-change', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'one execution' }, _meta: { 'claudecode/toolUseId': 'toolu_goal_change' } });
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'old final', session_id: 'session-1' });
				return;
			}
			await goalChanged;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'next goal complete', session_id: 'session-2' });
		},
	}, { contextRotationTokens: 80_000 }, {
		terminate: async (child) => {
			if (child === children[0] && !goalChangeStarted) {
				goalChangeStarted = true;
				await agent.setGoalRevision(1);
				releaseGoalChange();
			}
			if (child.exitCode === null) child.exit(0);
		},
	});
	try {
		agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const first = agent.act('Old goal.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED', reasonCode: 'DONE' }) });
		await assert.rejects(first);
		assert.equal(goalChangeStarted, true, 'the goal changed during the old process retirement window');
		const second = await agent.act('Latest goal.', { goalRevision: 1, executeTool: async () => ({}) });
		assert.equal(second.status, 'completed');
		assert.equal(children.length, 2, 'the second act reuses the promoted process instead of spawning an orphan');
		const replacementInputs = children[1].lines.filter((line) => line.type === 'user').map((line) => line.message.content);
		assert.equal(replacementInputs.length, 2, 'the promoted session contains its handoff and the new goal');
		assert.match(replacementInputs[0], /^Mid-turn continuation/);
		assert.equal(replacementInputs[1], 'Latest goal.');
	} finally { releaseGoalChange(); await close(); }
});

test('Claude rejects a queued tool call from the retired route without executing it', async () => {
	let calls = [];
	let responses = [];
	const { service, children, close } = await harness({
		async onUser(child) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'parallel-tools', 85_000);
				responses = await Promise.all([
					child.rpc('tools/call', { name: 'say', arguments: { message: 'first' }, _meta: { 'claudecode/toolUseId': 'toolu_first' } }),
					child.rpc('tools/call', { name: 'say', arguments: { message: 'second' }, _meta: { 'claudecode/toolUseId': 'toolu_second' } }),
				]);
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'old process finished', session_id: 'session-1' });
				return;
			}
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: 'session-2' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Long turn.', { goalRevision: 0, executeTool: async ({ tool }) => {
			calls.push(tool.kind);
			await settle(30);
			return { state: 'SUCCEEDED', reasonCode: `RAN_${calls.length}` };
		} });
		assert.equal(result.toolCalls, 1, 'the stale second call is not counted on the replacement route');
		assert.equal(calls.length, 1, 'the stale second call does not execute');
		await waitFor(() => responses.length === 2);
		assert.equal(responses.length, 2);
		assert.ok(responses.every((response) => JSON.parse(response.result.content[0].text).reasonCode === 'SESSION_ROTATED'));
	} finally { await close(); }
});

test('Claude can rotate again during a later long turn', async () => {
	let toolExecutions = 0;
	const { service, children, close } = await harness({
		async onUser(child, content) {
			if (content.startsWith('Mid-turn continuation')) {
				child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: `session-${children.indexOf(child) + 1}` });
				return;
			}
			usageLine(child, `long-${children.indexOf(child)}-${child.lines.length}`, 85_000);
			await child.rpc('tools/call', { name: 'say', arguments: { message: 'continue' }, _meta: { 'claudecode/toolUseId': `toolu_${children.indexOf(child)}` } });
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: `session-${children.indexOf(child) + 1}` });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await agent.act('First long turn.', { goalRevision: 0, executeTool: async () => { toolExecutions += 1; return { state: 'SUCCEEDED' }; } });
		await agent.act('Second long turn.', { goalRevision: 0, executeTool: async () => { toolExecutions += 1; return { state: 'SUCCEEDED' }; } });
		assert.equal(toolExecutions, 2);
		assert.equal(children.length, 3, 'the second active turn is allowed its own handoff');
	} finally { await close(); }
});

test('a hung Claude standby times out quickly and is not respawned at each tool boundary', async () => {
	let releaseStandby;
	let calls = 0;
	const keepAlive = setTimeout(() => {}, 20_000);
	const { service, children, close } = await harness({
		async beforeToolsListed(child) {
			if (children.indexOf(child) === 1) await new Promise((resolve) => { releaseStandby = resolve; });
		},
		async onUser(child) {
			for (let index = 0; index < 2; index++) {
				usageLine(child, `hung-${index}`, 85_000);
				calls += 1;
				const started = Date.now();
				await child.rpc('tools/call', { name: 'say', arguments: { message: `boundary-${index}` }, _meta: { 'claudecode/toolUseId': `toolu_hung_${index}` } });
				assert.ok(Date.now() - started < 1_000, 'a hung standby cannot use the full provider startup timeout');
			}
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'done', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000, startupTimeoutMs: 5_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Two boundaries.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED' }) });
		assert.equal(result.toolCalls, 2);
		assert.equal(calls, 2);
		assert.equal(children.length, 2, 'a failed warmup is not retried at the next boundary');
	} finally { clearTimeout(keepAlive); releaseStandby?.(); await close(); }
});

test('Claude books the calls of a retired process that never reports a result, and the replacement result adds to them', async () => {
	let agent;
	const { service, children, close } = await harness({
		async onUser(child) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'old-process-call', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'one execution' }, _meta: { 'claudecode/toolUseId': 'toolu_usage' } });
				return;
			}
			usageLine(child, 'replacement-process-call', 30_000);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'continued', session_id: 'session-2', total_cost_usd: 0.01, usage: { output_tokens: 5 } });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Long turn.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED' }) });
		assert.equal(result.usage.calls, 2);
		assert.equal(result.usage.cacheRead, 115_000);
		// 5 input, 85000 read, 200 written and 40 output tokens at the traced price card, plus the reported 0.01.
		assert.equal(Math.round(result.usage.costUsd * 1e5), Math.round((5 * 2e-6 + 85_000 * 0.2e-6 + 200 * 8e-6 + 40 * 20e-6 + 0.01) * 1e5));
	} finally { await close(); }
});

test('Claude books a call that was still streaming when its tool request triggered the handoff', async () => {
	const { service, children, close } = await harness({
		async onUser(child) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'finished-call', 85_000);
				// The tool request arrives before this call's message_stop, as with a streaming Claude Code.
				child.emitLine({ type: 'stream_event', event: { type: 'message_start', message: { id: 'open-call', usage: { input_tokens: 3, cache_read_input_tokens: 90_000, cache_creation_input_tokens: 100, output_tokens: 1 } } } });
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'once' }, _meta: { 'claudecode/toolUseId': 'toolu_open' } });
				return;
			}
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'done', session_id: 'session-2', total_cost_usd: 0.01, usage: { output_tokens: 5 } });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Long turn.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED' }) });
		assert.equal(result.usage.calls, 2, 'the open call counts as a call');
		assert.equal(result.usage.cacheRead, 175_000);
		const expected = (5 * 2e-6 + 85_000 * 0.2e-6 + 200 * 8e-6 + 40 * 20e-6) + (3 * 2e-6 + 90_000 * 0.2e-6 + 100 * 8e-6 + 1 * 20e-6) + 0.01;
		assert.equal(Math.round(result.usage.costUsd * 1e5), Math.round(expected * 1e5));
	} finally { await close(); }
});

test('Claude stops the replaced process before answering its held request, so it cannot start another model call', async () => {
	let heldRequestAnswered = false;
	let exitedBeforeAnswer = null;
	let terminated = 0;
	const { service, children, close } = await harness({
		async onUser(child) {
			if (children.indexOf(child) === 0) {
				usageLine(child, 'retiring-call', 85_000);
				await child.rpc('tools/call', { name: 'say', arguments: { message: 'once' }, _meta: { 'claudecode/toolUseId': 'toolu_retire' } });
				exitedBeforeAnswer = child.exitCode !== null;
				heldRequestAnswered = true;
				return;
			}
			usageLine(child, 'replacement-call', 30_000);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'done', session_id: 'session-2', total_cost_usd: 0.01, usage: { output_tokens: 5 } });
		},
	}, { contextRotationTokens: 80_000 }, {
		terminate: async (child) => {
			if (child === children[0]) terminated += 1;
			if (child.exitCode === null) child.exit(0);
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const result = await agent.act('Long turn.', { goalRevision: 0, executeTool: async () => ({ state: 'SUCCEEDED' }) });
		assert.equal(result.status, 'completed');
		await waitFor(() => heldRequestAnswered);
		assert.equal(terminated, 1, 'the old process is terminated once, immediately');
		assert.equal(exitedBeforeAnswer, true, 'the held request is only released after the old process is gone');
		assert.equal(children[0].lines.filter((line) => line.type === 'user').length, 1, 'the old process never gets another user message');
	} finally { await close(); }
});

test('a warm standby expires even when the turn that warmed it failed', async () => {
	const { service, children, close } = await harness({
		async onUser(child) {
			usageLine(child, 'failing-turn', 85_000);
			await settle(20);
			child.emitLine({ type: 'result', subtype: 'error_during_execution', is_error: true, result: 'boom', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000, standbyIdleTimeoutMs: 100 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		await assert.rejects(agent.act('Failing long turn.', { goalRevision: 0, executeTool: async () => ({}) }));
		await waitFor(() => children.length === 2);
		assert.equal(children[1].exitCode, null, 'the standby is warm right after the failed turn');
		await waitFor(() => children[1].exitCode !== null, 1_500);
		assert.notEqual(children[1].exitCode, null, 'an idle standby is not kept for the rest of the process');
	} finally { await close(); }
});

test('rotation still happens when the standby never becomes ready', async () => {
	let releaseStandby;
	const { service, children, close } = await harness({
		async beforeToolsListed(child) {
			if (children.indexOf(child) === 1) await new Promise((resolve) => { releaseStandby = resolve; });
		},
		async onUser(child) {
			usageLine(child, `over-${child.lines.length}`, 90_000);
			await settle(100);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		for (let index = 1; index <= 8; index++) await agent.act(`Turn ${index}`, { goalRevision: 0, executeTool: async () => ({}) });
		await settle();
		assert.notEqual(children[0].exitCode, null, 'the over-threshold session was replaced');
		assert.notEqual(children[1].exitCode, null, 'the hung standby was stopped, not promoted');
		const restartedInputs = children.slice(2).flatMap((child) => child.lines.filter((line) => line.type === 'user').map((line) => line.message.content));
		assert.match(restartedInputs[0], /^Session refreshed to keep context small/, 'the next turn runs on the fresh session with the carry-over');
	} finally { releaseStandby?.(); await close(); }
});

test('rotation still happens when the shared standby cap leaves this agent without a standby', async () => {
	let releaseBlocker;
	const blockerGate = new Promise((resolve) => { releaseBlocker = resolve; });
	const { service, children, close } = await harness({
		async onUser(child, content) {
			usageLine(child, `over-${child.lines.length}`, 90_000);
			if (content.startsWith('Blocker')) await blockerGate;
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000, maxConcurrentStandbys: 1 });
	try {
		const blocker = await service.createAgent(profile({ agentId: 'claude-blocker' }), { controlProtocol: 'native_tools' });
		const other = await service.createAgent(profile({ agentId: 'claude-other' }), { controlProtocol: 'native_tools' });
		const held = blocker.act('Blocker turn that keeps its standby lease.', { goalRevision: 0, executeTool: async () => ({}) });
		await waitFor(() => children.length === 2);
		for (let index = 1; index <= 4; index++) await other.act(`Other turn ${index}`, { goalRevision: 0, executeTool: async () => ({}) });
		await settle();
		const otherInputs = children.slice(2).flatMap((child) => child.lines.filter((line) => line.type === 'user').map((line) => line.message.content));
		assert.equal(children.length, 4, 'the capped agent got no standby; it restarted once after its third turn');
		assert.match(otherInputs.at(-1), /^Session refreshed to keep context small[\s\S]*Other turn 4/);
		releaseBlocker();
		await held;
	} finally { releaseBlocker(); await close(); }
});

test('Claude arms rotation carry-over before a slow process stop and consumes it once', async () => {
	let releaseStop;
	let stopStarted;
	const stopGate = new Promise((resolve) => { releaseStop = resolve; });
	const stopping = new Promise((resolve) => { stopStarted = resolve; });
	const { service, children, close } = await harness({
		async onUser(child) {
			usageLine(child, `rotation-${child.lines.length}`, 90_000);
			child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' });
		},
	}, { contextRotationTokens: 80_000 }, {
		terminate: async (child) => {
			if (child === children[0]) { stopStarted(); await stopGate; }
			if (child.exitCode === null) child.exit(0);
		},
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		for (let index = 0; index < 3; index++) await agent.act(`Before rotation ${index}`, { goalRevision: 0, executeTool: async () => ({}) });
		await stopping;
		await agent.act('Racing event', { goalRevision: 0, executeTool: async () => ({}) });
		releaseStop();
		await settle();
		await agent.act('Following event', { goalRevision: 0, executeTool: async () => ({}) });
		const userInputs = children.flatMap((child) => child.lines.filter((line) => line.type === 'user').map((line) => line.message.content));
		assert.match(userInputs.find((text) => text.includes('Racing event')), /^Session refreshed to keep context small/);
		assert.doesNotMatch(userInputs.find((text) => text.includes('Following event')), /earlier turns are not shown/,
			'a completed stop cannot re-arm carry-over already consumed by the racing turn');
	} finally {
		releaseStop();
		await close();
	}
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

test('a tool result for a settled turn is not committed as an observation baseline', { timeout: 3_000 }, async () => {
	let release;
	let toolStarted;
	const executingTool = new Promise((resolve) => { toolStarted = resolve; });
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
		const first = agent.act('One.', { goalRevision: 0, executeTool: () => new Promise((resolve) => { release = () => resolve(observed); toolStarted(); }) });
		void first.catch(() => {});
		await executingTool;
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
	let responseCount = 0;
	const { service, children, close } = await harness({
		async onUser(child) { responseCount += 1; respond = () => child.emitLine({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 'session-1' }); },
	});
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		const turn = agent.act(nativeEvent(1), { goalRevision: 0, executeTool: async () => ({}) });
		await waitFor(() => responseCount > 0);
		const steer = agent.steer(nativeEvent(2, (wake) => ({ ...wake, taskMemory: { ...wake.taskMemory, revision: 99 } })), { goalRevision: 0 });
		void steer.catch(() => {});
		respond();
		await turn;
		await assert.rejects(steer, (error) => error.code === 'TURN_NOT_ACTIVE');
		const previousResponses = responseCount;
		const next = agent.act(nativeEvent(3), { goalRevision: 0, executeTool: async () => ({}) });
		await waitFor(() => responseCount > previousResponses);
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
	}, { executable: 'claude-without-partial-messages', startupTimeoutMs: 60_000 });
	try {
		const agent = await service.createAgent(profile(), { controlProtocol: 'native_tools' });
		// The exit itself fails prewarm; a long startup deadline proves nothing waits for it.
		await assert.rejects(agent.prewarm(), (error) => error.code === 'PROVIDER_UNAVAILABLE');
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
