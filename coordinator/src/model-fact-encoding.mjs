import { isDeepStrictEqual } from 'node:util';

export const MODEL_FACT_FORMAT = 'minecraft-facts-v1';
export const MODEL_FACT_INSTRUCTIONS = `Compact factual replies use format:"minecraft-facts-v1": data is the original JSON, {$rows:{columns,rows}} represents an array of records in column order, and {$ref:N} is the exact value at values[N] in this reply. {$object:[[key,value],...]} preserves literal objects with reserved keys. Null, missing fields, coverage and freshness keep their meanings. Complete replies need no previous reply. observe defaults to a complete snapshot with observationView.id; view:"changes" plus afterObservationId requests changed whole sections against that exact ID. Apply observationView.replace and remove to that baseline; retain unchanged sections. Missing, stale or different-world baselines return full. Request view:"full" whenever the baseline is uncertain.`;
const MARKERS = new Set(['$ref', '$rows', '$object']);
const bytes = value => Buffer.byteLength(JSON.stringify(value), 'utf8');
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const marked = value => record(value) && Object.keys(value).length === 1 && MARKERS.has(Object.keys(value)[0]);

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
     const table = { $rows: { columns, rows: current.map(row => columns.map(column => pack(row[column]))) } };
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
 #baseline = null;
 #sequence = 0;
 #generation = 0;
 reset() { this.#baseline = null; this.#generation++; }

 prepare(value, tool) {
  if (tool.kind !== 'observe' || !record(value.observation)) return { value, commit() {} };
  const observation = value.observation;
  const id = `observation-${++this.#sequence}`;
  const world = observation.world;
  const identity = typeof world?.worldId === 'string' && typeof world?.dimension === 'string' ? JSON.stringify([world.worldId, world.dimension, observation.player?.dead === true, observation.continuity?.phase ?? null]) : null;
  const eligible = identity !== null && value.freshness?.fresh === true && observation.player?.dead !== true;
  const next = { id, identity, observation: structuredClone(observation) };
  const full = { ...value, observationView: { id, mode: 'full' } };
  let presented = full;
  const previous = this.#baseline;
  if (eligible && tool.view === 'changes' && previous?.id === tool.afterObservationId && previous.identity === identity) {
   const replace = Object.fromEntries(Object.entries(observation).filter(([key, current]) => !Object.hasOwn(previous.observation, key) || !isDeepStrictEqual(previous.observation[key], current)));
   const remove = Object.keys(previous.observation).filter(key => !Object.hasOwn(observation, key));
   const { observation: _full, ...metadata } = value;
   const changes = { ...metadata, observationView: { id, mode: 'changes', baseId: previous.id, replace, remove } };
   if (bytes(changes) < bytes(full)) presented = changes;
  }
  const generation = this.#generation;
  return { value: presented, commit: () => { if (generation === this.#generation) this.#baseline = eligible ? next : null; } };
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
  const encoded = encodeModelFacts(value);
  return encoded === value ? input : `${input.slice(0, separator + 1)}${JSON.stringify(encoded)}${input.slice(end)}`;
 } catch { return input; }
}
