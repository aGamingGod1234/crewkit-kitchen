import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, mkdir } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { performance } from 'node:perf_hooks';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { runPairedCli, phaseWorker } from '../src/benchmark/paired-cli.mjs';
import { runPairedPilot } from '../src/benchmark/paired-pilot.mjs';
import { normalizeHeadlessScenario, runHeadlessScenario } from '../src/headless-matrix.mjs';
import { runnerPhaseChannel, readPhaseMessage, writePhaseMessage } from '../src/benchmark/paired-runner-channel.mjs';

// One file owns its inert child fixture so the regression needs no generated helper.
if (process.argv[2] === '--fixture-worker') {
const [mode, requestPath] = process.argv.slice(3);
const requestConfig = JSON.parse(await readFile(requestPath, 'utf8'));
const directory = requestConfig.channel;
const channel = runnerPhaseChannel(directory);
const emit = (kind, value) => process.stdout.write(`PAIR_EVENT ${JSON.stringify({ kind, value })}\n`);
const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const seed = '-9223372036854775808';
const scenario = normalizeHeadlessScenario({ id: 'natural-review', ...profile, task: 'Obtain oak logs', timeoutMs: 2000,
  world: { mode: 'natural', seed }, requireFactualSuccess: true,
  assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'data get entity {agent} Inventory', match: 'minecraft:oak_log' }] });
const worldManifest = { version: 1, scenarioId: scenario.id, worldId: 'headless-review-fixture', fresh: true, world: scenario.world,
  savedSpawn: { source: 'level.dat', dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
  spawnLoading: { operation: 'temporary_spawn_chunk_loading', x: 0, z: 0, ready: true, elapsedMs: 1, terrainModified: false, inventoryModified: false } };
const audit = [];
let started = false;
const scenarioPromise = (async () => {
  const trialDeadlineMs = await channel.ready();
  await writeFile(path.join(directory, 'deadline-observed.json'), JSON.stringify({ trialDeadlineMs, nowMs: performance.now(), mode }));
  return runHeadlessScenario({ scenario, worldManifest, protocolAudit: audit, runDirectory: directory,
    now: () => performance.now(), trialDeadlineMs,
    onCleanup: async event => {
      await writeFile(path.join(directory, 'entered-cleanup.json'), JSON.stringify(event));
      return channel.cleanup(event);
    },
    fileSize: async () => 0, readFile: async () => { throw Object.assign(new Error('missing fixture file'), { code: 'ENOENT' }); },
    writeFile: async () => {},
    rcon: { close: async () => {}, command: async command => {
      if (command === 'seed') return { text: `Seed: [${seed}]` };
      if (command === 'difficulty') return { text: 'The difficulty is normal' };
      if (command.includes(' if loaded ')) return { text: 'The time is 1' };
      if (command.includes('summon-configured')) {
        const name = command.split(' ').at(-1);
        audit.push({ envelope: { type: 'agent_registered', agentId: 'offline-agent', payload: { agentId: 'offline-agent', name, ...profile } } });
        return { text: `Created ${name}. It is ready for a task.` };
      }
      if (command.startsWith('codex start ')) started = true;
      if (command.startsWith('codex status ')) return { text: mode === 'timeout' ? 'state=RUNNING' : 'state=COMPLETED' };
      if (command.startsWith('codex stop ')) return { text: `Stopped ${command.split(' ').at(-1)}.` };
      if (command.endsWith(' Pos')) return { text: 'player has the following entity data: [0.5d, 64.0d, 0.5d]' };
      if (command.endsWith(' Inventory')) return { text: `player has the following entity data: ${started ? '[{id:"minecraft:oak_log"}]' : '[]'}` };
      return { text: 'ok' };
    } },
  });
})();

// Minimal local relay uses the same requests as paired-phase-worker.ps1.
// It omits the Windows supervisor hop, so it gives the timeout more room.
for await (const line of createInterface({ input: process.stdin })) {
  const request = JSON.parse(line);
  if (request.phase === 'startup') {
    await readPhaseMessage(directory, 'runner-ready');
    emit('startup', { modSha256: requestConfig.arm.artifactSha256, worldId: worldManifest.worldId });
  } else if (request.phase === 'trial') {
    await writePhaseMessage(directory, 'runner-trial', { clock: request.clock, cutoffMs: request.cutoffMs });
    emit('trial', await readPhaseMessage(directory, 'runner-trial-ended'));
  } else if (request.phase === 'cleanup') {
    if (mode === 'blocked-cleanup') { await writeFile(path.join(directory, 'blocked-cleanup.json'), JSON.stringify({ entered: true })); await new Promise(() => {}); }
    await writePhaseMessage(directory, 'runner-cleanup', { clock: request.clock, cutoffMs: request.cutoffMs });
    const report = await scenarioPromise;
    await writeFile(path.join(directory, 'finished-report.json'), JSON.stringify(report));
    emit('cleanup', { wrapper: { ok: true }, runnerExit: report.status === 'FAILED' ? 1 : 0, runner: report });
    process.exit(0);
  }
}

} else {
test('actual paired CLI preserves full-budget timeouts and still contains blocked cleanup', { timeout: 180_000 }, async () => {
const directory = process.env.G08_EVIDENCE_DIR ?? tmpdir();
await mkdir(directory, { recursive: true });
const root = fileURLToPath(new URL('../../', import.meta.url));
const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const seed = '-9223372036854775808';
const results = [];
for (const mode of ['pass', 'timeout', 'blocked-cleanup']) {
  const fixture = await mkdtemp(path.join(directory, `cli-${mode}-`));
  const matrixPath = path.join(fixture, 'matrix.json');
  await writeFile(matrixPath, JSON.stringify({ version: 1, scenarios: [{
    id: 'natural-review', ...profile, task: 'Obtain oak logs', timeoutMs: 2000, scenarioTimeoutMs: 2000,
    world: { mode: 'natural', seed }, requireFactualSuccess: true,
    assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'data get entity {agent} Inventory', match: 'minecraft:oak_log' }],
  }] }));
  const workers = [];
  const config = { runtimeBudgetMs: 90000, startupMs: 5000, cleanupMs: 2000,
    outputDirectory: path.join(fixture, 'result'), matrixPath, serverTemplate: fixture,
    arms: ['A', 'B'].map(id => ({ id, profile, sourceRoot: root, artifactPath: path.join(fixture, 'unused.jar'),
      sourceManifestPath: path.join(fixture, 'unused.json'), artifactSha256: 'a'.repeat(64), sourceManifestSha256: 'b'.repeat(64) })),
    scenarios: [{ id: 'natural-review', seed, trialMs: 2000 }],
  };
  let report;
  try {
    report = await runPairedCli(config, {
      launcher: path.join(root, 'scripts/run-headless-provider-matrix.ps1'),
      checkArm: async () => ({ fixtureOnly: true }),
      makeWorker(_command, args, options) {
        const requestPath = args.at(-1);
        const worker = phaseWorker(process.execPath, [fileURLToPath(import.meta.url), '--fixture-worker', mode, requestPath], options);
        workers.push({ worker, requestPath });
        return worker;
      },
    });
  } finally {
    // All are exclusively owned inert children. terminate awaits their root exit.
    for (const { worker } of workers) await worker.terminate();
  }
  const childEvidence = [];
  for (const { worker, requestPath } of workers) {
    const { channel } = JSON.parse(await readFile(requestPath, 'utf8'));
    const evidence = { pid: worker.pid, exited: false, channel: path.relative(directory, channel) };
    try { process.kill(worker.pid, 0); } catch (error) { if (error.code !== 'ESRCH') throw error; evidence.exited = true; }
    assert.equal(evidence.exited, true, 'owned fixture must be gone');
    for (const name of ['entered-cleanup', 'finished-report', 'blocked-cleanup']) {
      try { evidence[name] = JSON.parse(await readFile(path.join(channel, `${name}.json`), 'utf8')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; evidence[name] = null; }
    }
    childEvidence.push(evidence);
  }
  const journal = JSON.parse(await readFile(path.join(config.outputDirectory, 'journal.json'), 'utf8'));
  const receipt = JSON.parse(await readFile(path.join(config.outputDirectory, 'completion.json'), 'utf8'));
  results.push({ mode, report, receipt, journal, childEvidence });
await writeFile(path.join(directory, 'cli-integration-output.json'), JSON.stringify(results, null, 2));
}
await writeFile(path.join(directory, 'cli-integration-output.json'), JSON.stringify(results, null, 2));

const [pass, timeout, blocked] = results;
assert.equal(pass.report.status, 'COMPLETE');
assert.equal(pass.report.counts.outcomes.PASSED, 4);
assert.equal(timeout.report.status, 'COMPLETE');
assert.equal(timeout.receipt.status, 'COMPLETE');
assert.equal(timeout.report.counts.outcomes.TIMED_OUT, 4);
assert.equal(timeout.report.counts.attempted, 4);
for (const trial of timeout.report.pairs.flatMap(pair => pair.trials)) {
  assert.equal(trial.cleanup, 'CLEAN');
  assert.equal(trial.resourcesClean, true);
  assert.equal(trial.phases.trial.deadlineMs, trial.phases.trial.startedAtMs + 2000);
  assert.ok(trial.phases.cleanup.deadlineMs <= trial.phases.trial.deadlineMs + 2000);
  assert.ok(trial.finishedAtMs <= trial.reservedUntilMs);
}
assert.equal(timeout.childEvidence.every(value => value['finished-report']?.classification === 'TIMEOUT'), true);
assert.equal(blocked.report.status, 'INCOMPLETE');
assert.equal(blocked.report.counts.attempted, 1);
assert.equal(blocked.report.pairs[0].trials[0].cleanup, 'TIMED_OUT');
assert.equal(blocked.childEvidence[0]['blocked-cleanup'].entered, true);
assert.equal(results.every(result => result.childEvidence.every(value => value.exited)), true);
});

test('deadline handoff spends cleanup reserve and requires final timeout evidence', { timeout: 180_000 }, async () => {
  for (const outcome of ['TIMED_OUT', 'PASSED', null, 'cleanup-overrun']) {
    let time = 0, trialDeadline, calls = 0;
    const profile = { provider: 'codex', model: 'offline', reasoningEffort: 'high', serviceTier: 'priority' };
    const report = await runPairedPilot({ deadlineMs: 1400, startupMs: 100, cleanupMs: 50,
      arms: ['A', 'B'].map(id => ({ id, profile, artifactSha256: 'a'.repeat(64), sourceManifestSha256: 'b'.repeat(64) })),
      scenarios: [{ id: 'boundary', seed: '1', trialMs: 200 }],
    }, {
      now: () => time,
      startup: async () => { time += 50; return {}; },
      runTrial: async context => {
        calls++;
        assert.equal(context.deadlineMs, time + 200);
        assert.equal(context.cleanupDeadlineMs, context.deadlineMs + 50);
        trialDeadline = context.deadlineMs;
        time = trialDeadline + 10;
        return { status: 'TIMED_OUT', deadlineReached: true };
      },
      cleanup: async context => {
        // Unused startup time cannot enlarge the cleanup allowance after cutoff.
        assert.equal(context.deadlineMs, trialDeadline + 50);
        assert.equal(context.deadlineMs - time, 40);
        time = context.deadlineMs + (outcome === 'cleanup-overrun' ? 1 : 0);
        return { ok: true, ...(outcome ? { trialOutcome: { status: outcome === 'cleanup-overrun' ? 'TIMED_OUT' : outcome, resourcesClean: true } } : {}) };
      },
    });
    assert.equal(calls, outcome === 'TIMED_OUT' ? 4 : 1, outcome);
    assert.equal(report.status, outcome === 'TIMED_OUT' ? 'COMPLETE' : 'INCOMPLETE', outcome);
    assert.equal(report.pairs[0].trials[0].phases.trial.overrunMs, 10);
    if (outcome === null) assert.equal(report.pairs[0].trials[0].resourcesClean, false);
    if (outcome === 'PASSED') assert.equal(report.pairs[0].trials[0].status, 'ERROR');
  }
});

test('a worker ignoring trial expiry is contained at the existing cleanup cutoff', { timeout: 180_000 }, async () => {
  const worker = phaseWorker(process.execPath, ['-e', 'setInterval(() => {}, 1000)']);
  const deadlineMs = performance.now() + 100;
  const cleanupDeadlineMs = deadlineMs + 200;
  try {
    assert.deepEqual(await worker.phase('trial', { now: () => performance.now(), deadlineMs, cleanupDeadlineMs }), { deadlineReached: true });
    assert.equal(worker.forced, false);
    await assert.rejects(worker.phase('cleanup', { now: () => performance.now(), deadlineMs: cleanupDeadlineMs }), { code: 'HEADLESS_TIMEOUT' });
    assert.equal(worker.forced, true);
  } finally { await worker.terminate(); }
  assert.throws(() => process.kill(worker.pid, 0), { code: 'ESRCH' });
});

test('deadline handoff retains containment when its caller never begins cleanup', { timeout: 180_000 }, async () => {
  const worker = phaseWorker(process.execPath, ['-e', 'setInterval(() => {}, 1000)']);
  const deadlineMs = performance.now() + 100;
  try {
    await worker.phase('trial', { now: () => performance.now(), deadlineMs, cleanupDeadlineMs: deadlineMs + 200 });
    assert.equal(worker.forced, false);
    await new Promise(resolve => setTimeout(resolve, 400));
    assert.equal(worker.forced, true);
  } finally { await worker.terminate(); }
  assert.throws(() => process.kill(worker.pid, 0), { code: 'ESRCH' });
});
}
