import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { root, launcher } from './paired-cli-fixture.mjs';
import { windowsPowerShellEnv } from '../../src/benchmark/windows-powershell-env.mjs';
const evidence = tmpdir();
const lifecycle = path.join(root,'scripts/test-run-headless-provider-matrix.ps1');
const quotePS = value => "'" + value.replaceAll("'", "''") + "'";
export async function powershellFixture(script, files = {}) {
 const directory = await mkdtemp(path.join(evidence, 'g26-fixture-'));
 for (const [name, contents] of Object.entries(files)) await writeFile(path.join(directory, name), contents);
 const entry = path.join(directory, 'verify.ps1');
 await writeFile(entry, `$SourceRoot = ${quotePS(root)}
$Launcher = ${quotePS(launcher)}
$Lifecycle = ${quotePS(lifecycle)}
$Node = ${quotePS(process.execPath)}
` + script);
 const result = spawnSync('powershell.exe', ['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',entry], {cwd:root,env:windowsPowerShellEnv(),encoding:'utf8',windowsHide:true,timeout:90000});
 await writeFile(path.join(directory,'result.json'),JSON.stringify({status:result.status,stdout:result.stdout,stderr:result.stderr,error:result.error?.message},null,2));
 assert.equal(result.status, 0, `Fixture ${directory}
${result.stderr}
${result.stdout}`);
 return result.stdout;
}
