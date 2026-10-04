import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { ToolResponseSummary, normalizeToolResponseSummary } from '../src/tool-response-summary.mjs';

const response = { success: true, contentItems: [{ type: 'inputText', text: 'PRIVATE_PAYLOAD_\u79d8\u5bc6_\ud83d\ude42' }] };
const serializedResponse = JSON.stringify(response);
const fields = { name: 'act', kind: 'action', hasPostAction: true, success: true, serializedResponse, executionMs: 12 };
const hash = text => `sha256:${createHash('sha256').update(text, 'utf8').digest('hex')}`;
const chain = (previous, text) => hash(`${previous ?? ''}\n${text}`);

test('exact UTF-8 bytes, attempt order and completion outcome survive without private payloads', () => {
	const summary = new ToolResponseSummary();
	const first = summary.begin(fields);
	const second = summary.begin({ ...fields, serializedResponse: `${serializedResponse} ` });
	const third = summary.begin(fields);
	second.finish(false, 4.5);
	first.finish(true, 3);
	first.finish(false, 900); // Completion is idempotent.
	const row = summary.snapshot().rows[0];
	const bytes = Buffer.byteLength(serializedResponse, 'utf8');
	assert.ok(bytes > serializedResponse.length);
	assert.equal(first.bytes, bytes);
	assert.equal(third.bytes, bytes);
	assert.deepEqual([row.attempts, row.accepted, row.failed, row.pending], [3, 1, 1, 1]);
	assert.deepEqual([row.attemptedBytes, row.acceptedBytes, row.failedBytes, row.pendingBytes], [3 * bytes + 1, bytes, bytes + 1, bytes]);
	assert.equal(row.family, 'action_post_observation');
	assert.deepEqual([row.executionMs, row.executionSamples, row.respondMs, row.respondSamples], [36, 3, 7.5, 2]);
	assert.equal(row.responseHashChain, chain(chain(chain(null, hash(serializedResponse)), hash(`${serializedResponse} `)), hash(serializedResponse)));
	assert.equal(row.completionHashChain, chain(chain(null, `failed:${hash(`${serializedResponse} `)}`), `accepted:${hash(serializedResponse)}`));
	assert.equal(summary.snapshot().receiptAcknowledged, false);
	assert.doesNotMatch(JSON.stringify(summary.snapshot()), /PRIVATE_PAYLOAD|contentItems|inputText/);
	assert.deepEqual(normalizeToolResponseSummary(summary.snapshot()), summary.snapshot());
});

test('all supported families use fixed vocabulary and tool names never copy arbitrary identifiers', () => {
	for (const [name, kind, hasPostAction, success, expected] of [
		['observe', 'observe', false, true, 'observation'], ['sequence', 'sequence', true, true, 'sequence'],
		['act', 'action', false, true, 'action'], ['startAction', 'start_action', true, true, 'action_post_observation'],
		...['run_program', 'queue_program', 'cancel_queued_program', 'program_status', 'cancel_program', 'respond_program'].map(kind => ['runProgram', kind, false, true, 'program']),
		['lookAround', 'lookAround', false, true, 'camera'], ['inspect', 'inspect', false, true, 'inspection'],
		...['task_memory', 'task_plan', 'notebook', 'query_memory'].map(kind => ['taskMemory', kind, false, true, 'memory']),
		['capabilities', 'capabilities', false, true, 'other'], ['observe', 'observe', false, false, 'error'],
		['PRIVATE_NAME', 'PRIVATE_program_KIND', true, true, 'other'],
	]) {
		const summary = new ToolResponseSummary();
		summary.begin({ name, kind, hasPostAction, success, serializedResponse: '{}' }).finish(true);
		const row = summary.snapshot().rows[0];
		assert.equal(row.family, expected);
		assert.equal(row.tool, name === 'PRIVATE_NAME' ? 'unknown' : name);
		assert.equal(row.accepted, 1, 'even an error response can be accepted by transport');
		assert.equal(row.failed, 0);
		assert.doesNotMatch(JSON.stringify(row), /PRIVATE/);
	}
});

test('unknown outcomes remain pending; invalid/missing timing is not a measured zero', () => {
	const summary = new ToolResponseSummary();
	const pending = summary.begin({ ...fields, executionMs: undefined });
	for (const outcome of [undefined, null, 'false', 1, {}, Symbol('private')]) assert.doesNotThrow(() => pending.finish(outcome));
	let row = summary.snapshot().rows[0];
	assert.deepEqual([row.accepted, row.failed, row.pending, row.executionSamples, row.respondSamples], [0, 0, 1, 0, 0]);
	assert.equal(summary.snapshot().captureFailures, 6);
	pending.finish(false, Infinity);
	summary.begin({ ...fields, executionMs: -1 }).finish(true, NaN);
	summary.begin({ ...fields, executionMs: 0 }).finish(true, 0);
	row = summary.snapshot().rows[0];
	assert.deepEqual([row.executionMs, row.executionSamples, row.respondMs, row.respondSamples], [0, 1, 0, 1]);
	assert.deepEqual([row.accepted, row.failed, row.pending], [2, 1, 0]);
	assert.deepEqual(normalizeToolResponseSummary(summary.snapshot()), summary.snapshot());
});

test('malformed input and hostile metadata cannot escape or leave partial counts', () => {
	const summary = new ToolResponseSummary();
	let accesses = 0;
	for (const malformed of [undefined, null, {}, { serializedResponse: { toJSON() { assert.fail('never serialize raw objects'); } } },
		{ get name() { accesses++; throw new Error('PRIVATE_ERROR'); } }]) {
		assert.equal(summary.begin(malformed), null);
	}
	assert.equal(accesses, 1);
	assert.equal(summary.snapshot().captureFailures, 5);
	assert.deepEqual(summary.snapshot().rows, []);
	summary.captureFailure(); // Boundary serialization can report failure without copying the error.
	assert.equal(summary.snapshot().captureFailures, 6);
	const noCoercion = { toString() { assert.fail('never coerce private metadata'); } };
	summary.begin({ ...fields, name: noCoercion, kind: noCoercion, executionMs: noCoercion }).finish(true, noCoercion);
	assert.equal(summary.snapshot().rows[0].tool, 'unknown');
	assert.doesNotMatch(JSON.stringify(summary.snapshot()), /PRIVATE_ERROR/);
});

test('finite timing overflow is contained atomically and later completion remains possible', () => {
	const summary = new ToolResponseSummary();
	const first = summary.begin({ ...fields, executionMs: Number.MAX_VALUE });
	assert.equal(summary.begin({ ...fields, executionMs: Number.MAX_VALUE }), null);
	const second = summary.begin({ ...fields, executionMs: 0 });
	first.finish(true, Number.MAX_VALUE);
	second.finish(false, Number.MAX_VALUE);
	let row = summary.snapshot().rows[0];
	assert.deepEqual([row.attempts, row.accepted, row.failed, row.pending], [2, 1, 0, 1]);
	assert.equal(summary.snapshot().captureFailures, 2);
	second.finish(false, 0);
	row = summary.snapshot().rows[0];
	assert.deepEqual([row.accepted, row.failed, row.pending], [1, 1, 0]);
	assert.deepEqual(normalizeToolResponseSummary(summary.snapshot()), summary.snapshot());
});

test('snapshots are detached and per-turn state does not leak across collectors', () => {
	const summary = new ToolResponseSummary();
	const pending = summary.begin(fields);
	const before = summary.snapshot();
	before.rows[0].accepted = 100;
	before.rows.length = 0;
	pending.finish(true, 2);
	assert.equal(summary.snapshot().rows[0].accepted, 1);
	assert.deepEqual(new ToolResponseSummary().snapshot().rows, []);
});

test('strict persistence removes private extras and rejects inconsistent evidence', () => {
	const summary = new ToolResponseSummary();
	summary.begin(fields).finish(true, 1);
	const value = summary.snapshot();
	value.payload = 'PRIVATE_DATA';
	value.receiptAcknowledged = true;
	value.rows[0].arguments = 'PRIVATE_ARGS';
	value.rows[0].text = 'PRIVATE_TEXT';
	const normalized = normalizeToolResponseSummary(value);
	assert.doesNotMatch(JSON.stringify(normalized), /PRIVATE_|payload|arguments|text/);
	assert.equal(normalized.receiptAcknowledged, false);
	assert.ok(Object.isFrozen(normalized.rows[0]));
	for (const mutate of [
		v => { v.version = 2; }, v => { v.scope = 'billing'; }, v => { v.captureFailures = -1; },
		v => { v.rows.push({ ...v.rows[0] }); }, v => { v.rows[0].tool = 'PRIVATE_NAME'; },
		v => { v.rows[0].family = 'PRIVATE_FAMILY'; }, v => { v.rows[0].pending++; },
		v => { v.rows[0].acceptedBytes++; }, v => { v.rows[0].respondSamples = 2; },
		v => { v.rows[0].executionSamples = 0; }, v => { v.rows[0].respondMs = NaN; },
		v => { v.rows[0].responseHashChain = 'PRIVATE_HASH'; }, v => { v.rows[0].completionHashChain = null; },
		v => { v.rows[0].accepted = 0; v.rows[0].pending = 1; },
	]) {
		const invalid = summary.snapshot();
		mutate(invalid);
		assert.throws(() => normalizeToolResponseSummary(invalid), TypeError);
	}
});

test('offline delivery fixture preserves sync throws and async rejection without acceptance', async () => {
	// This exercises the documented integration protocol, not Codex production wiring.
	const summary = new ToolResponseSummary();
	const error = new Error('PRIVATE_TRANSPORT_ERROR');
	const deliver = async (send, captureFields = fields) => {
		const measurement = summary.begin(captureFields);
		try {
			const result = await send(response);
			measurement?.finish(true, 2);
			return result;
		} catch (error) { measurement?.finish(false, 2); throw error; }
	};
	const sent = [];
	assert.equal(await deliver(value => { sent.push(value); return 17; }, null), 17);
	assert.strictEqual(sent[0], response);
	await assert.rejects(deliver(() => { throw error; }), caught => caught === error);
	await assert.rejects(deliver(() => Promise.reject(error)), caught => caught === error);
	assert.equal(await deliver(async value => { assert.strictEqual(value, response); return 23; }), 23);
	assert.deepEqual([summary.snapshot().rows[0].accepted, summary.snapshot().rows[0].failed], [1, 2]);
	assert.equal(summary.snapshot().captureFailures, 1);
	assert.doesNotMatch(JSON.stringify(summary.snapshot()), /PRIVATE_/);
});

test('persistence validates the same values it writes and ignores overridden array methods', () => {
	const summary = new ToolResponseSummary();
	summary.begin(fields).finish(true);
	const value = summary.snapshot();
	let toolReads = 0;
	let scopeReads = 0;
	Object.defineProperty(value.rows[0], 'tool', { get() { return ++toolReads === 1 ? 'act' : 'PRIVATE_TOOL'; } });
	Object.defineProperty(value, 'scope', { get() { return ++scopeReads === 1 ? 'serialized_tool_response_transport_boundary' : 'PRIVATE_SCOPE'; } });
	value.rows.map = () => ['PRIVATE_ARRAY_METHOD'];
	const normalized = normalizeToolResponseSummary(value);
	assert.equal(normalized.rows[0].tool, 'act');
	assert.equal(toolReads, 1);
	assert.equal(scopeReads, 1);
	assert.doesNotMatch(JSON.stringify(normalized), /PRIVATE_/);
});
