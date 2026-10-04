import assert from 'node:assert/strict';
import test from 'node:test';

// Real coordinator regressions adapted from the independently verified fixtures.
// Provider, bridge, and clocks below are controlled offline test boundaries.

test("g01 f016 status exposes least healthy real profile and native operation without duplicate wire identities", { timeout: 15000 }, async () => {
const { EventEmitter, once } = await import('node:events');
const { AgentRegistry, DynamicAgentState } = await import('../src/agent-registry.mjs');
const { AgentPlanner } = await import('../src/agent-planner.mjs');
const { PlanningScheduler } = await import('../src/planning-scheduler.mjs');
const { ProviderHealthRegistry } = await import('../src/provider-health-registry.mjs');
const { createDynamicCoordinator } = await import('../src/dynamic-main.mjs');
const { buildCoordinatorStatus } = await import('../src/coordinator-status.mjs');
const { createSessionMetadata, profileFingerprint } = await import('../src/provider-session.mjs');

// Independently authored integration fixture. The original a08 statusProbe was
// audited for boundary setup, but this one records failures through real planner
// calls and uses the real scheduler and reconciliation path.
const profiles = ['create-fails', 'turn-fails', 'healthy'].map(agentId => ({
  agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high',
  serviceTier: 'priority', state: DynamicAgentState.IDLE, goalRevision: 0, queue: [],
}));
const registry = new AgentRegistry();
const scheduler = new PlanningScheduler();
const healthRegistry = new ProviderHealthRegistry({ now: () => 1000 });
const telemetry = [];
const calls = [];
const agents = new Map();
let monotonicTime = 0;
let providerStopped = false;
let intervalCleared = false;
let periodicStatus;
const failure = () => Object.assign(new Error('Controlled local provider failure'), { code: 'TURN_FAILED' });
const service = {
  catalog: { stale: false, async refresh() { return { models: [] }; }, assertSupported() {} },
  async start() {},
  async stop() { providerStopped = true; agents.clear(); },
  async reconcile(records) { return { valid: records, invalid: [], catalog: { models: [] } }; },
  getAgent(agentId) { return agents.get(agentId) ?? null; },
  recoverySnapshot() { return [{ provider: 'codex', state: 'backoff', failureCode: 'TURN_FAILED', consecutiveFailureCount: 5, nextProbeAtEpochMs: 31000 }]; },
  async createAgent(profile, options) {
    calls.push({ agentId: profile.agentId, operation: 'create_agent' });
    assert.equal(options.controlProtocol, 'native_tools');
    if (profile.agentId === 'create-fails') throw failure();
    if (!agents.has(profile.agentId)) agents.set(profile.agentId, {
      sessionMetadata: () => createSessionMetadata(profile),
      async setGoalRevision() {},
      async interrupt() {},
      async act() {
        calls.push({ agentId: profile.agentId, operation: 'native_turn' });
        if (profile.agentId === 'turn-fails') throw failure();
        return { status: 'completed', toolCalls: 0 };
      },
    });
    return agents.get(profile.agentId);
  },
};
const planner = new AgentPlanner({ registry, scheduler, codexService: service, healthRegistry,
  now: () => (monotonicTime += 10), telemetrySink: row => telemetry.push(row),
});
const bridge = new EventEmitter();
Object.assign(bridge, {
  connectionEpoch: 1, connected: true, serverInstanceId: 'f016-fixture', sent: [],
  start() { this.ready = true; }, stop() { this.ready = false; },
  async send(type, agentId, payload) {
    this.sent.push({ type, agentId, payload });
    if (type === 'coordinator_status') this.emit('status-sent', payload);
  },
});
const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 'f016-local-fixture-secret-32-chars' }, codex: {} }, {
  bridge, registry, scheduler, planner, healthRegistry, codexService: service,
  memoryDirectory: null, env: {},
  setStatusInterval(callback, delay) { assert.equal(delay, 1000); periodicStatus = callback; return 'manual-interval'; },
  clearStatusInterval(handle) { assert.equal(handle, 'manual-interval'); intervalCleared = true; },
});
const runtimeErrors = [];
coordinator.on('runtimeError', error => runtimeErrors.push({ message: error.message, code: error.code }));
const statusAfterTick = async () => {
  const next = once(bridge, 'status-sent', { signal: AbortSignal.timeout(3000) });
  periodicStatus();
  return (await next)[0];
};
const request = agentId => planner.requestNativeTurn({ agentId, goalRevision: 0,
  input: 'Controlled fixture request', executeTool: async () => { throw new Error('No tools should execute'); },
  preserveState: true,
});
const identity = (profile, operation) => ({ provider: profile.provider, model: profile.model,
  profileFingerprint: profileFingerprint(profile), operation,
});
const snapshot = (profile, operation) => healthRegistry.snapshot(identity(profile, operation));
try {
  await coordinator.start();
  const reconciled = once(coordinator, 'reconciled', { signal: AbortSignal.timeout(3000) });
  bridge.emit('ready', { serverInstanceId: 'f016-fixture', connectionEpoch: 1, registry: profiles });
  await reconciled;
  const baseline = bridge.sent.findLast(row => row.type === 'coordinator_status').payload;
  assert.equal(baseline.reconciled, true);
  assert.equal(baseline.supportedProfileCount, 3);

  for (const profile of profiles.slice(0, 2)) {
    for (let attempt = 0; attempt < 5; attempt++) await assert.rejects(request(profile.agentId), { code: 'TURN_FAILED' });
  }
  await request('healthy');
  const beforeRejections = calls.length;
  for (const profile of profiles.slice(0, 2)) await assert.rejects(request(profile.agentId), { code: 'PROVIDER_CIRCUIT_OPEN' });
  // Native admission still performs cached create_agent, but no sixth act call.
  assert.equal(calls.length, beforeRejections + 1);
  assert.equal(calls.filter(row => row.agentId === 'create-fails').length, 5);
  assert.equal(calls.filter(row => row.agentId === 'turn-fails' && row.operation === 'native_turn').length, 5);
  const actual = [snapshot(profiles[0], 'create_agent'), snapshot(profiles[1], 'native_turn'),
    snapshot(profiles[2], 'create_agent'), snapshot(profiles[2], 'native_turn')];
  assert.deepEqual(actual.map(row => [row.count, row.circuit, row.failureRate]),
    [[5, 'open', 1], [5, 'open', 1], [1, 'closed', 0], [1, 'closed', 0]]);
  assert.equal(new Set(profiles.map(profileFingerprint)).size, 3);
  assert(telemetry.every(row => typeof row.profileFingerprint === 'string'));

  const published = await statusAfterTick();
  assert.deepEqual(published.circuits.map(row => [row.operation, row.count, row.circuit, row.failureRate, row.p95Ms]),
    [['create_agent', 5, 'open', 1, actual[0].p95Ms], ['native_turn', 5, 'open', 1, actual[1].p95Ms]]);
  assert.equal(published.circuits.some(row => row.operation === 'native_turn'), true);
  assert.equal(published.components.find(row => row.component === 'provider:codex').state, 'backoff');

  // Countercontrol: the sanitizer can publish a real native_turn failure snapshot.
  const formatter = buildCoordinatorStatus({ reconciled: true, records: registry.list(),
    supportedAgentIds: new Set(profiles.map(row => row.agentId)), readyStates: new Set([DynamicAgentState.IDLE]),
    pressure: scheduler.pressureSnapshot, healthSnapshots: [actual[1]], latencies: [], bridgeSessionEpoch: 1,
  });
  assert.deepEqual(formatter.circuits.map(row => [row.operation, row.count, row.circuit]), [['native_turn', 5, 'open']]);

  // Countercontrol: explicitly injecting legacy data is visible, proving the
  // coordinator reads the other key rather than delaying/filtering all data.
  healthRegistry.record({ provider: profiles[0].provider, model: profiles[0].model,
    operation: 'decide', durationMs: 37, errorCode: null });
  const legacyControl = await statusAfterTick();
  assert.equal(legacyControl.circuits.find(row => row.operation === 'decide'), undefined, 'native status does not consult unused legacy operations');
  assert.equal(snapshot(profiles[1], 'native_turn').circuit, 'open');
  assert.deepEqual(runtimeErrors, []);

} finally {
  await coordinator.stop();
  assert.equal(providerStopped, true);
  assert.equal(intervalCleared, true);
  assert.equal(bridge.ready, false);
  assert.equal(scheduler.pressureSnapshot.active, 0);

}

});

test("g01 f020 factual dragon fallback survives exhausted local corrections and subsequent outage but respects server rejection", { timeout: 15000 }, async () => {
// Independently authored verifier. Actual coordinator, planner, scheduler, registry,
// and translator; only Minecraft bridge, provider responses, and retry clock are fake.
const { EventEmitter } = await import('node:events');
const { createDynamicCoordinator } = await import('../src/dynamic-main.mjs');
const { fallbackCompiledDragonGoal } = await import('../src/goal-spec-translator.mjs');

const dragon = { requestId: '00000000-0000-4000-8000-000000000199', originalRequest: 'Beat the game', candidateIds: ['minecraft:ender_dragon'] };
const generic = { ...dragon, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] };
const plan = { steps: [{ id: 'prepare', label: 'Prepare equipment', kind: 'manual', status: 'pending', dependsOn: [], detail: 'Choose prerequisites using live evidence.', evidence: null }] };
const cases = [
  { name: 'direct_outage', sequence: ['outage'], calls: 1, proposals: 1 },
  { name: 'schema_invalid_four_times', sequence: ['schema', 'schema', 'schema', 'schema'], calls: 4, proposals: 1 },
  { name: 'local_then_three_outages', sequence: ['schema', 'outage', 'outage', 'outage'], calls: 2, proposals: 1 },
  { name: 'local_then_corrected', sequence: ['schema', 'valid'], calls: 2, proposals: 1 },
  { name: 'invalid_optional_plan', sequence: ['invalid_plan'], calls: 1, proposals: 1 },
  { name: 'schema_valid_unlisted_identifier', sequence: ['unlisted', 'unlisted', 'unlisted', 'unlisted'], calls: 4, proposals: 1 },
  { name: 'server_rejected_then_outage', sequence: ['valid', 'outage'], calls: 2, proposals: 1, serverReject: true, pending: true },
  { name: 'generic_invalid_four_times', request: generic, sequence: ['schema', 'schema', 'schema', 'schema'], calls: 4, proposals: 0, terminal: true },
];

async function run(spec) {
  const request = spec.request ?? dragon;
  const changed = new EventEmitter();
  const timers = new Map();
  const attempts = [], removed = [], runtimeErrors = [], timerDelays = [];
  let serial = 0, stopped = false;
  const touch = () => changed.emit('change');
  const wait = predicate => {
    if (predicate()) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { changed.off('change', check); reject(Error(`Fixture timed out: ${spec.name}`)); }, 2000);
      function check() { if (predicate()) { clearTimeout(timeout); changed.off('change', check); resolve(); } }
      changed.on('change', check);
      check();
    });
  };
  class Bridge extends EventEmitter {
    ready = false; connected = true; connectionEpoch = 1; serverInstanceId = 'f020-fixture'; sent = [];
    start() { this.ready = true; }
    stop() { this.ready = false; }
    async send(type, agentId, payload) { this.sent.push({ type, agentId, payload }); touch(); }
  }
  const bridge = new Bridge();
  const provider = {
    catalog: { stale: false, async refresh() { return { models: [] }; }, assertSupported() {} },
    async reconcile(records) { return { valid: records, invalid: [], catalog: { models: [] } }; },
    getAgent() { return null; },
    async createAgent(profile, settings) {
      assert.equal(profile.model, 'gpt-6-luna');
      assert.equal(settings.controlProtocol, 'goal_spec');
      assert.notEqual(profile.agentId, 'agent-a');
      return {
        async setGoalRevision(revision) { assert.equal(revision, 0); },
        async decide(prompt, options) {
          const mode = spec.sequence[attempts.length];
          assert.ok(mode, 'no unexpected provider calls');
          assert.ok(options.outputSchema.required.includes('plan'));
          const correction = prompt.includes('local validation rejected') ? 'local' : prompt.includes('Minecraft rejected') ? 'server' : null;
          attempts.push({ mode, correction, model: profile.model, controlProtocol: settings.controlProtocol });
          touch();
          if (mode === 'outage') throw Object.assign(Error('Owned fixture provider outage'), { code: 'PROVIDER_UNAVAILABLE' });
          const predicate = request === generic
            ? { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 }
            : { type: 'entity_killed_by_agent', entityType: mode === 'unlisted' ? 'minecraft:zombie' : 'minecraft:ender_dragon', afterGoalStart: true };
          const output = { requestId: request.requestId, summary: 'Choose prerequisites from live evidence.', predicate: mode === 'schema' ? { type: 'unknown' } : predicate,
            plan: mode === 'invalid_plan' ? { steps: [{ ...plan.steps[0], dependsOn: ['prepare'] }] } : plan };
          return options.parseOutput(JSON.stringify(output));
        },
      };
    },
    async removeAgent(id) { removed.push(id); touch(); },
    async stop() { stopped = true; },
  };
  // Omitted controlProtocol selects the current native_tools default. No planner
  // override is passed, so factory wiring creates the real AgentPlanner.
  const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 'owned-fixture-secret'.repeat(3) }, codex: {} }, {
    bridge, codexService: provider, memoryDirectory: null, env: {},
    setStatusInterval: () => 1, clearStatusInterval: () => {},
    setGoalSpecTimeout(callback, ms) { const id = ++serial; timers.set(id, { callback, ms }); timerDelays.push(ms); touch(); return id; },
    clearGoalSpecTimeout(id) { timers.delete(id); touch(); },
  });
  coordinator.on('runtimeError', error => { runtimeErrors.push(error.code ?? error.message); touch(); });
  const proposals = () => bridge.sent.filter(event => event.type === 'goal_spec_proposal');
  const errors = () => bridge.sent.filter(event => event.type === 'agent_error').map(event => event.payload.code);
  let result;
  try {
    await coordinator.start();
    bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'f020-fixture', registry: [{ agentId: 'agent-a', provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'priority', state: 'IDLE', goalRevision: 0, queue: [] }] });
    await wait(() => bridge.sent.some(event => event.type === 'agent_ready'));
    bridge.emit('goal_spec_request', { connectionEpoch: 1, agentId: 'agent-a', payload: request });
    if (spec.serverReject) {
      await wait(() => proposals().length === 1 && timers.size === 1 && removed.length === 1);
      bridge.emit('goal_spec_result', { connectionEpoch: 1, agentId: 'agent-a', payload: { requestId: request.requestId, status: 'rejected', reasonCode: 'INVALID_GOAL_PREDICATE' } });
    }
    for (let count = 1; count < spec.calls; count++) {
      await wait(() => attempts.length === count && timers.size === 1);
      const [id, timer] = timers.entries().next().value;
      timers.delete(id);
      timer.callback();
    }
    await wait(() => attempts.length === spec.calls && removed.length === spec.calls && (spec.terminal ? errors().includes('GOAL_SPEC_TRANSLATION_REJECTED') : timers.size === 1));
    assert.equal(proposals().length, spec.proposals);
    assert.deepEqual(errors(), spec.terminal ? ['GOAL_SPEC_TRANSLATION_REJECTED'] : []);
    assert.equal(timers.size, spec.terminal ? 0 : 1);
    assert.equal(removed.length, spec.calls, `${spec.name}: all auxiliary sessions removed`);
    if (spec.calls > 1) assert.deepEqual(attempts.slice(1).map(attempt => attempt.correction), Array(spec.calls - 1).fill(spec.serverReject ? 'server' : 'local'));
    for (const proposal of proposals()) {
      assert.deepEqual(proposal.payload.predicate, fallbackCompiledDragonGoal(dragon).predicate);
      assert.equal(proposal.payload.plan, undefined, 'advisory plan is stripped before server delivery');
    }
    result = { name: spec.name, fallbackEligible: fallbackCompiledDragonGoal(request) !== null, attempts, proposalCount: proposals().length, errorCodes: errors(), runtimeErrors,
      pendingTimers: timers.size, timerDelays, removedAuxiliarySessions: removed.length, serverRejectionsInjected: spec.serverReject ? 1 : 0 };
    if (proposals().length && !spec.serverReject) {
      bridge.emit('goal_spec_result', { connectionEpoch: 1, agentId: 'agent-a', payload: { requestId: request.requestId, status: 'accepted', reasonCode: 'PROPOSAL_ACTIVATED' } });
      await wait(() => timers.size === 0);
    }
  } finally {
    await coordinator.stop();
    assert.equal(timers.size, 0);
    assert.equal(stopped, true);
    assert.equal(bridge.ready, false);
  }
  return { ...result, cleanup: 'Coordinator stopped; auxiliary sessions removed; timers empty; no subprocess or files created.' };
}

assert.ok(fallbackCompiledDragonGoal(dragon));
assert.equal(fallbackCompiledDragonGoal(generic), null);
const results = [];
for (const spec of cases) results.push(await run(spec));


});

test("g01 f039 native push ingestion has one owner and preserves heartbeat stale classic sample and death controls", { timeout: 15000 }, async () => {
const { EventEmitter, once } = await import('node:events');
const { createDynamicCoordinator, normalizeDynamicConfig } = await import('../src/dynamic-main.mjs');
const { AgentRegistry } = await import('../src/agent-registry.mjs');
const { PlanningScheduler } = await import('../src/planning-scheduler.mjs');
const { RuntimeMemoryContext } = await import('../src/runtime-memory-context.mjs');
const { TaskMemoryStore } = await import('../src/task-memory-store.mjs');
const { AtomicAgentStore } = await import('../src/observed-memory-store.mjs');
const { NativeToolRuntime } = await import('../src/native-tool-runtime.mjs');

// Independently authored integrated probe. External bridge/provider are inert
// fixtures; coordinator ingress, native storage and both memory layers are real.
const results = [];
const originals = [];
const calls = [];
const contexts = new Set();
function wrap(prototype, name, wrapper) {
  const original = prototype[name]; originals.push(() => { prototype[name] = original; });
  prototype[name] = wrapper(original);
}
wrap(RuntimeMemoryContext.prototype, 'observe', original => function(record, observation) {
  contexts.add(this); calls.push({ kind: 'context', observation: structuredClone(observation) });
  return original.call(this, record, observation);
});
wrap(TaskMemoryStore.prototype, 'observe', original => async function(scope, observation) {
  calls.push({ kind: 'task', observation: structuredClone(observation) });
  return original.call(this, scope, observation);
});
wrap(TaskMemoryStore.prototype, 'summary', original => async function(...args) {
  calls.push({ kind: 'summary' }); return original.apply(this, args);
});
wrap(AtomicAgentStore.prototype, 'write', original => function(key, value) {
  if (value?.worldId && value?.agents) calls.push({ kind: 'taskWrite' });
  return original.call(this, key, value);
});
for (const name of ['updateObservation', 'refreshObservation']) {
  wrap(NativeToolRuntime.prototype, name, original => function(...args) {
    const accepted = original.apply(this, args);
    calls.push({ kind: name, accepted }); return accepted;
  });
}
const counts = () => Object.fromEntries(['context','task','summary','taskWrite','updateObservation','refreshObservation'].map(kind => [kind, calls.filter(c => c.kind === kind).length]));
async function settle() {
  await Promise.all([...contexts].map(context => context.flush()));
  await new Promise(resolve => setImmediate(resolve));
}
class Bridge extends EventEmitter {
  sent = []; start() {} stop() {}
  async send(type, agentId, payload) { this.sent.push({ type, agentId, payload }); }
  async deliver(event, payload) {
    let completion;
    this.emit(event, { agentId: 'f039', connectionEpoch: 1, payload, waitUntil: value => { completion = value; } });
    assert.ok(completion, 'real coordinator registered an awaitable ingress handler');
    await completion; await settle();
  }
}
const profile = { agentId: 'f039', provider: 'codex', model: 'gpt-6-astra', reasoningEffort: 'xhigh', serviceTier: 'priority', state: 'STARTING', goalRevision: 1, currentGoal: 'Observe the surroundings.', queue: [] };
function observation(eventSequence, persistent = true) {
  return { goalRevision: 1, eventSequence, observedAtEpochMs: eventSequence, ready: true, attention: false, changedFacts: [],
    position: { x: 0, y: 64, z: 0 }, view: { yaw: 0, pitch: 0 },
    player: { health: 20, maxHealth: 20, foodLevel: 20, onGround: true, effects: [] },
    entities: [], blocks: [], inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1, damage: 0, maxDamage: 250, slot: 0 }] },
    world: { ...(persistent ? { worldId: 'f039-world' } : {}), dimension: 'minecraft:overworld', gameTime: eventSequence, dayTime: eventSequence, raining: false, thundering: false },
    currentAction: { active: false }, lastResult: { present: false } };
}
async function coordinatorCase(protocol, persistent = true) {
  const bridge = new Bridge(), registry = new AgentRegistry(), scheduler = new PlanningScheduler();
  if (protocol === 'arena_script') scheduler.hasScheduled = () => true; // classic downstream planning is outside this ingestion control
  const gate = Promise.withResolvers(), requested = Promise.withResolvers(), requests = [], errors = [];
  const planner = {
    beginReconcile(records, options) {
      const result = registry.reconcile(records, options);
      return { registry: result, complete: Promise.resolve({ registry: result, providers: { valid: result.records, invalid: [], catalog: { models: [] } } }) };
    },
    requestNativeTurn(request) { requests.push(request); requested.resolve(); return gate.promise; },
    async requestPlan() { throw new Error('Fixture must not enter classic planning'); },
    async interrupt() {},
  };
  const supervisor = Object.fromEntries(['activate','terminate','end','progress','observed','recover','ensure','factualProgress','suspend','close'].map(name => [name, () => {}]));
  supervisor.begin = (key, kind) => ({ ...key, kind, operationId: 'fixture' });
  const config = { bridge: { secret: 'f039-isolated-fixture-secret-value' }, codex: protocol === 'native_tools' ? {} : { controlProtocol: protocol } };
  assert.equal(normalizeDynamicConfig({ bridge: config.bridge, codex: {} }).codex.controlProtocol, 'native_tools');
  const coordinator = createDynamicCoordinator(config, { bridge, registry, scheduler, planner,
    codexService: { catalog: { stale: false }, async stop() {} }, goalSupervisor: supervisor,
    memoryDirectory: null, setStatusInterval: () => null, clearStatusInterval: () => {} });
  coordinator.on('runtimeError', error => errors.push(error));
  try {
    await coordinator.start();
    const ready = once(coordinator, 'reconciled');
    bridge.emit('ready', { serverInstanceId: 'f039-fixture', connectionEpoch: 1, registry: [profile] });
    await ready;
    calls.length = 0;
    await bridge.deliver('observation', observation(1, persistent));
    if (protocol === 'native_tools') {
      let timeout;
      try { await Promise.race([requested.promise, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Fixture native request did not settle')), 3000); })]); }
      finally { clearTimeout(timeout); }
      await settle();
    }
    const first = counts();
    assert.equal(first.context, 1);
    assert.equal(first.task, persistent ? first.context : 0);
    if (protocol === 'native_tools' && persistent) {
      const snapshots = calls.filter(c => c.kind === 'task').map(c => c.observation);
      assert.equal(snapshots.length, 1);
      assert.equal(first.summary, 2, 'one ingestion summary plus native turn input summary');
      assert.equal(first.taskWrite, 1, 'duplicate processing does not duplicate persistence');
      calls.length = 0;
      await bridge.deliver('observation', observation(2));
      const heartbeat = counts();
      assert.equal(heartbeat.task, 1); assert.equal(heartbeat.summary, 1);
      assert.equal(heartbeat.refreshObservation, 1); assert.equal(heartbeat.updateObservation, 0);
      assert.equal(heartbeat.taskWrite, 0);
      assert.equal(requests.length, 1, 'unchanged heartbeat does not start another native turn');
      results.push({ check: 'real coordinator quiet heartbeat', counts: heartbeat, nativeTurns: requests.length });
      calls.length = 0;
      await bridge.deliver('observation', observation(2));
      const stale = counts();
      assert.equal(stale.context, 1); assert.equal(stale.task, 1);
      assert.ok(calls.filter(c => ['refreshObservation','updateObservation'].includes(c.kind)).every(c => c.accepted === false));
      results.push({ check: 'same event sequence negative control', counts: stale, nativeStoreRejected: true });
    }
    results.push({ check: `real coordinator ${protocol} persistent=${persistent}`, counts: first, nativeTurns: requests.length });
    assert.deepEqual(errors, []);
  } finally {
    gate.resolve({ status: 'completed', toolCalls: 1 });
    await new Promise(resolve => setImmediate(resolve));
    await coordinator.stop();
    assert.deepEqual(errors, []);
  }
}
async function callbackOwnershipControls() {
  const record = { ...profile };
  const memory = new RuntimeMemoryContext();
  let sequence = 1;
  const live = { ready: true, world: { worldId: 'f039-control-world', dimension: 'minecraft:overworld', gameTime: 1 }, position: { x: 0, y: 64, z: 0 }, player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] } };
  const runtime = new NativeToolRuntime({ registry: { get: () => record }, bridge: { send: async () => {} },
    memoryObservation: (r,o) => memory.observe(r,o), taskContext: r => memory.taskContext(r),
    requestObservation: async () => ({ eventSequence: ++sequence, observation: structuredClone(live) }) });
  try {
    calls.length = 0;
    const result = await runtime.execute({ agentId: record.agentId, goalRevision: 1, turnId: 'control', callId: 'observe', tool: { kind: 'observe' } }, record);
    await settle();
    const inspection = counts();
    assert.equal(inspection.task, 1); assert.equal(result.taskMemory.worldId, live.world.worldId);
    results.push({ check: 'native requested sample callback ownership', counts: inspection, fresh: result.freshness.fresh });
    calls.length = 0;
    const death = { death: { x: 0, y: 64, z: 0, dimensionId: 'minecraft:overworld', cause: 'fall', diedAtEpochMs: 2 } };
    memory.observe(record, death);
    runtime.updateObservation(record, death, { eventSequence: ++sequence });
    await settle();
    const snapshots = calls.filter(c => c.kind === 'task').map(c => c.observation);
    assert.equal(snapshots.length, 2);
    assert.equal(snapshots[0].lastLiveInventory, undefined);
    assert.equal(snapshots[1].lastLiveInventory.items[0].itemId, 'minecraft:iron_pickaxe');
    const summary = await memory.taskContext(record);
    assert.equal(summary.totals.deaths, 1);
    assert.equal(summary.deaths[0].lostInventory[0].itemId, 'minecraft:iron_pickaxe');
    results.push({ check: 'sparse death is enriched, not an identical second snapshot', ingestions: 2, deathRecords: summary.totals.deaths, retainedLostItem: summary.deaths[0].lostInventory[0].itemId });
  } finally { await runtime.dispose(record.agentId); await memory.flush(); }
}
try {
  await coordinatorCase('native_tools');
  await coordinatorCase('native_tools', false);
  await coordinatorCase('arena_script');
  await callbackOwnershipControls();

} finally { for (const restore of originals.reverse()) restore(); }

});

test("g01 f050 voice bootstrap preserves explicit profile receiver cache separation and automatic assignments", { timeout: 15000 }, async () => {
﻿// Adapted from a35/probe-profile-bootstrap.mjs after audit. New paired-mode,
// forwarding, repeated-choice, automatic-choice and missing-Fish controls.
const { EventEmitter } = await import('node:events');
const { Readable } = await import('node:stream');
const { startVoiceWorker } = await import('../src/dynamic-main.mjs');
const { createVoiceHttpServer, createVoiceRequestHeaders } = await import('../src/voice/voice-http-server.mjs');
const { VoiceProfileStore, directorVoiceProfiles } = await import('../src/voice/voice-profile-store.mjs');
const { OpenAiTtsProvider, openAiVoiceForProfile } = await import('../src/voice/openai-speech-provider.mjs');

const secret = 'f050-offline-fixture-secret';
const agentId = '00000000-0000-4000-8000-000000000001';
const choices = directorVoiceProfiles();
const results = [];
const originalFetch = globalThis.fetch;
globalThis.fetch = async () => { throw new Error('Unexpected real network request'); };
async function request(worker, profileId, sequence, text = 'Identical line.') {
  const body = Buffer.from(JSON.stringify({ agentId, text, profileId, radius: 48, conversationSequence: sequence, speed: 1, tone: 'neutral' }));
  const req = Readable.from([body]);
  req.method = 'POST'; req.url = '/v1/tts';
  req.headers = Object.fromEntries(Object.entries({ ...createVoiceRequestHeaders({ secret, path: req.url, contentType: 'application/json', body }), 'Content-Type': 'application/json', 'Content-Length': String(body.length) }).map(([key,value]) => [key.toLowerCase(), value]));
  const res = new EventEmitter();
  res.headersSent = false; res.destroyed = false; res.writableFinished = false;
  res.writeHead = (status, headers) => { res.status = status; res.headers = headers; res.headersSent = true; };
  res.end = body => { res.body = body; res.writableFinished = true; };
  await worker.server.listeners('request')[0](req, res);
  return { status: res.status, requested: profileId, returned: res.headers['X-Voice-Profile'], bytes: res.body.length };
}
try {
  for (const mode of ['fish', 'openai']) {
    for (const variant of ['direct-control', 'production-bootstrap', 'forwarding-control']) {
      const store = new VoiceProfileStore();
      const synthesisInputs = [], openAiRequestVoices = [];
      const openai = new OpenAiTtsProvider({ apiKey: 'f050-fixture-key', fetchImpl: async (_url, options) => {
        openAiRequestVoices.push(JSON.parse(options.body).voice);
        return new Response(Buffer.alloc(480), { status: 200 });
      } });
      const provider = {
        cacheNamespace: () => mode === 'openai' ? openai.cacheNamespace() : 'fish/f050',
        async synthesize(value) {
          synthesisInputs.push(value.voiceId);
          return mode === 'openai' ? openai.synthesize(value) : { sampleRateHz: 48000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960) };
        },
      };
      let worker, receivedStore;
      if (variant === 'direct-control') {
        receivedStore = store;
        worker = createVoiceHttpServer({ provider, fishProvider: mode === 'fish' ? provider : null,
          directorUsesPrimaryProvider: mode === 'openai', profileStore: store, secret, port: 0 });
      } else {
        worker = await startVoiceWorker({ voice: { provider: mode === 'openai' ? 'openai' : 'legacy', secret, port: 0 } },
          { FISH_AUDIO_API_KEY: 'f050-fixture-key', OPENAI_API_KEY: 'f050-fixture-key' }, {
            platform: 'linux', createLocalSpeechProvider: async () => null,
            loadProfileStore: async () => ({ store }), createTtsProvider: () => provider,
            createOpenAiTtsProvider: () => provider, createOpenAiSttProvider: () => ({}),
            createVoiceServer(options) {
              receivedStore = options.profileStore;
              assert.equal(typeof receivedStore.resolveRequested, 'function');
              // Test-only differential: retain production lifecycle wrapper and restore
              // exactly the missing interface method. No product source is modified.
              if (variant === 'forwarding-control') options = { ...options,
                profileStore: { ...options.profileStore, resolveRequested: (...args) => store.resolveRequested(...args) } };
              const server = createVoiceHttpServer(options);
              return { ...server, start: async () => ({ port: 0 }) };
            },
          });
      }
      try {
        const rows = [];
        for (const [index, choice] of choices.entries()) rows.push(await request(worker, choice.profileId, index + 1));
        const repeat = await request(worker, choices[0].profileId, 7);
        assert.equal(rows.every(row => row.status === 200), true);
        assert.equal(repeat.status, 200);
        const explicitAssignments = store.snapshotAssignments();
        const explicitSynthesisCalls = synthesisInputs.length;
        {
          assert.deepEqual(rows.map(row => row.returned), choices.map(choice => choice.profileId));
          assert.deepEqual(synthesisInputs, choices.map(choice => choice.voiceId));
          assert.deepEqual(explicitAssignments, {});
          assert.equal(explicitSynthesisCalls, 6);
        }
        assert.equal(repeat.returned, rows[0].returned);
        const auto = await request(worker, 'voice.auto.v1', 8, 'Automatic control.');
        assert.equal(auto.status, 200); assert.equal(auto.returned, 'voice.ember.v1');
        assert.equal(synthesisInputs.length, explicitSynthesisCalls + 1);
        if (mode === 'openai') assert.deepEqual(openAiRequestVoices, synthesisInputs.map(openAiVoiceForProfile));
        results.push({ mode, variant, resolverAtBootstrap: typeof receivedStore.resolveRequested, rows, repeat,
          explicitSynthesisCalls, explicitAssignments, auto, synthesisInputs, openAiRequestVoices });
      } finally {
        await worker.close(); assert.equal(worker.server.listening, false);
      }
    }
  }
  // A missing Fish configuration rejects before profile resolution rather than
  // silently substituting another provider. This narrows the production trigger.
  let providerCalls = 0;
  const missingStore = new VoiceProfileStore();
  const missing = await startVoiceWorker({ voice: { secret, port: 0 } }, {}, {
    platform: 'win32', createLocalSpeechProvider: async () => null,
    loadProfileStore: async () => ({ store: missingStore }),
    createWindowsTtsProvider: () => ({ async synthesize() { providerCalls++; throw new Error('must not synthesize'); } }),
    createVoiceServer(options) { const server = createVoiceHttpServer(options); return { ...server, start: async () => ({ port: 0 }) }; },
  });
  try {
    const row = await request(missing, choices[0].profileId, 1);
    assert.equal(row.status, 503); assert.equal(providerCalls, 0); assert.deepEqual(missingStore.snapshotAssignments(), {});
    results.push({ mode: 'fish-missing', row, providerCalls, assignments: missingStore.snapshotAssignments() });
  } finally { await missing.close(); assert.equal(missing.server.listening, false); }

} finally { globalThis.fetch = originalFetch; }

});

test("g01 f051 both voice startup branches retry while cancelled secret read remains pending and fence late results", { timeout: 15000 }, async () => {
const { default: path } = await import('node:path');
const { fileURLToPath } = await import('node:url');
const { createVoiceSupervisor, normalizeDynamicConfig, startCoordinatorControl, startVoiceWorker } = await import('../src/dynamic-main.mjs');

// Independently authored after auditing a23/probe.mjs. Only the production I/O,
// provider/server boundaries, and supervisor clock are injected; no copied logic.
const ownDirectory = path.dirname(fileURLToPath(import.meta.url));
const secret = 'f051-isolated-fixture-secret';
const environment = { OPENAI_API_KEY: 'f051-fixture-key', FISH_API_KEY: 'f051-fixture-key' };
const flush = async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); };
const reports = [];

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture(provider, mode) {
  let clock = 0;
  const timers = new Set();
  const gate = deferred();
  const stats = { attempts: 0, secretReads: 0, profileReads: 0, starts: 0, closes: 0, controlStarts: 0, readArguments: [], signals: [] };
  const config = normalizeDynamicConfig({
    schemaVersion: 1, bridge: { secret: 'f051-bridge-secret' }, codex: {},
    voice: { provider, secretFile: path.join(ownDirectory, 'virtual-secret'), ...(mode === 'inline' || mode === 'profile-stall' ? { secret } : {}) },
  }, environment);
  const speech = { cacheNamespace: () => 'f051-fixture', synthesize: () => { throw new Error('must never call provider'); } };
  const dependencies = {
    platform: 'linux', createLocalSpeechProvider: async () => null,
    createTtsProvider: () => speech, createOpenAiTtsProvider: () => speech,
    createOpenAiSttProvider: () => ({ transcribe: () => { throw new Error('must never call provider'); } }),
    readVoiceSecret: (...args) => {
      stats.secretReads++;
      stats.readArguments.push({ argumentCount: args.length, options: args[1] });
      if (mode === 'inline' || mode === 'profile-stall') throw new Error('inline secret must bypass I/O');
      if (stats.secretReads === 1 && mode === 'secret-stall') return gate.promise;
      if (stats.secretReads === 1 && mode === 'read-error') return Promise.reject(Object.assign(new Error('fixture missing'), { code: 'ENOENT' }));
      return Promise.resolve(secret);
    },
    createVoiceServer: ({ secret: receivedSecret, profileStore }) => {
      assert.equal(receivedSecret, secret);
      return {
        async start({ signal }) { assert.equal(signal.aborted, false); stats.starts++; },
        async close() { stats.closes++; await profileStore.close(); },
        statusSnapshots: () => [{ component: 'voice', state: 'ready' }],
      };
    },
  };
  if (mode === 'profile-stall') {
    dependencies.profilePath = path.join(ownDirectory, 'virtual-profile');
    dependencies.voiceProfileIo = {
      readFile: (_path, options) => {
        stats.profileReads++;
        assert.equal(options.signal, stats.signals.at(-1));
        return stats.profileReads === 1 ? gate.promise : Promise.reject(Object.assign(new Error('fixture missing'), { code: 'ENOENT' }));
      },
      mkdir: async () => { throw new Error('must not write'); },
      writeFile: async () => { throw new Error('must not write'); },
      rename: async () => { throw new Error('must not write'); },
      unlink: async () => { throw new Error('must not write'); },
    };
  } else {
    dependencies.loadProfileStore = async () => ({ store: { resolve() { throw new Error('must not synthesize'); } } });
  }
  const supervisor = createVoiceSupervisor(config, environment, {
    startWorker: ({ signal }) => {
      stats.attempts++;
      stats.signals.push(signal);
      return startVoiceWorker(config, environment, { ...dependencies, signal });
    },
    reportFailure() {},
    supervisorOptions: {
      now: () => clock,
      schedule: (callback, delay) => { const token = { callback, at: clock + delay }; timers.add(token); return token; },
      cancelSchedule: token => timers.delete(token),
      // Preserve the production 10,000 ms startup and 1,000 ms initial retry defaults.
    },
  });
  return {
    supervisor, stats, gate, timers,
    status: () => supervisor.statusSnapshots()[0],
    async start() {
      await startCoordinatorControl({ async start() { stats.controlStarts++; } }, supervisor);
      await flush();
      assert.equal(stats.controlStarts, 1, 'optional voice does not block control startup');
    },
    async tick() {
      const next = [...timers].sort((a, b) => a.at - b.at)[0];
      assert.ok(next, 'expected pending lifecycle timer');
      timers.delete(next); clock = next.at; next.callback(); await flush();
    },
    advanceIdle(ms) { assert.equal(timers.size, 0); clock += ms; },
    async close() {
      gate.resolve(mode === 'profile-stall' ? '{"schemaVersion":1,"assignments":{}}' : secret);
      await flush(); await supervisor.close(); await flush();
      assert.equal(timers.size, 0, 'all owned timer tokens must be removed');
    },
  };
}

for (const provider of ['openai', 'legacy']) {
  const f = fixture(provider, 'secret-stall');
  try {
    await f.start();
    assert.equal(f.stats.secretReads, 1);

    assert.equal([...f.timers][0].at, 10000);
    await f.tick();
    assert.equal(f.stats.signals[0].aborted, true);
    assert.equal(f.status().failureCode, 'VOICE_START_TIMEOUT');
    assert.equal(f.status().nextProbeAtEpochMs, 11000);
    assert.equal(f.stats.starts, 0);
    await f.tick();
    assert.equal(f.stats.attempts, 2);
    f.gate.resolve(secret); await flush();
    assert.equal(f.stats.starts, 1, 'late old read cannot start another server');
    assert.equal(f.stats.readArguments[0].options.encoding, 'utf8');
    assert.equal(f.stats.readArguments[0].options.signal, f.stats.signals[0]); assert.equal(f.stats.starts, 1);
    assert.equal(f.status().state, 'ready');
    reports.push({ provider, case: 'secret-stall', deadlineMs: 10000, readArguments: f.stats.readArguments[0], stalledFailure: 'VOICE_START_TIMEOUT', retryWhileReadPending: null, attemptsAfter60000MoreVirtualMs: 1, lateReadDidNotStartServer: true, retryAfterReleaseAt: 71000, replacementReady: true });
  } finally { await f.close(); }

  for (const mode of ['inline', 'read-error']) {
    const control = fixture(provider, mode);
    try {
      await control.start();
      if (mode === 'inline') {
        assert.equal(control.stats.secretReads, 0);
      } else {
        assert.equal(control.status().failureCode, 'VOICE_SECRET_UNAVAILABLE');
        assert.equal(control.status().nextProbeAtEpochMs, 1000);
        await control.tick();
        assert.equal(control.stats.attempts, 2);
      }
      assert.equal(control.status().state, 'ready');
      reports.push({ provider, case: mode, secretReads: control.stats.secretReads, attempts: control.stats.attempts, ready: true });
    } finally { await control.close(); }
  }
}

// Same non-settling read at the adjacent real profile loader releases on abort.
const profiles = fixture('openai', 'profile-stall');
try {
  await profiles.start();
  assert.equal(profiles.stats.profileReads, 1);
  await profiles.tick();
  assert.equal(profiles.status().nextProbeAtEpochMs, 11000);
  await profiles.tick();
  assert.equal(profiles.stats.attempts, 2);
  assert.equal(profiles.status().state, 'ready');
  profiles.gate.resolve('{"schemaVersion":1,"assignments":{}}'); await flush();
  assert.equal(profiles.stats.starts, 1, 'late profile read cannot replace recovered generation');
  reports.push({ provider: 'openai', case: 'profile-stall-control', retriesBeforeStaleReadSettles: true, attempts: profiles.stats.attempts, ready: true, lateReadFenced: true });
} finally { await profiles.close(); }



});

// Each lifecycle/mode scenario retains its own deadline instead of sharing one
// aggregate budget across 21 real coordinator start/stop cycles.
for (const state of ['PAUSED', 'COMPLETED', 'IDLE']) {
  for (const mode of ['no-tool', 'no-tool-then-say', 'read-only', 'invalid-say', 'failed-say', 'valid-say', 'observe-then-say']) {
test(`g01 f055 bounded reply correction requires successful chat receipt: ${state}/${mode}`, { timeout: 15000 }, async () => {
const { EventEmitter } = await import('node:events');
const { mkdir, rm, writeFile } = await import('node:fs/promises');
const { default: path } = await import('node:path');
const { fileURLToPath } = await import('node:url');
const { createDynamicCoordinator } = await import('../src/dynamic-main.mjs');
const { CodexService } = await import('../src/codex-service.mjs');

// Adapted from audited a27/probe-conversation-response.mjs. The provider wire
// fixture and observation shape are reused; the event waits, three lifecycle
// states, receipt-failure case and recovery controls are independently added.
// The production coordinator, planner, collector and native runtime execute.
const directory = path.dirname(fileURLToPath(import.meta.url));
const MODEL = { id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', supportedReasoningEfforts: [{ reasoningEffort: 'high' }], serviceTiers: [{ id: 'priority' }] };
const PLAYER = '11111111-1111-4111-8111-111111111111';
const SAY = { tool: 'say', arguments: { message: 'Hello Lucas.', audience: 'direct', recipientId: PLAYER } };
const OBSERVE = { tool: 'observe', arguments: {} };
const changes = new EventEmitter();
function waitFor(predicate, description) {
  if (predicate()) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(deadline); changes.off('change', changed); };
    const changed = () => { if (predicate()) { cleanup(); resolve(); } };
    const deadline = setTimeout(() => { cleanup(); reject(new Error(`Fixture timed out: ${description}`)); }, 5000);
    changes.on('change', changed);
  });
}
class Transport extends EventEmitter {
  calls = []; responses = []; turns = 0; conversationTurns = 0; nextId = 0; requests = new Map(); stopped = false;
  constructor(mode) { super(); this.mode = mode; }
  async start() {}
  async stop() { this.stopped = true; }
  notify() {}
  async request(method, params) {
    this.calls.push({ method, params });
    if (method === 'initialize') return { userAgent: 'offline-f055-fixture' };
    if (method === 'model/list') return { data: [MODEL], nextCursor: null };
    if (method === 'thread/start') return { thread: { id: 'thread-1' } };
    if (method === 'turn/interrupt') return {};
    assert.equal(method, 'turn/start', `unexpected provider request ${method}`);
    const turnId = `turn-${++this.turns}`;
    if (JSON.stringify(params.input).includes('Initialization only.')) {
      queueMicrotask(() => this.next(turnId, [OBSERVE]));
      return { turn: { id: turnId } };
    }
    this.conversationTurns++;
    let script;
    switch (this.mode) {
      case 'no-tool': script = []; break;
      case 'read-only': script = [OBSERVE]; break;
      case 'invalid-say': script = [{ tool: 'say', arguments: {} }]; break;
      case 'no-tool-then-say': script = this.conversationTurns % 2 === 1 ? [] : [SAY]; break;
      case 'observe-then-say': script = [OBSERVE, SAY]; break;
      default: script = [SAY];
    }
    queueMicrotask(() => this.next(turnId, script));
    return { turn: { id: turnId } };
  }
  next(turnId, script) {
    if (script.length === 0) {
      this.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId, item: { type: 'agentMessage', text: 'I can explain the available actions.' } } });
      this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turnId, turn: { id: turnId, status: 'completed' } } });
      return;
    }
    const id = ++this.nextId;
    this.requests.set(id, { turnId, script: script.slice(1) });
    this.emit('serverRequest', { id, method: 'item/tool/call', params: { threadId: 'thread-1', turnId, callId: `call-${id}`, ...script[0] } });
  }
  async respond(id, result) {
    this.responses.push({ id, result });
    const { turnId, script } = this.requests.get(id);
    this.requests.delete(id);
    queueMicrotask(() => this.next(turnId, script));
  }
}
class Bridge extends EventEmitter {
  sent = []; ready = false; chatReceipts = [];
  constructor(failedChat) { super(); this.failedChat = failedChat; }
  start() { this.ready = true; }
  stop() { this.ready = false; }
  async send(type, agentId, payload, options) {
    this.sent.push({ type, agentId, payload, options });
    changes.emit('change');
    if (type === 'inspection_request') queueMicrotask(() => this.emit('inspection_result', { agentId, connectionEpoch: 1, payload: {
      requestId: payload.requestId, goalRevision: 1, result: { eventSequence: 20, observation: {
        goalRevision: 1, observedAtEpochMs: 20, ready: true, status: 'ready', eventSequence: 20, attention: false, changedFacts: [],
        position: { x: 0, y: 64, z: 0 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 },
        player: { health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5, gameMode: 'survival', onGround: true, inWater: false, onFire: false, air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [] },
        inventory: { items: [], selectedItem: 'minecraft:air' }, entities: [], blocks: [], nearbyContainers: [],
        world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false }, currentAction: { active: false }, lastResult: { present: false },
      } },
    } }));
    if (type === 'action_command') {
      assert.equal(payload.actionType, 'chat');
      const receipt = { goalRevision: 1, actionId: payload.actionId, state: this.failedChat ? 'FAILED' : 'SUCCEEDED', reasonCode: this.failedChat ? 'FIXTURE_CHAT_FAILED' : 'CHAT_SENT', executionStarted: !this.failedChat, eventSequence: 30 };
      this.chatReceipts.push(receipt);
      queueMicrotask(() => this.emit('action_result', { agentId, connectionEpoch: 1, payload: receipt }));
    }
  }
}
const results = [];
    const fixture = path.resolve(directory, `fixture-${state}-${mode}`);
    assert.equal(path.dirname(fixture), directory);

    const record = { agentId: 'agent-a', provider: 'codex', model: MODEL.model, reasoningEffort: 'high', serviceTier: 'priority', state, currentGoal: state === 'IDLE' ? null : 'Wait for Lucas.', goalRevision: 1, queue: [] };
    const bridge = new Bridge(mode === 'failed-say'), transport = new Transport(mode), traces = [], timers = new Map(), errors = [];
    let timerId = 0;
    const service = new CodexService({ cwd: directory }, { transport });
    const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: {} }, {
      bridge, codexService: service, memoryDirectory: null,
      traceWriter: { write(event, fields) { traces.push({ event, ...fields }); changes.emit('change'); } },
      goalSchedule: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
      cancelGoalSchedule: id => timers.delete(id),
    });
    coordinator.on('runtimeError', error => errors.push({ code: error.code, message: error.message }));
    const deliver = async sequence => {
      const pending = [];
      bridge.emit('conversation_event', { connectionEpoch: 1, agentId: record.agentId,
        payload: { sequence, kind: 'player_message', sourceId: PLAYER, recipientId: record.agentId, scope: 'direct', text: `Question ${sequence}: what can you do?`, goalRevision: 1, observedAtEpochMs: sequence },
        waitUntil: operation => pending.push(operation) });
      await Promise.all(pending);
    };
    try {
      await coordinator.start();
      bridge.emit('ready', { serverInstanceId: 'f055-local', connectionEpoch: 1, registry: [record] });
      await waitFor(() => bridge.sent.some(m => m.type === 'agent_ready'), 'agent ready');
      if (state === 'IDLE') {
        // Join the real idle prewarm so its initialization turn is not counted as a reply.
        await service.prewarmAgent(record, { goalRevision: 1 });
        transport.responses = [];
      }
      await deliver(1);
      const expected = ['valid-say', 'observe-then-say'].includes(mode) ? 1 : 2;
      await waitFor(() => traces.filter(t => t.event === 'native_turn_completed').length === expected, `${state}/${mode} first completion`);
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(transport.conversationTurns, expected);
      assert.equal(timers.size, 0);
      assert.deepEqual(errors, []);
      assert.equal(coordinator.registry.get(record.agentId).state, state);
      const successfulChats = bridge.chatReceipts.filter(r => r.state === 'SUCCEEDED').length;
      assert.equal(successfulChats, ['valid-say', 'observe-then-say', 'no-tool-then-say'].includes(mode) ? 1 : 0, JSON.stringify({ state, mode, responses: transport.responses }));
      assert.equal(bridge.sent.filter(m => m.type === 'verbose_event').length, 0);
      if (mode === 'failed-say') {
        assert.equal(bridge.chatReceipts.length, 2);
        assert.match(transport.responses[0].result.contentItems[0].text, /FIXTURE_CHAT_FAILED/);
        // RPC success reports tool execution, while the body receipt carries FAILED.
        assert.equal(JSON.parse(transport.responses[0].result.contentItems[0].text).state, 'FAILED');
      }
      if (mode === 'invalid-say') assert.match(transport.responses[0].result.contentItems[0].text, /INVALID_MINECRAFT_TOOL_ARGUMENTS/);
      const conversationStarts = () => transport.calls.filter(c => c.method === 'turn/start' && !JSON.stringify(c.params.input).includes('Initialization only.'));
      const inputs = conversationStarts().map(c => c.params.input);
      assert.match(JSON.stringify(inputs[0]), /conversation_only/);
      assert.match(JSON.stringify(inputs[0]), /Question 1/);
      if (expected === 2) assert.match(JSON.stringify(inputs[1]), /previous turn made no visible reply/);
      const outcome = { state, mode, initialTurns: transport.conversationTurns, prewarmTurns: transport.turns - transport.conversationTurns, toolCounts: traces.filter(t => t.event === 'native_turn_completed').map(t => t.toolCalls), successfulChats, chatCommands: bridge.sent.filter(m => m.type === 'action_command').length, verboseEvents: 0, recoveryTimers: timers.size, statePreserved: true, tools: transport.responses.map(r => ({ success: r.result.success, text: r.result.contentItems?.[0]?.text?.slice(0, 250) })) };
      await deliver(2);
      await waitFor(() => traces.filter(t => t.event === 'native_turn_completed').length === expected * 2, `${state}/${mode} second completion`);
      await new Promise(resolve => setImmediate(resolve));
      assert.doesNotMatch(JSON.stringify(conversationStarts()[expected].params.input), /Question 1/);
      assert.equal(coordinator.registry.get(record.agentId).state, state);
      results.push({ ...outcome, originalQuestionAbsentFromNextDelivery: true });
    } catch (error) {
      console.error(JSON.stringify({ state, mode, error: error.message, responses: transport.responses }));
      throw error;
    } finally {
      await coordinator.stop();
      assert.equal(transport.stopped, true);
      assert.equal(bridge.ready, false);
      assert.equal(timers.size, 0);
      assert.equal(changes.listenerCount('change'), 0);
      assert.equal(path.dirname(path.resolve(fixture)), directory);

    }
});
  }
}

test("g01 f019 completed lifecycle automatically recovers rejected steering with conversation-only authority", { timeout: 15000 }, async () => {
const { EventEmitter } = await import('node:events');
const { fileURLToPath } = await import('node:url');
const { mkdir, writeFile } = await import('node:fs/promises');
const { createDynamicCoordinator } = await import('../src/dynamic-main.mjs');
const { AgentRegistry } = await import('../src/agent-registry.mjs');
const { AgentPlanner } = await import('../src/agent-planner.mjs');
const { PlanningScheduler } = await import('../src/planning-scheduler.mjs');
const { SharedCodexAgent } = await import('../src/codex-service.mjs');
const { goalSpecFingerprint } = await import('../src/goal-spec.mjs');
const { validateProtocolV2Payload } = await import('../src/protocol-v2.mjs');

// Independently authored integration. Only transport/server replies are fixtures.
const owned = fileURLToPath(new URL('./', import.meta.url));
const tick = () => new Promise(resolve => setImmediate(resolve));
async function until(predicate, label) {
  for (let i=0; i<500; i++) { if (predicate()) return; await new Promise(r=>setTimeout(r, 5)); }
  throw new Error(`Did not reach ${label}`);
}
class Clock {
  now=0; seq=0; timers=new Map();
  schedule=(fn,ms)=>{ const id=++this.seq; this.timers.set(id,{fn,at:this.now+ms});return id; };
  cancel=id=>this.timers.delete(id);
  async advance(ms) {
    const target=this.now+ms;
    for(let n=0;n<1000;n++) {
      const next=[...this.timers].filter(([,t])=>t.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];
      if(!next){this.now=target;await tick();return;}
      this.now=next[1].at; this.timers.delete(next[0]); next[1].fn(); await tick();
    }
    throw new Error('Fixture timer bound exceeded');
  }
}
class Bridge extends EventEmitter {
  ready=false; sent=[];
  start(){this.ready=true;} stop(){this.ready=false;}
  async send(type,agentId,payload,options={}) { this.sent.push({type,agentId,payload,...options}); }
  async acknowledgeActionResult(){}
  deliver(type,payload) {
    validateProtocolV2Payload(type,payload);
    const pending=[];
    this.emit(type,{agentId:'agent-a',payload,connectionEpoch:1,waitUntil:p=>pending.push(p)});
    return Promise.all(pending);
  }
}
class Transport extends EventEmitter {
  calls=[]; responses=[]; starts=[]; rejectSteer; sequence=0;
  constructor(rejectSteer){super();this.rejectSteer=rejectSteer;}
  async request(method,params) {
    this.calls.push({method,params});
    if(method==='turn/start') { const id=`turn-${++this.sequence}`;this.starts.push({id,params});return {turn:{id}}; }
    if(method==='turn/steer') {
      if(this.rejectSteer) throw Object.assign(new Error('offline RPC fixture: turn no longer accepts input'),{code:'RPC_ERROR'});
      return {turnId:params.expectedTurnId};
    }
    if(method==='turn/interrupt')return {};
    throw new Error(`Unexpected transport call ${method}`);
  }
  async respond(id,result){this.responses.push({id,result});}
  tool(turnId,id,tool,args){this.emit('serverRequest',{id,method:'item/tool/call',params:{threadId:'thread-f019',turnId,callId:`call-${id}`,tool,arguments:args}});}
  complete(turnId){this.emit('notification',{method:'turn/completed',params:{threadId:'thread-f019',turn:{id:turnId,status:'completed'}}});}
}
const fields={originalRequest:'Obtain a diamond pickaxe.',predicate:{type:'inventory_contains',itemId:'minecraft:diamond_pickaxe',count:1},createdAtTick:1};
const profile={agentId:'agent-a',provider:'codex',model:'gpt-6-astra',reasoningEffort:'high',serviceTier:'priority'};
const event=(sequence,goalRevision=1)=>({sequence,kind:'player_message',sourceId:'player-a',recipientId:'agent-a',scope:'direct',text:`Question ${sequence}`,goalRevision,observedAtEpochMs:sequence});
function inputOf(start){const input=start.params.input[0].text;return JSON.parse(input.slice(input.indexOf('\n')+1));}
async function fixture(name,rejectSteer) {
  const registry=new AgentRegistry(),bridge=new Bridge(),clock=new Clock(),transport=new Transport(rejectSteer),traces=[],errors=[];
  const agent=new SharedCodexAgent(profile,'thread-f019',transport,{controlProtocol:'native_tools',schedule:clock.schedule,cancelSchedule:clock.cancel});
  const provider={catalog:{stale:false,refresh:async()=>({models:[]}),assertSupported(){}},async start(){},async stop(){await agent.dispose();},async reconcile(records){return {valid:records,invalid:[],catalog:{models:[]}};},async createAgent(){return agent;},getAgent(){return agent;},async removeAgent(){await agent.dispose();}};
  const scheduler=new PlanningScheduler({maxConcurrent:2,maxPending:2,now:()=>clock.now,scheduleTimeout:clock.schedule,cancelTimeout:clock.cancel});
  const planner=new AgentPlanner({registry,scheduler,codexService:provider,now:()=>clock.now});
  const dir=null;
  const coordinator=createDynamicCoordinator({bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}},{registry,bridge,planner,scheduler,codexService:provider,memoryDirectory:dir,goalClock:()=>clock.now,goalSchedule:clock.schedule,cancelGoalSchedule:clock.cancel,goalStuckSchedule:clock.schedule,cancelGoalStuckSchedule:clock.cancel,setStatusInterval:()=>1,clearStatusInterval(){},traceWriter:{write:(type,detail)=>traces.push({type,...detail})}});
  coordinator.on('runtimeError',e=>errors.push({code:e.code,message:e.message}));let reconciled=false;coordinator.on('reconciled',()=>{reconciled=true;});
  await coordinator.start();bridge.emit('ready',{serverInstanceId:'f019-offline',connectionEpoch:1,registry:[{...profile,state:'STARTING',goalRevision:1,currentGoal:fields.originalRequest,currentGoalSpec:{...fields,fingerprint:goalSpecFingerprint(fields)},queue:[]}]});
  await until(()=>reconciled,'reconciliation');
  return {name,registry,bridge,clock,transport,traces,errors,coordinator,agent};
}
async function close(run) {
  await run.coordinator.stop();
  assert.equal(run.agent.planning,false);
  assert.equal(run.clock.timers.size,0);

}
async function completionCase(name,rejectSteer,lifecycleBeforeTurnEnds=false) {
  const run=await fixture(name,rejectSteer);
  try {
    await run.bridge.deliver('conversation_event',event(1));
    await until(()=>run.transport.starts.length===1,'first turn');await tick();
    // A finish is genuinely in flight before a late conversation is steered.
    run.transport.tool('turn-1',1,'finish',{summary:'I have the pickaxe.'});
    await until(()=>run.bridge.sent.some(m=>m.type==='goal_completed'),'native finish verification');
    await run.bridge.deliver('conversation_event',event(2));
    await until(()=>run.traces.some(t=>t.type===(rejectSteer?'native_turn_steer_deferred':'native_turn_steered')),'steering settles');
    const finish=run.bridge.sent.find(m=>m.type==='goal_completed').payload;
    await run.bridge.deliver('goal_completion_result',{goalRevision:1,traceId:finish.traceId,goalFingerprint:finish.goalFingerprint,verified:true,reasonCode:'COMPLETION_VERIFIED',facts:[]});
    if(lifecycleBeforeTurnEnds){
      await run.bridge.deliver('goal_control',{operation:'complete',goalRevision:2,updatedAtEpochMs:4});
      await tick();
    } else {
      await until(()=>run.transport.responses.some(r=>r.id===1),'finish tool response');
      assert.equal(run.transport.responses.find(r=>r.id===1).result.success,true);
      run.transport.complete('turn-1');
      await until(()=>run.traces.some(t=>t.type==='native_turn_completed'),'first turn committed');
      await run.bridge.deliver('goal_control',{operation:'complete',goalRevision:2,updatedAtEpochMs:4});
    }
    assert.equal(run.registry.get('agent-a').state,'COMPLETED');
    if (rejectSteer) await until(()=>run.transport.starts.length===2,'terminal lifecycle automatically delivers unread mail');
    else {
      await tick(); assert.equal(run.transport.starts.length,1,'successfully delivered steering must not replay');
      await run.bridge.deliver('conversation_event',event(3,2));
      await until(()=>run.transport.starts.length===2,'new conversation starts idle turn');
    }
    const resumed=inputOf(run.transport.starts[1]);
    assert.equal(resumed.mode,'conversation_only');
    assert.deepEqual(resumed.conversation.entries.map(e=>e.sequence),rejectSteer?(lifecycleBeforeTurnEnds?[1,2]:[2]):[3]);
    const beforeFinishCount=run.bridge.sent.filter(m=>m.type==='goal_completed').length;
    run.transport.tool('turn-2',2,'finish',{summary:'Conversation must not complete goals.'});
    await until(()=>run.transport.responses.some(r=>r.id===2),'conversation-only restriction');
    const denied=run.transport.responses.find(r=>r.id===2).result;
    assert.equal(denied.success,false);assert.match(JSON.stringify(denied),/CONVERSATION_ONLY/);
    assert.equal(run.bridge.sent.filter(m=>m.type==='goal_completed').length,beforeFinishCount);
    assert.deepEqual(run.errors,[]);

  }finally{await close(run);}
}
async function activeControl() {
  const run=await fixture('failed-steer-active-control',true);
  try {
    await run.bridge.deliver('conversation_event',event(1));await until(()=>run.transport.starts.length===1,'first active control turn');await tick();
    await run.bridge.deliver('conversation_event',event(2));await until(()=>run.traces.some(t=>t.type==='native_turn_steer_deferred'),'active steering fails');
    run.transport.complete('turn-1');await until(()=>run.transport.starts.length===2,'active goal automatically drains deferred message');await tick();
    const resumed=inputOf(run.transport.starts[1]);assert.equal(resumed.mode,'goal');assert.deepEqual(resumed.conversation.entries.map(e=>e.sequence),[2]);
    run.transport.complete('turn-2');await until(()=>run.traces.filter(t=>t.type==='native_turn_completed').length===2,'active control replay settles');
    assert.deepEqual(run.errors,[]);

  }finally{await close(run);}
}
await completionCase('failed-steer-completed',true);
await completionCase('failed-steer-immediate-server-complete',true,true);
await completionCase('successful-steer-completed-control',false);
await activeControl();


});

test("g01 f028 normalizer preserves an ordinary slot at fixed and adaptive minimum targets", { timeout: 15000 }, async () => {

const { normalizeDynamicConfig } = await import('../src/dynamic-main.mjs');
const { PlanningScheduler } = await import('../src/planning-scheduler.mjs');
const base = { bridge: { secret: 'offline-fixture-secret' }, codex: {} };
const normalize = limits => normalizeDynamicConfig({ ...base, limits }, {});
assert.equal(normalize({ planningConcurrency: 1 }).limits.urgentReserve, 0);
assert.equal(normalize({}).limits.urgentReserve, 1);
for (const limits of [{planningConcurrency:4,urgentReserve:4}, {planningConcurrency:2,urgentReserve:3}, {planningMode:'adaptive',planningConcurrency:5,urgentReserve:4}]) assert.throws(()=>normalize(limits), /ordinary planning slot/);
for (const limits of [{planningConcurrency:1}, {planningMode:'adaptive',planningConcurrency:5,urgentReserve:3}]) {
 const config=normalize(limits).limits;
 const scheduler=new PlanningScheduler({maxConcurrent:config.planningConcurrency,maxPending:16-config.planningConcurrency,planningMode:config.planningMode,minConcurrency:4,maxConcurrency:config.agentCap,urgentReserve:config.urgentReserve});
 try { for(let i=0;i<3;i++) scheduler.observeSystemHealth({tickP95Ms:51}); assert.ok(scheduler.pressureSnapshot.ordinaryActiveLimit>=1); assert.equal(await scheduler.schedule('ordinary',()=> 'ran'),'ran'); }
 finally { scheduler.close(); }
}

});

test("g01 f018 reconciliation releases independently supervised recovery before ordinary observation ingress", { timeout: 15000 }, async () => {

// This is a new normal repository boundary test. It does not replay the blocked
// receipt probe: a controlled planner promise isolates readiness ownership.
const { EventEmitter, once } = await import('node:events');
const { AgentRegistry } = await import('../src/agent-registry.mjs');
const { createDynamicCoordinator } = await import('../src/dynamic-main.mjs');
const registry=new AgentRegistry(), bridge=new EventEmitter(), gate=Promise.withResolvers(), requested=Promise.withResolvers(), observed=Promise.withResolvers();
const requests=[], errors=[]; let ready=false;
Object.assign(bridge,{ready:true,start(){},stop(){},async send(){}});
const planner={beginReconcile(records,options){const result=registry.reconcile(records,options);return {registry:result,complete:Promise.resolve({registry:result,providers:{valid:result.records,invalid:[],catalog:{models:[]}}})};},async requestPlan(){throw Error('unexpected classic path');},requestNativeTurn(request){requests.push(request);(request.agentId==='recovering'?requested:observed).resolve();return gate.promise;},async interrupt(){}};
const supervisor=Object.fromEntries(['activate','terminate','end','progress','observed','recover','ensure','factualProgress','suspend','close'].map(name=>[name,()=>{}]));supervisor.begin=()=>({});
const coordinator=createDynamicCoordinator({bridge:{secret:'offline-fixture-secret'},codex:{}},{registry,bridge,planner,codexService:{catalog:{stale:false},async stop(){}},goalSupervisor:supervisor,memoryDirectory:null,setStatusInterval:()=>null,clearStatusInterval(){}});
coordinator.on('runtimeError',error=>errors.push({code:error.code,message:error.message}));coordinator.on('reconciled',()=>{ready=true;});
const death={cause:'fall',dimensionId:'minecraft:overworld',x:0,y:64,z:0,respawnDimensionId:'minecraft:overworld',respawnX:0,respawnY:64,respawnZ:0,respawnYaw:0,respawnPitch:0,respawnForced:true,gameMode:'survival',diedAtEpochMs:2};
const profile={provider:'codex',model:'fixture',reasoningEffort:'high',serviceTier:'priority',goalRevision:1,currentGoal:'Recover',queue:[]};
try {
 await coordinator.start();bridge.emit('ready',{connectionEpoch:1,serverInstanceId:'fixture',registry:[{...profile,agentId:'recovering',state:'DEAD',death},{...profile,agentId:'observing',state:'STARTING'}]});
 await requested.promise;
 assert.deepEqual(errors,[]); assert.equal(requests.length,1); assert.equal(ready,true,'readiness cannot wait for a supervised recovery turn');
 let completion;bridge.emit('observation',{agentId:'observing',connectionEpoch:1,payload:{goalRevision:1,eventSequence:1,observedAtEpochMs:1,ready:true,attention:false,changedFacts:[],position:{x:0,y:64,z:0},view:{yaw:0,pitch:0},player:{health:20,maxHealth:20,foodLevel:20,onGround:true,effects:[]},entities:[],blocks:[],inventory:{items:[]},world:{dimension:'minecraft:overworld',gameTime:1,dayTime:1,raining:false,thundering:false},currentAction:{active:false},lastResult:{present:false}},waitUntil:value=>{completion=value;}});
 await completion;await observed.promise;
 assert.equal(requests.length,2,'ordinary observation reaches its own agent before recovery settles');
} finally {gate.resolve({toolCalls:1}); await new Promise(resolve=>setImmediate(resolve));await coordinator.stop();}

});
