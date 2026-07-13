import { EventEmitter } from 'node:events';
import net from 'node:net';

import {
	LOOPBACK_HOST,
	MAX_COMMAND_ID_LENGTH,
	MAX_GOAL_LENGTH,
	MAX_LINE_BYTES,
	MAX_REASON_CODE_LENGTH,
	MAX_RESULT_MESSAGE_LENGTH,
	MAX_SUMMARY_LENGTH,
	PROTOCOL_VERSION,
} from './constants.mjs';
import { encodeJsonLine, JsonlDecoder } from './jsonl.mjs';
import {
	createActionCommand,
	validateActionProgress,
	validateActionResult,
	validateEnvelope,
	validateObservation,
	ValidationError,
} from './schema.mjs';

const DEFAULT_RECONNECT_DELAY_MS = 500;
const DEFAULT_MAX_RECONNECT_DELAY_MS = 5_000;
const MAX_INBOUND_MESSAGE_IDS = 4_096;
const MAX_TERMINAL_COMMAND_IDS = 4_096;

export class BridgeProtocolError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'BridgeProtocolError';
		this.code = code;
	}
}

export class MessageIdGenerator {
	#prefix;
	#sequence = 0;

	constructor(prefix = 'coordinator') {
		if (typeof prefix !== 'string' || prefix.trim().length === 0 || prefix.length > MAX_COMMAND_ID_LENGTH - 2) {
			throw new TypeError(`message ID prefix must be nonblank and leave room within ${MAX_COMMAND_ID_LENGTH} characters`);
		}
		this.#prefix = prefix;
	}

	next() {
		if (this.#sequence === Number.MAX_SAFE_INTEGER) throw new BridgeProtocolError('MESSAGE_ID_EXHAUSTED', 'Message ID sequence is exhausted');
		this.#sequence += 1;
		const value = `${this.#prefix}-${this.#sequence}`;
		if (value.length > MAX_COMMAND_ID_LENGTH) throw new BridgeProtocolError('MESSAGE_ID_EXHAUSTED', 'Message ID exceeds the protocol limit');
		return value;
	}
}

export class MinecraftBridge extends EventEmitter {
	#agentId;
	#host;
	#port;
	#socketFactory;
	#schedule;
	#cancelSchedule;
	#now;
	#initialReconnectDelayMs;
	#maxReconnectDelayMs;
	#reconnectDelayMs;
	#messageIds = new MessageIdGenerator('coordinator');
	#commandIds;
	#socket = null;
	#decoder = null;
	#running = false;
	#ready = false;
	#helloMessageId = null;
	#reconnectHandle = null;
	#inboundMessageIds = new Set();
	#terminalCommandIds = new Set();

	constructor(config, dependencies = {}) {
		super();
		if (config === null || typeof config !== 'object') throw new TypeError('bridge config must be an object');
		this.#agentId = requireBoundedText(config.agentId, 'agentId');
		this.#host = config.host ?? LOOPBACK_HOST;
		if (this.#host !== LOOPBACK_HOST) throw new BridgeProtocolError('LOOPBACK_REQUIRED', `Minecraft bridge host must be ${LOOPBACK_HOST}`);
		if (!Number.isInteger(config.port) || config.port < 1_024 || config.port > 65_535) throw new TypeError('bridge port must be an integer between 1024 and 65535');
		this.#port = config.port;
		this.#initialReconnectDelayMs = positiveInteger(config.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS, 'reconnectDelayMs');
		this.#maxReconnectDelayMs = positiveInteger(config.maxReconnectDelayMs ?? DEFAULT_MAX_RECONNECT_DELAY_MS, 'maxReconnectDelayMs');
		if (this.#initialReconnectDelayMs > this.#maxReconnectDelayMs) throw new TypeError('reconnectDelayMs must not exceed maxReconnectDelayMs');
		this.#reconnectDelayMs = this.#initialReconnectDelayMs;
		this.#socketFactory = dependencies.socketFactory ?? ((options) => net.createConnection(options));
		this.#schedule = dependencies.schedule ?? ((callback, delay) => setTimeout(callback, delay));
		this.#cancelSchedule = dependencies.cancelSchedule ?? clearTimeout;
		this.#now = dependencies.now ?? Date.now;
		this.#commandIds = new MessageIdGenerator(`${this.#agentId}-action`);
	}

	get agentId() { return this.#agentId; }
	get isReady() { return this.#ready; }

	start() {
		if (this.#running) return;
		this.#running = true;
		this.#connect();
	}

	stop() {
		if (!this.#running && this.#socket === null) return;
		this.#running = false;
		this.#ready = false;
		if (this.#reconnectHandle !== null) {
			this.#cancelSchedule(this.#reconnectHandle);
			this.#reconnectHandle = null;
		}
		const socket = this.#socket;
		this.#socket = null;
		if (socket !== null && !socket.destroyed) socket.destroy();
		this.emit('stopped');
	}

	sendAction(action) {
		this.#requireReady();
		const commandId = this.#commandIds.next();
		const command = createActionCommand(action, { commandId, issuedAtEpochMs: this.#now() });
		this.#send('action_command', { command });
		return commandId;
	}

	cancelAction(commandId) {
		this.#requireReady();
		requireBoundedText(commandId, 'commandId');
		this.#send('cancel_action', { commandId });
	}

	requestObservation() {
		this.#requireReady();
		this.#send('request_observation');
	}

	shutdown() {
		if (this.#ready) this.#send('shutdown');
		this.stop();
	}

	#connect() {
		if (!this.#running || this.#socket !== null) return;
		this.#decoder = new JsonlDecoder({ maxBytes: MAX_LINE_BYTES });
		this.#inboundMessageIds.clear();
		let socket;
		try {
			socket = this.#socketFactory({ host: this.#host, port: this.#port });
		} catch (error) {
			this.#emitBridgeError('CONNECT_FAILED', `Could not create bridge socket: ${error.message}`, error);
			this.#scheduleReconnect();
			return;
		}
		this.#socket = socket;
		if (typeof socket.setNoDelay === 'function') socket.setNoDelay(true);
		socket.on('connect', () => this.#onConnect(socket));
		socket.on('data', (chunk) => this.#onData(socket, chunk));
		socket.on('error', (error) => this.#emitBridgeError('BRIDGE_IO', `Minecraft bridge socket failed: ${error.message}`, error));
		socket.on('close', () => this.#onClose(socket));
	}

	#onConnect(socket) {
		if (socket !== this.#socket || !this.#running) return;
		this.#helloMessageId = this.#messageIds.next();
		this.#write({ protocolVersion: PROTOCOL_VERSION, agentId: this.#agentId, type: 'hello', messageId: this.#helloMessageId });
	}

	#onData(socket, chunk) {
		if (socket !== this.#socket || !this.#running) return;
		try {
			for (const message of this.#decoder.push(chunk)) this.#acceptInbound(message);
		} catch (error) {
			this.#emitProtocolError(error);
			if (!socket.destroyed) socket.destroy();
		}
	}

	#onClose(socket) {
		if (socket !== this.#socket) return;
		this.#socket = null;
		const wasReady = this.#ready;
		this.#ready = false;
		this.#helloMessageId = null;
		if (wasReady) this.emit('disconnect');
		this.#scheduleReconnect();
	}

	#acceptInbound(message) {
		const envelope = basicEnvelope(message);
		if (envelope.agentId !== this.#agentId) throw new BridgeProtocolError('AGENT_ID_MISMATCH', `Bridge message was for '${envelope.agentId}', expected '${this.#agentId}'`);
		if (this.#inboundMessageIds.has(envelope.messageId)) throw new BridgeProtocolError('DUPLICATE_MESSAGE_ID', `Duplicate bridge messageId '${envelope.messageId}'`);
		rememberBounded(this.#inboundMessageIds, envelope.messageId, MAX_INBOUND_MESSAGE_IDS);

		if (!this.#ready) {
			const acknowledgement = validateEnvelope(message);
			if (acknowledgement.type !== 'hello_ack' || acknowledgement.replyTo !== this.#helloMessageId) throw new BridgeProtocolError('AUTHENTICATION_REQUIRED', 'First bridge response must acknowledge this session hello');
			this.#ready = true;
			this.#reconnectDelayMs = this.#initialReconnectDelayMs;
			this.emit('ready');
			return;
		}

		let validated;
		switch (envelope.type) {
			case 'observation':
				validated = validateObservation(message);
				break;
			case 'action_progress':
				validated = validateActionProgress(message);
				break;
			case 'action_result':
				validated = validateActionResult(message);
				if (this.#terminalCommandIds.has(validated.commandId)) {
					this.#emitProtocolError(new BridgeProtocolError('DUPLICATE_TERMINAL_RESULT', `Duplicate terminal result for '${validated.commandId}'`));
					return;
				}
				rememberBounded(this.#terminalCommandIds, validated.commandId, MAX_TERMINAL_COMMAND_IDS);
				break;
			case 'goal_event':
				validated = validateGoalEvent(message);
				break;
			case 'significant_event':
				validated = validateSignificantEvent(message);
				break;
			case 'error':
				validated = validateBridgeError(message);
				break;
			default:
				throw new BridgeProtocolError('UNKNOWN_MESSAGE_TYPE', `Unknown bridge message type '${envelope.type}'`);
		}
		this.emit('message', validated);
		this.emit(validated.type, validated);
	}

	#send(type, payload = {}) {
		this.#write({ protocolVersion: PROTOCOL_VERSION, agentId: this.#agentId, type, messageId: this.#messageIds.next(), ...payload });
	}

	#write(message) {
		if (this.#socket === null || this.#socket.destroyed) throw new BridgeProtocolError('BRIDGE_DISCONNECTED', 'Minecraft bridge is disconnected');
		this.#socket.write(encodeJsonLine(message));
	}

	#requireReady() {
		if (!this.#ready) throw new BridgeProtocolError('BRIDGE_NOT_READY', 'Minecraft bridge has not authenticated');
	}

	#scheduleReconnect() {
		if (!this.#running || this.#reconnectHandle !== null) return;
		const delay = this.#reconnectDelayMs;
		this.#reconnectDelayMs = Math.min(this.#maxReconnectDelayMs, this.#reconnectDelayMs * 2);
		this.#reconnectHandle = this.#schedule(() => {
			this.#reconnectHandle = null;
			this.#connect();
		}, delay);
		this.emit('reconnecting', { delayMs: delay });
	}

	#emitProtocolError(error) {
		const normalized = error instanceof BridgeProtocolError
			? error
			: new BridgeProtocolError(error.code ?? 'INVALID_MESSAGE', error.message, { cause: error });
		this.emit('protocolError', normalized);
	}

	#emitBridgeError(code, message, cause) {
		this.emit('bridgeError', new BridgeProtocolError(code, message, { cause }));
	}
}

function basicEnvelope(message) {
	if (message === null || typeof message !== 'object' || Array.isArray(message)) throw new BridgeProtocolError('INVALID_FIELD', 'Bridge message must be an object');
	if (!Number.isSafeInteger(message.protocolVersion) || message.protocolVersion !== PROTOCOL_VERSION) throw new BridgeProtocolError('UNSUPPORTED_VERSION', `Unsupported protocolVersion ${String(message.protocolVersion)}; expected ${PROTOCOL_VERSION}`);
	return {
		agentId: requireBoundedText(message.agentId, 'agentId'),
		type: requireBoundedText(message.type, 'type'),
		messageId: requireBoundedText(message.messageId, 'messageId'),
	};
}

export function validateGoalEvent(message) {
	const value = validateEnvelope(message, ['operation', 'goal']);
	if (!['set', 'stop'].includes(value.operation)) throw new BridgeProtocolError('INVALID_FIELD', "goal operation must be 'set' or 'stop'");
	if (value.operation === 'set') requireText(value.goal, 'goal', MAX_GOAL_LENGTH);
	else if (value.goal !== '') throw new BridgeProtocolError('INVALID_FIELD', 'stop goal_event must carry an empty goal');
	return value;
}

function validateSignificantEvent(message) {
	const value = validateEnvelope(message, ['event', 'details', 'observedAtEpochMs']);
	requireText(value.event, 'event', MAX_REASON_CODE_LENGTH);
	requireText(value.details, 'details', MAX_RESULT_MESSAGE_LENGTH, true);
	if (!Number.isSafeInteger(value.observedAtEpochMs) || value.observedAtEpochMs <= 0) throw new BridgeProtocolError('INVALID_FIELD', 'observedAtEpochMs must be positive');
	return value;
}

function validateBridgeError(message) {
	const value = validateEnvelope(message, ['code', 'message']);
	requireText(value.code, 'code', MAX_REASON_CODE_LENGTH);
	requireText(value.message, 'message', MAX_RESULT_MESSAGE_LENGTH, true);
	return value;
}

function requireBoundedText(value, field) {
	return requireText(value, field, MAX_COMMAND_ID_LENGTH);
}

function requireText(value, field, maximum, emptyAllowed = false) {
	if (typeof value !== 'string' || (!emptyAllowed && value.trim().length === 0) || value.length > maximum) throw new BridgeProtocolError('INVALID_FIELD', `${field} must be ${emptyAllowed ? '' : 'nonblank and '}at most ${maximum} characters`);
	return value;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function rememberBounded(set, value, maximum) {
	set.add(value);
	if (set.size > maximum) set.delete(set.values().next().value);
}
