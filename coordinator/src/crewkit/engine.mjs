// CrewKit run engine: brief -> search -> map -> quote -> budget gate (+ rework) -> checkout -> poll -> record.
// Emits contract events (docs/crewkit/CONTRACT.md) through `emit`. Never touches the API key or card data.
import { checkRequirements, validateQuoteItems, evaluateGate, planRework, quoteTotals, r2, toMoney } from './gate.mjs';
import { mapToMcItem } from './mapping.mjs';

const PER = new Set(['person', 'pair', 'room']);
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);
const REQUOTE_CODES = new Set(['QUOTE_EXPIRED', 'QUOTE_REPLACEMENT_REQUIRED']);
const defaultSleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Real sandbox stock: a line over its stock fails the whole quote (409 VARIANT_UNAVAILABLE, or
// 400 AGENTIC_REQUEST_REJECTED on field items).
const isStockError = (e) => e?.code === 'VARIANT_UNAVAILABLE'
  || (e?.code === 'AGENTIC_REQUEST_REJECTED' && (e.detail?.errors || []).some((x) => String(x?.field ?? '').startsWith('items')));
// Sandbox answers 503 AGENTIC_SERVICE_UNAVAILABLE / QUOTE_TEMPORARILY_UNAVAILABLE under bursts of merchant requests.
const BUSY = new Set(['AGENTIC_SERVICE_UNAVAILABLE', 'QUOTE_TEMPORARILY_UNAVAILABLE']);
const isBusy = (e) => BUSY.has(e?.code) || e?.status === 503;

// 'activity' narration helpers (docs/crewkit/ACTIVITY.md): text is capped at 40 chars.
const TEXT_MAX = 40;
const clip = (s, max = TEXT_MAX) => { s = String(s ?? ''); return s.length <= max ? s : `${s.slice(0, max - 3).trimEnd()}...`; };
const fit = (prefix, name, suffix = '') => `${prefix}${clip(name, Math.max(6, TEXT_MAX - prefix.length - suffix.length))}${suffix}`;
const money = (m) => `${m.currency === 'SGD' ? 'S$' : `${m.currency} `}${Number(m.amount).toFixed(2)}`;
const WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];
const outcomeOf = (e) => (isBusy(e) ? 'busy' : isStockError(e) ? 'sold_out' : 'error');

// Bounds on what one brief may ask for, so a bad or hostile brief cannot fan out into unbounded API calls or spend.
const LIMITS = { needs: 10, extras: 5, altQueries: 3, guests: 40, budget: 10000 };

export function normalizeBrief(raw) {
  if (!raw || typeof raw !== 'object') throw new TypeError('brief must be an object');
  const budget = { amount: Number(raw.budget?.amount), currency: String(raw.budget?.currency || '').toUpperCase() };
  if (!(budget.amount > 0) || !/^[A-Z]{3}$/.test(budget.currency)) throw new TypeError('brief.budget needs amount > 0 and a 3-letter currency');
  if (budget.amount > LIMITS.budget) throw new TypeError(`brief.budget.amount is capped at ${LIMITS.budget}`);
  if ((raw.needs || []).length > LIMITS.needs) throw new TypeError(`brief.needs is capped at ${LIMITS.needs} items`);
  if ((raw.extras || []).length > LIMITS.extras) throw new TypeError(`brief.extras is capped at ${LIMITS.extras} items`);
  const guests = (raw.guests || []).map((g) => (typeof g === 'string' ? { name: g, skin: null } : { name: String(g.name), skin: g.skin ?? null }));
  const guestCount = Number(raw.guestCount) || guests.length;
  if (!(guestCount > 0)) throw new TypeError('brief needs guests or guestCount');
  if (guestCount > LIMITS.guests || guests.length > LIMITS.guests) throw new TypeError(`brief is capped at ${LIMITS.guests} guests`);
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
    if ((need.altQueries || []).length > LIMITS.altQueries) throw new TypeError(`need ${need.id}: altQueries is capped at ${LIMITS.altQueries}`);
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
const MAX_POLL_ERRORS = 10;

export function pickable(products, currency, lockedMerchant) {
  return (products || []).filter((p) => {
    if (p.available === false || p.previewVariant?.available === false) return false;
    if (lockedMerchant && p.merchant?.name !== lockedMerchant) return false;
    const price = p.previewVariant?.price ?? p.priceRange?.min;
    if (price && typeof price === 'object' && price.currency && price.currency !== currency) return false;
    return toMoney(price, currency).amount > 0; // free gifts and unpriced listings are never part of a kit
  }).map((p) => ({
    productId: p.id, variantId: p.previewVariant?.id ?? null, realName: p.name, merchant: p.merchant?.name || 'unknown',
    unitPrice: toMoney(p.previewVariant?.price ?? p.priceRange?.min, currency),
  }));
}

// Decision visual: up to 5 real search results from the chosen product's merchant, chosen one included.
export function candidatesFor(query, searched, chosen, needLabel, max = 5) {
  const same = searched.filter((o) => o.merchant === chosen.merchant && o.productId !== chosen.productId);
  const at = Math.max(0, Math.min(searched.filter((o) => o.merchant === chosen.merchant).findIndex((o) => o.productId === chosen.productId), max - 1));
  const picks = same.slice(0, max - 1);
  picks.splice(Math.min(at, picks.length), 0, chosen);
  return {
    query,
    options: picks.map((o) => ({ realName: o.realName, mcItem: mapToMcItem({ productId: o.productId, productName: o.realName, needLabel }), price: o.unitPrice })),
    chosenIndex: picks.indexOf(chosen),
  };
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
export async function runCrewkit({ brief: rawBrief, api, emit, enrollmentId, pollEveryMs = 2500, pollTimeoutMs = 15 * 60_000, maxQuotes = 8, probeGapMs = 1000, sleep = defaultSleep, now = Date.now, log = () => {} }) {
  const brief = rawBrief.needs?.[0]?.queries ? rawBrief : normalizeBrief(rawBrief);
  const { budget, guestCount } = brief;
  const cur = budget.currency;
  let calls = 0;
  const call = async (op, ...args) => {
    try { return await api[op](...args); } finally { calls += 1; emit('calls', { count: calls }); }
  };
  // Narration only: activity events never change which Reap calls happen or the other events.
  const act = (kind, text, extra = {}) => emit('activity', { kind, text: clip(text), ...extra });
  const think = (text) => act('think', text, { result: 'ok' });
  const quoteVerdict = (totals, fields, label = 'Quote') => {
    if (totals.invalid) return [`${label}: unreadable`, { ...fields, result: 'error' }];
    const over = totals.total.amount > budget.amount;
    return [`${label}: ${money(totals.total)}${over ? ' (over)' : ''}`, { ...fields, result: over ? 'over' : 'ok', amount: totals.total }];
  };
  const result = { status: 'RUNNING', calls: 0, cart: [], quote: null, checkout: null, record: null };
  const fail = (event, status, reason) => {
    emit(event, { status, reason });
    Object.assign(result, { status, reason, calls });
    return result;
  };

  emit('reset', {});
  emit('brief', { guests: brief.guests, budget, needs: brief.needs.map(needText), title: brief.title });

  // Live and simulate only (replay has no getEnrollment): a checkout needs an ACTIVE enrollment.
  if (api.getEnrollment) {
    let en = null;
    try { en = await api.getEnrollment(enrollmentId); } catch (e) { log(`enrollment check ${e.code || e.message}`); }
    const st = en?.status ?? en?.enrollment?.status ?? 'UNKNOWN';
    if (st !== 'ACTIVE') return fail('failed', 'ENROLLMENT_NOT_ACTIVE', `Enrollment ${enrollmentId} is ${st}, not ACTIVE; finish enrollment before ordering`);
  }

  // 1. Search; pick Reap's top result per need and keep the other results as permitted substitutes.
  const names = brief.guests.map((g) => g.name);
  const required = (n) => qtyFor(n.per, guestCount, n.qty);
  let lockedMerchant = null;
  const resolveVariant = async (p) => {
    if (p.variantId) return p;
    const who = { realName: p.realName };
    act('details', fit('Reading label: ', p.realName), { ...who, result: 'pending' });
    let d;
    try { d = await call('details', [p.productId]); } catch (e) { act('details', fit('No label: ', p.realName), { ...who, result: outcomeOf(e) }); throw e; }
    const dv = d?.products?.[0]?.defaultVariant;
    const usable = Boolean(dv?.id) && dv.available !== false;
    act('details', fit(usable ? 'Got it: ' : 'Not on sale: ', p.realName), { ...who, result: usable ? 'ok' : 'sold_out' });
    if (!usable) return null;
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
    let usedQuery = need.queries[0];
    for (const query of need.queries) {
      act('search', fit('Searching: ', query), { item: need.id, result: 'pending' });
      let res;
      try { res = await call('search', query, { merchant: brief.merchant || undefined, mode: 'ONLY', limit: 10, country: brief.country, currency: cur }); }
      catch (e) { act('search', fit('Search failed: ', query), { item: need.id, result: outcomeOf(e) }); throw e; }
      options = pickable(res?.products, cur, lockedMerchant);
      act('search', options.length ? fit(`${options.length} hits: `, query) : fit('Nothing for: ', query), { item: need.id, result: 'ok' });
      usedQuery = query;
      if (options.length) break;
    }
    const searched = options.slice();
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
    emit('candidates', candidatesFor(usedQuery, searched, first, need.label));
    addLine(need, first, required(need), need.substitutes ? options : []);
  }

  think(`Cart has ${result.cart.length} lines. Pricing it up.`);

  // 2. Quote, gate, rework until the gate passes on a fresh read of the quote.
  let quotes = 0;
  let quote = null;
  let lastTotal = null;
  const handleBlocked = async (gate, totals) => {
    if (gate.code === 'QUOTE_EXPIRED') { log(`quote expired, re-quoting: ${gate.reason}`); think('Quote went stale. Re-quoting.'); return null; }
    if (gate.code === 'QUOTE_INVALID') {
      emit('gate_blocked', { over: { amount: 0, currency: cur }, reason: gate.reason, code: gate.code });
      return fail('failed', 'QUOTE_INVALID', gate.reason);
    }
    if (gate.code === 'CURRENCY_MISMATCH') {
      emit('gate_blocked', { over: { amount: 0, currency: cur }, reason: gate.reason, code: gate.code });
      return fail('failed', 'CURRENCY_MISMATCH', gate.reason);
    }
    emit('gate_blocked', { over: gate.over, reason: gate.reason, code: gate.code });
    if (gate.over) think(`Over by ${money(gate.over)}. Hunting cheaper picks.`);
    const plan = planRework({ cart: result.cart, total: totals.total.amount, budget });
    if (!plan.changes.length || !plan.fits) {
      think('No cheaper kit fits. Stopping here.');
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
      if (!to) { // substitute vanished; drop it so the next rework cannot pick it again
        item.alternates = item.alternates.filter((a) => a !== ch.to);
        continue;
      }
      const need = brief.needs.find((n) => n.id === item.needId);
      emit('item_removed', { id: item.id, qtyRemoved: item.qty, why: 'swap for a cheaper substitute' });
      const qty = item.qty;
      item.qty = 0;
      addLine(need, to, qty, item.alternates.filter((a) => a.productId !== to.productId));
    }
    think('Swapped in cheaper picks. Re-quoting.');
    return null;
  };

  // Real sandbox stock (see isStockError): find the line, swap that need to another product from
  // the same merchant that quotes at the full quantity, and show it in the kitchen as a sold-out beat.
  // Busy (see BUSY): the client already retried quickly, so back off longer before giving up.
  // say: { kind, text, fields, soldText, done(response, fields) } narrates each attempt as 'activity'.
  const quoteCall = async (items, merchants, say) => {
    for (let attempt = 0; ; attempt++) {
      const fields = { ...say.fields, ...(attempt ? { attempt: attempt + 1 } : {}) };
      act(say.kind, say.text, { ...fields, result: 'pending' });
      let response;
      try { response = await call('createQuote', items, merchants); }
      catch (e) {
        const outcome = outcomeOf(e);
        act(say.kind, outcome === 'sold_out' ? say.soldText : outcome === 'busy' ? 'Reap busy' : `Reap said ${e?.code || 'error'}`, { ...fields, result: outcome });
        if (!isBusy(e) || attempt >= 3) throw e;
        log(`Reap busy (${e.code}), waiting ${5 * (attempt + 1)}s`);
        act('backoff', `Reap busy, retry in ${5 * (attempt + 1)}s`, { result: 'busy', attempt: attempt + 1 });
        await sleep(5000 * (attempt + 1));
        continue;
      }
      say.done(response, fields);
      return response;
    }
  };
  // failedAt: smallest quantity at which a variant was refused, so no quantity is probed twice.
  const failedAt = new Map();
  const probe = async (p, quantity, needLabel) => {
    const { variantId, merchant } = p;
    const known = failedAt.get(variantId);
    if (known !== undefined && quantity >= known) return false;
    await sleep(probeGapMs); // space merchant requests; bursts get 503s in sandbox
    const fields = { realName: p.realName, mcItem: p.mcItem ?? mapToMcItem({ productId: p.productId, productName: p.realName, needLabel }), qty: quantity };
    const say = {
      kind: 'probe', fields,
      text: fit('Checking stock: ', p.realName, ` x${quantity}`),
      soldText: fit('Sold out: ', p.realName, ` x${quantity}`),
      done: (_, f) => act('probe', fit('In stock: ', p.realName, ` x${quantity}`), { ...f, result: 'ok' }),
    };
    try { await quoteCall([{ variantId, quantity }], [merchant], say); return true; }
    catch (e) {
      if (!isStockError(e)) throw e;
      failedAt.set(variantId, Math.min(known ?? Infinity, quantity));
      return false;
    }
  };
  const handleStock = async (err) => {
    const lines = result.cart.filter((c) => c.qty > 0);
    const indexed = (err.detail?.errors || []).map((x) => /items\[(\d+)\]/.exec(String(x?.field ?? ''))?.[1]).filter((i) => i !== undefined).map(Number);
    let bad = indexed.map((i) => lines[i]).filter(Boolean);
    if (!bad.length) think('Something is out. Checking each line.');
    if (!bad.length) for (const l of lines) if (!(await probe(l, l.qty, l.label))) bad.push(l);
    if (!bad.length) return fail('failed', err.code, `${err.message} (every line quotes on its own)`);
    for (const item of bad) {
      const need = brief.needs.find((n) => n.id === item.needId);
      const qty = item.qty;
      const alts = [];
      for (const a of item.alternates) { const c = await resolveVariant(a); if (c) alts.push(c); }
      // 1. one other product that has the whole quantity
      let pieces = null;
      for (const c of alts) if (await probe(c, qty, need.label)) { pieces = [[c, qty]]; break; }
      // 2. otherwise split the quantity across products (this one included), largest piece first
      if (!pieces) {
        const self = { productId: item.productId, variantId: item.variantId, realName: item.realName, merchant: item.merchant, unitPrice: item.unitPrice };
        let remaining = qty;
        pieces = [];
        for (const c of [self, ...alts]) {
          if (remaining === 0) break;
          for (const q of [...new Set([remaining, Math.ceil(remaining / 2), 1])]) {
            if (q <= remaining && await probe(c, q, need.label)) { pieces.push([c, q]); remaining -= q; break; }
          }
        }
        if (remaining > 0) pieces = null;
      }
      log(`${item.realName} is sold out at qty ${qty}${pieces ? `; now ${pieces.map(([c, q]) => `${q} x ${c.realName}`).join(' + ')}` : ''}`);
      const plan = !pieces ? 'Not enough stock.' : pieces.length > 1 ? `Splitting ${pieces.map(([, q]) => q).join('+')}.` : 'Swapping it.';
      think(fit('', need.label, ` sold out at ${qty}. ${plan}`));
      emit('item_removed', { id: item.id, qtyRemoved: qty, why: 'sold_out' });
      item.qty = 0;
      if (pieces) {
        const used = new Set(pieces.map(([c]) => c.productId));
        for (const [c, q] of pieces) addLine(need, c, q, item.alternates.filter((a) => !used.has(a.productId)));
        continue;
      }
      if (need.optional) continue; // an extra may go; a mandatory need may not
      return fail('failed', 'BRIEF_INFEASIBLE', `${need.label}: not enough stock at ${item.merchant} for ${qty}`);
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
    const say = {
      kind: 'quote', fields: {},
      text: `${quotes > 1 ? 'Re-quoting' : 'Quoting'} cart (${lines.length} lines)`,
      soldText: 'Quote refused: out of stock',
      done: (q, f) => act('quote', ...quoteVerdict(quoteTotals(q, cur), f)),
    };
    try {
      quote = await quoteCall(items, lines.map((c) => c.merchant), say);
    } catch (e) {
      if (REQUOTE_CODES.has(e.code)) continue;
      if (isStockError(e)) { const stop = await handleStock(e); if (stop) return stop; continue; }
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    let totals = quoteTotals(quote, cur);
    if (!totals.invalid) emit('quote', { total: totals.total, budgetRemaining: { amount: r2(budget.amount - totals.total.amount), currency: cur }, shipping: totals.shipping, quoteId: totals.quoteId, expiresAt: totals.expiresAt });
    lastTotal = totals.total;
    let gate = evaluateGate({ totals, budget, now: now() });
    if (!gate.ok) { const stop = await handleBlocked(gate, totals); if (stop) return stop; continue; }

    // Re-read right before checkout: shipping-inclusive total, currency, expiry.
    act('quote', 'Double-checking the total', { result: 'pending' });
    try {
      const fresh = await call('getQuote', quote.id);
      // Gate the fresh read on its own; never fill its gaps from the earlier quote.
      totals = quoteTotals(fresh && typeof fresh === 'object' ? { ...fresh, id: fresh.id ?? quote.id } : fresh, cur);
    } catch (e) {
      act('quote', `Recheck failed: ${e?.code || 'error'}`, { result: outcomeOf(e) });
      if (REQUOTE_CODES.has(e.code)) continue;
      return fail('failed', e.code || 'QUOTE_ERROR', e.message);
    }
    act('quote', ...quoteVerdict(totals, {}, 'Confirmed'));
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
    const covered = new Set(result.cart.filter((c) => c.qty > 0).map((c) => c.needId)).size;
    think(`All ${WORDS[covered] ?? covered} covered. Asking for approval.`);

    act('quote', 'Opening checkout', { result: 'pending' });
    try {
      checkout = await call('createCheckout', quote.id, enrollmentId);
      act('quote', 'Checkout open', { result: 'ok', amount: toMoney(checkout?.amount, cur) });
    } catch (e) {
      act('quote', `Checkout failed: ${e?.code || 'error'}`, { result: outcomeOf(e) });
      if (REQUOTE_CODES.has(e.code)) { log(`checkout said ${e.code}, re-quoting`); continue; }
      return fail('failed', e.code || 'CHECKOUT_ERROR', e.message);
    }
  }

  // 3. Hosted approval, then poll every pollEveryMs until terminal.
  const approvalUrl = checkout.nextAction?.url ?? null;
  emit('checkout', { approvalUrl, status: checkout.status, checkoutId: checkout.id, amount: toMoney(checkout.amount, cur) });
  result.checkout = { id: checkout.id }; // the approval URL goes only to the mod via emit
  let status = checkout.status;
  let latest = checkout;
  const started = now();
  let pollErrors = 0;
  for (;;) {
    if (TERMINAL.has(status) && latest.orderId !== undefined) break;
    if (now() - started > pollTimeoutMs) return fail('expired', 'POLL_TIMEOUT', `No terminal status after ${Math.round(pollTimeoutMs / 1000)}s`);
    if (!TERMINAL.has(status)) await sleep(pollEveryMs);
    act('details', 'Checking for approval', { result: 'pending' });
    try {
      latest = await call('getCheckout', checkout.id);
      pollErrors = 0;
    } catch (e) {
      act('details', `Status check failed: ${e?.code || 'error'}`, { result: outcomeOf(e) });
      log(`poll error ${e.code || e.message}`);
      if (++pollErrors >= MAX_POLL_ERRORS) return fail('failed', 'POLL_ERROR', `Checkout status unreadable after ${pollErrors} attempts: ${e.code || e.message}`);
      await sleep(pollEveryMs); // a terminal status skips the top-of-loop sleep, so back off here too
      continue;
    }
    act('details', latest.status === status ? 'Still waiting for approval' : fit('Status: ', String(latest.status)), { result: 'ok' });
    if (latest.status !== status) {
      status = latest.status;
      if (!TERMINAL.has(status)) emit('checkout', { approvalUrl: latest.nextAction?.url ?? approvalUrl, status, checkoutId: checkout.id });
    }
    if (TERMINAL.has(status)) break;
  }

  if (status === 'FAILED') return fail('failed', 'FAILED', 'Reap: the charge or the merchant order did not go through');
  if (status === 'EXPIRED') return fail('expired', 'EXPIRED', 'Approval was not given before the link expired');

  // COMPLETED must carry the order and the real charge; never record the pre-approval amount as charged.
  if (latest.orderId == null || latest.finalAmount == null) {
    return fail('failed', 'COMPLETED_INCOMPLETE', `Reap reported COMPLETED without ${latest.orderId == null ? 'an orderId' : 'a finalAmount'}`);
  }
  const finalAmount = toMoney(latest.finalAmount, cur);
  emit('completed', { orderId: latest.orderId, finalAmount });
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
