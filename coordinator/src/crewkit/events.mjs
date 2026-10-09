// CrewKit event stream: every event gets runId + monotonically increasing seq (contract: crewkit_state).
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export const CREWKIT_EVENTS = Object.freeze([
  'brief', 'item_added', 'quote', 'gate_blocked', 'item_removed', 'gate_passed',
  'checkout', 'completed', 'record', 'failed', 'expired', 'calls', 'reset', 'requirements', 'candidates', 'activity',
]);
const KNOWN = new Set(CREWKIT_EVENTS);

// The hosted approval URL lets whoever holds it approve the payment. Only the mod (for the QR) gets it;
// logs, records, tapes and anything an LLM can read get this marker instead.
export const REDACTED = '[redacted]';

export function redactApproval(value) {
  if (Array.isArray(value)) return value.map(redactApproval);
  if (value === null || typeof value !== 'object') return value;
  const out = {};
  for (const [k, v] of Object.entries(value)) {
    if (k === 'approvalUrl' && v !== null && v !== undefined) out[k] = REDACTED;
    else if (k === 'nextAction' && v && typeof v === 'object' && 'url' in v) out[k] = { ...redactApproval(v), url: REDACTED };
    else out[k] = redactApproval(v);
  }
  return out;
}

/**
 * sinks get the redacted payload (files, console, logs). bridgeSinks get the real payload and are only for
 * delivering crewkit_state to the mod. log keeps the redacted copy.
 */
export function createEventStream({ runId, sinks = [], bridgeSinks = [] }) {
  let seq = 0;
  const log = [];
  const deliver = (list, payload) => {
    for (const sink of list) {
      // A broken sink (bridge down, disk full) must not stop the purchase flow.
      try { const r = sink(payload); if (r && typeof r.catch === 'function') r.catch(() => {}); } catch { /* ignore */ }
    }
  };
  const emit = (event, data = {}) => {
    if (!KNOWN.has(event)) throw new Error(`Unknown crewkit event '${event}'`);
    const payload = { runId, seq: ++seq, event, data };
    const safe = redactApproval(payload);
    log.push(safe);
    deliver(bridgeSinks, payload);
    deliver(sinks, safe);
    return safe;
  };
  return { emit, log, get seq() { return seq; }, runId };
}

export const ndjsonSink = (file) => {
  mkdirSync(dirname(file), { recursive: true });
  return (payload) => appendFileSync(file, JSON.stringify(payload) + '\n');
};

export const consoleSink = (write = (s) => process.stdout.write(s)) => (payload) => write(JSON.stringify(payload) + '\n');
