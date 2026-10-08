import { isDeepStrictEqual } from 'node:util';
import { randomUUID } from 'node:crypto';

export const MODEL_FACT_FORMAT = 'minecraft-facts-v1';
export const MODEL_FACT_INSTRUCTIONS = `Compact factual replies use format:"minecraft-facts-v1": data is the original JSON, {$rows:{columns,rows,optional?}} represents an array of records in column order (a null cell in an optional column means that field is absent), and {$ref:N} is the exact value at values[N] in this reply. {$object:[[key,value],...]} preserves literal objects with reserved keys. Null, missing fields, coverage and freshness keep their meanings. Complete replies need no previous reply. observe and available action/sequence postAction samples default to complete snapshots with observationView.id alongside observation. view:"changes" plus afterObservationId requests changed whole sections against that exact delivered ID, including across observe, postAction and wake events. Apply observationView.replace and remove to that baseline; retain unchanged sections. observationView.retainMetadata lists top-level sample fields to copy from that same exact baseline; all other metadata is current, and missing fields are absent. Action receipts and per-step history are unchanged. Missing, stale or different-world baselines return full. Request view:"full" whenever the baseline is uncertain.`;
const MAX_DELIVERED_VIEWS = 8;
const MAX_DELIVERED_VIEW_BYTES = 2 * 1024 * 1024;
const RETAINABLE_METADATA = ['taskMemory', 'goal', 'goalSpec', 'executionSettings'];
// Event fields that rarely change between wakes; repeating them unchanged in every event only grows the context.
const EVENT_METADATA = ['goal', 'goalSpec', 'taskMemory'];
const MIN_RETAINED_EVENT_FIELD_BYTES = 64;
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
    // Mixed rows (a slab with state among plain stone) share one table: a column missing from some
    // rows is listed in optional, where null means absent, unless a present value is itself null.
    const columns = [...new Set(current.flatMap(row => Object.keys(row)))];
    const optional = columns.filter(column => !current.every(row => Object.hasOwn(row, column)));
    const representable = optional.every(column => current.every(row => !Object.hasOwn(row, column) || row[column] !== null));
    if (representable && columns.length <= 64) {
     // Reuse packed children, including dictionary-backed/literal rows. Packing
     // those children again doubles recursive work at every nested row level.
     const table = { $rows: { columns, rows: rows.map((row, index) => {
      const packed = marked(row) && Object.hasOwn(row, '$ref') ? values[row.$ref] : row;
      const fields = marked(packed) && Object.hasOwn(packed, '$object') ? Object.fromEntries(packed.$object) : packed;
      return columns.map(column => Object.hasOwn(current[index], column) ? fields[column] : null);
     }), ...(optional.length === 0 ? {} : { optional }) } };
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
   const { columns, rows, optional = [] } = current.$rows ?? {};
   if (!Array.isArray(columns) || columns.some(column => typeof column !== 'string') || new Set(columns).size !== columns.length || !Array.isArray(rows) || rows.some(row => !Array.isArray(row) || row.length !== columns.length)) throw new TypeError('Invalid fact rows');
   if (!Array.isArray(optional) || optional.some(column => !columns.includes(column))) throw new TypeError('Invalid optional fact columns');
   const absent = new Set(optional);
   return rows.map(row => Object.fromEntries(columns.flatMap((column, index) => absent.has(column) && row[index] === null ? [] : [[column, unpack(row[index])]])));
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
 #latestViewId = null;
 #eventMetadata = null;
 reset() { this.#resetObservations(); this.#eventMetadata = null; }
 #resetObservations() { this.#baseline = null; this.#history.clear(); this.#historyBytes = 0; this.#latestViewId = null; this.#generation++; }
 /** An event may have been encoded but not delivered; force a full view until delivery is certain again. */
 forgetEventView() { this.reset(); }
 /** A turn that may not have reached the model must not become the baseline for omitted event fields. */
 forgetEventMetadata() { this.#eventMetadata = null; }
 /** Omits goal, goalSpec and taskMemory when they equal the previous event delivered in this same provider context. */
 retainEventMetadata(value) {
  const identity = observationIdentity(value.observation);
  const previous = this.#eventMetadata;
  this.#eventMetadata = identity === null ? null : { identity, fields: Object.fromEntries(EVENT_METADATA.filter(key => Object.hasOwn(value, key)).map(key => [key, structuredClone(value[key])])) };
  if (previous === null || identity === null || previous.identity !== identity) return value;
  const same = EVENT_METADATA.filter(key => Object.hasOwn(value, key) && Object.hasOwn(previous.fields, key)
   && bytes(value[key] ?? null) > MIN_RETAINED_EVENT_FIELD_BYTES && isDeepStrictEqual(value[key], previous.fields[key]));
  if (same.length === 0) return value;
  const retained = { ...value };
  for (const key of same) delete retained[key];
  retained.sameAsPreviousEvent = same;
  return retained;
 }
 observeEvent(observation) {
  const identity = observationIdentity(observation);
  // Observation baselines are per sample; event metadata is checked against its own world identity below.
  if (identity === null || identity !== this.#baseline?.identity || observation.player?.dead === true || observation.continuity?.phase === 'dead') this.#resetObservations();
 }

 prepare(value, tool, { minRetainedMetadataBytes = 0 } = {}) {
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
   const retainMetadata = RETAINABLE_METADATA.filter(key => Object.hasOwn(metadata, key) && Object.hasOwn(previous.metadata, key)
    && bytes(metadata[key] ?? null) > minRetainedMetadataBytes && isDeepStrictEqual(metadata[key], previous.metadata[key]));
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
   this.#latestViewId = baseline.id;
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
 /** Wake observations share the exact delivered-view history used by observe and postAction replies. */
 prepareEvent(value) {
  if (observationIdentity(value.observation) === null) {
   this.#resetObservations();
   return value;
  }
  const hadFreshness = Object.hasOwn(value, 'freshness');
  const freshness = value.freshness ?? { fresh: value.observation?.freshness?.fresh !== false };
  const prepared = this.prepare({ ...value, freshness }, {
   kind: 'observe', view: 'changes', afterObservationId: this.#latestViewId,
  }, { minRetainedMetadataBytes: MIN_RETAINED_EVENT_FIELD_BYTES });
  prepared.commit();
  if (hadFreshness) return prepared.value;
  const { freshness: _freshness, ...event } = prepared.value;
  return event;
 }
}

/** True when the observation carries the compact heard section, which replaces raw sound perception events. */
export function hasHeardSection(observation) {
 return Array.isArray(observation?.heard) || Array.isArray(observation?.player?.heard);
}

/** Drops raw sound events from perception when heard already reports them; a no-op without heard. */
export function withoutHeardSoundEvents(perception, observation) {
 if (!hasHeardSection(observation) || !record(perception) || !Array.isArray(perception.events)) return perception;
 const events = perception.events.filter(event => event?.type !== 'sound');
 if (events.length === perception.events.length) return perception;
 return { ...perception, events, soundEventsInHeard: perception.events.length - events.length };
}

/** Applies withoutHeardSoundEvents to the observation-bearing parts of a tool result. */
export function presentHeardSounds(value) {
 if (!record(value)) return value;
 const strip = holder => record(holder) && record(holder.perception) ? { ...holder, perception: withoutHeardSoundEvents(holder.perception, holder) } : holder;
 let result = strip(value);
 if (record(result.observation)) result = { ...result, observation: strip(result.observation) };
 if (record(result.postAction?.observation)) result = { ...result, postAction: { ...result.postAction, observation: strip(result.postAction.observation) } };
 return result;
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
  const retained = typeof views?.retainEventMetadata === 'function' ? views.retainEventMetadata(value) : value;
  const eventView = typeof views?.prepareEvent === 'function' ? views.prepareEvent(retained) : retained;
  const encoded = encodeModelFacts(eventView);
  return encoded === value ? input : `${input.slice(0, separator + 1)}${JSON.stringify(encoded)}${input.slice(end)}`;
 } catch { return input; }
}
