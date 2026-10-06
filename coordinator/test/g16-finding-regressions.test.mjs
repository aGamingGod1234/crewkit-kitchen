import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { CodexService } from '../src/codex-service.mjs';
import { ProviderService } from '../src/provider-service.mjs';
import { createDynamicCoordinator } from '../src/dynamic-main.mjs';

const profile = { model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast' };
const smokeModel = { id: profile.model, model: profile.model, displayName: 'Smoke model', supportedReasoningEfforts: ['xhigh'], serviceTiers: ['fast'] };

class CaptureBridge extends EventEmitter {
  ready = false;
  connectionEpoch = 1;
  sent = [];
  start() { this.ready = true; }
  stop() { this.ready = false; }
  async send(type, agentId, payload, options) {
    const message = structuredClone({ type, agentId, payload, options });
    this.sent.push(message);
    this.emit('captured', message);
  }
}

function waitFor(emitter, event, predicate = () => true) {
  let handler;
  let timer;
  const promise = new Promise((resolve, reject) => {
    handler = (...args) => {
      if (!predicate(...args)) return;
      clearTimeout(timer);
      emitter.off(event, handler);
      resolve(args[0]);
    };
    emitter.on(event, handler);
    timer = setTimeout(() => {
      emitter.off(event, handler);
      reject(new Error(`Fixture deadline waiting for ${event}`));
    }, 5000);
  });
  return promise;
}

async function runCase(mode) {
  const calls = [];
  const transport = {
    async start() { calls.push('start'); },
    async request(method) {
      calls.push(method);
      if (method === 'initialize') {
        if (mode === 'initialize-error') throw Object.assign(new Error('fixture initialize rejected'), { code: 'RPC_ERROR' });
        return {};
      }
      assert.equal(method, 'model/list', 'the empty roster must never request a model turn');
      if (mode === 'discovery-error' || mode === 'different-builtin') throw Object.assign(new Error('fixture model/list rejected'), { code: 'RPC_ERROR' });
      if (mode === 'malformed-catalog') return { data: 'invalid' };
      if (mode === 'empty-catalog') return { data: [] };
      return { data: [smokeModel] };
    },
    notify(method) { calls.push(method); },
    async stop() { calls.push('stop'); },
  };
  const launchProfile = mode === 'different-builtin' ? { ...profile, model: 'fixture-other-model' } : profile;
  const codex = new CodexService({ cwd: process.cwd(), launchProfile }, { transport });
  const unavailable = () => ({
    async start() { throw Object.assign(new Error('isolated unavailable provider'), { code: 'PROVIDER_UNAVAILABLE' }); },
    async stop() {},
    catalog: { stale: true, async refresh() { throw new Error('unreachable'); } },
  });
  const providers = new ProviderService({ codex, gemini: unavailable(), claude: unavailable() });
  const bridge = new CaptureBridge();
  const coordinator = createDynamicCoordinator({ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile } }, {
    bridge, providerService: providers, memoryDirectory: null, env: {},
    setStatusInterval: () => 1, clearStatusInterval() {},
  });
  const errors = [];
  coordinator.on('runtimeError', error => errors.push({ code: error.code, message: error.message }));
  try {
    await coordinator.start();
    const reconciled = waitFor(coordinator, 'reconciled');
    bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'f108-offline', registry: [] });
    await reconciled;
    assert.deepEqual(calls, [], 'actual empty-roster reconciliation must not launch providers');
    const initial = bridge.sent.filter(message => message.type === 'catalog_snapshot');
    assert.ok(initial.length > 0);
    assert.ok(initial.every(message => message.payload.models.length === 0));
    const published = waitFor(bridge, 'captured', message => message.type === 'catalog_snapshot');
    bridge.emit('catalog_request', { connectionEpoch: 1, agentId: 'server', payload: {} });
    const message = await published;
    const catalog = codex.catalog.snapshot();
    // The Java smoke separately verifies this unique discovery row; builtin fallback must not forge it.
    const fallbackPresent = message.payload.models.some(model => model.provider === 'codex' && model.id === profile.model);
    const uniqueFakeResultPresent = message.payload.models.some(model => model.provider === 'codex' && model.id === profile.model && model.displayName === 'Smoke model');
    const rejectedControl = ['initialize-error', 'different-builtin'].includes(mode);
    assert.equal(fallbackPresent, !rejectedControl, "intentional exact-profile fallback stays available");
    assert.equal(uniqueFakeResultPresent, mode === 'success');
    assert.equal(calls.filter(call => call === 'model/list').length, mode === 'initialize-error' ? 0 : 1);
    assert.equal(catalog.source, mode === 'success' ? 'live' : 'builtin');
    assert.equal(codex.started, mode !== 'initialize-error');
    assert.equal(Object.hasOwn(message.payload, 'source'), false);
    assert.equal(Object.hasOwn(message.payload, 'recovery'), false);
    assert.deepEqual(errors, []);
    return { mode, callsBeforeCleanup: [...calls], initialCatalogCount: initial.length, fallbackPresent, uniqueFakeResultPresent, codexStarted: codex.started, catalogSource: catalog.source, catalogFailure: catalog.recovery.failureCode, publishedCatalog: message.payload };
  } finally {
    await coordinator.stop();
    assert.equal(bridge.ready, false);
    assert.equal(codex.started, false);
  }
}

for (const mode of ['success', 'discovery-error', 'malformed-catalog', 'empty-catalog', 'initialize-error', 'different-builtin']) {
  test(`startup discovery sentinel survives only successful discovery: ${mode}`, async () => {
    await runCase(mode);
  });
}
