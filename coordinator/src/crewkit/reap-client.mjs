// Reap Agentic thin slice: client + CLI.
// Key comes from REAP_API_KEY or reap/.env and is never printed.
// Usage: node reap/reap.mjs <command> [args]   (run with no args for help)
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateQuoteItems } from './gate.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const envFile = join(here, '.env');
const stateFile = join(here, 'state.json');

if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}

const BASE = process.env.REAP_BASE_URL || 'https://sg.sandbox.api.reap.global'; // canonical; sandbox.api.reap.global is an alias
const VERSION = process.env.REAP_VERSION || '2025-02-14';
// Reap rejected example.com return URLs and .example emails (400 AGENTIC_REQUEST_REJECTED); this URL is accepted.
const RETURN_URL = process.env.REAP_RETURN_URL || 'https://github.com/aGamingGod1234/crewkit-kitchen';
const EMAIL = process.env.REAP_EMAIL || null; // must be a real-looking address; set REAP_EMAIL in .env

// Venue, used as the delivery address for quotes.
export const VENUE = {
  firstName: 'CrewKit', lastName: 'Kitchen', phone: process.env.REAP_PHONE || '+6560000000',
  addressLine1: '65 Mohamed Sultan Road', city: 'Singapore', postalCode: '239001', country: 'SG',
};

export const loadState = () => (existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, 'utf8')) : {});
export const saveState = (patch) => {
  const next = { ...loadState(), ...patch };
  writeFileSync(stateFile, JSON.stringify(next, null, 2));
  return next;
};

export class ReapError extends Error {
  constructor(status, body) {
    const e = body?.error || {};
    super(`${status} ${e.code || 'HTTP_ERROR'}: ${e.message || JSON.stringify(body)}`);
    this.status = status; this.code = e.code; this.detail = e.detail; this.body = body;
  }
}

let calls = 0;
export const callCount = () => calls;

export async function reap(method, path, body, { idempotent = false, idempotencyKey, headers = {}, retries = 3 } = {}) {
  const key = process.env.REAP_API_KEY;
  if (!key) throw new Error('REAP_API_KEY missing: put it in reap/.env as REAP_API_KEY=...');
  // One key per logical operation, reused across network/429/5xx retries (docs: cached errors replay).
  const idem = idempotent ? (idempotencyKey || randomUUID()) : null;
  for (let attempt = 0; ; attempt++) {
    calls++;
    let res;
    try {
      res = await fetch(BASE + path, {
        method,
        headers: {
          Authorization: `Bearer ${key}`,
          'Reap-Version': VERSION,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
          ...(idem ? { 'Idempotency-Key': idem } : {}),
          ...headers,
        },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (err) {
      if (attempt < retries) { await sleep(500 * 2 ** attempt); continue; }
      throw err;
    }
    const text = await res.text();
    const json = text ? safeJson(text) : null;
    if (res.ok) return json;
    const retryable = res.status === 429 || res.status >= 500;
    if (retryable && attempt < retries) {
      const after = Number(res.headers.get('retry-after'));
      await sleep(after > 0 ? after * 1000 : 500 * 2 ** attempt);
      continue;
    }
    throw new ReapError(res.status, json ?? text);
  }
}

function requireEmail() {
  if (!EMAIL) throw new Error('REAP_EMAIL missing: add REAP_EMAIL=<real address> to coordinator/src/crewkit/.env');
  return EMAIL;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const safeJson = (t) => { try { return JSON.parse(t); } catch { return t; } };

// ---- API wrappers (docs.reap.global/api-reference/agentic) ----
export const createEnrollment = (ownerId = 'crewkit-demo', email = requireEmail()) =>
  reap('POST', '/agentic/enrollments', {
    source: 'EXTERNAL',
    owner: { type: 'CLIENT_REFERENCE', id: ownerId, email },
    presentation: { type: 'REDIRECT', returnUrl: RETURN_URL },
  }, { idempotent: true });
export const getEnrollment = (id) => reap('GET', `/agentic/enrollments/${id}`);
export const listEnrollments = (ownerId = 'crewkit-demo') =>
  reap('GET', `/agentic/enrollments?ownerType=CLIENT_REFERENCE&ownerId=${encodeURIComponent(ownerId)}&limit=20`);

export const search = (query, { merchant, mode = 'ONLY', limit = 10, country = 'SG', currency = 'SGD' } = {}) =>
  reap('POST', '/agentic/products/search', {
    query,
    context: { country, currency },
    filters: { availability: 'AVAILABLE_ONLY' },
    pagination: { limit },
    ...(merchant ? { merchantPreference: { mode, merchantName: merchant } } : {}),
  });
// details takes 1 to 10 product ids per call; batch larger lists and merge.
export async function details(productIds) {
  const out = { products: [], errors: [] };
  for (let i = 0; i < productIds.length; i += 10) {
    const r = await reap('POST', '/agentic/products/details', { productIds: productIds.slice(i, i + 10) });
    out.products.push(...(r?.products || [])); out.errors.push(...(r?.errors || []));
  }
  return out;
}
export const variant = (productId, optionIds) => reap('POST', '/agentic/products/variant', { productId, optionIds });

export const createQuote = (items, { email = requireEmail(), address = VENUE, merchants = [] } = {}) =>
  validateQuoteItems(items, merchants) && reap('POST', '/agentic/quotes', { items, email, shippingAddress: address }, { idempotent: true });
export const getQuote = (id) => reap('GET', `/agentic/quotes/${id}`);
export const selectShipping = (quoteId, shippingOptionId) =>
  reap('POST', `/agentic/quotes/${quoteId}/shipping-option`, { shippingOptionId });

export const createCheckout = (quoteId, enrollmentId, { simulate = false } = {}) =>
  reap('POST', '/agentic/checkouts', {
    quoteId, enrollmentId, presentation: { type: 'REDIRECT', returnUrl: RETURN_URL },
  }, { idempotent: true, headers: simulate ? { 'X-Simulate-Checkout': 'COMPLETED' } : {} });
export const getCheckout = (id) => reap('GET', `/agentic/checkouts/${id}`);

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);
export async function pollCheckout(id, { everyMs = 2500, timeoutMs = 10 * 60_000, onStatus } = {}) {
  const start = Date.now();
  let last;
  for (;;) {
    const c = await getCheckout(id);
    if (c.status !== last) { last = c.status; onStatus?.(c); }
    if (TERMINAL.has(c.status)) return c;
    if (Date.now() - start > timeoutMs) throw new Error(`poll timeout, last status ${c.status}`);
    await sleep(everyMs);
  }
}

// ---- CLI ----
const HELP = `Commands:
  ping                              key + agentic access check (one search call)
  search <query> [merchant]         e.g. search notebook popular.com.sg
  details <productId...>
  variant <productId> <optionId...>
  enroll                            create EXTERNAL enrollment, prints card-entry URL
  enrollment [id]                   read enrollment status (default: saved one)
  quote <variantId:qty> [...]       quote to the venue, prints amountBreakdown
  shipping <quoteId> <optionId>
  checkout [quoteId] [--simulate]   uses saved quote + enrollment, prints approval URL
  poll [checkoutId]                 poll every 2.5s until COMPLETED/FAILED/EXPIRED`;

async function main([cmd, ...args]) {
  const st = loadState();
  const out = (x) => console.log(JSON.stringify(x, null, 2));
  switch (cmd) {
    case 'ping': {
      const r = await search('notebook', { limit: 1 });
      console.log(`OK: ${BASE} answered, ${r.products?.length ?? 0} product(s) for "notebook"`);
      break;
    }
    case 'search': {
      const [query, merchant] = args;
      const r = await search(query, { merchant });
      const money = (m) => (m && typeof m === 'object' ? `${m.amount} ${m.currency}` : String(m ?? '?'));
      for (const p of r.products || [])
        console.log(`${p.id}  ${p.merchant?.name} | ${p.name} | ${money(p.previewVariant?.price ?? p.priceRange?.min)} | variant ${p.previewVariant?.id}`);
      if (r.warnings?.length) out({ warnings: r.warnings });
      break;
    }
    case 'details': out(await details(args)); break;
    case 'variant': out(await variant(args[0], args.slice(1))); break;
    case 'enroll': {
      const e = await createEnrollment();
      saveState({ enrollmentId: e.id });
      console.log(`enrollment ${e.id} ${e.status}\nOpen and add a test card (OTP 456789):\n${e.nextAction?.url}`);
      break;
    }
    case 'enrollment': out(await getEnrollment(args[0] || st.enrollmentId)); break;
    case 'quote': {
      const items = args.map((a) => { const [variantId, q] = a.split(':'); return { variantId, quantity: Number(q || 1) }; });
      const q = await createQuote(items);
      saveState({ quoteId: q.id });
      out({ id: q.id, expiresAt: q.expiresAt, amountBreakdown: q.amountBreakdown, shippingOptions: q.shippingOptions });
      break;
    }
    case 'shipping': out(await selectShipping(args[0], args[1])); break;
    case 'checkout': {
      const simulate = args.includes('--simulate');
      const quoteId = args.find((a) => !a.startsWith('--')) || st.quoteId;
      const c = await createCheckout(quoteId, st.enrollmentId, { simulate });
      saveState({ checkoutId: c.id });
      console.log(`checkout ${c.id} ${c.status} ${c.amount?.amount} ${c.amount?.currency}\nApprove here:\n${c.nextAction?.url ?? '(no nextAction)'}`);
      break;
    }
    case 'poll': {
      const c = await pollCheckout(args[0] || st.checkoutId, { onStatus: (x) => console.log(new Date().toLocaleTimeString(), x.status) });
      out({ status: c.status, orderId: c.orderId, finalAmount: c.finalAmount });
      break;
    }
    default: console.log(HELP);
  }
  console.error(`(${callCount()} Reap call${callCount() === 1 ? '' : 's'})`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  // exitCode, not process.exit(): exiting with fetch handles open trips a libuv assertion on Windows.
  main(process.argv.slice(2)).catch((e) => { console.error(e.message); if (e.detail) console.error(JSON.stringify(e.detail)); process.exitCode = 1; });
}
