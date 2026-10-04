import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import path from 'node:path';
import { normalizeHeadlessMatrix } from '../src/headless-matrix.mjs';
import {  } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, json, exec } from './fixtures/paired-cli-fixture.mjs';
const quotePS = value => "'" + value.replaceAll("'", "''") + "'";
test('extracted phase code preserves the existing single-scenario launcher path', { skip: process.platform !== 'win32' }, async t => {
 const f = await fixture(t); const arm = f.config.arms[0];
 const normalized = normalizeHeadlessMatrix(await json(f.config.matrixPath)).scenarios[0];
 const scenarioFile = path.join(f.directory, 'normalized.json'); await writeFile(scenarioFile, JSON.stringify(normalized));
 const script = path.join(f.directory, 'single.ps1'); const legacyDirectory = path.join(f.directory, 'legacy'); await mkdir(legacyDirectory);
 const legacyReport = path.join(f.directory, 'legacy-report.json');
 await writeFile(script, `. ${quotePS(f.fakeLauncher)} -ProjectRoot ${quotePS(arm.sourceRoot)} -FunctionsOnly
 $scenario=Get-Content -Raw -LiteralPath ${quotePS(scenarioFile)} | ConvertFrom-Json
 $report=Invoke-Scenario $scenario ${quotePS(arm.sourceRoot)} ${quotePS(legacyDirectory)} ${quotePS(f.config.serverTemplate)} ${quotePS(f.config.matrixPath)} ${quotePS(process.execPath)} ${quotePS(process.execPath)} ${quotePS(arm.artifactPath)}
 $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath ${quotePS(legacyReport)} -Encoding UTF8
 `);
 const result = await exec('powershell.exe', ['-NoProfile','-NonInteractive','-File',script]);
 assert.equal(result.code, 0, result.stderr);
 const report = JSON.parse((await readFile(legacyReport,'utf8')).replace(/^\uFEFF/,''));
 assert.equal(report.status,'PASSED'); assert.equal(report.cleanup.status,'CLEAN'); assert.equal(report.cleanup.runner.status,'CLEAN');
});
