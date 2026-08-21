import assert from 'node:assert/strict';
import test from 'node:test';

import {
	ReplayProvider,
	createReplayRecord,
	decisionHash,
	hashIdentity,
	normalizeDecision,
	verifyReplayDecision,
} from '../src/benchmark/provider-replay.mjs';

const PROFILE = Object.freeze({
	provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast',
});
const SCENARIO = Object.freeze({ id: 'wait', seed: 42, commands: [{ actionType: 'wait', arguments: { durationMs: 1 } }] });
const DECISION = Object.freeze({
	summary: 'wait', directive: 'replace',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
});

function record(overrides = {}) {
	return createReplayRecord({
		trialId: 'trial-1', prompt: 'prompt-v1', providerProfile: PROFILE, scenario: SCENARIO,
		protocolVersion: 2, decision: DECISION, ...overrides,
	});
}

test('records only bounded normalized decisions and exact identity hashes', () => {
	const result = record();
	assert.equal(result.trialId, 'trial-1');
	assert.equal(result.protocolVersion, 2);
	assert.equal(result.promptHash, hashIdentity('prompt-v1'));
	assert.equal(result.profileHash, hashIdentity(PROFILE));
	assert.equal(result.scenarioHash, hashIdentity(SCENARIO));
	assert.equal(result.decisionHash, decisionHash(DECISION));
	assert.deepEqual(result.decision, normalizeDecision(DECISION));
	assert.ok(Object.isFrozen(result));
	assert.equal(JSON.stringify(result).includes('prompt-v1'), false);
});

test('replays exact decisions and rejects identity drift before execution', async () => {
	const recording = record();
	const provider = new ReplayProvider({
		recordings: [recording], trialId: 'trial-1', prompt: 'prompt-v1',
		providerProfile: PROFILE, scenario: SCENARIO, protocolVersion: 2,
	});
	const session = await provider.createAgent({ agentId: 'agent-a', ...PROFILE });
	await session.setGoalRevision(1);
	assert.deepEqual(await session.decide('prompt-v1', { goalRevision: 1 }), DECISION);
	assert.deepEqual(await session.decide('prompt-v1', { goalRevision: 1 }), DECISION);
	assert.equal(provider.calls, 2);

	for (const drift of [
		{ prompt: 'prompt-v2' },
		{ providerProfile: { ...PROFILE, model: 'different-model' } },
		{ scenario: { ...SCENARIO, seed: 43 } },
		{ protocolVersion: 3 },
		{ trialId: 'trial-other' },
	]) {
		const drifted = new ReplayProvider({ recordings: [recording], trialId: 'trial-1', prompt: 'prompt-v1', providerProfile: PROFILE, scenario: SCENARIO, protocolVersion: 2, ...drift });
		await assert.rejects(async () => {
			const driftSession = await drifted.createAgent({ agentId: 'agent-a', ...PROFILE });
			await driftSession.setGoalRevision(1);
			await driftSession.decide('prompt-v1', { goalRevision: 1 });
		}, (error) => error.code === 'REPLAY_IDENTITY_MISMATCH');
	}
});

test('rejects a replay decision mismatch rather than accepting provider drift', () => {
	const recording = record();
	assert.throws(
		() => verifyReplayDecision(recording, { ...DECISION, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);' }),
		(error) => error.code === 'REPLAY_DECISION_MISMATCH',
	);
});
