import { EventEmitter } from 'node:events';
import { CodexService as ProductionService } from '../../src/codex-service.mjs';

// Substitute only provider transport; the CLI uses the production collector and normalization.
class OfflineTransport extends EventEmitter {
	turn = 0;
	queue = [];
	active = null;
	async start() {}
	async stop() {}
	notify() {}
	async request(method, params) {
		if (method === 'initialize') return {};
		if (method === 'model/list') return { data: [{ id: 'gpt-5.6-luna', model: 'gpt-5.6-luna',
			supportedReasoningEfforts: [{ reasoningEffort: 'xhigh' }], serviceTiers: [{ id: 'fast' }] }], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: 'offline-thread' } };
		if (method === 'turn/start') {
			const turnId = `offline-turn-${++this.turn}`;
			this.queue = this.turn < 3 ? [['say', { message: 'Hello!' }]]
				: this.turn === 4 ? [['act', { actionType: 'craft_inventory', arguments: {
					recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15000 } }]] : movementAndMining();
			this.active = { threadId: params.threadId, turnId };
			setImmediate(() => this.next());
			return { turn: { id: turnId } };
		}
		if (['turn/interrupt', 'thread/archive'].includes(method)) return {};
		throw new Error(`Unexpected offline request ${method}`);
	}
	next() {
		const next = this.queue.shift();
		if (!next) return this.emit('notification', { method: 'turn/completed', params: {
			...this.active, turn: { id: this.active.turnId, status: 'completed' } } });
		this.emit('serverRequest', { id: `${this.turn}-${this.queue.length}`, method: 'item/tool/call', params: {
			...this.active, callId: `${this.turn}-${this.queue.length}`, tool: next[0], arguments: next[1] } });
	}
	async respond() { setImmediate(() => this.next()); }
}

function movementAndMining() {
	const variant = process.env.NATIVE_BENCHMARK_CASE;
	const endpoint = { x: 2, y: 64, z: 1 };
	if (['adjacent', 'adjacent-autoAim'].includes(variant)) endpoint.x = 1;
	if (variant === 'fractional-near') { endpoint.x = 2.6; endpoint.z = 1.6; }
	if (variant === 'boundary') endpoint.z = 2;
	if (variant === 'outside') endpoint.x = 3.01;
	if (variant === 'diagonal') { endpoint.x = 3; endpoint.z = 2; }
	if (variant === 'wrong-height') endpoint.y = 65;
	const mine = { x: variant === 'wrong-target' ? 3 : 2, y: 64, z: 1,
		expectedBlockId: variant === 'wrong-block' ? 'minecraft:dirt' : 'minecraft:stone',
		...(['autoAim', 'adjacent-autoAim'].includes(variant) ? { autoAim: true } : {}) };
	const calls = [['moveTo', endpoint], ['mine', mine]];
	if (variant === 'reversed') calls.reverse();
	if (variant === 'missing-mine') calls.pop();
	if (variant === 'missing-move') calls.shift();
	if (variant === 'extra') calls.push(['say', { message: 'Extra work' }]);
	if (['wrong-aim', 'reversed-aim', 'extra-sequence', 'finish-sequence'].includes(variant)) {
		const aim = { actionType: 'look_at', arguments: { x: variant === 'wrong-aim' ? 3.5 : 2.5, y: 64.5, z: 1.5 } };
		const actions = [aim, { actionType: 'break_block', arguments: mine }];
		if (variant === 'reversed-aim') actions.reverse();
		if (variant === 'extra-sequence') actions.push({ actionType: 'chat', arguments: { message: 'Extra work' } });
		calls[1] = ['sequence', { actions, ...(variant === 'finish-sequence' ? { finish: { summary: 'Done' } } : {}) }];
	}
	return calls;
}

export class CodexService extends ProductionService {
	constructor(config, dependencies) { super(config, { ...dependencies, transport: new OfflineTransport() }); }
}
