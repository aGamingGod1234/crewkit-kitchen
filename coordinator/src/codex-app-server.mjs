import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

import { JsonlDecoder, encodeJsonLine } from './jsonl.mjs';
import { parseDecision } from './decision-parser.mjs';
import { PLANNER_OUTPUT_SCHEMA, PLANNER_SYSTEM_PROMPT } from './prompts.mjs';

const APP_SERVER_MAX_LINE_BYTES = 4 * 1_024 * 1_024;
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
const DEFAULT_PLANNING_TIMEOUT_MS = 45_000;
const PROFILE_VALUE_PATTERN = /^[A-Za-z0-9._-]+$/;
const CLIENT_INFO = Object.freeze({ name: 'arena-agents-coordinator', title: 'Minecraft Arena Agents', version: '1.0.0' });

export class CodexProtocolError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'CodexProtocolError';
		this.code = code;
	}
}

export function buildCodexArgs(config) {
	const model = profileValue(config.model, 'model');
	const effort = profileValue(config.reasoningEffort, 'reasoningEffort');
	const serviceTier = profileValue(config.serviceTier, 'serviceTier');
	return [
		'app-server', '--stdio',
		'-c', `model="${model}"`,
		'-c', `model_reasoning_effort="${effort}"`,
		'-c', `service_tier="${serviceTier}"`,
		'-c', 'features.fast_mode=true',
	];
}

export function resolveCodexLaunch(config, dependencies = {}) {
	const platform = dependencies.platform ?? process.platform;
	const environment = dependencies.env ?? process.env;
	const nodeExecutable = dependencies.execPath ?? process.execPath;
	const pathExists = dependencies.existsSync ?? existsSync;
	if (platform === 'win32' && typeof environment.APPDATA === 'string') {
		const entrypoint = path.join(environment.APPDATA, 'npm', 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
		if (pathExists(entrypoint)) return { command: nodeExecutable, args: [entrypoint, ...buildCodexArgs(config)] };
	}
	return { command: 'codex', args: buildCodexArgs(config) };
}

export class CodexStdioTransport extends EventEmitter {
	#config;
	#spawn;
	#child = null;
	#decoder = null;
	#requestId = 0;
	#pending = new Map();

	constructor(config, dependencies = {}) {
		super();
		this.#config = config;
		this.#spawn = dependencies.spawn ?? spawn;
	}

	async start() {
		if (this.#child !== null) return;
		this.#decoder = new JsonlDecoder({ maxBytes: APP_SERVER_MAX_LINE_BYTES });
		let child;
		try {
			const launch = resolveCodexLaunch(this.#config);
			child = this.#spawn(launch.command, launch.args, {
				cwd: this.#config.cwd,
				stdio: ['pipe', 'pipe', 'pipe'],
				windowsHide: true,
			});
		} catch (error) {
			throw new CodexProtocolError('SPAWN_FAILED', `Could not start Codex app-server: ${error.message}`, { cause: error });
		}
		this.#child = child;
		child.stdout.on('data', (chunk) => this.#onStdout(child, chunk));
		child.stderr.on('data', (chunk) => this.emit('diagnostic', redact(String(chunk))));
		child.on('exit', (code, signal) => this.#onExit(child, code, signal));
		await new Promise((resolve, reject) => {
			const onSpawn = () => { cleanup(); resolve(); };
			const onError = (error) => { cleanup(); this.#child = null; reject(new CodexProtocolError('SPAWN_FAILED', `Could not start Codex app-server: ${error.message}`, { cause: error })); };
			const cleanup = () => { child.off('spawn', onSpawn); child.off('error', onError); };
			child.once('spawn', onSpawn);
			child.once('error', onError);
		});
	}

	request(method, params = {}, { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS } = {}) {
		this.#requireRunning();
		const id = this.#nextRequestId();
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => {
				this.#pending.delete(id);
				reject(new CodexProtocolError('REQUEST_TIMEOUT', `Codex request '${method}' timed out after ${timeoutMs} ms`));
			}, timeoutMs);
			this.#pending.set(id, { method, resolve, reject, timer });
			try {
				this.#write({ id, method, params });
			} catch (error) {
				clearTimeout(timer);
				this.#pending.delete(id);
				reject(error);
			}
		});
	}

	notify(method, params = {}) {
		this.#requireRunning();
		this.#write({ method, params });
	}

	async stop() {
		const child = this.#child;
		if (child === null) return;
		this.#child = null;
		this.#rejectPending(new CodexProtocolError('TRANSPORT_STOPPED', 'Codex app-server transport stopped'));
		if (!child.killed) child.kill();
		if (child.exitCode === null && child.signalCode === null) {
			await Promise.race([
				new Promise((resolve) => child.once('exit', resolve)),
				new Promise((resolve) => setTimeout(resolve, 2_000)),
			]);
		}
	}

	#onStdout(child, chunk) {
		if (child !== this.#child) return;
		try {
			for (const message of this.#decoder.push(chunk)) this.#acceptMessage(message);
		} catch (error) {
			this.emit('protocolError', new CodexProtocolError(error.code ?? 'INVALID_RESPONSE', error.message, { cause: error }));
			if (!child.killed) child.kill();
		}
	}

	#acceptMessage(message) {
		if (Object.hasOwn(message, 'id')) {
			const pending = this.#pending.get(message.id);
			if (pending === undefined) {
				this.emit('protocolError', new CodexProtocolError('UNKNOWN_RESPONSE_ID', `Codex response used unknown id '${String(message.id)}'`));
				return;
			}
			this.#pending.delete(message.id);
			clearTimeout(pending.timer);
			if (Object.hasOwn(message, 'error')) pending.reject(new CodexProtocolError('RPC_ERROR', `${pending.method}: ${rpcErrorMessage(message.error)}`));
			else if (Object.hasOwn(message, 'result')) pending.resolve(message.result);
			else pending.reject(new CodexProtocolError('INVALID_RESPONSE', `Codex response for '${pending.method}' has no result or error`));
			return;
		}
		if (typeof message.method === 'string' && !Object.hasOwn(message, 'id')) {
			this.emit('notification', { method: message.method, params: message.params ?? {} });
			return;
		}
		this.emit('protocolError', new CodexProtocolError('INVALID_RESPONSE', 'Codex app-server emitted an invalid JSON-RPC message'));
	}

	#onExit(child, code, signal) {
		if (child !== this.#child) return;
		this.#child = null;
		const error = new CodexProtocolError('PROCESS_EXITED', `Codex app-server exited (code=${String(code)}, signal=${String(signal)})`);
		this.#rejectPending(error);
		this.emit('exit', error);
	}

	#write(message) {
		this.#requireRunning();
		this.#child.stdin.write(encodeJsonLine(message, { maxBytes: APP_SERVER_MAX_LINE_BYTES }));
	}

	#requireRunning() {
		if (this.#child === null || this.#child.killed) throw new CodexProtocolError('TRANSPORT_NOT_RUNNING', 'Codex app-server transport is not running');
	}

	#nextRequestId() {
		if (this.#requestId === Number.MAX_SAFE_INTEGER) throw new CodexProtocolError('REQUEST_ID_EXHAUSTED', 'Codex request ID sequence is exhausted');
		this.#requestId += 1;
		return this.#requestId;
	}

	#rejectPending(error) {
		for (const pending of this.#pending.values()) {
			clearTimeout(pending.timer);
			pending.reject(error);
		}
		this.#pending.clear();
	}
}

export class CodexAgent {
	#config;
	#transport;
	#started = false;
	#threadId = null;
	#activeTurnId = null;

	constructor(config, transport = new CodexStdioTransport(config)) {
		this.#config = validateAgentConfig(config);
		this.#transport = transport;
	}

	get model() { return this.#config.model; }
	get reasoningEffort() { return this.#config.reasoningEffort; }
	get serviceTier() { return this.#config.serviceTier; }

	async start() {
		if (this.#started) return;
		await this.#transport.start();
		try {
			await this.#transport.request('initialize', { clientInfo: CLIENT_INFO });
			this.#transport.notify('initialized', {});
			const models = await this.#listModels();
			verifyModelProfile(models, this.#config);
			const response = await this.#transport.request('thread/start', {
				model: this.#config.model,
				serviceTier: this.#config.serviceTier,
				cwd: this.#config.cwd,
				approvalPolicy: 'never',
				sandbox: 'read-only',
				dynamicTools: [],
				environments: [],
				ephemeral: true,
				baseInstructions: PLANNER_SYSTEM_PROMPT,
				developerInstructions: 'Return only the validated Minecraft decision object. Never call tools.',
			});
			this.#threadId = requireNestedId(response, 'thread', 'thread/start');
			this.#started = true;
		} catch (error) {
			await this.#transport.stop();
			throw error;
		}
	}

	async decide(input) {
		if (!this.#started || this.#threadId === null) throw new CodexProtocolError('AGENT_NOT_STARTED', 'Codex agent has not started');
		if (this.#activeTurnId !== null) throw new CodexProtocolError('TURN_IN_PROGRESS', 'Only one Codex turn may run at a time');
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('planner input must be a nonblank string');
		const collector = this.#collectTurn();
		try {
			const response = await this.#transport.request('turn/start', {
				threadId: this.#threadId,
				input: [{ type: 'text', text: input }],
				model: this.#config.model,
				effort: this.#config.reasoningEffort,
				serviceTier: this.#config.serviceTier,
				approvalPolicy: 'never',
				environments: [],
				outputSchema: PLANNER_OUTPUT_SCHEMA,
			});
			this.#activeTurnId = requireNestedId(response, 'turn', 'turn/start');
			collector.setTurnId(this.#activeTurnId);
			const text = await withTimeout(collector.promise, this.#config.planningTimeoutMs, async () => {
				await this.interrupt();
			});
			return parseDecision(text);
		} catch (error) {
			if (error?.code === 'TIMEOUT') throw new CodexProtocolError('PLANNING_TIMEOUT', `Codex planning exceeded ${this.#config.planningTimeoutMs} ms`, { cause: error });
			throw error;
		} finally {
			collector.dispose();
			this.#activeTurnId = null;
		}
	}

	async interrupt() {
		if (!this.#started || this.#threadId === null || this.#activeTurnId === null) return;
		await this.#transport.request('turn/interrupt', { threadId: this.#threadId, turnId: this.#activeTurnId });
	}

	async stop() {
		if (this.#activeTurnId !== null) {
			try { await this.interrupt(); } catch { /* teardown continues */ }
		}
		this.#started = false;
		this.#threadId = null;
		this.#activeTurnId = null;
		await this.#transport.stop();
	}

	async #listModels() {
		const models = [];
		let cursor = null;
		do {
			const response = await this.#transport.request('model/list', { cursor, limit: 100, includeHidden: true });
			if (!Array.isArray(response?.data)) throw new CodexProtocolError('INVALID_CATALOG', 'model/list response must contain a data array');
			models.push(...response.data);
			cursor = response.nextCursor ?? null;
		} while (cursor !== null);
		return models;
	}

	#collectTurn() {
		let expectedTurnId = null;
		let lastMessage = null;
		let resolvePromise;
		let rejectPromise;
		const promise = new Promise((resolve, reject) => { resolvePromise = resolve; rejectPromise = reject; });
		const onNotification = ({ method, params }) => {
			if (params?.threadId !== this.#threadId) return;
			if (expectedTurnId !== null && turnIdOf(method, params) !== expectedTurnId) return;
			if (method === 'item/completed' && params.item?.type === 'agentMessage' && typeof params.item.text === 'string') lastMessage = params.item.text;
			if (method === 'turn/completed') {
				if (params.turn?.status !== 'completed') rejectPromise(new CodexProtocolError('TURN_FAILED', `Codex turn ended with status '${String(params.turn?.status)}'`));
				else if (lastMessage === null) rejectPromise(new CodexProtocolError('MISSING_FINAL_MESSAGE', 'Codex turn completed without an agent message'));
				else resolvePromise(lastMessage);
			}
		};
		this.#transport.on('notification', onNotification);
		return {
			promise,
			setTurnId: (turnId) => { expectedTurnId = turnId; },
			dispose: () => this.#transport.off('notification', onNotification),
		};
	}
}

export async function checkCodexModelProfile(configValue, transport = new CodexStdioTransport(configValue)) {
	const config = validateAgentConfig(configValue);
	await transport.start();
	try {
		await transport.request('initialize', { clientInfo: CLIENT_INFO });
		transport.notify('initialized', {});
		const models = [];
		let cursor = null;
		do {
			const response = await transport.request('model/list', { cursor, limit: 100, includeHidden: true });
			if (!Array.isArray(response?.data)) throw new CodexProtocolError('INVALID_CATALOG', 'model/list response must contain a data array');
			models.push(...response.data);
			cursor = response.nextCursor ?? null;
		} while (cursor !== null);
		return verifyModelProfile(models, config);
	} finally {
		await transport.stop();
	}
}

export function verifyModelProfile(models, config) {
	const model = models.find((candidate) => candidate?.model === config.model || candidate?.id === config.model);
	if (model === undefined) throw new CodexProtocolError('MODEL_PROFILE_UNAVAILABLE', `Model '${config.model}' is absent from the Codex catalog`);
	const efforts = Array.isArray(model.supportedReasoningEfforts)
		? model.supportedReasoningEfforts.map((entry) => typeof entry === 'string' ? entry : entry?.reasoningEffort)
		: [];
	const tiers = [
		...(Array.isArray(model.serviceTiers) ? model.serviceTiers.map((entry) => typeof entry === 'string' ? entry : entry?.id) : []),
		...(Array.isArray(model.additionalSpeedTiers) ? model.additionalSpeedTiers : []),
	];
	if (!efforts.includes(config.reasoningEffort) || !tiers.includes(config.serviceTier)) throw new CodexProtocolError('MODEL_PROFILE_UNAVAILABLE', `Model '${config.model}' does not advertise effort '${config.reasoningEffort}' with tier '${config.serviceTier}'`);
	return model;
}

function validateAgentConfig(config) {
	if (config === null || typeof config !== 'object') throw new TypeError('Codex agent config must be an object');
	for (const field of ['agentId', 'model', 'reasoningEffort', 'serviceTier']) profileValue(config[field], field);
	if (config.serviceTier !== 'fast') throw new TypeError("serviceTier must be exactly 'fast'");
	if (typeof config.cwd !== 'string' || config.cwd.trim().length === 0) throw new TypeError('cwd must be a nonblank path');
	const planningTimeoutMs = config.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS;
	if (!Number.isSafeInteger(planningTimeoutMs) || planningTimeoutMs <= 0) throw new TypeError('planningTimeoutMs must be a positive safe integer');
	return { ...config, planningTimeoutMs };
}

function profileValue(value, field) {
	if (typeof value !== 'string' || !PROFILE_VALUE_PATTERN.test(value)) throw new TypeError(`${field} contains an unsupported profile value`);
	return value;
}

function requireNestedId(response, field, method) {
	const id = response?.[field]?.id;
	if (typeof id !== 'string' || id.length === 0) throw new CodexProtocolError('INVALID_RESPONSE', `${method} response is missing ${field}.id`);
	return id;
}

function turnIdOf(method, params) {
	return method === 'turn/completed' ? params.turn?.id : params.turnId;
}

function withTimeout(promise, timeoutMs, onTimeout) {
	return new Promise((resolve, reject) => {
		const timer = setTimeout(async () => {
			try { await onTimeout(); } catch { /* primary timeout wins */ }
			const error = new Error('operation timed out');
			error.code = 'TIMEOUT';
			reject(error);
		}, timeoutMs);
		promise.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
	});
}

function rpcErrorMessage(error) {
	if (error && typeof error.message === 'string') return redact(error.message);
	return 'unknown JSON-RPC error';
}

function redact(value) {
	return value
		.replace(/(?:Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
		.replace(/(?:api[_-]?key|token|secret)(\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]');
}
