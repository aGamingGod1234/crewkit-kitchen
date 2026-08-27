import { EventEmitter } from 'node:events';
import { createHash } from 'node:crypto';
import { appendFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentPlanner } from './agent-planner.mjs';
import { AgentRegistry, AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
import { ActiveGoalSupervisor } from './active-goal-supervisor.mjs';
import { AgentWorkspaceManager } from './agent-workspace.mjs';
import { MinecraftAgentWorkspace } from './minecraft-agent-workspace.mjs';
import { AcpProviderService } from './acp-service.mjs';
import { AntigravityProviderService } from './antigravity-service.mjs';
import { CodexService } from './codex-service.mjs';
import { CursorProviderService } from './cursor-service.mjs';
import { ControlLatencyRegistry } from './control-latency-registry.mjs';
import { ConversationMemory } from './conversation-memory.mjs';
import { FactLedger } from './fact-ledger.mjs';
import { ProviderService } from './provider-service.mjs';
import { ProviderTurnRecorder } from './provider-turn-recorder.mjs';
import {
	DEFAULT_AGENT_CAP,
	DEFAULT_GOAL_QUEUE_CAP,
	DEFAULT_PLANNING_CONCURRENCY,
	DEFAULT_SERVICE_TIER,
} from './constants.mjs';
import { PlanningScheduler } from './planning-scheduler.mjs';
import { MAX_VERBOSE_MESSAGE_LENGTH, MultiplexedServerBridge, ProtocolV2Error, VERBOSE_STAGES } from './protocol-v2.mjs';
import { adaptObservation } from './observation-adapter.mjs';
import { advanceContextCursor, buildPlannerInput, createContextCursor } from './prompts.mjs';
import { profileFingerprint } from './provider-session.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { NativeToolRuntime } from './native-tool-runtime.mjs';
import { classifyNativeGoalError } from './native-goal-error-policy.mjs';
import { ProgramRuntimeManager } from './program-runtime-manager.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { TraceWriter } from './trace-writer.mjs';
import { wireRuntimeDiagnostics } from './runtime-diagnostics.mjs';
import { RuntimeErrorReporter } from './runtime-error-reporter.mjs';
import { FishTtsProvider } from './voice/fish-tts-provider.mjs';
import { DeepgramSttProvider, NoSttProvider } from './voice/deepgram-stt-provider.mjs';
import { LocalSpeechProvider } from './voice/local-speech-provider.mjs';
import { createVoiceHttpServer } from './voice/voice-http-server.mjs';
import { loadPersistentVoiceProfileStore } from './voice/voice-profile-store.mjs';
import { WindowsTtsProvider } from './voice/windows-tts-provider.mjs';

const SOURCE_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const COORDINATOR_DIRECTORY = path.resolve(SOURCE_DIRECTORY, '..');
const PROJECT_DIRECTORY = path.resolve(COORDINATOR_DIRECTORY, '..');
const DEFAULT_DYNAMIC_CONFIG_PATH = path.join(COORDINATOR_DIRECTORY, 'config', 'dynamic-agents.json');
const DEFAULT_INVALID_DECISION_RETRIES = 1;
const EMPTY_TURN_RETRY_DELAY_MS = 1_000;
const GOAL_SPEC_RETRY_BASE_MS = 1_000;
const GOAL_SPEC_RETRY_MAX_MS = 30_000;
const GOAL_SPEC_PROPOSAL_RETRY_MS = 5_000;
const QUIET_RETRYABLE_PROVIDER_ERRORS = new Set(['MISSING_AGENT_MESSAGE', 'MISSING_FINAL_MESSAGE', 'REQUEST_TIMEOUT']);
const QUIET_LIFECYCLE_ERRORS = new Set(['PLAN_CANCELLED', 'STALE_PLAN', 'STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION']);
const MAX_CONVERSATION_WAKE_TRANSACTIONS = 4_096;
const MAX_PUBLIC_NARRATIVE_RAW_CHARS = 1_024;
const DEFAULT_VOICE_PORT = 8_766;
const DEFAULT_VOICE_MAX_CONCURRENT = 5;
const DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH = path.join('runtime', 'voice-profile-assignments.json');
const DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE = 'FISH_AUDIO_API_KEY';
const DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE = 'DEEPGRAM_API_KEY';
const DEFAULT_LOCAL_SPEECH_PYTHON_PATH = path.join('runtime', 'local-speech', '.venv', 'Scripts', 'python.exe');
const WINDOWS_TTS_FALLBACK_CODES = new Set([
	'TTS_AUDIO_TOO_LONG',
	'TTS_MALFORMED_AUDIO',
	'TTS_PROVIDER_ERROR',
	'TTS_RATE_LIMITED',
	'TTS_TIMEOUT',
	'TTS_UNAVAILABLE',
]);

export class DynamicCoordinator extends EventEmitter {
	#registry;
	#scheduler;
	#codexService;
	#planner;
	#bridge;
	#listeners = [];
	#agentOperations = new Map();
	#providerWork = new Map();
	#pendingAttention = new Map();
	#attentionFlushes = new Map();
	#lifecycleGenerations = new Map();
	#providerRetryAfter = new Map();
	#verboseReporters = new Set();
	#factLedgers = new Map();
	#conversationMemories = new Map();
	#contextCursors = new Map();
	#conversationWakeTransactions = new Map();
	#goalSpecRequests = new Map();
	#setGoalSpecTimeout;
	#clearGoalSpecTimeout;
	#programRuntime;
	#nativeRuntime;
	#goalSupervisor;
	#codexControlProtocol;
	#reconciliation = Promise.resolve();
	#started = false;
	#stopping = false;
	#closed = false;
	#healthRegistry;
	#latencyRegistry;
	#controlNow;
	#epochNow;
	#disconnectedAt = null;
	#supportedAgentIds = new Set();
	#reconciledStatus = false;
	#setStatusInterval;
	#clearStatusInterval;
	#statusHandle = null;
	#serverInstanceId = null;
	#traceWriter;
	#providerTurnRecorder;
	#verboseEnabled = false;

	constructor({ registry, scheduler, codexService, planner, bridge, healthRegistry, latencyRegistry, goalSupervisor, codexControlProtocol = 'native_tools', traceWriter = null, providerTurnRecorder = null, benchmarkRecorder = null, controlNow = () => performance.now(), epochNow = Date.now, setStatusInterval = defaultStatusInterval, clearStatusInterval = clearInterval, setGoalSpecTimeout = defaultGoalSpecTimeout, clearGoalSpecTimeout = clearTimeout }) {
		super();
		this.#registry = requireDependency(registry, 'registry');
		this.#scheduler = requireDependency(scheduler, 'scheduler');
		this.#codexService = requireDependency(codexService, 'codexService');
		this.#planner = requireDependency(planner, 'planner');
		this.#bridge = requireDependency(bridge, 'bridge');
		this.#healthRegistry = requireDependency(healthRegistry, 'healthRegistry');
		this.#latencyRegistry = requireDependency(latencyRegistry, 'latencyRegistry');
		this.#goalSupervisor = requireDependency(goalSupervisor, 'goalSupervisor');
		if (traceWriter !== null && typeof traceWriter.write !== 'function') throw new TypeError('traceWriter.write must be a function');
		this.#traceWriter = traceWriter;
		if (providerTurnRecorder !== null && typeof providerTurnRecorder.close !== 'function') throw new TypeError('providerTurnRecorder.close must be a function');
		this.#providerTurnRecorder = providerTurnRecorder;
		if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codexControlProtocol must be arena_script or native_tools');
		this.#codexControlProtocol = codexControlProtocol;
		if (typeof controlNow !== 'function') throw new TypeError('controlNow must be a function');
		if (typeof epochNow !== 'function') throw new TypeError('epochNow must be a function');
		this.#controlNow = controlNow;
		this.#epochNow = epochNow;
		this.#programRuntime = new ProgramRuntimeManager({
			registry: this.#registry,
			bridge: this.#bridge,
			planner: this.#planner,
			reportError: (agentId, error) => this.#reportAgentError(agentId, error),
			onCompletionRequested: (request) => this.#publishGoalCompleted(request),
			latencyRegistry: this.#latencyRegistry,
			trace: (event, fields) => this.#writeTrace(event, fields),
			plannerContext: (agentId) => this.#plannerContext(agentId),
			clock: () => this.#controlNow(),
			benchmarkRecorder,
		});
		this.#nativeRuntime = new NativeToolRuntime({
			bridge: this.#bridge,
			trace: (event, fields) => this.#writeTrace(event, fields),
			onFinish: async ({ record, result, lifecycleGeneration }) => {
				if (this.#stopping || this.#closed) return;
				const current = this.#registry.get(record.agentId);
				if (current === null || current.goalRevision !== record.goalRevision
					|| !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return;
				if (result.state === 'COMPLETED') {
					this.#goalSupervisor.terminate(this.#supervisionKey(current, lifecycleGeneration));
					this.#registry.setState(record.agentId, DynamicAgentState.COMPLETED, { goalRevision: record.goalRevision });
				}
			},
		});
		this.#setStatusInterval = requireDependency(setStatusInterval, 'setStatusInterval');
		this.#clearStatusInterval = requireDependency(clearStatusInterval, 'clearStatusInterval');
		this.#setGoalSpecTimeout = requireDependency(setGoalSpecTimeout, 'setGoalSpecTimeout');
		this.#clearGoalSpecTimeout = requireDependency(clearGoalSpecTimeout, 'clearGoalSpecTimeout');
	}

	get registry() { return this.#registry; }
	get bridge() { return this.#bridge; }

	handleLeaseExpired({ key, lease }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		this.#writeTrace('work_lease_expired', { ...key, kind: lease.kind, operationId: lease.operationId });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', `${lease.kind} work timed out; recovering automatically.`);
		if (lease.kind === 'provider') {
			const work = this.#providerWork.get(key.agentId);
			if (work?.kind === 'native' && work.goalRevision === key.goalRevision
				&& work.lifecycleGeneration === key.lifecycleGeneration
				&& work.supervisionToken?.operationId === lease.operationId) {
				work.expired = true;
				this.#providerWork.delete(key.agentId);
			}
			try {
				void Promise.resolve(this.#planner.interrupt(key.agentId, 'Provider work lease expired'))
					.catch((error) => this.#reportAgentError(key.agentId, error));
			} catch (error) {
				void this.#reportAgentError(key.agentId, error);
			}
		}
		if (['provider', 'action', 'completion'].includes(lease.kind)) {
			void this.#nativeRuntime.dispose(key.agentId, `${lease.kind}_lease_expired`)
				.catch((error) => this.#reportAgentError(key.agentId, error));
		}
	}

	handleGoalStuck({ key, inactiveMs, history }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		this.#rememberPendingAttention(key.agentId, key.goalRevision, { priority: 'urgent', trigger: 'stuck' });
		this.#writeTrace('goal_factual_progress_stuck', { ...key, inactiveMs, positionSamples: history.length });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', 'No factual world progress for 30 seconds; reassessing without pausing the goal.');
	}

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
		this.#setVerboseEnabled(false);
		if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
		this.#statusHandle = null;
		this.#bridge.stop();
		this.#unbindBridge();
		this.#scheduler.close('Dynamic coordinator stopped');
		this.#goalSupervisor.close();
		await Promise.allSettled([this.#reconciliation, ...this.#agentOperations.values()]);
		this.#agentOperations.clear();
		this.#providerWork.clear();
		this.#pendingAttention.clear();
		this.#attentionFlushes.clear();
		this.#lifecycleGenerations.clear();
		this.#programRuntime.disposeAll();
		await this.#nativeRuntime.disposeAll();
		this.#providerRetryAfter.clear();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		this.#conversationWakeTransactions.clear();
		this.#cancelGoalSpecRequests();
		if (this.#traceWriter !== null && typeof this.#traceWriter.close === 'function') await this.#traceWriter.close();
		if (this.#providerTurnRecorder !== null) await Promise.resolve(this.#providerTurnRecorder.close()).catch(() => {});
		await this.#codexService.stop();
		this.#started = false;
		this.#stopping = false;
		this.#closed = true;
	}

	#bindBridge() {
		this.#listen('ready', ({ serverInstanceId, registry }) => {
			this.#setVerboseEnabled(false);
			if (this.#serverInstanceId !== null && serverInstanceId !== this.#serverInstanceId) {
				this.#invalidateServerInstance();
			}
			this.#serverInstanceId = serverInstanceId;
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			const startedReconciliation = this.#planner.beginReconcile(registry);
			const reconciliation = Promise.resolve().then(async () => {
			if (typeof this.#codexService.bootstrapCatalog === 'function') {
				await this.#publishCatalog(await this.#codexService.bootstrapCatalog());
			}
			const reconciliation = await startedReconciliation.complete;
			const providers = reconciliation.providers ?? reconciliation.codex;
			await this.#publishCatalog(providers.catalog);
			for (const profile of providers.valid) {
				let record = this.#registry.get(profile.agentId);
				if (record === null) throw new ProtocolV2Error('UNKNOWN_AGENT', `Reconciled provider profile references unknown agent '${profile.agentId}'`);
				if (this.#usesNativeTools(record) && record.state === DynamicAgentState.DISCONNECTED && record.currentGoal !== null) {
					record = this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				await this.#bridge.send('agent_ready', profile.agentId, { goalRevision: record.goalRevision, reconciled: true });
				this.#supportedAgentIds.add(profile.agentId);
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent reconciled and ready.');
				this.#prewarmNativeAgent(record);
				if (record.state === DynamicAgentState.DEAD) await this.#installDeadStatePlan(record, record.death);
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
			this.#reconciliation = reconciliation.catch((error) => {
				this.#emitRuntimeError(error);
				return null;
			});
		});
		this.#listen('verbose_control', (message) => {
			this.#setVerboseEnabled(message.payload.enabled);
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
			this.#codexService.catalog.assertSupported(record.provider, record.model, record.reasoningEffort, record.serviceTier ?? DEFAULT_SERVICE_TIER);
			await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
			this.#supportedAgentIds.add(record.agentId);
			this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent registered and ready.');
			this.#prewarmNativeAgent(record);
			await this.#publishStatus();
		}));
		this.#listen('agent_removed', (message) => this.#run(async () => {
			await this.#reconciliation;
			this.#publishVerbose(message.agentId, message.payload.goalRevision, 'lifecycle', 'Agent removed from the coordinator roster.');
			const current = this.#registry.get(message.agentId);
			if (current !== null && this.#usesNativeTools(current)) this.#goalSupervisor.terminate(this.#supervisionKey(current));
			this.#programRuntime.dispose(message.agentId);
			await this.#nativeRuntime.dispose(message.agentId, 'agent_removed');
			this.#providerWork.delete(message.agentId);
			this.#pendingAttention.delete(message.agentId);
			this.#attentionFlushes.delete(message.agentId);
			await this.#planner.remove(message.agentId);
			this.#lifecycleGenerations.delete(message.agentId);
			this.#providerRetryAfter.delete(message.agentId);
			this.#factLedgers.delete(message.agentId);
			this.#conversationMemories.delete(message.agentId);
			this.#contextCursors.delete(message.agentId);
			this.#forgetConversationWakes(message.agentId);
			this.#cancelGoalSpecRequests(message.agentId);
			this.#supportedAgentIds.delete(message.agentId);
			await this.#publishStatus();
		}));
		this.#listen('goal_spec_request', (message) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const fingerprint = JSON.stringify(message.payload);
			const existing = this.#goalSpecRequests.get(key);
			if (existing !== undefined) {
				if (existing.fingerprint !== fingerprint) {
					this.#emitRuntimeError(new ProtocolV2Error('TRANSACTION_COLLISION', `Goal translation request '${message.payload.requestId}' changed during replay`));
					return;
				}
				if (existing.proposal !== null) {
					void this.#bridge.send('goal_spec_proposal', message.agentId, existing.proposal).catch((error) => this.#emitRuntimeError(error));
				}
				return;
			}
			const entry = {
				agentId: message.agentId, requestId: message.payload.requestId, request: message.payload,
				fingerprint, proposal: null, attempts: 0, translating: false, retryHandle: null,
			};
			this.#goalSpecRequests.set(key, entry);
			this.#run(() => this.#processGoalSpecRequest(key, entry));
		});
		this.#listen('goal_spec_result', (message) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const existing = this.#goalSpecRequests.get(key);
			if (existing === undefined) return;
			this.#goalSpecRequests.delete(key);
			if (existing.retryHandle !== null) this.#clearGoalSpecTimeout(existing.retryHandle);
			this.#planner.cancelGoalSpec?.(message.agentId, message.payload.requestId);
		});
		this.#listen('goal_control', (message) => {
			const previous = this.#registry.get(message.agentId);
			let record;
			try {
				record = this.#registry.applyGoalControl(message.agentId, message.payload);
			} catch (error) {
				void this.#reportAgentError(message.agentId, error);
				return;
			}
			const lifecycleChanged = previous !== null && message.payload.operation !== 'queue'
				&& (record.goalRevision > previous.goalRevision
					|| (record.goalRevision === previous.goalRevision && ['dead', 'respawn'].includes(message.payload.operation)));
			let nativeDisposal = Promise.resolve();
			if (lifecycleChanged) {
				this.#cancelGoalSpecRequests(message.agentId);
				this.#retireGoalSupervision(previous, message.payload.operation);
				this.#invalidateAcceptedLifecycle(message.agentId);
				this.#beginGoalControlInterruption(message);
				this.#programRuntime.onGoalControl(previous, message.payload.operation);
				nativeDisposal = Promise.resolve(this.#nativeRuntime.dispose(previous.agentId, `goal_${message.payload.operation}`));
			}
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			this.#enqueueAgent(message.agentId, async () => {
				await nativeDisposal;
				const acceptedStillCurrent = () => {
					const current = this.#registry.get(message.agentId);
					return current !== null && current.goalRevision === record.goalRevision
						&& this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration);
				};
				if (!acceptedStillCurrent()) return;
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', `Goal lifecycle operation '${message.payload.operation}' accepted.`);
				if (message.payload.operation !== 'queue') {
					this.#providerRetryAfter.delete(message.agentId);
				}
				if (message.payload.operation === 'dead') {
					void this.#installDeadStatePlan(record, message.payload.death).catch((error) => this.#reportAgentError(record.agentId, error));
				}
				const resumesGoal = ['start', 'resume', 'steer'].includes(message.payload.operation)
					|| message.payload.operation === 'respawn' && record.state === DynamicAgentState.STARTING;
				const activatesQueuedGoal = message.payload.operation === 'complete' && record.state === DynamicAgentState.STARTING;
				if ((resumesGoal || activatesQueuedGoal) && this.#usesNativeTools(record)) {
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				if (resumesGoal) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, {
						priority: message.payload.operation === 'steer' ? 'urgent' : 'ordinary',
						trigger: message.payload.operation,
					});
				}
				if (resumesGoal) {
					await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision });
				}
				if (!acceptedStillCurrent()) return;
				this.emit('goalControl', record);
			});
		});
		this.#listen('conversation_event', (message) => {
			this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.goalRevision, 'conversation', `Conversation event '${message.payload.kind}' received from '${message.payload.sourceId}'.`);
				this.#conversationMemory(message.agentId).ingest(message.payload);
				const record = this.#registry.get(message.agentId);
				if (record !== null) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation' });
					if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload, 'conversation');
					else this.#schedulePendingAttentionFlush(record);
				}
				this.emit('conversationEvent', message);
			}, { waitForReconciliation: false });
		});
		this.#listen('conversation_wake', (message) => {
			this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.event.goalRevision, 'conversation', `Conversation wake '${message.payload.event.kind}' received.`);
				const fingerprint = JSON.stringify({ agentId: message.agentId, event: message.payload.event, control: message.payload.control });
				const existing = this.#conversationWakeTransactions.get(message.payload.transactionId);
				if (existing !== undefined) {
					if (existing.fingerprint !== fingerprint) {
						throw new ProtocolV2Error('TRANSACTION_COLLISION', `Conversation wake '${message.payload.transactionId}' changed during replay`);
					}
					const record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
					if (this.#usesNativeTools(record)) this.#goalSupervisor.activate(this.#supervisionKey(record));
					this.#providerRetryAfter.delete(message.agentId);
					this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation_wake' });
					if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload.event, 'conversation_wake');
					else this.#schedulePendingAttentionFlush(record);
					await this.#bridge.send('conversation_wake_ack', message.agentId, {
						transactionId: message.payload.transactionId,
						goalRevision: record.goalRevision,
					});
					await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision });
					return;
				}
				const previous = this.#registry.get(message.agentId);
				let record;
				try {
					record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
				} catch (error) {
					if (error instanceof AgentRegistryError && ['STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION', 'INVALID_AGENT_STATE'].includes(error.code)) return;
					throw error;
				}
				if (previous !== null && record.goalRevision > previous.goalRevision) {
					this.#retireGoalSupervision(previous, 'start');
					this.#invalidateAcceptedLifecycle(message.agentId);
					this.#programRuntime.onGoalControl(previous, 'start');
					await this.#nativeRuntime.dispose(previous.agentId, 'conversation_wake');
				}
				this.#conversationMemory(message.agentId).ingest(message.payload.event);
				if (this.#usesNativeTools(record)) this.#goalSupervisor.activate(this.#supervisionKey(record));
				this.#providerRetryAfter.delete(message.agentId);
				this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation_wake' });
				if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload.event, 'conversation_wake');
				else this.#schedulePendingAttentionFlush(record);
				this.#rememberConversationWake(message.payload.transactionId, message.agentId, fingerprint);
				await this.#bridge.send('conversation_wake_ack', record.agentId, {
					transactionId: message.payload.transactionId,
					goalRevision: record.goalRevision,
				});
				await this.#bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision });
				this.emit('conversationEvent', { ...message, payload: message.payload.event });
				this.emit('goalControl', record);
			}, { waitForReconciliation: false });
		});
		this.#listen('observation', (message) => {
			const receiptMonotonicMs = safeClockRead(this.#controlNow);
			const receiptEpochMs = safeClockRead(this.#epochNow);
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			this.#enqueueAgent(message.agentId, async () => {
				if (!this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration)) return;
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				if (![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
				const wireObservation = message.payload.observation ?? message.payload;
				if (this.#usesNativeTools(record)) {
					const supervisionKey = this.#supervisionKey(record, lifecycleGeneration);
					this.#goalSupervisor.observed(supervisionKey);
					this.#goalSupervisor.factualProgress(supervisionKey, factualProgressSignature(wireObservation), factualProgressDetails(wireObservation));
				}
				const observation = adaptObservation(wireObservation);
				const classified = classifyObservationTrigger(message.payload, wireObservation);
				const pendingAttention = this.#pendingAttention.get(record.agentId);
				const attention = pendingAttention?.goalRevision === record.goalRevision
					? mergeAttentionTrigger(classified, pendingAttention)
					: classified;
				if (pendingAttention?.goalRevision === record.goalRevision) this.#pendingAttention.delete(record.agentId);
				const ledger = this.#ledger(record.agentId);
				ledger.ingest('observation', wireObservation);
				if (this.#usesNativeTools(record)) {
					const conversation = this.#conversationMemory(record.agentId).delta(null).entries.slice(-4);
					this.#nativeRuntime.updateObservation(record, observation, { eventSequence: message.payload.eventSequence, conversation });
					this.#scheduleNativeTurn(record, {
						agentId: record.agentId,
						goalRevision: record.goalRevision,
						observation,
						eventSequence: message.payload.eventSequence,
						priority: attention.priority,
						trigger: attention.trigger,
						lifecycleGeneration,
						input: buildNativeEventInput(record, {
							event: 'observation', trigger: attention.trigger, observation, conversation,
						}),
					});
					return;
				}
				const installed = await this.#programRuntime.onObservation(record, {
					observation,
					eventSequence: message.payload.eventSequence,
					attention: attention.attention,
					priority: attention.priority,
					trigger: attention.trigger,
					receiptMonotonicMs,
					receiptEpochMs,
					observedAtEpochMs: message.payload.observedAtEpochMs,
				});
				if (installed !== null) return;
				this.#scheduleInitialPlan(record, {
					agentId: record.agentId,
					goalRevision: record.goalRevision,
					observation,
					wireObservation,
					eventSequence: message.payload.eventSequence,
					receiptMonotonicMs,
					attention: attention.attention,
					priority: attention.priority,
					trigger: attention.trigger,
					preserveState: false,
					kind: 'initial',
					lifecycleGeneration,
					input: buildPlannerInput({
						agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
						goal: record.currentGoal,
						goalRevision: record.goalRevision,
						attentionPriority: attention.priority,
						attentionTrigger: attention.trigger,
						observation,
					}, {
						untrustedFacts: ledger.toPlannerFacts(),
						conversationContext: this.#conversationMemory(record.agentId).toPlannerContext(),
					}),
				});
			});
		});
		this.#listen('action_progress', (message) => {
			this.#enqueueAgent(message.agentId, async () => {
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				const nativeWork = this.#providerWork.get(message.agentId);
				if (this.#usesNativeTools(record) && nativeWork?.goalRevision === record.goalRevision) {
					this.#goalSupervisor.progress(nativeWork.supervisionToken);
					if (nativeWork.toolSupervisionToken !== null) this.#goalSupervisor.progress(nativeWork.toolSupervisionToken);
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionProgress(record, message.payload)) {
					this.emit('actionProgress', message);
					return;
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) return;
				if (!this.#programRuntime.onActionProgress(record, message.payload)) throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
				this.emit('actionProgress', message);
			});
		});
		this.#listen('action_result', (message) => {
			this.#enqueueAgent(message.agentId, async () => {
			const current = this.#registry.get(message.agentId);
			if (current === null || message.payload.goalRevision !== current.goalRevision) return;
			const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
			this.#ledger(record.agentId).ingest('action_result', message.payload);
			if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionResult(record, message.payload)) {
				this.emit('actionResult', message);
				return;
			}
			if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) return;
			if (!await this.#programRuntime.onActionResult(record, message.payload)) {
				if (this.#programRuntime.isActionResultStale(record, message.payload)) return;
				throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
			}
			this.emit('actionResult', message);
			});
		});
		this.#listen('goal_completion_result', (message) => {
			this.#enqueueAgent(message.agentId, async () => {
				const current = this.#registry.get(message.agentId);
				if (current === null || current.goalRevision !== message.payload.goalRevision) return;
				if (this.#usesNativeTools(current) && this.#nativeRuntime.onCompletionResult(current, message.payload)) return;
				const accepted = this.#programRuntime.onCompletionResult(current, message.payload);
				if (!accepted) throw new ProtocolV2Error('UNEXPECTED_COMPLETION_RESULT', `Agent '${message.agentId}' has no matching completion request`);
			});
		});
		this.#listen('disconnected', () => {
			this.#setVerboseEnabled(false);
			this.#cancelGoalSpecRequests();
			for (const record of this.#registry.list()) {
				if (this.#usesNativeTools(record)) this.#goalSupervisor.suspend(this.#supervisionKey(record));
				this.#advanceLifecycleGeneration(record.agentId);
			}
			this.#run(async () => {
			this.#disconnectedAt ??= safeClockRead(this.#controlNow);
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#programRuntime.disposeAll();
			void this.#nativeRuntime.disposeAll('bridge_disconnected');
			this.#pendingAttention.clear();
			this.#attentionFlushes.clear();
			this.#providerRetryAfter.clear();
			this.#providerWork.clear();
			await Promise.allSettled(this.#registry.list().map(async (record) => {
				if (![DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED].includes(record.state)) this.#registry.setState(record.agentId, DynamicAgentState.DISCONNECTED, { goalRevision: record.goalRevision });
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

	async #installDeadStatePlan(record, death) {
		if (death === null || death === undefined) throw new ProtocolV2Error('MISSING_FIELD', `DEAD agent '${record.agentId}' requires death facts`);
		if (record.currentGoal === null) return;
		if (this.#programRuntime.hasCurrent(record)) return;
		const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
		if (this.#usesNativeTools(record)) {
			const conversation = this.#conversationMemory(record.agentId).delta(null).entries.slice(-4);
			const observation = { death: structuredClone(death) };
			this.#nativeRuntime.updateObservation(record, observation, { eventSequence: 0, conversation });
			return this.#scheduleNativeTurn(record, {
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				observation,
				eventSequence: 0,
				priority: 'urgent',
				trigger: 'player_death',
				preserveState: true,
				lifecycleGeneration,
				input: buildNativeEventInput(record, { event: 'player_death', trigger: 'player_death', observation, conversation }),
			});
		}
		return this.#scheduleProviderPlan(record, {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			observation: { death },
			eventSequence: 0,
			attention: true,
			priority: 'urgent',
			trigger: 'player_death',
			preserveState: true,
			kind: 'death',
			lifecycleGeneration,
			input: buildPlannerInput({
				agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
				goal: record.currentGoal,
				goalRevision: record.goalRevision,
				decisionContext: 'player_death',
				attentionPriority: 'urgent',
				attentionTrigger: 'player_death',
				death,
			}),
		}, { preserveState: true, kind: 'death' });
	}

	#usesNativeTools(record) {
		return this.#codexControlProtocol === 'native_tools' && record?.provider === 'codex';
	}

	#prewarmNativeAgent(record) {
		if (!this.#usesNativeTools(record) || record.state !== DynamicAgentState.IDLE || typeof this.#codexService.prewarmAgent !== 'function') return;
		void Promise.resolve(this.#codexService.prewarmAgent(record, { goalRevision: record.goalRevision })).catch((error) => {
			this.#writeTrace('native_prewarm_failed', {
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				errorCode: String(error?.code ?? 'PREWARM_FAILED').slice(0, 128),
			});
		});
	}

	#scheduleNativeConversation(record, event, trigger) {
		const conversationOnly = [DynamicAgentState.IDLE, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED].includes(record.state);
		if (!conversationOnly && ![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
		const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
		const conversation = this.#conversationMemory(record.agentId).delta(null).entries.slice(-4);
		this.#scheduleNativeTurn(record, {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			priority: 'urgent',
			trigger,
			lifecycleGeneration,
			preserveState: conversationOnly,
			conversationOnly,
			input: buildNativeEventInput(record, {
				event: event?.kind ?? 'conversation',
				trigger,
				observation: {},
				conversation,
				conversationOnly,
			}),
		});
	}

	#scheduleNativeTurn(record, request) {
		if (!this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (
				existing.kind === 'native'
				&& existing.goalRevision === request.goalRevision
				&& existing.lifecycleGeneration === request.lifecycleGeneration
				&& request.priority === 'urgent'
			) {
				this.#queueNativeSteer(existing, request);
				return existing.promise;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return existing.promise;
		}
		const supervisionKey = this.#supervisionKey(record, request.lifecycleGeneration);
		this.#goalSupervisor.activate(supervisionKey);
		const work = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration: request.lifecycleGeneration,
			kind: 'native',
			request,
			pending: null,
			steerQueued: null,
			steerPromise: null,
			traceId: planningTraceId(record.agentId, record.goalRevision, request.lifecycleGeneration, 'native'),
			promise: null,
			supervisionKey,
			supervisionToken: this.#goalSupervisor.begin(supervisionKey, 'provider'),
			toolSupervisionToken: null,
			expired: false,
		};
		this.#providerWork.set(record.agentId, work);
		try {
			if (request.preserveState !== true) {
				if (record.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
				void this.#bridge.send('planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING })
					.catch((error) => this.#reportAgentError(record.agentId, error));
			}
		} catch (error) {
			this.#providerWork.delete(record.agentId);
			this.#goalSupervisor.end(work.supervisionToken);
			throw error;
		}
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision, { allowPublicAgentMessage: true });
		work.promise = Promise.resolve()
			.then(() => this.#planner.requestNativeTurn({
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				input: request.input,
				recoverySummary: record.lastSummary,
				priority: request.priority,
				preserveState: request.preserveState === true,
				traceId: work.traceId,
				onVerbose: verboseReporter,
				executeTool: (toolRequest) => this.#executeNativeTool(work, toolRequest),
			}))
			.then((result) => this.#completeNativeTurn(work, result), (error) => this.#failNativeTurn(work, error))
			.finally(() => verboseReporter.dispose());
		return work.promise;
	}

	#queueNativeSteer(work, request) {
		work.steerQueued = mergePlannerRequest(work.steerQueued, request);
		if (work.steerPromise !== null) return;
		const steering = this.#drainNativeSteering(work);
		const tracked = steering.finally(() => {
			if (work.steerPromise === tracked) work.steerPromise = null;
		});
		work.steerPromise = tracked;
	}

	async #drainNativeSteering(work) {
		while (work.steerQueued !== null) {
			const request = work.steerQueued;
			work.steerQueued = null;
			try {
				await this.#planner.steerNativeTurn({
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					input: request.input,
				});
				this.#writeTrace('native_turn_steered', {
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					traceId: work.traceId,
					trigger: request.trigger,
				});
			} catch (error) {
				work.pending = mergePlannerRequest(work.pending, request);
				if (work.steerQueued !== null) work.pending = mergePlannerRequest(work.pending, work.steerQueued);
				work.steerQueued = null;
				this.#writeTrace('native_turn_steer_deferred', {
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					traceId: work.traceId,
					errorCode: String(error?.code ?? 'TURN_STEER_FAILED').slice(0, 128),
				});
				return;
			}
		}
	}

	async #settleNativeSteering(work) {
		while (work.steerPromise !== null) await work.steerPromise;
	}

	async #executeNativeTool(work, toolRequest) {
		const record = this.#registry.get(work.agentId);
		if (work.expired === true || record === null || record.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			throw Object.assign(new Error('Native tool belongs to an obsolete goal'), { code: 'STALE_PLAN' });
		}
		if (work.request.conversationOnly === true
				&& toolRequest.tool.kind !== 'observe'
				&& !(toolRequest.tool.kind === 'action' && toolRequest.tool.actionType === 'chat')) {
			throw Object.assign(new Error('Idle conversation turns may only observe and reply with chat'), { code: 'CONVERSATION_ONLY' });
		}
		const executesBody = toolRequest.tool.kind === 'action' || toolRequest.tool.kind === 'sequence';
		const supervisionKind = executesBody ? 'action' : toolRequest.tool.kind === 'finish' ? 'completion' : null;
		let supervisionToken = null;
		let result;
		try {
			if (executesBody && record.state === DynamicAgentState.PLANNING) {
				this.#registry.setState(record.agentId, DynamicAgentState.ACTING, { goalRevision: record.goalRevision });
			}
			supervisionToken = supervisionKind === null ? null : this.#goalSupervisor.begin(work.supervisionKey, supervisionKind);
			work.toolSupervisionToken = supervisionToken;
			this.#goalSupervisor.progress(work.supervisionToken);
			result = await this.#nativeRuntime.execute(toolRequest, record, { lifecycleGeneration: work.lifecycleGeneration });
			return result;
		} finally {
			if (supervisionToken !== null) {
				this.#goalSupervisor.end(supervisionToken, { progress: ['SUCCEEDED', 'COMPLETED'].includes(result?.state) });
			}
			if (work.toolSupervisionToken === supervisionToken) work.toolSupervisionToken = null;
			this.#goalSupervisor.progress(work.supervisionToken);
			const latest = this.#registry.get(work.agentId);
			if (!this.#stopping && !this.#closed && executesBody && latest?.goalRevision === work.goalRevision && latest.state === DynamicAgentState.ACTING
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
				this.#registry.setState(latest.agentId, DynamicAgentState.PLANNING, { goalRevision: latest.goalRevision });
			}
		}
	}

	async #completeNativeTurn(work, result) {
		try {
			await this.#settleNativeSteering(work);
		} finally {
			this.#goalSupervisor.end(work.supervisionToken, { progress: (result?.toolCalls ?? 0) > 0 });
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		if (record !== null && record.goalRevision === work.goalRevision && this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			this.#writeTrace('native_turn_completed', { agentId: work.agentId, goalRevision: work.goalRevision, traceId: work.traceId, toolCalls: result?.toolCalls ?? 0 });
		}
		if (work.request.conversationOnly === true && (result?.toolCalls ?? 0) === 0
				&& work.request.conversationRetry !== true && record !== null) {
			this.#scheduleNativeTurn(record, {
				...work.request,
				conversationRetry: true,
				input: `${work.request.input}\nYour previous turn made no visible reply. Call say exactly once now.`,
			});
			return result;
		}
		const rescheduled = this.#reschedulePendingNativeTurn(pending);
		if (!rescheduled && this.#isActiveNativeGoal(work)) {
			this.#goalSupervisor.ensure(work.supervisionKey, (result?.toolCalls ?? 0) === 0 ? 'zero_tool_turn' : 'turn_completed');
		}
		return result;
	}

	async #failNativeTurn(work, error) {
		let classification = classifyNativeGoalError(error);
		try {
			await this.#settleNativeSteering(work);
		} catch (steeringError) {
			error = steeringError;
			classification = classifyNativeGoalError(error);
		} finally {
			this.#goalSupervisor.end(work.supervisionToken, { scheduleRecovery: classification !== 'stale' });
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		const staleLifecycle = record?.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		if (staleLifecycle || classification === 'stale' || this.#stopping || this.#closed) {
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		await this.#nativeRuntime.dispose(work.agentId, 'native_turn_failed');
		if (classification === 'terminal') {
			this.#goalSupervisor.terminate(work.supervisionKey);
			if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) {
				this.#registry.setState(work.agentId, DynamicAgentState.ERROR, {
					goalRevision: work.goalRevision,
					error: { code: String(error?.code ?? 'NATIVE_TURN_FAILED').slice(0, 128), message: String(error?.message ?? error).slice(0, 2_048) },
				});
			}
			await this.#reportAgentError(work.agentId, error);
		} else {
			this.#goalSupervisor.recover(work.supervisionKey, { errorCode: error?.code ?? 'NATIVE_TURN_FAILED' });
			this.#reschedulePendingNativeTurn(pending);
		}
		return null;
	}

	#reschedulePendingNativeTurn(request) {
		if (request === null || request === undefined || this.#stopping || this.#closed) return false;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision || !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return false;
		if (!this.#usesNativeTools(record) || ![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state)) return false;
		this.#scheduleNativeTurn(record, request);
		return true;
	}

	#isActiveNativeGoal(work) {
		if (this.#stopping || this.#closed) return false;
		const record = this.#registry.get(work.agentId);
		return record !== null
			&& record.goalRevision === work.goalRevision
			&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)
			&& this.#usesNativeTools(record)
			&& [DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state);
	}

	#scheduleInitialPlan(record, request) {
		if (!this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== request.lifecycleGeneration) {
				existing.pending = mergePlannerRequest(existing.pending, request);
				return;
			}
			if (request.priority === 'urgent' && existing.request.priority !== 'urgent' && this.#scheduler.pendingAgentIds?.includes(record.agentId)) {
				// Retain the urgent payload before cancellation settles. The scheduler
				// may reject the queued turn immediately, so interruption alone cannot
				// be the handoff mechanism for the replacement request.
				existing.pending = mergePlannerRequest(existing.pending, request);
				void Promise.resolve(this.#planner.interrupt(record.agentId, 'Urgent planning trigger')).catch(() => {});
				return;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return;
		}
		if (this.#scheduler.hasScheduled(record.agentId)) return;
		if (request.priority !== 'urgent' && request.receiptMonotonicMs !== null && (this.#providerRetryAfter.get(record.agentId) ?? 0) > request.receiptMonotonicMs) return;
		void this.#bridge.send('planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING }).catch((error) => this.#reportAgentError(record.agentId, error));
		void this.#scheduleProviderPlan(record, request, { preserveState: false, kind: 'initial' });
	}

	#scheduleProviderPlan(record, request, { preserveState = false, kind = 'initial' } = {}) {
		const lifecycleGeneration = request.lifecycleGeneration ?? this.#lifecycleGeneration(record.agentId);
		if (!this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return Promise.resolve(null);
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== lifecycleGeneration) {
				existing.pending = mergePlannerRequest(existing.pending, request);
				return existing.promise;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return existing.promise;
		}
		const work = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration,
			kind,
			preserveState,
			request,
			contextSnapshot: this.#contextSnapshot(record),
			pending: null,
			traceId: request.traceId ?? planningTraceId(record.agentId, record.goalRevision, lifecycleGeneration, kind),
			promise: null,
		};
		this.#providerWork.set(record.agentId, work);
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision);
		const providerRequest = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			preserveState,
			recoverySummary: record.lastSummary,
			input: request.input,
			traceId: work.traceId,
			planningPriority: request.priority,
			priority: request.priority,
			onVerbose: verboseReporter,
		};
		work.promise = Promise.resolve()
			.then(() => this.#planner.requestPlan(providerRequest))
			.then((decision) => this.#completeProviderPlan(work, decision), (error) => this.#failProviderPlan(work, record, error))
			.finally(() => verboseReporter.dispose());
		return work.promise;
	}

	async #completeProviderPlan(work, decision) {
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const record = this.#registry.get(work.agentId);
		if (record === null || record.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			const pending = work.pending;
			this.#providerWork.delete(work.agentId);
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		let runtime = null;
		try {
			runtime = await this.#programRuntime.installDecision(record, decision, {
				observation: work.request.observation,
				eventSequence: work.request.eventSequence,
				traceId: work.traceId,
			});
		} catch (error) {
			this.#providerWork.delete(work.agentId);
			const current = this.#registry.get(work.agentId);
			const stale = current?.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
			if (!stale) await this.#reportAgentError(work.agentId, error);
			if (stale || work.pending?.priority === 'urgent') this.#reschedulePendingProviderPlan(work.pending);
			return null;
		}
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		this.#publishVerbose(record.agentId, record.goalRevision, 'decision', verboseDecisionSummary(decision));
		if (runtime === null) return runtime;
		const latest = this.#registry.get(work.agentId);
		if (latest === null || latest.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) return runtime;
		this.#rememberAcceptedContextCursor(latest, work.contextSnapshot);
		this.#flushPendingAttention(latest);
		if (pending === null) return runtime;
		try {
			if (pending.observation !== undefined) {
				await this.#programRuntime.onObservation(latest, {
					observation: pending.observation,
					eventSequence: pending.eventSequence,
					attention: pending.attention,
					priority: pending.priority,
					trigger: pending.trigger,
				});
			} else if (pending.attention) {
				this.#programRuntime.notifyAttention(latest, { priority: pending.priority, trigger: pending.trigger });
			}
		} catch (error) {
			await this.#reportAgentError(work.agentId, error);
		}
		return runtime;
	}

	async #failProviderPlan(work, record, error) {
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const current = this.#registry.get(work.agentId);
		const stale = current?.goalRevision !== work.goalRevision || !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		this.#promotePendingAttention(work, current);
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		const urgentRecovery = pending?.priority === 'urgent';
		const quietRetry = QUIET_RETRYABLE_PROVIDER_ERRORS.has(error?.code);
		if (!stale && !urgentRecovery) await this.#reportAgentError(record.agentId, error);
		if (!stale && quietRetry && current?.state === DynamicAgentState.ERROR) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (stateError) {
				void this.#reportAgentError(record.agentId, stateError);
				return null;
			}
		}
		if (urgentRecovery || stale) this.#reschedulePendingProviderPlan(pending);
		return null;
	}

	#promotePendingAttention(work, record) {
		const attention = this.#pendingAttention.get(work.agentId);
		if (record === null || attention?.goalRevision !== work.goalRevision) return;
		const request = work.request;
		const input = buildPlannerInput({
			agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
			goal: record.currentGoal,
			goalRevision: record.goalRevision,
			attentionPriority: 'urgent',
			attentionTrigger: attention.trigger,
			observation: request.observation,
		}, {
			untrustedFacts: this.#ledger(record.agentId).toPlannerFacts(),
			conversationContext: this.#conversationMemory(record.agentId).toPlannerContext(),
		});
		work.pending = mergePlannerRequest(work.pending, {
			...request,
			attention: true,
			priority: 'urgent',
			trigger: attention.trigger,
			preserveState: false,
			input,
		});
		this.#pendingAttention.delete(work.agentId);
	}

	#reschedulePendingProviderPlan(request) {
		if (request === null || request === undefined) return;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision || !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		if (record.state === DynamicAgentState.ERROR && request.preserveState !== true) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (error) {
				void this.#reportAgentError(record.agentId, error);
				return;
			}
		}
		void this.#scheduleProviderPlan(record, request, { preserveState: request.preserveState === true, kind: request.kind ?? 'initial' });
	}

	#unbindBridge() {
		for (const [event, listener] of this.#listeners) this.#bridge.off(event, listener);
		this.#listeners = [];
	}

	#beginGoalControlInterruption(message) {
		let reason = null;
		if (['stop', 'disconnect', 'dead'].includes(message.payload.operation)) reason = `Goal ${message.payload.operation}`;
		if (message.payload.operation === 'steer') reason = 'Goal steered';
		if (reason === null) return;
		try {
			Promise.resolve(this.#planner.interrupt(message.agentId, reason)).catch((error) => this.#reportAgentError(message.agentId, error));
		} catch (error) {
			void this.#reportAgentError(message.agentId, error);
		}
	}

	#lifecycleGeneration(agentId) {
		return this.#lifecycleGenerations.get(agentId) ?? 0;
	}

	#supervisionKey(record, lifecycleGeneration = this.#lifecycleGeneration(record.agentId)) {
		return { agentId: record.agentId, goalRevision: record.goalRevision, lifecycleGeneration };
	}

	#retireGoalSupervision(record, operation) {
		if (!this.#usesNativeTools(record)) return;
		const key = this.#supervisionKey(record);
		if (['disconnect', 'dead'].includes(operation)) this.#goalSupervisor.suspend(key);
		else this.#goalSupervisor.terminate(key);
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
		this.#invalidateAcceptedLifecycle(message.agentId);
	}

	#invalidateAcceptedLifecycle(agentId) {
		this.#pendingAttention.delete(agentId);
		this.#attentionFlushes.delete(agentId);
		this.#contextCursors.delete(agentId);
		this.#advanceLifecycleGeneration(agentId);
	}

	#rememberPendingAttention(agentId, goalRevision, attention) {
		const previous = this.#pendingAttention.get(agentId);
		if (previous === undefined || previous.goalRevision !== goalRevision) {
			this.#pendingAttention.set(agentId, { goalRevision, ...attention });
			return;
		}
		const merged = mergeAttentionTrigger(previous, attention);
		this.#pendingAttention.set(agentId, { goalRevision, ...merged });
	}

	#schedulePendingAttentionFlush(record) {
		if (!this.#programRuntime.hasCurrent(record)) return;
		const existing = this.#attentionFlushes.get(record.agentId);
		if (existing?.goalRevision === record.goalRevision) return;
		const token = { goalRevision: record.goalRevision };
		this.#attentionFlushes.set(record.agentId, token);
		setImmediate(() => {
			if (this.#attentionFlushes.get(record.agentId) !== token) return;
			this.#attentionFlushes.delete(record.agentId);
			if (this.#stopping || this.#closed) return;
			const current = this.#registry.get(record.agentId);
			const pending = this.#pendingAttention.get(record.agentId);
			if (current === null || pending?.goalRevision !== token.goalRevision || current.goalRevision !== token.goalRevision) return;
			this.#flushPendingAttention(current);
		});
	}

	#flushPendingAttention(record) {
		const pending = this.#pendingAttention.get(record.agentId);
		if (pending?.goalRevision !== record.goalRevision || !this.#programRuntime.hasCurrent(record)) return;
		try {
			const notified = this.#programRuntime.notifyAttention(record, { priority: pending.priority, trigger: pending.trigger });
			if (notified !== null) this.#pendingAttention.delete(record.agentId);
		} catch (error) {
			void this.#reportAgentError(record.agentId, error);
		}
	}

	#isLifecycleGenerationCurrent(agentId, generation) {
		return this.#lifecycleGeneration(agentId) === generation;
	}

	#enqueueAgent(agentId, operation, { waitForReconciliation = true } = {}) {
		const previous = this.#agentOperations.get(agentId) ?? Promise.resolve();
		const current = previous.catch(() => {})
			.then(() => waitForReconciliation ? this.#reconciliation : undefined)
			.then(operation);
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
		try {
			const verboseRecord = this.#registry.get(agentId);
			if (QUIET_LIFECYCLE_ERRORS.has(error?.code)) return;
			if (QUIET_RETRYABLE_PROVIDER_ERRORS.has(error?.code)) {
				// App-server transport silence is retried from the next fresh observation.
				// It is not a world-action failure that the player or agent must repair.
				if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'retry', 'Provider output was incomplete; retrying from the next fresh observation.');
				const retryAt = safeClockRead(this.#controlNow);
				if (retryAt === null) this.#providerRetryAfter.delete(agentId);
				else this.#providerRetryAfter.set(agentId, retryAt + EMPTY_TURN_RETRY_DELAY_MS);
				return;
			}
			if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'error', verboseErrorMessage(error));
			this.#emitRuntimeError(error);
			if (!this.#bridge.ready || !this.#registry.has(agentId)) return;
			const record = this.#registry.get(agentId);
			try {
				await this.#bridge.send('agent_error', agentId, {
					goalRevision: record.goalRevision,
					code: String(error?.code ?? 'COORDINATOR_ERROR').slice(0, 128),
					message: String(error?.message ?? error).slice(0, 2_048),
				});
			} catch (reportError) {
				if (!QUIET_LIFECYCLE_ERRORS.has(reportError?.code)) this.#emitRuntimeError(reportError);
			}
		} catch (reportFailure) {
			this.#emitRuntimeError(reportFailure);
		}
	}

	#emitRuntimeError(error) {
		try { this.emit('runtimeError', error); }
		catch { /* reporting must never reject agent control work */ }
	}

	#writeTrace(event, fields) {
		if (this.#traceWriter === null) return;
		try {
			Promise.resolve(this.#traceWriter.write(event, fields)).catch(() => {});
			if (typeof this.#traceWriter.writeDiagnostic === 'function') Promise.resolve(this.#traceWriter.writeDiagnostic(event, fields)).catch(() => {});
		} catch { /* diagnostics cannot interrupt agent control */ }
	}

	#verboseReporter(agentId, goalRevision, { allowPublicAgentMessage = false } = {}) {
		let publishedAgentMessage = false;
		const reporter = (stage, message) => {
			try {
				if (!this.#verboseEnabled) return;
				if (allowPublicAgentMessage && stage === 'agent_message') {
					if (publishedAgentMessage) return;
					const publicMessage = sanitizePublicAgentMessage(message);
					if (publicMessage.length === 0) return;
					publishedAgentMessage = true;
					this.#publishVerbose(agentId, goalRevision, 'decision', publicMessage);
					return;
				}
			} catch { /* verbose reporting is observational */ }
		};
		reporter.reset = () => {};
		reporter.dispose = () => {
			this.#verboseReporters.delete(reporter);
		};
		this.#verboseReporters.add(reporter);
		return reporter;
	}

	#setVerboseEnabled(enabled) {
		this.#verboseEnabled = enabled;
		if (enabled) return;
		for (const reporter of this.#verboseReporters) reporter.reset();
	}

	#publishVerbose(agentId, goalRevision, stage, message) {
		try {
			const bounded = sanitizeVerboseMessage(stage, message);
			if (bounded.length === 0) return;
			this.#sendVerbose(agentId, goalRevision, stage, bounded);
		} catch { /* verbose delivery is best effort */ }
	}

	#sendVerbose(agentId, goalRevision, stage, message) {
		if (!this.#verboseEnabled || !this.#bridge.ready || !VERBOSE_STAGES.includes(stage)) return;
		const current = this.#registry.get(agentId);
		if (current === null || current.goalRevision !== goalRevision || message.length === 0 || message.length > MAX_VERBOSE_MESSAGE_LENGTH) return;
		Promise.resolve(this.#bridge.send('verbose_event', agentId, { goalRevision, stage, message })).catch(() => {});
	}

	async #publishCatalog(snapshot) {
		await this.#bridge.send('catalog_snapshot', 'server', snapshot);
	}

	async #publishGoalCompleted({ record, goalFingerprint, traceId }) {
		if (!this.#bridge.ready) throw codedRuntimeError('BRIDGE_NOT_READY', 'Minecraft bridge is not ready for completion verification');
		if (!this.#supportedAgentIds.has(record.agentId)) throw codedRuntimeError('AGENT_NOT_SUPPORTED', `Agent '${record.agentId}' is not in the reconciled bridge roster`);
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		};
		try {
			await this.#bridge.send('goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				goalFingerprint,
				traceId,
				profile,
			});
		} catch (error) {
			if (isTransientCompletionSendError(error)) throw error;
			throw codedRuntimeError('COMPLETION_SEND_FAILED', 'Minecraft bridge rejected completion verification', error);
		}
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
			scheduler: {
				active: pressure.active,
				pending: pressure.pending,
				maxConcurrent: pressure.maxConcurrent,
				maxPending: pressure.maxPending,
				warning: pressure.warning,
				mode: pressure.mode,
				configuredTarget: pressure.configuredTarget,
				target: pressure.target,
				minConcurrency: pressure.minConcurrency,
				maxConcurrency: pressure.maxConcurrency,
				urgentReserve: pressure.urgentReserve,
				ordinaryActiveLimit: pressure.ordinaryActiveLimit,
				activeOrdinary: pressure.activeOrdinary,
				activeUrgent: pressure.activeUrgent,
				pendingOrdinary: pressure.pendingOrdinary,
				pendingUrgent: pressure.pendingUrgent,
				growthCount: pressure.growthCount,
				backoffCount: pressure.backoffCount,
				lastChangeReason: pressure.lastChangeReason,
				healthyCompletions: pressure.healthyCompletions,
				ordinaryReservationRejections: pressure.ordinaryReservationRejections,
				urgentReservationRejections: pressure.urgentReservationRejections,
			},
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

	#plannerContext(agentId) {
		const ledger = this.#ledger(agentId);
		const memory = this.#conversationMemory(agentId);
		const cursor = this.#contextCursors.get(agentId);
		const record = this.#registry.get(agentId);
		if (cursor === undefined || record === null || this.#serverInstanceId === null) {
			return {
				untrustedFacts: ledger.toPlannerFacts(),
				conversationContext: memory.toPlannerContext(),
			};
		}
		const binding = this.#contextBinding(record);
		return {
			factLedger: ledger,
			conversationMemory: memory,
			contextCursor: cursor,
			contextBinding: binding,
			cursorBinding: cursor,
		};
	}

	#contextBinding(record) {
		const session = typeof this.#codexService.getAgent === 'function'
			? this.#codexService.getAgent(record.agentId)
			: null;
		let metadata = null;
		try { metadata = session?.sessionMetadata?.() ?? null; } catch { metadata = null; }
		const selectedProfile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		};
		const sessionFingerprint = metadata?.profileFingerprint ?? session?.profileFingerprint;
		const resolvedFingerprint = typeof sessionFingerprint === 'string' && /^sha256:[0-9a-f]{64}$/.test(sessionFingerprint)
			? sessionFingerprint
			: profileFingerprint(selectedProfile);
		const sessionGeneration = metadata?.sessionGeneration ?? session?.sessionGeneration;
		return {
			agentId: record.agentId,
			profileFingerprint: resolvedFingerprint,
			sessionGeneration: Number.isSafeInteger(sessionGeneration) && sessionGeneration >= 1 ? sessionGeneration : 1,
			goalRevision: record.goalRevision,
			serverInstanceId: this.#serverInstanceId,
		};
	}

	#contextSnapshot(record) {
		return {
			factRevision: this.#ledger(record.agentId).delta(null).nextRevision,
			conversationSequence: this.#conversationMemory(record.agentId).delta(null).nextSequence,
		};
	}

	#rememberAcceptedContextCursor(record, snapshot = null) {
		if (this.#serverInstanceId === null) return;
		const binding = this.#contextBinding(record);
		const revisions = snapshot ?? this.#contextSnapshot(record);
		const value = {
			...binding,
			factRevision: revisions.factRevision,
			conversationSequence: revisions.conversationSequence,
			providerAccepted: true,
		};
		const previous = this.#contextCursors.get(record.agentId);
		try {
			this.#contextCursors.set(record.agentId, previous === undefined ? createContextCursor(value) : advanceContextCursor(previous, value));
		} catch {
			// A replacement provider session starts from a full current baseline.
			this.#contextCursors.set(record.agentId, createContextCursor(value));
		}
	}

	#invalidateServerInstance() {
		this.#cancelGoalSpecRequests();
		this.#healthRegistry.reset();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		this.#conversationWakeTransactions.clear();
		this.#providerWork.clear();
		for (const record of this.#registry.list()) {
			if (this.#usesNativeTools(record)) this.#goalSupervisor.terminate(this.#supervisionKey(record));
			this.#advanceLifecycleGeneration(record.agentId);
			this.#programRuntime.dispose(record.agentId);
			void this.#nativeRuntime.dispose(record.agentId, 'server_replaced');
			this.#pendingAttention.delete(record.agentId);
			this.#attentionFlushes.delete(record.agentId);
			this.#providerRetryAfter.delete(record.agentId);
			try {
				Promise.resolve(this.#planner.interrupt(record.agentId, 'Minecraft server instance changed'))
					.catch((error) => this.#reportAgentError(record.agentId, error));
			} catch (error) {
				void this.#reportAgentError(record.agentId, error);
			}
		}
	}

	#goalSpecRequestKey(agentId, requestId) {
		return `${this.#serverInstanceId ?? 'disconnected'}\u0000${agentId}\u0000${requestId}`;
	}

	async #processGoalSpecRequest(key, entry) {
		if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null || entry.translating) return;
		if (entry.retryHandle !== null) {
			this.#clearGoalSpecTimeout(entry.retryHandle);
			entry.retryHandle = null;
		}
		if (entry.proposal !== null) {
			try {
				await this.#bridge.send('goal_spec_proposal', entry.agentId, entry.proposal);
			} catch (error) {
				if (this.#goalSpecRequests.get(key) === entry) this.#emitRuntimeError(error);
			}
			if (this.#goalSpecRequests.get(key) === entry) this.#scheduleGoalSpecRequest(key, entry, GOAL_SPEC_PROPOSAL_RETRY_MS);
			return;
		}
		entry.translating = true;
		try {
			await this.#reconciliation;
			if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null) return;
			entry.proposal = await this.#planner.requestGoalSpec({ agentId: entry.agentId, request: entry.request });
			entry.attempts = 0;
		} catch (error) {
			if (this.#goalSpecRequests.get(key) !== entry) return;
			entry.attempts += 1;
			this.#emitRuntimeError(error);
			const delay = Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** Math.min(entry.attempts - 1, 5)));
			this.#scheduleGoalSpecRequest(key, entry, delay);
			return;
		} finally {
			entry.translating = false;
		}
		if (this.#goalSpecRequests.get(key) === entry) await this.#processGoalSpecRequest(key, entry);
	}

	#scheduleGoalSpecRequest(key, entry, delayMs) {
		if (entry.retryHandle !== null) this.#clearGoalSpecTimeout(entry.retryHandle);
		entry.retryHandle = this.#setGoalSpecTimeout(() => {
			entry.retryHandle = null;
			this.#run(() => this.#processGoalSpecRequest(key, entry));
		}, delayMs);
	}

	#cancelGoalSpecRequests(agentId = null) {
		for (const [key, request] of this.#goalSpecRequests) {
			if (agentId !== null && request.agentId !== agentId) continue;
			this.#goalSpecRequests.delete(key);
			if (request.retryHandle !== null) this.#clearGoalSpecTimeout(request.retryHandle);
			try { this.#planner.cancelGoalSpec?.(request.agentId, request.requestId); } catch { /* cancellation is best effort */ }
		}
	}

	#conversationMemory(agentId) {
		let memory = this.#conversationMemories.get(agentId);
		if (memory === undefined) {
			memory = new ConversationMemory();
			this.#conversationMemories.set(agentId, memory);
		}
		return memory;
	}

	#rememberConversationWake(transactionId, agentId, fingerprint) {
		this.#forgetConversationWakes(agentId);
		this.#conversationWakeTransactions.set(transactionId, { agentId, fingerprint });
		while (this.#conversationWakeTransactions.size > MAX_CONVERSATION_WAKE_TRANSACTIONS) {
			this.#conversationWakeTransactions.delete(this.#conversationWakeTransactions.keys().next().value);
		}
	}

	#forgetConversationWakes(agentId) {
		for (const [transactionId, transaction] of this.#conversationWakeTransactions) {
			if (transaction.agentId === agentId) this.#conversationWakeTransactions.delete(transactionId);
		}
	}

}

export function createDynamicCoordinator(configValue, dependencies = {}) {
	const providerTurnRecorder = dependencies.providerTurnRecorder ?? null;
	const config = normalizeDynamicConfig(configValue, dependencies.env ?? process.env);
	const providerEnvironment = createProviderChildEnvironment(
		dependencies.env ?? process.env,
		config.bridge.secretEnvironmentVariable,
	);
	const registry = dependencies.registry ?? new AgentRegistry({
		agentCap: config.limits.agentCap,
		queueCap: config.limits.goalQueueCap,
		now: dependencies.now ?? Date.now,
	});
	const scheduler = dependencies.scheduler ?? new PlanningScheduler({
		maxConcurrent: config.limits.planningConcurrency,
		maxPending: Math.max(0, config.limits.agentCap - config.limits.planningConcurrency),
		planningMode: config.limits.planningMode,
		minConcurrency: 4,
		maxConcurrency: config.limits.agentCap,
		urgentReserve: config.limits.urgentReserve,
		onPressure: (snapshot) => dependencies.onSchedulerPressure?.(snapshot),
		benchmarkRecorder: dependencies.benchmarkRecorder,
	});
	const workspaceManager = dependencies.workspaceManager ?? new AgentWorkspaceManager(config.workspaceRoot);
	const minecraftWorkspace = dependencies.minecraftWorkspace ?? new MinecraftAgentWorkspace({
		root: config.minecraftAgentRoot,
		templateRoot: config.minecraftAgentTemplateRoot,
	});
	const codexService = dependencies.providerService ?? dependencies.codexService ?? new ProviderService({
		codex: new CodexService({ ...config.codex, environment: providerEnvironment, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transport: dependencies.codexTransport, now: dependencies.now ?? Date.now, workspaceManager, minecraftWorkspace }),
		gemini: new AntigravityProviderService({ ...config.gemini, environment: providerEnvironment, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.antigravitySpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
		kimi: new AcpProviderService({ ...config.kimi, environment: providerEnvironment, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transportFactory: dependencies.kimiTransportFactory, workspaceManager }),
		cursor: new CursorProviderService({ ...config.cursor, environment: providerEnvironment, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.cursorSpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
	}, { turnRecorder: providerTurnRecorder });
	const healthRegistry = dependencies.healthRegistry ?? dependencies.planner?.healthRegistry ?? new ProviderHealthRegistry({ now: dependencies.healthNow ?? Date.now });
	const latencyRegistry = dependencies.latencyRegistry ?? new ControlLatencyRegistry();
	const planner = dependencies.planner ?? new AgentPlanner({
		registry,
		scheduler,
		codexService,
		invalidDecisionRetries: config.limits.invalidDecisionRetries,
		healthRegistry,
		latencyRegistry,
		now: dependencies.plannerNow ?? dependencies.now,
		telemetrySink: dependencies.telemetrySink,
		benchmarkRecorder: dependencies.benchmarkRecorder,
		turnRecorder: providerTurnRecorder,
	});
	const bridge = dependencies.bridge ?? new MultiplexedServerBridge(config.bridge, {
		audit: dependencies.protocolAudit,
		socketFactory: dependencies.socketFactory,
		schedule: dependencies.schedule,
		cancelSchedule: dependencies.cancelSchedule,
		currentRevision: (agentId) => registry.get(agentId)?.goalRevision ?? null,
	});
	let coordinator = null;
	const goalSupervisor = dependencies.goalSupervisor ?? new ActiveGoalSupervisor({
		requestObservation: ({ agentId, goalRevision }) => bridge.send('request_observation', agentId, { goalRevision }),
		clock: dependencies.goalClock ?? dependencies.controlNow ?? Date.now,
		schedule: dependencies.goalSchedule,
		cancelSchedule: dependencies.cancelGoalSchedule,
		stuckSchedule: dependencies.goalStuckSchedule,
		cancelStuckSchedule: dependencies.cancelGoalStuckSchedule,
		onExpire: (event) => coordinator?.handleLeaseExpired(event),
		onStuck: (event) => coordinator?.handleGoalStuck(event),
	});
	coordinator = new DynamicCoordinator({
		registry,
		scheduler,
		codexService,
		planner,
		bridge,
		healthRegistry,
		latencyRegistry,
		goalSupervisor,
		codexControlProtocol: config.codex.controlProtocol,
		traceWriter: dependencies.traceWriter,
		providerTurnRecorder,
		controlNow: dependencies.controlNow,
		epochNow: dependencies.epochNow,
		setStatusInterval: dependencies.setStatusInterval,
		clearStatusInterval: dependencies.clearStatusInterval,
		setGoalSpecTimeout: dependencies.setGoalSpecTimeout,
		clearGoalSpecTimeout: dependencies.clearGoalSpecTimeout,
		benchmarkRecorder: dependencies.benchmarkRecorder,
	});
	return coordinator;
}

function defaultGoalSpecTimeout(callback, delayMs) {
	const handle = setTimeout(callback, delayMs);
	handle.unref?.();
	return handle;
}

export function parseDynamicCliArguments(args) {
	if (!Array.isArray(args)) throw new TypeError('CLI arguments must be an array');
	if (args.length === 0) return { configPath: DEFAULT_DYNAMIC_CONFIG_PATH };
	if (args.length !== 2 || args[0] !== '--config') throw new Error('Usage: node coordinator/src/dynamic-main.mjs [--config <absolute-path>]');
	if (!path.isAbsolute(args[1])) throw new Error('--config must be an absolute path');
	return { configPath: args[1] };
}

export function resolveDynamicCliRuntime(environment = process.env) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('runtime environment must be an object');
	const tracePath = environment.ARENA_HEADLESS_TRACE_PATH ?? path.join(PROJECT_DIRECTORY, 'runtime', 'traces', 'coordinator.jsonl');
	const separator = tracePath.includes('\\') ? '\\' : '/';
	const traceDirectory = tracePath.slice(0, Math.max(0, tracePath.lastIndexOf(separator)));
	return {
		tracePath,
		diagnosticTracePath: environment.ARENA_HEADLESS_PRIVATE_TRACE_PATH ?? `${traceDirectory}${separator}coordinator-private.jsonl`,
		protocolAuditPath: environment.ARENA_PROTOCOL_AUDIT_PATH ?? null,
		providerTurnsPath: environment.ARENA_PROVIDER_TURNS_PATH ?? null,
		runId: environment.ARENA_HEADLESS_RUN_ID ?? 'dynamic-run',
		scenarioId: environment.ARENA_HEADLESS_SCENARIO_ID ?? 'dynamic',
	};
}

export async function loadDynamicConfig(configPath = DEFAULT_DYNAMIC_CONFIG_PATH) {
	const document = JSON.parse(await readFile(configPath, 'utf8'));
	return normalizeDynamicConfig(document, process.env);
}

export function normalizeDynamicConfig(value, environment = process.env) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('dynamic coordinator config must be an object');
	if (value.bridge === null || typeof value.bridge !== 'object' || Array.isArray(value.bridge)) throw new TypeError('dynamic coordinator bridge config must be an object');
	if (value.codex === null || typeof value.codex !== 'object' || Array.isArray(value.codex)) throw new TypeError('dynamic coordinator Codex config must be an object');
	if (value.voice !== undefined && (value.voice === null || typeof value.voice !== 'object' || Array.isArray(value.voice))) throw new TypeError('dynamic coordinator voice config must be an object');
	const secret = value.bridge.secret ?? environment[value.bridge.secretEnvironmentVariable ?? 'ARENA_AGENT_BRIDGE_SECRET'];
	const cwd = value.codex.cwd ?? PROJECT_DIRECTORY;
	const workspaceRoot = value.workspaceRoot === undefined
		? path.join(PROJECT_DIRECTORY, 'runtime', 'agent-workspaces')
		: path.resolve(PROJECT_DIRECTORY, value.workspaceRoot);
	const minecraftAgentRoot = value.minecraftAgentRoot === undefined
		? path.join(PROJECT_DIRECTORY, 'runtime', 'minecraft-agent')
		: path.resolve(PROJECT_DIRECTORY, value.minecraftAgentRoot);
	const agentCap = positiveInteger(value.limits?.agentCap ?? DEFAULT_AGENT_CAP, 'limits.agentCap');
	const planningConcurrency = positiveInteger(value.limits?.planningConcurrency ?? DEFAULT_PLANNING_CONCURRENCY, 'limits.planningConcurrency');
	const planningMode = value.limits?.planningMode ?? 'fixed';
	if (planningMode !== 'fixed' && planningMode !== 'adaptive') throw new TypeError("limits.planningMode must be 'fixed' or 'adaptive'");
	if (agentCap > 16) throw new TypeError('limits.agentCap must not exceed 16');
	if (planningMode === 'adaptive' && (agentCap < 4 || planningConcurrency < 4 || planningConcurrency > 16 || planningConcurrency > agentCap)) {
		throw new TypeError('adaptive planningConcurrency and agentCap must be in [4, 16]');
	}
	if (planningConcurrency > agentCap) throw new TypeError('limits.planningConcurrency must not exceed limits.agentCap');
	const urgentReserve = value.limits?.urgentReserve ?? (agentCap >= 4 ? 1 : 0);
	if (!Number.isSafeInteger(urgentReserve) || urgentReserve < 0 || urgentReserve > agentCap) throw new TypeError('limits.urgentReserve must be a non-negative safe integer within limits.agentCap');
	const voice = normalizeVoiceConfig(value.voice, environment);
	const codexControlProtocol = value.codex.controlProtocol ?? 'native_tools';
	if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codex.controlProtocol must be arena_script or native_tools');
	return {
		bridge: { ...value.bridge, secret },
		workspaceRoot,
		minecraftAgentRoot,
		minecraftAgentTemplateRoot: path.join(COORDINATOR_DIRECTORY, 'config', 'minecraft-agent'),
		voice,
		codex: {
			...value.codex,
			cwd,
			controlProtocol: codexControlProtocol,
			launchProfile: value.codex.launchProfile === undefined ? undefined : { ...value.codex.launchProfile, cwd },
		},
		gemini: {
			provider: 'gemini',
			cwd,
			catalogDiscovery: true,
			models: ['gemini-3.7-flash', 'gemini-3.1-pro', 'gemini-3.6-flash', 'gemini-3.5-flash'],
			modelReasoningEfforts: {
				'gemini-3.7-flash': ['high', 'medium', 'low'],
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
		cursor: {
			provider: 'cursor',
			cwd,
			executable: process.platform === 'win32' && typeof environment.LOCALAPPDATA === 'string' && environment.LOCALAPPDATA.trim() !== ''
				? path.join(environment.LOCALAPPDATA, 'cursor-agent', 'agent.ps1')
				: 'agent',
			catalogDiscovery: true,
			models: ['composer-2.5', 'grok-4.5', 'grok-4.6'],
			modelReasoningEfforts: {
				'composer-2.5': ['high'],
				'grok-4.5': ['low', 'medium', 'high'],
				'grok-4.6': ['low', 'medium', 'high', 'xhigh'],
			},
			...(value.cursor ?? {}),
		},
		limits: {
			agentCap,
			goalQueueCap: positiveInteger(value.limits?.goalQueueCap ?? DEFAULT_GOAL_QUEUE_CAP, 'limits.goalQueueCap'),
			planningConcurrency,
			planningMode,
			urgentReserve,
			invalidDecisionRetries: nonNegativeInteger(value.limits?.invalidDecisionRetries ?? DEFAULT_INVALID_DECISION_RETRIES, 'limits.invalidDecisionRetries'),
		},
	};
}

async function runCli(reporter = new RuntimeErrorReporter()) {
	const { configPath } = parseDynamicCliArguments(process.argv.slice(2));
	const config = await loadDynamicConfig(configPath);
	const runtime = resolveDynamicCliRuntime(process.env);
	const traceWriter = new TraceWriter(runtime.tracePath, { diagnosticFilePath: runtime.diagnosticTracePath });
	const protocolAudit = runtime.protocolAuditPath === null ? null : createJsonlAudit(runtime.protocolAuditPath, { runId: runtime.runId, scenarioId: runtime.scenarioId });
	if (runtime.providerTurnsPath !== null) await mkdir(path.dirname(path.resolve(runtime.providerTurnsPath)), { recursive: true });
	const providerTurnRecorder = runtime.providerTurnsPath === null ? null : new ProviderTurnRecorder({
		runId: runtime.runId,
		scenarioId: runtime.scenarioId,
		privatePath: runtime.providerTurnsPath,
	});
	const coordinator = createDynamicCoordinator(config, { traceWriter, protocolAudit, providerTurnRecorder });
	const disposeDiagnostics = wireRuntimeDiagnostics(coordinator, reporter);
	let voiceWorker = await startVoiceWorker(config, process.env).catch(() => {
		process.stderr.write('[voice-worker] unavailable; proximity speech will fall back to text\n');
		return null;
	});
	try {
		await coordinator.start();
	} catch (error) {
		await Promise.allSettled([coordinator.stop(), voiceWorker?.close(), protocolAudit?.close()]);
		disposeDiagnostics();
		throw error;
	}
	let shutdownPromise = null;
	const shutdown = async () => {
		shutdownPromise ??= Promise.allSettled([coordinator.stop(), voiceWorker?.close(), protocolAudit?.close()])
			.finally(disposeDiagnostics);
		await shutdownPromise;
		process.exitCode = 0;
	};
	process.once('SIGINT', shutdown);
	process.once('SIGTERM', shutdown);
}

function createJsonlAudit(filePath, metadata) {
	if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('protocol audit path must be nonblank');
	let queue = Promise.resolve();
	let closed = false;
	const ready = mkdir(path.dirname(path.resolve(filePath)), { recursive: true });
	const audit = (direction, envelope) => {
		if (closed) return Promise.reject(new Error('protocol audit is closed'));
		const encoded = `${JSON.stringify({ ...metadata, direction, envelope })}\n`;
		queue = queue.catch(() => {}).then(async () => { await ready; await appendFile(filePath, encoded, { encoding: 'utf8', flag: 'a' }); });
		return queue;
	};
	audit.close = async () => { closed = true; await queue; };
	return audit;
}

export async function startVoiceWorker(config, environment = process.env, dependencies = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('voice worker config must be an object');
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('voice worker environment must be an object');
	const voice = config.voice ?? {};
	const fishApiKey = firstNonBlank(
		environment[voice.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE],
		environment.FISH_API_KEY,
	);
	const platform = dependencies.platform ?? process.platform;
	const createLocalSpeechProvider = dependencies.createLocalSpeechProvider
		?? ((options) => LocalSpeechProvider.createIfAvailable(options));
	if (typeof createLocalSpeechProvider !== 'function') throw new TypeError('createLocalSpeechProvider must be a function');
	const localSpeechProvider = await createLocalSpeechProvider({
		executable: firstNonBlank(environment.ARENA_AGENT_SPEECH_PYTHON, voice.localSpeechPythonPath)
			?? path.resolve(PROJECT_DIRECTORY, DEFAULT_LOCAL_SPEECH_PYTHON_PATH),
		scriptPath: path.join(SOURCE_DIRECTORY, 'voice', 'local-speech-worker.py'),
		timeoutMs: voice.localSpeechTimeoutMs ?? 120_000,
	});
	if (localSpeechProvider === null && fishApiKey === null && platform !== 'win32') return null;
	const profilePath = dependencies.profilePath
		?? voice.profileAssignmentsPath
		?? path.resolve(PROJECT_DIRECTORY, DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH);
	const loadProfileStore = dependencies.loadProfileStore ?? loadPersistentVoiceProfileStore;
	const createTtsProvider = dependencies.createTtsProvider ?? ((options) => new FishTtsProvider(options));
	const createWindowsTtsProvider = dependencies.createWindowsTtsProvider ?? ((options) => new WindowsTtsProvider(options));
	const createSttProvider = dependencies.createSttProvider ?? ((options) => new DeepgramSttProvider(options));
	const createServer = dependencies.createVoiceServer ?? createVoiceHttpServer;
	if (typeof loadProfileStore !== 'function') throw new TypeError('loadProfileStore must be a function');
	if (typeof createTtsProvider !== 'function') throw new TypeError('createTtsProvider must be a function');
	if (typeof createWindowsTtsProvider !== 'function') throw new TypeError('createWindowsTtsProvider must be a function');
	if (typeof createServer !== 'function') throw new TypeError('createVoiceServer must be a function');
	const profiles = await loadProfileStore(profilePath);
	if (profiles === null || typeof profiles !== 'object' || profiles.store === null || typeof profiles.store?.resolve !== 'function') {
		throw new TypeError('loadProfileStore must return a profile store');
	}
	const deepgramApiKey = firstNonBlank(
		environment[voice.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE],
	);
	if (deepgramApiKey !== null && typeof createSttProvider !== 'function') throw new TypeError('createSttProvider must be a function when Deepgram is configured');
	let provider = localSpeechProvider;
	if (provider === null) provider = fishApiKey === null ? createWindowsTtsProvider({}) : createTtsProvider({ apiKey: fishApiKey });
	if (localSpeechProvider === null && fishApiKey !== null && platform === 'win32') {
		provider = ttsProviderWithFallback(provider, createWindowsTtsProvider({}));
	}
	const worker = createServer({
		provider,
		sttProvider: localSpeechProvider ?? (deepgramApiKey === null ? new NoSttProvider() : createSttProvider({ apiKey: deepgramApiKey })),
		profileStore: typeof profiles.flush === 'function' ? Object.assign(profiles.store, { flush: profiles.flush }) : profiles.store,
		secret: config.bridge?.secret,
		port: voice.port ?? DEFAULT_VOICE_PORT,
		maxConcurrent: voice.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT,
	});
	if (worker === null || typeof worker !== 'object' || typeof worker.start !== 'function' || typeof worker.close !== 'function') {
		throw new TypeError('createVoiceServer must return a voice worker');
	}
	try {
		await worker.start();
		if (typeof localSpeechProvider?.warmup === 'function') {
			try { await localSpeechProvider.warmup(); }
			catch { /* warmup is opportunistic; the first real request can retry model loading */ }
		}
		return localSpeechProvider === null ? worker : voiceWorkerWithOwnedProvider(worker, localSpeechProvider);
	} catch (error) {
		await Promise.allSettled([worker.close(), localSpeechProvider?.close()]);
		throw error;
	}
}

function voiceWorkerWithOwnedProvider(worker, provider) {
	let closePromise = null;
	return Object.freeze({
		...worker,
		start: worker.start.bind(worker),
		close() {
			closePromise ??= Promise.allSettled([worker.close(), provider.close()]).then(() => undefined);
			return closePromise;
		},
	});
}

function ttsProviderWithFallback(primary, fallback) {
	return Object.freeze({
		async synthesize(request) {
			try {
				return await primary.synthesize(request);
			} catch (error) {
				if (!shouldUseWindowsTtsFallback(error)) throw error;
				return fallback.synthesize(request);
			}
		},
	});
}

function shouldUseWindowsTtsFallback(error) {
	if (error?.name === 'AbortError') return false;
	return error?.name === 'TimeoutError' || WINDOWS_TTS_FALLBACK_CODES.has(error?.code);
}

function normalizeVoiceConfig(value, environment) {
	const source = value ?? {};
	const configuredPort = environment.ARENA_AGENT_VOICE_PORT === undefined
		? source.port ?? DEFAULT_VOICE_PORT
		: Number(environment.ARENA_AGENT_VOICE_PORT);
	if (!Number.isSafeInteger(configuredPort) || configuredPort < 1 || configuredPort > 65_535) throw new TypeError('voice.port must be an integer between 1 and 65535');
	const maxConcurrent = positiveInteger(source.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT, 'voice.maxConcurrent');
	if (maxConcurrent > 5) throw new TypeError('voice.maxConcurrent must not exceed 5');
	const profileAssignmentsPath = path.resolve(PROJECT_DIRECTORY, source.profileAssignmentsPath ?? DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH);
	return {
		...source,
		port: configuredPort,
		maxConcurrent,
		profileAssignmentsPath,
		fishApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE, 'voice.fishApiKeyEnvironmentVariable'),
		deepgramApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE, 'voice.deepgramApiKeyEnvironmentVariable'),
	};
}

function firstNonBlank(...values) {
	for (const value of values) if (typeof value === 'string' && value.trim() !== '') return value;
	return null;
}

function requireEnvironmentVariableName(value, field) {
	if (typeof value !== 'string' || !/^[A-Z_][A-Z0-9_]*$/.test(value)) throw new TypeError(`${field} must be an environment variable name`);
	return value;
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

function safeClockRead(clock) {
	try {
		const value = clock();
		return Number.isFinite(value) && value >= 0 ? value : null;
	} catch {
		return null;
	}
}

function codedRuntimeError(code, message, cause = undefined) {
	return Object.assign(new Error(message, cause === undefined ? undefined : { cause }), { code });
}

function isTransientCompletionSendError(error) {
	return ['BRIDGE_NOT_READY', 'BRIDGE_DISCONNECTED', 'CONNECTION_BACKPRESSURE', 'AGENT_BACKPRESSURE'].includes(error?.code);
}

function verboseErrorMessage(error) {
	const code = String(error?.code ?? 'COORDINATOR_ERROR').slice(0, 128);
	return `${verboseErrorScope(code)} error (${code}).`;
}

function sanitizeVerboseMessage(stage, message) {
	const normalized = String(message ?? '')
		.replace(/[\u0000-\u001f\u007f-\u009f]+/g, ' ')
		.replace(/\s+/g, ' ')
		.trim();
	if (stage === 'error') {
		const code = normalized.match(/\(([A-Z][A-Z0-9_]{1,127})\)/)?.[1]
			?? normalized.match(/\b[A-Z][A-Z0-9_]{2,127}\b/)?.[0]
			?? 'COORDINATOR_ERROR';
		const scope = /^(Provider|Planning|Coordinator) error \(/i.exec(normalized)?.[1] ?? verboseErrorScope(code);
		return `${scope[0].toUpperCase()}${scope.slice(1).toLowerCase()} error (${code}).`.slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
	}
	return sanitizeVerboseOutput(normalized).slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
}

function verboseDecisionSummary(decision) {
	return sanitizePublicNarrative(decision?.summary) || 'Plan accepted.';
}

function sanitizePublicAgentMessage(message) {
	return sanitizePublicNarrative(message);
}

function sanitizePublicNarrative(message) {
	const source = typeof message === 'string' ? message : String(message ?? '');
	let raw = source.slice(0, MAX_PUBLIC_NARRATIVE_RAW_CHARS);
	if (source.length > MAX_PUBLIC_NARRATIVE_RAW_CHARS) raw = completePublicSentences(raw);
	const visible = sanitizeVerboseOutput(raw);
	if (visible.length === 0 || /^[{]/.test(visible)) return '';
	const boundary = publicNarrativeBoundary(visible);
	return visible.slice(0, boundary ?? visible.length).replace(/[\s,;:-]+$/, '').trim().slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
}

function completePublicSentences(value) {
	let boundary = 0;
	for (const match of value.matchAll(/[.!?](?=\s|$)/g)) boundary = match.index + match[0].length;
	return value.slice(0, boundary);
}

function publicNarrativeBoundary(value) {
	const matches = [
		value.search(/\{/),
		value.search(/\[\s*\{/),
		value.search(/\b(?:action[_ -]?id|native[_ -]?action|action[_ -]?call|call[_ -]?id|tool[_ -]?(?:call|record|result)|trace[_ -]?id|uuid|diagnostic|program[_ -]?(?:step|compiled|replaced))\b/i),
		value.search(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i),
		value.search(/\bnative:[a-z0-9._-]+:\d+:\d+\b/i),
		value.search(/\baction-progress-\d+\b/i),
		value.search(/\bcall_[a-z0-9]{3,}\b/i),
		value.search(/\b[a-z0-9._-]+:\d+:\d+:\d+:program-\d+-\d+:\d+:\d+:(?:arena-state|step)-\d+\b/i),
		value.search(/\bprogram-\d+-\d+:\d+:\d+:(?:arena-state|step)-\d+\b/i),
		value.search(/\btrace-[a-z0-9._:-]+-\d+-\d+-[a-z][a-z0-9_-]*\b/i),
		value.search(/\b(?:calling|executing|invoking)\s+[a-z][a-z0-9_]*\s+with\s+(?:[a-z][a-z0-9_]*\s*=|\{)/i),
	].filter((index) => index >= 0);
	return matches.length === 0 ? null : Math.min(...matches);
}

function verboseErrorScope(code) {
	if (/^(?:AUTHENTICATION_REQUIRED|PROVIDER|TURN_|REQUEST_TIMEOUT|MISSING_(?:AGENT|FINAL)_MESSAGE|SPAWN_|APP_SERVER_)/.test(code)) return 'Provider';
	if (/(?:DECISION|PLANNER|PLANNING_TIMEOUT|PLAN_|PARSE|DIRECTIVE|ARENA_SCRIPT)/.test(code)) return 'Planning';
	return 'Coordinator';
}

function sanitizeVerboseOutput(message) {
	return String(message ?? '')
		.replace(/[\u0000-\u001f\u007f-\u009f]+/g, ' ')
		.replace(/\s+/g, ' ')
		.trim()
		.replace(/\bsk-[A-Za-z0-9_-]{16,}\b/g, '[REDACTED_KEY]')
		.replace(/\bAIza[A-Za-z0-9_-]{20,}\b/g, '[REDACTED_KEY]')
		.replace(/\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+/gi, '[REDACTED_AUTH]')
		.replace(/(\b(?:secret|token|api[-_ ]?key|password|authorization)\b["']?\s*(?:[:=]\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1[REDACTED]');
}

function factualProgressSignature(observation) {
	const projection = factualProgressProjection(observation);
	return createHash('sha256').update(JSON.stringify(sortFactualValue(projection))).digest('hex');
}

function factualProgressDetails(observation) {
	const player = observation?.player ?? {};
	const position = player.position ?? player;
	return {
		position: {
			x: finiteOrNull(position?.x),
			y: finiteOrNull(position?.y),
			z: finiteOrNull(position?.z),
		},
		lastResult: observation?.lastResult?.present === true ? {
			actionType: observation.lastResult.actionType,
			state: observation.lastResult.state,
			reasonCode: observation.lastResult.reasonCode,
		} : null,
	};
}

function factualProgressProjection(observation) {
	const player = observation?.player ?? {};
	const position = player.position ?? player;
	return {
		position: [finiteOrNull(position?.x), finiteOrNull(position?.y), finiteOrNull(position?.z)],
		alive: player.dead === true ? false : player.alive ?? null,
		health: finiteOrNull(player.health),
		inventory: (observation?.inventory?.items ?? []).map((item) => ({ itemId: item?.itemId ?? item?.id ?? null, count: item?.count ?? null })),
		blocks: (observation?.blocks ?? []).map((block) => ({ x: block?.x ?? null, y: block?.y ?? null, z: block?.z ?? null, blockId: block?.blockId ?? block?.id ?? null, state: block?.state ?? block?.properties ?? null })),
		advancements: observation?.advancements ?? null,
		killEvidence: observation?.killEvidence ?? null,
	};
}

function sortFactualValue(value) {
	if (Array.isArray(value)) return value.map(sortFactualValue);
	if (value !== null && typeof value === 'object') {
		return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortFactualValue(value[key])]));
	}
	return value;
}

function finiteOrNull(value) {
	return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function classifyObservationTrigger(payload, observation) {
	const explicitTrigger = typeof payload.trigger === 'string' && payload.trigger.trim().length > 0 ? payload.trigger.trim().slice(0, 128) : null;
	const attention = payload.attention === true;
	if (!attention && explicitTrigger === null) return { attention: false, priority: 'ordinary', trigger: 'observation' };
	if (explicitTrigger !== null) return { attention: true, priority: payload.priority === 'urgent' ? 'urgent' : 'ordinary', trigger: explicitTrigger };
	const changedFacts = Array.isArray(payload.changedFacts) ? payload.changedFacts : [];
	const joinedFacts = changedFacts.filter((value) => typeof value === 'string').join('|').toLowerCase();
	const player = observation?.player ?? {};
	if (joinedFacts.includes('health') || joinedFacts.includes('attacker') || joinedFacts.includes('damage')) return { attention: true, priority: 'urgent', trigger: 'damage' };
	if (joinedFacts.includes('lava')) return { attention: true, priority: 'urgent', trigger: 'lava' };
	if (joinedFacts.includes('fire') || player.fire === true) return { attention: true, priority: 'urgent', trigger: 'fire' };
	if (joinedFacts.includes('suffoc') || joinedFacts.includes('air')) return { attention: true, priority: 'urgent', trigger: 'suffocation' };
	if (joinedFacts.includes('fall')) return { attention: true, priority: 'urgent', trigger: 'fall' };
	if (Array.isArray(observation?.blocks) && observation.blocks.some((block) => typeof block?.blockId === 'string' && block.blockId.toLowerCase().includes('lava'))) return { attention: true, priority: 'urgent', trigger: 'lava' };
	return { attention: true, priority: 'ordinary', trigger: 'attention' };
}

function mergeAttentionTrigger(previous, next) {
	const priority = previous?.priority === 'urgent' || next?.priority === 'urgent' ? 'urgent' : 'ordinary';
	const winner = next?.priority === priority ? next : previous;
	return {
		attention: previous?.attention === true || next?.attention !== false,
		priority,
		trigger: winner?.trigger ?? 'attention',
	};
}

function mergePlannerRequest(previous, next) {
	if (previous === null || previous === undefined) return next;
	const priority = previous.priority === 'urgent' || next.priority === 'urgent' ? 'urgent' : 'ordinary';
	const winner = next.priority === priority ? next : previous;
	return { ...next, priority, trigger: winner.trigger };
}

export function buildNativeEventInput(record, { event, trigger, observation = {}, conversation = [], conversationOnly = false } = {}) {
	const compactObservation = {
		player: observation.player ?? {},
		inventory: { items: (observation.inventory?.items ?? []).slice(0, 32), ...(observation.inventory?.tagCounts === undefined ? {} : { tagCounts: observation.inventory.tagCounts }) },
		items: (observation.items ?? []).slice(0, 16),
		entities: (observation.entities ?? []).filter((entity) => entity?.type !== 'minecraft:item').slice(0, 16),
		blocks: (observation.blocks ?? []).slice(0, 32),
	};
	const payload = {
		event: typeof event === 'string' && event.length > 0 ? event : 'observation',
		trigger: typeof trigger === 'string' && trigger.length > 0 ? trigger : 'observation',
		mode: conversationOnly === true ? 'conversation_only' : 'goal',
		goal: record?.currentGoal ?? null,
		goalSpec: record?.currentGoalSpec ?? null,
		goalRevision: record?.goalRevision ?? 0,
		observation: compactObservation,
		conversation: Array.isArray(conversation) ? conversation.slice(-4) : [],
	};
	let json = JSON.stringify(payload);
	if (Buffer.byteLength(json, 'utf8') > 16_384) {
		json = JSON.stringify({ ...payload, observation: { player: compactObservation.player, inventory: { items: compactObservation.inventory.items.slice(0, 16) }, items: [], entities: [], blocks: compactObservation.blocks.slice(0, 12) } });
	}
	return `Live Minecraft event. Choose and call the smallest useful tool now.\n${json}`;
}

function planningTraceId(agentId, goalRevision, lifecycleGeneration, kind) {
	const identity = String(agentId).replace(/[^A-Za-z0-9._:-]/g, '_');
	return `trace-${identity}-${goalRevision}-${lifecycleGeneration}-${kind}`.slice(0, 128);
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	const reporter = new RuntimeErrorReporter();
	runCli(reporter).catch((error) => {
		reporter.report(error, { phase: 'startup' });
		process.exitCode = 1;
	});
}
