import assert from 'node:assert/strict';
import test from 'node:test';

import { observationHash, TraceWriter } from '../src/trace-writer.mjs';

test('appends redacted JSONL rows in order', async () => {
	const chunks = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => { chunks.push(value); },
	});
	await Promise.all([
		writer.write({ event: 'one', authorization: 'Bearer private', nested: { apiKey: 'secret' } }),
		writer.write({ event: 'two', message: 'Bearer abc.def' }),
	]);
	await writer.close();
	assert.equal(JSON.parse(chunks[0]).authorization, '[REDACTED]');
	assert.equal(JSON.parse(chunks[0]).nested.apiKey, '[REDACTED]');
	assert.equal(JSON.parse(chunks[1]).message, 'Bearer [REDACTED]');
});

test('hashes identical observations deterministically', () => {
	assert.equal(observationHash({ ready: true, x: 1 }), observationHash({ ready: true, x: 1 }));
	assert.notEqual(observationHash({ ready: true, x: 1 }), observationHash({ ready: true, x: 2 }));
});

test('writes typed program events without leaking tokens or unbounded source', async () => {
	const chunks = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => { chunks.push(value); },
	});
	await writer.write('program_step', {
		programId: 'program-1-1',
		sourceStepId: 'step-1-9',
		token: 'secret-value',
		source: 'program.onUnhandledAttention("continue_and_notify");',
	});
	await writer.close();
	const line = chunks.join('');
	assert.match(line, /program_step/);
	assert.match(line, /program-1-1/);
	assert.match(line, /sourceHash/);
	assert.doesNotMatch(line, /secret-value/);
	assert.doesNotMatch(line, /onUnhandledAttention/);
});

test('keeps private source bounded while bounding other diagnostics more tightly', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		diagnosticFilePath: 'C:\\runtime\\private.jsonl',
		mkdir: async () => {},
		appendFile: async (filePath, value) => { rows.push({ filePath, row: JSON.parse(value) }); },
	});
	const source = 's'.repeat(70_000);
	await writer.writeDiagnostic('program_compiled', { source, message: 'm'.repeat(3_000) });
	await writer.close();
	const row = rows[0].row;
	assert.equal(row.source.length, 65_536);
	assert.equal(row.message.length, 2_048);
});
