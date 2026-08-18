import assert from 'node:assert/strict';
import test from 'node:test';

import { ProviderTurnRecorder } from '../src/provider-turn-recorder.mjs';

test('records bounded redacted private turns and hash/excerpt-only public rows', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-1', scenarioId: 'scenario-1', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)),
		publicSink: (row) => publicRows.push(row), now: () => 1234,
	});
	await recorder.record({
		provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', goalRevision: 4, attempt: 2, retry: true,
		input: 'prompt authorization: Bearer abc123 token=secret-token password=hunter2 SECRET_SHAPED=supersecret ' + '🙂'.repeat(100_000),
		output: '{"directive":"finish"}' + '漢'.repeat(40_000),
	});
	await recorder.close();

	assert.equal(privateRows.length, 1);
	assert.equal(publicRows.length, 1);
	const privateRow = privateRows[0];
	assert.equal(privateRow.runId, 'run-1');
	assert.equal(privateRow.scenarioId, 'scenario-1');
	assert.equal(privateRow.provider, 'codex');
	assert.equal(privateRow.model, 'gpt-5.6-sol');
	assert.equal(privateRow.reasoningEffort, 'high');
	assert.equal(privateRow.goalRevision, 4);
	assert.equal(privateRow.attempt, 2);
	assert.equal(privateRow.retry, true);
	assert.equal(privateRow.outcome, 'success');
	assert.equal(privateRow.timestamp, 1234);
	assert.match(privateRow.output, /^\{"directive":"finish"\}/);
	assert.ok(Buffer.byteLength(JSON.stringify(privateRow), 'utf8') <= 262_144);
	assert.ok(Buffer.byteLength(privateRow.input, 'utf8') <= 65_536);
	assert.ok(Buffer.byteLength(privateRow.output, 'utf8') <= 65_536);
	assert.equal(privateRow.input.includes('abc123'), false);
	assert.equal(privateRow.input.includes('hunter2'), false);
	assert.equal(privateRow.input.includes('supersecret'), false);
	assert.equal(typeof publicRows[0].inputHash, 'string');
	assert.equal(typeof publicRows[0].outputHash, 'string');
	assert.equal(typeof publicRows[0].inputExcerpt, 'string');
	assert.equal(typeof publicRows[0].outputExcerpt, 'string');
	assert.ok(Buffer.byteLength(publicRows[0].inputExcerpt, 'utf8') <= 512);
	assert.ok(Buffer.byteLength(publicRows[0].outputExcerpt, 'utf8') <= 512);
	assert.equal(Object.hasOwn(publicRows[0], 'input'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('secret-token'), false);
});

test('redacts quoted JSON credential keys and values in both private and public records', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-json', scenarioId: 'scenario-json', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const quotedSecrets = '{"token":"TOKENSECRET","password":"PASSSECRET","client_secret":"CLIENTSECRET","authorization":"Bearer BEARERSECRET"}';
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'high', goalRevision: 1, attempt: 1, retry: false, input: quotedSecrets, output: quotedSecrets });
	await recorder.close();

	assert.equal(privateRows.length, 1);
	assert.equal(JSON.stringify(privateRows[0]).includes('TOKENSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('PASSSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('CLIENTSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('BEARERSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('TOKENSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('PASSSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('CLIENTSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('BEARERSECRET'), false);
});

test('redacts escaped and delimiter-rich quoted JSON credential values', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-json-rich', scenarioId: 'scenario-json-rich', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const quotedSecrets = [
		'{"token":"TOKEN SECRET"}',
		'{"token":"TOKEN\\nSECRET"}',
		'{"password": "my password"}',
		'{"client_secret":"CLIENT,SECRET"}',
		'{"authorization":"secret } value"}',
	].join(' ');
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'high', goalRevision: 1, attempt: 1, retry: false, input: quotedSecrets, output: quotedSecrets });
	await recorder.close();

	const privateText = JSON.stringify(privateRows[0]);
	const publicText = JSON.stringify(publicRows[0]);
	for (const secret of ['TOKEN SECRET', 'TOKEN\\nSECRET', 'my password', 'CLIENT,SECRET', 'secret } value']) {
		assert.equal(privateText.includes(secret), false, `private record leaked ${secret}`);
		assert.equal(publicText.includes(secret), false, `public record leaked ${secret}`);
	}
});

test('serializes records and swallows public sink failures without blocking close', async () => {
	const writes = [];
	let release;
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => {
			writes.push(JSON.parse(text));
			if (writes.length === 1) await new Promise((resolve) => { release = resolve; });
		},
		publicSink: () => { throw new Error('sink down'); },
	});
	const first = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 1, retry: false, input: 'a', output: 'b' });
	const second = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 2, retry: true, input: 'c', output: 'd' });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(writes.length, 1);
	release();
	await Promise.all([first, second, recorder.close()]);
	assert.deepEqual(writes.map((row) => row.attempt), [1, 2]);
});

test('error rows contain typed bounded provider errors without stack, paths, or environment values', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl', appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	const error = Object.assign(new Error('failed at C:\\Users\\lucas\\secret\\provider.js token=env-value'), { code: 'PROVIDER_UNAVAILABLE', stack: 'Error\n at C:\\Users\\lucas\\secret\\provider.js' });
	await recorder.record({ provider: 'gemini', model: 'm', reasoningEffort: 'high', goalRevision: 2, attempt: 1, retry: false, input: 'prompt', output: 'partial output', error });
	await recorder.close();
	assert.equal(rows[0].outcome, 'error');
	assert.equal(rows[0].error.code, 'PROVIDER_UNAVAILABLE');
	assert.equal(typeof rows[0].error.message, 'string');
	assert.equal(Object.hasOwn(rows[0].error, 'stack'), false);
	assert.equal(JSON.stringify(rows[0]).includes('C:\\Users\\lucas\\secret'), false);
	assert.equal(JSON.stringify(rows[0]).includes('env-value'), false);
});
