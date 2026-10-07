import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { normalizeMinecraftToolCall, NATIVE_AGENT_INSTRUCTIONS, MINECRAFT_DYNAMIC_TOOLS } from '../src/native-minecraft-tools.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { DeferredCompletionBridge, FakePlanner, record, immutableGoalSpec, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

// A player's DM to an agent with no active task: the model decides whether it is a request,
// adopts it with takeTask, and Minecraft decides whether the goal may start.
const PLAYER = '11111111-1111-4111-8111-111111111111';
const NATIVE_CONFIG = {
	bridge: { port: 25570, secret: 's'.repeat(32) },
	codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
};
const WAIT = normalizeMinecraftToolCall('wait', { durationMs: 1_000 });

const dm = (text, sequence, goalRevision) => ({ agentId: 'agent-a', payload: {
	sequence, kind: 'player_message', sourceId: PLAYER, recipientId: 'agent-a', scope: 'direct',
	text, goalRevision, observedAtEpochMs: 10 + sequence,
} });
const observation = (goalRevision, eventSequence) => ({ agentId: 'agent-a', payload: {
	goalRevision, eventSequence, observation: { player: { x: 0, y: 64, z: 0 } },
} });
const inputOf = (request) => JSON.parse(request.input.slice(request.input.indexOf('\n') + 1));
const settle = async () => { for (let index = 0; index < 8; index += 1) await new Promise((resolve) => setImmediate(resolve)); };

/** Each planner turn runs its scripted tool calls and records every outcome. */
function scriptedPlanner(registry, turns) {
	const planner = new FakePlanner(registry);
	planner.outcomes = [];
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const turn = planner.requests.length;
		const script = turns[turn - 1] ?? [];
		for (const [index, tool] of script.entries()) {
			const outcome = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: `turn-${turn}`, callId: `call-${turn}-${index}`, tool })
				.then((result) => ({ turn, tool: tool.kind, result }), (error) => ({ turn, tool: tool.kind, error }));
			planner.outcomes.push(outcome);
		}
		return { status: 'completed', toolCalls: script.length };
	};
	return planner;
}

function answerTaskRequest(bridge, { status = 'accepted', reasonCode = 'TASK_STARTED', message = 'Started.', goalRevision }) {
	const request = bridge.sent.filter(({ type }) => type === 'task_request').at(-1);
	bridge.emit('task_request_result', { agentId: 'agent-a', payload: { requestId: request.payload.requestId, status, reasonCode, message, goalRevision } });
	return request;
}

async function finishWait(bridge, run) {
	await eventually(() => bridge.sent.some(({ type, payload }) => type === 'action_command' && payload.actionType === 'wait'));
	const command = bridge.sent.find(({ type, payload }) => type === 'action_command' && payload.actionType === 'wait');
	run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
		goalRevision: command.payload.goalRevision, actionId: command.payload.actionId, actionType: 'wait', state: 'SUCCEEDED',
		reasonCode: 'WAITED', executionStarted: true, eventSequence: 9,
	} });
}

test('takeTask is a model-visible tool and the native instructions tell the model when to use it', () => {
	assert.ok(MINECRAFT_DYNAMIC_TOOLS.some((tool) => tool.name === 'takeTask'));
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /No active task: requests mean takeTask, then act; else say\./);
	assert.deepEqual(normalizeMinecraftToolCall('takeTask', {}), { kind: 'take_task', resume: false });
	assert.deepEqual(normalizeMinecraftToolCall('takeTask', { request: 'Continue mining iron.', resume: true, requesterId: PLAYER.toUpperCase() }),
		{ kind: 'take_task', request: 'Continue mining iron.', resume: true, requesterId: PLAYER });
	assert.throws(() => normalizeMinecraftToolCall('takeTask', { requesterId: 'player-a' }), /player UUID/);
	assert.throws(() => normalizeMinecraftToolCall('takeTask', { goal: 'x' }));
	assert.deepEqual(validateProtocolV2Payload('task_request', { requestId: 'r-1', goalRevision: 0, requesterId: PLAYER, request: 'get wood', resume: false }),
		{ requestId: 'r-1', goalRevision: 0, requesterId: PLAYER, request: 'get wood', resume: false });
	assert.throws(() => validateProtocolV2Payload('task_request', { requestId: 'r-1', goalRevision: 0, requesterId: 'nobody', request: 'get wood', resume: false }));
	assert.equal(validateProtocolV2Payload('task_request_result', { requestId: 'r-1', status: 'rejected', reasonCode: 'AGENT_TAKEN_OVER', message: 'Busy.', goalRevision: 0 }).status, 'rejected');
	assert.throws(() => validateProtocolV2Payload('task_request_result', { requestId: 'r-1', status: 'maybe', reasonCode: 'X', message: 'Busy.', goalRevision: 0 }));
});

test('an idle agent adopts "go get a stone pickaxe" with takeTask, then acts with every tool', async () => {
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [
		[WAIT, { kind: 'take_task', resume: false }, WAIT],
		[WAIT],
	]);
	const run = await start({ registry, planner, config: NATIVE_CONFIG });
	try {
		run.bridge.emit('conversation_event', dm('go get a stone pickaxe', 1, 0));
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'task_request'));
		const first = inputOf(planner.requests[0]);
		assert.equal(first.mode, 'conversation_only');
		assert.match(planner.requests[0].input, /call takeTask/);
		assert.equal(planner.outcomes[0].error?.code, 'CONVERSATION_ONLY', 'body tools still need an adopted task');
		assert.match(planner.outcomes[0].error.message, /call takeTask first/);
		// Minecraft starts the goal through its normal lifecycle, then answers the tool.
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 1, goal: 'go get a stone pickaxe', goalSpec: immutableGoalSpec('go get a stone pickaxe'),
		} });
		const request = answerTaskRequest(run.bridge, { goalRevision: 1 });
		assert.deepEqual({ ...request.payload, requestId: undefined }, { requestId: undefined, goalRevision: 0, requesterId: PLAYER, request: 'go get a stone pickaxe', resume: false });
		await eventually(() => planner.outcomes.length === 3);
		assert.equal(planner.outcomes[1].result.state, 'SUCCEEDED');
		assert.equal(planner.outcomes[1].result.goalRevision, 1);
		assert.match(planner.outcomes[1].result.message, /End this turn now/);
		assert.equal(planner.outcomes[2].result.reasonCode, 'STALE_PLAN', 'the conversation turn dispatches nothing after adoption');
		assert.match(planner.outcomes[2].result.message, /task was accepted/);
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'action_command').length, 0);
		await eventually(() => registry.get('agent-a').state === DynamicAgentState.STARTING);
		run.bridge.emit('observation', observation(1, 1));
		await eventually(() => planner.requests.length === 2);
		assert.equal(inputOf(planner.requests[1]).mode, 'goal');
		assert.equal(planner.requests[1].goalRevision, 1);
		await finishWait(run.bridge, run);
		await eventually(() => planner.outcomes.length === 4);
		assert.equal(planner.outcomes[3].result?.state, 'SUCCEEDED', 'the adopted task turn may act');
	} finally {
		await run.coordinator.stop();
	}
});

test('a completed agent told "continue" adopts a model-written task and starts a goal turn', async () => {
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[{ kind: 'take_task', request: 'Continue mining iron near the cave.', resume: false }], []]);
	const run = await start({ registry, planner, config: NATIVE_CONFIG, initialRegistry: [{ ...record(), state: DynamicAgentState.COMPLETED, goalRevision: 3, currentGoal: 'Mine some iron.' }] });
	try {
		run.bridge.emit('conversation_event', dm('continue', 1, 3));
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'task_request'));
		assert.equal(inputOf(planner.requests[0]).mode, 'conversation_only');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: {
			operation: 'start', goalRevision: 4, goal: 'Continue mining iron near the cave.', goalSpec: immutableGoalSpec('Continue mining iron near the cave.'),
		} });
		const request = answerTaskRequest(run.bridge, { goalRevision: 4 });
		assert.equal(request.payload.request, 'Continue mining iron near the cave.');
		assert.equal(request.payload.goalRevision, 3);
		assert.equal(request.payload.requesterId, PLAYER);
		await eventually(() => planner.outcomes.length === 1);
		assert.equal(planner.outcomes[0].result.state, 'SUCCEEDED');
		await eventually(() => registry.get('agent-a').state === DynamicAgentState.STARTING);
		run.bridge.emit('observation', observation(4, 1));
		await eventually(() => planner.requests.length === 2);
		assert.equal(inputOf(planner.requests[1]).mode, 'goal');
		assert.equal(inputOf(planner.requests[1]).goal, 'Continue mining iron near the cave.');
	} finally {
		await run.coordinator.stop();
	}
});

test('a refused takeTask returns the server reason and the agent stays idle and can still reply', async () => {
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[
		{ kind: 'take_task', resume: false },
		{ kind: 'action', actionType: 'chat', arguments: { message: 'Someone is controlling me right now.', audience: 'direct', recipientId: PLAYER } },
	]]);
	const run = await start({ registry, planner, config: NATIVE_CONFIG });
	try {
		run.bridge.emit('conversation_event', dm('go get a stone pickaxe', 1, 0));
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'task_request'));
		answerTaskRequest(run.bridge, { status: 'rejected', reasonCode: 'AGENT_TAKEN_OVER', message: 'A player is controlling this agent right now.', goalRevision: 0 });
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'action_command' && payload.actionType === 'chat'));
		assert.equal(planner.outcomes[0].result.state, 'REJECTED');
		assert.equal(planner.outcomes[0].result.reasonCode, 'AGENT_TAKEN_OVER');
		assert.match(planner.outcomes[0].result.message, /controlling this agent.*Tell the player with say/);
		assert.equal(registry.get('agent-a').state, DynamicAgentState.IDLE);
	} finally {
		await run.coordinator.stop();
	}
});

test('plain chat stays a chat-only reply: no task request and no body actions', async () => {
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[
		{ kind: 'action', actionType: 'chat', arguments: { message: 'Hi Lucas!', audience: 'direct', recipientId: PLAYER } },
		WAIT,
	]]);
	const run = await start({ registry, planner, config: NATIVE_CONFIG });
	try {
		run.bridge.emit('conversation_event', dm('hi', 1, 0));
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'action_command' && payload.actionType === 'chat'));
		const chat = run.bridge.sent.find(({ type, payload }) => type === 'action_command' && payload.actionType === 'chat');
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 0, actionId: chat.payload.actionId, actionType: 'chat', state: 'SUCCEEDED', reasonCode: 'CHAT_SENT', executionStarted: true, eventSequence: 1,
		} });
		await eventually(() => planner.outcomes.length === 2);
		assert.equal(planner.outcomes[1].error?.code, 'CONVERSATION_ONLY');
		await settle();
		assert.equal(run.bridge.sent.some(({ type }) => type === 'task_request'), false);
		assert.equal(registry.get('agent-a').state, DynamicAgentState.IDLE);
		assert.equal(planner.requests.length, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('takeTask without any player message is refused locally and sends nothing', async () => {
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[{ kind: 'take_task', resume: false, requesterId: '22222222-2222-4222-8222-222222222222' }]]);
	const run = await start({ registry, planner, config: NATIVE_CONFIG });
	try {
		run.bridge.emit('conversation_event', dm('hello there', 1, 0));
		await eventually(() => planner.outcomes.length === 1);
		assert.equal(planner.outcomes[0].result.reasonCode, 'UNKNOWN_REQUESTER', 'a model cannot name a requester who never asked');
		assert.equal(run.bridge.sent.some(({ type }) => type === 'task_request'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('an agent finished and waiting for confirmation gets a full goal turn when told "continue"', async () => {
	const bridge = new DeferredCompletionBridge();
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[{ kind: 'finish', summary: 'The logs are collected.' }], [WAIT]]);
	const run = await start({ bridge, registry, planner, config: NATIVE_CONFIG });
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Collect 8 oak logs.', goalSpec: immutableGoalSpec('Collect 8 oak logs.') } });
		bridge.emit('observation', observation(1, 1));
		await eventually(() => bridge.sent.some(({ type }) => type === 'goal_completed'));
		const completion = bridge.sent.find(({ type }) => type === 'goal_completed');
		bridge.emit('goal_completion_result', { agentId: 'agent-a', payload: {
			goalRevision: 1, traceId: completion.payload.traceId, goalFingerprint: completion.payload.goalFingerprint,
			verified: false, reasonCode: 'PREDICATE_FAILED', facts: [{ type: 'operator_confirmed', satisfied: false }],
		} });
		await eventually(() => planner.outcomes.length === 1);
		assert.equal(planner.outcomes[0].result.state, 'AWAITING_OPERATOR_CONFIRMATION');
		await settle();
		bridge.emit('conversation_event', dm('continue', 1, 1));
		await eventually(() => planner.requests.length === 2);
		assert.equal(inputOf(planner.requests[1]).mode, 'goal', 'awaiting confirmation is still an active goal, never conversation-only');
		await finishWait(bridge, run);
		await eventually(() => planner.outcomes.length === 2);
		assert.equal(planner.outcomes[1].result?.state, 'SUCCEEDED');
	} finally {
		await run.coordinator.stop();
	}
});
