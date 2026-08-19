import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import {
	CursorProviderService,
	buildCursorLaunch,
	parseCursorModelList,
} from '../src/cursor-service.mjs';

const DECISION = JSON.stringify({
	summary: 'Wait safely.',
	directive: 'replace',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(25);',
});

const MODELS_OUTPUT = `Available models
composer-2.5 - Composer 2.5
cursor-grok-4.6-high-fast - Cursor Grok 4.6 Fast
cursor-grok-4.5-high - Cursor Grok 4.5
cursor-grok-4.6-low - Cursor Grok 4.6 Low
composer-2.5-fast - Composer 2.5 Fast
cursor-grok-4.5-low-fast - Cursor Grok 4.5 Low Fast
claude-opus-4-8-high - Claude Opus 4.8
`;

class FakeChild extends EventEmitter {
	constructor() {
		super();
		this.stdin = new EventEmitter();
		this.stdin.chunks = [];
		this.stdin.end = (value = '') => { this.stdin.chunks.push(String(value)); };
		this.stdout = new EventEmitter();
		this.stderr = new EventEmitter();
		this.exitCode = null;
		this.signalCode = null;
		this.pid = 4_242;
	}
}

function config(overrides = {}) {
	return {
		provider: 'cursor',
		cwd: 'C:\\workspace',
		executable: 'C:\\Cursor\\agent.ps1',
		models: ['composer-2.5', 'grok-4.5', 'grok-4.6'],
		modelReasoningEfforts: {
			'composer-2.5': ['high'],
			'grok-4.5': ['low', 'medium', 'high'],
			'grok-4.6': ['low', 'medium', 'high', 'xhigh'],
		},
		planningTimeoutMs: 1_000,
		...overrides,
	};
}

function profile(overrides = {}) {
	return {
		agentId: 'cursor-a',
		provider: 'cursor',
		model: 'composer-2.5',
		reasoningEffort: 'high',
		serviceTier: 'fast',
		...overrides,
	};
}

test('Cursor catalog normalizes native Composer and Cursor Grok variants into three player-facing models', () => {
	assert.deepEqual(parseCursorModelList(MODELS_OUTPUT), [
		{ id: 'composer-2.5', model: 'composer-2.5', displayName: 'Composer 2.5' },
		{ id: 'grok-4.6', model: 'grok-4.6', displayName: 'Grok 4.6' },
		{ id: 'grok-4.5', model: 'grok-4.5', displayName: 'Grok 4.5' },
	]);
});

test('Cursor launch uses native Windows CLI in read-only JSON mode without the unsupported Windows sandbox', () => {
	const launch = buildCursorLaunch(profile(), config(), {
		cwd: 'C:\\agents\\cursor\\cursor-a',
		env: { PATH: 'test', ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret' },
		platform: 'win32',
	});
	assert.equal(launch.command, 'powershell.exe');
	assert.deepEqual(launch.args, [
		'-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', 'C:\\Cursor\\agent.ps1',
		'--print', '--output-format', 'json', '--mode', 'ask', '--trust',
		'--model', 'composer-2.5-fast',
	]);
	assert.equal(launch.options.cwd, 'C:\\agents\\cursor\\cursor-a');
	assert.equal(launch.options.env.PATH, 'test');
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.deepEqual(launch.options.stdio, ['pipe', 'pipe', 'pipe']);
});

test('Cursor launch maps player-facing Grok settings to exact native model IDs', () => {
	const cases = [
		[{ model: 'grok-4.5', reasoningEffort: 'low', serviceTier: 'priority' }, 'cursor-grok-4.5-low'],
		[{ model: 'grok-4.5', reasoningEffort: 'high', serviceTier: 'fast' }, 'cursor-grok-4.5-high-fast'],
		[{ model: 'grok-4.6', reasoningEffort: 'low', serviceTier: 'priority' }, 'cursor-grok-4.6-low'],
		[{ model: 'grok-4.6', reasoningEffort: 'high', serviceTier: 'fast' }, 'cursor-grok-4.6-high-fast'],
	];
	for (const [settings, expectedModel] of cases) {
		const launch = buildCursorLaunch(profile(settings), config(), { platform: 'win32' });
		assert.equal(launch.args[launch.args.indexOf('--model') + 1], expectedModel);
	}
});

test('Cursor parses one JSON result, records provider/API timing, and resumes the same session', async () => {
	const calls = [];
	const children = [];
	const spawn = (command, args, options) => {
		calls.push({ command, args, options });
		const child = new FakeChild();
		children.push(child);
		queueMicrotask(() => {
			child.stdout.emit('data', Buffer.from(JSON.stringify({
				type: 'result', subtype: 'success', is_error: false, result: DECISION,
				session_id: 'cursor-session-1', duration_ms: 1_234, duration_api_ms: 987,
				usage: { inputTokens: 90, outputTokens: 14, cacheReadTokens: 22, cacheWriteTokens: 3 },
			})));
			child.exitCode = 0;
			child.emit('close', 0, null);
		});
		return child;
	};
	const service = new CursorProviderService(config(), {
		spawn,
		discoverCatalog: async () => parseCursorModelList(MODELS_OUTPUT),
		workspaceManager: { async prepare() { return 'C:\\agents\\cursor\\cursor-a'; } },
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(7);
	const turns = [];
	const turnRecorder = { async record(row) { turns.push(row); } };
	const first = await agent.decide('authoritative state', { goalRevision: 7, turnRecorder, queueWaitMs: 11 });
	const second = await agent.decide('compiler correction', { goalRevision: 7, turnRecorder, attempt: 2, retry: true });

	assert.equal(first.directive, 'replace');
	assert.equal(second.directive, 'replace');
	assert.match(children[0].stdin.chunks.join(''), /strategic author for one Minecraft player/);
	assert.match(children[0].stdin.chunks.join(''), /authoritative state/);
	assert.deepEqual(calls[1].args.slice(-2), ['--resume', 'cursor-session-1']);
	assert.equal(turns.length, 2);
	assert.deepEqual(turns[0].timing, { durationMs: 1_234, apiDurationMs: 987, queueWaitMs: 11 });
	assert.equal(turns[0].agentId, 'cursor-a');
	assert.deepEqual(turns[0].tokens, { input: 90, output: 14, reasoning: null, cached: 22, cacheWrite: 3 });
	assert.equal(turns[1].attempt, 2);
	assert.equal(turns[1].retry, true);
	await service.stop();
});

test('Cursor parse failures record only a generic structured error', async () => {
	const secret = 'ARBITRARY_CURSOR_MODEL_SECRET';
	const spawn = () => {
		const child = new FakeChild();
		queueMicrotask(() => {
			child.stdout.emit('data', Buffer.from(JSON.stringify({
				type: 'result', subtype: 'success', is_error: false, result: `not-json ${secret}`,
				session_id: 'cursor-session-secret', duration_ms: 12, duration_api_ms: 9,
			})));
			child.exitCode = 0;
			child.emit('close', 0, null);
		});
		return child;
	};
	const service = new CursorProviderService(config(), {
		spawn, discoverCatalog: async () => parseCursorModelList(MODELS_OUTPUT),
		workspaceManager: { async prepare() { return 'C:\\agents\\cursor\\cursor-secret'; } },
	});
	const agent = await service.createAgent(profile({ agentId: 'cursor-secret' }));
	await agent.setGoalRevision(1);
	const rows = [];
	await assert.rejects(agent.decide('state', { goalRevision: 1, turnRecorder: { async record(row) { rows.push(row); } } }), (error) => error?.code === 'MALFORMED_DECISION');
	assert.equal(rows.length, 1);
	assert.deepEqual(rows[0].error, { code: 'MALFORMED_DECISION', category: 'decision_parse' });
	assert.doesNotMatch(JSON.stringify(rows[0]), new RegExp(secret));
	await service.stop();
});

test('Cursor rejects models outside Composer and Grok plus unsupported settings', async () => {
	assert.throws(
		() => new CursorProviderService(config({ models: ['claude-opus-4-8'], modelReasoningEfforts: { 'claude-opus-4-8': ['high'] } })),
		/model|Composer|Grok/i,
	);
	const service = new CursorProviderService(config(), { discoverCatalog: async () => parseCursorModelList(MODELS_OUTPUT) });
	await assert.rejects(service.createAgent(profile({ model: 'claude-opus-4-8' })), (error) => error?.code === 'UNSUPPORTED_MODEL');
	await assert.rejects(service.createAgent(profile({ reasoningEffort: 'max' })), (error) => error?.code === 'UNSUPPORTED_THINKING');
	await service.stop();
});

test('Cursor interruption terminates the active native process', async () => {
	const child = new FakeChild();
	const terminated = [];
	const service = new CursorProviderService(config(), {
		spawn: () => child,
		discoverCatalog: async () => parseCursorModelList(MODELS_OUTPUT),
		terminate: async (active) => {
			terminated.push(active);
			active.signalCode = 'SIGTERM';
			active.emit('close', null, 'SIGTERM');
		},
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);
	const turn = agent.decide('state', { goalRevision: 1 });
	await agent.interrupt();
	await assert.rejects(turn, (error) => error?.code === 'PLAN_CANCELLED');
	assert.deepEqual(terminated, [child]);
	await service.stop();
});
