// Wires a CrewKit run: picks the API adapter for the mode, event sinks, tape recording and purchase records.
// Shared by the CLI, the coordinator HTTP trigger and the crewkit_shop agent tool.
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runCrewkit, normalizeBrief } from './engine.mjs';
import { createEventStream, ndjsonSink } from './events.mjs';
import { liveApi, recordingApi, replayApi } from './tape.mjs';

const here = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = join(here, '..', '..', '..');
export const RECORDS_DIR = process.env.CREWKIT_RECORDS_DIR || join(REPO_ROOT, 'crewkit-records');
export const DEFAULT_TAPE = join(here, 'fixtures', 'demo-popular-sg.tape.json');
export const DEFAULT_BRIEF = join(here, 'fixtures', 'demo-brief.json');
export const MODES = ['replay', 'simulate', 'live'];

function resolveEnrollmentId(brief) {
  if (brief.enrollmentId) return brief.enrollmentId;
  if (process.env.REAP_ENROLLMENT_ID) return process.env.REAP_ENROLLMENT_ID;
  const state = join(here, 'state.json');
  if (existsSync(state)) { try { return JSON.parse(readFileSync(state, 'utf8')).enrollmentId || null; } catch { /* fallthrough */ } }
  return null;
}

const csvCell = (v) => (/[",\n]/.test(String(v ?? '')) ? `"${String(v).replace(/"/g, '""')}"` : String(v ?? ''));

export function writeRecord({ runId, mode, brief, result, dir = RECORDS_DIR }) {
  mkdirSync(dir, { recursive: true });
  const at = new Date().toISOString();
  const json = {
    runId, mode, at, title: brief.title, merchant: brief.merchant, status: result.status, outcome: result.status === 'COMPLETED' ? 'order placed' : 'no order placed', reason: result.reason ?? null,
    budget: brief.budget, quoteId: result.quote?.id ?? null, checkoutId: result.checkout?.id ?? null,
    record: result.record, calls: result.calls,
    // Lines swapped out or dropped stay in the file with qty 0 so the rework is auditable.
    items: result.cart.map(({ id, needId, realName, merchant, mcItem, unitPrice, qty, per, optional, variantId }) => ({ id, needId, realName, merchant, mcItem, unitPrice, qty, per, optional, variantId })),
  };
  const jsonFile = join(dir, `${runId}.json`);
  writeFileSync(jsonFile, JSON.stringify(json, null, 2));
  const rows = [['item', 'need', 'realName', 'merchant', 'per', 'qty', 'unitPrice', 'lineTotal', 'currency']];
  for (const i of json.items) rows.push([i.id, i.needId, i.realName, i.merchant, i.per, i.qty, i.unitPrice.amount, Math.round(i.qty * i.unitPrice.amount * 100) / 100, i.unitPrice.currency]);
  if (result.record) {
    const r = result.record;
    rows.push([], ['budget', 'quoted', 'charged', 'variance', 'orderId', 'currency'], [r.budget, r.quoted, r.charged, r.variance, r.orderId, r.currency]);
  }
  const csvFile = join(dir, `${runId}.csv`);
  writeFileSync(csvFile, rows.map((r) => r.map(csvCell).join(',')).join('\n') + '\n');
  const ledger = join(dir, 'purchases.csv');
  if (!existsSync(ledger)) appendFileSync(ledger, 'at,runId,mode,status,budget,quoted,charged,variance,orderId,currency\n');
  const r = result.record || {};
  appendFileSync(ledger, [at, runId, mode, result.status, brief.budget.amount, r.quoted ?? '', r.charged ?? '', r.variance ?? '', r.orderId ?? '', brief.budget.currency].map(csvCell).join(',') + '\n');
  return { jsonFile, csvFile, ledger };
}

/**
 * Start a run. Returns { runId, done } where done resolves to the result.
 * opts: mode, sinks[], tape (replay path or object), speed (replay latency scale), record (save tape in live/simulate),
 *       pollEveryMs, writeRecords
 */
export async function startRun(rawBrief, { mode = 'replay', sinks = [], bridgeSinks = [], tape = DEFAULT_TAPE, speed = 1, recordTape = true, pollEveryMs, writeRecords = true, log = () => {}, runId = `ck-${Date.now().toString(36)}-${randomUUID().slice(0, 6)}` } = {}) {
  if (!MODES.includes(mode)) throw new TypeError(`mode must be one of ${MODES.join(', ')}`);
  const brief = normalizeBrief(rawBrief);
  let api; let enrollmentId;
  if (mode === 'replay') {
    api = replayApi(tape, { speed });
    enrollmentId = api.enrollmentId;
  } else {
    if (!process.env.REAP_API_KEY) await import('./reap-client.mjs'); // loads .env
    if (!process.env.REAP_API_KEY) throw new Error('REAP_API_KEY missing in coordinator/src/crewkit/.env');
    if (!process.env.REAP_EMAIL) throw new Error('REAP_EMAIL missing in coordinator/src/crewkit/.env (quotes need a real-looking email)');
    enrollmentId = resolveEnrollmentId(brief);
    if (!enrollmentId) throw new Error('No ACTIVE enrollment id: run `node coordinator/src/crewkit/reap-client.mjs enroll`, add a test card, or set REAP_ENROLLMENT_ID');
    api = await liveApi({ simulate: mode === 'simulate' });
    if (recordTape) api = recordingApi(api, { note: `${mode} run ${runId}: ${brief.title}` });
  }
  const eventsFile = join(RECORDS_DIR, `${runId}.events.ndjson`);
  const stream = createEventStream({ runId, sinks: [ndjsonSink(eventsFile), ...sinks], bridgeSinks });
  const done = (async () => {
    let result;
    try {
      result = await runCrewkit({ brief, api, emit: stream.emit, enrollmentId, log, ...(pollEveryMs !== undefined ? { pollEveryMs } : mode === 'replay' ? { pollEveryMs: 2500 * speed } : {}), ...(mode === 'replay' && speed === 0 ? { sleep: async () => {} } : {}) });
    } catch (e) {
      // Unexpected errors still end the run visibly. Messages come from Reap error bodies, never the key.
      stream.emit('failed', { status: e.code || 'ERROR', reason: String(e.message).slice(0, 300) });
      result = { status: e.code || 'ERROR', reason: e.message, cart: [], calls: 0 };
    }
    if (api.save) {
      api.tape.enrollmentId = enrollmentId;
      result.tapeFile = api.save(join(RECORDS_DIR, 'tapes', `${runId}.tape.json`));
    }
    if (writeRecords) result.files = writeRecord({ runId, mode, brief, result });
    result.eventsFile = eventsFile;
    result.events = stream.log;
    return result;
  })();
  return { runId, done, brief };
}
