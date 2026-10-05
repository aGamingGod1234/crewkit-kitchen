import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { AgentPlanner } from '../src/agent-planner.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';
import { SOURCE, createDynamicCoordinator, FakeBridge, GatedActionCancelBridge, FakeProvider, FakePlanner, record, factToWireObservation, DEATH, immutableGoalSpec, eventually, start, realPlannerProvider } from './fixtures/dynamic-main-fixture.mjs';

for (const steerOutcome of ['resolved', 'rejected']) {
test(`queued native steering cannot cross a same-revision death lifecycle (${steerOutcome})`, async () => {
	let releaseTurn;
	let releaseSteer;
	const turnGate = new Promise(resolve => { releaseTurn = resolve; });
	const steerGate = new Promise((resolve, reject) => {
		releaseSteer = () => steerOutcome === 'resolved' ? resolve() : reject(Object.assign(new Error('old turn ended'), { code: 'TURN_NOT_ACTIVE' }));
	});
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	const traceEvents = [];
	planner.requestNativeTurn = async request => {
		planner.requests.push(request);
		await turnGate;
		return { status: 'completed', toolCalls: 0 };
	};
	planner.steerNativeTurn = async request => {
		steers.push(request);
		if (steers.length === 1) await steerGate;
	};
	const run = await start({ registry, planner, traceWriter: { write(event) { traceEvents.push(event); } }, config: {
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: { controlProtocol: 'native_tools' },
	} });
	const say = sequence => run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
		sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: `Pre-death instruction ${sequence}.`, goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 + sequence,
	} });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Explore.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 1);
		say(1);
		await eventually(() => steers.length === 1);
		say(2);
		for (let index = 0; index < 5; index++) await new Promise(resolve => setImmediate(resolve));
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'dead', goalRevision: 1, updatedAtEpochMs: 2, death: DEATH } });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.DEAD);
		releaseSteer();
		for (let index = 0; index < 10; index++) await new Promise(resolve => setImmediate(resolve));
		assert.equal(steers.length, 1, 'the old turn must not deliver queued instructions after death');
		assert.equal(traceEvents.includes('native_turn_steer_deferred'), false, 'obsolete failures cannot restore delivery or queue recovery');
		releaseTurn();
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[1].input, /Pre-death instruction 1\./, 'replacement turn must receive unacknowledged steering conversation');
		assert.match(planner.requests[1].input, /Pre-death instruction 2\./, 'replacement turn must receive queued conversation');
	} finally {
		releaseSteer();
		releaseTurn();
		await run.coordinator.stop();
	}
});
}

test('reconnect replays unacknowledged steering before replacement delivery and fences a late rejection', async () => {
	let releaseTurn;
	let rejectSteer;
	const turnGate = new Promise(resolve => { releaseTurn = resolve; });
	const steerGate = new Promise((resolve, reject) => { rejectSteer = reject; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	planner.requestNativeTurn = async request => {
		planner.requests.push(request);
		await turnGate;
		return { status: 'completed', toolCalls: 1 };
	};
	planner.steerNativeTurn = async request => {
		steers.push(request);
		if (steers.length === 1) await steerGate;
	};
	const run = await start({ registry, planner, config: {
		bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' },
	} });
	const observe = () => run.bridge.emit('observation', { agentId: 'agent-a', payload: {
		goalRevision: 1, eventSequence: 1,
		observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
	} });
	const say = sequence => run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
		sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: `Reconnect instruction ${sequence}.`, goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 + sequence,
	} });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Explore.', updatedAtEpochMs: 1 } });
		observe();
		await eventually(() => planner.requests.length === 1);
		say(1);
		await eventually(() => steers.length === 1);
		run.bridge.emit('disconnected');
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.DISCONNECTED);
		run.bridge.emit('ready', { serverInstanceId: 'test', registry: [
			{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Explore.', goalRevision: 1 },
		] });
		await eventually(() => run.bridge.sent.some(message => message.type === 'agent_ready' && message.payload?.reconciled === true));
		observe();
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[1].input, /Reconnect instruction 1\./);
		rejectSteer(Object.assign(new Error('old turn ended'), { code: 'TURN_NOT_ACTIVE' }));
		for (let index = 0; index < 5; index++) await new Promise(resolve => setImmediate(resolve));
		say(2);
		await eventually(() => steers.length === 2);
		assert.match(steers[1].input, /Reconnect instruction 2\./);
		assert.doesNotMatch(steers[1].input, /Reconnect instruction 1\./, 'late failure must not rewind replacement delivery');
	} finally {
		rejectSteer(Object.assign(new Error('old turn ended'), { code: 'TURN_NOT_ACTIVE' }));
		releaseTurn();
		await run.coordinator.stop();
	}
});

test('failed urgent native steering retains one pending turn with the newest event', async () => {
	let releaseFirst;
	const firstGate = new Promise((resolve) => { releaseFirst = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let steerAttempts = 0;
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) await firstGate;
		return { status: 'completed', toolCalls: 0 };
	};
	planner.steerNativeTurn = async () => {
		steerAttempts += 1;
		throw Object.assign(new Error('turn already ended'), { code: 'TURN_NOT_ACTIVE' });
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
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait for Lucas.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Please reply.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 },
		});
		await eventually(() => steerAttempts === 1);
		releaseFirst();
		await eventually(() => planner.requests.length === 2);
		assert.match(planner.requests[1].input, /Please reply\./);
		assert.equal(planner.requests[1].priority, 'urgent');
	} finally {
		releaseFirst();
		await run.coordinator.stop();
	}
});

test('native Codex control reissues the newest goal after an obsolete turn settles', async () => {
	let releaseFirst;
	const firstGate = new Promise((resolve) => { releaseFirst = resolve; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) await firstGate;
		return { status: 'completed', toolCalls: 0 };
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
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'New goal.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 2, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.registry.get('agent-a').goalRevision === 2);
		await new Promise((resolve) => setImmediate(resolve));
		releaseFirst();
		await eventually(() => planner.requests.length === 2);
		assert.equal(planner.requests[1].goalRevision, 2);
		assert.match(planner.requests[1].input, /New goal/);
	} finally {
		releaseFirst();
		await run.coordinator.stop();
	}
});

test('native Codex control reissues a resumed goal after the interrupted turn fails', async () => {
	let rejectFirst;
	const firstGate = new Promise((_, reject) => { rejectFirst = reject; });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) await firstGate;
		return { status: 'completed', toolCalls: 0 };
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
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Old goal.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 2 } });
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.PAUSED);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'resume', goalRevision: 3 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 3, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.registry.get('agent-a').goalRevision === 3);
		for (let index = 0; index < 10; index += 1) await new Promise((resolve) => setImmediate(resolve));
		rejectFirst(Object.assign(new Error('obsolete provider turn interrupted'), { code: 'STALE_PLAN' }));
		await eventually(() => planner.requests.length === 2);
		assert.equal(planner.requests[1].goalRevision, 3);
		assert.match(planner.requests[1].input, /Old goal/);
	} finally {
		rejectFirst(Object.assign(new Error('test cleanup'), { code: 'STALE_PLAN' }));
		await run.coordinator.stop();
	}
});

test('old agent removal awaiting reconciliation cannot remove replacement-session state', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let reconciliationCount = 0;
	let releaseOldReconciliation;
	planner.beginReconcile = (records, options) => {
		const reconciledRegistry = registry.reconcile(records, options);
		const result = {
			registry: reconciledRegistry,
			providers: { valid: reconciledRegistry.records, invalid: [], catalog: { models: [] } },
		};
		reconciliationCount += 1;
		if (reconciliationCount !== 1) return { registry: reconciledRegistry, complete: Promise.resolve(result) };
		return {
			registry: reconciledRegistry,
			complete: new Promise((resolve) => { releaseOldReconciliation = () => resolve(result); }),
		};
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => reconciliationCount === 1 && typeof releaseOldReconciliation === 'function');
		bridge.emit('agent_removed', { connectionEpoch: 1, agentId: 'agent-a', payload: { goalRevision: 0 } });
		await new Promise((resolve) => setImmediate(resolve));

		const replacement = { ...record(), model: 'gpt-5.6-luna' };
		bridge.emit('ready', { connectionEpoch: 2, serverInstanceId: 'test', registry: [replacement] });
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		releaseOldReconciliation();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));

		assert.equal(registry.get('agent-a')?.model, 'gpt-5.6-luna');
		assert.equal(registry.get('agent-a')?.state, DynamicAgentState.IDLE);
	} finally {
		releaseOldReconciliation?.();
		await coordinator.stop();
	}
});

test('old compiler correction cannot delete replacement provider work after reconnect', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let releaseOldCorrection;
	let releaseReplacementPlan;
	const oldCorrection = new Promise((resolve) => { releaseOldCorrection = resolve; });
	const replacementPlan = new Promise((resolve) => { releaseReplacementPlan = resolve; });
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) {
			return withCompletionContract({ summary: 'Compile invalid source.', directive: 'replace', source: 'not valid ArenaScript {' }, request.goalRevision);
		}
		if (planner.requests.length === 2) {
			await oldCorrection;
			return withCompletionContract({ summary: 'Old corrected source.', directive: 'replace', source: SOURCE }, request.goalRevision);
		}
		if (planner.requests.length === 3) {
			await replacementPlan;
			return withCompletionContract({ summary: 'Replacement source.', directive: 'replace', source: SOURCE }, request.goalRevision);
		}
		throw new Error('unexpected provider request');
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('goal_control', { connectionEpoch: 1, agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait safely.' } });
		run.bridge.emit('observation', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 2);

		run.bridge.emit('disconnected', { connectionEpoch: 1 });
		await eventually(() => registry.get('agent-a')?.state === DynamicAgentState.DISCONNECTED);
		run.bridge.emit('ready', {
			connectionEpoch: 2,
			serverInstanceId: 'test',
			registry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Wait safely.', goalRevision: 1 }],
		});
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		run.bridge.emit('observation', { connectionEpoch: 2, agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 2,
			observation: { player: { x: 1, y: 64, z: 0 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 3);

		releaseOldCorrection();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_command'), false, 'old correction cannot install into epoch 2');

		releaseReplacementPlan();
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command' && message.connectionEpoch === 2));
	} finally {
		releaseOldCorrection?.();
		releaseReplacementPlan?.();
		await run.coordinator.stop();
	}
});

test('old conversation wake awaiting native disposal cannot repopulate replacement state', async () => {
	const bridge = new GatedActionCancelBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length !== 1) return { status: 'completed', toolCalls: 1 };
		return request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-before-wake',
			callId: 'call-before-wake',
			tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Waiting.', audience: 'direct', recipientId: '11111111-1111-4111-8111-111111111111' } },
		});
	};
	const run = await start({
		bridge,
		registry,
		planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	const wake = {
		transactionId: 'wake-obsolete-epoch',
		event: {
			sequence: 2, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Obsolete wake text.', goalRevision: 0, observedAtEpochMs: 20,
		},
		control: { operation: 'start', goalRevision: 1, updatedAtEpochMs: 21, goal: 'Respond after reconnect.' },
	};
	try {
		bridge.emit('conversation_event', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Start the first turn.', goalRevision: 0, observedAtEpochMs: 10,
		} });
		await eventually(() => bridge.sent.some((message) => message.payload?.actionType === 'chat'));
		bridge.emit('conversation_wake', { connectionEpoch: 1, agentId: 'agent-a', payload: wake });
		await eventually(() => bridge.cancelPending);

		bridge.emit('disconnected', { connectionEpoch: 1 });
		await eventually(() => registry.get('agent-a')?.state === DynamicAgentState.DISCONNECTED);
		bridge.emit('ready', {
			connectionEpoch: 2,
			serverInstanceId: 'test',
			registry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: wake.control.goal, goalRevision: 1 }],
		});
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		bridge.releaseCancel();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(planner.requests.length, 1, 'old wake cannot schedule replacement-session work');

		bridge.emit('conversation_event', { connectionEpoch: 2, agentId: 'agent-a', payload: {
			sequence: 3, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Fresh replacement text.', goalRevision: 1, observedAtEpochMs: 30,
		} });
		await eventually(() => planner.requests.length === 2);
		assert.doesNotMatch(planner.requests[1].input, /Obsolete wake text/);
	} finally {
		bridge.releaseCancel();
		await run.coordinator.stop();
	}
});

test('zero-tool native conversation retries visibly under the replacement work epoch', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const traces = [], errors = [], steers = [];
	let releaseOldTurn;
	const oldTurn = new Promise((resolve) => { releaseOldTurn = resolve; });
	let releaseReplacementTurn, recoveredTurnStarted, steeringDeferred;
	const replacementTurn = new Promise((resolve) => { releaseReplacementTurn = resolve; });
	const recoveredTurn = new Promise((resolve) => { recoveredTurnStarted = resolve; });
	const deferredSteering = new Promise((resolve) => { steeringDeferred = resolve; });
	let chatResult;
	planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	planner.steerNativeTurn = async (request) => {
		steers.push(request);
		throw Object.assign(new Error('replacement turn no longer accepts steering'), { code: 'TURN_NOT_ACTIVE' });
	};
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		assert.ok(planner.requests.length <= 4, 'only the old, recovered, pending and correction turns are expected');
		if (planner.requests.length === 1) {
			await oldTurn;
			return { status: 'completed', toolCalls: 0 };
		}
		if (planner.requests.length === 2) {
			recoveredTurnStarted();
			await replacementTurn;
			return { status: 'completed', toolCalls: 0 };
		}
		if (!request.input.includes('previous turn made no visible reply')) return { status: 'completed', toolCalls: 0 };
		chatResult = await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-visible-retry',
			callId: 'call-visible-retry',
			tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Visible reply.', audience: 'direct', recipientId: '11111111-1111-4111-8111-111111111111' } },
		});
		assert.equal(chatResult.state, 'SUCCEEDED');
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		traceWriter: { write(event, fields) {
			traces.push({ event, ...fields });
			if (event === 'native_turn_steer_deferred') steeringDeferred();
		} },
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } },
	});
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	try {
		run.bridge.emit('conversation_event', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Old pending turn.', goalRevision: 0, observedAtEpochMs: 10,
		} });
		await eventually(() => planner.requests.length === 1);
		run.bridge.emit('disconnected', { connectionEpoch: 1 });
		await eventually(() => planner.interruptions.includes('agent-a'));
		run.bridge.emit('ready', { connectionEpoch: 2, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		// Reconciliation now resumes unread input without another player message.
		// Hold that real replacement turn so new input deterministically becomes
		// deferred work before its zero-tool completion requests a visible reply.
		await recoveredTurn;
		assert.match(planner.requests[1].input, /Old pending turn/);
		assert.doesNotMatch(planner.requests[1].input, /Reply in the replacement session/);
		releaseOldTurn();

		run.bridge.emit('conversation_event', { connectionEpoch: 2, agentId: 'agent-a', payload: {
			sequence: 2, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Reply in the replacement session.', goalRevision: 0, observedAtEpochMs: 20,
		} });
		await deferredSteering;
		assert.equal(steers.length, 1);
		assert.match(steers[0].input, /Reply in the replacement session/);
		releaseReplacementTurn();
		await eventually(() => planner.requests.length === 4);
		assert.match(planner.requests[2].input, /Reply in the replacement session/);
		assert.doesNotMatch(planner.requests[2].input, /previous turn made no visible reply/);
		assert.match(planner.requests[3].input, /previous turn made no visible reply/);
		assert.equal(planner.requests.filter(request => request.input.includes('previous turn made no visible reply')).length, 1);
		await eventually(() => run.bridge.sent.some((message) => message.payload?.actionType === 'chat' && message.connectionEpoch === 2));
		const command = run.bridge.sent.find((message) => message.payload?.actionType === 'chat' && message.connectionEpoch === 2);
		run.bridge.emit('action_result', { connectionEpoch: 2, agentId: 'agent-a', payload: {
			goalRevision: 0, actionId: command.payload.actionId, actionType: 'chat', state: 'SUCCEEDED',
			reasonCode: 'CHAT_SENT', executionStarted: true, eventSequence: 2,
		} });
		await eventually(() => traces.filter(trace => trace.event === 'native_turn_completed').length === 3);
		assert.equal(chatResult.state, 'SUCCEEDED');
		assert.equal(registry.get('agent-a')?.state, DynamicAgentState.IDLE);
		assert.deepEqual(errors, []);
		assert.equal(run.bridge.sent.filter(message => message.payload?.actionType === 'chat').length, 1);
	} finally {
		releaseOldTurn?.();
		releaseReplacementTurn?.();
		await run.coordinator.stop();
	}
});

test('live task view polling uses the bridge without starting planner work and rejects stale revisions', async () => {
	const run = await start();
	try {
		const current = run.registry.get('agent-a');
		const before = run.planner.requests.length;
		run.bridge.emit('task_view_request', { agentId: current.agentId, payload: { goalRevision: current.goalRevision } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'task_view'));
		const view = run.bridge.sent.find((message) => message.type === 'task_view');
		validateProtocolV2Payload('task_view', view.payload);
		assert.equal(view.payload.goalRevision, current.goalRevision);
		assert.equal(run.planner.requests.length, before);
		const replies = run.bridge.sent.filter((message) => message.type === 'task_view').length;
		run.bridge.emit('task_view_request', { agentId: current.agentId, payload: { goalRevision: current.goalRevision + 1 } });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.filter((message) => message.type === 'task_view').length, replies);
	} finally { await run.coordinator.stop(); }
});

test('live task view start and replacement clear prior plans and output while resume retains the task', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let releaseTurn, published = false;
	const turnGate = new Promise((resolve) => { releaseTurn = resolve; });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (request.goalRevision === 1) {
			await request.executeTool({ agentId: request.agentId, goalRevision: 1, turnId: 'plan-window-turn', callId: 'plan-window-call', tool: { kind: 'task_plan', operation: 'replace', plan: { steps: [
				{ id: 'past', label: 'Previous milestone', kind: 'milestone', status: 'complete', dependsOn: [], detail: 'Previous task only', evidence: null },
			] } } });
			request.onVerbose('live_tool', 'Previous task output');published = true;
		}
		await turnGate;return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({ registry, planner, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const poll = async () => {
		const current = registry.get('agent-a'), before = run.bridge.sent.filter(message => message.type === 'task_view').length;
		run.bridge.emit('task_view_request', { agentId: 'agent-a', payload: { goalRevision: current.goalRevision } });
		await eventually(() => run.bridge.sent.filter(message => message.type === 'task_view').length > before);
		return run.bridge.sent.filter(message => message.type === 'task_view').at(-1).payload;
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Repeat the same task.' } });
		const observation = factToWireObservation({ player: { x: 0, y: 64, z: 0, health: 20 } }, 1, 1, false, 1);observation.world.worldId = 'plan-window-test-world';
		run.bridge.emit('observation', { agentId: 'agent-a', payload: observation });await eventually(() => published);
		assert.equal((await poll()).plan.steps[0].status, 'complete');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 2 } });await eventually(() => registry.get('agent-a').goalRevision === 2);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'resume', goalRevision: 3 } });await eventually(() => registry.get('agent-a').goalRevision === 3);
		assert.equal((await poll()).plan.steps[0].status, 'complete');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'replace', goalRevision: 4, goal: 'Repeat the same task.' } });await eventually(() => registry.get('agent-a').goalRevision === 4);
		let view = await poll();assert.equal(view.plan, null);assert.equal(view.events.some(event => event.message === 'Previous task output'), false);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'complete', goalRevision: 5 } });await eventually(() => registry.get('agent-a').goalRevision === 5);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 6, goal: 'Repeat the same task.' } });await eventually(() => registry.get('agent-a').goalRevision === 6);
		view = await poll();assert.equal(view.plan, null);assert.equal(view.events.some(event => event.message === 'Previous task output'), false);
		const before = planner.requests.length;for(let i=0;i<3;i++)await poll();assert.equal(planner.requests.length,before,'display polling cannot start any model turn');
	} finally { releaseTurn();await run.coordinator.stop(); }
});

test('publishes a bootstrap catalog before slower full reconciliation completes', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	let releaseReconciliation;
	const reconciliationGate = new Promise((resolve) => { releaseReconciliation = resolve; });
	const planner = new FakePlanner(registry);
	planner.beginReconcile = (records) => {
		const reconciledRegistry = registry.reconcile(records);
		return { registry: reconciledRegistry, complete: reconciliationGate.then(() => ({
			registry: reconciledRegistry,
			providers: {
				valid: reconciledRegistry.records,
				invalid: [],
				catalog: { refreshedAtEpochMs: 2, models: [{ provider: 'gemini', id: 'gemini-3.1-pro' }] },
			},
		})) };
	};
	const provider = new FakeProvider();
	provider.bootstrapCatalog = async () => ({
		refreshedAtEpochMs: 1,
		models: [{ provider: 'codex', id: 'gpt-5.6-luna' }],
	});
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast' } } },
		{ bridge, registry, planner, codexService: provider },
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { serverInstanceId: 'test', registry: [record()] });
		await eventually(() => bridge.sent.some((message) => message.type === 'catalog_snapshot'));
		assert.deepEqual(
			bridge.sent.filter((message) => message.type === 'catalog_snapshot').map((message) => message.payload),
			[{ refreshedAtEpochMs: 1, models: [{ provider: 'codex', id: 'gpt-5.6-luna' }] }],
		);
		bridge.emit('conversation_wake', {
			agentId: 'agent-a',
			payload: {
				transactionId: 'wake-during-reconciliation',
				event: {
					sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
					text: 'Respond now.', goalRevision: 0, observedAtEpochMs: 1_787_184_000_000,
				},
				control: { operation: 'start', goalRevision: 1, updatedAtEpochMs: 1_787_184_000_001, goal: 'Respond to the player.' },
			},
		});
		await eventually(() => bridge.sent.some((message) => message.type === 'conversation_wake_ack'));
		assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
		assert.equal(bridge.sent.filter((message) => message.type === 'catalog_snapshot').length, 1,
			'wake acknowledgement does not wait for or release full provider reconciliation');
		releaseReconciliation();
		await eventually(() => bridge.sent.filter((message) => message.type === 'catalog_snapshot').length === 2);
		assert.deepEqual(
			bridge.sent.filter((message) => message.type === 'catalog_snapshot').at(-1).payload,
			{ refreshedAtEpochMs: 2, models: [{ provider: 'gemini', id: 'gemini-3.1-pro' }] },
		);
	} finally {
		releaseReconciliation();
		await coordinator.stop();
	}
});

test('a transient reconciliation timeout cannot poison later agent lifecycle traffic', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const timeout = Object.assign(new Error("Codex request 'model/list' timed out"), { code: 'REQUEST_TIMEOUT' });
	planner.beginReconcile = (records) => {
		const reconciledRegistry = registry.reconcile(records);
		return { registry: reconciledRegistry, complete: Promise.reject(timeout) };
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast' } } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	const runtimeErrors = [];
	coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	await coordinator.start();
	try {
		bridge.emit('ready', { serverInstanceId: 'test', registry: [record()] });
		await eventually(() => runtimeErrors.length === 1);
		bridge.emit('goal_control', {
			agentId: 'agent-a',
			payload: { operation: 'start', goalRevision: 1, goal: 'Respond to the player.', updatedAtEpochMs: 2 },
		});
		await eventually(() => registry.get('agent-a')?.goalRevision === 1);
		assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready' && message.payload.goalRevision === 1));
		assert.deepEqual(runtimeErrors, [timeout], 'one provider outage is reported once instead of once per queued agent event');
	} finally {
		await coordinator.stop();
	}
});

test('a restored roster provider is reconciled and promoted without restarting the coordinator', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	let statusTick;
	let now = 1_000;
	const provider = new (class extends EventEmitter {
		catalog = { stale: false, refresh: async () => ({ refreshedAtEpochMs: 2, models: [] }), assertSupported() {} };
		async start() {}
		async stop() {}
		recoverySnapshot() {
			return [{ provider: 'codex', state: 'degraded', nextProbeAtEpochMs: 2_000 }];
		}
	})();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	planner.beginReconcile = (records, options) => {
		const reconciledRegistry = registry.reconcile(records, options);
		attempts += 1;
		const valid = attempts === 1 ? [] : reconciledRegistry.records;
		const invalid = attempts === 1
			? [{ profile: reconciledRegistry.records[0], code: 'PROVIDER_TIMEOUT', message: 'provider timed out authorization=profile-secret at C:\\private\\profile.json' }]
			: [];
		return { registry: reconciledRegistry, complete: Promise.resolve({
			registry: reconciledRegistry,
			providers: { valid, invalid, catalog: { refreshedAtEpochMs: attempts, models: [] } },
		}) };
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{
			bridge, registry, planner, codexService: provider,
			epochNow: () => now,
			setStatusInterval: (callback) => { statusTick = callback; return { id: 'status' }; },
			clearStatusInterval: () => {},
		},
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => bridge.sent.some(({ type }) => type === 'agent_error'));
		const agentError = bridge.sent.find(({ type }) => type === 'agent_error');
		assert.equal(agentError.payload.code, 'PROVIDER_TIMEOUT');
		assert.doesNotMatch(agentError.payload.message, /profile-secret|private/);
		assert.equal(bridge.sent.some(({ type }) => type === 'agent_ready'), false);

		statusTick();
		for (let index = 0; index < 3; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(attempts, 1, 'maintenance does not probe before the provider recovery deadline');
		now = 2_000;
		statusTick();
		await eventually(() => bridge.sent.some(({ type }) => type === 'agent_ready'));
		assert.equal(attempts, 2);
	} finally {
		await coordinator.stop();
	}
});

test('a replacement connection discards the old reconciliation completion and its publications', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const reconciliations = [];
	planner.beginReconcile = (records, options) => {
		const reconciledRegistry = registry.reconcile(records, options);
		let resolve;
		const complete = new Promise((release) => { resolve = release; });
		reconciliations.push({
			resolve: () => resolve({
				registry: reconciledRegistry,
				providers: {
					valid: reconciledRegistry.records,
					invalid: [],
					catalog: { refreshedAtEpochMs: reconciliations.length, models: [{ id: `epoch-${reconciliations.length}` }] },
				},
			}),
		});
		return { registry: reconciledRegistry, complete };
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => reconciliations.length === 1);
		bridge.emit('disconnected', { connectionEpoch: 1 });
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'test', registry: [record()] });
		for (let index = 0; index < 2; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(reconciliations.length, 1, 'a disconnected epoch cannot authenticate again');
		bridge.emit('ready', { connectionEpoch: 2, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => reconciliations.length === 2);
		reconciliations[1].resolve();
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		const currentPublications = bridge.sent.length;

		reconciliations[0].resolve();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(bridge.sent.length, currentPublications);
		assert.equal(bridge.sent.some((message) => message.payload?.models?.some(({ id }) => id === 'epoch-1')), false);
	} finally {
		for (const reconciliation of reconciliations) reconciliation.resolve();
		await coordinator.stop();
	}
});

test('late provider completion from an older connection cannot install an action in the replacement session', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let releasePlan;
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		await new Promise((resolve) => { releasePlan = resolve; });
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, registry, planner, codexService: new FakeProvider() },
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'test', registry: [record()] });
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready'));
		bridge.emit('goal_control', { connectionEpoch: 1, agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		bridge.emit('observation', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 1);
		bridge.emit('ready', {
			connectionEpoch: 2,
			serverInstanceId: 'test',
			registry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Wait.', goalRevision: 1 }],
		});
		await eventually(() => bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		releasePlan();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(bridge.sent.some((message) => message.type === 'action_command'), false);
	} finally {
		releasePlan?.();
		await coordinator.stop();
	}
});

test('late action completion from an older connection cannot advance the live program', async () => {
	const run = await start();
	try {
		run.bridge.emit('goal_control', { connectionEpoch: 1, agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('ready', {
			connectionEpoch: 2,
			serverInstanceId: 'test',
			registry: [{ ...record(), state: DynamicAgentState.ACTING, currentGoal: 'Wait.', goalRevision: 1 }],
		});
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_ready' && message.connectionEpoch === 2));
		const observationsBefore = run.bridge.sent.filter((message) => message.type === 'request_observation').length;
		run.bridge.emit('action_result', { connectionEpoch: 1, agentId: 'agent-a', payload: {
			goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE',
		} });
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.filter((message) => message.type === 'request_observation').length, observationsBefore);
	} finally {
		await run.coordinator.stop();
	}
});

test('does not submit a duplicate initial plan while the agent already has a scheduled turn', async () => {
	let release;
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	const blocker = new Promise((resolve) => { release = resolve; });
	const run = await start({ scheduler });
	try {
		void scheduler.schedule('agent-a', async () => blocker).catch(() => {});
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.planner.requests.length, 0);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('retains an urgent observation while an ordinary provider turn is queued behind scheduler capacity', async () => {
	let releaseBlocker;
	const blocker = new Promise((resolve) => { releaseBlocker = resolve; });
	let attempts = 0;
	const provider = realPlannerProvider(async (input) => {
		attempts += 1;
		assert.match(input, /damage|health/i);
		return { summary: 'Respond.', directive: 'replace', source: SOURCE };
	});
	const registry = new AgentRegistry();
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 4 });
	const planner = new AgentPlanner({ registry, scheduler, codexService: provider });
	const run = await start({ registry, scheduler, planner, codexService: provider });
	try {
		scheduler.schedule('blocking-agent', async () => blocker);
		await eventually(() => scheduler.activeAgentIds.includes('blocking-agent'));
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await eventually(() => scheduler.pendingAgentIds.includes('agent-a'));
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, attention: true, changedFacts: ['player.health'], observation: { player: { x: 0, y: 64, z: 0, health: 18 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await new Promise((resolve) => setImmediate(resolve));
		releaseBlocker();
		await eventually(() => attempts === 1 && run.bridge.sent.some((message) => message.type === 'action_command'));
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
		assert.equal(run.bridge.sent.find((message) => message.type === 'action_command').payload.goalRevision, 1);
	} finally {
		releaseBlocker();
		await run.coordinator.stop();
	}
});

test('binds the provider work trace to runtime dispatch when the planner omits a decision echo', async () => {
	const run = await start();
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const workTraceId = run.planner.requests[0].traceId;
		const command = run.bridge.sent.find((message) => message.type === 'action_command').payload;
		assert.equal(typeof workTraceId, 'string');
		assert.equal(command.traceId, workTraceId);
		assert.equal(command.provenance.traceId, workTraceId);
	} finally {
		await run.coordinator.stop();
	}
});

test('keeps lifecycle intake responsive while an initial provider turn is pending', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const run = await start();
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		await gate;
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'Stop waiting.' } });
		await eventually(() => run.registry.get('agent-a')?.goalRevision === 2);
		release();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.some((message) => message.type === 'action_command'), false, 'the stale initial result cannot install after steering');
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('reissues the newest lifecycle plan after an older provider turn settles', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const run = await start();
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		if (request.goalRevision === 1) await gate;
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'steer', goalRevision: 2, goal: 'Stop waiting.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 2, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.registry.get('agent-a')?.goalRevision === 2);
		release();
		await eventually(() => run.planner.requests.length === 2);
		assert.equal(run.planner.requests[1].goalRevision, 2);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('retries a queued urgent trigger after a failed initial provider turn', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const run = await start();
	const runtimeErrors = [];
	run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	let attempts = 0;
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) {
			await gate;
			run.registry.setState('agent-a', DynamicAgentState.ERROR, { goalRevision: 1, error: { code: 'PROVIDER_DOWN', message: 'Provider unavailable' } });
			throw Object.assign(new Error('Provider unavailable'), { code: 'PROVIDER_DOWN' });
		}
		return withCompletionContract({ summary: 'Recovered.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Urgent: respond.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 },
		});
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, attention: true, observation: { player: { x: 0, y: 64, z: 0, health: 19 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await new Promise((resolve) => setImmediate(resolve));
		release();
		await eventually(() => run.planner.requests.length === 2);
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		assert.equal(run.planner.requests[1].planningPriority, 'urgent');
		assert.equal(run.bridge.sent.find((message) => message.type === 'action_command').payload.goalRevision, 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING);
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false, 'urgent recovery must not advance the authoritative server revision');
		assert.equal(runtimeErrors.some((error) => error.code === 'ILLEGAL_STATE_TRANSITION'), false);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('flushes conversation attention that arrived during initial planning after install', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const run = await start();
	let attempts = 0;
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		attempts += 1;
		if (attempts === 1) await gate;
		return attempts === 1
			? withCompletionContract({ summary: 'Installed.', directive: 'replace', source: SOURCE }, request.goalRevision)
			: { summary: 'Continue.', directive: 'continue' };
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Please answer now.', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 },
		});
		release();
		await eventually(() => run.planner.requests.length === 2);
		assert.equal(run.planner.requests[1].planningPriority, 'urgent');
		assert.match(run.planner.requests[1].input, /Please answer now\./);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('keeps a non-quiet initial provider failure active when no urgent recovery is pending', async () => {
	const run = await start();
	const runtimeErrors = [];
	run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		throw Object.assign(new Error('Provider unavailable'), { code: 'PROVIDER_DOWN' });
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.planner.requests.length === 1);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
		assert.equal(runtimeErrors.some((error) => error.code === 'PROVIDER_DOWN'), false);
		assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.ERROR);
		assert.notEqual(run.registry.get('agent-a').state, DynamicAgentState.PAUSED);
	} finally {
		await run.coordinator.stop();
	}
});

test('recovers a conversation captured during a real planner failure without advancing the server revision', async () => {
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	let attempts = 0;
	let firstStarted;
	const firstStartedPromise = new Promise((resolve) => { firstStarted = resolve; });
	const provider = realPlannerProvider(async (input) => {
		attempts += 1;
		if (attempts === 1) {
			firstStarted();
			await gate;
			throw Object.assign(new Error('Provider unavailable'), { code: 'PROVIDER_DOWN' });
		}
		assert.match(input, /Urgent: respond/);
		return { summary: 'Recovered.', directive: 'replace', source: SOURCE };
	});
	const registry = new AgentRegistry();
	const scheduler = new PlanningScheduler();
	const planner = new AgentPlanner({ registry, scheduler, codexService: provider });
	const run = await start({ registry, scheduler, planner, codexService: provider });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await firstStartedPromise;
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: { sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: 'Urgent: respond', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000 } });
		release();
		await eventually(() => attempts === 2 && run.bridge.sent.some((message) => message.type === 'action_command'));
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
		assert.equal(run.bridge.sent.find((message) => message.type === 'action_command').payload.goalRevision, 1);
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('keeps REQUEST_TIMEOUT retryable with a real planner and retries after backoff', async () => {
	let now = 100;
	let attempts = 0;
	const provider = realPlannerProvider(async () => {
		attempts += 1;
		throw Object.assign(new Error('request timed out'), { code: 'REQUEST_TIMEOUT' });
	});
	const registry = new AgentRegistry();
	const scheduler = new PlanningScheduler();
	const planner = new AgentPlanner({ registry, scheduler, codexService: provider });
	const run = await start({ registry, scheduler, planner, codexService: provider, controlNow: () => now });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Respond.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await eventually(() => attempts === 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PLANNING);
		now = 1_200;
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 1, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: [] } } } });
		await eventually(() => attempts === 2);
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
	} finally { await run.coordinator.stop(); }
});

test('installs a selected-model program and continues its next primitive without another provider turn', async () => {
	const run = await start();
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 1);
		const first = run.bridge.sent.find((message) => message.type === 'action_command');
		assert.equal(run.planner.requests[0].agentId, 'agent-a');
		assert.equal(first.payload.provenance.programId, 'program-1-1');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'action_command').length === 2);
		assert.equal(run.planner.requests.length, 1);
	} finally { await run.coordinator.stop(); }
});

test('publishes completed program state back to the server registry', async () => {
	const run = await start();
	run.planner.requestPlan = async (request) => {
		run.planner.requests.push(request);
		return withCompletionContract({
			summary: 'Wait, then finish.',
			directive: 'replace',
			source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");',
		}, request.goalRevision);
	};
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'Wait, then finish.', goalSpec: immutableGoalSpec('Wait, then finish.'),
		} });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_command'));
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 2, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'goal_completed'));
		const stateMessages = run.bridge.sent.filter((message) => message.type === 'goal_completed');
		const completion = stateMessages.at(-1);
		assert.equal(completion.type, 'goal_completed');
		assert.equal(completion.agentId, 'agent-a');
		assert.equal(completion.payload.goalRevision, 1);
		assert.equal(completion.payload.goalFingerprint, immutableGoalSpec('Wait, then finish.').fingerprint);
		assert.deepEqual(completion.payload.profile, {
			provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
		});
		assert.equal(completion.payload.traceId, 'trace-agent-a-1-1-initial');
		assert.match(completion.payload.goalFingerprint, /^[0-9a-f]{64}$/);
		run.bridge.emit('conversation_event', {
			agentId: 'agent-a',
			payload: {
				sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
				text: 'Are you still there?', goalRevision: 1, observedAtEpochMs: 1_787_184_000_000,
			},
		});
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 2, goal: 'Respond to the player.' } });
		await eventually(() => run.registry.get('agent-a').goalRevision === 2);
	} finally { await run.coordinator.stop(); }
});
