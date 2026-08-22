import { runLatencyMatrix, normalizeLatencyMatrix } from './latency-runner.mjs';
import { compileScenarioDecision } from './scenario-program.mjs';
import { createReplayRecord } from './provider-replay.mjs';

const DEFAULT_MAX_TRIALS = 64;
const DEFAULT_MAX_TURNS = 16;
const DEFAULT_MAX_RECORDS = 4_096;
const DEFAULT_MAX_DELAY_MS = 300_000;
const LOADS = new Set([1, 4, 8, 16]);

/**
 * Capture deterministic decisions through the production runner and return
 * private replay records containing hashes, decisions, and timing only.
 */
export async function generateReplayRecordings(input, options = {}) {
	const request = normalizeRequest(input, options);
	const matrix = normalizeReplayMatrix(request.matrix);
	const limits = normalizeLimits(request);
	const plannedRecords = matrix.trials.reduce((total, trial) => total + trial.agentLoad, 0);
	if (matrix.trials.length > limits.maxTrials) throw captureError('REPLAY_FIXTURE_LIMIT', `replay fixture trial count exceeds ${limits.maxTrials}`);
	if (matrix.trials.some((trial) => trial.repetitions !== 1)) throw captureError('REPLAY_FIXTURE_REPETITIONS', 'replay fixture generation requires repetitions: 1 for every trial');
	if (plannedRecords > limits.maxRecords) throw captureError('REPLAY_FIXTURE_LIMIT', `replay fixture record count exceeds ${limits.maxRecords}`);

	const captures = new Map();
	const providerNames = new Set(matrix.trials.map((trial) => trial.providerProfile.provider));
	const providerFactories = Object.fromEntries([...providerNames].map((providerName) => [
		providerName,
		(profile, context) => captureProvider({ profile, context, captures, maxTurns: limits.maxTurns }),
	]));
	const runnerOptions = {
		...request,
		matrix,
		providerFactories: { ...(request.providerFactories ?? {}), ...providerFactories },
		artifactDirectory: null,
	};
	delete runnerOptions.delayMs;
	delete runnerOptions.delayForTurn;
	delete runnerOptions.maxTrials;
	delete runnerOptions.maxTurns;
	delete runnerOptions.maxRecords;
	delete runnerOptions.maxDelayMs;
	let run;
	try {
		run = await runLatencyMatrix(runnerOptions);
	} catch (error) {
		clearCaptures(captures);
		throw error;
	}
	if (run.status !== 'PASSED' || run.trials.some((trial) => trial.status !== 'PASSED')) {
		clearCaptures(captures);
		throw captureError('REPLAY_FIXTURE_CAPTURE_FAILED', 'replay fixture capture did not complete successfully', run);
	}

	const recordings = [];
	try {
		for (const trial of matrix.trials) {
			const capture = captures.get(captureKey(trial.id, 1));
			if (!capture) throw captureError('REPLAY_FIXTURE_CAPTURE_MISSING', `no capture was produced for '${trial.id}'`);
			for (const agentId of capture.agentIds) {
				const turns = capture.turns.get(agentId) ?? [];
				if (turns.length === 0) throw captureError('REPLAY_FIXTURE_CAPTURE_MISSING', `no planner turn was captured for '${trial.id}/${agentId}'`);
				if (turns.length > limits.maxTurns) throw captureError('REPLAY_FIXTURE_LIMIT', `planner turn count exceeds ${limits.maxTurns}`);
				const delaysMs = turns.map((turn, turnIndex) => resolveDelay(request, {
					trial,
					repetition: 1,
					agentId,
					turnIndex,
				}, limits.maxDelayMs));
				recordings.push(createReplayRecord({
					trialId: trial.id,
					agentId,
					agentLoad: trial.agentLoad,
					prompts: turns.map((turn) => turn.input),
					decisions: turns.map((turn) => turn.decision),
					delaysMs,
					providerProfile: trial.providerProfile,
					scenario: capture.scenario,
					protocolVersion: matrix.protocolVersion,
				}));
			}
		}
		if (recordings.length > limits.maxRecords) throw captureError('REPLAY_FIXTURE_LIMIT', `replay fixture record count exceeds ${limits.maxRecords}`);
	} finally {
		clearCaptures(captures);
	}
	return deepFreeze({
		recordings,
		trials: run.trials.map((trial) => ({ trialId: trial.trialId, repetition: trial.repetition, agentLoad: trial.agentLoad, status: trial.status })),
	});
}

export const generateReplayRecords = generateReplayRecordings;

function captureProvider({ profile, context, captures, maxTurns }) {
	const trial = context?.trial;
	const repetition = context?.repetition ?? 1;
	if (!trial || typeof trial.id !== 'string') throw new TypeError('capture provider requires a trial context');
	const loadScenario = context.loadScenario;
	const agentManifests = loadScenario?.agentManifests;
	if (!loadScenario || !loadScenario.agentIds || !agentManifests) throw captureError('REPLAY_FIXTURE_MANIFEST_MISSING', `load-translated manifests are missing for '${trial.id}'`);
	const decisions = new Map(loadScenario.agentIds.map((agentId) => {
		const manifest = agentManifests[agentId];
		if (!manifest) throw captureError('REPLAY_FIXTURE_MANIFEST_MISSING', `manifest is missing for '${trial.id}/${agentId}'`);
		return [agentId, compileScenarioDecision(manifest)];
	}));
	const initialized = new Set();
	const key = captureKey(trial.id, repetition);
	const capture = { trialId: trial.id, repetition, scenario: context.scenario, agentIds: [...loadScenario.agentIds], turns: new Map() };
	captures.set(key, capture);
	return {
		available: true,
		synthetic: true,
		provider: profile.provider,
		model: profile.model,
		reasoningEffort: profile.reasoningEffort,
		serviceTier: profile.serviceTier,
		providerProfile: { ...profile },
		async start() {},
		async stop() {},
		async createAgent(record) {
			const agentId = record?.agentId;
			if (!decisions.has(agentId)) throw captureError('REPLAY_FIXTURE_AGENT_MISSING', `no compiled decision exists for '${trial.id}/${agentId}'`);
			const turns = capture.turns.get(agentId) ?? [];
			capture.turns.set(agentId, turns);
			return {
				async setGoalRevision() {},
				async decide(input) {
					if (turns.length >= maxTurns) throw captureError('REPLAY_FIXTURE_LIMIT', `planner turn count exceeds ${maxTurns}`);
					if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('capture planner input must be a nonblank string');
					const initialDecision = !initialized.has(agentId);
					initialized.add(agentId);
					const decision = initialDecision ? decisions.get(agentId) : CONTINUE_DECISION;
					turns.push({ input, decision });
					return decision;
				},
			};
		},
	};
}

const CONTINUE_DECISION = Object.freeze({ summary: 'continue deterministic program', directive: 'continue' });

function normalizeRequest(input, options) {
	if (input && typeof input === 'object' && !Array.isArray(input) && Object.hasOwn(input, 'matrix')) return { ...options, ...input };
	if (input === undefined) throw new TypeError('replay fixture matrix is required');
	return { ...options, matrix: input };
}

function normalizeReplayMatrix(value) {
	const matrix = normalizeLatencyMatrix(value);
	if (matrix.trials.some((trial) => trial.mode !== 'replay')) throw new TypeError('replay fixture matrix must contain only replay trials');
	for (const trial of matrix.trials) if (!LOADS.has(trial.agentLoad)) throw new TypeError('replay fixture trials must use supported agent loads');
	return matrix;
}

function normalizeLimits(options) {
	const maxTrials = positiveBound(options.maxTrials ?? DEFAULT_MAX_TRIALS, 'maxTrials');
	const maxTurns = positiveBound(options.maxTurns ?? DEFAULT_MAX_TURNS, 'maxTurns');
	const maxRecords = positiveBound(options.maxRecords ?? DEFAULT_MAX_RECORDS, 'maxRecords');
	const maxDelayMs = finiteBound(options.maxDelayMs ?? DEFAULT_MAX_DELAY_MS, 'maxDelayMs', DEFAULT_MAX_DELAY_MS);
	return { maxTrials, maxTurns, maxRecords, maxDelayMs };
}

function resolveDelay(options, context, maxDelayMs) {
	const source = options.delayForTurn ?? options.delayMs ?? 0;
	const value = typeof source === 'function' ? source(context) : Array.isArray(source) ? source[context.turnIndex] ?? 0 : source;
	if (!Number.isFinite(value) || value < 0 || value > maxDelayMs) throw new TypeError(`delayMs must be finite and within [0, ${maxDelayMs}]`);
	return value;
}

function positiveBound(value, name) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${name} must be a positive safe integer`);
	return value;
}

function finiteBound(value, name, maximum) {
	if (!Number.isFinite(value) || value < 0 || value > maximum) throw new TypeError(`${name} must be finite and within [0, ${maximum}]`);
	return value;
}

function captureKey(trialId, repetition) { return `${trialId}\u0000${repetition}`; }

function clearCaptures(captures) {
	for (const capture of captures.values()) {
		for (const turns of capture.turns.values()) turns.length = 0;
		capture.turns.clear();
		capture.agentIds.length = 0;
		capture.scenario = null;
	}
	captures.clear();
}

function captureError(code, message, result = undefined) {
	const error = Object.assign(new Error(message), { code });
	if (result !== undefined) error.result = result;
	return error;
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}
