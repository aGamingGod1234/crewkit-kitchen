// Replays the program shapes of a recorded session through the real NativeProgramExecutor to count how often the
// remaining-work planning advisory would fire and what it would do to model calls and body idle time.
// usage: node src/benchmark/wake-advisory-replay.mjs <coordinator.jsonl> [--lead-ms N] [--floor-ms N] [--decision-ms N] [--wake-ms N]
// Everything this prints is SIMULATED: program action counts and durations are measured in the trace, but the
// source shape (straight line) and whether the model queues a successor are assumptions.
import { readFileSync } from 'node:fs';
import { NativeProgramExecutor } from '../native-program-executor.mjs';

const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const record = { agentId: 'replay', goalRevision: 1, provider: 'codex', model: 'replay', reasoningEffort: 'low', serviceTier: 'priority' };
const observation = () => ({ player: { x: 0, y: 64, z: 0, health: 20 }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } });

export function programsFromTrace(text) {
	return text.split('\n').filter(Boolean).map((line) => JSON.parse(line))
		.filter((row) => row.event === 'native_program_ended' && row.actions > 0 && Number.isFinite(row.durationMs))
		.map((row) => ({ reasonCode: row.reasonCode, actions: row.actions, durationMs: row.durationMs }));
}

/** Runs an n-action straight-line program whose actions take the same time each; returns when the advisory fired. */
export async function replayProgram({ actions, durationMs }, { leadMs, floorMs }) {
	const clock = { now: 0 };
	const executor = new NativeProgramExecutor({ now: () => clock.now });
	const each = durationMs / actions;
	let sequence = 1;
	let fired = null;
	await executor.run(record, { source: `${prefix} ${'await player.wait(1); '.repeat(actions)}`, planningLeadMs: leadMs, planningFloorMs: floorMs, maxActions: 256 }, {
		observation: observation(), eventSequence: sequence,
		executeAction: async () => { await Promise.resolve(); clock.now += each; return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
		cancelAction: async () => {},
		refreshObservation: async () => ({ observation: observation(), eventSequence: ++sequence }),
		onPlanningDue: (_status, details) => { fired = { atMs: Math.round(clock.now), leftMs: Math.round(durationMs - clock.now), ...details }; },
	});
	return fired;
}

// Deterministic coin so a run is reproducible.
function random(seed) { let state = seed >>> 0; return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 2 ** 32; }; }

function simulate(rows, queueRate, { gated, wakeMs, decisionMs }) {
	const next = random(7);
	const sessions = 400;
	let programs = 0, calls = 0, idleSavedMs = 0, advisories = 0;
	for (let session = 0; session < sessions; session += 1) {
		let misses = 0;
		for (const row of rows) {
			programs += 1;
			calls += 1; // the post-exhaustion wake, unless an advisory is answered with a queued successor
			if (row.fired === null || (gated && misses >= 2)) continue;
			advisories += 1;
			calls += 1;
			if (next() < queueRate) { calls -= 1; misses = 0; idleSavedMs += wakeMs - Math.max(0, decisionMs - row.fired.leftMs); }
			else misses += 1;
		}
	}
	return { callsPerExhaustedProgram: +(calls / programs).toFixed(3), advisoriesPerExhaustedProgram: +(advisories / programs).toFixed(3), bodyIdleSavedPerExhaustedProgramMs: Math.round(idleSavedMs / programs) };
}

export async function replay(programs, { leadMs, floorMs, decisionMs, wakeMs }) {
	const exhausted = programs.filter((program) => program.reasonCode === 'PROGRAM_EXHAUSTED');
	const rows = [];
	for (const program of exhausted) rows.push({ ...program, fired: await replayProgram(program, { leadMs, floorMs }) });
	const fired = rows.filter((row) => row.fired !== null);
	// Per exhausted program the baseline is one post-exhaustion wake. An advisory is one extra model turn that, when
	// the model queues a successor, replaces that wake; when it does not, the wake still happens. The gate mirrors
	// NativeToolRuntime for a model that has already queued a successor unaided: two misses in a row stop advisories. A
	// model that never queues unaided gets no early advisory at all, so it costs nothing and is not simulated here.
	const scenarios = [0, 0.25, 0.5, 0.75, 1].map((queueRate) => {
		const ungated = simulate(rows, queueRate, { gated: false, wakeMs, decisionMs });
		const gated = simulate(rows, queueRate, { gated: true, wakeMs, decisionMs });
		return { queueRate, ungated, gated };
	});
	return { exhausted: exhausted.length, firedCount: fired.length, rows, scenarios };
}

if (import.meta.url === `file:///${process.argv[1]?.replaceAll('\\', '/')}`) {
	const [path, ...flags] = process.argv.slice(2);
	const flag = (name, fallback) => { const index = flags.indexOf(name); return index === -1 ? fallback : Number(flags[index + 1]); };
	const result = await replay(programsFromTrace(readFileSync(path, 'utf8')), {
		leadMs: flag('--lead-ms', 8260), floorMs: flag('--floor-ms', 2850), decisionMs: flag('--decision-ms', 3600), wakeMs: flag('--wake-ms', 4100),
	});
	for (const row of result.rows) console.log(`${String(row.actions).padStart(3)} actions ${String(Math.round(row.durationMs)).padStart(6)} ms  ${row.fired === null ? 'no advisory' : `advisory at ${row.fired.atMs} ms, ${row.fired.leftMs} ms left (${row.fired.commands} commands)`}`);
	console.log(JSON.stringify({ exhausted: result.exhausted, advisoryFired: result.firedCount, scenarios: result.scenarios }, null, 2));
}
