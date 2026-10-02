import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath, pathToFileURL } from 'node:url';

const coordinatorRoot = fileURLToPath(new URL('../../', import.meta.url));
const repositoryRoot = path.dirname(coordinatorRoot);
export const baselineRef = '267f1a3de226a55046a0f97c903f39a7bf5a898c';
export const defaultSettings = Object.freeze({ repetitions: 20, warmups: 2, modelDelayMs: 100,
	predecessorMs: 150, successorMs: 40, observationMs: 10 });
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const guard = 'player.state().health >= 10';
const hash = value => createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const tick = () => new Promise(resolve => setImmediate(resolve));
const recordTemplate = Object.freeze({ agentId: 'handoff-benchmark-agent', provider: 'codex', model: 'fixture-authored-no-inference',
	reasoningEffort: 'low', serviceTier: 'priority', goalRevision: 1, currentGoal: 'Execute the two explicitly authored controls' });

/** Freeze the complete relative-import graph from Git, rather than importing mutable baseline dependencies. */
export async function freezeBaseline(ref = baselineRef) {
	const directory = await mkdtemp(path.join(coordinatorRoot, '.native-successor-baseline-'));
	const dispose = async () => {
		const resolved = path.resolve(directory);
		if (path.dirname(resolved) !== path.resolve(coordinatorRoot) || !path.basename(resolved).startsWith('.native-successor-baseline-')) {
			throw new Error('Baseline cleanup target escaped its temporary coordinator directory');
		}
		await rm(resolved, { recursive: true, force: true });
	};
	const files = new Map();
	const visit = async relative => {
		if (files.has(relative)) return;
		const source = execFileSync('git', ['show', `${ref}:${relative}`], { cwd: repositoryRoot, encoding: 'utf8' });
		files.set(relative, hash(source));
		const destination = path.join(directory, relative.replace(/^coordinator\//, ''));
		await mkdir(path.dirname(destination), { recursive: true });
		await writeFile(destination, source);
		for (const match of source.matchAll(/(?:from\s*|import\s*\(\s*|import\s*)['"](\.[^'"]+)['"]/g)) {
			await visit(path.posix.normalize(path.posix.join(path.posix.dirname(relative), match[1])));
		}
	};
	try {
		await visit('coordinator/src/native-tool-runtime.mjs');
		const module = await import(pathToFileURL(path.join(directory, 'src/native-tool-runtime.mjs')).href);
		return { NativeToolRuntime: module.NativeToolRuntime, manifest: { commit: ref, files: Object.fromEntries([...files].sort()) }, dispose };
	} catch (error) { await dispose(); throw error; }
}

async function currentManifest() {
	const files = new Map();
	const visit = async relative => {
		if (files.has(relative)) return;
		const source = await readFile(path.join(repositoryRoot, relative), 'utf8');
		files.set(relative, hash(source));
		for (const match of source.matchAll(/(?:from\s*|import\s*\(\s*|import\s*)['"](\.[^'"]+)['"]/g)) {
			await visit(path.posix.normalize(path.posix.join(path.posix.dirname(relative), match[1])));
		}
	};
	await visit('coordinator/src/native-tool-runtime.mjs');
	return { head: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repositoryRoot, encoding: 'utf8' }).trim(), files: Object.fromEntries([...files].sort()) };
}

export function sources(settings) {
	return { predecessor: `${prefix} await player.wait(${settings.predecessorMs});`,
		successor: `${prefix} if (${guard}) { await player.wait(${settings.successorMs}); }`,
		conditional: `${prefix} await player.wait(${settings.predecessorMs}); if (${guard}) { await player.wait(${settings.successorMs}); }`,
		precondition: guard };
}

/** Real NativeToolRuntime + ArenaScript with a timer-controlled, headless fake bridge. */
export function createHarness(NativeToolRuntime, settings = defaultSettings, options = {}) {
	let sequence = 1, callNumber = 0, health = 20;
	let record = { ...recordTemplate };
	const actions = [], samples = [], events = [], timers = new Set();
	const dispatchWaiters = [], eventWaiters = [];
	const active = new Map();
	const observation = () => ({ ready: true, observedAtEpochMs: Date.now(), world: { worldId: 'handoff-fixture-world', dimension: 'minecraft:overworld' },
		player: { x: 0, y: 64, z: 0, health, food: 20, air: 300, dead: false }, entities: [], blocks: [], items: [], inventory: { items: [], tagCounts: {} } });
	const schedule = (fn, ms) => { const timer = setTimeout(() => { timers.delete(timer); fn(); }, ms); timers.add(timer); return timer; };
	let runtime;
	const finish = (entry, state = 'SUCCEEDED') => {
		if (!active.has(entry.actionId)) return;
		active.delete(entry.actionId);
		entry.completedAt = performance.now(); entry.state = state;
		runtime.onActionResult(record, { goalRevision: entry.goalRevision, actionId: entry.actionId, state,
			reasonCode: state === 'SUCCEEDED' ? 'WAIT_COMPLETED' : state, executionStarted: true, physicalAttempted: false });
	};
	runtime = new NativeToolRuntime({
		sessionId: 'native-successor-benchmark',
		registry: { get: () => record },
		bridge: { send: async (type, _agentId, payload) => {
			if (type === 'action_cancel') { const entry = active.get(payload.actionId); if (entry) finish(entry, 'CANCELLED'); return; }
			if (type !== 'action_command') return;
			const entry = { ...structuredClone(payload), dispatchedAt: performance.now(), completedAt: null };
			actions.push(entry); active.set(entry.actionId, entry);
			for (const waiter of [...dispatchWaiters]) if (actions.length >= waiter.count) { dispatchWaiters.splice(dispatchWaiters.indexOf(waiter), 1); waiter.resolve(entry); }
			if (options.autoComplete !== false) schedule(() => finish(entry, options.actionState ?? 'SUCCEEDED'), payload.arguments.durationMs);
		} },
		requestObservation: async () => {
			const sample = { requestedAt: performance.now(), returnedAt: null, eventSequence: null };
			samples.push(sample);
			if (options.beforeObservation) await options.beforeObservation(sample);
			await delay(settings.observationMs);
			sample.returnedAt = performance.now(); sample.eventSequence = ++sequence;
			return { observation: observation(), eventSequence: sequence };
		},
		onProgramEvent: (_record, event) => {
			const entry = { ...event, at: performance.now() }; events.push(entry);
			for (const waiter of [...eventWaiters]) if (waiter.predicate(entry)) { eventWaiters.splice(eventWaiters.indexOf(waiter), 1); waiter.resolve(entry); }
		},
		...options.runtime,
	});
	runtime.updateObservation(record, observation(), { eventSequence: sequence });
	const call = tool => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision,
		turnId: `fixture-turn-${++callNumber}`, callId: `fixture-call-${callNumber}`, tool }, record);
	return { runtime, actions, samples, events, call, record: () => record, finish,
		setHealth(value) { health = value; },
		async changeGoal() { await runtime.dispose(record.agentId, 'goal_changed'); record = { ...record, goalRevision: record.goalRevision + 1 }; runtime.updateObservation(record, observation(), { eventSequence: ++sequence }); },
		waitDispatch(count) { if (actions.length >= count) return Promise.resolve(actions[count - 1]); return new Promise(resolve => dispatchWaiters.push({ count, resolve })); },
		waitEvent(predicate) { const found = events.find(predicate); return found ? Promise.resolve(found) : new Promise(resolve => eventWaiters.push({ predicate, resolve })); },
		async dispose() { for (const timer of timers) clearTimeout(timer); timers.clear(); await runtime.disposeAll(); },
	};
}

export function summarize(values) {
	const sorted = [...values].sort((left, right) => left - right);
	const percentile = fraction => sorted.length ? Number(sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(3)) : null;
	return { n: values.length, p50Ms: percentile(.5), p95Ms: percentile(.95), meanMs: sorted.length ? Number((sorted.reduce((sum, value) => sum + value, 0) / sorted.length).toFixed(3)) : null };
}

export async function runBehaviorCases(NativeToolRuntime) {
	const cases = {};
	const settings = { ...defaultSettings, observationMs: 1 };
	const source = sources(settings);
	const begin = async fixture => {
		const handle = await fixture.call({ kind: 'run_program', background: true, source: source.predecessor, maxActions: 1, timeoutMs: 5000 });
		await fixture.waitDispatch(1);
		const status = await fixture.call({ kind: 'program_status', programId: handle.programId });
		return { handle, tool: { kind: 'queue_program', afterProgramId: handle.programId, goalRevision: 1,
			programVersion: status.programVersion, source: source.successor, precondition: source.precondition, maxActions: 1, timeoutMs: 5000 } };
	};
	const within = async promise => {
		let timer;
		try { return await Promise.race([promise, new Promise((_resolve, reject) => { timer = setTimeout(() => reject(new Error('Behavior case did not settle within 3 seconds')), 3000); })]); }
		finally { clearTimeout(timer); }
	};
	for (const name of ['ready', 'guard_false', 'changed_goal', 'predecessor_failure', 'late_queue_not_ready']) {
		const fixture = createHarness(NativeToolRuntime, settings, { autoComplete: false });
		try {
			const { handle, tool } = await begin(fixture);
			let queued = null, rejection = null;
			if (name !== 'late_queue_not_ready') {
				queued = await fixture.call(tool);
				assert.equal(queued.state, 'QUEUED');
				assert.equal(fixture.actions.length, 1, 'queue preparation must not execute a body action');
			}
			if (name === 'ready') {
				fixture.finish(fixture.actions[0]);
				await within(fixture.waitDispatch(2));
				assert.ok(fixture.actions[1].dispatchedAt > fixture.actions[0].completedAt);
				assert.ok(fixture.samples.some(sample => sample.requestedAt >= fixture.actions[0].completedAt && sample.returnedAt <= fixture.actions[1].dispatchedAt), 'fresh sample precedes guarded dispatch');
				fixture.finish(fixture.actions[1]);
				await within(fixture.waitEvent(event => event.event === 'program_ended' && event.programId !== handle.programId));
			} else if (name === 'guard_false') {
				fixture.setHealth(8); // Leave cached health at 20; only the fresh server sample reveals this change.
				fixture.finish(fixture.actions[0]);
				await within(fixture.waitEvent(event => event.event === 'program_handoff_rejected'));
				assert.equal(fixture.actions.length, 1, 'false fresh guard prevents successor dispatch');
			} else if (name === 'changed_goal') {
				await fixture.changeGoal();
				await tick();
				assert.equal(fixture.actions.length, 1, 'lifecycle disposal prevents old-goal successor dispatch');
				const status = await fixture.call({ kind: 'program_status' });
				assert.ok(!status.pendingSuccessor, 'old-goal queue is gone');
			} else if (name === 'predecessor_failure') {
				fixture.finish(fixture.actions[0], 'FAILED');
				await within(fixture.waitEvent(event => ['program_handoff_rejected', 'program_ended', 'program_attention'].includes(event.event)));
				await tick();
				assert.equal(fixture.actions.length, 1, 'failed predecessor must not dispatch successor');
				const status = await fixture.call({ kind: 'program_status' });
				assert.ok(!status.pendingSuccessor, 'failed predecessor drops prepared successor');
			} else {
				fixture.finish(fixture.actions[0]);
				await within(fixture.waitEvent(event => event.event === 'program_ended' && event.programId === handle.programId));
				try { await fixture.call(tool); assert.fail('late queue must reject its ended predecessor'); }
				catch (error) { assert.ok(error.code, 'late queue has a typed runtime rejection'); rejection = { code: error.code, message: error.message }; }
				assert.equal(fixture.actions.length, 1, 'no ready successor means no automatic speculative dispatch');
				const fallback = fixture.call({ kind: 'run_program', source: source.successor, maxActions: 1, timeoutMs: 5000 });
				await within(fixture.waitDispatch(2)); fixture.finish(fixture.actions[1]);
				assert.equal((await within(fallback)).reasonCode, 'PROGRAM_EXHAUSTED');
			}
			cases[name] = { result: 'PASSED', actions: fixture.actions.length, requestedObservations: fixture.samples.length,
				queued, rejection, events: fixture.events.map(({ event, reasonCode, result }) => ({ event, ...(reasonCode ? { reasonCode } : {}), ...(result ? { outcomeReasonCode: result.reasonCode } : {}) })) };
		} finally { await fixture.dispose(); }
	}
	return cases;
}

function observedWaitBetween(samples, start, end) {
	const intervals = samples.filter(sample => sample.returnedAt !== null).map(sample => [Math.max(start, sample.requestedAt), Math.min(end, sample.returnedAt)]).filter(([left, right]) => right > left).sort((a, b) => a[0] - b[0]);
	let total = 0, rightmost = start;
	for (const [left, right] of intervals) { total += Math.max(0, right - Math.max(left, rightmost)); rightmost = Math.max(rightmost, right); }
	return total;
}

async function runScenario(NativeToolRuntime, scenario, settings) {
	const fixture = createHarness(NativeToolRuntime, settings);
	const source = sources(settings);
	let modelStartedAt = null, modelFinishedAt = null, queueAcceptedAt = null, queueResult = null;
	const start = performance.now();
	try {
		if (scenario === 'individual') {
			await fixture.call({ kind: 'action', actionType: 'wait', arguments: { durationMs: settings.predecessorMs } });
			await fixture.call({ kind: 'observe' });
			modelStartedAt = performance.now(); await delay(settings.modelDelayMs); modelFinishedAt = performance.now();
			await fixture.call({ kind: 'action', actionType: 'wait', arguments: { durationMs: settings.successorMs } });
			await fixture.call({ kind: 'observe' });
		} else if (scenario === 'foreground') {
			const predecessor = await fixture.call({ kind: 'run_program', source: source.predecessor, maxActions: 1, timeoutMs: 10_000 });
			assert.equal(predecessor.reasonCode, 'PROGRAM_EXHAUSTED');
			modelStartedAt = performance.now(); await delay(settings.modelDelayMs); modelFinishedAt = performance.now();
			const successor = await fixture.call({ kind: 'run_program', source: source.successor, maxActions: 1, timeoutMs: 10_000 });
			assert.equal(successor.reasonCode, 'PROGRAM_EXHAUSTED');
		} else if (scenario === 'conditional') {
			const result = await fixture.call({ kind: 'run_program', source: source.conditional, maxActions: 2, timeoutMs: 10_000 });
			assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
		} else if (scenario === 'queued') {
			const handle = await fixture.call({ kind: 'run_program', background: true, source: source.predecessor, maxActions: 1, timeoutMs: 10_000 });
			await fixture.waitDispatch(1);
			const status = await fixture.call({ kind: 'program_status', programId: handle.programId });
			modelStartedAt = performance.now(); await delay(settings.modelDelayMs); modelFinishedAt = performance.now();
			queueResult = await fixture.call({ kind: 'queue_program', afterProgramId: handle.programId, goalRevision: 1,
				programVersion: status.programVersion, source: source.successor, precondition: source.precondition, maxActions: 1, timeoutMs: 10_000 });
			queueAcceptedAt = performance.now();
			assert.equal(queueResult.state, 'QUEUED');
			assert.equal(fixture.actions.length, 1, 'a prepared successor must not dispatch while its predecessor owns the body');
			await fixture.waitDispatch(2);
			const ended = await fixture.waitEvent(event => event.event === 'program_ended' && event.programId !== handle.programId);
			assert.equal(ended.result.reasonCode, 'PROGRAM_EXHAUSTED');
		} else throw new Error(`Unknown scenario: ${scenario}`);
		assert.equal(fixture.actions.length, 2);
		assert.ok(fixture.actions.every(action => action.state === 'SUCCEEDED'));
		const shape = fixture.actions.map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
		assert.deepEqual(shape, [settings.predecessorMs, settings.successorMs].map(durationMs => ({ actionType: 'wait', arguments: { durationMs } })));
		assert.ok(fixture.actions.every(action => action.provenance.provider === recordTemplate.provider && action.provenance.model === recordTemplate.model));
		const completedAt = fixture.actions[0].completedAt, dispatchedAt = fixture.actions[1].dispatchedAt;
		const dispatchGapMs = dispatchedAt - completedAt;
		const observationWaitMs = observedWaitBetween(fixture.samples, completedAt, dispatchedAt);
		const modelDelayAfterCompletionMs = modelStartedAt === null ? 0 : Math.max(0, Math.min(dispatchedAt, modelFinishedAt) - Math.max(completedAt, modelStartedAt));
		return { durationMs: performance.now() - start, dispatchGapMs, observationWaitMs, modelDelayAfterCompletionMs,
			otherHandoffMs: dispatchGapMs - observationWaitMs - modelDelayAfterCompletionMs,
			modelDelayMeasuredMs: modelStartedAt === null ? 0 : modelFinishedAt - modelStartedAt,
			preparedBeforeCompletion: scenario === 'queued' ? queueAcceptedAt < completedAt : null,
			actions: shape, actionShapeHash: hash(shape), requestedObservations: fixture.samples.length,
			queueResult, eventNames: fixture.events.map(event => event.event) };
	} finally { await fixture.dispose(); }
}

function summarizeRows(rows) {
	return { dispatchGap: summarize(rows.map(row => row.dispatchGapMs)), observationBarrier: summarize(rows.map(row => row.observationWaitMs)),
		modelDelayAfterCompletion: summarize(rows.map(row => row.modelDelayAfterCompletionMs)), otherHandoff: summarize(rows.map(row => row.otherHandoffMs)),
		modelDelayMeasured: summarize(rows.map(row => row.modelDelayMeasuredMs)), duration: summarize(rows.map(row => row.durationMs)),
		actions: rows.length * 2, requestedObservations: rows.reduce((sum, row) => sum + row.requestedObservations, 0),
		actionShapeHash: rows[0]?.actionShapeHash ?? null, preparedBeforeCompletion: rows.filter(row => row.preparedBeforeCompletion).length };
}

export async function runBenchmark({ mode = 'compare', settings = defaultSettings, ref = baselineRef } = {}) {
	const baseline = await freezeBaseline(ref);
	try {
		const optimized = mode === 'baseline' ? null : (await import('../native-tool-runtime.mjs')).NativeToolRuntime;
		const output = { benchmark: 'native-successor-handoff', result: 'PASSED', measuredAt: new Date().toISOString(), baseline: baseline.manifest,
			...(mode === 'baseline' ? {} : { optimized: await currentManifest() }), environment: { node: process.version, platform: process.platform, arch: process.arch },
			settings, controller: 'timer-controlled fake bridge; real NativeToolRuntime and ArenaScript; no model/provider inference or Minecraft server',
			authorship: 'Explicit fixture source and guard stand in for selected-model tool output. Decision time is a separately injected assumption.',
			sources: sources(settings), comparisons: {} };
		for (const [name, beforeScenario, afterScenario] of [['individual_to_conditional', 'individual', 'conditional'], ['foreground_to_queued', 'foreground', 'queued'],
			['conditional_runtime_control', 'conditional', 'conditional']]) {
			const rows = { before: [], after: [] };
			for (let index = 0; index < settings.warmups + settings.repetitions; index++) {
				for (const arm of index % 2 === 0 ? ['before', 'after'] : ['after', 'before']) {
					if (mode === 'baseline' && arm === 'after') continue;
					const row = await runScenario(arm === 'before' ? baseline.NativeToolRuntime : optimized, arm === 'before' ? beforeScenario : afterScenario, settings);
					if (index >= settings.warmups) rows[arm].push(row);
				}
			}
			if (mode !== 'baseline') assert.ok(rows.before.every((row, index) => row.actionShapeHash === rows.after[index].actionShapeHash), 'paired action order and shapes must match');
			output.comparisons[name] = { beforeScenario, afterScenario, before: summarizeRows(rows.before), ...(mode === 'baseline' ? {} : {
				after: summarizeRows(rows.after), pairedGapWins: rows.after.filter((row, index) => row.dispatchGapMs < rows.before[index].dispatchGapMs).length }), rows };
		}
		if (mode !== 'baseline') output.behaviorCases = await runBehaviorCases(optimized);
		return output;
	} finally { await baseline.dispose(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	const flags = Object.fromEntries(process.argv.slice(2).map(value => { const [key, ...rest] = value.replace(/^--/, '').split('='); return [key, rest.join('=')]; }));
	const settings = { ...defaultSettings };
	for (const [flag, key] of [['repetitions', 'repetitions'], ['warmups', 'warmups'], ['model-delay-ms', 'modelDelayMs'], ['predecessor-ms', 'predecessorMs'], ['successor-ms', 'successorMs'], ['observation-ms', 'observationMs']]) {
		if (flags[flag] !== undefined) settings[key] = Number(flags[flag]);
	}
	assert.ok(Object.values(settings).every(value => Number.isInteger(value) && value >= 0));
	assert.ok(settings.repetitions >= 1 && settings.repetitions <= 500);
	assert.ok(settings.predecessorMs >= 1 && settings.successorMs >= 1 && settings.modelDelayMs <= 5000);
	const result = await runBenchmark({ mode: flags.mode ?? 'compare', settings, ref: flags['baseline-ref'] ?? baselineRef });
	if (flags.json) { const destination = path.resolve(flags.json); await mkdir(path.dirname(destination), { recursive: true }); await writeFile(destination, `${JSON.stringify(result, null, 2)}\n`); }
	const printable = flags.quiet === 'true' ? { benchmark: result.benchmark, result: result.result, settings: result.settings,
		comparisons: Object.fromEntries(Object.entries(result.comparisons).map(([name, { before, after, pairedGapWins }]) => [name, { before, after, pairedGapWins }])),
		behaviorCases: result.behaviorCases } : result;
	process.stdout.write(`${JSON.stringify(printable, null, 2)}\n`);
}
