import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL } from 'node:url';

const [baselineRoot, optimizedRoot, repetitionsArg = '120'] = process.argv.slice(2);
const repetitions = Number(repetitionsArg);
if (!baselineRoot || !optimizedRoot || !Number.isInteger(repetitions) || repetitions < 1 || repetitions > 500) {
  throw new Error('Usage: node native-observation-handoff.mjs <baseline-root> <optimized-root> [paired-repetitions]');
}

const actionSource = 'program.onUnhandledAttention("continue_and_notify"); '
  + Array.from({ length: 8 }, (_, index) => `await player.wait(${index + 1});`).join('');
const record = {
  agentId: 'speed-agent', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'low', serviceTier: 'priority',
  goalRevision: 1, currentGoal: 'Execute the chosen controls',
};
const observation = {
  world: { worldId: 'speed-world', dimensionId: 'minecraft:overworld' },
  player: { x: 0, y: 64, z: 0, health: 20, food: 20, air: 300 },
  blocks: [], entities: [], items: [], inventory: { items: [], tagCounts: {} },
};
const url = (root, relative) => pathToFileURL(path.join(root, 'coordinator', 'src', relative)).href;

async function createArm(root, name) {
  const [{ NativeToolRuntime }, { ModelNotebook }] = await Promise.all([
    import(url(root, 'native-tool-runtime.mjs')),
    import(url(root, 'model-notebook.mjs')),
  ]);
  const backingNotebook = new ModelNotebook({ directory: path.join(os.tmpdir(), `arena-speed-${name}-${randomUUID()}`) });
  let unresolvedQueries = 0;
  const notebook = new Proxy(backingNotebook, {
    get(target, key) {
      if (key === 'listUnresolved') return (...args) => { unresolvedQueries++; return target.listUnresolved(...args); };
      const value = target[key];
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
  let eventSequence = 1;
  let requestedObservations = 0;
  let executionSettingsReads = 0;
  let dispatches = [];
  let completions = [];
  const actions = [];
  let runtime;
  runtime = new NativeToolRuntime({
    sessionId: `speed-${name}`,
    notebook,
    executionSettings: async () => { executionSettingsReads++; return { effective: { model: 'gpt-5.6-sol' } }; },
    bridge: { send: async (type, _agentId, payload) => {
      if (type !== 'action_command') return;
      actions.push({ actionType: payload.actionType, arguments: payload.arguments });
      queueMicrotask(() => runtime.onActionResult(record, {
        goalRevision: 1, actionId: payload.actionId, state: 'SUCCEEDED',
        reasonCode: 'WAIT_COMPLETED', executionStarted: true,
      }));
    } },
    requestObservation: async () => {
      requestedObservations++;
      return { observation, eventSequence: ++eventSequence };
    },
    trace: (event) => {
      if (event === 'native_tool_dispatch_started') dispatches.push(performance.now());
      if (event === 'native_tool_action_completed') completions.push(performance.now());
    },
  });
  runtime.updateObservation(record, observation, { eventSequence });
  let callNumber = 0;
  return {
    name,
    counters: () => ({ requestedObservations, unresolvedQueries, executionSettingsReads, actions: actions.length }),
    actionHash: (startIndex) => createHash('sha256').update(JSON.stringify(actions.slice(startIndex))).digest('hex'),
    async run() {
      dispatches = [];
      completions = [];
      const start = performance.now();
      const result = await runtime.execute({
        agentId: record.agentId, goalRevision: record.goalRevision,
        turnId: `turn-${++callNumber}`, callId: `call-${callNumber}`,
        tool: { kind: 'run_program', source: actionSource, maxActions: 8, timeoutMs: 30_000 },
      }, record);
      const durationMs = performance.now() - start;
      assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
      assert.equal(result.actions, 8);
      assert.equal(result.receipts.length, 8);
      assert.ok(result.receipts.every(receipt => receipt.state === 'SUCCEEDED'));
      assert.equal(dispatches.length, 8);
      assert.equal(completions.length, 8);
      return { durationMs, gapsMs: Array.from({ length: 7 }, (_, i) => dispatches[i + 1] - completions[i]) };
    },
    dispose: () => runtime.disposeAll(),
  };
}

function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = (fraction) => sorted[Math.ceil(sorted.length * fraction) - 1];
  return { n: values.length, p50Ms: percentile(.5), p95Ms: percentile(.95),
    meanMs: sorted.reduce((sum, value) => sum + value, 0) / sorted.length };
}

const arms = {
  baseline: await createArm(baselineRoot, 'baseline'),
  optimized: await createArm(optimizedRoot, 'optimized'),
};
// Warm both module graphs and storage paths before collecting paired samples.
for (let i = 0; i < 10; i++) {
  for (const name of i % 2 === 0 ? ['baseline', 'optimized'] : ['optimized', 'baseline']) await arms[name].run();
}
const starts = Object.fromEntries(Object.entries(arms).map(([name, arm]) => [name, arm.counters()]));
const samples = { baseline: [], optimized: [] };
for (let i = 0; i < repetitions; i++) {
  for (const name of i % 2 === 0 ? ['baseline', 'optimized'] : ['optimized', 'baseline']) {
    samples[name].push(await arms[name].run());
  }
}
const results = {};
for (const [name, arm] of Object.entries(arms)) {
  const rows = samples[name];
  const start = starts[name];
  const end = arm.counters();
  assert.equal(end.actions - start.actions, repetitions * 8);
  assert.equal(end.requestedObservations - start.requestedObservations, repetitions * 8);
  results[name] = {
    program: summarize(rows.map(row => row.durationMs)),
    handoff: summarize(rows.flatMap(row => row.gapsMs)),
    requestedObservations: end.requestedObservations - start.requestedObservations,
    unresolvedQueries: end.unresolvedQueries - start.unresolvedQueries,
    executionSettingsReads: end.executionSettingsReads - start.executionSettingsReads,
    actionShapeHash: arm.actionHash(start.actions),
  };
  await arm.dispose();
}
assert.equal(results.baseline.actionShapeHash, results.optimized.actionShapeHash, 'authored controls must be identical');
const pairWins = {
  program: samples.baseline.filter((row, index) => samples.optimized[index].durationMs < row.durationMs).length,
  handoff: samples.baseline.filter((row, index) => summarize(samples.optimized[index].gapsMs).p50Ms < summarize(row.gapsMs).p50Ms).length,
};
process.stdout.write(JSON.stringify({
  benchmark: 'native-observation-handoff', repetitions, actionsPerArm: repetitions * 8,
  controller: 'immediate fake bridge; real NativeToolRuntime, ArenaScript, and disk-backed ModelNotebook',
  result: 'PASSED', results, pairWins,
}) + '\n');
