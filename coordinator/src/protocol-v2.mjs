import { timingSafeEqual } from 'node:crypto';
import { EventEmitter } from 'node:events';
import net from 'node:net';

import {
	DEFAULT_AGENT_MESSAGE_QUEUE_CAP,
	DEFAULT_CONNECTION_QUEUE_CAP,
	BLOCK_FACES,
	LOOPBACK_HOST,
	MAX_BRIDGE_SECRET_LENGTH,
	MAX_BLOCKS,
	MAX_CHAT_LENGTH,
	MAX_CONVERSATION_LENGTH,
	MAX_COMMAND_ID_LENGTH,
	MAX_EFFECTS,
	MAX_ENTITIES,
	MAX_GOAL_LENGTH,
	MAX_IDENTIFIER_LENGTH,
	MAX_INVENTORY_SUMMARIES,
	MAX_OBSERVATION_TAGS,
	MAX_TAG_COUNT_ENTRIES,
	MAX_LINE_BYTES,
	MAX_REASON_CODE_LENGTH,
	MAX_RESULT_MESSAGE_LENGTH,
	MAX_SUMMARY_LENGTH,
	MULTIPLEXED_PROTOCOL_VERSION,
} from './constants.mjs';
import { encodeJsonLine, JsonlDecoder } from './jsonl.mjs';
import { MessageIdGenerator } from './message-id.mjs';
import { ValidationError, validateAction, validateActionCommandPayload } from './schema.mjs';
import { bindCompletionContract, parseCompletionContract } from './goal-contract.mjs';

const MAX_COORDINATOR_CIRCUITS = 32;

export const COORDINATOR_TO_SERVER_TYPES = Object.freeze([
	'hello',
	'catalog_snapshot',
	'coordinator_status',
	'agent_ready',
	'planning_state',
	'goal_completed',
	'conversation_wake_ack',
	'action_command',
	'action_cancel',
	'agent_error',
	'heartbeat',
]);

export const SERVER_TO_COORDINATOR_TYPES = Object.freeze([
	'hello_ack',
	'catalog_request',
	'agent_registered',
	'agent_removed',
	'goal_control',
	'observation',
	'conversation_event',
	'conversation_wake',
	'action_progress',
	'action_result',
	'goal_completion_result',
	'heartbeat',
	'shutdown',
]);

const COORDINATOR_TYPES = new Set(COORDINATOR_TO_SERVER_TYPES);
const SERVER_TYPES = new Set(SERVER_TO_COORDINATOR_TYPES);
const REVISION_GUARDED_INBOUND_TYPES = new Set(['observation', 'conversation_event', 'action_progress', 'action_result', 'goal_completion_result']);
const REVISION_GUARDED_OUTBOUND_TYPES = new Set(['agent_ready', 'planning_state', 'goal_completed', 'conversation_wake_ack', 'action_command', 'action_cancel', 'agent_error']);
const TERMINAL_ACTION_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const MAX_TRACKED_MESSAGE_IDS = 4_096;
const MAX_TRACKED_TERMINAL_ACTION_IDS = 4_096;
const PENDING_SERVER_INSTANCE_ID = 'pending';
const MAX_CATALOG_MODELS = 512;
const MAX_MODEL_CAPABILITIES = 32;
const MAX_REGISTRY_SNAPSHOT_AGENTS = 1_024;
const MAX_NEARBY_TRANSACTION_TARGETS = 16;
const MAX_COMPLETION_FACTS = 16;
const MAX_CHANGED_FACTS = 256;
const FACTUAL_PLAYER_FIELDS = new Set([
	'health', 'maxHealth', 'armor', 'foodLevel', 'saturation', 'gameMode', 'onGround', 'inWater',
	'onFire', 'air', 'maxAir', 'suffocating', 'fallDistance', 'lastAttacker', 'effects',
]);
const FACTUAL_TOP_LEVEL_PATHS = new Set([
	'ready', 'status', 'position', 'velocity', 'view', 'inventory', 'entities', 'blocks', 'nearbyContainers', 'world', 'currentAction', 'lastResult',
]);

export class ProtocolV2Error extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'ProtocolV2Error';
		this.code = code;
	}
}

export function createProtocolV2Envelope({ serverInstanceId, agentId, type, messageId, payload }) {
	return validateProtocolV2Envelope({
		protocolVersion: MULTIPLEXED_PROTOCOL_VERSION,
		serverInstanceId,
		agentId,
		type,
		messageId,
		payload,
	});
}

export function validateProtocolV2Envelope(value, { direction } = {}) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_ENVELOPE', 'Protocol v2 envelope must be an object');
	const keys = Object.keys(value);
	const expectedKeys = ['protocolVersion', 'serverInstanceId', 'agentId', 'type', 'messageId', 'payload'];
	for (const key of expectedKeys) if (!Object.hasOwn(value, key)) throw new ProtocolV2Error('MISSING_FIELD', `Protocol v2 envelope field '${key}' is required`);
	for (const key of keys) if (!expectedKeys.includes(key)) throw new ProtocolV2Error('INVALID_FIELD', `Unknown protocol v2 envelope field '${key}'`);
	if (value.protocolVersion !== MULTIPLEXED_PROTOCOL_VERSION) throw new ProtocolV2Error('UNSUPPORTED_VERSION', `Expected protocol version ${MULTIPLEXED_PROTOCOL_VERSION}`);
	const serverInstanceId = requireIdentifier(value.serverInstanceId, 'serverInstanceId');
	const agentId = requireIdentifier(value.agentId, 'agentId');
	const type = requireIdentifier(value.type, 'type');
	const messageId = requireText(value.messageId, 'messageId', MAX_COMMAND_ID_LENGTH);
	if (!isPlainObject(value.payload)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Protocol v2 payload must be an object');
	if (direction === 'coordinator_to_server' && !COORDINATOR_TYPES.has(type)) throw new ProtocolV2Error('INVALID_MESSAGE_TYPE', `Message type '${type}' is not coordinator-to-server`);
	if (direction === 'server_to_coordinator' && !SERVER_TYPES.has(type)) throw new ProtocolV2Error('INVALID_MESSAGE_TYPE', `Message type '${type}' is not server-to-coordinator`);
	if ((type === 'hello' || type === 'hello_ack' || type === 'catalog_request' || type === 'catalog_snapshot' || type === 'coordinator_status' || type === 'heartbeat' || type === 'shutdown') && agentId !== 'server') {
		throw new ProtocolV2Error('INVALID_AGENT_SCOPE', `Message type '${type}' must use agentId 'server'`);
	}
	const payload = validateProtocolV2Payload(type, value.payload);
	if (type === 'conversation_event' && payload.recipientId !== agentId) {
		throw new ProtocolV2Error('INVALID_AGENT_SCOPE', 'conversation_event recipientId must match the envelope agentId');
	}
	if (type === 'conversation_wake' && payload.event.recipientId !== agentId) {
		throw new ProtocolV2Error('INVALID_AGENT_SCOPE', 'conversation_wake event recipientId must match the envelope agentId');
	}
	return { protocolVersion: MULTIPLEXED_PROTOCOL_VERSION, serverInstanceId, agentId, type, messageId, payload };
}

export function validateProtocolV2Payload(type, value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${type} payload must be an object`);
	switch (type) {
		case 'hello':
			exactKeys(value, ['secret'], ['secret'], type);
			return { secret: boundedText(value.secret, 'secret', MAX_BRIDGE_SECRET_LENGTH, 32) };
		case 'hello_ack':
			exactKeys(value, ['replyTo', 'authenticated', 'registry'], ['replyTo', 'authenticated', 'registry'], type);
			if (value.authenticated !== true) throw new ProtocolV2Error('INVALID_PAYLOAD', 'hello_ack authenticated must be true');
			return {
				replyTo: boundedText(value.replyTo, 'replyTo', MAX_COMMAND_ID_LENGTH),
				authenticated: true,
				registry: boundedArray(value.registry, 'registry', MAX_REGISTRY_SNAPSHOT_AGENTS).map((entry) => normalizeRegisteredAgent(entry, 'registry entry')),
			};
		case 'catalog_request':
			exactKeys(value, [], [], type);
			return {};
		case 'catalog_snapshot':
			return normalizeCatalogSnapshot(value);
		case 'coordinator_status':
			return normalizeCoordinatorStatus(value);
		case 'agent_registered':
			return normalizeRegisteredAgent(value, type);
		case 'agent_removed':
			exactKeys(value, ['goalRevision'], ['goalRevision'], type);
			return { goalRevision: revision(value.goalRevision, 'goalRevision') };
		case 'goal_control':
			return normalizeGoalControl(value);
		case 'observation':
			return normalizeObservation(value);
		case 'conversation_event':
			return normalizeConversationEvent(value);
		case 'conversation_wake':
			return normalizeConversationWake(value);
		case 'action_progress':
			return normalizeActionProgress(value);
		case 'action_result':
			return normalizeActionResult(value);
		case 'agent_ready':
			exactKeys(value, ['goalRevision', 'reconciled'], ['goalRevision'], type);
			return value.reconciled === undefined
				? { goalRevision: revision(value.goalRevision, 'goalRevision') }
				: { goalRevision: revision(value.goalRevision, 'goalRevision'), reconciled: boolean(value.reconciled, 'reconciled') };
		case 'planning_state':
			exactKeys(value, ['goalRevision', 'state'], ['goalRevision', 'state'], type);
			return { goalRevision: revision(value.goalRevision, 'goalRevision'), state: boundedText(value.state, 'state', MAX_REASON_CODE_LENGTH) };
		case 'goal_completed':
			return normalizeGoalCompletionRequest(value);
		case 'goal_completion_result':
			exactKeys(value, ['goalRevision', 'traceId', 'contractHash', 'verified', 'reasonCode', 'facts'], ['goalRevision', 'traceId', 'contractHash', 'verified', 'reasonCode', 'facts'], type);
			return {
				goalRevision: revision(value.goalRevision, 'goalRevision'),
				traceId: requireTraceId(value.traceId),
				contractHash: boundedText(value.contractHash, 'contractHash', 80),
				verified: boolean(value.verified, 'verified'),
				reasonCode: boundedText(value.reasonCode, 'reasonCode', MAX_REASON_CODE_LENGTH),
				facts: boundedArray(value.facts, 'facts', MAX_COMPLETION_FACTS).map((fact, index) => normalizeCompletionFact(fact, index)),
			};
		case 'conversation_wake_ack':
			exactKeys(value, ['transactionId', 'goalRevision'], ['transactionId', 'goalRevision'], type);
			return {
				transactionId: requireIdentifier(value.transactionId, 'transactionId'),
				goalRevision: revision(value.goalRevision, 'goalRevision'),
			};
		case 'action_command':
			return normalizeActionCommand(value);
		case 'action_cancel':
			exactKeys(value, ['goalRevision', 'actionId'], ['goalRevision', 'actionId'], type);
			return {
				goalRevision: revision(value.goalRevision, 'goalRevision'),
				actionId: requireIdentifier(value.actionId, 'actionId'),
			};
		case 'agent_error':
			exactKeys(value, ['goalRevision', 'code', 'message'], ['goalRevision', 'code', 'message'], type);
			return {
				goalRevision: revision(value.goalRevision, 'goalRevision'),
				code: boundedText(value.code, 'code', MAX_REASON_CODE_LENGTH),
				message: boundedText(value.message, 'message', MAX_RESULT_MESSAGE_LENGTH),
			};
		case 'heartbeat':
			exactKeys(value, [], [], type);
			return {};
		case 'shutdown':
			exactKeys(value, ['reason'], ['reason'], type);
			return { reason: boundedText(value.reason, 'reason', MAX_REASON_CODE_LENGTH) };
		default:
			throw new ProtocolV2Error('INVALID_MESSAGE_TYPE', `Unsupported protocol v2 payload type '${String(type)}'`);
	}
}

function normalizeCompletionFact(value, index) {
	const field = `facts[${index}]`;
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	exactKeys(value, ['predicateIndex', 'type', 'satisfied', 'observedValue'], ['predicateIndex', 'type', 'satisfied', 'observedValue'], field);
	const predicateIndex = nonnegativeInteger(value.predicateIndex, `${field}.predicateIndex`);
	if (predicateIndex >= MAX_COMPLETION_FACTS) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.predicateIndex must be less than ${MAX_COMPLETION_FACTS}`);
	return {
		predicateIndex,
		type: boundedText(value.type, `${field}.type`, MAX_REASON_CODE_LENGTH),
		satisfied: boolean(value.satisfied, `${field}.satisfied`),
		observedValue: boundedText(value.observedValue, `${field}.observedValue`, 128, 0),
	};
}

export class MultiplexedServerBridge extends EventEmitter {
	#host;
	#port;
	#secret;
	#expectedServerInstanceId;
	#serverInstanceId;
	#socketFactory;
	#schedule;
	#cancelSchedule;
	#currentRevision;
	#initialReconnectDelayMs;
	#maxReconnectDelayMs;
	#reconnectDelayMs;
	#connectionQueueCap;
	#agentQueueCap;
	#messageIds;
	#audit;
	#socket = null;
	#decoder = null;
	#running = false;
	#ready = false;
	#helloMessageId = null;
	#reconnectHandle = null;
	#outboundQueue = [];
	#queuedByAgent = new Map();
	#writeBlocked = false;
	#inboundMessageIds = new Set();
	#terminalActionIds = new Set();
	#knownAgentIds = new Set();
	#observedRevisions = new Map();

	constructor(config, { audit = null, ...dependencies } = {}) {
		super();
		if (!isPlainObject(config)) throw new TypeError('multiplexed bridge config must be an object');
		this.#host = config.host ?? LOOPBACK_HOST;
		if (this.#host !== LOOPBACK_HOST) throw new ProtocolV2Error('LOOPBACK_REQUIRED', `Multiplexed bridge host must be ${LOOPBACK_HOST}`);
		this.#port = requirePort(config.port);
		this.#secret = requireSecret(config.secret);
		this.#expectedServerInstanceId = config.serverInstanceId === undefined ? null : requireIdentifier(config.serverInstanceId, 'serverInstanceId');
		this.#serverInstanceId = this.#expectedServerInstanceId ?? PENDING_SERVER_INSTANCE_ID;
		this.#socketFactory = dependencies.socketFactory ?? (() => net.createConnection({ host: this.#host, port: this.#port }));
		this.#schedule = dependencies.schedule ?? ((callback, delay) => setTimeout(callback, delay));
		this.#cancelSchedule = dependencies.cancelSchedule ?? clearTimeout;
		this.#currentRevision = dependencies.currentRevision ?? (() => null);
		this.#initialReconnectDelayMs = positiveInteger(config.reconnectDelayMs ?? 500, 'reconnectDelayMs');
		this.#maxReconnectDelayMs = positiveInteger(config.maxReconnectDelayMs ?? 5_000, 'maxReconnectDelayMs');
		if (this.#maxReconnectDelayMs < this.#initialReconnectDelayMs) throw new TypeError('maxReconnectDelayMs must be at least reconnectDelayMs');
		this.#reconnectDelayMs = this.#initialReconnectDelayMs;
		this.#connectionQueueCap = positiveInteger(config.connectionQueueCap ?? DEFAULT_CONNECTION_QUEUE_CAP, 'connectionQueueCap');
		this.#agentQueueCap = positiveInteger(config.agentQueueCap ?? DEFAULT_AGENT_MESSAGE_QUEUE_CAP, 'agentQueueCap');
		this.#messageIds = dependencies.messageIds ?? new MessageIdGenerator('coordinator-v2');
		if (audit !== null && typeof audit !== 'function') throw new TypeError('audit must be a function or null');
		this.#audit = audit;
	}

	get ready() { return this.#ready; }
	get serverInstanceId() { return this.#ready ? this.#serverInstanceId : null; }
	get knownAgentIds() { return [...this.#knownAgentIds]; }

	start() {
		if (this.#running) return;
		this.#running = true;
		this.#connect();
	}

	stop() {
		if (!this.#running) return;
		this.#running = false;
		this.#ready = false;
		if (this.#reconnectHandle !== null) {
			this.#cancelSchedule(this.#reconnectHandle);
			this.#reconnectHandle = null;
		}
		this.#clearOutboundQueue(new ProtocolV2Error('BRIDGE_STOPPED', 'Multiplexed bridge stopped'));
		this.#socket?.destroy();
		this.#socket = null;
	}

	async send(type, agentId, payload) {
		if (!this.#ready) return Promise.reject(new ProtocolV2Error('BRIDGE_NOT_READY', 'Multiplexed bridge handshake is incomplete'));
		if (agentId !== 'server' && !this.#knownAgentIds.has(agentId)) throw new ProtocolV2Error('UNKNOWN_AGENT', `Cannot send a message for unknown agent '${agentId}'`);
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: this.#messageIds.next(),
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'coordinator_to_server' });
		this.#assertRevision(envelope, REVISION_GUARDED_OUTBOUND_TYPES);
		return this.#enqueue(envelope);
	}

	#connect() {
		if (!this.#running) return;
		const socket = this.#socketFactory();
		this.#socket = socket;
		this.#decoder = new JsonlDecoder({ maxBytes: MAX_LINE_BYTES });
		socket.setNoDelay?.(true);
		socket.on('connect', () => this.#onConnect(socket));
		socket.on('data', (chunk) => this.#onData(socket, chunk));
		socket.on('drain', () => { this.#writeBlocked = false; this.#flush(); });
		socket.on('error', (error) => this.emit('transportError', error));
		socket.on('close', () => this.#onClose(socket));
	}

	#onConnect(socket) {
		if (socket !== this.#socket || !this.#running) return;
		this.#inboundMessageIds.clear();
		this.#observedRevisions.clear();
		const messageId = this.#messageIds.next();
		this.#helloMessageId = messageId;
		const hello = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId: 'server',
			type: 'hello',
			messageId,
			payload: { secret: this.#secret },
		});
		const encoded = encodeJsonLine(hello);
		this.#invokeAudit('coordinator_to_server', { ...hello, payload: { secret: '[REDACTED]' } });
		socket.write(encoded);
	}

	#onData(socket, chunk) {
		if (socket !== this.#socket || !this.#running) return;
		let messages;
		try {
			messages = this.#decoder.push(chunk);
		} catch (error) {
			this.#fail(error);
			return;
		}
		for (const value of messages) {
			try {
				this.#accept(value);
			} catch (error) {
				this.#fail(withInboundEnvelopeContext(error, value));
				return;
			}
		}
	}

	#accept(value) {
		const envelope = validateProtocolV2Envelope(value, { direction: 'server_to_coordinator' });
		this.#invokeAudit('server_to_coordinator', envelope);
		if (this.#inboundMessageIds.has(envelope.messageId)) throw new ProtocolV2Error('DUPLICATE_MESSAGE', `Duplicate message ID '${envelope.messageId}'`);
		rememberBounded(this.#inboundMessageIds, envelope.messageId, MAX_TRACKED_MESSAGE_IDS);
		if (!this.#ready) {
			this.#acceptHelloAck(envelope);
			return;
		}
		if (envelope.serverInstanceId !== this.#serverInstanceId) throw new ProtocolV2Error('SERVER_INSTANCE_MISMATCH', 'Server instance changed during an authenticated session');
		this.#trackInboundRevision(envelope);
		this.#assertRevision(envelope, REVISION_GUARDED_INBOUND_TYPES);
		this.#trackTerminalResult(envelope);
		if (envelope.type === 'agent_registered') this.#knownAgentIds.add(envelope.agentId);
		if (envelope.agentId !== 'server' && !this.#knownAgentIds.has(envelope.agentId) && envelope.type !== 'agent_registered') {
			throw new ProtocolV2Error('UNKNOWN_AGENT', `Message references unknown agent '${envelope.agentId}'`);
		}
		if (envelope.type === 'agent_removed') {
			this.#knownAgentIds.delete(envelope.agentId);
			this.#observedRevisions.delete(envelope.agentId);
		}
		this.emit(envelope.type, envelope);
		this.emit('message', envelope);
	}

	#acceptHelloAck(envelope) {
		if (envelope.type !== 'hello_ack' || envelope.agentId !== 'server') throw new ProtocolV2Error('HANDSHAKE_REQUIRED', 'hello_ack must be the first server message');
		if (envelope.payload.replyTo !== this.#helloMessageId) throw new ProtocolV2Error('HANDSHAKE_MISMATCH', 'hello_ack does not match the active hello');
		if (envelope.payload.authenticated !== true) throw new ProtocolV2Error('AUTHENTICATION_FAILED', 'Server rejected bridge authentication');
		if (this.#expectedServerInstanceId !== null && envelope.serverInstanceId !== this.#expectedServerInstanceId) throw new ProtocolV2Error('SERVER_INSTANCE_MISMATCH', 'Connected server instance does not match configuration');
		const registry = envelope.payload.registry ?? [];
		if (!Array.isArray(registry)) throw new ProtocolV2Error('INVALID_REGISTRY_SNAPSHOT', 'hello_ack registry must be an array');
		this.#serverInstanceId = envelope.serverInstanceId;
		this.#knownAgentIds = new Set(registry.map((entry) => requireIdentifier(entry?.agentId, 'registry agentId')));
		this.#observedRevisions = new Map(registry.map((entry) => [
			requireIdentifier(entry?.agentId, 'registry agentId'),
			revision(entry?.goalRevision, 'registry goalRevision'),
		]));
		if (this.#knownAgentIds.size !== registry.length) throw new ProtocolV2Error('DUPLICATE_AGENT', 'hello_ack registry contains duplicate agents');
		this.#ready = true;
		this.#reconnectDelayMs = this.#initialReconnectDelayMs;
		this.emit('ready', { serverInstanceId: this.#serverInstanceId, registry: structuredClone(registry) });
	}

	#assertRevision(envelope, guardedTypes) {
		if (!guardedTypes.has(envelope.type)) return;
		const revision = envelope.payload.goalRevision;
		if (!Number.isSafeInteger(revision) || revision < 0) throw new ProtocolV2Error('INVALID_GOAL_REVISION', `${envelope.type} requires a nonnegative goalRevision`);
		const current = this.#trackedRevision(envelope.agentId);
		if (current !== null && current !== undefined && revision !== current) throw new ProtocolV2Error('STALE_GOAL_REVISION', `Message revision ${revision} does not match current revision ${current}`);
	}

	#trackInboundRevision(envelope) {
		if (envelope.type === 'agent_registered') {
			this.#observedRevisions.set(envelope.agentId, envelope.payload.goalRevision);
			return;
		}
		if (envelope.type !== 'goal_control' && envelope.type !== 'conversation_wake') return;
		const control = envelope.type === 'conversation_wake' ? envelope.payload.control : envelope.payload;
		const next = control.goalRevision;
		const current = this.#trackedRevision(envelope.agentId);
		if (current !== null && current !== undefined) {
			if (control.operation === 'queue' && next !== current) {
				throw new ProtocolV2Error('STALE_GOAL_REVISION', `Queued goal revision ${next} does not match current revision ${current}`);
			}
			if (control.operation !== 'queue' && next < current) {
				throw new ProtocolV2Error('STALE_GOAL_REVISION', `Goal revision ${next} moved backwards from ${current}`);
			}
		}
		this.#observedRevisions.set(envelope.agentId, next);
		if (control.operation !== 'queue' && (current === null || current === undefined || next > current)) {
			this.#dropSupersededQueuedMessages(envelope.agentId, next);
		}
	}

	#dropSupersededQueuedMessages(agentId, goalRevision) {
		const retained = [];
		for (const entry of this.#outboundQueue) {
			if (entry.envelope.agentId !== agentId
					|| !REVISION_GUARDED_OUTBOUND_TYPES.has(entry.envelope.type)
					|| entry.envelope.payload.goalRevision === goalRevision) {
				retained.push(entry);
				continue;
			}
			this.#decrementQueued(agentId);
			entry.reject(new ProtocolV2Error(
				'STALE_GOAL_REVISION',
				`Queued ${entry.envelope.type} revision ${entry.envelope.payload.goalRevision} was superseded by lifecycle revision ${goalRevision}`,
			));
		}
		this.#outboundQueue = retained;
	}

	#trackedRevision(agentId) {
		const observed = this.#observedRevisions.get(agentId);
		const applied = this.#currentRevision(agentId);
		if (observed === undefined || observed === null) return applied;
		if (applied === undefined || applied === null) return observed;
		return Math.max(observed, applied);
	}

	#trackTerminalResult(envelope) {
		if (envelope.type !== 'action_result' || !TERMINAL_ACTION_STATES.has(envelope.payload.state)) return;
		const actionId = requireIdentifier(envelope.payload.actionId ?? envelope.payload.commandId, 'actionId');
		const key = `${envelope.agentId}:${actionId}`;
		if (this.#terminalActionIds.has(key)) throw new ProtocolV2Error('DUPLICATE_TERMINAL_RESULT', `Duplicate terminal result for action '${actionId}'`);
		rememberBounded(this.#terminalActionIds, key, MAX_TRACKED_TERMINAL_ACTION_IDS);
	}

	#enqueue(envelope) {
		if (this.#outboundQueue.length >= this.#connectionQueueCap) return Promise.reject(new ProtocolV2Error('CONNECTION_BACKPRESSURE', 'Connection outbound queue is full'));
		const agentCount = this.#queuedByAgent.get(envelope.agentId) ?? 0;
		if (agentCount >= this.#agentQueueCap) return Promise.reject(new ProtocolV2Error('AGENT_BACKPRESSURE', `Outbound queue for agent '${envelope.agentId}' is full`));
		return new Promise((resolve, reject) => {
			this.#outboundQueue.push({ envelope, encoded: encodeJsonLine(envelope), resolve, reject });
			this.#queuedByAgent.set(envelope.agentId, agentCount + 1);
			this.#flush();
		});
	}

	#flush() {
		const socket = this.#socket;
		if (!this.#ready || this.#writeBlocked || socket === null || socket.destroyed) return;
		while (this.#outboundQueue.length > 0) {
			const entry = this.#outboundQueue.shift();
			this.#decrementQueued(entry.envelope.agentId);
			let writable;
			try {
				this.#invokeAudit('coordinator_to_server', entry.envelope);
				writable = socket.write(entry.encoded);
			} catch (error) { entry.reject(error); this.#fail(error); return; }
			entry.resolve(entry.envelope.messageId);
			if (!writable) {
				this.#writeBlocked = true;
				return;
			}
		}
	}

	#invokeAudit(direction, envelope) {
		if (this.#audit === null) return;
		let result;
		try { result = this.#audit(direction, structuredClone(envelope)); }
		catch (error) { this.#reportAuditError(error); return; }
		if (result !== null && result !== undefined && typeof result.then === 'function') {
			Promise.resolve(result).catch((error) => this.#reportAuditError(error));
		}
	}

	#reportAuditError(error) {
		try { this.emit('auditError', error); } catch {}
	}

	#decrementQueued(agentId) {
		const count = this.#queuedByAgent.get(agentId) ?? 0;
		if (count <= 1) this.#queuedByAgent.delete(agentId);
		else this.#queuedByAgent.set(agentId, count - 1);
	}

	#clearOutboundQueue(error) {
		for (const entry of this.#outboundQueue.splice(0)) entry.reject(error);
		this.#queuedByAgent.clear();
	}

	#fail(error) {
		this.emit('protocolError', error instanceof Error ? error : new ProtocolV2Error('PROTOCOL_ERROR', String(error)));
		this.#socket?.destroy();
	}

	#onClose(socket) {
		if (socket !== this.#socket) return;
		const wasReady = this.#ready;
		this.#socket = null;
		this.#ready = false;
		this.#writeBlocked = false;
		this.#helloMessageId = null;
		this.#knownAgentIds.clear();
		this.#clearOutboundQueue(new ProtocolV2Error('BRIDGE_DISCONNECTED', 'Multiplexed bridge disconnected'));
		if (wasReady) this.emit('disconnected');
		if (!this.#running || this.#reconnectHandle !== null) return;
		const delay = this.#reconnectDelayMs;
		this.#reconnectDelayMs = Math.min(this.#maxReconnectDelayMs, this.#reconnectDelayMs * 2);
		this.#reconnectHandle = this.#schedule(() => {
			this.#reconnectHandle = null;
			this.#connect();
		}, delay);
	}
}

export function secretsEqual(left, right) {
	if (typeof left !== 'string' || typeof right !== 'string') return false;
	const leftBytes = Buffer.from(left, 'utf8');
	const rightBytes = Buffer.from(right, 'utf8');
	return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function normalizeCoordinatorStatus(value) {
	const requiredKeys = ['reconciled', 'profiles', 'supportedProfileCount', 'rosterReadyCount', 'rosterCount', 'scheduler', 'circuits'];
	const allowedKeys = [...requiredKeys, 'latencies'];
	exactKeys(value, allowedKeys, requiredKeys, 'coordinator_status');
	const profiles = boundedArray(value.profiles, 'coordinator_status.profiles', 16).map((profile, index) => {
		const field = `coordinator_status.profiles[${index}]`;
		exactKeys(profile, ['agentId', 'provider', 'model', 'reasoningEffort'], ['agentId', 'provider', 'model', 'reasoningEffort'], field);
		return {
			agentId: requireIdentifier(profile.agentId, `${field}.agentId`),
			provider: requireIdentifier(profile.provider, `${field}.provider`),
			model: boundedText(profile.model, `${field}.model`, MAX_IDENTIFIER_LENGTH),
			reasoningEffort: requireIdentifier(profile.reasoningEffort, `${field}.reasoningEffort`),
		};
	});
	const supportedProfileCount = nonnegativeInteger(value.supportedProfileCount, 'coordinator_status.supportedProfileCount');
	const rosterReadyCount = nonnegativeInteger(value.rosterReadyCount, 'coordinator_status.rosterReadyCount');
	const rosterCount = nonnegativeInteger(value.rosterCount, 'coordinator_status.rosterCount');
	if (supportedProfileCount !== profiles.length || rosterReadyCount > rosterCount || rosterCount > 16) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status profile and roster counts are inconsistent');
	}
	if (new Set(profiles.map((profile) => profile.agentId)).size !== profiles.length) throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status profile identities must be unique');
	const schedulerField = 'coordinator_status.scheduler';
	const schedulerOptionalKeys = [
		'mode', 'configuredTarget', 'target', 'minConcurrency', 'maxConcurrency', 'urgentReserve',
		'ordinaryActiveLimit', 'activeOrdinary', 'activeUrgent', 'pendingOrdinary', 'pendingUrgent',
		'growthCount', 'backoffCount', 'lastChangeReason', 'healthyCompletions',
		'ordinaryReservationRejections', 'urgentReservationRejections',
	];
	exactKeys(value.scheduler, ['active', 'pending', 'maxConcurrent', 'maxPending', 'warning', ...schedulerOptionalKeys], ['active', 'pending', 'maxConcurrent', 'maxPending', 'warning'], schedulerField);
	const scheduler = {
		active: nonnegativeInteger(value.scheduler.active, `${schedulerField}.active`),
		pending: nonnegativeInteger(value.scheduler.pending, `${schedulerField}.pending`),
		maxConcurrent: nonnegativeInteger(value.scheduler.maxConcurrent, `${schedulerField}.maxConcurrent`),
		maxPending: nonnegativeInteger(value.scheduler.maxPending, `${schedulerField}.maxPending`),
		warning: boolean(value.scheduler.warning, `${schedulerField}.warning`),
	};
	const schedulerTarget = value.scheduler.target === undefined ? scheduler.maxConcurrent : nonnegativeInteger(value.scheduler.target, `${schedulerField}.target`);
	const schedulerHardConcurrentLimit = value.scheduler.mode === 'adaptive' && value.scheduler.maxConcurrency !== undefined
		? nonnegativeInteger(value.scheduler.maxConcurrency, `${schedulerField}.maxConcurrency`)
		: scheduler.maxConcurrent;
	if (scheduler.active > schedulerHardConcurrentLimit || scheduler.pending > scheduler.maxPending
		|| scheduler.active + scheduler.pending > scheduler.maxConcurrent + scheduler.maxPending) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status scheduler counts exceed capacity');
	}
	if (scheduler.maxConcurrent < 1 || scheduler.maxConcurrent + scheduler.maxPending > 16) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status scheduler capacity must be in [1, 16]');
	}
	if (value.scheduler.mode !== undefined && !['fixed', 'adaptive'].includes(value.scheduler.mode)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.mode is invalid`);
	if (value.scheduler.mode !== undefined) scheduler.mode = value.scheduler.mode;
	for (const field of ['configuredTarget', 'minConcurrency', 'maxConcurrency', 'urgentReserve', 'ordinaryActiveLimit', 'activeOrdinary', 'activeUrgent', 'pendingOrdinary', 'pendingUrgent', 'growthCount', 'backoffCount', 'healthyCompletions', 'ordinaryReservationRejections', 'urgentReservationRejections']) {
		if (value.scheduler[field] !== undefined) scheduler[field] = nonnegativeInteger(value.scheduler[field], `${schedulerField}.${field}`);
	}
	if (value.scheduler.target !== undefined) scheduler.target = schedulerTarget;
	if (value.scheduler.lastChangeReason !== undefined) scheduler.lastChangeReason = boundedText(value.scheduler.lastChangeReason, `${schedulerField}.lastChangeReason`, MAX_REASON_CODE_LENGTH);
	if (schedulerTarget < 1 || schedulerTarget > 16) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.target must be in [1, 16]`);
	if (scheduler.mode === 'adaptive') {
		if (scheduler.minConcurrency !== undefined && scheduler.minConcurrency < 4) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.minConcurrency must be at least 4 in adaptive mode`);
		if (scheduler.maxConcurrency !== undefined && scheduler.maxConcurrency > 16) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.maxConcurrency must not exceed 16`);
		if (scheduler.minConcurrency !== undefined && schedulerTarget < scheduler.minConcurrency) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.target is below its adaptive minimum`);
		if (scheduler.maxConcurrency !== undefined && schedulerTarget > scheduler.maxConcurrency) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.target exceeds its adaptive maximum`);
	}
	if (scheduler.urgentReserve !== undefined && scheduler.maxConcurrency !== undefined && scheduler.urgentReserve > scheduler.maxConcurrency) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.urgentReserve exceeds maxConcurrency`);
	if (scheduler.ordinaryActiveLimit !== undefined && scheduler.ordinaryActiveLimit > schedulerTarget) throw new ProtocolV2Error('INVALID_PAYLOAD', `${schedulerField}.ordinaryActiveLimit exceeds target`);
	if (scheduler.activeOrdinary !== undefined && scheduler.activeUrgent !== undefined && scheduler.activeOrdinary + scheduler.activeUrgent !== scheduler.active) throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status scheduler active priority counts are inconsistent');
	if (scheduler.pendingOrdinary !== undefined && scheduler.pendingUrgent !== undefined && scheduler.pendingOrdinary + scheduler.pendingUrgent !== scheduler.pending) throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status scheduler pending priority counts are inconsistent');
	const circuits = boundedArray(value.circuits, 'coordinator_status.circuits', MAX_COORDINATOR_CIRCUITS).map((circuit, index) => {
		const field = `coordinator_status.circuits[${index}]`;
		exactKeys(circuit, ['provider', 'model', 'operation', 'count', 'p50Ms', 'p95Ms', 'failureRate', 'circuit'], ['provider', 'model', 'operation', 'count', 'p50Ms', 'p95Ms', 'failureRate', 'circuit'], field);
		const state = requireIdentifier(circuit.circuit, `${field}.circuit`);
		if (!['closed', 'open', 'half_open'].includes(state)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.circuit is invalid`);
		const failureRate = finiteNumber(circuit.failureRate, `${field}.failureRate`);
		if (failureRate < 0 || failureRate > 1) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.failureRate must be in [0, 1]`);
		return {
			provider: requireIdentifier(circuit.provider, `${field}.provider`),
			model: boundedText(circuit.model, `${field}.model`, MAX_IDENTIFIER_LENGTH),
			operation: requireIdentifier(circuit.operation, `${field}.operation`),
			count: nonnegativeInteger(circuit.count, `${field}.count`),
			p50Ms: nonnegativeInteger(circuit.p50Ms, `${field}.p50Ms`),
			p95Ms: nonnegativeInteger(circuit.p95Ms, `${field}.p95Ms`),
			failureRate,
			circuit: state,
		};
	});
	if (new Set(circuits.map((circuit) => JSON.stringify([circuit.provider, circuit.model, circuit.operation]))).size !== circuits.length) throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status circuit identities must be unique');
	const latencies = boundedArray(value.latencies ?? [], 'coordinator_status.latencies', 16).map((latency, index) => {
		const field = `coordinator_status.latencies[${index}]`;
		exactKeys(latency, ['operation', 'count', 'p50Ms', 'p95Ms'], ['operation', 'count', 'p50Ms', 'p95Ms'], field);
		return {
			operation: requireIdentifier(latency.operation, `${field}.operation`),
			count: nonnegativeInteger(latency.count, `${field}.count`),
			p50Ms: nonnegativeFiniteNumber(latency.p50Ms, `${field}.p50Ms`),
			p95Ms: nonnegativeFiniteNumber(latency.p95Ms, `${field}.p95Ms`),
		};
	});
	if (new Set(latencies.map((latency) => latency.operation)).size !== latencies.length) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'coordinator_status latency operations must be unique');
	}
	return { reconciled: boolean(value.reconciled, 'coordinator_status.reconciled'), profiles, supportedProfileCount, rosterReadyCount, rosterCount, scheduler, circuits, latencies };
}

function normalizeRegisteredAgent(value, field) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	const keys = ['schemaVersion', 'agentId', 'entityUuid', 'name', 'provider', 'model', 'reasoningEffort', 'serviceTier', 'gameMode', 'skinVariant', 'state', 'currentGoal', 'goalRevision', 'queue', 'lastSummary', 'death', 'createdAtEpochMs', 'updatedAtEpochMs', 'lastError'];
	const required = ['schemaVersion', 'agentId', 'model', 'reasoningEffort', 'skinVariant', 'state', 'goalRevision', 'queue', 'createdAtEpochMs', 'updatedAtEpochMs'];
	exactKeys(value, keys, required, field);
	const queue = boundedArray(value.queue, `${field}.queue`, 256).map((goal, index) => boundedText(goal, `${field}.queue[${index}]`, MAX_GOAL_LENGTH));
	let lastError = null;
	if (value.lastError !== undefined) {
		exactKeys(value.lastError, ['code', 'message'], ['code', 'message'], `${field}.lastError`);
		lastError = { code: boundedText(value.lastError.code, `${field}.lastError.code`, MAX_REASON_CODE_LENGTH), message: boundedText(value.lastError.message, `${field}.lastError.message`, MAX_RESULT_MESSAGE_LENGTH) };
	}
	const state = boundedText(value.state, `${field}.state`, MAX_REASON_CODE_LENGTH);
	const death = value.death === undefined ? null : normalizeDeath(value.death);
	if (state === 'DEAD' && death === null) throw new ProtocolV2Error('MISSING_FIELD', `${field} DEAD state requires death facts`);
	if (state !== 'DEAD' && death !== null) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} death facts require DEAD state`);
	return {
		schemaVersion: nonnegativeInteger(value.schemaVersion, `${field}.schemaVersion`),
		agentId: requireIdentifier(value.agentId, `${field}.agentId`),
		entityUuid: value.entityUuid === undefined ? null : requireIdentifier(value.entityUuid, `${field}.entityUuid`),
		name: value.name === undefined ? null : boundedText(value.name, `${field}.name`, MAX_IDENTIFIER_LENGTH),
		provider: normalizeProvider(value.provider ?? 'codex', `${field}.provider`),
		model: requireIdentifier(value.model, `${field}.model`),
		reasoningEffort: requireIdentifier(value.reasoningEffort, `${field}.reasoningEffort`),
		serviceTier: requireIdentifier(value.serviceTier ?? 'priority', `${field}.serviceTier`),
		gameMode: requireIdentifier(value.gameMode ?? 'survival', `${field}.gameMode`),
		skinVariant: requireIdentifier(value.skinVariant, `${field}.skinVariant`),
		state,
		currentGoal: value.currentGoal === undefined ? null : boundedText(value.currentGoal, `${field}.currentGoal`, MAX_GOAL_LENGTH),
		goalRevision: revision(value.goalRevision, `${field}.goalRevision`),
		queue,
		lastSummary: value.lastSummary === undefined ? null : boundedText(value.lastSummary, `${field}.lastSummary`, MAX_SUMMARY_LENGTH),
		death,
		respawnPolicy: {},
		createdAtEpochMs: nonnegativeInteger(value.createdAtEpochMs, `${field}.createdAtEpochMs`),
		updatedAtEpochMs: nonnegativeInteger(value.updatedAtEpochMs, `${field}.updatedAtEpochMs`),
		lastError,
	};
}

function normalizeCatalogSnapshot(value) {
	exactKeys(value, ['refreshedAtEpochMs', 'models'], ['refreshedAtEpochMs', 'models'], 'catalog_snapshot');
	return {
		refreshedAtEpochMs: nonnegativeInteger(value.refreshedAtEpochMs, 'refreshedAtEpochMs'),
		models: boundedArray(value.models, 'models', MAX_CATALOG_MODELS).map((model, index) => {
			if (!isPlainObject(model)) throw new ProtocolV2Error('INVALID_PAYLOAD', `models[${index}] must be an object`);
			exactKeys(model, ['provider', 'id', 'model', 'displayName', 'reasoningEfforts', 'serviceTiers'], ['id', 'model', 'displayName', 'reasoningEfforts', 'serviceTiers'], `models[${index}]`);
			return {
				provider: normalizeProvider(model.provider ?? 'codex', `models[${index}].provider`),
				id: requireIdentifier(model.id, `models[${index}].id`),
				model: requireIdentifier(model.model, `models[${index}].model`),
				displayName: boundedText(model.displayName, `models[${index}].displayName`, MAX_IDENTIFIER_LENGTH),
				reasoningEfforts: boundedArray(model.reasoningEfforts, `models[${index}].reasoningEfforts`, MAX_MODEL_CAPABILITIES)
					.map((effort, effortIndex) => requireIdentifier(effort, `models[${index}].reasoningEfforts[${effortIndex}]`)),
				serviceTiers: boundedArray(model.serviceTiers, `models[${index}].serviceTiers`, MAX_MODEL_CAPABILITIES)
					.map((tier, tierIndex) => requireIdentifier(tier, `models[${index}].serviceTiers[${tierIndex}]`)),
			};
		}),
	};
}

function normalizeProvider(value, field) {
	const provider = requireIdentifier(value, field);
	if (!['codex', 'gemini', 'kimi', 'cursor'].includes(provider)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be codex, gemini, kimi, or cursor`);
	return provider;
}

function normalizeGoalControl(value) {
	exactKeys(value, ['operation', 'goalRevision', 'updatedAtEpochMs', 'goal', 'death'], ['operation', 'goalRevision', 'updatedAtEpochMs'], 'goal_control');
	const operation = boundedText(value.operation, 'operation', MAX_REASON_CODE_LENGTH);
	if (!['start', 'stop', 'queue', 'steer', 'resume', 'complete', 'fail', 'disconnect', 'dead', 'respawn'].includes(operation)) throw new ProtocolV2Error('INVALID_PAYLOAD', `Unsupported goal operation '${operation}'`);
	const normalized = { operation, goalRevision: revision(value.goalRevision, 'goalRevision'), updatedAtEpochMs: nonnegativeInteger(value.updatedAtEpochMs, 'updatedAtEpochMs') };
	if (value.goal !== undefined) normalized.goal = boundedText(value.goal, 'goal', MAX_GOAL_LENGTH);
	if (value.death !== undefined) normalized.death = normalizeDeath(value.death);
	if (['start', 'steer', 'queue'].includes(operation) && normalized.goal === undefined) throw new ProtocolV2Error('MISSING_FIELD', `goal_control ${operation} requires goal`);
	if (!['start', 'steer', 'queue'].includes(operation) && normalized.goal !== undefined) throw new ProtocolV2Error('INVALID_PAYLOAD', `goal_control ${operation} must not include goal`);
	if (operation === 'dead' && normalized.death === undefined) throw new ProtocolV2Error('MISSING_FIELD', 'goal_control dead requires death facts');
	if (operation !== 'dead' && normalized.death !== undefined) throw new ProtocolV2Error('INVALID_PAYLOAD', `goal_control ${operation} must not include death facts`);
	return normalized;
}

function normalizeDeath(value) {
	exactKeys(
		value,
		['cause', 'dimensionId', 'x', 'y', 'z', 'respawnDimensionId', 'respawnX', 'respawnY', 'respawnZ', 'respawnYaw', 'respawnPitch', 'respawnForced', 'gameMode', 'diedAtEpochMs'],
		['cause', 'dimensionId', 'x', 'y', 'z', 'respawnDimensionId', 'respawnX', 'respawnY', 'respawnZ', 'respawnYaw', 'respawnPitch', 'respawnForced', 'gameMode', 'diedAtEpochMs'],
		'death',
	);
	const respawnDimensionId = nullableIdentifier(value.respawnDimensionId, 'death.respawnDimensionId');
	const respawnX = nullableFiniteNumber(value.respawnX, 'death.respawnX');
	const respawnY = nullableFiniteNumber(value.respawnY, 'death.respawnY');
	const respawnZ = nullableFiniteNumber(value.respawnZ, 'death.respawnZ');
	const respawnYaw = nullableFiniteNumber(value.respawnYaw, 'death.respawnYaw');
	const respawnPitch = nullableFiniteNumber(value.respawnPitch, 'death.respawnPitch');
	const respawnForced = nullableBoolean(value.respawnForced, 'death.respawnForced');
	const respawnFacts = [respawnDimensionId, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced];
	if (respawnFacts.some((fact) => fact === null) && respawnFacts.some((fact) => fact !== null)) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'death vanilla respawn facts must be present together or all null');
	}
	const gameMode = requireIdentifier(value.gameMode, 'death.gameMode');
	if (!['survival', 'creative', 'adventure', 'spectator'].includes(gameMode)) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'death.gameMode must be a vanilla game mode');
	}
	return Object.freeze({
		cause: boundedText(value.cause, 'death.cause', MAX_RESULT_MESSAGE_LENGTH),
		dimensionId: requireIdentifier(value.dimensionId, 'death.dimensionId'),
		x: finiteNumber(value.x, 'death.x'), y: finiteNumber(value.y, 'death.y'), z: finiteNumber(value.z, 'death.z'),
		respawnDimensionId, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced, gameMode,
		diedAtEpochMs: nonnegativeInteger(value.diedAtEpochMs, 'death.diedAtEpochMs'),
	});
}

function normalizeConversationEvent(value) {
	exactKeys(
		value,
		['sequence', 'kind', 'sourceId', 'recipientId', 'scope', 'text', 'goalRevision', 'observedAtEpochMs'],
		['sequence', 'kind', 'sourceId', 'recipientId', 'scope', 'text', 'goalRevision', 'observedAtEpochMs'],
		'conversation_event',
	);
	const kind = requireIdentifier(value.kind, 'conversation_event.kind');
	if (!['agent_message', 'player_message', 'player_steer', 'proximity_speech'].includes(kind)) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'conversation_event.kind is invalid');
	}
	const scope = requireIdentifier(value.scope, 'conversation_event.scope');
	if (!['public', 'direct', 'proximity'].includes(scope)) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'conversation_event.scope is invalid');
	}
	return {
		sequence: nonnegativeInteger(value.sequence, 'conversation_event.sequence'),
		kind,
		sourceId: boundedText(value.sourceId, 'conversation_event.sourceId', MAX_IDENTIFIER_LENGTH),
		recipientId: boundedText(value.recipientId, 'conversation_event.recipientId', MAX_IDENTIFIER_LENGTH),
		scope,
		text: boundedCodePointText(value.text, 'conversation_event.text', MAX_CONVERSATION_LENGTH),
		goalRevision: revision(value.goalRevision, 'conversation_event.goalRevision'),
		observedAtEpochMs: nonnegativeInteger(value.observedAtEpochMs, 'conversation_event.observedAtEpochMs'),
	};
}

function normalizeConversationWake(value) {
	exactKeys(value, ['transactionId', 'event', 'control'], ['transactionId', 'event', 'control'], 'conversation_wake');
	const event = normalizeConversationEvent(value.event);
	const control = normalizeGoalControl(value.control);
	if (control.operation !== 'start') {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'conversation_wake control must be start');
	}
	if (event.goalRevision === Number.MAX_SAFE_INTEGER || control.goalRevision !== event.goalRevision + 1) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'conversation_wake control revision must immediately follow the event revision');
	}
	return {
		transactionId: requireIdentifier(value.transactionId, 'transactionId'),
		event,
		control,
	};
}

function normalizeObservation(value) {
	const allowed = ['goalRevision', 'observedAtEpochMs', 'ready', 'status', 'eventSequence', 'attention', 'changedFacts', 'position', 'velocity', 'view', 'player', 'inventory', 'entities', 'blocks', 'nearbyContainers', 'world', 'currentAction', 'lastResult', 'interaction'];
	exactKeys(value, allowed, ['goalRevision', 'observedAtEpochMs', 'ready', 'status'], 'observation');
	const normalized = {
		goalRevision: revision(value.goalRevision, 'goalRevision'),
		observedAtEpochMs: nonnegativeInteger(value.observedAtEpochMs, 'observedAtEpochMs'),
		ready: boolean(value.ready, 'ready'),
		status: boundedText(value.status, 'status', MAX_REASON_CODE_LENGTH),
	};
	if (!normalized.ready) {
		for (const key of allowed.slice(7)) if (Object.hasOwn(value, key)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Unavailable observation must not contain live entity fields');
		if (Object.hasOwn(value, 'eventSequence')) normalized.eventSequence = positiveInteger(value.eventSequence, 'eventSequence');
		if (Object.hasOwn(value, 'attention')) normalized.attention = boolean(value.attention, 'attention');
		if (Object.hasOwn(value, 'changedFacts')) normalized.changedFacts = changedFactPaths(value.changedFacts);
		if (normalized.attention === false && normalized.changedFacts?.length > 0) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Non-attention observation cannot contain changed facts');
		return normalized;
	}
	for (const key of allowed.slice(4).filter((field) => field !== 'interaction')) if (!Object.hasOwn(value, key)) throw new ProtocolV2Error('MISSING_FIELD', `observation field '${key}' is required when ready`);
	normalized.eventSequence = positiveInteger(value.eventSequence, 'eventSequence');
	normalized.attention = boolean(value.attention, 'attention');
	normalized.changedFacts = changedFactPaths(value.changedFacts);
	if (!normalized.attention && normalized.changedFacts.length > 0) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Non-attention observation cannot contain changed facts');
	normalized.position = vector(value.position, 'position');
	normalized.velocity = vector(value.velocity, 'velocity');
	normalized.view = numericObject(value.view, 'view', ['yaw', 'pitch']);
	normalized.player = playerObservation(value.player);
	normalized.inventory = inventoryObservation(value.inventory);
	normalized.entities = boundedArray(value.entities, 'entities', MAX_ENTITIES).map(entityObservation);
	normalized.blocks = boundedArray(value.blocks, 'blocks', MAX_BLOCKS).map(blockObservation);
	normalized.nearbyContainers = boundedArray(value.nearbyContainers, 'nearbyContainers', MAX_NEARBY_TRANSACTION_TARGETS).map(nearbyContainerObservation);
	normalized.world = worldObservation(value.world);
	normalized.currentAction = currentActionObservation(value.currentAction);
	normalized.lastResult = lastResultObservation(value.lastResult);
	if (value.interaction !== undefined) normalized.interaction = interactionObservation(value.interaction);
	return normalized;
}

function interactionObservation(value) {
	exactKeys(
		value,
		['mainHandItemId', 'offHandItemId', 'usingItem', 'activeHand', 'useRemainingTicks', 'attackCooldown', 'input', 'menu', 'rayTarget'],
		['mainHandItemId', 'offHandItemId', 'usingItem', 'activeHand', 'useRemainingTicks', 'attackCooldown', 'input', 'menu', 'rayTarget'],
		'interaction',
	);
	const activeHand = requireIdentifier(value.activeHand, 'interaction.activeHand');
	if (!['none', 'main_hand', 'off_hand'].includes(activeHand)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'interaction.activeHand is invalid');
	exactKeys(
		value.input,
		['active', 'forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand'],
		['active', 'forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand'],
		'interaction.input',
	);
	const hand = requireIdentifier(value.input.hand, 'interaction.input.hand');
	if (!['main_hand', 'off_hand'].includes(hand)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'interaction.input.hand is invalid');
	const selectedSlot = nonnegativeInteger(value.input.selectedSlot, 'interaction.input.selectedSlot');
	if (selectedSlot > 8) throw new ProtocolV2Error('INVALID_PAYLOAD', 'interaction.input.selectedSlot must be in [0, 8]');
	exactKeys(value.menu, ['type', 'cursor', 'slots', 'capabilities'], ['type', 'cursor', 'slots', 'capabilities'], 'interaction.menu');
	exactKeys(value.menu.cursor, ['itemId', 'count'], ['itemId', 'count'], 'interaction.menu.cursor');
	const menuSlots = boundedArray(value.menu.slots, 'interaction.menu.slots', 64).map((slot, index) => {
		exactKeys(slot, ['slot', 'itemId', 'count'], ['slot', 'itemId', 'count'], `interaction.menu.slots[${index}]`);
		const slotIndex = nonnegativeInteger(slot.slot, `interaction.menu.slots[${index}].slot`);
		if (slotIndex > 255) throw new ProtocolV2Error('INVALID_PAYLOAD', `interaction.menu.slots[${index}].slot must be at most 255`);
		return {
			slot: slotIndex,
			itemId: requireIdentifier(slot.itemId, `interaction.menu.slots[${index}].itemId`),
			count: nonnegativeInteger(slot.count, `interaction.menu.slots[${index}].count`),
		};
	});
	const menuCapabilities = boundedArray(value.menu.capabilities, 'interaction.menu.capabilities', 8)
		.map((capability, index) => requireIdentifier(capability, `interaction.menu.capabilities[${index}]`));
	const rayAllowed = ['type', 'x', 'y', 'z', 'face', 'blockId'];
	exactKeys(value.rayTarget, rayAllowed, ['type'], 'interaction.rayTarget');
	const rayType = requireIdentifier(value.rayTarget.type, 'interaction.rayTarget.type');
	if (!['miss', 'block', 'entity'].includes(rayType)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'interaction.rayTarget.type is invalid');
	if (rayType === 'block') {
		for (const field of ['x', 'y', 'z', 'face', 'blockId']) if (!Object.hasOwn(value.rayTarget, field)) throw new ProtocolV2Error('MISSING_FIELD', `interaction.rayTarget.${field} is required for a block`);
	} else if (Object.keys(value.rayTarget).length !== 1) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'non-block ray targets must not contain block fields');
	}
	return {
		mainHandItemId: requireIdentifier(value.mainHandItemId, 'interaction.mainHandItemId'),
		offHandItemId: requireIdentifier(value.offHandItemId, 'interaction.offHandItemId'),
		usingItem: boolean(value.usingItem, 'interaction.usingItem'),
		activeHand,
		useRemainingTicks: nonnegativeInteger(value.useRemainingTicks, 'interaction.useRemainingTicks'),
		attackCooldown: finiteNumber(value.attackCooldown, 'interaction.attackCooldown'),
		input: {
			active: boolean(value.input.active, 'interaction.input.active'),
			forward: finiteNumber(value.input.forward, 'interaction.input.forward'),
			strafe: finiteNumber(value.input.strafe, 'interaction.input.strafe'),
			jump: boolean(value.input.jump, 'interaction.input.jump'),
			sneak: boolean(value.input.sneak, 'interaction.input.sneak'),
			sprint: boolean(value.input.sprint, 'interaction.input.sprint'),
			attack: boolean(value.input.attack, 'interaction.input.attack'),
			use: boolean(value.input.use, 'interaction.input.use'),
			yaw: finiteNumber(value.input.yaw, 'interaction.input.yaw'),
			pitch: finiteNumber(value.input.pitch, 'interaction.input.pitch'),
			selectedSlot,
			hand,
		},
		menu: {
			type: requireIdentifier(value.menu.type, 'interaction.menu.type'),
			cursor: {
				itemId: requireIdentifier(value.menu.cursor.itemId, 'interaction.menu.cursor.itemId'),
				count: nonnegativeInteger(value.menu.cursor.count, 'interaction.menu.cursor.count'),
			},
			slots: menuSlots,
			capabilities: menuCapabilities,
		},
		rayTarget: rayType === 'block' ? {
			type: rayType,
			x: integer(value.rayTarget.x, 'interaction.rayTarget.x'),
			y: integer(value.rayTarget.y, 'interaction.rayTarget.y'),
			z: integer(value.rayTarget.z, 'interaction.rayTarget.z'),
			face: requireIdentifier(value.rayTarget.face, 'interaction.rayTarget.face'),
			blockId: requireIdentifier(value.rayTarget.blockId, 'interaction.rayTarget.blockId'),
		} : { type: rayType },
	};
}

function changedFactPaths(value) {
	const paths = boundedArray(value, 'changedFacts', MAX_CHANGED_FACTS).map((path, index) => {
		if (typeof path !== 'string' || !isFactualChangedPath(path)) {
			throw new ProtocolV2Error('INVALID_PAYLOAD', `changedFacts[${index}] must be a factual observation path`);
		}
		return path;
	});
	if (new Set(paths).size !== paths.length) throw new ProtocolV2Error('INVALID_PAYLOAD', 'changedFacts must not contain duplicates');
	return paths;
}

function isFactualChangedPath(path) {
	if (FACTUAL_TOP_LEVEL_PATHS.has(path)) return true;
	if (path.startsWith('player.')) return FACTUAL_PLAYER_FIELDS.has(path.slice('player.'.length));
	if (/^entities\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(path)) return true;
	return /^blocks\.-?\d+,-?\d+,-?\d+$/.test(path);
}

function normalizeActionProgress(value) {
	const allowed = ['traceId', 'goalRevision', 'actionId', 'commandId', 'actionType', 'state', 'message', 'progress', 'elapsedMs', 'observedAtEpochMs'];
	exactKeys(value, allowed, ['traceId', 'goalRevision', 'actionId'], 'action_progress');
	const actionId = requireIdentifier(value.actionId, 'actionId');
	if (value.commandId !== undefined && value.commandId !== actionId) throw new ProtocolV2Error('INVALID_PAYLOAD', 'commandId must match actionId');
	const normalized = { traceId: requireTraceId(value.traceId), goalRevision: revision(value.goalRevision, 'goalRevision'), actionId };
	if (value.commandId !== undefined) normalized.commandId = actionId;
	if (value.actionType !== undefined) normalized.actionType = requireIdentifier(value.actionType, 'actionType');
	if (value.state !== undefined) normalized.state = boundedText(value.state, 'state', MAX_REASON_CODE_LENGTH);
	if (value.message !== undefined) normalized.message = boundedText(value.message, 'message', MAX_RESULT_MESSAGE_LENGTH, 0);
	if (value.progress !== undefined) normalized.progress = finiteNumber(value.progress, 'progress');
	if (value.elapsedMs !== undefined) normalized.elapsedMs = nonnegativeInteger(value.elapsedMs, 'elapsedMs');
	if (value.observedAtEpochMs !== undefined) normalized.observedAtEpochMs = nonnegativeInteger(value.observedAtEpochMs, 'observedAtEpochMs');
	return normalized;
}

function normalizeActionResult(value) {
	const allowed = ['traceId', 'goalRevision', 'actionId', 'commandId', 'actionType', 'state', 'reasonCode', 'message', 'elapsedMs', 'observedAtEpochMs', 'executionStarted', 'physicalAttempted'];
	exactKeys(value, allowed, ['traceId', 'goalRevision', 'actionId', 'commandId', 'actionType', 'state', 'reasonCode', 'message', 'elapsedMs', 'observedAtEpochMs'], 'action_result');
	const actionId = requireIdentifier(value.actionId, 'actionId');
	if (value.commandId !== actionId) throw new ProtocolV2Error('INVALID_PAYLOAD', 'commandId must match actionId');
	const state = boundedText(value.state, 'state', MAX_REASON_CODE_LENGTH);
	if (!TERMINAL_ACTION_STATES.has(state)) throw new ProtocolV2Error('INVALID_PAYLOAD', `Unsupported terminal action state '${state}'`);
	const normalized = {
		traceId: requireTraceId(value.traceId),
		goalRevision: revision(value.goalRevision, 'goalRevision'),
		actionId,
		commandId: actionId,
		actionType: requireIdentifier(value.actionType, 'actionType'),
		state,
		reasonCode: boundedText(value.reasonCode, 'reasonCode', MAX_REASON_CODE_LENGTH),
		message: boundedText(value.message, 'message', MAX_RESULT_MESSAGE_LENGTH, 0),
		elapsedMs: nonnegativeInteger(value.elapsedMs, 'elapsedMs'),
		observedAtEpochMs: nonnegativeInteger(value.observedAtEpochMs, 'observedAtEpochMs'),
	};
	if (value.executionStarted !== undefined) {
		if (typeof value.executionStarted !== 'boolean') throw new ProtocolV2Error('INVALID_PAYLOAD', 'executionStarted must be a boolean');
		normalized.executionStarted = value.executionStarted;
	}
	if (value.physicalAttempted !== undefined) {
		if (typeof value.physicalAttempted !== 'boolean') throw new ProtocolV2Error('INVALID_PAYLOAD', 'physicalAttempted must be a boolean');
		normalized.physicalAttempted = value.physicalAttempted;
	}
	if (normalized.physicalAttempted === true && normalized.executionStarted !== true) throw new ProtocolV2Error('INVALID_PAYLOAD', 'physicalAttempted requires executionStarted');
	return normalized;
}

function normalizeGoalCompletionRequest(value) {
	const allowed = ['goalRevision', 'completionContract', 'traceId', 'profile', 'contractHash'];
	if (!Object.hasOwn(value, 'completionContract')) throw new ProtocolV2Error('CONTRACT_REQUIRED', 'goal_completed requires a factual completionContract');
	exactKeys(value, allowed, allowed, 'goal_completed');
	if (value.completionContract === null) throw new ProtocolV2Error('CONTRACT_REQUIRED', 'goal_completed requires a factual completionContract');
	const goalRevision = revision(value.goalRevision, 'goalRevision');
	const traceId = requireTraceId(value.traceId);
	const contractHash = boundedText(value.contractHash, 'contractHash', 80);
	let completionContract;
	try {
		completionContract = parseCompletionContract(value.completionContract, { goalRevision });
		const bound = bindCompletionContract(completionContract, { goalRevision, traceId, profile: value.profile });
		if (bound.contractHash !== contractHash) throw new Error('contractHash does not match completionContract');
		return { goalRevision, completionContract, traceId, profile: bound.profile, contractHash };
	} catch (error) {
		throw new ProtocolV2Error('INVALID_COMPLETION_CONTRACT', error?.message ?? 'completionContract is invalid', { cause: error });
	}
}

function normalizeActionCommand(value) {
	const allowed = ['traceId', 'goalRevision', 'actionId', 'actionType', 'arguments', 'provenance'];
	exactKeys(value, allowed, ['traceId', 'goalRevision', 'actionId', 'arguments', 'provenance'], 'action_command');
	const actionId = requireIdentifier(value.actionId, 'actionId');
	const traceId = requireTraceId(value.traceId);
	const type = requireIdentifier(value.actionType, 'actionType');
	if (!isPlainObject(value.arguments)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'action_command arguments must be an object');
	for (const key of Reflect.ownKeys(value.arguments)) {
		if (typeof key !== 'string' || key === 'type' || allowed.includes(key)) {
			throw new ProtocolV2Error('INVALID_PAYLOAD_FIELD', `Reserved action_command argument field '${String(key)}'`);
		}
	}
	const action = protocolAction({ ...value.arguments, type });
	const command = validateActionCommandPayload({
		goalRevision: value.goalRevision,
		actionId,
		action,
		provenance: value.provenance,
	});
	if (command.provenance.traceId !== undefined && command.provenance.traceId !== traceId) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', 'provenance.traceId must match traceId');
	}
	const { type: actionType, ...argumentsValue } = action;
	const normalized = { traceId, goalRevision: command.goalRevision, actionId, actionType, arguments: argumentsValue, provenance: command.provenance };
	return deepFreeze(normalized);
}

function protocolAction(value) {
	try { return validateAction(value); } catch (error) { throw new ProtocolV2Error('INVALID_ACTION', error.message, { cause: error }); }
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}

function playerObservation(value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'player must be an object');
	const allowed = [
		'health', 'maxHealth', 'armor', 'foodLevel', 'saturation', 'gameMode',
		'onGround', 'inWater', 'onFire', 'air', 'maxAir', 'suffocating',
		'fallDistance', 'lastAttacker', 'effects',
	];
	const required = allowed.filter((field) => field !== 'lastAttacker');
	exactKeys(value, allowed, required, 'player');
	const normalized = {
		health: finiteNumber(value.health, 'player.health'),
		maxHealth: finiteNumber(value.maxHealth, 'player.maxHealth'),
		armor: nonnegativeInteger(value.armor, 'player.armor'),
		foodLevel: nonnegativeInteger(value.foodLevel, 'player.foodLevel'),
		saturation: finiteNumber(value.saturation, 'player.saturation'),
		gameMode: requireIdentifier(value.gameMode, 'player.gameMode'),
		onGround: boolean(value.onGround, 'player.onGround'),
		inWater: boolean(value.inWater, 'player.inWater'),
		onFire: boolean(value.onFire, 'player.onFire'),
		air: nonnegativeInteger(value.air, 'player.air'),
		maxAir: nonnegativeInteger(value.maxAir, 'player.maxAir'),
		suffocating: boolean(value.suffocating, 'player.suffocating'),
		fallDistance: finiteNumber(value.fallDistance, 'player.fallDistance'),
		effects: boundedArray(value.effects, 'player.effects', MAX_EFFECTS).map((effect, index) => {
			exactKeys(effect, ['effectId', 'amplifier', 'duration'], ['effectId', 'amplifier', 'duration'], `effects[${index}]`);
			return {
				effectId: requireIdentifier(effect.effectId, `effects[${index}].effectId`),
				amplifier: nonnegativeInteger(effect.amplifier, `effects[${index}].amplifier`),
				duration: nonnegativeInteger(effect.duration, `effects[${index}].duration`),
			};
		}),
	};
	if (value.lastAttacker !== undefined) {
		exactKeys(
			value.lastAttacker,
			['uuid', 'type', 'distance'],
			['uuid', 'type', 'distance'],
			'player.lastAttacker',
		);
		normalized.lastAttacker = {
			uuid: requireIdentifier(value.lastAttacker.uuid, 'player.lastAttacker.uuid'),
			type: requireIdentifier(value.lastAttacker.type, 'player.lastAttacker.type'),
			distance: finiteNumber(value.lastAttacker.distance, 'player.lastAttacker.distance'),
		};
	}
	return normalized;
}

function inventoryObservation(value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'inventory must be an object');
	exactKeys(value, ['items', 'selectedItem', 'tagCounts'], ['items', 'selectedItem'], 'inventory');
	const normalizedInventory = {
		items: boundedArray(value.items, 'inventory.items', MAX_INVENTORY_SUMMARIES).map((item, index) => {
			exactKeys(
				item,
				['itemId', 'count', 'damage', 'maxDamage', 'slot', 'hotbar', 'tags'],
				['itemId', 'count', 'damage', 'maxDamage', 'slot'],
				`inventory.items[${index}]`,
			);
			const slot = Number.isSafeInteger(item.slot)
				? nonnegativeInteger(item.slot, `inventory.items[${index}].slot`)
				: requireIdentifier(item.slot, `inventory.items[${index}].slot`);
			const normalized = {
				itemId: requireIdentifier(item.itemId, `inventory.items[${index}].itemId`),
				count: nonnegativeInteger(item.count, `inventory.items[${index}].count`),
				damage: nonnegativeInteger(item.damage, `inventory.items[${index}].damage`),
				maxDamage: nonnegativeInteger(item.maxDamage, `inventory.items[${index}].maxDamage`),
				slot,
			};
			if (item.hotbar !== undefined) normalized.hotbar = boolean(item.hotbar, `inventory.items[${index}].hotbar`);
			if (item.tags !== undefined) normalized.tags = observationTags(item.tags, `inventory.items[${index}].tags`);
			return normalized;
		}),
		selectedItem: requireIdentifier(value.selectedItem, 'inventory.selectedItem'),
	};
	if (value.tagCounts !== undefined) normalizedInventory.tagCounts = observationTagCounts(value.tagCounts, 'inventory.tagCounts');
	return normalizedInventory;
}

function entityObservation(value, index) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `entities[${index}] must be an object`);
	const field = `entities[${index}]`;
	exactKeys(
		value,
		['uuid', 'type', 'name', 'distance', 'position', 'isPlayer', 'itemId', 'count', 'tags'],
		['uuid', 'type', 'name', 'distance', 'position'],
		field,
	);
	const type = requireIdentifier(value.type, `${field}.type`);
	const normalized = {
		uuid: requireIdentifier(value.uuid, `${field}.uuid`),
		type,
		name: boundedText(value.name, `${field}.name`, MAX_CHAT_LENGTH),
		distance: finiteNumber(value.distance, `${field}.distance`),
		position: vector(value.position, `${field}.position`),
	};
	if (value.tags !== undefined) normalized.tags = observationTags(value.tags, `${field}.tags`);
	if (value.isPlayer !== undefined) normalized.isPlayer = boolean(value.isPlayer, `${field}.isPlayer`);
	if (type === 'minecraft:item') {
		if (value.itemId === undefined || value.count === undefined) {
			throw new ProtocolV2Error('MISSING_FIELD', `${field} item entities require itemId and count`);
		}
		normalized.itemId = requireIdentifier(value.itemId, `${field}.itemId`);
		normalized.count = nonnegativeInteger(value.count, `${field}.count`);
		if (normalized.count < 1) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.count must be positive`);
	} else if (value.itemId !== undefined || value.count !== undefined) {
		throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} itemId and count are only valid for item entities`);
	}
	return normalized;
}

function blockObservation(value, index) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `blocks[${index}] must be an object`);
	const field = `blocks[${index}]`;
	exactKeys(value, ['x', 'y', 'z', 'blockId', 'placeableFaces', 'tags'], ['x', 'y', 'z', 'blockId', 'placeableFaces'], field);
	const placeableFaces = boundedArray(value.placeableFaces, `${field}.placeableFaces`, BLOCK_FACES.length)
		.map((face, faceIndex) => {
			const normalized = requireIdentifier(face, `${field}.placeableFaces[${faceIndex}]`);
			if (!BLOCK_FACES.includes(normalized)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.placeableFaces contains unsupported face '${normalized}'`);
			return normalized;
		});
	if (new Set(placeableFaces).size !== placeableFaces.length) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}.placeableFaces must be unique`);
	const normalized = {
		x: integer(value.x, `${field}.x`),
		y: integer(value.y, `${field}.y`),
		z: integer(value.z, `${field}.z`),
		blockId: requireIdentifier(value.blockId, `${field}.blockId`),
		placeableFaces,
	};
	if (value.tags !== undefined) normalized.tags = observationTags(value.tags, `${field}.tags`);
	return normalized;
}

function observationTags(value, field) {
	const tags = boundedArray(value, field, MAX_OBSERVATION_TAGS).map((tag, index) => {
		if (typeof tag !== 'string' || !tag.startsWith('#') || tag.length < 2 || tag.length > MAX_IDENTIFIER_LENGTH) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field}[${index}] must be a tag identifier`);
		return tag;
	});
	if (new Set(tags).size !== tags.length) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be unique`);
	return tags;
}

function observationTagCounts(value, field) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	const keys = Object.keys(value);
	if (keys.length > MAX_TAG_COUNT_ENTRIES) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} exceeds bound of ${MAX_TAG_COUNT_ENTRIES}`);
	const result = {};
	for (const key of keys) {
		if (!key.startsWith('#')) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} keys must be tag identifiers`);
		result[key] = nonnegativeInteger(value[key], `${field}.${key}`);
	}
	return result;
}

function nearbyContainerObservation(value, index) {
	const field = `nearbyContainers[${index}]`;
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	exactKeys(
		value,
		['x', 'y', 'z', 'blockId', 'distance', 'withinInteractionRange', 'capabilities'],
		['x', 'y', 'z', 'blockId', 'distance', 'withinInteractionRange', 'capabilities'],
		field,
	);
	return {
		x: integer(value.x, `${field}.x`),
		y: integer(value.y, `${field}.y`),
		z: integer(value.z, `${field}.z`),
		blockId: requireIdentifier(value.blockId, `${field}.blockId`),
		distance: finiteNumber(value.distance, `${field}.distance`),
		withinInteractionRange: boolean(value.withinInteractionRange, `${field}.withinInteractionRange`),
		capabilities: boundedArray(value.capabilities, `${field}.capabilities`, MAX_MODEL_CAPABILITIES)
			.map((capability, capabilityIndex) => requireIdentifier(capability, `${field}.capabilities[${capabilityIndex}]`)),
	};
}

function worldObservation(value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'world must be an object');
	exactKeys(value, ['dimension', 'gameTime', 'dayTime', 'raining', 'thundering'], ['dimension', 'gameTime', 'dayTime', 'raining', 'thundering'], 'world');
	return { dimension: requireIdentifier(value.dimension, 'world.dimension'), gameTime: nonnegativeInteger(value.gameTime, 'world.gameTime'), dayTime: nonnegativeInteger(value.dayTime, 'world.dayTime'), raining: boolean(value.raining, 'world.raining'), thundering: boolean(value.thundering, 'world.thundering') };
}

function currentActionObservation(value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'currentAction must be an object');
	exactKeys(value, ['active', 'actionId', 'actionType'], ['active'], 'currentAction');
	const active = boolean(value.active, 'currentAction.active');
	if (!active && Object.keys(value).length !== 1) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Inactive currentAction cannot contain action fields');
	return active ? { active, actionId: requireIdentifier(value.actionId, 'currentAction.actionId'), actionType: requireIdentifier(value.actionType, 'currentAction.actionType') } : { active };
}

function lastResultObservation(value) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', 'lastResult must be an object');
	exactKeys(value, ['present', 'actionId', 'actionType', 'state', 'reasonCode', 'message'], ['present'], 'lastResult');
	const present = boolean(value.present, 'lastResult.present');
	if (!present && Object.keys(value).length !== 1) throw new ProtocolV2Error('INVALID_PAYLOAD', 'Empty lastResult cannot contain result fields');
	return present ? { present, actionId: requireIdentifier(value.actionId, 'lastResult.actionId'), actionType: requireIdentifier(value.actionType, 'lastResult.actionType'), state: boundedText(value.state, 'lastResult.state', MAX_REASON_CODE_LENGTH), reasonCode: boundedText(value.reasonCode, 'lastResult.reasonCode', MAX_REASON_CODE_LENGTH), message: boundedText(value.message, 'lastResult.message', MAX_RESULT_MESSAGE_LENGTH, 0) } : { present };
}

function vector(value, field) {
	return numericObject(value, field, ['x', 'y', 'z']);
}

function numericObject(value, field, keys) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	exactKeys(value, keys, keys, field);
	return Object.fromEntries(keys.map((key) => [key, finiteNumber(value[key], `${field}.${key}`)]));
}

function exactKeys(value, allowed, required, field) {
	if (!isPlainObject(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be an object`);
	for (const key of Object.keys(value)) if (!allowed.includes(key)) throw new ProtocolV2Error('INVALID_PAYLOAD_FIELD', `Unknown ${field} field '${key}'`);
	for (const key of required) if (!Object.hasOwn(value, key)) throw new ProtocolV2Error('MISSING_FIELD', `${field} field '${key}' is required`);
}

function boundedArray(value, field, maximum) {
	if (!isExactArray(value) || value.length > maximum) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a dense plain array with at most ${maximum} entries`);
	return value;
}

function isExactArray(value) {
	if (!Array.isArray(value) || Object.getPrototypeOf(value) !== Array.prototype) return false;
	const expected = ['length', ...Array.from({ length: value.length }, (_, index) => String(index))].sort();
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key !== 'string') || keys.map(String).sort().join('\u0000') !== expected.join('\u0000')) return false;
	for (let index = 0; index < value.length; index += 1) {
		const descriptor = Object.getOwnPropertyDescriptor(value, String(index));
		if (!descriptor || !Object.hasOwn(descriptor, 'value') || !descriptor.enumerable || !descriptor.writable || !descriptor.configurable) return false;
	}
	return true;
}

function boundedText(value, field, maximum, minimum = 1) {
	if (typeof value !== 'string' || value.length < minimum || value.length > maximum || (minimum > 0 && value.trim().length === 0)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must contain ${minimum} to ${maximum} characters`);
	return value;
}

function boundedCodePointText(value, field, maximum) {
	if (typeof value !== 'string') throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a string`);
	if (value.trim().length === 0) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must not be blank`);
	if ([...value].length > maximum) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must contain at most ${maximum} code points`);
	return value;
}


function boolean(value, field) {
	if (typeof value !== 'boolean') throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a boolean`);
	return value;
}

function nullableIdentifier(value, field) {
	return value === null ? null : requireIdentifier(value, field);
}

function nullableBoolean(value, field) {
	return value === null ? null : boolean(value, field);
}

function finiteNumber(value, field) {
	if (typeof value !== 'number' || !Number.isFinite(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a finite number`);
	return value;
}

function nullableFiniteNumber(value, field) {
	return value === null ? null : finiteNumber(value, field);
}

function integer(value, field) {
	if (!Number.isSafeInteger(value)) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a safe integer`);
	return value;
}

function nonnegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new ProtocolV2Error('INVALID_PAYLOAD', `${field} must be a nonnegative safe integer`);
	return value;
}

function nonnegativeFiniteNumber(value, field) {
	if (!Number.isFinite(value) || value < 0) throw new ProtocolV2Error('INVALID_FIELD', `${field} must be a non-negative finite number`);
	return value;
}

function withInboundEnvelopeContext(error, value) {
	const original = error instanceof Error
		? error
		: new ProtocolV2Error('PROTOCOL_ERROR', String(error));
	const type = typeof value?.type === 'string' ? boundedDiagnostic(value.type) : typeof value?.type;
	const revisionValue = isPlainObject(value?.payload) ? value.payload.goalRevision : undefined;
	const revision = revisionValue === undefined
		? 'absent'
		: `${typeof revisionValue}:${boundedDiagnostic(JSON.stringify(revisionValue))}`;
	const contextual = new ProtocolV2Error(
		original.code ?? 'PROTOCOL_ERROR',
		`${original.message} [inbound type=${type}, goalRevision=${revision}]`
	);
	contextual.cause = original;
	return contextual;
}

function boundedDiagnostic(value) {
	return String(value).replace(/[\u0000-\u001f\u007f]/g, '?').slice(0, 96);
}

function revision(value, field) {
	return nonnegativeInteger(value, field);
}

function requireSecret(value) {
	if (typeof value !== 'string' || value.length < 32 || value.length > MAX_BRIDGE_SECRET_LENGTH) throw new TypeError(`bridge secret must contain 32 to ${MAX_BRIDGE_SECRET_LENGTH} characters`);
	return value;
}

function requirePort(value) {
	if (!Number.isInteger(value) || value < 1_024 || value > 65_535) throw new TypeError('bridge port must be an integer between 1024 and 65535');
	return value;
}

function requireIdentifier(value, field) {
	return requireText(value, field, MAX_IDENTIFIER_LENGTH);
}

function requireText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new ProtocolV2Error('INVALID_FIELD', `${field} must be nonblank and at most ${maximum} characters`);
	return value;
}

function requireTraceId(value) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new ProtocolV2Error('INVALID_FIELD', 'traceId must be nonblank');
	if (Buffer.byteLength(value, 'utf8') > 128) throw new ProtocolV2Error('INVALID_FIELD', 'traceId must be at most 128 UTF-8 bytes');
	if ([...value].some((character) => /[\u0000-\u001f\u007f]/u.test(character))) throw new ProtocolV2Error('INVALID_FIELD', 'traceId contains control characters');
	return value;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function isPlainObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}

function rememberBounded(set, value, maximum) {
	set.add(value);
	if (set.size > maximum) set.delete(set.values().next().value);
}
