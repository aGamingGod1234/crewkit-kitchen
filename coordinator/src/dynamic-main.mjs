import { generateDirectorScript } from './director-script-generator.mjs';
import { EventEmitter } from 'node:events';
import { createHash } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentPlanner } from './agent-planner.mjs';
import { AgentRegistry, AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
import { ActiveGoalSupervisor } from './active-goal-supervisor.mjs';
import { MAX_LEASE_TIMEOUT_MS } from './work-lease-supervisor.mjs';
import { AgentWorkspaceManager } from './agent-workspace.mjs';
import { MinecraftAgentWorkspace } from './minecraft-agent-workspace.mjs';
import { AcpProviderService } from './acp-service.mjs';
import { AntigravityProviderService } from './antigravity-service.mjs';
import { CodexService } from './codex-service.mjs';
import { CursorProviderService } from './cursor-service.mjs';
import { ControlLatencyRegistry } from './control-latency-registry.mjs';
import { buildCoordinatorStatus, providerRecoveryComponents } from './coordinator-status.mjs';
import { ConversationMemory } from './conversation-memory.mjs';
import { PendingConversationInbox } from './pending-conversation-inbox.mjs';
import { FactLedger } from './fact-ledger.mjs';
import { InspectionClient } from './inspection-client.mjs';
import { ModelNotebook } from './model-notebook.mjs';
import { RuntimeMemoryContext } from './runtime-memory-context.mjs';
import { TaskMemoryStore } from './task-memory-store.mjs';
import { LiveTaskViews } from './live-task-view.mjs';
import { ObservedMemoryStore } from './observed-memory-store.mjs';
import { ExplorationOccupancy } from './explore-frontier.mjs';
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
import { advanceContextCursor, buildPlannerInput, buildPlannerRequest, contextCursorMatches, createContextCursor } from './prompts.mjs';
import { profileFingerprint } from './provider-session.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { classifyRecoveryFailure } from './recovery-policy.mjs';
import { ReportingTransitionDeduper } from './reporting-transition-deduper.mjs';
import { NativeToolRuntime } from './native-tool-runtime.mjs';
import { classifyNativeGoalError } from './native-goal-error-policy.mjs';
import { MAX_GOAL_SPEC_CORRECTION_ATTEMPTS, fallbackCompiledDragonGoal, localGoalSpecFeedback } from './goal-spec-translator.mjs';
import { ProgramRuntimeManager } from './program-runtime-manager.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { PROVIDER_IDS } from './provider-identity.mjs';
import { TraceWriter } from './trace-writer.mjs';
import { wireRuntimeDiagnostics } from './runtime-diagnostics.mjs';
import { RuntimeErrorReporter } from './runtime-error-reporter.mjs';
import { BestEffortDiagnosticQueue } from './best-effort-diagnostic-queue.mjs';
import { RotatingJsonlSink } from './rotating-jsonl-sink.mjs';
import { sanitizeDiagnosticCode, sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText, sanitizeDiagnosticValue } from './diagnostic-sanitizer.mjs';
import { FishTtsProvider } from './voice/fish-tts-provider.mjs';
import { OpenAiTtsProvider, OpenAiSttProvider, DEFAULT_OPENAI_TTS_MODEL, DEFAULT_OPENAI_STT_MODEL } from './voice/openai-speech-provider.mjs';
import { DeepgramSttProvider, NoSttProvider } from './voice/deepgram-stt-provider.mjs';
import { LocalSpeechProvider } from './voice/local-speech-provider.mjs';
import { providerCacheNamespace, tagSynthesisCacheNamespace } from './voice/tts-cache-identity.mjs';
import { createVoiceHttpServer } from './voice/voice-http-server.mjs';
import { VoiceSupervisor } from './voice/voice-supervisor.mjs';
import { loadPersistentVoiceProfileStore, VoiceProfileStore } from './voice/voice-profile-store.mjs';
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
const TERMINAL_GOAL_SPEC_REJECTIONS = new Set(['UNKNOWN_GOAL_DRAFT', 'GOAL_DRAFT_AGENT_MISMATCH', 'STALE_GOAL_DRAFT']);
const QUIET_LIFECYCLE_ERRORS = new Set(['PLAN_CANCELLED', 'STALE_PLAN', 'STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION']);
const MAX_CONVERSATION_WAKE_TRANSACTIONS = 4_096;
const MAX_NATIVE_MOVEMENT_HISTORY = 12;
const MAX_NATIVE_RESOURCE_MEMORY = 512;
const DEFAULT_CONNECTION_OPERATION_CAP = 256;
const DEFAULT_AGENT_OPERATION_CAP = 32;
const MAX_PUBLIC_NARRATIVE_RAW_CHARS = 1_024;
const DEFAULT_MAX_PENDING_AGENT_OPERATIONS = 64;
const DEFAULT_MAX_PENDING_AGENT_TRANSACTIONS = 16;
const DEFAULT_VOICE_PORT = 8_766;
const DEFAULT_VOICE_MAX_CONCURRENT = 5;
const DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH = path.join('runtime', 'voice-profile-assignments.json');
const DEFAULT_VOICE_SECRET_PATH = path.join('runtime', 'voice-secret.txt');
const DEFAULT_LOCAL_SPEECH_TIMEOUT_MS = 120_000;
const DEFAULT_FISH_FALLBACK_BASE_DELAY_MS = 30_000;
const DEFAULT_FISH_FALLBACK_MAX_DELAY_MS = 300_000;
const VOICE_WARMUP_GRACE_MS = 1_000;
const DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE = 'FISH_AUDIO_API_KEY';
const DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE = 'DEEPGRAM_API_KEY';
const DEFAULT_LOCAL_SPEECH_PYTHON_DIRECTORY = path.join('runtime', 'local-speech', '.venv');
const STT_ONLY_PROFILE_STORE = Object.freeze({
	resolve() { throw new Error('voice profiles are unavailable without TTS'); },
});
const WINDOWS_TTS_FALLBACK_CODES = new Set([
	'LOCAL_SPEECH_ERROR',
	'LOCAL_SPEECH_UNAVAILABLE',
	'LOCAL_TTS_ERROR',
	'TTS_AUDIO_TOO_LONG',
	'TTS_AUTHENTICATION_FAILED',
	'TTS_MALFORMED_AUDIO',
	'TTS_PROVIDER_ERROR',
	'TTS_RATE_LIMITED',
	'TTS_TIMEOUT',
	'TTS_UNAVAILABLE',
]);

export class DynamicCoordinator extends EventEmitter {
	#registry;
	#taskViews;
	#scheduler;
	#codexService;
	#planner;
	#bridge;
	#listeners = [];
	#providerListeners = [];
	#agentOperations = new Map();
	#agentOperationCounts = new Map();
	#totalAgentOperations = 0;
	#connectionOperationCap;
	#agentOperationCap;
	#providerWork = new Map();
	#pendingAttention = new Map();
	#attentionFlushes = new Map();
	#lifecycleGenerations = new Map();
	#providerRetryAfter = new Map();
	#providerProbeDeadlines = new Map();
	#deferredProviderRecovery = new Map();
	#programRuntimeEpochs = new Map();
	#nativeRuntimeEpochs = new Map();
	#nativeProgramLeases = new Map();
	#verboseReporters = new Set();
	#factLedgers = new Map();
	#conversationMemories = new Map();
	#contextCursors = new Map();
	#pendingConversationInboxes = new Map();
	#memoryDirectory;
	#nativeConversationRecoveries = new Map();
	#nativeObservationSignatures = new Map();
	#nativeWorldSignals = new Map();
	#nativeConfirmationWaits = new Map();
	#supervisedObservationRequests = new Map();
	#conversationWakeTransactions = new Map();
	#directorRequests = new Set();
	#goalSpecRequests = new Map();
	#goalSpecRequestCap;
	#setGoalSpecTimeout;
	#clearGoalSpecTimeout;
	#programRuntime;
	#nativeRuntime;
	#inspections;
	#playerMemory;
	#memorySummaries = new Map();
	#receiptReconciliations = new Map();
	#latestActionDispatches = new Map();
	#goalSupervisor;
	#codexControlProtocol;
	#reconciliation = Promise.resolve();
	#started = false;
	#stopping = false;
	#closed = false;
	#stopPromise = null;
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
	#connectionEpoch = 0;
	#connected = false;
	#readyRegistry = [];
	#providerRecoveryPending = false;
	#traceWriter;
	#providerTurnRecorder;
	#verboseEnabled = false;
	#runtimeGeneration;
	#verboseTransitions = new ReportingTransitionDeduper();
	#maxPendingAgentOperations;
	#maxPendingAgentTransactions;
	#runtimeHooks;

	constructor({ registry, scheduler, codexService, planner, bridge, healthRegistry, latencyRegistry, goalSupervisor, codexControlProtocol = 'native_tools', memoryDirectory = null, runtimeSessionId, traceWriter = null, providerTurnRecorder = null, runtimeGeneration = null, runtimeHooks = {}, benchmarkRecorder = null, controlNow = () => performance.now(), epochNow = Date.now, setStatusInterval = defaultStatusInterval, clearStatusInterval = clearInterval, setGoalSpecTimeout = defaultGoalSpecTimeout, clearGoalSpecTimeout = clearTimeout, connectionOperationCap = DEFAULT_CONNECTION_OPERATION_CAP, agentOperationCap = DEFAULT_AGENT_OPERATION_CAP, goalSpecRequestCap = connectionOperationCap, maxPendingAgentOperations = DEFAULT_MAX_PENDING_AGENT_OPERATIONS, maxPendingAgentTransactions = DEFAULT_MAX_PENDING_AGENT_TRANSACTIONS }) {
		super();
		this.#memoryDirectory = memoryDirectory;
		this.#registry = requireDependency(registry, 'registry');
		this.#scheduler = requireDependency(scheduler, 'scheduler');
		this.#codexService = requireDependency(codexService, 'codexService');
		this.#planner = requireDependency(planner, 'planner');
		this.#bridge = requireDependency(bridge, 'bridge');
		this.#inspections = new InspectionClient({ send: (type, agentId, payload, options) => this.#sendForEpoch(options.connectionEpoch, type, agentId, payload) });
		this.#playerMemory = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory: memoryDirectory }), taskMemory: new TaskMemoryStore({ directory: memoryDirectory }), sessionId: runtimeSessionId });
		this.#taskViews = new LiveTaskViews({ directory: memoryDirectory ? path.join(memoryDirectory, 'plans') : null });
		this.#healthRegistry = requireDependency(healthRegistry, 'healthRegistry');
		this.#latencyRegistry = requireDependency(latencyRegistry, 'latencyRegistry');
		this.#goalSupervisor = requireDependency(goalSupervisor, 'goalSupervisor');
		if (traceWriter !== null && typeof traceWriter.write !== 'function') throw new TypeError('traceWriter.write must be a function');
		this.#traceWriter = traceWriter;
		if (providerTurnRecorder !== null && typeof providerTurnRecorder.close !== 'function') throw new TypeError('providerTurnRecorder.close must be a function');
		this.#providerTurnRecorder = providerTurnRecorder;
		this.#runtimeGeneration = runtimeGeneration;
		if (runtimeHooks === null || typeof runtimeHooks !== 'object' || Array.isArray(runtimeHooks)) throw new TypeError('runtimeHooks must be an object');
		if (runtimeHooks.onRemoved !== undefined && typeof runtimeHooks.onRemoved !== 'function') throw new TypeError('runtimeHooks.onRemoved must be a function');
		this.#runtimeHooks = runtimeHooks;
		this.#connectionOperationCap = positiveInteger(connectionOperationCap, 'connectionOperationCap');
		this.#agentOperationCap = positiveInteger(agentOperationCap, 'agentOperationCap');
		this.#goalSpecRequestCap = positiveInteger(goalSpecRequestCap, 'goalSpecRequestCap');
		if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codexControlProtocol must be arena_script or native_tools');
		this.#codexControlProtocol = codexControlProtocol;
		if (typeof controlNow !== 'function') throw new TypeError('controlNow must be a function');
		if (typeof epochNow !== 'function') throw new TypeError('epochNow must be a function');
		if (!Number.isSafeInteger(maxPendingAgentOperations) || maxPendingAgentOperations < 1) throw new TypeError('maxPendingAgentOperations must be a positive safe integer');
		if (!Number.isSafeInteger(maxPendingAgentTransactions) || maxPendingAgentTransactions < 1) throw new TypeError('maxPendingAgentTransactions must be a positive safe integer');
		this.#controlNow = controlNow;
		this.#epochNow = epochNow;
		this.#maxPendingAgentOperations = maxPendingAgentOperations;
		this.#maxPendingAgentTransactions = maxPendingAgentTransactions;
		const programBridge = {
			send: (type, agentId, payload) => this.#sendRuntimeMessage('program', type, agentId, payload),
		};
		const nativeBridge = {
			send: (type, agentId, payload) => this.#sendRuntimeMessage('native', type, agentId, payload),
		};
		this.#programRuntime = new ProgramRuntimeManager({
			sessionId: runtimeSessionId,
			memoryOperation: (record, operation) => this.#playerMemory.execute(record, operation),
			inspectObservation: async (record, query, authority = {}) => ({ state: 'SUCCEEDED', reasonCode: 'INSPECTED', ...await this.#inspections.request(record, query, { ...authority, connectionEpoch: this.#connectionEpoch }) }),
			registry: this.#registry,
			bridge: programBridge,
			planner: this.#planner,
			reportError: (agentId, error) => this.#reportAgentError(agentId, error, this.#programRuntimeEpochs.get(agentId)),
			requestRecovery: ({ record, reason, errorCode, recoveryKind, nextProbeAtEpochMs }) => {
				const connectionEpoch = this.#programRuntimeEpochs.get(record.agentId);
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return false;
				const current = this.#registry.get(record.agentId);
				if (current === null || current.goalRevision !== record.goalRevision) return false;
				const key = this.#supervisionKey(current);
				this.#goalSupervisor.activate(key);
				const details = this.#recoveryDetails(record.agentId, { errorCode, recoveryKind, nextProbeAtEpochMs });
				return this.#goalSupervisor.recover(key, { reason, ...details });
			},
			onCompletionRequested: (request) => this.#publishGoalCompleted(request, this.#programRuntimeEpochs.get(request.record.agentId)),
			latencyRegistry: this.#latencyRegistry,
			trace: (event, fields) => this.#writeTrace(event, fields),
			plannerContext: (agentId) => this.#plannerContext(agentId),
			onContextAccepted: (record, receipt) => this.#rememberAcceptedContextCursor(record, receipt),
			clock: () => this.#controlNow(),
			benchmarkRecorder,
		});
		this.#nativeRuntime = new NativeToolRuntime({
			taskPlan: (record, tool) => this.#taskViews.operate(record, tool),
			sessionId: runtimeSessionId,
			requestObservation: async (record) => {
				const result = await this.#inspections.request(record, { section: 'observation' }, { connectionEpoch: this.#connectionEpoch });
				return { ...result, observation: adaptObservation(result.observation) };
			},
			inspectObservation: (record, query, authority = {}) => this.#inspections.request(record, query, { ...authority, connectionEpoch: this.#connectionEpoch }),
			// Coordinator ingress owns authoritative terminal persistence/retries.
			// Avoid the runtime's best-effort duplicate write ahead of live delivery;
			// dispatch and unknown-receipt durability still use the real notebook.
			notebook: {
				...Object.fromEntries(['writeNote', 'query', 'listUnresolved', 'recordDispatch', 'recordUnknown', 'findReceipt'].map((method) => [method, this.#playerMemory.notebook[method].bind(this.#playerMemory.notebook)])),
				recordReceipt: async () => {},
			},
			memoryOperation: (record, operation) => this.#playerMemory.execute(record, operation),
			taskContext: (record) => this.#playerMemory.taskContext(record),
			memoryObservation: (record, observation) => this.#playerMemory.observe(record, observation),
			executionSettings: (record) => this.#planner.getExecutionSettings?.(record.agentId) ?? null,
			planningLeadTime: (record) => this.#planner.getNativeDecisionTiming?.(record.agentId)?.p95Ms ?? null,
			occupancy: new ExplorationOccupancy({ memoryStore: new ObservedMemoryStore({ directory: memoryDirectory }) }),
			bridge: nativeBridge,
			registry: this.#registry,
			trace: (event, fields) => this.#writeTrace(event, fields),
			onFinish: async ({ record, result, lifecycleGeneration }) => {
				const connectionEpoch = this.#nativeRuntimeEpochs.get(record.agentId);
				if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(connectionEpoch)) return;
				const current = this.#registry.get(record.agentId);
				if (current === null || current.goalRevision !== record.goalRevision
					|| !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return;
				if (result.state === 'COMPLETED') {
					this.#goalSupervisor.terminate(this.#supervisionKey(current, lifecycleGeneration));
					this.#registry.setState(record.agentId, DynamicAgentState.COMPLETED, { goalRevision: record.goalRevision });
				}
			},
			onWorkStarted: (record, kind, options) => {
				const key = this.#supervisionKey(record);
				this.#goalSupervisor.activate(key);
				const token = this.#goalSupervisor.begin(key, kind, options);
				const programLease = kind === 'program' ? { token, programId: options.programId } : null;
				if (programLease !== null) this.#nativeProgramLeases.set(record.agentId, programLease);
				let released = false;
				return () => {
					if (released) return;
					released = true;
					if (this.#nativeProgramLeases.get(record.agentId) === programLease) this.#nativeProgramLeases.delete(record.agentId);
					this.#goalSupervisor.end(token);
				};
			},
			onProgramEvent: (record, event) => {
				// Already-authorized successor work owns the body; no planner wake is needed.
				if (event.event === 'program_handoff_started') {
					this.#writeTrace('native_program_handoff_started', { agentId: record.agentId, goalRevision: record.goalRevision,
						programId: event.programId, predecessorProgramId: event.predecessorProgramId, queueId: event.queueId });
					return;
				}
				const connectionEpoch = this.#nativeRuntimeEpochs.get(record.agentId);
				const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
				const current = this.#registry.get(record.agentId);
				if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(connectionEpoch)
					|| current?.goalRevision !== record.goalRevision || ![DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(current.state)) return;
				this.#scheduleNativeTurn(current, { agentId: current.agentId, goalRevision: current.goalRevision,
					observation: event.observation, eventSequence: event.eventSequence, preserveState: true,
					priority: event.priority ?? event.status?.decision?.priority ?? 'ordinary', trigger: event.status?.decision?.trigger ?? event.event,
					connectionEpoch, lifecycleGeneration, nativeEvent: event });
			},
		});
		this.#setStatusInterval = requireDependency(setStatusInterval, 'setStatusInterval');
		this.#clearStatusInterval = requireDependency(clearStatusInterval, 'clearStatusInterval');
		this.#setGoalSpecTimeout = requireDependency(setGoalSpecTimeout, 'setGoalSpecTimeout');
		this.#clearGoalSpecTimeout = requireDependency(clearGoalSpecTimeout, 'clearGoalSpecTimeout');
	}

	get registry() { return this.#registry; }
	get bridge() { return this.#bridge; }

	requestSupervisedObservation(key) {
		if (this.#stopping || this.#closed || key === null || typeof key !== 'object') return false;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision
			|| !this.#isConnectionEpochCurrent(key.sessionEpoch)
			|| !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)
			|| key.profileFingerprint !== profileFingerprint(record)) return false;
		// DEAD snapshots are not admitted by the ordinary observation handler.
		// Rebuild the death request from current facts instead of requesting a sample
		// that cannot consume recovery. The lease supplies the retry backoff.
		if (this.#usesNativeTools(record) && record.state === DynamicAgentState.DEAD) {
			return this.#installDeadStatePlan(record, record.death, key.sessionEpoch);
		}
		const conversationRecovery = this.#nativeConversationRecoveries.get(key.agentId);
		if (conversationRecovery !== undefined && sameSupervisionKey(conversationRecovery.supervisionKey, key)) {
			this.#nativeConversationRecoveries.delete(key.agentId);
			this.#scheduleNativeTurn(record, conversationRecovery.request);
			return true;
		}
		if (this.#usesNativeTools(record)) {
			this.#supervisedObservationRequests.set(key.agentId, {
				goalRevision: key.goalRevision,
				lifecycleGeneration: key.lifecycleGeneration,
				connectionEpoch: key.sessionEpoch,
			});
		}
		return this.#bridge.send('request_observation', key.agentId, { goalRevision: key.goalRevision });
	}

	handleLeaseExpired({ key, lease }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const connectionEpoch = this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		if (key.sessionEpoch !== connectionEpoch || key.profileFingerprint !== profileFingerprint(record)) return;
		this.#writeTrace('work_lease_expired', { ...key, kind: lease.kind, operationId: lease.operationId });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', `${lease.kind} work timed out; recovering automatically.`);
		if (lease.kind === 'program') {
			const owned = this.#nativeProgramLeases.get(key.agentId);
			if (owned?.token.operationId === lease.operationId) {
				// The executor deadline and outer lease can fire in either order.
				// Settle only this program, retaining its physical cancellation fence.
				void this.#nativeRuntime.expireProgram(record, owned.programId)
					.catch((error) => this.#reportAgentError(key.agentId, error, connectionEpoch));
			}
			return;
		}
		let preserveProgram = false;
		if (lease.kind === 'provider') {
			const work = this.#providerWork.get(key.agentId);
			if (work?.kind === 'native' && work.goalRevision === key.goalRevision
				&& work.lifecycleGeneration === key.lifecycleGeneration
				&& work.supervisionToken?.operationId === lease.operationId) {
				preserveProgram = this.#preparationHasRunningProgram(work, record);
				work.expired = true;
				this.#restoreNativeConversation(work.steerRequest);
				this.#restoreNativeConversation(work.request);
				if (work.request.conversationOnly === true) {
					this.#nativeConversationRecoveries.set(work.agentId, {
						supervisionKey: work.supervisionKey,
						request: work.request,
					});
				}
				this.#providerWork.delete(key.agentId);
			}
			try {
				void Promise.resolve(this.#planner.interrupt(key.agentId, 'Provider work lease expired'))
					.catch((error) => this.#reportAgentError(key.agentId, error, connectionEpoch));
			} catch (error) {
				void this.#reportAgentError(key.agentId, error, connectionEpoch);
			}
		}
		if (['provider', 'action', 'completion'].includes(lease.kind) && !preserveProgram) {
			this.#nativeObservationSignatures.delete(key.agentId);
			void this.#nativeRuntime.dispose(key.agentId, `${lease.kind}_lease_expired`)
				.catch((error) => this.#reportAgentError(key.agentId, error, connectionEpoch));
		}
	}

	handleGoalStuck({ key, inactiveMs, history }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		if (key.sessionEpoch !== this.#connectionEpoch || key.profileFingerprint !== profileFingerprint(record)) return;
		this.#rememberPendingAttention(key.agentId, key.goalRevision, { priority: 'urgent', trigger: 'stuck' });
		this.#writeTrace('goal_factual_progress_stuck', { ...key, inactiveMs, positionSamples: history.length });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', 'No factual world progress for 30 seconds; reassessing without pausing the goal.');
	}

	async start() {
		if (this.#started) return;
		if (this.#closed) throw new Error('Dynamic coordinator cannot restart after it has been stopped');
		this.#stopping = false;
		try {
			this.#bindBridge();
			this.#bridge.start();
			this.#statusHandle = this.#setStatusInterval(() => {
				const connectionEpoch = this.#connectionEpoch;
				this.#requestProviderRecovery(connectionEpoch);
				this.#run(() => this.#publishStatus(connectionEpoch), connectionEpoch);
			}, 1_000);
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

	stop() {
		if (this.#stopPromise !== null) return this.#stopPromise;
		this.#stopPromise = this.#stopOnce();
		return this.#stopPromise;
	}

	async #stopOnce() {
		if (this.#closed) return;
		this.#stopping = true;
		const receipts = [...this.#receiptReconciliations.values()];
		for (const receipt of receipts) { clearTimeout(receipt.retry); receipt.resolve(); }
		this.#inspections.cancel();
		this.#connected = false;
		this.#setVerboseEnabled(false);
		if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
		this.#statusHandle = null;
		this.#bridge.stop();
		this.#unbindBridge();
		this.#scheduler.close('Dynamic coordinator stopped');
		this.#goalSupervisor.close();
		await Promise.allSettled([this.#reconciliation, ...[...this.#agentOperations.values()].map((queue) => queue.drainPromise)]);
		await Promise.all([...this.#pendingConversationInboxes.values()].map((inbox) => inbox.close().catch((error) => this.#emitRuntimeError(error))));
		this.#pendingConversationInboxes.clear();
		this.#agentOperations.clear();
		this.#agentOperationCounts.clear();
		this.#totalAgentOperations = 0;
		this.#providerWork.clear();
		this.#pendingAttention.clear();
		this.#attentionFlushes.clear();
		this.#lifecycleGenerations.clear();
		this.#programRuntime.disposeAll();
		// Release native waiters immediately, independently of stalled terminal I/O.
		const nativeDisposal = this.#nativeRuntime.disposeAll();
		const closeMemory = async () => {
			await Promise.all([Promise.allSettled(receipts.map((receipt) => receipt.pending)), nativeDisposal]);
			await this.#playerMemory.markUnknown(undefined, 'COORDINATOR_STOPPED');
			await this.#playerMemory.flush();
		};
		// A stuck receipt write cannot hold shutdown forever. Unacknowledged
		// terminals remain in the server journal for replay on the next session.
		let receiptShutdownTimer;
		await Promise.race([
			closeMemory().catch((error) => this.#emitRuntimeError(error)),
			...(receipts.length === 0 ? [] : [new Promise((resolve) => {
				receiptShutdownTimer = setTimeout(() => {
					this.#emitRuntimeError(codedRuntimeError('RECEIPT_SHUTDOWN_PENDING', 'Receipt storage has not settled; server results remain unacknowledged for replay'));
					resolve();
				}, 1_000);
			})]),
		]);
		clearTimeout(receiptShutdownTimer);
		this.#receiptReconciliations.clear();
		this.#latestActionDispatches.clear();
		for (const receipt of receipts) receipt.resolve();
		await this.#taskViews.flush().catch((error) => this.#emitRuntimeError(error));
		this.#memorySummaries.clear();
		this.#providerRetryAfter.clear();
		this.#providerProbeDeadlines.clear();
		this.#deferredProviderRecovery.clear();
		this.#programRuntimeEpochs.clear();
		this.#nativeRuntimeEpochs.clear();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		this.#nativeConversationRecoveries.clear();
		this.#nativeObservationSignatures.clear();
		this.#nativeWorldSignals.clear();
		this.#nativeConfirmationWaits.clear();
		this.#supervisedObservationRequests.clear();
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
		this.#listen('inspection_result', (message) => { this.#inspections.accept(message); });
		this.#bindProviderRecovery();
		this.#listen('ready', (connection) => {
			const connectionEpoch = this.#acceptReadyEpoch(connection);
			if (connectionEpoch === null) return;
			const { serverInstanceId, registry } = connection;
			this.#setVerboseEnabled(false);
			if (this.#serverInstanceId !== null && serverInstanceId !== this.#serverInstanceId) {
				this.#invalidateServerInstance(connectionEpoch);
			}
			this.#serverInstanceId = serverInstanceId;
			this.#readyRegistry = structuredClone(registry);
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#beginReconciliation(registry, connectionEpoch);
		}, { lifecycle: true });
		this.#listen('verbose_control', (message) => {
			this.#setVerboseEnabled(message.payload.enabled);
		});
		this.#listen('catalog_request', (_message, connectionEpoch) => this.#run(async () => {
			await this.#reconciliation;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			const catalog = await this.#codexService.catalog.refresh({ force: true });
			await this.#publishCatalog(catalog, connectionEpoch);
		}, connectionEpoch));
		this.#listen('agent_registered', (message, connectionEpoch) => this.#enqueueAgent(message.agentId, async () => {
			const previous = this.#registry.get(message.agentId);
			const record = this.#registry.register(message.payload.record === undefined
				? { ...message.payload, agentId: message.agentId }
				: { ...message.payload.record, agentId: message.agentId });
			this.#codexService.catalog.assertSupported(record.provider, record.model, record.reasoningEffort, record.serviceTier ?? DEFAULT_SERVICE_TIER);
			const replacesDeath = previous?.state === DynamicAgentState.DEAD && record.state === DynamicAgentState.DEAD
				&& record.goalRevision > previous.goalRevision;
			let lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
			const isCurrent = () => this.#isConnectionEpochCurrent(connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)
				&& this.#registry.get(record.agentId)?.goalRevision === record.goalRevision;
			if (replacesDeath) {
				this.#cancelGoalSpecRequests(record.agentId);
				this.#retireGoalSupervision(previous, 'replace');
				this.#invalidateAcceptedLifecycle(record.agentId);
				lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
				this.#beginGoalControlInterruption({ agentId: record.agentId, payload: { operation: 'steer' } }, connectionEpoch);
				this.#programRuntime.onGoalControl(previous, 'steer');
				await this.#nativeRuntime.dispose(record.agentId, 'dead_registration_replaced');
				if (!isCurrent()) return;
			}
			await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
			if (!isCurrent()) return;
			this.#supportedAgentIds.add(record.agentId);
			this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent registered and ready.', connectionEpoch);
			this.#prewarmNativeAgent(record);
			if (replacesDeath) {
				const recovery = this.#installDeadStatePlan(record, record.death, connectionEpoch);
				if (this.#usesNativeTools(record)) void recovery.catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
				else await recovery;
			}
			await this.#publishStatus(connectionEpoch);
		}, { connectionEpoch, transactional: true }));
		this.#listen('agent_removed', (message, connectionEpoch) => this.#run(async () => {
			await this.#reconciliation;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#publishVerbose(message.agentId, message.payload.goalRevision, 'lifecycle', 'Agent removed from the coordinator roster.', connectionEpoch);
			// Removal also owns persisted mailboxes not yet opened after restart.
			const inbox = this.#pendingConversationInbox(message.agentId);
			inbox.fence();
			const current = this.#registry.get(message.agentId);
			if (current !== null) this.#goalSupervisor.terminate(this.#supervisionKey(current));
			this.#programRuntime.dispose(message.agentId);
			await this.#nativeRuntime.dispose(message.agentId, 'agent_removed');
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#providerWork.delete(message.agentId);
			this.#verboseTransitions.clear(message.agentId);
			this.#programRuntimeEpochs.delete(message.agentId);
			this.#nativeRuntimeEpochs.delete(message.agentId);
			this.#pendingAttention.delete(message.agentId);
			this.#attentionFlushes.delete(message.agentId);
			await this.#planner.remove(message.agentId);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#lifecycleGenerations.delete(message.agentId);
			this.#providerRetryAfter.delete(message.agentId);
			this.#providerProbeDeadlines.delete(message.agentId);
			this.#deferredProviderRecovery.delete(message.agentId);
			this.#factLedgers.delete(message.agentId);
			this.#memorySummaries.delete(message.agentId);
			this.#playerMemory.forget(message.agentId);
			this.#conversationMemories.delete(message.agentId);
			this.#contextCursors.delete(message.agentId);
			await inbox.remove(() => this.#isConnectionEpochCurrent(connectionEpoch));
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#nativeConversationRecoveries.delete(message.agentId);
			this.#nativeObservationSignatures.delete(message.agentId);
			this.#nativeWorldSignals.delete(message.agentId);
			this.#nativeConfirmationWaits.delete(message.agentId);
			this.#supervisedObservationRequests.delete(message.agentId);
			this.#forgetConversationWakes(message.agentId);
			this.#cancelGoalSpecRequests(message.agentId);
			this.#supportedAgentIds.delete(message.agentId);
			try {
				void Promise.resolve(this.#runtimeHooks.onRemoved?.(message.agentId)).catch((error) => this.#emitRuntimeError(error));
			} catch (error) { this.#emitRuntimeError(error); }
			await this.#publishStatus(connectionEpoch);
		}, connectionEpoch));
        this.#listen('director_script_request', (message, connectionEpoch) => {
            const request=message.payload;
            if(this.#directorRequests.has(request.requestId)) return;
            this.#run(async () => {
                let script='', error='';
                if(this.#directorRequests.has(request.requestId)) return;
                if(this.#directorRequests.size>=4) error='Luna is busy. Try again shortly.';
                else {
                    this.#directorRequests.add(request.requestId);
                    try {
                        const value=await this.#scheduler.schedule(`director-${request.requestId}`, ({signal})=>generateDirectorScript(this.#codexService,request,{signal}), {lane:'codex',priority:'ordinary',capacityClass:'auxiliary'});
                        script=JSON.stringify(value);
                    } catch(failure) { error=('Luna could not generate this script: '+(failure.message??'Unknown error')).slice(0,400); }
                    finally { this.#directorRequests.delete(request.requestId); }
                }
                if(this.#isConnectionEpochCurrent(connectionEpoch)) await this.#sendForEpoch(connectionEpoch,'director_script_result','server',{requestId:request.requestId,script,error});
            },connectionEpoch);
        });
		this.#listen('goal_spec_request', (message, connectionEpoch) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const fingerprint = JSON.stringify(message.payload);
			const existing = this.#goalSpecRequests.get(key);
			if (existing !== undefined) {
				if (existing.fingerprint !== fingerprint) {
					this.#emitRuntimeError(new ProtocolV2Error('TRANSACTION_COLLISION', `Goal translation request '${message.payload.requestId}' changed during replay`));
					return;
				}
				if (existing.proposal !== null) {
					void this.#sendForEpoch(connectionEpoch, 'goal_spec_proposal', message.agentId, existing.proposal).catch((error) => this.#emitRuntimeError(error));
				}
				return existing.completion;
			}
			if (this.#goalSpecRequests.size >= this.#goalSpecRequestCap) {
				throw new ProtocolV2Error('GOAL_SPEC_REQUEST_BACKPRESSURE', 'Coordinator goal translation request capacity is full');
			}
			const releaseCapacity = this.#reserveAgentOperation(message.agentId);
			let resolveCompletion;
			const completion = new Promise((resolve) => { resolveCompletion = resolve; });
			const entry = {
				agentId: message.agentId, requestId: message.payload.requestId, request: message.payload,
				fingerprint, proposal: null, attempts: 0, rejectionAttempts: 0, correctiveFeedback: null, serverRejected: false,
				translating: false, retryHandle: null, connectionEpoch, completion, resolveCompletion, releaseCapacity,
			};
			this.#goalSpecRequests.set(key, entry);
			const initialProcessing = this.#run(() => this.#processGoalSpecRequest(key, entry), connectionEpoch);
			return Promise.all([initialProcessing, completion]);
		});
		this.#listen('goal_spec_result', (message, connectionEpoch) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const existing = this.#goalSpecRequests.get(key);
			if (existing === undefined || existing.proposal === null) return;
			if (message.payload.status === 'accepted' || TERMINAL_GOAL_SPEC_REJECTIONS.has(message.payload.reasonCode)) {
				this.#forgetGoalSpecRequest(key, existing);
				return;
			}
			if (existing.retryHandle !== null) {
				this.#clearGoalSpecTimeout(existing.retryHandle);
				existing.retryHandle = null;
			}
			existing.rejectionAttempts += 1;
			existing.serverRejected = true;
			existing.correctiveFeedback = {
				attempt: existing.rejectionAttempts,
				reasonCode: message.payload.reasonCode,
				rejectedProposal: existing.proposal,
			};
			existing.proposal = null;
			try { this.#planner.cancelGoalSpec?.(message.agentId, message.payload.requestId); } catch { /* completed translation cleanup is best effort */ }
			if (existing.rejectionAttempts <= MAX_GOAL_SPEC_CORRECTION_ATTEMPTS) {
				const delay = Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** (existing.rejectionAttempts - 1)));
				this.#scheduleGoalSpecRequest(key, existing, delay);
				return;
			}
			this.#forgetGoalSpecRequest(key, existing);
			void this.#reportAgentError(message.agentId, codedRuntimeError(
				'GOAL_SPEC_TRANSLATION_REJECTED',
				`Minecraft rejected ${MAX_GOAL_SPEC_CORRECTION_ATTEMPTS + 1} goal translation proposals; the pending draft requires operator correction or cancellation`,
			), connectionEpoch);
		});
		this.#listen('task_view_request', (message, connectionEpoch) => {
			const record = this.#registry.get(message.agentId);
			if (record === null || record.goalRevision !== message.payload.goalRevision) return;
			return this.#sendForEpoch(connectionEpoch, 'task_view', record.agentId, this.#taskViews.snapshot(record));
		});
		this.#listen('goal_control', (message, connectionEpoch) => {
			let record;
			let nativeDisposal = Promise.resolve();
			let lifecycleGeneration;
			return this.#enqueueAgent(message.agentId, async () => {
				await nativeDisposal;
				const acceptedStillCurrent = () => {
					const current = this.#registry.get(message.agentId);
					return this.#isConnectionEpochCurrent(connectionEpoch)
						&& current !== null && current.goalRevision === record.goalRevision
						&& this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration);
				};
				if (!acceptedStillCurrent()) return;
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', `Goal lifecycle operation '${message.payload.operation}' accepted.`, connectionEpoch);
				if (!['queue', 'dequeue'].includes(message.payload.operation)) {
					this.#providerRetryAfter.delete(message.agentId);
				}
				if (message.payload.operation === 'dead') {
					void this.#installDeadStatePlan(record, message.payload.death, connectionEpoch).catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
				}
				const resumesGoal = ['start', 'replace', 'resume', 'steer'].includes(message.payload.operation)
					|| message.payload.operation === 'respawn' && record.state === DynamicAgentState.STARTING;
				const activatesQueuedGoal = message.payload.operation === 'complete' && record.state === DynamicAgentState.STARTING;
				if (resumesGoal || activatesQueuedGoal) {
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				if (resumesGoal) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, {
						priority: message.payload.operation === 'steer' ? 'urgent' : 'ordinary',
						trigger: message.payload.operation,
					});
				}
				if (resumesGoal) {
					await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
				}
				if (!acceptedStillCurrent()) return;
				if (message.payload.operation === 'complete' && record.state === DynamicAgentState.COMPLETED) {
					await this.#resumeUnreadNativeConversation(record, connectionEpoch, lifecycleGeneration);
					if (!acceptedStillCurrent()) return;
				}
				this.emit('goalControl', record);
			}, {
				connectionEpoch,
				transactional: true,
				onAdmitted: () => {
					const previous = this.#registry.get(message.agentId);
					record = this.#registry.applyGoalControl(message.agentId, message.payload);
					if (['start','replace','steer'].includes(message.payload.operation)) this.#taskViews.begin(record, { fresh: ['start','replace'].includes(message.payload.operation) });
					if (message.payload.operation === 'dead') void this.#taskViews.observe(record, { ready: false, player: { dead: true } }).catch(() => {});
					const lifecycleChanged = previous !== null && !['queue', 'dequeue'].includes(message.payload.operation)
						&& (record.goalRevision > previous.goalRevision
							|| (record.goalRevision === previous.goalRevision && ['dead', 'respawn'].includes(message.payload.operation)));
					if (lifecycleChanged) {
						this.#cancelGoalSpecRequests(message.agentId);
						this.#retireGoalSupervision(previous, message.payload.operation);
						this.#invalidateAcceptedLifecycle(message.agentId);
						this.#beginGoalControlInterruption(message, connectionEpoch);
						this.#programRuntime.onGoalControl(previous, message.payload.operation);
						nativeDisposal = Promise.resolve(this.#nativeRuntime.dispose(previous.agentId, `goal_${message.payload.operation}`));
					}
					lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
				},
			});
		});
		this.#listen('conversation_event', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.goalRevision, 'conversation', `Conversation event '${message.payload.kind}' received from '${message.payload.sourceId}'.`, connectionEpoch);
				const ingested = await this.#admitConversation(message.agentId, message.payload, connectionEpoch);
				const record = this.#registry.get(message.agentId);
				if (ingested && record !== null) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation' });
					if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload, 'conversation');
					else {
						if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state)) {
							this.#goalSupervisor.activate(this.#supervisionKey(record));
						}
						this.#schedulePendingAttentionFlush(record);
					}
				}
				this.emit('conversationEvent', message);
			}, { waitForReconciliation: false, connectionEpoch, transactional: true });
		});
		this.#listen('conversation_wake', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.event.goalRevision, 'conversation', `Conversation wake '${message.payload.event.kind}' received.`, connectionEpoch);
				const fingerprint = JSON.stringify({ agentId: message.agentId, event: message.payload.event, control: message.payload.control });
				const existing = this.#conversationWakeTransactions.get(message.payload.transactionId);
				if (existing !== undefined) {
					if (existing.fingerprint !== fingerprint) {
						throw new ProtocolV2Error('TRANSACTION_COLLISION', `Conversation wake '${message.payload.transactionId}' changed during replay`);
					}
					const record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
					this.#goalSupervisor.activate(this.#supervisionKey(record));
					this.#providerRetryAfter.delete(message.agentId);
					await this.#sendForEpoch(connectionEpoch, 'conversation_wake_ack', message.agentId, {
						transactionId: message.payload.transactionId,
						goalRevision: record.goalRevision,
					});
					await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
					return;
				}
				const previous = this.#registry.get(message.agentId);
				if (this.#usesNativeTools(previous)) {
					await this.#pendingConversationInbox(message.agentId).checkWake(this.#serverInstanceId, message.payload.event,
						{ transactionId: message.payload.transactionId, fingerprint });
					if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
				}
				let record;
				try {
					record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
				} catch (error) {
					if (error instanceof AgentRegistryError && ['STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION', 'INVALID_AGENT_STATE'].includes(error.code)) return;
					throw error;
				}
				if (previous !== null && record.goalRevision > previous.goalRevision) {
					this.#retireGoalSupervision(previous, message.payload.control.operation);
					this.#invalidateAcceptedLifecycle(message.agentId);
					this.#programRuntime.onGoalControl(previous, message.payload.control.operation);
					await this.#nativeRuntime.dispose(previous.agentId, 'conversation_wake');
					if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
				}
				await this.#admitConversation(message.agentId, message.payload.event, connectionEpoch,
					{ transactionId: message.payload.transactionId, fingerprint });
				this.#goalSupervisor.activate(this.#supervisionKey(record));
				this.#providerRetryAfter.delete(message.agentId);
				this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation_wake' });
				if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload.event, 'conversation_wake');
				else this.#schedulePendingAttentionFlush(record);
				this.#rememberConversationWake(message.payload.transactionId, message.agentId, fingerprint);
				await this.#sendForEpoch(connectionEpoch, 'conversation_wake_ack', record.agentId, {
					transactionId: message.payload.transactionId,
					goalRevision: record.goalRevision,
				});
				await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
				this.emit('conversationEvent', { ...message, payload: message.payload.event });
				this.emit('goalControl', record);
			}, { waitForReconciliation: false, connectionEpoch, transactional: true });
		});
		this.#listen('observation', (message, connectionEpoch) => {
			const receiptMonotonicMs = safeClockRead(this.#controlNow);
			const receiptEpochMs = safeClockRead(this.#epochNow);
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			return this.#enqueueAgent(message.agentId, async () => {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
				if (!this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration)) return;
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				if (![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
				const wireObservation = message.payload.observation ?? message.payload;
				const supervisionKey = this.#supervisionKey(record, lifecycleGeneration);
				this.#goalSupervisor.factualProgress(supervisionKey, factualProgressSignature(wireObservation), factualProgressDetails(wireObservation));
				if (this.#usesNativeTools(record)) this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
				const observation = adaptObservation(wireObservation);
				void this.#taskViews.observe(record, observation).catch((error) => this.#writeTrace('task_view_observation_error', { code: error?.code ?? 'PLAN_VIEW_ERROR' }));
				const refreshMemorySummary = () => {
					const memoryKey = `${record.goalRevision}:${this.#playerMemory.worldId(record)}`;
					if (this.#memorySummaries.get(record.agentId)?.key !== memoryKey) {
						return this.#playerMemory.unresolved(record, { limit: 8 }).then((unresolved) => {
							if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return false;
							this.#memorySummaries.set(record.agentId, { key: memoryKey, unresolved });
							return true;
						});
					}
				};
				const worldSignals = this.#usesNativeTools(record)
					? this.#rememberNativeWorldSignals(record, lifecycleGeneration, connectionEpoch, observation)
					: null;
				const classified = classifyObservationTrigger(message.payload, wireObservation, worldSignals);
				const pendingAttention = this.#pendingAttention.get(record.agentId);
				const attention = pendingAttention?.goalRevision === record.goalRevision
					? mergeAttentionTrigger(classified, pendingAttention)
					: classified;
				if (pendingAttention?.goalRevision === record.goalRevision) this.#pendingAttention.delete(record.agentId);
				const ledger = this.#ledger(record.agentId);
				ledger.ingest('observation', wireObservation);
				if (this.#usesNativeTools(record)) {
					const observationSignature = nativeObservationSignature(observation);
					const previousSignature = this.#nativeObservationSignatures.get(record.agentId);
					const supervisedRequest = this.#supervisedObservationRequests.get(record.agentId);
					const forcedContinuation = supervisedRequest?.goalRevision === record.goalRevision
						&& supervisedRequest.lifecycleGeneration === lifecycleGeneration
						&& supervisedRequest.connectionEpoch === connectionEpoch;
					if (forcedContinuation) this.#supervisedObservationRequests.delete(record.agentId);
					const unchangedHeartbeat = attention.attention === false && !forcedContinuation
						&& previousSignature?.goalRevision === record.goalRevision
						&& previousSignature.lifecycleGeneration === lifecycleGeneration
						&& previousSignature.connectionEpoch === connectionEpoch
						&& previousSignature.signature === observationSignature;
					this.#nativeObservationSignatures.set(record.agentId, {
						goalRevision: record.goalRevision,
						lifecycleGeneration,
						connectionEpoch,
						signature: observationSignature,
					});
					const memory = this.#conversationMemory(record.agentId);
					const conversation = memory.history();
					if (unchangedHeartbeat) {
						if (this.#nativeRuntime.refreshObservation(record, observation, { eventSequence: message.payload.eventSequence, conversation })) return;
					}
					const accepted = this.#nativeRuntime.updateObservation(record, observation, { eventSequence: message.payload.eventSequence, conversation, attention: attention.attention, priority: attention.priority, trigger: attention.trigger, changedFacts: message.payload.changedFacts });
					// Accepted native snapshots are ingested by the runtime callback, which
					// also preserves requested samples and enriched sparse death facts.
					if (!accepted) this.#playerMemory.observe(record, observation);
					const pendingSummary = refreshMemorySummary();
					if (pendingSummary !== undefined && !await pendingSummary) return;
					this.#goalSupervisor.observed(supervisionKey);
					// The program owns ordinary progress. It explicitly notifies the model
					// when reconsideration or a new intention is needed.
					if (this.#nativeRuntime.hasProgram(record) && !forcedContinuation && pendingAttention?.goalRevision !== record.goalRevision) return;
					this.#scheduleNativeTurn(record, {
						agentId: record.agentId,
						goalRevision: record.goalRevision,
						observation,
						memory: this.#memorySummaries.get(record.agentId)?.unresolved,
						executionSettings: this.#planner.getExecutionSettings?.(record.agentId) ?? null,
						eventSequence: message.payload.eventSequence,
						priority: attention.priority,
						trigger: attention.trigger,
						lifecycleGeneration,
						connectionEpoch,
						nativeEvent: { event: 'observation', trigger: forcedContinuation ? 'continuation' : attention.trigger, observation },
					});
					return;
				}
				this.#playerMemory.observe(record, observation);
				const pendingSummary = refreshMemorySummary();
				if (pendingSummary !== undefined && !await pendingSummary) return;
				this.#goalSupervisor.observed(supervisionKey);
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
					connectionEpoch,
					...buildPlannerRequest({
						agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
						goal: record.currentGoal,
						goalRevision: record.goalRevision,
						attentionPriority: attention.priority,
						attentionTrigger: attention.trigger,
						observation,
						memory: this.#memorySummaries.get(record.agentId)?.unresolved,
						executionSettings: this.#planner.getExecutionSettings?.(record.agentId) ?? null,
					}, this.#plannerContext(record.agentId)),
				});
			}, {
				connectionEpoch,
				coalesceKey: message.payload.attention === false
					&& message.payload.trigger === undefined
					&& !this.#pendingAttention.has(message.agentId)
					&& !this.#supervisedObservationRequests.has(message.agentId)
					? `quiet-observation:${connectionEpoch}:${lifecycleGeneration}:${message.payload.goalRevision}`
					: null,
			});
		});
		this.#listen('action_progress', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				const nativeWork = this.#providerWork.get(message.agentId);
				if (this.#usesNativeTools(record) && nativeWork?.goalRevision === record.goalRevision) {
					this.#goalSupervisor.progress(nativeWork.supervisionToken);
					for (const call of nativeWork.toolSupervision.values()) {
						if (call.token !== null) this.#goalSupervisor.progress(call.token);
					}
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionProgress(record, message.payload)) {
					this.emit('actionProgress', message);
					return;
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) return;
				if (!this.#programRuntime.onActionProgress(record, message.payload)) throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
				this.emit('actionProgress', message);
			}, { connectionEpoch });
		});
		this.#listen('action_result', (message, connectionEpoch) => {
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			let receipt;
			return this.#enqueueAgent(message.agentId, async () => {
				const dispatch = this.#latestActionDispatches.get(message.agentId);
				if (dispatch?.connectionEpoch === connectionEpoch && dispatch.actionId === message.payload.actionId
					&& dispatch.goalRevision === message.payload.goalRevision && message.payload.actionType !== undefined
					&& dispatch.actionType !== message.payload.actionType) {
					// Invalid receipt evidence must neither enter live facts nor fail valid
					// gameplay through the agent-error/cancellation path. No disk read here.
					this.#emitRuntimeError(codedRuntimeError('UNCORRELATED_ACTION_RECEIPT', 'Terminal action type does not match its live dispatch; withholding ACK'));
					return;
				}
				// Live correlation must not wait for disk. The retained server result is
				// acknowledged separately, only after authoritative storage succeeds.
				receipt = this.#queueReceiptReconciliation(message, connectionEpoch);
				try {
					const current = this.#registry.get(message.agentId);
					if (current === null || message.payload.goalRevision !== current.goalRevision
						|| !this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration)) {
						return;
					}
					const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
					this.#ledger(record.agentId).ingest('action_result', message.payload);
					if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionResult(record, message.payload)) {
						this.emit('actionResult', message);
						return;
					}
					if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) {
						return;
					}
					if (!await this.#programRuntime.onActionResult(record, message.payload)) {
						if (this.#programRuntime.isActionResultStale(record, message.payload)) {
							return;
						}
						throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
					}
					this.emit('actionResult', message);
				} finally {
					this.#reconcileReceipt(receipt);
				}
			}, { connectionEpoch, terminal: true });
		});
		this.#listen('goal_completion_result', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				const current = this.#registry.get(message.agentId);
				if (current === null || current.goalRevision !== message.payload.goalRevision) return;
				if (this.#usesNativeTools(current) && this.#nativeRuntime.onCompletionResult(current, message.payload)) { if (message.payload.verified) this.#taskViews.verified(current); return; }
				const accepted = this.#programRuntime.onCompletionResult(current, message.payload);
				if (!accepted) throw new ProtocolV2Error('UNEXPECTED_COMPLETION_RESULT', `Agent '${message.agentId}' has no matching completion request`);
				if (message.payload.verified) this.#taskViews.verified(current);
			}, { connectionEpoch, transactional: true });
		});
		this.#listen('disconnected', (event) => {
			const connectionEpoch = this.#eventConnectionEpoch(event);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#inspections.cancel(undefined, 'BRIDGE_DISCONNECTED');
			void this.#playerMemory.markUnknown(undefined, 'BRIDGE_DISCONNECTED').catch((error) => this.#emitRuntimeError(error));
			this.#connected = false;
			this.#retireReceiptReconciliations();
            for(const requestId of this.#directorRequests) this.#scheduler.cancel(`director-${requestId}`, 'Director connection closed');
			this.#setVerboseEnabled(false);
			this.#cancelGoalSpecRequests();
			for (const record of this.#registry.list()) {
				const work = this.#providerWork.get(record.agentId);
				if (work?.kind === 'native') {
					this.#restoreNativeConversation(work.steerRequest);
					this.#restoreNativeConversation(work.request);
				}
				this.#nativeConversationRecoveries.delete(record.agentId);
				this.#goalSupervisor.suspend(this.#supervisionKey(record));
				this.#advanceLifecycleGeneration(record.agentId);
			}
			this.#run(async () => {
			if (this.#connectionEpoch !== connectionEpoch) return;
			this.#disconnectedAt ??= safeClockRead(this.#controlNow);
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#programRuntimeEpochs.clear();
			this.#nativeRuntimeEpochs.clear();
			this.#programRuntime.disposeAll();
			void this.#nativeRuntime.disposeAll('bridge_disconnected');
			this.#pendingAttention.clear();
			this.#attentionFlushes.clear();
			this.#providerRetryAfter.clear();
			this.#providerProbeDeadlines.clear();
			this.#deferredProviderRecovery.clear();
			this.#providerWork.clear();
			await Promise.allSettled(this.#registry.list().map(async (record) => {
				if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) {
					this.#registry.setState(record.agentId, DynamicAgentState.DISCONNECTED, { goalRevision: record.goalRevision });
				}
				await this.#planner.interrupt(record.agentId, 'Minecraft bridge disconnected');
			}));
			}, connectionEpoch, { requireConnected: false });
		}, { lifecycle: true });
		this.#listen('shutdown', () => {
			this.#run(async () => {
				try { this.emit('shutdown'); }
				finally { await this.stop(); }
			});
		});
		this.#listen('protocolError', (error) => this.emit('runtimeError', error), { lifecycle: true });
		this.#listen('transportError', (error) => this.emit('runtimeError', error), { lifecycle: true });
	}

	#beginReconciliation(registry, connectionEpoch) {
		let startedReconciliation;
		try {
			startedReconciliation = this.#planner.beginReconcile(registry, { recovery: true });
		} catch (error) {
			startedReconciliation = { complete: Promise.reject(error) };
		}
		const reconciliation = Promise.resolve().then(async () => {
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			if (typeof this.#codexService.bootstrapCatalog === 'function') {
				const catalog = await this.#codexService.bootstrapCatalog(registry);
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				await this.#publishCatalog(catalog, connectionEpoch);
			}
			const result = await startedReconciliation.complete;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			const providers = result.providers ?? result.codex;
			await this.#publishCatalog(providers.catalog, connectionEpoch);
			const deadStatePlans = [];
			for (const profile of providers.valid) {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				const record = this.#registry.get(profile.agentId);
				if (record === null) throw new ProtocolV2Error('UNKNOWN_AGENT', `Reconciled provider profile references unknown agent '${profile.agentId}'`);
				const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
				if (this.#supportedAgentIds.has(profile.agentId)) continue;
				if (record.state === DynamicAgentState.STARTING && record.currentGoal !== null) {
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				await this.#sendForEpoch(connectionEpoch, 'agent_ready', profile.agentId, { goalRevision: record.goalRevision, reconciled: true });
				this.#supportedAgentIds.add(profile.agentId);
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent reconciled and ready.', connectionEpoch);
				this.#prewarmNativeAgent(record);
				if (this.#usesNativeTools(record) && [DynamicAgentState.IDLE, DynamicAgentState.PAUSED, DynamicAgentState.COMPLETED].includes(record.state)) {
					await this.#resumeUnreadNativeConversation(record, connectionEpoch, lifecycleGeneration);
				}
				if (record.state === DynamicAgentState.DEAD) {
					const recovery = this.#installDeadStatePlan(record, record.death, connectionEpoch);
					// Native recovery is already supervised. Its body may need receipts
					// admitted only after reconciliation, so readiness cannot join the turn.
					if (this.#usesNativeTools(record)) void recovery.catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
					else deadStatePlans.push(recovery);
				}
			}
			for (const invalid of providers.invalid) {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				const agentId = invalid.agentId ?? invalid.profile?.agentId;
				await this.#sendForEpoch(connectionEpoch, 'agent_error', agentId, {
					goalRevision: this.#registry.get(agentId)?.goalRevision ?? 0,
					code: sanitizeDiagnosticCode(invalid.code, { fallback: 'INVALID_PROFILE' }),
					message: sanitizeDiagnosticText(invalid.message ?? 'Provider profile is unavailable.', { maxBytes: 2_048 }),
				});
			}
			await Promise.all(deadStatePlans);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			this.#reconciledStatus = this.#readyRegistry.every(({ agentId }) => this.#supportedAgentIds.has(agentId));
			if (this.#disconnectedAt !== null) this.#disconnectedAt = null;
			await this.#publishStatus(connectionEpoch);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			this.emit('reconciled', result);
			return result;
		});
		this.#reconciliation = reconciliation.catch((error) => {
			if (this.#isConnectionEpochCurrent(connectionEpoch)) this.#emitRuntimeError(error);
			return null;
		});
	}

	#bindProviderRecovery() {
		if (typeof this.#codexService.on !== 'function' || typeof this.#codexService.off !== 'function') return;
		const listener = () => this.#requestProviderRecovery(this.#connectionEpoch, { force: true });
		this.#codexService.on('providerRestored', listener);
		this.#providerListeners.push(['providerRestored', listener]);
	}

	#requestProviderRecovery(connectionEpoch, { force = false } = {}) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || this.#providerRecoveryPending) return;
		if (!this.#readyRegistry.some(({ agentId }) => !this.#supportedAgentIds.has(agentId))) return;
		if (!force && !this.#providerProbeDue()) return;
		this.#providerRecoveryPending = true;
		void Promise.resolve(this.#reconciliation).finally(() => {
			this.#providerRecoveryPending = false;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			if (!this.#readyRegistry.some(({ agentId }) => !this.#supportedAgentIds.has(agentId))) return;
			this.#beginReconciliation(structuredClone(this.#readyRegistry), connectionEpoch);
		});
	}

	#providerProbeDue() {
		if (typeof this.#codexService.recoverySnapshot !== 'function') return true;
		let recovery;
		try { recovery = this.#codexService.recoverySnapshot(); }
		catch { return true; }
		if (!Array.isArray(recovery)) return true;
		const missingProviders = new Set(this.#readyRegistry
			.filter(({ agentId }) => !this.#supportedAgentIds.has(agentId))
			.map(({ provider }) => provider ?? 'codex'));
		const now = safeClockRead(this.#epochNow);
		for (const provider of missingProviders) {
			const record = recovery.find((entry) => entry?.provider === provider);
			if (record?.state !== 'degraded' || record.nextProbeAtEpochMs === null || now === null || now >= record.nextProbeAtEpochMs) return true;
		}
		return false;
	}

	#listen(event, listener, { lifecycle = false } = {}) {
		const registered = lifecycle ? listener : (message) => {
			const connectionEpoch = this.#eventConnectionEpoch(message);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			const result = listener(message, connectionEpoch);
			if (typeof message?.waitUntil === 'function' && result !== undefined) message.waitUntil(result);
			return result;
		};
		this.#bridge.on(event, registered);
		this.#listeners.push([event, registered]);
	}

	#acceptReadyEpoch(connection) {
		const supplied = connection?.connectionEpoch;
		let connectionEpoch;
		if (supplied === undefined) {
			connectionEpoch = this.#connected && connection?.serverInstanceId === this.#serverInstanceId
				? this.#connectionEpoch
				: this.#connectionEpoch + 1;
		} else if (!Number.isSafeInteger(supplied) || supplied < 1) {
			this.#emitRuntimeError(new ProtocolV2Error('INVALID_CONNECTION_EPOCH', 'Bridge ready event requires a positive connection epoch'));
			return null;
		} else {
			connectionEpoch = supplied;
		}
		if (connectionEpoch <= this.#connectionEpoch) return null;
		this.#retireReceiptReconciliations();
		this.#inspections.cancel(undefined, 'STALE_CONNECTION_EPOCH');
		this.#memorySummaries.clear();
		if (this.#connectionEpoch > 0) {
			for (const record of this.#registry.list()) {
				void this.#nativeRuntime.dispose(record.agentId, 'connection_replaced').catch((error) => this.#emitRuntimeError(error));
			}
		}
		this.#connectionEpoch = connectionEpoch;
		this.#connected = true;
		return connectionEpoch;
	}

	#eventConnectionEpoch(event) {
		const supplied = event?.connectionEpoch;
		return Number.isSafeInteger(supplied) && supplied >= 1 ? supplied : this.#connectionEpoch;
	}

	#isConnectionEpochCurrent(connectionEpoch) {
		return this.#connected && Number.isSafeInteger(connectionEpoch) && connectionEpoch === this.#connectionEpoch;
	}

	async #installDeadStatePlan(record, death, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
		if (death === null || death === undefined) throw new ProtocolV2Error('MISSING_FIELD', `DEAD agent '${record.agentId}' requires death facts`);
		if (record.currentGoal === null) return;
		if (this.#programRuntime.hasCurrent(record)) return;
		this.#playerMemory.observe(record, { death: structuredClone(death) });
		const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
		if (this.#usesNativeTools(record)) {
			this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
			const observation = { death: structuredClone(death) };
			const live = this.#nativeRuntime.snapshotLive(record.agentId);
			const eventSequence = Number.isSafeInteger(live?.eventSequence) ? live.eventSequence : 0;
			this.#nativeRuntime.updateObservation(record, observation, {
				eventSequence,
				conversation: this.#conversationMemory(record.agentId).history(),
				force: true,
			});
			return this.#scheduleNativeTurn(record, {
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				observation,
				eventSequence,
				priority: 'urgent',
				trigger: 'player_death',
				preserveState: true,
				lifecycleGeneration,
				connectionEpoch,
				nativeEvent: { event: 'player_death', trigger: 'player_death', observation },
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
			connectionEpoch,
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

	#rememberNativeWorldSignals(record, lifecycleGeneration, connectionEpoch, observation) {
		const previous = this.#nativeWorldSignals.get(record.agentId);
		const sameLifecycle = previous?.goalRevision === record.goalRevision
			&& previous.lifecycleGeneration === lifecycleGeneration
			&& previous.connectionEpoch === connectionEpoch;
		const state = sameLifecycle
			? previous
			: { goalRevision: record.goalRevision, lifecycleGeneration, connectionEpoch, positions: [], resources: new Set() };
		const positionKey = nativeBlockPositionKey(observation?.player);
		const previousPositionKey = state.positions.at(-1);
		if (positionKey !== null && positionKey !== previousPositionKey) {
			state.positions.push(positionKey);
			if (state.positions.length > MAX_NATIVE_MOVEMENT_HISTORY) state.positions.shift();
		}
		let resourceDiscovery = false;
		for (const candidate of observedResourceCandidates(observation)) {
			if (!state.resources.has(candidate)) resourceDiscovery = true;
			state.resources.add(candidate);
		}
		while (state.resources.size > MAX_NATIVE_RESOURCE_MEMORY) state.resources.delete(state.resources.values().next().value);
		const looping = detectMovementLoop(state.positions);
		const movementLoop = looping && state.movementLoopActive !== true;
		const previousPlayer = state.player;
		state.player = observation.player;
		// Repeated samples of the same unresolved loop are not new decisions.
		// Moving out of it rearms attention without an arbitrary cooldown.
		state.movementLoopActive = looping;
		this.#nativeWorldSignals.set(record.agentId, state);
		return {
			previousPlayer,
			movementLoop,
			resourceDiscovery,
		};
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
		const connectionEpoch = this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
		this.#scheduleNativeTurn(record, {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			priority: 'urgent',
			trigger,
			lifecycleGeneration,
			connectionEpoch,
			preserveState: conversationOnly,
			conversationOnly,
			nativeEvent: {
				event: event?.kind ?? 'conversation',
				trigger,
				observation: {},
				conversationOnly,
			},
		});
	}

	async #resumeUnreadNativeConversation(record, connectionEpoch, lifecycleGeneration) {
		if (!this.#usesNativeTools(record)) return;
		const isCurrent = () => this.#isConnectionEpochCurrent(connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)
			&& this.#registry.get(record.agentId)?.goalRevision === record.goalRevision;
		const hasCurrentWork = () => {
			const work = this.#providerWork.get(record.agentId);
			return work?.kind === 'native' && work.goalRevision === record.goalRevision
				&& work.connectionEpoch === connectionEpoch && work.lifecycleGeneration === lifecycleGeneration;
		};
		if (!isCurrent() || hasCurrentWork()) return;
		const inbox = this.#pendingConversationInbox(record.agentId);
		await inbox.open(this.#serverInstanceId);
		if (!isCurrent() || hasCurrentWork()) return;
		const reservation = await inbox.reserve();
		inbox.rollback(reservation.token);
		if (reservation.conversation.entries.length === 0 && !reservation.more) return;
		if (!isCurrent() || hasCurrentWork()) return;
		// Derive authority from the new lifecycle, never replay the old goal request.
		this.#scheduleNativeConversation(record, null, 'conversation');
	}

	#awaitingNativeConfirmation(record, lifecycleGeneration = this.#lifecycleGeneration(record.agentId)) {
		return sameSupervisionKey(this.#nativeConfirmationWaits.get(record.agentId), this.#supervisionKey(record, lifecycleGeneration));
	}

	#confirmationBlocksRequest(record, request) {
		return this.#awaitingNativeConfirmation(record, request.lifecycleGeneration)
			&& (request.priority !== 'urgent' || request.trigger === 'stuck' || request.nativeEvent?.trigger === 'continuation');
	}

	#scheduleNativeTurn(record, request) {
		if (!this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		if (!this.#nativePreparationIsCurrent(record, request) || this.#confirmationBlocksRequest(record, request)) return;
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (
				existing.kind === 'native'
				&& existing.goalRevision === request.goalRevision
				&& existing.lifecycleGeneration === request.lifecycleGeneration
				&& existing.connectionEpoch === request.connectionEpoch
				&& (request.priority === 'urgent' || request.nativeEvent?.event === 'program_planning_due')
			) {
				if (request.nativeEvent?.event === 'program_planning_due') this.#queueNativePreparation(existing, request);
				else this.#queueNativeSteer(existing, request);
				return existing.promise;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return existing.promise;
		}
		const supervisionKey = this.#supervisionKey(record, request.lifecycleGeneration);
		this.#forgetNativeConversationRecovery(supervisionKey);
		this.#goalSupervisor.activate(supervisionKey);
		const work = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration: request.lifecycleGeneration,
			connectionEpoch: request.connectionEpoch,
			kind: 'native',
			request,
			pending: null,
			steerQueued: null,
			steerRequest: null,
			steerPromise: null,
			preparationPromise: null,
			traceId: planningTraceId(record.agentId, record.goalRevision, request.lifecycleGeneration, 'native'),
			promise: null,
			supervisionKey,
			supervisionToken: this.#goalSupervisor.begin(supervisionKey, 'provider', { timeoutMs: Math.min(MAX_LEASE_TIMEOUT_MS, this.#planner.getExecutionSettings?.(record.agentId)?.limits?.nativeTurnBudgetMs ?? MAX_LEASE_TIMEOUT_MS) }),
			toolSupervision: new Map(),
			successfulChat: false,
			expired: false,
		};
		this.#providerWork.set(record.agentId, work);
		try {
			if (request.preserveState !== true) {
				if (record.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
				void this.#sendForEpoch(request.connectionEpoch, 'planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING })
					.catch((error) => this.#reportAgentError(record.agentId, error, request.connectionEpoch));
			}
		} catch (error) {
			this.#providerWork.delete(record.agentId);
			this.#goalSupervisor.end(work.supervisionToken);
			throw error;
		}
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision, { allowPublicAgentMessage: true, connectionEpoch: request.connectionEpoch });
		work.promise = Promise.resolve()
			.then(async () => this.#planner.requestNativeTurn({
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				input: await this.#nativeTurnInput(record, request),
				recoverySummary: record.lastSummary,
				priority: request.priority,
				preserveState: request.preserveState === true,
				traceId: work.traceId,
				onVerbose: verboseReporter,
				onProgress: () => {
					if (this.#providerWork.get(record.agentId) === work && this.#isConnectionEpochCurrent(request.connectionEpoch)) this.#goalSupervisor.progress(work.supervisionToken);
				},
				executeTool: (toolRequest) => this.#executeNativeTool(work, toolRequest),
			}))
			.then(async (result) => {
				try { return await this.#completeNativeTurn(work, result); }
				catch (error) { return this.#failNativeTurn(work, error); }
			}, (error) => this.#failNativeTurn(work, error))
			.finally(() => verboseReporter.dispose());
		return work.promise;
	}

	#nativePreparationIsCurrent(record, request) {
		const event = request?.nativeEvent;
		return event?.event !== 'program_planning_due'
			|| this.#nativeRuntime.canPrepareProgram(record, event.programId, event.status?.programVersion);
	}

	#preparationHasRunningProgram(work, record) {
		return (work.request?.nativeEvent?.event === 'program_planning_due' || work.preparingProgram != null)
			&& this.#nativeRuntime.hasProgram(record);
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

	#queueNativePreparation(work, request) {
		// Planning is a one-shot advisory. It must never delay or replace an
		// urgent steering request, and its eventual failure must not become a
		// replayable pending turn.
		if (work.preparationPromise !== null
				|| work.request?.priority === 'urgent'
				|| work.steerQueued?.priority === 'urgent'
				|| work.pending?.priority === 'urgent'
				|| work.steerPromise !== null) return;
		const preparation = this.#deliverNativePreparation(work, request);
		const tracked = preparation.finally(() => {
			if (work.preparationPromise === tracked) work.preparationPromise = null;
		});
		work.preparationPromise = tracked;
	}

	async #deliverNativePreparation(work, request) {
		try {
			const record = this.#registry.get(work.agentId);
			if (record === null || record.goalRevision !== work.goalRevision
					|| work.expired === true
					|| this.#providerWork.get(work.agentId) !== work
					|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
					|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)
					|| !this.#nativePreparationIsCurrent(record, request)
					|| work.steerQueued?.priority === 'urgent'
					|| work.pending?.priority === 'urgent'
					|| work.steerPromise !== null) return;
			work.preparingProgram = { programId: request.nativeEvent.programId, programVersion: request.nativeEvent.status?.programVersion };
			await this.#planner.steerNativeTurn({
				agentId: work.agentId,
				goalRevision: work.goalRevision,
				input: await this.#nativeTurnInput(record, request, { deliverConversation: false }),
			});
			this.#writeTrace('native_turn_prepared', {
				agentId: work.agentId,
				goalRevision: work.goalRevision,
				traceId: work.traceId,
				trigger: request.trigger,
			});
		} catch (error) {
			// Advisory delivery has no turn ownership. Do not restore conversation
			// state or queue a stale reminder after the body turn has moved on.
			this.#writeTrace('native_turn_preparation_skipped', {
				agentId: work.agentId,
				goalRevision: work.goalRevision,
				traceId: work.traceId,
				errorCode: String(error?.code ?? 'PREPARATION_FAILED').slice(0, 128),
			});
		}
	}

	async #drainNativeSteering(work) {
		const isCurrent = () => work.expired !== true
			&& this.#providerWork.get(work.agentId) === work
			&& this.#registry.get(work.agentId)?.goalRevision === work.goalRevision
			&& this.#isConnectionEpochCurrent(work.connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		while (work.steerQueued !== null) {
			// Death and reconnect can replace a lifecycle without changing the goal
			// revision. Never deliver its queued events into the replacement turn.
			if (!isCurrent()) { work.steerQueued = null; return; }
			const request = work.steerQueued;
			work.steerQueued = null;
			work.steerRequest = request;
			try {
				const record = this.#registry.get(work.agentId);
				if (record === null || record.goalRevision !== work.goalRevision) throw Object.assign(new Error('Native steering belongs to an obsolete goal'), { code: 'STALE_PLAN' });
				if (!this.#nativePreparationIsCurrent(record, request)) continue;
				await this.#planner.steerNativeTurn({
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					input: await this.#nativeTurnInput(record, request),
				});
				if (!isCurrent()) { this.#restoreNativeConversation(request); work.steerQueued = null; return; }
				await this.#commitNativeConversation(request);
				if (!isCurrent()) { work.steerQueued = null; return; }
				// Steering can be truncated just like turn/start. Drain its unread
				// tail while this turn still accepts steering; a rejection below
				// releases its reservation and transfers it to the pending turn instead.
				if (request.nativeConversationDelivery?.omittedEntries > 0 && work.steerQueued === null) {
					work.steerQueued = { ...request };
				}
				this.#writeTrace('native_turn_steered', {
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					traceId: work.traceId,
					trigger: request.trigger,
				});
			} catch (error) {
				// A late rejection has no authority to rewind current conversation
				// delivery or add recovery work after its lifecycle has ended.
				if (!isCurrent()) { work.steerQueued = null; return; }
				this.#restoreNativeConversation(request);
				if (error?.code === 'CONVERSATION_STORAGE_FAILED') await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
				if (!isCurrent()) { work.steerQueued = null; return; }
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
			} finally {
				work.steerRequest = null;
			}
		}
	}

	async #settleNativeSteering(work) {
		while (work.steerPromise !== null) await work.steerPromise;
	}

	async #executeNativeTool(work, toolRequest) {
		const record = this.#registry.get(work.agentId);
		if (work.expired === true || record === null || record.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			return { state: 'CANCELLED', reasonCode: 'STALE_PLAN', executed: false,
				goalRevision: work.goalRevision, currentGoalRevision: record?.goalRevision ?? null,
				message: 'This goal turn has ended or been superseded. No action was dispatched. End this turn and await the next goal event.' };
		}
		if (work.request.conversationOnly === true
				&& toolRequest.tool.kind !== 'observe'
				&& !(toolRequest.tool.kind === 'action' && toolRequest.tool.actionType === 'chat')) {
			throw Object.assign(new Error('Idle conversation turns may only observe and reply with chat'), { code: 'CONVERSATION_ONLY' });
		}
		const executesBody = toolRequest.tool.kind === 'action'
			|| toolRequest.tool.kind === 'sequence'
			|| toolRequest.tool.kind === 'lookAround'
			|| toolRequest.tool.kind === 'run_program'
			|| toolRequest.tool.kind === 'respond_program'
			|| toolRequest.tool.kind === 'start_action'
			|| toolRequest.tool.kind === 'replace_action';
		const supervisionKind = executesBody ? 'action' : toolRequest.tool.kind === 'finish' ? 'completion' : null;
		if (work.toolSupervision.has(toolRequest.callId)) throw codedRuntimeError('DUPLICATE_TOOL_CALL', 'A tool call with this callId is already running');
		const call = { token: null, executesBody, done: null, resolve: null };
		call.done = new Promise((resolve) => { call.resolve = resolve; });
		const finishPredecessors = toolRequest.tool.kind === 'finish' ? [...work.toolSupervision.values()].map((pending) => pending.done) : [];
		work.toolSupervision.set(toolRequest.callId, call);
		let supervisionToken = null;
		let result;
		try {
			if (finishPredecessors.length > 0) {
				await Promise.all(finishPredecessors);
				if (work.expired || !this.#isConnectionEpochCurrent(work.connectionEpoch)
					|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) return { state: 'CANCELLED', reasonCode: 'STALE_PLAN', executed: false };
			}
			if (executesBody && record.state === DynamicAgentState.PLANNING) {
				this.#registry.setState(record.agentId, DynamicAgentState.ACTING, { goalRevision: record.goalRevision });
			}
			supervisionToken = supervisionKind === null ? null : this.#goalSupervisor.begin(work.supervisionKey, supervisionKind);
			call.token = supervisionToken;
			this.#goalSupervisor.progress(work.supervisionToken);
			result = await this.#nativeRuntime.execute(toolRequest, record, { lifecycleGeneration: work.lifecycleGeneration });
			if (toolRequest.tool.kind === 'action' && toolRequest.tool.actionType === 'chat' && result?.state === 'SUCCEEDED') work.successfulChat = true;
			if (result?.advisory === 'program_planning_due') work.preparingProgram = { programId: result.programId, programVersion: result.programVersion };
			const current = this.#registry.get(work.agentId);
			if (current?.goalRevision === work.goalRevision
				&& this.#isConnectionEpochCurrent(work.connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
				if (result?.state === 'AWAITING_OPERATOR_CONFIRMATION') {
					// Waiting belongs to this goal lifecycle, not just the provider turn that
					// requested verification. Ordinary queued sightings cannot resume it.
					this.#nativeConfirmationWaits.set(record.agentId, work.supervisionKey);
					this.#goalSupervisor.terminate(work.supervisionKey);
				} else if (toolRequest.tool.kind === 'finish' || (executesBody
					&& !['chat', 'wait'].includes(toolRequest.tool.actionType))) {
					this.#nativeConfirmationWaits.delete(record.agentId);
				}
			}
			return result;
		} finally {
			if (supervisionToken !== null) {
				this.#goalSupervisor.end(supervisionToken, { progress: ['SUCCEEDED', 'COMPLETED'].includes(result?.state) });
			}
			if (work.toolSupervision.get(toolRequest.callId) === call) work.toolSupervision.delete(toolRequest.callId);
			call.resolve();
			this.#goalSupervisor.progress(work.supervisionToken);
			const latest = this.#registry.get(work.agentId);
			if (!this.#stopping && !this.#closed && executesBody && latest?.goalRevision === work.goalRevision && latest.state === DynamicAgentState.ACTING
				&& ![...work.toolSupervision.values()].some((pending) => pending.executesBody)
				&& this.#isConnectionEpochCurrent(work.connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
				this.#registry.setState(latest.agentId, DynamicAgentState.PLANNING, { goalRevision: latest.goalRevision });
			}
		}
	}

	async #completeNativeTurn(work, result) {
		await Promise.all([...work.toolSupervision.values()].map((call) => call.done));
		await this.#settleNativeSteering(work);
		if (this.#providerWork.get(work.agentId) !== work) {
			this.#goalSupervisor.end(work.supervisionToken, { scheduleRecovery: false });
			return null;
		}
		if (this.#isConnectionEpochCurrent(work.connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) await this.#commitNativeConversation(work.request);
		else this.#restoreNativeConversation(work.request);
		// Persistence is part of delivery. A failed commit still owns the exact
		// supervision entry, so failNativeTurn can arrange autonomous recovery.
		this.#goalSupervisor.end(work.supervisionToken, { progress: (result?.toolCalls ?? 0) > 0 });
		if (work.request.conversationOnly === true && this.#providerWork.get(work.agentId) === work) this.#goalSupervisor.terminate(work.supervisionKey);
		// Disk completion can race replacement work just like provider completion.
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		if (record !== null && record.goalRevision === work.goalRevision
				&& this.#isConnectionEpochCurrent(work.connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			this.#writeTrace('native_turn_completed', { agentId: work.agentId, goalRevision: work.goalRevision, traceId: work.traceId, toolCalls: result?.toolCalls ?? 0 });
		}
		if (this.#reschedulePendingNativeTurn(pending)) return result;
		if (work.request.nativeConversationDelivery?.omittedEntries > 0
			&& this.#reschedulePendingNativeTurn({ ...work.request, conversationRetry: false })) return result;
		if (work.request.conversationOnly === true && !work.successfulChat
				&& work.request.conversationRetry !== true && record !== null) {
			this.#scheduleNativeTurn(record, {
				...work.request,
				connectionEpoch: work.connectionEpoch,
				conversationRetry: true,
				retryInstruction: 'Your previous turn made no visible reply. Call say exactly once now.',
			});
			return result;
		}
		const awaitingConfirmation = record !== null && this.#awaitingNativeConfirmation(record, work.lifecycleGeneration);
		if (awaitingConfirmation) this.#goalSupervisor.terminate(work.supervisionKey);
		if (!awaitingConfirmation && this.#isActiveNativeGoal(work)) {
			this.#goalSupervisor.ensure(work.supervisionKey, (result?.toolCalls ?? 0) === 0 ? 'zero_tool_turn' : 'turn_completed');
		}
		return result;
	}

	async #failNativeTurn(work, error) {
		let recovery = classifyRecoveryFailure(error);
		let classification = recovery.retryable ? 'recoverable' : classifyNativeGoalError(error);
		this.#writeTrace('native_turn_failed', { agentId: work.agentId, goalRevision: work.goalRevision,
			errorCode: sanitizeDiagnosticErrorCode(error, { fallback: 'NATIVE_TURN_FAILED' }), classification });
		try {
			await this.#settleNativeSteering(work);
		} catch (steeringError) {
			error = steeringError;
			recovery = classifyRecoveryFailure(error);
			classification = recovery.retryable ? 'recoverable' : classifyNativeGoalError(error);
		} finally {
			this.#goalSupervisor.end(work.supervisionToken, { scheduleRecovery: classification !== 'stale' });
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		const staleLifecycle = record?.goalRevision !== work.goalRevision
			|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
			|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		if (staleLifecycle || this.#stopping || this.#closed) {
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		this.#restoreNativeConversation(work.request);
		if (classification === 'stale') {
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		if (this.#preparationHasRunningProgram(work, record)) {
			this.#writeTrace('native_preparation_failed_current_program_preserved', { agentId: work.agentId, goalRevision: work.goalRevision,
				errorCode: sanitizeDiagnosticErrorCode(error, { fallback: 'NATIVE_TURN_FAILED' }) });
			this.#goalSupervisor.ensure(work.supervisionKey, 'program_preparation_failed');
			await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		await this.#nativeRuntime.dispose(work.agentId, 'native_turn_failed');
		this.#nativeObservationSignatures.delete(work.agentId);
		if (classification === 'terminal') {
			this.#goalSupervisor.terminate(work.supervisionKey);
			if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) {
				this.#registry.setState(work.agentId, DynamicAgentState.ERROR, {
					goalRevision: work.goalRevision,
					error: { code: sanitizeDiagnosticErrorCode(error, { fallback: 'NATIVE_TURN_FAILED' }), message: sanitizeDiagnosticErrorMessage(error, { maxBytes: 2_048 }) },
				});
			}
			await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
		} else {
			if (work.request.conversationOnly === true) {
				this.#nativeConversationRecoveries.set(work.agentId, {
					supervisionKey: work.supervisionKey,
					request: work.request,
				});
			}
			this.#goalSupervisor.recover(work.supervisionKey, this.#recoveryDetails(work.agentId, {
				errorCode: recovery.code,
				recoveryKind: recovery.kind,
				nextProbeAtEpochMs: recovery.nextProbeAtEpochMs,
			}));
			this.#reschedulePendingNativeTurn(pending);
		}
		return null;
	}

	#reschedulePendingNativeTurn(request) {
		if (request === null || request === undefined || this.#stopping || this.#closed) return false;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision
				|| !this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return false;
		const eligible = request.conversationOnly === true
			? [DynamicAgentState.IDLE, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED]
			: [DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD];
		if (!this.#usesNativeTools(record) || !eligible.includes(record.state)) return false;
		if (!this.#nativePreparationIsCurrent(record, request) || this.#confirmationBlocksRequest(record, request)) return false;
		this.#scheduleNativeTurn(record, request);
		return true;
	}

	#isActiveNativeGoal(work) {
		if (this.#stopping || this.#closed) return false;
		const record = this.#registry.get(work.agentId);
		return record !== null
			&& !this.#awaitingNativeConfirmation(record, work.lifecycleGeneration)
			&& record.goalRevision === work.goalRevision
			&& this.#isConnectionEpochCurrent(work.connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)
			&& this.#usesNativeTools(record)
			&& [DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state);
	}

	#scheduleInitialPlan(record, request) {
		if (!this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== request.lifecycleGeneration) {
				existing.pending = mergePlannerRequest(existing.pending, request);
				return;
			}
			if (request.priority === 'urgent' && existing.request.priority !== 'urgent') {
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
		void this.#sendForEpoch(request.connectionEpoch, 'planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING })
			.catch((error) => this.#reportAgentError(record.agentId, error, request.connectionEpoch));
		void this.#scheduleProviderPlan(record, request, { preserveState: false, kind: 'initial' });
	}

	#afterProviderProbeDeadline(record, request) {
		const probeDeadline = this.#providerProbeDeadlines.get(record.agentId);
		const now = safeClockRead(this.#epochNow);
		if (probeDeadline !== undefined && now !== null && now < probeDeadline) {
			const deferred = this.#deferredProviderRecovery.get(record.agentId);
			this.#deferredProviderRecovery.set(record.agentId, mergePlannerRequest(deferred, request));
			return null;
		}
		if (probeDeadline !== undefined) {
			this.#providerProbeDeadlines.delete(record.agentId);
			const deferred = this.#deferredProviderRecovery.get(record.agentId);
			this.#deferredProviderRecovery.delete(record.agentId);
			request = mergePlannerRequest(deferred, request);
		}
		return request;
	}

	#scheduleProviderPlan(record, request, { preserveState = false, kind = 'initial' } = {}) {
		const lifecycleGeneration = request.lifecycleGeneration ?? this.#lifecycleGeneration(record.agentId);
		const connectionEpoch = request.connectionEpoch ?? this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return Promise.resolve(null);
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== lifecycleGeneration
					|| existing.connectionEpoch !== connectionEpoch) {
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
			connectionEpoch,
			kind,
			preserveState,
			request,
			contextSnapshot: this.#contextSnapshot(record),
			pending: null,
			traceId: request.traceId ?? planningTraceId(record.agentId, record.goalRevision, lifecycleGeneration, kind),
			promise: null,
		};
		this.#providerWork.set(record.agentId, work);
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision, { connectionEpoch });
		const providerRequest = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			preserveState,
			recoverySummary: record.lastSummary,
			input: request.input,
			prepareInput: request.prepareInput ?? null,
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
		if (record === null || record.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			const pending = work.pending;
			this.#providerWork.delete(work.agentId);
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		let runtime = null;
		try {
			this.#programRuntimeEpochs.set(record.agentId, work.connectionEpoch);
			runtime = await this.#programRuntime.installDecision(record, decision, {
				observation: work.request.observation,
				eventSequence: work.request.eventSequence,
				traceId: work.traceId,
			});
		} catch (error) {
			if (this.#providerWork.get(work.agentId) !== work) return null;
			this.#providerWork.delete(work.agentId);
			const current = this.#registry.get(work.agentId);
			const stale = current?.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
			if (this.#programRuntimeEpochs.get(work.agentId) === work.connectionEpoch) this.#programRuntimeEpochs.delete(work.agentId);
			if (!stale) await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
			if (stale || work.pending?.priority === 'urgent') this.#reschedulePendingProviderPlan(work.pending);
			return null;
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const current = this.#registry.get(work.agentId);
		if (current?.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			const pending = work.pending;
			this.#providerWork.delete(work.agentId);
			if (this.#programRuntimeEpochs.get(work.agentId) === work.connectionEpoch) {
				this.#programRuntime.dispose(work.agentId);
				this.#programRuntimeEpochs.delete(work.agentId);
			}
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		this.#publishVerbose(record.agentId, record.goalRevision, 'decision', verboseDecisionSummary(decision), work.connectionEpoch);
		if (runtime === null) return runtime;
		const latest = this.#registry.get(work.agentId);
		if (latest === null || latest.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) return runtime;
		if (decision.contextReceipt != null) this.#rememberAcceptedContextCursor(latest, decision.contextReceipt);
		this.#flushPendingAttention(latest, work.connectionEpoch);
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
			await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
		}
		return runtime;
	}

	async #failProviderPlan(work, record, error) {
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const current = this.#registry.get(work.agentId);
		const stale = current?.goalRevision !== work.goalRevision
			|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
			|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		this.#promotePendingAttention(work, current);
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		const urgentRecovery = pending?.priority === 'urgent';
		const recovery = classifyRecoveryFailure(error);
		const quietRetry = recovery.quiet;
		if (!stale && recovery.retryable) {
			if (quietRetry) {
				const retryAt = safeClockRead(this.#controlNow);
				if (retryAt !== null) this.#providerRetryAfter.set(record.agentId, retryAt + EMPTY_TURN_RETRY_DELAY_MS);
			}
			this.#publishVerbose(record.agentId, record.goalRevision, 'retry', verboseRecoveryMessage(recovery), work.connectionEpoch, {
				component: 'provider', boundary: recovery.kind ?? 'planning', code: recovery.code ?? 'PROVIDER_RETRY', state: 'retrying',
			});
			try {
				const latest = this.#registry.get(record.agentId);
				if (latest?.state === DynamicAgentState.ERROR) this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
				if (this.#registry.get(record.agentId)?.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
			} catch (stateError) {
				void this.#reportAgentError(record.agentId, stateError, work.connectionEpoch);
			}
			const session = typeof this.#codexService.getAgent === 'function' ? this.#codexService.getAgent(record.agentId) : null;
			if (session !== null && typeof this.#codexService.replaceAgent === 'function') {
				try {
					await this.#codexService.replaceAgent(record, {
						recoverySummary: 'provider_plan_recovery',
						controlProtocol: 'arena_script',
						...(Number.isSafeInteger(session.sessionGeneration) ? { expectedSessionGeneration: session.sessionGeneration } : {}),
					});
				} catch (replacementError) {
					this.#writeTrace('provider_session_replacement_failed', {
						agentId: record.agentId,
						goalRevision: record.goalRevision,
						errorCode: replacementError?.code ?? 'SESSION_REPLACEMENT_FAILED',
					});
				}
			}
			const recoveryDetails = this.#recoveryDetails(record.agentId, {
				errorCode: recovery.code,
				recoveryKind: recovery.kind,
				nextProbeAtEpochMs: recovery.nextProbeAtEpochMs,
			});
			const supervisionKey = this.#supervisionKey(record, work.lifecycleGeneration);
			this.#goalSupervisor.activate(supervisionKey);
			this.#goalSupervisor.recover(supervisionKey, recoveryDetails);
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		if (!stale && !urgentRecovery) await this.#reportAgentError(record.agentId, error, work.connectionEpoch);
		if (!stale && quietRetry && current?.state === DynamicAgentState.ERROR) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (stateError) {
				void this.#reportAgentError(record.agentId, stateError, work.connectionEpoch);
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
		const prepared = buildPlannerRequest({
			agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
			goal: record.currentGoal,
			goalRevision: record.goalRevision,
			attentionPriority: 'urgent',
			attentionTrigger: attention.trigger,
			observation: request.observation,
		}, this.#plannerContext(record.agentId));
		work.pending = mergePlannerRequest(work.pending, {
			...request,
			attention: true,
			priority: 'urgent',
			trigger: attention.trigger,
			preserveState: false,
			...prepared,
		});
		this.#pendingAttention.delete(work.agentId);
	}

	#reschedulePendingProviderPlan(request) {
		if (request === null || request === undefined) return;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision
				|| !this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		if (record.state === DynamicAgentState.ERROR && request.preserveState !== true) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (error) {
				void this.#reportAgentError(record.agentId, error, request.connectionEpoch);
				return;
			}
		}
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		void this.#scheduleProviderPlan(record, request, { preserveState: request.preserveState === true, kind: request.kind ?? 'initial' });
	}

	#unbindBridge() {
		for (const [event, listener] of this.#listeners) this.#bridge.off(event, listener);
		this.#listeners = [];
		if (typeof this.#codexService.off === 'function') {
			for (const [event, listener] of this.#providerListeners) this.#codexService.off(event, listener);
		}
		this.#providerListeners = [];
	}

	#beginGoalControlInterruption(message, connectionEpoch) {
		let reason = null;
		if (['stop', 'disconnect', 'dead'].includes(message.payload.operation)) reason = `Goal ${message.payload.operation}`;
		if (message.payload.operation === 'steer') reason = 'Goal steered';
		if (message.payload.operation === 'replace') reason = 'Goal replaced';
		if (message.payload.operation === 'complete') reason = 'Goal completed';
		if (reason === null) return;
		try {
			Promise.resolve(this.#planner.interrupt(message.agentId, reason)).catch((error) => this.#reportAgentError(message.agentId, error, connectionEpoch));
		} catch (error) {
			void this.#reportAgentError(message.agentId, error, connectionEpoch);
		}
	}

	#lifecycleGeneration(agentId) {
		return this.#lifecycleGenerations.get(agentId) ?? 0;
	}

	#supervisionKey(record, lifecycleGeneration = this.#lifecycleGeneration(record.agentId)) {
		return {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration,
			sessionEpoch: this.#connectionEpoch,
			profileFingerprint: profileFingerprint(record),
		};
	}

	#recoveryDetails(agentId, { errorCode, recoveryKind, nextProbeAtEpochMs }) {
		const deadline = Number.isFinite(nextProbeAtEpochMs) && nextProbeAtEpochMs >= 0 ? nextProbeAtEpochMs : null;
		if (deadline === null) this.#providerProbeDeadlines.delete(agentId);
		else this.#providerProbeDeadlines.set(agentId, deadline);
		const now = safeClockRead(this.#epochNow);
		return {
			errorCode,
			...(recoveryKind === undefined ? {} : { recoveryKind }),
			...(deadline === null ? {} : {
				nextProbeAtEpochMs: deadline,
				retryDelayMs: now === null ? 0 : Math.max(0, deadline - now),
			}),
		};
	}

	#retireGoalSupervision(record, operation) {
		const key = this.#supervisionKey(record);
		if (['disconnect', 'dead'].includes(operation)) this.#goalSupervisor.suspend(key);
		else this.#goalSupervisor.terminate(key);
	}

	#advanceLifecycleGeneration(agentId) {
		this.#latestActionDispatches.delete(agentId);
		// Release unacknowledged reservations before a replacement consumes them.
		// Late callbacks have no reservation identity left to commit or rewind.
		const work = this.#providerWork.get(agentId);
		if (work?.kind === 'native') {
			this.#restoreNativeConversation(work.steerRequest);
			this.#restoreNativeConversation(work.request);
		}
		this.#pendingConversationInboxes.get(agentId)?.fence();
		this.#inspections.cancel(agentId);
		this.#memorySummaries.delete(agentId);
		const next = this.#lifecycleGeneration(agentId) + 1;
		this.#lifecycleGenerations.set(agentId, next);
		return next;
	}

	#invalidateLifecycleWork(message) {
		if (['queue', 'dequeue'].includes(message.payload.operation)) return;
		const current = this.#registry.get(message.agentId);
		if (current !== null && message.payload.goalRevision <= current.goalRevision) return;
		this.#invalidateAcceptedLifecycle(message.agentId);
	}

	#invalidateAcceptedLifecycle(agentId) {
		this.#pendingAttention.delete(agentId);
		this.#attentionFlushes.delete(agentId);
		this.#contextCursors.delete(agentId);
		this.#nativeObservationSignatures.delete(agentId);
		this.#supervisedObservationRequests.delete(agentId);
		this.#nativeConversationRecoveries.delete(agentId);
		this.#providerProbeDeadlines.delete(agentId);
		this.#deferredProviderRecovery.delete(agentId);
		this.#nativeWorldSignals.delete(agentId);
		this.#nativeConfirmationWaits.delete(agentId);
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
		const token = { goalRevision: record.goalRevision, connectionEpoch: this.#connectionEpoch };
		this.#attentionFlushes.set(record.agentId, token);
		setImmediate(() => {
			if (this.#attentionFlushes.get(record.agentId) !== token) return;
			this.#attentionFlushes.delete(record.agentId);
			if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(token.connectionEpoch)) return;
			const current = this.#registry.get(record.agentId);
			const pending = this.#pendingAttention.get(record.agentId);
			if (current === null || pending?.goalRevision !== token.goalRevision || current.goalRevision !== token.goalRevision) return;
			this.#flushPendingAttention(current, token.connectionEpoch);
		});
	}

	#flushPendingAttention(record, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		const pending = this.#pendingAttention.get(record.agentId);
		if (pending?.goalRevision !== record.goalRevision || !this.#programRuntime.hasCurrent(record)) return;
		try {
			const notified = this.#programRuntime.notifyAttention(record, { priority: pending.priority, trigger: pending.trigger });
			if (notified !== null) this.#pendingAttention.delete(record.agentId);
		} catch (error) {
			void this.#reportAgentError(record.agentId, error, connectionEpoch);
		}
	}

	#isLifecycleGenerationCurrent(agentId, generation) {
		return this.#lifecycleGeneration(agentId) === generation;
	}

	#enqueueAgent(agentId, operation, { waitForReconciliation = true, connectionEpoch = this.#connectionEpoch, coalesceKey = null, terminal = false, terminalKey = null, transactional = false, onAdmitted = null } = {}) {
		const releaseCapacity = this.#reserveAgentOperation(agentId);
		let resolve;
		let reject;
		const promise = new Promise((resolveValue, rejectValue) => { resolve = resolveValue; reject = rejectValue; });
		const waiter = {
			resolve: (value) => { releaseCapacity(); resolve(value); },
			reject: (error) => { releaseCapacity(); reject(error); },
		};
		let queue = this.#agentOperations.get(agentId);
		if (queue === undefined) {
			queue = { items: [], drainPromise: null };
			this.#agentOperations.set(agentId, queue);
		}
		const tail = queue.items.at(-1);
		if (coalesceKey !== null && tail?.coalesceKey === coalesceKey) {
			for (const superseded of tail.waiters) superseded.resolve(undefined);
			tail.operation = operation;
			tail.waitForReconciliation = waitForReconciliation;
			tail.connectionEpoch = connectionEpoch;
			tail.transactional = transactional;
			tail.waiters = [waiter];
		} else {
			const pendingTerminal = terminalKey === null ? undefined : queue.items.find((item) => item.terminal === true
				&& item.terminalKey === terminalKey && item.connectionEpoch === connectionEpoch);
			if (terminal && pendingTerminal !== undefined) {
				// An exact replay shares reconciliation and its durable-before-ACK outcome.
				// Distinct receipts remain queued, bounded by the admission reservations.
				pendingTerminal.waiters.push(waiter);
				promise.catch((error) => this.#reportAgentError(agentId, error, connectionEpoch));
				return promise;
			}
			const ordinaryPending = queue.items.filter((item) => item.terminal !== true && item.transactional !== true).length;
			const transactionalPending = queue.items.filter((item) => item.transactional === true).length;
			const capacity = transactional ? this.#maxPendingAgentTransactions : this.#maxPendingAgentOperations;
			const pending = transactional ? transactionalPending : ordinaryPending;
			if (!terminal && pending >= capacity) {
				const lane = transactional ? 'transactional' : 'ordinary';
				const error = codedRuntimeError('AGENT_EVENT_BACKPRESSURE', `Agent '${agentId}' has ${capacity} pending ${lane} coordinator events`);
				waiter.reject(error);
				promise.catch((caught) => this.#reportAgentError(agentId, caught, connectionEpoch));
				return promise;
			}
			try {
				if (onAdmitted !== null) onAdmitted();
			} catch (error) {
				waiter.reject(error);
				promise.catch((caught) => this.#reportAgentError(agentId, caught, connectionEpoch));
				if (queue.items.length === 0 && this.#agentOperations.get(agentId) === queue) this.#agentOperations.delete(agentId);
				return promise;
			}
			queue.items.push({ operation, waitForReconciliation, connectionEpoch, coalesceKey, terminal, terminalKey, transactional, waiters: [waiter] });
		}
		promise.catch((error) => this.#reportAgentError(agentId, error, connectionEpoch));
		if (queue.drainPromise === null) queue.drainPromise = Promise.resolve().then(() => this.#drainAgentOperations(agentId, queue));
		return promise;
	}

	async #drainAgentOperations(agentId, queue) {
		while (queue.items.length > 0) {
			const item = queue.items.shift();
			try {
				if (this.#isConnectionEpochCurrent(item.connectionEpoch) && item.waitForReconciliation) await this.#reconciliation;
				const value = this.#isConnectionEpochCurrent(item.connectionEpoch) ? await item.operation() : undefined;
				for (const waiter of item.waiters) waiter.resolve(value);
			} catch (error) {
				for (const waiter of item.waiters) waiter.reject(error);
			}
		}
		if (this.#agentOperations.get(agentId) === queue) this.#agentOperations.delete(agentId);
	}

	#reserveAgentOperation(agentId) {
		const agentCount = this.#agentOperationCounts.get(agentId) ?? 0;
		if (this.#totalAgentOperations >= this.#connectionOperationCap) {
			throw new ProtocolV2Error('CONNECTION_INBOUND_BACKPRESSURE', 'Coordinator inbound operation queue is full');
		}
		if (agentCount >= this.#agentOperationCap) {
			throw new ProtocolV2Error('AGENT_INBOUND_BACKPRESSURE', `Coordinator inbound operation queue for agent '${agentId}' is full`);
		}
		this.#totalAgentOperations++;
		this.#agentOperationCounts.set(agentId, agentCount + 1);
		let released = false;
		return () => {
			if (released) return;
			released = true;
			this.#totalAgentOperations = Math.max(0, this.#totalAgentOperations - 1);
			const pending = this.#agentOperationCounts.get(agentId) ?? 0;
			if (pending <= 1) this.#agentOperationCounts.delete(agentId);
			else this.#agentOperationCounts.set(agentId, pending - 1);
		};
	}

	#run(operation, connectionEpoch = this.#connectionEpoch, { requireConnected = true } = {}) {
		return Promise.resolve().then(() => {
			const current = requireConnected
				? this.#isConnectionEpochCurrent(connectionEpoch)
				: connectionEpoch === this.#connectionEpoch;
			return current ? operation() : undefined;
		}).catch((error) => this.emit('runtimeError', error));
	}

	async #sendForEpoch(connectionEpoch, type, agentId, payload) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) {
			throw codedRuntimeError('STALE_CONNECTION_EPOCH', `Bridge connection epoch ${connectionEpoch ?? 'unknown'} is no longer active`);
		}
		return this.#bridge.send(type, agentId, payload, { connectionEpoch });
	}

	async #acknowledgeActionResult(message, connectionEpoch) {
		if (typeof this.#bridge.acknowledgeActionResult === 'function') {
			await this.#bridge.acknowledgeActionResult(message.agentId, message.payload, { connectionEpoch });
		}
	}

	#queueReceiptReconciliation(message, connectionEpoch) {
		const key = JSON.stringify([connectionEpoch, message.agentId, message.payload]);
		const existing = this.#receiptReconciliations.get(key);
		if (existing !== undefined) return existing;
		// Separate capacity from gameplay ingress: a stalled disk must not consume
		// every observation/control slot. Overflow remains unacknowledged upstream.
		const agentPending = [...this.#receiptReconciliations.values()].filter((entry) => entry.message.agentId === message.agentId).length;
		if (this.#receiptReconciliations.size >= this.#connectionOperationCap || agentPending >= this.#agentOperationCap) {
			this.#emitRuntimeError(codedRuntimeError('RECEIPT_BACKPRESSURE', 'Receipt reconciliation is full; server must retain this unacknowledged result'));
			return null;
		}
		const predecessor = [...this.#receiptReconciliations.values()].findLast((entry) => entry.message.agentId === message.agentId && entry.connectionEpoch === connectionEpoch);
		const receipt = { key, message: { agentId: message.agentId, payload: structuredClone(message.payload) }, connectionEpoch, pending: null, retry: null, failures: 0, durable: false, done: null, resolve: null, predecessor: predecessor?.done };
		receipt.done = new Promise((resolve) => { receipt.resolve = resolve; });
		this.#receiptReconciliations.set(key, receipt);
		return receipt;
	}

	#retireReceiptReconciliations() {
		for (const receipt of this.#receiptReconciliations.values()) {
			clearTimeout(receipt.retry);
			receipt.resolve();
			// In-flight I/O remains tracked for bounded shutdown until it settles.
			if (receipt.pending === null) this.#receiptReconciliations.delete(receipt.key);
		}
	}

	#reconcileReceipt(receipt) {
		if (receipt === null || receipt.pending !== null || receipt.retry !== null || this.#stopping || this.#closed) return;
		const { message, connectionEpoch } = receipt;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) {
			this.#receiptReconciliations.delete(receipt.key);
			receipt.resolve();
			return;
		}
		receipt.pending = Promise.resolve().then(async () => {
			await receipt.predecessor;
			if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(connectionEpoch)) {
				this.#receiptReconciliations.delete(receipt.key);
				receipt.resolve();
				return;
			}
			if (!receipt.durable) {
				if (message.payload.actionType !== undefined) {
					const dispatch = await this.#playerMemory.notebook.findReceipt(message.agentId, { actionId: message.payload.actionId });
					if (dispatch?.actionType !== message.payload.actionType) throw codedRuntimeError('UNCORRELATED_ACTION_RECEIPT', 'Terminal action type does not match its durable dispatch; withholding ACK');
				}
				const stored = await this.#playerMemory.recordResult({ agentId: message.agentId, goalRevision: message.payload.goalRevision }, message.payload);
				if (stored !== true) throw codedRuntimeError('UNCORRELATED_ACTION_RECEIPT', 'No authoritative dispatch matches the terminal receipt; withholding ACK');
				receipt.durable = true;
				if (this.#isConnectionEpochCurrent(connectionEpoch)) this.#memorySummaries.delete(message.agentId);
			}
			if (!this.#stopping && !this.#closed && this.#isConnectionEpochCurrent(connectionEpoch)) await this.#acknowledgeActionResult(message, connectionEpoch);
			this.#receiptReconciliations.delete(receipt.key);
			receipt.resolve();
		}).catch((error) => {
			if (error?.code === 'UNCORRELATED_ACTION_RECEIPT' || ['RECEIPT_CONFLICT', 'RECEIPT_WORLD_REQUIRED'].includes(error?.message)) {
				// Missing or conflicting durable authority cannot be repaired by retrying
				// this payload. Leave it unacknowledged on the server, but release the
				// local queue so later, correctly correlated receipts can be persisted.
				this.#emitRuntimeError(error);
				this.#receiptReconciliations.delete(receipt.key);
				receipt.resolve();
				return;
			}
			// Storage errors are operational evidence, not failed gameplay. Keep the
			// exact receipt and retry without cancelling already-authorized work.
			receipt.failures++;
			this.#emitRuntimeError(error);
			if (!this.#stopping && !this.#closed && this.#isConnectionEpochCurrent(connectionEpoch)) {
				receipt.retry = setTimeout(() => { receipt.retry = null; this.#reconcileReceipt(receipt); }, Math.min(5_000, 100 * 2 ** Math.min(receipt.failures - 1, 6)));
				receipt.retry.unref?.();
			} else { this.#receiptReconciliations.delete(receipt.key); receipt.resolve(); }
		}).finally(() => { receipt.pending = null; });
	}

	async #sendRuntimeMessage(kind, type, agentId, payload) {
		const epochs = kind === 'native' ? this.#nativeRuntimeEpochs : this.#programRuntimeEpochs;
		const connectionEpoch = epochs.get(agentId);
		if (type === 'action_cancel' && !this.#isConnectionEpochCurrent(connectionEpoch)) return null;
		if (type === 'action_command' && this.#isConnectionEpochCurrent(connectionEpoch)) {
			// Both execution modes pass here. One latest dispatch per agent is enough
			// for active correlation; historical receipts still use durable authority.
			this.#latestActionDispatches.set(agentId, { actionId: payload.actionId, goalRevision: payload.goalRevision, actionType: payload.actionType, connectionEpoch });
		}
		if (kind === 'program' && type === 'action_command') {
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) throw Object.assign(new Error('Action belongs to an obsolete connection'), { code: 'STALE_SESSION' });
			await this.#playerMemory.recordDispatch(this.#registry.assertCurrentRevision(agentId, payload.goalRevision), payload);
		}
		if (kind === 'native' && type === 'action_command') {
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) throw codedRuntimeError('STALE_SESSION', 'Action belongs to an obsolete connection');
			const record = this.#registry.assertCurrentRevision(agentId, payload.goalRevision);
			if (!this.#nativeRuntime.hasCurrent(record)) {
				const lifecycleGeneration = this.#lifecycleGeneration(agentId);
				// Conversation replies can precede the first world observation. Retain
				// their exact dispatch in a durable transport-session scope. The
				// ephemeral "session:" notebook namespace intentionally never reaches
				// disk; this separate scope is not an observed world or model memory.
				await this.#playerMemory.notebook.recordDispatch(agentId, {
					worldId: `dispatch-session:${this.#playerMemory.sessionId}`,
					actionId: payload.actionId, goalRevision: payload.goalRevision,
					actionType: payload.actionType, arguments: payload.arguments,
				});
				if (!this.#isLifecycleGenerationCurrent(agentId, lifecycleGeneration)
					|| this.#nativeRuntime.isActionResultStale(record, payload)) throw codedRuntimeError('STALE_PLAN', 'Native action lifecycle ended before bridge send');
				this.#registry.assertCurrentRevision(agentId, payload.goalRevision);
			}
		}
		return this.#sendForEpoch(connectionEpoch, type, agentId, payload);
	}

	async #reportAgentError(agentId, error, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		try {
			const verboseRecord = this.#registry.get(agentId);
			if (QUIET_LIFECYCLE_ERRORS.has(error?.code)) return;
			if (classifyRecoveryFailure(error).quiet) {
				// App-server transport silence is retried from the next fresh observation.
				// It is not a world-action failure that the player or agent must repair.
				if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'retry', 'Provider output was incomplete; retrying from the next fresh observation.', this.#connectionEpoch, {
					component: 'provider', boundary: 'planning', code: String(error?.code ?? 'EMPTY_PROVIDER_TURN'), state: 'retrying',
				});
				const retryAt = safeClockRead(this.#controlNow);
				if (retryAt === null) this.#providerRetryAfter.delete(agentId);
				else this.#providerRetryAfter.set(agentId, retryAt + EMPTY_TURN_RETRY_DELAY_MS);
				return;
			}
			if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'error', verboseErrorMessage(error), this.#connectionEpoch, {
				component: 'coordinator', boundary: 'agent_work', code: String(error?.code ?? 'COORDINATOR_ERROR'), state: 'degraded',
			});
			this.#emitRuntimeError(error);
			if (!this.#bridge.ready || !this.#registry.has(agentId)) return;
			const record = this.#registry.get(agentId);
			try {
				await this.#sendForEpoch(connectionEpoch, 'agent_error', agentId, {
					goalRevision: record.goalRevision,
					code: sanitizeDiagnosticErrorCode(error, { fallback: 'COORDINATOR_ERROR' }),
					message: sanitizeDiagnosticErrorMessage(error, { maxBytes: 2_048 }),
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

	#verboseReporter(agentId, goalRevision, { allowPublicAgentMessage = false, connectionEpoch = this.#connectionEpoch } = {}) {
		let publishedAgentMessage = false;
		const reporter = (stage, message) => {
			try {
				const viewRecord = this.#registry.get(agentId);
				if (viewRecord !== null && viewRecord.goalRevision === goalRevision && this.#isConnectionEpochCurrent(connectionEpoch)) {
					this.#taskViews.event(viewRecord, stage, message);
					if (stage === 'live_usage') {
						const value = JSON.parse(message), usage = this.#taskViews.snapshot(viewRecord).usage;
						if (usage !== null) this.#writeTrace('native_token_usage', { agentId, goalRevision, scope: 'thread_total', sessionKey: createHash('sha256').update(String(value.threadId ?? '')).digest('hex'), ...usage });
					}
				}
				if (!this.#verboseEnabled) return;
				if (allowPublicAgentMessage && stage === 'agent_message') {
					if (publishedAgentMessage) return;
					const publicMessage = sanitizePublicAgentMessage(message);
					if (publicMessage.length === 0) return;
					publishedAgentMessage = true;
					this.#publishVerbose(agentId, goalRevision, 'decision', publicMessage, connectionEpoch);
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
		this.#verboseTransitions.clearAll();
		for (const reporter of this.#verboseReporters) reporter.reset();
	}

	#publishVerbose(agentId, goalRevision, stage, message, connectionEpoch = this.#connectionEpoch, transition = {}) {
		try {
			const viewRecord = this.#registry.get(agentId);
			if (viewRecord !== null && viewRecord.goalRevision === goalRevision && this.#isConnectionEpochCurrent(connectionEpoch)) this.#taskViews.event(viewRecord, stage, message);
			const bounded = sanitizeVerboseMessage(stage, message);
			if (bounded.length === 0) return;
			const identity = verboseTransitionIdentity(stage, bounded, transition);
			if (!this.#verboseTransitions.accept({ agentId, goalRevision, ...identity })) return;
			this.#sendVerbose(agentId, goalRevision, stage, bounded, connectionEpoch);
		} catch { /* verbose delivery is best effort */ }
	}

	#sendVerbose(agentId, goalRevision, stage, message, connectionEpoch) {
		if (!this.#verboseEnabled || !this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready || !VERBOSE_STAGES.includes(stage)) return;
		const current = this.#registry.get(agentId);
		if (current === null || current.goalRevision !== goalRevision || message.length === 0 || message.length > MAX_VERBOSE_MESSAGE_LENGTH) return;
		Promise.resolve(this.#sendForEpoch(connectionEpoch, 'verbose_event', agentId, { goalRevision, stage, message })).catch(() => {});
	}

	async #publishCatalog(snapshot, connectionEpoch = this.#connectionEpoch) {
		await this.#sendForEpoch(connectionEpoch, 'catalog_snapshot', 'server', {
			refreshedAtEpochMs: snapshot.refreshedAtEpochMs,
			models: snapshot.models,
		});
	}

	async #publishGoalCompleted({ record, goalFingerprint, traceId }, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready) throw codedRuntimeError('BRIDGE_NOT_READY', 'Minecraft bridge is not ready for completion verification');
		if (!this.#supportedAgentIds.has(record.agentId)) throw codedRuntimeError('AGENT_NOT_SUPPORTED', `Agent '${record.agentId}' is not in the reconciled bridge roster`);
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		};
		try {
			await this.#sendForEpoch(connectionEpoch, 'goal_completed', record.agentId, {
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

	async #publishStatus(connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready) return;
		const records = this.#registry.list();
		const readyStates = new Set([DynamicAgentState.IDLE, DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.PAUSED, DynamicAgentState.COMPLETED]);
		const profiles = records.filter((record) => this.#supportedAgentIds.has(record.agentId));
		const healthIdentities = profiles.flatMap((profile) => ['create_agent', this.#usesNativeTools(profile) ? 'native_turn' : 'decide'].map((operation) => ({
			provider: profile.provider,
			model: profile.model,
			profileFingerprint: profileFingerprint(profile),
			operation,
		})));
		// The wire contract has one row per provider/model/operation. Publish the
		// least healthy profile's complete snapshot, retaining truthful quantiles
		// instead of averaging percentiles or masking an open profile circuit.
		const healthByOperation = new Map();
		const circuitRank = { closed: 0, half_open: 1, open: 2 };
		for (const identity of healthIdentities) {
			const snapshot = this.#healthRegistry.snapshot(identity);
			const key = JSON.stringify([identity.provider, identity.model, identity.operation]);
			const previous = healthByOperation.get(key);
			if (previous === undefined || circuitRank[snapshot.circuit] > circuitRank[previous.circuit]
				|| (snapshot.circuit === previous.circuit && (snapshot.failureRate > previous.failureRate
					|| (snapshot.failureRate === previous.failureRate && snapshot.count > previous.count)))) healthByOperation.set(key, snapshot);
		}
		const healthSnapshots = [...healthByOperation.values()].sort((left, right) => left.provider.localeCompare(right.provider)
			|| left.model.localeCompare(right.model) || left.operation.localeCompare(right.operation));
		const pressure = this.#scheduler.pressureSnapshot;
		let providerRecovery = [];
		try { providerRecovery = this.#codexService.recoverySnapshot?.() ?? []; }
		catch { /* optional status must not affect coordinator control */ }
		const components = providerRecoveryComponents(providerRecovery);
		try {
			const diagnostics = this.#traceWriter?.statusSnapshot?.();
			if (diagnostics !== null && diagnostics !== undefined) components.push(diagnostics);
		} catch { /* optional status must not affect coordinator control */ }
		try {
			const providerAudit = this.#providerTurnRecorder?.statusSnapshot?.();
			if (providerAudit !== null && providerAudit !== undefined) components.push(providerAudit);
		} catch { /* optional status must not affect coordinator control */ }
		await this.#sendForEpoch(connectionEpoch, 'coordinator_status', 'server', buildCoordinatorStatus({
			reconciled: this.#reconciledStatus,
			records,
			supportedAgentIds: this.#supportedAgentIds,
			readyStates,
			pressure: {
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
			healthSnapshots: healthSnapshots.slice(0, 32),
			latencies: this.#latencyRegistry.snapshot(),
			bridgeSessionEpoch: connectionEpoch,
			runtimeGeneration: this.#runtimeGeneration,
			components,
		}));
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
		if (record === null || this.#serverInstanceId === null) {
			return {
				untrustedFacts: ledger.toPlannerFacts(),
				conversationContext: memory.toPlannerContext(),
				taskMemory: record === null ? null : this.#playerMemory.peekTaskContext(record),
			};
		}
		const binding = this.#contextBinding(record);
		return {
			factLedger: ledger,
			taskMemory: this.#playerMemory.peekTaskContext(record),
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
			agentId: record.agentId,
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
		// Accept only the exact delivered session/server/goal and captured revisions.
		if (snapshot == null || !contextCursorMatches(snapshot, binding)) return;
		const revisions = snapshot;
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

	#invalidateServerInstance(connectionEpoch) {
		this.#cancelGoalSpecRequests();
		this.#healthRegistry.reset();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		for (const inbox of this.#pendingConversationInboxes.values()) inbox.fence();
		this.#nativeConversationRecoveries.clear();
		this.#nativeObservationSignatures.clear();
		this.#nativeWorldSignals.clear();
		this.#nativeConfirmationWaits.clear();
		this.#supervisedObservationRequests.clear();
		this.#conversationWakeTransactions.clear();
		this.#providerWork.clear();
		this.#providerProbeDeadlines.clear();
		this.#deferredProviderRecovery.clear();
		this.#programRuntimeEpochs.clear();
		this.#nativeRuntimeEpochs.clear();
		for (const record of this.#registry.list()) {
			this.#goalSupervisor.terminate(this.#supervisionKey(record));
			this.#advanceLifecycleGeneration(record.agentId);
			this.#programRuntime.dispose(record.agentId);
			void this.#nativeRuntime.dispose(record.agentId, 'server_replaced');
			this.#pendingAttention.delete(record.agentId);
			this.#attentionFlushes.delete(record.agentId);
			this.#providerRetryAfter.delete(record.agentId);
			try {
				Promise.resolve(this.#planner.interrupt(record.agentId, 'Minecraft server instance changed'))
					.catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
			} catch (error) {
				void this.#reportAgentError(record.agentId, error, connectionEpoch);
			}
		}
	}

	#goalSpecRequestKey(agentId, requestId) {
		return `${this.#serverInstanceId ?? 'disconnected'}\u0000${agentId}\u0000${requestId}`;
	}

	async #processGoalSpecRequest(key, entry) {
		if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null
				|| !this.#isConnectionEpochCurrent(entry.connectionEpoch) || entry.translating) return;
		if (entry.retryHandle !== null) {
			this.#clearGoalSpecTimeout(entry.retryHandle);
			entry.retryHandle = null;
		}
		if (entry.proposal !== null) {
			try {
				await this.#sendForEpoch(entry.connectionEpoch, 'goal_spec_proposal', entry.agentId, entry.proposal);
			} catch (error) {
				if (this.#goalSpecRequests.get(key) === entry) this.#emitRuntimeError(error);
			}
			if (this.#goalSpecRequests.get(key) === entry) this.#scheduleGoalSpecRequest(key, entry, GOAL_SPEC_PROPOSAL_RETRY_MS);
			return;
		}
		entry.translating = true;
		try {
			await this.#reconciliation;
			if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null
					|| !this.#isConnectionEpochCurrent(entry.connectionEpoch)) return;
			entry.proposal = await this.#planner.requestGoalSpec({
				agentId: entry.agentId,
				request: entry.request,
				...(entry.correctiveFeedback === null ? {} : { correctiveFeedback: entry.correctiveFeedback }),
			});
			if (entry.proposal.plan !== undefined) {
				this.#taskViews.suggest(entry.agentId, entry.request.originalRequest, entry.proposal.plan);
				const { plan, ...wireProposal } = entry.proposal;
				entry.proposal = wireProposal;
			}
			entry.attempts = 0;
		} catch (error) {
			if (this.#goalSpecRequests.get(key) !== entry) return;
			this.#emitRuntimeError(error);
			const localFeedback = localGoalSpecFeedback(error, entry.rejectionAttempts + 1);
			if (localFeedback !== null) {
				entry.rejectionAttempts += 1;
				entry.correctiveFeedback = localFeedback;
				entry.proposal = null;
				if (entry.rejectionAttempts > MAX_GOAL_SPEC_CORRECTION_ATTEMPTS && (entry.serverRejected || fallbackCompiledDragonGoal(entry.request) === null)) {
					this.#forgetGoalSpecRequest(key, entry);
					await this.#reportAgentError(entry.agentId, codedRuntimeError('GOAL_SPEC_TRANSLATION_REJECTED',
						`Goal translation failed validation after ${entry.rejectionAttempts} proposals (${localFeedback.reasonCode}); the pending draft requires operator correction or cancellation`), entry.connectionEpoch);
					return;
				}
				if (entry.rejectionAttempts <= MAX_GOAL_SPEC_CORRECTION_ATTEMPTS) {
					this.#scheduleGoalSpecRequest(key, entry, Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** (entry.rejectionAttempts - 1))));
					return;
				}
			}
			// Only an exact, server-constrained dragon goal may proceed when its
			// optional Luna advice is unavailable. Other translations keep retrying.
			if (!entry.serverRejected) {
				entry.proposal = fallbackCompiledDragonGoal(entry.request);
			}
			if (entry.proposal === null) {
				entry.attempts += 1;
				const delay = Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** Math.min(entry.attempts - 1, 5)));
				this.#scheduleGoalSpecRequest(key, entry, delay);
				return;
			}
		} finally {
			entry.translating = false;
		}
		if (this.#goalSpecRequests.get(key) === entry) await this.#processGoalSpecRequest(key, entry);
	}

	#scheduleGoalSpecRequest(key, entry, delayMs) {
		if (entry.retryHandle !== null) this.#clearGoalSpecTimeout(entry.retryHandle);
		entry.retryHandle = this.#setGoalSpecTimeout(() => {
			entry.retryHandle = null;
			this.#run(() => this.#processGoalSpecRequest(key, entry), entry.connectionEpoch);
		}, delayMs);
	}

	#forgetGoalSpecRequest(key, entry) {
		if (this.#goalSpecRequests.get(key) !== entry) return;
		this.#goalSpecRequests.delete(key);
		if (entry.retryHandle !== null) this.#clearGoalSpecTimeout(entry.retryHandle);
		try { this.#planner.cancelGoalSpec?.(entry.agentId, entry.requestId); } catch { /* cancellation is best effort */ }
		entry.releaseCapacity();
		entry.resolveCompletion();
	}

	#cancelGoalSpecRequests(agentId = null) {
		for (const [key, request] of this.#goalSpecRequests) {
			if (agentId !== null && request.agentId !== agentId) continue;
			this.#goalSpecRequests.delete(key);
			if (request.retryHandle !== null) this.#clearGoalSpecTimeout(request.retryHandle);
			try { this.#planner.cancelGoalSpec?.(request.agentId, request.requestId); } catch { /* cancellation is best effort */ }
			request.releaseCapacity();
			request.resolveCompletion();
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

	#pendingConversationInbox(agentId) {
		let inbox = this.#pendingConversationInboxes.get(agentId);
		if (!inbox) {
			inbox = new PendingConversationInbox({ directory: this.#memoryDirectory, agentId });
			this.#pendingConversationInboxes.set(agentId, inbox);
		}
		return inbox;
	}

	async #admitConversation(agentId, event, connectionEpoch, wake = null) {
		const record = this.#registry.get(agentId);
		const ingested = this.#usesNativeTools(record)
			? await this.#pendingConversationInbox(agentId).append(this.#serverInstanceId, event, wake)
			: true;
		// A retry can find a durable append whose original caller saw an I/O failure.
		const retryPending = this.#usesNativeTools(record) && !ingested
			? await this.#pendingConversationInbox(agentId).needsDelivery(event.sequence) : false;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) throw Object.assign(new Error('Conversation admission belongs to an obsolete connection'), { code: 'STALE_PLAN' });
		if (this.#usesNativeTools(record) && !ingested) return retryPending;
		const historyIngested = this.#conversationMemory(agentId).ingest(event);
		return this.#usesNativeTools(record) ? ingested : historyIngested;
	}

	async #nativeTurnInput(record, request, { deliverConversation = true } = {}) {
		if (request.nativeEvent === undefined) return request.input;
		const taskMemory = request.nativeEvent.event === 'program_planning_due' ? null : await this.#playerMemory.taskContext(record);
		const isCurrent = () => this.#registry.get(record.agentId)?.goalRevision === record.goalRevision
			&& this.#isConnectionEpochCurrent(request.connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration);
		if (!isCurrent()) throw Object.assign(new Error('Native input belongs to an obsolete lifecycle'), { code: 'STALE_PLAN' });
		delete request.nativeConversationDelivery;
		const inbox = this.#pendingConversationInbox(record.agentId);
		await inbox.open(this.#serverInstanceId);
		if (!isCurrent()) throw Object.assign(new Error('Native input belongs to an obsolete lifecycle'), { code: 'STALE_PLAN' });
		const reservation = deliverConversation ? await inbox.reserve() : null;
		if (reservation) request.nativeConversationDelivery = { inbox, token: reservation.token, omittedEntries: 0 };
		try {
			if (!isCurrent()) throw Object.assign(new Error('Native input belongs to an obsolete lifecycle'), { code: 'STALE_PLAN' });
			const input = buildNativeEventInput(record, {
				...request.nativeEvent, taskMemory,
				observation: this.#nativeRuntime.decorateObservation(record, request.nativeEvent.observation ?? {}),
				conversation: reservation?.conversation ?? { mode: 'unread', baseSequence: null, nextSequence: -1, entries: [] },
			});
			if (reservation) {
				const delivered = JSON.parse(input.slice(input.indexOf('\n') + 1)).conversation;
				inbox.trim(reservation.token, delivered.entries.length);
				request.nativeConversationDelivery.omittedEntries = delivered.omittedEntries + (reservation.more ? 1 : 0);
			}
			return request.retryInstruction === undefined ? input : `${input}\n${request.retryInstruction}`;
		} catch (error) { this.#restoreNativeConversation(request); throw error; }
	}

	async #commitNativeConversation(request) {
		const delivery = request?.nativeConversationDelivery;
		if (delivery?.token) {
			await delivery.inbox.commit(delivery.token);
			delete delivery.token;
		}
	}

	#restoreNativeConversation(request) {
		const delivery = request?.nativeConversationDelivery;
		if (delivery?.token) delivery.inbox.rollback(delivery.token);
		if (request) delete request.nativeConversationDelivery;
	}

	#forgetNativeConversationRecovery(supervisionKey) {
		const recovery = this.#nativeConversationRecoveries.get(supervisionKey.agentId);
		if (recovery !== undefined && sameSupervisionKey(recovery.supervisionKey, supervisionKey)) {
			this.#nativeConversationRecoveries.delete(supervisionKey.agentId);
		}
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
	const coordinatorEnvironment = dependencies.env ?? process.env;
	const config = normalizeDynamicConfig(configValue, coordinatorEnvironment);
	const providerEnvironments = Object.fromEntries(PROVIDER_IDS.map((provider) => [
		provider,
		createProviderChildEnvironment(provider, coordinatorEnvironment, config.bridge.secretEnvironmentVariable,
			config.voice.provider === 'openai' ? { speechApiKeyEnvironmentVariable: config.voice.openaiApiKeyEnvironmentVariable } : {}),
	]));
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
		codex: new CodexService({ ...config.codex, environment: providerEnvironments.codex, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transport: dependencies.codexTransport, now: dependencies.now ?? Date.now, workspaceManager, minecraftWorkspace }),
		gemini: new AntigravityProviderService({ ...config.gemini, environment: providerEnvironments.gemini, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.antigravitySpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
		kimi: new AcpProviderService({ ...config.kimi, environment: providerEnvironments.kimi, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transportFactory: dependencies.kimiTransportFactory, workspaceManager }),
		cursor: new CursorProviderService({ ...config.cursor, environment: providerEnvironments.cursor, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.cursorSpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
	}, { turnRecorder: providerTurnRecorder, now: dependencies.epochNow ?? Date.now });
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
		nativeTimingSink: (event, fields) => dependencies.traceWriter?.write(event, fields),
		benchmarkRecorder: dependencies.benchmarkRecorder,
		turnRecorder: providerTurnRecorder,
	});
	const bridge = dependencies.bridge ?? new MultiplexedServerBridge(config.bridge, {
		audit: dependencies.protocolAudit,
		socketFactory: dependencies.socketFactory,
		schedule: dependencies.schedule,
		cancelSchedule: dependencies.cancelSchedule,
		scheduleDeadline: dependencies.scheduleDeadline,
		cancelDeadline: dependencies.cancelDeadline,
		currentRevision: (agentId) => registry.get(agentId)?.goalRevision ?? null,
	});
	let coordinator = null;
	const goalSupervisor = dependencies.goalSupervisor ?? new ActiveGoalSupervisor({
		requestObservation: (key, reason) => coordinator?.requestSupervisedObservation(key, reason) ?? false,
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
		memoryDirectory: Object.hasOwn(dependencies, 'memoryDirectory') ? dependencies.memoryDirectory : path.join(config.workspaceRoot, 'player-memory'),
		runtimeSessionId: dependencies.runtimeSessionId,
		traceWriter: dependencies.traceWriter,
		providerTurnRecorder,
		runtimeGeneration: dependencies.runtimeGeneration,
		runtimeHooks: dependencies.runtimeHooks,
		controlNow: dependencies.controlNow,
		epochNow: dependencies.epochNow,
		setStatusInterval: dependencies.setStatusInterval,
		clearStatusInterval: dependencies.clearStatusInterval,
		setGoalSpecTimeout: dependencies.setGoalSpecTimeout,
		clearGoalSpecTimeout: dependencies.clearGoalSpecTimeout,
		maxPendingAgentOperations: dependencies.maxPendingAgentOperations,
		maxPendingAgentTransactions: dependencies.maxPendingAgentTransactions,
		connectionOperationCap: dependencies.connectionOperationCap,
		agentOperationCap: dependencies.agentOperationCap,
		goalSpecRequestCap: dependencies.goalSpecRequestCap,
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
		runtimeGeneration: /^[0-9a-f]{64}$/.test(environment.ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION ?? '')
			? environment.ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION
			: null,
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
	value = migrateDynamicConfig(value);
	assertKnownConfigKeys(value, ['schemaVersion', 'bridge', 'voice', 'codex', 'gemini', 'kimi', 'cursor', 'limits', 'workspaceRoot', 'minecraftAgentRoot', 'minecraftAgentTemplateRoot'], 'config');
	if (value.bridge === null || typeof value.bridge !== 'object' || Array.isArray(value.bridge)) throw new TypeError('dynamic coordinator bridge config must be an object');
	if (value.codex === null || typeof value.codex !== 'object' || Array.isArray(value.codex)) throw new TypeError('dynamic coordinator Codex config must be an object');
	if (value.voice !== undefined && (value.voice === null || typeof value.voice !== 'object' || Array.isArray(value.voice))) throw new TypeError('dynamic coordinator voice config must be an object');
	assertOptionalConfigObject(value.limits, 'limits');
	assertOptionalConfigObject(value.gemini, 'gemini');
	assertOptionalConfigObject(value.kimi, 'kimi');
	assertOptionalConfigObject(value.cursor, 'cursor');
	assertKnownConfigKeys(value.bridge, ['host', 'port', 'secret', 'secretEnvironmentVariable', 'reconnectDelayMs', 'maxReconnectDelayMs', 'connectionQueueCap', 'agentQueueCap', 'inboundConnectionQueueCap', 'inboundAgentQueueCap', 'inboundDispatchBatch', 'trackedTerminalActionIdCap', 'handshakeTimeoutMs', 'heartbeatIntervalMs', 'heartbeatTimeoutMs', 'serverInstanceId', 'launchId'], 'bridge');
	assertKnownConfigKeys(value.voice ?? {}, ['port', 'maxConcurrent', 'profileAssignmentsPath', 'provider', 'openaiApiKeyEnvironmentVariable', 'openaiTtsModel', 'openaiSttModel', 'fishApiKeyEnvironmentVariable', 'deepgramApiKeyEnvironmentVariable', 'localSpeechTimeoutMs', 'localSpeechPythonPath', 'secret', 'secretFile'], 'voice');
	assertKnownConfigKeys(value.codex, ['cwd', 'controlProtocol', 'planningTimeoutMs', 'maxDecisionBytes', 'catalogTtlMs', 'startupTimeoutMs', 'serviceTier', 'launchProfile'], 'codex');
	if (value.codex.launchProfile !== undefined) {
		assertOptionalConfigObject(value.codex.launchProfile, 'codex.launchProfile');
		assertKnownConfigKeys(value.codex.launchProfile, ['agentId', 'model', 'reasoningEffort', 'serviceTier', 'planningTimeoutMs', 'maxDecisionBytes', 'cwd'], 'codex.launchProfile');
	}
	const providerKeys = ['provider', 'cwd', 'executable', 'models', 'reasoningEfforts', 'modelReasoningEfforts', 'catalogDiscovery', 'catalogDiscoveryTimeoutMs', 'planningTimeoutMs', 'maxDecisionBytes', 'stdoutLimitBytes', 'stderrLimitBytes', 'serviceTier'];
	for (const provider of ['gemini', 'kimi', 'cursor']) assertKnownConfigKeys(value[provider] ?? {}, providerKeys, provider);
	assertKnownConfigKeys(value.limits ?? {}, ['agentCap', 'goalQueueCap', 'planningConcurrency', 'planningMode', 'urgentReserve', 'invalidDecisionRetries'], 'limits');
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
	const urgentReserve = value.limits?.urgentReserve ?? (agentCap >= 4 && planningConcurrency > 1 ? 1 : 0);
	if (!Number.isSafeInteger(urgentReserve) || urgentReserve < 0 || urgentReserve > agentCap) throw new TypeError('limits.urgentReserve must be a non-negative safe integer within limits.agentCap');
	if (urgentReserve >= (planningMode === 'adaptive' ? 4 : planningConcurrency)) throw new TypeError('limits.urgentReserve must leave at least one ordinary planning slot at every target');
	const voice = normalizeVoiceConfig(value.voice, environment);
	const codexControlProtocol = value.codex.controlProtocol ?? 'native_tools';
	if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codex.controlProtocol must be arena_script or native_tools');
	return {
		schemaVersion: 1,
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

function migrateDynamicConfig(value) {
	const schemaVersion = value.schemaVersion ?? 0;
	if (!Number.isSafeInteger(schemaVersion) || schemaVersion < 0) throw new TypeError('config.schemaVersion must be a nonnegative safe integer');
	if (schemaVersion > 1) throw new TypeError(`Unsupported dynamic coordinator config schemaVersion ${schemaVersion}`);
	if (schemaVersion === 1) return value;
	const cursor = value.cursor === undefined || value.cursor === null || typeof value.cursor !== 'object' || Array.isArray(value.cursor)
		? value.cursor
		: Object.fromEntries(Object.entries(value.cursor).filter(([key]) => key !== 'serviceTiers'));
	return { ...value, schemaVersion: 1, ...(cursor === undefined ? {} : { cursor }) };
}

function assertOptionalConfigObject(value, field) {
	if (value !== undefined && (value === null || typeof value !== 'object' || Array.isArray(value))) {
		throw new TypeError(`dynamic coordinator ${field} config must be an object`);
	}
}

function assertKnownConfigKeys(value, allowed, field) {
	for (const key of Object.keys(value)) {
		if (!allowed.includes(key)) throw new TypeError(`Unknown dynamic coordinator config key '${field}.${key}'`);
	}
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
	const voiceSupervisor = createVoiceSupervisor(config, process.env);
	const coordinator = createDynamicCoordinator(config, {
		traceWriter, protocolAudit, providerTurnRecorder, runtimeGeneration: runtime.runtimeGeneration,
		runtimeHooks: { onRemoved: (agentId) => voiceSupervisor.removeAgent(agentId) },
	});
	const disposeDiagnostics = wireRuntimeDiagnostics(coordinator, reporter);
	try {
		await startCoordinatorControl(coordinator, voiceSupervisor);
	} catch (error) {
		await Promise.allSettled([coordinator.stop(), voiceSupervisor.close(), protocolAudit?.close()]);
		disposeDiagnostics();
		throw error;
	}
	let shutdownPromise = null;
	const shutdown = async () => {
		shutdownPromise ??= Promise.allSettled([coordinator.stop(), voiceSupervisor.close(), protocolAudit?.close()])
			.finally(disposeDiagnostics);
		await shutdownPromise;
		process.exitCode = 0;
	};
	process.once('SIGINT', shutdown);
	process.once('SIGTERM', shutdown);
}

export async function startCoordinatorControl(coordinator, voiceSupervisor) {
	if (coordinator === null || typeof coordinator?.start !== 'function') {
		throw new TypeError('coordinator.start is required');
	}
	if (voiceSupervisor === null || typeof voiceSupervisor?.start !== 'function'
			|| typeof voiceSupervisor?.close !== 'function') {
		throw new TypeError('voiceSupervisor.start and voiceSupervisor.close are required');
	}
	if (typeof coordinator.once === 'function') {
		coordinator.once('shutdown', () => { void Promise.resolve(voiceSupervisor.close()).catch(() => {}); });
	}
	await coordinator.start();
	try { Promise.resolve(voiceSupervisor.start()).catch(() => {}); }
	catch { /* optional voice startup cannot reject coordinator control */ }
}

export function createVoiceSupervisor(config, environment = process.env, dependencies = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) {
		throw new TypeError('voice supervisor config must be an object');
	}
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('voice supervisor environment must be an object');
	}
	const localSpeechTimeoutMs = config.voice?.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS;
	if (!Number.isSafeInteger(localSpeechTimeoutMs) || localSpeechTimeoutMs < 1 || localSpeechTimeoutMs > 600_000) {
		throw new TypeError('voice.localSpeechTimeoutMs must be between 1 and 600000');
	}
	const reportVoiceDiagnostic = dependencies.reportVoiceDiagnostic ?? writeVoiceDiagnostic;
	if (typeof reportVoiceDiagnostic !== 'function') throw new TypeError('reportVoiceDiagnostic must be a function');
	const startWorker = dependencies.startWorker
		?? (({ signal }) => startVoiceWorker(config, environment, { signal, reportVoiceDiagnostic }));
	const reportFailure = dependencies.reportFailure ?? (({ failureCode }) => {
		process.stderr.write(`[voice-supervisor] ${failureCode}: proximity speech unavailable; retrying automatically\n`);
	});
	return new VoiceSupervisor({
		startWorker,
		warmupTimeoutMs: Math.min(Number.MAX_SAFE_INTEGER, localSpeechTimeoutMs + VOICE_WARMUP_GRACE_MS),
		onFailure: reportFailure,
		...(dependencies.supervisorOptions ?? {}),
	});
}

export function createJsonlAudit(filePath, metadata, dependencies = {}) {
	if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('protocol audit path must be nonblank');
	const makeDirectory = dependencies.mkdir ?? mkdir;
	let queue;
	const sink = new RotatingJsonlSink(filePath, {
		...dependencies,
		inspect: dependencies.appendFile === undefined || dependencies.stat !== undefined
			|| dependencies.maxFileBytes !== undefined || dependencies.maxFileAgeMs !== undefined,
	});
	let closed = false;
	let closePromise = null;
	// Attach rejection handling immediately, including when no audit row ever arrives.
	const ready = Promise.resolve().then(() => makeDirectory(path.dirname(path.resolve(filePath)), { recursive: true }))
		.then(() => true, () => { queue.reportFailure(); return false; });
	queue = new BestEffortDiagnosticQueue({ ...dependencies, ready });
	const requireReady = async () => {
		if (!await ready) throw new Error('protocol audit directory is unavailable');
	};
	const audit = (direction, envelope) => {
		if (closed) return Promise.resolve();
		try {
			const safeMetadata = sanitizeDiagnosticValue(metadata);
			const row = Object.assign(Object.create(null),
				safeMetadata !== null && typeof safeMetadata === 'object' && !Array.isArray(safeMetadata) ? safeMetadata : {},
				{ direction: sanitizeDiagnosticValue(direction), envelope: sanitizeDiagnosticValue(envelope) });
			const encoded = `${JSON.stringify(row)}\n`;
			queue.submit(async () => { await requireReady(); await sink.append(encoded, { encoding: 'utf8', flag: 'a' }); });
		} catch { /* invalid audit evidence is observational */ }
		return Promise.resolve();
	};
	audit.close = () => {
		if (closePromise !== null) return closePromise;
		closed = true;
		closePromise = queue.close();
		return closePromise;
	};
	audit.statusSnapshot = () => Object.freeze({ ...queue.statusSnapshot('protocol_audit'), droppedCount: queue.droppedCount });
	return audit;
}

export async function startVoiceWorker(config, environment = process.env, dependencies = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('voice worker config must be an object');
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('voice worker environment must be an object');
	const voice = config.voice ?? {};
	const localSpeechTimeoutMs = voice.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS;
	if (!Number.isSafeInteger(localSpeechTimeoutMs) || localSpeechTimeoutMs < 1 || localSpeechTimeoutMs > 600_000) {
		throw new TypeError('voice.localSpeechTimeoutMs must be between 1 and 600000');
	}
	const signal = dependencies.signal;
	const reportVoiceDiagnostic = dependencies.reportVoiceDiagnostic ?? (() => {});
	if (typeof reportVoiceDiagnostic !== 'function') throw new TypeError('reportVoiceDiagnostic must be a function');
	if (signal !== undefined && (signal === null || typeof signal !== 'object' || typeof signal.aborted !== 'boolean')) {
		throw new TypeError('voice startup signal must be an AbortSignal');
	}
	throwIfVoiceStartupAborted(signal);
	const fishApiKey = firstNonBlank(
		environment[voice.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE],
		environment.FISH_API_KEY,
	);
	const deepgramApiKey = firstNonBlank(
		environment[voice.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE],
	);
	const platform = dependencies.platform ?? process.platform;
	const openaiApiKey = firstNonBlank(environment[voice.openaiApiKeyEnvironmentVariable ?? 'OPENAI_API_KEY']);
	if (voice.provider === 'openai') {
		if (openaiApiKey === null) throw codedRuntimeError('VOICE_OPENAI_KEY_MISSING', 'Set OPENAI_API_KEY on the host and restart Minecraft to enable OpenAI speech');
		return startOpenAiVoiceWorker(voice, openaiApiKey, dependencies, { signal, reportVoiceDiagnostic, localSpeechTimeoutMs });
	}
	const createLocalSpeechProvider = dependencies.createLocalSpeechProvider
		?? ((options) => LocalSpeechProvider.createIfAvailable(options));
	if (typeof createLocalSpeechProvider !== 'function') throw new TypeError('createLocalSpeechProvider must be a function');
	let localSpeechProvider = null;
	let profiles = null;
	let profileStore = null;
	let worker = null;
	try {
		localSpeechProvider = await createLocalSpeechProvider({
			executable: firstNonBlank(environment.ARENA_AGENT_SPEECH_PYTHON, voice.localSpeechPythonPath)
				?? path.resolve(PROJECT_DIRECTORY, defaultLocalSpeechPythonPath(platform)),
			scriptPath: path.join(SOURCE_DIRECTORY, 'voice', 'local-speech-worker.py'),
			timeoutMs: localSpeechTimeoutMs,
			environment,
			signal,
			accessFile: dependencies.localSpeechAccess,
		});
		throwIfVoiceStartupAborted(signal);
		if (localSpeechProvider === null && fishApiKey === null && deepgramApiKey === null && platform !== 'win32') return null;
		const voiceSecret = await resolveVoiceSecret(voice, dependencies.readVoiceSecret ?? readFile, signal);
		throwIfVoiceStartupAborted(signal);
		const createFishTtsProvider = dependencies.createTtsProvider ?? ((options) => new FishTtsProvider(options));
		let fishTtsProvider;
		const createTtsProvider = (options) => fishTtsProvider ??= createFishTtsProvider(options);
		const createWindowsTtsProvider = dependencies.createWindowsTtsProvider ?? ((options) => new WindowsTtsProvider(options));
		const createSttProvider = dependencies.createSttProvider ?? ((options) => new DeepgramSttProvider(options));
		const createServer = dependencies.createVoiceServer ?? createVoiceHttpServer;
		if (typeof createFishTtsProvider !== 'function') throw new TypeError('createTtsProvider must be a function');
		if (typeof createWindowsTtsProvider !== 'function') throw new TypeError('createWindowsTtsProvider must be a function');
		if (typeof createServer !== 'function') throw new TypeError('createVoiceServer must be a function');
		const hasTtsProvider = localSpeechProvider !== null || fishApiKey !== null || platform === 'win32';
		if (hasTtsProvider) {
			const profilePath = dependencies.profilePath
				?? voice.profileAssignmentsPath
				?? path.resolve(PROJECT_DIRECTORY, DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH);
			const loadProfileStore = dependencies.loadProfileStore ?? loadPersistentVoiceProfileStore;
			if (typeof loadProfileStore !== 'function') throw new TypeError('loadProfileStore must be a function');
			try {
				profiles = await loadProfileStore(profilePath, { ...(dependencies.voiceProfileIo ?? {}), signal });
			} catch (error) {
				if (!canUseVolatileLocalProfiles({ error, localSpeechProvider, fishApiKey, deepgramApiKey, platform })) throw error;
				profiles = { store: new VoiceProfileStore() };
			}
			throwIfVoiceStartupAborted(signal);
			if (profiles === null || typeof profiles !== 'object' || profiles.store === null || typeof profiles.store?.resolve !== 'function') {
				throw new TypeError('loadProfileStore must return a profile store');
			}
			profileStore = voiceProfileStoreWithLifecycle(profiles);
		} else {
			profileStore = voiceProfileStoreWithLifecycle({ store: STT_ONLY_PROFILE_STORE });
		}
		if (deepgramApiKey !== null && typeof createSttProvider !== 'function') throw new TypeError('createSttProvider must be a function when Deepgram is configured');
		let provider;
		let sttProvider;
		let ownedSpeechProvider = localSpeechProvider;
		if (localSpeechProvider !== null) {
			const fallback = fishApiKey !== null || deepgramApiKey !== null
				? createRemoteFirstSpeechRouting(localSpeechProvider, {
					fishApiKey,
					deepgramApiKey,
					platform,
					createTtsProvider,
					createWindowsTtsProvider,
					createSttProvider,
					fallbackCircuit: voiceFallbackCircuitOptions(dependencies),
				})
				: createLocalSpeechFailover(localSpeechProvider, {
				fishApiKey,
				deepgramApiKey,
				platform,
				createTtsProvider,
				createWindowsTtsProvider,
				createSttProvider,
				fallbackCircuit: voiceFallbackCircuitOptions(dependencies),
				});
			provider = fallback.tts;
			sttProvider = fallback.stt;
			ownedSpeechProvider = fallback;
		} else {
			provider = fishApiKey === null
				? (platform === 'win32' ? createWindowsTtsProvider({}) : null)
				: createTtsProvider({ apiKey: fishApiKey });
			if (fishApiKey !== null && platform === 'win32') {
				provider = ttsProviderWithFallback(provider, createWindowsTtsProvider({}), voiceFallbackCircuitOptions(dependencies));
			}
			sttProvider = deepgramApiKey === null ? new NoSttProvider() : createSttProvider({ apiKey: deepgramApiKey });
		}
		emitVoiceDiagnostic(reportVoiceDiagnostic, fishApiKey === null
			? {
				code: 'VOICE_TTS_REMOTE_UNCONFIGURED',
				effectiveProvider: voiceProviderNamespace(provider, 'tts/unavailable'),
				reason: 'fish_credential_missing',
			}
			: {
				code: 'VOICE_TTS_REMOTE_CONFIGURED',
				effectiveProvider: voiceProviderNamespace(provider, 'fish/s2.1-pro-free'),
				reason: 'fish_credential_configured',
			});
		worker = createServer({
			provider,
			fishProvider: fishApiKey === null ? null : createTtsProvider({ apiKey: fishApiKey }),
			sttProvider,
			profileStore,
			secret: voiceSecret,
			port: voice.port ?? DEFAULT_VOICE_PORT,
			maxConcurrent: voice.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT,
			requestTimeoutMs: localSpeechTimeoutMs,
			onDiagnostic: reportVoiceDiagnostic,
		});
		throwIfVoiceStartupAborted(signal);
		if (worker === null || typeof worker !== 'object' || typeof worker.start !== 'function' || typeof worker.close !== 'function') {
			throw new TypeError('createVoiceServer must return a voice worker');
		}
		await worker.start({ signal });
		throwIfVoiceStartupAborted(signal);
		return localSpeechProvider === null ? worker : voiceWorkerWithOwnedProvider(worker, ownedSpeechProvider);
	} catch (error) {
		await settleVoiceBootstrapCleanup(
			[
				() => worker?.close(),
				() => profileStore === null ? closeLoadedVoiceProfiles(profiles) : profileStore.close(),
				() => localSpeechProvider?.close(),
			],
			dependencies.cleanupTimeoutMs ?? 1_000,
		);
		throw error;
	}
}

async function startOpenAiVoiceWorker(voice, apiKey, dependencies, { signal, reportVoiceDiagnostic, localSpeechTimeoutMs }) {
	let profiles = null;
	let profileStore = null;
	let worker = null;
	try {
		const secret = await resolveVoiceSecret(voice, dependencies.readVoiceSecret ?? readFile, signal);
		throwIfVoiceStartupAborted(signal);
		const loadProfiles = dependencies.loadProfileStore ?? loadPersistentVoiceProfileStore;
		profiles = await loadProfiles(dependencies.profilePath ?? voice.profileAssignmentsPath
			?? path.resolve(PROJECT_DIRECTORY, DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH), { ...(dependencies.voiceProfileIo ?? {}), signal });
		throwIfVoiceStartupAborted(signal);
		profileStore = voiceProfileStoreWithLifecycle(profiles);
		const provider = (dependencies.createOpenAiTtsProvider ?? (options => new OpenAiTtsProvider(options)))({ apiKey, model: voice.openaiTtsModel ?? DEFAULT_OPENAI_TTS_MODEL });
		const sttProvider = (dependencies.createOpenAiSttProvider ?? (options => new OpenAiSttProvider(options)))({ apiKey, model: voice.openaiSttModel ?? DEFAULT_OPENAI_STT_MODEL });
		worker = (dependencies.createVoiceServer ?? createVoiceHttpServer)({
			provider, sttProvider, profileStore, secret, port: voice.port ?? DEFAULT_VOICE_PORT,
			maxConcurrent: voice.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT, requestTimeoutMs: localSpeechTimeoutMs,
			directorUsesPrimaryProvider: true, onDiagnostic: reportVoiceDiagnostic,
		});
		await worker.start({ signal });
		throwIfVoiceStartupAborted(signal);
		emitVoiceDiagnostic(reportVoiceDiagnostic, { code: 'VOICE_OPENAI_CONFIGURED', effectiveProvider: provider.cacheNamespace(), reason: 'openai_credential_configured' });
		return worker;
	} catch (error) {
		await settleVoiceBootstrapCleanup([
			() => worker?.close(), () => profileStore === null ? closeLoadedVoiceProfiles(profiles) : profileStore.close(),
		], dependencies.cleanupTimeoutMs ?? 1_000);
		throw error;
	}
}

function createRemoteFirstSpeechRouting(localProvider, {
	fishApiKey,
	deepgramApiKey,
	platform,
	createTtsProvider,
	createWindowsTtsProvider,
	createSttProvider,
	fallbackCircuit,
}) {
	const owned = new Set([localProvider]);
	let provider = localProvider;
	let sttProvider = localProvider;
	let localTtsFallback = localProvider;
	if (platform === 'win32') {
		const windows = createWindowsTtsProvider({});
		owned.add(windows);
		localTtsFallback = ttsProviderWithFallback(localProvider, windows, fallbackCircuit);
	}
	if (fishApiKey !== null) {
		const fish = createTtsProvider({ apiKey: fishApiKey });
		owned.add(fish);
		provider = ttsProviderWithFallback(fish, localTtsFallback, fallbackCircuit);
	} else {
		provider = localTtsFallback;
	}
	if (deepgramApiKey !== null) {
		const deepgram = createSttProvider({ apiKey: deepgramApiKey });
		owned.add(deepgram);
		sttProvider = sttProviderWithFallback(deepgram, localProvider);
	}
	const localIsPrimary = fishApiKey === null || deepgramApiKey === null;
	let warmupPromise = null;
	return Object.freeze({
		tts: provider,
		stt: sttProvider,
		warmup({ signal } = {}) {
			if (!localIsPrimary || typeof localProvider.warmup !== 'function') return;
			warmupPromise ??= Promise.resolve(localProvider.warmup({ signal })).catch((error) => {
				if (error?.name === 'AbortError' || signal?.aborted) {
					warmupPromise = null;
					throw error;
				}
				return undefined;
			});
			return warmupPromise;
		},
		async close() {
			await Promise.allSettled([...owned].map((candidate) => candidate?.close?.()));
		},
	});
}

function sttProviderWithFallback(primary, fallback) {
	return Object.freeze({
		async transcribe(request) {
			try {
				return await primary.transcribe(request);
			} catch (error) {
				if (!shouldUseLocalSttFallback(error, request?.signal)) throw error;
				return fallback.transcribe(request);
			}
		},
	});
}

function canUseVolatileLocalProfiles({ error, localSpeechProvider, fishApiKey, deepgramApiKey, platform }) {
	if (localSpeechProvider === null || fishApiKey !== null || deepgramApiKey === null || platform === 'win32') return false;
	if (error instanceof SyntaxError) return true;
	return ['EACCES', 'EPERM', 'EISDIR', 'ENOTDIR'].includes(error?.code);
}

function voiceProfileStoreWithLifecycle(profiles) {
	const store = profiles.store;
	const flush = profiles.flush ?? store.flush;
	const close = profiles.close ?? store.close;
	if (flush !== undefined && typeof flush !== 'function') throw new TypeError('profile store flush must be a function');
	if (close !== undefined && typeof close !== 'function') throw new TypeError('profile store close must be a function');
	const flushOperation = flush === undefined
		? null
		: flush.bind(typeof profiles.flush === 'function' ? profiles : store);
	const closeOperation = close === undefined
		? null
		: close.bind(typeof profiles.close === 'function' ? profiles : store);
	let closePromise = null;
	return Object.freeze({
		resolve(agentId) { return store.resolve(agentId); },
		...(typeof store.resolveRequested === 'function' ? { resolveRequested: store.resolveRequested.bind(store) } : {}),
		remove(agentId) { return store.remove?.(agentId) ?? false; },
		flush(options) { return flushOperation === null ? Promise.resolve() : flushOperation(options); },
		close() {
			closePromise ??= Promise.resolve().then(() => closeOperation === null ? flushOperation?.() : closeOperation());
			return closePromise;
		},
	});
}

function closeLoadedVoiceProfiles(profiles) {
	if (profiles === null || typeof profiles !== 'object') return undefined;
	if (typeof profiles.close === 'function') return profiles.close();
	if (typeof profiles.store?.close === 'function') return profiles.store.close();
	if (typeof profiles.flush === 'function') return profiles.flush();
	if (typeof profiles.store?.flush === 'function') return profiles.store.flush();
	return undefined;
}

async function settleVoiceBootstrapCleanup(operations, timeoutMs) {
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('voice cleanup timeout must be positive');
	const cleanup = Promise.allSettled(operations.map((operation) => Promise.resolve().then(operation)));
	let timer;
	await Promise.race([
		cleanup,
		new Promise((resolve) => { timer = setTimeout(resolve, timeoutMs); }),
	]);
	clearTimeout(timer);
}

function throwIfVoiceStartupAborted(signal) {
	if (!signal?.aborted) return;
	if (signal.reason instanceof Error) throw signal.reason;
	const error = new Error('Voice startup was cancelled');
	error.name = 'AbortError';
	error.code = 'VOICE_OPERATION_CANCELLED';
	throw error;
}

function voiceWorkerWithOwnedProvider(worker, provider) {
	let closePromise = null;
	return Object.freeze({
		...worker,
		start: worker.start.bind(worker),
		warmup: typeof provider.warmup === 'function'
			? ({ signal } = {}) => provider.warmup({ signal })
			: undefined,
		close() {
			closePromise ??= Promise.allSettled([worker.close(), provider.close()]).then(() => undefined);
			return closePromise;
		},
	});
}

function defaultLocalSpeechPythonPath(platform) {
	return path.join(
		DEFAULT_LOCAL_SPEECH_PYTHON_DIRECTORY,
		platform === 'win32' ? 'Scripts' : 'bin',
		platform === 'win32' ? 'python.exe' : 'python',
	);
}

function createLocalSpeechFailover(localProvider, {
	fishApiKey,
	deepgramApiKey,
	platform,
	createTtsProvider,
	createWindowsTtsProvider,
	createSttProvider,
	fallbackCircuit,
}) {
	let activeTts = localProvider;
	let activeStt = localProvider;
	let fallbackTts = null;
	let fallbackStt = null;
	let localUnavailable = false;
	let ttsTransitionPromise = null;
	let sttTransitionPromise = null;
	let warmupPromise = null;
	let localClosePromise = null;
	const closeLocal = () => {
		localClosePromise ??= Promise.resolve().then(() => localProvider.close?.()).then(() => undefined, () => undefined);
		return localClosePromise;
	};
	const closeLocalIfUnused = () => {
		if (activeTts === localProvider || activeStt === localProvider) return Promise.resolve();
		localUnavailable = true;
		return closeLocal();
	};
	const fallbackTtsFactory = () => {
		if (fallbackTts !== null) return fallbackTts;
		if (fishApiKey !== null) {
			const fish = createTtsProvider({ apiKey: fishApiKey });
			fallbackTts = platform === 'win32'
				? ttsProviderWithFallback(fish, createWindowsTtsProvider({}), fallbackCircuit)
				: fish;
			return fallbackTts;
		}
		if (platform === 'win32') fallbackTts = createWindowsTtsProvider({});
		return fallbackTts;
	};
	const fallbackSttFactory = () => {
		if (fallbackStt !== null) return fallbackStt;
		fallbackStt = deepgramApiKey === null
			? new NoSttProvider()
			: createSttProvider({ apiKey: deepgramApiKey });
		return fallbackStt;
	};
	const switchTtsToFallback = async (error, signal, allowUnavailable = false) => {
		if (!shouldUseLocalTtsFallback(error, signal)) throw error;
		if (fishApiKey === null && platform !== 'win32' && !allowUnavailable) throw error;
		if (activeTts !== localProvider) return;
		ttsTransitionPromise ??= Promise.resolve().then(async () => {
			throwIfVoiceStartupAborted(signal);
			if (activeTts === localProvider) activeTts = fallbackTtsFactory();
			await closeLocalIfUnused();
		}).catch((transitionError) => {
			ttsTransitionPromise = null;
			throw transitionError;
		});
		await ttsTransitionPromise;
	};
	const switchSttToFallback = async (error, signal, allowUnavailable = false) => {
		if (!shouldUseLocalSttFallback(error, signal)) throw error;
		if (deepgramApiKey === null && !allowUnavailable) throw error;
		if (activeStt !== localProvider) return;
		sttTransitionPromise ??= Promise.resolve().then(async () => {
			throwIfVoiceStartupAborted(signal);
			if (activeStt === localProvider) activeStt = fallbackSttFactory();
			await closeLocalIfUnused();
		}).catch((transitionError) => {
			sttTransitionPromise = null;
			throw transitionError;
		});
		await sttTransitionPromise;
	};
	const switchFailedWarmupChannels = async ({ sttReady, ttsReady }, error, signal) => {
		if (error?.name === 'AbortError' || signal?.aborted) throw error;
		const ttsUsable = ttsReady || fishApiKey !== null || platform === 'win32';
		const sttUsable = sttReady || deepgramApiKey !== null;
		if (!ttsReady && !sttReady) {
			if (!ttsUsable && !sttUsable) throw error;
			await closeLocal();
			throwIfVoiceStartupAborted(signal);
			await Promise.all([
				switchTtsToFallback(error, signal, true),
				switchSttToFallback(error, signal, true),
			]);
			return;
		}
		await Promise.all([
			ttsReady ? undefined : switchTtsToFallback(error, signal, true),
			sttReady ? undefined : switchSttToFallback(error, signal, true),
		]);
	};
	return Object.freeze({
		tts: Object.freeze({
			cacheNamespace() {
				if (activeTts === localProvider) return 'local-chatterbox/chatterbox-v1';
				if (activeTts === null) return 'tts/unavailable';
				return providerCacheNamespace(
					activeTts,
					fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
				);
			},
			async synthesize(request) {
				if (activeTts !== null) {
					const attemptedProvider = activeTts;
					const attemptedNamespace = attemptedProvider === localProvider
						? 'local-chatterbox/chatterbox-v1'
						: providerCacheNamespace(
							attemptedProvider,
							fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
						);
					try {
						return tagSynthesisCacheNamespace(await attemptedProvider.synthesize(request), attemptedNamespace);
					} catch (error) {
						if (attemptedProvider !== localProvider) throw error;
						await switchTtsToFallback(error, request?.signal);
						const fallbackNamespace = providerCacheNamespace(
							activeTts,
							fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
						);
						return tagSynthesisCacheNamespace(await activeTts.synthesize(request), fallbackNamespace);
					}
				}
				const error = new Error('Speech synthesis is not configured');
				error.code = 'TTS_UNAVAILABLE';
				throw error;
			},
		}),
		stt: Object.freeze({
			async transcribe(request) {
				const attemptedProvider = activeStt;
				try {
					return await attemptedProvider.transcribe(request);
				} catch (error) {
					if (attemptedProvider !== localProvider) throw error;
					await switchSttToFallback(error, request?.signal);
					return activeStt.transcribe(request);
				}
			},
		}),
		warmup({ signal } = {}) {
			if (localUnavailable || typeof localProvider.warmup !== 'function') return;
			warmupPromise ??= (async () => {
				let readiness;
				try {
					readiness = await localProvider.warmup({ signal });
				} catch (error) {
					await switchFailedWarmupChannels({ sttReady: false, ttsReady: false }, error, signal);
					return;
				}
				if (readiness === undefined) return;
				if (readiness === null || typeof readiness !== 'object'
						|| typeof readiness.sttReady !== 'boolean' || typeof readiness.ttsReady !== 'boolean') {
					throw new TypeError('local speech warmup must return channel readiness');
				}
				if (readiness.sttReady && readiness.ttsReady) return;
				const retryLocalOnlyChannel = (!readiness.sttReady && deepgramApiKey === null)
						|| (!readiness.ttsReady && fishApiKey === null && platform !== 'win32');
				if (retryLocalOnlyChannel && (readiness.sttReady || readiness.ttsReady)) {
					try {
						const retried = await localProvider.warmup({ signal });
						if (retried === null || typeof retried !== 'object'
								|| typeof retried.sttReady !== 'boolean' || typeof retried.ttsReady !== 'boolean') {
							throw new TypeError('local speech warmup must return channel readiness');
						}
						readiness = retried;
					} catch (error) {
						if (error?.name === 'AbortError' || signal?.aborted) throw error;
					}
					if (readiness.sttReady && readiness.ttsReady) return;
				}
				const error = Object.assign(new Error('Local speech warmup did not initialize every channel'), {
					code: 'LOCAL_SPEECH_WARMUP_FAILED',
				});
				await switchFailedWarmupChannels(readiness, error, signal);
			})().catch((error) => {
				warmupPromise = null;
				throw error;
			});
			return warmupPromise;
		},
		async close() {
			await Promise.allSettled([
				closeLocal(),
				fallbackTts?.close?.(),
				fallbackStt?.close?.(),
			]);
		},
	});
}

function shouldUseLocalTtsFallback(error, signal) {
	if (error?.name === 'AbortError' || signal?.aborted) return false;
	if (error instanceof TypeError || error?.code === 'TTS_INVALID_REQUEST') return false;
	return true;
}

function shouldUseLocalSttFallback(error, signal) {
	if (error?.name === 'AbortError' || signal?.aborted) return false;
	if (error instanceof TypeError || ['STT_INVALID_REQUEST', 'STT_MALFORMED_AUDIO'].includes(error?.code)) return false;
	return true;
}

function voiceFallbackCircuitOptions(dependencies) {
	return {
		now: dependencies.voiceFallbackNow ?? Date.now,
		baseDelayMs: dependencies.voiceFallbackBaseDelayMs ?? DEFAULT_FISH_FALLBACK_BASE_DELAY_MS,
		maxDelayMs: dependencies.voiceFallbackMaxDelayMs ?? DEFAULT_FISH_FALLBACK_MAX_DELAY_MS,
		onDiagnostic: dependencies.reportVoiceDiagnostic ?? (() => {}),
	};
}

function ttsProviderWithFallback(primary, fallback, {
	now = Date.now,
	baseDelayMs = DEFAULT_FISH_FALLBACK_BASE_DELAY_MS,
	maxDelayMs = DEFAULT_FISH_FALLBACK_MAX_DELAY_MS,
	onDiagnostic = () => {},
} = {}) {
	if (typeof now !== 'function') throw new TypeError('Fish fallback clock must be a function');
	if (typeof onDiagnostic !== 'function') throw new TypeError('Fish fallback diagnostic reporter must be a function');
	if (!Number.isSafeInteger(baseDelayMs) || baseDelayMs < 1) throw new TypeError('Fish fallback base delay must be positive');
	if (!Number.isSafeInteger(maxDelayMs) || maxDelayMs < baseDelayMs) throw new TypeError('Fish fallback maximum delay must not be less than its base delay');
	let consecutiveFailures = 0;
	let nextProbeAt = null;
	let probePromise = null;
	const readNow = () => {
		const value = now();
		if (!Number.isSafeInteger(value) || value < 0) throw new TypeError('Fish fallback clock must return a non-negative safe integer');
		return value;
	};
	const primaryProvider = voiceProviderNamespace(primary, 'tts/primary');
	const fallbackProvider = voiceProviderNamespace(fallback, 'tts/fallback');
	const recordFailure = (error) => {
		consecutiveFailures = Math.min(consecutiveFailures + 1, 31);
		const exponent = Math.min(consecutiveFailures - 1, 30);
		const delay = Math.min(maxDelayMs, baseDelayMs * (2 ** exponent));
		nextProbeAt = Math.min(Number.MAX_SAFE_INTEGER, readNow() + delay);
		emitVoiceDiagnostic(onDiagnostic, {
			code: 'VOICE_TTS_FALLBACK_ACTIVATED',
			primaryProvider,
			effectiveProvider: fallbackProvider,
			failureCode: voiceDiagnosticFailureCode(error),
		});
	};
	const synthesizeFallback = async (request) => {
		const output = await fallback.synthesize(request);
		return tagSynthesisCacheNamespace(
			{ ...output, cacheable: false },
			providerCacheNamespace(fallback, 'windows/system-speech'),
		);
	};
	const attemptPrimary = async (request) => {
		const recovering = nextProbeAt !== null;
		try {
			const output = await primary.synthesize(request);
			consecutiveFailures = 0;
			nextProbeAt = null;
			if (recovering) emitVoiceDiagnostic(onDiagnostic, {
				code: 'VOICE_TTS_PRIMARY_RESTORED',
				primaryProvider,
				effectiveProvider: primaryProvider,
			});
			return tagSynthesisCacheNamespace(output, providerCacheNamespace(primary, 'fish/s2.1-pro-free'));
		} catch (error) {
			if (!shouldUseWindowsTtsFallback(error)) throw error;
			recordFailure(error);
			return synthesizeFallback(request);
		}
	};
	return Object.freeze({
		cacheNamespace() {
			return nextProbeAt === null
				? providerCacheNamespace(primary, 'fish/s2.1-pro-free')
				: providerCacheNamespace(fallback, 'windows/system-speech');
		},
		async synthesize(request) {
			if (nextProbeAt === null) return attemptPrimary(request);
			if (probePromise !== null || readNow() < nextProbeAt) return synthesizeFallback(request);
			probePromise = attemptPrimary(request).finally(() => { probePromise = null; });
			return probePromise;
		},
	});
}

function voiceProviderNamespace(provider, fallback) {
	try {
		const namespace = providerCacheNamespace(provider, fallback);
		return /^[a-z0-9][a-z0-9._/-]{0,127}$/.test(namespace) ? namespace : 'tts/unspecified';
	} catch {
		return 'tts/unspecified';
	}
}

function voiceDiagnosticFailureCode(error) {
	const value = error?.code;
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'TTS_PROVIDER_ERROR';
}

function emitVoiceDiagnostic(reporter, event) {
	try { reporter(Object.freeze({ ...event })); }
	catch { /* voice diagnostics are observational */ }
}

function writeVoiceDiagnostic(event) {
	const fields = Object.entries(event)
		.filter(([key]) => key !== 'code')
		.map(([key, value]) => `${key}=${value}`)
		.join(' ');
	process.stderr.write(`[voice] ${event.code}${fields === '' ? '' : ` ${fields}`}\n`);
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
	const localSpeechTimeoutMs = positiveInteger(source.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS, 'voice.localSpeechTimeoutMs');
	if (localSpeechTimeoutMs > 600_000) throw new TypeError('voice.localSpeechTimeoutMs must not exceed 600000');
	const secret = firstNonBlank(source.secret, environment.ARENA_AGENT_VOICE_SECRET);
	const configuredSecretFile = firstNonBlank(source.secretFile, environment.ARENA_AGENT_VOICE_SECRET_FILE);
	return {
		...source,
		secret,
		secretFile: path.resolve(PROJECT_DIRECTORY, configuredSecretFile ?? DEFAULT_VOICE_SECRET_PATH),
		port: configuredPort,
		maxConcurrent,
		profileAssignmentsPath,
		localSpeechTimeoutMs,
		provider: requireSpeechProvider(source.provider ?? 'legacy'),
		openaiApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.openaiApiKeyEnvironmentVariable ?? 'OPENAI_API_KEY', 'voice.openaiApiKeyEnvironmentVariable'),
		openaiTtsModel: source.openaiTtsModel ?? DEFAULT_OPENAI_TTS_MODEL,
		openaiSttModel: source.openaiSttModel ?? DEFAULT_OPENAI_STT_MODEL,
		fishApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE, 'voice.fishApiKeyEnvironmentVariable'),
		deepgramApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE, 'voice.deepgramApiKeyEnvironmentVariable'),
	};
}

function requireSpeechProvider(value) {
	if (!['openai', 'legacy'].includes(value)) throw new TypeError('voice.provider must be openai or legacy');
	return value;
}

async function resolveVoiceSecret(voice, readSecretFile, signal) {
	throwIfVoiceStartupAborted(signal);
	let secret = firstNonBlank(voice.secret);
	if (secret === null) {
		if (typeof readSecretFile !== 'function') throw new TypeError('readVoiceSecret must be a function');
		let onAbort;
		try {
			const read = Promise.resolve().then(() => readSecretFile(voice.secretFile, { encoding: 'utf8', signal }));
			const aborted = new Promise((_, reject) => {
				onAbort = () => { try { throwIfVoiceStartupAborted(signal); } catch (error) { reject(error); } };
				signal?.addEventListener('abort', onAbort, { once: true });
				if (signal?.aborted) onAbort();
			});
			secret = firstNonBlank(await Promise.race([read, aborted]));
		} catch (error) {
			throwIfVoiceStartupAborted(signal);
			throw codedRuntimeError('VOICE_SECRET_UNAVAILABLE', 'Dedicated voice authentication secret is unavailable', error);
		} finally {
			if (onAbort) signal?.removeEventListener('abort', onAbort);
		}
	}
	if (secret !== null) secret = secret.trim();
	if (secret === null || secret.length < 16 || secret.length > 512) {
		throw codedRuntimeError('VOICE_SECRET_INVALID', 'Dedicated voice authentication secret must contain 16 to 512 characters');
	}
	return secret;
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

function verboseRecoveryMessage(recovery) {
	if (recovery.quiet) return 'Provider output was incomplete; retrying from the next fresh observation.';
	if (recovery.blocked) return 'Provider access is unavailable; retrying automatically from fresh state.';
	return 'Provider work failed; recovering automatically from fresh state.';
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

function verboseTransitionIdentity(stage, message, value) {
	const defaults = {
		conversation: { component: 'conversation', boundary: 'message', code: 'MESSAGE_RECEIVED', state: 'ready' },
		decision: { component: 'provider', boundary: 'planning', code: 'PLAN_ACCEPTED', state: 'ready' },
		error: { component: 'coordinator', boundary: 'agent_work', code: 'COORDINATOR_ERROR', state: 'degraded' },
		lifecycle: { component: 'lifecycle', boundary: 'goal', code: 'LIFECYCLE_CHANGED', state: 'ready' },
		retry: { component: 'provider', boundary: 'planning', code: 'PROVIDER_RETRY', state: 'retrying' },
	}[stage] ?? { component: 'coordinator', boundary: stage, code: 'STATUS_CHANGED', state: 'ready' };
	return {
		component: String(value?.component ?? defaults.component).slice(0, 128),
		boundary: String(value?.boundary ?? defaults.boundary).slice(0, 128),
		code: String(value?.code ?? defaults.code).slice(0, 128),
		state: String(value?.state ?? defaults.state).slice(0, 128),
		detail: message,
	};
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
	const position = observation?.position ?? player.position ?? player;
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
	const position = observation?.position ?? player.position ?? player;
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

export function classifyObservationTrigger(payload, observation, signals = null) {
	const explicitTrigger = typeof payload.trigger === 'string' && payload.trigger.trim().length > 0 ? payload.trigger.trim().slice(0, 128) : null;
	const attention = payload.attention === true;
	if (explicitTrigger !== null) return { attention: true, priority: payload.priority === 'urgent' || ['damage', 'lava', 'fire', 'suffocation', 'fall'].includes(explicitTrigger) ? 'urgent' : 'ordinary', trigger: explicitTrigger };
	const changedFacts = Array.isArray(payload.changedFacts) ? payload.changedFacts : [];
	const joinedFacts = changedFacts.filter((value) => typeof value === 'string').join('|').toLowerCase();
	const player = observation?.player ?? {};
	const before = signals?.previousPlayer;
	const healthDecreased = Number.isFinite(player.health) && Number.isFinite(before?.health) && player.health < before.health;
	// The server's health delta already means damage. When both samples are
	// available, also exclude healing from legacy/general-purpose deltas.
	const healthDelta = joinedFacts.includes('health') && (!Number.isFinite(before?.health) || !Number.isFinite(player.health) || healthDecreased);
	if (healthDecreased || healthDelta || joinedFacts.includes('attacker') || joinedFacts.includes('damage')) return { attention: true, priority: 'urgent', trigger: 'damage' };
	if (player.inLava === true || joinedFacts.includes('player.inlava')) return { attention: true, priority: 'urgent', trigger: 'lava' };
	if (joinedFacts.includes('fire') || player.onFire === true || player.fire === true) return { attention: true, priority: 'urgent', trigger: 'fire' };
	if (joinedFacts.includes('suffoc') || joinedFacts.includes('air')) return { attention: true, priority: 'urgent', trigger: 'suffocation' };
	if (joinedFacts.includes('fall')) return { attention: true, priority: 'urgent', trigger: 'fall' };
	// Visible lava is relevant evidence, not proof the player is inside it.
	if (signals?.movementLoop === true) return { attention: true, priority: 'urgent', trigger: 'movement_loop' };
	if (signals?.resourceDiscovery === true) return { attention: true, priority: 'ordinary', trigger: 'resource_discovery' };
	if (!attention) return { attention: false, priority: 'ordinary', trigger: 'observation' };
	return { attention: true, priority: 'ordinary', trigger: 'attention' };
}

/** Detects a repeated two-point walk without flagging ordinary forward travel. */
export function detectMovementLoop(positionKeys) {
	if (!Array.isArray(positionKeys) || positionKeys.length < 4) return false;
	const tail = positionKeys.slice(-6);
	if (tail.length < 4) return false;
	const unique = new Set(tail);
	if (unique.size !== 2) return false;
	for (let index = 2; index < tail.length; index += 1) {
		if (tail[index] !== tail[index - 2]) return false;
	}
	return true;
}

function nativeBlockPositionKey(player) {
	if (![player?.x, player?.y, player?.z].every((value) => typeof value === 'number' && Number.isFinite(value))) return null;
	return `${Math.round(player.x)},${Math.round(player.y)},${Math.round(player.z)}`;
}

function observedResourceCandidates(observation) {
	const candidates = [];
	for (const block of observation?.blocks ?? []) {
		if (!isResourceBlock(block)) continue;
		candidates.push(`block:${block.x},${block.y},${block.z}:${block.blockId}`);
	}
	for (const landmark of observation?.landmarks ?? []) {
		if (!isResourceBlock(landmark)) continue;
		candidates.push(`landmark:${landmark.x},${landmark.y},${landmark.z}:${landmark.blockId}`);
	}
	for (const item of observation?.items ?? []) {
		if (typeof item?.stableId !== 'string' || typeof item?.itemId !== 'string') continue;
		candidates.push(`item:${item.stableId}:${item.itemId}`);
	}
	return candidates;
}

function isResourceBlock(block) {
	const blockId = typeof block?.blockId === 'string' ? block.blockId.toLowerCase() : '';
	const blockTags = Array.isArray(block?.tags) ? block.tags : [];
	return blockTags.some((tag) => typeof tag === 'string' && (
		tag === '#minecraft:logs'
		|| tag === '#minecraft:leaves'
		|| tag === '#minecraft:ores'
		|| tag === '#minecraft:crops'
		|| tag === '#minecraft:flowers'
	)) || RESOURCE_BLOCK_SUFFIXES.some((suffix) => blockId.endsWith(suffix));
}

const RESOURCE_BLOCK_SUFFIXES = Object.freeze([
	'_log', '_wood', '_ore', '_leaves', '_crop', '_crops', '_flower', '_mushroom',
	'crafting_table', 'furnace', 'chest', 'barrel', 'hay_block', 'pumpkin', 'melon',
]);

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

function sameSupervisionKey(left, right) {
	return left?.agentId === right?.agentId
		&& left?.goalRevision === right?.goalRevision
		&& left?.lifecycleGeneration === right?.lifecycleGeneration
		&& left?.sessionEpoch === right?.sessionEpoch
		&& left?.profileFingerprint === right?.profileFingerprint;
}

export function buildNativeEventInput(record, { event, trigger, programId, status, result, planningLeadMs, eventSequence, taskMemory = null, observation = {}, conversation = { mode: 'unread', baseSequence: -1, nextSequence: -1, entries: [] }, conversationOnly = false } = {}) {
	const eventName = typeof event === 'string' && event.length > 0 ? event : event?.event;
	const normalizedEvent = typeof eventName === 'string' && eventName.length > 0 ? eventName : 'observation';
	const eventPlanningLeadMs = status?.planningLeadMs ?? planningLeadMs ?? event?.planningLeadMs;
	const inventorySource = asArray(observation.inventory?.items);
	const itemSource = asArray(observation.items);
	const entitySource = asArray(observation.entities).filter((entity) => entity?.type !== 'minecraft:item');
	const blockSource = asArray(observation.blocks);
	const landmarkSource = Array.isArray(observation.landmarks) ? observation.landmarks : null;
	const nearbyContainerSource = Array.isArray(observation.nearbyContainers) ? observation.nearbyContainers : null;
	const optionSource = Array.isArray(observation.options) ? observation.options : null;
	const inventoryRows = boundedEventArray(inventorySource, 32);
	const itemRows = boundedEventArray(itemSource, 16);
	const entityRows = boundedEventArray(entitySource, 16);
	const blockRows = boundedEventArray(blockSource, 32, isHazardousEventFact);
	const landmarkRows = landmarkSource === null ? null : boundedEventArray(landmarkSource, 32);
	const nearbyContainerRows = nearbyContainerSource === null ? null : boundedEventArray(nearbyContainerSource, 16);
	const optionRows = optionSource === null ? null : boundedEventArray(optionSource, 4);
	const compactObservation = {
		...(observation.observedAtEpochMs === undefined ? {} : { observedAtEpochMs: observation.observedAtEpochMs }),
		...(observation.eventSequence === undefined ? {} : { eventSequence: observation.eventSequence }),
		...(observation.freshness === undefined ? {} : { freshness: observation.freshness }),
		...(observation.coverage === undefined ? {} : { coverage: compactCoverageForEvent(observation.coverage) }),
		...(observation.perception === undefined ? {} : { perception: compactPerceptionForEvent(observation.perception) }),
		...(observation.ready === undefined ? {} : { ready: observation.ready }),
		...(observation.status === undefined ? {} : { status: observation.status }),
		...(observation.velocity === undefined ? {} : { velocity: observation.velocity }),
		player: observation.player ?? {},
		inventory: {
			items: inventoryRows.values,
			...(observation.inventory?.selectedItem === undefined ? {} : { selectedItem: observation.inventory.selectedItem }),
			...(observation.inventory?.tagCounts === undefined ? {} : { tagCounts: observation.inventory.tagCounts }),
		},
		items: itemRows.values,
		entities: entityRows.values,
		blocks: blockRows.values,
		...(landmarkRows === null ? {} : { landmarks: landmarkRows.values }),
		...(nearbyContainerRows === null ? {} : { nearbyContainers: nearbyContainerRows.values }),
		...(observation.world === undefined ? {} : { world: observation.world }),
		...(observation.currentAction === undefined ? {} : { currentAction: observation.currentAction }),
		...(observation.lastResult === undefined ? {} : { lastResult: observation.lastResult }),
		...(observation.interaction === undefined ? {} : { interaction: observation.interaction }),
		...(observation.death === undefined ? {} : { death: observation.death }),
		...(observation.recovery === undefined ? {} : { recovery: observation.recovery }),
		...(optionRows === null ? {} : { options: optionRows.values }),
		...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
		...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
		...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
		resultCoverage: {
			inventory: eventArrayCoverage(inventorySource, inventoryRows.values),
			items: eventArrayCoverage(itemSource, itemRows.values),
			entities: eventArrayCoverage(entitySource, entityRows.values),
			blocks: eventArrayCoverage(blockSource, blockRows.values),
			...(landmarkRows === null ? {} : { landmarks: eventArrayCoverage(landmarkSource, landmarkRows.values) }),
			...(nearbyContainerRows === null ? {} : { nearbyContainers: eventArrayCoverage(nearbyContainerSource, nearbyContainerRows.values) }),
			...(optionRows === null ? {} : { options: eventArrayCoverage(optionSource, optionRows.values) }),
		},
	};
	const conversationEntries = Array.isArray(conversation) ? conversation : (Array.isArray(conversation?.entries) ? conversation.entries : []);
	const unreadEntries = boundedEventArray(conversationEntries, Number.POSITIVE_INFINITY).values;
	const unreadConversation = Array.isArray(conversation)
		? { mode: 'unread', baseSequence: null, nextSequence: conversation.at(-1)?.sequence ?? -1, entries: unreadEntries, omittedEntries: 0 }
		: {
			mode: 'unread',
			baseSequence: conversation?.baseSequence ?? null,
			nextSequence: conversation?.nextSequence ?? -1,
			entries: unreadEntries,
			omittedEntries: 0,
		};
	const isPlanningDue = normalizedEvent === 'program_planning_due';
	const payload = {
		event: normalizedEvent,
		trigger: typeof trigger === 'string' && trigger.length > 0 ? trigger : (isPlanningDue ? normalizedEvent : 'observation'),
		mode: conversationOnly === true ? 'conversation_only' : 'goal',
		goal: record?.currentGoal ?? null,
		goalSpec: record?.currentGoalSpec ?? null,
		goalRevision: record?.goalRevision ?? 0,
		...(taskMemory === null ? {} : { taskMemory }),
		...(eventSequence === undefined ? {} : { eventSequence }),
		observation: isPlanningDue ? compactPlanningDueObservation(compactObservation) : compactObservation,
		conversation: unreadConversation,
		...(programId === undefined ? {} : { program: { programId,
			...(status === undefined ? {} : { state: status.state, engineState: status.engineState, programVersion: status.programVersion, deadlineEpochMs: status.deadlineEpochMs, planningLeadMs: status.planningLeadMs ?? eventPlanningLeadMs, decision: status.decision, pendingSuccessor: status.pendingSuccessor }),
			...(result === undefined ? {} : { result: { state: result.state, reasonCode: result.reasonCode, actions: result.actions, actionsSucceeded: result.actionsSucceeded, actionsFailed: result.actionsFailed, programVersion: result.programVersion, queueId: result.queueId, predecessorProgramId: result.predecessorProgramId, discardedSuccessor: result.discardedSuccessor, receipts: result.receipts?.slice(-8), omittedReceipts: Math.max(0, (result.receipts?.length ?? 0) - 8) + (result.omittedReceipts ?? 0) } }) } }),
	};
	// Authoritative persisted steering has its own schema-derived bound. Reserve
	// its actual encoded bytes once, so even escaped/non-ASCII history cannot
	// consume the optional event/unread budget or force instructions out of it.
	const eventBudgetBytes = 16_384 + Buffer.byteLength(JSON.stringify(payload.goal), 'utf8');
	let json = JSON.stringify(payload);
	if (Buffer.byteLength(json, 'utf8') > eventBudgetBytes) {
		const overflowObservation = compactEventObservationForBudget(compactObservation);
		const overflowConversation = { ...unreadConversation, entries: [...unreadConversation.entries] };
		const overflowPayload = {
			...payload,
			observation: isPlanningDue ? compactPlanningDueObservation(overflowObservation) : overflowObservation,
			conversation: overflowConversation,
		};
		json = JSON.stringify(overflowPayload);
		// Protect the oldest unread instructions. Later messages stay unread and
		// are delivered by a following turn, never acknowledged via a skipped tail.
		while (Buffer.byteLength(json, 'utf8') > eventBudgetBytes && overflowConversation.entries.length > 0) {
			overflowConversation.entries.pop();
			overflowConversation.omittedEntries += 1;
			overflowConversation.nextSequence = overflowConversation.entries.at(-1)?.sequence ?? unreadConversation.baseSequence ?? -1;
			json = JSON.stringify(overflowPayload);
		}
		if (unreadConversation.entries.length > 0 && overflowConversation.entries.length === 0) {
			throw Object.assign(new TypeError(`Native event context leaves no room for unread conversation sequence ${unreadConversation.entries[0].sequence}; messages remain unread. Reduce the event or task context before retrying.`), { code: 'NATIVE_CONVERSATION_BUDGET_EXCEEDED' });
		}
	}
	const instruction = isPlanningDue
		? 'Live Minecraft event. Program planning is due soon: prepare the next intention while the current authorised routine keeps running. This is advisory and does not require a pending decisionId; use the current programVersion and timing context, and do not blindly renew or cancel the current program.'
		: 'Live Minecraft event. Advance the current goal using fresh facts. Keep authorised routines running while you reason; respond explicitly to pending program decisions.';
	return `${instruction}\n${json}`;
}

function boundedEventArray(value, limit, preserve = null) {
	const source = asArray(value);
	const retained = source.slice(0, limit);
	if (retained.length >= source.length || typeof preserve !== 'function') return { values: retained, omitted: source.length - retained.length };
	let replacement = retained.length - 1;
	for (let index = retained.length; index < source.length && replacement >= 0; index++) {
		if (!preserve(source[index])) continue;
		while (replacement >= 0 && preserve(retained[replacement])) replacement -= 1;
		if (replacement < 0) break;
		retained[replacement] = source[index];
		replacement -= 1;
	}
	return { values: retained, omitted: source.length - retained.length };
}

function eventArrayCoverage(source, retained) {
	return { retained: retained.length, availableInSnapshot: source.length, omitted: Math.max(0, source.length - retained.length) };
}

function compactEventObservationForBudget(observation) {
	const inventoryRows = boundedEventArray(compactEventRows(observation.inventory?.items), 16, isHazardousEventFact);
	const itemRows = boundedEventArray(compactEventRows(observation.items), 8, isHazardousEventFact);
	const entityRows = boundedEventArray(compactEventRows(observation.entities), 8, isHazardousEventFact);
	const blockRows = boundedEventArray(compactEventRows(observation.blocks), 12, isHazardousEventFact);
	const landmarkRows = Array.isArray(observation.landmarks)
		? boundedEventArray(compactEventRows(observation.landmarks), 12, isHazardousEventFact)
		: null;
	const nearbyContainerRows = Array.isArray(observation.nearbyContainers)
		? boundedEventArray(compactEventRows(observation.nearbyContainers), 8, isHazardousEventFact)
		: null;
	const optionRows = Array.isArray(observation.options)
		? boundedEventArray(compactEventRows(observation.options), 2)
		: null;
	const resultCoverage = retainedEventCoverage(observation.resultCoverage, {
		inventory: inventoryRows.values.length,
		items: itemRows.values.length,
		entities: entityRows.values.length,
		blocks: blockRows.values.length,
		...(landmarkRows === null ? {} : { landmarks: landmarkRows.values.length }),
		...(nearbyContainerRows === null ? {} : { nearbyContainers: nearbyContainerRows.values.length }),
		...(optionRows === null ? {} : { options: optionRows.values.length }),
	});
	return {
		...observation,
		inventory: { ...observation.inventory, items: inventoryRows.values },
		items: itemRows.values,
		entities: entityRows.values,
		blocks: blockRows.values,
		...(landmarkRows === null ? {} : { landmarks: landmarkRows.values }),
		...(nearbyContainerRows === null ? {} : { nearbyContainers: nearbyContainerRows.values }),
		...(optionRows === null ? {} : { options: optionRows.values }),
		...(observation.world === undefined ? {} : { world: compactWorldForEvent(observation.world) }),
		...(observation.lastResult === undefined ? {} : { lastResult: compactLastResultForEvent(observation.lastResult) }),
		...(observation.recovery === undefined ? {} : { recovery: compactRecoveryForEvent(observation.recovery) }),
		...(observation.coverage === undefined ? {} : { coverage: compactCoverageForEvent(observation.coverage) }),
		...(observation.perception === undefined ? {} : { perception: compactPerceptionForEvent(observation.perception) }),
		resultCoverage,
	};
}

function compactPlanningDueObservation(observation) {
	const inventoryRows = boundedEventArray(compactEventRows(observation.inventory?.items), 8, isHazardousEventFact);
	const itemRows = boundedEventArray(compactEventRows(observation.items), 4, isHazardousEventFact);
	const entityRows = boundedEventArray(compactEventRows(observation.entities), 4, isHazardousEventFact);
	const blockRows = boundedEventArray(compactEventRows(observation.blocks), 8, isHazardousEventFact);
	const resultCoverage = retainedEventCoverage(observation.resultCoverage, {
		inventory: inventoryRows.values.length,
		items: itemRows.values.length,
		entities: entityRows.values.length,
		blocks: blockRows.values.length,
		landmarks: 0,
		nearbyContainers: 0,
		options: 0,
	});
	const metadata = Object.fromEntries(['observedAtEpochMs', 'eventSequence', 'freshness', 'coverage', 'ready', 'status', 'velocity', 'perception']
		.filter((field) => observation[field] !== undefined)
		.map((field) => [field, observation[field]]));
	return {
		...metadata,
		player: observation.player ?? {},
		inventory: { ...observation.inventory, items: inventoryRows.values },
		items: itemRows.values,
		entities: entityRows.values,
		blocks: blockRows.values,
		...(observation.world === undefined ? {} : { world: compactWorldForEvent(observation.world) }),
		...(observation.currentAction === undefined ? {} : { currentAction: observation.currentAction }),
		...(observation.lastResult === undefined ? {} : { lastResult: compactLastResultForEvent(observation.lastResult) }),
		...(observation.interaction === undefined ? {} : { interaction: observation.interaction }),
		...(observation.death === undefined ? {} : { death: observation.death }),
		...(observation.recovery === undefined ? {} : { recovery: compactRecoveryForEvent(observation.recovery) }),
		...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
		...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
		...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
		resultCoverage,
	};
}

function retainedEventCoverage(coverage, retainedCounts) {
	if (coverage === null || typeof coverage !== 'object' || Array.isArray(coverage)) return coverage;
	return Object.fromEntries(Object.entries(coverage).map(([section, detail]) => {
		if (detail === null || typeof detail !== 'object' || retainedCounts[section] === undefined) return [section, detail];
		const available = Number.isSafeInteger(detail.availableInSnapshot) ? detail.availableInSnapshot : detail.retained;
		const retained = retainedCounts[section];
		return [section, { ...detail, retained, omitted: Math.max(0, available - retained) }];
	}));
}

const COMPACT_EVENT_ROW_FIELDS = ['uuid', 'stableId', 'type', 'name', 'position', 'x', 'y', 'z', 'distance', 'blockId', 'itemId', 'count', 'slot', 'tags', 'velocity', 'bounds', 'pickable', 'parentId', 'partName', 'state', 'bearing', 'elevation', 'id', 'feasible', 'moveTo', 'reason', 'cause', 'hazard', 'damage', 'maxDamage', 'fingerprint', 'hotbar', 'displayName', 'maxStackSize', 'hostile', 'alive', 'health', 'maxHealth', 'withinInteractionRange', 'capabilities'];

function compactEventRows(value) {
	return asArray(value).map((entry) => {
		if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) return entry;
		const compact = Object.fromEntries(COMPACT_EVENT_ROW_FIELDS.filter((field) => entry[field] !== undefined).map((field) => [field, entry[field]]));
		if (Object.keys(compact).length === 0) return entry;
		const omittedFields = [...new Set([...(entry.omittedFields ?? []), ...Object.keys(entry).filter((field) => field !== 'omittedFields' && !COMPACT_EVENT_ROW_FIELDS.includes(field))])];
		return omittedFields.length === 0 ? compact : { ...compact, omittedFields };
	});
}

function isHazardousEventFact(value) {
	if (value === null || typeof value !== 'object') return false;
	if (value.hostile === true && value.alive !== false) return true;
	const text = ['blockId', 'itemId', 'type', 'name', 'reason', 'cause', 'hazard'].map((field) => value[field]).filter((field) => typeof field === 'string').join(' ');
	return /lava|fire|magma|cactus|campfire|tnt|creeper|ghast|blaze|wither|warden|dragon|hostile/i.test(text);
}

function compactRecoveryForEvent(recovery) {
	if (recovery === null || typeof recovery !== 'object') return recovery;
	return {
		...(recovery.lastDeath === undefined ? {} : { lastDeath: recovery.lastDeath }),
		...(Array.isArray(recovery.lastLostInventory) ? { lastLostInventory: recovery.lastLostInventory.slice(0, 16) } : {}),
		...(Array.isArray(recovery.alreadyHave) ? { alreadyHave: recovery.alreadyHave.slice(-32) } : {}),
		...(Array.isArray(recovery.doNotRedo) ? { doNotRedo: recovery.doNotRedo.slice(0, 24) } : {}),
		...(typeof recovery.facts === 'string' ? { facts: recovery.facts } : {}),
	};
}

function compactLastResultForEvent(lastResult) {
	if (lastResult === null || typeof lastResult !== 'object') return lastResult;
	return {
		...(lastResult.state === undefined ? {} : { state: lastResult.state }),
		...(lastResult.reasonCode === undefined ? {} : { reasonCode: lastResult.reasonCode }),
	};
}

function compactWorldForEvent(world) {
	if (world === null || typeof world !== 'object') return world;
	return {
		...(world.dimension === undefined ? {} : { dimension: world.dimension }),
		...(world.dimensionId === undefined ? {} : { dimensionId: world.dimensionId }),
	};
}

function compactCoverageForEvent(coverage) {
	if (coverage === null || typeof coverage !== 'object' || Array.isArray(coverage)) return coverage;
	return {
		...coverage,
		...(coverage.sections === null || typeof coverage.sections !== 'object' || Array.isArray(coverage.sections)
			? {}
			: { sections: Object.fromEntries(Object.entries(coverage.sections).map(([section, detail]) => [section,
				detail === null || typeof detail !== 'object' || Array.isArray(detail) ? detail : Object.fromEntries(
					['returned', 'total', 'omittedByWire', 'complete', 'hasMore', 'nextOffset'].filter((field) => detail[field] !== undefined).map((field) => [field, detail[field]]),
				)])) }),
	};
}

function compactPerceptionForEvent(perception) {
	if (perception === null || typeof perception !== 'object' || Array.isArray(perception)) return perception;
	return {
		...perception,
		...(Array.isArray(perception.events) ? { events: perception.events.slice(-8), omittedEvents: Math.max(0, perception.events.length - 8) } : {}),
		...(Array.isArray(perception.bossBars) ? { bossBars: perception.bossBars.slice(-16), omittedBossBars: Math.max(0, perception.bossBars.length - 16) } : {}),
	};
}

function asArray(value) {
	return Array.isArray(value) ? value : [];
}

export function nativeObservationSignature(observation) {
	return createHash('sha256').update(JSON.stringify(sortFactualValue(nativeActionableObservationProjection(observation)))).digest('hex');
}

function nativeActionableObservationProjection(observation) {
	const projection = { ...observation };
	// Receipt/sample identity remains in raw facts, but is not a changed world fact.
	delete projection.observedAtEpochMs;
	delete projection.eventSequence;
	if (observation.freshness !== undefined) {
		const { observedAtEpochMs, eventSequence, afterEventSequence, ...freshness } = observation.freshness;
		projection.freshness = freshness;
	}
	delete projection.recovery;
	delete projection.options;
	delete projection.failureClass;
	delete projection.continuity;
	delete projection.lastLiveInventory;
	if (observation.player !== undefined) {
		projection.player = { ...observation.player };
		if (observation.player.effects !== undefined) {
			projection.player.effects = observation.player.effects.map(({ duration, ...effect }) => effect);
		}
	}
	if (Array.isArray(observation.items)) projection.items = observation.items.map(({ distance, ...item }) => item);
	if (Array.isArray(observation.entities)) projection.entities = observation.entities.map(({ distance, ...entity }) => entity);
	if (Array.isArray(observation.nearbyContainers)) {
		projection.nearbyContainers = observation.nearbyContainers.map(({ distance, ...container }) => container);
	}
	if (observation.landmarks !== undefined) {
		projection.landmarks = observation.landmarks.map(({ distance, bearing, elevation, ...landmark }) => landmark);
	}
	if (observation.world !== undefined) {
		const { gameTime, dayTime, ...world } = observation.world;
		projection.world = world;
	}
	if (observation.interaction !== undefined) {
		const { useRemainingTicks, attackCooldown, ...interaction } = observation.interaction;
		projection.interaction = { ...interaction, attackReady: attackCooldown >= 1 };
	}
	return projection;
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
