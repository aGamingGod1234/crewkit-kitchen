import assert from 'node:assert/strict';
import test from 'node:test';

import { RuntimeErrorReporter } from '../src/runtime-error-reporter.mjs';

test('repeated bridge refusal writes one error and one recovery summary', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	for (let count = 0; count < 100; count += 1) {
		reporter.report(Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:25570'), { code: 'ECONNREFUSED' }));
	}

	assert.equal(writes.length, 1);
	assert.match(writes[0], /ECONNREFUSED/);

	reporter.recovered();

	assert.equal(writes.length, 2);
	assert.match(writes[1], /100 refused connection attempts/);
});

test('recovery summary resets refusal aggregation for the next outage', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const refusal = Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' });

	reporter.report(refusal);
	reporter.report(refusal);
	reporter.recovered();
	reporter.recovered();
	reporter.report(refusal);

	assert.equal(writes.length, 3);
	assert.match(writes[1], /2 refused connection attempts/);
	assert.match(writes[2], /ECONNREFUSED/);
});

test('unexpected errors retain bounded code, message, context, and one redacted stack', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = Object.assign(new Error('bad frame token=provider-secret raw prompt: build a shelter'), {
		code: 'INVALID_FRAME',
		stack: 'Error: bad frame token=provider-secret\n    at bridge (C:\\private\\prompt.js:1:1)\n    at bridge (C:\\private\\prompt.js:2:1)',
		prompt: 'raw prompt should never be logged',
		secret: 'bridge-secret-value',
	});

	reporter.report(error, {
		agentId: 'luna',
		goalRevision: 4,
		lifecycleGeneration: 2,
		activeWorkKind: 'provider',
		prompt: 'raw context prompt must never be logged',
		secret: 'context-secret-value',
	});

	assert.equal(writes.length, 1);
	assert.match(writes[0], /INVALID_FRAME/);
	assert.match(writes[0], /goalRevision=4/);
	assert.match(writes[0], /Error: bad frame/);
	assert.doesNotMatch(writes[0], /provider-secret|bridge-secret-value|raw prompt|prompt\.js|context-secret-value/);
	assert.equal((writes[0].match(/Error:/g) ?? []).length, 1);
});

test('unexpected diagnostics redact authorization credentials in messages and stacks', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = Object.assign(new Error('Authorization: authorization-secret'), {
		code: 'UNAUTHORIZED',
		stack: 'Error: Authorization: authorization-secret',
	});

	reporter.report(error);

	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /authorization-secret/);
});

test('hostile error getters cannot escape report()', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = new Proxy({}, {
		get() { throw new Error('private error getter'); },
	});

	assert.doesNotThrow(() => reporter.report(error));
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /private error getter/);
});

test('hostile context proxies cannot escape report()', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const context = new Proxy({}, {
		getOwnPropertyDescriptor() { throw new Error('private context getter'); },
	});

	assert.doesNotThrow(() => reporter.report(new Error('bad frame'), context));
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /private context getter/);
});

test('huge stack diagnostics use a bounded line extraction and output', () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const hugeStack = Array.from({ length: 100_000 }, (_, index) => `    at frame-${index}`).join('\n');
	const error = Object.assign(new Error('huge diagnostic'), { stack: hugeStack });
	const originalSplit = String.prototype.split;
	let stackSplitLimit;
	String.prototype.split = function patchedSplit(separator, limit) {
		if (separator instanceof RegExp && separator.source === '\\r?\\n') stackSplitLimit = limit;
		return originalSplit.call(this, separator, limit);
	};
	try {
		reporter.report(error);
	} finally {
		String.prototype.split = originalSplit;
	}

	assert.equal(stackSplitLimit, 16);
	assert.ok(Buffer.byteLength(writes[0], 'utf8') <= 5_000);
});
