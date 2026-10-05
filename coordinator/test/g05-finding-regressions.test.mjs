import { ModelNotebook } from '../src/model-notebook.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { presentNativeToolResult } from '../src/codex-service.mjs';
import { decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { toolResultContent, MAX_TOOL_RESULT_BYTES } from '../src/native-minecraft-tools.mjs';
import { Session } from 'node:inspector';
import { createInterpreterFacts, isTrustedInterpreterFacts } from '../src/arena-script/facts.mjs';
import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { SCRIPT_BINDINGS } from '../src/arena-script/minecraft-api.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { nativeObservationSignature } from '../src/dynamic-main.mjs';
import { ExplorationOccupancy } from '../src/explore-frontier.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { encodeJsonLine } from '../src/jsonl.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { ObservedMemoryStore } from '../src/observed-memory-store.mjs';
import { createProtocolV2Envelope, validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import assert from 'node:assert/strict';
import test from 'node:test';

test('g05: protocol-valid rich facts execute through native programs without losing geometry', async () => {

// Regression fixtures adapted from f003/f005/f026/f036/f059 verification.
// Bridge acknowledgements are synthetic; runtime, schema and interpreter are real.
const materials = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped', 'stone', 'cobblestone', 'mossy_cobblestone', 'brick', 'stone_brick'];
function observation(count, rich = true) {
  return {
    goalRevision: 1, observedAtEpochMs: 1, ready: true, status: 'READY', eventSequence: 1, attention: false, changedFacts: [],
    position: { x: 0, y: 64, z: 0 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
    player: { health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5, gameMode: 'survival', onGround: true, inWater: false, onFire: false, air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [] },
    inventory: { items: [], selectedItem: 'minecraft:air', tagCounts: {} }, entities: [], nearbyContainers: [],
    world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false }, currentAction: { active: false }, lastResult: { present: false },
    blocks: Array.from({ length: count }, (_, index) => ({
      x: index % 13 - 6, y: 63, z: Math.floor(index / 13) - 4, blockId: `minecraft:${materials[Math.floor(index / 8)]}_stairs`,
      ...(rich ? {
        state: { facing: 'north', half: 'bottom', shape: 'straight', waterlogged: 'false' },
        bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 0.5, maxZ: 1 }, { minX: 0, minY: 0.5, minZ: 0, maxX: 1, maxY: 1, maxZ: 0.5 }],
        boundsTruncated: false, replaceable: false,
      } : {}),
      tags: ['#minecraft:stairs'], placeableFaces: [],
    })),
  };
}
function wire(count, rich = true) {
  const envelope = createProtocolV2Envelope({ serverInstanceId: 'f003-server', agentId: 'f003-agent', type: 'observation', messageId: `f003-${count}`, payload: observation(count, rich) });
  const encoded = encodeJsonLine(envelope);
  assert.ok(Buffer.byteLength(encoded) < 65536);
  // Parse/validate again to emulate a real serialization boundary, not only trusted envelope reuse.
  const payload = validateProtocolV2Payload('observation', JSON.parse(encoded).payload);
  return { payload, bytes: Buffer.byteLength(encoded) };
}
function keys(value) {
  return value !== null && typeof value === 'object' ? Object.keys(value).length + Object.values(value).reduce((sum, child) => sum + keys(child), 0) : 0;
}
const record = { agentId: 'f003-agent', goalRevision: 1, provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const source = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);';
const cases = [];
for (const [count, rich] of [[64, true], [115, true], [116, true], [117, true], [128, true], [128, false]]) {
  const { payload, bytes } = wire(count, rich);
  const adapted = adaptObservation(payload);
  const facts = createInterpreterFacts(adapted);
  assert.equal(facts.world.blocks.length, count);
  for (let i = 0; i < count; i++) {
    assert.deepEqual(JSON.parse(JSON.stringify(facts.world.blocks[i].bounds ?? null)), adapted.blocks[i].bounds ?? null);
    assert.deepEqual(JSON.parse(JSON.stringify(facts.world.blocks[i].state ?? null)), adapted.blocks[i].state ?? null);
  }
  if (rich && count === 128) {
    assert.equal(isTrustedInterpreterFacts(facts), true);
    const vm = new ArenaScriptInterpreter(parseArenaScript(source), SCRIPT_BINDINGS);
    assert.throws(() => vm.start(structuredClone(facts)), error => error.code === 'FACT_LIMIT', 'plain caller data cannot forge the observation trust brand');
  }
  const timers = new Set();
  const executor = new NativeProgramExecutor({ sessionId: `f003-${count}-${rich}`, setTimeoutFn: () => { const handle = {}; timers.add(handle); return handle; }, clearTimeoutFn: h => timers.delete(h) });
  let actions = 0;
  const result = await executor.run(record, { source }, {
    observation: payload, eventSequence: 1,
    executeAction: async () => { actions++; return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
    cancelAction: async () => {}, refreshObservation: async () => ({ observation: payload, eventSequence: 2 }),
  });
  assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
  assert.equal(actions, 1);
  assert.equal(executor.status(record), null);
  assert.equal(timers.size, 0);
  cases.push({ count, rich, bytes, rootKeys: keys(facts), worldKeys: keys(facts.world), trusted: isTrustedInterpreterFacts(facts), state: result.state, reasonCode: result.reasonCode, actions, cleanedUp: true });
}

// Actual native runtime with its default real NativeProgramExecutor. Only the
// Minecraft transport/sample acquisition is synthetic; no provider/game is run.
// dynamic-main supplies already adapted observations to the native runtime.
let current = adaptObservation(wire(128).payload), sequence = 1, call = 0, runtime;
const sent = [], acknowledgements = [];
let outstandingLeases = 0;
runtime = new NativeToolRuntime({
  registry: { get: () => record }, sessionId: 'f003-runtime',
  onWorkStarted: () => { outstandingLeases++; return () => { outstandingLeases--; }; },
  bridge: { send: async (type, agentId, payload) => {
    validateProtocolV2Payload(type, payload);
    sent.push({ type, agentId, payload });
    if (type === 'action_command') queueMicrotask(() => acknowledgements.push(runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: 'DONE', executionStarted: true })));
  } },
  requestObservation: async () => ({ observation: current, eventSequence: ++sequence }),
});
function execute(tool) {
  if (tool.kind === 'run_program') {
    const { kind, ...arguments_ } = tool;
    tool = normalizeMinecraftToolCall('runProgram', arguments_);
  }
  return runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'f003-turn', callId: `f003-call-${++call}`, tool }, record);
}
try {
  assert.equal(runtime.updateObservation(record, current, { eventSequence: sequence }), true);
  const completed = await execute({ kind: 'run_program', source });
  assert.equal(completed.state, 'YIELDED');
  assert.equal(completed.reasonCode, 'PROGRAM_EXHAUSTED');
  assert.equal(completed.actions, 1);
  assert.equal(sent.length, 1);
  assert.equal(outstandingLeases, 0);
  const statusAfterCompletion = await execute({ kind: 'program_status', programId: completed.programId });
  assert.equal(statusAfterCompletion.state, 'YIELDED');
  const alternateSource = await execute({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("no actions");' });
  assert.notEqual(alternateSource.reasonCode, 'FACT_LIMIT');
  assert.equal(sent.length, 1);
  const individualAction = await execute({ kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } });
  assert.equal(individualAction.state, 'SUCCEEDED');
  assert.equal(sent.length, 2);
  current = adaptObservation(wire(64).payload);
  assert.equal(runtime.updateObservation(record, current, { eventSequence: ++sequence }), true);
  const recovered = await execute({ kind: 'run_program', source });
  assert.equal(recovered.reasonCode, 'PROGRAM_EXHAUSTED');
  assert.equal(recovered.actions, 1);
  assert.equal(sent.length, 3);
  assert.ok(acknowledgements.every(Boolean));
  assert.equal(outstandingLeases, 0);
} finally {
  await runtime.dispose(record.agentId, 'agent_removed');
  assert.equal(outstandingLeases, 0);
}
});

test('g05: formal parameters shadow function self names before and after yields', async () => {

// Adapted from the independently verified f005 fixture. Only action execution and observations
// are fixtures; compilation, environments, resumption, tool normalization and
// native program execution are production code. No provider or game connection.
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
const facts = { player: { x: 0, y: 64, z: 0, health: 20 }, world: {}, inventory: { tagCounts: {} } };
const cases = [
  { id: 'declaration-collision', helper: 'function count(count) { return count; }', call: 'count(7)', broken: true },
  { id: 'declaration-renamed-control', helper: 'function count(value) { return value; }', call: 'count(7)' },
  { id: 'named-expression-collision', helper: 'const count = function inner(inner) { return inner; };', call: 'count(7)', broken: true },
  { id: 'named-expression-renamed-control', helper: 'const count = function inner(value) { return value; };', call: 'count(7)' },
  { id: 'anonymous-expression-outer-name-control', helper: 'const count = function(count) { return count; };', call: 'count(7)' },
  { id: 'arrow-outer-name-control', helper: 'const count = (count) => count;', call: 'count(7)' },
  { id: 'yielded-declaration-collision', helper: 'async function count(count) { await player.wait(1); return count; }', call: 'await count(7)', broken: true, yields: true },
  { id: 'yielded-declaration-renamed-control', helper: 'async function count(value) { await player.wait(1); return value; }', call: 'await count(7)', yields: true },
  { id: 'yielded-named-expression-collision', helper: 'const count = async function inner(inner) { await player.wait(1); return inner; };', call: 'await count(7)', broken: true, yields: true },
  { id: 'yielded-named-expression-renamed-control', helper: 'const count = async function inner(value) { await player.wait(1); return value; };', call: 'await count(7)', yields: true },
];
for (const entry of cases) {
  const source = `${prefix} ${entry.helper} await player.wait(${entry.call});`;
  const compiled = parseArenaScript(source);
  const interpreter = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS);
  let yielded = 0, outcome;
  try {
    outcome = interpreter.start(facts);
    if (entry.yields) {
      assert.equal(outcome.kind, 'command');
      assert.equal(outcome.call.arguments, 1);
      yielded++;
      outcome = interpreter.resume({ stateToken: outcome.stateToken, state: 'SUCCEEDED', reasonCode: 'DONE' }, facts);
    }
  } catch (error) {
    outcome = { code: error.code, message: error.message };
  }
  assert.equal(outcome.kind, 'command');
  assert.equal(outcome.call.arguments, 7);
}

// Ordinary ECMAScript lexical precedence is a comparison, not an implementation
// substitute. The production-code checks above establish ArenaScript behavior.
function count(count) { return count; }
const named = function inner(inner) { return inner; };
assert.equal(count(7), 7);
assert.equal(named(7), 7);

const record = { agentId: 'f005-offline', goalRevision: 1, provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } };
for (const collision of [true, false]) {
  const actions = [], cancellations = [];
  let sequence = 1;
  const executor = new NativeProgramExecutor({ sessionId: 'f005-offline' });
  const parameter = collision ? 'count' : 'value';
  const source = `${prefix} function count(${parameter}) { return ${parameter}; } await player.wait(count(7));`;
  const request = normalizeMinecraftToolCall('runProgram', { source });
  assert.equal(request.kind, 'run_program');
  const result = await executor.run(record, request, {
    observation, eventSequence: sequence,
    executeAction: async command => { actions.push(command); return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
    cancelAction: async (...args) => { cancellations.push(args); },
    refreshObservation: async () => ({ observation, eventSequence: ++sequence }),
  });
  assert.equal(executor.status(record), null);
  assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
  assert.equal(actions.length, 1);
  assert.equal(actions[0].action.arguments.durationMs, 7);
}
});

test('g05: every native navigation entry point enforces matching goal radius', async () => {

// Adapted from the independently verified f026 matrix. Real normalizer/runtime/ArenaScript engine; bridge receipts are fixtures.
const target = { x: 12, y: 64, z: 12 };
const radius = 1;
const rows = [];
const defer = () => new Promise(resolve => setImmediate(resolve));
const watchdog = setTimeout(() => { console.error('Fixture deadline exceeded'); process.exit(2); }, 10000);
const observation = { ready: true, player: { x: 9, y: 64, z: 12, health: 20 }, blocks: [], entities: [], items: [], world: { worldId: 'f026-offline', dimension: 'minecraft:overworld' }, inventory: { items: [] } };

async function scenario(path, control, args, expectedTolerance, { replaceTerminal = 'CANCELLED' } = {}) {
  const fields = { originalRequest: 'Reach 12 64 12', predicate: { type: 'position_within', dimensionId: 'minecraft:overworld', ...target, radius, stableTicks: 20 }, createdAtTick: 10 };
  const record = { agentId: 'f026', provider: 'codex', model: 'offline-fixture', reasoningEffort: 'low', serviceTier: 'priority', goalRevision: 3, currentGoal: fields.originalRequest, currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) } };
  const originalSpec = structuredClone(record.currentGoalSpec);
  const commands = [];
  const cancellations = [];
  let sequence = 1;
  let calls = 0;
  let autoAck = path !== 'startAction';
  let runtime;
  const receipt = (payload, state = 'SUCCEEDED') => {
    assert.equal(runtime.onActionResult(record, { actionId: payload.actionId, goalRevision: 3, state, reasonCode: state === 'CANCELLED' ? 'ACTION_CANCELLED' : 'DESTINATION_REACHED', executionStarted: true }), true);
  };
  runtime = new NativeToolRuntime({
    bridge: { send: async (type, agentId, payload) => {
      assert.equal(agentId, record.agentId);
      if (type === 'action_command') {
        commands.push(structuredClone(payload));
        if (autoAck && payload.actionType !== 'wait') setImmediate(() => receipt(payload));
      } else if (type === 'action_cancel') cancellations.push(structuredClone(payload));
      else assert.fail(`Unexpected bridge message ${type}`);
    } },
    requestObservation: async () => ({ eventSequence: ++sequence, observation: structuredClone(observation) }),
  });
  runtime.updateObservation(record, observation, { eventSequence: sequence });
  const call = (name, args) => runtime.execute({ agentId: record.agentId, goalRevision: 3, turnId: 'f026-turn', callId: `call-${++calls}`, tool: normalizeMinecraftToolCall(name, args) }, record);
  let result;
  let initialState = null;
  try {
    if (path === 'moveTo') result = await call('moveTo', args);
    else if (path === 'act') result = await call('act', { actionType: 'navigate_to', arguments: args });
    else if (path === 'sequence') result = await call('sequence', { actions: [{ actionType: 'navigate_to', arguments: args }, { actionType: 'look_at', arguments: target }] });
    else if (path === 'startAction') {
      const handle = await call('startAction', { actionType: 'navigate_to', arguments: args });
      assert.equal(handle.state, 'RUNNING');
      assert.equal(commands.length, 1);
      initialState = handle.state;
      receipt(commands[0]);
      result = await call('actionStatus', { actionId: handle.actionId });
      assert.equal(result.actionId, handle.actionId);
    } else if (path === 'replaceAction') {
      const handle = await call('startAction', { actionType: 'wait', arguments: { durationMs: 1000 } });
      assert.equal(handle.state, 'RUNNING');
      const pending = call('replaceAction', { actionId: handle.actionId, goalRevision: 3, actionType: 'navigate_to', arguments: args });
      await defer();
      assert.equal(cancellations.length, 1);
      assert.equal(cancellations[0].actionId, handle.actionId);
      assert.equal(commands.length, 1, 'Replacement cannot dispatch before cancellation receipt');
      receipt(commands[0], replaceTerminal);
      result = await pending;
      if (replaceTerminal !== 'CANCELLED') {
        assert.equal(result.state, 'REPLACEMENT_NOT_STARTED');
        assert.equal(commands.length, 1);
      }
    } else {
      const background = path === 'runProgram-background';
      const handle = await call('runProgram', { source: `program.onUnhandledAttention("pause_and_notify"); await player.navigateTo(${JSON.stringify(args)});`, background, timeoutMs: 2000, maxActions: 1 });
      initialState = handle.state;
      if (background) {
        assert.equal(handle.state, 'RUNNING');
        // Wait only for local JS scheduling, no live polling or model/game execution.
        for (let i = 0; i < 20; i++) {
          await defer();
          result = await call('programStatus', { programId: handle.programId });
          if (result.reasonCode === 'PROGRAM_EXHAUSTED') break;
        }
      } else result = handle;
      assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
      assert.equal(result.actionsSucceeded, 1);
      assert.equal(result.actionsFailed, 0);
    }
    const nav = commands.filter(c => c.actionType === 'navigate_to');
    assert.equal(nav.length, replaceTerminal === 'CANCELLED' ? 1 : 0);
    if (nav.length) {
      assert.equal(nav[0].arguments.tolerance, expectedTolerance);
      assert.equal(nav[0].arguments.x, args.x);
    }
    assert.deepEqual(record.currentGoalSpec, originalSpec);
    rows.push({ path, control, goalRadius: radius, inputTolerance: args.tolerance, dispatchedTolerance: nav[0]?.arguments.tolerance ?? null, initialState, terminalState: result.state, terminalReason: result.reasonCode, actionsSucceeded: result.actionsSucceeded ?? null, cancellationAcknowledgedBeforeReplacement: path === 'replaceAction', goalVerificationRequested: false });
  } finally {
    await runtime.disposeAll();
  }
}

try {
  const paths = ['moveTo', 'act', 'sequence', 'startAction', 'replaceAction', 'runProgram', 'runProgram-background'];
  for (const path of paths) {
    await scenario(path, 'matching-broad', { ...target, tolerance: 4, sprint: false, timeoutMs: 1000 }, 1);
    await scenario(path, 'unrelated-endpoint', { ...target, x: 20, tolerance: 4, sprint: false, timeoutMs: 1000 }, 4);
    await scenario(path, 'already-tighter', { ...target, tolerance: 0.5, sprint: false, timeoutMs: 1000 }, 0.5);
  }
  await scenario('replaceAction', 'previous-finishes-before-cancel', { ...target, tolerance: 4, sprint: false, timeoutMs: 1000 }, null, { replaceTerminal: 'SUCCEEDED' });
  const report = { passed: rows.length, provenance: 'independently authored after auditing original probe; no copied production logic', server: 'injected bridge receipts only; server controller and goal verifier not executed', cleanup: 'All runtimes disposed; no external processes or temporary files created', rows };
} finally { clearTimeout(watchdog); }
});

test('g05: equal-signature heartbeats refresh visible identities and only supplied properties', async () => {

// Adapted from the independently verified f059 fixture. Only production JS classes
// implement observation storage, staleness, state projection and candidates.
const record = { agentId: 'f059', provider: 'codex', model: 'offline', reasoningEffort: 'medium', serviceTier: 'priority', goalRevision: 1, currentGoal: 'Remember the visible door' };
const world = { worldId: 'f059-offline', dimension: 'minecraft:overworld' };
const door = { x: 12, y: 64, z: 0, blockId: 'minecraft:oak_door', state: { open: 'false' } };
const absentControl = { x: -12, y: 64, z: 0, blockId: 'minecraft:chest' };
const view = (gameTime, blocks, x = 0) => ({ ready: true, world: { ...world, gameTime }, player: { x, y: 64, z: 0, dead: false }, inventory: { items: [] }, entities: [], blocks });
const results = [];

async function scenario(mode, sparse = false) {
  const memory = new ObservedMemoryStore();
  const occupancy = new ExplorationOccupancy({ memoryStore: memory });
  let sample;
  const runtime = new NativeToolRuntime({ bridge: { send: async () => { throw new Error('Unexpected bridge operation'); } }, occupancy,
    requestObservation: async () => sample });
  let sequence = 0;
  let previous;
  const publish = (observation, reuse = false) => {
    if (reuse) assert.equal(nativeObservationSignature(previous), nativeObservationSignature(observation), 'refresh precondition must hold');
    const stored = reuse
      ? runtime.refreshObservation(record, observation, { eventSequence: ++sequence })
      : runtime.updateObservation(record, observation, { eventSequence: ++sequence });
    assert.equal(stored, true);
    previous = observation;
  };
  const query = tick => memory.query(record.agentId, { ...world, nowTick: tick });
  try {
    publish(view(10, [door, absentControl]));
    await runtime.initializeMemory(record.agentId);
    const visible = sparse ? { x: door.x, y: door.y, z: door.z, blockId: door.blockId } : door;
    publish(view(20, [visible])); // Changed signature: absent chest/sparse state gets full ingestion.
    for (let tick = 30; tick <= 1230; tick += 10) publish(view(tick, [visible]), mode === 'reuse');
    const during = runtime.decorateObservation(record, previous).exploration;
    const duringDoor = during.candidates.find(c => c.blockId === door.blockId);
    assert.equal(duringDoor.stale, false, 'current-block overlay correctly supplies visible identity');
    assert.equal(runtime.snapshotLive(record.agentId).observation.world.gameTime, 1230, 'raw clocks stay current');
    const duringUnknown = during.candidates.filter(c => c.kind === 'unknown_cell').length;
    publish(view(1235, [], 1)); // Next real update cannot refresh a now absent door.
    const retained = query(1235).blocks.find(b => b.blockId === door.blockId);
    const exposed = runtime.decorateObservation(record, previous).exploration.candidates.find(c => c.blockId === door.blockId);
    const absent = query(1235).blocks.find(b => b.blockId === absentControl.blockId);
    assert.equal(absent.lastSeenTick, 10, 'absent facts must not gain heartbeat recency');
    assert.equal(absent.stale, true);
    assert.equal(exposed.stale, retained.stale);
    assert.equal(retained.lastSeenTick, 1230);
    assert.equal(retained.stale, false);
    assert.equal(retained.blockState, sparse ? undefined : '{"open":"false"}');

    // Exercise the actual explicit-sample tool path as a repair control.
    sample = { observation: view(1240, [door]), eventSequence: sequence + 1 };
    const repaired = await runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'repair', callId: 'observe', tool: { kind: 'observe' } }, record);
    assert.equal(repaired.freshness.fresh, true);
    assert.equal(query(1240).blocks.find(b => b.blockId === door.blockId).lastSeenTick, 1240);
    assert.equal(query(1240).blocks.find(b => b.blockId === door.blockId).blockState, '{"open":"false"}');
    assert.equal(query(1240).blocks.find(b => b.blockId === absentControl.blockId).lastSeenTick, 10);
    results.push({ mode, sparse, heartbeatCount: 121, duringUnknown, duringDoor, retained, exposed, absent, explicitSampleRepair: true });
  } finally { await runtime.disposeAll(); }
}

for (const sparse of [false, true]) {
  await scenario('full', sparse);
  await scenario('reuse', sparse);
}
for (const sparse of [false, true]) {
  const full = results.find(r => r.mode === 'full' && r.sparse === sparse);
  const reuse = results.find(r => r.mode === 'reuse' && r.sparse === sparse);
  assert.ok(full.duringUnknown > 0);
  assert.equal(reuse.duringUnknown, full.duringUnknown);
  assert.equal(full.retained.blockStateObservedTick, sparse ? 10 : 1230);
  assert.equal(reuse.retained.blockStateObservedTick, sparse ? 10 : 1230);
}
});

test('g05: decorating queued old inventory cannot replay a recovered gain', async () => {

// F1 sequence adapted from a18/probe.mjs after audit; F2 is intentionally omitted.
// Controls and the complete coordinator event-path race below are newly authored.
const record = { agentId: 'f036', provider: 'codex', model: 'gpt-6-astra', reasoningEffort: 'high', serviceTier: 'priority', goalRevision: 1 };
const death = { cause: 'fixture', dimensionId: 'minecraft:overworld', x: 0, y: 64, z: 0, diedAtEpochMs: 100,
  respawnDimensionId: 'minecraft:overworld', respawnX: 0, respawnY: 64, respawnZ: 0, respawnYaw: 0, respawnPitch: 0, respawnForced: true, gameMode: 'spectator' };
const live = (count, tick) => ({ ready: true, worldTick: tick, world: { worldId: 'f036-world', dimension: 'minecraft:overworld' }, player: { dead: false, x: 0, y: 64, z: 0 }, inventory: { items: count ? [{ itemId: 'minecraft:diamond', count }] : [] }, blocks: [] });
const remaining = (view) => view.recovery?.lastLostInventory?.find(x => x.itemId === 'minecraft:diamond')?.count ?? 0;

async function runtimeBoundary() {
  const runtime = new NativeToolRuntime({ bridge: { send() { throw new Error('No bridge operations authorized'); } } });
  try {
    runtime.updateObservation(record, live(3, 1), { eventSequence: 1 });
    runtime.updateObservation(record, { death }, { eventSequence: 2 });
    const old = live(0, 3), current = live(1, 4);
    runtime.updateObservation(record, old, { eventSequence: 3 });
    runtime.updateObservation(record, current, { eventSequence: 4 });
    assert.equal(remaining(runtime.decorateObservation(record, current)), 2);
    for (let i = 0; i < 3; i++) assert.equal(remaining(runtime.decorateObservation(record, current)), 2);
    assert.equal(runtime.updateObservation(record, old, { eventSequence: 3 }), false);
    assert.equal(remaining(runtime.decorateObservation(record, {})), 2);
    runtime.decorateObservation(record, { ...old, continuity: { rememberedSections: ['inventory'] } });
    assert.equal(remaining(runtime.decorateObservation(record, current)), 2);
    runtime.decorateObservation(record, old);
    const afterFirstReplay = remaining(runtime.decorateObservation(record, current));
    assert.equal(afterFirstReplay, 2);
    runtime.decorateObservation(record, old);
    const afterSecondReplay = remaining(runtime.decorateObservation(record, current));
    assert.equal(afterSecondReplay, 2);
  } finally { await runtime.dispose(record.agentId, 'agent_removed'); }
}


await runtimeBoundary();
});

test('g05: oversized native receipts keep identity, explicit omissions and traversal', async () => {
 const notebook = new ModelNotebook();
 const memory = new RuntimeMemoryContext({ notebook });
 const record = { agentId: 'g05-receipts', goalRevision: 1, provider: 'codex', model: 'offline', reasoningEffort: 'high', serviceTier: 'priority' };
 const worldId = 'g05-world';
 memory.observe(record, { world: { worldId } });
 const runtime = new NativeToolRuntime({ bridge: { send() { throw Error('unexpected bridge dispatch'); } }, memoryOperation: (r, op) => memory.execute(r, op) });
 const frames = Array.from({ length: 64 }, (_, i) => ({ forward: 1, strafe: 0, jump: false, sneak: false, sprint: true, attack: false, use: false, yaw: i * 2.125, pitch: 0, selectedSlot: i % 9, hand: 'main', ticks: 1, branches: [{ condition: 'horizontal_collision', value: true, nextFrame: 64 }, { condition: 'health_below', value: 10, nextFrame: 64 }] }));
 const largeArguments = normalizeMinecraftToolCall('startAction', { actionType: 'control_sequence', arguments: { maxTicks: 64, frames } }).arguments;
 assert.ok(Buffer.byteLength(JSON.stringify(largeArguments)) > MAX_TOOL_RESULT_BYTES);
 for (const [actionId, large] of [['old', false], ['large', true], ['new', false]]) await notebook.recordReceipt(record.agentId, { worldId, actionId, actionType: large ? 'control_sequence' : 'wait', arguments: large ? largeArguments : { durationMs: 1 }, state: 'SUCCEEDED', reasonCode: 'DONE', executionStarted: true });
 try {
  for (const limit of [1, 20]) {
   let offset = 0;
   const rows = [];
   do {
    const tool = normalizeMinecraftToolCall('queryMemory', { kind: 'receipts', offset, limit });
    const raw = await runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 't', callId: `c-${offset}`, tool }, record);
    const before = structuredClone(raw);
    const presented = presentNativeToolResult(raw, tool);
    const text = presented.response.contentItems[0].text;
    const page = decodeModelFacts(JSON.parse(text));
    presented.commit();
    assert.ok(Buffer.byteLength(text) <= MAX_TOOL_RESULT_BYTES);
    assert.deepEqual(raw, before);
    rows.push(...page.entries);
    assert.ok(page.nextOffset === null || page.nextOffset > offset);
    offset = page.nextOffset;
   } while (offset !== null);
   assert.deepEqual(rows.map(r => r.actionId), ['new', 'large', 'old']);
   const large = rows[1];
   assert.equal(large.state, 'SUCCEEDED');
   assert.equal(large.source, 'server_action_result');
   assert.equal(large.reasonCode, 'DONE');
   // Lossless encoding can now fit these repetitive frames before truncation.
   if (large.arguments !== undefined) assert.deepEqual(large.arguments, largeArguments);
   else {
    assert.ok(large.omittedFields.includes('arguments'));
    assert.equal(large.truncated, true);
   }
  }
  assert.deepEqual((await notebook.findReceipt(record.agentId, { worldId, actionId: 'large' })).arguments, largeArguments);
 } finally { await runtime.disposeAll(); await memory.flush(); }
});

test('g05: compaction evaluates only needed candidates and preserves metadata fallback', async () => {
 const notebook = new ModelNotebook();
 for (let i = 0; i < 20; i++) await notebook.writeNote('g05', { worldId: 'world', key: `note-${i}`, text: 'v'.repeat(2048) });
 const raw = await notebook.query('g05', { worldId: 'world' });
 const session = new Session(); session.connect();
 const post = (method, params = {}) => new Promise((resolve, reject) => session.post(method, params, (err, value) => err ? reject(err) : resolve(value)));
 const profile = async value => {
  await post('Profiler.startPreciseCoverage', { callCount: true, detailed: true });
  try {
   const selected = JSON.parse(toolResultContent(value).contentItems[0].text);
   const coverage = await post('Profiler.takePreciseCoverage');
   const file = coverage.result.find(x => x.url.endsWith('/coordinator/src/native-minecraft-tools.mjs'));
   return { selected, calls: Object.fromEntries(['compactInspectionResult', 'compactInspectionMetadata', 'compactToolResult', 'survivalFacts'].map(name => [name, file.functions.find(f => f.functionName === name)?.ranges[0].count ?? 0])) };
  } finally { await post('Profiler.stopPreciseCoverage'); }
 };
 try {
  await post('Profiler.enable');
  const first = await profile(raw);
  assert.deepEqual(first.calls, { compactInspectionResult: 1, compactInspectionMetadata: 0, compactToolResult: 0, survivalFacts: 0 });
  assert.deepEqual(first.selected.entries, raw.entries.slice(0, first.selected.entries.length));
  assert.equal(first.selected.evictedNotes, 0);
  const second = await profile({ ...raw, outerDetails: 'x'.repeat(20000) });
  assert.equal(second.calls.compactInspectionResult, 2);
  assert.equal(second.calls.compactToolResult, 0);
  assert.ok(second.selected.omittedFields.includes('outerDetails'));
  assert.ok(second.selected.entries.length > 0);
  const small = await notebook.query('g05', { worldId: 'world', limit: 1 });
  const untruncated = await profile(small);
  assert.deepEqual(untruncated.selected, small);
  assert.ok(Object.values(untruncated.calls).every(v => v === 0));
 } finally { await post('Profiler.disable'); session.disconnect(); }
});
