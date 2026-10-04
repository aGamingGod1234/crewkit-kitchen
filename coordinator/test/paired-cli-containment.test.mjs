import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, json } from './fixtures/paired-cli-fixture.mjs';
const quotePS = value => "'" + value.replaceAll("'", "''") + "'";
test('job containment kills orphaned descendants and does not trust a false clean wrapper', { skip: process.platform !== 'win32' }, async t => {
	const f = await fixture(t);
	const rogue = path.join(f.directory, 'orphan.mjs'); await writeFile(rogue, 'setInterval(()=>{},1000);');
	await writeFile(f.fakeLauncher, `param([string] $ProjectRoot,[switch] $FunctionsOnly)
. ${quotePS(launcher)} -ProjectRoot $ProjectRoot -FunctionsOnly
function Resolve-Java($Project) { return ${quotePS(process.execPath)} }
function Resolve-Node { return ${quotePS(process.execPath)} }
function Test-ProviderPreflight($Provider) { return @{ Available=$true } }
function Invoke-Scenario($Scenario,$Project,$RunDirectory,$Template,$MatrixFile,$Java,$Node,$BuiltJar) {
 $info=[Diagnostics.ProcessStartInfo]::new(); $info.FileName=$Node; $info.Arguments='"'+${quotePS(rogue)}+'"'; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
 $orphan=[Diagnostics.Process]::Start($info)
 Send-PairedEvent 'resource' @{ ProcessId=$orphan.Id; fixture='orphan' }
 Send-PairedEvent 'startup' @{ worldId='headless-orphan'; modSha256=(Get-FileSha256 $BuiltJar) }
 Receive-PairedPhase 'trial'; Send-PairedEvent 'trial' @{ classification='PENDING_EVIDENCE' }
 Receive-PairedPhase 'cleanup'
 return @{ runner=@{status='PASSED';classification='PASSED';cleanup=@{status='CLEAN'}};runnerExit=0;wrapper=@{ok=$true} }
}
`);
	f.config.cleanupMs = 500;
	const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
	assert.equal(report.status, 'INCOMPLETE'); assert.equal(report.counts.started, 1);
	assert.notEqual(report.pairs[0].trials[0].cleanup, 'CLEAN');
	const journal = await json(path.join(f.config.outputDirectory, 'journal.json'));
	const orphan = journal.find(row => row.identity?.fixture === 'orphan'); assert.ok(orphan);
	assert.throws(() => process.kill(orphan.identity.ProcessId, 0));
	assert.equal(journal.at(-1).cleanup, 'UNKNOWN');
});
