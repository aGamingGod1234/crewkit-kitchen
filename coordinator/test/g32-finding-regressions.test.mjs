import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { MultiplexedServerBridge, createBridgeAuthenticationProof } from '../src/protocol-v2.mjs';
import { classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';

const secret = 's'.repeat(32);
const record = { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority', goalRevision: 1, currentGoal: 'Collect stone', currentGoalSpec: null };
const registered = { ...record, schemaVersion: 1, skinVariant: 'teal', state: 'ACTING', queue: [], createdAtEpochMs: 1, updatedAtEpochMs: 1 };
delete registered.currentGoalSpec;
const envelope = (type, messageId, payload, agentId = 'agent-a') => ({ protocolVersion: 2, serverInstanceId: 'server-instance', agentId, type, messageId, payload });
class FakeSocket extends EventEmitter {
  writes = []; destroyed = false;
  write(data) { this.writes.push(JSON.parse(String(data))); return true; }
  pause() {} resume() {} setNoDelay() {}
  destroy() { if (!this.destroyed) { this.destroyed = true; this.emit('close'); } }
}
function fixture(options = {}, registry = [registered]) {
  const socket = new FakeSocket();
  const bridge = new MultiplexedServerBridge({ port: 25570, secret, ...options }, { socketFactory: () => socket, schedule: () => 1, cancelSchedule() {} });
  const errors = [];
  bridge.on('protocolError', error => errors.push(error.code));
  bridge.on('listenerError', error => errors.push(String(error.stack)));
  const emit = frames => socket.emit('data', Buffer.from(frames.map(JSON.stringify).join('\n') + '\n'));
  bridge.start(); socket.emit('connect');
  const challenge = socket.writes.at(-1);
  const clientNonce = challenge.payload.clientNonce;
  const serverNonce = Buffer.alloc(32, 7).toString('base64url');
  emit([envelope('auth_response', 'auth', { replyTo: challenge.messageId, clientNonce, serverNonce, proof: createBridgeAuthenticationProof(secret, 'server', { clientNonce, serverNonce, serverInstanceId: 'server-instance' }) }, 'server')]);
  emit([envelope('hello_ack', 'hello', { replyTo: socket.writes.at(-1).messageId, authenticated: true, registry }, 'server')]);
  assert.equal(bridge.ready, true);
  return { socket, bridge, emit, errors };
}
function observation(sequence, playerChanges = {}, changedFacts = []) {
  return {
    goalRevision: 1, observedAtEpochMs: sequence * 50, ready: true, status: 'ACTING', eventSequence: sequence,
    attention: changedFacts.length > 0, changedFacts,
    position: { x: 0, y: 64, z: 0 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
    player: { health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5, gameMode: 'survival', onGround: true, inWater: false, onFire: false, air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [], ...playerChanges },
    inventory: { items: [], selectedItem: 'minecraft:air' }, entities: [], blocks: [], nearbyContainers: [],
    world: { dimension: 'minecraft:overworld', gameTime: sequence, dayTime: sequence, raining: false, thundering: false },
    currentAction: { active: false }, lastResult: { present: false },
  };
}
const occupancy = () => ({ ingest() {}, candidates() { return []; }, async load() {}, async flush() {}, clear() {} });

// Authenticated in-memory transport and real native executor regressions adapted
// from verified f032/f033 probes. No providers, sockets, game, or persistent memory.
const nextTurn = () => new Promise(setImmediate);
async function runCase({ name, hazard = null, combined = true, reassessFalse = false, watcher = false, repeatedDelta = false }) {
  const f = fixture();
  const events = [], received = [];
  const runtime = new NativeToolRuntime({ bridge: f.bridge, registry: { get: () => record }, occupancy: occupancy(),
    onProgramEvent: (_record, event) => events.push(structuredClone(event)) });
  let previousPlayer = adaptObservation(observation(1)).player;
  f.bridge.on('observation', event => {
    const payload = event.payload;
    const fresh = adaptObservation(payload);
    const classified = classifyObservationTrigger(payload, payload, { previousPlayer });
    previousPlayer = fresh.player;
    received.push({ sequence: payload.eventSequence, attention: payload.attention, changedFacts: payload.changedFacts, classified });
    runtime.updateObservation(record, fresh, { eventSequence: payload.eventSequence, ...classified, changedFacts: payload.changedFacts });
  });
  let callCount = 0;
  const call = tool => runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'offline-turn', callId: `offline-call-${++callCount}`, tool }, record);
  try {
    runtime.updateObservation(record, adaptObservation(observation(1)), { eventSequence: 1 });
    const condition = hazard?.condition;
    const source = `program.onUnhandledAttention("continue_and_notify"${reassessFalse ? ', {reassessWhen:()=>false}' : ''});
      ${watcher ? `program.watch(() => ${condition}, {mode:"interrupt"}, async () => { await player.wait(1); });` : ''}
      await player.wait(1000);`;
    const handle = await call({ kind: 'run_program', source, background: true, timeoutMs: 30000 });
    for (let i = 0; i < 20 && !f.socket.writes.some(x => x.type === 'action_command'); i++) await nextTurn();
    assert.equal(f.socket.writes.filter(x => x.type === 'action_command').length, 1, 'real native execute must dispatch the waiting action');
    const changes = hazard ? [hazard.path] : [];
    const first = observation(2, hazard?.player ?? {}, changes);
    const last = observation(3, hazard?.player ?? {}, repeatedDelta ? changes : []);
    if (!hazard) first.attention = true; // Real receipt/sample administrative wake shape.
    const frames = [envelope('observation', 'first', first), envelope('observation', 'last', last)];
    if (combined) f.emit(frames); else for (const frame of frames) f.emit([frame]);
    await nextTurn();
    await nextTurn();
    assert.deepEqual(f.errors, []);
    const status = await call({ kind: 'program_status', programId: handle.programId });
    const cancellations = f.socket.writes.filter(x => x.type === 'action_cancel').length;
    const attentionEvents = events.filter(x => x.event === 'program_attention');
    const latest = runtime.snapshotLive(record.agentId);
    for (const [field, value] of Object.entries(hazard?.player ?? {})) {
      // Adapter names onFire as fire, but keeps all the target hazard fields.
      if (field === 'onFire') assert.equal(latest.observation.player.fire, value);
      else assert.equal(latest.observation.player[field], value);
    }
    assert.equal(latest.eventSequence, 3);
    const urgentExpected = !!hazard && !watcher;
    assert.equal(cancellations, watcher || urgentExpected ? 1 : 0, `${name}: cancellation result`);
    assert.equal(attentionEvents.length, urgentExpected ? 1 : 0, `${name}: program attention notification`);
    if (urgentExpected) {
      assert.equal(status.decision?.priority, 'urgent');
      assert.equal(status.decision?.trigger, hazard.trigger);
    } else assert.equal(status.decision, undefined);
    return { name, combined, reassessFalse, watcher, repeatedDelta, received, cancellations,
      attentionNotifications: attentionEvents.length, decision: status.decision ?? null,
      latestSequence: latest.eventSequence, engineState: status.engineState };
  } finally {
    await runtime.disposeAll('f032_fixture_done');
    await nextTurn();
    f.bridge.stop();
    assert.equal(f.socket.destroyed, true);
  }
}
const hazards = [
  { path: 'player.air', player: { inWater: true, air: 50 }, condition: 'player.state().air <= 60', trigger: 'suffocation' },
  { path: 'player.suffocating', player: { suffocating: true }, condition: 'player.state().suffocating === true', trigger: 'suffocation' },
  { path: 'player.fallDistance', player: { onGround: false, fallDistance: 7 }, condition: 'player.state().fallDistance >= 6', trigger: 'fall' },
];

for (const hazard of hazards) {
  for (const reassessFalse of [false, true]) {
    for (const combined of [false, true]) {
      const name = `${hazard.path}-${reassessFalse ? 'false-reassess' : 'default'}-${combined ? 'combined' : 'separate'}`;
      test(name, () => runCase({ name, hazard, reassessFalse, combined }));
    }
  }
  test(`${hazard.path}-authored-watcher-control`, () => runCase({ name: hazard.path, hazard, watcher: true }));
}
test('air-latest-delta-preserved-control', () => runCase({ name: 'repeated air', hazard: hazards[0], repeatedDelta: true }));
test('health-comparison-control', () => runCase({ name: 'health', hazard: { path: 'player.health', player: { health: 19 }, trigger: 'damage' } }));
test('current-fire-control', () => runCase({ name: 'fire', hazard: { path: 'player.onFire', player: { onFire: true }, trigger: 'fire' } }));
test('healthy-administrative-wake-control', () => runCase({ name: 'healthy' }));

function receiptObservation(sequence, attention = false) {
  return { ...observation(sequence), attention, position: { x: sequence >= 3 ? 2 : 0, y: 64, z: 0 } };
}
async function receiptCase(spec) {
  const f = fixture();
  const order = [], samples = [];
  const runtime = new NativeToolRuntime({ bridge: f.bridge, registry: { get: () => record }, occupancy: occupancy(), requestObservation: async (_record, { afterEventSequence }) => {
    samples.push(afterEventSequence);
    return { eventSequence: afterEventSequence + 1, observation: adaptObservation(receiptObservation(afterEventSequence + 1)) };
  } });
  try {
    runtime.updateObservation(record, adaptObservation(receiptObservation(1)), { eventSequence: 1 });
    f.bridge.on('observation', event => {
      order.push(`observation:${event.payload.eventSequence}`);
      runtime.updateObservation(record, adaptObservation(event.payload), { eventSequence: event.payload.eventSequence, attention: event.payload.attention, priority: 'ordinary', trigger: 'attention', changedFacts: [] });
    });
    f.bridge.on('action_result', event => { order.push('action_result'); assert.equal(runtime.onActionResult(record, event.payload), true); });
    const tool = spec.directAction ? { kind: 'action', actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30000 } } : { kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);', timeoutMs: 30000, observationIntervalMs: 5000 };
    const running = runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'turn', callId: 'call', tool }, record);
    for (let i = 0; i < 20 && !f.socket.writes.some(message => message.type === 'action_command'); i++) await new Promise(setImmediate);
    const command = f.socket.writes.find(message => message.type === 'action_command');
    assert.ok(command, 'default native executor must dispatch the authored wait');
    const terminal = { traceId: command.payload.traceId, goalRevision: 1, actionId: command.payload.actionId, commandId: command.payload.actionId, actionType: command.payload.actionType, state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 1, observedAtEpochMs: 125 };
    const frames = {
      before: envelope('observation', 'before', receiptObservation(2)),
      result: envelope('action_result', 'result', terminal),
      after: envelope('observation', 'after', receiptObservation(3, true)),
      later: envelope('observation', 'later', receiptObservation(4, true)),
    };
    for (const chunk of spec.chunks) f.emit(chunk.map(key => frames[key]));
    const outcome = await running;
    assert.deepEqual(f.errors, []);
    if (spec.directAction) {
      assert.equal(outcome.state, 'SUCCEEDED');
      assert.equal(outcome.postAction.freshness.fresh, true);
      assert.equal(outcome.postAction.eventSequence, spec.expectedSamples.length ? spec.expectedSamples[0] + 1 : 3);
      assert.equal(outcome.postAction.observation.player.x, 2);
    } else assert.equal(outcome.reasonCode, 'PROGRAM_EXHAUSTED');
    assert.deepEqual(order, spec.expectedOrder);
    assert.deepEqual(samples, spec.expectedSamples);
    return { name: spec.name, chunks: spec.chunks, order, explicitSampleRequests: samples, outcome: outcome.reasonCode, protocolErrors: f.errors };
  } finally {
    await runtime.disposeAll();
    f.bridge.stop();
    assert.equal(f.socket.destroyed, true);
  }
}
const specs = [
  { name: 'all separate', chunks: [['before'], ['result'], ['after']], expectedOrder: ['observation:2', 'action_result', 'observation:3'], expectedSamples: [] },
  { name: 'all combined reproducer', chunks: [['before', 'result', 'after']], expectedOrder: ['observation:2', 'action_result', 'observation:3'], expectedSamples: [] },
  { name: 'boundary before result', chunks: [['before'], ['result', 'after']], expectedOrder: ['observation:2', 'action_result', 'observation:3'], expectedSamples: [] },
  { name: 'boundary after result', chunks: [['before', 'result'], ['after']], expectedOrder: ['observation:2', 'action_result', 'observation:3'], expectedSamples: [] },
  { name: 'no pre-result sample to replace', chunks: [['result', 'after']], expectedOrder: ['action_result', 'observation:3'], expectedSamples: [] },
  { name: 'genuinely missing post-result publication', chunks: [['before', 'result']], expectedOrder: ['observation:2', 'action_result'], expectedSamples: [2] },
  { name: 'later post-result publication rescues combined chunk', chunks: [['before', 'result', 'after'], ['later']], expectedOrder: ['observation:2', 'action_result', 'observation:3', 'observation:4'], expectedSamples: [] },
  { name: 'both observations actually precede result', chunks: [['before', 'after', 'result']], expectedOrder: ['observation:3', 'action_result'], expectedSamples: [3] },
];

for (const spec of specs) test(`receipt: ${spec.name}`, () => receiptCase(spec));
for (const spec of [specs[1], specs[5]]) test(`postAction: ${spec.name}`, () => receiptCase({ ...spec, directAction: true }));

function deliveredFrames(frames) {
  const f = fixture();
  const received = [];
  f.bridge.on('message', event => received.push(event));
  try {
    f.emit(frames);
    assert.deepEqual(f.errors, []);
    return received;
  } finally { f.bridge.stop(); assert.equal(f.socket.destroyed, true); }
}
const obsFrame = (sequence, payload = observation(sequence)) => envelope('observation', `obs-${sequence}`, payload);
test('adjacent quiet snapshots still coalesce to the latest facts', () => {
  const received = deliveredFrames([obsFrame(2), obsFrame(3), obsFrame(4)]);
  assert.deepEqual(received.map(event => event.payload.eventSequence), [4]);
  assert.equal(received[0].payload.attention, false);
});
test('attention evidence is retained even when the next snapshot is healthy', () => {
  const received = deliveredFrames([obsFrame(2, observation(2, { air: 50, inWater: true }, ['player.air'])), obsFrame(3)]);
  assert.deepEqual(received.map(event => event.payload.eventSequence), [2, 3]);
  assert.deepEqual(received[0].payload.changedFacts, ['player.air']);
  assert.equal(received[0].payload.player.air, 50);
  assert.equal(received[1].payload.player.air, 300);
});
test('unavailable observations remain ordered between live snapshots', () => {
  const unavailable = { goalRevision: 1, observedAtEpochMs: 125, ready: false, status: 'PLAYER_UNAVAILABLE', eventSequence: 3 };
  const received = deliveredFrames([obsFrame(2), obsFrame(3, unavailable), obsFrame(4)]);
  assert.deepEqual(received.map(event => event.payload.eventSequence), [2, 3, 4]);
  assert.equal(received[1].payload.ready, false);
});
test('same-revision lifecycle control is an observation ordering barrier', () => {
  const control = envelope('goal_control', 'resume', { operation: 'resume', goalRevision: 1, updatedAtEpochMs: 125 });
  const received = deliveredFrames([obsFrame(2), control, obsFrame(3)]);
  assert.deepEqual(received.map(event => event.type), ['observation', 'goal_control', 'observation']);
  assert.deepEqual(received.filter(event => event.type === 'observation').map(event => event.payload.eventSequence), [2, 3]);
});

test('interleaved agents retain their relative observation order', () => {
  const f = fixture({}, [registered, { ...registered, agentId: 'agent-b' }]);
  const received = [];
  f.bridge.on('observation', event => received.push(`${event.agentId}:${event.payload.eventSequence}`));
  try {
    f.emit([obsFrame(2), envelope('observation', 'agent-b-2', observation(2), 'agent-b'), obsFrame(3)]);
    assert.deepEqual(f.errors, []);
    assert.deepEqual(received, ['agent-a:2', 'agent-b:2', 'agent-a:3']);
  } finally { f.bridge.stop(); assert.equal(f.socket.destroyed, true); }
});

test('attention bursts drain through existing queue limits without losing evidence', async () => {
  const f = fixture({ inboundConnectionQueueCap: 4, inboundAgentQueueCap: 4 });
  const received = [];
  f.bridge.on('observation', event => {
    received.push(event.payload.eventSequence);
    event.waitUntil(Promise.resolve());
  });
  const sequences = Array.from({ length: 12 }, (_, index) => index + 2);
  try {
    f.emit(sequences.map(sequence => obsFrame(sequence, observation(sequence, { inWater: true, air: 50 }, ['player.air']))));
    for (let turn = 0; turn < 20 && received.length < sequences.length && f.errors.length === 0; turn++) await nextTurn();
    assert.deepEqual(f.errors, []);
    assert.deepEqual(received, sequences);
  } finally { f.bridge.stop(); assert.equal(f.socket.destroyed, true); }
});
