import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, profile } from './fixtures/paired-cli-fixture.mjs';
test('real launcher rejects passed reports with the wrong profile or seed before the peer', { skip: process.platform !== 'win32' }, async t => {
	for (const mode of [ 'passed_wrong_seed' ]) {
		const f = await fixture(t, mode);
		const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
		assert.equal(report.status, 'INCOMPLETE', mode);
		assert.equal(report.counts.started, 1, mode);
		assert.equal(report.counts.attempted, 1, mode);
		assert.equal(report.pairs[0].trials[0].status, 'ERROR', mode);
		assert.equal(report.pairs[0].trials[1].status, 'NOT_STARTED', mode);
	}
});
