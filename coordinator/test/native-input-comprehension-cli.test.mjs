import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { access, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const coordinatorRoot = fileURLToPath(new URL('../', import.meta.url));
const fixture = new URL('./fixtures/comprehension-offline-transport.mjs', import.meta.url).href;

test('comprehension CLI rejects invalid output before transport startup and retains valid report output', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'comprehension-cli-'));
	try {
		const context = path.join(root, 'context.json');
		await writeFile(context, JSON.stringify({ minecraftInstructions: '', skillInstructions: '', nativeInstructions: '', tools: [{ name: 'say' }] }));
		for (const suffix of ['md', 'JSON', 'json']) {
			const destination = path.join(root, `report.${suffix}`);
			const countsPath = path.join(root, `counts-${suffix}.json`);
			const result = spawnSync(process.execPath, ['--import', fixture,
				path.join(coordinatorRoot, 'src/benchmark/native-input-comprehension.mjs'),
				'--before', context, '--after', context, '--output', destination], {
				cwd: root, env: { ...process.env, COMPREHENSION_TRANSPORT_COUNTS: countsPath },
				encoding: 'utf8', windowsHide: true, timeout: 10000,
			});
			assert.equal(result.error, undefined);
			assert.equal(result.signal, null);
			const valid = suffix === 'json';
			assert.equal(result.status, valid ? 0 : 1, result.stdout + result.stderr);
			assert.deepEqual(JSON.parse(await readFile(countsPath, 'utf8')), {
				starts: valid ? 1 : 0, stops: valid ? 1 : 0, requests: 0, modelTurns: 0,
			});
			if (valid) {
				const report = JSON.parse(await readFile(destination, 'utf8'));
				assert.equal(report.status, 'UNAVAILABLE');
				assert.equal(report.failure, 'OFFLINE_START_SENTINEL');
				assert.deepEqual(report.turns, []);
				assert.match(await readFile(destination.replace(/\.json$/, '.md'), 'utf8'), /Status: UNAVAILABLE/);
			} else {
				assert.match(result.stderr, /--output must end in \.json/);
				await assert.rejects(access(destination), { code: 'ENOENT' });
			}
		}
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
