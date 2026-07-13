import assert from 'node:assert/strict';
import test from 'node:test';

import { JsonlDecoder, encodeJsonLine } from '../src/jsonl.mjs';

test('frames fragmented JSONL objects', () => {
	const decoder = new JsonlDecoder({ maxBytes: 65_536 });
	assert.deepEqual(decoder.push('{"a":1}\n{"b"'), [{ a: 1 }]);
	assert.deepEqual(decoder.push(':2}\r\n'), [{ b: 2 }]);
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

test('encodes exactly one bounded JSON line', () => {
	assert.equal(encodeJsonLine({ ok: true }), '{"ok":true}\n');
	assert.throws(() => encodeJsonLine(null), /must be an object/);
	assert.throws(() => encodeJsonLine({ text: 'x'.repeat(100) }, { maxBytes: 16 }), /exceeds 16/);
});
