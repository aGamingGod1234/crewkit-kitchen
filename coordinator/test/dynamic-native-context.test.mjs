import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';
import { encodeNativeEventInput, decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';
import { SOURCE, FakeBridge, DeferredCompletionBridge, GatedAgentReadyBridge, ThrowingPlanningRegistry, FakeProvider, FakePlanner, RecordingGoalSupervisor, record, factToWireObservation, pickProfile, immutableGoalSpec, eventually, ManualTimerQueue, start, resolveNativeSteerInput } from './fixtures/dynamic-main-fixture.mjs';

for (const boundary of ['goal revision', 'disconnect', 'connection replacement']) {
	test(`native inspection cancellation fences delayed replies across ${boundary}`, async () => {
		const bridge = new FakeBridge();
		bridge.automaticInspections = false;
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		const timers = new ManualTimerQueue();
		const outcomes = [];
		planner.requestNativeTurn = async (request) => {
			planner.requests.push(request);
			const outcome = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: `query-${planner.requests.length}`, callId: 'observe', tool: { kind: 'observe' } })
				.then((result) => ({ result }), (error) => ({ error }));
			outcomes.push(outcome);
			return { status: 'completed', toolCalls: 1 };
		};
		const run = await start({ bridge, registry, planner, goalSchedule: timers.schedule, cancelGoalSchedule: timers.cancel, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		try {
			bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Read current surroundings.' } });
			bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 1, y: 64, z: 0 } } } });
			await eventually(() => bridge.sent.some(({ type }) => type === 'inspection_request'));
			const oldQuery = bridge.sent.find(({ type }) => type === 'inspection_request');
			if (boundary === 'goal revision') {
				bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'Read the changed surroundings.' } });
			} else if (boundary === 'disconnect') {
				bridge.emit('disconnected', { connectionEpoch: 1 });
			} else {
				bridge.emit('ready', { connectionEpoch: 2, serverInstanceId: 'test', registry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Read current surroundings.', goalRevision: 1 }] });
			}
			await eventually(() => outcomes.length === 1);
			assert.equal(outcomes[0].error?.code, { disconnect: 'BRIDGE_DISCONNECTED', 'goal revision': 'INSPECTION_CANCELLED', 'connection replacement': 'STALE_CONNECTION_EPOCH' }[boundary]);
			if (boundary === 'disconnect') {
				await eventually(() => registry.get('agent-a').state === DynamicAgentState.DISCONNECTED);
				bridge.emit('ready', { connectionEpoch: 2, serverInstanceId: 'test', registry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Read current surroundings.', goalRevision: 1 }] });
				await eventually(() => bridge.sent.some(({ type, connectionEpoch }) => type === 'agent_ready' && connectionEpoch === 2));
			}
			const revision = boundary === 'goal revision' ? 2 : 1;
			bridge.emit('observation', { agentId: 'agent-a', connectionEpoch: bridge.connectionEpoch, payload: { goalRevision: revision, eventSequence: 4, observation: { player: { x: 9, y: 64, z: 0 } } } });
			await eventually(() => bridge.sent.filter(({ type }) => type === 'inspection_request').length === 2);
			const currentQuery = bridge.sent.filter(({ type }) => type === 'inspection_request')[1];
			bridge.replyInspection(oldQuery, { observation: factToWireObservation({ player: { x: 100, y: 64, z: 0 } }, 1, 99, true, 99), eventSequence: 99 });
			await new Promise((resolve) => setImmediate(resolve));
			assert.equal(outcomes.length, 1, 'retired correlation cannot settle the replacement query');
			bridge.sampleInspection(currentQuery);
			await eventually(() => outcomes.length === 2);
			assert.equal(outcomes[1].error, undefined);
			assert.equal(outcomes[1].result.observation.player.x, 9);
			assert.equal(outcomes[1].result.eventSequence, 5);
			assert.equal(outcomes[1].result.freshness.fresh, true);
		} finally {
			await run.coordinator.stop();
		}
	});
}

test('an unfinished native turn with no tools requests a fresh observation instead of stopping', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		await timers.runNext();
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'request_observation').length, 1);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PLANNING);
	} finally {
		await run.coordinator.stop();
	}
});

test('a rejected native planning transition releases its supervisor token', async () => {
	const timers = new ManualTimerQueue();
	const registry = new ThrowingPlanningRegistry();
	const run = await start({
		registry,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		await eventually(() => timers.pendingCount === 1);
		registry.rejectPlanning = true;
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'agent_error'));
		assert.equal(timers.pendingCount, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('recovery-policy failures stay active and schedule bounded fresh-fact recovery', async () => {
	for (const code of [
		'PROVIDER_TIMEOUT', 'REQUEST_TIMEOUT', 'PLANNING_TIMEOUT', 'PROCESS_TERMINATION_FAILED',
		'AUTHENTICATION_REQUIRED', 'INVALID_PROVIDER_OUTPUT', 'PROVIDER_DOWN',
		'SESSION_GENERATION_MISMATCH', 'TURN_NOT_ACTIVE', 'UNKNOWN_RESPONSE_ID',
	]) {
		const timers = new ManualTimerQueue();
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestNativeTurn = async (request) => {
			planner.requests.push(request);
			throw Object.assign(new Error(`provider failure ${code}`), { code });
		};
		const run = await start({
			registry,
			planner,
			goalSchedule: timers.schedule,
			cancelGoalSchedule: timers.cancel,
			config: {
				bridge: { port: 25570, secret: 's'.repeat(32) },
				codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
			},
		});
		try {
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
			await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
			await timers.runNext();
			assert.equal(run.bridge.sent.filter(({ type }) => type === 'request_observation').length, 1, code);
			assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false, code);
			assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.ERROR, code);
			assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.PAUSED, code);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('ArenaScript infrastructure failure replaces the exact session and retries once from fresh facts', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) throw Object.assign(new Error('provider planning timed out'), { code: 'PLANNING_TIMEOUT' });
		return withCompletionContract({ summary: 'Recovered.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const provider = new FakeProvider();
	const session = { sessionGeneration: 3 };
	const replacements = [];
	provider.getAgent = () => session;
	provider.replaceAgent = async (profile, options) => { replacements.push({ profile, options }); return session; };
	const run = await start({
		registry,
		planner,
		codexService: provider,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'arena_script', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 1 && replacements.length === 1 && timers.pendingCount === 1);
		assert.equal(replacements[0].profile.agentId, 'agent-a');
		assert.equal(replacements[0].options.expectedSessionGeneration, 3);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PLANNING);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false);
		await timers.runNext();
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'request_observation').length, 1);

		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 2 && run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.equal(replacements.length, 1);
		assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.PAUSED);
		assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.ERROR);
	} finally {
		await run.coordinator.stop();
	}
});

test('circuit recovery defers observations until the absolute probe deadline and uses the latest facts once', async () => {
	let epochNow = 1_000;
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) {
			throw Object.assign(new Error('circuit is cooling down'), { code: 'PROVIDER_CIRCUIT_OPEN', nextProbeAtEpochMs: 5_000 });
		}
		assert.match(request.input, /"x":3/);
		return withCompletionContract({ summary: 'Probe recovered.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({
		registry,
		planner,
		epochNow: () => epochNow,
		goalClock: () => epochNow,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 1 && timers.pendingCount === 1);
		assert.equal(timers.history.at(-1).delay, 4_000);
		for (const [eventSequence, x] of [[2, 1], [3, 2]]) {
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence, observation: { player: { x, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		}
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(attempts, 1, 'fresh observations are coalesced while the circuit owns its deadline');
		epochNow = 5_000;
		await timers.runNext();
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'request_observation').length, 1);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 4, observation: { player: { x: 3, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 2 && run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.equal(attempts, 2);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('an observation pending behind a circuit failure cannot bypass its probe deadline', async () => {
	let epochNow = 1_000;
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	let rejectFirst;
	const first = new Promise((_resolve, reject) => { rejectFirst = reject; });
	planner.requestPlan = (request) => {
		planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) return first;
		assert.match(request.input, /"x":2/);
		return Promise.resolve(withCompletionContract({ summary: 'Probe recovered.', directive: 'replace', source: SOURCE }, request.goalRevision));
	};
	const run = await start({
		registry,
		planner,
		epochNow: () => epochNow,
		goalClock: () => epochNow,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 1);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await new Promise((resolve) => setImmediate(resolve));
		rejectFirst(Object.assign(new Error('circuit is cooling down'), { code: 'PROVIDER_CIRCUIT_OPEN', nextProbeAtEpochMs: 5_000 }));
		await eventually(() => timers.pendingCount === 1);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(attempts, 1, 'pending provider work waits for the exact-profile circuit deadline');

		epochNow = 5_000;
		await timers.runNext();
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 3, observation: { player: { x: 2, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 2);
		assert.equal(attempts, 2);
	} finally {
		rejectFirst?.(Object.assign(new Error('test cleanup'), { code: 'STALE_PLAN' }));
		await run.coordinator.stop();
	}
});

test('native circuit recovery also defers observations until its absolute probe deadline', async () => {
	let epochNow = 1_000;
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) {
			throw Object.assign(new Error('circuit is cooling down'), { code: 'PROVIDER_CIRCUIT_OPEN', nextProbeAtEpochMs: 5_000 });
		}
		assert.match(request.input, /"x":2/);
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		epochNow: () => epochNow,
		goalClock: () => epochNow,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 1 && timers.pendingCount === 1);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(attempts, 1);

		epochNow = 5_000;
		await timers.runNext();
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 3, observation: { player: { x: 2, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => attempts === 2);
		assert.equal(attempts, 2);
	} finally {
		await run.coordinator.stop();
	}
});

test('a replaced native goal fences an already queued recovery callback', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		const staleRecovery = timers.history.at(-1).callback;
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'New goal.' } });
		await eventually(() => run.registry.get('agent-a').goalRevision === 2);
		await staleRecovery();
		assert.equal(run.bridge.sent.some(({ type, payload }) => type === 'request_observation' && payload.goalRevision === 1), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('an invalid higher-revision control cannot retire the live native goal', async () => {
	const timers = new ManualTimerQueue();
	const run = await start({
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.STARTING && timers.pendingCount === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 2, goal: 'Invalid replacement.' } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'agent_error'));
		assert.equal(run.registry.get('agent-a').goalRevision, 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.STARTING);
		assert.equal(timers.pendingCount, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('a replace control starts the new goal without consuming the queued head', async () => {
	const run = await start();
	try {
		run.bridge.sent.length = 0;
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Old goal.', updatedAtEpochMs: 1,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'queue', goalRevision: 1, goal: 'Queued goal.', updatedAtEpochMs: 2,
		} });
		await eventually(() => run.registry.get('agent-a').queue.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'replace', goalRevision: 2, goal: 'New goal.', updatedAtEpochMs: 3,
		} });
		await eventually(() => run.bridge.sent.some(({ type, payload }) =>
			type === 'agent_ready' && payload.goalRevision === 2));
		const replaced = run.registry.get('agent-a');
		assert.equal(replaced.currentGoal, 'New goal.');
		assert.deepEqual(replaced.queue.map((entry) => entry.goal), ['Queued goal.']);
		assert.equal(run.planner.interruptions.length, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('bridge queue rejection reaches the registry before a later queued goal starts', async () => {
	const removedFields = { originalRequest: 'Removed block goal', predicate: { type: 'operator_confirmed' }, createdAtTick: 2 };
	const removedSpec = { ...removedFields, fingerprint: goalSpecFingerprint(removedFields) };
	const laterFields = { originalRequest: 'Valid later goal', predicate: { type: 'operator_confirmed' }, createdAtTick: 3 };
	const laterSpec = { ...laterFields, fingerprint: goalSpecFingerprint(laterFields) };
	const run = await start();
	try {
		run.bridge.sent.length = 0;
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Current goal', updatedAtEpochMs: 1,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'queue', goalRevision: 1, goal: removedFields.originalRequest,
			goalSpec: removedSpec, updatedAtEpochMs: 2,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'queue', goalRevision: 1, goal: laterFields.originalRequest,
			goalSpec: laterSpec, updatedAtEpochMs: 3,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'complete', goalRevision: 2, updatedAtEpochMs: 4,
		} });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.COMPLETED);

		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'dequeue', goalRevision: 2, goal: removedFields.originalRequest,
			goalSpec: removedSpec, updatedAtEpochMs: 5,
		} });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 3, goal: laterFields.originalRequest,
			goalSpec: laterSpec, updatedAtEpochMs: 6,
		} });

		await eventually(() => run.registry.get('agent-a').goalRevision === 3);
		const promoted = run.registry.get('agent-a');
		assert.equal(promoted.currentGoal, laterFields.originalRequest);
		assert.deepEqual(promoted.currentGoalSpec, laterSpec);
		assert.deepEqual(promoted.queue, []);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'agent_error'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('back-to-back accepted controls do not publish stale lifecycle side effects', async () => {
	const run = await start();
	try {
		run.bridge.sent.length = 0;
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'New goal.' } });
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'agent_ready' && payload.goalRevision === 2));
		assert.deepEqual(
			run.bridge.sent.filter(({ type }) => type === 'agent_ready').map(({ payload }) => payload.goalRevision),
			[2],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('a superseded control cannot emit stale lifecycle events after an awaited acknowledgement', async () => {
	const bridge = new GatedAgentReadyBridge();
	const run = await start({ bridge });
	const revisions = [];
	run.coordinator.on('goalControl', (record) => revisions.push(record.goalRevision));
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		await eventually(() => bridge.blocked);
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'New goal.' } });
		bridge.release();
		await eventually(() => revisions.includes(2));
		assert.deepEqual(revisions, [2]);
	} finally {
		bridge.release();
		await run.coordinator.stop();
	}
});

test('an invalid higher-revision conversation wake cannot retire the live native goal', async () => {
	const timers = new ManualTimerQueue();
	const run = await start({
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.STARTING && timers.pendingCount === 1);
		run.bridge.emit('conversation_wake', {
			agentId: 'agent-a',
			payload: {
				transactionId: 'invalid-higher-revision-wake',
				event: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Replace this goal.', goalRevision: 1, observedAtEpochMs: 1 },
				control: { operation: 'start', goalRevision: 2, updatedAtEpochMs: 2, goal: 'Invalid replacement.' },
			},
		});
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.registry.get('agent-a').goalRevision, 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.STARTING);
		assert.equal(timers.pendingCount, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('agent removal terminates ArenaScript supervision for the removed goal', async () => {
	const goalSupervisor = new RecordingGoalSupervisor();
	const removedProfiles = [];
	const run = await start({ goalSupervisor, runtimeHooks: { onRemoved: (agentId) => removedProfiles.push(agentId) } });
	try {
		run.bridge.emit('goal_control', {
			agentId: 'agent-a',
			payload: { operation: 'start', goalRevision: 1, goal: 'Gather wood.' },
		});
		await eventually(() => run.registry.get('agent-a')?.goalRevision === 1);
		goalSupervisor.terminations.length = 0;

		const removal = [];
		run.bridge.emit('agent_removed', { agentId: 'agent-a', payload: { goalRevision: 1 }, waitUntil: pending => removal.push(pending) });
		await Promise.all(removal);
		await eventually(() => run.registry.get('agent-a') === null);

		assert.equal(goalSupervisor.terminations.length, 1);
		assert.equal(goalSupervisor.terminations[0].goalRevision, 1);
		assert.deepEqual(removedProfiles, ['agent-a']);
	} finally {
		await run.coordinator.stop();
	}
});

test('direct conversation and composite wake activate bounded ArenaScript supervision', async () => {
	const goalSupervisor = new RecordingGoalSupervisor();
	const run = await start({ goalSupervisor });
	try {
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: {
				sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
				text: 'Hello?', goalRevision: 0, observedAtEpochMs: 1,
			},
		});
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(goalSupervisor.activations.length, 0, 'conversation without a goal does not create supervision work');
		run.bridge.emit('goal_control', {
			agentId: 'agent-a',
			payload: { operation: 'start', goalRevision: 1, goal: 'Gather wood.' },
		});
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'agent_ready' && payload.goalRevision === 1));
		goalSupervisor.activations.length = 0;

		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: {
				sequence: 2, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
				text: 'Keep going.', goalRevision: 1, observedAtEpochMs: 2,
			},
		});
		await eventually(() => goalSupervisor.activations.length === 1);
		assert.equal(goalSupervisor.activations[0].goalRevision, 1);
		run.bridge.emit('goal_control', {
			agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 2 },
		});
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.PAUSED);

		run.bridge.emit('conversation_wake', {
			agentId: 'agent-a',
			payload: {
				transactionId: 'arena-wake-0001',
				event: {
					sequence: 3, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
					text: 'Resume now.', goalRevision: 2, observedAtEpochMs: 3,
				},
				control: { operation: 'start', goalRevision: 2, updatedAtEpochMs: 4, goal: 'Gather wood.' },
			},
		});
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'conversation_wake_ack' && payload.goalRevision === 2));
		assert.equal(goalSupervisor.activations.length, 2);
		assert.equal(goalSupervisor.activations[1].goalRevision, 2);
	} finally {
		await run.coordinator.stop();
	}
});

test('native model-authored sequence keeps lifecycle acting until its final body result', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		if (planner.requests.length > 0) return { status: 'completed', toolCalls: 0 };
		planner.requests.push(request);
		await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-sequence-1',
			callId: 'call-sequence-1',
			tool: {
				kind: 'sequence',
				actions: [
					{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
					{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
				],
			},
		});
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Mine stone.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [{ x: 2, y: 64, z: 1, blockId: 'minecraft:stone' }], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING);
		const first = run.bridge.sent.filter((message) => message.type === 'action_command')[0];
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, eventSequence: 2 } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 2);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING);
		const second = run.bridge.sent.filter((message) => message.type === 'action_command')[1];
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: second.payload.actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, eventSequence: run.bridge.latestSequences.get('agent-a') + 1 } });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.PLANNING);
	} finally {
		await run.coordinator.stop();
	}
});

test('native lookAround holds an action lease and acting state across its camera steps', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const goalSupervisor = new RecordingGoalSupervisor();
	const leases = [];
	const released = [];
	goalSupervisor.begin = (key, kind) => {
		const token = { ...key, kind, operationId: `operation-${leases.length}` };
		leases.push(token);
		return token;
	};
	goalSupervisor.end = (token) => released.push(token);
	let swept = false;
	planner.requestNativeTurn = async (request) => {
		if (swept) return { status: 'completed', toolCalls: 0 };
		swept = true;
		planner.requests.push(request);
		await request.executeTool({
			agentId: request.agentId, goalRevision: request.goalRevision,
			turnId: 'turn-look-around', callId: 'call-look-around',
			tool: { kind: 'lookAround', centerYaw: 0, pitch: 0, steps: 2, ticksPerStep: 2 },
		});
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry, planner, goalSupervisor,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	const commands = () => run.bridge.sent.filter(({ type }) => type === 'action_command');
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Look for trees.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => commands().length === 1);
		const actionLease = leases.find(({ kind }) => kind === 'action');
		assert.ok(actionLease, 'camera sweeps need the same action watchdog as other body tools');
		for (let index = 0; index < 2; index += 1) {
			await eventually(() => commands().length === index + 1);
			assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING);
			assert.equal(released.includes(actionLease), false);
			assert.equal(commands()[index].payload.actionType, 'control');
			run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: commands()[index].payload.actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, eventSequence: index * 2 + 2 } });
		}
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.PLANNING);
		assert.equal(released.filter((token) => token === actionLease).length, 1);
		assert.equal(leases.filter(({ kind }) => kind === 'action').length, 1);
	} finally { await run.coordinator.stop(); }
});

test('native programs and asynchronous action tools acquire body leases while frontier queries stay read-only', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const bridge = new FakeBridge();
	const goalSupervisor = new RecordingGoalSupervisor();
	const leases = [];
	const released = [];
	const dispatched = [];
	let currentTool;
	let done = false;
	goalSupervisor.begin = (key, kind) => {
		const token = { ...key, kind, operationId: `operation-${leases.length}` };
		leases.push(token);
		return token;
	};
	goalSupervisor.end = (token) => released.push(token);
	const send = bridge.send.bind(bridge);
	bridge.send = async (type, agentId, payload, options) => {
		await send(type, agentId, payload, options);
		if (type === 'action_cancel') queueMicrotask(() => bridge.emit('action_result', { agentId, payload: { goalRevision: 1, actionId: payload.actionId, state: 'CANCELLED', reasonCode: 'MODEL_CANCELLED', executionStarted: true, eventSequence: 20 } }));
		if (type === 'action_command') {
			assert.equal(registry.get(agentId).state, DynamicAgentState.ACTING);
			const actionLease = leases.findLast(({ kind }) => kind === 'action');
			assert.ok(actionLease);
			assert.equal(released.includes(actionLease), false);
			dispatched.push(currentTool);
			if (currentTool !== 'start_action') queueMicrotask(() => bridge.emit('action_result', { agentId, payload: { goalRevision: 1, actionId: payload.actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, eventSequence: 20 + dispatched.length } }));
		}
		if (type === 'inspection_request' && currentTool === 'explore_frontier') {
			assert.equal(registry.get(agentId).state, DynamicAgentState.PLANNING);
			assert.equal(leases.filter(({ kind }) => kind === 'action').length, 4);
		}
	};
	planner.requestNativeTurn = async (request) => {
		if (done) return { status: 'completed', toolCalls: 0 };
		planner.requests.push(request);
		let call = 0;
		const execute = async (tool) => {
			currentTool = tool.kind;
			const result = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'body-tools', callId: `body-${++call}`, tool });
			assert.equal(registry.get(request.agentId).state, DynamicAgentState.PLANNING);
			return result;
		};
		const handle = await execute({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1000 } });
		assert.equal(handle.state, 'RUNNING');
		await execute({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 1, actionType: 'wait', arguments: { durationMs: 1 } });
		const program = await execute({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);' });
		assert.equal(program.reasonCode, 'PROGRAM_EXHAUSTED');
		await execute({ kind: 'explore_frontier', arguments: {} });
		done = true;
		return { status: 'completed', toolCalls: call };
	};
	const run = await start({ bridge, registry, planner, goalSupervisor, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const errors = [];
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Inspect and wait.' } });
		bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		try { await eventually(() => done || errors.length > 0 || bridge.sent.some(({ type }) => type === 'agent_error')); }
		catch (error) { throw new Error(JSON.stringify({ currentTool, dispatched, messages: bridge.sent.slice(-4) }), { cause: error }); }
		assert.equal(bridge.sent.some(({ type }) => type === 'agent_error'), false, JSON.stringify(bridge.sent.filter(({ type }) => type === 'agent_error')));
		assert.deepEqual(errors, []);
		assert.deepEqual(dispatched, ['start_action', 'replace_action', 'run_program']);
		const actionLeases = leases.filter(({ kind }) => kind === 'action');
		assert.equal(actionLeases.length, 4, 'detached action ownership survives its tool-call lease');
		assert.equal(leases.filter(({ kind }) => kind === 'program').length, 1);
		assert.equal(released.includes(leases.find(({ kind }) => kind === 'program')), true);
		for (const lease of actionLeases) assert.equal(released.filter((token) => token === lease).length, 1);
	} finally { await run.coordinator.stop(); }
});

test('a pre-disconnect native completion cannot complete the replacement lifecycle', async () => {
	const bridge = new DeferredCompletionBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-stale-completion',
			callId: 'finish-stale-completion',
			tool: { kind: 'finish', summary: 'Done.' },
		});
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		bridge,
		registry,
		planner,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Finish safely.', goalSpec: immutableGoalSpec('Finish safely.'),
		} });
		bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => bridge.sent.some(({ type }) => type === 'goal_completed'));
		const completion = bridge.sent.find(({ type }) => type === 'goal_completed');
		bridge.emit('goal_completion_result', { agentId: 'agent-a', payload: {
			goalRevision: 1,
			traceId: completion.payload.traceId,
			goalFingerprint: completion.payload.goalFingerprint,
			verified: true,
			reasonCode: 'COMPLETION_VERIFIED',
			facts: [],
		} });
		bridge.emit('disconnected');
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.DISCONNECTED);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.DISCONNECTED);
	} finally {
		await run.coordinator.stop();
	}
});

test('operator confirmation wait drops ordinary continuation but admits urgent input and a new goal', async () => {
	const bridge = new DeferredCompletionBridge();
	const registry = new AgentRegistry();
	const timers = new ManualTimerQueue();
	const planner = new FakePlanner(registry);
	let releaseFirstTurn;
	const firstTurn = new Promise((resolve) => { releaseFirstTurn = resolve; });
	let settleFirstTurn;
	const firstTurnSettled = new Promise((resolve) => { settleFirstTurn = resolve; });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const firstRequest = planner.requests.length === 1;
		if (firstRequest) {
			const result = await request.executeTool({
				agentId: request.agentId,
				goalRevision: request.goalRevision,
				turnId: 'turn-awaiting-confirmation',
				callId: 'finish-awaiting-confirmation',
				tool: { kind: 'finish', summary: 'The logs are collected.' },
			});
			assert.equal(result.state, 'AWAITING_OPERATOR_CONFIRMATION');
			await firstTurn;
		}
		if (firstRequest) settleFirstTurn();
		return { status: 'completed', toolCalls: firstRequest ? 1 : 0 };
	};
	const run = await start({
		bridge,
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	const observation = (eventSequence, x = 0) => ({
		agentId: 'agent-a',
		payload: {
			goalRevision: 1,
			eventSequence,
			attention: false,
			observation: { player: { x, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Collect 8 oak logs.', goalSpec: immutableGoalSpec('Collect 8 oak logs.'),
		} });
		run.bridge.emit('observation', observation(1));
		await eventually(() => planner.requests.length === 1 && bridge.sent.some(({ type }) => type === 'goal_completed'));
		const completion = bridge.sent.find(({ type }) => type === 'goal_completed');
		// Queue ordinary work before the server answers the completion request.
		run.bridge.emit('observation', observation(2, 1));
		await new Promise((resolve) => setImmediate(resolve));
		bridge.emit('goal_completion_result', { agentId: 'agent-a', payload: {
			goalRevision: 1,
			traceId: completion.payload.traceId,
			goalFingerprint: completion.payload.goalFingerprint,
			verified: false,
			reasonCode: 'PREDICATE_FAILED',
			facts: [{ type: 'operator_confirmed', satisfied: false }],
		} });
		releaseFirstTurn();
		await firstTurnSettled;
		for (let sequence = 3; sequence <= 8; sequence += 1) {
			run.bridge.emit('observation', observation(sequence, sequence));
			await new Promise((resolve) => setImmediate(resolve));
		}
		assert.equal(planner.requests.length, 1, 'ordinary observations cannot restart a confirmation-waiting goal');
		assert.equal(timers.pendingCount, 0, 'confirmation wait terminates supervision instead of arming recovery');

		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Please answer me.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000,
		} });
		await eventually(() => planner.requests.length === 2);
		assert.equal(planner.requests[1].priority, 'urgent');
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(planner.requests.length, 2, 'urgent operator input remains deliverable without rearming ordinary work');

		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'steer', goalRevision: 2, goal: 'Wait for the next instruction.',
		} });
		run.bridge.emit('observation', { ...observation(9), payload: { ...observation(9).payload, goalRevision: 2 } });
		await eventually(() => planner.requests.length === 3);
		assert.equal(planner.requests[2].goalRevision, 2, 'a new goal revision clears the confirmation wait gate');
	} finally {
		releaseFirstTurn();
		await run.coordinator.stop();
	}
});

test('coordinator reconnect preserves player pause and schedules one plan for duplicate recovery facts', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let releasePlan;
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		await new Promise((resolve) => { releasePlan = resolve; });
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const active = {
		...record('agent-a'), state: DynamicAgentState.ACTING, currentGoal: 'Keep working.', goalRevision: 4,
		provider: 'gemini', model: 'gemini-3.1-pro', reasoningEffort: 'low', serviceTier: 'priority',
	};
	const paused = { ...record('agent-b'), state: DynamicAgentState.PAUSED, currentGoal: 'Wait for Lucas.', goalRevision: 2 };
	const run = await start({ registry, planner, initialRegistry: [active, paused] });
	try {
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.STARTING);
		assert.deepEqual(pickProfile(run.registry.get('agent-a')), pickProfile(active));
		assert.equal(run.registry.get('agent-a').goalRevision, 4);
		assert.equal(run.registry.get('agent-b').state, DynamicAgentState.PAUSED);

		run.bridge.emit('disconnected');
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.DISCONNECTED);
		assert.equal(run.registry.get('agent-b').state, DynamicAgentState.PAUSED, 'transport loss cannot overwrite player pause');

		run.bridge.emit('ready', { serverInstanceId: 'test', registry: [active, paused] });
		run.bridge.emit('ready', { serverInstanceId: 'test', registry: [active, paused] });
		await eventually(() => run.registry.get('agent-a')?.state === DynamicAgentState.STARTING);
		assert.deepEqual(pickProfile(run.registry.get('agent-a')), pickProfile(active));
		assert.equal(run.registry.get('agent-b').state, DynamicAgentState.PAUSED);
		assert.equal(run.bridge.sent.some((message) => message.type === 'goal_control' && message.payload?.operation === 'resume'), false);

		const facts = { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } };
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 4, eventSequence: 1, observation: facts } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 4, eventSequence: 1, observation: facts } });
		await eventually(() => planner.requests.length === 1);
		assert.equal(planner.requests.length, 1, 'duplicate recovery facts schedule one plan');
	} finally {
		releasePlan?.();
		await run.coordinator.stop();
	}
});

test('idle native agents answer direct conversation without creating a physical goal', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const result = await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-idle-conversation',
			callId: 'call-idle-conversation',
			tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Hi!', audience: 'direct', recipientId: '11111111-1111-4111-8111-111111111111' } },
		});
		assert.equal(result.state, 'SUCCEEDED');
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
	});
	try {
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: '11111111-1111-4111-8111-111111111111', recipientId: 'agent-a', scope: 'direct',
			text: 'Hi', goalRevision: 0, observedAtEpochMs: 10,
		} });
		await eventually(() => run.bridge.sent.some((message) => message.payload?.actionType === 'chat'));
		const command = run.bridge.sent.find((message) => message.payload?.actionType === 'chat');
		assert.equal(planner.requests[0].preserveState, true);
		assert.match(planner.requests[0].input, /conversation_only/);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.IDLE);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 0, actionId: command.payload.actionId, actionType: 'chat', state: 'SUCCEEDED',
			reasonCode: 'CHAT_SENT', executionStarted: true, eventSequence: 1,
		} });
		await eventually(() => planner.requests.length === 1 && run.registry.get('agent-a').state === DynamicAgentState.IDLE);
	} finally {
		await run.coordinator.stop();
	}
});

test('an expired native provider turn is evicted so a fresh observation can start replacement work', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) return new Promise(() => {});
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		goalClock: () => 0,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		await timers.runNext();
		await eventually(() => planner.interruptions.includes('agent-a') && run.bridge.sent.some((message) => message.type === 'request_observation'));
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 2);
		assert.equal(run.registry.get('agent-a').goalRevision, 1);
		assert.notEqual(planner.requests[0], planner.requests[1]);
	} finally {
		await run.coordinator.stop();
	}
});

test('native scheduling drops unchanged quiet heartbeats but accepts the supervisor continuation observation', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		goalClock: () => 0,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } };
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, attention: false, observation } });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		const scheduledLeaseCount = timers.history.length;
		const scheduledLeaseHandle = timers.history.at(-1).handle.id;
		for (let sequence = 2; sequence <= 20; sequence += 1) {
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: sequence, observedAtEpochMs: sequence * 1000, attention: false, observation } });
			await new Promise((resolve) => setImmediate(resolve));
		}
		assert.equal(planner.requests.length, 1);
		assert.equal(timers.history.length, scheduledLeaseCount, 'quiet heartbeats preserve the existing recovery deadline');
		assert.equal(timers.history.at(-1).handle.id, scheduledLeaseHandle, 'quiet heartbeats preserve recovery lease identity');
		await timers.runNext();
		await eventually(() => run.bridge.sent.some((message) => message.type === 'request_observation'));
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 21, attention: false, observation } });
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[1].input, /"trigger":"continuation"/);
	} finally { await run.coordinator.stop(); }
});

test('native supervision resets only when an observation starts actionable work', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 1 };
	};
	const goalSupervisor = new RecordingGoalSupervisor();
	const run = await start({
		registry,
		planner,
		goalSupervisor,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } };
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, attention: false, observation } });
		await eventually(() => planner.requests.length === 1 && goalSupervisor.observations.length === 1);

		for (let sequence = 2; sequence <= 17; sequence += 1) {
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: sequence, attention: false, observation } });
			await new Promise((resolve) => setImmediate(resolve));
		}
		assert.equal(goalSupervisor.observations.length, 1, 'ignored heartbeats do not reset supervision');

		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 18, attention: true, trigger: 'damage', observation } });
		await eventually(() => planner.requests.length === 2 && goalSupervisor.observations.length === 2);
		assert.equal(goalSupervisor.observations[1].goalRevision, 1);
	} finally { await run.coordinator.stop(); }
});

test('native scheduling ignores clock-only heartbeats but replans for actionable changes', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	const heartbeat = factToWireObservation({
		player: { x: 0, y: 64, z: 0, health: 20 },
		items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} },
	}, 1, 1, false, 1);
	heartbeat.player.effects = [{ effectId: 'minecraft:speed', amplifier: 0, duration: 100 }];
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: heartbeat });
		await eventually(() => planner.requests.length === 1);

		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			...heartbeat,
			eventSequence: 2,
			player: { ...heartbeat.player, effects: [{ ...heartbeat.player.effects[0], duration: 99 }] },
			world: { ...heartbeat.world, gameTime: 2, dayTime: 2 },
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(planner.requests.length, 1, 'advancing clocks and effect countdowns do not queue another provider turn');

		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			...heartbeat,
			eventSequence: 3,
			world: { ...heartbeat.world, gameTime: 3, dayTime: 3, raining: true },
		} });
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[1].input, /\"raining\":true/);
	} finally { await run.coordinator.stop(); }
});

for (const failFirstChunk of [false, true]) {
	test(`oversized native conversation delivers contiguous prefixes with failure rollback ${failFirstChunk}`, async t => {
		let now = 1000, contextSize = 0;
		t.mock.method(RuntimeMemoryContext.prototype, 'taskContext', async () => ({ summary: 'x'.repeat(contextSize) }));
		const registry = new AgentRegistry(), planner = new FakePlanner(registry);
		const delivered = [];
		planner.requestNativeTurn = async request => {
			planner.requests.push(request);
			const encoded = encodeNativeEventInput(request.input);
			const event = decodeModelFacts(JSON.parse(encoded.split('\n')[1]));
			assert.deepEqual(event.conversation, JSON.parse(request.input.split('\n')[1]).conversation, 'provider encoding preserves the acknowledged prefix');
			if (planner.requests.length === 1 || (failFirstChunk && planner.requests.length === 2)) throw Object.assign(new Error('cooldown'), { code: 'PROVIDER_CIRCUIT_OPEN', nextProbeAtEpochMs: now + 4000 });
			delivered.push(...event.conversation.entries.map(entry => entry.sequence));
			return { toolCalls: 1 };
		};
		const run = await start({ registry, planner, epochNow: () => now, goalSupervisor: new RecordingGoalSupervisor(), config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		const observation = sequence => run.bridge.emit('observation', { agentId: 'agent-a', payload: factToWireObservation({ player: { health: 20 }, inventory: { items: [] } }, 1, sequence, true, now) });
		const message = sequence => { const pending = []; run.bridge.emit('conversation_event', { waitUntil: operation => pending.push(operation), agentId: 'agent-a', payload: { sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: `Instruction ${sequence}: ${'y'.repeat(250)}`, goalRevision: 1, observedAtEpochMs: sequence } }); return Promise.all(pending); };
		try {
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Listen.' } });
			observation(1); await eventually(() => planner.requests.length === 1);
			await new Promise(resolve => setImmediate(resolve));
			contextSize = 14000;
			for (let sequence = 1; sequence <= 12; sequence++) await message(sequence);
			await new Promise(resolve => setImmediate(resolve));
			now = 5000; observation(2);
			await eventually(() => planner.requests.length >= 2);
			const first = JSON.parse(planner.requests[1].input.split('\n')[1]).conversation;
			assert.equal(first.entries[0].sequence, 1);
			assert.ok(first.omittedEntries > 0);
			assert.equal(first.nextSequence, first.entries.at(-1).sequence);
			if (failFirstChunk) {
				await new Promise(resolve => setImmediate(resolve));
				await message(13); await new Promise(resolve => setImmediate(resolve));
				now = 9000; observation(3);
			}
			const count = failFirstChunk ? 13 : 12;
			await eventually(() => delivered.length === count);
			assert.deepEqual(delivered, Array.from({ length: count }, (_, index) => index + 1));
			assert.equal(JSON.parse(planner.requests.at(-1).input.split('\n')[1]).conversation.omittedEntries, 0);
		} finally { await run.coordinator.stop(); }
	});
}

for (const failTail of [false, true]) for (const toolCalls of [0, 1]) {
	test(`truncated steering drains retained messages with tail rejection ${failTail} and ${toolCalls}-tool completion`, async t => {
		let contextSize = 0, finish, finishSteer, steerCount = 0;
		const steerGate = new Promise(resolve => { finishSteer = resolve; });
		const turnGate = new Promise(resolve => { finish = resolve; });
		t.mock.method(RuntimeMemoryContext.prototype, 'taskContext', async () => ({ summary: 'x'.repeat(contextSize) }));
		const registry = new AgentRegistry(), planner = new FakePlanner(registry), delivered = [];
		const collect = input => delivered.push(...JSON.parse(input.split('\n')[1]).conversation.entries.map(entry => entry.sequence));
		planner.requestNativeTurn = async request => { planner.requests.push(request); collect(request.input); if (planner.requests.length === 1) await turnGate; return { toolCalls }; };
		planner.steerNativeTurn = async request => {
			steerCount++;
			if (steerCount === 1) await steerGate;
			const input = await resolveNativeSteerInput(request);
			if (failTail && steerCount === 3) throw Object.assign(new Error('turn ended'), { code: 'TURN_NOT_ACTIVE' });
			collect(input);
			return {};
		};
		const run = await start({ registry, planner, goalSupervisor: new RecordingGoalSupervisor(), config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		const message = sequence => { const pending = []; run.bridge.emit('conversation_event', { waitUntil: operation => pending.push(operation), agentId: 'agent-a', payload: { sequence, kind: 'player_message', sourceId: 'player', recipientId: 'agent-a', scope: 'direct', text: `Instruction ${sequence}: ${'y'.repeat(250)}`, goalRevision: 0, observedAtEpochMs: sequence } }); return Promise.all(pending); };
		try {
			await message(1); await eventually(() => planner.requests.length === 1);
			contextSize = 14000;
			for (let sequence = 2; sequence <= 12; sequence++) await message(sequence);
			await eventually(() => steerCount === 1);
			await new Promise(resolve => setImmediate(resolve));
			finishSteer();
			await eventually(() => failTail ? steerCount === 3 : delivered.length === 12);
			finish();
			await eventually(() => delivered.length === 12);
			await eventually(() => planner.requests.length === 2);
			assert.deepEqual(delivered, Array.from({ length: 12 }, (_, index) => index + 1));
			assert.equal(planner.requests.length, 2, 'superseded steering drains the retained tail through one replacement turn');
		} finally { finishSteer(); finish(); await run.coordinator.stop(); }
	});
}

test('oversized task memory is trimmed so unread conversation still reaches the model', async t => {
	let contextSize = 20000;
	t.mock.method(RuntimeMemoryContext.prototype, 'taskContext', async () => ({ summary: 'x'.repeat(contextSize) }));
	const registry = new AgentRegistry(), planner = new FakePlanner(registry);
	planner.requestNativeTurn = async request => { planner.requests.push(request); return { toolCalls: 1 }; };
	const run = await start({ registry, planner, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const message = sequence => run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: { sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: `Important ${sequence}`, goalRevision: 0, observedAtEpochMs: sequence } });
	try {
		message(1);
		await eventually(() => planner.requests.length === 1);
		const first = JSON.parse(planner.requests[0].input.split('\n')[1]);
		assert.deepEqual(first.conversation.entries.map(entry => entry.sequence), [1], 'the message is delivered instead of failing the turn');
		assert.equal(first.taskMemory.truncated, true);
		assert.ok(first.contextTrimmed.taskMemory > 20_000);
		assert.equal(run.bridge.sent.some(message => message.type === 'agent_error' && message.payload.code === 'NATIVE_CONVERSATION_BUDGET_EXCEEDED'), false);
		contextSize = 0; message(2);
		await eventually(() => planner.requests.length === 2);
		assert.deepEqual(JSON.parse(planner.requests[1].input.split('\n')[1]).conversation.entries.map(entry => entry.sequence), [2]);
	} finally { await run.coordinator.stop(); }
});

test('native turns receive each conversation entry exactly once', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Listen and keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, attention: false, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		for (const [sequence, text] of [[1, 'First message'], [2, 'Second message']]) {
			run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
				sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text,
				goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 + sequence,
			} });
			await eventually(() => planner.requests.length === sequence + 1);
		}
		const delivered = planner.requests.slice(1).map(({ input }) => JSON.parse(input.split('\n').at(-1)).conversation);
		assert.deepEqual(delivered.map(({ mode }) => mode), ['unread', 'unread']);
		assert.deepEqual(delivered.map(({ entries }) => entries.map(({ sequence }) => sequence)), [[1], [2]]);
		assert.equal(planner.requests[2].input.includes('First message'), false);
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 2, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Second message',
			goalRevision: 1, observedAtEpochMs: 1_787_184_000_002,
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(planner.requests.length, 3, 'replayed delivery does not schedule another native turn');
	} finally { await run.coordinator.stop(); }
});

test('failed idle native conversation keeps the direct message unread for its recovery turn', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) {
			throw Object.assign(new Error('provider unavailable'), { code: 'PROVIDER_DOWN' });
		}
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	try {
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Please answer after you reconnect.', goalRevision: 0, observedAtEpochMs: 1_787_184_000_001,
		} });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Please answer after you reconnect.', goalRevision: 0, observedAtEpochMs: 1_787_184_000_001,
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(planner.requests.length, 1, 'replayed delivery cannot bypass the fenced recovery turn');
		await timers.runNext();
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[0].input, /Please answer after you reconnect\./);
		assert.match(planner.requests[1].input, /Please answer after you reconnect\./);
	} finally { await run.coordinator.stop(); }
});

test('expired idle native conversation keeps the proximity message unread for its replacement turn', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) return new Promise(() => {});
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	try {
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'proximity',
			text: 'Reply after the expired provider turn.', goalRevision: 0, observedAtEpochMs: 1_787_184_000_001,
		} });
		await eventually(() => planner.requests.length === 1 && timers.pendingCount === 1);
		await timers.runNext();
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[0].input, /Reply after the expired provider turn\./);
		assert.match(planner.requests[1].input, /Reply after the expired provider turn\./);
	} finally { await run.coordinator.stop(); }
});

test('failed active native conversation remains unread through fresh-fact recovery', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let failedConversation = false;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (!failedConversation && request.input.includes('Do not forget this steering message.')) {
			failedConversation = true;
			throw Object.assign(new Error('provider unavailable'), { code: 'PROVIDER_DOWN' });
		}
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep working.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'proximity',
			text: 'Do not forget this steering message.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_001,
		} });
		await eventually(() => planner.requests.length === 2 && timers.pendingCount === 1);
		await timers.runNext();
		const request = run.bridge.sent.findLast(({ type }) => type === 'request_observation');
		assert.ok(request);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 2,
			observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 3);
		assert.match(planner.requests[2].input, /Do not forget this steering message\./);
	} finally { await run.coordinator.stop(); }
});

test('a native conversation waits for the normal body-tool boundary instead of interrupting', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	let bodyResult = null;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		bodyResult = await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-moving',
			callId: 'call-moving',
			tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 20, y: 64, z: 0, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
		});
		return { status: 'completed', toolCalls: 1 };
	};
	planner.steerNativeTurn = async (request) => {
		request.onInterrupt?.();
		await resolveNativeSteerInput(request);
		steers.push(request);
		return { turnId: 'turn-moving' };
	};
	const run = await start({
		registry,
		planner,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Walk to the ridge.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Answer me while you walk.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 },
		});
		await eventually(() => steers.length === 1);
		assert.match(steers[0].input, /Answer me while you walk\./);
		assert.deepEqual(JSON.parse(steers[0].input.slice(steers[0].input.indexOf('\n') + 1)).conversation.entries.map(({ sequence }) => sequence), [1]);
		assert.equal(steers[0].onInterrupt, null, 'a conversation does not ask the body tool to return early');
		assert.equal(bodyResult, null, 'the provider receives the message, but the body call stays blocked');
		assert.equal(planner.requests.length, 1);
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_cancel' && message.payload.actionId === command.payload.actionId), false);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'ARRIVED', executionStarted: true, eventSequence: 2 } });
		await eventually(() => bodyResult?.state === 'SUCCEEDED');
	} finally {
		await run.coordinator.stop();
	}
});

test('a distant mild threat reaches the normal body-tool boundary without interrupting', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	let bodyResult = null;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		bodyResult = await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-moving',
			callId: 'call-moving',
			tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 20, y: 64, z: 0, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
		});
		return { status: 'completed', toolCalls: 1 };
	};
	planner.steerNativeTurn = async (request) => {
		request.onInterrupt?.();
		await resolveNativeSteerInput(request);
		steers.push(request);
		return { turnId: 'turn-moving' };
	};
	const run = await start({
		registry,
		planner,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Walk to the ridge.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 2, attention: true, priority: 'urgent', trigger: 'threat', changedFacts: ['threats.entries'],
			observation: { player: { x: 1, y: 64, z: 0, threats: [{ uuid: 'zombie-1', type: 'minecraft:zombie', distance: 14.2, targeting: false, signals: ['targeting'] }] }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => steers.length === 1);
		assert.equal(bodyResult, null, 'the provider receives the urgent event, but the body call stays blocked');
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_cancel' && message.payload.actionId === command.payload.actionId), false);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'ARRIVED', executionStarted: true, eventSequence: 3 } });
		await eventually(() => bodyResult?.state === 'SUCCEEDED');
	} finally {
		await run.coordinator.stop();
	}
});

test('a detected movement loop steers once until movement escapes and a new loop begins', async () => {
	let release;
	const gate = new Promise(resolve => { release = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	planner.requestNativeTurn = async request => { planner.requests.push(request); await gate; return { status: 'completed', toolCalls: 0 }; };
	planner.steerNativeTurn = async request => { await resolveNativeSteerInput(request); steers.push(request); return { turnId: 'thinking' }; };
	const run = await start({ registry, planner, config: {
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
	} });
	let sequence = 0;
	const observe = async x => {
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: ++sequence, attention: false,
			observation: { player: { x, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await new Promise(resolve => setImmediate(resolve));
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Find a tree.' } });
		await observe(0);
		await eventually(() => planner.requests.length === 1);
		for (const x of [1, 0, 1, 0, 1, 1, 1, 1]) await observe(x);
		assert.equal(steers.length, 1, 'one unresolved loop must not flood the thinking model');
		assert.match(steers[0].input, /movement_loop/);
		for (const x of [2, 3, 4, 5, 6, 5, 6, 5, 6]) await observe(x);
		assert.equal(steers.length, 2, 'escaping the first loop rearms detection for a different loop');
	} finally { release(); await run.coordinator.stop(); }
});

test('a movement loop seen only in action progress positions steers once and asks the server for an observation', async () => {
	let release;
	const gate = new Promise(resolve => { release = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	planner.requestNativeTurn = async request => { planner.requests.push(request); await gate; return { status: 'completed', toolCalls: 0 }; };
	planner.steerNativeTurn = async request => { steers.push(request); return { turnId: 'thinking' }; };
	const run = await start({ registry, planner, config: {
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
	} });
	const progress = async x => {
		run.bridge.emit('action_progress', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: 'walk-1', state: 'RUNNING', progress: 0.5, elapsedMs: 100, observedAtEpochMs: 1,
			actionObservation: { observedAtEpochMs: 1, position: { x, y: 64, z: 0 } } } });
		await new Promise(resolve => setImmediate(resolve));
	};
	const observationRequests = () => run.bridge.sent.filter(message => message.type === 'request_observation').length;
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Find a tree.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, attention: false,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		for (const x of [1, 0]) await progress(x);
		assert.equal(observationRequests(), 0, 'three samples are not a loop yet');
		await progress(1);
		await eventually(() => observationRequests() === 1);
		for (const x of [0, 1, 0, 1]) await progress(x);
		assert.equal(observationRequests(), 1, 'one unresolved loop asks once');
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, attention: false,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => steers.length === 1);
		assert.match(steers[0].input, /movement_loop/);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 3, attention: false,
			observation: { player: { x: 1, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		for (let index = 0; index < 5; index += 1) await new Promise(resolve => setImmediate(resolve));
		assert.equal(steers.length, 1, 'the observation that follows does not report the same loop again');
	} finally { release(); await run.coordinator.stop(); }
});

test('new resource observations coalesce behind an active model turn without interrupting it', async () => {
	let release;
	const gate = new Promise(resolve => { release = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	planner.requestNativeTurn = async request => { planner.requests.push(request); await gate; return { status: 'completed', toolCalls: 0 }; };
	planner.steerNativeTurn = async request => { await resolveNativeSteerInput(request); steers.push(request); };
	const run = await start({ registry, planner, config: {
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
	} });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Find a tree.' } });
		for (let index = 0; index <= 6; index++) {
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: index + 1, attention: false,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], inventory: { items: [], tagCounts: {} },
					blocks: index === 0 ? [] : [{ stableId: `tree-${index}`, x: index, y: 64, z: 0, blockId: 'minecraft:oak_log' }] } } });
			await new Promise(resolve => setImmediate(resolve));
			// Resource discoveries must arrive after durable input preparation starts the turn.
			if (index === 0) await eventually(() => planner.requests.length === 1);
		}
		assert.equal(planner.requests.length, 1);
		assert.equal(steers.length, 0, 'resource discoveries must not continually restart reconsideration');
		release();
		await eventually(() => planner.requests.length === 2);
		assert.equal(JSON.parse(planner.requests[1].input.split('\n').slice(1).join('\n')).observation.blocks[0].x, 6);
	} finally { release(); await run.coordinator.stop(); }
});
