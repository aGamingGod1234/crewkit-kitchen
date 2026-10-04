import assert from 'node:assert/strict';
import test from 'node:test';

import { BestEffortDiagnosticQueue } from '../src/best-effort-diagnostic-queue.mjs';
import { RotatingJsonlSink } from '../src/rotating-jsonl-sink.mjs';

test('a detached timed-out append retains rotation ownership until its actual I/O settles', async () => {
	const files = new Map([
		['audit.jsonl', '12345678'], ['audit.jsonl.1', 'older-1'],
		['audit.jsonl.2', 'older-2'], ['audit.jsonl.3', 'oldest'],
	]);
	let releaseStat;
	let firstStat = true;
	let statCalls = 0;
	const sink = new RotatingJsonlSink('audit.jsonl', {
		maxFileBytes: 8, now: () => 1,
		stat: async (file) => {
			statCalls += 1;
			const metadata = { size: Buffer.byteLength(files.get(file)), mtimeMs: 1 };
			if (firstStat) { firstStat = false; await new Promise((resolve) => { releaseStat = resolve; }); }
			return metadata;
		},
		unlink: async (file) => { files.delete(file); },
		rename: async (from, to) => { files.set(to, files.get(from)); files.delete(from); },
		appendFile: async (file, encoded) => { files.set(file, (files.get(file) ?? '') + encoded); },
	});
	const timers = [];
	const queue = new BestEffortDiagnosticQueue({
		dispatch: (callback) => callback(),
		schedule: (callback) => { const timer = { callback, active: true }; timers.push(timer); return timer; },
		cancel: (timer) => { timer.active = false; },
	});
	let firstAppend;
	queue.submit(() => { firstAppend = sink.append('A\n'); return firstAppend; });
	queue.submit(() => sink.append('B\n'));
	timers.find((timer) => timer.active).callback();
	await new Promise(setImmediate);
	assert.equal(statCalls, 1, 'a detached append must exclude later metadata/rotation work');
	assert.equal(files.get('audit.jsonl.3'), 'oldest');
	assert.equal(queue.statusSnapshot().state, 'degraded');
	releaseStat();
	await firstAppend;
	await new Promise(setImmediate);
	queue.submit(() => sink.append('C\n'));
	await queue.close();
	assert.deepEqual([...files], [
		['audit.jsonl.3', 'older-2'], ['audit.jsonl.2', 'older-1'],
		['audit.jsonl.1', '12345678'], ['audit.jsonl', 'A\nC\n'],
	]);
	assert.equal(queue.statusSnapshot().state, 'ready');
	assert.equal(queue.statusSnapshot().incompleteCapture, true);
});

test('failed I/O releases exclusive append ownership for subsequent records', async () => {
	let calls = 0;
	const sink = new RotatingJsonlSink('audit.jsonl', {
		inspect: false,
		appendFile: async () => { calls += 1; if (calls === 1) throw new Error('disk failure'); },
	});
	await assert.rejects(sink.append('first\n'), /disk failure/);
	await sink.append('second\n');
	assert.equal(calls, 2);
});
