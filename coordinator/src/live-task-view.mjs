import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';

const KINDS = ['inventory', 'world', 'milestone', 'manual'];
const STATES = ['pending', 'active', 'complete', 'lost'];
const MAX_PLAN_BYTES = 12_288;
export const TASK_PLAN_SCHEMA = {
 type: 'object', additionalProperties: false, required: ['steps'], properties: {
  steps: { type: 'array', maxItems: 48, items: { type: 'object', additionalProperties: false,
   required: ['id', 'label', 'kind', 'status', 'dependsOn', 'detail', 'evidence'], properties: {
    id: { type: 'string', pattern: '^[a-zA-Z0-9_-]{1,48}$' }, label: { type: 'string', minLength: 1, maxLength: 100 },
    kind: { type: 'string', enum: KINDS }, status: { type: 'string', enum: STATES },
    dependsOn: { type: 'array', maxItems: 12, items: { type: 'string', maxLength: 48 } },
    detail: { type: 'string', maxLength: 200 },
    evidence: { type: ['object', 'null'], additionalProperties: false,
     required: ['itemIds', 'count', 'dimension', 'x', 'y', 'z', 'blockId'], properties: {
      itemIds: { type: 'array', maxItems: 16, items: { type: 'string', maxLength: 128 } }, count: { type: 'integer', minimum: 1, maximum: 2147483647 },
      dimension: { type: ['string', 'null'], maxLength: 128 }, x: { type: ['integer', 'null'] }, y: { type: ['integer', 'null'] }, z: { type: ['integer', 'null'] }, blockId: { type: ['string', 'null'], maxLength: 128 },
     } },
   } } },
 } };

export function validateTaskPlan(value) {
 exact(value, ['steps']);
 if (!Array.isArray(value.steps) || value.steps.length > 48) throw new TypeError('Plan requires at most 48 steps');
 const steps = value.steps.map((v) => {
  exact(v, ['id', 'label', 'kind', 'status', 'dependsOn', 'detail', 'evidence']);
  if (!/^[a-zA-Z0-9_-]{1,48}$/.test(v.id) || !KINDS.includes(v.kind) || !STATES.includes(v.status)) throw new TypeError('Invalid plan step identity, kind or status');
  if (!Array.isArray(v.dependsOn) || v.dependsOn.length > 12 || new Set(v.dependsOn).size !== v.dependsOn.length) throw new TypeError('Invalid dependencies');
  let evidence = v.evidence;
  if (evidence !== null) {
   exact(evidence, ['itemIds', 'count', 'dimension', 'x', 'y', 'z', 'blockId']);
   if (!Array.isArray(evidence.itemIds) || evidence.itemIds.length > 16 || evidence.itemIds.some(id => !identifier(id)) || new Set(evidence.itemIds).size !== evidence.itemIds.length) throw new TypeError('Invalid inventory evidence');
   if (!Number.isSafeInteger(evidence.count) || evidence.count < 1 || evidence.count > 2147483647) throw new TypeError('Invalid evidence count');
   if (evidence.dimension !== null && !identifier(evidence.dimension) || evidence.blockId !== null && !identifier(evidence.blockId)) throw new TypeError('Invalid world identifier');
   for (const field of ['x', 'y', 'z']) if (evidence[field] !== null && (!Number.isSafeInteger(evidence[field]) || Math.abs(evidence[field]) > (field === 'y' ? 2048 : 30000000))) throw new TypeError('Invalid evidence position');
   evidence = structuredClone(evidence);
  }
  if (v.kind === 'inventory' && (!evidence || evidence.itemIds.length === 0)) throw new TypeError('Inventory steps require item evidence');
  if (v.kind === 'world' && (!evidence || !evidence.dimension || !evidence.blockId || ['x', 'y', 'z'].some(k => evidence[k] === null))) throw new TypeError('World steps require an exact block position and dimension');
  return { id: v.id, label: text(v.label, 100, false), kind: v.kind, status: v.status, dependsOn: [...v.dependsOn], detail: text(v.detail, 200, true), evidence };
 });
 const ids = new Set(steps.map(s => s.id));
 if (ids.size !== steps.length) throw new TypeError('Duplicate plan step ID');
 const visited = new Set(), visiting = new Set();
 function visit(id) {
  if (visiting.has(id)) throw new TypeError('Cyclic plan dependencies');
  if (visited.has(id)) return;
  const step = steps.find(s => s.id === id); if (!step) throw new TypeError('Unknown plan dependency');
  visiting.add(id); step.dependsOn.forEach(visit); visiting.delete(id); visited.add(id);
 }
 steps.forEach(s => visit(s.id));
 const result = { steps }; if (Buffer.byteLength(JSON.stringify(result)) > MAX_PLAN_BYTES) throw new TypeError('Plan exceeds 12 KiB');
 return result;
}

/** A factual, persistent view of agent-authored plans; never chooses game actions. */
export class LiveTaskViews {
 #directory; #states = new Map(); #advice = new Map(); #writes = Promise.resolve(); #now;
 constructor({ directory = null, now = Date.now } = {}) {
  if (typeof now !== 'function') throw new TypeError('Live task view clock must be a function');
  this.#directory = directory; this.#now = now;
 }
 suggest(agentId, goal, plan) { const checked=validateTaskPlan(plan); for(const step of checked.steps) step.status='pending'; this.#advice.set(`${agentId}:${goal}`, checked); }
 #state(record) {
  const identity = taskIdentity(record);
  let state = this.#states.get(record.agentId);
  if (!state || identity !== null && state.taskIdentity !== identity) {
   state = this.#newState(record);
   this.#states.set(record.agentId, state);
  }
  if (state.goalRevision !== record.goalRevision) { state.usage = null; state.usageSample = null; }
  state.goalRevision = record.goalRevision;
  return state;
 }
 #newState(record, fresh = false) {
  // Steering changes the planner prompt, not the immutable task or its saved scope.
  return { taskIdentity: taskIdentity(record), goal: record.currentGoalSpec?.originalRequest ?? record.currentGoal ?? '', goalRevision: record.goalRevision, plan: null, revision: 0, events: [], sequence: 0, usage: null, usageSample: null, allowance: null, worldId: null, observation: null, scope: null, loading: null, lastObserved: {}, verified: false, fresh };
 }
 async #load(record, state) {
  if (!state.worldId) return;
  const scope = createHash('sha256').update(JSON.stringify([state.worldId, record.agentId, state.goal])).digest('hex');
  if (state.scope === scope) { await state.loading; return; }
  state.scope = scope;
  state.loading = (async () => {
   let saved = null;
   if (this.#directory && !state.fresh) {
    try { saved = JSON.parse(await readFile(path.join(this.#directory, `${scope}.json`), 'utf8')); }
    catch (e) { if (e.code !== 'ENOENT' && this.#states.get(record.agentId) === state && state.scope === scope) this.event(record, 'status', 'Saved plan unavailable; the agent can publish a fresh plan.'); }
   }
   if (this.#states.get(record.agentId) !== state || state.scope !== scope) return;
   const wasFresh = state.fresh; state.fresh = false;
   if (saved?.plan) { try { state.plan = validateTaskPlan(saved.plan); state.revision = Number.isSafeInteger(saved.revision) ? Math.max(0, saved.revision) : 0; state.lastObserved=Object.fromEntries(Object.entries(saved.lastObserved ?? {}).filter(([id,at])=>state.plan.steps.some(s=>s.id===id) && Number.isSafeInteger(at) && at>=0)); } catch { /* invalid saved advice does not block play */ } }
   const adviceKey = `${record.agentId}:${record.currentGoalSpec?.originalRequest ?? state.goal}`;
   const advice = this.#advice.get(adviceKey);
   if (!state.plan && advice) { state.plan = structuredClone(advice); state.revision++; }
   this.#advice.delete(adviceKey);
   if (state.plan || wasFresh) { this.#reconcile(state); this.#save(state); }
  })();
  await state.loading;
 }
 async observe(record, observation) {
  const state = this.#state(record), world = observation.world?.worldId ?? null;
  if (world && state.worldId && world !== state.worldId) { state.plan = null; state.revision = 0; state.scope = null; state.lastObserved={}; state.verified=false; state.usage=null; state.usageSample=null; }
  if (world) state.worldId = world;
  state.observation = observation;
  await this.#load(record, state);
  if (this.#states.get(record.agentId) !== state) return;
  if (this.#reconcile(state)) { state.revision++; this.#save(state); }
 }
 #reconcile(state) {
  const o = state.observation; if (!o || !state.plan) return false;
  let changed = false;
  for (const step of state.plan.steps) {
   let status = step.status;
   if (step.kind === 'inventory') {
    if (o.player?.dead) status = step.status === 'complete' ? 'lost' : step.status === 'active' ? 'pending' : step.status;
    else if (o.ready === true) {
     const count = (o.inventory?.items ?? []).filter(v => step.evidence.itemIds.includes(v.itemId)).reduce((a, v) => a + v.count, 0);
     status = count >= step.evidence.count ? 'complete' : step.status === 'complete' ? 'lost' : step.status;
    }
   } else if (step.kind === 'world' && o.ready === true && o.world?.dimension === step.evidence.dimension) {
    const e = step.evidence, block = (o.blocks ?? []).find(b => b.x === e.x && b.y === e.y && b.z === e.z);
    if (block) {
     status = block.blockId === e.blockId ? 'complete' : step.status === 'complete' ? 'lost' : step.status;
     const observedAt = o.observedAtEpochMs ?? null;
     if (state.lastObserved[step.id] !== observedAt) { state.lastObserved[step.id] = observedAt; changed = true; }
    }
   }
   if (status !== step.status) { step.status = status; changed = true; }
  }
  return changed;
 }
 #save(state) {
  if (!this.#directory || !state.scope) return;
  const filename = path.join(this.#directory, `${state.scope}.json`);
  const plan = state.plan ? { steps: state.plan.steps.map(({ lastObservedAt, ...s }) => s) } : null;
  const content = JSON.stringify({ version: 1, plan, revision: state.revision, lastObserved: state.lastObserved });
  this.#writes = this.#writes.catch(() => {}).then(async () => {
   await mkdir(this.#directory, { recursive: true }); const temp = `${filename}.${randomUUID()}.tmp`;
   await writeFile(temp, content, 'utf8'); await rename(temp, filename);
  });
  void this.#writes.catch(() => {});
 }
 async operate(record, tool) {
  const state = this.#state(record); await this.#load(record, state);
  if (this.#states.get(record.agentId) !== state || state.goalRevision !== record.goalRevision) throw Object.assign(new Error('Task changed while loading its plan'), { code: 'STALE_NATIVE_TOOL' });
  if (tool.operation === 'replace') {
   if (!record.currentGoal) throw new Error('No active goal to replan');
   if (!state.worldId) throw new Error('A fresh world observation is required before saving a plan');
   const plan = validateTaskPlan(tool.plan);
   // Evidence, not an asserted green inventory/world node, supplies factual completion.
   for (const s of plan.steps) if (['inventory', 'world'].includes(s.kind) && s.status === 'complete') {
    const previous = state.plan?.steps.find(p => p.id === s.id && p.status === 'complete' && p.kind === s.kind && JSON.stringify(p.evidence) === JSON.stringify(s.evidence));
    if (s.kind !== 'world' || !previous) s.status = 'pending';
   }
   state.plan = plan; state.lastObserved=Object.fromEntries(Object.entries(state.lastObserved).filter(([id])=>plan.steps.some(s=>s.id===id))); state.revision++; this.#reconcile(state); this.#save(state);
  }
  return { goal: state.goal, revision: state.revision, plan: structuredClone(state.plan), advisory: true };
 }
 event(record, stage, value) {
  const state = this.#state(record);
  if (stage === 'live_usage') { try { updateUsage(state, record, JSON.parse(value), this.#now()); } catch {} return; }
  if (stage === 'live_allowance') { try { state.allowance = allowance(JSON.parse(value)); } catch {} return; }
  const message = sanitizeDiagnosticText(String(value), { maxBytes: 1024 });
  if (!message) return;
  if (['live_delta','live_summary'].includes(stage) && state.events.at(-1)?.stage === stage) state.events.at(-1).message = message;
  else state.events.push({ sequence: ++state.sequence, stage: text(stage, 40, false), message, at: this.#now() });
  while (Buffer.byteLength(JSON.stringify(state.events)) > 8192) state.events.shift();
 }
 snapshot(record) {
  const s = this.#state(record);
  return { goalRevision: record.goalRevision, goal: s.goal, active: Boolean(record.currentGoal), verified: s.verified, revision: s.revision, plan: structuredClone(s.plan), lastObserved: { ...s.lastObserved }, events: [...s.events], usage: s.usage, allowance: s.allowance, generatedAt: this.#now() };
 }
 verified(record) { this.#state(record).verified=true; }
 begin(record, { fresh = false } = {}) {
  // Explicit new tasks discard previous terminal output and task milestones, including identical text.
  if (fresh) {
   const previous = this.#states.get(record.agentId), state = this.#newState(record, true);
   this.#states.set(record.agentId, state);
   // Clear a reused task file immediately, even if the coordinator restarts before the first new sample.
   if (previous?.worldId) {
    state.scope = createHash('sha256').update(JSON.stringify([previous.worldId, record.agentId, state.goal])).digest('hex');
    this.#save(state); state.scope = null;
   }
  }
  else {
   const previous = this.#states.get(record.agentId);
   // Legacy records lack an immutable spec. Explicit steer/resume retains their
   // existing task, while start/replace always takes the fresh branch above.
   if (previous?.taskIdentity?.startsWith('prompt:') && !record.currentGoalSpec?.fingerprint) previous.taskIdentity = taskIdentity(record);
   this.#state(record).verified=false;
  }
 }
 async flush() { await this.#writes; }
}
function taskIdentity(record) {
 if (record.currentGoalSpec?.fingerprint) return `spec:${record.currentGoalSpec.fingerprint}`;
 return record.currentGoal ? `prompt:${record.currentGoal}` : null;
}
function numericUsage(value) {
 const out = {}; for (const key of ['inputTokens', 'cachedInputTokens', 'outputTokens', 'reasoningOutputTokens', 'totalTokens']) if (Number.isSafeInteger(value?.[key]) && value[key] >= 0) out[key] = value[key];
 if (out.inputTokens !== undefined && out.cachedInputTokens !== undefined && out.cachedInputTokens <= out.inputTokens) out.uncachedInputTokens = out.inputTokens - out.cachedInputTokens;
 return Object.keys(out).length ? out : null;
}
function updateUsage(state, record, value, fallbackNow) {
 const totals = numericUsage(value); if (totals === null) return;
 const at = Number.isSafeInteger(value?.reportedAtEpochMs) && value.reportedAtEpochMs >= 0 ? value.reportedAtEpochMs : fallbackNow;
 const latest = numericUsage(value?.last);
 const usage = { ...totals, ...Object.fromEntries(Object.entries(latest ?? {}).map(([key, number]) => [`last${key[0].toUpperCase()}${key.slice(1)}`, number])) };
 // Identity is private bookkeeping. The transmitted display contract remains
 // flat safe integers and each provider update replaces its cumulative totals.
 const session = JSON.stringify([record.provider, record.model, record.reasoningEffort, record.serviceTier, state.worldId, typeof value?.threadId === 'string' ? value.threadId : null]);
 const previous = state.usageSample;
 const reset = !previous || previous.session !== session || at < previous.at
  || Object.entries(totals).some(([key, count]) => previous.totals[key] !== undefined && count < previous.totals[key]);
 if (!reset) {
  const changed = Object.entries(totals).some(([key, count]) => previous.totals[key] !== count);
  if (!changed) {
   // A repeated cumulative notification is not a new zero-token model call.
   Object.assign(usage, Object.fromEntries(Object.entries(state.usage ?? {}).filter(([key]) => key === 'observedElapsedMs' || key.startsWith('interval') || key.endsWith('PerMinute'))));
   state.usage = usage; return;
  }
  const elapsed = at - previous.at;
  if (elapsed > 0 && Number.isSafeInteger(elapsed)) {
   usage.observedElapsedMs = elapsed;
   for (const key of ['inputTokens', 'cachedInputTokens', 'uncachedInputTokens', 'outputTokens']) {
    if (totals[key] === undefined || previous.totals[key] === undefined) continue;
    const delta = totals[key] - previous.totals[key];
    if (delta < 0) continue;
    usage[`interval${key[0].toUpperCase()}${key.slice(1)}`] = delta;
    const rate = Math.round(delta / elapsed * 60_000);
    if (Number.isSafeInteger(rate) && rate >= 0) usage[`${key}PerMinute`] = rate;
   }
  }
 }
 state.usage = usage;
 state.usageSample = { session, at, totals };
}
function allowance(value) {
 const out = {}; for (const key of ['primary', 'secondary']) if (Number.isFinite(value?.[key]?.usedPercent)) out[key] = { usedPercent: value[key].usedPercent, windowDurationMins: value[key].windowDurationMins ?? null };
 return Object.keys(out).length ? out : null;
}
function identifier(v) { return typeof v === 'string' && v.length <= 128 && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(v); }
function text(v, max, empty) { if (typeof v !== 'string' || v.length > max || (!empty && !v.trim()) || /[\x00-\x1f\x7f]/.test(v)) throw new TypeError('Invalid plan text'); return v; }
function exact(v, keys) { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(k => !keys.includes(k)) || keys.some(k => !Object.hasOwn(v, k))) throw new TypeError('Invalid plan fields'); }
