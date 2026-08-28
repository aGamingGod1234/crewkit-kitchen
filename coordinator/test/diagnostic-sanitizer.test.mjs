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

test('credential grammar redacts quoted assignments and authorization schemes', () => {
	const cases = [
		['api_key = "super secret"', 'super secret'],
		["'client_secret' = 'client secret value'", 'client secret value'],
		['password: "space rich password"', 'space rich password'],
		['Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==', 'QWxhZGRpbjpvcGVuIHNlc2FtZQ=='],
		['authorization = Bearer sk-live_realistic.credential-value', 'sk-live_realistic.credential-value'],
		['github_token=ghp_0123456789abcdefghijklmnopqrstuvwxyz', 'ghp_0123456789abcdefghijklmnopqrstuvwxyz'],
		['raw prompt: mine the nearby tree\noperation=decide', 'mine the nearby tree'],
	];
	for (const [source, secret] of cases) {
		const sanitized = sanitizeDiagnosticText(source);
		assert.equal(sanitized.includes(secret), false, `leaked credential from ${source}`);
		assert.match(sanitized, /\[REDACTED\]/);
	}
});

test('sanitizer preserves URLs and operational token metrics while redacting only absolute filesystem paths', () => {
	const preserved = [
		'https://example.com/v1/token?count=2',
		'http://127.0.0.1:8766/v1/tts',
		'inputTokens=123 output_token_count=45 tokenCount:8 cached_tokens=9',
		'token_bucket=4 token_latency_ms=12',
		'src/token/worker.js',
	].join(' ');
	assert.equal(sanitizeDiagnosticText(preserved), preserved);
	for (const absolutePath of [
		'C:\\Users\\lucas\\Arena Agents\\secret.json',
		'/var/lib/arena agents/secret.json',
		'\\\\server\\share\\Arena Agents\\secret.json',
	]) {
		const sanitized = sanitizeDiagnosticText(`failure at "${absolutePath}"`);
		assert.equal(sanitized.includes(absolutePath), false);
		assert.match(sanitized, /\[location redacted\]/);
	}
});

test('structured token metrics remain visible but actual token credentials are redacted', () => {
	const sanitized = sanitizeDiagnosticValue({
		inputTokens: 123,
		output_token_count: 45,
		tokenCount: 8,
		tokens: { input: 123, output: 45, reasoning: 8, cached: 3, cacheWrite: null },
		access_token: 'secret-access-token',
		github_token: 'secret-github-token',
	});
	assert.equal(sanitized.inputTokens, 123);
	assert.equal(sanitized.output_token_count, 45);
	assert.equal(sanitized.tokenCount, 8);
	assert.deepEqual({ ...sanitized.tokens }, { input: 123, output: 45, reasoning: 8, cached: 3, cacheWrite: null });
	assert.equal(sanitized.access_token, '[REDACTED]');
	assert.equal(sanitized.github_token, '[REDACTED]');
	assert.equal(sanitizeDiagnosticValue({ tokens: 'credential-shaped-secret' }).tokens, '[REDACTED]');
});

test('launcher and account credential aliases redact in text and structured diagnostics', () => {
	const aliases = ['launcherAccount', 'launcher_account', 'launcher-account', 'launcheraccount', 'accountData', 'account_data', 'account-data', 'accountdata'];
	for (const [index, alias] of aliases.entries()) {
		const secret = `private-value-${index}`;
		const text = sanitizeDiagnosticText(`${alias} = "${secret}"`);
		assert.equal(text.includes(secret), false, `${alias} text assignment leaked`);
		assert.match(text, /\[REDACTED\]/);
		const structured = sanitizeDiagnosticValue({ [alias]: secret });
		assert.equal(structured[alias], '[REDACTED]', `${alias} structured field leaked`);
	}
});

test('file absolute URIs redact without changing network URLs or relative paths', () => {
	for (const location of [
		'file:///C:/Users/lucas/Arena%20Agents/secret.json',
		'file:///var/lib/arena%20agents/secret.json',
		'file://server/share/Arena%20Agents/secret.json',
	]) {
		const sanitized = sanitizeDiagnosticText(`failure at ${location}`);
		assert.equal(sanitized.includes(location), false, `${location} leaked`);
		assert.match(sanitized, /\[location redacted\]/);
	}
	const safe = 'https://example.com/file:///docs http://127.0.0.1:8766/v1/tts relative/file.txt inputTokens=4';
	assert.equal(sanitizeDiagnosticText(safe), safe);
});
