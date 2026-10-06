import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexStdioTransport } from '../src/codex-app-server.mjs';
import { SharedCodexAgent } from '../src/codex-service.mjs';
import { CodexService } from '../src/codex-service.mjs';
import { ProviderService } from '../src/provider-service.mjs';
import { ModelCatalogCache } from '../src/model-catalog-cache.mjs';
import { MinecraftAgentWorkspace } from '../src/minecraft-agent-workspace.mjs';
import path from 'node:path';

// Usage ordering cases adapted from the verified f017 fixture. Only the process
// boundary is fake: production decoding, notification routing and native act run.
const profile = { agentId: 'f017-fixture', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const threadId = 'f017-thread';
const tick = () => new Promise(setImmediate);

async function fixture() {
  const child = new EventEmitter();
  Object.assign(child, { stdout: new EventEmitter(), stderr: new EventEmitter(), stdin: new EventEmitter(), exitCode: null, signalCode: null });
  const requests = [];
  const emit = (...messages) => child.stdout.emit('data', messages.map(message => JSON.stringify(message)).join('\n') + '\n');
  child.stdin.write = line => {
    const request = JSON.parse(String(line));
    requests.push(request);
    if (request.method === 'turn/interrupt') emit({ id: request.id, result: {} });
    return true;
  };
  child.kill = () => { child.exitCode = 0; child.emit('exit', 0, null); return true; };
  let spawnCalls = 0;
  const transport = new CodexStdioTransport({ ...profile, cwd: process.cwd(), environment: {} }, {
    spawn: () => { spawnCalls++; return child; }, stopTimeoutMs: 1,
  });
  const starting = transport.start();
  child.emit('spawn');
  await starting;
  const agent = new SharedCodexAgent(profile, threadId, transport, { controlProtocol: 'native_tools', planningTimeoutMs: 1000 });
  const start = async turnId => {
    const pending = agent.act(`fixture ${turnId}`, { goalRevision: 0, executeTool: async () => { throw new Error('No tool call expected'); } });
    const request = requests.findLast(item => item.method === 'turn/start');
    assert.ok(request);
    emit({ id: request.id, result: { turn: { id: turnId } } });
    await tick();
    return { pending };
  };
  const close = async () => {
    await agent.dispose();
    await transport.stop();
    assert.equal(spawnCalls, 1);
    assert.equal(child.exitCode, 0);
    assert.equal(transport.listenerCount('notification'), 0);
    assert.equal(transport.listenerCount('serverRequest'), 0);
    assert.equal(requests.filter(item => item.method === 'turn/interrupt').length, 0);
  };
  return { emit, start, close };
}

const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const catalogModel = id => ({ id, model: id, displayName: id, supportedReasoningEfforts: ['high'], serviceTiers: ['priority'] });
const inertService = () => ({ start: async () => {}, stop: async () => {}, catalog: { stale: false, refresh: async () => ({ models: [], source: 'live' }) } });

for (const warm of [false, true]) {
  test(`equivalent ${warm ? 'warm' : 'cold'} catalog readers both consume one accepted refresh`, async () => {
    const gate = deferred();
    let loads = 0;
    const catalog = new ModelCatalogCache(async () => {
      loads++;
      if (!warm || loads > 1) await gate.promise;
      return [catalogModel(warm && loads === 1 ? 'old' : 'new')];
    });
    const router = new ProviderService({ codex: { ...inertService(), catalog }, gemini: inertService(), claude: inertService() });
    try {
      if (warm) await router.catalog.refresh({ providers: ['codex'] });
      const one = router.catalog.refresh({ providers: ['codex'], force: warm });
      await tick();
      const two = router.catalog.refresh({ providers: ['codex'], force: warm });
      await tick();
      gate.resolve();
      const results = await Promise.all([one, two]);
      assert.deepEqual(results.map(result => result.models.map(model => model.id)), [['new'], ['new']]);
      assert.deepEqual(results.map(result => result.source), ['live', 'live']);
      assert.equal(loads, warm ? 2 : 1);
    } finally { gate.resolve(); await router.stop(); }
  });
}

test('stopping still fences both subscribers of an obsolete catalog load', async () => {
  const gate = deferred();
  const catalog = new ModelCatalogCache(async () => { await gate.promise; return [catalogModel('obsolete')]; });
  const router = new ProviderService({ codex: { ...inertService(), catalog }, gemini: inertService(), claude: inertService() });
  const one = router.catalog.refresh({ providers: ['codex'] });
  const two = router.catalog.refresh({ providers: ['codex'] });
  await tick();
  const stopping = router.stop();
  gate.resolve();
  assert.deepEqual((await Promise.all([one, two])).map(result => result.models), [[], []]);
  await stopping;
  assert.equal(router.catalog.lastValid.size, 0);
});

test('different catalog reads still fence the superseded physical outcome', async () => {
  const old = deferred();
  const fresh = deferred();
  let loads = 0;
  const catalog = { stale: false, refresh: async () => {
    const own = ++loads;
    await (own === 1 ? old.promise : fresh.promise);
    return { models: [{ ...catalogModel(own === 1 ? 'old' : 'new'), reasoningEfforts: ['high'] }], source: 'live' };
  } };
  const router = new ProviderService({ codex: { ...inertService(), catalog }, gemini: inertService(), claude: inertService() });
  try {
    const one = router.catalog.refresh({ providers: ['codex'] });
    await tick();
    const two = router.catalog.refresh({ providers: ['codex'], force: true });
    await tick();
    fresh.resolve();
    assert.deepEqual((await two).models.map(model => model.id), ['new']);
    old.resolve();
    assert.deepEqual((await one).models.map(model => model.id), ['new']);
    assert.deepEqual(router.catalog.lastValid.get('codex').models.map(model => model.id), ['new']);
    assert.equal(loads, 2);
  } finally { old.resolve(); fresh.resolve(); await router.stop(); }
});

test('obsolete child cleanup remains diagnostic without invalidating successor turns', { timeout: 3000 }, async () => {
  const children = [];
  const transport = new CodexStdioTransport({ ...profile, cwd: process.cwd(), environment: {} }, {
    stopTimeoutMs: 10,
    spawn() {
      const child = new EventEmitter();
      const index = children.length;
      Object.assign(child, { stdin: new EventEmitter(), stdout: new EventEmitter(), stderr: new EventEmitter(), exitCode: null, signalCode: null, turns: [] });
      let threads = 0;
      child.stdin.write = line => {
        const request = JSON.parse(line);
        if (!Object.hasOwn(request, 'id')) return true;
        let result = {};
        if (request.method === 'model/list') result = { data: [catalogModel(profile.model)], nextCursor: null };
        if (request.method === 'thread/start') result = { thread: { id: `child-${index}-thread-${++threads}` } };
        if (request.method === 'turn/start') {
          const id = `turn-${child.turns.length}`;
          child.turns.push({ threadId: request.params.threadId, turn: { id, status: 'completed' } });
          result = { turn: { id } };
        }
        queueMicrotask(() => child.stdout.emit('data', JSON.stringify({ id: request.id, result }) + '\n'));
        return true;
      };
      child.kill = signal => {
        if (index === 0) {
          if (signal === 'SIGKILL') throw new Error('fixture obsolete child cleanup failure');
        } else { child.exitCode = 0; child.emit('exit', 0, null); }
        return true;
      };
      children.push(child);
      queueMicrotask(() => child.emit('spawn'));
      return child;
    },
  });
  const service = new CodexService({ cwd: process.cwd() }, { transport });
  const diagnostics = [];
  transport.on('diagnostic', message => diagnostics.push(message));
  // Observe either old or corrected event so the pre-fix failure is an assertion,
  // not a hanging wait. All children are PID-free in-memory objects.
  const cleanup = new Promise(resolve => {
    transport.once('diagnostic', resolve);
    transport.once('protocolError', resolve);
  });
  try {
    const original = await service.createAgent(profile);
    children[0].stdin.emit('error', new Error('fixture I/O failure'));
    const replacement = await service.replaceAgent(profile, { expectedSessionGeneration: original.sessionGeneration });
    const shared = await service.createAgent({ ...profile, agentId: 'second' });
    const turns = [replacement, shared].map(agent => agent.act('fixture', { goalRevision: 0, executeTool: async () => assert.fail('no tools expected') }).then(result => result.status, error => error.code));
    await tick();
    await cleanup;
    assert.equal(service.started, true);
    assert.equal(service.getAgent(profile.agentId), replacement);
    assert.equal(replacement.sessionGeneration, 2);
    assert.equal(children[1].exitCode, null);
    assert.equal(diagnostics.length, 1);
    assert.match(diagnostics[0], /cleanup.*failure/);
    children[0].stdin.emit('error', new Error('stale stdin'));
    children[0].stdout.emit('data', 'invalid stale JSON\n');
    for (const params of children[1].turns) children[1].stdout.emit('data', JSON.stringify({ method: 'turn/completed', params }) + '\n');
    assert.deepEqual(await Promise.all(turns), ['completed', 'completed']);
    // Current transport failures must still invalidate its active sessions.
    children[1].stdin.emit('error', new Error('current I/O failure'));
    assert.equal(service.started, false);
    assert.deepEqual(service.agentIds, []);
  } finally { children[0].exitCode = 0; children[0].emit('exit', 0, null); await service.stop(); }
});

function preparationFixture({ hang = 'mkdir' } = {}) {
  const gate = deferred();
  const timers = new Set();
  const calls = [];
  let held = false;
  const root = path.resolve('g03-in-memory-workspace');
  const fs = Object.fromEntries(['mkdir', 'chmod', 'readdir', 'writeFile', 'rename', 'rm', 'unlink', 'readFile'].map(method => [method, async (...args) => {
    calls.push({ method, args });
    if (method === hang && !held) { held = true; await gate.promise; }
    if (method === 'readdir') return [];
    if (method === 'readFile') {
      if (args[0].startsWith(path.join(root, 'templates') + path.sep)) return 'fixture instructions';
      throw Object.assign(new Error('fixture missing'), { code: 'ENOENT' });
    }
  }]));
  const workspace = new MinecraftAgentWorkspace({ root, templateRoot: path.join(root, 'templates') }, { fs, sourceCodexHome: path.join(root, 'source') });
  const transportCalls = [];
  const transport = {
    setEnvironment: value => transportCalls.push(['environment', value]), setWorkingDirectory() {},
    async start() { transportCalls.push(['start']); }, async stop() { transportCalls.push(['stop']); },
    async request() { return {}; }, notify() {},
  };
  const service = new CodexService({ cwd: root, environment: {}, launchProfile: profile }, {
    transport, minecraftWorkspace: workspace,
    startupSchedule: (callback, delay) => { const timer = { callback, delay }; timers.add(timer); return timer; },
    startupCancelSchedule: timer => timers.delete(timer),
  });
  return { gate, timers, calls, service, workspace, transportCalls };
}

for (const hang of ['mkdir', 'readFile']) {
  test(`startup retries while obsolete ${hang} remains pending and fences its late continuation`, async () => {
    const f = preparationFixture({ hang });
    const first = f.service.start().then(() => 'started', error => error.code);
    try {
      await tick();
      assert.equal(f.timers.size, 1, 'existing startup deadline includes preparation');
      const timer = [...f.timers][0];
      assert.equal(timer.delay, 15000);
      timer.callback();
      assert.equal(await first, 'PROVIDER_START_TIMEOUT');
      await f.service.start();
      assert.equal(f.service.started, true);
      const before = f.calls.length;
      f.gate.resolve();
      await tick();
      assert.equal(f.calls.length, before, 'abandoned filesystem continuation performs no new operation');
      assert.equal(f.transportCalls.filter(([name]) => name === 'start').length, 1);
      assert.equal(f.transportCalls.filter(([name]) => name === 'environment').length, 1);
    } finally { f.gate.resolve(); await first; await f.service.stop(); assert.equal(f.timers.size, 0); }
  });
}

test('aborting an in-flight file mutation keeps later preparations serialized', async () => {
  const f = preparationFixture({ hang: 'rename' });
  const controller = new AbortController();
  const first = f.workspace.prepare({ signal: controller.signal }).then(() => 'prepared', error => error.name);
  await tick();
  controller.abort();
  const before = f.calls.length;
  let settled = false;
  const second = f.workspace.prepare().then(() => { settled = true; });
  await tick();
  assert.equal(settled, false);
  assert.equal(f.calls.length, before, 'no successor write may race the unresolved rename');
  f.gate.resolve();
  assert.equal(await first, 'AbortError');
  await second;
  await f.service.stop();
});

const usage = (amount, turnId, explicitTurnId = true) => ({ method: 'thread/tokenUsage/updated', params: {
  threadId, ...(explicitTurnId ? { turnId } : {}), tokenUsage: { total: { inputTokens: amount } },
} });
const completed = turnId => ({ method: 'turn/completed', params: { threadId, turn: { id: turnId, status: 'completed' } } });

const scenarios = [
  { name: 'completion_then_usage_same_chunk', mode: 'same', explicit: true, first: 'missing', start: 150, delta: 30 },
  { name: 'same_chunk_usage_without_turn_id', mode: 'same', explicit: false, first: 'missing', start: 150, delta: 30 },
  { name: 'usage_after_await_act_control', mode: 'after', explicit: false, first: 'missing', start: 150, delta: 30 },
  { name: 'usage_before_completion_control', mode: 'before', explicit: false, first: 'available', start: 150, delta: 30 },
  { name: 'no_fresh_usage_control', mode: 'none', explicit: false, first: 'missing', start: null, delta: null },
  { name: 'in_turn_usage_then_late_counter_control', mode: 'prior', explicit: false, first: 'available', start: 150, delta: 30 },
];

for (const scenario of scenarios) {
  test(scenario.name, { timeout: 2500 }, async () => {
    const f = await fixture();
    try {
      f.emit(usage(100, 'old', scenario.explicit));
      const first = await f.start('first');
      if (scenario.mode === 'before') f.emit(usage(150, 'first', scenario.explicit), completed('first'));
      else if (scenario.mode === 'prior') f.emit(usage(120, 'first', scenario.explicit), completed('first'), usage(150, 'first', scenario.explicit));
      else if (scenario.mode === 'same') f.emit(completed('first'), usage(150, 'first', scenario.explicit));
      else f.emit(completed('first'));
      const firstEvidence = (await first.pending).nativeTurn;
      if (scenario.mode === 'after') f.emit(usage(150, 'first', scenario.explicit));
      const second = await f.start('second');
      f.emit(usage(180, 'second', scenario.explicit), completed('second'));
      const secondEvidence = (await second.pending).nativeTurn;
      assert.equal(firstEvidence.usage.status, scenario.first);
      assert.equal(firstEvidence.tokens.input, scenario.first === 'missing' ? null : scenario.mode === 'prior' ? 20 : 50);
      assert.equal(secondEvidence.usage.start?.input ?? null, scenario.start);
      assert.equal(secondEvidence.tokens.input, scenario.delta);
      assert.equal(secondEvidence.usage.status, scenario.delta === null ? 'baseline_unknown' : 'available');
      assert.equal(firstEvidence.usage.attributionComplete, false);
      assert.equal(secondEvidence.usage.attributionComplete, false);
      if (scenario.mode === 'prior') assert.equal(secondEvidence.usage.gapBefore.input, 30);
    } finally { await f.close(); }
  });
}
