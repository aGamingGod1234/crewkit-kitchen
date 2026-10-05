import { isDeepStrictEqual } from 'node:util';
import { randomUUID } from 'node:crypto';

export const MODEL_FACT_FORMAT = 'minecraft-facts-v1';
export const MODEL_FACT_INSTRUCTIONS = `Compact factual replies use format:"minecraft-facts-v1": data is the original JSON, {$rows:{columns,rows}} represents an array of records in column order, and {$ref:N} is the exact value at values[N] in this reply. {$object:[[key,value],...]} preserves literal objects with reserved keys. Null, missing fields, coverage and freshness keep their meanings. Complete replies need no previous reply. observe and available action/sequence postAction samples default to complete snapshots with observationView.id alongside observation. view:"changes" plus afterObservationId requests changed whole sections against that exact delivered ID, including across observe and postAction. Apply observationView.replace and remove to that baseline; retain unchanged sections. observationView.retainMetadata lists top-level sample fields to copy from that same exact baseline; all other metadata is current, and missing fields are absent. Action receipts and per-step history are unchanged. Missing, stale or different-world baselines return full. Request view:"full" whenever the baseline is uncertain.`;
const MAX_DELIVERED_VIEWS = 8;
const MAX_DELIVERED_VIEW_BYTES = 2 * 1024 * 1024;
const RETAINABLE_METADATA = ['taskMemory', 'goal', 'goalSpec', 'executionSettings'];
const MARKERS = new Set(['$ref', '$rows', '$object']);
const bytes = value => Buffer.byteLength(JSON.stringify(value), 'utf8');
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const marked = value => record(value) && Object.keys(value).length === 1 && MARKERS.has(Object.keys(value)[0]);
const observationIdentity = observation => typeof observation?.world?.worldId === 'string' && typeof observation?.world?.dimension === 'string'
 ? JSON.stringify([observation.world.worldId, observation.world.dimension, observation.player?.dead === true, observation.continuity?.phase ?? null]) : null;

/** Provider presentation only. Each reply is complete; no dictionary survives a reply. */
export function encodeModelFacts(value) {
 const originalBytes = bytes(value);
 const wrappedAlready = value?.format === MODEL_FACT_FORMAT && Array.isArray(value.values) && Object.hasOwn(value, 'data');
 if (originalBytes < 1024 && !wrappedAlready) return value;
 const counts = new Map();
 const count = current => {
  if (typeof current === 'string' && current.length >= 32 || current !== null && typeof current === 'object' && bytes(current) >= 80) {
   const key = JSON.stringify(current);
   counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  if (Array.isArray(current)) for (const child of current) count(child);
  else if (record(current)) for (const child of Object.values(current)) count(child);
 };
 count(value);
 const shared = new Set([...counts].filter(([key, times]) => times >= 2 && (times - 1) * Buffer.byteLength(key) - times * 12 > 40).map(([key]) => key));
 const values = [], indexes = new Map();
 const pack = (current, skip = null) => {
  const key = JSON.stringify(current);
  if (key !== skip && shared.has(key)) {
   if (!indexes.has(key)) {
    const index = values.length;
    indexes.set(key, index); values.push(null);
    values[index] = pack(current, key);
   }
   return { $ref: indexes.get(key) };
  }
  if (Array.isArray(current)) {
   const rows = current.map(child => pack(child));
   if (current.length >= 3 && current.every(record)) {
    const columns = Object.keys(current[0]);
    if (current.every(row => Object.keys(row).length === columns.length && columns.every(column => Object.hasOwn(row, column)))) {
     // Reuse packed children, including dictionary-backed/literal rows. Packing
     // those children again doubles recursive work at every nested row level.
     const table = { $rows: { columns, rows: rows.map(row => {
      const packed = marked(row) && Object.hasOwn(row, '$ref') ? values[row.$ref] : row;
      const fields = marked(packed) && Object.hasOwn(packed, '$object') ? Object.fromEntries(packed.$object) : packed;
      return columns.map(column => fields[column]);
     }) } };
     if (bytes(table) < bytes(rows)) return table;
    }
   }
   return rows;
  }
  if (record(current)) {
   const entries = Object.entries(current).map(([key, child]) => [key, pack(child)]);
   return marked(current) ? { $object: entries } : Object.fromEntries(entries);
  }
  return current;
 };
 const data = pack(value);
 // Packing rows may bypass a whole-record reference. Retain only definitions
 // reachable from the final data, rather than sending unused dictionary entries.
 const used = new Set();
 const visit = current => {
  if (record(current) && Object.keys(current).length === 1 && Object.hasOwn(current, '$ref')) {
   const index = current.$ref;
   if (!used.has(index)) { used.add(index); visit(values[index]); }
  } else if (Array.isArray(current)) for (const child of current) visit(child);
  else if (record(current)) for (const child of Object.values(current)) visit(child);
 };
 visit(data);
 const order = [...used].sort((a, b) => a - b), remap = new Map(order.map((old, index) => [old, index]));
 const rewrite = current => {
  if (record(current) && Object.keys(current).length === 1 && Object.hasOwn(current, '$ref')) return { $ref: remap.get(current.$ref) };
  if (Array.isArray(current)) return current.map(rewrite);
  if (record(current)) return Object.fromEntries(Object.entries(current).map(([key, child]) => [key, rewrite(child)]));
  return current;
 };
 const encoded = { format: MODEL_FACT_FORMAT, values: order.map(index => rewrite(values[index])), data: rewrite(data) };
 return wrappedAlready || bytes(encoded) + 128 < originalBytes ? encoded : value;
}

/** Exact decoder used for replay checks; the runtime keeps its original objects. */
export function decodeModelFacts(value) {
 if (value?.format !== MODEL_FACT_FORMAT || !Array.isArray(value.values) || !Object.hasOwn(value, 'data')) return value;
 const resolved = new Map(), resolving = new Set();
 const unpack = current => {
  if (Array.isArray(current)) return current.map(unpack);
  if (!record(current)) return current;
  if (Object.keys(current).length === 1 && Object.hasOwn(current, '$ref')) {
   const index = current.$ref;
   if (!Number.isSafeInteger(index) || index < 0 || index >= value.values.length || resolving.has(index)) throw new TypeError('Invalid fact reference');
   if (!resolved.has(index)) { resolving.add(index); resolved.set(index, unpack(value.values[index])); resolving.delete(index); }
   return structuredClone(resolved.get(index));
  }
  if (Object.keys(current).length === 1 && Object.hasOwn(current, '$rows')) {
   const { columns, rows } = current.$rows ?? {};
   if (!Array.isArray(columns) || columns.some(column => typeof column !== 'string') || new Set(columns).size !== columns.length || !Array.isArray(rows) || rows.some(row => !Array.isArray(row) || row.length !== columns.length)) throw new TypeError('Invalid fact rows');
   return rows.map(row => Object.fromEntries(columns.map((column, index) => [column, unpack(row[index])])));
  }
  if (Object.keys(current).length === 1 && Object.hasOwn(current, '$object')) {
   if (!Array.isArray(current.$object) || current.$object.some(entry => !Array.isArray(entry) || entry.length !== 2 || typeof entry[0] !== 'string')) throw new TypeError('Invalid literal fact object');
   return Object.fromEntries(current.$object.map(([key, child]) => [key, unpack(child)]));
  }
  return Object.fromEntries(Object.entries(current).map(([key, child]) => [key, unpack(child)]));
 };
 return unpack(value.data);
}

/** Changes are opt-in, apply to whole named sections, and require the exact view. */
export class ModelObservationViews {
 #session = randomUUID();
 #baseline = null;
 #history = new Map();
 #historyBytes = 0;
 #sequence = 0;
 #committedSequence = 0;
 #generation = 0;
 reset() { this.#baseline = null; this.#history.clear(); this.#historyBytes = 0; this.#generation++; }
 observeEvent(observation) {
  const identity = observationIdentity(observation);
  if (identity === null || identity !== this.#baseline?.identity || observation.player?.dead === true || observation.continuity?.phase === 'dead') this.reset();
 }

 prepare(value, tool) {
  // Only the final fresh sample is a view. Per-step observations remain
  // historical receipts, even when a sequence stopped on a failed action.
  const nested = (tool.kind === 'action' || tool.kind === 'sequence') && record(value?.postAction);
  const sample = nested ? value.postAction : tool.kind === 'observe' ? value : null;
  if (!record(sample?.observation)) return { value, commit() {} };
  const owned = structuredClone(value);
  const snapshot = nested ? owned.postAction : owned;
  const observation = snapshot.observation;
  const sequence = ++this.#sequence;
  const id = `observation-${this.#session}-${sequence}`;
  const identity = observationIdentity(observation);
  const eligible = identity !== null && snapshot.freshness?.fresh === true && observation.player?.dead !== true && observation.continuity?.phase !== 'dead';
  const next = { id, identity, observation: structuredClone(observation), metadata: Object.fromEntries(RETAINABLE_METADATA.filter(key => Object.hasOwn(snapshot, key)).map(key => [key, structuredClone(snapshot[key])])), goalRevision: snapshot.goalRevision };
  next.bytes = bytes(next);
  const full = { ...snapshot, observationView: { id, mode: 'full' } };
  let presented = full;
  const previous = this.#history.get(tool.afterObservationId) ?? null;
  if (eligible && tool.view === 'changes' && previous !== null && previous.id === tool.afterObservationId && previous.identity === identity && previous.goalRevision === snapshot.goalRevision) {
   const replace = Object.fromEntries(Object.entries(observation).filter(([key, current]) => !Object.hasOwn(previous.observation, key) || !isDeepStrictEqual(previous.observation[key], current)));
   const remove = Object.keys(previous.observation).filter(key => !Object.hasOwn(observation, key));
   const { observation: _full, ...metadata } = snapshot;
   const retainMetadata = RETAINABLE_METADATA.filter(key => Object.hasOwn(metadata, key) && Object.hasOwn(previous.metadata, key) && isDeepStrictEqual(metadata[key], previous.metadata[key]));
   for (const key of retainMetadata) delete metadata[key];
   const changes = { ...metadata, observationView: { id, mode: 'changes', baseId: previous.id, replace, remove, ...(retainMetadata.length === 0 ? {} : { retainMetadata }) } };
   if (bytes(changes) < bytes(full)) presented = changes;
  }
  const generation = this.#generation;
  let committed = false;
  const commit = baseline => {
   if (committed || generation !== this.#generation) return;
   committed = true;
   // Sibling replies may finish delivery in either order. Keep each exact
   // delivered view, but an older callback must not reset newer context.
   if (sequence > this.#committedSequence) {
    if (baseline === null || this.#baseline !== null && this.#baseline.identity !== baseline.identity) this.reset();
    this.#committedSequence = sequence;
    this.#baseline = baseline === null ? null : { identity: baseline.identity };
   } else if (baseline === null || baseline.identity !== this.#baseline?.identity) return;
   if (baseline === null || baseline.bytes > MAX_DELIVERED_VIEW_BYTES) return;
   this.#history.set(baseline.id, baseline);
   this.#historyBytes += baseline.bytes;
   while (this.#history.size > MAX_DELIVERED_VIEWS || this.#historyBytes > MAX_DELIVERED_VIEW_BYTES) {
    const oldest = this.#history.keys().next().value;
    this.#historyBytes -= this.#history.get(oldest).bytes;
    this.#history.delete(oldest);
   }
  };
  return { value: nested ? { ...owned, postAction: presented } : presented,
   commit: () => commit(eligible ? next : null), commitWithoutView: () => commit(null) };
 }
}

/** Compress only the JSON data line emitted by buildNativeEventInput. */
export function encodeNativeEventInput(input, views = null) {
 const separator = input.indexOf('\n');
 if (separator < 0) return input;
 const lineEnd = input.indexOf('\n', separator + 1);
 const end = lineEnd < 0 ? input.length : lineEnd;
 try {
  const value = JSON.parse(input.slice(separator + 1, end));
  if (!record(value) || typeof value.event !== 'string' || !record(value.observation)) return input;
  if (value.event === 'player_death' || value.observation.player?.dead === true || value.observation.continuity?.phase === 'dead') views?.reset();
  else views?.observeEvent(value.observation);
  const encoded = encodeModelFacts(value);
  return encoded === value ? input : `${input.slice(0, separator + 1)}${JSON.stringify(encoded)}${input.slice(end)}`;
 } catch { return input; }
}
