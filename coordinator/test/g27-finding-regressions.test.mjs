import { windowsPowerShellEnv } from '../src/benchmark/windows-powershell-env.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../', import.meta.url));
const source = process.env.G27_SOURCE || path.join(repo, 'scripts/run-latency-ab-experiment.ps1');
const windows = process.platform === 'win32';
function fixture(action) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'g27-regression-'));
  try { action(root); }
  finally {
    assert.equal(path.dirname(root), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith('g27-regression-'));
    fs.rmSync(root, { recursive: true, force: true });
  }
}
function powershell(root, body) {
  const script = `$ErrorActionPreference = 'Stop'\n$ProgressPreference = 'SilentlyContinue'\n. $env:G27_SOURCE\n` + String.raw`
function Check($ok, $message) { if (-not $ok) { throw $message } }
function Reject($action, $message) {
  $rejected = $false
  try { & $action | Out-Null } catch { $rejected = $true }
  Check $rejected $message
}
` + body;
  return execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {
    cwd: repo, encoding: 'utf8', timeout: 90000, windowsHide: true,
    env: { ...windowsPowerShellEnv(), G27_SOURCE: source, G27_FIXTURE: root, G27_NODE: process.execPath, PATH: `${path.dirname(process.execPath)};${process.env.PATH}` },
  });
}

test('diagnostic A/B relative arm runners execute distinct modules; shared Node runner needs neutral intent', { skip: !windows }, () => fixture(root => {
  const relative = 'coordinator/src/benchmark/latency-runner-cli.mjs';
  for (const arm of ['baseline', 'optimized']) {
    const dir = path.join(root, arm);
    fs.mkdirSync(path.dirname(path.join(dir, relative)), { recursive: true });
    fs.mkdirSync(path.join(dir, 'coordinator/config'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'coordinator/config/latency-matrix.json'), JSON.stringify({ trials: [{ id: 'g27', mode: 'instant', repetitions: 1 }] }));
    fs.writeFileSync(path.join(dir, 'coordinator/src/benchmark/marker.mjs'), `export default '${arm}';`);
    fs.writeFileSync(path.join(dir, relative), String.raw`
import marker from './marker.mjs';
const args = new Map(); for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1]);
const metadata = Object.fromEntries(['arm', 'run-id', 'source-hash', 'config-hash', 'trial-id', 'planning-concurrency'].map(key => [key.replace(/-([a-z])/g, (_, c) => c.toUpperCase()), args.get('--' + key)]));
metadata.planningConcurrency = Number(metadata.planningConcurrency);
console.log(JSON.stringify({ metadata, status: 'PASSED', cleanup: { ok: true }, trials: [{ trialId: metadata.trialId, repetition: 1, status: 'PASSED', durationMs: 12, marker }] }));
`);
  }
  const output = powershell(root, String.raw`
$base = Join-Path $env:G27_FIXTURE 'baseline'
$opt = Join-Path $env:G27_FIXTURE 'optimized'
$relative = 'coordinator/src/benchmark/latency-runner-cli.mjs'
$common = @{BaselinePath=$base;OptimizedPath=$opt;OutputRoot=(Join-Path $env:G27_FIXTURE 'artifacts');OuterTimeoutSeconds=10;KeepArtifacts=$true}
foreach ($kind in @('relative','default','absolute')) {
  $args = $common.Clone()
  if ($kind -eq 'relative') { $args.BaselineRunnerPath=$relative; $args.OptimizedRunnerPath=$relative }
  if ($kind -eq 'absolute') { $args.BaselineRunnerPath=Join-Path $base $relative; $args.OptimizedRunnerPath=Join-Path $opt $relative }
  $manifest = @(Invoke-LatencyAbExperiment @args | Where-Object { $_ -isnot [string] })[-1]
  Check ($manifest.status -eq 'passed') "$kind experiment failed"
  $markers = @($manifest.results | Sort-Object arm | ForEach-Object { $_.result.trials[0].marker })
  Check (($markers -join ',') -eq 'baseline,optimized') "$kind selected wrong implementation"
}
$shared = Join-Path $base $relative
Reject { Invoke-LatencyAbExperiment @common -BaselineRunnerPath $shared -OptimizedRunnerPath $shared } 'shared explicit per-arm runner accepted without neutral intent'
$neutral = @(Invoke-LatencyAbExperiment @common -BaselineRunnerPath $shared -OptimizedRunnerPath $shared -NeutralRunner | Where-Object { $_ -isnot [string] })[-1]
Check ($neutral.status -eq 'passed') 'explicit neutral runner rejected'
Set-Location -LiteralPath $env:G27_FIXTURE
Reject { Invoke-LatencyAbExperiment @common -RunnerPath ('baseline/' + $relative) } 'common runner accepted without neutral intent'
$neutral = @(Invoke-LatencyAbExperiment @common -RunnerPath ('baseline/' + $relative) -NeutralRunner | Where-Object { $_ -isnot [string] })[-1]
Check ($neutral.status -eq 'passed') 'caller-relative common neutral runner rejected'
'PASS runner identity and neutral control'
`);
  assert.match(output, /PASS runner identity/);
}));

test('diagnostic arm consumer rejects incomplete repetitions or metrics before p95', { skip: !windows }, () => fixture(root => {
  const output = powershell(root, String.raw`
$context = [pscustomobject]@{ RunnerTrialId='g27';TrialId='g27';Arm='baseline';RunId='run';SourceHash='source';ConfigHash='config';PlanningConcurrency=16;Seed=1;Mode='instant';ExpectedRepetitions=5 }
function Sample($count) {
  [pscustomobject]@{ metadata=[pscustomobject]@{trialId='g27';arm='baseline';runId='run';sourceHash='source';configHash='config';planningConcurrency=16};status='PASSED';cleanup=[pscustomobject]@{ok=$true};trials=@(1..$count | ForEach-Object {[pscustomobject]@{trialId='g27';repetition=$_;status='PASSED';durationMs=$(if($_ -eq $count){100}else{1})}}) }
}
$good = Assert-RunnerResult -Result (Sample 5) -Context $context -ResultLimit 131072
Check ($good.latencyMs -eq 100) 'complete five-repetition p95 changed'
Reject { Assert-RunnerResult -Result (Sample 4) -Context $context -ResultLimit 131072 } 'missing tail accepted'
$bad = Sample 5; $bad.trials[4].PSObject.Properties.Remove('durationMs')
Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } 'missing duration accepted'
$bad = Sample 5; $bad.trials[4].repetition=6
Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } 'wrong repetition set accepted'
$bad = Sample 5; $bad.trials[4].repetition=1
Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } 'duplicate repetition accepted'
foreach ($flag in @('trialsTruncated','bounded')) {
  $bad = Sample 5; $bad | Add-Member -NotePropertyName $flag -NotePropertyValue $true
  Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } "$flag evidence accepted"
}
$context.ExpectedRepetitions=300
$bad = Sample 16; $bad | Add-Member -NotePropertyName trialsTruncated -NotePropertyValue $true
Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } 'bounded 300 -> 16 samples accepted'
$context.ExpectedRepetitions=1
$single=Assert-RunnerResult -Result (Sample 1) -Context $context -ResultLimit 131072
Check ($single.latencyMs -eq 100) 'single complete repetition rejected'
$bad=Sample 1; $bad.trials[0].repetition=2
Reject { Assert-RunnerResult -Result $bad -Context $context -ResultLimit 131072 } 'wrong singleton identity accepted'
# Exercise the real arm entry point, including expected count propagation and artifacts.
function Invoke-RunnerProcess {
  param($CommandPlan,$Arguments,$WorkingDirectory,$TimeoutSeconds,$OutputLimit)
  [pscustomobject]@{Started=$true;ExitCode=0;TimedOut=$false;Stdout=($script:candidate|ConvertTo-Json -Depth 12 -Compress);Stderr='';StdoutOverflow=$false;StderrOverflow=$false;Cleanup=[pscustomobject]@{Ok=$true};Error=$null;DurationMs=0}
}
$cell=[pscustomobject]@{TrialId='g27';BaseTrialId='g27';Mode='instant';Provider='';Scenario=[pscustomobject]@{repetitions=5}}
$initial=[pscustomobject]@{SourceSha256='source';Matrix=[pscustomobject]@{Path=(Join-Path $env:G27_FIXTURE 'matrix.json')}}
foreach($count in @(5,4)) {
  $script:candidate=Sample $count
  $result=Invoke-OneLatencyArm -Cell $cell -ArmName baseline -WorktreePath $env:G27_FIXTURE -InitialState $initial -CommandPlan ([pscustomobject]@{Kind='node'}) -RunId run -RunSeed 1 -PlanningConcurrency 16 -ArmConfigHash config -TimeoutSeconds 5 -Retries 0 -UserRunnerArguments @() -MatrixPathValue $initial.Matrix.Path -EffectiveMatrixPath $initial.Matrix.Path -ArmRoot (Join-Path $env:G27_FIXTURE "arm-$count") -OutputLimit 65536 -ResultLimit 131072
  if($count -eq 5){Check ($result.Summary.status -eq 'PASSED' -and $result.Summary.latencyMs -eq 100) 'complete arm failed'}
  else {Check ($result.Summary.status -eq 'FAILED' -and $null -eq $result.Summary.latencyMs) 'incomplete arm still reports p95'}
}
'PASS complete samples and arm consumer'
`);
  assert.match(output, /PASS complete samples/);
}));

test('diagnostic cleanup retains orphan ownership and never kills replacement handles', { skip: !windows }, () => fixture(root => {
  const output = powershell(root, String.raw`
function Handle($id,$exited,$key) {
  $p=[pscustomobject]@{Id=$id;HasExited=$exited;Killed=$false;Disposed=$false;Handle=1;StartTime=[DateTime]'2026-01-01';Key=$key}
  $p | Add-Member ScriptMethod Kill { $this.Killed=$true; $this.HasExited=$true }
  $p | Add-Member ScriptMethod Dispose { $this.Disposed=$true }
  $p
}
function Identity($p) { [pscustomobject]@{ProcessId=$p.Id;CreationKey=$p.Key;Process=$p} }
function Get-LatencyProcessSnapshot { $script:snapshot }
function Get-Process { param($Id,$ErrorAction) $script:current[$Id] }
function Open-LatencyProcessIdentity { param($Process) Identity $Process }
foreach($forced in @($false,$true)) {
  $root=Handle 101 $true '1'; $old=Handle 202 $true '2'; $leaf=Handle 303 $false '3'; $replacement=Handle 202 $false '8'; $stranger=Handle 404 $false '9'
  $tracked=@{101=(Identity $root);202=(Identity $old);303=(Identity $leaf)}
  $script:current=@{202=$replacement;303=$leaf;404=$stranger}
  $script:snapshot=@{202=[pscustomobject]@{ProcessId=202;ParentProcessId=999;CreationKey='8'};303=[pscustomobject]@{ProcessId=303;ParentProcessId=202;CreationKey='3'};404=[pscustomobject]@{ProcessId=404;ParentProcessId=202;CreationKey='9'}}
  $result=Stop-TrackedProcessTree -RootProcessId 101 -TrackedProcesses $tracked -ForceRoot:$forced
  Check ($leaf.Killed -and $result.Ok) 'tracked orphan was not cleaned'
  Check (-not $replacement.Killed -and -not $stranger.Killed) 'unowned replacement or its child was killed'
}
# Reject replacement acquired between process table snapshot and handle capture.
$root=Handle 101 $false '1'; $replacement=Handle 202 $false '8'
$tracked=@{101=(Identity $root)}
$script:current=@{101=$root;202=$replacement}
$script:snapshot=@{101=[pscustomobject]@{ProcessId=101;ParentProcessId=1;CreationKey='1'};202=[pscustomobject]@{ProcessId=202;ParentProcessId=101;CreationKey='2'}}
Add-LatencyTrackedProcesses -Tracked $tracked
Check (-not $tracked.ContainsKey(202) -and $replacement.Disposed) 'snapshot race acquired replacement'
$result=Stop-TrackedProcessTree -RootProcessId 101 -TrackedProcesses $tracked -ForceRoot
Check ($root.Killed -and -not $replacement.Killed -and $result.Ok) 'forced root control failed'
'PASS historical ownership and replacement identity controls'
`);
  assert.match(output, /PASS historical ownership/);
}));

test('diagnostic process entry point cleans real owned orphan tree and timeout', { skip: !windows }, () => fixture(root => {
  fs.writeFileSync(path.join(root, 'tree.mjs'), String.raw`
import fs from 'node:fs'; import path from 'node:path'; import { spawn } from 'node:child_process'; import { fileURLToPath } from 'node:url';
const [role,dir,shape]=process.argv.slice(2), self=fileURLToPath(import.meta.url);
fs.writeFileSync(path.join(dir, role+'.json'),JSON.stringify({pid:process.pid,role}));
setTimeout(()=>process.exit(90),25000);
setInterval(()=>{if(fs.existsSync(path.join(dir,'stop')))process.exit(0)},30);
if(role!=='leaf') {
 const next=role==='root'&&shape==='orphan'?'middle':'leaf';
 const child=spawn(process.execPath,[self,next,dir,shape],{detached:true,stdio:'ignore',windowsHide:true}); child.unref();
 if(role==='root'&&shape==='orphan')child.once('exit',()=>process.exit(0));
 else if(shape!=='timeout')setInterval(()=>{if(fs.existsSync(path.join(dir,'release')))setTimeout(()=>process.exit(0),150)},30);
}
`);
  const output = powershell(root, String.raw`
$productionAdd=(Get-Command Add-LatencyTrackedProcesses).ScriptBlock
function Add-LatencyTrackedProcesses {
  param($Tracked)
  & $productionAdd -Tracked $Tracked
  $leafPath=Join-Path $script:caseRoot 'leaf.json'
  if(Test-Path -LiteralPath $leafPath) {
    $leaf=Get-Content -Raw -LiteralPath $leafPath | ConvertFrom-Json
    if($Tracked.ContainsKey([int]$leaf.pid)) {
      $script:observed=$true
      [IO.File]::WriteAllText((Join-Path $script:caseRoot 'release'),'tracked')
    }
  }
}
foreach($shape in @('orphan','direct','timeout')) {
  $script:caseRoot=Join-Path $env:G27_FIXTURE $shape
  New-Item -ItemType Directory -Path $script:caseRoot | Out-Null
  $script:observed=$false
  try {
    $plan=[pscustomobject]@{Executable=$env:G27_NODE;PrefixArguments=@((Join-Path $env:G27_FIXTURE 'tree.mjs'))}
    $seconds=if($shape -eq 'timeout'){3}else{12}
    $result=Invoke-RunnerProcess -CommandPlan $plan -Arguments @('root',$script:caseRoot,$shape) -WorkingDirectory $env:G27_FIXTURE -TimeoutSeconds $seconds -OutputLimit 65536
    Check $script:observed 'owned leaf was never tracked'
    Check $result.Cleanup.Ok 'owned process remains after cleanup'
    if($shape -eq 'timeout'){Check $result.TimedOut 'timeout control did not time out'}
    else{Check (-not $result.TimedOut -and $result.ExitCode -eq 0) 'normal tree did not exit successfully'}
  } finally { [IO.File]::WriteAllText((Join-Path $script:caseRoot 'stop'),'done') }
}
'PASS actual Windows orphan, direct child and forced timeout'
`);
  assert.match(output, /PASS actual Windows/);
}));
