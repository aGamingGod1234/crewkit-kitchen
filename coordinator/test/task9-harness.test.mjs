import assert from 'node:assert/strict';
import { readFile, mkdtemp, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
	TASK9_ADAPTIVE_CONTROLLER_V1,
	TASK9_FIXED_CONCURRENCIES,
	TASK9_LUNA_PICKAXE_PROFILE,
	TASK9_REQUIRED_PHASES,
	buildTask9TrialReport,
	createFairAbRows,
	createSchedulerSweepRows,
	createTask9RunManifest,
	normalizeTask9Matrix,
	runTask9SimulatorMatrix,
	validateTask9TrialReport,
} from '../src/benchmark/task9-harness.mjs';
import { compileScenarioDecision } from '../src/benchmark/scenario-program.mjs';

const profile = { provider: 'replay', model: 'controlled-v1', reasoningEffort: 'fixed', serviceTier: 'synthetic-delayed' };
const trial = (overrides = {}) => ({ id: 'stone', mode: 'replay', scenarioId: 'stone-tool-gathering', seed: 20260821, agentLoad: 4, repetitions: 5, providerProfile: profile, turnBudgetMs: 10_000, trialBudgetMs: 60_000, turnCap: 8, ...overrides });
const matrix = (overrides = {}) => ({ schemaVersion: 3, fixedSeeds: [20260821, 20260822], agentLoads: [1, 4, 8, 16], trials: [trial(overrides)] });
const phases = TASK9_REQUIRED_PHASES.map((phase, index) => ({ phase, sequence: index + 1, monotonicMs: index, durationMs: 1 }));

test('normalizes the schema-3 deterministic matrix and rejects live trials', () => {
	const normalized = normalizeTask9Matrix(matrix());
	assert.equal(normalized.schemaVersion, 3);
	assert.deepEqual(normalized.agentLoads, [1, 4, 8, 16]);
	assert.throws(() => normalizeTask9Matrix(matrix({ mode: 'live' })), /instant or replay/i);
});

test('creates fair A/B rows with identical cells and a separately versioned scheduler sweep', () => {
	const fair = createFairAbRows({ trials: matrix(), sourceCommits: { baseline: 'base', optimized: 'tip' }, fixedConcurrency: 16 });
	assert.equal(fair.length, 10);
	for (let index = 0; index < fair.length; index += 2) {
		assert.equal(fair[index].cellId, fair[index + 1].cellId);
		assert.equal(fair[index].scheduler.fixedConcurrency, 16);
		assert.equal(fair[index].scheduler.urgentReserve, 0);
		assert.equal(fair[index + 1].scheduler.urgentReserve, 0);
		assert.equal(fair[index].seed, fair[index + 1].seed);
		assert.equal(fair[index].agentLoad, fair[index + 1].agentLoad);
	}
	const sweep = createSchedulerSweepRows({ trials: matrix(), sourceCommit: 'tip' });
	assert.deepEqual(sweep.slice(0, TASK9_FIXED_CONCURRENCIES.length).map((row) => row.scheduler.fixedConcurrency), [...TASK9_FIXED_CONCURRENCIES]);
	assert.ok(sweep.filter(row => row.scheduler.mode === 'fixed').every(row => row.scheduler.urgentReserve === 0));
	assert.equal(sweep.at(-1).scheduler.mode, 'adaptive');
	assert.deepEqual(sweep.at(-1).scheduler.controller, TASK9_ADAPTIVE_CONTROLLER_V1);
});

test('generated fixed rows admit the complete ordinary workload through the real runner', async (context) => {
	for (const load of [1, 4]) await context.test(`load ${load}`, async (loadContext) => {
		const input = matrix({ id: `stone-${load}`, agentLoad: load, repetitions: 1, turnBudgetMs: 1000, trialBudgetMs: 1800 });
		// Only the negative control needs the short budget: it can never finish, so it times out at any load.
		// The passing rows get a budget a loaded runner cannot exhaust.
		const roomy = matrix({ id: `stone-${load}`, agentLoad: load, repetitions: 1, turnBudgetMs: 10_000, trialBudgetMs: 60_000 });
		const fair = createFairAbRows({ trials: input, sourceCommits: { baseline: 'base', optimized: 'head' }, fixedConcurrency: 4 });
		const sweep = createSchedulerSweepRows({ trials: input, sourceCommit: 'head' }).find(row => row.scheduler.fixedConcurrency === 4);
		const cases = [...fair.map(row => [row.arm, row.scheduler]), ['sweep', sweep.scheduler],
			// Retain the original defect as a negative control, without altering global scheduler defaults.
			['reserved-slot', { ...sweep.scheduler, urgentReserve: 1 }]];
		for (const [name, scheduler] of cases) await loadContext.test(name, async () => {
			const providerTurns = [];
			const provider = (_profile, runnerContext) => ({
				available: true, synthetic: true, ...profile, providerProfile: profile,
				async start() {}, async stop() {},
				async createAgent(record) {
					// Compile the runner's translated manifest for each actual agent, not a shared origin fixture.
					const decision = compileScenarioDecision(runnerContext.loadScenario.agentManifests[record.agentId]);
					let initialized = false;
					return {
						async setGoalRevision() {},
						async decide() {
							providerTurns.push(record.agentId);
							await new Promise(resolve => setTimeout(resolve, 20));
							if (initialized) return { directive: 'continue', summary: 'continue' };
							initialized = true;
							return decision;
						},
					};
				},
			});
			const report = await runTask9SimulatorMatrix({ matrix: name === 'reserved-slot' ? input : roomy, scheduler, providerFactories: { replay: provider }, artifactDirectory: null });
			const result = report.trials[0];
			assert.deepEqual(result.scheduler, { mode: 'fixed', maxConcurrent: load, maxPending: 0, urgentReserve: scheduler.urgentReserve });
			assert.equal(report.runManifest.scheduler.urgentReserve, scheduler.urgentReserve);
			assert.equal(result.cleanup.ok, true);
			assert.equal(result.cleanup.processTreeClean, true);
			assert.equal(result.cleanup.listenersClosed, true);
			if (name === 'reserved-slot') {
				assert.equal(report.status, 'FAILED');
				assert.equal(result.status, 'TIMED_OUT');
				assert.equal(result.correctness.factualSuccess, false);
				assert.equal(providerTurns.length, load - 1);
			} else {
				assert.equal(report.status, 'PASSED');
				assert.equal(result.status, 'PASSED');
				assert.equal(result.correctness.factualSuccess, true);
				assert.deepEqual(result.missingPhases, []);
				assert.equal(providerTurns.length, load);
				assert.equal(new Set(providerTurns).size, load);
			}
		});
	});
});

test('does not call live providers and keeps factual/cleanup evidence separate from latency', () => {
	const report = buildTask9TrialReport({
		identity: { runId: 'r', trialId: 't', repetition: 1, cellId: 'c', scenarioId: 'stone', seed: 1, agentLoad: 1, providerProfile: profile },
		status: 'PASSED', events: phases, cpuSamples: [3, 1, 2], rssSamples: [30, 10, 20], tickSamples: [4, 2, 3], factualSuccess: true,
		cleanup: { ok: true, processTreeClean: true, listenersClosed: true },
	});
	assert.equal(report.resources.cpu.p95, 3);
	assert.equal(report.resources.cpu.basis, 'process_cpu_interval_delta_ms');
	assert.equal(report.resources.minecraftTick.p99, 4);
	assert.deepEqual(report.missingPhases, []);
	assert.equal(validateTask9TrialReport(report), true);
	const incomplete = buildTask9TrialReport({ identity: report, status: 'PASSED', events: [], factualSuccess: false, cleanup: { ok: true } });
	assert.equal(incomplete.status, 'FAILED');
	assert.ok(incomplete.missingPhases.length > 0);
});

test('adapts deterministic runner output into raw evidence artifacts', async () => {
	const root = await mkdtemp(path.join(tmpdir(), 'task9-harness-'));
	const output = await runTask9SimulatorMatrix({
		matrix: matrix({ repetitions: 1 }), artifactDirectory: root, runId: 'run-1', arm: 'optimized', sourceHash: 'sha256:source', configHash: 'sha256:config',
		runMatrix: async (options) => {
			assert.equal(options.includeRawEvents, true);
			assert.equal(options.matrix.trials[0].providerAvailabilityRequired, true);
			return {
				status: 'PASSED', rawEvents: phases.map((event) => ({ ...event, trialId: 'stone', repetition: 1 })),
				trials: [{ trialId: 'stone', repetition: 1, status: 'PASSED', scheduler: { mode: 'fixed', maxConcurrent: 4, maxPending: 0, urgentReserve: 0 }, scenarioId: 'stone-tool-gathering', seed: 20260821, agentLoad: 4, providerProfile: profile, metrics: { raw: { ticks: [{ wallDurationMs: 2 }] } }, systemSummary: { rawSamples: [{ cpu: { totalMs: 10 }, memory: { rssBytes: 2 } }, { cpu: { totalMs: 14 }, memory: { rssBytes: 3 } }] }, debug: { scenarioPassed: true }, cleanup: { ok: true, activeActions: 0, listeners: 0, relays: 0 } }],
			};
		},
	});
	assert.equal(output.status, 'PASSED');
	assert.equal(output.trials[0].correctness.factualSuccess, true);
	assert.deepEqual(output.trials[0].resources.cpu.raw, [4]);
	assert.deepEqual(output.trials[0].missingPhases, []);
	const files = await readdir(root);
	assert.ok(files.includes('run-manifest.json'));
	assert.ok(files.includes('events.jsonl'));
	assert.ok(files.some((name) => name.startsWith('trial-stone-1')));
});

test('pins the exact Luna xhigh fast Desktop scenario without performing a live run', async () => {
	const config = JSON.parse(await readFile(new URL('../config/headless-provider-matrix.json', import.meta.url), 'utf8'));
	const scenario = config.scenarios.find((entry) => entry.id === TASK9_LUNA_PICKAXE_PROFILE.id);
	assert.deepEqual({ provider: scenario.provider, model: scenario.model, reasoningEffort: scenario.reasoningEffort, serviceTier: scenario.serviceTier }, { provider: TASK9_LUNA_PICKAXE_PROFILE.provider, model: TASK9_LUNA_PICKAXE_PROFILE.model, reasoningEffort: TASK9_LUNA_PICKAXE_PROFILE.reasoningEffort, serviceTier: TASK9_LUNA_PICKAXE_PROFILE.serviceTier });
	assert.equal(scenario.repetitions, 3);
	assert.equal(scenario.requireFactualSuccess, true);
	assert.match(scenario.assert[1].command, /\{agent\}/);
	assert.equal(createTask9RunManifest({ runId: 'r', sourceCommit: 'a', sourceHash: 'b', matrixHash: 'c', configHash: 'd', pairingKey: 'p', providerProfile: profile, scheduler: { mode: 'fixed', fixedConcurrency: 16 } }).clockBasis.wall, 'monotonic_ms');
	const deterministic = JSON.parse(await readFile(new URL('../config/task9-performance-matrix.json', import.meta.url), 'utf8'));
	const normalized = normalizeTask9Matrix(deterministic);
	assert.equal(normalized.trials.length, 19);
	assert.equal(normalized.trials.every((entry) => entry.repetitions === 5), true);
});
