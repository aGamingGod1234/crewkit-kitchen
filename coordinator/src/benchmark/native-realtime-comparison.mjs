import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

/**
 * A small, controlled comparison for the native planning-ahead contract.
 *
 * The bridge and controller in this benchmark are deliberately synthetic. The
 * executor, parser, action dispatch, timeout and planning-due callback are the
 * revision's real implementations. No live provider or successor action is
 * inferred from the advisory callback.
 */
export const NATIVE_REALTIME_WORKLOAD = Object.freeze({
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	timeoutMs: 300,
	planningLeadMs: 200,
	controllerDecisionPrepMs: 100,
	successorActionDurationMs: 8,
	actionDurationMinMs: 214,
	actionDurationMaxMs: 226,
	repetitions: 6,
	seed: 0x5eed38,
});

const METRICS = Object.freeze(['preparationLeadMs', 'usefulOverlapMs', 'decisionGapMs', 'bodyActionDurationMs']);
const RECORD = Object.freeze({
	agentId: 'native-realtime-benchmark-agent',
	goalRevision: 1,
	provider: 'controlled-synthetic',
	model: 'controlled-synthetic-model',
	reasoningEffort: 'high',
	serviceTier: 'priority',
});

/** Run both revisions with paired, counterbalanced inputs and return raw samples plus summaries. */
export async function runNativeRealtimeComparison(options = {}) {
	const config = normalizeConfig(options.config);
	const repoRoot = resolve(options.repoRoot ?? process.cwd());
	const baselineRevision = options.baselineRevision ?? '47c83ca8';
	const optimizedRevision = options.optimizedRevision ?? 'HEAD';
	const ownedTempDirs = [];
	let baselineSourceDir = options.baselineSourceDir;
	let optimizedSourceDir = options.optimizedSourceDir;
	try {
		if (!baselineSourceDir) {
			baselineSourceDir = await materializeRevision(repoRoot, baselineRevision, ownedTempDirs);
		}
		if (!optimizedSourceDir) {
			optimizedSourceDir = await materializeRevision(repoRoot, optimizedRevision, ownedTempDirs);
		}
		const [baseline, optimized] = await Promise.all([
			loadExecutor(baselineSourceDir, `baseline-${baselineRevision}`),
			loadExecutor(optimizedSourceDir, `optimized-${optimizedRevision}`),
		]);
		const revisions = {
			baseline: await sourceProvenance(repoRoot, baselineRevision, baselineSourceDir),
			optimized: await sourceProvenance(repoRoot, optimizedRevision, optimizedSourceDir),
		};
		const samples = [];
		for (let repetition = 0; repetition < config.repetitions; repetition++) {
			const first = repetition % 2 === 0 ? ['baseline', baseline] : ['optimized', optimized];
			const second = repetition % 2 === 0 ? ['optimized', optimized] : ['baseline', baseline];
			const actionDurationMs = actionDurationFor(config, repetition);
			for (const [arm, executor] of [first, second]) {
				samples.push(await runNativeRealtimeTrial(executor, {
					arm, repetition, actionDurationMs, config,
					pairingKey: `pair-${repetition + 1}`,
					order: arm === first[0] ? 1 : 2,
				}));
			}
		}
		return {
			benchmark: 'native-realtime-comparison',
			provenance: {
				revisions,
				config,
				configHash: sha256(stableJson(config)),
					execution: {
						node: process.version,
						platform: process.platform,
						provider: 'controlled-synthetic',
						bridge: 'controlled-synthetic',
						modelController: 'controlled-synthetic-explicit-successor',
					liveProvider: false,
					counterbalanced: true,
				},
			},
			rawSamples: samples,
			summary: summarizeComparison(samples),
		};
	} finally {
		await Promise.all(ownedTempDirs.map((directory) => removeOwnedTempDir(directory)));
	}
}

/** Execute one revision through the real NativeProgramExecutor entrypoint. */
export async function runNativeRealtimeTrial(Executor, { arm, repetition, actionDurationMs, config, pairingKey, order } = {}) {
	if (typeof Executor !== 'function') throw new TypeError('Executor must be a NativeProgramExecutor constructor');
	const now = () => performance.now();
	const startedAt = now();
	let eventSequence = 1;
	const actionRecords = [];
	let planningDueAt = null;
	let preparationStartedAt = null;
	let preparationFinishedAt = null;
	let preparationTrigger = null;
	let preparationPromise = null;
	let resolvePreparation;
	let deadlineTargetAt = null;
	let planningStatus = null;
	let planningDetails = null;
	const cancellations = [];
	const sleep = (durationMs) => new Promise((resolve) => setTimeout(resolve, durationMs));
	const observation = syntheticObservation();
	const executor = new Executor({
		sessionId: `native-realtime-${arm}-${repetition + 1}`,
		setTimeoutFn: (callback, delay, ...args) => {
			const scheduledAt = now();
			if (deadlineTargetAt === null && delay === config.timeoutMs) deadlineTargetAt = scheduledAt + delay;
			return setTimeout(callback, delay, ...args);
		},
		clearTimeoutFn: (handle) => clearTimeout(handle),
	});
	const context = {
		observation,
		eventSequence,
		executeAction: async (command) => {
			const actionIndex = actionRecords.length;
			const action = { index: actionIndex, command, startedAt: now(), finishedAt: null };
			actionRecords.push(action);
			await sleep(actionIndex === 0 ? actionDurationMs : config.successorActionDurationMs);
			action.finishedAt = now();
			return {
				state: 'SUCCEEDED',
				reasonCode: 'DONE',
				eventSequence: ++eventSequence,
				observation,
			};
		},
		cancelAction: async (actionId, reason) => { cancellations.push({ actionId, reason }); },
		onPlanningDue: (status, details) => {
			if (planningDueAt !== null || actionRecords.length !== 1 || actionRecords[0].finishedAt !== null) return;
			planningDueAt = now();
			planningStatus = status;
			planningDetails = details;
			beginPreparation('native_event');
		},
	};
	function beginPreparation(trigger) {
		if (preparationPromise !== null) return preparationPromise;
		preparationTrigger = trigger;
		preparationStartedAt = now();
		preparationPromise = new Promise((resolve) => { resolvePreparation = resolve; });
		setTimeout(() => {
			preparationFinishedAt = now();
			resolvePreparation();
		}, config.controllerDecisionPrepMs);
		return preparationPromise;
	}
	const runOptions = {
		source: config.source,
		timeoutMs: config.timeoutMs,
		planningLeadMs: config.planningLeadMs,
		programId: `native-realtime-${arm}-${repetition + 1}`,
		provenance: { traceId: `native-realtime-${pairingKey}` },
	};
	let firstResult;
	let successorResult;
	let error = null;
	try {
		firstResult = await executor.run(RECORD, runOptions, context);
		if (preparationPromise === null) beginPreparation('program_completion');
		await preparationPromise;
		context.eventSequence = eventSequence;
		successorResult = await executor.run(RECORD, {
			...runOptions,
			programId: `native-realtime-${arm}-${repetition + 1}-successor`,
		}, context);
	} catch (caught) {
		error = serializeError(caught);
	}
	const finishedAt = now();
	const firstAction = actionRecords[0] ?? null;
	const successorAction = actionRecords[1] ?? null;
	const deadlineAt = deadlineTargetAt ?? startedAt + config.timeoutMs;
	const eventObserved = planningDueAt !== null;
	const bodyContinuity = eventObserved && firstAction !== null && firstAction.finishedAt !== null
		&& firstResult?.state === 'YIELDED' && firstResult?.reasonCode === 'PROGRAM_EXHAUSTED'
		&& firstResult?.receipts?.length === 1 && firstResult.receipts[0].state === 'SUCCEEDED';
	const controllerFollowed = firstAction !== null && firstAction.finishedAt !== null && successorAction !== null && successorAction.startedAt !== null
		&& preparationFinishedAt !== null && successorAction.startedAt >= firstAction.finishedAt
		&& successorAction.startedAt >= preparationFinishedAt;
	const bothProgramsPassed = firstResult?.reasonCode === 'PROGRAM_EXHAUSTED' && successorResult?.reasonCode === 'PROGRAM_EXHAUSTED';
	return {
		arm,
		pairingKey,
		repetition: repetition + 1,
		order,
		provider: 'controlled-synthetic',
		liveProvider: false,
		controller: 'controlled-synthetic-explicit-successor',
		status: error === null && bothProgramsPassed ? 'passed' : 'failed',
		error,
		configured: {
			timeoutMs: config.timeoutMs,
			planningLeadMs: config.planningLeadMs,
			actionDurationMs,
		},
		outcome: {
			first: firstResult === undefined ? null : { state: firstResult.state, reasonCode: firstResult.reasonCode, actions: firstResult.actions, receipts: firstResult.receipts?.length ?? null },
			successor: successorResult === undefined ? null : { state: successorResult.state, reasonCode: successorResult.reasonCode, actions: successorResult.actions, receipts: successorResult.receipts?.length ?? null },
		},
		observed: {
			nativeEventObserved: eventObserved,
			planningStatus: planningStatus === null ? null : {
				programVersion: planningStatus.programVersion,
				engineState: planningStatus.engineState,
			},
			planningDetails,
			controllerFollowed,
			preparationTrigger,
			successorStartedBy: controllerFollowed ? 'controlled-synthetic-controller' : null,
			bodyActionStarted: firstAction !== null,
			bodyActionCompleted: firstAction !== null && firstAction.finishedAt !== null,
			bodyContinuity,
			bodyActionCount: actionRecords.length,
			successorActionStarted: successorAction !== null,
			cancellationCount: cancellations.length,
		},
		metrics: {
			// Measured from this run's wall clock; null means the event did not
			// occur or the condition needed to interpret it was not observed.
			preparationLeadMs: eventObserved ? deadlineAt - planningDueAt : null,
			usefulOverlapMs: bodyContinuity ? firstAction.finishedAt - planningDueAt : null,
			decisionGapMs: controllerFollowed ? successorAction.startedAt - firstAction.finishedAt : null,
			bodyActionDurationMs: firstAction !== null && firstAction.startedAt !== null && firstAction.finishedAt !== null
				? firstAction.finishedAt - firstAction.startedAt : null,
		},
		timing: {
			startedAt,
			finishedAt,
			durationMs: finishedAt - startedAt,
		},
	};
}

/** Nearest-rank summaries preserve missing event-dependent metrics as missing. */
export function summarizeNearestRank(values) {
	const finite = values.filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	const percentile = (rank) => finite.length === 0 ? null : finite[Math.max(0, Math.ceil(rank * finite.length) - 1)];
	return {
		count: finite.length,
		missing: values.length - finite.length,
		min: finite[0] ?? null,
		p50: percentile(0.5),
		p95: percentile(0.95),
		max: finite.at(-1) ?? null,
	};
}

export function summarizeComparison(samples) {
	const byArm = {};
	for (const arm of ['baseline', 'optimized']) {
		const armSamples = samples.filter((sample) => sample.arm === arm);
		byArm[arm] = {
			trials: armSamples.length,
			passed: armSamples.filter((sample) => sample.status === 'passed').length,
			nativeEventCount: armSamples.filter((sample) => sample.observed.nativeEventObserved).length,
			bodyContinuityCount: armSamples.filter((sample) => sample.observed.bodyContinuity).length,
			controllerFollowCount: armSamples.filter((sample) => sample.observed.controllerFollowed).length,
			metrics: Object.fromEntries(METRICS.map((metric) => [metric, summarizeNearestRank(armSamples.map((sample) => sample.metrics[metric]))])),
		};
	}
	const pairs = new Map();
	for (const sample of samples) {
		const pair = pairs.get(sample.pairingKey) ?? {};
		pair[sample.arm] = sample;
		pairs.set(sample.pairingKey, pair);
	}
	const paired = [...pairs.entries()].filter(([, pair]) => pair.baseline && pair.optimized);
	const delta = Object.fromEntries(METRICS.map((metric) => [metric, summarizeNearestRank(paired
		.map(([, pair]) => Number.isFinite(pair.optimized.metrics[metric]) && Number.isFinite(pair.baseline.metrics[metric])
			? pair.optimized.metrics[metric] - pair.baseline.metrics[metric] : null))]));
	return {
		byArm,
		pairedTrials: paired.length,
		counterbalancedOrders: paired.map(([pairingKey, pair]) => ({
			pairingKey,
			first: pair.baseline.order === 1 ? 'baseline' : 'optimized',
			second: pair.baseline.order === 1 ? 'optimized' : 'baseline',
		})),
		pairedDeltaOptimizedMinusBaselineMs: delta,
		factualParity: {
			pairedOutcomeParityCount: paired.filter(([, pair]) => pair.baseline.status === 'passed' && pair.optimized.status === 'passed'
				&& pair.baseline.observed.bodyActionCount === pair.optimized.observed.bodyActionCount).length,
			baselinePlanningEventAbsentCount: samples.filter((sample) => sample.arm === 'baseline' && !sample.observed.nativeEventObserved).length,
			optimizedPlanningEventObservedCount: samples.filter((sample) => sample.arm === 'optimized' && sample.observed.nativeEventObserved).length,
		},
	};
}

async function loadExecutor(sourceDir, cacheKey) {
	const url = `${pathToFileURL(resolve(sourceDir, 'native-program-executor.mjs')).href}?benchmark=${encodeURIComponent(cacheKey)}`;
	const module = await import(url);
	if (typeof module.NativeProgramExecutor !== 'function') throw new TypeError(`NativeProgramExecutor missing from ${sourceDir}`);
	return module.NativeProgramExecutor;
}

async function materializeRevision(repoRoot, revision, ownedTempDirs) {
	const target = await mkdtemp(join(tmpdir(), 'native-realtime-'));
	ownedTempDirs.push(target);
	const archive = execFileSync('git', ['archive', '--format=tar', revision, 'coordinator/src'], { cwd: repoRoot, maxBuffer: 64 * 1024 * 1024 });
	const archivePath = join(target, 'source.tar');
	await writeFile(archivePath, archive);
	execFileSync('tar', ['-xf', archivePath], { cwd: target });
	// Relative imports in the archived parser use its package scope. Reuse the
	// checked-out coordinator dependencies while keeping all source files
	// revision-isolated.
	await symlink(resolve(repoRoot, 'coordinator', 'node_modules'), join(target, 'coordinator', 'node_modules'), 'junction');
	return join(target, 'coordinator', 'src');
}

async function removeOwnedTempDir(directory) {
	const tempRoot = resolve(tmpdir());
	const target = resolve(directory);
	const separator = target.includes('\\') ? '\\' : '/';
	const prefix = `${tempRoot}${separator}native-realtime-`;
	if (!target.startsWith(prefix)) throw new Error(`refusing to remove unexpected benchmark directory: ${target}`);
	await rm(target, { recursive: true, force: true });
}

async function sourceProvenance(repoRoot, revision, sourceDir) {
	let resolvedRevision = revision;
	try { resolvedRevision = execFileSync('git', ['rev-parse', revision], { cwd: repoRoot, encoding: 'utf8' }).trim(); } catch { /* source may be supplied outside this checkout */ }
	return {
		revision,
		resolvedRevision,
		sourceKind: 'isolated-coordinator-src',
		sourceHash: await hashDirectory(sourceDir),
	};
}

async function hashDirectory(directory) {
	const hash = createHash('sha256');
	const files = [];
	async function visit(current) {
		for (const entry of await readdir(current, { withFileTypes: true })) {
			const path = join(current, entry.name);
			// The benchmark is an observer of the runtime. Exclude benchmark
			// helpers from the runtime source hash so adding this file cannot make
			// the optimized implementation appear to have changed twice.
			if (entry.isDirectory() && entry.name === 'benchmark') continue;
			if (entry.isDirectory()) await visit(path);
			else if (entry.isFile()) files.push(path);
		}
	}
	await visit(directory);
	for (const path of files.sort()) {
		hash.update(relative(directory, path).replaceAll('\\', '/'));
		hash.update(await readFile(path));
	}
	return hash.digest('hex');
}

function normalizeConfig(overrides = {}) {
	const config = { ...NATIVE_REALTIME_WORKLOAD, ...overrides };
	if (!Number.isSafeInteger(config.repetitions) || config.repetitions < 5) throw new RangeError('repetitions must be at least 5');
	if (typeof config.source !== 'string' || config.source.length === 0) throw new TypeError('source is required');
	return config;
}

function actionDurationFor(config, repetition) {
	const span = config.actionDurationMaxMs - config.actionDurationMinMs + 1;
	let value = config.seed >>> 0;
	for (let index = 0; index <= repetition; index++) value = (Math.imul(value, 1664525) + 1013904223) >>> 0;
	return config.actionDurationMinMs + (value % span);
}

function syntheticObservation() {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } };
}

function serializeError(error) { return { name: error?.name ?? 'Error', code: error?.code ?? null, message: error?.message ?? String(error) }; }
function stableJson(value) {
	if (Array.isArray(value)) return `[${value.map((item) => stableJson(item)).join(',')}]`;
	if (value && typeof value === 'object') return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
	return JSON.stringify(value);
}
function sha256(value) { return createHash('sha256').update(value).digest('hex'); }

async function main() {
	const args = parseArgs(process.argv.slice(2));
	const report = await runNativeRealtimeComparison({
		repoRoot: args.repo ?? process.cwd(),
		baselineRevision: args.baseline ?? '47c83ca8',
		optimizedRevision: args.optimized ?? 'HEAD',
		baselineSourceDir: args['baseline-source'],
		optimizedSourceDir: args['optimized-source'],
		config: {
			...(args.repetitions === undefined ? {} : { repetitions: Number(args.repetitions) }),
		},
	});
	const text = `${JSON.stringify(report, null, 2)}\n`;
	if (args.output) await writeFile(resolve(args.output), text);
	else process.stdout.write(text);
}

function parseArgs(args) {
	const parsed = {};
	for (let index = 0; index < args.length; index++) {
		const argument = args[index];
		if (!argument.startsWith('--')) continue;
		const key = argument.slice(2);
		parsed[key] = args[index + 1]?.startsWith('--') || args[index + 1] === undefined ? true : args[++index];
	}
	return parsed;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) await main();
