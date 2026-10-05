import assert from 'node:assert/strict';
import test from 'node:test';
import { FactLedger } from '../src/fact-ledger.mjs';
import { eventually, start } from './fixtures/dynamic-main-fixture.mjs';

test('ArenaScript rejects a wrong-type receipt before facts, completion, or cancellation', async (t) => {
	const run = await start();
	const errors = [], acks = [];
	let delivered = 0;
	run.coordinator.on('runtimeError', (error) => errors.push(error));
	run.coordinator.on('actionResult', () => delivered++);
	run.bridge.acknowledgeActionResult = async (_agentId, payload) => { acks.push(payload); };
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait twice.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0 } } } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const command = run.bridge.sent.find(({ type }) => type === 'action_command').payload;
		assert.equal(command.actionType, 'wait');
		const ingest = t.mock.method(FactLedger.prototype, 'ingest');
		const terminal = { goalRevision: 1, actionId: command.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 };
		let ingress;
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...terminal, actionType: 'chat', reasonCode: 'CHAT_SENT' }, waitUntil(promise) { ingress = promise; } });
		await ingress;
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(delivered, 0, 'the authored wait must remain active');
		assert.equal(ingest.mock.calls.filter(({ arguments: args }) => args[0] === 'action_result').length, 0);
		assert.equal(run.bridge.sent.some(({ type }) => ['action_cancel', 'agent_error', 'request_observation'].includes(type)), false);
		assert.equal(acks.length, 0);
		assert.deepEqual(errors.map(({ code }) => code), ['UNCORRELATED_ACTION_RECEIPT']);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { ...terminal, actionType: 'wait' } });
		await eventually(() => delivered === 1 && acks.length === 1);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 3, observation: { player: { x: 0, y: 64, z: 0 } } } });
		await eventually(() => run.bridge.sent.filter(({ type }) => type === 'action_command').length === 2);
		assert.deepEqual(errors.map(({ code }) => code), ['UNCORRELATED_ACTION_RECEIPT']);
	} finally { await run.coordinator.stop(); }
});
