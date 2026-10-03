import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath, pathToFileURL } from 'node:url';

const coordinatorRoot = fileURLToPath(new URL('../../', import.meta.url));
const repositoryRoot = path.dirname(coordinatorRoot);
export const defaultBaselineBundle = path.join(repositoryRoot, 'reports/cave-navigation-baseline-sources-2026-10-02.json');
export const defaultSettings = Object.freeze({ repetitions: 12, warmups: 1, actionMs: 60, decisionMs: 100, observationMs: 5 });
const hash = text => createHash('sha256').update(text).digest('hex');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const profile = Object.freeze({ agentId: 'cave-attention-fixture', goalRevision: 1,
  provider: 'codex', model: 'fixture-authored-no-inference', reasoningEffort: 'medium', serviceTier: 'fast',
  currentGoal: 'Execute the explicitly authored observed route' });
const initialPosition = Object.freeze({ x: 0.5, y: 64, z: 0.5 });

// Geometry describes authored coordinates only. The fake bridge has no pathfinder or collision physics.
export const routes = Object.freeze({
  turns: Object.freeze([[2.5, 64, .5], [2.5, 64, 2.5], [4.5, 64, 2.5], [4.5, 64, 4.5], [6.5, 64, 4.5], [6.5, 64, 6.5]]),
  slope: Object.freeze([[2.5, 64, .5], [3.5, 65, .5], [4.5, 66, .5], [4.5, 66, 2.5], [6.5, 66, 2.5], [7.5, 65, 2.5]]),
  ledge: Object.freeze([[2.5, 64, .5], [2.5, 65, 1.5], [2.5, 65, 3.5], [4.5, 65, 3.5], [4.5, 64, 4.5], [6.5, 64, 4.5]]),
});

function observedBlocks(route) {
  return route.map(([x, y, z]) => ({ stableId: `block:${Math.floor(x)},${y - 1},${Math.floor(z)}`,
    x: Math.floor(x), y: y - 1, z: Math.floor(z), blockId: 'minecraft:stone', tags: [] }));
}

export function routeSources(route, { predicate = null } = {}) {
  const blocks = observedBlocks(route);
  // Every expected support identity is agent-authored; unknown additions/removals also require reassessment.
  const reassessWhen = predicate ?? [
    'player.state().health < 20', 'world.state().dimension !== "minecraft:overworld"',
    'world.entities({hostile:true}).length > 0', `world.blocks().length !== ${blocks.length}`,
    ...blocks.map(({ tags: _tags, ...identity }) => `world.blocks(${JSON.stringify(identity)}).length !== 1`),
  ].join(' || ');
  const controls = route.map(([x, y, z], index) => `const receipt${index} = await player.navigateTo(${JSON.stringify({ x, y, z, tolerance: .35, sprint: false, timeoutMs: 3000 })});\nif (receipt${index}.state !== "SUCCEEDED") { program.checkpoint("route-action-failed"); }`).join('\n');
  return { legacy: `program.onUnhandledAttention("pause_and_notify");\n${controls}`,
    optedIn: `program.onUnhandledAttention("pause_and_notify", {reassessWhen: () => ${reassessWhen}});\n${controls}`,
    reassessWhen };
}

/** Load a complete, hash-checked import graph captured before the movement changes. */
export async function loadFrozenBaseline(bundlePath = defaultBaselineBundle) {
  const bundle = JSON.parse(await readFile(bundlePath, 'utf8'));
  const directory = await mkdtemp(path.join(coordinatorRoot, '.cave-attention-baseline-'));
  try {
    for (const [relative, entry] of Object.entries(bundle.files)) {
      assert.equal(hash(entry.source), entry.sha256, `baseline source integrity: ${relative}`);
      assert.ok(relative.startsWith('coordinator/') && !relative.includes('..') && !relative.includes('\\'));
      const destination = path.resolve(directory, relative.slice('coordinator/'.length));
      assert.ok(destination.startsWith(`${path.resolve(directory)}${path.sep}`));
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, entry.source);
    }
    const runtime = await import(pathToFileURL(path.join(directory, 'src/native-tool-runtime.mjs')).href);
    const tools = await import(pathToFileURL(path.join(directory, 'src/native-minecraft-tools.mjs')).href);
    return { NativeToolRuntime: runtime.NativeToolRuntime, normalizeMinecraftToolCall: tools.normalizeMinecraftToolCall,
      manifest: { capturedAt: bundle.capturedAt, bundleSha256: hash(await readFile(bundlePath)),
        files: Object.fromEntries(Object.entries(bundle.files).map(([relative, entry]) => [relative, entry.sha256])) },
      dispose: () => disposeBaseline(directory) };
  } catch (error) { await disposeBaseline(directory); throw error; }
}

async function disposeBaseline(directory) {
  const resolved = path.resolve(directory);
  assert.equal(path.dirname(resolved), path.resolve(coordinatorRoot));
  assert.ok(path.basename(resolved).startsWith('.cave-attention-baseline-'));
  await rm(resolved, { recursive: true, force: true });
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
  await visit('coordinator/src/native-minecraft-tools.mjs');
  return Object.fromEntries([...files].sort());
}

function createHarness(implementation, route, settings, options = {}) {
  const { NativeToolRuntime, normalizeMinecraftToolCall } = implementation;
  let sequence = 1, callNumber = 0, currentPosition = { ...initialPosition }, health = 20;
  let blocks = observedBlocks(route), entities = [], worldDimension = 'minecraft:overworld';
  let runtime, resolveDone, rejectDone, responderFailure;
  const actions = [], samples = [], events = [], decisions = [], attention = [], timers = new Set();
  const active = new Map();
  const done = new Promise((resolve, reject) => { resolveDone = resolve; rejectDone = reject; });
  const schedule = (callback, ms) => {
    const timer = setTimeout(() => { timers.delete(timer); callback(); }, ms);
    timers.add(timer); return timer;
  };
  const observe = () => ({ ready: true, observedAtEpochMs: Date.now(),
    world: { worldId: 'cave-fixture-world', dimension: worldDimension },
    player: { ...currentPosition, health, food: 20, air: 300, dead: false },
    blocks, entities, items: [], inventory: { items: [], tagCounts: {} } });
  const call = (name, args = {}) => runtime.execute({ agentId: profile.agentId, goalRevision: profile.goalRevision,
    turnId: 'deterministic-fixture-turn', callId: `cave-${++callNumber}`, tool: normalizeMinecraftToolCall(name, args) }, profile);
  const publish = metadata => {
    const at = performance.now();
    attention.push({ at, ...metadata, observation: structuredClone(observe()) });
    runtime.updateObservation(profile, observe(), { eventSequence: ++sequence, attention: true, ...metadata });
  };
  const finish = entry => {
    if (!active.has(entry.actionId)) return;
    active.delete(entry.actionId);
    entry.completedAt = performance.now();
    const special = options.caseName && entry.ordinal === (options.caseAction ?? 3);
    const failed = special && options.caseName === 'blocked_route' || options.caseName === 'repeated_failure';
    entry.state = failed ? 'FAILED' : 'SUCCEEDED';
    entry.reasonCode = failed ? 'NO_STANDABLE_PATH' : 'ARRIVED';
    if (!failed) currentPosition = { x: entry.arguments.x, y: entry.arguments.y, z: entry.arguments.z };
    runtime.onActionResult(profile, { goalRevision: 1, actionId: entry.actionId, state: entry.state,
      reasonCode: entry.reasonCode, executionStarted: true, physicalAttempted: false });
    if (special) {
      if (options.caseName === 'new_geometry') blocks = [...blocks, { stableId: 'block:8,64,8', x: 8, y: 64, z: 8, blockId: 'minecraft:stone', tags: [] }];
      if (options.caseName === 'changed_support') blocks = blocks.map((block, index) => index === 0 ? { ...block, blockId: 'minecraft:air' } : block);
      if (options.caseName === 'new_resource') blocks = [...blocks, { stableId: 'block:8,64,8', x: 8, y: 64, z: 8, blockId: 'minecraft:iron_ore', tags: [] }];
      if (options.caseName === 'observed_hostile') entities = [{ stableId: 'known-zombie', uuid: '00000000-0000-4000-8000-000000000001', type: 'minecraft:zombie', hostile: true, x: 3, y: 64, z: 2 }];
      if (options.caseName === 'urgent_damage') health = 18;
      if (options.caseName === 'changed_dimension') worldDimension = 'minecraft:the_nether';
    }
    // The real server also publishes post-result observations. Only bridge timing and supplied facts are simulated.
    if (!failed) publish({ priority: special && options.caseName === 'urgent_damage' ? 'urgent' : 'ordinary',
      trigger: special && options.caseName === 'urgent_damage' ? 'damage' : 'nearby_blocks_changed', changedFacts: ['blocks', 'player.position'] });
  };
  runtime = new NativeToolRuntime({ sessionId: 'cave-attention-benchmark', registry: { get: () => profile },
    bridge: { send: async (type, _agentId, payload) => {
      if (type === 'action_cancel') {
        const entry = active.get(payload.actionId);
        if (entry) { active.delete(entry.actionId); entry.completedAt = performance.now(); entry.state = 'CANCELLED';
          runtime.onActionResult(profile, { goalRevision: 1, actionId: entry.actionId, state: 'CANCELLED', reasonCode: 'CANCELLED' }); }
        return;
      }
      if (type !== 'action_command') return;
      const entry = { ...structuredClone(payload), ordinal: actions.length + 1, dispatchedAt: performance.now(), completedAt: null };
      actions.push(entry); active.set(entry.actionId, entry);
      schedule(() => finish(entry), settings.actionMs);
    } },
    requestObservation: async () => {
      const entry = { requestedAt: performance.now(), returnedAt: null }; samples.push(entry);
      await delay(settings.observationMs);
      entry.returnedAt = performance.now(); entry.eventSequence = ++sequence;
      return { observation: observe(), eventSequence: sequence };
    },
    onProgramEvent: (_record, event) => {
      events.push({ ...event, at: performance.now() });
      if (event.event === 'program_ended') { resolveDone(event); return; }
      if (event.event !== 'program_attention') return;
      const decision = { startedAt: performance.now(), finishedAt: null, trigger: event.status.decision?.trigger }; decisions.push(decision);
      // This is a declared response fixture, not a model: safe progress continues; behavior cases explicitly stop.
      schedule(async () => {
        try {
          const status = await call('programStatus', { programId: event.programId });
          if (!status.decision) return;
          decision.finishedAt = performance.now();
          await call('respondProgram', { programId: event.programId, goalRevision: 1,
            decisionId: status.decision.decisionId, directive: options.stopOnDecision ? 'pause' : 'continue' });
        } catch (error) { responderFailure = error; rejectDone(error); }
      }, settings.decisionMs);
    },
  });
  runtime.updateObservation(profile, observe(), { eventSequence: sequence });
  return { runtime, call, done, actions, samples, events, decisions, attention,
    position: () => ({ ...currentPosition }), failure: () => responderFailure,
    async dispose() { for (const timer of timers) clearTimeout(timer); timers.clear(); await runtime.disposeAll(); } };
}

function distance(route) {
  let previous = initialPosition, total = 0;
  for (const [x, y, z] of route) { total += Math.hypot(x - previous.x, y - previous.y, z - previous.z); previous = { x, y, z }; }
  return total;
}

export async function runRoute(implementation, route, settings, source, options = {}) {
  const fixture = createHarness(implementation, route, settings, options);
  const start = performance.now();
  let timer;
  try {
    const handle = await fixture.call('runProgram', { source, background: true, maxActions: route.length, timeoutMs: 5000 });
    assert.ok(handle.programId, `program start: ${JSON.stringify(handle)}`);
    const ended = await Promise.race([fixture.done, new Promise((_resolve, reject) => { timer = setTimeout(() => reject(new Error('Route fixture did not settle within 6 seconds')), 6000); })]);
    if (fixture.failure()) throw fixture.failure();
    const end = performance.now();
    const actionShape = fixture.actions.map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
    const gaps = fixture.actions.slice(1).map((action, index) => action.dispatchedAt - fixture.actions[index].completedAt);
    const terminal = fixture.actions.at(-1)?.completedAt ?? start;
    const bodyBusyMs = fixture.actions.reduce((sum, action) => sum + action.completedAt - action.dispatchedAt, 0);
    const elapsedMs = terminal - (fixture.actions[0]?.dispatchedAt ?? start);
    const complete = fixture.actions.length === route.length && fixture.actions.every(action => action.state === 'SUCCEEDED')
      && JSON.stringify(fixture.position()) === JSON.stringify(Object.fromEntries(['x', 'y', 'z'].map((key, index) => [key, route.at(-1)[index]])));
    if (!options.caseName) assert.equal(complete, true, `all authored targets and success receipts must be present: ${JSON.stringify({ ended, actions: fixture.actions, events: fixture.events })}`);
    assert.ok(fixture.actions.every(action => action.provenance.provider === profile.provider && action.provenance.model === profile.model));
    return { elapsedMs, timeThroughProgramEndMs: end - start, bodyBusyMs, bodyIdleMs: elapsedMs - bodyBusyMs,
      handoffGapsMs: gaps, planningTurns: fixture.decisions.length, authoredDistance: distance(route), factualSuccess: complete,
      finalPosition: fixture.position(), actionShape, actionShapeSha256: hash(JSON.stringify(actionShape)),
      sources: { sha256: hash(source), source }, requestedObservations: fixture.samples.length,
      terminalReasonCode: ended.result.reasonCode, actions: fixture.actions, observations: fixture.attention,
      samples: fixture.samples, decisions: fixture.decisions, events: fixture.events };
  } finally { clearTimeout(timer); await fixture.dispose(); }
}

export function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = fraction => sorted.length ? Number(sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(3)) : null;
  return { n: sorted.length, p50Ms: percentile(.5), p95Ms: percentile(.95), meanMs: sorted.length ? Number((sorted.reduce((a, b) => a + b, 0) / sorted.length).toFixed(3)) : null };
}

function summarizeRows(rows) {
  return { routeTime: summarize(rows.map(row => row.elapsedMs)), bodyIdle: summarize(rows.map(row => row.bodyIdleMs)),
    actionHandoff: summarize(rows.flatMap(row => row.handoffGapsMs)),
    planningTurns: rows.map(row => row.planningTurns), factualSuccesses: rows.filter(row => row.factualSuccess).length,
    requestedObservations: rows.reduce((sum, row) => sum + row.requestedObservations, 0), actionShapeSha256: rows[0]?.actionShapeSha256 ?? null };
}

export async function runBehaviorCases(implementation, settings = defaultSettings) {
  const result = {};
  for (const name of ['new_geometry', 'changed_support', 'new_resource', 'observed_hostile', 'urgent_damage', 'changed_dimension', 'blocked_route', 'urgent_false_predicate']) {
    const route = routes.turns;
    const source = routeSources(route, name === 'urgent_false_predicate' ? { predicate: 'false' } : {}).optedIn;
    const row = await runRoute(implementation, route, { ...settings, actionMs: 20, decisionMs: 1, observationMs: 1 }, source,
      { caseName: name === 'urgent_false_predicate' ? 'urgent_damage' : name, stopOnDecision: true });
    assert.equal(row.actions.length, 3, `${name}: stop at the changed boundary without the next target`);
    if (name === 'blocked_route') assert.equal(row.terminalReasonCode, 'PROGRAM_CHECKPOINT', 'authored failure check returns control after one failed leg');
    else assert.ok(row.planningTurns >= 1, `${name}: selected-agent reconsideration remains available`);
    assert.equal(row.factualSuccess, false, `${name}: a partial route cannot claim success`);
    result[name] = { result: 'PASSED', stoppedAfterActions: row.actions.length, planningTurns: row.planningTurns,
      trigger: row.decisions[0]?.trigger, reasonCode: row.terminalReasonCode,
      actionShapeSha256: row.actionShapeSha256, changedObservation: row.observations.at(-1),
      sourceSha256: row.sources.sha256 };
  }
  const route = routes.turns;
  const source = `program.onUnhandledAttention("pause_and_notify", {reassessWhen: () => false});\nawait program.repeatUntil(() => false, {maxIterations:3}, async () => { await player.navigateTo({x:2.5,y:64,z:0.5,tolerance:0.35,sprint:false,timeoutMs:3000}); });`;
  const row = await runRoute(implementation, route, { ...settings, actionMs: 20, decisionMs: 1, observationMs: 1 }, source,
    { caseName: 'repeated_failure', stopOnDecision: true });
  assert.equal(row.actions.length, 2, 'the existing repeated-failure boundary halts identical failed retries');
  assert.equal(row.decisions[0]?.trigger, 'action_failure', 'an exact-false predicate cannot suppress action failure recovery');
  result.repeated_failure_false_predicate = { result: 'PASSED', stoppedAfterActions: 2, planningTurns: row.planningTurns,
    trigger: row.decisions[0].trigger, reasonCode: row.terminalReasonCode, sourceSha256: row.sources.sha256 };
  return result;
}

export async function runBenchmark({ mode = 'compare', settings = defaultSettings, baselineBundle = defaultBaselineBundle } = {}) {
  const baseline = await loadFrozenBaseline(baselineBundle);
  try {
    const current = mode === 'baseline' ? null : {
      ...(await import('../native-tool-runtime.mjs')), ...(await import('../native-minecraft-tools.mjs')) };
    const result = { benchmark: 'cave-navigation-attention', result: 'PASSED', measuredAt: new Date().toISOString(),
      kind: 'controlled-native-runtime-timing', entryPoint: 'normalizeMinecraftToolCall -> NativeToolRuntime -> ArenaScript -> timer-controlled fake bridge',
      providerUsed: false, minecraftServerUsed: false, installedClientVerified: false, settings,
      authorship: 'Explicit fixture-authored route, factual predicate and continue/pause responses; fixed injected decision latency, no new decision maker.',
      limitations: ['Coordinate turns/slopes/ledges are supplied observation fixtures, not Minecraft pathfinding or collision tests.',
        'Route time, body idle and handoff are fake-bridge control-chain timings. They do not measure model inference, token speed, FPS or whole-goal time.',
        'No provider call evaluates whether the guidance changes the selected agent\'s preparation or route choices.'],
      environment: { node: process.version, platform: process.platform, arch: process.arch },
      baseline: baseline.manifest, current: mode === 'baseline' ? null : await currentManifest(), comparisons: {} };
    for (const [routeName, route] of Object.entries(routes)) {
      const sources = routeSources(route);
      for (const [comparison, afterSource] of [['predicate_opt_in', sources.optedIn], ['legacy_same_source_control', sources.legacy]]) {
        if (mode === 'baseline' && comparison === 'legacy_same_source_control') continue;
        const rows = { before: [], after: [] };
        for (let repetition = 0; repetition < settings.warmups + settings.repetitions; repetition++) {
          for (const arm of repetition % 2 === 0 ? ['before', 'after'] : ['after', 'before']) {
            if (arm === 'after' && mode === 'baseline') continue;
            const row = await runRoute(arm === 'before' ? baseline : current, route, settings,
              arm === 'before' ? sources.legacy : afterSource);
            if (repetition >= settings.warmups) rows[arm].push(row);
          }
        }
        if (mode !== 'baseline') assert.ok(rows.before.every((row, index) => row.actionShapeSha256 === rows.after[index].actionShapeSha256));
        result.comparisons[`${routeName}_${comparison}`] = { route, sources, before: summarizeRows(rows.before),
          ...(mode === 'baseline' ? {} : { after: summarizeRows(rows.after), pairedRouteTimeWins: rows.after.filter((row, index) => row.elapsedMs < rows.before[index].elapsedMs).length }), rows };
      }
    }
    if (mode !== 'baseline') result.behaviorCases = await runBehaviorCases(current, settings);
    return result;
  } finally { await baseline.dispose(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const flags = Object.fromEntries(process.argv.slice(2).map(value => { const [key, ...rest] = value.replace(/^--/, '').split('='); return [key, rest.join('=')]; }));
  const settings = { ...defaultSettings };
  for (const [flag, name] of [['repetitions', 'repetitions'], ['warmups', 'warmups'], ['action-ms', 'actionMs'], ['decision-ms', 'decisionMs'], ['observation-ms', 'observationMs']]) if (flags[flag] !== undefined) settings[name] = Number(flags[flag]);
  assert.ok(Object.values(settings).every(value => Number.isSafeInteger(value) && value >= 0));
  assert.ok(settings.repetitions > 0 && settings.repetitions <= 100 && settings.actionMs > 0 && settings.actionMs <= 500 && settings.decisionMs <= 500);
  const result = await runBenchmark({ mode: flags.mode ?? 'compare', settings, baselineBundle: flags['baseline-bundle'] ?? defaultBaselineBundle });
  if (flags.json) { await mkdir(path.dirname(path.resolve(flags.json)), { recursive: true }); await writeFile(path.resolve(flags.json), `${JSON.stringify(result, null, 2)}\n`); }
  process.stdout.write(`${JSON.stringify(flags.quiet === 'true' ? { benchmark: result.benchmark, result: result.result,
    settings, comparisons: Object.fromEntries(Object.entries(result.comparisons).map(([name, { before, after, pairedRouteTimeWins }]) => [name, { before, after, pairedRouteTimeWins }])), behaviorCases: result.behaviorCases } : result, null, 2)}\n`);
}
