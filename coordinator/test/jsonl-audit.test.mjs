import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';

import { createJsonlAudit } from '../src/dynamic-main.mjs';

test('protocol audit owns hung writes without delaying control and bounds close', async () => {
	const audit = createJsonlAudit('protocol.jsonl', { runId: 'run' }, {
		mkdir: async () => {}, appendFile: () => new Promise(() => {}),
		maxPending: 2, operationTimeoutMs: 20, closeTimeoutMs: 30,
	});
	for (let index = 0; index < 100; index += 1) await audit('out', { sequence: index });
	assert.ok(audit.statusSnapshot().droppedCount > 0);
	const first = audit.close();
	assert.strictEqual(audit.close(), first);
	await first;
});

test('protocol audit uses shared redaction and survives rejected writes', async () => {
	const rows = [];
	let calls = 0;
	const audit = createJsonlAudit('protocol.jsonl', { authorization: 'Bearer metadata-secret' }, {
		mkdir: async () => {},
		appendFile: async (_path, text) => {
			calls += 1;
			if (calls === 1) throw new Error('temporary failure');
			rows.push(JSON.parse(text));
		},
	});
	await audit('out', { password: 'payload-secret', path: 'C:\\Users\\lucas\\secret.json' });
	await audit('out', { ok: true });
	await audit.close();
	assert.equal(rows.length, 1);
	assert.equal(JSON.stringify(rows).includes('metadata-secret'), false);
	assert.equal(JSON.stringify(rows).includes('lucas'), false);
	assert.equal(audit.statusSnapshot().state, 'ready');
	assert.equal(audit.statusSnapshot().failedOperationCount, 1);
	assert.equal(audit.statusSnapshot().incompleteCapture, true);
});

test('audit directory failure cannot escape through Node unhandled-rejection policy', () => {
	const moduleUrl = new URL('../src/dynamic-main.mjs', import.meta.url).href;
	for (const immediateWrite of [false, true]) {
		const source = `
			import assert from 'node:assert/strict';
			import { createJsonlAudit } from ${JSON.stringify(moduleUrl)};
			const audit = createJsonlAudit('unused.jsonl', {}, {
				mkdir: async () => { throw new Error('directory unavailable'); },
				appendFile: async () => assert.fail('cannot write before directory preparation'),
			});
			if (${immediateWrite}) await audit('out', { sequence: 1 });
			await new Promise(setImmediate);
			await audit.close();
			assert.equal(audit.statusSnapshot().state, 'degraded');
			assert.equal(audit.statusSnapshot().incompleteCapture, true);
		`;
		const child = spawnSync(process.execPath, ['--unhandled-rejections=strict', '--input-type=module', '--eval', source], { encoding: 'utf8' });
		assert.equal(child.status, 0, child.stderr || child.stdout);
	}
});

test('audit directory synchronous throws are contained without losing healthy first-row admission', async () => {
	const failed = createJsonlAudit('unused.jsonl', {}, { mkdir: () => { throw new Error('directory unavailable'); } });
	await new Promise(setImmediate);
	await failed.close();
	assert.equal(failed.statusSnapshot().state, 'degraded');
	let writes = 0;
	const healthy = createJsonlAudit('unused.jsonl', {}, {
		maxPending: 1, mkdir: async () => {}, appendFile: async () => { writes += 1; },
	});
	await healthy('out', { sequence: 1 });
	await healthy.close();
	assert.equal(writes, 1);
	assert.equal(healthy.statusSnapshot().incompleteCapture, false);
});
