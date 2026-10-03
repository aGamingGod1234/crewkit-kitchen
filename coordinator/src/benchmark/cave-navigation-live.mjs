import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { MultiplexedServerBridge } from '../protocol-v2.mjs';
import { HeadlessRconClient } from '../headless-rcon.mjs';
import { NativeToolRuntime } from '../native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../native-minecraft-tools.mjs';
import { adaptObservation } from '../observation-adapter.mjs';
import { classifyObservationTrigger } from '../dynamic-main.mjs';
import { parseProbeArguments, probeFailureMessage } from '../player-capability-probe.mjs';

const PROFILE = Object.freeze({ provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' });
const terminal = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED']);
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export const route = Object.freeze([[2.5, 64, .5], [2.5, 64, 2.5], [3.5, 65, 2.5],
  [4.5, 65, 2.5], [4.5, 65, 4.5], [6.5, 65, 4.5]]);
export const settings = Object.freeze({ pairs: 3, decisionResponseMs: 100, tolerance: .35, actionTimeoutMs: 10000 });

// Unknown support is actionable. Grounding and the current support identity come from live player observations.
export const predicate = `player.state().health !== 20 || player.state().onGround !== true || world.state().dimension !== "minecraft:overworld" || world.blocks({x:math.floor(player.state().x),y:math.floor(player.state().y)-1,z:math.floor(player.state().z),blockId:"minecraft:stone"}).length !== 1`;
export function routeSources() {
  const controls = route.map(([x, y, z], index) => `const r${index} = await player.navigateTo(${JSON.stringify({ x, y, z,
    tolerance: settings.tolerance, sprint: false, timeoutMs: settings.actionTimeoutMs })});\nif (r${index}.state !== "SUCCEEDED") { program.checkpoint("blocked-physical-route"); }`).join('\n');
  return { before: `program.onUnhandledAttention("pause_and_notify");\n${controls}`,
    after: `program.onUnhandledAttention("pause_and_notify", {reassessWhen: () => ${predicate}});\n${controls}` };
}

function summary(values) {
  const sorted = [...values].sort((a, b) => a - b), p = fraction => Number(sorted[Math.ceil(sorted.length * fraction) - 1].toFixed(3));
  return { n: values.length, p50Ms: p(.5), p95Ms: p(.95), meanMs: Number((values.reduce((a, b) => a + b, 0) / values.length).toFixed(3)) };
}

/** Same-current-JAR physical policy comparison; all destinations and responses are explicitly fixture-authored. */
export async function runCaveNavigationLive(config) {
  const startedAt = Date.now(), now = () => performance.now();
  const secret = (await readFile(config.bridgeSecretFile, 'utf8')).trim();
  const password = (await readFile(config.rconPasswordFile, 'utf8')).trim();
  const report = { schemaVersion: 1, scenarioId: 'cave-navigation-live', status: 'FAILED',
    kind: 'same-current-jar-controlled-physical-policy-comparison', providerUsed: false, installedMinecraft: true,
    settings, route, sources: routeSources(), checks: [], rows: [], cleanup: {},
    entryPoint: 'normalizeMinecraftToolCall -> NativeToolRuntime -> authenticated MultiplexedServerBridge -> installed Fabric navigation controller',
    notificationPolicy: 'Controlled ordinary notifications at successful physical-action boundaries using fresh server observations. Other ordinary pushes update facts without notification; actual urgent survival notifications remain live.',
    limitations: ['Both arms use the same current JAR, not an old/new JAR comparison.',
      'Responses are a declared 100 ms continue fixture, not provider inference or a new gameplay decision maker.',
      'Artificial isolated stone route with corners, a one-block ascent and a one-block-wide raised ledge; not a natural-cave or occluded-hostile evaluation.',
      'Reported idle is a completion-receipt-to-next-dispatch proxy, not direct measurement of vanilla input idle or FPS.'] };
  const bridge = new MultiplexedServerBridge({ port: config.bridgePort, secret });
  const rcon = new HeadlessRconClient({ host: config.host, port: config.rconPort, password });
  const messages = [], commands = [], results = [], samples = [], events = [], responses = [], progress = [], boundaryFacts = [];
  let record, player, runtime, activeArm = null, pendingBoundary = null, ordinal = 0, latestSequence = 0, failure = null;
  const timers = new Set();
  const command = async text => (await rcon.command(text)).text;
  bridge.on('message', event => messages.push({ ...event, at: now() }));
  bridge.on('transportError', error => { failure = error; });
  bridge.on('protocolError', error => { failure = error; });
  const until = async (predicate, label, timeoutMs = 20000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (failure) throw failure;
      const value = predicate(); if (value) return value;
      await sleep(5);
    }
    throw new Error(`Timed out waiting for ${label}`);
  };
  const waitMessage = (type, predicate = () => true, offset = 0) => until(() => messages.slice(offset).find(event => event.type === type && predicate(event)), type);
  const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision,
    turnId: 'provider-free-physical-route-fixture', callId: `cave-live-${++ordinal}`, tool: normalizeMinecraftToolCall(name, args) }, record);
  const supportOf = observation => {
    const p = observation.player, x = Math.floor(p.x), y = Math.floor(p.y) - 1, z = Math.floor(p.z);
    return observation.blocks.find(block => block.x === x && block.y === y && block.z === z && block.blockId === 'minecraft:stone') ?? null;
  };
  const ingest = (observation, eventSequence, metadata = {}) => {
    if (!runtime || eventSequence <= latestSequence) return;
    latestSequence = eventSequence;
    const urgent = metadata.priority === 'urgent';
    const boundary = pendingBoundary && eventSequence > pendingBoundary.afterSequence ? pendingBoundary : null;
    if (boundary) {
      pendingBoundary = null;
      boundaryFacts.push({ actionId: boundary.actionId, eventSequence, at: now(), health: observation.player.health,
        onGround: observation.player.onGround, position: { x: observation.player.x, y: observation.player.y, z: observation.player.z },
        support: supportOf(observation), observedAtEpochMs: observation.observedAtEpochMs, worldTick: observation.world?.gameTime,
        dimension: observation.world?.dimension,
        notification: urgent ? 'actual_urgent' : 'controlled_ordinary_boundary' });
    }
    runtime.updateObservation(record, observation, { eventSequence, ...metadata,
      attention: urgent || Boolean(boundary), priority: urgent ? 'urgent' : 'ordinary',
      trigger: urgent ? metadata.trigger : boundary ? 'controlled_route_boundary' : 'observation',
      changedFacts: urgent ? metadata.changedFacts : boundary ? ['player.position', 'blocks'] : [] });
  };
  const passiveObservation = async () => {
    const requestId = `cave-live-${randomUUID()}`, offset = messages.length, requestedAt = now();
    await bridge.send('inspection_request', record.agentId, { requestId, goalRevision: record.goalRevision, query: { section: 'observation', limit: 16, offset: 0 } });
    const reply = await waitMessage('inspection_result', event => event.agentId === record.agentId && event.payload.requestId === requestId, offset);
    assert.equal(reply.payload.error, undefined);
    const result = { ...reply.payload.result, observation: adaptObservation(reply.payload.result.observation) };
    assert.equal(result.observation.ready, true);
    samples.push({ requestedAt, returnedAt: now(), eventSequence: result.eventSequence, observation: result.observation });
    ingest(result.observation, result.eventSequence);
    return result;
  };
  try {
    const mods = join(config.runDirectory, 'server', 'mods');
    const jars = (await readdir(mods)).filter(name => /^arena-agents-[\d.]+\.jar$/.test(name));
    assert.equal(jars.length, 1);
    report.installedJar = { name: jars[0], sha256: hash(await readFile(join(mods, jars[0]))) };
    report.runtimeSha256 = hash(await readFile(new URL('../native-tool-runtime.mjs', import.meta.url)));
    report.probeSha256 = hash(await readFile(fileURLToPath(import.meta.url)));
    report.checks.push({ check: 'exactly_one_installed_arena_jar', status: 'PASSED', sha256: report.installedJar.sha256 });
    await rcon.connect();
    const ready = new Promise((resolve, reject) => {
      bridge.once('ready', resolve); bridge.once('protocolError', reject); bridge.once('transportError', reject);
      const timer = setTimeout(() => reject(new Error('Authenticated bridge did not become ready')), 12000); timer.unref();
    });
    bridge.start(); await ready;
    await bridge.send('catalog_snapshot', 'server', { refreshedAtEpochMs: Date.now(), models: [{ provider: PROFILE.provider, model: PROFILE.model,
      id: PROFILE.model, displayName: 'Provider-free physical route fixture', reasoningEfforts: ['medium'], serviceTiers: ['priority', 'fast'] }] });
    for (const text of ['forceload add -16 -16 16 16', 'difficulty peaceful', 'time set day', 'weather clear',
      'fill -3 64 -3 9 69 7 minecraft:air', 'fill -3 63 -3 9 63 7 minecraft:stone',
      'fill 1 64 1 1 66 1 minecraft:stone', 'fill 3 64 2 4 64 2 minecraft:stone',
      'fill 4 64 3 4 64 4 minecraft:stone', 'fill 5 64 4 6 64 4 minecraft:stone']) await command(text);
    const offset = messages.length;
    await command(`execute in minecraft:overworld positioned 0.5 64 0.5 run codex summon ${PROFILE.model} ${PROFILE.reasoningEffort} ${config.agentName}`);
    const registered = await waitMessage('agent_registered', () => true, offset);
    record = { ...(registered.payload.record ?? registered.payload), agentId: registered.agentId };
    player = record.entityUuid ?? config.agentName;
    bridge.on('goal_spec_request', event => {
      if (event.agentId !== record.agentId) return;
      bridge.send('goal_spec_proposal', record.agentId, { requestId: event.payload.requestId,
        summary: 'Obtain a stone pickaxe after the physical route fixture.', predicate: { type: 'inventory_contains', itemId: 'minecraft:stone_pickaxe', count: 1 } }).catch(error => { failure = error; });
    });
    await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
    await waitMessage('observation', event => event.agentId === record.agentId && event.payload.ready === true);
    const goalOffset = messages.length;
    await command(`codex start ${config.agentName} Get a stone pickaxe`);
    const goal = await waitMessage('goal_control', event => event.agentId === record.agentId && event.payload.operation === 'start', goalOffset);
    record.goalRevision = goal.payload.goalRevision;
    await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
    runtime = new NativeToolRuntime({ sessionId: 'cave-navigation-live', registry: { get: () => record },
      bridge: { send: async (type, agentId, payload) => {
        if (type === 'action_command') {
          assert.equal(pendingBoundary, null, 'fresh controlled boundary must be ingested before the next physical action dispatch');
          commands.push({ ...structuredClone(payload), dispatchedAt: now(), arm: activeArm });
        }
        return bridge.send(type, agentId, payload);
      } }, requestObservation: passiveObservation,
      onProgramEvent: (_record, event) => {
        events.push({ ...event, at: now(), arm: activeArm });
        if (event.event !== 'program_attention') return;
        const response = { programId: event.programId, arm: activeArm, startedAt: now(), trigger: event.status.decision?.trigger, finishedAt: null };
        responses.push(response);
        const timer = setTimeout(async () => {
          timers.delete(timer);
          try {
            const status = await call('programStatus', { programId: event.programId });
            if (!status.decision) return;
            // Fixed harmless-route continuation only. Actual urgency stops the fixture instead of selecting a tactic.
            assert.equal(status.decision.priority, 'ordinary', 'actual urgent attention must not receive an automatic continue');
            response.finishedAt = now();
            await call('respondProgram', { programId: event.programId, goalRevision: record.goalRevision,
              decisionId: status.decision.decisionId, directive: 'continue' });
          } catch (error) { failure = error; }
        }, settings.decisionResponseMs);
        timers.add(timer);
      } });
    bridge.on('observation', event => {
      if (event.agentId !== record.agentId || event.payload.goalRevision !== record.goalRevision) return;
      const raw = event.payload.observation ?? event.payload;
      ingest(adaptObservation(raw), event.payload.eventSequence, { ...classifyObservationTrigger(event.payload, raw), changedFacts: event.payload.changedFacts });
    });
    bridge.on('action_progress', event => {
      if (event.agentId !== record.agentId) return;
      progress.push({ ...structuredClone(event.payload), receivedAt: now() });
      runtime.onActionProgress(record, event.payload);
    });
    bridge.on('action_result', event => {
      if (event.agentId !== record.agentId || !commands.some(action => action.actionId === event.payload.actionId)) return;
      results.push({ ...structuredClone(event.payload), receivedAt: now() });
      if (activeArm && event.payload.state === 'SUCCEEDED') pendingBoundary = { actionId: event.payload.actionId,
        afterSequence: latestSequence };
      runtime.onActionResult(record, event.payload);
      bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: event.payload.actionId }).catch(error => { failure = error; });
    });
    report.registeredProfile = Object.fromEntries(Object.keys(PROFILE).map(key => [key, record[key]]));
    for (let pair = 0; pair < settings.pairs; pair++) {
      for (const arm of pair % 2 === 0 ? ['before', 'after'] : ['after', 'before']) {
        activeArm = null; pendingBoundary = null;
        await command(`tp ${player} 0.5 64 0.5 -90 35`);
        await command(`clear ${player}`);
        await command(`effect clear ${player}`);
        await command(`effect give ${player} minecraft:saturation 1 10 true`);
        await sleep(1100);
        await command(`effect clear ${player}`);
        const initial = await passiveObservation();
        assert.equal(initial.observation.player.health, 20);
        assert.equal(initial.observation.player.foodLevel, 20);
        assert.ok(Math.hypot(initial.observation.player.x - .5, initial.observation.player.y - 64, initial.observation.player.z - .5) < .1);
        const commandOffset = commands.length, resultOffset = results.length, responseOffset = responses.length, boundaryOffset = boundaryFacts.length;
        activeArm = { pair, arm };
        const handle = await call('runProgram', { source: report.sources[arm], background: true, maxActions: route.length,
          timeoutMs: 90000 });
        assert.ok(handle.programId);
        const ended = await until(() => events.find(event => event.programId === handle.programId && event.event === 'program_ended'), `${arm} physical route`, 95000);
        activeArm = null; pendingBoundary = null;
        const actual = commands.slice(commandOffset), receipts = results.slice(resultOffset).filter(result => terminal.has(result.state));
        assert.equal(actual.length, route.length, JSON.stringify({ ended: ended.result, actual: actual.map(action => action.actionType), receipts }));
        assert.equal(receipts.length, route.length);
        assert.ok(receipts.every(result => result.state === 'SUCCEEDED' && result.physicalAttempted === true));
        assert.equal(ended.result.reasonCode, 'PROGRAM_EXHAUSTED');
        const shape = actual.map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
        assert.deepEqual(shape, route.map(([x, y, z]) => ({ actionType: 'navigate_to', arguments: { x, y, z,
          tolerance: settings.tolerance, sprint: false, timeoutMs: settings.actionTimeoutMs } })));
        for (const action of actual) for (const key of Object.keys(PROFILE)) assert.equal(action.provenance[key], record[key]);
        const final = (await passiveObservation()).observation;
        assert.equal(final.player.health, 20, 'route completion must not cost health');
        const destination = route.at(-1);
        assert.ok(Math.hypot(final.player.x - destination[0], final.player.y - destination[1], final.player.z - destination[2]) <= settings.tolerance + .05);
        const rconPosition = await command(`data get entity ${player} Pos`);
        const rconCoordinates = [...rconPosition.matchAll(/(-?\d+(?:\.\d+)?)[dDfF]/g)].map(match => Number(match[1]));
        assert.equal(rconCoordinates.length, 3);
        assert.ok(Math.hypot(...rconCoordinates.map((value, index) => value - destination[index])) <= settings.tolerance + .05);
        const gaps = actual.slice(1).map((action, index) => action.dispatchedAt - receipts.find(result => result.actionId === actual[index].actionId).receivedAt);
        const row = { pair, arm, programId: handle.programId, sourceSha256: hash(report.sources[arm]), actionShapeSha256: hash(shape),
          routeTimeMs: receipts.at(-1).receivedAt - actual[0].dispatchedAt, completionToDispatchGapsMs: gaps,
          bodyIdleProxyMs: gaps.reduce((a, b) => a + b, 0), reconsiderationResponses: responses.length - responseOffset,
          successfulPhysicalActions: receipts.length, factualSuccess: true, health: final.player.health,
          finalPosition: { x: final.player.x, y: final.player.y, z: final.player.z }, rconPosition,
          freshBoundaryFacts: boundaryFacts.slice(boundaryOffset), responseTimings: responses.slice(responseOffset),
          actionIds: actual.map(action => action.actionId), receipts: receipts.map(compactReceipt),
          progress: progress.filter(entry => actual.some(action => action.actionId === entry.actionId)).map(compactProgress) };
        report.rows.push(row);
        report.checks.push({ check: 'six_authored_physical_navigation_controls', pair, arm, status: 'PASSED',
          successfulPhysicalActions: receipts.length, reasonCode: ended.result.reasonCode,
          actionShapeSha256: row.actionShapeSha256, finalHealth: final.player.health,
          observedDestination: row.finalPosition, rconDestination: rconCoordinates,
          profilePreserved: true, initialFoodLevel: initial.observation.player.foodLevel });
        process.stdout.write(`${JSON.stringify({ scenario: report.scenarioId, pair, arm, routeTimeMs: row.routeTimeMs,
          gapMs: summary(gaps), responses: row.reconsiderationResponses, successfulPhysicalActions: row.successfulPhysicalActions })}\n`);
      }
    }
    for (let pair = 0; pair < settings.pairs; pair++) assert.equal(report.rows.find(row => row.pair === pair && row.arm === 'before').actionShapeSha256,
      report.rows.find(row => row.pair === pair && row.arm === 'after').actionShapeSha256);
    report.checks.push({ check: 'all_three_pairs_have_identical_action_arguments', status: 'PASSED', pairs: settings.pairs });
    report.summary = Object.fromEntries(['before', 'after'].map(arm => { const rows = report.rows.filter(row => row.arm === arm); return [arm, {
      routeTime: summary(rows.map(row => row.routeTimeMs)), bodyIdleProxy: summary(rows.map(row => row.bodyIdleProxyMs)),
      completionToDispatch: summary(rows.flatMap(row => row.completionToDispatchGapsMs)), responses: rows.map(row => row.reconsiderationResponses),
      factualSuccesses: rows.filter(row => row.factualSuccess).length }]; }));
    report.pairedRouteTimeWins = report.rows.filter(row => row.arm === 'after' && row.routeTimeMs < report.rows.find(before => before.pair === row.pair && before.arm === 'before').routeTimeMs).length;
    report.status = 'PASSED';
  } catch (error) { report.failure = probeFailureMessage(error, [secret, password]); }
  finally {
    activeArm = null;
    for (const timer of timers) clearTimeout(timer);
    try { await runtime?.disposeAll(); report.cleanup.runtime = 'CLEAN'; } catch { report.cleanup.runtime = 'FAILED'; }
    try { if (record) await command(`codex remove ${config.agentName}`); report.cleanup.agent = 'REMOVED'; } catch { report.cleanup.agent = 'FAILED'; }
    bridge.stop(); await rcon.close();
    report.elapsedMs = Date.now() - startedAt;
    const rawEvidence = { commands, results, samples, progress, responses, boundaryFacts };
    report.rawEvidenceFile = 'cave-navigation-live-raw.json';
    await writeFile(join(config.runDirectory, report.rawEvidenceFile), `${JSON.stringify(rawEvidence, null, 2)}\n`);
    report.actions = commands.map(action => ({ ...action, result: compactReceipt(results.findLast(result => result.actionId === action.actionId && terminal.has(result.state))) }));
    report.samples = samples.map(({ requestedAt, returnedAt, eventSequence, observation }) => ({ requestedAt, returnedAt, eventSequence,
      ready: observation.ready, player: observation.player, world: observation.world, support: supportOf(observation), observedBlocks: observation.blocks.length }));
    report.boundaryFacts = boundaryFacts;
    report.programEvents = events.map(event => ({ event: event.event, at: event.at, programId: event.programId,
      reasonCode: event.result?.reasonCode, decision: event.status?.decision }));
    compactCaveNavigationReport(report);
    await writeFile(join(config.runDirectory, 'player-capability-report.json'), `${JSON.stringify(report, null, 2)}\n`);
  }
  return report;
}

/** Preserve decisive facts in the bounded harness report; complete observations and tick progress stay in rawEvidenceFile. */
export function compactCaveNavigationReport(report) {
  const support = block => block ? { x: block.x, y: block.y, z: block.z, blockId: block.blockId } : null;
  const boundary = facts => ({ ...facts, support: support(facts.support) });
  for (const row of report.rows) {
    row.receipts = row.receipts.map(compactReceipt);
    row.freshBoundaryFacts = row.freshBoundaryFacts.map(boundary);
    row.progressSummary = row.actionIds.map(actionId => {
      const entries = row.progress.filter(entry => entry.actionId === actionId);
      const heights = entries.map(entry => entry.position?.y).filter(Number.isFinite);
      return { actionId, updates: entries.length, first: entries[0] ?? null, last: entries.at(-1) ?? null,
        minY: heights.length ? Math.min(...heights) : null, maxY: heights.length ? Math.max(...heights) : null,
        horizontalCollisionUpdates: entries.filter(entry => entry.collision?.horizontal === true).length,
        inWallUpdates: entries.filter(entry => entry.collision?.inWall === true).length };
    });
    delete row.progress;
  }
  report.samples = report.samples.map(sample => ({ requestedAt: sample.requestedAt, returnedAt: sample.returnedAt,
    eventSequence: sample.eventSequence, ready: sample.ready,
    player: Object.fromEntries(['x', 'y', 'z', 'health', 'foodLevel', 'onGround'].map(key => [key, sample.player[key]])),
    dimension: sample.world.dimension, support: support(sample.support), observedBlocks: sample.observedBlocks }));
  report.boundaryCount = report.boundaryFacts.length;
  const retainedBoundaryActions = new Set(report.rows.flatMap(row => row.freshBoundaryFacts.map(facts => facts.actionId)));
  // Completed rows already retain every boundary. Keep standalone boundaries only for an incomplete/failed row.
  report.boundaryFacts = report.boundaryFacts.filter(facts => !retainedBoundaryActions.has(facts.actionId)).map(boundary);
  // Receipts occur once per row; this index keeps command arguments, timing and provenance reviewable without duplicating receipts.
  report.actions = report.actions.map(({ result, ...action }) => ({ ...action,
    result: result ? { state: result.state, reasonCode: result.reasonCode, physicalAttempted: result.physicalAttempted } : null }));
  return report;
}

function compactReceipt(result) {
  if (!result) return null;
  const receipt = Object.fromEntries(['actionId', 'state', 'reasonCode', 'elapsedMs', 'receivedAt', 'physicalAttempted', 'executionStarted']
    .filter(key => result[key] !== undefined).map(key => [key, result[key]]));
  if (result.actionObservation) receipt.actionObservation = Object.fromEntries(['observedAtEpochMs', 'worldTick', 'position', 'collision', 'target', 'progress']
    .filter(key => result.actionObservation[key] !== undefined).map(key => [key, result.actionObservation[key]]));
  return receipt;
}

function compactProgress(result) {
  return { actionId: result.actionId, receivedAt: result.receivedAt, elapsedMs: result.elapsedMs,
    worldTick: result.actionObservation?.worldTick, position: result.actionObservation?.position, collision: result.actionObservation?.collision };
}

if (process.argv[1] && fileURLToPath(import.meta.url).toLowerCase() === process.argv[1].toLowerCase()) {
  const report = await runCaveNavigationLive(parseProbeArguments(process.argv.slice(2)));
  process.stdout.write(`${JSON.stringify(report)}\n`);
  if (report.status !== 'PASSED') process.exitCode = 1;
}
