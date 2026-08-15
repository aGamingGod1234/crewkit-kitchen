import { EventEmitter } from 'node:events';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentPlanner } from './agent-planner.mjs';
import { AgentRegistry, DynamicAgentState } from './agent-registry.mjs';
import { AgentWorkspaceManager } from './agent-workspace.mjs';
import { AcpProviderService } from './acp-service.mjs';
import { AntigravityProviderService } from './antigravity-service.mjs';
import { CodexService } from './codex-service.mjs';
import { ControlLatencyRegistry } from './control-latency-registry.mjs';
import { FactLedger } from './fact-ledger.mjs';
import { ProviderService } from './provider-service.mjs';
import {
	DEFAULT_AGENT_CAP,
	DEFAULT_GOAL_QUEUE_CAP,
	DEFAULT_PLANNING_CONCURRENCY,
	DEFAULT_SERVICE_TIER,
} from './constants.mjs';
import { PlanningScheduler } from './planning-scheduler.mjs';
import { MultiplexedServerBridge, ProtocolV2Error } from './protocol-v2.mjs';
import { buildPlannerInput } from './prompts.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { ProgramRuntimeManager } from './program-runtime-manager.mjs';

const SOURCE_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const COORDINATOR_DIRECTORY = path.resolve(SOURCE_DIRECTORY, '..');
const PROJECT_DIRECTORY = path.resolve(COORDINATOR_DIRECTORY, '..');
const DEFAULT_DYNAMIC_CONFIG_PATH = path.join(COORDINATOR_DIRECTORY, 'config', 'dynamic-agents.json');
const DEFAULT_INVALID_DECISION_RETRIES = 1;
const EMPTY_TURN_RETRY_DELAY_MS = 1_000;
const QUIET_RETRYABLE_PROVIDER_ERRORS = new Set(['MISSING_AGENT_MESSAGE', 'MISSING_FINAL_MESSAGE']);

export class DynamicCoordinator extends EventEmitter {
	#registry;
	#scheduler;
	#codexService;
	#planner;
	#bridge;
	#listeners = [];
	#agentOperations = new Map();
	#lifecycleGenerations = new Map();
	#providerRetryAfter = new Map();
	#factLedgers = new Map();
	#programRuntime;
	#reconciliation = Promise.resolve();
	#started = false;
	#stopping = false;
	#closed = false;
	#healthRegistry;
	#latencyRegistry;
	#controlNow;
	#disconnectedAt = null;
	#supportedAgentIds = new Set();
	#reconciledStatus = false;
	#setStatusInterval;
	#clearStatusInterval;
	#statusHandle = null;
	#serverInstanceId = null;

	constructor({ registry, scheduler, codexService, planner, bridge, healthRegistry, latencyRegistry, controlNow = () => performance.now(), epochNow = Date.now, setStatusInterval = defaultStatusInterval, clearStatusInterval = clearInterval }) {
		super();
		this.#registry = requireDependency(registry, 'registry');
		this.#scheduler = requireDependency(scheduler, 'scheduler');
		this.#codexService = requireDependency(codexService, 'codexService');
		this.#planner = requireDependency(planner, 'planner');
		this.#bridge = requireDependency(bridge, 'bridge');
		this.#healthRegistry = requireDependency(healthRegistry, 'healthRegistry');
		this.#latencyRegistry = requireDependency(latencyRegistry, 'latencyRegistry');
		if (typeof controlNow !== 'function') throw new TypeError('controlNow must be a function');
		if (typeof epochNow !== 'function') throw new TypeError('epochNow must be a function');
		this.#controlNow = controlNow;
		this.#programRuntime = new ProgramRuntimeManager({
			registry: this.#registry,
			bridge: this.#bridge,
			planner: this.#planner,
			reportError: (agentId, error) => this.#reportAgentError(agentId, error),
			latencyRegistry: this.#latencyRegistry,
			clock: () => this.#controlNow(),
			epochClock: epochNow,
		});
		this.#setStatusInterval = requireDependency(setStatusInterval, 'setStatusInterval');
		this.#clearStatusInterval = requireDependency(clearStatusInterval, 'clearStatusInterval');
	}

	get registry() { return this.#registry; }
	get bridge() { return this.#bridge; }

	async start() {
		if (this.#started) return;
		if (this.#closed) throw new Error('Dynamic coordinator cannot restart after it has been stopped');
		this.#stopping = false;
		await this.#codexService.start();
		try {
			this.#bindBridge();
			this.#bridge.start();
			this.#statusHandle = this.#setStatusInterval(() => this.#run(() => this.#publishStatus()), 1_000);
			this.#started = true;
		} catch (error) {
			if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
			this.#statusHandle = null;
			this.#bridge.stop();
			this.#unbindBridge();
			await this.#codexService.stop();
			throw error;
		}
	}

	async stop() {
		if (this.#stopping || this.#closed) return;
		this.#stopping = true;
		if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
		this.#statusHandle = null;
		this.#bridge.stop();
		this.#unbindBridge();
		this.#scheduler.close('Dynamic coordinator stopped');
		await Promise.allSettled([this.#reconciliation, ...this.#agentOperations.values()]);
		this.#agentOperations.clear();
		this.#lifecycleGenerations.clear();
		this.#programRuntime.disposeAll();
		this.#providerRetryAfter.clear();
		await this.#codexService.stop();
		this.#started = false;
		this.#stopping = false;
		this.#closed = true;
	}

	#bindBridge() {
		this.#listen('ready', ({ serverInstanceId, registry }) => {
			if (this.#serverInstanceId !== null && serverInstanceId !== this.#serverInstanceId) this.#healthRegistry.reset();
			this.#serverInstanceId = serverInstanceId;
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#reconciliation = Promise.resolve().then(async () => {
			const reconciliation = await this.#planner.reconcile(registry);
			const providers = reconciliation.providers ?? reconciliation.codex;
			await this.#publishCatalog(providers.catalog);
			for (const profile of providers.valid) {
				const record = this.#registry.get(profile.agentId);
				if (record === null) throw new ProtocolV2Error('UNKNOWN_AGENT', `Reconciled provider profile references unknown agent '${profile.agentId}'`);
				await this.#bridge.send('agent_ready', profile.agentId, { goalRevision: record.goalRevision, reconciled: true });
				this.#supportedAgentIds.add(profile.agentId);
			}
			for (const invalid of providers.invalid) {
				const agentId = invalid.agentId ?? invalid.profile?.agentId;
				await this.#bridge.send('agent_error', agentId, { goalRevision: this.#registry.get(agentId)?.goalRevision ?? 0, code: invalid.code, message: invalid.message });
			}
			this.#reconciledStatus = true;
			if (this.#disconnectedAt !== null) this.#disconnectedAt = null;
			await this.#publishStatus();
			this.emit('reconciled', reconciliation);
			});
			this.#reconciliation.catch((error) => this.emit('runtimeError', error));
		});
		this.#listen('catalog_request', () => this.#run(async () => {
			await this.#reconciliation;
			const catalog = await this.#codexService.catalog.refresh({ force: true });
			await this.#publishCatalog(catalog);
		}));
		this.#listen('agent_registered', (message) => this.#enqueueAgent(message.agentId, async () => {
			const record = this.#registry.register(message.payload.record === undefined
				? { ...message.payload, agentId: message.agentId }
				: { ...message.payload.record, agentId: message.agentId });
			if (this.#codexService.catalog.stale) await this.#codexService.catalog.refresh();
			this.#codexService.catalog.assertSupported(record.provider, record.model, record.reasoningEffort, record.serviceTier ?? DEFAULT_SERVICE_TIER);
			await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
			this.#supportedAgentIds.add(record.agentId);
			await this.#publishStatus();
		}));
		this.#listen('agent_removed', (message) => this.#run(async () => {
			await this.#reconciliation;
			this.#programRuntime.dispose(message.agentId);
			await this.#planner.remove(message.agentId);
			this.#lifecycleGenerations.delete(message.agentId);
			this.#providerRetryAfter.delete(message.agentId);
			this.#factLedgers.delete(message.agentId);
			this.#supportedAgentIds.delete(message.agentId);
			await this.#publishStatus();
		}));
		this.#listen('goal_control', (message) => {
			this.#invalidateLifecycleWork(message);
			const previous = this.#registry.get(message.agentId);
			if (previous !== null && message.payload.operation !== 'queue') this.#programRuntime.onGoalControl(previous, message.payload.operation);
			const interruption = this.#beginGoalControlInterruption(message);
			this.#enqueueAgent(message.agentId, async () => {
				const record = this.#registry.applyGoalControl(message.agentId, message.payload);
				if (message.payload.operation !== 'queue') {
					this.#providerRetryAfter.delete(message.agentId);
				}
				const interruptionResult = await interruption;
				if (interruptionResult.error !== null) throw interruptionResult.error;
				if (['start', 'resume', 'steer'].includes(message.payload.operation)) {
					await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision });
				}
				this.emit('goalControl', record);
			});
		});
		this.#listen('observation', (message) => {
			const receivedAt = this.#controlNow();
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			this.#enqueueAgent(message.agentId, async () => {
				if (!this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration)) return;
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				if (![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
				const observation = message.payload.observation ?? message.payload;
				const ledger = this.#ledger(record.agentId);
				ledger.ingest('observation', observation);
				const installed = await this.#programRuntime.onObservation(record, {
					observation,
					eventSequence: message.payload.eventSequence,
					attention: message.payload.attention === true,
					receivedAtMonotonic: receivedAt,
					observedAtEpochMs: message.payload.observedAtEpochMs,
				});
				if (installed !== null) return;
				if ((this.#providerRetryAfter.get(record.agentId) ?? 0) > receivedAt) return;
				await this.#bridge.send('planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING });
				const decision = await this.#planner.requestPlan({
					agentId: record.agentId,
					goalRevision: record.goalRevision,
					recoverySummary: record.lastSummary,
					input: buildPlannerInput({
						agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
						goal: record.currentGoal, goalRevision: record.goalRevision, observation,
					}, { untrustedFacts: ledger.toPlannerFacts() }),
				});
				if (!this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return;
				const runtime = await this.#programRuntime.installDecision(record, decision, { observation, eventSequence: message.payload.eventSequence });
				if (runtime !== null) this.#providerRetryAfter.delete(record.agentId);
			});
		});
		this.#listen('action_progress', (message) => {
			const receivedAt = this.#controlNow();
			this.#enqueueAgent(message.agentId, async () => {
			const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
			if (!this.#programRuntime.onActionProgress(record, message.payload)) throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
			this.emit('actionProgress', message);
			});
		});
		this.#listen('action_result', (message) => {
			const receivedAt = this.#controlNow();
			this.#enqueueAgent(message.agentId, async () => {
			const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
			this.#ledger(record.agentId).ingest('action_result', message.payload);
			if (!await this.#programRuntime.onActionResult(record, message.payload)) throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
			this.emit('actionResult', message);
			});
		});
		this.#listen('disconnected', () => {
			for (const record of this.#registry.list()) this.#advanceLifecycleGeneration(record.agentId);
			this.#run(async () => {
			this.#disconnectedAt ??= this.#controlNow();
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#programRuntime.disposeAll();
			this.#providerRetryAfter.clear();
			await Promise.allSettled(this.#registry.list().map(async (record) => {
				if (record.state !== DynamicAgentState.DISCONNECTED) this.#registry.setState(record.agentId, DynamicAgentState.DISCONNECTED, { goalRevision: record.goalRevision });
				await this.#planner.interrupt(record.agentId, 'Minecraft bridge disconnected');
			}));
			});
		});
		this.#listen('shutdown', () => this.#run(() => this.stop()));
		this.#listen('protocolError', (error) => this.emit('runtimeError', error));
		this.#listen('transportError', (error) => this.emit('runtimeError', error));
	}

	#listen(event, listener) {
		this.#bridge.on(event, listener);
		this.#listeners.push([event, listener]);
	}

	#unbindBridge() {
		for (const [event, listener] of this.#listeners) this.#bridge.off(event, listener);
		this.#listeners = [];
	}

	#beginGoalControlInterruption(message) {
		let reason = null;
		if (['stop', 'disconnect', 'dead'].includes(message.payload.operation)) reason = `Goal ${message.payload.operation}`;
		if (message.payload.operation === 'steer') reason = 'Goal steered';
		if (reason === null) return Promise.resolve({ error: null });
		try {
			return Promise.resolve(this.#planner.interrupt(message.agentId, reason)).then(
				() => ({ error: null }),
				(error) => ({ error }),
			);
		} catch (error) {
			return Promise.resolve({ error });
		}
	}

	#lifecycleGeneration(agentId) {
		return this.#lifecycleGenerations.get(agentId) ?? 0;
	}

	#advanceLifecycleGeneration(agentId) {
		const next = this.#lifecycleGeneration(agentId) + 1;
		this.#lifecycleGenerations.set(agentId, next);
		return next;
	}

	#invalidateLifecycleWork(message) {
		if (message.payload.operation === 'queue') return;
		const current = this.#registry.get(message.agentId);
		if (current !== null && message.payload.goalRevision <= current.goalRevision) return;
		this.#advanceLifecycleGeneration(message.agentId);
	}

	#isLifecycleGenerationCurrent(agentId, generation) {
		return this.#lifecycleGeneration(agentId) === generation;
	}

	#enqueueAgent(agentId, operation) {
		const previous = this.#agentOperations.get(agentId) ?? Promise.resolve();
		const current = previous.catch(() => {}).then(() => this.#reconciliation).then(operation);
		this.#agentOperations.set(agentId, current);
		current.catch((error) => this.#reportAgentError(agentId, error)).finally(() => {
			if (this.#agentOperations.get(agentId) === current) this.#agentOperations.delete(agentId);
		});
		return current;
	}

	#run(operation) {
		Promise.resolve().then(operation).catch((error) => this.emit('runtimeError', error));
	}

	async #reportAgentError(agentId, error) {
		if (['PLAN_CANCELLED', 'STALE_PLAN'].includes(error?.code)) return;
		if (QUIET_RETRYABLE_PROVIDER_ERRORS.has(error?.code)) {
			// App-server transport silence is retried from the next fresh observation.
			// It is not a world-action failure that the player or agent must repair.
			this.#providerRetryAfter.set(agentId, this.#controlNow() + EMPTY_TURN_RETRY_DELAY_MS);
			return;
		}
		this.emit('runtimeError', error);
		if (!this.#bridge.ready || !this.#registry.has(agentId)) return;
		const record = this.#registry.get(agentId);
		try {
			await this.#bridge.send('agent_error', agentId, {
				goalRevision: record.goalRevision,
				code: String(error?.code ?? 'COORDINATOR_ERROR').slice(0, 128),
				message: String(error?.message ?? error).slice(0, 2_048),
			});
		} catch (reportError) {
			this.emit('runtimeError', reportError);
		}
	}

	async #publishCatalog(snapshot) {
		await this.#bridge.send('catalog_snapshot', 'server', snapshot);
	}

	async #publishStatus() {
		if (!this.#bridge.ready) return;
		const records = this.#registry.list();
		const readyStates = new Set([DynamicAgentState.IDLE, DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.PAUSED, DynamicAgentState.COMPLETED]);
		const profiles = records
			.filter((record) => this.#supportedAgentIds.has(record.agentId))
			.map((record) => ({ agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort }))
			.sort((left, right) => left.agentId.localeCompare(right.agentId));
		const rosterReadyCount = records.filter((record) => this.#supportedAgentIds.has(record.agentId) && readyStates.has(record.state)).length;
		const healthIdentities = [...new Map(profiles.flatMap((profile) => ['create_agent', 'decide'].map((operation) => ({
			provider: profile.provider,
			model: profile.model,
			operation,
		}))).map((identity) => [JSON.stringify([identity.provider, identity.model, identity.operation]), identity])).values()]
			.sort((left, right) => left.provider.localeCompare(right.provider)
			|| left.model.localeCompare(right.model) || left.operation.localeCompare(right.operation));
		const pressure = this.#scheduler.pressureSnapshot;
		await this.#bridge.send('coordinator_status', 'server', {
			reconciled: this.#reconciledStatus,
			profiles,
			supportedProfileCount: profiles.length,
			rosterReadyCount,
			rosterCount: records.length,
			scheduler: { active: pressure.active, pending: pressure.pending, maxConcurrent: pressure.maxConcurrent, maxPending: pressure.maxPending, warning: pressure.warning },
			circuits: healthIdentities.slice(0, 32).map((identity) => this.#healthRegistry.snapshot(identity)),
			latencies: this.#latencyRegistry.snapshot(),
		});
	}

	#ledger(agentId) {
		let ledger = this.#factLedgers.get(agentId);
		if (ledger === undefined) {
			ledger = new FactLedger();
			this.#factLedgers.set(agentId, ledger);
		}
		return ledger;
	}

}

export function createDynamicCoordinator(configValue, dependencies = {}) {
	const config = normalizeDynamicConfig(configValue, dependencies.env ?? process.env);
	const registry = dependencies.registry ?? new AgentRegistry({
		agentCap: config.limits.agentCap,
		queueCap: config.limits.goalQueueCap,
		now: dependencies.now ?? Date.now,
	});
	const scheduler = dependencies.scheduler ?? new PlanningScheduler({
		maxConcurrent: config.limits.planningConcurrency,
		maxPending: Math.max(0, config.limits.agentCap - config.limits.planningConcurrency),
		onPressure: (snapshot) => dependencies.onSchedulerPressure?.(snapshot),
	});
	const workspaceManager = dependencies.workspaceManager ?? new AgentWorkspaceManager(config.workspaceRoot);
	const codexService = dependencies.providerService ?? dependencies.codexService ?? new ProviderService({
		codex: new CodexService(config.codex, { transport: dependencies.codexTransport, now: dependencies.now ?? Date.now, workspaceManager }),
		gemini: new AntigravityProviderService(config.gemini, {
			spawn: dependencies.antigravitySpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
		kimi: new AcpProviderService(config.kimi, { transportFactory: dependencies.kimiTransportFactory, workspaceManager }),
	});
	const healthRegistry = dependencies.healthRegistry ?? dependencies.planner?.healthRegistry ?? new ProviderHealthRegistry({ now: dependencies.healthNow ?? Date.now });
	const latencyRegistry = dependencies.latencyRegistry ?? new ControlLatencyRegistry();
	const planner = dependencies.planner ?? new AgentPlanner({
		registry,
		scheduler,
		codexService,
		invalidDecisionRetries: config.limits.invalidDecisionRetries,
		healthRegistry,
		telemetrySink: dependencies.telemetrySink,
	});
	const bridge = dependencies.bridge ?? new MultiplexedServerBridge(config.bridge, {
		socketFactory: dependencies.socketFactory,
		schedule: dependencies.schedule,
		cancelSchedule: dependencies.cancelSchedule,
		currentRevision: (agentId) => registry.get(agentId)?.goalRevision ?? null,
	});
	return new DynamicCoordinator({
		registry,
		scheduler,
		codexService,
		planner,
		bridge,
		healthRegistry,
		latencyRegistry,
		controlNow: dependencies.controlNow,
		epochNow: dependencies.epochNow,
		setStatusInterval: dependencies.setStatusInterval,
		clearStatusInterval: dependencies.clearStatusInterval,
	});
}

export function parseDynamicCliArguments(args) {
	if (!Array.isArray(args)) throw new TypeError('CLI arguments must be an array');
	if (args.length === 0) return { configPath: DEFAULT_DYNAMIC_CONFIG_PATH };
	if (args.length !== 2 || args[0] !== '--config') throw new Error('Usage: node coordinator/src/dynamic-main.mjs [--config <absolute-path>]');
	if (!path.isAbsolute(args[1])) throw new Error('--config must be an absolute path');
	return { configPath: args[1] };
}

export async function loadDynamicConfig(configPath = DEFAULT_DYNAMIC_CONFIG_PATH) {
	const document = JSON.parse(await readFile(configPath, 'utf8'));
	return normalizeDynamicConfig(document, process.env);
}

export function normalizeDynamicConfig(value, environment = process.env) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('dynamic coordinator config must be an object');
	if (value.bridge === null || typeof value.bridge !== 'object' || Array.isArray(value.bridge)) throw new TypeError('dynamic coordinator bridge config must be an object');
	if (value.codex === null || typeof value.codex !== 'object' || Array.isArray(value.codex)) throw new TypeError('dynamic coordinator Codex config must be an object');
	const secret = value.bridge.secret ?? environment[value.bridge.secretEnvironmentVariable ?? 'ARENA_AGENT_BRIDGE_SECRET'];
	const cwd = value.codex.cwd ?? PROJECT_DIRECTORY;
	const workspaceRoot = path.resolve(PROJECT_DIRECTORY, value.workspaceRoot ?? path.join('runtime', 'agent-workspaces'));
	const agentCap = positiveInteger(value.limits?.agentCap ?? DEFAULT_AGENT_CAP, 'limits.agentCap');
	const planningConcurrency = positiveInteger(value.limits?.planningConcurrency ?? DEFAULT_PLANNING_CONCURRENCY, 'limits.planningConcurrency');
	if (agentCap > 16) throw new TypeError('limits.agentCap must not exceed 16');
	if (planningConcurrency > agentCap) throw new TypeError('limits.planningConcurrency must not exceed limits.agentCap');
	return {
		bridge: { ...value.bridge, secret },
		workspaceRoot,
		codex: {
			...value.codex,
			cwd,
			launchProfile: value.codex.launchProfile === undefined ? undefined : { ...value.codex.launchProfile, cwd },
		},
		gemini: {
			provider: 'gemini',
			cwd,
			catalogDiscovery: true,
			models: ['gemini-3.1-pro', 'gemini-3.6-flash', 'gemini-3.5-flash'],
			modelReasoningEfforts: {
				'gemini-3.1-pro': ['high', 'low'],
				'gemini-3.6-flash': ['high', 'medium', 'low'],
				'gemini-3.5-flash': ['high', 'medium', 'low'],
			},
			...(value.gemini ?? {}),
		},
		kimi: {
			provider: 'kimi',
			cwd,
			catalogDiscovery: true,
			models: ['kimi-code/k3', 'kimi-code/k3-256k', 'kimi-code/kimi-for-coding', 'kimi-code/kimi-for-coding-highspeed'],
			reasoningEfforts: ['low', 'high', 'max'],
			...(value.kimi ?? {}),
		},
		limits: {
			agentCap,
			goalQueueCap: positiveInteger(value.limits?.goalQueueCap ?? DEFAULT_GOAL_QUEUE_CAP, 'limits.goalQueueCap'),
			planningConcurrency,
			invalidDecisionRetries: nonNegativeInteger(value.limits?.invalidDecisionRetries ?? DEFAULT_INVALID_DECISION_RETRIES, 'limits.invalidDecisionRetries'),
		},
	};
}

async function runCli() {
	const { configPath } = parseDynamicCliArguments(process.argv.slice(2));
	const coordinator = createDynamicCoordinator(await loadDynamicConfig(configPath));
	coordinator.on('runtimeError', (error) => {
		const summary = `[dynamic-coordinator] ${error?.code ?? 'ERROR'}: ${error?.message ?? String(error)}`;
		const stack = typeof error?.stack === 'string' && !error.stack.startsWith(summary)
			? `\n${error.stack}`
			: '';
		process.stderr.write(`${summary}${stack}\n`);
	});
	await coordinator.start();
	const shutdown = async () => {
		await coordinator.stop();
		process.exitCode = 0;
	};
	process.once('SIGINT', shutdown);
	process.once('SIGTERM', shutdown);
}

function requireDependency(value, name) {
	if (value === null || value === undefined) throw new TypeError(`${name} is required`);
	return value;
}

function defaultStatusInterval(callback, milliseconds) {
	const handle = setInterval(callback, milliseconds);
	handle.unref?.();
	return handle;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function nonNegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a non-negative safe integer`);
	return value;
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	runCli().catch((error) => {
		process.stderr.write(`${error?.stack ?? error}\n`);
		process.exitCode = 1;
	});
}
