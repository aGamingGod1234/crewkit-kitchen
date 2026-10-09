// Server-side budget gate. Pure functions so the rules are testable without Reap.
// The model never decides whether money moves; this code does.

export const r2 = (n) => Math.round(Number(n) * 100) / 100;

// Reap money is { amount, currency } in major units, but tolerate bare numbers/strings.
export function toMoney(value, fallbackCurrency) {
  if (value === null || value === undefined) return { amount: 0, currency: fallbackCurrency };
  if (typeof value === 'number' || typeof value === 'string') return { amount: r2(value), currency: fallbackCurrency };
  if (typeof value === 'object' && 'amount' in value) return { amount: r2(value.amount), currency: value.currency || fallbackCurrency };
  return { amount: 0, currency: fallbackCurrency };
}

// Normalise a Reap quote into the totals the gate and events need.
export function quoteTotals(quote, currency) {
  const b = quote?.amountBreakdown || {};
  const total = toMoney(b.finalAmount, currency);
  const shipping = toMoney(b.shipping, total.currency);
  const subtotal = toMoney(b.itemsSubtotal, total.currency);
  return { quoteId: quote?.id, total, shipping, subtotal, expiresAt: quote?.expiresAt ?? null };
}

// Minimum time a quote must still be valid before we create a checkout from it.
export const EXPIRY_MARGIN_MS = 20_000;

/**
 * @returns {{ ok: true } | { ok: false, code: 'CURRENCY_MISMATCH'|'QUOTE_EXPIRED'|'OVER_BUDGET', reason: string, over?: {amount,currency} }}
 */
export function evaluateGate({ totals, budget, now = Date.now(), marginMs = EXPIRY_MARGIN_MS }) {
  const { total, expiresAt } = totals;
  if (total.currency !== budget.currency) {
    return { ok: false, code: 'CURRENCY_MISMATCH', reason: `Quote is in ${total.currency}, budget is in ${budget.currency}` };
  }
  if (expiresAt && Date.parse(expiresAt) - now < marginMs) {
    return { ok: false, code: 'QUOTE_EXPIRED', reason: `Quote ${totals.quoteId} expires at ${expiresAt}` };
  }
  if (total.amount > budget.amount) {
    const over = { amount: r2(total.amount - budget.amount), currency: budget.currency };
    return { ok: false, code: 'OVER_BUDGET', over, reason: `Total ${total.amount} ${total.currency} incl. shipping is ${over.amount} over the ${budget.amount} budget` };
  }
  return { ok: true };
}
/**
 * Plan rework that keeps every mandatory quantity. Two moves only:
 *  1. swap a line to a cheaper permitted substitute from the same merchant (biggest saving first),
 *  2. drop optional extras (most expensive first).
 * Mandatory quantities are never reduced. If even all moves cannot fit, fits=false and the caller
 * reports BRIEF_INFEASIBLE so the organiser changes the brief.
 * cart: [{ id, needId, qty, unitPrice:{amount}, optional, alternates:[{ productId, unitPrice:{amount}, ... }] }]
 * @returns {{ changes: Array<{type:'swap', id, needId, to, saving} | {type:'drop', id, needId, saving}>, estimatedTotal, fits }}
 */
export function planRework({ cart, total, budget }) {
  const changes = [];
  let estimate = r2(total);
  const swaps = [];
  for (const c of cart) {
    if (c.qty <= 0 || c.substitutes === false) continue;
    const cheapest = (c.alternates || []).filter((a) => a.unitPrice.amount < c.unitPrice.amount)
      .sort((a, b) => a.unitPrice.amount - b.unitPrice.amount)[0];
    if (cheapest) swaps.push({ type: 'swap', id: c.id, needId: c.needId, to: cheapest, saving: r2((c.unitPrice.amount - cheapest.unitPrice.amount) * c.qty) });
  }
  swaps.sort((a, b) => b.saving - a.saving);
  for (const s of swaps) {
    if (estimate <= budget.amount) break;
    estimate = r2(estimate - s.saving);
    changes.push(s);
  }
  const extras = cart.filter((c) => c.optional && c.qty > 0)
    .map((c) => {
      const swapped = changes.find((x) => x.id === c.id);
      const unit = swapped ? swapped.to.unitPrice.amount : c.unitPrice.amount;
      return { type: 'drop', id: c.id, needId: c.needId, saving: r2(unit * c.qty), swapped };
    })
    .sort((a, b) => b.saving - a.saving);
  for (const d of extras) {
    if (estimate <= budget.amount) break;
    estimate = r2(estimate - d.saving);
    if (d.swapped) changes.splice(changes.indexOf(d.swapped), 1); // no point swapping a line we drop
    delete d.swapped;
    changes.push(d);
  }
  return { changes, estimatedTotal: estimate, fits: estimate <= budget.amount };
}

/** Deterministic requirements check: every mandatory need must be covered at its full quantity. */
export function checkRequirements({ needs, cart, required }) {
  const missing = [];
  for (const n of needs) {
    if (n.optional) continue;
    const want = required(n);
    const have = cart.filter((c) => c.needId === n.id).reduce((s, c) => s + c.qty, 0);
    if (have < want) missing.push({ need: n.id, qty: want - have });
  }
  return { ok: missing.length === 0, missing };
}

/** Reap quote limits: 1 to 20 lines, positive integer quantities, and (our rule) one merchant per quote. */
export function validateQuoteItems(items, merchants = []) {
  if (!Array.isArray(items) || items.length < 1 || items.length > 20) throw Object.assign(new Error(`A quote takes 1 to 20 items, got ${items?.length ?? 0}`), { code: 'QUOTE_ITEMS_INVALID' });
  for (const i of items) {
    if (!i?.variantId || !Number.isInteger(i.quantity) || i.quantity < 1) throw Object.assign(new Error(`Invalid quote line ${JSON.stringify(i)}`), { code: 'QUOTE_ITEMS_INVALID' });
  }
  const distinct = [...new Set(merchants.filter(Boolean))];
  if (distinct.length > 1) throw Object.assign(new Error(`One quote is one merchant request; cart mixes ${distinct.join(', ')}`), { code: 'MIXED_MERCHANTS' });
  return items;
}
