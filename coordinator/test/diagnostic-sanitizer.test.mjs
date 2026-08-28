import assert from 'node:assert/strict';
import test from 'node:test';

import { sanitizeDiagnosticText, sanitizeDiagnosticValue } from '../src/diagnostic-sanitizer.mjs';

test('shared diagnostic sanitizer redacts credentials and absolute paths', () => {
	const source = [
		'{"api_key":"JSON SECRET", "password":"PASS SECRET"}',
		'Authorization: Bearer bearer-secret',
		'C:\\Users\\lucas\\private\\token.json',
		'/home/lucas/private/token.json',
		'\\\\server\\share\\private\\token.json',
	].join(' ');
	const result = sanitizeDiagnosticText(source, { maxBytes: 2_048 });
	for (const secret of ['JSON SECRET', 'PASS SECRET', 'bearer-secret', 'lucas', 'server', 'share']) {
		assert.equal(result.includes(secret), false, `leaked ${secret}`);
	}
});

test('shared diagnostic sanitizer contains proxies and accessors and bounds strings', () => {
	const value = Object.create(null, {
		password: { enumerable: true, value: 'secret' },
		message: { enumerable: true, value: 'x'.repeat(20_000) },
		hostile: { enumerable: true, get() { throw new Error('must not run'); } },
	});
	assert.doesNotThrow(() => sanitizeDiagnosticValue(value));
	const safe = sanitizeDiagnosticValue(value, { maxStringBytes: 64 });
	assert.equal(safe.password, '[REDACTED]');
	assert.ok(Buffer.byteLength(safe.message, 'utf8') <= 64);
	assert.equal(Object.hasOwn(safe, 'hostile'), false);
	assert.equal(sanitizeDiagnosticValue(new Proxy({}, { ownKeys() { throw new Error('hostile proxy'); } })), '[UNSAFE_OBJECT]');
});
