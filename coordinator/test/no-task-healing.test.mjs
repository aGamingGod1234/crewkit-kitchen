import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { NO_TASK_HEAL_INSTRUCTION, healingFacts, isSelfPreservationTool, lowHealthWake } from '../src/dynamic-main.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { FakeBridge, FakePlanner, immutableGoalSpec, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

// Play-test: with no task, the agent escaped summoned creepers at one heart and then just stood there. Low health
// now gives the model a no-task turn in which it may eat or get food; it still decides, and other work needs takeTask.
const NATIVE_CONFIG = {
	bridge: { port: 25570, secret: 's'.repeat(32) },
	codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
};
const COW = '00000000-0000-0000-0000-0000000000c1';
const DROP = '00000000-0000-0000-0000-0000000000d1';
const FINISH = { kind: 'finish', summary: 'Done.' };
const act = (actionType, args) => normalizeMinecraftToolCall('act', { actionType, arguments: args });
const PICK_UP = act('pick_up_item', { targetSelector: DROP });
const HARVEST = act('break_block', { x: 3, y: 64, z: 0, expectedBlockId: 'minecraft:wheat', timeoutMs: 5_000 });
const MINE_STONE = act('break_block', { x: 1, y: 64, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 5_000 });
const settle = async () => { for (let index = 0; index < 8; index += 1) await new Promise((resolve) => setImmediate(resolve)); };

test('low health wakes once per health level and rearms after recovery', () => {
	const at = (health, extra = {}) => ({ player: { health, maxHealth: 20, ...extra } });
	assert.deepEqual(lowHealthWake(at(12)), { wake: false, health: null }, '60% is not low');
	assert.deepEqual(lowHealthWake(at(10)), { wake: true, health: 10 }, 'half health is low');
	assert.deepEqual(lowHealthWake(at(10), 10), { wake: false, health: 10 }, 'the same level does not nag');
	assert.deepEqual(lowHealthWake(at(11), 10), { wake: false, health: 10 }, 'regenerating does not wake again');
	assert.deepEqual(lowHealthWake(at(2), 10), { wake: true, health: 2 }, 'losing more health wakes again');
	assert.deepEqual(lowHealthWake(at(14), 2), { wake: false, health: null }, 'recovered to 70% rearms');
	assert.equal(lowHealthWake(at(5, { operatorControlled: true })).wake, false, 'a takeover owns the body');
	assert.equal(lowHealthWake(at(5, { gameMode: 'creative' })).wake, false);
	assert.equal(lowHealthWake(at(0, { dead: true })).wake, false);
	assert.equal(lowHealthWake({ player: { health: 6 } }).wake, true, 'six health points is low even without maxHealth');
	assert.equal(lowHealthWake({ player: { health: 15, maxHealth: 40 } }).wake, true, 'half of a larger maximum is low');
});

test('healing facts list carried food and the nearest food sources in view', () => {
	const facts = healingFacts({
		player: { x: 0, y: 64, z: 0, health: 2, maxHealth: 20, foodLevel: 9, saturation: 0, safe: true, bestFood: null, threats: [] },
		entities: [{ stableId: COW, type: 'minecraft:cow', x: 6, y: 64, z: 0, distance: 6 }, { stableId: 'z', type: 'minecraft:zombie', x: 2, y: 64, z: 0 }],
		blocks: [{ stableId: '3,64,0', x: 3, y: 64, z: 0, blockId: 'minecraft:wheat', state: { age: '7' } },
			{ stableId: '4,64,0', x: 4, y: 64, z: 0, blockId: 'minecraft:wheat', state: { age: '2' } },
			{ stableId: '1,64,0', x: 1, y: 64, z: 0, blockId: 'minecraft:stone' }],
		items: [{ stableId: DROP, itemId: 'minecraft:beef', count: 2, x: 5, y: 64, z: 0 }, { stableId: 'x', itemId: 'minecraft:cobblestone', count: 1, x: 1, y: 64, z: 0 }],
	});
	assert.equal(facts.health, 2);
	assert.equal(facts.foodLevel, 9);
	assert.deepEqual(facts.foodSources.animals, [{ stableId: COW, type: 'minecraft:cow', distance: 6 }]);
	assert.deepEqual(facts.foodSources.plants.map(({ ripe, distance }) => ({ ripe, distance })), [{ ripe: true, distance: 3 }, { ripe: false, distance: 4 }]);
	assert.deepEqual(facts.foodSources.drops, [{ stableId: DROP, itemId: 'minecraft:beef', count: 2, distance: 5 }]);
});

test('getting food counts as self-preservation; other breaking does not', () => {
	assert.equal(isSelfPreservationTool(PICK_UP), true);
	assert.equal(isSelfPreservationTool(HARVEST), true);
	assert.equal(isSelfPreservationTool(act('break_block', { x: 3, y: 64, z: 0, expectedBlockId: 'minecraft:melon', timeoutMs: 5_000 })), true);
	assert.equal(isSelfPreservationTool(act('break_block', { x: 3, y: 64, z: 0, expectedBlockId: 'minecraft:cave_vines_plant', timeoutMs: 5_000 })), false);
	assert.equal(isSelfPreservationTool(MINE_STONE), false);
	assert.equal(isSelfPreservationTool(act('place_block', { x: 1, y: 64, z: 0, face: 'up', itemId: 'minecraft:dirt' })), false);
	assert.equal(isSelfPreservationTool(normalizeMinecraftToolCall('sequence', { actions: [
		{ actionType: 'look_at', arguments: { x: 3.5, y: 64.5, z: 0.5 } },
		{ actionType: 'break_block', arguments: { x: 3, y: 64, z: 0, expectedBlockId: 'minecraft:wheat', timeoutMs: 5_000 } }] })), true);
});

function scriptedPlanner(registry, turns) {
	const planner = new FakePlanner(registry);
	planner.outcomes = [];
	planner.steerNativeTurn = async () => {};
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const turn = planner.requests.length;
		let calls = 0;
		for (const [index, step] of (turns[turn - 1] ?? []).entries()) {
			calls += 1;
			const outcome = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: `turn-${turn}`, callId: `call-${turn}-${index}`, tool: step })
				.then((result) => ({ turn, tool: step.kind, result }), (error) => ({ turn, tool: step.kind, error }));
			planner.outcomes.push(outcome);
		}
		return { status: 'completed', toolCalls: calls };
	};
	return planner;
}

async function completedAgent(turns) {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = scriptedPlanner(registry, [[FINISH], ...turns]);
	const traces = [];
	const run = await start({ bridge, registry, planner, config: NATIVE_CONFIG, traceWriter: { write(event, details) { traces.push({ event, ...details }); } } });
	bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Craft a table.', goalSpec: immutableGoalSpec('Craft a table.') } });
	bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 } } } });
	await eventually(() => planner.outcomes.length === 1);
	bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'complete', goalRevision: 2 } });
	await eventually(() => registry.get('agent-a').state === DynamicAgentState.COMPLETED);
	return { bridge, registry, planner, traces, run };
}

const lowAndSafe = (eventSequence, health, extra = {}) => ({ agentId: 'agent-a', payload: { goalRevision: 2, eventSequence, observation: {
	player: { x: 0, y: 64, z: 0, health, maxHealth: 20, foodLevel: 8, ...extra },
	entities: [{ stableId: COW, type: 'minecraft:cow', x: 6, y: 64, z: 0 }],
	blocks: [{ stableId: '3,64,0', x: 3, y: 64, z: 0, blockId: 'minecraft:wheat', state: { age: '7' } }],
	items: [{ stableId: DROP, itemId: 'minecraft:beef', count: 1, x: 2, y: 64, z: 0 }],
} } });
const commands = (bridge) => bridge.sent.filter(({ type }) => type === 'action_command').map(({ payload }) => payload.actionType);
async function succeedNext(bridge, index, reasonCode) {
	await eventually(() => bridge.sent.filter(({ type }) => type === 'action_command').length > index);
	const command = bridge.sent.filter(({ type }) => type === 'action_command')[index];
	bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: command.payload.goalRevision, actionId: command.payload.actionId,
		actionType: command.payload.actionType, state: 'SUCCEEDED', reasonCode, executionStarted: true, eventSequence: 50 + index } });
}

test('a completed agent at low health gets one recovery turn that may get food but not do other work', async () => {
	const { bridge, registry, planner, traces, run } = await completedAgent([[PICK_UP, HARVEST, MINE_STONE]]);
	try {
		bridge.emit('observation', lowAndSafe(2, 2));
		await succeedNext(bridge, 0, 'ITEM_PICKED_UP');
		await succeedNext(bridge, 1, 'BLOCK_BROKEN');
		await eventually(() => planner.outcomes.length === 4);
		const [instruction, json] = [planner.requests[1].input.split('\n')[0], JSON.parse(planner.requests[1].input.slice(planner.requests[1].input.indexOf('\n') + 1))];
		assert.equal(instruction, NO_TASK_HEAL_INSTRUCTION);
		assert.equal(json.trigger, 'low_health_idle');
		assert.equal(json.healing.health, 2);
		assert.equal(json.healing.foodSources.plants[0].blockId, 'minecraft:wheat');
		assert.equal(json.healing.foodSources.drops[0].stableId, DROP);
		assert.deepEqual(commands(bridge), ['pick_up_item', 'break_block'], 'picking up and harvesting food reach Minecraft without a task');
		assert.equal(planner.outcomes[1].result?.state, 'SUCCEEDED');
		assert.equal(planner.outcomes[3].error?.code, 'CONVERSATION_ONLY', 'mining stone still needs takeTask');
		assert.equal(registry.get('agent-a').state, DynamicAgentState.COMPLETED, 'recovering never reopens the finished task');
		assert.equal(traces.filter(({ event }) => event === 'native_heal_wake').length, 1);
		bridge.emit('observation', lowAndSafe(3, 2));
		await settle();
		assert.equal(planner.requests.length, 2, 'the same low health does not wake again, and the turn is not retried for a chat reply');
	} finally {
		await run.coordinator.stop();
	}
});

test('a harvest of a ripe crop dispatches with no task', async () => {
	const { bridge, planner, run } = await completedAgent([[HARVEST]]);
	try {
		bridge.emit('observation', lowAndSafe(2, 4));
		await succeedNext(bridge, 0, 'BLOCK_BROKEN');
		await eventually(() => planner.outcomes.length === 2);
		assert.equal(planner.outcomes[1].result?.state, 'SUCCEEDED');
	} finally {
		await run.coordinator.stop();
	}
});

test('a danger turn comes first; when it ends at low health the recovery turn follows', async () => {
	const { bridge, planner, run } = await completedAgent([[], []]);
	try {
		bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 2, eventSequence: 2, attention: true, changedFacts: ['player.health'],
			observation: { player: { x: 0, y: 64, z: 0, health: 2, maxHealth: 20 }, items: [{ stableId: DROP, itemId: 'minecraft:beef', count: 1, x: 2, y: 64, z: 0 }] } } });
		await eventually(() => planner.requests.length === 3);
		assert.match(planner.requests[1].input.split('\n')[0], /danger/);
		assert.equal(planner.requests[2].input.split('\n')[0], NO_TASK_HEAL_INSTRUCTION);
	} finally {
		await run.coordinator.stop();
	}
});

test('takeover and healthy bodies are never woken to heal', async () => {
	const { bridge, planner, run } = await completedAgent([]);
	try {
		bridge.emit('observation', lowAndSafe(2, 3, { operatorControlled: true }));
		bridge.emit('observation', lowAndSafe(3, 18));
		await settle();
		assert.equal(planner.requests.length, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('control frames with no task may move and aim but not attack or use; berries are a right-click', () => {
	const frame = (attack, use) => ({ forward: 1, strafe: 0, jump: false, sneak: false, sprint: true, attack, use, yaw: 0, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 10 });
	assert.equal(isSelfPreservationTool(act('control', frame(false, false))), true);
	assert.equal(isSelfPreservationTool(act('control', frame(true, false))), false, 'a held attack could break any block');
	assert.equal(isSelfPreservationTool(act('control', frame(false, true))), false, 'a held use could place blocks or open containers');
	assert.equal(isSelfPreservationTool(act('control_sequence', { frames: [frame(false, false), frame(false, true)], maxTicks: 20 })), false);
	assert.equal(isSelfPreservationTool(act('control_sequence', { frames: [frame(false, false)], maxTicks: 20 })), true);
	assert.equal(isSelfPreservationTool(act('interact_block', { x: 3, y: 64, z: 0, face: 'up', hand: 'main', expectedItemId: 'minecraft:air' })), true, 'Minecraft checks the berries are ripe');
	assert.equal(isSelfPreservationTool(act('break_block', { x: 3, y: 64, z: 0, expectedBlockId: 'minecraft:sweet_berry_bush', timeoutMs: 5_000 })), false, 'berries are picked, not the bush broken');
});

test('heal wakes need a 2+ point further drop, and a regenerating body with nothing to eat is left alone', () => {
	const at = (health, extra = {}) => ({ player: { health, maxHealth: 20, ...extra } });
	assert.equal(lowHealthWake(at(9), 10).wake, false, 'one point of starvation does not wake again');
	assert.equal(lowHealthWake(at(8), 10).wake, true);
	assert.equal(lowHealthWake(at(4, { foodLevel: 19 })).wake, false, 'a full food bar regenerates and there is nothing to eat');
	assert.equal(lowHealthWake({ ...at(4, { foodLevel: 19 }), entities: [{ stableId: COW, type: 'minecraft:cow', x: 1, y: 0, z: 0 }] }).wake, true, 'food in view is worth a turn');
	assert.equal(lowHealthWake(at(4, { foodLevel: 19, bestFood: { slot: 0, itemId: 'minecraft:bread', nutrition: 5 } })).wake, true);
	assert.equal(lowHealthWake(at(4, { foodLevel: 12 })).wake, true, 'a hungry body should hear about it');
});
