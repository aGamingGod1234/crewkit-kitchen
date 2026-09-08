import test from 'node:test';
import assert from 'node:assert/strict';
import { generateDirectorScript, parseDirectorScript } from '../src/director-script-generator.mjs';
test('Director generation is isolated Luna low and returns only a bounded draft', async () => {
	let profile, protocol, removed, options;
	const service = {
		createAgent: async (p, o) => {
			profile = p;
			protocol = o.controlProtocol;
			return {
				setGoalRevision: async () => {},
				decide: async (prompt, o) => {
					options = o;
					assert.match(prompt, /here/);
					return o.parseOutput(
						JSON.stringify({
							steps: [
								{ action: 'move', arguments: '40', destination: 'here', right: 0, up: 0, forward: 0 },
							],
						}),
					);
				},
			};
		},
		removeAgent: async (id) => {
			removed = id;
		},
	};
	const result = await generateDirectorScript(service, {
		requestId: 'test-1',
		description: 'Fly here',
		actorName: 'Astra',
	});
	assert.equal(profile.model, 'gpt-5.6-luna');
	assert.equal(profile.reasoningEffort, 'low');
	assert.equal(protocol, 'director_script');
	assert.equal(result.steps.length, 1);
	assert.equal(removed, profile.agentId);
	assert.ok(options.outputSchema);
});
test('Malformed or unsupported generated actions are rejected', () => {
	for (const value of [
		{ steps: [] },
		{ steps: [{ action: 'shell', arguments: 'rm -rf', destination: 'here', right: 0, up: 0, forward: 0 }] },
		{ steps: [{ action: 'move', arguments: '40', destination: 'here', right: 0, up: Infinity, forward: 0 }] },
	])
		assert.throws(() => parseDirectorScript(JSON.stringify(value)));
});

test('Failed generation disposes its temporary session', async () => {
	let removed;
	const service = {
		createAgent: async () => {
			throw new Error('Luna unavailable');
		},
		removeAgent: async (id) => {
			removed = id;
		},
	};
	await assert.rejects(
		() => generateDirectorScript(service, { requestId: 'failure', description: 'Jump', actorName: 'Astra' }),
		/Luna unavailable/,
	);
	assert.equal(removed, 'director-failure');
});
test('Generation wire messages accept an empty error on success and reject extra fields', async () => {
	const { validateProtocolV2Envelope } = await import('../src/protocol-v2.mjs');
	const envelope = {
		protocolVersion: 2,
		serverInstanceId: 'world',
		agentId: 'server',
		type: 'director_script_result',
		messageId: 'reply',
		payload: { requestId: 'draft', script: '{"steps":[]}', error: '' },
	};
	assert.equal(validateProtocolV2Envelope(envelope).payload.error, '');
	assert.throws(() =>
		validateProtocolV2Envelope({ ...envelope, payload: { ...envelope.payload, command: 'execute' } }),
	);
});
