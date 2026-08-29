import assert from 'node:assert/strict';
import test from 'node:test';

import { JsonlDecoder, encodeJsonLine } from '../src/jsonl.mjs';

test('frames fragmented JSONL objects', () => {
	const decoder = new JsonlDecoder({ maxBytes: 65_536 });
	assert.deepEqual(decoder.push('{"a":1}\n{"b"'), [{ a: 1 }]);
	assert.deepEqual(decoder.push(':2}\r\n'), [{ b: 2 }]);
});

test('does not concatenate an accumulated partial tail for every fragment', () => {
	const decoder = new JsonlDecoder();
	const frame = `{"payload":"${'x'.repeat(4096)}"}\n`;
	const originalConcat = Buffer.concat;
	let concatCalls = 0;
	Buffer.concat = (...args) => {
		concatCalls += 1;
		return originalConcat(...args);
	};
	try {
		for (let index = 0; index < frame.length; index += 1) {
			const messages = decoder.push(frame.slice(index, index + 1));
			if (index < frame.length - 1) assert.deepEqual(messages, []);
			else assert.deepEqual(messages, [{ payload: 'x'.repeat(4096) }]);
		}
	} finally {
		Buffer.concat = originalConcat;
	}
	assert.equal(concatCalls, 0);
});

test('preserves frames split at every UTF-8 byte boundary', () => {
	const encoded = Buffer.from('{"text":"héllo 💥"}\n', 'utf8');
	for (let split = 1; split < encoded.length; split += 1) {
		const decoder = new JsonlDecoder();
		assert.deepEqual(decoder.push(encoded.subarray(0, split)), [], `split ${split}`);
		assert.deepEqual(decoder.push(encoded.subarray(split)), [{ text: 'héllo 💥' }], `split ${split}`);
	}
});

test('recovers after a malformed fragmented frame without retaining caller bytes', () => {
	const decoder = new JsonlDecoder();
	const first = Buffer.from('{"bad":1,}\n{"next"', 'utf8');
	const malformedFrameBytes = Buffer.byteLength('{"bad":1,}\n', 'utf8');
	assert.throws(() => decoder.push(first), (error) => error.code === 'MALFORMED_JSON');
	first.fill(0x20, malformedFrameBytes);
	assert.deepEqual(decoder.push(Buffer.from(':true}\n', 'utf8')), [{ next: true }]);
});

test('counts UTF-8 bytes and rejects an oversized frame before a newline', () => {
	const decoder = new JsonlDecoder({ maxBytes: 8 });
	assert.throws(() => decoder.push('"💥💥"'), /exceeds 8 UTF-8 bytes/);
});

test('rejects blank, malformed, scalar, and incomplete frames', () => {
	assert.throws(() => new JsonlDecoder().push('\n'), /must not be blank/);
	assert.throws(() => new JsonlDecoder().push('{bad}\n'), /Malformed JSONL/);
	assert.throws(() => new JsonlDecoder().push('42\n'), /must be an object/);
	const decoder = new JsonlDecoder();
	decoder.push('{"open":true}');
	assert.throws(() => decoder.finish(), /Incomplete JSONL frame/);
});

test('rejects duplicate JSON names before materialising the protocol object', () => {
	const decoder = new JsonlDecoder();
	assert.throws(() => decoder.push('{"agentId":"a","agentId":"b"}\n'), (error) => error.code === 'MALFORMED_JSON' && /Duplicate/.test(error.message));
	assert.throws(() => decoder.push('{"payload":{"actionId":"a","actionId":"b"}}\n'), (error) => error.code === 'MALFORMED_JSON' && /Duplicate/.test(error.message));
});

test('encodes exactly one bounded JSON line', () => {
	assert.equal(encodeJsonLine({ ok: true }), '{"ok":true}\n');
	assert.throws(() => encodeJsonLine(null), /must be an object/);
	assert.throws(() => encodeJsonLine({ text: 'x'.repeat(100) }, { maxBytes: 16 }), /exceeds 16/);
});
