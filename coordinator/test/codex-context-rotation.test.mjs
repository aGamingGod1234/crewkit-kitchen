import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService } from '../src/codex-service.mjs';
import { ContextCarryOver } from '../src/context-carry-over.mjs';

// The 2026-10-08 Sol session re-read a growing Codex thread on every call: 17k context tokens on the first call,
// 242k by the 68th. Claude already starts a fresh session past 64k tokens; Codex now does the same.
const MODEL = { id: 'gpt-6.1-sol', model: 'gpt-6.1-sol', supportedReasoningEfforts: [{ reasoningEffort: 'low' }], serviceTiers: [{ id: 'priority' }] };
const profile = { agentId: 'rotating', model: 'gpt-6.1-sol', reasoningEffort: 'low', serviceTier: 'priority' };
const turn = () => new Promise((resolve) => setImmediate(resolve));

class Transport extends EventEmitter {
	calls = [];
	threads = 0;
	turns = 0;
	contextTokens = 1_000;
	tool = null;
	async start() {}
	async stop() {}
	notify() {}
	respond(id, result) { this.calls.push({ method: '$respond', id, result }); setImmediate(() => this.finish()); }
	async request(method, params) {
		this.calls.push({ method, params });
		if (method === 'initialize') return {};
		if (method === 'model/list') return { data: [MODEL], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: `thread-${++this.threads}` } };
		if (method === 'thread/unsubscribe') return { status: 'unsubscribed' };
		if (method === 'turn/start') {
			const turnId = `turn-${++this.turns}`;
			this.active = { threadId: params.threadId, turnId };
			setImmediate(() => {
				if (this.tool === null) return this.finish();
				this.emit('serverRequest', { id: `call-${this.turns}`, method: 'item/tool/call', params: { ...this.active, callId: `call-${this.turns}`, tool: this.tool.name, arguments: this.tool.arguments } });
			});
			return { turn: { id: turnId } };
		}
		if (method === 'turn/interrupt') return {};
		throw new Error(`unexpected ${method}`);
	}
	finish() {
		const { threadId, turnId } = this.active;
		this.emit('notification', { method: 'thread/tokenUsage/updated', params: { threadId, turnId, tokenUsage: {
			last: { inputTokens: this.contextTokens, cachedInputTokens: this.contextTokens - 500, outputTokens: 4 },
			total: { inputTokens: this.contextTokens * this.turns, outputTokens: 4 * this.turns } } } });
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: 'ok' } } });
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'completed' } } });
	}
}

const event = (text = 'event') => `Live Minecraft event.\n${JSON.stringify({ event: 'observation', note: text, conversation: { entries: [{ kind: 'player_message', sourceName: 'Lucas', text: 'find lava' }] } })}`;
const turnStarts = (transport) => transport.calls.filter(({ method }) => method === 'turn/start');

async function setup(config = {}) {
	const transport = new Transport();
	const service = new CodexService({ cwd: 'C:\\workspace', ...config }, { transport });
	const agent = await service.createAgent(profile, { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const act = (text) => agent.act(event(text), { goalRevision: 1, executeTool: async () => ({ state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN' }) });
	return { transport, service, agent, act };
}

test('a Codex thread past the context threshold continues on a fresh thread with a carry-over', async () => {
	const { transport, service, agent, act } = await setup();
	try {
		transport.tool = { name: 'mine', arguments: { x: 1, y: 64, z: 0, expectedBlockId: 'minecraft:stone' } };
		transport.contextTokens = 70_000;
		await act('one');
		assert.equal(agent.rotations, 0, 'a fresh thread waits a few turns before rotating');
		await act('two');
		await act('three');
		await turn();
		assert.equal(agent.rotations, 1);
		assert.equal(transport.calls.filter(({ method }) => method === 'thread/start').length, 2);
		const [first, second] = transport.calls.filter(({ method }) => method === 'thread/start').map(({ params }) => params);
		assert.equal(second.baseInstructions, first.baseInstructions, 'the same instructions and tools keep the prefix stable');
		assert.deepEqual(second.dynamicTools, first.dynamicTools);
		assert.deepEqual(transport.calls.filter(({ method }) => method === 'thread/unsubscribe').map(({ params }) => params.threadId), ['thread-1']);
		transport.contextTokens = 20_000;
		await act('four');
		const fourth = turnStarts(transport).at(-1).params;
		assert.equal(fourth.threadId, 'thread-2');
		const text = fourth.input[0].text;
		assert.match(text, /^Session refreshed to keep context small/);
		assert.match(text, /action:break_block \{[^}]*"x":1[^}]*\} -> SUCCEEDED BLOCK_BROKEN/);
		assert.match(text, /Lucas: find lava/);
		await act('five');
		assert.doesNotMatch(turnStarts(transport).at(-1).params.input[0].text, /Session refreshed/, 'the carry-over is sent once');
	} finally {
		await service.stop();
	}
});

test('small contexts never rotate and 0 disables rotation', async () => {
	for (const [config, tokens] of [[{}, 30_000], [{ contextRotationTokens: 0 }, 500_000]]) {
		const { transport, service, agent, act } = await setup(config);
		try {
			transport.contextTokens = tokens;
			for (let index = 0; index < 5; index++) await act(`turn ${index}`);
			await turn();
			assert.equal(agent.rotations, 0);
			assert.ok(turnStarts(transport).every(({ params }) => params.threadId === 'thread-1'));
		} finally {
			await service.stop();
		}
	}
});

test('carry-over remembers recent tools, chat and program state in a bounded text', () => {
	const carry = new ContextCarryOver();
	for (let index = 0; index < 12; index++) carry.rememberTool({ kind: 'action', actionType: 'break_block', arguments: { x: index } }, { state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN' });
	carry.rememberTool({ kind: 'run_program', source: 'x' }, { programId: 'p-1', state: 'RUNNING', engineState: 'ACTIVE' });
	carry.rememberTool({ kind: 'inspect' }, null, Object.assign(new Error('no'), { code: 'INSPECTION_UNAVAILABLE' }));
	carry.noteEvent(event());
	const text = carry.text('Session refreshed');
	assert.equal((text.match(/^- action:break_block/gm) ?? []).length, 8, 'only the last ten tool calls are kept');
	assert.match(text, /inspect \{\} -> error INSPECTION_UNAVAILABLE/);
	assert.match(text, /"programId":"p-1"/);
	assert.match(text, /Lucas: find lava/);
});
