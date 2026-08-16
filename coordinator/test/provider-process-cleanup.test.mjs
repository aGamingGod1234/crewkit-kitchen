import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AcpStdioTransport } from '../src/acp-transport.mjs';
import { terminateChildProcess } from '../src/child-process-lifecycle.mjs';
import { CodexStdioTransport } from '../src/codex-app-server.mjs';

const FAST_STOP_TIMEOUT_MS = 5;
const REQUEST_TIMEOUT_MS = 5;
const SETTLE_TIMEOUT_MS = 100;

class UncooperativeChild extends EventEmitter {
	constructor() {
		super();
		this.stdout = new EventEmitter();
		this.stderr = new EventEmitter();
		this.stdin = { write() {} };
		this.killed = false;
		this.exitCode = null;
		this.signalCode = null;
		this.signals = [];
	}

	kill(signal = 'SIGTERM') {
		this.killed = true;
		this.signals.push(signal);
		return true;
	}
}

function spawnUncooperativeChild(child) {
	return () => {
		queueMicrotask(() => child.emit('spawn'));
		return child;
	};
}

function spawnAlreadyRunningChild(child) {
	return () => {
		child.pid = 12345;
		child.emit('spawn');
		return child;
	};
}

async function within(promise, timeoutMs = SETTLE_TIMEOUT_MS) {
	let timer;
	try {
		return await Promise.race([
			promise,
			new Promise((_, reject) => {
				timer = setTimeout(() => reject(new Error(`operation did not settle within ${timeoutMs} ms`)), timeoutMs);
			}),
		]);
	} finally {
		clearTimeout(timer);
	}
}

function transportCases() {
	return [
		{
			name: 'Codex',
			create(child) {
				return new CodexStdioTransport(
					{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
		{
			name: 'Gemini ACP',
			create(child) {
				return new AcpStdioTransport(
					{ provider: 'gemini' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
		{
			name: 'Kimi ACP',
			create(child) {
				return new AcpStdioTransport(
					{ provider: 'kimi', reasoningEffort: 'high' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
	];
}

function createWithSpawn(entry, child, spawn) {
	if (entry.name === 'Codex') {
		return new CodexStdioTransport(
			{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
			{ spawn, stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
		);
	}
	return new AcpStdioTransport(
		{ provider: entry.name === 'Gemini ACP' ? 'gemini' : 'kimi', reasoningEffort: 'high' },
		{ spawn, stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
}

for (const entry of transportCases()) {
	test(`${entry.name} starts when the child is already running before listeners attach`, async () => {
		const child = new UncooperativeChild();
		const transport = createWithSpawn(entry, child, spawnAlreadyRunningChild(child));

		await within(transport.start());
		child.pid = undefined;
		await within(transport.stop());
	});

	test(`${entry.name} shutdown force-kills a child that ignores graceful termination`, async () => {
		const child = new UncooperativeChild();
		const transport = entry.create(child);
		await transport.start();

		await within(transport.stop());

		assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
	});

	if (entry.name === 'Codex') continue;
	test(`${entry.name} request timeout tears down its unresponsive child`, async () => {
		const child = new UncooperativeChild();
		const transport = entry.create(child);
		await transport.start();

		await assert.rejects(
			transport.request('unresponsive/request', {}, { timeoutMs: REQUEST_TIMEOUT_MS }),
			(error) => error?.code === 'REQUEST_TIMEOUT',
		);
		await within(new Promise((resolve) => setTimeout(resolve, FAST_STOP_TIMEOUT_MS * 3)));

		assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
		assert.throws(
			() => transport.notify('after/timeout'),
			(error) => error?.code === 'TRANSPORT_NOT_RUNNING',
		);
	});
}

test('Codex request timeout preserves the shared transport and unrelated requests', async () => {
	const child = new UncooperativeChild();
	const requests = [];
	child.stdin.write = (line) => requests.push(JSON.parse(String(line).trim()));
	const transport = new CodexStdioTransport(
		{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
		{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
	const protocolErrors = [];
	transport.on('protocolError', (error) => protocolErrors.push(error.code));
	await transport.start();

	const timedOut = transport.request('slow/request', {}, { timeoutMs: REQUEST_TIMEOUT_MS });
	const survivor = transport.request('fast/request', {}, { timeoutMs: SETTLE_TIMEOUT_MS });
	await assert.rejects(timedOut, (error) => error?.code === 'REQUEST_TIMEOUT');
	child.stdout.emit('data', `${JSON.stringify({ id: requests[0].id, result: { late: true } })}\n`);
	child.stdout.emit('data', `${JSON.stringify({ id: requests[1].id, result: { ok: true } })}\n`);

	assert.deepEqual(await within(survivor), { ok: true });
	assert.deepEqual(protocolErrors, []);
	assert.deepEqual(child.signals, []);
	assert.doesNotThrow(() => transport.notify('still/running'));
	await transport.stop();
});

test('Windows cleanup terminates the complete provider process tree', async () => {
	const child = new UncooperativeChild();
	child.pid = 4_242;
	const calls = [];
	const execute = (file, args, options, callback) => {
		calls.push({ file, args, options });
		callback(null, '', '');
	};

	await terminateChildProcess(child, {
		timeoutMs: FAST_STOP_TIMEOUT_MS,
		platform: 'win32',
		execFile: execute,
	});

	assert.deepEqual(calls.map((call) => call.args), [
		['/PID', '4242', '/T'],
		['/PID', '4242', '/T', '/F'],
	]);
});
