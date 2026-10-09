// CrewKit run engine: brief -> search -> map -> quote -> budget gate (+ rework) -> checkout -> poll -> record.
// Emits contract events (docs/crewkit/CONTRACT.md) through `emit`. Never touches the API key or card data.
import { evaluateGate, planRework, quoteTotals, r2, toMoney } from './gate.mjs';
import { mapToMcItem } from './mapping.mjs';

const PER = new Set(['person', 'pair', 'room']);
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);
const REQUOTE_CODES = new Set(['QUOTE_EXPIRED', 'QUOTE_REPLACEMENT_REQUIRED']);
const defaultSleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function normalizeBrief(raw) {
  if (!raw || typeof raw !== 'object') throw new TypeError('brief must be an object');
  const budget = { amount: Number(raw.budget?.amount), currency: String(raw.budget?.currency || '').toUpperCase() };
  if (!(budget.amount > 0) || !/^[A-Z]{3}$/.test(budget.currency)) throw new TypeError('brief.budget needs amount > 0 and a 3-letter currency');
  const guests = (raw.guests || []).map((g) => (typeof g === 'string' ? { name: g, skin: null } : { name: String(g.name), skin: g.skin ?? null }));
  const guestCount = Number(raw.guestCount) || guests.length;
  if (!(guestCount > 0)) throw new TypeError('brief needs guests or guestCount');
  const needs = (raw.needs || []).map((n, i) => {
    const need = typeof n === 'string' ? { label: n } : { ...n };
    need.label = String(need.label || need.query || `item ${i + 1}`);
    need.id = String(need.id || need.label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''));
    need.per = need.per || 'person';
    if (!PER.has(need.per)) throw new TypeError(`need ${need.id}: per must be person, pair or room`);
    need.queries = [need.query || need.label, ...(need.altQueries || [])];
    return need;
  });
  if (!needs.length) throw new TypeError('brief.needs is empty');
  return {
    title: String(raw.title || 'CrewKit order'),
    guests, guestCount, budget, needs,
    merchant: raw.merchant === undefined ? 'popular.com.sg' : raw.merchant,
    country: raw.country || 'SG',
    enrollmentId: raw.enrollmentId || null,
  };
}

export const qtyFor = (per, guestCount, explicit) =>
  explicit > 0 ? explicit : per === 'person' ? guestCount : per === 'pair' ? Math.ceil(guestCount / 2) : 1;

const needText = (n) => `${n.label} (${n.per === 'person' ? (n.shareAs === 'pair' ? 'each, or one per pair' : 'each') : n.per === 'pair' ? 'one per pair' : 'one for the room'})`;

function pickProduct(products, currency) {
  for (const p of products || []) {
    if (p.available === false || p.previewVariant?.available === false) continue;
    const price = p.previewVariant?.price ?? p.priceRange?.min;
    if (price && typeof price === 'object' && price.currency && price.currency !== currency) continue;
    return p;
  }
  return null;
}

export class RunFailed extends Error {
  constructor(status, reason) { super(`${status}: ${reason}`); this.status = status; this.reason = reason; }
}

/**
 * @param {object} o
 * @param {object} o.brief normalized or raw brief
 * @param {object} o.api adapter from tape.mjs (search, details, createQuote, getQuote, createCheckout, getCheckout)
 * @param {(event:string, data:object)=>void} o.emit
 * @param {string} o.enrollmentId
 */
export async function runCrewkit({ brief: rawBrief, api, emit, enrollmentId, pollEveryMs = 2500, pollTimeoutMs = 15 * 60_000, maxQuotes = 4, sleep = defaultSleep, now = Date.now, log = () => {} }) {
  const brief = rawBrief.needs?.[0]?.queries ? rawBrief : normalizeBrief(rawBrief);
  const { budget, guestCount } = brief;
  const cur = budget.currency;
  let calls = 0;
  const call = async (op, ...args) => {
    try { return await api[op](...args); } finally { calls += 1; emit('calls', { count: calls }); }
  };
  const result = { status: 'RUNNING', calls: 0, cart: [], quote: null, checkout: null, record: null };
  const fail = (event, status, reason) => {
    emit(event, { status, reason });
    Object.assign(result, { status, reason, calls });
    return result;
  };

  emit('reset', {});
  emit('brief', { guests: brief.guests, budget, needs: brief.needs.map(needText), title: brief.title });

  // 1. Search and pick one product per need from the one merchant.
  const names = brief.guests.map((g) => g.name);
  for (const need of brief.needs) {
    let product = null;
    for (const query of need.queries) {
      const res = await call('search', query, { merchant: brief.merchant || undefined, mode: 'ONLY', limit: 10, country: brief.country, currency: cur });
      product = pickProduct(res?.products, cur);
      if (product) break;
    }
    if (!product) return fail('failed', 'NO_PRODUCT', `No available ${cur} product for "${need.label}" at ${brief.merchant || 'any merchant'}`);
    let variantId = product.previewVariant?.id;
    let price = product.previewVariant?.price ?? product.priceRange?.min;
    if (!variantId) {
      const d = await call('details', [product.id]);
      const dv = d?.products?.[0]?.defaultVariant;
      if (!dv?.id || dv.available === false) return fail('failed', 'NO_VARIANT', `No available variant for ${product.name}`);
      variantId = dv.id; price = dv.price ?? price;
    }
    const item = {
      id: need.id, label: need.label, productId: product.id, variantId,
      realName: product.name, merchant: product.merchant?.name || brief.merchant || 'unknown',
      mcItem: mapToMcItem({ productId: product.id, productName: product.name, needLabel: need.label }),
      unitPrice: toMoney(price, cur), qty: qtyFor(need.per, guestCount, need.qty),
      per: need.per, shareAs: need.shareAs || null, optional: Boolean(need.optional),
    };
    result.cart.push(item);
    emit('item_added', {
      id: item.id, realName: item.realName, merchant: item.merchant, mcItem: item.mcItem,
      unitPrice: item.unitPrice, qty: item.qty, seats: item.per === 'room' ? [] : names, per: item.per,
    });
  }

  // 2. Quote, gate, rework until the gate passes on a fresh read of the quote.
  let quotes = 0;
  let quote = null;
  let lastTotal = null;
  const handleBlocked = (gate, totals) => {
    if (gate.code === 'QUOTE_EXPIRED') { log(`quote expired, re-quoting: ${gate.reason}`); return null; }
    if (gate.code === 'CURRENCY_MISMATCH') {
      emit('gate_blocked', { over: { amount: 0, currency: cur }, reason: gate.reason, code: gate.code });
      return fail('failed', 'CURRENCY_MISMATCH', gate.reason);
    }
    emit('gate_blocked', { over: gate.over, reason: gate.reason, code: gate.code });
    const plan = planRework({ cart: result.cart, total: totals.total.amount, budget, guestCount });
    if (!plan.changes.length) return fail('failed', 'OVER_BUDGET', `${gate.reason}; nothing left to share or drop`);
    for (const ch of plan.changes) {
      const item = result.cart.find((c) => c.id === ch.id);
      item.qty = ch.newQty;
      if (ch.newPer) item.per = ch.newPer;
      emit('item_removed', { id: ch.id, qtyRemoved: ch.qtyRemoved, newQty: ch.newQty, why: ch.why });
    }
    return null;
  };

  let checkout = null;
  while (!checkout) {
    if (quotes >= maxQuotes) return fail('failed', 'QUOTE_LIMIT', `Gave up after ${maxQuotes} quotes`);
    quotes += 1;
    const items = result.cart.filter((c) => c.qty > 0).map((c) => ({ variantId: c.variantId, quantity: c.qty }));
    try {
      quote = await call('createQuote', items);
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) continue;
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    let totals = quoteTotals(quote, cur);
    emit('quote', { total: totals.total, budgetRemaining: { amount: r2(budget.amount - totals.total.amount), currency: cur }, shipping: totals.shipping, quoteId: totals.quoteId, expiresAt: totals.expiresAt });
    lastTotal = totals.total;
    let gate = evaluateGate({ totals, budget, now: now() });
    if (!gate.ok) { const stop = handleBlocked(gate, totals); if (stop) return stop; continue; }

    // Re-read right before checkout: shipping-inclusive total, currency, expiry.
    try {
      const fresh = await call('getQuote', quote.id);
      totals = quoteTotals({ ...quote, ...fresh, id: fresh?.id ?? quote.id }, cur);
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) continue;
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    if (totals.total.amount !== lastTotal.amount) {
      emit('quote', { total: totals.total, budgetRemaining: { amount: r2(budget.amount - totals.total.amount), currency: cur }, shipping: totals.shipping, quoteId: totals.quoteId, expiresAt: totals.expiresAt });
      lastTotal = totals.total;
    }
    gate = evaluateGate({ totals, budget, now: now() });
    if (!gate.ok) { const stop = handleBlocked(gate, totals); if (stop) return stop; continue; }
    emit('gate_passed', { total: totals.total });
    result.quote = { id: quote.id, total: totals.total, shipping: totals.shipping, subtotal: totals.subtotal };

    try {
      checkout = await call('createCheckout', quote.id, enrollmentId);
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) { log(`checkout said ${e.code}, re-quoting`); continue; }
      return fail('failed', e.code || 'CHECKOUT_ERROR', e.message);
    }
  }

  // 3. Hosted approval, then poll every pollEveryMs until terminal.
  const approvalUrl = checkout.nextAction?.url ?? null;
  emit('checkout', { approvalUrl, status: checkout.status, checkoutId: checkout.id, amount: toMoney(checkout.amount, cur) });
  result.checkout = { id: checkout.id, approvalUrl };
  let status = checkout.status;
  let latest = checkout;
  const started = now();
  for (;;) {
    if (TERMINAL.has(status) && latest.orderId !== undefined) break;
    if (now() - started > pollTimeoutMs) return fail('expired', 'POLL_TIMEOUT', `No terminal status after ${Math.round(pollTimeoutMs / 1000)}s`);
    if (!TERMINAL.has(status)) await sleep(pollEveryMs);
    try {
      latest = await call('getCheckout', checkout.id);
    } catch (e) {
      log(`poll error ${e.code || e.message}`);
      continue;
    }
    if (latest.status !== status) {
      status = latest.status;
      if (!TERMINAL.has(status)) emit('checkout', { approvalUrl: latest.nextAction?.url ?? approvalUrl, status, checkoutId: checkout.id });
    }
    if (TERMINAL.has(status)) break;
  }

  if (status === 'FAILED') return fail('failed', 'FAILED', 'Reap: the charge or the merchant order did not go through');
  if (status === 'EXPIRED') return fail('expired', 'EXPIRED', 'Approval was not given before the link expired');

  const finalAmount = toMoney(latest.finalAmount ?? checkout.amount, cur);
  emit('completed', { orderId: latest.orderId ?? null, finalAmount });
  const record = {
    budget: budget.amount,
    quoted: result.quote.total.amount,
    charged: finalAmount.amount,
    variance: r2(finalAmount.amount - result.quote.total.amount),
    orderId: latest.orderId ?? null,
    currency: cur,
  };
  emit('record', record);
  Object.assign(result, { status: 'COMPLETED', record, calls, orderId: record.orderId, checkout: { ...result.checkout, status } });
  return result;
}
