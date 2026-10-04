import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';

const profileKeys = ['provider', 'model', 'reasoningEffort', 'serviceTier'];
const outcomes = new Set(['PASSED', 'FAILED', 'TIMED_OUT', 'INTERRUPTED', 'ERROR', 'SKIPPED']);
const measured = new Set(['PASSED', 'FAILED', 'TIMED_OUT']);
const duration = (value, name) => {
	assert.ok(Number.isSafeInteger(value) && value >= 0, `${name} must be a nonnegative safe integer`);
	return value;
};
const timestamp = value => {
	assert.ok(Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER, 'invalid monotonic timestamp');
	return value;
};
function freeze(value) {
	if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
	return value;
}

/** Deadlines and readings must share this clock's origin, never Date.now(). */
export function createBudgetClock(read = () => performance.now(), startedAtMs = 0) {
	let previous = timestamp(startedAtMs);
	return () => {
		const current = timestamp(read());
		assert.ok(current >= previous, 'budget clock moved backwards');
		previous = current;
		return current;
	};
}

/** Per-arm setup and cleanup are reserved twice. Nothing has a default budget. */
export function pairedAdmission({ nowMs, deadlineMs, startupMs, trialMs, cleanupMs }) {
	timestamp(nowMs); timestamp(deadlineMs);
	for (const [key, value] of Object.entries({ startupMs, trialMs, cleanupMs })) duration(value, key);
	assert.ok(trialMs > 0, 'trialMs must be positive');
	const armMs = duration(startupMs + trialMs + cleanupMs, 'arm reserve');
	const requiredMs = duration(2 * armMs, 'pair reserve');
	const remainingMs = Math.max(0, deadlineMs - nowMs);
	return { admitted: remainingMs >= requiredMs, armMs, requiredMs, remainingMs };
}

/** Supplied identities are declarations, not verification of installed artifacts. */
export function preparePairedPilot(config) {
	const value = structuredClone(config);
	timestamp(value.deadlineMs);
	duration(value.startupMs, 'startupMs'); duration(value.cleanupMs, 'cleanupMs');
	assert.deepEqual(value.arms?.map(arm => arm.id), ['A', 'B'], 'arms must be A and B');
	for (const arm of value.arms) {
		for (const key of profileKeys) assert.ok(typeof arm.profile?.[key] === 'string' && arm.profile[key].trim(), `missing profile ${key}`);
		assert.deepEqual(arm.profile, value.arms[0].profile, 'arm profile mismatch');
		if (arm.auxiliaryProfile !== undefined) assert.deepEqual(arm.auxiliaryProfile, arm.profile, 'auxiliary profile mismatch');
		for (const key of ['artifactSha256', 'sourceManifestSha256']) assert.match(arm[key] ?? '', /^[a-f0-9]{64}$/, `missing ${key}`);
	}
	assert.ok(Array.isArray(value.scenarios) && value.scenarios.length > 0, 'scenarios required');
	const ids = new Set();
	for (const scenario of value.scenarios) {
		assert.ok(typeof scenario.id === 'string' && /^[a-z0-9_-]+$/.test(scenario.id) && !ids.has(scenario.id), 'unique safe scenario id required');
		ids.add(scenario.id);
		assert.ok(typeof scenario.seed === 'string' && /^-?\d+$/.test(scenario.seed), 'exact decimal seed required');
		pairedAdmission({ ...value, nowMs: 0, trialMs: scenario.trialMs });
	}
	const pairs = value.scenarios.flatMap(scenario => [['A', 'B'], ['B', 'A']].map(order => ({
		id: `${scenario.id}-${order.join('')}`, scenarioId: scenario.id, seed: scenario.seed, trialMs: scenario.trialMs,
		order, arms: order.map(id => value.arms.find(arm => arm.id === id)),
	})));
	return freeze({ ...value, pairs });
}

/** Consume a single runHeadlessScenario report, not the matrix aggregate status.
 * The existing runner reports TIMEOUT as FAILED. Keep that distinction and its
 * cleanup failure, without copying private diagnostic/provider payloads.
 */
export function headlessTrialOutcome(report) {
	const classifications = { PASSED: 'PASSED', TIMEOUT: 'TIMED_OUT', DEAD: 'FAILED',
		ASSERTION_MISMATCH: 'FAILED', FAILED_USER_OBJECTIVE: 'FAILED', SKIPPED_PROFILE: 'SKIPPED' };
	// FAILED is also used by the runner for invalid execution. Only explicit
	// measured classifications may enter a complete pair's denominator.
	const status = classifications[report?.classification] ?? (report?.status === 'SKIPPED' ? 'SKIPPED' : 'ERROR');
	return { status, resourcesClean: report?.cleanup?.status === 'CLEAN' };
}

/** Offline-testable executor; it never launches a process or calls a provider.
 * Callbacks MUST enforce deadlineMs using the supplied clock and settle only
 * after their work has stopped. A Promise.race cannot stop external work, so we
 * do not disguise an uncooperative callback as cancellation. Overruns are charged
 * in full and block subsequent work. An explicit trial deadline handoff instead
 * consumes the existing cleanup reserve and requires confirmed timeout evidence.
 * Cleanup receives its own non-aborted signal
 * and an expired deadline means stop-only teardown, not extra runtime credit.
 * startup can call own(resource) before failing so partial acquisition is cleaned.
 * Callers persist prepared intent before execution and the returned report after;
 * a killed process requires their durable journal, not an in-memory completion.
 */
export async function runPairedPilot(config, { now = createBudgetClock(), startup, runTrial, cleanup, signal } = {}) {
	for (const callback of [now, startup, runTrial, cleanup]) assert.equal(typeof callback, 'function', 'explicit lifecycle callbacks required');
	const prepared = preparePairedPilot(config);
	const clock = createBudgetClock(now);
	let lastNow = clock();
	const report = { version: 1, status: 'INCOMPLETE', deadlineMs: prepared.deadlineMs, startedAtMs: lastNow,
		clockInvalid: false, installedVerified: false, providerVerified: false, promotionApproved: false,
		pairs: prepared.pairs.map(pair => ({ id: pair.id, scenarioId: pair.scenarioId, seed: pair.seed, order: pair.order,
			status: 'NOT_STARTED', trials: pair.arms.map(arm => ({ arm: arm.id, profile: arm.profile,
				artifactSha256: arm.artifactSha256, sourceManifestSha256: arm.sourceManifestSha256,
				status: 'NOT_STARTED', durationMs: null, phases: {} })) })) };
	const sample = () => {
		try { lastNow = clock(); } catch { report.clockInvalid = true; }
		return lastNow;
	};
	let stopped = false;
	for (let index = 0; index < prepared.pairs.length; index++) {
		const pair = prepared.pairs[index]; const record = report.pairs[index];
		const admittedAt = sample();
		record.admission = pairedAdmission({ ...prepared, trialMs: pair.trialMs, nowMs: admittedAt });
		if (stopped || report.clockInvalid || signal?.aborted || !record.admission.admitted) {
			record.status = signal?.aborted ? 'NOT_STARTED_INTERRUPTED' : stopped || report.clockInvalid ? 'NOT_STARTED_PRIOR_FAILURE' : 'NOT_STARTED_PAIR_RESERVE';
			continue;
		}
		record.startedAtMs = admittedAt;
		record.reservedUntilMs = admittedAt + record.admission.requiredMs;
		for (let slot = 0; slot < pair.arms.length; slot++) {
			const arm = pair.arms[slot]; const trial = record.trials[slot];
			// Fixed arm boundary: no setup/trial/cleanup callback sees its peer's reserve.
			const armEnd = admittedAt + (slot + 1) * record.admission.armMs;
			const startedAt = sample();
			if (report.clockInvalid || signal?.aborted || armEnd - startedAt < pair.trialMs + prepared.cleanupMs) {
				trial.status = signal?.aborted ? 'NOT_STARTED_INTERRUPTED' : 'NOT_STARTED_RESERVE_OVERRUN'; stopped = true; break;
			}
			let resource;
			let resourcesClean = true;
			trial.startedAtMs = startedAt;
			trial.reservedUntilMs = armEnd;
			const phase = async (name, budgetMs, latestDeadline, operation, phaseSignal) => {
				const start = sample();
				const deadlineMs = Math.min(start + budgetMs, latestDeadline);
				const timing = trial.phases[name] = { startedAtMs: start, deadlineMs, attempted: false, status: 'RUNNING' };
				let result;
				try {
					if (report.clockInvalid && name !== 'cleanup') timing.status = 'CLOCK_INVALID';
					else if (phaseSignal?.aborted) timing.status = 'INTERRUPTED';
					else if (name === 'trial' && start + budgetMs > latestDeadline) timing.status = 'NOT_STARTED_RESERVE_OVERRUN';
					else {
						timing.attempted = true;
						result = await operation({ arm, pair, resource, now: () => { const time = sample(); assert.ok(!report.clockInvalid, 'invalid budget clock'); return time; }, deadlineMs, cleanupDeadlineMs: name === 'trial' ? Math.min(deadlineMs + prepared.cleanupMs, armEnd) : undefined, signal: phaseSignal, stopOnly: name === 'cleanup' && (report.clockInvalid || start >= deadlineMs),
							own: value => { resource = value; } });
						timing.status = 'RETURNED';
					}
				} catch (error) {
					timing.status = phaseSignal?.aborted || error?.name === 'AbortError' ? 'INTERRUPTED'
						: ['HEADLESS_TIMEOUT', 'TIMEOUT', 'ETIMEDOUT'].includes(error?.code) ? 'TIMED_OUT' : 'ERROR';
				}
				const end = sample();
				if (name === 'trial' && result?.deadlineReached === true && end >= deadlineMs) timing.deadlineHandoff = true;
				Object.assign(timing, { finishedAtMs: end, elapsedMs: report.clockInvalid ? null : end - start,
					overrunMs: report.clockInvalid ? null : Math.max(0, end - deadlineMs) });
				if (report.clockInvalid) timing.status = 'CLOCK_INVALID';
				else if (phaseSignal?.aborted) timing.status = 'INTERRUPTED';
				else if (end > deadlineMs || timing.deadlineHandoff) timing.status = 'TIMED_OUT';
				return result;
			};
			try {
				const acquired = await phase('startup', prepared.startupMs, armEnd - pair.trialMs - prepared.cleanupMs, startup, signal);
				if (acquired !== undefined) resource = acquired;
				trial.status = trial.phases.startup.status;
				if (trial.status === 'RETURNED') {
					// Keep the full equal trial allowance; never silently shorten the peer.
					if (armEnd - sample() < pair.trialMs + prepared.cleanupMs || report.clockInvalid) trial.status = 'NOT_STARTED_RESERVE_OVERRUN';
					else {
						const result = await phase('trial', pair.trialMs, armEnd - prepared.cleanupMs, runTrial, signal);
						trial.status = trial.phases.trial.status === 'RETURNED'
							? outcomes.has(result?.status) ? result.status : 'ERROR' : trial.phases.trial.status;
						resourcesClean = result?.resourcesClean !== false && !trial.phases.trial.deadlineHandoff; trial.resourcesClean = resourcesClean;
					}
				}
			} finally {
				const handoff = trial.phases.trial?.deadlineHandoff === true;
				// Scheduling and acknowledgement after cutoff spend cleanup, never extend it.
				const cleanupEnd = handoff ? Math.min(armEnd, trial.phases.trial.deadlineMs + prepared.cleanupMs) : armEnd;
				const result = await phase('cleanup', prepared.cleanupMs, cleanupEnd, cleanup, new AbortController().signal);
				// Evidence and agent removal finish in cleanup. Preserve the final runner
				// outcome separately from wrapper teardown and never repair a late trial.
				if (result?.trialOutcome && (trial.phases.trial?.status === 'RETURNED' || handoff)) {
					trial.status = outcomes.has(result.trialOutcome.status) && (!handoff || result.trialOutcome.status === 'TIMED_OUT') ? result.trialOutcome.status : 'ERROR';
					resourcesClean = result.trialOutcome.resourcesClean === true;
					trial.resourcesClean = resourcesClean;
				}
				trial.cleanup = trial.phases.cleanup.status === 'RETURNED' ? result?.ok === true ? 'CLEAN' : 'UNKNOWN' : trial.phases.cleanup.status;
				trial.finishedAtMs = sample();
				trial.durationMs = report.clockInvalid ? null : trial.finishedAtMs - startedAt;
			}
			// Ordinary measured failures/timeouts still get their matched peer. A
			// lifecycle error, overrun, or uncertain cleanup stops resource reuse.
			stopped = report.clockInvalid || signal?.aborted || !resourcesClean || trial.cleanup !== 'CLEAN'
				|| !measured.has(trial.status) || !trial.phases.trial
				|| Object.entries(trial.phases).some(([name, value]) => value.overrunMs > 0 && !(name === 'trial' && value.deadlineHandoff));
			if (stopped) break;
		}
		record.finishedAtMs = sample();
		record.durationMs = report.clockInvalid ? null : record.finishedAtMs - admittedAt;
		record.status = !stopped && record.trials.every(trial => measured.has(trial.status)) ? 'COMPLETE' : 'INCOMPLETE';
	}
	report.finishedAtMs = sample();
	report.elapsedMs = report.clockInvalid ? null : report.finishedAtMs - report.startedAtMs;
	report.overrunMs = report.clockInvalid ? null : Math.max(0, report.finishedAtMs - prepared.deadlineMs);
	const trials = report.pairs.flatMap(pair => pair.trials);
	report.counts = { scheduled: trials.length, started: trials.filter(trial => trial.startedAtMs !== undefined).length,
		attempted: trials.filter(trial => trial.phases.trial?.attempted).length,
		outcomes: Object.fromEntries([...new Set(trials.map(trial => trial.status))].map(status => [status, trials.filter(trial => trial.status === status).length])) };
	report.status = !report.clockInvalid && report.pairs.every(pair => pair.status === 'COMPLETE') ? 'COMPLETE' : 'INCOMPLETE';
	return report;
}
