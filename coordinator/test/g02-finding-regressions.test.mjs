import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentPlanner } from '../src/agent-planner.mjs';
import { AgentRegistry } from '../src/agent-registry.mjs';
import { PlanningScheduler, AdaptiveAdmissionController } from '../src/planning-scheduler.mjs';
import { ProviderHealthRegistry } from '../src/provider-health-registry.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { buildPlannerRequest, createContextCursor, PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';
import { FactLedger } from '../src/fact-ledger.mjs';
import { ConversationMemory } from '../src/conversation-memory.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
const profile = { agentId: 'g02', provider: 'codex', model: 'gpt-6-astra', reasoningEffort: 'high', serviceTier: 'priority', goalRevision: 1, lastSummary: 'Collected wood and located iron.' };
const decision = { summary: 'Continue', directive: 'continue', source: null };
const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, blocks: [], items: [], entities: [], inventory: { items: [], tagCounts: { '#minecraft:logs': 0 } } };
const registryStub = { get: () => profile, assertCurrentRevision: () => profile, setState() {} };
const coded = code => Object.assign(new Error(code), { code });

function contextFixture() {
	const facts = new FactLedger();
	facts.add({ key: 'route', fact: 'Use the west staircase', source: 'observation', tick: 1, dimension: 'minecraft:overworld', expiresAtTick: 1000, confidence: 1 });
	const memory = new ConversationMemory();
	memory.ingest({ sequence: 1, kind: 'PLAYER_MESSAGE', sourceId: 'operator', recipientId: profile.agentId, scope: 'direct', text: 'Keep the reserve diamonds', goalRevision: 1, observedAtEpochMs: 1 });
	const binding = { agentId: profile.agentId, profileFingerprint: profileFingerprint(profile), sessionGeneration: 1, goalRevision: 1, serverInstanceId: 'fixture-world' };
	const cursor = createContextCursor({ ...binding, factRevision: facts.delta(null).nextRevision, conversationSequence: memory.delta(null).nextSequence });
	return { facts, memory, binding, cursor, context: { factLedger: facts, conversationMemory: memory, contextCursor: cursor, contextBinding: binding, cursorBinding: cursor } };
}

for (const failure of ['PLANNING_TIMEOUT', 'MALFORMED_DECISION']) test(`classic ${failure} retry preserves the proper supplemental baseline`, async () => {
	const f = contextFixture();
	const sent = [];
	let generation = 1;
	const makeAgent = () => ({ sessionGeneration: generation, profileFingerprint: profileFingerprint(profile), async setGoalRevision() {}, async decide(input) { sent.push(input); if (sent.length === 1) throw coded(failure); return decision; } });
	let agent = makeAgent();
	const scheduler = new PlanningScheduler();
	const planner = new AgentPlanner({ registry: registryStub, scheduler, codexService: { getAgent: () => agent, createAgent: async () => agent, replaceAgent: async () => { generation++; return agent = makeAgent(); } } });
	try {
		const result = await planner.requestPlan({ agentId: profile.agentId, goalRevision: 1, ...buildPlannerRequest({ goal: 'Collect logs', goalRevision: 1 }, f.context) });
		assert.doesNotMatch(sent[0], /west staircase|reserve diamonds/);
		if (failure === 'PLANNING_TIMEOUT') assert.match(sent[1], /west staircase[\s\S]*reserve diamonds/);
		else assert.doesNotMatch(sent[1], /west staircase|reserve diamonds/);
		assert.equal(result.contextReceipt.sessionGeneration, generation);
		assert.equal(result.contextReceipt.factRevision, f.cursor.factRevision);
	} finally { scheduler.close(); }
});

test('prepared context survives an admission replacement without acknowledging later facts', async () => {
	const f = contextFixture();
	const request = buildPlannerRequest({ goal: 'Collect logs', goalRevision: 1 }, f.context);
	f.facts.add({ key: 'later', fact: 'Arrived during the request', source: 'observation', tick: 2, dimension: 'minecraft:overworld', expiresAtTick: 1000, confidence: 1 });
	const prepared = request.prepareInput({ sessionGeneration: 2, profileFingerprint: profileFingerprint(profile) });
	assert.match(prepared.input, /west staircase[\s\S]*reserve diamonds/);
	assert.doesNotMatch(prepared.input, /Arrived during/);
	assert.equal(prepared.contextReceipt.factRevision, f.cursor.factRevision);
	assert.ok(f.facts.delta(prepared.contextReceipt.factRevision).upserts.some(fact => fact.key === 'later'));
});

for (const queueMs of [0, 1200]) test(`goal translation attributes ${queueMs}ms scheduler wait separately from startup`, async () => {
	let now = 0;
	const rows = [], options = [], profiles = [];
	const health = new ProviderHealthRegistry();
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1 });
	let release;
	const blocker = queueMs ? scheduler.schedule('blocker', () => new Promise(resolve => { release = resolve; })) : null;
	if (blocker) await tick();
	const request = { requestId: '00000000-0000-4000-8000-000000000054', originalRequest: 'Obtain iron', candidateIds: ['minecraft:iron_ingot'] };
	const planner = new AgentPlanner({ registry: registryStub, scheduler, now: () => now, healthRegistry: health, turnRecorder: { record() {} }, telemetrySink: row => rows.push(row), codexService: {
		async createAgent(p) { profiles.push(p); now += 31; return { async setGoalRevision() {}, async decide(_input, opts) { options.push(opts); now += 7; return { requestId: request.requestId, summary: 'Obtain iron', predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_ingot', count: 1 } }; } }; }, async removeAgent() {},
	} });
	try {
		const run = planner.requestGoalSpec({ agentId: profile.agentId, request });
		if (blocker) { now += queueMs; release(); await blocker; }
		await run;
		assert.deepEqual(rows.map(row => row.queueWaitMs), [queueMs, queueMs]);
		assert.deepEqual(rows.map(row => row.durationMs), [31, 7]);
		assert.equal(options[0].queueWaitMs, queueMs);
		for (const operation of ['goal_spec_create', 'goal_spec']) assert.equal(health.snapshot({ provider: 'codex', model: 'gpt-6-luna', profileFingerprint: profileFingerprint(profiles[0]), operation }).count, 0);
	} finally { release?.(); scheduler.close(); }
});

test('auxiliary failed-request circuit survives retries and retires on cancellation', async () => {
	const health = new ProviderHealthRegistry();
	const scheduler = new PlanningScheduler();
	let creates = 0, auxiliary;
	const request = { requestId: '00000000-0000-4000-8000-000000000035', originalRequest: 'Obtain iron', candidateIds: ['minecraft:iron_ingot'] };
	const planner = new AgentPlanner({ registry: registryStub, scheduler, healthRegistry: health, codexService: { async createAgent(p) { auxiliary = p; creates++; throw coded('PROVIDER_UNAVAILABLE'); }, async removeAgent() {} } });
	try {
		for (let i = 0; i < 6; i++) await assert.rejects(planner.requestGoalSpec({ agentId: profile.agentId, request }));
		assert.equal(creates, 5);
		const identity = { provider: 'codex', model: 'gpt-6-luna', profileFingerprint: profileFingerprint(auxiliary), operation: 'goal_spec_create' };
		assert.equal(health.snapshot(identity).circuit, 'open');
		planner.cancelGoalSpec(profile.agentId, request.requestId);
		assert.equal(health.snapshot(identity).count, 0);
		assert.equal(health.canAttempt({ ...identity, profileFingerprint: profileFingerprint(profile) }), true);
	} finally { scheduler.close(); }
});

test('auxiliary cancellation waits for late provider telemetry before forgetting health', async () => {
	const health = new ProviderHealthRegistry();
	const scheduler = new PlanningScheduler();
	let auxiliary, finish;
	const request = { requestId: '00000000-0000-4000-8000-000000000036', originalRequest: 'Obtain iron', candidateIds: ['minecraft:iron_ingot'] };
	const planner = new AgentPlanner({ registry: registryStub, scheduler, healthRegistry: health, codexService: {
		async createAgent(p) { auxiliary = p; return { async setGoalRevision() {}, decide() { return new Promise(resolve => { finish = resolve; }); } }; }, async removeAgent() {},
	} });
	try {
		const run = planner.requestGoalSpec({ agentId: profile.agentId, request });
		const rejected = assert.rejects(run, error => error.code === 'PLAN_CANCELLED');
		await tick();
		planner.cancelGoalSpec(profile.agentId, request.requestId);
		finish({ requestId: request.requestId, summary: 'Obtain iron', predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_ingot', count: 1 } });
		await rejected;
		await tick();
		for (const operation of ['goal_spec_create', 'goal_spec']) assert.equal(health.snapshot({ provider: 'codex', model: 'gpt-6-luna', profileFingerprint: profileFingerprint(auxiliary), operation }).count, 0);
		assert.equal(scheduler.activeCount, 0);
	} finally { scheduler.close(); }
});

test('native adaptive feedback uses completion and provider pressure, not elapsed tool time', async () => {
	let now = 0;
	const controller = new AdaptiveAdmissionController({ planningMode: 'adaptive', configuredTarget: 8 });
	const rows = [];
	let failure = null;
	const agent = { async setGoalRevision() {}, async act(_input, opts) { now += 3; await opts.executeTool({ tool: { kind: 'action', arguments: { durationMs: 500000 } } }); now += 2; if (failure) throw coded(failure); return { status: 'completed' }; } };
	const planner = new AgentPlanner({ registry: registryStub, now: () => now,
		scheduler: { pressureSnapshot: { pendingOrdinary: 1 }, schedule: (_id, op) => op({ signal: new AbortController().signal }), observeProviderTelemetry: (row, pressure) => controller.observeProviderTelemetry(row, pressure) },
		codexService: { createAgent: async () => agent }, telemetrySink: row => rows.push(row),
	});
	const run = () => planner.requestNativeTurn({ agentId: profile.agentId, goalRevision: 1, input: 'Observe', executeTool: async () => { now += 500000; return { state: 'SUCCEEDED' }; } });
	for (let i = 0; i < 4; i++) await run();
	assert.equal(controller.target, 9);
	assert.equal(planner.getNativeDecisionTiming(profile.agentId).p95Ms, 3);
	failure = 'PLANNING_TIMEOUT'; await assert.rejects(run()); assert.equal(controller.target, 8);
	failure = 'PLAN_CANCELLED'; await assert.rejects(run()); assert.equal(controller.target, 8);
	const fixed = new AdaptiveAdmissionController({ configuredTarget: 8 });
	for (const row of rows) fixed.observeProviderTelemetry(row, { pendingOrdinary: 1 });
	assert.equal(fixed.target, 8);
});

function managerFixture(planner, extra = {}) {
	const registry = new AgentRegistry();
	const goal = { originalRequest: 'Collect eight logs', predicate: { type: 'inventory_contains', itemId: 'minecraft:oak_log', count: 8 }, createdAtTick: 1 };
	registry.register({ ...profile, state: 'STARTING', currentGoal: goal.originalRequest, currentGoalSpec: { ...goal, fingerprint: goalSpecFingerprint(goal) }, queue: [] });
	const errors = [], sent = [], completions = [];
	const manager = new ProgramRuntimeManager({ registry, bridge: { async send(type, agentId, payload) { sent.push({ type, agentId, payload }); } }, planner,
		reportError: (_id, error) => errors.push(error), onCompletionRequested: event => completions.push(event), ...extra });
	return { registry, manager, errors, sent, completions, record: () => registry.get(profile.agentId) };
}

for (const stale of [true, false]) test(`completion-correction rejection ${stale ? 'cannot escape disposed revision' : 'still reports current permanent failure'}`, async () => {
	let reject;
	const f = managerFixture({ requestPlan: () => new Promise((_resolve, fail) => { reject = fail; }) });
	try {
		await f.manager.installDecision(f.record(), { directive: 'finish' }, { observation, eventSequence: 1 });
		const completion = f.completions[0];
		f.manager.onCompletionResult(f.record(), { goalRevision: 1, traceId: completion.traceId, goalFingerprint: completion.goalFingerprint, verified: false, reasonCode: 'MISSING', facts: [] });
		await tick();
		if (stale) { const old = f.record(); f.registry.applyGoalControl(profile.agentId, { operation: 'steer', goalRevision: 2, goal: 'Keep working' }); f.manager.onGoalControl(old, 'steer'); }
		reject(coded(stale ? 'PLAN_CANCELLED' : 'UNSUPPORTED_PROTOCOL'));
		await tick();
		assert.equal(f.errors.length, stale ? 0 : 1);
		if (!stale) assert.equal(f.errors[0].code, 'COMPLETION_CORRECTION_FAILED');
	} finally { f.manager.disposeAll(); }
});

for (const failedAim of [false, true]) test(`shipped collection example reconsiders ${failedAim ? 'failed mining' : 'missing targets'} without pausing`, async () => {
	const requests = [];
	const f = managerFixture({ async requestPlan(request) { requests.push(request); return { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);' }; } });
	const source = /Multi-tree collection example:\n([\s\S]*?)\n\nWatcher example/.exec(PLANNER_SYSTEM_PROMPT)[1];
	const seen = failedAim ? { ...observation, blocks: [{ stableId: 'tree', blockId: 'minecraft:oak_log', x: 1, y: 64, z: 0, tags: ['#minecraft:logs'] }] } : observation;
	try {
		await f.manager.installDecision(f.record(), { directive: 'replace', source }, { observation: seen, eventSequence: 1 });
		await tick();
		if (failedAim) { const action = f.sent.find(row => row.type === 'action_command').payload; assert.equal(action.actionType, 'break_block'); await f.manager.onActionResult(f.record(), { actionId: action.actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_VISIBLE' }); await f.manager.onObservation(f.record(), { observation: seen, eventSequence: 2 }); await tick(); }
		assert.equal(requests.length, 1);
		assert.equal(f.record().state, 'ACTING');
		assert.equal(f.sent.filter(row => row.type === 'action_command').at(-1).payload.actionType, 'wait');
		assert.deepEqual(f.errors, []);
	} finally { f.manager.disposeAll(); }
});

test('lease replacement keeps factual recovery separate and retains the hard native cap', async () => {
	let options, replacement;
	const agent = { sessionGeneration: 1, profileFingerprint: profileFingerprint(profile), async setGoalRevision() {}, async act() { return { status: 'completed' }; } };
	const planner = new AgentPlanner({ registry: registryStub, scheduler: { schedule(_id, op, opts) { options = opts; return op({ signal: new AbortController().signal }); } }, codexService: { getAgent: () => agent, createAgent: async () => agent, replaceAgent: async (_record, opts) => { replacement = opts; return agent; } } });
	await planner.requestNativeTurn({ agentId: profile.agentId, goalRevision: 1, input: 'Observe', executeTool: async () => ({}) });
	assert.equal(options.maxLeaseDurationMs, 900000);
	await options.onLeaseExpired();
	assert.equal(replacement.recoverySummary, profile.lastSummary);
	assert.equal(replacement.resetReason, 'planning_lease_expired');
	assert.equal(replacement.expectedSessionGeneration, 1);
});

test('reactive acceptance reports captured revisions and the next request omits only delivered facts', async () => {
	const c = contextFixture();
	let cursor = c.cursor;
	c.facts.add({ key: 'new', fact: 'Newly observed route', source: 'observation', tick: 2, dimension: 'minecraft:overworld', expiresAtTick: 1000, confidence: 1 });
	const receipts = [], requests = [];
	const f = managerFixture({ async requestPlan(request) {
		const prepared = request.prepareInput({ sessionGeneration: 1, profileFingerprint: profileFingerprint(profile) });
		requests.push(prepared.input);
		return { ...decision, contextReceipt: prepared.contextReceipt };
	} }, { plannerContext: () => ({ ...c.context, contextCursor: cursor, cursorBinding: cursor }), onContextAccepted(_record, receipt) { receipts.push(receipt); cursor = receipt; } });
	try {
		await f.manager.installDecision(f.record(), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(10000);' }, { observation, eventSequence: 1 });
		f.manager.notifyAttention(f.record(), { priority: 'urgent', trigger: 'conversation' }); await tick();
		assert.equal(receipts.length, 1);
		assert.match(requests[0], /Newly observed route/);
		f.manager.notifyAttention(f.record(), { priority: 'urgent', trigger: 'conversation' }); await tick();
		assert.equal(receipts.length, 2);
		assert.doesNotMatch(requests[1], /Newly observed route/);
	} finally { f.manager.disposeAll(); }
});

test('intentional external blocker checkpoints retain their pause contract', async () => {
	let requests = 0;
	const f = managerFixture({ async requestPlan() { requests++; return decision; } });
	try {
		await f.manager.installDecision(f.record(), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.checkpoint("Need operator access");' }, { observation, eventSequence: 1 });
		assert.equal(f.record().state, 'PAUSED');
		await f.manager.onObservation(f.record(), { observation, eventSequence: 2, attention: true, priority: 'urgent' });
		await tick(); assert.equal(requests, 0);
	} finally { f.manager.disposeAll(); }
});
