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
