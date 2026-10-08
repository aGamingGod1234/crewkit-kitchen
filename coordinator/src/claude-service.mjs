import { spawn as nodeSpawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { chmod, mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { terminateChildProcess } from './child-process-lifecycle.mjs';
import { ClaudeToolServer } from './claude-tool-server.mjs';
import { goalSpecInstructions, nativeInstructions, nativeRecoveryInstructions, presentNativeToolResult, recoveryInstructions } from './codex-service.mjs';
import { parseDecision } from './decision-parser.mjs';
import { ModelObservationViews, encodeNativeEventInput } from './model-fact-encoding.mjs';
import { NATIVE_AGENT_INSTRUCTIONS, normalizeMinecraftToolCall, toolResultContent } from './native-minecraft-tools.mjs';
import { PLANNER_OUTPUT_SCHEMA, PLANNER_SYSTEM_PROMPT } from './prompts.mjs';
import { DIRECTLY_SPAWNABLE_WINDOWS_EXTENSIONS, createProviderChildEnvironment, environmentValue, findExecutableOnPath, findNpmEntrypointBesideShim } from './provider-environment.mjs';
import { createExecutionSettings } from './provider-identity.mjs';
import { createSessionMetadata, profileFingerprint } from './provider-session.mjs';
import { recordProviderTurn } from './provider-turn-recorder.mjs';
import { reportVisibleOutput } from './verbose-output.mjs';

export const CLAUDE_MODELS = Object.freeze({
	'claude-opus-5-5': 'Claude Opus 5.5',
	'claude-sonnet-5-5': 'Claude Sonnet 5.5',
	'claude-fable-5-1': 'Claude Fable 5.1',
});
// Claude agents are offered only Low through High; xhigh and max are deliberately excluded.
export const CLAUDE_REASONING_EFFORTS = Object.freeze(['low', 'medium', 'high']);

const CONTROL_PROTOCOLS = new Set(['arena_script', 'native_tools', 'goal_spec']);
const MCP_SERVER_NAME = 'minecraft';
const DEFAULT_PLANNING_TIMEOUT_MS = 120_000;
const DEFAULT_STARTUP_TIMEOUT_MS = 30_000;
const DEFAULT_INTERRUPT_TIMEOUT_MS = 5_000;
const DEFAULT_MAX_DECISION_BYTES = 256 * 1_024;
const DEFAULT_STDOUT_LINE_LIMIT_BYTES = 4 * 1_024 * 1_024;
const DEFAULT_STDERR_LIMIT_BYTES = 64 * 1_024;
const MAX_PUBLIC_AGENT_MESSAGE_CHARS = 1_280;
// Claude Code would otherwise apply its own MCP timeout to long-running tools such as runProgram.
const MCP_TOOL_TIMEOUT_MS = '600000';
// Every model call re-reads the whole conversation. Near this threshold, warm a fresh session and hand the
// active turn over at its next tool boundary; the same prompt and tools preserve prefix reuse.
const DEFAULT_CONTEXT_ROTATION_TOKENS = 80_000;
// Completed turns keep the existing hysteresis; a long turn can hand off after one earlier turn on the session.
const MIN_TURNS_BETWEEN_ROTATIONS = 3;
const STANDBY_WARMUP_LEAD_TOKENS = 10_000;
const CARRY_OVER_TOOL_CALLS = 10;
const MAX_TRACED_TOOLS = 8;
const CARRY_OVER_CONVERSATION = 6;
// Streamed token events renew liveness at most this often.
const STREAM_PROGRESS_INTERVAL_MS = 1_000;
// Claude Code builds without --include-partial-messages; recorded when one rejects the flag.
const PARTIAL_MESSAGES_UNSUPPORTED = new Set();

/**
 * Tool names, instructions, and the dedicated Minecraft workspace are the same ones
 * Codex receives. Claude Code namespaces MCP tools, so the bare names used by the
 * shared instructions are mapped once here.
 */
const CLAUDE_TOOL_NOTE = `Claude Code exposes the Minecraft tools as ${'`'}mcp__${MCP_SERVER_NAME}__<name>${'`'}; every tool name in these instructions refers to that tool (for example observe is mcp__${MCP_SERVER_NAME}__observe). A tool result may end with newer coordinator events for the same body; treat them exactly like the turn input.`;

export class ClaudeProviderError extends Error {
	constructor(code, message, options = undefined) {
		super(message, options);
		this.name = 'ClaudeProviderError';
		this.code = code;
	}
}

export class ClaudeProviderService {
	#config;
	#dependencies;
	#minecraftWorkspace;
	#workspaceManager;
	#toolServer;
	#ownsToolServer;
	#agents = new Map();
	#creating = new Map();
	#replacing = new Map();
	#sessionGenerations = new Map();
	#lifecycleGeneration = 0;

	constructor(config, dependencies = {}) {
		this.#config = validateServiceConfig(config);
		this.#dependencies = {
			spawn: dependencies.spawn ?? nodeSpawn,
			terminate: dependencies.terminate ?? terminateChildProcess,
			fs: { mkdir, writeFile, rm, chmod, ...(dependencies.fs ?? {}) },
			schedule: dependencies.schedule ?? setTimeout,
			cancelSchedule: dependencies.cancelSchedule ?? clearTimeout,
		};
		this.#minecraftWorkspace = dependencies.minecraftWorkspace ?? null;
		if (this.#minecraftWorkspace !== null && typeof this.#minecraftWorkspace.prepare !== 'function') {
			throw new TypeError('minecraftWorkspace must expose prepare()');
		}
		this.#workspaceManager = dependencies.workspaceManager ?? null;
		if (this.#workspaceManager !== null && typeof this.#workspaceManager.prepare !== 'function') {
			throw new TypeError('workspaceManager must expose prepare(provider, agentId)');
		}
		this.#ownsToolServer = dependencies.toolServer === undefined;
		this.#toolServer = dependencies.toolServer ?? new ClaudeToolServer();
		this.catalog = new ClaudeCatalog(this.#config);
	}

	async start() {}
	get agentIds() { return [...this.#agents.keys()]; }
	getAgent(agentId) { return this.#agents.get(agentId) ?? null; }

	async createAgent(profileValue, { recoverySummary = null, controlProtocol = 'native_tools' } = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		const profile = validateProfile(profileValue, this.#config);
		const protocol = validateControlProtocol(controlProtocol);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameSession(replacing, profile, protocol);
			return replacing.promise;
		}
		const existing = this.#agents.get(profile.agentId);
		if (existing !== undefined) {
			if (!existing.matchesProfile(profile) || !existing.matchesControlProtocol(protocol)) throw profileConflict(profile.agentId);
			return existing;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) {
			assertSameSession(creating, profile, protocol);
			return creating.promise;
		}
		const promise = this.#createAgentOnce(profile, recoverySummary, protocol, lifecycleGeneration);
		this.#creating.set(profile.agentId, { profile, controlProtocol: protocol, promise });
		try { return await promise; } finally { this.#creating.delete(profile.agentId); }
	}

	async replaceAgent(profileValue, { recoverySummary = null, controlProtocol = 'native_tools', expectedSessionGeneration = null } = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		const profile = validateProfile(profileValue, this.#config);
		const protocol = validateControlProtocol(controlProtocol);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameSession(replacing, profile, protocol);
			return replacing.promise;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) assertSameSession(creating, profile, protocol);
		const existing = this.#agents.get(profile.agentId);
		if (existing !== undefined) {
			if (!existing.matchesProfile(profile) || !existing.matchesControlProtocol(protocol)) throw profileConflict(profile.agentId);
			if (expectedSessionGeneration !== null && existing.sessionGeneration !== expectedSessionGeneration) {
				throw new ClaudeProviderError('STALE_SESSION_GENERATION', 'Claude replacement target is no longer current');
			}
		}
		const promise = (async () => {
			if (creating !== undefined) await creating.promise.catch(() => {});
			const owned = this.#agents.get(profile.agentId);
			if (owned !== undefined) {
				this.#agents.delete(profile.agentId);
				await owned.dispose();
			}
			return this.#createAgentOnce(profile, recoverySummary, protocol, lifecycleGeneration);
		})();
		const entry = { profile, controlProtocol: protocol, promise };
		this.#replacing.set(profile.agentId, entry);
		try { return await promise; } finally {
			if (this.#replacing.get(profile.agentId) === entry) this.#replacing.delete(profile.agentId);
		}
	}

	async #createAgentOnce(profile, recoverySummary, controlProtocol, lifecycleGeneration) {
		this.catalog.assertSupported(profile.model, profile.reasoningEffort, profile.serviceTier);
		const summary = normalizeRecoverySummary(recoverySummary);
		let cwd;
		let systemPrompt;
		try {
			if (this.#minecraftWorkspace !== null) {
				// Same dedicated workspace, AGENTS.md, and minecraft-control skill that Codex receives.
				const prepared = await this.#minecraftWorkspace.prepare();
				if (prepared === null || typeof prepared !== 'object' || typeof prepared.cwd !== 'string') {
					throw new TypeError('minecraftWorkspace.prepare() must return cwd');
				}
				cwd = prepared.cwd;
				systemPrompt = systemPromptFor(controlProtocol, summary, prepared.instructions?.trim() ?? '', prepared.skillInstructions?.trim() ?? '');
			} else {
				cwd = this.#workspaceManager === null
					? this.#config.cwd
					: await this.#workspaceManager.prepare(profile.provider, profile.agentId);
				systemPrompt = systemPromptFor(controlProtocol, summary, '', '');
			}
		} catch (error) {
			throw new ClaudeProviderError('PROVIDER_UNAVAILABLE', `Could not prepare the Claude agent workspace: ${error?.message ?? String(error)}`, { cause: error });
		}
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration);
		const sessionGeneration = (this.#sessionGenerations.get(profile.agentId) ?? 0) + 1;
		this.#sessionGenerations.set(profile.agentId, sessionGeneration);
		const agentRoot = path.join(this.#config.runtimeRoot, agentDirectoryName(profile.agentId));
		const systemPromptFile = path.join(agentRoot, `system-prompt-${sessionGeneration}.md`);
		await this.#dependencies.fs.mkdir(agentRoot, { recursive: true });
		await this.#dependencies.fs.writeFile(systemPromptFile, systemPrompt, 'utf8');
		await this.#dependencies.fs.chmod(systemPromptFile, 0o600).catch(() => {});
		const agent = new ClaudeAgent(profile, {
			...this.#dependencies,
			config: this.#config,
			cwd,
			controlProtocol,
			systemPromptFile,
			toolServer: this.#toolServer,
			sessionGeneration,
			resetReason: sessionGeneration > 1 ? 'session_replaced' : null,
			onInvalidated: (invalidated) => { if (this.#agents.get(invalidated.agentId) === invalidated) this.#agents.delete(invalidated.agentId); },
		});
		if (lifecycleGeneration !== this.#lifecycleGeneration) {
			await agent.dispose();
			throw new ClaudeProviderError('PROVIDER_STOPPED', 'Claude service lifecycle was stopped');
		}
		this.#agents.set(profile.agentId, agent);
		return agent;
	}

	async prewarmAgent(profileValue, { goalRevision = 0 } = {}) {
		const agent = await this.createAgent(profileValue, { controlProtocol: 'native_tools' });
		await agent.setGoalRevision(goalRevision);
		await agent.prewarm({ goalRevision });
		return agent;
	}

	async removeAgent(agentId) {
		const creating = this.#creating.get(agentId);
		if (creating !== undefined) await creating.promise.catch(() => {});
		const replacing = this.#replacing.get(agentId);
		if (replacing !== undefined) await replacing.promise.catch(() => {});
		const agent = this.#agents.get(agentId);
		if (agent === undefined) return false;
		this.#agents.delete(agentId);
		await agent.dispose();
		return true;
	}

	async reconcile(records, { signal } = {}) {
		if (!Array.isArray(records)) throw new TypeError('claude reconciliation records must be an array');
		assertReconciliationActive(signal);
		const desiredIds = new Set(records.map((record) => record.agentId));
		const removed = [];
		for (const agentId of this.#agents.keys()) {
			assertReconciliationActive(signal);
			if (!desiredIds.has(agentId)) {
				await this.removeAgent(agentId);
				assertReconciliationActive(signal);
				removed.push(agentId);
			}
		}
		const valid = [];
		const invalid = [];
		for (const record of records) {
			try {
				const profile = validateProfile(record, this.#config);
				this.catalog.assertSupported(profile.model, profile.reasoningEffort, profile.serviceTier);
				valid.push(profile);
			} catch (error) { invalid.push({ profile: record, code: error.code ?? 'INVALID_PROFILE', message: error.message }); }
		}
		return { valid, invalid, removed, catalog: await this.catalog.refresh() };
	}

	async stop() {
		this.#lifecycleGeneration += 1;
		await Promise.allSettled([
			...[...this.#creating.values()].map((entry) => entry.promise),
			...[...this.#replacing.values()].map((entry) => entry.promise),
		]);
		this.#creating.clear();
		this.#replacing.clear();
		const agents = [...this.#agents.values()];
		this.#agents.clear();
		await Promise.allSettled(agents.map((agent) => agent.dispose()));
		if (this.#ownsToolServer) await this.#toolServer.stop();
	}
}

class ClaudeCatalog {
	#config;
	stale = false;

	constructor(config) { this.#config = config; }

	async refresh() { return this.snapshot(); }

	snapshot() {
		return {
			provider: 'claude',
			refreshedAtEpochMs: Date.now(),
			models: this.#config.models.map((model) => ({
				id: model,
				model,
				displayName: CLAUDE_MODELS[model] ?? readableModel(model),
				reasoningEfforts: [...this.#config.reasoningEfforts],
				serviceTiers: ['priority'],
			})),
		};
	}

	assertSupported(model, reasoningEffort, serviceTier = 'priority') {
		if (!this.#config.models.includes(model)) throw new ClaudeProviderError('UNSUPPORTED_MODEL', `claude model '${model}' is not configured`);
		if (!this.#config.reasoningEfforts.includes(reasoningEffort)) throw new ClaudeProviderError('UNSUPPORTED_THINKING', `claude model '${model}' does not support effort '${reasoningEffort}'`);
		if (serviceTier !== 'priority') throw new ClaudeProviderError('UNSUPPORTED_SERVICE_TIER', `claude service tier '${serviceTier}' is not supported`);
	}
}

/**
 * One Claude Code process per native agent keeps the conversation warm across
 * turns, mirroring a Codex app-server thread. Turns are user messages on the
 * stream-json input, and each `result` line closes the current turn.
 */
class ClaudeAgent {
	#profile;
	#config;
	#cwd;
	#controlProtocol;
	#systemPromptFile;
	#toolServer;
	#spawn;
	#terminate;
	#fs;
	#schedule;
	#cancelSchedule;
	#onInvalidated;
	#sessionGeneration;
	#resetReason;
	#executionSettings;
	#goalRevision = 0;
	#sessionState = 'cold';
	#process = null;
	#processStart = null;
	#standbyProcess = null;
	#standbyStart = null;
	#route = null;
	#sessionId = null;
	#active = null;
	#awaitingResult = 0;
	#idleWaiters = [];
	#toolsListed = null;
	#oneShot = null;
	#disposed = false;
	#invalidationError = null;
	#turnSequence = 0;
	#observationViews = new ModelObservationViews();
	// Token accounting across this agent's Claude Code sessions (rotation keeps one running total).
	#usage = { calls: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, costUsd: 0 };
	#calls = new Map();
	#currentCallId = null;
	#requestAt = null;
	// Stream marks of the model call in flight; a call keeps the object it started with.
	#stream = newStreamMarks();
	#lastContextTokens = 0;
	#rotationDue = false;
	#midTurnRotationUsed = false;
	#rotations = 0;
	#turnsSinceRotation = 0;
	#pendingCarryOver = null;
	#processUsed = false;
	#processCostUsd = 0;
	#lastStreamProgressAt = 0;
	#recentTools = [];
	#recentConversation = [];
	#lastProgram = null;
	#lastAgentText = null;

	constructor(profile, { config, cwd, controlProtocol, systemPromptFile, toolServer, spawn, terminate, fs, schedule, cancelSchedule, sessionGeneration, resetReason, onInvalidated }) {
		this.#profile = structuredClone(profile);
		this.#config = config;
		this.#cwd = cwd;
		this.#controlProtocol = controlProtocol;
		this.#systemPromptFile = systemPromptFile;
		this.#toolServer = toolServer;
		this.#spawn = spawn;
		this.#terminate = terminate;
		this.#fs = fs;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#sessionGeneration = sessionGeneration;
		this.#resetReason = resetReason;
		this.#onInvalidated = onInvalidated;
		this.#executionSettings = createExecutionSettings(profile, {
			transport: 'claude_code_cli', controlProtocol, modelSelector: profile.model,
			evidence: { model: 'launch_argument', reasoningEffort: 'launch_argument', serviceTier: 'unreported' },
		});
	}

	get agentId() { return this.#profile.agentId; }
	get provider() { return 'claude'; }
	get model() { return this.#profile.model; }
	get reasoningEffort() { return this.#profile.reasoningEffort; }
	get serviceTier() { return this.#profile.serviceTier; }
	get goalRevision() { return this.#goalRevision; }
	get planning() { return this.#active !== null || this.#oneShot !== null; }
	get sessionGeneration() { return this.#sessionGeneration; }
	get profileFingerprint() { return profileFingerprint(this.#profile); }
	get executionSettings() { return structuredClone(this.#executionSettings); }
	sessionMetadata() {
		return createSessionMetadata(this.#profile, { sessionGeneration: this.#sessionGeneration, sessionState: this.#sessionState, continuation: 'durable', durability: 'provider', resetReason: this.#resetReason });
	}
	matchesProfile(profile) { return profilesMatch(this.#profile, profile); }
	matchesControlProtocol(value) { return this.#controlProtocol === value; }

	async setGoalRevision(revision) {
		if (!Number.isSafeInteger(revision) || revision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
		if (revision < this.#goalRevision) throw new ClaudeProviderError('STALE_GOAL_REVISION', `Goal revision ${revision} is older than ${this.#goalRevision}`);
		if (revision === this.#goalRevision) return;
		this.#goalRevision = revision;
		if ((this.#active !== null && this.#active.goalRevision !== revision) || this.#oneShot !== null) await this.interrupt();
	}

	/** Starts Claude Code and waits until it has discovered the Minecraft tools, without spending a model turn. */
	async prewarm({ goalRevision = this.#goalRevision } = {}) {
		this.#assertNative('prewarm');
		this.#assertUsable();
		if (goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_GOAL_REVISION', `Goal revision ${goalRevision} does not match ${this.#goalRevision}`);
		await this.#ensureProcess();
		await withDeadline(this.#toolsListed.promise, this.#config.startupTimeoutMs, this.#schedule, this.#cancelSchedule,
			() => new ClaudeProviderError('PROVIDER_START_TIMEOUT', `Claude Code did not load the Minecraft tools within ${this.#config.startupTimeoutMs} ms`));
		return this;
	}

	async act(input, { goalRevision, signal, executeTool, onVerbose = null, onProgress = null } = {}) {
		this.#assertNative('act');
		this.#assertUsable();
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('native event input must be nonblank');
		if (typeof executeTool !== 'function') throw new TypeError('executeTool must be a function');
		if (onProgress !== null && typeof onProgress !== 'function') throw new TypeError('onProgress must be a function or null');
		if (goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_GOAL_REVISION', `Goal revision ${String(goalRevision)} does not match ${this.#goalRevision}`);
		if (signal?.aborted) throw signal.reason ?? new ClaudeProviderError('TURN_INTERRUPTED', 'Native tool turn was interrupted');
		if (this.#active !== null) throw new ClaudeProviderError('TURN_IN_PROGRESS', `Claude agent '${this.agentId}' already has an active turn`);
		await this.#ensureProcess();
		// An interrupted turn still owes Claude Code's closing result line; never let it close this turn.
		await this.#waitForIdle();
		this.#assertUsable();
		if (goalRevision !== this.#goalRevision || signal?.aborted) throw new ClaudeProviderError('STALE_PLAN', 'Claude native turn belongs to an obsolete goal revision');
		if (this.#active !== null) throw new ClaudeProviderError('TURN_IN_PROGRESS', `Claude agent '${this.agentId}' already has an active turn`);

		let resolveTurn;
		let rejectTurn;
		const turnPromise = new Promise((resolve, reject) => { resolveTurn = resolve; rejectTurn = reject; });
		const silence = createSilenceDeadline(this.#config.planningTimeoutMs, this.#schedule, this.#cancelSchedule);
		const active = {
			turnId: `${this.#sessionGeneration}:${++this.#turnSequence}`,
			input,
			goalRevision,
			executeTool,
			onVerbose,
			onProgress,
			silence,
			toolCalls: 0,
			usage: { calls: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, costUsd: null },
			publishedMessage: false,
			pendingSteers: null,
			tail: Promise.resolve(),
			settled: false,
			resolve: (value) => { if (!active.settled) { active.settled = true; failPendingSteers(active); resolveTurn(value); } },
			reject: (error) => { if (!active.settled) { active.settled = true; failPendingSteers(active); rejectTurn(error); } },
		};
		void turnPromise.catch(() => {});
		this.#active = active;
		const abort = () => {
			active.reject(new ClaudeProviderError('STALE_PLAN', 'Claude native turn was aborted'));
			void this.interrupt().catch(() => {});
		};
		signal?.addEventListener('abort', abort, { once: true });
		let completed = false;
		try {
			// Same compact fact encoding Codex receives; the observe tool description documents it.
			const encoded = encodeNativeEventInput(input, this.#observationViews);
			const carryOver = this.#pendingCarryOver;
			this.#writeUserMessage(carryOver === null ? encoded : `${carryOver}

${encoded}`);
			providerEvent(onVerbose, 'native_provider_turn_sent', { turnId: active.turnId, sessionGeneration: this.#sessionGeneration });
			this.#pendingCarryOver = null;
			this.#processUsed = true;
			this.#noteEventFacts(input);
			this.#awaitingResult += 1;
			silence.restart();
			const result = await Promise.race([turnPromise, silence.promise]);
			if (this.#active !== active || this.#goalRevision !== goalRevision || signal?.aborted) {
				throw new ClaudeProviderError('STALE_PLAN', 'Claude native turn belongs to an obsolete goal revision');
			}
			this.#sessionState = 'warm';
			this.#turnsSinceRotation += 1;
			completed = true;
			return result;
		} catch (error) {
			this.#observationViews.forgetEventView();
			if (error?.code === 'PLANNING_TIMEOUT') await this.interrupt().catch(() => {});
			throw error;
		} finally {
			signal?.removeEventListener('abort', abort);
			silence.dispose();
			if (this.#active === active) this.#active = null;
			// Keep a replacement warm while the next model call runs; a tool boundary can then hand off this turn.
			if (completed) this.#prepareStandbyIfUseful();
			if (completed) this.#rotateAfterTurn();
		}
	}

	async steer(input, { goalRevision = this.#goalRevision, onInterrupt = null, onDiscard = null } = {}) {
		this.#assertNative('steer');
		this.#assertUsable();
		if (!(typeof input === 'function' || typeof input === 'string' && input.trim().length > 0)) throw new TypeError('native steer input must be nonblank or a builder');
		if (onInterrupt !== null && typeof onInterrupt !== 'function') throw new TypeError('onInterrupt must be a function or null');
		if (onDiscard !== null && typeof onDiscard !== 'function') throw new TypeError('onDiscard must be a function or null');
		if (goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_GOAL_REVISION', `Goal revision ${goalRevision} does not match ${this.#goalRevision}`);
		const active = this.#active;
		if (active === null || active.settled || active.goalRevision !== goalRevision) {
			throw new ClaudeProviderError('TURN_NOT_ACTIVE', `Claude agent '${this.agentId}' has no steerable native turn`);
		}
		// Claude Code's own mid-turn message queue can spill into a second turn, which would desynchronize
		// turn accounting. Keep only the newest pending builder and resolve every folded request from that delivery.
		const delivered = new Promise((resolve, reject) => {
			const waiter = { resolve: () => resolve({ turnId: active.turnId }), reject };
			const previous = active.pendingSteers;
			active.pendingSteers = {
				buildInput: typeof input === 'function' ? input : async () => input,
				onDiscard, discarded: false,
				waiters: [...(previous?.waiters ?? []), waiter],
				queuedAt: previous?.queuedAt ?? Date.now(),
			};
			discardPendingSteer(previous);
			active.silence.restart();
		});
		try { onInterrupt?.(); } catch { /* steer delivery remains authoritative if interruption reporting fails */ }
		return delivered;
	}

	/** One-shot structured decision used for goal translation and ArenaScript planning. */
	async decide(input, {
		goalRevision, signal, turnRecorder = null, attempt = 1, retry = false, queueWaitMs, onVerbose = null,
		outputSchema = PLANNER_OUTPUT_SCHEMA, parseOutput = parseDecision, systemPrompt,
	} = {}) {
		if (this.#controlProtocol === 'native_tools') throw new ClaudeProviderError('CONTROL_PROTOCOL_MISMATCH', 'Native tool agents must use act()');
		this.#assertUsable();
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('planner input must be nonblank');
		if (typeof parseOutput !== 'function') throw new TypeError('parseOutput must be a function');
		if (systemPrompt !== undefined && typeof systemPrompt !== 'string') throw new TypeError('systemPrompt must be a string');
		if (goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_GOAL_REVISION', `Goal revision ${String(goalRevision)} does not match ${this.#goalRevision}`);
		if (signal?.aborted) throw signal.reason ?? new ClaudeProviderError('PLAN_CANCELLED', 'Planning was cancelled');
		if (this.#oneShot !== null) throw new ClaudeProviderError('TURN_IN_PROGRESS', `Claude agent '${this.agentId}' already has an active turn`);

		const schemaText = outputSchema === null || outputSchema === undefined
			? ''
			: `\n\nReturn only one JSON value matching this JSON Schema, with no prose or code fences:\n${JSON.stringify(outputSchema)}`;
		const prompt = `${systemPrompt ? `${systemPrompt}\n\n` : ''}${input}${schemaText}`;
		const startedAt = Date.now();
		const operation = this.#runOneShot(prompt);
		this.#oneShot = operation;
		const abort = () => { void operation.cancel(new ClaudeProviderError('PLAN_CANCELLED', 'Planning was cancelled')); };
		signal?.addEventListener('abort', abort, { once: true });
		let output = '';
		let timing = null;
		try {
			const result = await operation.promise;
			output = result.text;
			timing = { durationMs: result.durationMs ?? Date.now() - startedAt, apiDurationMs: result.apiDurationMs ?? null, ...(Number.isFinite(queueWaitMs) && queueWaitMs >= 0 ? { queueWaitMs } : {}) };
			if (Buffer.byteLength(output, 'utf8') > this.#config.maxDecisionBytes) throw new ClaudeProviderError('TURN_OUTPUT_LIMIT', `Claude planner output exceeded ${this.#config.maxDecisionBytes} bytes`);
			if (signal?.aborted || goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_PLAN', 'claude result belongs to an obsolete goal');
			reportVisibleOutput(onVerbose, output);
			let decision;
			try { decision = parseOutput(stripCodeFence(output.trim())); }
			catch (error) {
				const parseError = new ClaudeProviderError(error?.code ?? 'INVALID_DECISION', 'claude returned an invalid planner decision', { cause: error });
				parseError.category = 'decision_parse';
				throw parseError;
			}
			recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings, agentId: this.agentId, provider: 'claude', model: this.#profile.model,
				reasoningEffort: this.#profile.reasoningEffort, goalRevision, attempt, retry, input: prompt, output, error: null, timing,
				...(result.tokens === null ? {} : { tokens: result.tokens }),
			});
			return decision;
		} catch (error) {
			recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings, agentId: this.agentId, provider: 'claude', model: this.#profile.model,
				reasoningEffort: this.#profile.reasoningEffort, goalRevision, attempt, retry, input: prompt,
				output: error?.category === 'decision_parse' ? '' : output, error, timing,
			});
			if (signal?.aborted || goalRevision !== this.#goalRevision) throw new ClaudeProviderError('STALE_PLAN', 'claude result belongs to an obsolete goal', { cause: error });
			throw error;
		} finally {
			signal?.removeEventListener('abort', abort);
			if (this.#oneShot === operation) this.#oneShot = null;
		}
	}

	async interrupt() {
		const oneShot = this.#oneShot;
		if (oneShot !== null) await oneShot.cancel(new ClaudeProviderError('PLAN_CANCELLED', 'Planning was cancelled'));
		const active = this.#active;
		if (active === null) return;
		active.reject(new ClaudeProviderError('STALE_PLAN', 'Claude turn was interrupted'));
		if (this.#process === null) return;
		this.#writeLine({ type: 'control_request', request_id: `interrupt-${active.turnId}`, request: { subtype: 'interrupt' } });
		try {
			await withDeadline(this.#idlePromise(), this.#config.interruptTimeoutMs, this.#schedule, this.#cancelSchedule,
				() => new ClaudeProviderError('INTERRUPT_TIMEOUT', 'Claude Code did not acknowledge the interrupt'));
		} catch {
			// A process that ignores interrupts loses its warm context rather than blocking the body.
			await this.#stopProcess();
		}
	}

	async dispose() {
		if (this.#disposed) return;
		this.#disposed = true;
		const error = this.#invalidationError ?? new ClaudeProviderError('AGENT_DISPOSED', `Claude agent '${this.agentId}' is disposed`);
		this.#active?.reject(error);
		this.#active = null;
		await this.#oneShot?.cancel(error);
		await this.#stopProcess();
		await this.#standbyStart?.catch(() => {});
		await this.#stopStandbyProcess();
		await this.#fs.rm(this.#systemPromptFile, { force: true }).catch(() => {});
	}

	invalidateSession(cause) {
		if (this.#invalidationError !== null || this.#disposed) return;
		this.#invalidationError = new ClaudeProviderError('SESSION_INVALIDATED', 'Claude session is no longer usable', { cause });
		this.#sessionState = 'cold';
		this.#active?.reject(this.#invalidationError);
		this.#onInvalidated?.(this);
		void this.dispose();
	}

	#assertNative(operation) {
		if (this.#controlProtocol !== 'native_tools') throw new ClaudeProviderError('CONTROL_PROTOCOL_MISMATCH', `Only native tool agents can ${operation}`);
	}

	#assertUsable() {
		if (this.#disposed) throw this.#invalidationError ?? new ClaudeProviderError('AGENT_DISPOSED', `Claude agent '${this.agentId}' is disposed`);
	}

	#ensureProcess() {
		if (this.#process !== null) return Promise.resolve();
		// prewarm() and act() can race; both must share one Claude Code process.
		this.#processStart ??= this.#startProcess().finally(() => { this.#processStart = null; });
		return this.#processStart;
	}

	async #startProcess({ standby = false } = {}) {
		if (!standby) this.#resetContextState();
		let resolveListed;
		let rejectListed;
		const toolsListed = { promise: new Promise((resolve, reject) => { resolveListed = resolve; rejectListed = reject; }), resolve: () => resolveListed(), reject: (error) => rejectListed(error) };
		void toolsListed.promise.catch(() => {});
		const route = await this.#toolServer.register({
			callTool: (name, args, meta) => this.#callTool(name, args, meta),
			onToolsListed: () => toolsListed.resolve(),
			onToolResponded: ({ toolUseId }) => {
				if (toolUseId !== null && this.#active !== null) providerEvent(this.#active.onVerbose, 'native_provider_tool_result_sent', { callId: toolUseId });
			},
		});
		const state = { child: null, stdout: '', stderr: '', stderrBytes: 0, exited: false, route, toolsListed, sessionId: null, standby };
		if (standby) this.#standbyProcess = state;
		else { this.#route = route; this.#toolsListed = toolsListed; }
		this.#assertUsable();
		const launch = buildClaudeLaunch(this.#profile, this.#config, {
			cwd: this.#cwd,
			systemPromptFile: this.#systemPromptFile,
			mcpConfig: { mcpServers: { [MCP_SERVER_NAME]: { type: 'http', url: route.url, headers: { Authorization: `Bearer ${route.token}` } } } },
			streaming: true,
		});
		let child;
		try { child = this.#spawn(launch.command, launch.args, launch.options); }
		catch (error) {
			route.unregister();
			if (standby) this.#standbyProcess = null;
			else this.#route = null;
			throw new ClaudeProviderError('SPAWN_FAILED', `Could not start Claude Code: ${error.message}`, { cause: error });
		}
		state.child = child;
		if (!standby) this.#process = state;
		child.stdout?.setEncoding?.('utf8');
		child.stdout?.on('data', (chunk) => this.#onStdout(state, String(chunk)));
		child.stderr?.on('data', (chunk) => {
			const text = String(chunk);
			state.stderrBytes += Buffer.byteLength(text, 'utf8');
			if (state.stderrBytes <= this.#config.stderrLimitBytes) state.stderr += text;
		});
		child.stdin?.on?.('error', () => {});
		child.once('error', (error) => this.#onProcessExit(state, new ClaudeProviderError('SPAWN_FAILED', `Could not start Claude Code: ${error.message}`, { cause: error })));
		child.once('close', (exitCode, signalCode) => this.#onProcessExit(state, new ClaudeProviderError('PROVIDER_UNAVAILABLE',
			`Claude Code exited with code ${String(exitCode)} and signal ${String(signalCode)} [stderr=${excerpt(state.stderr)}]`)));
	}

	#prepareStandbyIfUseful() {
		const threshold = this.#config.contextRotationTokens;
		if (threshold <= 0 || this.#lastContextTokens < Math.max(1, threshold - STANDBY_WARMUP_LEAD_TOKENS)
			|| this.#disposed || this.#process === null
			|| this.#standbyProcess !== null || this.#standbyStart !== null) return;
		const warming = (async () => {
			await this.#startProcess({ standby: true });
			const state = this.#standbyProcess;
			if (state === null) throw new ClaudeProviderError('PROVIDER_UNAVAILABLE', 'Claude standby process exited during startup');
			await withDeadline(state.toolsListed.promise, this.#config.startupTimeoutMs, this.#schedule, this.#cancelSchedule,
				() => new ClaudeProviderError('PROVIDER_START_TIMEOUT', `Claude Code did not load the Minecraft tools within ${this.#config.startupTimeoutMs} ms`));
			if (this.#standbyProcess !== state || state.exited) throw new ClaudeProviderError('PROVIDER_UNAVAILABLE', 'Claude standby process exited during startup');
			return state;
		})();
		this.#standbyStart = warming.catch(async (error) => {
			await this.#stopStandbyProcess();
			throw error;
		}).finally(() => { if (this.#standbyStart === tracked) this.#standbyStart = null; });
		const tracked = this.#standbyStart;
		void tracked.catch(() => {});
	}

	async #standbyAtToolBoundary() {
		if (!this.#rotationDue || this.#midTurnRotationUsed || this.#disposed) return null;
		this.#prepareStandbyIfUseful();
		if (this.#standbyStart === null) return this.#standbyProcess;
		try { return await this.#standbyStart; } catch { return null; }
	}

	async #stopStandbyProcess() {
		const state = this.#standbyProcess;
		this.#standbyProcess = null;
		if (state === null) return;
		state.exited = true;
		state.route?.unregister();
		try { state.child?.stdin?.end?.(); } catch { /* the process may already be gone */ }
		await Promise.resolve(this.#terminate(state.child)).catch(() => {});
	}

	async #promoteStandby(state) {
		const previous = this.#process;
		this.#pendingCarryOver = this.#carryOver('Session refreshed to keep context small');
		this.#process = null;
		this.#route = null;
		if (previous !== null) {
			previous.exited = true;
			previous.route?.unregister();
			try { previous.child.stdin?.end?.(); } catch { /* the process may already be gone */ }
			await Promise.resolve(this.#terminate(previous.child)).catch(() => {});
		}
		if (this.#disposed) return false;
		if (this.#standbyProcess === state && !state.exited) {
			this.#standbyProcess = null;
			state.standby = false;
			this.#process = state;
			this.#route = state.route;
			this.#toolsListed = state.toolsListed;
			this.#sessionId = state.sessionId;
			this.#resetContextState();
		} else {
			// If the warmed process exits during handoff, keep the completed tool result and continue cold.
			await this.#ensureProcess();
			await withDeadline(this.#toolsListed.promise, this.#config.startupTimeoutMs, this.#schedule, this.#cancelSchedule,
				() => new ClaudeProviderError('PROVIDER_START_TIMEOUT', `Claude Code did not load the Minecraft tools within ${this.#config.startupTimeoutMs} ms`));
		}
		this.#rotations += 1;
		return true;
	}

	#onStdout(state, chunk) {
		if (this.#process !== state && this.#standbyProcess !== state) return;
		state.stdout += chunk;
		if (Buffer.byteLength(state.stdout, 'utf8') > this.#config.stdoutLineLimitBytes && !state.stdout.includes('\n')) {
			this.invalidateSession(new ClaudeProviderError('OUTPUT_LIMIT_EXCEEDED', `Claude Code output line exceeded ${this.#config.stdoutLineLimitBytes} bytes`));
			return;
		}
		let newline;
		while ((newline = state.stdout.indexOf('\n')) >= 0) {
			const line = state.stdout.slice(0, newline).trim();
			state.stdout = state.stdout.slice(newline + 1);
			if (line.length === 0) continue;
			let message;
			try { message = JSON.parse(line); } catch { continue; }
			this.#onMessage(message, state);
		}
	}

	#onMessage(message, state = this.#process) {
		if (state?.standby === true && this.#standbyProcess === state) {
			if (message?.type === 'system' && message.subtype === 'init') {
				if (typeof message.session_id === 'string') state.sessionId = message.session_id;
				return;
			}
			return;
		}
		const active = this.#active;
		const now = Date.now();
		// Token deltas arrive many times a second; renewing timers and leases for each one is wasted work.
		const renew = message?.type !== 'stream_event' || now - this.#lastStreamProgressAt >= STREAM_PROGRESS_INTERVAL_MS;
		if (active !== null && !active.settled && renew) {
			this.#lastStreamProgressAt = now;
			active.silence.restart();
			try { active.onProgress?.({ phase: 'provider' }); } catch { /* progress reporting cannot fail provider work */ }
		}
		if (message?.type === 'stream_event') { this.#noteStreamMarks(message.event, now); this.#onStreamEvent(message.event); return; }
		if (message?.type === 'assistant') this.#noteAssistantUsage(message.message);
		if (message?.type === 'system' && message.subtype === 'init') {
			if (typeof message.session_id === 'string') { this.#sessionId = message.session_id; if (state !== null) state.sessionId = message.session_id; }
			if (typeof message.model === 'string' && message.model.length > 0 && message.model.length <= 256) {
				this.#executionSettings.effective.model = message.model;
				this.#executionSettings.evidence.model = 'provider_reported';
			}
			return;
		}
		if (message?.type === 'assistant' && active !== null && !active.publishedMessage) {
			const text = (Array.isArray(message.message?.content) ? message.message.content : [])
				.filter((block) => block?.type === 'text' && typeof block.text === 'string').map((block) => block.text).join('\n').trim();
			if (text.length > 0) {
				this.#lastAgentText = text.slice(0, 400);
				active.publishedMessage = true;
				safeVerbose(active.onVerbose, 'agent_message', text.slice(0, MAX_PUBLIC_AGENT_MESSAGE_CHARS));
			}
			return;
		}
		if (message?.type !== 'result') return;
		if (typeof message.session_id === 'string') this.#sessionId = message.session_id;
		this.#finishCalls();
		// In streaming input mode total_cost_usd is the running total for this Claude Code process.
		if (Number.isFinite(message.total_cost_usd) && message.total_cost_usd >= 0) {
			const delta = message.total_cost_usd >= this.#processCostUsd ? message.total_cost_usd - this.#processCostUsd : message.total_cost_usd;
			this.#processCostUsd = message.total_cost_usd;
			this.#usage.costUsd += delta;
			if (active !== null) active.usage.costUsd = (active.usage.costUsd ?? 0) + delta;
		}
		// Per-step assistant output_tokens are placeholders; the result's usage covers this turn's real output.
		const turnOutput = message.usage?.output_tokens;
		if (active !== null && Number.isSafeInteger(turnOutput) && turnOutput >= 0) {
			this.#usage.output += turnOutput - active.usage.output;
			active.usage.output = turnOutput;
		}
		if (this.#awaitingResult > 0) this.#awaitingResult -= 1;
		if (this.#awaitingResult === 0) for (const resolve of this.#idleWaiters.splice(0)) resolve();
		if (active === null || active.settled) return;
		if (message.subtype === 'success' && message.is_error !== true) {
			// Tool responses are returned before Claude Code continues, but drain the queue defensively.
			void active.tail.then(() => active.resolve({ status: 'completed', toolCalls: active.toolCalls, ...(active.usage.calls === 0 && active.usage.costUsd === null ? {} : { usage: { ...active.usage, contextTokens: this.#lastContextTokens } }) }));
			return;
		}
		active.reject(new ClaudeProviderError(resultErrorCode(message) ?? 'TURN_FAILED',
			`Claude Code ended the turn (${String(message.subtype ?? 'error')}): ${excerpt(message.result ?? message.errors ?? '')}`));
	}

	#encodeSteer = async (input) => {
		const text = await resolveSteerInput(input);
		this.#noteEventFacts(text);
		return encodeNativeEventInput(text, this.#observationViews);
	};

	async #callTool(name, args, { toolUseId }) {
		const active = this.#active;
		if (this.#disposed || active === null || active.settled) {
			return mcpContent(toolResultContent({ state: 'FAILED', reasonCode: 'TURN_NOT_ACTIVE', message: 'No Minecraft turn is active for this body.' }, false));
		}
		active.toolCalls += 1;
		const run = async () => {
			if (active.settled) return toolResultContent({ state: 'FAILED', reasonCode: 'TURN_NOT_ACTIVE', message: 'The Minecraft turn already ended.' }, false);
			active.silence.pause();
			let content;
			let result;
			let tool;
			let toolFailed = false;
			let commit = () => {};
			try {
				tool = normalizeMinecraftToolCall(name, args);
				result = await active.executeTool({
					agentId: this.agentId,
					goalRevision: active.goalRevision,
					threadId: this.#sessionId,
					turnId: active.turnId,
					callId: toolUseId,
					tool,
				});
				this.#rememberTool(name, args, result);
				this.#noteProgram(result);
			} catch (error) {
				this.#rememberTool(name, args, { state: 'FAILED', reasonCode: error?.code ?? 'TOOL_EXECUTION_FAILED' });
				result = {
					state: 'FAILED',
					reasonCode: String(error?.code ?? 'TOOL_EXECUTION_FAILED').slice(0, 128),
					message: String(error?.message ?? error).slice(0, 512),
					...(error?.actionContract === undefined ? {} : { actionContract: error.actionContract }),
				};
				toolFailed = true;
			} finally {
				active.silence.resume();
			}
			if (result !== undefined && tool !== undefined && this.#rotationDue && !this.#midTurnRotationUsed && !this.#disposed) {
				active.silence.pause();
				try {
					const standby = await this.#standbyAtToolBoundary();
					if (standby !== null && !active.settled) {
						const promoted = await this.#promoteStandby(standby);
						if (promoted) {
							this.#midTurnRotationUsed = true;
							// The replacement has no prior view IDs, so its first tool result is always self-contained.
							const turnInput = encodeNativeEventInput(active.input, this.#observationViews);
							const presented = presentNativeToolResult(result, { ...tool, view: 'full' }, this.#observationViews);
							if (toolFailed) presented.response.success = false;
							commit = presented.commit;
							const delivered = await deliverSteers(active, presented.response, this.#encodeSteer);
							const [toolResult, ...steers] = delivered.contentItems.map((item) => item.text);
							const handoff = [
								this.#pendingCarryOver,
								`Current turn input to continue:\n${turnInput}`,
								`Tool result for Minecraft tool "${name}"${toolUseId === null ? '' : ` (tool use ${toolUseId})`}:\n${toolResult}`,
								...steers,
							].filter(Boolean).join('\n\n');
							this.#pendingCarryOver = handoff;
							this.#writeUserMessage(handoff);
							this.#pendingCarryOver = null;
							this.#processUsed = true;
							commit();
							return mcpContent(toolResultContent({ state: 'FAILED', reasonCode: 'SESSION_ROTATED', message: 'This process ended at a session boundary; the completed tool result continues in the replacement turn.' }, false));
						}
					}
				} finally {
					active.silence.resume();
				}
			}
			if (content === undefined) {
				if (tool === undefined) content = toolResultContent(result, false);
				else {
					const presented = presentNativeToolResult(result, tool, this.#observationViews);
					if (toolFailed) presented.response.success = false;
					content = presented.response;
					commit = presented.commit;
				}
			}
			const delivered = await deliverSteers(active, content, this.#encodeSteer);
			// A settled (interrupted or aborted) turn may never show this result to the model.
			if (!active.settled) commit();
			this.#requestAt = Date.now();
			return delivered;
		};
		// Codex executes one Minecraft tool at a time; parallel Claude tool calls are serialized the same way.
		const task = active.tail.then(run, run);
		active.tail = task.catch(() => {});
		return mcpContent(await task);
	}

	/** Rotates after a completed turn when a long-turn tool boundary did not use the warm session. */
	#rotateAfterTurn() {
		if (!this.#rotationDue || this.#turnsSinceRotation < MIN_TURNS_BETWEEN_ROTATIONS) return;
		if (this.#process === null || this.#active !== null || this.#awaitingResult > 0 || this.#disposed) return;
		const carryOver = this.#carryOver('Session refreshed to keep context small');
		// Arm before #stopProcess yields so a racing act cannot consume a generic restart note.
		this.#pendingCarryOver = carryOver;
		this.#rotations += 1;
		void this.#stopProcess().then(async () => {
			if (this.#disposed || this.#invalidationError !== null) return;
			if (this.#process !== null) { await this.#standbyStart?.catch(() => {}); await this.#stopStandbyProcess(); return; }
			let standby = this.#standbyProcess;
			if (standby === null && this.#standbyStart !== null) {
				try { standby = await this.#standbyStart; } catch { /* a normal replacement starts below */ }
			}
			if (this.#process !== null) { await this.#standbyStart?.catch(() => {}); await this.#stopStandbyProcess(); return; }
			if (standby !== null && this.#standbyProcess === standby && !standby.exited) {
				this.#standbyProcess = null;
				standby.standby = false;
				this.#process = standby;
				this.#route = standby.route;
				this.#toolsListed = standby.toolsListed;
				this.#sessionId = standby.sessionId;
				return;
			}
			return this.#ensureProcess();
		}).catch(() => {});
	}

	#carryOver(reason) {
		const lines = this.#recentTools.map((entry) => `- ${entry}`);
		return [
			`${reason}: your earlier turns are not shown. The event below restates your goal, task memory and facts; read taskPlan, programStatus, queryMemory or taskMemory for anything else.`,
			...(lines.length === 0 ? [] : ['Your most recent tool calls (oldest first):', ...lines]),
			...(this.#recentConversation.length === 0 ? [] : ['Recent conversation already delivered to you (oldest first):', ...this.#recentConversation.map((entry) => `- ${entry}`)]),
			...(this.#lastProgram === null ? [] : [`Last known program (verify with programStatus): ${JSON.stringify(this.#lastProgram)}`]),
			...(this.#lastAgentText === null ? [] : [`Your last note: ${JSON.stringify(this.#lastAgentText)}`]),
		].join('\n');
	}

	/** Remembers delivered conversation and program state from a native event for a later carry-over. */
	#noteEventFacts(text) {
		const separator = text.indexOf('\n');
		if (separator < 0) return;
		const lineEnd = text.indexOf('\n', separator + 1);
		let value;
		try { value = JSON.parse(text.slice(separator + 1, lineEnd < 0 ? text.length : lineEnd)); } catch { return; }
		for (const entry of Array.isArray(value?.conversation?.entries) ? value.conversation.entries : []) {
			const speaker = typeof entry?.sourceName === 'string' ? entry.sourceName : typeof entry?.sourceId === 'string' ? entry.sourceId : entry?.kind ?? 'message';
			if (typeof entry?.text !== 'string') continue;
			this.#recentConversation.push(`${truncate(String(speaker), 48)}: ${truncate(entry.text, 200)}`);
		}
		if (this.#recentConversation.length > CARRY_OVER_CONVERSATION) this.#recentConversation.splice(0, this.#recentConversation.length - CARRY_OVER_CONVERSATION);
		if (value?.program !== null && typeof value?.program === 'object') this.#noteProgram(value.program);
	}

	#noteProgram(value) {
		if (value === null || typeof value !== 'object' || typeof value.programId !== 'string') return;
		const decision = value.decision ?? value.status?.decision;
		this.#lastProgram = {
			programId: value.programId,
			...(typeof value.state === 'string' ? { state: value.state } : {}),
			...(typeof value.engineState === 'string' ? { engineState: value.engineState } : {}),
			...(typeof decision?.decisionId === 'string' ? { pendingDecisionId: decision.decisionId, trigger: decision.trigger } : {}),
		};
	}

	#rememberTool(name, args, result) {
		const argsText = truncate(JSON.stringify(args ?? {}), 160);
		const outcome = [result?.state, result?.reasonCode].filter((value) => typeof value === 'string').join(' ');
		this.#recentTools.push(`${name} ${argsText} -> ${outcome || 'returned'}`);
		if (this.#recentTools.length > CARRY_OVER_TOOL_CALLS) this.#recentTools.splice(0, this.#recentTools.length - CARRY_OVER_TOOL_CALLS);
	}

	/**
	 * Marks the call's first stream event of any kind, so a late or missing message_start cannot hide the
	 * prefill/generation split, and records every tool the model starts, including Claude Code's own (Read, Glob) that
	 * never reach the Minecraft tool server.
	 */
	#noteStreamMarks(event, now) {
		const marks = this.#stream;
		marks.firstAt ??= now;
		marks.events += 1;
		if (event?.type === 'message_start') marks.startAt ??= now;
		else if (event?.type === 'content_block_start' && event.content_block?.type === 'tool_use' && typeof event.content_block.name === 'string' && marks.tools.length < MAX_TRACED_TOOLS) {
			marks.tools.push({ name: event.content_block.name.slice(0, 64), at: now });
		}
	}

	/** --include-partial-messages: message_start is the first streamed event (after prefill), message_stop ends generation. */
	#onStreamEvent(event) {
		const id = event?.message?.id;
		if (event?.type === 'message_start' && typeof id === 'string') {
			this.#currentCallId = id;
			const call = this.#call(id);
			mergeUsage(call.usage, event.message.usage);
			return;
		}
		const call = this.#currentCallId === null ? undefined : this.#calls.get(this.#currentCallId);
		if (call === undefined) return;
		if (event?.type === 'message_delta') mergeUsage(call.usage, event.usage);
		else if (event?.type === 'message_stop') this.#finishCall(call);
	}

	/** Without partial messages each API call still arrives as assistant messages sharing one message id. */
	#noteAssistantUsage(message) {
		if (typeof message?.id !== 'string') return;
		const call = this.#call(message.id);
		call.firstEventAt ??= Date.now();
		mergeUsage(call.usage, message.usage);
	}

	#call(id) {
		let call = this.#calls.get(id);
		if (call === undefined) {
			call = { id, requestAt: this.#requestAt, firstEventAt: null, stream: this.#stream, usage: { input: 0, cacheRead: 0, cacheWrite: 0, output: 0 }, done: false };
			this.#calls.set(id, call);
			this.#currentCallId = id;
		}
		return call;
	}

	#finishCalls() {
		for (const call of this.#calls.values()) this.#finishCall(call);
		this.#calls.clear();
		this.#currentCallId = null;
		this.#stream = newStreamMarks();
	}

	#finishCall(call) {
		if (call.done) return;
		call.done = true;
		if (this.#stream === call.stream) this.#stream = newStreamMarks();
		const endedAt = Date.now();
		// Stream events, when present, mark the true first token of the call; an assistant message only proves it had finished.
		const firstAt = call.stream.firstAt ?? call.firstEventAt;
		const { input, cacheRead, cacheWrite, output } = call.usage;
		const contextTokens = input + cacheRead + cacheWrite;
		this.#lastContextTokens = contextTokens;
		const rotationTokens = this.#config.contextRotationTokens;
		if (rotationTokens > 0 && contextTokens >= rotationTokens) this.#rotationDue = true;
		this.#prepareStandbyIfUseful();
		addUsage(this.#usage, call.usage);
		const active = this.#active;
		if (active === null) return;
		addUsage(active.usage, call.usage);
		const total = this.#usage;
		const requestAt = call.requestAt;
		safeVerbose(active.onVerbose, 'live_usage', JSON.stringify({
			inputTokens: total.input + total.cacheRead + total.cacheWrite, cachedInputTokens: total.cacheRead, cacheWriteInputTokens: total.cacheWrite,
			outputTokens: total.output, totalTokens: total.input + total.cacheRead + total.cacheWrite + total.output,
			threadId: `claude:${this.agentId}:${this.#sessionGeneration}`, reportedAtEpochMs: endedAt,
			last: { inputTokens: contextTokens, cachedInputTokens: cacheRead, cacheWriteInputTokens: cacheWrite, outputTokens: output, totalTokens: contextTokens + output },
			call: {
				provider: 'claude', turnId: active.turnId, contextTokens, rotations: this.#rotations,
				...(requestAt === null || firstAt === null ? {} : { firstEventMs: Math.max(0, firstAt - requestAt) }),
				...(firstAt === null ? {} : { streamMs: Math.max(0, endedAt - firstAt) }),
				...(requestAt === null ? {} : { totalMs: Math.max(0, endedAt - requestAt), requestAt }),
				...(firstAt === null ? {} : { firstEventAt: firstAt, firstEventSource: call.stream.firstAt === null ? 'assistant' : 'stream' }),
				...(requestAt === null || call.stream.startAt === null ? {} : { startMs: Math.max(0, call.stream.startAt - requestAt) }),
				streamEvents: call.stream.events,
				...(call.stream.tools.length === 0 ? {} : { toolNames: call.stream.tools.map((tool) => tool.name), ...(requestAt === null ? {} : { toolStartMs: call.stream.tools.map((tool) => Math.max(0, tool.at - requestAt)) }) }),
			},
		}));
	}

	#writeUserMessage(text) {
		this.#writeLine({ type: 'user', message: { role: 'user', content: text } });
		this.#requestAt = Date.now();
	}

	#writeLine(value) {
		const child = this.#process?.child;
		if (child?.stdin === undefined || child.stdin === null || child.stdin.destroyed) {
			throw new ClaudeProviderError('PROVIDER_UNAVAILABLE', 'Claude Code input is closed');
		}
		child.stdin.write(`${JSON.stringify(value)}\n`);
	}

	#idlePromise() {
		if (this.#awaitingResult === 0) return Promise.resolve();
		return new Promise((resolve) => { this.#idleWaiters.push(resolve); });
	}

	async #waitForIdle() {
		if (this.#awaitingResult === 0) return;
		try {
			await withDeadline(this.#idlePromise(), this.#config.interruptTimeoutMs, this.#schedule, this.#cancelSchedule,
				() => new ClaudeProviderError('INTERRUPT_TIMEOUT', 'Claude Code did not finish the interrupted turn'));
		} catch {
			await this.#stopProcess();
			await this.#ensureProcess();
		}
	}

	#onProcessExit(state, error) {
		if (state.exited) return;
		state.exited = true;
		if (/unknown option[^\n]*--include-partial-messages/i.test(state.stderr)) PARTIAL_MESSAGES_UNSUPPORTED.add(partialMessagesKey(this.#config));
		if (this.#standbyProcess === state) {
			this.#standbyProcess = null;
			state.route?.unregister();
			state.toolsListed?.reject(error);
			return;
		}
		if (this.#process !== state) return;
		this.#process = null;
		// A process that dies before listing the tools fails prewarm now, not at the startup deadline.
		state.toolsListed?.reject(error);
		this.#releaseProcessState();
		if (!this.#disposed) this.invalidateSession(error);
	}

	async #stopProcess() {
		const state = this.#process;
		this.#process = null;
		this.#releaseProcessState();
		if (state === null) return;
		state.exited = true;
		try { state.child.stdin?.end?.(); } catch { /* the process may already be gone */ }
		await Promise.resolve(this.#terminate(state.child)).catch(() => {});
	}

	#releaseProcessState() {
		this.#route?.unregister();
		this.#route = null;
		this.#awaitingResult = 0;
		for (const resolve of this.#idleWaiters.splice(0)) resolve();
		this.#sessionState = 'cold';
		// An unplanned restart (unacknowledged interrupt, stuck turn) also loses the conversation.
		if (this.#processUsed && this.#pendingCarryOver === null) this.#pendingCarryOver = this.#carryOver('Claude Code restarted');
		this.#resetContextState();
	}

	/** A new Claude Code process has never seen earlier events, fact views or omitted metadata. */
	#resetContextState() {
		this.#observationViews.reset();
		this.#rotationDue = false;
		this.#lastContextTokens = 0;
		this.#midTurnRotationUsed = false;
		this.#turnsSinceRotation = 0;
		this.#processUsed = false;
		this.#processCostUsd = 0;
		this.#calls.clear();
		this.#currentCallId = null;
		this.#stream = newStreamMarks();
	}

	#runOneShot(prompt) {
		const launch = buildClaudeLaunch(this.#profile, this.#config, { cwd: this.#cwd, systemPromptFile: this.#systemPromptFile, mcpConfig: null, streaming: false });
		let child;
		try { child = this.#spawn(launch.command, launch.args, launch.options); }
		catch (error) {
			return { promise: Promise.reject(new ClaudeProviderError('SPAWN_FAILED', `Could not start Claude Code: ${error.message}`, { cause: error })), cancel: async () => {} };
		}
		let cancellation = null;
		let settle;
		let timer = null;
		const stdout = [];
		const stderr = [];
		let stdoutBytes = 0;
		let stderrBytes = 0;
		const terminate = this.#terminate;
		const cancel = async (error) => {
			cancellation ??= error;
			await Promise.resolve(terminate(child)).catch(() => {});
			settle?.(cancellation);
		};
		const promise = new Promise((resolve, reject) => {
			let settled = false;
			settle = (error, value) => {
				if (settled) return;
				settled = true;
				if (timer !== null) this.#cancelSchedule(timer);
				if (error === null) resolve(value); else reject(error);
			};
			child.stdout?.on('data', (chunk) => {
				stdoutBytes += chunk.length;
				if (stdoutBytes > this.#config.stdoutLineLimitBytes) { void cancel(new ClaudeProviderError('OUTPUT_LIMIT_EXCEEDED', 'Claude Code stdout exceeded its limit')); return; }
				stdout.push(Buffer.from(chunk));
			});
			child.stderr?.on('data', (chunk) => {
				stderrBytes += chunk.length;
				if (stderrBytes <= this.#config.stderrLimitBytes) stderr.push(Buffer.from(chunk));
			});
			child.once('error', (error) => settle(new ClaudeProviderError('SPAWN_FAILED', `Could not start Claude Code: ${error.message}`, { cause: error })));
			child.once('close', (exitCode, signalCode) => {
				if (cancellation !== null) { settle(cancellation); return; }
				const text = Buffer.concat(stdout).toString('utf8');
				if (exitCode !== 0) {
					settle(new ClaudeProviderError('PROVIDER_UNAVAILABLE', `Claude Code exited with code ${String(exitCode)} and signal ${String(signalCode)} [stderr=${excerpt(Buffer.concat(stderr).toString('utf8'))}] [stdout=${excerpt(text)}]`));
					return;
				}
				try { settle(null, parseClaudeJsonResult(text)); } catch (error) { settle(error); }
			});
			timer = this.#schedule(() => { void cancel(new ClaudeProviderError('PLANNING_TIMEOUT', `Claude planning timed out after ${this.#config.planningTimeoutMs} ms`)); }, this.#config.planningTimeoutMs);
			child.stdin?.on?.('error', () => {});
			child.stdin?.end?.(prompt, 'utf8');
		});
		return { promise, cancel };
	}
}

/**
 * Resolves how the Claude Code CLI is started, mirroring resolveCodexLaunch: an explicit
 * configured path wins, then a native claude.exe on PATH, then the npm package entrypoint
 * run through this Node (npm's claude.cmd shim cannot be spawned without a shell), then
 * the native installer location, and finally the bare name for the OS to resolve.
 * `args` carries the arguments that must precede the CLI's own arguments.
 */
const CLAUDE_NPM_ENTRYPOINT = Object.freeze(['@anthropic-ai', 'claude-code', 'cli.js']);

export function resolveClaudeLaunch(config, dependencies = {}) {
	const platform = dependencies.platform ?? process.platform;
	const environment = createProviderChildEnvironment('claude', dependencies.env ?? config.environment ?? process.env, config.bridgeSecretEnvironmentVariable);
	const pathExists = dependencies.existsSync ?? existsSync;
	const nodeExecutable = dependencies.execPath ?? process.execPath;
	const configured = typeof config.executable === 'string' && config.executable.trim().length > 0 ? config.executable.trim() : 'claude';
	const explicitPath = path.isAbsolute(configured) || /[\\/]/.test(configured);
	if (explicitPath && pathExists(configured)) return { command: configured, args: [], environment, source: 'configured' };
	if (platform === 'win32') {
		const onPath = explicitPath ? null : findExecutableOnPath(configured, environment, { platform, existsSync: pathExists, extensions: DIRECTLY_SPAWNABLE_WINDOWS_EXTENSIONS });
		if (onPath !== null) return { command: onPath, args: [], environment, source: 'path' };
		const appData = environmentValue(environment, 'APPDATA');
		if (appData !== null) {
			const entrypoint = path.join(appData, 'npm', 'node_modules', ...CLAUDE_NPM_ENTRYPOINT);
			if (pathExists(entrypoint)) return { command: nodeExecutable, args: [entrypoint], environment, source: 'npm' };
		}
		// Custom npm prefixes (nvm-windows and friends) only expose claude.cmd; run the package beside it.
		const besideShim = explicitPath ? null : findNpmEntrypointBesideShim(configured, CLAUDE_NPM_ENTRYPOINT, environment, { platform, existsSync: pathExists });
		if (besideShim !== null) return { command: nodeExecutable, args: [besideShim], environment, source: 'npm-shim' };
		const userProfile = environmentValue(environment, 'USERPROFILE');
		if (userProfile !== null) {
			const nativeInstall = path.join(userProfile, '.local', 'bin', 'claude.exe');
			if (pathExists(nativeInstall)) return { command: nativeInstall, args: [], environment, source: 'native' };
		}
	}
	return { command: configured, args: [], environment, source: 'bare' };
}

export function buildClaudeLaunch(profile, config, { cwd, systemPromptFile, mcpConfig = null, streaming = true }) {
	const launch = resolveClaudeLaunch(config);
	const args = [
		...launch.args,
		'--print',
		...(streaming ? ['--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', ...(PARTIAL_MESSAGES_UNSUPPORTED.has(partialMessagesKey(config)) ? [] : ['--include-partial-messages'])] : ['--output-format', 'json']),
		'--model', profile.model,
		'--effort', profile.reasoningEffort,
		'--system-prompt-file', systemPromptFile,
		// Isolation equivalent to Codex's dedicated CODEX_HOME: no user/project settings,
		// CLAUDE.md, hooks, plugins, or MCP servers other than the Minecraft tools.
		'--setting-sources', '',
		'--strict-mcp-config',
		...(mcpConfig === null ? [] : ['--mcp-config', JSON.stringify(mcpConfig)]),
		// Read-only access to the workspace references, matching the Codex permission profile.
		'--tools', mcpConfig === null ? '' : 'Read,Glob,Grep',
		...(mcpConfig === null ? [] : ['--allowedTools', `mcp__${MCP_SERVER_NAME}`]),
		'--permission-mode', 'dontAsk',
		// Confines the file tools to the workspace and removes every command-running tool.
		'--restricted',
		'--disable-slash-commands',
		'--no-session-persistence',
	];
	return {
		command: launch.command,
		args,
		options: {
			cwd,
			env: {
				...launch.environment,
				CLAUDE_CODE_DISABLE_AUTO_MEMORY: '1',
				CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
				DISABLE_AUTOUPDATER: '1',
				MCP_TOOL_TIMEOUT: MCP_TOOL_TIMEOUT_MS,
			},
			stdio: ['pipe', 'pipe', 'pipe'],
			windowsHide: true,
		},
	};
}

function systemPromptFor(controlProtocol, recoverySummary, minecraftInstructions, skillInstructions) {
	if (controlProtocol === 'native_tools') {
		const base = minecraftInstructions === '' ? NATIVE_AGENT_INSTRUCTIONS : nativeInstructions(minecraftInstructions, skillInstructions);
		return `${base}\n\n${CLAUDE_TOOL_NOTE}\n\n${nativeRecoveryInstructions(recoverySummary)}\n`;
	}
	if (controlProtocol === 'goal_spec') return `${goalSpecInstructions()}\n\nReturn only one JSON value matching the supplied output schema. Never call tools.\n`;
	return `${PLANNER_SYSTEM_PROMPT}\n\n${recoveryInstructions(recoverySummary)}\n`;
}

function parseClaudeJsonResult(output) {
	let document;
	try { document = JSON.parse(String(output).trim()); }
	catch (error) { throw new ClaudeProviderError('INVALID_PROVIDER_OUTPUT', 'Claude Code returned invalid JSON', { cause: error }); }
	if (document?.type !== 'result' || document.subtype !== 'success' || document.is_error === true || typeof document.result !== 'string') {
		throw new ClaudeProviderError(resultErrorCode(document) ?? 'PROVIDER_UNAVAILABLE', `Claude Code returned an unsuccessful result [output=${excerpt(output)}]`);
	}
	return {
		text: document.result,
		durationMs: finiteDuration(document.duration_ms),
		apiDurationMs: finiteDuration(document.duration_api_ms),
		tokens: claudeTokenUsage(document.usage),
	};
}

function claudeTokenUsage(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
	return {
		input: nativeToken(value.input_tokens),
		output: nativeToken(value.output_tokens),
		reasoning: null,
		cached: nativeToken(value.cache_read_input_tokens),
		cacheWrite: nativeToken(value.cache_creation_input_tokens),
	};
}

/** Maps Claude Code's error text onto the coordinator's recovery codes so account problems are not retried blindly. */
function resultErrorCode(document) {
	const text = String(document?.result ?? '');
	// e.g. "Fable 5.1 requires usage credits. Switch to another model": retrying the same model cannot succeed.
	if (/usage credits|switch to another model|do(?:es)? not have access to|model .*not (?:available|found)/i.test(text)) return 'MODEL_UNAVAILABLE';
	if (/not logged in|\/login|invalid api key|authentication/i.test(text)) return 'AUTHENTICATION_REQUIRED';
	if (/rate.?limit|usage limit|overloaded|429/i.test(text)) return 'RATE_LIMITED';
	return null;
}

/** Appends queued steers to a tool result so the model sees them before its next decision. */
async function deliverSteers(active, content, encode = (text) => text) {
	while (active.pendingSteers !== null) {
		const pending = active.pendingSteers;
		let text;
		try { text = await encode(await pending.buildInput()); }
		catch (error) {
			if (active.pendingSteers !== pending) { discardPendingSteer(pending); continue; }
			active.pendingSteers = null;
			discardPendingSteer(pending);
			for (const waiter of pending.waiters) waiter.reject(error);
			return content;
		}
		if (active.pendingSteers !== pending) { discardPendingSteer(pending); continue; }
		active.pendingSteers = null;
		providerEvent(active.onVerbose, 'native_provider_steer_delivered', { turnId: active.turnId, steers: pending.waiters.length, waitMs: Math.max(0, Date.now() - pending.queuedAt) });
		const delivered = { ...content, contentItems: [...content.contentItems, {
			type: 'inputText', text: `Newer coordinator events for this turn (treat them exactly like the turn input):\n${text}`,
		}] };
		for (const waiter of pending.waiters) waiter.resolve();
		return delivered;
	}
	return content;
}

function failPendingSteers(active) {
	const pending = active.pendingSteers;
	active.pendingSteers = null;
	discardPendingSteer(pending);
	for (const waiter of pending?.waiters ?? []) waiter.reject(new ClaudeProviderError('TURN_NOT_ACTIVE', 'Claude turn ended before the steer reached a tool boundary'));
}

function discardPendingSteer(pending) {
	if (pending === null || pending === undefined || pending.discarded) return;
	pending.discarded = true;
	try { pending.onDiscard?.(); } catch { /* cleanup reporting cannot interrupt the next steer */ }
}

async function resolveSteerInput(input) {
	const resolved = typeof input === 'function' ? await input() : input;
	if (typeof resolved !== 'string' || resolved.trim().length === 0) throw new TypeError('native steer builder must return nonblank text');
	return resolved;
}

function mcpContent({ success, contentItems }) {
	return {
		content: contentItems.map((item) => ({ type: 'text', text: item.text })),
		isError: success === false,
	};
}

function createSilenceDeadline(timeoutMs, schedule, cancelSchedule) {
	let handle = null;
	let generation = 0;
	let disposed = false;
	let paused = 0;
	let rejectDeadline;
	const promise = new Promise((_, reject) => { rejectDeadline = reject; });
	void promise.catch(() => {});
	const clear = () => {
		generation += 1;
		if (handle !== null) cancelSchedule(handle);
		handle = null;
	};
	const restart = () => {
		if (disposed) return;
		clear();
		if (paused > 0) return;
		const expected = generation;
		handle = schedule(() => {
			if (disposed || generation !== expected) return;
			handle = null;
			rejectDeadline(new ClaudeProviderError('PLANNING_TIMEOUT', `Claude provider was silent for ${timeoutMs} ms`));
		}, timeoutMs);
	};
	return {
		promise,
		restart,
		pause() { paused += 1; clear(); },
		resume() { paused = Math.max(0, paused - 1); restart(); },
		dispose() { disposed = true; clear(); },
	};
}

function withDeadline(promise, timeoutMs, schedule, cancelSchedule, createError) {
	let handle;
	const timeout = new Promise((_, reject) => {
		handle = schedule(() => reject(createError()), timeoutMs);
		handle?.unref?.();
	});
	return Promise.race([promise, timeout]).finally(() => cancelSchedule(handle));
}

function validateServiceConfig(config) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('Claude service config must be an object');
	if ((config.provider ?? 'claude') !== 'claude') throw new TypeError('Claude provider must be claude');
	const cwd = requireText(config.cwd, 'cwd');
	const models = requireStringArray(config.models ?? Object.keys(CLAUDE_MODELS), 'models');
	for (const model of models) if (!/^claude-[a-z0-9.-]+$/.test(model)) throw new TypeError(`Claude model '${model}' must be a claude-* model id`);
	const reasoningEfforts = requireStringArray(config.reasoningEfforts ?? CLAUDE_REASONING_EFFORTS, 'reasoningEfforts');
	for (const effort of reasoningEfforts) {
		if (!CLAUDE_REASONING_EFFORTS.includes(effort)) throw new TypeError(`Claude reasoning effort '${effort}' must be one of ${CLAUDE_REASONING_EFFORTS.join(', ')}`);
	}
	return {
		...config,
		provider: 'claude',
		cwd,
		executable: requireText(config.executable ?? 'claude', 'executable'),
		runtimeRoot: path.resolve(config.runtimeRoot ?? path.join(cwd, 'runtime', 'claude-agents')),
		models,
		reasoningEfforts,
		planningTimeoutMs: positiveInteger(config.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS, 'planningTimeoutMs'),
		startupTimeoutMs: positiveInteger(config.startupTimeoutMs ?? DEFAULT_STARTUP_TIMEOUT_MS, 'startupTimeoutMs'),
		interruptTimeoutMs: positiveInteger(config.interruptTimeoutMs ?? DEFAULT_INTERRUPT_TIMEOUT_MS, 'interruptTimeoutMs'),
		maxDecisionBytes: positiveInteger(config.maxDecisionBytes ?? DEFAULT_MAX_DECISION_BYTES, 'maxDecisionBytes'),
		stdoutLineLimitBytes: positiveInteger(config.stdoutLimitBytes ?? DEFAULT_STDOUT_LINE_LIMIT_BYTES, 'stdoutLimitBytes'),
		stderrLimitBytes: positiveInteger(config.stderrLimitBytes ?? DEFAULT_STDERR_LIMIT_BYTES, 'stderrLimitBytes'),
		contextRotationTokens: nonnegativeInteger(config.contextRotationTokens ?? DEFAULT_CONTEXT_ROTATION_TOKENS, 'contextRotationTokens'),
	};
}

function validateProfile(value, config) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('agent profile must be an object');
	const profile = {
		agentId: requireText(value.agentId, 'agentId'),
		provider: value.provider ?? 'claude',
		model: requireText(value.model, 'model'),
		reasoningEffort: requireText(value.reasoningEffort, 'reasoningEffort'),
		serviceTier: requireText(value.serviceTier ?? 'priority', 'serviceTier'),
	};
	if (profile.provider !== 'claude') throw new ClaudeProviderError('PROVIDER_MISMATCH', `Expected claude profile, received ${profile.provider}`);
	if (!config.models.includes(profile.model)) throw new ClaudeProviderError('UNSUPPORTED_MODEL', `claude model '${profile.model}' is not configured`);
	return profile;
}

function validateControlProtocol(value) {
	if (!CONTROL_PROTOCOLS.has(value)) throw new TypeError("controlProtocol must be 'arena_script', 'native_tools', or 'goal_spec'");
	return value;
}

function assertSameSession(entry, profile, protocol) {
	if (!profilesMatch(entry.profile, profile) || entry.controlProtocol !== protocol) throw profileConflict(profile.agentId);
}

function profileConflict(agentId) {
	return new ClaudeProviderError('AGENT_PROFILE_CONFLICT', `claude agent '${agentId}' already has a different profile`);
}

function assertReconciliationActive(signal) {
	if (signal?.aborted) throw new ClaudeProviderError('STALE_RECONCILIATION', 'Claude reconciliation was superseded');
}

function assertLifecycleActive(expected, current) {
	if (expected !== current) throw new ClaudeProviderError('PROVIDER_STOPPED', 'Claude service lifecycle was stopped');
}

function agentDirectoryName(agentId) {
	return createHash('sha256').update(agentId, 'utf8').digest('hex').slice(0, 24);
}

function stripCodeFence(text) {
	const fenced = /^```(?:json)?\s*\n([\s\S]*?)\n```$/.exec(text);
	return fenced === null ? text : fenced[1].trim();
}

/** Stage timestamp for the latency trace; the coordinator stamps it when the verbose channel delivers it. */
function providerEvent(callback, event, fields) {
	safeVerbose(callback, 'provider_event', JSON.stringify({ event, ...fields }));
}

function newStreamMarks() {
	return { firstAt: null, startAt: null, events: 0, tools: [] };
}

function safeVerbose(callback, stage, message) {
	if (typeof callback !== 'function') return;
	try { Promise.resolve(callback(stage, message)).catch(() => {}); }
	catch { /* public-agent-message reporting cannot affect provider work */ }
}

function normalizeRecoverySummary(value) {
	if (value === null || value === undefined || value === '') return null;
	if (typeof value !== 'string' || value.length > 2_048) throw new TypeError('recoverySummary must be at most 2048 characters');
	return value;
}

function profilesMatch(left, right) { return ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier'].every((key) => left[key] === right[key]); }
function readableModel(value) { return value.split('-').map((part) => part.length === 0 ? '' : part[0].toUpperCase() + part.slice(1)).filter(Boolean).join(' '); }
function excerpt(value) { return JSON.stringify((typeof value === 'string' ? value : JSON.stringify(value ?? '')).replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 512)); }
function nativeToken(value) { return Number.isSafeInteger(value) && value >= 0 ? value : null; }
function finiteDuration(value) { return Number.isFinite(value) && value >= 0 ? Math.round(value) : null; }
function requireText(value, field) { if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`${field} must be nonblank`); return value.trim(); }
function requireStringArray(value, field) { if (!Array.isArray(value) || value.length === 0) throw new TypeError(`${field} must be a nonempty array`); return [...new Set(value.map((entry) => requireText(entry, field)))]; }
function partialMessagesKey(config) { return String(config.executable ?? 'claude'); }
function nonnegativeInteger(value, field) { if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative safe integer`); return value; }
function truncate(text, max) { return text.length <= max ? text : `${text.slice(0, max - 3)}...`; }
function mergeUsage(target, usage) {
	if (usage === null || typeof usage !== 'object') return;
	// Streaming repeats cumulative counts per message, so the largest value seen is the call's total.
	for (const [key, field] of [['input', 'input_tokens'], ['cacheRead', 'cache_read_input_tokens'], ['cacheWrite', 'cache_creation_input_tokens'], ['output', 'output_tokens']]) {
		if (Number.isSafeInteger(usage[field]) && usage[field] > target[key]) target[key] = usage[field];
	}
}
function addUsage(target, usage) {
	target.calls += 1;
	for (const key of ['input', 'cacheRead', 'cacheWrite', 'output']) target[key] += usage[key];
}
function positiveInteger(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`); return value; }
