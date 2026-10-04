import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { ProviderTurnRecorder, recordProviderTurn } from '../src/provider-turn-recorder.mjs';
import { createProviderTurnTelemetry } from '../src/provider-turn-telemetry.mjs';
import { createExecutionSettings } from '../src/provider-identity.mjs';
import { ToolResponseSummary } from '../src/tool-response-summary.mjs';

test('preserves bounded requested versus effective execution evidence without filling unknown settings', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'settings', scenarioId: 'native', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const executionSettings = createExecutionSettings({ provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'xhigh', serviceTier: 'priority' }, {
		transport: 'acp', controlProtocol: 'arena_script', effective: { model: 'kimi-for-coding', thinkingMode: 'on' },
		evidence: { model: 'provider_reported', reasoningEffort: 'process_environment', serviceTier: 'not_supported' },
		limitations: ['effort_not_reported_by_provider'],
	});
	executionSettings.unrecognized = { token: 'never-copy' };
	await recorder.record({ provider: 'kimi', model: 'kimi-code/k3', executionSettings });
	await recorder.close();
	delete executionSettings.unrecognized;
	assert.deepEqual(privateRows[0].executionSettings, executionSettings);
	assert.deepEqual(publicRows[0].executionSettings, executionSettings);
	assert.equal(publicRows[0].executionSettings.effective.reasoningEffort, null);
	assert.equal(JSON.stringify(privateRows).includes('never-copy'), false);
});

test('prepares the private provider-turn artifact before appending', async () => {
	const prepared = [];
	const writes = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-private-path', scenarioId: 'scenario-private-path', privatePath: 'private.jsonl',
		preparePrivateArtifact: async (filePath) => { prepared.push(filePath); },
		appendFile: async (_filePath, _text, options) => { writes.push(options); },
	});
	await recorder.record({ provider: 'codex', model: 'm' });
	await recorder.close();
	assert.deepEqual(prepared, ['private.jsonl']);
	assert.deepEqual(writes, [{ encoding: 'utf8', flag: 'a', mode: 0o600 }]);
});

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
		timing: { durationMs: 1234, apiDurationMs: 987 },
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
	assert.deepEqual(privateRow.timing, { durationMs: 1234, apiDurationMs: 987 });
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
	assert.deepEqual(publicRows[0].timing, { durationMs: 1234, apiDurationMs: 987 });
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

test('preserves wall-clock timing when a provider has no native API duration', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-timing', scenarioId: 'scenario-timing', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', timing: { durationMs: 42, apiDurationMs: null } });
	await recorder.close();
	assert.deepEqual(rows[0].timing, { durationMs: 42, apiDurationMs: null });
});

test('preserves agent isolation and provider-native usage without estimating missing categories', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-metrics', scenarioId: 'scenario-metrics', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	await recorder.record({
		agentId: 'agent-8', provider: 'codex', model: 'm', reasoningEffort: 'high', retry: true,
		timing: { durationMs: 80, apiDurationMs: 60, queueWaitMs: 20 },
		tokens: { input: 100, output: 25, reasoning: 10, cached: 40 },
		rateLimited: true, compaction: true,
	});
	await recorder.close();

	assert.equal(rows[0].agentId, 'agent-8');
	assert.deepEqual(rows[0].timing, { durationMs: 80, apiDurationMs: 60, queueWaitMs: 20 });
	assert.deepEqual(rows[0].tokens, { input: 100, output: 25, reasoning: 10, cached: 40, cacheWrite: null });
	assert.equal(rows[0].rateLimited, true);
	assert.equal(rows[0].compaction, true);
});

test('optional complete provider telemetry remains a strict bounded allowlist', () => {
	const telemetry = createProviderTurnTelemetry({
		provider: 'codex', model: 'm', operation: 'decide', durationMs: 50,
		tokens: { input: 12, output: null, reasoning: 3, cached: null, cacheWrite: 4 },
		rateLimited: true, compaction: false, privatePrompt: 'never retain this',
	});
	assert.deepEqual(telemetry.tokens, { input: 12, output: null, reasoning: 3, cached: null, cacheWrite: 4 });
	assert.equal(telemetry.rateLimited, true);
	assert.equal(telemetry.compaction, false);
	assert.equal(JSON.stringify(telemetry).includes('privatePrompt'), false);
});

test('complete latency snapshots include a nearest-rank p99 without changing legacy status snapshots', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 100 });
	for (let value = 1; value <= 100; value += 1) registry.record('action_completion', value);
	assert.deepEqual(registry.performanceSnapshot(), [{
		operation: 'action_completion', count: 100, p50Ms: 50, p95Ms: 95, p99Ms: 99,
	}]);
	assert.deepEqual(registry.snapshot(), [{ operation: 'action_completion', count: 100, p50Ms: 50, p95Ms: 95 }]);
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
	let started;
	const firstWriteStarted = new Promise(resolve => { started = resolve; });
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => {
			writes.push(JSON.parse(text));
			if (writes.length === 1) await new Promise((resolve) => { release = resolve; started(); });
		},
		publicSink: () => { throw new Error('sink down'); },
	});
	const first = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 1, retry: false, input: 'a', output: 'b' });
	const second = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 2, retry: true, input: 'c', output: 'd' });
	await firstWriteStarted;
	assert.equal(writes.length, 1);
	release();
	await Promise.all([first, second, recorder.close()]);
	assert.deepEqual(writes.map((row) => row.attempt), [1, 2]);
});

test('error rows retain only allowlisted structured fields', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl', appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	const error = Object.assign(new Error('ARBITRARY_PROVIDER_SECRET at C:\\Users\\lucas\\secret\\provider.js token=env-value'), { code: 'PROVIDER_UNAVAILABLE', category: 'provider', stack: 'Error\n at C:\\Users\\lucas\\secret\\provider.js' });
	await recorder.record({ provider: 'gemini', model: 'm', reasoningEffort: 'high', goalRevision: 2, attempt: 1, retry: false, input: 'prompt', output: 'partial output', error });
	await recorder.close();
	assert.equal(rows[0].outcome, 'error');
	assert.equal(rows[0].error.code, 'PROVIDER_UNAVAILABLE');
	assert.deepEqual(rows[0].error, { code: 'PROVIDER_UNAVAILABLE', category: 'provider' });
	assert.equal(JSON.stringify(rows[0]).includes('ARBITRARY_PROVIDER_SECRET'), false);
	assert.equal(JSON.stringify(rows[0]).includes('C:\\Users\\lucas\\secret'), false);
	assert.equal(JSON.stringify(rows[0]).includes('env-value'), false);
});

test('hung provider diagnostics never delay control and close remains bounded and idempotent', async () => {
	const recorder = new ProviderTurnRecorder({
		runId: 'run-hung', scenarioId: 'scenario-hung', privatePath: 'private.jsonl',
		appendFile: () => new Promise(() => {}),
		maxPending: 2, operationTimeoutMs: 20, closeTimeoutMs: 30,
	});
	const startedAt = Date.now();
	for (let index = 0; index < 100; index += 1) {
		await recorder.record({ provider: 'codex', model: 'm', attempt: index });
	}
	assert.ok(Date.now() - startedAt < 100, 'recording must only transfer bounded ownership');
	assert.ok(recorder.statusSnapshot().droppedCount > 0);
	const firstClose = recorder.close();
	assert.strictEqual(recorder.close(), firstClose);
	await firstClose;
	assert.ok(Date.now() - startedAt < 200, 'hung diagnostics must not hang shutdown');
});

test('provider recorder survives a rejected sink and writes later records', async () => {
	const attempts = [];
	let calls = 0;
	const recorder = new ProviderTurnRecorder({
		runId: 'run-recovery', scenarioId: 'scenario-recovery', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => {
			calls += 1;
			if (calls === 1) throw new Error('temporary sink failure');
			attempts.push(JSON.parse(text).attempt);
		},
	});
	await recorder.record({ provider: 'codex', model: 'm', attempt: 1 });
	await recorder.record({ provider: 'codex', model: 'm', attempt: 2 });
	await recorder.close();
	assert.deepEqual(attempts, [2]);
	assert.equal(recorder.statusSnapshot().state, 'ready');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 1);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
});

test('provider write failures remain observational but report degraded capture for either sink', async (t) => {
	for (const failedSink of ['private', 'public-sync', 'public-async']) await t.test(failedSink, async () => {
		let privateCalls = 0;
		let publicCalls = 0;
		const recorder = new ProviderTurnRecorder({
			runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
			appendFile: async () => { privateCalls += 1; if (failedSink === 'private') throw new Error('disk full'); },
			publicSink: () => {
				publicCalls += 1;
				if (failedSink === 'public-sync') throw new Error('sink failed');
				if (failedSink === 'public-async') return Promise.reject(new Error('sink failed'));
			},
		});
		await recorder.record({ provider: 'codex', input: 'input' });
		await recorder.close();
		assert.equal(privateCalls, 1);
		assert.equal(publicCalls, 1);
		assert.equal(recorder.statusSnapshot().state, 'degraded');
		assert.equal(recorder.statusSnapshot().failureCode, 'DIAGNOSTIC_SINK_FAILED');
		assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	});
});

test('preparation failures before any record are contained and visible, including synchronous throws', async (t) => {
	for (const synchronous of [true, false]) await t.test(String(synchronous), async () => {
		let publicCalls = 0;
		const recorder = new ProviderTurnRecorder({
			runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
			preparePrivateArtifact: () => {
				if (synchronous) throw new Error('permission failure');
				return Promise.reject(new Error('permission failure'));
			},
			appendFile: async () => assert.fail('unprepared artifacts must not be written'),
			publicSink: () => { publicCalls += 1; },
		});
		await new Promise(setImmediate);
		assert.equal(recorder.statusSnapshot().state, 'degraded');
		await recorder.record({ provider: 'codex' });
		await recorder.close();
		assert.equal(publicCalls, 1);
		assert.equal(recorder.statusSnapshot().state, 'degraded');
		assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	});
});

test('preparing an artifact does not consume the first record admission slot', async () => {
	let writes = 0;
	const recorder = new ProviderTurnRecorder({ runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		maxPending: 1, appendFile: async () => { writes += 1; } });
	await recorder.record({ provider: 'codex' });
	await recorder.close();
	assert.equal(writes, 1);
	assert.equal(recorder.statusSnapshot().incompleteCapture, false);
});

test('native turn evidence keeps explicit unknown usage and bounded identifiers in both records', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row) });
	await recorder.record({ provider: 'codex', input: 'actual prompt', traceId: 'trace-1', sessionGeneration: 2,
		threadId: 'thread-1', turnId: 'turn-1', toolCalls: 2, toolResultBytes: 128, inputBytes: 13, inputCount: 1,
		usage: { scope: 'observed_thread_counter_delta', status: 'baseline_unknown', start: null,
			end: { input: 30, output: 5 }, updates: 1, counterReset: false, secret: 'never-copy' } });
	await recorder.close();
	for (const row of [privateRows[0], publicRows[0]]) {
		assert.equal(row.traceId, 'trace-1');
		assert.equal(row.toolCalls, 2);
		assert.equal(row.usage.status, 'baseline_unknown');
		assert.equal(row.usage.start, null);
		assert.deepEqual(row.usage.end, { input: 30, output: 5, reasoning: null, cached: null, cacheWrite: null });
		assert.equal(JSON.stringify(row).includes('never-copy'), false);
	}
	assert.equal(Object.hasOwn(publicRows[0], 'input'), false);
});

test('malformed records report incomplete capture without rejecting or revisiting hostile accessors', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)) });
	await recorder.record({ tokens: { input: -1 } });
	assert.equal(recorder.statusSnapshot().state, 'degraded');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 1);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	let accessorCalls = 0;
	const hostileError = Object.create(null, { message: { get() { assert.fail('failure reporting must not inspect thrown values'); } } });
	await recorder.record({ get error() { accessorCalls += 1; throw hostileError; } });
	assert.equal(accessorCalls, 1, 'only the existing normalizer reads the supplied accessor');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 2);
	await recorder.record({ provider: 'codex', usage: {} });
	await recorder.close();
	assert.equal(rows.length, 1);
	assert.deepEqual(rows[0].usage, { scope: null, status: null, start: null, end: null, gapBefore: null,
		attributionComplete: null, updates: null, counterReset: false });
	assert.equal(recorder.statusSnapshot().state, 'ready', 'valid unknown usage can recover current health');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 2);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true, 'recovery cannot erase malformed lost records');
});

test('empty usage metadata alone remains valid unknown evidence', async () => {
	const recorder = new ProviderTurnRecorder({ runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl', appendFile: async () => {} });
	await recorder.record({ usage: {} });
	await recorder.close();
	assert.equal(recorder.statusSnapshot().state, 'ready');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 0);
	assert.equal(recorder.statusSnapshot().incompleteCapture, false);
});

test('native usage gap and attribution evidence survives private-file and public-row roundtrips', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'provider-usage-roundtrip-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const privatePath = join(directory, 'usage.jsonl');
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'run', scenarioId: 'scenario', privatePath,
		// This synthetic fixture exercises disk persistence; ACL lifecycle has separate coverage.
		preparePrivateArtifact: async () => {},
		publicSink: (row) => publicRows.push(JSON.parse(JSON.stringify(row))) });
	await recorder.record({ tokens: { input: 30 }, usage: { scope: 'observed_thread_counter_delta', status: 'available',
		start: { input: 150 }, end: { input: 180 }, gapBefore: { input: 30 }, attributionComplete: false } });
	await recorder.record({ usage: { gapBefore: null, attributionComplete: false } });
	await recorder.record({ usage: {} });
	await recorder.record({ usage: { gapBefore: { input: 0 }, attributionComplete: 'false' } });
	await recorder.close();
	const privateRows = (await readFile(privatePath, 'utf8')).trim().split('\n').map((row) => JSON.parse(row));
	assert.equal(privateRows.length, 4);
	assert.equal(publicRows.length, 4);
	for (const rows of [privateRows, publicRows]) {
		assert.equal(rows[0].tokens.input, 30, 'the unassigned gap must not inflate this turn\'s observed tokens');
		assert.deepEqual(rows[0].usage.gapBefore, { input: 30, output: null, reasoning: null, cached: null, cacheWrite: null });
		assert.equal(rows[0].usage.attributionComplete, false);
		assert.equal(rows[1].usage.gapBefore, null);
		assert.equal(rows[1].usage.attributionComplete, false);
		assert.equal(rows[2].usage.gapBefore, null);
		assert.equal(rows[2].usage.attributionComplete, null);
		assert.equal(rows[3].usage.gapBefore.input, 0);
		assert.equal(rows[3].usage.attributionComplete, null, 'a nonboolean value must stay unknown');
	}
	assert.equal(recorder.statusSnapshot().incompleteCapture, false);
});

test('tool response summaries persist to disk and public rows without inventing usage or retaining payloads', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'provider-summary-roundtrip-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const privatePath = join(directory, 'summary.jsonl');
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'summary', scenarioId: 'offline', privatePath,
		preparePrivateArtifact: async () => {}, publicSink: row => publicRows.push(JSON.parse(JSON.stringify(row))) });
	const summary = new ToolResponseSummary();
	const fields = { name: 'observe', kind: 'observe', success: true,
		serializedResponse: JSON.stringify({ success: true, contentItems: [{ text: 'PRIVATE_TOOL_PAYLOAD_\u79d8\u5bc6' }] }) };
	summary.begin(fields).finish(true, 1);
	summary.begin(fields).finish(false, 2);
	summary.begin(fields); // A shutdown snapshot must not claim this was delivered.
	const toolResponses = summary.snapshot();
	toolResponses.raw = 'PRIVATE_TOOL_PAYLOAD';
	toolResponses.receiptAcknowledged = true;
	toolResponses.rows[0].arguments = 'PRIVATE_TOOL_ARGUMENTS';
	const statuses = ['available', 'missing', 'baseline_unknown', 'counter_reset'];
	for (const status of statuses) {
		const usage = { scope: 'observed_thread_counter_delta', status, start: status === 'baseline_unknown' ? null : { input: 20 },
			end: status === 'missing' ? null : { input: status === 'counter_reset' ? 5 : 30 }, updates: status === 'missing' ? 0 : 1,
			counterReset: status === 'counter_reset', attributionComplete: false, gapBefore: { input: 4 } };
		const telemetry = createProviderTurnTelemetry({ provider: 'codex', model: 'fixture', operation: 'native', usage, toolResponses,
			...(status === 'available' ? { tokens: { input: 10 } } : {}) });
		assert.equal(telemetry.toolResponses.receiptAcknowledged, false);
		assert.equal(telemetry.usage.status, status);
		await recorder.record(telemetry);
	}
	// Records take snapshots at admission, not when asynchronous disk writes execute.
	toolResponses.rows[0].accepted = 99;
	await recorder.close();
	const privateRows = (await readFile(privatePath, 'utf8')).trim().split('\n').map(JSON.parse);
	for (const rows of [privateRows, publicRows]) {
		assert.equal(rows.length, statuses.length);
		for (const [index, row] of rows.entries()) {
			assert.equal(row.usage.status, statuses[index]);
			assert.equal(row.usage.attributionComplete, false);
			assert.equal(row.usage.gapBefore.input, 4);
			assert.equal(row.toolResponses.receiptAcknowledged, false);
			const measured = row.toolResponses.rows[0];
			assert.deepEqual([measured.accepted, measured.failed, measured.pending], [1, 1, 1]);
			assert.equal(measured.attemptedBytes, measured.acceptedBytes + measured.failedBytes + measured.pendingBytes);
			assert.equal(measured.executionSamples, 0);
			if (index === 0) assert.deepEqual(row.tokens, { input: 10, output: null, reasoning: null, cached: null, cacheWrite: null });
			else assert.equal(Object.hasOwn(row, 'tokens'), false);
			assert.doesNotMatch(JSON.stringify(row), /PRIVATE_TOOL|arguments|contentItems|billed|dollars/);
		}
		assert.equal(rows[1].usage.end, null);
		assert.equal(rows[2].usage.start, null);
		assert.equal(rows[3].usage.counterReset, true);
	}
	assert.equal(recorder.statusSnapshot().incompleteCapture, false);
});

test('malformed summary persistence reports capture failure and recovers without copying errors', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'summary', scenarioId: 'offline', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)) });
	const summary = new ToolResponseSummary();
	summary.begin({ name: 'observe', kind: 'observe', serializedResponse: '{}' }).finish(false);
	const malformed = summary.snapshot();
	malformed.rows[0].accepted = 1;
	assert.throws(() => createProviderTurnTelemetry({ provider: 'codex', model: 'm', operation: 'native', toolResponses: malformed }), /inconsistent/);
	await recorder.record({ toolResponses: malformed });
	await recorder.record({ toolResponses: { get version() { throw new Error('PRIVATE_ERROR'); } } });
	await recorder.record({ toolResponses: summary.snapshot() });
	await recorder.close();
	assert.equal(rows.length, 1);
	assert.equal(rows[0].toolResponses.rows[0].failed, 1);
	assert.equal(recorder.statusSnapshot().failedOperationCount, 2);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	assert.doesNotMatch(JSON.stringify(rows), /PRIVATE_ERROR/);
});

test('optional summary preserves absent evidence and unknown token categories', () => {
	const input = { provider: 'codex', model: 'fixture', operation: 'native' };
	const legacy = createProviderTurnTelemetry(input);
	assert.equal(Object.hasOwn(legacy, 'toolResponses'), false);
	assert.equal(Object.hasOwn(legacy, 'tokens'), false);
	const summary = new ToolResponseSummary();
	summary.captureFailure();
	const telemetry = createProviderTurnTelemetry({ ...input, toolResponses: summary.snapshot(), tokens: { input: null } });
	assert.deepEqual(telemetry.toolResponses.rows, []);
	assert.equal(telemetry.toolResponses.captureFailures, 1);
	assert.deepEqual(telemetry.tokens, { input: null, output: null, reasoning: null, cached: null, cacheWrite: null });
});

test('observational recorder wrapper contains synchronous and asynchronous failures', async () => {
	const error = new Error('PRIVATE_RECORDER_FAILURE');
	for (const recorder of [null, { record() { throw error; } }, { record() { return Promise.reject(error); } },
		{ record() { return { then(_resolve, reject) { reject(error); } }; } }]) {
		assert.doesNotThrow(() => recordProviderTurn(recorder, {}));
	}
	await new Promise(setImmediate); // An unhandled rejection would fail this test.
});

function readinessGate() {
	let resolve, reject;
	const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
	return { promise, resolve, reject };
}

function recorderClock() {
	let now = 0;
	const timers = new Set();
	const flush = () => new Promise(setImmediate);
	return {
		options: {
			dispatch: queueMicrotask,
			schedule(callback, delay) { const timer = { callback, at: now + delay }; timers.add(timer); return timer; },
			cancel(timer) { timers.delete(timer); },
		},
		flush,
		async advance(duration) {
			const end = now + duration;
			await flush();
			for (;;) {
				const next = [...timers].sort((a, b) => a.at - b.at)[0];
				if (!next || next.at > end) break;
				now = next.at;
				timers.delete(next);
				next.callback();
				await flush();
			}
			now = end;
		},
	};
}

test('readiness: cold admission excludes setup from row deadlines and drains in order', async (t) => {
	for (const closeBeforeReady of [false, true]) await t.test(`closeBeforeReady=${closeBeforeReady}`, async () => {
		const setup = readinessGate();
		const clock = recorderClock();
		const privateRows = [], publicRows = [];
		const recorder = new ProviderTurnRecorder({ runId: 'cold', scenarioId: 'offline', privatePath: 'injected.jsonl',
			preparePrivateArtifact: () => setup.promise, ...clock.options,
			appendFile: async (_path, text) => privateRows.push(JSON.parse(text).attempt),
			publicSink: row => publicRows.push(row.attempt) });
		for (let attempt = 1; attempt <= 4; attempt++) await recorder.record({ attempt });
		assert.deepEqual(privateRows, [], 'record returns before setup or writes');
		let closed = false;
		const closing = closeBeforeReady ? recorder.close().then(() => { closed = true; }) : null;
		await clock.advance(750); // Three normal row deadlines, without wall-clock waits.
		assert.equal(recorder.statusSnapshot().incompleteCapture, false);
		assert.equal(recorder.statusSnapshot().droppedCount, 0);
		assert.equal(closed, false);
		setup.resolve();
		await clock.flush();
		await (closing ?? recorder.close());
		assert.deepEqual(privateRows, [1, 2, 3, 4]);
		assert.deepEqual(publicRows, [1, 2, 3, 4]);
		assert.equal(recorder.statusSnapshot().incompleteCapture, false);
	});
});

test('readiness: unresolved setup has bounded close and cannot write abandoned rows later', async (t) => {
	for (const rows of [0, 4]) for (const lateFailure of [false, true]) await t.test(`${rows} rows, lateFailure=${lateFailure}`, async () => {
		const setup = readinessGate(), clock = recorderClock();
		const writes = [];
		const recorder = new ProviderTurnRecorder({ runId: 'hung', scenarioId: 'offline', privatePath: 'injected.jsonl',
			preparePrivateArtifact: () => setup.promise, ...clock.options,
			appendFile: async () => writes.push('private'), publicSink: () => writes.push('public') });
		for (let attempt = 0; attempt < rows; attempt++) await recorder.record({ attempt });
		let closed = false;
		const closing = recorder.close();
		assert.strictEqual(recorder.close(), closing);
		closing.then(() => { closed = true; });
		await clock.advance(999);
		assert.equal(closed, false);
		await clock.advance(1);
		await closing;
		assert.equal(recorder.statusSnapshot().failureCode, 'DIAGNOSTIC_CLOSE_TIMEOUT');
		assert.equal(recorder.statusSnapshot().incompleteCapture, true);
		assert.equal(recorder.statusSnapshot().droppedCount, rows);
		if (lateFailure) setup.reject(new Error('permission denied')); else setup.resolve();
		await recorder.record({ attempt: 99 });
		await clock.flush();
		assert.deepEqual(writes, []);
		assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	});
});

test('readiness: cold queue capacity stays bounded without consuming a setup slot', async () => {
	const setup = readinessGate(), clock = recorderClock();
	const writes = [];
	const recorder = new ProviderTurnRecorder({ runId: 'capacity', scenarioId: 'offline', privatePath: 'injected.jsonl',
		maxPending: 3, preparePrivateArtifact: () => setup.promise, ...clock.options,
		appendFile: async (_path, text) => writes.push(JSON.parse(text).attempt) });
	for (let attempt = 0; attempt < 10; attempt++) await recorder.record({ attempt });
	await clock.advance(750);
	assert.equal(recorder.statusSnapshot().droppedCount, 7);
	assert.equal(recorder.statusSnapshot().failedOperationCount, 0);
	setup.resolve();
	await clock.flush();
	await recorder.close();
	assert.deepEqual(writes, [0, 1, 2]);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
});

test('readiness: rejected preparation still delivers public rows and records missing private evidence', async () => {
	const setup = readinessGate(), clock = recorderClock();
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({ runId: 'failure', scenarioId: 'offline', privatePath: 'injected.jsonl',
		preparePrivateArtifact: () => setup.promise, ...clock.options,
		appendFile: async () => assert.fail('must not bypass failed permissions'),
		publicSink: row => publicRows.push(row.attempt) });
	for (let attempt = 0; attempt < 4; attempt++) await recorder.record({ attempt });
	await clock.advance(750);
	setup.reject(new Error('permission failure'));
	await clock.flush();
	await recorder.close();
	assert.deepEqual(publicRows, [0, 1, 2, 3]);
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
	assert.equal(recorder.statusSnapshot().failureCode, 'DIAGNOSTIC_SINK_FAILED');
	assert.equal(recorder.statusSnapshot().droppedCount, 0);
});

test('readiness: real sink execution still times out and rotation rejects concurrent detached writes truthfully', async () => {
	const setup = readinessGate(), write = readinessGate(), clock = recorderClock();
	const privateRows = [], publicRows = [];
	let appendCalls = 0;
	const recorder = new ProviderTurnRecorder({ runId: 'slow', scenarioId: 'offline', privatePath: 'injected.jsonl',
		preparePrivateArtifact: () => setup.promise, ...clock.options,
		appendFile: async (_path, text) => { appendCalls++; await write.promise; privateRows.push(JSON.parse(text).attempt); },
		publicSink: row => publicRows.push(row.attempt) });
	for (let attempt = 1; attempt <= 4; attempt++) await recorder.record({ attempt });
	await clock.advance(750);
	setup.resolve();
	await clock.flush();
	await clock.advance(249);
	assert.equal(recorder.statusSnapshot().incompleteCapture, false);
	assert.equal(appendCalls, 1);
	await clock.advance(1);
	assert.deepEqual(publicRows, [1, 2, 3, 4]);
	assert.deepEqual(privateRows, []);
	assert.equal(appendCalls, 1, 'rotating sink retains exclusive ownership of detached I/O');
	assert.equal(recorder.statusSnapshot().failedOperationCount, 4);
	write.resolve();
	await clock.flush();
	await recorder.record({ attempt: 5 });
	await clock.flush();
	await recorder.close();
	assert.deepEqual(privateRows, [1, 5]);
	assert.equal(recorder.statusSnapshot().state, 'ready');
	assert.equal(recorder.statusSnapshot().incompleteCapture, true);
});
