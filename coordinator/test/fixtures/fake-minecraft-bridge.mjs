import net from 'node:net';

import { encodeJsonLine, JsonlDecoder } from '../../src/jsonl.mjs';

export class FakeMinecraftBridge {
	#agentId;
	#server;
	#socket = null;
	#decoder = null;
	#messageSequence = 0;
	#authenticated = false;
	#connectionCount = 0;
	#crossAgentMessages = 0;
	#actions = [];

	constructor(agentId) {
		this.#agentId = agentId;
		this.#server = net.createServer((socket) => this.#accept(socket));
	}

	get port() { return this.#server.address().port; }
	get connectionCount() { return this.#connectionCount; }
	get crossAgentMessages() { return this.#crossAgentMessages; }
	get actions() { return [...this.#actions]; }

	async start() {
		await new Promise((resolve, reject) => {
			this.#server.once('error', reject);
			this.#server.listen(0, '127.0.0.1', () => {
				this.#server.off('error', reject);
				resolve();
			});
		});
	}

	async waitUntilAuthenticated(minimumConnections = 1) {
		await eventually(() => this.#authenticated && this.#connectionCount >= minimumConnections, `fake bridge ${this.#agentId} did not authenticate`);
	}

	sendGoal(goal) {
		this.#send({
			type: 'goal_event',
			operation: 'set',
			goal,
		});
	}

	async disconnect() {
		const socket = this.#socket;
		if (socket === null) return;
		await new Promise((resolve) => {
			socket.once('close', resolve);
			socket.destroy();
		});
	}

	async stop() {
		if (this.#socket !== null && !this.#socket.destroyed) this.#socket.destroy();
		if (!this.#server.listening) return;
		await new Promise((resolve, reject) => this.#server.close((error) => error ? reject(error) : resolve()));
	}

	#accept(socket) {
		this.#connectionCount += 1;
		this.#authenticated = false;
		this.#socket = socket;
		this.#decoder = new JsonlDecoder();
		socket.setNoDelay(true);
		socket.on('data', (chunk) => {
			try {
				for (const message of this.#decoder.push(chunk)) this.#handle(message);
			} catch {
				socket.destroy();
			}
		});
		socket.on('close', () => {
			if (socket === this.#socket) {
				this.#socket = null;
				this.#authenticated = false;
			}
		});
	}

	#handle(message) {
		if (message.agentId !== this.#agentId) {
			this.#crossAgentMessages += 1;
			this.#socket.destroy();
			return;
		}
		if (!this.#authenticated) {
			if (message.type !== 'hello') throw new Error('first message must be hello');
			this.#authenticated = true;
			this.#send({ type: 'hello_ack', replyTo: message.messageId });
			return;
		}
		switch (message.type) {
			case 'request_observation':
				this.#sendObservation();
				break;
			case 'action_command':
				this.#actions.push(structuredClone(message.command));
				setImmediate(() => {
					if (this.#authenticated) this.#send({
						type: 'action_result',
						commandId: message.command.commandId,
						state: 'SUCCEEDED',
						reasonCode: 'FIXTURE_DONE',
						message: '',
						completedAtEpochMs: Date.now(),
					});
				});
				break;
			case 'cancel_action':
				this.#send({ type: 'action_result', commandId: message.commandId, state: 'CANCELLED', reasonCode: 'COORDINATOR_CANCELLED', message: '', completedAtEpochMs: Date.now() });
				break;
			case 'shutdown':
				this.#socket.destroy();
				break;
			default:
				throw new Error(`unexpected coordinator message '${message.type}'`);
		}
	}

	#sendObservation() {
		this.#send({
			type: 'observation',
			ready: true,
			status: 'ready',
			position: { x: 0, y: 64, z: 0 },
			velocity: { x: 0, y: 0, z: 0 },
			view: { yaw: 0, pitch: 0 },
			player: { health: 20, maxHealth: 20, hunger: 20, armor: 0, effects: [] },
			inventory: { selectedSlot: 0, selectedItemId: 'minecraft:air', selectedItemCount: 0, items: [] },
			entities: [],
			blocks: [],
			world: { dimensionId: 'minecraft:overworld', gameTime: 1, defaultClockTime: 1, raining: false, thundering: false },
			currentAction: { present: false, commandId: '', type: '', state: '' },
			lastResult: { present: false, commandId: '', state: '', reasonCode: '', message: '', completedAtEpochMs: 0 },
		});
	}

	#send(payload) {
		if (this.#socket === null || this.#socket.destroyed) throw new Error(`fake bridge ${this.#agentId} is disconnected`);
		this.#messageSequence += 1;
		this.#socket.write(encodeJsonLine({ protocolVersion: 1, agentId: this.#agentId, messageId: `server-${this.#messageSequence}`, ...payload }));
	}
}

async function eventually(predicate, message) {
	const deadline = Date.now() + 5_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(message);
}
