// CrewKit event stream: every event gets runId + monotonically increasing seq (contract: crewkit_state).
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export const CREWKIT_EVENTS = Object.freeze([
  'brief', 'item_added', 'quote', 'gate_blocked', 'item_removed', 'gate_passed',
  'checkout', 'completed', 'record', 'failed', 'expired', 'calls', 'reset',
]);
const KNOWN = new Set(CREWKIT_EVENTS);

export function createEventStream({ runId, sinks = [] }) {
  let seq = 0;
  const log = [];
  const emit = (event, data = {}) => {
    if (!KNOWN.has(event)) throw new Error(`Unknown crewkit event '${event}'`);
    const payload = { runId, seq: ++seq, event, data };
    log.push(payload);
    for (const sink of sinks) {
      // A broken sink (bridge down, disk full) must not stop the purchase flow.
      try { const r = sink(payload); if (r && typeof r.catch === 'function') r.catch(() => {}); } catch { /* ignore */ }
    }
    return payload;
  };
  return { emit, log, get seq() { return seq; }, runId };
}

export const ndjsonSink = (file) => {
  mkdirSync(dirname(file), { recursive: true });
  return (payload) => appendFileSync(file, JSON.stringify(payload) + '\n');
};

export const consoleSink = (write = (s) => process.stdout.write(s)) => (payload) => write(JSON.stringify(payload) + '\n');
