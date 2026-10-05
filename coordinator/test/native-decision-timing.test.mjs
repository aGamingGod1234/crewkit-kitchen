import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentPlanner } from '../src/agent-planner.mjs';
import { NativeDecisionTimingWindow } from '../src/native-decision-timing.mjs';
import { createExecutionSettings } from '../src/provider-identity.mjs';

const AGENT_ID = 'timing-agent';

test('native request timing includes collector arrival and reports execution queue wait separately', async () => {
	let clock = 0;
	const record = nativeRecord('model-a');
	const rows = [];
	const agent = {
		executionSettings: createExecutionSettings(record, { transport: 'test', controlProtocol: 'native_tools' }),
		async setGoalRevision() {},
		async act(_input, options) {
			clock = 17;
			options.onProgress({ phase: 'tool_queued', callId: 'queued-call', toolName: 'observe', requestArrivedAt: clock });
			clock = 57;
			await options.executeTool({ callId: 'queued-call', requestArrivedAt: 17, queueWaitMs: 40, tool: { kind: 'observe' } });
			return { status: 'completed', toolCalls: 1 };
		},
	};
	const planner = new AgentPlanner({ registry: registryFor(() => record), now: () => clock,
		scheduler: immediateScheduler, nativeTimingSink: (event, fields) => rows.push({ event, fields }),
		codexService: { async createAgent() { return agent; }, getAgent() { return null; } } });
	await planner.requestNativeTurn({ agentId: AGENT_ID, input: 'inspect', goalRevision: 1,
		executeTool: async () => { clock += 3; return { state: 'SUCCEEDED' }; } });
	assert.equal(planner.getNativeDecisionTiming(AGENT_ID).firstToolRequestP50Ms, 17);
	assert.equal(rows.find(row => row.event === 'native_tool_queue_timing').fields.queueWaitMs, 40);
	assert.equal(rows.find(row => row.event === 'native_tool_queue_timing').fields.arrivalObserved, true);
});

test('native timing windows bound samples and keep failed or cancelled segments out of percentiles', () => {
	const window = new NativeDecisionTimingWindow({ windowSize: 2 });
	window.recordProviderSegment({ durationMs: 10, outcome: 'completed', sample: true });
	window.recordProviderSegment({ durationMs: 20, outcome: 'failed', sample: true });
	window.recordProviderSegment({ durationMs: 30, outcome: 'cancelled', sample: true });
	window.recordProviderSegment({ durationMs: 40, outcome: 'completed', sample: false });
	window.recordFirstToolRequest(10);
	window.recordFirstToolRequest(20);
	window.recordFirstToolRequest(30);

	assert.deepEqual(window.snapshot(), {
		identity: null,
		count: 1,
		lifetimeCount: 1,
		completedSegmentCount: 2,
		lifetimeSegmentCount: 4,
		failedSegmentCount: 1,
		cancelledSegmentCount: 1,
		p50Ms: 10,
		p95Ms: 10,
		sampleWindowSize: 2,
		firstToolRequestCount: 2,
		firstToolRequestLifetimeCount: 3,
		firstToolRequestP50Ms: 20,
		firstToolRequestP95Ms: 30,
		firstUsableToolCount: 0,
		firstUsableToolLifetimeCount: 0,
		firstUsableToolP50Ms: null,
		firstUsableToolP95Ms: null,
	});
});

test('native planner records provider-only tool-boundary segments and emits bounded timing summaries', async () => {
	let clock = 0;
	let toolCall = 0;
	const record = nativeRecord('model-a');
	const sinkRows = [];
	const agent = {
		executionSettings: createExecutionSettings(record, { transport: 'test', controlProtocol: 'native_tools' }),
		async setGoalRevision() {},
		async act(_input, options) {
			clock += 17;
			assert.equal((await options.executeTool({ tool: { kind: 'action', actionType: 'wait', arguments: {} } })).state, 'FAILED');
			clock += 23;
			assert.equal((await options.executeTool({ tool: { kind: 'action', actionType: 'wait', arguments: {} } })).state, 'SUCCEEDED');
			return { status: 'completed', toolCalls: 2 };
		},
	};
	const planner = new AgentPlanner({
		registry: registryFor(() => record),
		now: () => clock,
		nativeDecisionTimingWindowSize: 4,
		nativeTimingSink: (event, fields) => { sinkRows.push({ event, fields }); return Promise.reject(new Error('diagnostic sink unavailable')); },
		scheduler: immediateScheduler,
		codexService: {
			async createAgent() { return agent; },
			getAgent() { return null; },
		},
	});
	const result = await planner.requestNativeTurn({
		agentId: AGENT_ID, input: 'keep working', goalRevision: 1,
		executeTool: async () => { if (toolCall++ === 0) { clock += 100; return { state: 'FAILED' }; } clock += 5; return { state: 'SUCCEEDED' }; },
	});

	assert.equal(result.toolCalls, 2);
	const timing = planner.getNativeDecisionTiming(AGENT_ID);
	assert.equal(timing.count, 2, 'only tool-boundary provider segments feed planning percentiles');
	assert.equal(timing.lifetimeCount, 2);
	assert.equal(timing.lifetimeSegmentCount, 3, 'the completed turn tail is counted but excluded from planning samples');
	assert.equal(timing.failedSegmentCount, 0);
	assert.equal(timing.cancelledSegmentCount, 0);
	assert.equal(timing.p50Ms, 17);
	assert.equal(timing.p95Ms, 23);
	assert.equal(timing.firstToolRequestP50Ms, 17);
	assert.equal(timing.firstUsableToolP50Ms, 145, 'usable result elapsed time includes tool execution');
	assert.equal(sinkRows.some(({ event }) => event === 'native_first_tool_requested'), true);
	assert.equal(sinkRows.some(({ event }) => event === 'native_first_tool_result'), true);
	const summaries = sinkRows.filter(({ event }) => event === 'native_decision_timing');
	assert.equal(summaries.length, 3);
	assert.equal(summaries.at(-1).fields.timingCount, 2);
	assert.equal(summaries.at(-1).fields.timingP95Ms, 23);
	assert.equal(summaries.at(-1).fields.agentId, AGENT_ID);
});

test('cancelled native provider segments are visible without becoming planning samples', async () => {
	let clock = 0;
	const record = nativeRecord('model-a');
	const planner = new AgentPlanner({
		registry: registryFor(() => record),
		now: () => clock,
		scheduler: immediateScheduler,
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async act() { clock += 9; throw Object.assign(new Error('stale'), { code: 'STALE_PLAN' }); },
				};
			},
			getAgent() { return null; },
		},
	});
	await assert.rejects(planner.requestNativeTurn({ agentId: AGENT_ID, input: 'stop', goalRevision: 1, executeTool: async () => ({ state: 'SUCCEEDED' }) }), { code: 'STALE_PLAN' });
	const timing = planner.getNativeDecisionTiming(AGENT_ID);
	assert.equal(timing.count, 0);
	assert.equal(timing.lifetimeCount, 0);
	assert.equal(timing.lifetimeSegmentCount, 1);
	assert.equal(timing.cancelledSegmentCount, 1);
	assert.equal(timing.p95Ms, null);
});

test('native timing samples stay segregated when the selected model changes', async () => {
	let clock = 0;
	let current = nativeRecord('model-a');
	const planner = new AgentPlanner({
		registry: registryFor(() => current),
		now: () => clock,
		scheduler: immediateScheduler,
		codexService: {
			async createAgent() {
				const record = current;
				return {
					executionSettings: createExecutionSettings(record, { transport: 'test', controlProtocol: 'native_tools' }),
					async setGoalRevision() {},
					async act(_input, options) { clock += record.model === 'model-a' ? 10 : 90; await options.executeTool({ tool: { kind: 'action', actionType: 'wait', arguments: {} } }); return { status: 'completed', toolCalls: 1 }; },
				};
			},
			getAgent() { return null; },
		},
	});
	const executeTool = async () => ({ state: 'SUCCEEDED' });
	await planner.requestNativeTurn({ agentId: AGENT_ID, input: 'one', goalRevision: 1, executeTool });
	assert.equal(planner.getNativeDecisionTiming(AGENT_ID).p95Ms, 10);
	current = nativeRecord('model-b');
	await planner.requestNativeTurn({ agentId: AGENT_ID, input: 'two', goalRevision: 1, executeTool });
	const timing = planner.getNativeDecisionTiming(AGENT_ID);
	assert.equal(timing.identity.model, 'model-b');
	assert.equal(timing.count, 1);
	assert.equal(timing.p95Ms, 90);
});

const immediateScheduler = {
	schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
	cancel() { return false; },
};

function nativeRecord(model) {
	return { agentId: AGENT_ID, provider: 'codex', model, reasoningEffort: 'xhigh', serviceTier: 'priority', goalRevision: 1 };
}

function registryFor(getRecord) {
	return {
		assertCurrentRevision() { return getRecord(); },
		get() { return getRecord(); },
		setState() {},
	};
}
