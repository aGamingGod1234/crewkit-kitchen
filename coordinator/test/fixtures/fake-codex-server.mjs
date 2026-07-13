import { EventEmitter } from 'node:events';

export class FakeCodexServer extends EventEmitter {
	#config;
	#outputs;
	#turnSequence = 0;
	#started = false;
	calls = [];

	constructor(config, outputs) {
		super();
		this.#config = config;
		this.#outputs = [...outputs];
	}

	get stopped() { return !this.#started; }
	get threadStart() { return this.calls.find((call) => call.method === 'thread/start')?.params ?? null; }

	async start() {
		this.#started = true;
		this.calls.push({ method: '$start' });
	}

	async stop() {
		this.#started = false;
		this.calls.push({ method: '$stop' });
	}

	notify(method, params) {
		this.#requireStarted();
		this.calls.push({ method, params });
	}

	async request(method, params) {
		this.#requireStarted();
		this.calls.push({ method, params });
		switch (method) {
			case 'initialize':
				return { userAgent: 'fake-codex-app-server' };
			case 'model/list':
				return {
					data: [{
						id: this.#config.model,
						model: this.#config.model,
						displayName: this.#config.model,
						description: 'fixture',
						hidden: false,
						isDefault: true,
						defaultReasoningEffort: this.#config.reasoningEffort,
						supportedReasoningEfforts: [{ reasoningEffort: this.#config.reasoningEffort, description: 'fixture' }],
						serviceTiers: [{ id: 'fast', name: 'Fast', description: 'fixture' }],
					}],
					nextCursor: null,
				};
			case 'thread/start':
				return { thread: { id: `${this.#config.agentId}-thread` } };
			case 'turn/start':
				return this.#startTurn(params);
			case 'turn/interrupt':
				return {};
			default:
				throw new Error(`Unexpected fake Codex method '${method}'`);
		}
	}

	#startTurn(params) {
		this.#turnSequence += 1;
		const turnId = `${this.#config.agentId}-turn-${this.#turnSequence}`;
		const output = this.#outputs.shift();
		if (typeof output !== 'string') throw new Error(`No fake Codex output remains for ${this.#config.agentId}`);
		setImmediate(() => {
			if (!this.#started) return;
			this.emit('notification', {
				method: 'item/completed',
				params: {
					threadId: `${this.#config.agentId}-thread`,
					turnId,
					completedAtMs: Date.now(),
					item: { id: `${turnId}-message`, type: 'agentMessage', text: output },
				},
			});
			this.emit('notification', {
				method: 'turn/completed',
				params: {
					threadId: `${this.#config.agentId}-thread`,
					turn: { id: turnId, status: 'completed', items: [], error: null },
				},
			});
		});
		return { turn: { id: turnId, status: 'inProgress', items: [], error: null } };
	}

	#requireStarted() {
		if (!this.#started) throw new Error('fake Codex server is stopped');
	}
}
