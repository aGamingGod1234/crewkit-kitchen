import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { runLatencyMatrix } from './latency-runner.mjs';

const MINIMUM_COMPARISON_SAMPLES = 5;
const MAX_COMPARISON_TRIALS = 10_000;

export async function runInstrumentationComparison(options = {}) {
	const runner = options.runMatrix ?? runLatencyMatrix;
	if (typeof runner !== 'function') throw new TypeError('runMatrix must be a function');
	const common = { ...options };
	delete common.runMatrix;
	delete common.first;
	delete common.maxP95Ratio;
	delete common.minimumSamples;
	delete common.artifactDirectory;
	const first = options.first === 'enabled' ? 'enabled' : 'disabled';
	const order = first === 'enabled' ? ['enabled', 'disabled'] : ['disabled', 'enabled'];
	const runs = {};
	for (const arm of order) runs[arm] = await runner({ ...common, ...(options.artifactDirectory ? { artifactDirectory: path.join(path.resolve(options.artifactDirectory), arm) } : {}), measurements: arm === 'enabled', instrumentation: arm === 'enabled', collectMetrics: arm === 'enabled' });
	const comparison = compareInstrumentationRuns({ enabled: runs.enabled, disabled: runs.disabled, maxP95Ratio: options.maxP95Ratio, minimumSamples: options.minimumSamples, order });
	if (options.artifactDirectory) {
		await mkdir(path.resolve(options.artifactDirectory), { recursive: true });
		await writeFile(path.join(path.resolve(options.artifactDirectory), 'instrumentation-comparison.json'), `${JSON.stringify(comparison, null, 2)}\n`, 'utf8');
	}
	return comparison;
}

export function compareInstrumentationRuns({ enabled, disabled, maxP95Ratio = 1.05, minimumSamples = MINIMUM_COMPARISON_SAMPLES, order = ['disabled', 'enabled'] } = {}) {
	if (!Number.isFinite(maxP95Ratio) || maxP95Ratio < 1) throw new TypeError('maxP95Ratio must be at least 1');
	if (!Number.isSafeInteger(minimumSamples) || minimumSamples < 1) throw new TypeError('minimumSamples must be a positive integer');
	const enabledTrials = normalizeTrials(enabled, 'enabled');
	const disabledTrials = normalizeTrials(disabled, 'disabled');
	const disabledByKey = new Map(disabledTrials.map((trial) => [keyFor(trial), trial]));
	const pairs = [];
	for (const trial of enabledTrials) {
		const key = keyFor(trial);
		const peer = disabledByKey.get(key);
		if (!peer) throw new TypeError(`instrumentation comparison is missing disabled trial '${key}'`);
		pairs.push({
			key,
			behaviorParity: trial.status === peer.status && nullable(trial.debug?.actionCommandHash) === nullable(peer.debug?.actionCommandHash) && nullable(trial.debug?.scenarioDigest) === nullable(peer.debug?.scenarioDigest),
			enabledDurationMs: finiteDuration(trial.durationMs),
			disabledDurationMs: finiteDuration(peer.durationMs),
		});
		disabledByKey.delete(key);
	}
	if (disabledByKey.size > 0) throw new TypeError(`instrumentation comparison has extra disabled trial '${disabledByKey.keys().next().value}'`);
	const enabledDurations = pairs.map((pair) => pair.enabledDurationMs).filter(Number.isFinite).sort((a, b) => a - b);
	const disabledDurations = pairs.map((pair) => pair.disabledDurationMs).filter(Number.isFinite).sort((a, b) => a - b);
	const enabledP95Ms = percentile(enabledDurations, 0.95);
	const disabledP95Ms = percentile(disabledDurations, 0.95);
	const p95Ratio = enabledP95Ms === null || disabledP95Ms === null || disabledP95Ms === 0 ? null : enabledP95Ms / disabledP95Ms;
	const checks = [
		{ code: 'INSTRUMENTATION_SAMPLE_COUNT', status: pairs.length >= minimumSamples && enabledDurations.length >= minimumSamples && disabledDurations.length >= minimumSamples ? 'PASSED' : 'FAILED', observed: { pairs: pairs.length, enabled: enabledDurations.length, disabled: disabledDurations.length }, required: minimumSamples },
		{ code: 'INSTRUMENTATION_BEHAVIOR_PARITY', status: pairs.every((pair) => pair.behaviorParity) ? 'PASSED' : 'FAILED', mismatches: pairs.filter((pair) => !pair.behaviorParity).map((pair) => pair.key) },
		{ code: 'INSTRUMENTATION_P95_OVERHEAD', status: p95Ratio !== null && p95Ratio <= maxP95Ratio ? 'PASSED' : 'FAILED', observedRatio: p95Ratio, maximumRatio: maxP95Ratio, enabledP95Ms, disabledP95Ms },
	];
	return Object.freeze({ schemaVersion: 1, status: checks.every((check) => check.status === 'PASSED') ? 'PASSED' : 'FAILED', order: [...order], minimumSamples, maxP95Ratio, checks: checks.map(Object.freeze), pairs: pairs.map(Object.freeze) });
}

function normalizeTrials(value, label) {
	if (!value || typeof value !== 'object' || !Array.isArray(value.trials)) throw new TypeError(`${label} run must contain trials`);
	if (value.trials.length > MAX_COMPARISON_TRIALS) throw new RangeError(`${label} run exceeds ${MAX_COMPARISON_TRIALS} trials`);
	return value.trials;
}
function keyFor(trial) { return `${String(trial?.trialId ?? '')}\u0000${Number(trial?.repetition ?? 0)}`; }
function nullable(value) { return value === undefined ? null : value; }
function finiteDuration(value) { return Number.isFinite(value) && value >= 0 ? value : null; }
function percentile(sorted, fraction) { return sorted.length === 0 ? null : sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]; }
