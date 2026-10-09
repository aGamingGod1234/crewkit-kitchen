// CrewKit run engine: brief -> search -> map -> quote -> budget gate (+ rework) -> checkout -> poll -> record.
// Emits contract events (docs/crewkit/CONTRACT.md) through `emit`. Never touches the API key or card data.
import { checkRequirements, validateQuoteItems, evaluateGate, planRework, quoteTotals, r2, toMoney } from './gate.mjs';
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
  // needs = mandatory quantities; extras = optional add-ons the rework may drop.
  const all = [...(raw.needs || []), ...(raw.extras || []).map((e) => (typeof e === 'string' ? { label: e, optional: true } : { ...e, optional: true }))];
  const needs = all.map((n, i) => {
    const need = typeof n === 'string' ? { label: n } : { ...n };
    need.optional = Boolean(need.optional);
    need.substitutes = need.substitutes !== false; // permitted: a cheaper product from the same merchant
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

const needText = (n) => `${n.label} (${n.per === 'person' ? 'each' : n.per === 'pair' ? 'one per pair' : 'one for the room'}${n.optional ? ', optional extra' : ''})`;

// Every available, priced product in the brief currency, in Reap's relevance order.
// Reap reports the display name ("Popular Bookstore"), not the domain we search with ("popular.com.sg"),
// so the one-merchant rule locks onto the merchant name of the first product picked.
function pickable(products, currency, lockedMerchant) {
  return (products || []).filter((p) => {
    if (p.available === false || p.previewVariant?.available === false) return false;
    if (lockedMerchant && p.merchant?.name !== lockedMerchant) return false;
    const price = p.previewVariant?.price ?? p.priceRange?.min;
    if (price && typeof price === 'object' && price.currency && price.currency !== currency) return false;
    return toMoney(price, currency).amount > 0; // free gifts and unpriced listings are never part of a kit
  }).map((p) => ({
    productId: p.id, variantId: p.previewVariant?.id ?? null, realName: p.name, merchant: p.merchant?.name || merchant || 'unknown',
    unitPrice: toMoney(p.previewVariant?.price ?? p.priceRange?.min, currency),
  }));
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

  // 1. Search; pick Reap's top result per need and keep the other results as permitted substitutes.
  const names = brief.guests.map((g) => g.name);
  const required = (n) => qtyFor(n.per, guestCount, n.qty);
  let lockedMerchant = null;
  const resolveVariant = async (p) => {
    if (p.variantId) return p;
    const d = await call('details', [p.productId]);
    const dv = d?.products?.[0]?.defaultVariant;
    if (!dv?.id || dv.available === false) return null;
    return { ...p, variantId: dv.id, unitPrice: dv.price ? toMoney(dv.price, cur) : p.unitPrice };
  };
  const addLine = (need, p, qty, alternates) => {
    const item = {
      id: result.cart.some((c) => c.needId === need.id) ? `${need.id}-alt${result.cart.filter((c) => c.needId === need.id).length}` : need.id,
      needId: need.id, label: need.label, productId: p.productId, variantId: p.variantId,
      realName: p.realName, merchant: p.merchant,
      mcItem: mapToMcItem({ productId: p.productId, productName: p.realName, needLabel: need.label }),
      unitPrice: p.unitPrice, qty, per: need.per, optional: need.optional, substitutes: need.substitutes, alternates,
    };
    result.cart.push(item);
    emit('item_added', {
      id: item.id, needId: need.id, realName: item.realName, merchant: item.merchant, mcItem: item.mcItem,
      unitPrice: item.unitPrice, qty: item.qty, seats: item.per === 'room' ? [] : names, per: item.per, optional: item.optional,
    });
    return item;
  };
  for (const need of brief.needs) {
    let options = [];
    for (const query of need.queries) {
      const res = await call('search', query, { merchant: brief.merchant || undefined, mode: 'ONLY', limit: 10, country: brief.country, currency: cur });
      options = pickable(res?.products, cur, lockedMerchant);
      if (options.length) break;
    }
    let first = null;
    while (options.length && !first) first = await resolveVariant(options.shift());
    if (first) lockedMerchant ??= first.merchant;
    // A permitted substitute must still be the same kind of thing: its name shares a word with the need.
    const words = [need.label, ...need.queries].join(' ').toLowerCase().match(/[a-z0-9]{3,}/g) || [];
    options = options.filter((o) => words.some((w) => o.realName.toLowerCase().includes(w)));
    if (!first) {
      if (need.optional) { log(`no product for optional extra ${need.id}, skipped`); continue; }
      return fail('failed', 'BRIEF_INFEASIBLE', `No available ${cur} product for "${need.label}" at ${brief.merchant || 'any merchant'}`);
    }
    addLine(need, first, required(need), need.substitutes ? options : []);
  }

  // 2. Quote, gate, rework until the gate passes on a fresh read of the quote.
  let quotes = 0;
  let quote = null;
  let lastTotal = null;
  const handleBlocked = async (gate, totals) => {
    if (gate.code === 'QUOTE_EXPIRED') { log(`quote expired, re-quoting: ${gate.reason}`); return null; }
    if (gate.code === 'QUOTE_INVALID') {
      emit('gate_blocked', { over: { amount: 0, currency: cur }, reason: gate.reason, code: gate.code });
      return fail('failed', 'QUOTE_INVALID', gate.reason);
    }
    if (gate.code === 'CURRENCY_MISMATCH') {
      emit('gate_blocked', { over: { amount: 0, currency: cur }, reason: gate.reason, code: gate.code });
      return fail('failed', 'CURRENCY_MISMATCH', gate.reason);
    }
    emit('gate_blocked', { over: gate.over, reason: gate.reason, code: gate.code });
    const plan = planRework({ cart: result.cart, total: totals.total.amount, budget });
    if (!plan.changes.length || !plan.fits) {
      return fail('failed', 'BRIEF_INFEASIBLE', `${gate.reason}. Cheapest permitted kit is about ${plan.estimatedTotal} ${cur}; change the brief (budget, items or extras)`);
    }
    for (const ch of plan.changes) {
      const item = result.cart.find((c) => c.id === ch.id);
      if (ch.type === 'drop') {
        emit('item_removed', { id: item.id, qtyRemoved: item.qty, why: 'drop optional extra' });
        item.qty = 0;
        continue;
      }
      const to = await resolveVariant(ch.to);
      if (!to) continue; // substitute vanished; the next quote and gate decide
      const need = brief.needs.find((n) => n.id === item.needId);
      emit('item_removed', { id: item.id, qtyRemoved: item.qty, why: 'swap for a cheaper substitute' });
      const qty = item.qty;
      item.qty = 0;
      addLine(need, to, qty, item.alternates.filter((a) => a.productId !== to.productId));
    }
    return null;
  };

  let checkout = null;
  while (!checkout) {
    if (quotes >= maxQuotes) return fail('failed', 'QUOTE_LIMIT', `Gave up after ${maxQuotes} quotes`);
    quotes += 1;
    const lines = result.cart.filter((c) => c.qty > 0);
    const items = lines.map((c) => ({ variantId: c.variantId, quantity: c.qty }));
    try { validateQuoteItems(items, lines.map((c) => c.merchant)); } catch (e) { return fail('failed', e.code, e.message); }
    try {
      quote = await call('createQuote', items, lines.map((c) => c.merchant));
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) continue;
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    let totals = quoteTotals(quote, cur);
    if (!totals.invalid) emit('quote', { total: totals.total, budgetRemaining: { amount: r2(budget.amount - totals.total.amount), currency: cur }, shipping: totals.shipping, quoteId: totals.quoteId, expiresAt: totals.expiresAt });
    lastTotal = totals.total;
    let gate = evaluateGate({ totals, budget, now: now() });
    if (!gate.ok) { const stop = await handleBlocked(gate, totals); if (stop) return stop; continue; }

    // Re-read right before checkout: shipping-inclusive total, currency, expiry.
    try {
      const fresh = await call('getQuote', quote.id);
      // Gate the fresh read on its own; never fill its gaps from the earlier quote.
      totals = quoteTotals(fresh && typeof fresh === 'object' ? { ...fresh, id: fresh.id ?? quote.id } : fresh, cur);
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) continue;
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    if (totals.total.amount !== lastTotal.amount) {
      if (!totals.invalid) emit('quote', { total: totals.total, budgetRemaining: { amount: r2(budget.amount - totals.total.amount), currency: cur }, shipping: totals.shipping, quoteId: totals.quoteId, expiresAt: totals.expiresAt });
      lastTotal = totals.total;
    }
    gate = evaluateGate({ totals, budget, now: now() });
    if (!gate.ok) { const stop = await handleBlocked(gate, totals); if (stop) return stop; continue; }
    // Requirements check next to the budget gate: every mandatory quantity must be covered.
    const reqs = checkRequirements({ needs: brief.needs, cart: result.cart, required });
    emit('requirements', reqs);
    if (!reqs.ok) return fail('failed', 'REQUIREMENTS_UNMET', `Missing ${reqs.missing.map((m) => `${m.qty} x ${m.need}`).join(', ')}`);
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
