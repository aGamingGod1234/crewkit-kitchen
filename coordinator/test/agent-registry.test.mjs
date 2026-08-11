import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, AgentRegistryError, DynamicAgentState, decodeAgentRegistrySnapshot, encodeAgentRegistrySnapshot, normalizeAgentRecord } from '../src/agent-registry.mjs';

function record(agentId, overrides = {}) {
	return {
		agentId,
		provider: 'codex',
		model: 'gpt-5.6-sol',
		reasoningEffort: 'high',
		state: DynamicAgentState.IDLE,
		goalRevision: 0,
		queue: [],
		...overrides,
	};
}

test('registry dynamically adds, snapshots, and removes agents without exposing mutable records', () => {
	const registry = new AgentRegistry({ agentCap: 2 });
	const added = registry.register(record('agent-a'));
	added.model = 'mutated';
	assert.equal(registry.get('agent-a').model, 'gpt-5.6-sol');
	registry.register(record('agent-b'));
	assert.throws(() => registry.register(record('agent-c')), (error) => error instanceof AgentRegistryError && error.code === 'AGENT_CAP_REACHED');
	assert.equal(registry.remove('agent-a').agentId, 'agent-a');
	assert.equal(registry.remove('agent-a'), null);
});

test('goal controls enforce monotonic revisions, queue bounds, and FIFO promotion', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Build shelter.' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Find food.' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Plant wheat.' });
	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Mine iron.' }),
		(error) => error.code === 'GOAL_QUEUE_FULL',
	);
	const promoted = registry.applyGoalControl('agent-a', { operation: 'complete', goalRevision: 2 });
	assert.equal(promoted.currentGoal, 'Find food.');
	assert.equal(promoted.queue[0].goal, 'Plant wheat.');
	assert.equal(promoted.state, DynamicAgentState.STARTING);
	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 2 }),
		(error) => error.code === 'STALE_GOAL_REVISION',
	);
});

test('server start promotions consume exactly the queued head through exhaustion', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });
	const promotedB = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'B' });
	assert.equal(promotedB.currentGoal, 'B');
	assert.deepEqual(promotedB.queue.map((entry) => entry.goal), ['C']);

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 2 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 2 });
	const promotedC = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 3, goal: 'C' });
	assert.equal(promotedC.currentGoal, 'C');
	assert.deepEqual(promotedC.queue, []);

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 3 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 3 });
	const exhausted = registry.applyGoalControl('agent-a', { operation: 'complete', goalRevision: 4 });
	assert.equal(exhausted.currentGoal, null);
	assert.deepEqual(exhausted.queue, []);
	assert.equal(exhausted.state, DynamicAgentState.IDLE);
});

test('server start promotions reject a goal that is not the queued head', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });

	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'C' }),
		(error) => error instanceof AgentRegistryError && error.code === 'PROMOTED_GOAL_MISMATCH',
	);
});

test('reconciliation pauses active persisted agents and rejects duplicate identities', () => {
	const registry = new AgentRegistry();
	const result = registry.reconcile([
		record('agent-a', { state: DynamicAgentState.ACTING, currentGoal: 'Explore.', goalRevision: 7 }),
	]);
	assert.deepEqual(result.added, ['agent-a']);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.PAUSED);
	assert.throws(() => registry.reconcile([record('agent-a'), record('agent-a')]), (error) => error.code === 'DUPLICATE_AGENT');
});

test('stop cleanup is idempotent while new commands still require newer revisions', () => {
	const registry = new AgentRegistry();
	registry.register(record('agent-a', { state: DynamicAgentState.ACTING, currentGoal: 'Explore.', goalRevision: 1 }));
	const stopped = registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 2 });
	const repeated = registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 2 });
	assert.deepEqual(repeated, stopped);
	assert.throws(() => registry.applyGoalControl('agent-a', { operation: 'resume', goalRevision: 2 }), (error) => error.code === 'STALE_GOAL_REVISION');
});

test('runtime state changes reject illegal lifecycle transitions', () => {
	const registry = new AgentRegistry({ now: () => 99 });
	registry.register(record('agent-a'));
	assert.throws(() => registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 0 }), (error) => error.code === 'ILLEGAL_STATE_TRANSITION');
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Explore.', updatedAtEpochMs: 1 });
	const planning = registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	assert.equal(planning.updatedAtEpochMs, 99);
});

test('record normalization excludes transient coordinator handles', () => {
	const normalized = normalizeAgentRecord({ ...record('agent-a'), socket: {}, threadId: 'thread-secret', activeTurnId: 'turn-secret' });
	assert.equal(Object.hasOwn(normalized, 'socket'), false);
	assert.equal(Object.hasOwn(normalized, 'threadId'), false);
	assert.equal(Object.hasOwn(normalized, 'activeTurnId'), false);
});

test('registry persistence codec is deterministic and pauses active work on reload', () => {
	const encoded = encodeAgentRegistrySnapshot([
		record('agent-b'),
		record('agent-a', { state: DynamicAgentState.PLANNING, currentGoal: 'Build.', goalRevision: 2 }),
	]);
	const decoded = decodeAgentRegistrySnapshot(encoded);
	assert.deepEqual(decoded.map((entry) => entry.agentId), ['agent-a', 'agent-b']);
	assert.equal(decoded[0].state, DynamicAgentState.PAUSED);
	assert.equal(decoded[0].provider, 'codex');
});

test('legacy registry snapshots migrate to the Codex provider while explicit providers round-trip', () => {
	const legacy = JSON.stringify({ schemaVersion: 1, agents: [record('legacy', { provider: undefined })] });
	assert.equal(decodeAgentRegistrySnapshot(legacy)[0].provider, 'codex');
	const encoded = encodeAgentRegistrySnapshot([record('kimi', { provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max' })]);
	assert.equal(decodeAgentRegistrySnapshot(encoded)[0].provider, 'kimi');
	assert.throws(() => normalizeAgentRecord(record('bad', { provider: 'unknown' })), /provider/i);
});
