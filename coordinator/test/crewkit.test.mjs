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
const tape = JSON.parse(readFileSync(path.join(fixtures, 'demo-popular-sg.tape.json'), 'utf8'));

const { evaluateGate, planRework, quoteTotals } = await import('../src/crewkit/gate.mjs');
const { mapToMcItem } = await import('../src/crewkit/mapping.mjs');
const { startRun } = await import('../src/crewkit/runner.mjs');
const { runCrewkit } = await import('../src/crewkit/engine.mjs');
const { replayApi, recordingApi } = await import('../src/crewkit/tape.mjs');
const { createEventStream } = await import('../src/crewkit/events.mjs');

const SGD = (amount) => ({ amount, currency: 'SGD' });
const budget = SGD(150);
const future = new Date(Date.now() + 10 * 60_000).toISOString();
const totals = (amount, extra = {}) => ({ quoteId: 'q', total: SGD(amount), shipping: SGD(4), subtotal: SGD(amount - 4), expiresAt: future, ...extra });
const names = (events) => events.map((e) => e.event).filter((e) => e !== 'calls');

test('gate passes at exactly the budget and blocks one cent over, shipping included', () => {
  assert.deepEqual(evaluateGate({ totals: totals(150), budget }), { ok: true });
  const g = evaluateGate({ totals: totals(150.01), budget });
  assert.equal(g.code, 'OVER_BUDGET');
  assert.deepEqual(g.over, SGD(0.01));
  const q = quoteTotals({ id: 'x', amountBreakdown: { itemsSubtotal: SGD(147), shipping: SGD(4), finalAmount: SGD(151) }, expiresAt: future }, 'SGD');
  assert.equal(evaluateGate({ totals: q, budget }).code, 'OVER_BUDGET', 'gate uses finalAmount, not the item subtotal');
});

test('gate blocks a currency mismatch and an expired or nearly expired quote', () => {
  assert.equal(evaluateGate({ totals: { ...totals(10), total: { amount: 10, currency: 'USD' } }, budget }).code, 'CURRENCY_MISMATCH');
  assert.equal(evaluateGate({ totals: totals(10, { expiresAt: new Date(Date.now() - 1000).toISOString() }), budget }).code, 'QUOTE_EXPIRED');
  assert.equal(evaluateGate({ totals: totals(10, { expiresAt: new Date(Date.now() + 5000).toISOString() }), budget }).code, 'QUOTE_EXPIRED');
});

test('rework shares per-person items per pair first, then drops optional items', () => {
  const cart = [
    { id: 'cable', per: 'person', shareAs: 'pair', qty: 12, unitPrice: SGD(7.9) },
    { id: 'pen', per: 'person', qty: 12, unitPrice: SGD(1.2) },
    { id: 'stickers', per: 'room', optional: true, qty: 1, unitPrice: SGD(20) },
  ];
  const a = planRework({ cart, total: 194.9, budget, guestCount: 12 });
  assert.deepEqual(a.changes.map((c) => [c.id, c.qtyRemoved, c.newQty]), [['cable', 6, 6]]);
  assert.equal(a.fits, true);
  const b = planRework({ cart, total: 210, budget, guestCount: 12 });
  assert.deepEqual(b.changes.map((c) => c.id), ['cable', 'stickers']);
  const c = planRework({ cart: [cart[1]], total: 300, budget, guestCount: 12 });
  assert.equal(c.changes.length, 0);
});

test('product names map to allowlisted vanilla items', () => {
  assert.equal(mapToMcItem({ productName: 'Deli Name Badge Holder with Lanyard' }), 'minecraft:name_tag');
  assert.equal(mapToMcItem({ productName: 'Pilot G-2 Gel Pen 0.7mm' }), 'minecraft:feather');
  assert.equal(mapToMcItem({ productName: 'Type-C Fast Charging Cable 1m' }), 'minecraft:lead');
  assert.equal(mapToMcItem({ productName: 'Pokka Jasmine Green Tea 500ml' }), 'minecraft:honey_bottle');
  assert.equal(mapToMcItem({ productName: 'Mini Portable Bluetooth Speaker' }), 'minecraft:note_block');
  assert.equal(mapToMcItem({ productName: 'Open Day Thing', needLabel: 'unknown' }), 'minecraft:paper', 'no "pen" match inside "Open"; falls back to paper');
});

const EXPECTED = ['reset', 'brief', 'item_added', 'item_added', 'item_added', 'item_added', 'item_added', 'item_added',
  'quote', 'gate_blocked', 'item_removed', 'quote', 'gate_passed', 'checkout', 'checkout', 'completed', 'record'];

test('replay emits the full contract sequence with one runId and gap-free seq', async () => {
  const sent = [];
  const { runId, done } = await startRun(brief, { mode: 'replay', speed: 0, sinks: [(p) => sent.push(p)] });
  const r = await done;
  assert.equal(r.status, 'COMPLETED');
  assert.deepEqual(names(sent), EXPECTED);
  assert.ok(sent.every((p) => p.runId === runId));
  assert.deepEqual(sent.map((p) => p.seq), sent.map((_, i) => i + 1));
  const calls = sent.filter((p) => p.event === 'calls').map((p) => p.data.count);
  assert.deepEqual(calls, calls.map((_, i) => i + 1));
  assert.equal(calls.at(-1), r.calls);
  const blocked = sent.find((p) => p.event === 'gate_blocked').data;
  assert.deepEqual(blocked.over, SGD(44.9));
  assert.deepEqual(sent.find((p) => p.event === 'item_removed').data.qtyRemoved, 6);
  assert.deepEqual(sent.find((p) => p.event === 'record').data, { budget: 150, quoted: 147.5, charged: 147.5, variance: 0, orderId: 'POP-SG-1048213', currency: 'SGD' });
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
  const { done } = await startRun(brief, { mode: 'replay', speed: 0, writeRecords: false });
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
