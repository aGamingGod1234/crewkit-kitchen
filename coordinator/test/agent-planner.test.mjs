import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentPlanner } from '../src/agent-planner.mjs';
import { DynamicAgentState } from '../src/agent-registry.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';

const AGENT_ID = 'agent-1';
const GOAL_REVISION = 7;
const RECORD = Object.freeze({
	agentId: AGENT_ID,
	provider: 'kimi',
	model: 'kimi-code/k3',
	reasoningEffort: 'high',
	serviceTier: 'fast',
	goalRevision: GOAL_REVISION,
});
const VALID_DECISION = Object.freeze({
	summary: 'Wait safely',
	directive: 'replace',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(100);',
});

test('ArenaScript planning explicitly creates an ArenaScript provider session', async () => {
	const registry = new FakeRegistry();
	const optionsSeen = [];
	const planner = createPlannerForService(registry, {
		async createAgent(_record, options) {
			optionsSeen.push(options);
			return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } };
		},
		getAgent() { return null; },
		async removeAgent() { return false; },
	});
	await planner.requestPlan({ agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION });
	assert.deepEqual(optionsSeen, [{ recoverySummary: null, controlProtocol: 'arena_script' }]);
});

test('retries one malformed planner decision with bounded corrective feedback', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const invalid = Object.assign(new Error('planner output was not JSON'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(inputs.length, 2);
	assert.equal(inputs[0], 'authoritative state');
	assert.match(inputs[1], /corrective retry 1/);
	assert.match(inputs[1], /MALFORMED_DECISION/);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('streams safe planner, provider, output, retry, and decision events without letting callback failures affect planning', async () => {
	const registry = new FakeRegistry();
	const events = [];
	let calls = 0;
	const invalid = Object.assign(new Error('planner output was not JSON'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide(_input, options) {
			options.onVerbose('output', 'Visible provider response');
			calls += 1;
			if (calls === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		onVerbose(stage, message) {
			events.push({ stage, message });
			throw new Error('verbose consumer unavailable');
		},
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(new Set(events.map(({ stage }) => stage)), new Set(['planner', 'provider', 'output', 'retry', 'decision']));
	assert.equal(events.every(({ message }) => message.length <= 256), true);

	agent.decide = async () => { throw Object.assign(new Error('authorization: Bearer provider-secret; stderr contained a private response body'), { code: 'FATAL_PROVIDER_ERROR' }); };
	const failureEvents = [];
	await assert.rejects(planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		onVerbose(stage, message) { failureEvents.push({ stage, message }); },
	}), (error) => error?.code === 'FATAL_PROVIDER_ERROR');
	assert.equal(failureEvents.some(({ stage }) => stage === 'error'), true, 'terminal planning errors are observable');
	const errorMessages = failureEvents.filter(({ stage }) => stage === 'error').map(({ message }) => message).join(' ');
	assert.match(errorMessages, /FATAL_PROVIDER_ERROR/);
	assert.doesNotMatch(errorMessages, /provider-secret|private response body|authorization|stderr/i);
});

test('keeps one trace across a malformed decision retry and records queue/provider/parse boundaries once', async () => {
	const registry = new FakeRegistry();
	const rows = [];
	const times = [100, 104, 110, 118, 121, 130, 136, 140, 141];
	let attempt = 0;
	const invalid = Object.assign(new Error('invalid output'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide() {
			attempt += 1;
			if (attempt === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		invalidDecisionRetries: 1,
		now: () => times.shift(),
		benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { return agent; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		traceId: 'trace-planner-retry',
	});

	assert.equal(result.traceId, 'trace-planner-retry');
	assert.equal(new Set(rows.map((row) => row.context.traceId)).size, 1);
	assert.equal(rows.every((row) => row.context.traceId === 'trace-planner-retry'), true);
	assert.equal(rows.filter((row) => row.stage === 'queue_wait').length, 1);
	const phases = new Set(['queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse']);
	assert.deepEqual(rows.filter((row) => phases.has(row.stage)).map((row) => row.stage), [
		'queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse',
	]);
	assert.equal(rows.find((row) => row.stage === 'parse').fields.retryReason, 'MALFORMED_DECISION');
});

test('clock regression never emits a regressing raw phase and poisons the trace', async () => {
	const registry = new FakeRegistry();
	const latencyRegistry = new ControlLatencyRegistry();
	const rows = [];
	const times = [100, 90, 91, 92, 93, 94];
	const planner = new AgentPlanner({
		registry,
		latencyRegistry,
		now: () => times.shift(),
		benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } }; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION, traceId: 'trace-clock-regression' });
	assert.equal(rows.some((row) => row.stage === 'queue_wait' && row.fields.startMonotonicMs > row.fields.endMonotonicMs), false);
	const summary = latencyRegistry.completeTrace('trace-clock-regression');
	assert.equal(summary.complete, false);
	assert.equal(summary.totalMs, null);
});

test('retries compact envelope validation mismatches with corrective feedback', async () => {
	for (const invalid of [
		{ code: 'DECISION_FIELD_MISMATCH', message: 'replace directive requires nonblank source' },
		{ code: 'DECISION_FIELD_MISMATCH', message: 'finish directive requires status completed or impossible' },
	]) {
		const registry = new FakeRegistry();
		const inputs = [];
		const error = Object.assign(new Error(invalid.message), { code: invalid.code });
		const agent = {
			async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
			async decide(input) {
				inputs.push(input);
				if (inputs.length === 1) throw error;
				return VALID_DECISION;
			},
		};
		const planner = createPlanner(registry, agent, 1);

		const result = await planner.requestPlan({
			agentId: AGENT_ID,
			input: 'authoritative state',
			goalRevision: GOAL_REVISION,
		});

		assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
		assert.equal(inputs.length, 2);
		assert.match(inputs[1], new RegExp(`corrective retry 1[\\s\\S]*${invalid.code}`));
		assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
	}
});

test('retries a duplicate decision envelope through the same provider agent', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	let creates = 0;
	const duplicate = Object.assign(new Error("Duplicate decision field 'source'"), { code: 'DUPLICATE_DECISION_FIELD' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw duplicate;
			return VALID_DECISION;
		},
	};
	const planner = createPlannerForService(registry, {
		async createAgent() { creates += 1; return agent; },
		getAgent() { return null; }, async removeAgent() { return false; },
	}, 1);
	const result = await planner.requestPlan({
		agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION,
	});
	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(creates, 1, 'corrective retry must retain the selected provider agent session');
	assert.equal(inputs.length, 2);
	assert.match(inputs[1], /DUPLICATE_DECISION_FIELD/);
});

test('returns a legacy action-array rejection to the same selected model as correction feedback', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	let creates = 0;
	const legacy = Object.assign(new Error('Legacy action-array decisions are not supported'), { code: 'INVALID_DECISION' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw legacy;
			return VALID_DECISION;
		},
	};
	const planner = createPlannerForService(registry, {
		async createAgent() { creates += 1; return agent; },
		getAgent() { return null; }, async removeAgent() { return false; },
	}, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID, input: '{"summary":"old","actions":[]}', goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(creates, 1);
	assert.equal(inputs.length, 2);
	assert.match(inputs[1], /INVALID_DECISION/);
});

test('retries one transient provider failure without changing authoritative input', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const transient = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw transient;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(inputs, ['authoritative state', 'authoritative state']);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('routes planning through the provider lane and preserves priority and provider identity across retries', async () => {
	const registry = new FakeRegistry();
	const scheduleCalls = [];
	const createdRecords = [];
	const transient = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	let decideAttempts = 0;
	const planner = new AgentPlanner({
		registry,
		scheduler: {
			schedule(agentId, operation, options) {
				scheduleCalls.push({ agentId, options });
				return operation({ signal: new AbortController().signal });
			},
			cancel() { return false; },
		},
		codexService: {
			async createAgent(record) {
				createdRecords.push(record);
				return {
					async setGoalRevision() {},
					async decide() {
						decideAttempts += 1;
						if (decideAttempts === 1) throw transient;
						return VALID_DECISION;
					},
				};
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		priority: 'urgent',
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(scheduleCalls, [{ agentId: AGENT_ID, options: { lane: RECORD.provider, priority: 'urgent' } }]);
	assert.equal(createdRecords.length, 1);
	assert.deepEqual(createdRecords[0], RECORD, 'urgent provider retry retains the exact selected profile');
	assert.equal(decideAttempts, 2);
});

test('retries one empty Codex turn without changing authoritative input', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const emptyTurn = Object.assign(new Error('Codex turn completed without an agent message'), {
		code: 'MISSING_AGENT_MESSAGE',
	});
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw emptyTurn;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(inputs, ['authoritative state', 'authoritative state']);
});

test('exhausted empty Codex turns remain planning for quiet observation retry', async () => {
	const registry = new FakeRegistry();
	const emptyTurn = Object.assign(new Error('Codex turn completed without an agent message'), {
		code: 'MISSING_AGENT_MESSAGE',
	});
	const agent = {
		async setGoalRevision() {},
		async decide() { throw emptyTurn; },
	};
	const planner = createPlanner(registry, agent, 1);
	await assert.rejects(planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), (error) => error === emptyTurn);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('exhausted retryable provider errors enter error after the retry budget', async () => {
	const registry = new FakeRegistry();
	const timeout = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const agent = {
		async setGoalRevision() {},
		async decide() { throw timeout; },
	};
	const planner = createPlanner(registry, agent, 1);
	await assert.rejects(planner.requestPlan({
		agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION,
	}), (error) => error === timeout);
	assert.deepEqual(registry.states.at(-1), {
		state: DynamicAgentState.ERROR,
		options: {
			goalRevision: GOAL_REVISION,
			error: { code: 'PLANNING_TIMEOUT', message: 'provider timed out' },
		},
	});
});

test('retries one transient provider initialization failure', async () => {
	const registry = new FakeRegistry();
	let createAttempts = 0;
	const transient = Object.assign(new Error('CLI did not start'), { code: 'SPAWN_FAILED' });
	const agent = {
		async setGoalRevision() {},
		async decide() { return VALID_DECISION; },
	};
	const planner = createPlannerForService(registry, {
		async createAgent() {
			createAttempts += 1;
			if (createAttempts === 1) throw transient;
			return agent;
		},
		getAgent() { return null; },
		async removeAgent() { return false; },
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(createAttempts, 2);
});

test('records a non-transient provider initialization failure', async () => {
	const registry = new FakeRegistry();
	const failure = Object.assign(new Error('login required'), { code: 'AUTHENTICATION_REQUIRED' });
	const planner = createPlannerForService(registry, {
		async createAgent() { throw failure; },
		getAgent() { return null; },
		async removeAgent() { return false; },
	});

	await assert.rejects(() => planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), failure);

	assert.deepEqual(registry.states.at(-1), {
		state: DynamicAgentState.ERROR,
		options: {
			goalRevision: GOAL_REVISION,
			error: { code: 'AUTHENTICATION_REQUIRED', message: 'login required' },
		},
	});
});

test('enters error only after malformed decision retries are exhausted', async () => {
	const registry = new FakeRegistry();
	let attempts = 0;
	const invalid = Object.assign(new Error('invalid output'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide() {
			attempts += 1;
			throw invalid;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	await assert.rejects(() => planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), invalid);

	assert.equal(attempts, 2);
	assert.deepEqual(registry.states.at(-1), {
		state: DynamicAgentState.ERROR,
		options: {
			goalRevision: GOAL_REVISION,
			error: { code: 'MALFORMED_DECISION', message: 'invalid output' },
		},
	});
});

test('records strict provider-attempt telemetry without planner input or output', async () => {
	const registry = new FakeRegistry();
	const telemetry = [];
	let time = 100;
	const planner = new AgentPlanner({
		registry,
		invalidDecisionRetries: 1,
		now: () => (time += 10),
		telemetrySink: (row) => telemetry.push(row),
		healthRegistry: { canAttempt: () => true, record: () => {} },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async decide() { return VALID_DECISION; },
						sessionMetadata() {
						return { profileFingerprint: `sha256:${'a'.repeat(64)}`, sessionGeneration: 3, sessionState: 'warm', sessionReuse: true, resetReason: null };
					},
				};
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'private prompt value', goalRevision: GOAL_REVISION });
	assert.deepEqual(telemetry.map((row) => row.operation), ['create_agent', 'decide']);
	assert.ok(telemetry.every((row) => row.provider === 'kimi' && row.model === 'kimi-code/k3'));
	assert.equal(JSON.stringify(telemetry).includes('private prompt value'), false);
	assert.ok(telemetry.every((row) => row.durationMs >= 0 && row.queueWaitMs >= 0));
	assert.ok(telemetry.every((row) => row.profileFingerprint === `sha256:${'a'.repeat(64)}` && row.sessionGeneration === 3));
	assert.equal(telemetry.find((row) => row.operation === 'decide').sessionReuse, true);
});

test('feeds redacted provider telemetry to the scheduler after health recording', async () => {
	const registry = new FakeRegistry();
	const order = [];
	let observed = null;
	const healthRegistry = {
		canAttempt: () => true,
		record: (row) => { order.push(`health:${row.operation}`); },
	};
	const scheduler = {
		schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
		cancel() { return false; },
		pressureSnapshot: { pendingOrdinary: 1 },
		observeProviderTelemetry(row, snapshot) {
			order.push(`scheduler:${row.operation}`);
			observed = { row, snapshot };
		},
	};
	const telemetry = [];
	const planner = new AgentPlanner({
		registry,
		healthRegistry,
		scheduler,
		telemetrySink: (row) => { order.push(`sink:${row.operation}`); telemetry.push(row); },
		codexService: {
			async createAgent() { return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } }; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(order, ['health:create_agent', 'scheduler:create_agent', 'sink:create_agent', 'health:decide', 'scheduler:decide', 'sink:decide']);
	assert.equal(observed.row.operation, 'decide');
	assert.deepEqual(observed.snapshot, { pendingOrdinary: 1 });
	assert.equal(telemetry.at(-1).errorCode, null);
});

test('provider circuit rejects work before allocating a provider session', async () => {
	const registry = new FakeRegistry();
	let creates = 0;
	const planner = new AgentPlanner({
		registry,
		healthRegistry: { canAttempt: () => false, record: () => { throw new Error('must not record'); } },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { creates += 1; throw new Error('must not create'); },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(
		planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION }),
		(error) => error?.code === 'PROVIDER_CIRCUIT_OPEN',
	);
	assert.equal(creates, 0);
	assert.equal(registry.states.at(-1).options.error.code, 'PROVIDER_CIRCUIT_OPEN');
});

test('native turn keeps scheduler and selected Codex profile while delegating body execution', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const states = [];
	const registry = {
		assertCurrentRevision(agentId, goalRevision) {
			assert.equal(agentId, AGENT_ID);
			assert.equal(goalRevision, GOAL_REVISION);
			return nativeRecord;
		},
		setState(_agentId, state, options) { states.push({ state, options }); },
	};
	const calls = [];
	const executeTool = async () => ({ state: 'SUCCEEDED' });
	const agent = {
		async setGoalRevision(revision) { calls.push(['revision', revision]); },
		async act(input, options) {
			calls.push(['act', input, options.goalRevision, options.executeTool]);
			return { status: 'completed', toolCalls: 2 };
		},
	};
	const planner = new AgentPlanner({
		registry,
		scheduler: {
			schedule(_agentId, operation, options) { calls.push(['schedule', options]); return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent(profile, options) { calls.push(['create', profile, options]); return agent; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	assert.deepEqual(await planner.requestNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'event: goal started',
		executeTool,
		priority: 'urgent',
	}), { status: 'completed', toolCalls: 2 });
	assert.deepEqual(calls.find((call) => call[0] === 'create')[2], { recoverySummary: null, controlProtocol: 'native_tools' });
	assert.equal(typeof calls.find((call) => call[0] === 'act')[3], 'function');
	assert.equal(states[0].state, DynamicAgentState.PLANNING);
});

test('native turn leaves lifecycle error decisions to the coordinator', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const states = [];
	const failure = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision() { return nativeRecord; },
			setState(_agentId, state, options) { states.push({ state, options }); },
		},
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async act() { throw failure; },
				};
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(planner.requestNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'event: goal started',
		executeTool: async () => ({ state: 'SUCCEEDED' }),
	}), (error) => error === failure);
	assert.deepEqual(states.map(({ state }) => state), [DynamicAgentState.PLANNING]);
});

test('urgent native steering reuses the active selected-model turn without scheduler admission', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const calls = [];
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision(agentId, goalRevision) {
				assert.equal(agentId, AGENT_ID);
				assert.equal(goalRevision, GOAL_REVISION);
				return nativeRecord;
			},
		},
		scheduler: {
			schedule() { throw new Error('steering must not consume another scheduler slot'); },
			cancel() { return false; },
		},
		codexService: {
			getAgent(agentId) {
				assert.equal(agentId, AGENT_ID);
				return { async steer(input, options) { calls.push({ input, options }); return { turnId: 'turn-active' }; } };
			},
			async createAgent() { throw new Error('steering must not create another agent session'); },
			async removeAgent() { return false; },
		},
	});

	assert.deepEqual(await planner.steerNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'urgent event: direct message from Lucas',
	}), { turnId: 'turn-active' });
	assert.deepEqual(calls, [{
		input: 'urgent event: direct message from Lucas',
		options: { goalRevision: GOAL_REVISION },
	}]);
});

function createPlanner(registry, agent, invalidDecisionRetries) {
	return createPlannerForService(registry, {
		async createAgent() { return agent; },
		getAgent() { return null; },
		async removeAgent() { return false; },
	}, invalidDecisionRetries);
}

function createPlannerForService(registry, codexService, invalidDecisionRetries = 1) {
	return new AgentPlanner({
		registry,
		invalidDecisionRetries,
		scheduler: {
			schedule(_agentId, operation) {
				return operation({ signal: new AbortController().signal });
			},
			cancel() { return false; },
		},
		codexService,
	});
}

class FakeRegistry {
	states = [];

	assertCurrentRevision(agentId, goalRevision) {
		assert.equal(agentId, AGENT_ID);
		assert.equal(goalRevision, GOAL_REVISION);
		return RECORD;
	}

	setState(_agentId, state, options) {
		this.states.push({ state, options });
	}
}


test('passes the exact optional turn recorder to the selected provider without changing the decision attempt', async () => {
	const registry = new FakeRegistry();
	const recorder = { record: async () => { throw new Error('recorder unavailable'); } };
	const optionsSeen = [];
	let clock = 100;
	let attempts = 0;
	const agent = {
		async setGoalRevision() {},
		async decide(input, options) {
			attempts += 1;
			optionsSeen.push({ input, options });
			try { await options.turnRecorder.record({ input }); } catch { /* provider hooks are observational */ }
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		turnRecorder: recorder,
		now: () => clock,
		scheduler: { schedule(_id, operation) { clock = 137; return operation({ signal: new AbortController().signal }); }, cancel() { return false; } },
		codexService: { async createAgent() { return agent; }, getAgent() { return null; }, async removeAgent() { return false; } },
	});

	const result = await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(attempts, 1);
	assert.equal(optionsSeen[0].options.turnRecorder, recorder);
	assert.equal(optionsSeen[0].options.queueWaitMs, 37);
});

test('preserves attempt and retry metadata through corrective provider retries', async () => {
	const registry = new FakeRegistry();
	const records = [];
	const recorder = { async record(row) { records.push(row); } };
	let calls = 0;
	const invalid = Object.assign(new Error('bad decision'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide(input, options) {
			await options.turnRecorder.record({ attempt: options.attempt, retry: options.retry, input });
			calls += 1;
			if (calls === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		turnRecorder: recorder,
		scheduler: { schedule(_id, operation) { return operation({ signal: new AbortController().signal }); }, cancel() { return false; } },
		codexService: { async createAgent() { return agent; }, getAgent() { return null; }, async removeAgent() { return false; } },
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(records.map(({ attempt, retry }) => ({ attempt, retry })), [
		{ attempt: 1, retry: false },
		{ attempt: 2, retry: true },
	]);
});
