import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, json } from './fixtures/paired-cli-fixture.mjs';
test('real driver and extracted launcher phases run AB/BA against isolated harmless fixtures', { skip: process.platform !== 'win32' }, async t => {
	const f = await fixture(t);
	const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
	assert.equal(report.status, 'COMPLETE', JSON.stringify({ report, journal: await json(path.join(f.config.outputDirectory, 'journal.json')) }));
	assert.equal(report.counts.attempted, 4);
	const journal = await json(path.join(f.config.outputDirectory, 'journal.json'));
	const starts = journal.filter(row => row.kind === 'phase' && row.phase === 'startup');
	assert.equal(new Set(starts.map(row => row.value.worldId)).size, 4);
	assert.deepEqual(starts.map(row => row.value.modSha256), [f.config.arms[0], f.config.arms[1], f.config.arms[1], f.config.arms[0]].map(arm => arm.artifactSha256));
	for (const start of starts) {
		const claim = await json(path.join(start.value.scenarioDirectory, 'world-manifest.json.claimed'));
		assert.equal(claim.worldId, start.value.worldId);
	}
	assert.equal(journal.filter(row => row.kind === 'settled').length, 4);
});
