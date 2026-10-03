import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { MultiplexedServerBridge } from '../protocol-v2.mjs';
import { HeadlessRconClient } from '../headless-rcon.mjs';
import { NativeToolRuntime } from '../native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from '../native-minecraft-tools.mjs';
import { parseArenaScript } from '../arena-script/parser.mjs';
import { adaptObservation } from '../observation-adapter.mjs';
import { classifyObservationTrigger } from '../dynamic-main.mjs';
import { RuntimeMemoryContext } from '../runtime-memory-context.mjs';
import { TaskMemoryStore } from '../task-memory-store.mjs';
import { parseProbeArguments, probeFailureMessage } from '../player-capability-probe.mjs';

const PROFILE = Object.freeze({ provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' });
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const routine = `${prefix} const args = program.parameters(); await player.wait(args.delay); await player.lookAt(args.target);`;
const waitRoutine = `${prefix} await player.wait(program.parameters().delay);`;
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const terminal = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED']);

/** Deterministic authorship fixture; every physical action uses the installed Minecraft bridge. */
export async function runNativeSuccessorLive(config) {
  const startedAt = Date.now();
  const report = { schemaVersion: 1, scenarioId: 'native-successor-live', status: 'FAILED',
    kind: 'production_bridge_mechanics', providerUsed: false, aiPerformanceMeasured: false,
    fixtureAuthorship: 'deterministic test script with declared selected-agent profile provenance',
    entryPoint: 'normalizeMinecraftToolCall -> NativeToolRuntime -> authenticated MultiplexedServerBridge -> installed Fabric mod',
    observationEntryPoint: 'passive inspection_request(section:observation), plus production observation/action_progress/action_result callbacks',
    fixtureSetupUsesOperatorCommands: true, checks: [], actions: [], samples: [], programEvents: [], cleanup: {} };
  const secret = (await readFile(config.bridgeSecretFile, 'utf8')).trim();
  const password = (await readFile(config.rconPasswordFile, 'utf8')).trim();
  const bridge = new MultiplexedServerBridge({ port: config.bridgePort, secret });
  const rcon = new HeadlessRconClient({ host: config.host, port: config.rconPort, password });
  const messages = [], commands = [], results = [], samples = [], events = [];
  const memory = new RuntimeMemoryContext({ taskMemory: new TaskMemoryStore({ directory: join(config.runDirectory, 'task-memory') }) });
  let record, runtime, callOrdinal = 0, failure = null;
  const now = () => performance.now();
  const command = async text => (await rcon.command(text)).text;
  bridge.on('message', event => messages.push({ ...event, at: now() }));
  bridge.on('transportError', error => { failure = error; });
  bridge.on('protocolError', error => { failure = error; });
  async function until(predicate, label, timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (failure) throw failure;
      const value = predicate();
      if (value) return value;
      await sleep(10);
    }
    throw new Error(`Timed out waiting for ${label}`);
  }
  const waitMessage = (type, predicate = () => true, offset = 0) => until(
    () => messages.slice(offset).find(event => event.type === type && predicate(event)), type);
  const passiveObservation = async () => {
    const requestId = `successor-live-inspect-${randomUUID()}`, offset = messages.length, requestedAt = now();
    await bridge.send('inspection_request', record.agentId, { requestId, goalRevision: record.goalRevision,
      query: { section: 'observation', limit: 16, offset: 0 } });
    const reply = await waitMessage('inspection_result', event => event.agentId === record.agentId && event.payload.requestId === requestId, offset);
    assert.equal(reply.payload.error, undefined);
    const wireResult = reply.payload.result;
    const result = { ...wireResult, observation: adaptObservation(wireResult.observation) };
    assert.equal(result.observation.ready, true);
    assert.ok(result.observation.world.worldId);
    assert.equal(result.observation.world.dimension, 'minecraft:overworld');
    samples.push({ requestedAt, returnedAt: now(), eventSequence: result.eventSequence,
      gameTime: result.observation.world.gameTime, health: result.observation.player.health,
      ready: result.observation.ready, world: result.observation.world,
      inventory: result.observation.inventory, view: wireResult.observation.view });
    return result;
  };
  const call = (name, args = {}) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision,
    turnId: 'deterministic-selected-agent-fixture', callId: `live-fixture-${++callOrdinal}`,
    tool: normalizeMinecraftToolCall(name, args) }, record);
  const check = async (name, body) => {
    const before = Date.now();
    try { const evidence = await body(); report.checks.push({ name, status: 'PASSED', elapsedMs: Date.now() - before, evidence }); }
    catch (error) { report.checks.push({ name, status: 'FAILED', reason: probeFailureMessage(error, [secret, password]) }); throw error; }
  };
  const finalProgram = programId => until(() => events.find(event => event.programId === programId && event.event === 'program_ended'), 'program end');
  try {
    const installedMods = join(config.runDirectory, 'server', 'mods');
    const jars = (await readdir(installedMods)).filter(name => /^arena-agents-[\d.]+\.jar$/.test(name));
    assert.equal(jars.length, 1, 'one current installed Arena Agents jar');
    report.installedJar = { name: jars[0], sha256: hash(await readFile(join(installedMods, jars[0]))) };
    report.sourceSha256 = hash(routine);
    report.runtimeSha256 = hash(await readFile(new URL('../native-tool-runtime.mjs', import.meta.url)));
    await rcon.connect();
    const bridgeReady = new Promise((resolve, reject) => {
      bridge.once('ready', resolve); bridge.once('protocolError', reject); bridge.once('transportError', reject);
      setTimeout(() => reject(new Error('Authenticated bridge did not become ready')), 12000).unref();
    });
    bridge.start(); await bridgeReady;
    await bridge.send('catalog_snapshot', 'server', { refreshedAtEpochMs: Date.now(), models: [{ provider: PROFILE.provider,
      model: PROFILE.model, id: PROFILE.model, displayName: 'Provider-free mechanics fixture', reasoningEfforts: ['medium'], serviceTiers: ['priority', 'fast'] }] });
    await command('forceload add -16 -16 16 16');
    await sleep(700);
    await command('difficulty peaceful');
    await command('fill -4 64 -4 8 68 8 minecraft:air');
    await command('fill -4 63 -4 8 63 8 minecraft:stone');
    const registeredOffset = messages.length;
    await command(`execute in minecraft:overworld positioned 0.5 64 0.5 run codex summon ${PROFILE.model} ${PROFILE.reasoningEffort} ${config.agentName}`);
    const registered = await waitMessage('agent_registered', () => true, registeredOffset);
    record = { ...(registered.payload.record ?? registered.payload), agentId: registered.agentId };
    bridge.on('goal_spec_request', event => {
      if (event.agentId !== record.agentId) return;
      bridge.send('goal_spec_proposal', record.agentId, { requestId: event.payload.requestId,
        summary: 'Obtain a stone pickaxe after the mechanics fixture.', predicate: { type: 'inventory_contains', itemId: 'minecraft:stone_pickaxe', count: 1 } }).catch(error => { failure = error; });
    });
    await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
    await waitMessage('observation', event => event.agentId === record.agentId && event.payload.ready === true);
    const goalOffset = messages.length;
    report.fixtureGoalResponse = await command(`codex start ${config.agentName} Get a stone pickaxe`);
    const goal = await waitMessage('goal_control', event => event.agentId === record.agentId && event.payload.operation === 'start', goalOffset);
    record.goalRevision = goal.payload.goalRevision;
    await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
    const player = record.entityUuid ?? config.agentName;
    await command(`tp ${player} 0.5 64 0.5 90 0`);
    await command(`clear ${player}`);
    report.registeredProfile = Object.fromEntries(Object.keys(PROFILE).map(key => [key, record[key]]));
    runtime = new NativeToolRuntime({ sessionId: 'native-successor-live', registry: { get: () => record },
      bridge: { send: async (type, agentId, payload) => {
        if (type === 'action_command') commands.push({ ...structuredClone(payload), dispatchedAt: now() });
        return bridge.send(type, agentId, payload);
      } }, requestObservation: passiveObservation,
      memoryObservation: (r, o) => memory.observe(r, o), taskContext: (r) => memory.taskContext(r),
      memoryOperation: (r, operation) => memory.execute(r, operation),
      onProgramEvent: (_record, event) => events.push({ ...event, at: now() }) });
    bridge.on('observation', event => {
      if (event.agentId !== record.agentId || event.payload.goalRevision !== record.goalRevision) return;
      const raw = event.payload.observation ?? event.payload;
      runtime.updateObservation(record, adaptObservation(raw), { eventSequence: event.payload.eventSequence,
        ...classifyObservationTrigger(event.payload, raw), changedFacts: event.payload.changedFacts });
    });
    bridge.on('action_progress', event => {
      if (event.agentId === record.agentId) runtime.onActionProgress(record, event.payload);
    });
    bridge.on('action_result', event => {
      if (event.agentId !== record.agentId || !commands.some(action => action.actionId === event.payload.actionId)) return;
      results.push({ ...structuredClone(event.payload), receivedAt: now() });
      runtime.onActionResult(record, event.payload);
      bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: event.payload.actionId }).catch(error => { failure = error; });
    });
    const initial = await passiveObservation();
    runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
    report.world = { ready: initial.observation.ready, ...initial.observation.world };
    await check('vanilla infinite Haste survives real observation ingress and subsequent actions', async () => {
      const offset = messages.length;
      await command(`effect give ${player} minecraft:haste infinite 0 true`);
      const sample = await passiveObservation();
      const effect = sample.observation.player.effects.find(effect => effect.effectId === 'minecraft:haste');
      assert.equal(effect?.duration, -1, 'vanilla infinite duration retained in adapted facts');
      const action = await call('wait', { durationMs: 50 });
      assert.equal(action.state, 'SUCCEEDED');
      const following = await passiveObservation();
      assert.equal(following.observation.player.effects.find(effect => effect.effectId === 'minecraft:haste')?.duration, -1);
      assert.ok(messages.slice(offset).some(event => event.type === 'observation'), 'ordinary observation stream remains live');
      await command(`effect clear ${player} minecraft:haste`);
      return { duration: -1, actionState: action.state, gameTimeBefore: sample.observation.world.gameTime,
        gameTimeAfter: following.observation.world.gameTime, providerCalls: 0 };
    });
    await check('parameterized routine and ready queued successor execute real independent programs', async () => {
      const offset = commands.length;
      const first = { delay: 1200, target: { x: 2.5, y: 65.5, z: 0.5 } };
      const second = { delay: 100, target: { x: 0.5, y: 65.5, z: 3.5 } };
      const parsed = parseArenaScript(routine);
      const predecessor = await call('runProgram', { source: routine, parameters: first, background: true,
        maxActions: 2, timeoutMs: 5000, expectedDurationMs: 1500 });
      await until(() => commands.length === offset + 1, 'predecessor dispatch');
      const status = await call('programStatus', { programId: predecessor.programId });
      const queued = await call('queueProgram', { source: routine, parameters: second, afterProgramId: predecessor.programId,
        goalRevision: record.goalRevision, programVersion: status.programVersion, precondition: 'player.state().health > 0',
        maxActions: 2, timeoutMs: 3000, expectedDurationMs: 500 });
      assert.equal(queued.state, 'QUEUED');
      assert.equal(commands.length, offset + 1, 'queue preparation does not dispatch a successor');
      const handoff = await until(() => events.find(event => event.event === 'program_handoff_started' && event.predecessorProgramId === predecessor.programId), 'ready successor handoff');
      const ended = await finalProgram(handoff.programId);
      assert.equal(ended.result.reasonCode, 'PROGRAM_EXHAUSTED');
      assert.equal(ended.result.actions, 2);
      assert.equal(handoff.status.maxActions, 2);
      assert.notEqual(handoff.programId, predecessor.programId);
      assert.ok(handoff.status.deadlineEpochMs > predecessor.deadlineEpochMs - 3500, 'successor has a separate bounded deadline');
      assert.equal(events.some(event => event.programId === predecessor.programId && event.event === 'program_ended'), false);
      const actual = commands.slice(offset).map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
      const expected = [first, second].flatMap(args => [
        normalizeMinecraftToolCall('wait', { durationMs: args.delay }),
        normalizeMinecraftToolCall('act', { actionType: 'look_at', arguments: args.target }),
      ]).map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
      assert.deepEqual(actual, expected);
      const receipts = results.filter(result => commands.slice(offset).some(action => action.actionId === result.actionId) && terminal.has(result.state));
      assert.equal(receipts.length, 4);
      assert.ok(receipts.every(receipt => receipt.state === 'SUCCEEDED'));
      const predecessorEnd = receipts[1].receivedAt, successorStart = commands[offset + 2].dispatchedAt;
      assert.ok(samples.some(sample => sample.requestedAt >= predecessorEnd && sample.returnedAt <= successorStart), 'fresh passive observation precedes successor dispatch');
      assert.equal(hash(parseArenaScript(routine).source), hash(parsed.source), 'parameter reuse preserves authored source');
      for (const action of commands.slice(offset)) for (const key of Object.keys(PROFILE)) assert.equal(action.provenance[key], record[key]);
      const finalFacts = (await passiveObservation()).observation;
      const view = { yaw: finalFacts.player.yaw, pitch: finalFacts.player.pitch };
      return { predecessorProgramId: predecessor.programId, successorProgramId: handoff.programId,
        sameSourceSha256: hash(parsed.source), authoredSourceUnchanged: true,
        expectedActionSha256: hash(expected), actualActionSha256: hash(actual), selectedProfilePreserved: true,
        actions: actual, successorMaxActions: handoff.status.maxActions, successorTimeoutMs: 3000,
        predecessorMaxActions: 2, predecessorTimeoutMs: 5000, handoffGapMs: successorStart - predecessorEnd,
        physicalLookResults: receipts.filter(receipt => receipt.actionType === 'look_at').map(receipt => ({ state: receipt.state, reasonCode: receipt.reasonCode, physicalAttempted: receipt.physicalAttempted })), finalView: view };
    });
    await check('fresh world tick makes an initially true queued precondition false', async () => {
      const initial = await passiveObservation(); runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
      const maxTick = initial.observation.world.gameTime + 5, commandOffset = commands.length;
      assert.ok(initial.observation.world.gameTime < maxTick);
      const predecessor = await call('runProgram', { source: waitRoutine, parameters: { delay: 1200 }, background: true, maxActions: 1, timeoutMs: 5000 });
      await until(() => commands.length === commandOffset + 1, 'guard predecessor dispatch');
      const status = await call('programStatus', { programId: predecessor.programId });
      const queued = await call('queueProgram', { source: waitRoutine, parameters: { delay: 100, maxTick },
        afterProgramId: predecessor.programId, goalRevision: record.goalRevision, programVersion: status.programVersion,
        precondition: 'world.state().gameTime < program.parameters().maxTick', maxActions: 1, timeoutMs: 3000 });
      const rejected = await until(() => events.find(event => event.event === 'program_handoff_rejected' && event.result.predecessorProgramId === predecessor.programId), 'fresh false guard');
      assert.equal(rejected.result.reasonCode, 'SUCCESSOR_PRECONDITION_FALSE');
      assert.equal(commands.length, commandOffset + 1);
      assert.ok(rejected.observation.world.gameTime >= maxTick);
      const completed = results.find(result => result.actionId === commands[commandOffset].actionId && terminal.has(result.state));
      assert.ok(samples.some(sample => sample.requestedAt >= completed.receivedAt && sample.gameTime >= maxTick));
      return { queueId: queued.pendingSuccessor.queueId, initiallyTrueGameTime: initial.observation.world.gameTime,
        maxTick, freshGameTime: rejected.observation.world.gameTime, rejectedReason: rejected.result.reasonCode,
        successorDispatched: false, guardObservationReady: rejected.observation.ready };
    });
    await check('truthy nonboolean precondition cannot authorize a successor', async () => {
      const commandOffset = commands.length;
      const predecessor = await call('runProgram', { source: waitRoutine, parameters: { delay: 700 }, background: true, maxActions: 1, timeoutMs: 4000 });
      await until(() => commands.length === commandOffset + 1, 'truthy guard predecessor dispatch');
      const status = await call('programStatus', { programId: predecessor.programId });
      await call('queueProgram', { source: waitRoutine, parameters: { delay: 100 }, afterProgramId: predecessor.programId,
        goalRevision: record.goalRevision, programVersion: status.programVersion, precondition: 'player.state().health', maxActions: 1, timeoutMs: 2000 });
      const rejected = await until(() => events.find(event => event.event === 'program_handoff_rejected' && event.result.predecessorProgramId === predecessor.programId), 'truthy guard rejection');
      assert.equal(rejected.result.reasonCode, 'SUCCESSOR_PRECONDITION_FALSE');
      assert.equal(commands.length, commandOffset + 1);
      return { healthValue: rejected.observation.player.health, rejectedReason: rejected.result.reasonCode, successorDispatched: false };
    });
    await check('optional mine autoAim preserves exact block coordinates and block identity', async () => {
      await command(`tp ${player} 0.5 64 0.5 90 0`);
      await command(`clear ${player}`);
      await command('setblock 1 64 0 minecraft:oak_log');
      await command('setblock 1 64 1 minecraft:oak_log');
      const target = { x: 1, y: 64, z: 0, expectedBlockId: 'minecraft:oak_log', timeoutMs: 10000 };
      let initial = await passiveObservation(); runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
      const defaultOffset = commands.length, withoutAim = await call('mine', target);
      assert.equal(withoutAim.state, 'FAILED');
      assert.equal(commands.length, defaultOffset + 1);
      assert.equal(commands[defaultOffset].actionType, 'break_block');
      const beforeTarget = await command('execute if block 1 64 0 minecraft:oak_log run time query gametime');
      assert.match(beforeTarget, /time is \d+/i);
      initial = await passiveObservation(); runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
      const offset = commands.length, normalized = normalizeMinecraftToolCall('mine', { ...target, autoAim: true });
      const mined = await call('mine', { ...target, autoAim: true });
      assert.equal(mined.state, 'SUCCEEDED');
      const actual = commands.slice(offset).map(({ actionType, arguments: args }) => ({ actionType, arguments: args }));
      assert.deepEqual(actual, normalized.actions);
      assert.match(await command('execute if block 1 64 0 minecraft:air run time query gametime'), /time is \d+/i);
      assert.match(await command('execute if block 1 64 1 minecraft:oak_log run time query gametime'), /time is \d+/i);
      let after = (await passiveObservation()).observation;
      if (!after.inventory.items.some(item => item.itemId === 'minecraft:oak_log' && item.count >= 1)) {
        const drop = after.items.find(item => item.itemId === 'minecraft:oak_log');
        assert.ok(drop?.uuid ?? drop?.stableId, 'freshly observed exact log drop');
        const pickedUp = await call('act', { actionType: 'pick_up_item', arguments: { targetSelector: drop.uuid ?? drop.stableId } });
        assert.equal(pickedUp.state, 'SUCCEEDED');
        after = (await passiveObservation()).observation;
      }
      assert.ok(after.inventory.items.some(item => item.itemId === 'minecraft:oak_log' && item.count >= 1));
      const inventoryRcon = await command(`data get entity ${player} Inventory`);
      assert.ok(inventoryRcon.includes('minecraft:oak_log'));
      return { target, defaultMineReasonCode: withoutAim.reasonCode, actualActions: actual,
        expectedActionSha256: hash(normalized.actions), actualActionSha256: hash(actual), exactTargetBecameAir: true,
        adjacentDecoyUnchanged: true, inventoryVerifiedBy: ['fresh live observation', 'RCON entity Inventory'],
        inventory: after.inventory.items, operatorCommandsUsedForMiningOrPickup: false };
    });
    await check('live injury interrupts mining, executes only authored retreat and waits for reconsideration', async () => {
      await command(`tp ${player} 0.5 64 0.5`);
      await command(`effect give ${player} minecraft:instant_health 1 5 true`);
      await command('setblock 1 64 0 minecraft:obsidian');
      await call('act', { actionType: 'look_at', arguments: { x: 1.5, y: 64.5, z: 0.5 } });
      const fresh = await passiveObservation(); runtime.updateObservation(record, fresh.observation, { eventSequence: fresh.eventSequence });
      assert.equal(fresh.observation.player.health, 20);
      await call('taskMemory', { operation: 'remember', entry: { kind: 'place', key: 'fixture-floor', label: 'Known retreat floor', summary: 'Observed cleared floor; chosen by deterministic fixture, not a runtime strategy.', position: { x: -3.5, y: 64, z: 0.5 } } });
      const source = `${prefix} const p = program.parameters();
        program.watch(() => player.state().health < p.health, {mode:"interrupt", after:"reconsider"}, async () => {
          await player.navigateTo({x:p.escape.x,y:p.escape.y,z:p.escape.z,tolerance:0.4,sprint:true,timeoutMs:5000});
        });
        await player.breakBlock({x:1,y:64,z:0,expectedBlockId:"minecraft:obsidian",timeoutMs:10000});
        await player.wait(1);`;
      const offset = commands.length;
      const handle = await call('runProgram', { source, parameters: { health: 20, escape: { x: -3.5, y: 64, z: 0.5 } }, background: true, observationIntervalMs: 100, timeoutMs: 12000, maxActions: 3 });
      await until(() => commands.slice(offset).some(a => a.actionType === 'break_block'), 'active mining');
      const injectedAt = now();
      await command(`damage ${player} 2 minecraft:generic`);
      await until(() => commands.slice(offset).some(a => a.actionType === 'navigate_to'), 'authored defensive retreat');
      await command(`effect give ${player} minecraft:instant_health 1 5 true`);
      await sleep(60); // Let the fixture's explicit heal apply on a server tick.
      const rearmed = await passiveObservation(); runtime.updateObservation(record, rearmed.observation, { eventSequence: rearmed.eventSequence });
      assert.equal(rearmed.observation.player.health, 20, 'fixture healing rearms the defensive condition');
      assert.equal(results.some(r => r.actionId === commands[offset + 1].actionId && terminal.has(r.state)), false, 'retreat remains in progress before the second hit');
      await command(`damage ${player} 2 minecraft:generic`);
      await until(() => events.some(e => e.programId === handle.programId && e.status?.decision?.trigger === 'defensive_handler_completed'), 'defensive reconsideration');
      const status = await call('programStatus', { programId: handle.programId });
      assert.equal(status.engineState, 'SUSPENDED');
      const actions = commands.slice(offset);
      assert.deepEqual(actions.map(a => a.actionType), ['break_block', 'navigate_to']);
      assert.equal(results.find(r => r.actionId === actions[0].actionId)?.state, 'CANCELLED');
      assert.equal(results.find(r => r.actionId === actions[1].actionId)?.state, 'SUCCEEDED');
      const after = await passiveObservation();
      assert.ok(Math.abs(after.observation.player.x + 3.5) <= 0.5);
      await sleep(350);
      assert.equal(commands.length - offset, 2, 'mining cannot silently resume');
      const recalled = await call('taskMemory', { operation: 'query', query: { kind: 'place' } });
      assert.equal(recalled.entries[0].key, 'fixture-floor');
      await call('respondProgram', { programId: handle.programId, goalRevision: record.goalRevision, decisionId: status.decision.decisionId, directive: 'pause' });
      return { injurySetup: 'Two RCON damage 2 minecraft:generic hits, with healing between them, during real mining and authored retreat', actionTypes: actions.map(a => a.actionType),
        damageCommands: 2, rearmedAtHealth: rearmed.observation.player.health,
        miningState: 'CANCELLED', retreatState: 'SUCCEEDED', autoResumedMining: false,
        injuryCommandToRetreatDispatchMs: actions[1].dispatchedAt - injectedAt, healthAfter: after.observation.player.health,
        providerCalls: 0, tacticAuthor: 'deterministic fixture', rememberedPlace: recalled.entries[0].key };
    });
    report.status = 'PASSED';
  } catch (error) {
    report.failure = probeFailureMessage(error, [secret, password]);
    report.recentProtocolErrors = messages.filter(event => event.type === 'error').slice(-3).map(event => event.payload);
    report.recentGoalEvents = messages.filter(event => ['goal_control', 'goal_spec_request', 'goal_spec_result', 'conversation_wake'].includes(event.type)).slice(-4).map(({ type, payload }) => ({ type, payload }));
  } finally {
    try { await runtime?.disposeAll(); report.cleanup.runtime = 'CLEAN'; } catch { report.cleanup.runtime = 'FAILED'; }
    try { await memory.flush(); report.cleanup.taskMemory = 'FLUSHED'; } catch { report.cleanup.taskMemory = 'FAILED'; }
    try { if (record) await command(`codex remove ${config.agentName}`); report.cleanup.agent = 'REMOVED'; } catch { report.cleanup.agent = 'FAILED'; }
    bridge.stop(); await rcon.close();
    report.actions = commands.map(action => {
      const result = results.findLast(result => result.actionId === action.actionId && terminal.has(result.state));
      return { ...action, result: result === undefined ? null : Object.fromEntries(['state', 'reasonCode', 'elapsedMs', 'receivedAt', 'executionStarted', 'physicalAttempted', 'actionObservation'].filter(key => result[key] !== undefined).map(key => [key, result[key]])) };
    });
    report.samples = samples.map(({ world, inventory, ...sample }) => ({ ...sample, world: { worldId: world.worldId, dimension: world.dimension }, inventoryItems: inventory.items }));
    report.programEvents = events.map(event => ({ event: event.event, at: event.at, programId: event.programId,
      predecessorProgramId: event.predecessorProgramId, queueId: event.queueId,
      reasonCode: event.result?.reasonCode, state: event.result?.state, actions: event.result?.actions,
      status: event.status === undefined ? undefined : { state: event.status.state, maxActions: event.status.maxActions, deadlineEpochMs: event.status.deadlineEpochMs, programVersion: event.status.programVersion },
      observation: { ready: event.observation.ready, gameTime: event.observation.world?.gameTime, health: event.observation.player?.health } }));
    report.elapsedMs = Date.now() - startedAt;
    await writeFile(join(config.runDirectory, 'player-capability-report.json'), JSON.stringify(report, null, 2));
  }
  return report;
}

if (process.argv[1] && fileURLToPath(import.meta.url).toLowerCase() === process.argv[1].toLowerCase()) {
  const report = await runNativeSuccessorLive(parseProbeArguments(process.argv.slice(2)));
  console.log(JSON.stringify(report));
  if (report.status !== 'PASSED') process.exitCode = 1;
}
