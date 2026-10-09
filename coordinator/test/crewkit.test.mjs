import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Records go to a temp dir, never the repo.
process.env.CREWKIT_RECORDS_DIR = mkdtempSync(path.join(tmpdir(), 'crewkit-test-'));
const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, '..', 'src', 'crewkit', 'fixtures');
const brief = JSON.parse(readFileSync(path.join(fixtures, 'demo-brief.json'), 'utf8'));
const tape = JSON.parse(readFileSync(path.join(here, 'fixtures', 'crewkit-handmade.tape.json'), 'utf8'));

const { evaluateGate, planRework, quoteTotals, checkRequirements, validateQuoteItems } = await import('../src/crewkit/gate.mjs');
const { mapToMcItem } = await import('../src/crewkit/mapping.mjs');
const { startRun } = await import('../src/crewkit/runner.mjs');
const { runCrewkit, pickable } = await import('../src/crewkit/engine.mjs');
const { replayApi, recordingApi } = await import('../src/crewkit/tape.mjs');
const { createEventStream } = await import('../src/crewkit/events.mjs');

const SGD = (amount) => ({ amount, currency: 'SGD' });
const budget = SGD(150); // unit tests; the demo brief itself is S$190
const future = new Date(Date.now() + 10 * 60_000).toISOString();
const totals = (amount, extra = {}) => ({ quoteId: 'q', total: SGD(amount), shipping: SGD(4), subtotal: SGD(amount - 4), expiresAt: future, invalid: null, ...extra });
const names = (events) => events.map((e) => e.event).filter((e) => e !== 'calls');

test('gate passes at exactly the budget and blocks one cent over, shipping included', () => {
  assert.deepEqual(evaluateGate({ totals: totals(150), budget }), { ok: true });
  const g = evaluateGate({ totals: totals(150.01), budget });
  assert.equal(g.code, 'OVER_BUDGET');
  assert.deepEqual(g.over, SGD(0.01));
  const q = quoteTotals({ id: 'x', amountBreakdown: { itemsSubtotal: SGD(147), shipping: SGD(4), finalAmount: SGD(151) }, expiresAt: future }, 'SGD');
  assert.equal(evaluateGate({ totals: q, budget }).code, 'OVER_BUDGET', 'gate uses finalAmount, not the item subtotal');
});

test('finding 1: gate fails closed on a quote it cannot read exactly', () => {
  const good = { id: 'q1', expiresAt: future, amountBreakdown: { finalAmount: SGD(100) } };
  assert.deepEqual(evaluateGate({ totals: quoteTotals(good, 'SGD'), budget }), { ok: true });
  const bad = {
    'missing finalAmount': { ...good, amountBreakdown: {} },
    'missing breakdown': { id: 'q1', expiresAt: future },
    'string amount': { ...good, amountBreakdown: { finalAmount: { amount: '100', currency: 'SGD' } } },
    'NaN amount': { ...good, amountBreakdown: { finalAmount: { amount: NaN, currency: 'SGD' } } },
    'bare number, no currency': { ...good, amountBreakdown: { finalAmount: 100 } },
    'missing currency': { ...good, amountBreakdown: { finalAmount: { amount: 100 } } },
    'missing expiresAt': { ...good, expiresAt: undefined },
    'invalid expiresAt': { ...good, expiresAt: 'soon' },
    'missing id': { ...good, id: undefined },
  };
  for (const [label, quote] of Object.entries(bad)) {
    assert.equal(evaluateGate({ totals: quoteTotals(quote, 'SGD'), budget }).code, 'QUOTE_INVALID', label);
  }
  assert.equal(evaluateGate({ totals: { ...totals(10), invalid: undefined }, budget }).code, 'QUOTE_INVALID', 'unchecked totals are not trusted');
  assert.equal(evaluateGate({ totals: quoteTotals({ ...good, amountBreakdown: { finalAmount: { amount: 100, currency: 'USD' } } }, 'SGD'), budget }).code, 'CURRENCY_MISMATCH');
});

test('finding 1: a quote without finalAmount blocks with QUOTE_INVALID before any checkout', async () => {
  const t = structuredClone(tape);
  for (const e of t.entries) if (e.op === 'createQuote') delete e.response.amountBreakdown.finalAmount;
  const { r, events } = await run(t);
  assert.equal(r.status, 'QUOTE_INVALID');
  const blocked = events.find((e) => e.event === 'gate_blocked');
  assert.equal(blocked.data.code, 'QUOTE_INVALID');
  assert.ok(!names(events).includes('checkout') && !names(events).includes('gate_passed') && !names(events).includes('quote'));
});

test('finding 1: the pre-checkout re-read is gated on its own, not merged with the first quote', async () => {
  const t = structuredClone(tape);
  const g = t.entries.find((e) => e.op === 'getQuote');
  g.response = { id: g.response.id, amountBreakdown: g.response.amountBreakdown }; // expiresAt missing on the fresh read
  const { r, events } = await run(t);
  assert.equal(r.status, 'QUOTE_INVALID');
  assert.ok(!names(events).includes('checkout'));
});

test('gate blocks a currency mismatch and an expired or nearly expired quote', () => {
  assert.equal(evaluateGate({ totals: { ...totals(10), total: { amount: 10, currency: 'USD' } }, budget }).code, 'CURRENCY_MISMATCH');
  assert.equal(evaluateGate({ totals: totals(10, { expiresAt: new Date(Date.now() - 1000).toISOString() }), budget }).code, 'QUOTE_EXPIRED');
  assert.equal(evaluateGate({ totals: totals(10, { expiresAt: new Date(Date.now() + 5000).toISOString() }), budget }).code, 'QUOTE_EXPIRED');
});

test('rework swaps to cheaper substitutes and drops only optional extras, never mandatory quantities', () => {
  const alt = (id, amount) => ({ productId: id, variantId: id, realName: id, merchant: 'popular.com.sg', unitPrice: SGD(amount) });
  const cart = [
    { id: 'cable', needId: 'cable', qty: 6, unitPrice: SGD(12.9), alternates: [alt('cheap_cable', 7.9)] },
    { id: 'notebook', needId: 'notebook', qty: 12, unitPrice: SGD(4.5), alternates: [alt('cheap_nb', 2.2)] },
    { id: 'pen', needId: 'pen', qty: 12, unitPrice: SGD(1.8), alternates: [] },
    { id: 'markers', needId: 'markers', qty: 1, optional: true, unitPrice: SGD(6.9), alternates: [] },
  ];
  const a = planRework({ cart, total: 196.6, budget });
  assert.deepEqual(a.changes.map((c) => [c.type, c.id]), [['swap', 'cable'], ['swap', 'notebook']]);
  assert.equal(a.fits, true);
  assert.ok(a.changes.every((c) => c.type !== 'drop' || cart.find((x) => x.id === c.id).optional));
  const b = planRework({ cart, total: 210, budget });
  assert.deepEqual(b.changes.map((c) => [c.type, c.id]), [['swap', 'cable'], ['swap', 'notebook'], ['drop', 'markers']]);
  const c = planRework({ cart, total: 300, budget });
  assert.equal(c.fits, false, 'infeasible: caller reports BRIEF_INFEASIBLE');
});

test('requirements check and quote line rules', () => {
  const needs = [{ id: 'pen', per: 'person' }, { id: 'cable', per: 'pair' }, { id: 'markers', per: 'room', optional: true }];
  const required = (n) => (n.per === 'person' ? 12 : n.per === 'pair' ? 6 : 1);
  assert.deepEqual(checkRequirements({ needs, required, cart: [{ needId: 'pen', qty: 0 }, { needId: 'pen', qty: 12 }, { needId: 'cable', qty: 6 }] }), { ok: true, missing: [] });
  assert.deepEqual(checkRequirements({ needs, required, cart: [{ needId: 'pen', qty: 10 }] }), { ok: false, missing: [{ need: 'pen', qty: 2 }, { need: 'cable', qty: 6 }] });
  assert.throws(() => validateQuoteItems([]), { code: 'QUOTE_ITEMS_INVALID' });
  assert.throws(() => validateQuoteItems(Array.from({ length: 21 }, (_, i) => ({ variantId: 'v' + i, quantity: 1 }))), { code: 'QUOTE_ITEMS_INVALID' });
  assert.throws(() => validateQuoteItems([{ variantId: 'v', quantity: 1.5 }]), { code: 'QUOTE_ITEMS_INVALID' });
  assert.throws(() => validateQuoteItems([{ variantId: 'a', quantity: 1 }, { variantId: 'b', quantity: 1 }], ['popular.com.sg', 'anker.com.sg']), { code: 'MIXED_MERCHANTS' });
});

test('product names map to allowlisted vanilla items', () => {
  assert.equal(mapToMcItem({ productName: 'Deli Name Badge Holder with Lanyard' }), 'minecraft:name_tag');
  assert.equal(mapToMcItem({ productName: 'Pilot G-2 Gel Pen 0.7mm' }), 'minecraft:feather');
  assert.equal(mapToMcItem({ productName: 'Type-C Fast Charging Cable 1m' }), 'minecraft:lead');
  assert.equal(mapToMcItem({ productName: 'Pokka Jasmine Green Tea 500ml' }), 'minecraft:honey_bottle');
  assert.equal(mapToMcItem({ productName: 'Mini Portable Bluetooth Speaker' }), 'minecraft:note_block');
  assert.equal(mapToMcItem({ productName: 'Campap A5 Ruled Exercise Book', needLabel: 'notebook' }), 'minecraft:writable_book', 'need label wins');
  assert.equal(mapToMcItem({ productName: 'Open Day Thing', needLabel: 'unknown' }), 'minecraft:paper', 'no "pen" match inside "Open"; falls back to paper');
});

const EXPECTED = ['reset', 'brief', ...Array(6).fill(['candidates', 'item_added']).flat(),
  'quote', 'gate_blocked', 'item_removed', 'item_added', 'item_removed', 'item_added', 'quote', 'requirements', 'gate_passed',
  'checkout', 'checkout', 'completed', 'record'];

test('candidates: each need fans up to 5 same-merchant search results right before its item_added', async () => {
  const brief6 = JSON.parse(readFileSync(path.join(fixtures, 'demo-brief-6.json'), 'utf8'));
  const { done } = await startRun(brief6, { mode: 'replay', speed: 0, writeRecords: false });
  const r = await done;
  const ev = r.events.filter((e) => e.event !== 'calls');
  const fans = ev.filter((e) => e.event === 'candidates');
  assert.equal(fans.length, brief6.needs.length + (brief6.extras || []).length);
  for (const f of fans) {
    const next = ev[ev.indexOf(f) + 1];
    assert.equal(next.event, 'item_added');
    const { query, options, chosenIndex } = f.data;
    assert.ok(typeof query === 'string' && query.length);
    assert.ok(options.length >= 1 && options.length <= 5);
    assert.equal(options[chosenIndex].realName, next.data.realName);
    for (const o of options) assert.ok(o.realName && o.mcItem.startsWith('minecraft:') && o.price.amount > 0 && o.price.currency === 'SGD');
  }
  assert.ok(fans.some((f) => f.data.options.length > 1), 'real tape gives a real choice');
});

test('replay emits the full contract sequence with one runId and gap-free seq', async () => {
  const sent = [];
  const { runId, done } = await startRun(brief, { mode: 'replay', speed: 0, tape, sinks: [(p) => sent.push(p)] });
  const r = await done;
  assert.equal(r.status, 'COMPLETED');
  assert.deepEqual(names(sent), EXPECTED);
  assert.ok(sent.every((p) => p.runId === runId));
  assert.deepEqual(sent.map((p) => p.seq), sent.map((_, i) => i + 1));
  const calls = sent.filter((p) => p.event === 'calls').map((p) => p.data.count);
  assert.deepEqual(calls, calls.map((_, i) => i + 1));
  assert.equal(calls.at(-1), r.calls);
  const blocked = sent.find((p) => p.event === 'gate_blocked').data;
  assert.deepEqual(blocked.over, SGD(60.45));
  const removed = sent.filter((p) => p.event === 'item_removed').map((p) => [p.data.id, p.data.qtyRemoved]);
  assert.deepEqual(removed, [['notebook', 12], ['badge', 12]]);
  const swappedIn = sent.filter((p) => p.event === 'item_added').slice(-2).map((p) => [p.data.id, p.data.needId, p.data.qty]);
  assert.deepEqual(swappedIn, [['notebook-alt1', 'notebook', 12], ['badge-alt1', 'badge', 12]], 'mandatory quantities kept');
  assert.ok(sent.filter((p) => p.event === 'item_added').every((p) => p.data.merchant === 'Popular Bookstore'), 'one merchant');
  assert.deepEqual(sent.find((p) => p.event === 'requirements').data, { ok: true, missing: [] });
  assert.equal(JSON.parse(readFileSync(r.files.jsonFile, 'utf8')).outcome, 'order placed');
  assert.deepEqual(sent.find((p) => p.event === 'record').data, { budget: 190, quoted: 181.45, charged: 181.45, variance: 0, orderId: 'POP-SG-DEMO-0001', currency: 'SGD' });
  assert.ok(existsSync(r.files.jsonFile) && existsSync(r.files.csvFile) && existsSync(r.eventsFile));
  assert.equal(readFileSync(r.eventsFile, 'utf8').trim().split('\n').length, sent.length);
});

function run(tapeObj) {
  const stream = createEventStream({ runId: 't', sinks: [] });
  const api = replayApi(tapeObj, { speed: 0 });
  return runCrewkit({ brief, api, emit: stream.emit, enrollmentId: 'e', sleep: async () => {} }).then((r) => ({ r, events: stream.log }));
}

test('expired quote at the pre-checkout re-read triggers a new quote before checkout', async () => {
  const t = structuredClone(tape);
  const gi = t.entries.findIndex((e) => e.op === 'getQuote');
  const second = t.entries.filter((e) => e.op === 'createQuote')[1];
  t.entries.splice(gi, 0, { op: 'getQuote', key: '', ms: 0, error: { status: 409, code: 'QUOTE_EXPIRED', message: 'expired' } },
    { ...structuredClone(second), response: { ...structuredClone(second.response), id: 'requoted' } });
  const { r, events } = await run(t);
  assert.equal(r.status, 'COMPLETED');
  assert.deepEqual(names(events).filter((e) => e === 'quote').length, 3);
  assert.ok(names(events).indexOf('gate_passed') > names(events).lastIndexOf('quote'));
});

test('a budget no permitted kit can meet fails BRIEF_INFEASIBLE without cutting a need', async () => {
  const stream = createEventStream({ runId: 't', sinks: [] });
  const r = await runCrewkit({ brief: { ...brief, budget: SGD(60) }, api: replayApi(tape, { speed: 0 }), emit: stream.emit, enrollmentId: 'e', sleep: async () => {} });
  assert.equal(r.status, 'BRIEF_INFEASIBLE');
  assert.equal(names(stream.log).at(-1), 'failed');
  assert.ok(!names(stream.log).includes('item_removed') && !names(stream.log).includes('checkout'));
});

test('checkout EXPIRED emits expired and never completed or record', async () => {
  const t = structuredClone(tape);
  const last = t.entries.findLastIndex((e) => e.op === 'getCheckout');
  t.entries[last].response.status = 'EXPIRED';
  t.entries[last].response.orderId = null;
  t.entries.splice(last - 1, 1); // drop PROCESSING
  const { r, events } = await run(t);
  assert.equal(r.status, 'EXPIRED');
  assert.equal(names(events).at(-1), 'expired');
  assert.ok(!names(events).includes('completed') && !names(events).includes('record'));
});

test('finding 2: approval URL goes only to the bridge sink, never to files, logs or the event log', async () => {
  const { REPLAY_APPROVAL_URL } = await import('../src/crewkit/tape.mjs');
  const bridge = []; const logged = [];
  const { done } = await startRun(brief, { mode: 'replay', speed: 0, tape, bridgeSinks: [(p) => bridge.push(p)], sinks: [(p) => logged.push(p)] });
  const r = await done;
  const raw = bridge.filter((p) => p.event === 'checkout');
  assert.ok(raw.length && raw.every((p) => p.data.approvalUrl === REPLAY_APPROVAL_URL), 'mod gets the replay placeholder for the QR');
  const onDisk = [r.eventsFile, r.files.jsonFile, r.files.csvFile].map((file) => readFileSync(file, 'utf8')).join(' ');
  for (const text of [onDisk, JSON.stringify(logged), JSON.stringify(r.events), JSON.stringify(r.checkout)]) {
    assert.ok(!text.includes(REPLAY_APPROVAL_URL) && !/approve/i.test(text.replace(/approvalUrl/g, '')), 'no approval URL outside the bridge');
  }
  assert.ok(logged.filter((p) => p.event === 'checkout').every((p) => p.data.approvalUrl === '[redacted]'));
});

test('finding 2: recorded tapes store [redacted] instead of the hosted approval URL', async () => {
  const secret = 'https://pay.sandbox.reap.global/approve/SECRET-TOKEN';
  const inner = { kind: 'live' };
  for (const op of ['search', 'details', 'createQuote', 'getQuote', 'getCheckout']) inner[op] = async () => ({});
  inner.createCheckout = async () => ({ id: 'c', status: 'REQUIRES_ACTION', nextAction: { type: 'REDIRECT', url: secret, expiresAt: future } });
  const rec = recordingApi(inner);
  const live = await rec.createCheckout('q', 'e');
  assert.equal(live.nextAction.url, secret, 'the engine still gets the real response');
  assert.ok(!JSON.stringify(rec.tape).includes('SECRET-TOKEN'));
  assert.equal(rec.tape.entries[0].response.nextAction.url, '[redacted]');
  assert.ok(!/approve\/|prava\.space|ses_/.test(readFileSync(path.join(fixtures, 'demo-popular-sg.tape.json'), 'utf8')), 'committed real fixture is redacted');
});

test('finding 2: crewkit_shop status never returns the approval URL to the model', async () => {
  const { getCrewkitController } = await import('../src/crewkit/service.mjs');
  const { crewkitShop } = await import('../src/native-tool-runtime.mjs');
  const controller = getCrewkitController();
  process.env.CREWKIT_REPLAY_SPEED = '0';
  const started = await crewkitShop({ action: 'start', mode: 'replay' });
  delete process.env.CREWKIT_REPLAY_SPEED;
  assert.equal(started.reasonCode, 'CREWKIT_STARTED');
  const deadline = Date.now() + 60_000;
  while (controller.active !== null && Date.now() < deadline) await new Promise((r) => setTimeout(r, 200));
  const status = await crewkitShop({ action: 'status' });
  assert.equal(status.last.status, 'COMPLETED');
  const text = JSON.stringify(status);
  assert.ok(!/approvalUrl|https?:\/\//.test(text), text);
});

test('a recorded tape replays to the same events', async () => {
  const stream1 = createEventStream({ runId: 'a', sinks: [] });
  const rec = recordingApi(replayApi(tape, { speed: 0 }));
  await runCrewkit({ brief, api: rec, emit: stream1.emit, enrollmentId: 'e', sleep: async () => {} });
  const stream2 = createEventStream({ runId: 'a', sinks: [] });
  await runCrewkit({ brief, api: replayApi(structuredClone(rec.tape), { speed: 0 }), emit: stream2.emit, enrollmentId: 'e', sleep: async () => {} });
  const strip = (log) => log.map(({ event, data }) => ({ event, data: { ...data, expiresAt: undefined } }));
  assert.deepEqual(strip(stream2.log), strip(stream1.log));
});

test('crewkit_state passes protocol validation and rejects unknown events', async () => {
  const { validateProtocolV2Payload, COORDINATOR_TO_SERVER_TYPES } = await import('../src/protocol-v2.mjs');
  assert.ok(COORDINATOR_TO_SERVER_TYPES.includes('crewkit_state'));
  const { done } = await startRun(brief, { mode: 'replay', speed: 0, tape, writeRecords: false });
  for (const p of (await done).events) assert.deepEqual(validateProtocolV2Payload('crewkit_state', p), p);
  assert.throws(() => validateProtocolV2Payload('crewkit_state', { runId: 'r', seq: 1, event: 'explode', data: {} }));
  assert.throws(() => validateProtocolV2Payload('crewkit_state', { runId: 'r', seq: 1, event: 'reset', data: {}, extra: 1 }));
});

test('coordinator sends crewkit_state to the server over the bridge', async () => {
  const { start } = await import('./fixtures/dynamic-main-fixture.mjs');
  const run = await start();
  try {
    const payload = { runId: 'ck-test', seq: 1, event: 'reset', data: {} };
    assert.equal(await run.coordinator.sendCrewkitState(payload), true);
    const msg = run.bridge.sent.find((m) => m.type === 'crewkit_state');
    assert.equal(msg.agentId, 'server');
    assert.deepEqual(msg.payload, payload);
  } finally { await run.coordinator.stop(); }
});

test('coordinator speaks a Chef line through the say path as proximity chat; no Chef means false', async () => {
  const { start, record, eventually } = await import('./fixtures/dynamic-main-fixture.mjs');
  const config = { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } };
  const run = await start({ config, initialRegistry: [{ ...record('chef-1'), name: 'Chef' }] });
  run.bridge.acknowledgeActionResult = async () => {};
  try {
    assert.equal(await run.coordinator.speakAsAgentNamed('Nobody', 'hi'), false);
    const spoken = run.coordinator.speakAsAgentNamed('Chef', 'Order placed! Plating up.');
    await eventually(() => run.bridge.sent.some((m) => m.type === 'action_command'));
    const cmd = run.bridge.sent.find((m) => m.type === 'action_command');
    assert.equal(cmd.agentId, 'chef-1');
    assert.deepEqual([cmd.payload.actionType, cmd.payload.arguments], ['chat', { message: 'Order placed! Plating up.', audience: 'proximity' }]);
    run.bridge.emit('action_result', { agentId: 'chef-1', payload: { goalRevision: 0, actionId: cmd.payload.actionId, actionType: 'chat', state: 'SUCCEEDED', reasonCode: 'CHAT_SENT', eventSequence: 1 } });
    assert.equal(await spoken, true);
  } finally { await run.coordinator.stop(); }
});

test('finding 3: checkout idempotency key is persisted per quote before sending and survives 409 IN_PROGRESS', async () => {
  const stateFile = path.join(process.env.CREWKIT_RECORDS_DIR, 'state-test.json');
  const saved = { key: process.env.REAP_API_KEY, state: process.env.CREWKIT_STATE_FILE, fetch: globalThis.fetch };
  process.env.CREWKIT_STATE_FILE = stateFile;
  const client = await import('../src/crewkit/reap-client.mjs');
  process.env.REAP_API_KEY = 'test-key-not-real'; // fetch is mocked below; nothing leaves the process
  const seen = [];
  let inProgress = 1;
  globalThis.fetch = async (url, init) => {
    const key = init.headers['Idempotency-Key'];
    const persisted = JSON.parse(readFileSync(stateFile, 'utf8')).checkoutKeys;
    seen.push({ key, persistedBeforeSend: Object.values(persisted).some((v) => v.key === key), quoteId: JSON.parse(init.body).quoteId });
    const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
    if (inProgress-- > 0) return json(409, { error: { code: 'IDEMPOTENCY_REQUEST_IN_PROGRESS', message: 'in progress' } });
    return json(200, { id: 'chk', status: 'REQUIRES_ACTION', nextAction: { url: '[x]' } });
  };
  try {
    const c1 = await client.createCheckout('quote-1', 'enr-1', { inProgressWaitMs: 5 });
    assert.equal(c1.id, 'chk');
    assert.equal(seen.length, 2, 'one 409 wait, then success');
    assert.equal(seen[0].key, seen[1].key, '409 IN_PROGRESS retried with the same key');
    assert.ok(seen.every((s) => s.persistedBeforeSend), 'key written to state.json before the request');
    await client.createCheckout('quote-1', 'enr-1');
    assert.equal(seen[2].key, seen[0].key, 'same quote retried later reuses the key');
    await client.createCheckout('quote-2', 'enr-1');
    assert.notEqual(seen[3].key, seen[0].key, 'a new quote gets a new key');
    const keys = JSON.parse(readFileSync(stateFile, 'utf8')).checkoutKeys;
    assert.deepEqual(Object.keys(keys).sort(), ['quote-1', 'quote-2']);
    inProgress = 99;
    await assert.rejects(client.createCheckout('quote-3', 'enr-1', { inProgressWaitMs: 1 }), (e) => e.code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS');
    const q3 = seen.filter((s) => s.quoteId === 'quote-3');
    assert.ok(q3.length > 1 && new Set(q3.map((s) => s.key)).size === 1, 'never a new key while in progress');
  } finally {
    globalThis.fetch = saved.fetch;
    if (saved.key === undefined) delete process.env.REAP_API_KEY; else process.env.REAP_API_KEY = saved.key;
    if (saved.state === undefined) delete process.env.CREWKIT_STATE_FILE; else process.env.CREWKIT_STATE_FILE = saved.state;
  }
});

test('sold-out line: probes each line, swaps that need to an in-stock product, shows item_removed why sold_out', async () => {
  const t = structuredClone(tape);
  const first = t.entries.findIndex((e) => e.op === 'createQuote');
  const okProbe = { op: 'createQuote', key: '', ms: 0, response: structuredClone(t.entries[first].response) };
  const soldOut = { op: 'createQuote', key: '', ms: 0, error: { status: 409, code: 'VARIANT_UNAVAILABLE', message: 'The selected item is sold out.', detail: null } };
  // full quote fails; probes: badge ok, notebook sold out, pen/cable/sticky/markers ok; first notebook substitute ok
  t.entries.splice(first, 0, soldOut, okProbe, soldOut, okProbe, okProbe, okProbe, okProbe, okProbe);
  const { r, events } = await run(t);
  assert.equal(r.status, 'COMPLETED');
  const removed = events.filter((e) => e.event === 'item_removed');
  assert.deepEqual([removed[0].data.id, removed[0].data.why], ['notebook', 'sold_out']);
  const next = events[events.indexOf(removed[0]) + 1];
  assert.equal(next.event, 'item_added');
  assert.equal(next.data.needId, 'notebook');
  assert.equal(next.data.qty, removed[0].data.qtyRemoved, 'mandatory quantity kept');
  assert.deepEqual(events.find((e) => e.event === 'requirements').data, { ok: true, missing: [] });
});

test('sold-out mandatory need with no in-stock substitute fails BRIEF_INFEASIBLE', async () => {
  const t = structuredClone(tape);
  for (const e of t.entries) if (e.op === 'search') e.response.products = e.response.products.slice(0, 1);
  const first = t.entries.findIndex((e) => e.op === 'createQuote');
  const soldOut = { op: 'createQuote', key: '', ms: 0, error: { status: 400, code: 'AGENTIC_REQUEST_REJECTED', message: 'rejected', detail: { errors: [{ field: 'items' }] } } };
  const okProbe = { op: 'createQuote', key: '', ms: 0, response: structuredClone(t.entries[first].response) };
  t.entries.splice(first, 0, soldOut, soldOut, okProbe, okProbe, okProbe, okProbe, okProbe);
  const { r, events } = await run(t);
  assert.equal(r.status, 'BRIEF_INFEASIBLE');
  assert.ok(!events.some((e) => e.event === 'checkout'));
});

test('the committed real Reap tape replays the 6-guest brief to the recorded order', async () => {
  const brief6 = JSON.parse(readFileSync(path.join(fixtures, 'demo-brief-6.json'), 'utf8'));
  const { done } = await startRun(brief6, { mode: 'replay', speed: 0, writeRecords: false });
  const r = await done;
  assert.equal(r.status, 'COMPLETED');
  assert.deepEqual(r.record, { budget: 105, quoted: 102.75, charged: 102.75, variance: 0, orderId: 'ord_01M4G0J3JSEP31G7NASSA0K649', currency: 'SGD' });
  const ev = r.events.map((e) => e.event);
  assert.ok(ev.indexOf('gate_blocked') < ev.indexOf('gate_passed'), 'first quote over budget, then passes');
  assert.ok(r.events.some((e) => e.event === 'item_removed' && e.data.why === 'sold_out'), 'sold-out beat present');
});

test('chef voice: event-built lines at each beat, once each, 6 s apart, dropped while busy', async () => {
  const { createChefVoice, words } = await import('../src/crewkit/chef-voice.mjs');
  assert.equal(words(105), 'a hundred and five');
  let t = 0;
  const lines = [];
  const voice = createChefVoice({ speak: async (l) => { lines.push(l); return true; }, now: () => t });
  const tick = async (ms) => { t += ms; await new Promise((r) => setImmediate(r)); };
  voice({ event: 'brief', data: { guests: Array(6).fill({ name: 'g' }), budget: SGD(105) } }); await tick(7000);
  voice({ event: 'item_added', data: { id: 'notebook', realName: 'Campap A5 Ruled Exercise Book 80 pages' } });
  voice({ event: 'gate_blocked', data: { over: SGD(4.5) } }); await tick(1000);
  voice({ event: 'gate_blocked', data: { over: SGD(2) } }); // second block: never repeated
  voice({ event: 'item_removed', data: { id: 'notebook', why: 'sold_out' } }); // within 6 s: dropped
  await tick(7000);
  voice({ event: 'checkout', data: {} }); await tick(7000);
  voice({ event: 'completed', data: {} }); await tick(0);
  assert.deepEqual(lines, ['Order in! Six guests, a hundred and five dollar budget.', 'We\'re five dollars over. Swapping to cheaper picks, nobody loses a seat.',
    'Scan to approve, I won\'t buy without you.', 'Order placed! Plating up.']);
  assert.ok(lines.every((l) => l.split(/\s+/).length <= 15));
  const busy = [];
  let release;
  const slow = createChefVoice({ speak: (l) => { busy.push(l); return new Promise((r) => { release = r; }); }, now: () => t, minGapMs: 0 });
  slow({ event: 'checkout', data: {} }); await tick(0);
  slow({ event: 'completed', data: {} }); await tick(0);
  release(true);
  assert.deepEqual(busy, ['Scan to approve, I won\'t buy without you.'], 'a line in flight drops the next');
  const sold = [];
  const s2 = createChefVoice({ speak: async (l) => { sold.push(l); return false; }, now: () => 0 });
  s2({ event: 'item_added', data: { id: 'pen', realName: 'Pilot G-2 Gel Pen 0.7mm Blue' } });
  s2({ event: 'item_removed', data: { id: 'pen', why: 'sold_out' } }); await tick(0);
  assert.deepEqual(sold, ['Pilot G-2 Gel Pen is sold out, grabbing another.'], 'no Chef connected: speak false is silently skipped');
});

test('chef voice runs from the runner on the real tape and CREWKIT_CHEF_VOICE=0 turns it off', async () => {
  const brief6 = JSON.parse(readFileSync(path.join(fixtures, 'demo-brief-6.json'), 'utf8'));
  const lines = [];
  const speak = async (l) => { lines.push(l); return true; };
  const r = await (await startRun(brief6, { mode: 'replay', speed: 0, writeRecords: false, speak, chefVoice: true })).done;
  assert.equal(r.status, 'COMPLETED');
  assert.ok(lines[0].startsWith('Order in! Six guests, a hundred and five dollar budget'), lines[0]);
  assert.ok(lines.length >= 1 && lines.length <= 5);
  const thrown = await (await startRun(brief6, { mode: 'replay', speed: 0, writeRecords: false, speak: () => { throw new Error('no chef'); }, chefVoice: true })).done;
  assert.equal(thrown.status, 'COMPLETED', 'a failing voice never stops the run');
  const saved = process.env.CREWKIT_CHEF_VOICE;
  process.env.CREWKIT_CHEF_VOICE = '0';
  try {
    const off = [];
    await (await startRun(brief6, { mode: 'replay', speed: 0, writeRecords: false, speak: async (l) => { off.push(l); return true; } })).done;
    assert.deepEqual(off, []);
  } finally {
    if (saved === undefined) delete process.env.CREWKIT_CHEF_VOICE; else process.env.CREWKIT_CHEF_VOICE = saved;
  }
});

test('edge 1: a search result without merchant.name maps to merchant unknown instead of throwing', () => {
  const opts = pickable([{ id: 'p1', name: 'Pen', previewVariant: { id: 'v1', price: SGD(1.5) } }], 'SGD', null);
  assert.equal(opts.length, 1);
  assert.equal(opts[0].merchant, 'unknown');
});

test('edge 2: getCheckout errors back off pollEveryMs each time and stop with POLL_ERROR after 10', async () => {
  const stream = createEventStream({ runId: 't', sinks: [] });
  const api = replayApi(tape, { speed: 0 });
  let polls = 0;
  api.getCheckout = async () => { polls += 1; throw Object.assign(new Error('boom'), { code: 'NETWORK' }); };
  const sleeps = [];
  const r = await runCrewkit({ brief, api, emit: stream.emit, enrollmentId: 'e', pollEveryMs: 7, sleep: async (ms) => { sleeps.push(ms); } });
  assert.equal(r.status, 'POLL_ERROR');
  assert.equal(polls, 10);
  assert.ok(sleeps.filter((ms) => ms === 7).length >= 10, 'every poll error sleeps pollEveryMs');
  assert.equal(names(stream.log).at(-1), 'failed');
});

test('edge 3: idempotent POSTs retry 429 with the same key but throw on 5xx; checkout 5xx forgets the persisted key', async () => {
  const stateFile = path.join(process.env.CREWKIT_RECORDS_DIR, 'state-edge3.json');
  const saved = { key: process.env.REAP_API_KEY, state: process.env.CREWKIT_STATE_FILE, fetch: globalThis.fetch };
  process.env.CREWKIT_STATE_FILE = stateFile;
  process.env.REAP_API_KEY = 'test-key-not-real'; // fetch is mocked below; nothing leaves the process
  const client = await import('../src/crewkit/reap-client.mjs');
  const seen = [];
  const replies = [];
  globalThis.fetch = async (url, init) => {
    seen.push(init.headers['Idempotency-Key']);
    const [status, body] = replies.shift();
    return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'retry-after': '0.001' } });
  };
  try {
    replies.push([429, { error: { code: 'RATE_LIMITED' } }], [200, { id: 'chk' }]);
    assert.equal((await client.createCheckout('q-a', 'enr')).id, 'chk');
    assert.equal(seen.length, 2);
    assert.equal(seen[0], seen[1], '429 retried with the same key');
    seen.length = 0;
    replies.push([503, { error: { code: 'UNAVAILABLE' } }], [200, { id: 'never' }]);
    await assert.rejects(client.createCheckout('q-b', 'enr'), (e) => e.status === 503);
    assert.equal(seen.length, 1, 'idempotent 5xx is not retried with the same key');
    assert.ok(!('q-b' in (JSON.parse(readFileSync(stateFile, 'utf8')).checkoutKeys || {})), 'checkout key forgotten after 5xx');
    replies.length = 0;
    replies.push([200, { id: 'chk2' }]);
    await client.createCheckout('q-b', 'enr');
    assert.notEqual(seen[1], seen[0], 'next checkout of the quote uses a fresh key');
  } finally {
    globalThis.fetch = saved.fetch;
    if (saved.key === undefined) delete process.env.REAP_API_KEY; else process.env.REAP_API_KEY = saved.key;
    if (saved.state === undefined) delete process.env.CREWKIT_STATE_FILE; else process.env.CREWKIT_STATE_FILE = saved.state;
  }
});

test('edge 6: COMPLETED without orderId or finalAmount fails COMPLETED_INCOMPLETE and records nothing', async () => {
  for (const field of ['orderId', 'finalAmount']) {
    const t = structuredClone(tape);
    const last = t.entries.findLastIndex((e) => e.op === 'getCheckout');
    delete t.entries[last].response[field];
    const { r, events } = await run(t);
    assert.equal(r.status, 'COMPLETED_INCOMPLETE', field);
    assert.ok(!names(events).includes('completed') && !names(events).includes('record'));
  }
});

test('edge 7: a live api with a non-ACTIVE enrollment fails ENROLLMENT_NOT_ACTIVE before any search', async () => {
  const stream = createEventStream({ runId: 't', sinks: [] });
  const api = replayApi(tape, { speed: 0 });
  let searched = 0;
  const search = api.search;
  api.search = (...a) => { searched += 1; return search(...a); };
  api.getEnrollment = async () => ({ status: 'PENDING' });
  const r = await runCrewkit({ brief, api, emit: stream.emit, enrollmentId: 'e', sleep: async () => {} });
  assert.equal(r.status, 'ENROLLMENT_NOT_ACTIVE');
  assert.equal(searched, 0);
  assert.equal(names(stream.log).at(-1), 'failed');
});
