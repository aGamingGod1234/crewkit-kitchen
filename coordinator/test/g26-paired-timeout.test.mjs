import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, json } from './fixtures/paired-cli-fixture.mjs';
// Four real PowerShell arms: minutes on a saturated runner, so the default per-test budget is too tight.
test('paired launcher completes real full-budget AB/BA timeout handoffs', { skip: process.platform !== 'win32', timeout: 600_000 }, async t => {
 const f = await fixture(t, 'full_timeout');
 const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
 await writeFile(path.join(f.directory, 'full-route-result.json'), JSON.stringify({ report, fixtureDirectory: f.directory }, null, 2));
 assert.equal(report.status, 'COMPLETE', JSON.stringify(report));
 assert.equal(report.counts.outcomes.TIMED_OUT, 4);
 assert.ok(report.pairs.flatMap(pair=>pair.trials).every(trial=>trial.cleanup==='CLEAN'));
 assert.equal((await json(path.join(f.config.outputDirectory,'completion.json'))).status, 'COMPLETE');
});
