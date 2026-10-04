import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { MinecraftAgentWorkspace } from '../src/minecraft-agent-workspace.mjs';
import { CodexService } from '../src/codex-service.mjs';

// Adapted from the F04 verifier: real temporary files and synthetic accounts,
// with only OS operation promise settlement controlled at the dependency seam.
const auth = (account, refresh = '2026-10-01T00:00:00Z') => `${JSON.stringify({
  auth_mode: 'chatgpt', tokens: { account_id: account, access_token: 'synthetic-only' }, last_refresh: refresh,
})}\n`;
const hash = content => createHash('sha256').update(content).digest('hex');
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const tick = () => new Promise(setImmediate);
const outcome = promise => promise.then(() => 'prepared', error => error);

async function fixture(t, { initial = true } = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'arena-auth-cancellation-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const root = path.join(dir, 'runtime');
  const source = path.join(dir, 'source');
  const templateRoot = path.join(dir, 'templates');
  const dest = path.join(root, '.codex-home', 'auth.json');
  const marker = path.join(root, '.auth-source.sha256');
  await fs.mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
  await fs.mkdir(source, { recursive: true });
  await fs.writeFile(path.join(templateRoot, 'AGENTS.md'), '# Fixture instructions\n');
  await fs.writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Fixture skill\n');
  const hooks = new Map();
  const calls = [];
  const options = { root, templateRoot };
  const dependencies = { sourceCodexHome: source, fs: {
    async rename(...args) {
      calls.push(args[1]);
      await fs.rename(...args);
      const hook = hooks.get(args[1]);
      hooks.delete(args[1]);
      if (hook) await hook();
    },
  } };
  let workspace = new MinecraftAgentWorkspace(options, dependencies);
  const rotate = content => fs.writeFile(path.join(source, 'auth.json'), content);
  const snapshot = async (content, saved = content === null ? 'missing' : hash(content)) => {
    if (content === null) await assert.rejects(fs.readFile(dest), { code: 'ENOENT' });
    else assert.equal(await fs.readFile(dest, 'utf8'), content);
    assert.equal((await fs.readFile(marker, 'utf8')).trim(), saved);
  };
  if (initial) {
    await rotate(auth('A'));
    await workspace.prepare();
    await snapshot(auth('A'));
  }
  return {
    root, source, dest, marker, calls, rotate, snapshot,
    get workspace() { return workspace; },
    arm(target, hook) { hooks.set(target, hook); },
    restart() { workspace = new MinecraftAgentWorkspace(options, dependencies); },
    logout: () => fs.unlink(path.join(source, 'auth.json')),
  };
}

async function recovery(f, restart, installed = auth('B')) {
  if (restart) f.restart();
  await f.workspace.prepare();
  await f.snapshot(installed);
  await f.rotate(auth('C'));
  await f.workspace.prepare();
  await f.snapshot(auth('C'));
  await f.logout();
  await f.workspace.prepare();
  await f.snapshot(null);
}

for (const entrypoint of ['prepare', 'startup deadline', 'service stop']) {
  for (const restart of [false, true]) {
    test(`auth commit survives ${entrypoint} cancellation with ${restart ? 'fresh' : 'same'} instance recovery`, async t => {
      const f = await fixture(t);
      const authEntered = deferred(), authRelease = deferred();
      const markerEntered = deferred(), markerRelease = deferred();
      let signal, preparation, timer, service;
      const controller = new AbortController();
      const transportCalls = [];
      if (entrypoint !== 'prepare') {
        service = new CodexService({ cwd: f.root, environment: { CODEX_HOME: f.source }, launchProfile: {
          model: 'synthetic-only', reasoningEffort: 'high', serviceTier: 'priority',
        } }, {
          transport: {
            setEnvironment() { transportCalls.push('environment'); }, setWorkingDirectory() {},
            async start() { transportCalls.push('start'); }, async stop() {},
            async request() { return {}; }, notify() {},
          },
          minecraftWorkspace: { prepare(options) {
            signal = options.signal;
            preparation = f.workspace.prepare(options);
            return preparation;
          } },
          startupSchedule(callback, delay) { assert.equal(delay, 15000); timer = callback; return {}; },
          startupCancelSchedule() {},
        });
      }
      f.arm(f.dest, async () => { authEntered.resolve(); await authRelease.promise; });
      f.arm(f.marker, async () => { markerEntered.resolve(); await markerRelease.promise; });
      await f.rotate(auth('B'));
      let queued;
      const start = outcome(service ? service.start() : (preparation = f.workspace.prepare({ signal: controller.signal })));
      try {
        await authEntered.promise;
        await f.snapshot(auth('B'), hash(auth('A')));
        if (entrypoint === 'prepare') { controller.abort(); signal = controller.signal; }
        else if (entrypoint === 'startup deadline') timer();
        else await service.stop();
        assert.equal(signal.aborted, true);
        let queuedSettled = false;
        const before = f.calls.length;
        queued = f.workspace.prepare().then(() => { queuedSettled = true; });
        await tick();
        assert.equal(queuedSettled, false);
        assert.equal(f.calls.length, before, 'successor cannot race the held auth mutation');
        authRelease.resolve();
        // Ownership must commit even with an already-aborted preparation signal.
        // Race against preparation settlement so the old implementation fails
        // with an assertion instead of waiting forever for a missing marker write.
        const phase = await Promise.race([markerEntered.promise.then(() => 'marker'), outcome(preparation)]);
        assert.equal(phase, 'marker');
        await tick();
        assert.equal(queuedSettled, false, 'serialization extends through ownership bookkeeping');
        assert.equal(f.calls.length, before + 1);
        markerRelease.resolve();
        assert.equal((await outcome(preparation)).name, 'AbortError');
        const startResult = await start;
        if (entrypoint === 'startup deadline') assert.equal(startResult.code, 'PROVIDER_START_TIMEOUT');
        else assert.equal(startResult.name, 'AbortError');
        await queued;
        await f.snapshot(auth('B'));
        assert.deepEqual(transportCalls, [], 'cancelled preparation never starts a provider or changes launch environment');
        if (service) assert.equal(service.started, false);
        await recovery(f, restart);
      } finally {
        authRelease.resolve(); markerRelease.resolve();
        await start;
        if (preparation) await outcome(preparation);
        if (queued) await queued;
        if (service) await service.stop();
      }
    });
  }
}

for (const restart of [false, true]) {
  test(`normal auth rotation and logout with ${restart ? 'fresh' : 'same'} instance`, async t => {
    const f = await fixture(t);
    await f.rotate(auth('B'));
    await f.workspace.prepare();
    await recovery(f, restart);
  });
  test(`independent auth survives source rotation and logout with ${restart ? 'fresh' : 'same'} instance`, async t => {
    const f = await fixture(t);
    await fs.writeFile(f.dest, auth('D'));
    await f.rotate(auth('B'));
    if (restart) f.restart();
    await f.workspace.prepare();
    await f.snapshot(auth('D'), hash(auth('A')));
    await f.logout();
    await f.workspace.prepare();
    await f.snapshot(auth('D'), 'missing');
  });
}

test('early auth cancellation preserves ownership and a later retry recovers', async t => {
  const f = await fixture(t);
  await f.rotate(auth('B'));
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), { name: 'AbortError' });
  await f.snapshot(auth('A'));
  await f.workspace.prepare();
  await recovery(f, true);
});

test('cancellation while reading auth prevents starting its commit', async t => {
  const f = await fixture(t);
  await f.rotate(auth('B'));
  const controller = new AbortController();
  const readFile = f.workspace.fs.readFile;
  f.workspace.fs.readFile = async (...args) => {
    const content = await readFile(...args);
    if (args[0] === f.dest) controller.abort();
    return content;
  };
  const before = f.calls.length;
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), { name: 'AbortError' });
  assert.equal(f.calls.length, before, 'no rename occurs before an already-cancelled auth commit');
  await f.snapshot(auth('A'));
  f.workspace.fs.readFile = readFile;
  await f.workspace.prepare();
  await recovery(f, true);
});

for (const install of ['initial', 'newer refresh']) {
  test(`cancelled ${install} auth installation retains durable ownership`, async t => {
    const f = await fixture(t, { initial: install !== 'initial' });
    const content = install === 'initial' ? auth('B') : auth('A', '2026-10-02T00:00:00Z');
    await f.rotate(content);
    const controller = new AbortController();
    f.arm(f.dest, () => controller.abort());
    await assert.rejects(f.workspace.prepare({ signal: controller.signal }), { name: 'AbortError' });
    await f.snapshot(content);
    await recovery(f, true, content);
  });
}

test('cancellation after durable marker rename also records in-memory ownership', async t => {
  const f = await fixture(t);
  await f.rotate(auth('B'));
  const controller = new AbortController();
  f.arm(f.marker, () => controller.abort());
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), { name: 'AbortError' });
  await f.snapshot(auth('B'));
  await recovery(f, false);
});

test('ownership I/O failure takes precedence over deferred cancellation', async t => {
  const f = await fixture(t);
  await f.rotate(auth('B'));
  const controller = new AbortController();
  const failure = Object.assign(new Error('synthetic marker I/O failure'), { code: 'EIO' });
  f.arm(f.dest, () => controller.abort());
  const writeFile = f.workspace.fs.writeFile;
  f.workspace.fs.writeFile = async (...args) => {
    if (path.basename(args[0]).startsWith('.' + path.basename(f.marker) + '.')) throw failure;
    return writeFile(...args);
  };
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), error => error === failure);
  await f.snapshot(auth('B'), hash(auth('A')));
});

test('failed auth mutation propagates its I/O error without advancing ownership', async t => {
  const f = await fixture(t);
  await f.rotate(auth('B'));
  const controller = new AbortController();
  const failure = Object.assign(new Error('synthetic auth I/O failure'), { code: 'EIO' });
  const rename = f.workspace.fs.rename;
  f.workspace.fs.rename = async (...args) => {
    if (args[1] === f.dest) { controller.abort(); throw failure; }
    return rename(...args);
  };
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), error => error === failure);
  await f.snapshot(auth('A'));
  f.workspace.fs.rename = rename;
  await f.workspace.prepare();
  await recovery(f, true);
});

test('cancelled owned-auth removal finishes its logout bookkeeping', async t => {
  const f = await fixture(t);
  await f.logout();
  const controller = new AbortController();
  const rm = f.workspace.fs.rm;
  f.workspace.fs.rm = async (...args) => {
    await rm(...args);
    if (args[0] === f.dest) controller.abort();
  };
  await assert.rejects(f.workspace.prepare({ signal: controller.signal }), { name: 'AbortError' });
  await f.snapshot(null);
  f.restart();
  await f.workspace.prepare();
  await f.snapshot(null);
});
