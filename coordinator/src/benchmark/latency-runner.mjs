import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';

import { createDynamicCoordinator } from '../dynamic-main.mjs';
import { DynamicAgentState } from '../agent-registry.mjs';
import { VirtualMinecraftBridge } from '../simulator/virtual-minecraft-bridge.mjs';
import { VirtualWorld } from '../simulator/virtual-world.mjs';
import { getSimulatorScenario } from '../simulator/simulator-scenarios.mjs';
import { createReplayProvider } from './provider-replay.mjs';
import { BenchmarkRecorder } from './benchmark-recorder.mjs';
import { summarizeBenchmark } from './benchmark-report.mjs';
import { PlanningScheduler } from '../planning-scheduler.mjs';
import { SystemSampler } from './system-sampler.mjs';
import { createLiveProviderFactory } from './live-provider-factories.mjs';
import { buildAuthoritativeScenarioOutcome, captureScenarioInitialSnapshot, compileScenarioDecision, runAuthoritativeScenarioSuccess } from './scenario-program.mjs';

const LOADS = Object.freeze([1, 4, 8, 16]);
const MODES = new Set(['instant', 'replay', 'live']);
const PROVIDERS = new Set(['codex', 'gemini', 'kimi', 'instant', 'replay']);
const MAX_TRIALS = 4_096;
const MAX_REPETITIONS = 1_024;

/** Strictly validate and freeze a latency matrix before executing it. */
export function normalizeLatencyMatrix(value) {
	if (!isRecord(value)) throw new TypeError('latency matrix must be an object');
	const version = positiveInt(value.version ?? 1, 'version');
	const benchmarkVersion = identifier(value.benchmarkVersion ?? 'latency-ab-v1', 'benchmarkVersion');
	const protocolVersion = positiveInt(value.protocolVersion ?? 2, 'protocolVersion');
	const fixedSeeds = normalizedSeeds(value.fixedSeeds);
	const agentLoads = normalizedLoads(value.agentLoads);
	if (agentLoads.length !== LOADS.length || LOADS.some((load) => !agentLoads.includes(load))) throw new TypeError('agentLoads must include 1, 4, 8, and 16');
	if (!Array.isArray(value.trials) || value.trials.length === 0 || value.trials.length > MAX_TRIALS) throw new TypeError('latency matrix trials must be a bounded non-empty array');
	const ids = new Set();
	const trials = value.trials.map((trial, index) => {
		if (!isRecord(trial)) throw new TypeError(`trial ${index} must be an object`);
		const id = identifier(trial.id, `trials[${index}].id`);
		if (ids.has(id)) throw new TypeError(`trial IDs must be unique: ${id}`);
		ids.add(id);
		const mode = identifier(trial.mode, `${id}.mode`).toLowerCase();
		if (!MODES.has(mode)) throw new TypeError(`${id}.mode must be instant, replay, or live`);
		const scenarioId = identifier(trial.scenarioId, `${id}.scenarioId`);
		const seed = safeInt(trial.seed, `${id}.seed`);
		if (!fixedSeeds.includes(seed)) throw new TypeError(`${id}.seed must be one of fixedSeeds`);
		const agentLoad = safeInt(trial.agentLoad, `${id}.agentLoad`);
		if (!LOADS.includes(agentLoad) || !agentLoads.includes(agentLoad)) throw new TypeError(`${id}.agentLoad must be one of 1, 4, 8, 16`);
		const providerProfile = normalizeProfile(trial.providerProfile, id);
		if (mode === 'instant' && providerProfile.provider !== 'instant') throw new TypeError(`${id}.instant trials require providerProfile.provider instant`);
		if (mode === 'live' && !['codex', 'gemini', 'kimi'].includes(providerProfile.provider)) throw new TypeError(`${id}.live trials require a Codex, Gemini, or Kimi provider`);
		const repetitions = positiveInt(trial.repetitions, `${id}.repetitions`);
		if (repetitions > MAX_REPETITIONS) throw new TypeError(`${id}.repetitions is too large`);
		return Object.freeze({
			id, mode, scenarioId, seed, agentLoad, providerProfile, repetitions,
			turnBudgetMs: positiveFinite(trial.turnBudgetMs, `${id}.turnBudgetMs`),
			trialBudgetMs: positiveFinite(trial.trialBudgetMs, `${id}.trialBudgetMs`),
			turnCap: positiveInt(trial.turnCap, `${id}.turnCap`),
			providerAvailabilityRequired: requireBoolean(trial.providerAvailabilityRequired, `${id}.providerAvailabilityRequired`),
		});
	});
	return deepFreeze({ version, benchmarkVersion, protocolVersion, fixedSeeds, agentLoads, trials });
}

/** Run the selected latency matrix without changing provider/model identity. */
export async function runLatencyMatrix(options = {}) {
	if (!isRecord(options)) throw new TypeError('latency runner options must be an object');
	const matrix = normalizeLatencyMatrix(await loadMatrix(options.matrix ?? options.matrixPath));
	const scenarioResolver = options.scenarioResolver ?? ((id) => getSimulatorScenario(id));
	if (typeof scenarioResolver !== 'function') throw new TypeError('scenarioResolver must be a function');
	const providerFactories = options.providerFactories ?? {};
	if (!isRecord(providerFactories)) throw new TypeError('providerFactories must be an object');
	const recorder = options.recorder ?? new BenchmarkRecorder({ maxEvents: options.maxEvents ?? 10_000 });
	const results = [];
	let requiredFailure = null;
	const cleanups = [];
	try {
		for (const trial of matrix.trials) {
			for (let repetition = 1; repetition <= trial.repetitions; repetition += 1) {
				const result = await runTrial({ ...options, matrix, trial, repetition, scenarioResolver, providerFactories, recorder });
				results.push(result);
				if (result.status === 'FAILED' && result.error?.code === 'PROVIDER_UNAVAILABLE' && trial.providerAvailabilityRequired) requiredFailure = result.error;
			}
		}
	} finally {
		for (const cleanup of cleanups.reverse()) await settle(cleanup);
	}
	const status = requiredFailure ? 'FAILED' : results.some((trial) => ['FAILED', 'TIMED_OUT'].includes(trial.status) || trial.cleanup?.ok === false) ? 'FAILED' : 'PASSED';
	const output = {
		status,
		matrix: { version: matrix.version, benchmarkVersion: matrix.benchmarkVersion, protocolVersion: matrix.protocolVersion },
		trials: results,
		cleanup: { ok: results.every((trial) => trial.cleanup?.ok !== false), activeActions: results.reduce((sum, trial) => sum + (trial.cleanup?.activeActions ?? 0), 0), listeners: results.reduce((sum, trial) => sum + (trial.cleanup?.listeners ?? 0), 0) },
		summary: recorder.snapshot ? recorder.snapshot().length : 0,
		benchmarkSummary: summarizeBenchmark(recorder.snapshot ? recorder.snapshot() : []),
	};
	if (requiredFailure) throw Object.assign(new Error(requiredFailure.message), requiredFailure, { result: output });
	if (options.artifactDirectory) await writeArtifacts(options.artifactDirectory, output, recorder, options.artifactFs);
	return deepFreeze(output);
}


async function runTrial({ matrix, trial, repetition, scenarioResolver, providerFactories, recorder, ...options }) {
	const startedAt = performance.now();
	const deadline = startedAt + trial.trialBudgetMs;
	let provider = null;
	let coordinator = null;
	let bridge = null;
	let world = null;
	let sampler = null;
	let initialSnapshots = new Map();
	const runtimeErrors = [];
	let result = null;
	const cleanup = [];
	const cleanupErrors = [];
	let turnCount = 0;
	let providerStopped = false;
	let providerCleanupRegistered = false;
	let trialRecorder = null;
	let scheduler = null;
	let systemSummary = null;
	const cleanupTimeoutMs = 1_000;
	try {
		const rawScenario = await runWithDeadline(() => scenarioResolver(trial.scenarioId, trial), deadline);
		if (!rawScenario) throw coded('SCENARIO_NOT_FOUND', `Unknown simulator scenario '${trial.scenarioId}'`);
		const scenario = cloneScenarioForLoad(rawScenario, trial.agentLoad, trial.seed);
		const defaultLiveFactory = trial.mode === 'live' && !providerFactories[trial.providerProfile.provider]
			? createLiveProviderFactory(trial.providerProfile.provider, options.liveProviderOptions ?? {})
			: null;
		const factory = providerFactories[trial.providerProfile.provider]
			?? (trial.mode === 'instant' ? defaultInstantFactory : null)
			?? (trial.mode === 'replay' ? defaultReplayFactory : null)
			?? defaultLiveFactory;
		if (factory === null && trial.mode !== 'live') throw coded('PROVIDER_UNAVAILABLE', `No factory is configured for ${trial.providerProfile.provider}`);
		if (factory !== null) provider = await runWithDeadline(() => factory(trial.providerProfile, { trial, repetition, mode: trial.mode, options, matrix, scenario: rawScenario, loadScenario: scenario, deadline }), deadline);
		const stopProvider = async () => {
			if (providerStopped) return;
			providerStopped = true;
			await provider?.stop?.();
		};
		const stopProviderAfterTimeout = () => {
			void withTimeout(Promise.resolve().then(stopProvider), cleanupTimeoutMs, 'CLEANUP_TIMEOUT').catch(() => {});
			return Promise.resolve();
		};
		const registerProviderCleanup = () => {
			if (!providerCleanupRegistered && provider && typeof provider.stop === 'function') {
				providerCleanupRegistered = true;
				cleanup.push(stopProvider);
			}
		};
		registerProviderCleanup();
		if (provider === null || provider === undefined || provider.available === false) {
			const error = coded('PROVIDER_UNAVAILABLE', boundedError(provider?.reason ?? 'provider is unavailable'));
			result = trialResult(trial, repetition, trial.providerAvailabilityRequired ? 'FAILED' : 'SKIPPED', error, startedAt, null, null);
			return result;
		}
		if (trial.mode === 'replay' && !provider.createAgent) provider = createReplayProvider({ ...options, ...trial.replay, recordings: options.replayRecordings, trialId: trial.id, providerProfile: trial.providerProfile, scenario: rawScenario, prompt: trial.prompt ?? options.replayPrompt ?? 'latency-replay-prompt', protocolVersion: matrix.protocolVersion });
		registerProviderCleanup();
		validateProviderIdentity(provider, trial);
		if (typeof provider.start === 'function') {
			try { await runWithDeadline(() => provider.start(), deadline); }
			catch (error) { await stopProviderAfterTimeout(); throw error; }
		}

		const records = scenario.agentIds.map((agentId) => ({ agentId, provider: internalProvider(trial.providerProfile.provider), model: trial.providerProfile.model, reasoningEffort: trial.providerProfile.reasoningEffort, serviceTier: trial.providerProfile.serviceTier, state: DynamicAgentState.IDLE, currentGoal: null, goalRevision: 0, queue: [] }));
		const virtualRecords = records.map((record) => ({ ...record, state: DynamicAgentState.STARTING, currentGoal: scenario.goal ?? `Complete ${trial.scenarioId}`, goalRevision: 1 }));
		world = new VirtualWorld(scenario.world, { scheduler: manualScheduler() });
		initialSnapshots = new Map(scenario.agentIds.map((agentId) => [agentId, captureScenarioInitialSnapshot({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, agentId })]));
		const virtual = new VirtualMinecraftBridge({ world, agentRecords: virtualRecords, serverInstanceId: `latency-${trial.id}-${repetition}` });
		bridge = new VirtualMinecraftBridgeAdapter(virtual, records);
		cleanup.push(() => bridge?.stop());
		trialRecorder = createTrialRecorder(recorder, { trial, repetition });
		scheduler = createTrialScheduler(trial, trialRecorder, options);
		cleanup.push(() => scheduler?.close?.('latency trial cleanup'));
		const providerService = factory !== null ? createInjectedProviderService(provider, trial, turnBudget(trial), deadline, () => ++turnCount, stopProvider, stopProviderAfterTimeout) : null;
		const config = benchmarkCoordinatorConfig(trial.agentLoad, options.planningConcurrency ?? trial.agentLoad);
		coordinator = createDynamicCoordinator(config, {
			bridge,
			scheduler,
			benchmarkRecorder: trialRecorder,
			...(providerService ? { providerService } : {}),
			setStatusInterval: () => null,
			clearStatusInterval: () => {},
			controlNow: () => world.timeMs,
			epochNow: () => world.timeMs,
		});
		const runtimeListener = (error) => { if (runtimeErrors.length < 64) runtimeErrors.push({ code: error?.code, message: boundedError(error?.message) }); };
		coordinator.on('runtimeError', runtimeListener);
		cleanup.push(() => coordinator?.off?.('runtimeError', runtimeListener));
		cleanup.push(() => coordinator?.stop());
		const samplerOptions = options.systemSamplerOptions ?? {};
		sampler = options.systemSampler
			?? options.systemSamplerFactory?.({ ...samplerOptions, schedulerReader: options.schedulerReader ?? samplerOptions.schedulerReader ?? (() => schedulerSnapshot(scheduler)), processReader: options.processReader ?? samplerOptions.processReader, childProcessReader: options.childProcessReader ?? samplerOptions.childProcessReader ?? (() => null) })
			?? new SystemSampler({ ...samplerOptions, schedulerReader: options.schedulerReader ?? samplerOptions.schedulerReader ?? (() => schedulerSnapshot(scheduler)), processReader: options.processReader ?? samplerOptions.processReader, childProcessReader: options.childProcessReader ?? samplerOptions.childProcessReader ?? (() => null) });
		sampler.start();
		sampler.sample?.();
		cleanup.push(() => sampler?.stop());
		const reconciled = new Promise((resolve) => coordinator.once('reconciled', resolve));
		await runWithDeadline(() => coordinator.start(), deadline);
		await runWithDeadline(() => reconciled, deadline);
		for (const agentId of scenario.agentIds) bridge.startAgent(agentId, scenario.goal ?? `Complete ${trial.scenarioId}`);
		await Promise.resolve();
		for (const agentId of scenario.agentIds) await runWithDeadline(() => bridge.publish(agentId), deadline);
		await runWithDeadline(() => runVirtualTicks({ world, bridge, coordinator, records, cap: trial.turnCap, deadline }), deadline);
		const statuses = records.map((record) => coordinator.registry.get(record.agentId)?.state);
		const runtimeError = runtimeErrors.find((entry) => entry.code);
		const runtimeTimedOut = isTimeoutErrorCode(runtimeError?.code);
		const registryStatus = runtimeTimedOut ? 'TIMED_OUT' : statuses.every((value) => value === DynamicAgentState.COMPLETED) ? 'PASSED' : statuses.some((value) => value === DynamicAgentState.ERROR) ? 'FAILED' : 'TIMED_OUT';
		const scenarioRequired = typeof rawScenario.success === 'function';
		const scenarioOutcomes = scenarioRequired ? scenario.agentIds.map((agentId) => buildAuthoritativeScenarioOutcome({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, actionCommands: authoritativeCommands(virtual, agentId), actionResults: authoritativeResults(virtual, agentId), initialSnapshot: initialSnapshots.get(agentId) })) : [];
		const scenarioPassed = !scenarioRequired || scenario.agentIds.every((agentId) => runAuthoritativeScenarioSuccess({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, bridge: virtual, actionCommands: authoritativeCommands(virtual, agentId), actionResults: authoritativeResults(virtual, agentId), initialSnapshot: initialSnapshots.get(agentId) }));
		const scenarioDigest = scenarioOutcomes.length > 0 ? hash(scenarioOutcomes.map(normalizeScenarioOutcome)) : null;
		const status = runtimeError ? (runtimeTimedOut ? 'TIMED_OUT' : 'FAILED') : registryStatus === 'PASSED' && scenarioPassed ? 'PASSED' : registryStatus === 'TIMED_OUT' ? 'TIMED_OUT' : 'FAILED';
		const error = runtimeError ? coded(runtimeError.code, runtimeError.message) : status === 'TIMED_OUT' ? coded('TURN_CAP', `trial exceeded the ${trial.turnCap}-turn cap`) : !scenarioPassed ? coded('SCENARIO_ASSERTION_FAILED', 'authoritative scenario outcome did not satisfy its success predicate') : null;
		const outcomeHash = hash({ trialId: trial.id, repetition, status, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, providerProfile: trial.providerProfile, statuses, turnCount, scenarioDigest });
		result = trialResult(trial, repetition, status, error, startedAt, outcomeHash, cleanupSnapshot(bridge, sampler));
		result.benchmark = { eventCount: trialRecorder?.count ?? 0 };
		result.debug = { statuses, turnCount, runtimeErrors, scenarioPassed, scenarioDigest, scenarioEvidence: scenarioEvidence(virtual, scenario.agentIds) };
	} catch (error) {
		if (error?.code === 'TRIAL_TIMEOUT') await new Promise((resolve) => setImmediate(resolve));
		let typed = normalizeTrialError(error);
		if (typed.code === 'TRIAL_TIMEOUT' && runtimeErrors.length > 0) {
			const providerError = runtimeErrors.find((entry) => ['TURN_TIMEOUT', 'PROVIDER_EXIT', 'PROVIDER_UNAVAILABLE', 'TURN_CAP', 'REPLAY_IDENTITY_MISMATCH', 'REPLAY_DECISION_MISMATCH'].includes(entry.code));
			const runtimeError = providerError ?? runtimeErrors.find((entry) => entry.code && !['TRIAL_TIMEOUT'].includes(entry.code));
			if (runtimeError) typed = coded(runtimeError.code, runtimeError.message);
		}
		result = trialResult(trial, repetition, isTimeoutErrorCode(typed.code) ? 'TIMED_OUT' : 'FAILED', typed, startedAt, null, cleanupSnapshot(bridge, sampler));
		result.benchmark = { eventCount: trialRecorder?.count ?? 0 };
		result.debug = { runtimeErrors, turnCount };
	} finally {
		try { sampler?.sample?.(); } catch {}
		for (const close of cleanup.reverse()) {
			try { await withTimeout(Promise.resolve().then(() => close?.()), cleanupTimeoutMs, 'CLEANUP_TIMEOUT'); }
			catch (error) { cleanupErrors.push({ code: 'CLEANUP_FAILED', message: boundedError(error) }); }
		}
		if (result !== null) {
			try { systemSummary = summarizeTrialSystem(sampler); } catch { systemSummary = null; }
			result.cleanup = cleanupSnapshot(bridge, sampler, cleanupErrors);
			result.systemSummary = systemSummary;
			if (!result.cleanup.ok) {
				result.status = 'FAILED';
				result.error = { code: 'CLEANUP_FAILED', message: 'trial cleanup left resources or reported an error' };
			}
		}
	}
	return result;
}

async function runVirtualTicks({ world, bridge, coordinator, records, cap, deadline }) {
	for (let tick = 0; tick < 10_000; tick += 1) {
		world.tick();
		await new Promise((resolve) => setImmediate(resolve));
		const states = records.map((record) => coordinator.registry.get(record.agentId)?.state);
		if (states.every((state) => [DynamicAgentState.COMPLETED, DynamicAgentState.ERROR, DynamicAgentState.PAUSED].includes(state))) return;
		if (performance.now() > deadline) throw coded('TRIAL_TIMEOUT', 'trial budget elapsed');
	}
	throw coded('TRIAL_TIMEOUT', 'trial did not reach a terminal state before its budget');
}

class VirtualMinecraftBridgeAdapter extends EventEmitter {
	#virtual;
	#records;
	#relays = [];
	#pendingObservations = new Map();
	#started = false;
	constructor(virtual, records) {
		super(); this.#virtual = virtual; this.#records = records;
		for (const [event, type] of [['observation', 'observation'], ['progress', 'action_progress'], ['result', 'action_result']]) {
			const relay = (entry) => {
				const message = { agentId: entry.envelope.agentId, payload: entry.envelope.payload };
				if (type === 'observation' && entry.envelope.payload.attention === true && entry.envelope.payload.lastResult?.present === true) {
					this.#pendingObservations.set(message.agentId, message);
					return;
				}
				this.emit(type, message);
				if (type === 'action_result') {
					const pending = this.#pendingObservations.get(message.agentId);
					this.#pendingObservations.delete(message.agentId);
					if (pending) this.emit('observation', pending);
				}
			};
			virtual.on(event, relay);
			this.#relays.push([event, relay]);
		}
	}
	start() { this.#started = true; queueMicrotask(() => this.emit('ready', { serverInstanceId: this.#virtual.serverInstanceId, registry: this.#records })); }
	stop() { this.#started = false; for (const [event, relay] of this.#relays) this.#virtual.off(event, relay); this.#relays = []; this.#pendingObservations.clear(); this.#virtual.stop(); }
	get ready() { return this.#started; }
	get relayCount() { return this.#relays.length; }
	get pendingObservationCount() { return this.#pendingObservations.size; }
	get listenerResidue() { return this.eventNames().reduce((count, event) => count + this.listenerCount(event), 0); }
	send(type, agentId, payload) { if (agentId === 'server') return Promise.resolve(); return this.#virtual.send(type, agentId, payload); }
	publish(agentId, options) { return this.#virtual.publish(agentId, options); }
	flush() { return this.#virtual.flush(); }
	startAgent(agentId, goal) { this.emit('goal_control', { agentId, payload: { operation: 'start', goalRevision: 1, goal, updatedAtEpochMs: 0 } }); }
	get activeActionIds() { return this.#virtual.activeActionIds; }
}

function createInjectedProviderService(provider, trial, budget, deadline, incrementTurn, stopProvider = async () => provider.stop?.(), stopProviderAfterTimeout = stopProvider) {
	const sessions = new Map();
	const agentTurns = new Map();
	return {
		catalog: { stale: false, async refresh() { return { models: [] }; }, assertSupported() {} },
		async start() {}, async stop() { await stopProvider(); sessions.clear(); },
		async bootstrapCatalog() { return { models: [] }; },
		async createAgent(record) {
			const profile = { ...record, ...trial.providerProfile, provider: trial.providerProfile.provider };
			let session;
			try { session = await runWithDeadline(() => provider.createAgent(profile), deadline); }
			catch (error) { if (error?.code === 'TRIAL_TIMEOUT') await stopProviderAfterTimeout(); throw error; }
			if (!session || typeof session.decide !== 'function') throw coded('PROVIDER_EXIT', 'provider returned no decision session');
			const wrapped = { ...session, async decide(input, options = {}) {
				const turns = agentTurns.get(record.agentId) ?? 0;
				if (turns >= trial.turnCap) throw coded('TURN_CAP', 'turn cap exceeded');
				agentTurns.set(record.agentId, turns + 1);
				incrementTurn();
				const remaining = deadline - performance.now();
				if (remaining <= 0) { await stopProviderAfterTimeout(); throw coded('TRIAL_TIMEOUT', 'trial deadline elapsed before provider turn'); }
				try {
					return await withTimeout(Promise.resolve().then(() => session.decide(input, options)), Math.min(budget, remaining), remaining <= budget ? 'TRIAL_TIMEOUT' : 'TURN_TIMEOUT');
				} catch (error) {
					if (error?.code === 'TRIAL_TIMEOUT' || error?.code === 'TURN_TIMEOUT') await stopProviderAfterTimeout();
					throw error;
				}
			} };
			sessions.set(record.agentId, wrapped); return wrapped;
		},
		getAgent(agentId) { return sessions.get(agentId) ?? null; },
		async removeAgent(agentId) { sessions.delete(agentId); return true; },
		async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: { models: [] } }; },
	};
}

function defaultInstantFactory(_profile, context = {}) {
	const manifests = context.loadScenario?.agentManifests ?? {};
	const defaultManifest = context.scenario;
	const fallbackDecision = defaultManifest?.commands?.length
		? compileScenarioDecision(defaultManifest)
		: { summary: 'deterministic wait', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");' };
	const decisions = new Map(Object.entries(manifests).map(([agentId, manifest]) => [agentId, manifest?.commands?.length ? compileScenarioDecision(manifest) : fallbackDecision]));
	const initialized = new Set();
	return { available: true, provider: 'instant', synthetic: true, async createAgent(record) { const decision = decisions.get(record.agentId) ?? fallbackDecision; return { async setGoalRevision() {}, async decide() { if (!initialized.has(record.agentId)) { initialized.add(record.agentId); return decision; } return { directive: 'continue', summary: 'continue deterministic program' }; } }; } };
}

async function defaultReplayFactory(profile, context = {}) {
	const recordings = context.options?.replayRecordings ?? context.replayRecordings;
	if (!Array.isArray(recordings) || recordings.length === 0) return { available: false, reason: 'no replay recordings were supplied' };
	return createReplayProvider({
		recordings,
		trialId: context.trial?.id ?? context.trialId,
		prompt: context.trial?.prompt ?? context.options?.replayPrompt ?? 'latency-replay-prompt',
		providerProfile: profile,
		scenario: context.scenario ?? context.trial?.scenario ?? {},
		protocolVersion: context.matrix?.protocolVersion ?? 2,
	});
}

function benchmarkCoordinatorConfig(agentCap, planningConcurrency = agentCap) {
	const effectiveConcurrency = Math.max(1, Math.min(agentCap, planningConcurrency));
	return { bridge: { secret: 'latency-fixture' }, codex: { cwd: process.cwd() }, limits: { agentCap, goalQueueCap: 8, planningConcurrency: effectiveConcurrency, invalidDecisionRetries: 0 }, workspaceRoot: path.join(os.tmpdir(), 'arena-latency-workspaces') };
}

function cloneScenarioForLoad(source, load, seed) {
	const sourceScenario = clonePreservingFunctions(source);
	const sourceWorld = structuredClone(source.world ?? source);
	const sourceAgents = sourceWorld.agents ?? sourceWorld.players ?? {};
	const sourceAgentIds = Object.keys(sourceAgents);
	const firstAgentId = source.agentId ?? sourceAgentIds[0];
	const firstAgent = sourceAgents[firstAgentId] ?? sourceAgents[sourceAgentIds[0]];
	if (!firstAgent) throw new TypeError('scenario must include a world agent');
	const agentIds = Array.from({ length: load }, (_, index) => index === 0 ? firstAgentId : `agent-${index + 1}`);
	const agentManifests = {};
	const worldBlocks = [];
	const worldEntities = [];
	const worldItems = [];
	const worldAgents = {};
	for (let index = 0; index < agentIds.length; index += 1) {
		const agentId = agentIds[index];
		const offset = index * 64;
		for (const block of sourceWorld.blocks ?? []) worldBlocks.push(translatePositioned(block, offset));
		for (const entity of sourceWorld.entities ?? []) worldEntities.push({ ...translatePositioned(entity, offset), ...(index === 0 ? {} : { id: `${entity.id ?? entity.uuid ?? 'entity'}-${index + 1}` }) });
		for (const item of sourceWorld.items ?? sourceWorld.drops ?? []) worldItems.push({ ...translatePositioned(item, offset), ...(index === 0 ? {} : { id: `${item.id ?? item.uuid ?? 'item'}-${index + 1}` }) });
		worldAgents[agentId] = translatePositioned(structuredClone(firstAgent), offset);
		const manifest = clonePreservingFunctions(sourceScenario);
		manifest.agentId = agentId;
		manifest.commands = (sourceScenario.commands ?? []).map((command) => ({ ...structuredClone(command), arguments: translateArguments(command.arguments ?? command.args, offset) }));
		manifest.expected = translateExpected(sourceScenario.expected, offset);
		manifest.world = { ...structuredClone(sourceWorld), seed, agents: { [agentId]: structuredClone(worldAgents[agentId]) }, blocks: sourceWorld.blocks?.map((block) => translatePositioned(block, offset)) ?? [] };
		agentManifests[agentId] = manifest;
	}
	if (sourceAgentIds.length > 1) {
		for (const id of sourceAgentIds.slice(1)) worldAgents[id] = structuredClone(sourceAgents[id]);
	}
	const world = { ...sourceWorld, seed, agents: worldAgents, blocks: worldBlocks, entities: worldEntities, items: worldItems };
	return { ...sourceScenario, agentIds, agentManifests, goal: sourceScenario.goal ?? 'Complete the deterministic scenario.', world };
}

function clonePreservingFunctions(value) {
	if (value === null || typeof value !== 'object') return value;
	return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, typeof child === 'function' ? child : structuredClone(child)]));
}

function translatePositioned(value, offset) {
	const result = structuredClone(value);
	if (result && typeof result === 'object') {
		if (Number.isFinite(result.x)) result.x += offset;
		if (Number.isFinite(result.z)) result.z += offset;
		if (result.position && typeof result.position === 'object') result.position = translatePositioned(result.position, offset);
		if (result.checkpoint && typeof result.checkpoint === 'object') result.checkpoint = translatePositioned(result.checkpoint, offset);
	}
	return result;
}

function translateArguments(value, offset) {
	if (value === undefined) return undefined;
	const result = structuredClone(value);
	if (result && typeof result === 'object') {
		if (Number.isFinite(result.x)) result.x += offset;
		if (Number.isFinite(result.z)) result.z += offset;
	}
	return result;
}

function translateExpected(value, offset) {
	if (!value || typeof value !== 'object') return value;
	const result = structuredClone(value);
	for (const key of ['position', 'tableBlock', 'obstacle', 'checkpoint']) if (result[key]) result[key] = translatePositioned(result[key], offset);
	if (Array.isArray(result.waypoints)) result.waypoints = result.waypoints.map((point) => translatePositioned(point, offset));
	return result;
}

function normalizeProfile(value, id) {
	if (!isRecord(value)) throw new TypeError(`${id}.providerProfile must be an object`);
	const provider = identifier(value.provider, `${id}.providerProfile.provider`).toLowerCase();
	if (!PROVIDERS.has(provider)) throw new TypeError(`${id}.providerProfile.provider is unsupported`);
	return Object.freeze({ provider, model: identifier(value.model, `${id}.providerProfile.model`), reasoningEffort: identifier(value.reasoningEffort, `${id}.providerProfile.reasoningEffort`), serviceTier: identifier(value.serviceTier, `${id}.providerProfile.serviceTier`) });
}
function normalizedSeeds(value) { if (!Array.isArray(value) || value.length === 0) throw new TypeError('fixedSeeds must be a non-empty array'); return [...new Set(value.map((seed) => safeInt(seed, 'fixedSeeds')))].sort((a, b) => a - b); }
function normalizedLoads(value) { if (!Array.isArray(value) || value.length === 0 || new Set(value).size !== value.length || value.some((load) => !LOADS.includes(load))) throw new TypeError('agentLoads must contain unique values from 1, 4, 8, 16'); return [...value]; }
async function loadMatrix(value) { if (value === undefined) return JSON.parse(await readFile(new URL('../../config/latency-matrix.json', import.meta.url), 'utf8')); if (typeof value === 'string') return JSON.parse(await readFile(value, 'utf8')); return value; }
function turnBudget(trial) { return trial.turnBudgetMs; }
function runWithDeadline(task, deadline, code = 'TRIAL_TIMEOUT') {
	const remaining = deadline - performance.now();
	if (remaining <= 0) return Promise.reject(coded(code, 'trial deadline elapsed'));
	return withTimeout(Promise.resolve().then(task), remaining, code);
}
function createTrialRecorder(recorder, { trial, repetition }) {
	let count = 0;
	return {
		record(stage, context = {}, fields = {}) {
			const row = recorder.record(stage, { trialId: trial.id, repetition, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, mode: trial.mode, synthetic: trial.mode !== 'live', provider: trial.providerProfile.provider, model: trial.providerProfile.model, reasoningEffort: trial.providerProfile.reasoningEffort, serviceTier: trial.providerProfile.serviceTier, ...context }, fields);
			if (row !== null) count += 1;
			return row;
		},
		get count() { return count; },
	};
}
function createTrialScheduler(trial, recorder, options) {
	if (options.planningScheduler !== undefined) return options.planningScheduler;
	if (typeof options.planningSchedulerFactory === 'function') return options.planningSchedulerFactory({ trial, recorder });
	const configuredConcurrency = options.planningConcurrency ?? trial.agentLoad;
	const maxConcurrent = Math.max(1, Math.min(trial.agentLoad, configuredConcurrency));
	return new PlanningScheduler({ maxConcurrent, maxPending: Math.max(0, trial.agentLoad - maxConcurrent), benchmarkRecorder: recorder });
}
function schedulerSnapshot(scheduler) { return { active: scheduler?.activeCount ?? null, pending: scheduler?.pendingCount ?? null }; }
function summarizeTrialSystem(sampler) {
	const summary = sampler?.summary?.() ?? null;
	if (summary === null || typeof summary !== 'object' || Array.isArray(summary)) return summary;
	const samples = typeof sampler?.snapshot === 'function' ? sampler.snapshot() : [];
	return { ...summary, immediateSample: samples[0] ?? null, finalSample: samples.at(-1) ?? null };
}
function validateProviderIdentity(provider, trial) {
	const expected = trial.providerProfile;
	if (trial.mode !== 'live') {
		if (provider?.provider !== undefined && provider.provider !== expected.provider) throw coded('PROVIDER_MISMATCH', `Provider '${String(provider.provider)}' does not match selected '${expected.provider}'`);
		if (provider?.synthetic === false) throw coded('PROVIDER_MISMATCH', `${trial.mode} provider must be synthetic`);
		return { provider: provider?.provider ?? expected.provider, synthetic: true };
	}
	if (provider === null || typeof provider !== 'object' || Array.isArray(provider) || typeof provider.provider !== 'string' || provider.provider.trim().length === 0) {
		throw coded('PROVIDER_MISMATCH', `Live provider identity is missing; expected '${expected.provider}'`);
	}
	if (provider.provider !== expected.provider) throw coded('PROVIDER_MISMATCH', `Provider '${String(provider.provider)}' does not match selected '${expected.provider}'`);
	for (const field of ['model', 'reasoningEffort', 'serviceTier']) {
		if (provider[field] !== undefined && provider[field] !== expected[field]) throw coded('PROVIDER_MISMATCH', `Live provider ${field} does not match selected profile`);
	}
	if (provider.providerProfile !== undefined) {
		if (!isRecord(provider.providerProfile)) throw coded('PROVIDER_MISMATCH', 'Live provider profile identity is invalid');
		for (const field of ['provider', 'model', 'reasoningEffort', 'serviceTier']) {
			if (provider.providerProfile[field] !== expected[field]) throw coded('PROVIDER_MISMATCH', `Live provider profile ${field} does not match selected profile`);
		}
	}
	return { provider: expected.provider, synthetic: false };
}
function isTimeoutErrorCode(code) { return typeof code === 'string' && (code === 'TURN_CAP' || code.includes('TIMEOUT')); }
function internalProvider(provider) { return PROVIDERS.has(provider) && ['codex', 'gemini', 'kimi'].includes(provider) ? provider : 'codex'; }
function manualScheduler() { const handles = new Set(); return { setInterval(callback) { const handle = { callback }; handles.add(handle); return handle; }, clearInterval(handle) { handles.delete(handle); } }; }
function authoritativeCommands(virtual, agentId) { return (virtual?.sent ?? []).filter((event) => event?.type === 'action_command' && event.agentId === agentId).map((event) => ({ ...event.payload, agentId })); }
function authoritativeResults(virtual, agentId) { return (virtual?.events ?? []).filter((event) => event?.type === 'result' && event.envelope?.agentId === agentId).map((event) => ({ ...event.envelope.payload, agentId })); }
function scenarioEvidence(virtual, agentIds) {
	const counts = new Map(agentIds.map((agentId) => [agentId, { actionResults: 0, succeeded: 0, failed: 0, cancelled: 0 }]));
	for (const event of virtual?.events ?? []) {
		if (event?.type !== 'result') continue;
		const entry = counts.get(event.envelope?.agentId);
		if (!entry) continue;
		entry.actionResults += 1;
		if (event.envelope.payload.state === 'SUCCEEDED') entry.succeeded += 1;
		if (event.envelope.payload.state === 'CANCELLED') entry.cancelled += 1;
		if (event.envelope.payload.state === 'FAILED' || event.envelope.payload.state === 'TIMED_OUT') entry.failed += 1;
	}
	return agentIds.map((agentId) => ({ agentId, ...counts.get(agentId) }));
}
function normalizeScenarioOutcome(outcome) {
	const state = outcome?.state ?? {};
	return {
		commandMappingValid: outcome?.commandMapping?.valid === true,
		results: (outcome?.results ?? []).slice(0, 32).map((result) => ({ actionId: boundedText(result.commandId ?? result.actionId, 128), actionType: boundedText(result.actionType, 64), state: boundedText(result.state, 32), reasonCode: boundedText(result.reasonCode, 64) })),
		state: {
			position: finitePosition(state.position),
			health: Number.isFinite(state.health) ? state.health : null,
			inventoryBefore: normalizeItems(state.inventoryBefore),
			inventoryAfter: normalizeItems(state.inventoryAfter),
			block: normalizeRecord(state.block),
			target: normalizeRecord(state.target),
			obstacle: normalizeRecord(state.obstacle),
			waypoints: Array.isArray(state.waypoints) ? state.waypoints.slice(0, 32).map(finitePosition) : [],
			hazard: normalizeRecord(state.hazard),
			reaction: boundedText(state.reaction, 64),
			events: Array.isArray(state.events) ? state.events.slice(0, 32).map(normalizeRecord) : [],
		},
	};
}
function normalizeItems(items) { return Array.isArray(items) ? items.slice(0, 256).map((item) => ({ itemId: boundedText(item?.itemId, 128), count: Number.isSafeInteger(item?.count) ? item.count : null, slot: Number.isSafeInteger(item?.slot) ? item.slot : null })) : []; }
function normalizeRecord(value) { return value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).filter(([key]) => ['id', 'entityId', 'blockId', 'desiredState', 'eventId', 'type', 'kind', 'sourceId', 'recipientId', 'wakeAcknowledged', 'processed', 'dead', 'health', 'x', 'y', 'z', 'position'].includes(key)).slice(0, 32).map(([key, child]) => [key, key === 'position' ? finitePosition(child) : typeof child === 'string' ? boundedText(child, 128) : child])) : null; }
function finitePosition(value) { return value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z) ? { x: value.x, y: value.y, z: value.z } : null; }
function boundedText(value, maximum) { return typeof value === 'string' ? value.slice(0, maximum) : null; }
function cleanupSnapshot(bridge, sampler, cleanupErrors = []) {
	const activeActions = bridge?.activeActionIds?.length ?? 0;
	const listeners = bridge?.listenerResidue ?? 0;
	const relays = bridge?.relayCount ?? 0;
	const pendingObservations = bridge?.pendingObservationCount ?? 0;
	const samplerActive = sampler?.active === true;
	const samplerErrors = sampler?.errors?.length ?? 0;
	return { ok: activeActions === 0 && listeners === 0 && relays === 0 && pendingObservations === 0 && !samplerActive && cleanupErrors.length === 0, activeActions, listeners, relays, pendingObservations, samplerActive, samplerErrors, errors: cleanupErrors.slice(0, 16) };
}
function trialResult(trial, repetition, status, error, startedAt, outcomeHash, cleanup) { return { trialId: trial.id, repetition, status, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, mode: trial.mode, providerProfile: trial.providerProfile, providerIdentity: { provider: trial.providerProfile.provider, synthetic: trial.mode !== 'live' }, ...(outcomeHash ? { outcomeHash } : {}), ...(error ? { error: { code: error.code, message: boundedError(error.message) } } : {}), cleanup: cleanup ?? { ok: true, activeActions: 0, listeners: 0 }, durationMs: Math.max(0, Math.round(performance.now() - startedAt)) }; }
function cleanupError() { return null; }
async function writeArtifacts(directory, output, recorder, artifactFs = {}) {
	const fs = { mkdir, rename, rm, writeFile, ...artifactFs };
	const target = path.resolve(directory);
	const temp = `${target}.tmp-${process.pid}-${Date.now()}`;
	const backup = `${target}.bak-${process.pid}-${Date.now()}`;
	await fs.mkdir(temp, { recursive: true });
	let preserveBackup = false;
	try {
		await fs.writeFile(path.join(temp, 'latency-manifest.json'), `${JSON.stringify(redactOutput(output), null, 2)}\n`);
		if (recorder?.writeArtifacts) await recorder.writeArtifacts(temp);
		let backedUp = false;
		try { await fs.rename(target, backup); backedUp = true; } catch (error) { if (error.code !== 'ENOENT') throw error; }
		try {
			await fs.rename(temp, target);
			if (backedUp) { await fs.rm(backup, { recursive: true, force: true }); backedUp = false; }
		} catch (error) {
			try { await fs.rm(target, { recursive: true, force: true }); }
			catch (removeError) { preserveBackup = backedUp; throw coded('ARTIFACT_ROLLBACK_FAILED', boundedError(removeError)); }
			if (backedUp) {
				try { await fs.rename(backup, target); backedUp = false; }
				catch (restoreError) { preserveBackup = true; throw coded('ARTIFACT_ROLLBACK_FAILED', boundedError(restoreError)); }
			}
			throw error;
		}
	} finally {
		await fs.rm(temp, { recursive: true, force: true });
		if (!preserveBackup) await fs.rm(backup, { recursive: true, force: true });
	}
}
function redactOutput(output) { return { ...output, trials: output.trials.map((trial) => ({ ...trial, error: trial.error ? { code: trial.error.code, message: boundedError(trial.error.message) } : undefined })) }; }
function normalizeTrialError(error) {
	if (error?.code) return coded(error.code, boundedError(error.message));
	const message = boundedError(error);
	if (/provider|process|spawn|exit|transport/i.test(message)) return coded('PROVIDER_EXIT', message);
	return coded('TRIAL_FAILED', message);
}
function boundedError(value) { return String(value ?? 'unknown error').replace(/(?:api[_-]?key|token|secret|password|authorization)\s*[:=]\s*\S+/gi, '$1=[REDACTED]').slice(0, 512); }
function withTimeout(promise, milliseconds, code) {
	let handle;
	const timeout = new Promise((_, reject) => { handle = setTimeout(() => reject(coded(code, `${code.toLowerCase()} after ${milliseconds}ms`)), milliseconds); });
	return Promise.race([promise, timeout]).finally(() => clearTimeout(handle));
}
async function settle(callback) { try { await callback?.(); } catch {} }
function hash(value) { return `sha256:${createHash('sha256').update(JSON.stringify(value)).digest('hex')}`; }
function coded(code, message) { return Object.assign(new Error(message), { code }); }
function isRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function identifier(value, field) { if (typeof value !== 'string' || value.trim() === '' || value.length > 128) throw new TypeError(`${field} must be a bounded nonblank string`); return value.trim(); }
function safeInt(value, field) { if (!Number.isSafeInteger(value)) throw new TypeError(`${field} must be a safe integer`); return value; }
function positiveInt(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`); return value; }
function positiveFinite(value, field) { if (!Number.isFinite(value) || value <= 0) throw new TypeError(`${field} must be a positive finite number`); return value; }
function requireBoolean(value, field) { if (typeof value !== 'boolean') throw new TypeError(`${field} must be a boolean`); return value; }
function deepFreeze(value, seen = new WeakSet()) { if (value === null || typeof value !== 'object' || seen.has(value)) return value; seen.add(value); for (const child of Object.values(value)) deepFreeze(child, seen); return Object.freeze(value); }
