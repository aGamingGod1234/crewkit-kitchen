import test from 'node:test';
import assert from 'node:assert/strict';
import { appendFile } from 'node:fs/promises';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, json } from './fixtures/paired-cli-fixture.mjs';

test('paired cleanup drains runner output after stopping sibling pipe writers', { skip: process.platform !== 'win32', timeout: 180_000 }, async t => {
	const f = await fixture(t);
	// Framework anonymous-pipe reads occupy pool threads. A small pool makes
	// idle server/coordinator reads deterministically delay the runner drains.
	await appendFile(f.fakeLauncher, `
if (-not [Threading.ThreadPool]::SetMinThreads(2,2)) { throw 'Could not constrain fixture minimum threads' }
if (-not [Threading.ThreadPool]::SetMaxThreads(2,2)) { throw 'Could not constrain fixture maximum threads' }
`);
	const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
	const journal = await json(path.join(f.config.outputDirectory, 'journal.json'));
	assert.equal(report.status, 'COMPLETE', JSON.stringify({ report, journal }));
	assert.equal(report.counts.outcomes.PASSED, 4);
	assert.ok(report.pairs.flatMap(pair => pair.trials).every(trial => trial.cleanup === 'CLEAN'));
});
