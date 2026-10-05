import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { FakePlanner, RecordingGoalSupervisor, factToWireObservation, immutableGoalSpec, eventually, start } from './fixtures/dynamic-main-fixture.mjs';

test('overlapping read/cancel calls preserve body supervision and finish waits for prior tools', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const supervisor = new RecordingGoalSupervisor();
	const leases = [], progressed = [], ended = [];
	supervisor.begin = (key, kind) => { const token = { ...key, kind, operationId: `lease-${leases.length}` }; leases.push(token); return token; };
	supervisor.progress = (token) => progressed.push(token);
	supervisor.end = (token) => ended.push(token);
	let releaseTurn;
	const turn = new Promise((resolve) => { releaseTurn = resolve; });
	planner.requestNativeTurn = async (request) => { planner.requests.push(request); await turn; return { status: 'completed', toolCalls: 4 }; };
	const run = await start({ registry, planner, goalSupervisor: supervisor, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const errors = [];
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	const pending = [];
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait, then verify.', goalSpec: immutableGoalSpec('Wait, then verify.') } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: factToWireObservation({ player: { x: 0, y: 64, z: 0 } }, 1, 1, false, 1) });
		await eventually(() => planner.requests.length === 1);
		const execute = (callId, tool) => {
			const result = planner.requests[0].executeTool({ agentId: 'agent-a', goalRevision: 1, turnId: 'overlap', callId, tool });
			pending.push(result);
			result.catch(() => {});
			return result;
		};
		const action = execute('body', { kind: 'action', actionType: 'wait', arguments: { durationMs: 1000 } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const command = run.bridge.sent.find(({ type }) => type === 'action_command');
		const bodyLease = leases.find(({ kind }) => kind === 'action');
		run.bridge.automaticInspections = false;
		let readDone = false;
		const read = execute('read', { kind: 'observe' }).then(
			(result) => { readDone = true; return { result }; },
			(error) => { readDone = true; return { error }; },
		);
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'inspection_request'));
		const progressedBefore = progressed.filter((token) => token === bodyLease).length;
		run.bridge.emit('action_progress', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'RUNNING', eventSequence: 2 } });
		await eventually(() => progressed.filter((token) => token === bodyLease).length > progressedBefore);
		assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
		const cancel = execute('cancel', { kind: 'cancel_action', actionId: command.payload.actionId, goalRevision: 1 });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_cancel'));
		assert.equal(readDone, false, 'exact cancel reaches the bridge without waiting for inspection');
		const finish = execute('finish', { kind: 'finish', summary: 'Wait cancelled as requested.' });
		assert.equal(run.bridge.sent.some(({ type }) => type === 'goal_completed'), false);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'CANCELLED', reasonCode: 'MODEL_CANCELLED', eventSequence: 3 } });
		assert.equal((await cancel).state, 'CANCELLED');
		assert.equal((await action).state, 'CANCELLED');
		assert.equal(ended.filter((token) => token === bodyLease).length, 1);
		assert.equal(registry.get('agent-a').state, DynamicAgentState.PLANNING);
		assert.equal(run.bridge.sent.some(({ type }) => type === 'goal_completed'), false, 'finish still waits for the earlier read');
		run.bridge.sampleInspection(run.bridge.sent.find(({ type }) => type === 'inspection_request'));
		assert.equal((await read).error?.code, 'STALE_NATIVE_TOOL', 'cancel retains the exact execution fence for an older read');
		assert.equal((await finish).state, 'COMPLETED');
		assert.equal(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
		assert.deepEqual(errors, []);
	} finally {
		releaseTurn();
		await run.coordinator.stop();
		await Promise.allSettled(pending);
	}
});
