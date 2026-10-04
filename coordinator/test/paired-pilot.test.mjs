import assert from 'node:assert/strict';
import test from 'node:test';
import { createBudgetClock, pairedAdmission, preparePairedPilot, runPairedPilot, headlessTrialOutcome } from '../src/benchmark/paired-pilot.mjs';
import { normalizeHeadlessScenario, runHeadlessScenario } from '../src/headless-matrix.mjs';

const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const config = (overrides = {}) => ({ deadlineMs: 1400, startupMs: 100, cleanupMs: 50,
	scenarios: [{ id: 'iron', seed: '9223372036854775807', trialMs: 200 }],
	arms: ['A', 'B'].map((id, index) => ({ id, profile: { ...profile },
		artifactSha256: String(index + 1).repeat(64), sourceManifestSha256: String(index + 3).repeat(64) })), ...overrides });
function fixture(overrides = {}) {
	const f = { time: 0, calls: [], resources: new Set() };
	f.callbacks = { now: () => f.time,
		startup: async context => { f.calls.push(['startup', context]); f.time += 100; const resource = {}; f.resources.add(resource); return resource; },
		runTrial: async context => { f.calls.push(['trial', context]); f.time += 200; return { status: 'PASSED' }; },
		cleanup: async context => { f.calls.push(['cleanup', context]); f.time += 50; f.resources.delete(context.resource); return { ok: true }; },
		...overrides };
	return f;
}

test('admission includes both setups, equal trials and both cleanups at the exact boundary', async () => {
	const c = config();
	assert.deepEqual(pairedAdmission({ ...c, trialMs: 200, nowMs: 700 }), { admitted: true, armMs: 350, requiredMs: 700, remainingMs: 700 });
	for (const remaining of [0, 699, 699.999]) {
		const f = fixture(); const report = await runPairedPilot(config({ deadlineMs: remaining }), f.callbacks);
		assert.equal(report.pairs[0].status, 'NOT_STARTED_PAIR_RESERVE'); assert.equal(report.counts.started, 0); assert.deepEqual(f.calls, []);
	}
	const f = fixture(); const report = await runPairedPilot(config({ deadlineMs: 700 }), f.callbacks);
	assert.equal(report.pairs[0].status, 'COMPLETE'); assert.equal(report.pairs[1].status, 'NOT_STARTED_PAIR_RESERVE');
	assert.equal(report.elapsedMs, 700); assert.equal(report.counts.attempted, 2); assert.equal(f.resources.size, 0);
});

test('AB and BA retain exact seed, profile and each artifact identity with full phase accounting', async () => {
	const input = config(); const prepared = preparePairedPilot(input); input.arms[0].profile.model = 'changed';
	assert.equal(prepared.arms[0].profile.model, profile.model);
	assert.throws(() => { prepared.pairs[0].arms[0].artifactSha256 = '0'.repeat(64); }, TypeError);
	const f = fixture(); const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.status, 'COMPLETE'); assert.equal(report.elapsedMs, 1400); assert.equal(report.promotionApproved, false);
	assert.deepEqual(f.calls.filter(([name]) => name === 'trial').map(([, c]) => c.arm.id), ['A', 'B', 'B', 'A']);
	assert.deepEqual(f.calls.map(([, c]) => c.deadlineMs), [100, 300, 350, 450, 650, 700, 800, 1000, 1050, 1150, 1350, 1400]);
	for (const [, c] of f.calls) { assert.equal(c.pair.seed, '9223372036854775807'); assert.deepEqual(c.arm.profile, profile); }
	assert.equal(report.pairs[0].trials[0].artifactSha256, report.pairs[1].trials[1].artifactSha256);
	assert.deepEqual(report.pairs[0].trials.map(t => t.durationMs), [350, 350]);
	assert.equal(report.pairs[0].durationMs, 700); assert.equal(report.overrunMs, 0);
	assert.deepEqual(report.counts, { scheduled: 4, started: 4, attempted: 4, outcomes: { PASSED: 4 } });
});

test('invalid, missing, overflow and unequal inputs are rejected before resources start', async () => {
	for (const change of [c => delete c.deadlineMs, c => { c.startupMs = -1; }, c => { c.cleanupMs = NaN; },
		c => { c.scenarios[0].trialMs = 0; }, c => { c.scenarios[0].trialMs = Number.MAX_SAFE_INTEGER; },
		c => { c.arms[1].profile.model = 'other'; }, c => { c.arms[0].artifactSha256 = 'bad'; },
		c => { c.scenarios[0].seed = 9223372036854775807; }, c => { c.arms[1].auxiliaryProfile = { ...profile, serviceTier: 'other' }; }]) {
		const c = config(); change(c); const f = fixture(); await assert.rejects(runPairedPilot(c, f.callbacks)); assert.deepEqual(f.calls, []);
	}
});

test('early completion does not enlarge any phase allowance or lend the peer reserve', async () => {
	const f = fixture(); f.callbacks.startup = async context => { f.calls.push(['startup', context]); f.time += 25; return {}; };
	f.callbacks.runTrial = async context => { f.calls.push(['trial', context]); assert.equal(context.deadlineMs - f.time, 200); f.time += 50; return { status: 'PASSED' }; };
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.status, 'COMPLETE'); assert.equal(report.elapsedMs, 500);
	assert.deepEqual(report.pairs.flatMap(p => p.trials).map(t => t.durationMs), [125, 125, 125, 125]);
});

test('honest failures and timeouts receive their peer instead of survivor-only results', async () => {
	for (const status of ['FAILED', 'TIMED_OUT']) {
		const f = fixture(); f.callbacks.runTrial = async () => { f.time += 200; return { status }; };
		const report = await runPairedPilot(config(), f.callbacks);
		assert.equal(report.status, 'COMPLETE'); assert.equal(report.counts.outcomes[status], 4); assert.equal(report.elapsedMs, 1400);
	}
});

test('startup, trial and cleanup overruns are fully charged and cannot start a peer', async () => {
	for (const name of ['startup', 'runTrial', 'cleanup']) {
		const f = fixture(); const original = f.callbacks[name];
		f.callbacks[name] = async context => { const value = await original(context); f.time += 1; return value; };
		const report = await runPairedPilot(config(), f.callbacks); const trial = report.pairs[0].trials[0];
		assert.equal(report.status, 'INCOMPLETE'); assert.equal(report.counts.started, 1); assert.equal(f.resources.size, 0);
		assert.equal(report.pairs[0].trials[1].status, 'NOT_STARTED');
		assert.equal(trial.phases[name === 'runTrial' ? 'trial' : name].overrunMs, 1);
		assert.equal(trial.durationMs, name === 'startup' ? 151 : 351);
		assert.ok(f.calls.every(([, c]) => c.deadlineMs <= 350));
		assert.equal(trial.status, name === 'cleanup' ? 'PASSED' : 'TIMED_OUT');
	}
});

test('partial setup failure is cleaned and does not expose private errors or resources', async () => {
	const f = fixture(); const resource = { secret: 'private-resource' };
	f.callbacks.startup = async ({ own }) => { own(resource); f.resources.add(resource); f.time += 17; throw new Error('private-provider-message'); };
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(f.resources.size, 0); assert.equal(report.pairs[0].trials[0].status, 'ERROR');
	assert.equal(report.pairs[0].trials[0].durationMs, 67); assert.equal(report.counts.attempted, 0);
	assert.doesNotMatch(JSON.stringify(report), /private-resource|private-provider-message/);
});

test('interruption charges attempted work and cleanup gets a usable independent signal', async () => {
	for (const stage of ['before', 'startup', 'trial']) {
		const controller = new AbortController(); const f = fixture({ signal: controller.signal });
		if (stage === 'before') controller.abort();
		else {
			const name = stage === 'trial' ? 'runTrial' : 'startup'; const operation = f.callbacks[name];
			f.callbacks[name] = async context => { const result = await operation(context); controller.abort(); return result; };
		}
		const report = await runPairedPilot(config(), f.callbacks);
		assert.equal(report.status, 'INCOMPLETE'); assert.equal(report.counts.started, stage === 'before' ? 0 : 1);
		assert.equal(report.counts.attempted, stage === 'trial' ? 1 : 0);
		if (stage !== 'before') { assert.equal(report.pairs[0].trials[0].status, 'INTERRUPTED'); assert.equal(f.calls.at(-1)[1].signal.aborted, false); }
		assert.equal(f.resources.size, 0);
	}
});

test('unknown, thrown and failed cleanup results block resource reuse', async () => {
	for (const cleanup of [async () => undefined, async () => ({ ok: false }), async () => { throw new Error('private'); }]) {
		const f = fixture({ cleanup }); const report = await runPairedPilot(config(), f.callbacks);
		assert.equal(report.counts.started, 1); assert.equal(report.status, 'INCOMPLETE'); assert.notEqual(report.pairs[0].trials[0].cleanup, 'CLEAN');
	}
	const f = fixture({ runTrial: async () => ({ status: 'PASSED', resourcesClean: false }) });
	const report = await runPairedPilot(config(), f.callbacks); assert.equal(report.counts.started, 1);
	assert.equal(report.pairs[0].trials[0].resourcesClean, false);
});

test('monotonic budget clock accepts fractional progress, rejects regression and ignores wall-clock jumps', async () => {
	let value = 1.25; const clock = createBudgetClock(() => value);
	assert.equal(clock(), 1.25); value = 1.75; assert.equal(clock(), 1.75); value = 1.5; assert.throws(clock, /backwards/);
	for (const bad of [NaN, Infinity, -1]) assert.throws(createBudgetClock(() => bad));
	const realClock = createBudgetClock(); const start = realClock(); const original = Date.now;
	try { Date.now = () => -1e12; assert.ok(realClock() >= start); } finally { Date.now = original; }
	const f = fixture(); f.callbacks.runTrial = async () => { f.time = 99; return { status: 'PASSED' }; };
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.clockInvalid, true); assert.equal(report.elapsedMs, null); assert.equal(report.counts.started, 1);
	assert.equal(f.resources.size, 0); assert.equal(report.pairs[0].trials[0].status, 'CLOCK_INVALID');
});

test('runner overhead is charged within setup, not an extra hidden global grant', async () => {
	const f = fixture(); f.callbacks.now = () => { f.time += 0.125; return f.time; };
	f.callbacks.startup = async () => { f.time += 90; return {}; };
	f.callbacks.runTrial = async ({ deadlineMs }) => { assert.equal(deadlineMs - f.time, 200); f.time += 190; return { status: 'PASSED' }; };
	f.callbacks.cleanup = async () => { f.time += 40; return { ok: true }; };
	const report = await runPairedPilot(config({ deadlineMs: 1500 }), f.callbacks);
	assert.equal(report.status, 'COMPLETE'); assert.ok(report.elapsedMs > 4 * 320);
});

test('headless outcome mapping keeps timeout, skip and cleanup failure distinct', () => {
	assert.deepEqual(headlessTrialOutcome({ status: 'FAILED', classification: 'TIMEOUT', cleanup: { status: 'CLEAN' } }), { status: 'TIMED_OUT', resourcesClean: true });
	assert.deepEqual(headlessTrialOutcome({ status: 'FAILED', classification: 'CLEANUP_FAILURE', cleanup: { status: 'FAILED' } }), { status: 'ERROR', resourcesClean: false });
	assert.equal(headlessTrialOutcome({ status: 'SKIPPED' }).status, 'SKIPPED'); assert.equal(headlessTrialOutcome(null).status, 'ERROR');
});

test('actual headless scenario entry point is consumed offline without a provider or game', async () => {
	const scenario = normalizeHeadlessScenario({ id: 'offline', ...profile, task: 'offline', timeoutMs: 200,
		assert: [{ type: 'lifecycle', state: 'COMPLETED' }] });
	let closed = 0;
	const result = await runHeadlessScenario({ scenario: { ...scenario, profileAvailable: false },
		runDirectory: process.cwd(), rcon: { command: async () => { assert.fail('no game command permitted'); }, close: async () => { closed++; } },
		readFile: async () => { assert.fail('no filesystem fixture required'); }, writeFile: async () => {}, now: () => 0 });
	assert.equal(closed, 1); assert.equal(result.status, 'SKIPPED');
	const f = fixture({ runTrial: async () => headlessTrialOutcome(result) });
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.counts.outcomes.SKIPPED, 1); assert.equal(report.counts.started, 1); assert.equal(report.status, 'INCOMPLETE');
});

test('thrown timeout remains a timed-out attempt and thrown trial failure is retained', async () => {
	for (const code of ['HEADLESS_TIMEOUT', 'ERROR']) {
		const f = fixture({ runTrial: async () => { throw Object.assign(new Error('private'), { code }); } });
		const report = await runPairedPilot(config(), f.callbacks);
		assert.equal(report.pairs[0].trials[0].status, code === 'HEADLESS_TIMEOUT' ? 'TIMED_OUT' : 'ERROR');
		assert.equal(report.counts.attempted, code === 'HEADLESS_TIMEOUT' ? 4 : 1);
		assert.equal(f.resources.size, 0);
	}
});

test('time used between setup and trial cannot silently shorten the equal trial allocation', async () => {
	const f = fixture(); let setupReturned = false;
	f.callbacks.startup = async () => { f.time = 100; setupReturned = true; return {}; };
	let samples = 0;
	f.callbacks.now = () => { if (setupReturned && ++samples >= 3) f.time += 1; return f.time; };
	f.callbacks.runTrial = async () => { assert.fail('shortened trial must not start'); };
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.pairs[0].trials[0].status, 'NOT_STARTED_RESERVE_OVERRUN');
	assert.equal(report.counts.attempted, 0); assert.equal(report.counts.started, 1);
});

test('a noncooperative overrun is visible in full and cleanup gets no new deadline credit', async () => {
	const f = fixture(); f.callbacks.runTrial = async () => { f.time += 1500; return { status: 'PASSED' }; };
	f.callbacks.cleanup = async ({ deadlineMs }) => { assert.equal(deadlineMs, 350); assert.ok(deadlineMs < f.time); f.time += 50; return { ok: true }; };
	const report = await runPairedPilot(config(), f.callbacks);
	assert.equal(report.counts.started, 1); assert.equal(report.elapsedMs, 1650); assert.equal(report.overrunMs, 250);
	assert.equal(report.pairs[0].trials[0].phases.trial.overrunMs, 1300);
	assert.equal(report.pairs[0].trials[0].cleanup, 'TIMED_OUT');
});

test('default monotonic clock supports the helper entry point without timers or live resources', async () => {
	const now = createBudgetClock();
	const report = await runPairedPilot(config({ deadlineMs: now() + 1400 }), {
		startup: async () => ({}), runTrial: async () => ({ status: 'PASSED' }), cleanup: async () => ({ ok: true }),
	});
	assert.equal(report.status, 'COMPLETE'); assert.equal(report.counts.attempted, 4); assert.ok(report.elapsedMs >= 0);
});

const seed = '-9223372036854775808';
async function naturalReport(mode, hooks = {}) {
  const scenario = normalizeHeadlessScenario({ id: 'natural-review', ...profile, task: 'Obtain oak logs', timeoutMs: 1000,
    world: { mode: 'natural', seed }, requireFactualSuccess: true,
    assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'data get entity {agent} Inventory', match: 'minecraft:oak_log' }] });
  const worldManifest = { version: 1, scenarioId: scenario.id, worldId: 'headless-review-fixture', fresh: true, world: scenario.world,
    savedSpawn: { source: 'level.dat', dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
    spawnLoading: { operation: 'temporary_spawn_chunk_loading', x: 0, z: 0, ready: true, elapsedMs: 1, terrainModified: false, inventoryModified: false } };
  const audit = []; const commands = []; let started = false; let closed = 0; let runnerTime = 0;
  const report = await runHeadlessScenario({ scenario, worldManifest, protocolAudit: audit,
    runDirectory: process.cwd(), now: () => runnerTime,
    fileSize: async () => 0, readFile: async () => { throw Object.assign(new Error('missing fixture file'), { code: 'ENOENT' }); },
    writeFile: async () => { if (mode === 'persist-failure') throw new Error('disk full'); }, poll: async () => { if (mode === 'timeout') runnerTime = 1000; },
    ...hooks,
    rcon: { close: async () => { closed++; }, command: async command => {
      hooks.onCommand?.(command);
      commands.push(command);
      if (command === 'seed') {
        if (mode === 'transport-error') throw Object.assign(new Error('connection closed'), { code: 'RCON_CLOSED' });
        return { text: `Seed: [${mode === 'wrong-seed' ? '2' : seed}]` };
      }
      if (command === 'difficulty') return { text: 'The difficulty is normal' };
      if (command.includes(' if loaded ')) return { text: 'The time is 1' };
      if (command.includes('summon-configured')) {
        const name = command.split(' ').at(-1);
        audit.push({ envelope: { type: 'agent_registered', agentId: 'offline-agent', payload: { agentId: 'offline-agent', name, ...profile, ...(mode === 'profile-mismatch' ? { reasoningEffort: 'low' } : {}) } } });
        return { text: `Created ${name}. It is ready for a task.` };
      }
      if (command.startsWith('codex start ')) started = true;
      if (command.startsWith('codex status ')) return { text: mode === 'timeout' ? 'state=RUNNING' : 'state=COMPLETED' };
      if (command.startsWith('codex stop ')) { runnerTime += 3; return { text: `Stopped ${command.split(' ').at(-1)}.` }; }
      if (mode === 'timeout' && runnerTime >= 1000 && command.startsWith('codex remove ')) runnerTime += 2;
      if (command.endsWith(' Pos')) return { text: 'player has the following entity data: [0.5d, 64.0d, 0.5d]' };
      if (command.endsWith(' Inventory')) return { text: `player has the following entity data: ${started && mode !== 'objective-failure' ? '[{id:"minecraft:oak_log"}]' : '[]'}` };
      return { text: 'ok' };
    } },
  });
  assert.equal(closed, 1); return { report, commands, runnerFinishedAtMs: runnerTime };
}


test('real runner infrastructure, seed and profile failures cannot become complete pairs', async () => {
 for (const [mode, expected] of Object.entries({ 'transport-error': 'ERROR', 'wrong-seed': 'ERROR', 'profile-mismatch': 'ERROR', pass: 'PASSED', 'objective-failure': 'FAILED', timeout: 'TIMED_OUT', 'persist-failure': 'ERROR' })) {
  const { report } = await naturalReport(mode);
  const adapted = headlessTrialOutcome(report);
  assert.equal(adapted.status, expected, mode);
  const f = fixture({ runTrial: async () => adapted });
  const paired = await runPairedPilot(config(), f.callbacks);
  assert.equal(paired.status, expected === 'ERROR' ? 'INCOMPLETE' : 'COMPLETE', mode);
  assert.equal(paired.counts.attempted, expected === 'ERROR' ? 1 : 4, mode);
 }
});

test('invalid phase-entry clock blocks startup and trial while allowing stop-only cleanup', async () => {
 for (const failedRead of [4, 7]) {
  const f = fixture(); let reads = 0, cleanupCalls = 0;
  f.callbacks.now = () => ++reads >= failedRead ? NaN : f.time;
  for (const key of ['startup', 'runTrial']) {
   const original = f.callbacks[key];
   f.callbacks[key] = async args => { assert.ok(reads < failedRead, key); return original(args); };
  }
  const cleanup = f.callbacks.cleanup;
  f.callbacks.cleanup = async args => { cleanupCalls++; assert.equal(args.stopOnly, true); return cleanup(args); };
  const report = await runPairedPilot(config(), f.callbacks);
  assert.equal(cleanupCalls, 1); assert.equal(report.counts.attempted, 0);
  assert.equal(report.clockInvalid, true); assert.equal(report.elapsedMs, null); assert.equal(f.resources.size, 0);
 }
});

test('real runner yields before post-stop evidence and uses only the supplied cleanup boundary', async () => {
 let cleanup = false; const commands = [];
 const actual = await naturalReport('timeout', { trialDeadlineMs: 1000,
  onCleanup: async event => { assert.equal(event.classification, 'TIMEOUT'); cleanup = true; return 1005; },
  onCommand: command => { if (command.startsWith('codex stop ') || command.startsWith('codex remove ')) assert.equal(cleanup, true); commands.push(command); },
 });
 assert.equal(actual.report.classification, 'TIMEOUT'); assert.equal(actual.runnerFinishedAtMs, 1005);
 assert.equal(actual.report.factualSuccess, true);
 assert.ok(commands.some(c => c.startsWith('codex stop ')));
});

test('invalid seed reaches cleanup boundary before reclaiming the partially owned spawn ticket', async () => {
 let boundaries = 0;
 const actual = await naturalReport('wrong-seed', { trialDeadlineMs: 1000,
  onCleanup: async event => { boundaries++; assert.equal(event.classification, 'ERROR'); return 10; },
 });
 assert.equal(boundaries, 1); assert.equal(actual.report.classification, 'ERROR');
 assert.equal(headlessTrialOutcome(actual.report).status, 'ERROR');
});
