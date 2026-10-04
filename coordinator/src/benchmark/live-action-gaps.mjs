import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Summarizes kept headless-run protocol audits with server clocks only. Action
// gaps are inclusive waits between a result and the next action admission; they
// can include model, coordinator, inspection, and transport time. Usage:
// node live-action-gaps.mjs <label>=<scenario-directory> ...

function parseJsonl(text) {
	return text.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
}

async function readOptionalJsonl(file) {
	const base = path.basename(file);
	const entries = await readdir(path.dirname(file));
	const generations = entries.flatMap((name) => name === base ? [{ name, generation: 0 }]
		: name.startsWith(`${base}.`) && /^[1-9]\d*$/.test(name.slice(base.length + 1))
			? [{ name, generation: Number(name.slice(base.length + 1)) }] : [])
		.sort((left, right) => right.generation - left.generation);
	const rows = [];
	// Read oldest first and one file at a time, retaining the original strict JSONL
	// parsing. A missing/corrupt retained generation must not silently become zero.
	for (const { name } of generations) {
		for (const row of parseJsonl(await readFile(path.join(path.dirname(file), name), 'utf8'))) rows.push(row);
	}
	return rows;
}

function eventName(row) {
	return row?.event ?? row?.type ?? row?.stage ?? row?.payload?.event ?? row?.payload?.type ?? null;
}

function eventPayload(row) {
	for (const candidate of [row?.fields, row?.payload, row?.data]) {
		if (candidate !== null && typeof candidate === 'object' && !Array.isArray(candidate)) return candidate;
	}
	return row ?? {};
}

function finiteNumber(value) {
	return Number.isFinite(value) ? value : null;
}

function percentile(values, fraction) {
	if (values.length === 0) return null;
	const sorted = [...values].sort((left, right) => left - right);
	return sorted[Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1)];
}

function round(value) {
	return value === null ? null : Math.round(value);
}

function firstGoalStart(protocolRows) {
	for (const row of protocolRows) {
		const envelope = row.envelope ?? row;
		const payload = envelope.payload ?? {};
		if (envelope.type === 'goal_control' && payload.operation === 'start') {
			const at = finiteNumber(payload.updatedAtEpochMs);
			if (at !== null) return at;
		}
	}
	return null;
}

function completionTimes(protocolRows) {
	let goalControlAt = null;
	let completedObservationAt = null;
	let completionResultAt = null;
	for (const row of protocolRows) {
		const envelope = row.envelope ?? row;
		const payload = envelope.payload ?? {};
		if (envelope.type === 'goal_control' && payload.operation === 'complete') {
			goalControlAt ??= finiteNumber(payload.updatedAtEpochMs);
		}
		if (envelope.type === 'observation' && payload.status === 'COMPLETED') {
			completedObservationAt ??= finiteNumber(payload.observedAtEpochMs);
		}
		if (envelope.type === 'goal_completion_result') {
			completionResultAt ??= finiteNumber(payload.observedAtEpochMs ?? payload.completedAtEpochMs ?? payload.updatedAtEpochMs);
		}
	}
	return { goalControlAt, completedObservationAt, completionResultAt };
}

function timingSegments(rows) {
	return rows.filter((row) => eventName(row) === 'native_decision_timing').map((row) => {
		const fields = eventPayload(row);
		return {
			segmentDurationMs: finiteNumber(fields.segmentDurationMs ?? fields.durationMs),
			segmentIndex: finiteNumber(fields.segmentIndex),
			traceId: fields.traceId ?? null,
		};
	});
}

export async function summarize(directory) {
	const [protocolRows, coordinatorRows, privateCoordinatorRows, reportText] = await Promise.all([
		readOptionalJsonl(path.join(directory, 'protocol.jsonl')),
		readOptionalJsonl(path.join(directory, 'coordinator.jsonl')),
		readOptionalJsonl(path.join(directory, 'coordinator-private.jsonl')),
		readFile(path.join(directory, 'report.json'), 'utf8'),
	]);
	const report = JSON.parse(reportText);
	const decisions = timingSegments([...coordinatorRows, ...privateCoordinatorRows]);
	const knownDecisionDurations = decisions.map(({ segmentDurationMs }) => segmentDurationMs).filter((duration) => duration !== null);
	const commands = new Map();
	const results = [];
	let inspections = 0;
	let observations = 0;
	for (const row of protocolRows) {
		const envelope = row.envelope ?? row;
		const payload = envelope.payload ?? {};
		if (envelope.type === 'action_command') commands.set(payload.actionId, payload);
		if (envelope.type === 'action_result') results.push(payload);
		if (envelope.type === 'inspection_request') inspections += 1;
		if (envelope.type === 'observation') observations += 1;
	}
	const actions = results.map((result) => {
		const command = commands.get(result.actionId) ?? {};
		const endedAt = finiteNumber(result.observedAtEpochMs);
		const elapsedMs = finiteNumber(result.elapsedMs);
		return {
			actionType: result.actionType,
			state: result.state,
			programId: command.provenance?.programId ?? null,
			startedAt: endedAt === null || elapsedMs === null ? null : endedAt - elapsedMs,
			endedAt,
			elapsedMs,
		};
	}).sort((left, right) => (left.startedAt ?? Number.POSITIVE_INFINITY) - (right.startedAt ?? Number.POSITIVE_INFINITY));

	const sweepGaps = [];
	const decisionGaps = [];
	const sweeps = new Map();
	let invalidGapCount = 0;
	for (let index = 1; index < actions.length; index += 1) {
		const previous = actions[index - 1];
		const current = actions[index];
		const gap = current.startedAt === null || previous.endedAt === null ? null : current.startedAt - previous.endedAt;
		if (gap === null || gap < 0) {
			invalidGapCount += 1;
			continue;
		}
		// A lookAround shares one native programId across its authored control steps.
		if (current.actionType === 'control' && previous.actionType === 'control' && current.programId !== null && current.programId === previous.programId) sweepGaps.push(gap);
		else decisionGaps.push(gap);
	}
	for (const action of actions.filter((entry) => entry.actionType === 'control' && entry.programId !== null && entry.startedAt !== null && entry.endedAt !== null)) {
		const sweep = sweeps.get(action.programId) ?? { steps: 0, startedAt: action.startedAt, endedAt: action.endedAt };
		sweep.steps += 1;
		sweep.startedAt = Math.min(sweep.startedAt, action.startedAt);
		sweep.endedAt = Math.max(sweep.endedAt, action.endedAt);
		sweeps.set(action.programId, sweep);
	}

	const firstAction = actions.find((action) => action.startedAt !== null) ?? null;
	const lastAction = [...actions].reverse().find((action) => action.endedAt !== null) ?? null;
	const goalStartedAt = firstGoalStart(protocolRows);
	const completion = completionTimes(protocolRows);
	const firstActionWaitMs = goalStartedAt === null || firstAction === null || firstAction.startedAt === null ? null : firstAction.startedAt - goalStartedAt;
	const completionObservationAt = completion.completedObservationAt ?? completion.goalControlAt ?? completion.completionResultAt;
	const completionAfterLastActionMs = lastAction === null || completionObservationAt === null ? null : completionObservationAt - lastAction.endedAt;
	const observedGoalWindowMs = goalStartedAt === null || completionObservationAt === null ? null : completionObservationAt - goalStartedAt;
	const actionDurations = actions.map((action) => action.elapsedMs);
	const actionBusyMs = actions.length > 0 && actionDurations.every((duration) => duration !== null)
		? round(actionDurations.reduce((sum, duration) => sum + duration, 0))
		: null;
	const runtimeProgramGapMs = round(sweepGaps.reduce((sum, gap) => sum + gap, 0));
	const betweenActionGapMs = round(decisionGaps.reduce((sum, gap) => sum + gap, 0));
	const accountedGoalWindowMs = [firstActionWaitMs, actionBusyMs, runtimeProgramGapMs, betweenActionGapMs, completionAfterLastActionMs]
		.every((value) => value !== null && value >= 0)
		? round(firstActionWaitMs + actionBusyMs + runtimeProgramGapMs + betweenActionGapMs + completionAfterLastActionMs)
		: null;
	const phaseAttribution = {
		goalTranslation: { durationMs: null, status: 'unobserved', evidence: 'No goal translation start/end event is present in the retained protocol or coordinator audit.' },
		firstModelSegment: { durationMs: decisions[0]?.segmentDurationMs ?? null, status: decisions[0]?.segmentDurationMs === null || decisions.length === 0 ? 'unobserved' : 'measured', meaning: 'First recorded provider segment from model invocation to its first tool request; excludes initial goal handling and coordinator time before the model call.' },
		firstActionWait: { durationMs: firstActionWaitMs === null || firstActionWaitMs < 0 ? null : round(firstActionWaitMs), status: firstActionWaitMs === null || firstActionWaitMs < 0 ? 'unobserved' : 'measured', meaning: 'Goal start accepted to first action admission; includes initial observation, planning, model response, and coordinator or transport time.' },
		authoredActionExecution: { durationMs: actionBusyMs, status: actionBusyMs === null ? 'unobserved' : 'measured', meaning: 'Sum of action_result.elapsedMs.' },
		betweenActionWait: { durationMs: betweenActionGapMs, count: decisionGaps.length, status: 'measured', meaning: 'Action result to next action admission; inclusive of model, coordinator, inspection, and transport time.' },
		runtimeProgramHandoff: { durationMs: runtimeProgramGapMs, count: sweepGaps.length, status: 'measured', meaning: 'Gap between control actions sharing a native programId.' },
		completionTail: { durationMs: completionAfterLastActionMs === null || completionAfterLastActionMs < 0 ? null : round(completionAfterLastActionMs), status: completionAfterLastActionMs === null || completionAfterLastActionMs < 0 ? 'unobserved' : 'measured', meaning: 'Last action result to completed observation, or goal completion signal if the observation is absent.' },
		completionVerification: { durationMs: null, status: 'unobserved', evidence: 'No explicit verification start/end timing is recorded; the completion tail includes propagation and any unseparated completion work.' },
		observedGoalWindow: { durationMs: observedGoalWindowMs === null || observedGoalWindowMs < 0 ? null : round(observedGoalWindowMs), accountedDurationMs: accountedGoalWindowMs, unattributedDurationMs: observedGoalWindowMs === null || accountedGoalWindowMs === null ? null : round(observedGoalWindowMs - accountedGoalWindowMs) },
	};
	return {
		status: report.status,
		lifecycle: report.lifecycle,
		elapsedMs: report.elapsedMs,
		completedActions: actions.length,
		modelDecisionSegments: decisions.length,
		modelSegmentMsTotal: decisions.length === 0 || knownDecisionDurations.length === 0 ? null : round(knownDecisionDurations.reduce((sum, duration) => sum + duration, 0)),
		modelDecisionTimingAvailable: knownDecisionDurations.length > 0,
		// These artifacts contain no capture-start/end receipt for this report's
		// run window. Even contiguous lifetime counters cannot prove a missing tail
		// or exclude earlier runs. Keep observed evidence without claiming a total.
		modelDecisionTimingComplete: false,
		modelDecisionTimingEvidence: {
			scope: 'retained_segments',
			complete: false,
			reasons: [knownDecisionDurations.length === 0 ? 'missing_timing' : 'run_window_capture_unverified'],
		},
		modelDecisionDurationCount: knownDecisionDurations.length,
		inspectionRequests: inspections,
		observations,
		sweeps: [...sweeps.values()].map((sweep) => ({ steps: sweep.steps, durationMs: round(sweep.endedAt - sweep.startedAt) })),
		sweepGapsMs: sweepGaps.map(round),
		decisionGapsMs: decisionGaps.map(round),
		invalidGapCount,
		actionBusyMs,
		phaseAttribution,
		actionSequence: actions.map((action) => action.actionType),
	};
}

function aggregateRuns(runs) {
	const sweepGaps = runs.flatMap((run) => run.sweepGapsMs);
	const sweepDurations = runs.flatMap((run) => run.sweeps.filter((sweep) => sweep.steps >= 2).map((sweep) => sweep.durationMs / sweep.steps));
	const modelTimedRuns = runs.filter((run) => run.modelDecisionTimingComplete);
	const phaseValues = (phase) => runs.map((run) => run.phaseAttribution[phase]?.durationMs).filter((value) => Number.isFinite(value) && value >= 0);
	return {
		runs,
		sweepGapCount: sweepGaps.length,
		sweepGapP50Ms: percentile(sweepGaps, 0.5),
		sweepGapP95Ms: percentile(sweepGaps, 0.95),
		sweepMsPerStepP50: percentile(sweepDurations, 0.5),
		elapsedMsP50: percentile(runs.map((run) => run.elapsedMs).filter(Number.isFinite), 0.5),
		modelDecisionTimingAvailableRuns: runs.filter((run) => run.modelDecisionTimingAvailable).length,
		modelDecisionTimingRuns: modelTimedRuns.length,
		modelDecisionTimingCoverage: runs.length === 0 ? null : modelTimedRuns.length / runs.length,
		// Availability of retained timing is not proof of full-run coverage.
		nonModelMsP50: modelTimedRuns.length === runs.length && runs.length > 0
			? percentile(runs.map((run) => run.elapsedMs - run.modelSegmentMsTotal), 0.5)
			: null,
		phaseP50Ms: {
			firstModelSegment: percentile(phaseValues('firstModelSegment'), 0.5),
			firstActionWait: percentile(phaseValues('firstActionWait'), 0.5),
			authoredActionExecution: percentile(phaseValues('authoredActionExecution'), 0.5),
			betweenActionWait: percentile(phaseValues('betweenActionWait'), 0.5),
			runtimeProgramHandoff: percentile(phaseValues('runtimeProgramHandoff'), 0.5),
			completionTail: percentile(phaseValues('completionTail'), 0.5),
			observedGoalWindow: percentile(phaseValues('observedGoalWindow'), 0.5),
		},
		passed: runs.filter((run) => run.status === 'PASSED').length,
	};
}

async function main() {
	const arms = new Map();
	for (const argument of process.argv.slice(2)) {
		const separator = argument.indexOf('=');
		if (separator < 1) throw new TypeError(`expected <label>=<scenario-dir>, got ${argument}`);
		const label = argument.slice(0, separator);
		const runs = arms.get(label) ?? [];
		runs.push({ directory: path.basename(path.dirname(argument.slice(separator + 1))), ...await summarize(argument.slice(separator + 1)) });
		arms.set(label, runs);
	}
	const output = Object.fromEntries([...arms].map(([label, runs]) => [label, aggregateRuns(runs)]));
	process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
