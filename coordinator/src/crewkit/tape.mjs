// Reap API adapters: live (reap-client), recording (live + tape), replay (tape only, no network).
// A tape is { version, recordedAt, note, entries: [{ op, key, ms, response } | { op, key, ms, error }] }.
// Tapes hold Reap responses only. The API key and card data are never part of a response we call.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { redactApproval } from './events.mjs';

// Replay never has a real approval page; the QR points at the project instead.
export const REPLAY_APPROVAL_URL = 'https://github.com/aGamingGod1234/crewkit-kitchen';

export const OPS = ['search', 'details', 'createQuote', 'getQuote', 'createCheckout', 'getCheckout'];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function liveApi({ simulate = false } = {}) {
  const c = await import('./reap-client.mjs');
  return {
    kind: simulate ? 'simulate' : 'live',
    search: (query, opts) => c.search(query, opts),
    details: (ids) => c.details(ids),
    createQuote: (items, merchants) => c.createQuote(items, { merchants }),
    getQuote: (id) => c.getQuote(id),
    createCheckout: (quoteId, enrollmentId) => c.createCheckout(quoteId, enrollmentId, { simulate }),
    getCheckout: (id) => c.getCheckout(id),
  };
}

const keyFor = (op, args) => (op === 'search' ? String(args[0]).toLowerCase() : op === 'details' ? String(args[0]?.[0] ?? '') : '');

export function recordingApi(inner, { note = '' } = {}) {
  const tape = { version: 1, recordedAt: new Date().toISOString(), note, entries: [] };
  const api = { kind: inner.kind, tape };
  for (const op of OPS) {
    api[op] = async (...args) => {
      const t0 = Date.now();
      try {
        const response = await inner[op](...args);
        tape.entries.push({ op, key: keyFor(op, args), ms: Date.now() - t0, response: redactApproval(response) });
        return response;
      } catch (e) {
        tape.entries.push({ op, key: keyFor(op, args), ms: Date.now() - t0, error: { status: e.status ?? 0, code: e.code ?? 'NETWORK', message: String(e.message).slice(0, 300), detail: e.detail ?? null } });
        throw e;
      }
    };
  }
  api.save = (file) => { mkdirSync(dirname(file), { recursive: true }); writeFileSync(file, JSON.stringify(tape, null, 2)); return file; };
  return api;
}

function withReplayUrl(value) {
  if (value && typeof value === 'object' && value.nextAction && typeof value.nextAction === 'object' && 'url' in value.nextAction) value.nextAction.url = REPLAY_APPROVAL_URL;
  return value;
}

function shiftExpiry(value, shiftMs) {
  if (!shiftMs || value === null || typeof value !== 'object') return value;
  for (const [k, v] of Object.entries(value)) {
    if (k === 'expiresAt' && typeof v === 'string' && Date.parse(v)) value[k] = new Date(Date.parse(v) + shiftMs).toISOString();
    else if (v && typeof v === 'object') shiftExpiry(v, shiftMs);
  }
  return value;
}

export class ReplayError extends Error {
  constructor(status, error) {
    super(`${status} ${error.code}: ${error.message || ''}`);
    this.status = status; this.code = error.code; this.detail = error.detail;
  }
}

/**
 * Deterministic replay. Entries are consumed in order per op (search also matches its query).
 * speed scales recorded latency: 1 = as recorded, 0 = instant (tests).
 */
export function replayApi(tapeOrPath, { speed = 1 } = {}) {
  const tape = typeof tapeOrPath === 'string' ? JSON.parse(readFileSync(tapeOrPath, 'utf8')) : tapeOrPath;
  const used = new Set();
  const lastByOp = new Map();
  const api = { kind: 'replay', tape, enrollmentId: tape.enrollmentId ?? 'replay-enrollment' };
  // Move recorded expiresAt values forward so a tape recorded hours ago still passes the expiry gate.
  const shiftMs = Date.parse(tape.recordedAt) ? Date.now() - Date.parse(tape.recordedAt) : 0;
  for (const op of OPS) {
    api[op] = async (...args) => {
      const key = keyFor(op, args);
      let idx = tape.entries.findIndex((e, i) => !used.has(i) && e.op === op && (op !== 'search' || e.key === key));
      if (idx === -1 && op === 'search') idx = tape.entries.findIndex((e, i) => !used.has(i) && e.op === op);
      let entry;
      if (idx === -1) {
        // Polling may outlast the tape; keep answering with the last status seen.
        entry = op === 'getCheckout' ? lastByOp.get(op) : null;
        if (!entry) throw new Error(`Replay tape has no ${op} entry for '${key}'`);
      } else {
        used.add(idx); entry = tape.entries[idx]; lastByOp.set(op, entry);
      }
      if (speed > 0 && entry.ms) await sleep(entry.ms * speed);
      if (entry.error) throw new ReplayError(entry.error.status, entry.error);
      return withReplayUrl(shiftExpiry(structuredClone(entry.response), shiftMs));
    };
  }
  return api;
}
