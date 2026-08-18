import assert from 'node:assert/strict';
import test from 'node:test';

import { observationHash, redact, TraceWriter } from '../src/trace-writer.mjs';

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

test('does not invoke accessors or proxy traps and emits canonical bounded records', async () => {
	let getterCalled = false;
	const accessor = {};
	Object.defineProperty(accessor, 'secret', { enumerable: true, get() { getterCalled = true; throw new Error('must not run'); } });
	const proxy = new Proxy({ value: 'hidden' }, {
		ownKeys() { throw new Error('must not run'); },
		getOwnPropertyDescriptor() { throw new Error('must not run'); },
	});
	const custom = Object.create({ inherited: 'must not copy' });
	custom.own = 'kept';
	const input = { accessor, proxy, custom };
	input.circular = input;
	const result = redact(input);
	assert.equal(getterCalled, false);
	assert.equal(Object.getPrototypeOf(result), null);
	assert.equal(Object.getPrototypeOf(result.custom), null);
	assert.equal(result.accessor.secret, undefined);
	assert.equal(result.proxy, '[UNSAFE_OBJECT]');
	assert.equal(result.custom.inherited, undefined);
	assert.equal(result.circular, '[CIRCULAR]');

	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', { mkdir: async () => {}, appendFile: async () => {} });
	await assert.doesNotReject(writer.write('safe', { accessor, proxy }));
	await writer.close();
});

test('redacts textual credential patterns from public and private strings', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		diagnosticFilePath: 'C:\\runtime\\private.jsonl',
		mkdir: async () => {},
		appendFile: async (filePath, value) => rows.push({ filePath, value }),
	});
	const source = 'program.chat({message: "api_key=abc token=def secret=ghi credential=jkl oauth=mno Bearer qrs"}); await player.wait(1);';
	await writer.write('public', { message: 'api_key=abc token=def secret=ghi oauth=mno Bearer qrs', source });
	await writer.writeDiagnostic('private', { source });
	await writer.close();
	assert.ok(rows.every(({ value }) => !/(api_key|token|secret|credential|oauth)=?(abc|def|ghi|jkl|mno)|Bearer qrs/i.test(value)));
	const privateRow = JSON.parse(rows.find(({ filePath }) => filePath.endsWith('private.jsonl')).value);
	assert.match(privateRow.source, /program\.chat/);
	assert.doesNotMatch(privateRow.source, /api_key=abc|token=def|secret=ghi|oauth=mno|Bearer qrs/i);
});

test('caps serialized rows including huge keys and unsupported non-string values', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => rows.push(value),
	});
	const hugeKey = 'k'.repeat(400_000);
	await assert.doesNotReject(writer.write('bounded', { [hugeKey]: 42, count: 7, bigint: 1n, symbol: Symbol('private') }));
	await writer.close();
	assert.ok(Buffer.byteLength(rows[0], 'utf8') <= 262_145);
	const row = JSON.parse(rows[0]);
	assert.equal(row.event, 'bounded');
	assert.equal(row.count, 7);
	assert.doesNotMatch(rows[0], /k{100}/);
});

test('reserves truncation-marker bytes at the serialized line boundary', async () => {
	const lines = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => lines.push(value),
	});
	const fields = Object.fromEntries(Array.from({ length: 64 }, (_, index) => [
		`${String(index).padStart(2, '0')}-${'k'.repeat(4_200)}`, `🙂${index}`,
	]));
	await writer.write('near-boundary', fields);
	await writer.close();
	assert.ok(Buffer.byteLength(lines[0], 'utf8') >= 250_000, 'fixture must exercise the near-boundary path');
	assert.ok(Buffer.byteLength(lines[0], 'utf8') <= 262_144, 'JSONL line including newline must fit the trace cap');
	const row = JSON.parse(lines[0]);
	assert.equal(row.event, 'near-boundary');
	assert.equal(row.truncated, '[BOUNDED]');
	assert.doesNotMatch(lines[0], /\uD800|\uDFFF/);
});
