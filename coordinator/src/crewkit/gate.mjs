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
 * Plan the cheapest-to-explain cuts that bring the estimated total under budget.
 * Order: share per-person items that allow it (one per pair), then drop optional needs.
 * cart: [{ id, per, qty, unitPrice:{amount}, shareAs?, optional? }]
 * @returns {{ changes: [{ id, qtyRemoved, newQty, newPer?, why }], estimatedTotal: number, fits: boolean }}
 */
export function planRework({ cart, total, budget, guestCount }) {
  const changes = [];
  let estimate = total;
  const pairs = Math.ceil(guestCount / 2);
  const shareable = cart.filter((c) => c.per === 'person' && c.shareAs === 'pair' && c.qty > pairs);
  // Biggest saving first so the fewest items move.
  shareable.sort((a, b) => (b.qty - pairs) * b.unitPrice.amount - (a.qty - pairs) * a.unitPrice.amount);
  for (const c of shareable) {
    if (estimate <= budget.amount) break;
    const qtyRemoved = c.qty - pairs;
    estimate = r2(estimate - qtyRemoved * c.unitPrice.amount);
    changes.push({ id: c.id, qtyRemoved, newQty: pairs, newPer: 'pair', why: 'share one per pair' });
  }
  const optional = cart.filter((c) => c.optional && !changes.some((x) => x.id === c.id));
  optional.sort((a, b) => b.qty * b.unitPrice.amount - a.qty * a.unitPrice.amount);
  for (const c of optional) {
    if (estimate <= budget.amount) break;
    estimate = r2(estimate - c.qty * c.unitPrice.amount);
    changes.push({ id: c.id, qtyRemoved: c.qty, newQty: 0, why: 'drop optional item' });
  }
  return { changes, estimatedTotal: estimate, fits: estimate <= budget.amount };
}
