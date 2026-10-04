import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { normalizeHeadlessMatrix } from '../src/headless-matrix.mjs';
import { runPairedCli, phaseWorker, verifyArm } from '../src/benchmark/paired-cli.mjs';

const root = path.resolve(fileURLToPath(new URL('../../', import.meta.url)));
const launcher = process.env.G26_WRAPPER ?? path.join(root, 'scripts/run-headless-provider-matrix.ps1');
const evidence = process.env.G26_EVIDENCE ?? tmpdir();
const lifecycle = process.env.G26_LIFECYCLE ?? path.join(root, 'scripts/test-run-headless-provider-matrix.ps1');
const hash = value => createHash('sha256').update(value).digest('hex');
const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const quotePS = value => `'${value.replaceAll("'", "''")}'`;
async function json(file) { return JSON.parse(await readFile(file, 'utf8')); }
async function fixture(t, mode = 'pass') {
	const directory = await mkdtemp(path.join(evidence, 'full-route-'));
	// Retain this owned fixture as integration evidence.
	const arms = [];
	for (const id of ['A', 'B']) {
		const sourceRoot = path.join(directory, id);
		const files = {
			'coordinator/src/dynamic-main.mjs': `// arm ${id}\nsetInterval(() => {}, 1000);`,
			'coordinator/src/headless-world-spawn.mjs': 'console.log(JSON.stringify({savedSpawn:{source:"level.dat",dimension:"minecraft:overworld",x:0,y:64,z:0},spawnLoading:{operation:"temporary_spawn_chunk_loading",x:0,z:0,ready:true,elapsedMs:0,terrainModified:false,inventoryModified:false}}));',
			'coordinator/config/dynamic-agents.json': '{}',
			'coordinator/src/headless-matrix.mjs': `
import { readFile, writeFile, open, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runnerPhaseChannel } from ${JSON.stringify(pathToFileURL(path.join(root, 'coordinator/src/benchmark/paired-runner-channel.mjs')).href)};
import { claimNaturalWorld } from ${JSON.stringify(pathToFileURL(path.join(root, 'coordinator/src/headless-world.mjs')).href)};
const args = new Map(); for(let i=2;i<process.argv.length;i+=2) args.set(process.argv[i],process.argv[i+1]);
const directory=args.get('--run-directory');
const manifest=JSON.parse(await readFile(args.get('--world-manifest'),'utf8'));
await claimNaturalWorld(args.get('--world-manifest'), manifest.worldId);
const scenario=JSON.parse(await readFile(args.get('--config'),'utf8')).scenarios[0];
const channel=args.has('--paired-channel') ? runnerPhaseChannel(args.get('--paired-channel')) : null;
if(channel) {
 const sourceRoot=fileURLToPath(new URL('../../',import.meta.url));
 const artifact=(await readdir(sourceRoot)).find(name=>name.endsWith('.jar'));
 for (const binding of ['coordinator/src/dynamic-main.mjs','coordinator/config/dynamic-agents.json','source-manifest.json',artifact].map(file=>path.join(sourceRoot,file)).concat(args.get('--config'))) {
  let locked=false; try { const handle=await open(binding,'r+'); await handle.close(); } catch { locked=true; }
  if(!locked) throw new Error('arm binding was mutable');
 }
 const deadline=await channel.ready();
 if(scenario.task==='full_timeout') { while(performance.now()<deadline) await new Promise(resolve=>setTimeout(resolve,Math.max(1,deadline-performance.now()))); }
}
if (scenario.task === 'block_trial') { while(true) {} }
if(channel) await channel.cleanup({classification:'PENDING_EVIDENCE'});
const classification=({full_timeout:'TIMEOUT',error:'ERROR',wrong_profile:'PROFILE_MISMATCH',wrong_seed:'ERROR',failure:'FAILED_USER_OBJECTIVE',timeout:'TIMEOUT',cleanup_failure:'CLEANUP_FAILURE'})[scenario.task] ?? 'PASSED';
const report={scenarioId:scenario.id,status:classification==='PASSED'?'PASSED':'FAILED',classification,cleanup:{status:scenario.task==='cleanup_failure'?'FAILED':'CLEAN'},world:{worldId:manifest.worldId,fresh:manifest.fresh,...manifest.world},profile:${JSON.stringify(profile)},settings:{configuredVerified:true,configured:${JSON.stringify(profile)}}};
if(scenario.task==='passed_wrong_profile') report.settings.configured.reasoningEffort='low';
if(scenario.task==='passed_wrong_seed') report.world.seed='2';
await writeFile(path.join(directory,'report.json'),JSON.stringify(report));
process.exitCode=report.status==='FAILED'?1:0;
`,
		};
		for (const [relative, text] of Object.entries(files)) { await mkdir(path.dirname(path.join(sourceRoot, relative)), { recursive: true }); await writeFile(path.join(sourceRoot, relative), text); }
		const artifactPath = path.join(sourceRoot, `fake-${id}.jar`); await writeFile(artifactPath, `not a runtime: ${id}`);
		const sourceManifestPath = path.join(sourceRoot, 'source-manifest.json');
		await writeFile(sourceManifestPath, JSON.stringify({ version: 1, files: Object.entries(files).map(([path, contents]) => ({ path, sha256: hash(contents) })) }));
		arms.push({ id, profile, sourceRoot, artifactPath, sourceManifestPath, artifactSha256: hash(await readFile(artifactPath)), sourceManifestSha256: hash(await readFile(sourceManifestPath)) });
	}
	const serverTemplate = path.join(directory, 'tiny-template'); await mkdir(serverTemplate);
	await writeFile(path.join(serverTemplate, 'fabric-server-launch.jar'), 'harmless fixture, never executed');
	const server = path.join(directory, 'idle.mjs'); await writeFile(server, "process.stdin.on('data', () => process.exit(0)); setInterval(() => {},1000);");
	const matrixPath = path.join(directory, 'matrix.json');
	await writeFile(matrixPath, JSON.stringify({ version: 1, scenarios: [{ id: 'natural-fixture', ...profile, task: mode, timeoutMs: 3000, scenarioTimeoutMs: 3000, world: { mode: 'natural', seed: '-9223372036854775808' }, requireFactualSuccess: true, assert: [{ type: 'rcon', command: 'data get entity {agent} Inventory', match: 'oak_log' }] }] }));
	const config = { runtimeBudgetMs: 150000, startupMs: 10000, cleanupMs: 15000, outputDirectory: path.join(directory, 'result'), matrixPath, serverTemplate, arms, scenarios: [{ id: 'natural-fixture', seed: '-9223372036854775808', trialMs: 3000 }] };
	const fakeLauncher = path.join(directory, 'fake-launcher.ps1');
	await writeFile(fakeLauncher, `param([string] $ProjectRoot, [switch] $FunctionsOnly)
. ${quotePS(launcher)} -ProjectRoot $ProjectRoot -FunctionsOnly
$script:ActualStart = \${function:Start-RedirectedProcess}
$script:ActualRead = \u0024{function:Read-Text}
$script:FixtureHandles = [System.Collections.Generic.List[object]]::new()
$script:Port = 21000
function Resolve-Java($Project) { return ${quotePS(process.execPath)} }
function Resolve-Node { return ${quotePS(process.execPath)} }
function Test-ProviderPreflight($Provider) { return @{ Available = $true } }
function Protect-LocalFile($Path) {}
function Assert-ArenaOfflineServerLoopback($Path, [switch] $RequireOffline) {}
function New-ScenarioConfig($Source,$Destination,$BridgePort,$WorkspaceRoot) { [IO.File]::WriteAllText($Destination, '{}') }
function Reserve-FreePort($Preferred,$Excluded) { $script:Port++; return $script:Port }
function Test-Port($Port) { return @($script:FixtureHandles | Where-Object { -not $_.Process.HasExited }).Count -gt 0 }
function Read-Text($Path) { if ($Path.EndsWith('latest.log')) { return 'Done (' }; return (& $script:ActualRead $Path) }
function Test-CoordinatorReady($Path,$Scenario) { return $true }
function Get-ProcessSnapshot {
 $result = @{}
 foreach ($handle in $script:FixtureHandles) {
  $handle.Process.Refresh()
  if (-not $handle.Process.HasExited) { $result[[int] $handle.Process.Id] = [pscustomobject]@{ ProcessId = $handle.Process.Id; ParentProcessId = $PID; CreationDate = $handle.Identity.CreationDate; WorkingSetSize = $handle.Process.WorkingSet64 } }
 }
 return $result
}
function Start-RedirectedProcess($FileName,$Arguments,$WorkingDirectory,$StdoutPath,$StderrPath,$Environment) {
 if ($Arguments.StartsWith('-Darenaagents')) { $Arguments = '"' + ${quotePS(server)} + '"' }
 $handle = & $script:ActualStart $FileName $Arguments $WorkingDirectory $StdoutPath $StderrPath $Environment
 $script:FixtureHandles.Add($handle)
 return $handle
}
`);
	return { directory, config, fakeLauncher };
}


test('paired launcher completes real full-budget AB/BA timeout handoffs', { skip: process.platform !== 'win32' }, async t => {
 const f = await fixture(t, 'full_timeout');
 const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
 await writeFile(path.join(f.directory, 'full-route-result.json'), JSON.stringify({ report, fixtureDirectory: f.directory }, null, 2));
 assert.equal(report.status, 'COMPLETE', JSON.stringify(report));
 assert.equal(report.counts.outcomes.TIMED_OUT, 4);
 assert.ok(report.pairs.flatMap(pair=>pair.trials).every(trial=>trial.cleanup==='CLEAN'));
 assert.equal((await json(path.join(f.config.outputDirectory,'completion.json'))).status, 'COMPLETE');
});

async function powershellFixture(script, files = {}) {
 const directory = await mkdtemp(path.join(evidence, 'g26-fixture-'));
 for (const [name, contents] of Object.entries(files)) await writeFile(path.join(directory, name), contents);
 const entry = path.join(directory, 'verify.ps1');
 await writeFile(entry, `$SourceRoot = ${quotePS(root)}
$Launcher = ${quotePS(launcher)}
$Lifecycle = ${quotePS(lifecycle)}
$Node = ${quotePS(process.execPath)}
` + script);
 const result = spawnSync('powershell.exe', ['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',entry], {cwd:root,encoding:'utf8',windowsHide:true,timeout:90000});
 await writeFile(path.join(directory,'result.json'),JSON.stringify({status:result.status,stdout:result.stdout,stderr:result.stderr,error:result.error?.message},null,2));
 assert.equal(result.status, 0, `Fixture ${directory}
${result.stderr}
${result.stdout}`);
 return result.stdout;
}

test('lifecycle assertions reject success for every actual pattern', { skip: process.platform !== 'win32' }, async () => {
 await powershellFixture(`Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($Lifecycle,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Parse error'}
$helper=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-Fails'},$true)
Invoke-Expression $helper.Extent.Text
$calls=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Assert-Fails'},$true))
$patterns=@($calls | ForEach-Object {$_.CommandElements[-1].Value} | Sort-Object -Unique)
foreach($pattern in $patterns){
 $rejected=$false
 try {Assert-Fails {'normal success'} $pattern} catch {$rejected=$true}
 if(-not $rejected){throw "Success accepted for $pattern"}
 $matching=if($pattern.StartsWith('Coordinator (?:')){'Coordinator bridge did not become ready'}else{($pattern -split '\\|')[0]}
 Assert-Fails {throw $matching} $pattern
 $rejected=$false
 try {Assert-Fails {throw 'unrelated zqx'} $pattern} catch {$rejected=$true}
 if(-not $rejected){throw 'Unrelated failure accepted'}
}
# Exact caller assertions must stop a successful injected launcher before PASS.
function Successful-Launcher {param($ProjectRoot,$MatrixPath,$ServerTemplate) 'success'}
$scriptPath='Successful-Launcher'; $fixture=$PSScriptRoot; $largeMatrix='uncreated.json'
foreach($pattern in @('distinct|duplicate|port','Selected scenario count|bounded maximum|maximum')){
 $call=@($calls|Where-Object {$_.CommandElements[-1].Value -eq $pattern})[0]
 $rejected=$false
 try {& ([scriptblock]::Create($call.Extent.Text + "\`nthrow 'UNREACHED_PASS'"))} catch {
  if($_.Exception.Message -eq 'UNREACHED_PASS'){throw 'Caller falsely reached PASS'}
  $rejected=$true
 }
 if(-not $rejected){throw 'Caller did not reject success'}
}
Write-Output "PASS all $($patterns.Count) assertion patterns and exact negative callers"
`);
});

test('cleanup shares discovery while preserving churn and identity validation', { skip: process.platform !== 'win32' }, async () => {
 await powershellFixture(`# Independent f127 fixture. Executes exact AST-extracted production functions.
# CIM, process handles, Stop-Process and Start-Sleep are in-memory boundaries.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$project = (Get-Location).Path
$sourcePath = $Launcher
$expectedHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Parse failure' }
$required = @('ConvertTo-ProcessCreationKey','Get-ProcessSnapshot','Test-ProcessIdentityMatch','Test-ChildCreationAfterParent','Add-ProcessTreeSnapshot','Get-TrackedResourceSnapshot','Add-TrackedProcessIdentity','Measure-RunnerResourcesUntilExit','Stop-TrackedProcessIds','Assert-TrackedProcessIdsGone')
$definitions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $required -contains $node.Name }, $true))
if ($definitions.Count -ne $required.Count) { throw 'Missing production function' }
foreach ($definition in $definitions) { Invoke-Expression $definition.Extent.Text }
$script:CleanupTimeoutSeconds = 30
$script:PairedState = $null
$script:PollMilliseconds = 250
$script:caseMode = 'empty'
$script:cimCalls = 0
$script:sleeps = 0
$script:stops = [Collections.Generic.List[int]]::new()
$script:live = @{}
$script:frames = @()
$script:waits = 0
$results = [Collections.Generic.List[object]]::new()
function Get-CimInstance {
    [CmdletBinding()] param([string]$ClassName)
    if ($ClassName -ne 'Win32_Process') { throw 'Unexpected CIM class' }
    $script:cimCalls += 1
    if ($script:caseMode -eq 'sampling') {
        $index = $script:cimCalls - 1
        if ($index -ge $script:frames.Count) { throw 'Unexpected extra sampling scan' }
        return $script:frames[$index]
    }
    if ($script:caseMode -eq 'reuse-during-discovery' -and $script:cimCalls -eq 2) { $script:live[42] = New-CimRow 42 999 '9000' }
    return @($script:live.Values)
}
function Stop-Process {
    [CmdletBinding()] param([int]$Id, [switch]$Force)
    if ($script:caseMode -ne 'mixed' -or $Id -ne 1001) { throw "Unexpected synthetic termination: $Id" }
    $script:stops.Add($Id)
    $script:live.Remove($Id)
}
function Start-Sleep { param([int]$Milliseconds) $script:sleeps += 1 }
function New-CimRow([int]$Id, [int]$Parent, [string]$Creation) {
    return [pscustomobject]@{ ProcessId=$Id; ParentProcessId=$Parent; CreationDate=$Creation; WorkingSetSize=1000L }
}
function New-HistoricalIdentities([int]$Count) {
    $items = [Collections.Generic.List[object]]::new()
    for ($index=1; $index -le $Count; $index++) { $items.Add((New-CimRow $index 999 ([string](1000+$index)))) }
    return ,$items
}
# Adapted original probe, with independent zero/one identity controls.
foreach ($count in @(0,1,100)) {
    $script:cimCalls=0; $script:sleeps=0; $script:stops.Clear(); $script:live=@{}; $script:caseMode='empty'
    $tracked = New-HistoricalIdentities $count
    Stop-TrackedProcessIds $tracked
    if ($script:cimCalls -ne 3 -or $script:stops.Count -ne 0) { throw 'Unexpected empty cleanup behavior' }
    $results.Add([pscustomobject]@{ case='dead-identity-scaling'; historicalIdentities=$count; cimCalls=$script:cimCalls; syntheticStops=$script:stops.Count; sleepCalls=$script:sleeps })
}
# New integrated sampler -> cleanup proof: three transient children, then every process exits.
$script:cimCalls=0; $script:sleeps=0; $script:stops.Clear(); $script:caseMode='sampling'; $script:waits=0
$root = New-CimRow 10 1 '100'
$script:frames = @(@($root,(New-CimRow 20 10 '200')), @($root,(New-CimRow 21 10 '201')), @($root,(New-CimRow 22 10 '202')), @())
$process = [pscustomobject]@{ Id=10; HasExited=$false; WorkingSet64=1000L }
$process | Add-Member -MemberType ScriptMethod -Name Refresh -Value {}
$process | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value { param($milliseconds) $script:waits += 1; $this.HasExited = ($script:waits -ge 3); return $this.HasExited }
$handle = @{ Process=$process; Identity=$root; InitialRssBytes=1000L }
$tracked = [Collections.Generic.List[object]]::new()
$peak = Measure-RunnerResourcesUntilExit $handle @($handle) $tracked ([DateTime]::UtcNow.AddSeconds(30))
$sampleCalls = $script:cimCalls
$trackedIds = @($tracked.ToArray() | ForEach-Object { $_.ProcessId })
if (($trackedIds -join ',') -ne '10,20,21,22' -or $sampleCalls -ne 4) { throw 'Sampler history or shared snapshots disproved' }
$script:caseMode='empty'; $script:cimCalls=0; $script:live=@{}
Stop-TrackedProcessIds $tracked
if ($script:cimCalls -ne 3 -or $script:stops.Count -ne 0) { throw 'Integrated cleanup count mismatch' }
$results.Add([pscustomobject]@{ case='sampler-to-cleanup'; samples=$sampleCalls; retainedIdentities=$trackedIds; peakProcesses=$peak.processCount; cleanupCimCalls=$script:cimCalls; syntheticStops=$script:stops.Count })
# New authentic-late-child and reused-PID control. Every row is synthetic.
$script:caseMode='mixed'; $script:cimCalls=0; $script:sleeps=0; $script:stops.Clear()
$script:live=@{ 1001=(New-CimRow 1001 42 '2000'); 50=(New-CimRow 50 999 '5000'); 1002=(New-CimRow 1002 50 '5001'); 9999=(New-CimRow 9999 9998 '6000') }
$tracked = New-HistoricalIdentities 100
Stop-TrackedProcessIds $tracked
$cleanupCalls = $script:cimCalls
if ($cleanupCalls -ne 3 -or ($script:stops.ToArray() -join ',') -ne '1001' -or $tracked.Count -ne 101 -or -not $script:live.ContainsKey(50) -or -not $script:live.ContainsKey(1002) -or -not $script:live.ContainsKey(9999)) { throw 'Identity-safety or late-child control failed' }
Assert-TrackedProcessIdsGone $tracked
$results.Add([pscustomobject]@{ case='late-child-and-reused-pid'; initialHistoricalIdentities=100; finalTrackedIdentities=$tracked.Count; cleanupCimCalls=$cleanupCalls; assertionCimCalls=($script:cimCalls-$cleanupCalls); syntheticStoppedIds=@($script:stops.ToArray()); preservedSyntheticIds=@($script:live.Keys | Sort-Object) })
# Existing optional snapshot parameter preserves the same expansion result with one CIM call.
# This is a helper control, not an implemented cleanup optimization or timing benchmark.
$script:live[1001]=New-CimRow 1001 42 '2000'; $script:cimCalls=0; $script:stops.Clear()
$tracked = New-HistoricalIdentities 100
$shared = Get-ProcessSnapshot
foreach ($identity in @($tracked.ToArray())) { Add-ProcessTreeSnapshot $tracked $identity $shared }
if ($script:cimCalls -ne 1 -or $tracked.Count -ne 101 -or @($tracked.ToArray() | Where-Object { $_.ProcessId -eq 1002 }).Count -ne 0) { throw 'Shared snapshot expansion control failed' }
$results.Add([pscustomobject]@{ case='shared-snapshot-expansion-control'; initialHistoricalIdentities=100; finalTrackedIdentities=$tracked.Count; cimCalls=$script:cimCalls; syntheticStops=$script:stops.Count })
$script:caseMode='reuse-during-discovery'; $script:cimCalls=0; $script:stops.Clear()
$script:live=@{42=(New-CimRow 42 999 '1042')}
$tracked=New-HistoricalIdentities 100
Stop-TrackedProcessIds $tracked
if($script:stops.Count -ne 0 -or $script:live[42].CreationDate -ne '9000'){throw 'Fresh identity validation failed'}
Write-Output 'PASS fresh identity validation rejects a PID reused after discovery'
[pscustomobject]@{ passed=$true; sourceSha256=$expectedHash; productionFunctions=$required; cases=@($results.ToArray()); cleanup='No external process was started or terminated. All rows, handles, sleeps, and process operations were in-memory. Only this worker evidence files were written.' } | ConvertTo-Json -Depth 8
`);
});

test('standalone resource lifecycle initializes state in a fresh session', { skip: process.platform !== 'win32' }, async () => {
 await powershellFixture(`Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$wrapperSource=Get-Content -Raw -LiteralPath $Launcher
$scriptPath=$Launcher
$ast=[Management.Automation.Language.Parser]::ParseFile($Lifecycle,[ref]$tokens,[ref]$errors)
foreach($name in @('Import-WrapperFunction','Test-FastExitResourceSampling')){
 $function=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
 Invoke-Expression $function.Extent.Text
}
if(Get-Variable PairedState -Scope Script -ErrorAction SilentlyContinue){throw 'Expected fresh scope'}
Test-FastExitResourceSampling $PSScriptRoot
Write-Output 'PASS full fast-exit resource test with fresh initialized scope and owned descendant cleanup'
`);
});

test('real active trial resource peaks survive into cleanup', { skip: process.platform !== 'win32' }, async () => {
 await powershellFixture(`$ErrorActionPreference = 'Stop'
$ownDirectory = $PSScriptRoot
. $Launcher -ProjectRoot $SourceRoot -FunctionsOnly

# Execute unchanged production phase assignments, not a rewritten lifecycle.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($Launcher, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Launcher parse failed' }
foreach ($name in @('trialPhase', 'runnerFinishPhase')) {
    $assignments = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -eq $name}, $true))
    if ($assignments.Count -ne 1) { throw "Expected one $name assignment" }
    Invoke-Expression $assignments[0].Extent.Text
}
# The actual writer and budget calculation preserve production file handoffs.
$workerAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $SourceRoot 'scripts/paired-phase-worker.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Worker parse failed' }
foreach ($name in @('Get-PairedRemaining', 'Write-PairedRunnerRequest')) {
    $definition = $workerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true)
    Invoke-Expression $definition.Extent.Text
}
$script:realSnapshot = (Get-Item Function:Get-ProcessSnapshot).ScriptBlock
$script:trace = [Collections.Generic.List[object]]::new()
function Receive-PairedPhase([string]$Expected) {
    # Only supervisor admission is injected; trial and cleanup messages use disk.
    $script:PairedState.phase = $Expected
    $script:PairedState.remainingMs = 20000
    $script:PairedState.clock.Restart()
    $script:trace.Add([ordered]@{ event='receive'; phase=$Expected })
}
function Send-PairedEvent([string]$Kind, $Value) {
    $script:trace.Add([ordered]@{event='event'; kind=$Kind})
}
function Get-ProcessSnapshot {
    $snapshot = & $script:realSnapshot
    $phase = if ($null -eq $script:PairedState) { 'single-active' } else { $script:PairedState.phase }
    $activePath = Join-Path $script:caseDirectory 'active-resource.json'
    $active = if (Test-Path -LiteralPath $activePath) { Get-Content -Raw -LiteralPath $activePath | ConvertFrom-Json } else { $null }
    $childPresent = $null -ne $active -and $snapshot.ContainsKey([int]$active.pid)
    $script:trace.Add([ordered]@{ event='sample'; phase=$phase; childPresent=$childPresent })
    # CIM enumeration can start before the child commits its allocation. Release
    # the fixture only after this same snapshot contains the allocated memory.
    if ($childPresent -and [long]$snapshot[[int]$active.pid].WorkingSetSize -ge [long]$active.allocatedBytes) {
        [IO.File]::WriteAllText((Join-Path $script:caseDirectory 'sample-observed.json'), '{}')
    }
    return $snapshot
}

$fixture = Join-Path $ownDirectory 'fixture-runner.mjs'
$results = [Collections.Generic.List[object]]::new()
foreach ($mode in @('paired-transient', 'paired-persistent', 'single')) {
    $script:caseDirectory = Join-Path $ownDirectory ($mode + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    $null = New-Item -ItemType Directory -Path $script:caseDirectory
    $script:trace.Clear()
    $script:PairedState = if ($mode -eq 'single') { $null } else {
        @{phase='startup'; remainingMs=20000; clock=[Diagnostics.Stopwatch]::StartNew(); channel=$script:caseDirectory;
          resources=[Collections.Generic.List[object]]::new(); drainFailed=$false}
    }
    $Scenario = [pscustomobject]@{timeoutMs=20000}
    $scenarioId = 'fixture'
    $scenarioDirectory = $script:caseDirectory
    $serverHandle = $null; $coordinatorHandle = $null; $runnerHandle = $null
    $processIds = [Collections.Generic.List[object]]::new()
    $peakRssBytes = 0L; $peakProcessCount = 0; $trialResourcePeak = $null
    try {
        $arguments = (Quote-Argument $fixture) + ' ' + $mode + ' ' + (Quote-Argument $script:caseDirectory) + ' ' + (Quote-Argument $SourceRoot)
        $runnerHandle = Start-RedirectedProcess $node $arguments $SourceRoot (Join-Path $script:caseDirectory 'stdout.log') (Join-Path $script:caseDirectory 'stderr.log') @{}
        Wait-Condition { Test-Path -LiteralPath (Join-Path $script:caseDirectory 'runner-ready.json') } 20 'Fixture runner did not become ready'
        . $trialPhase
        . $runnerFinishPhase
        if ($runnerExit -ne 0) { throw "Fixture runner exited $runnerExit" }
        $active = Get-Content -Raw -LiteralPath (Join-Path $script:caseDirectory 'active-resource.json') | ConvertFrom-Json
        $childTracked = @($processIds.ToArray() | Where-Object { $_.ProcessId -eq $active.pid }).Count -gt 0
        $samples = @($script:trace.ToArray() | Where-Object { $_.event -eq 'sample' })
        $result = [ordered]@{mode=$mode; runnerExit=$runnerExit; childRssDuringTrial=$active.rssBytes;
            allocatedBytes=$active.allocatedBytes; childExitedBeforeTrialEnd=$active.childExitedBeforeTrialEnd;
            reportedPeakRssBytes=$peakRssBytes; reportedPeakProcessCount=$peakProcessCount;
            childTracked=$childTracked; sampleCount=$samples.Count; trace=@($script:trace.ToArray());
            fixtureDirectory=[IO.Path]::GetFileName($script:caseDirectory)}
        if (-not $childTracked -or $peakProcessCount -lt 2 -or $peakRssBytes -lt $active.allocatedBytes) { throw 'Live child resources omitted' }
        if ($mode -eq 'paired-transient' -and -not $active.childExitedBeforeTrialEnd) { throw 'Transient fixture did not end before cleanup' }
        if ($mode -ne 'single' -and -not @($samples | Where-Object {$_.phase -eq 'trial'}).Count) { throw 'No active trial samples' }
        Assert-TrackedProcessIdsGone $processIds
        $results.Add($result)
    } finally {
        # This handle is created here. The fixture also owns a 25-second watchdog.
        if ($null -ne $runnerHandle -and -not $runnerHandle.Process.HasExited) {
            $runnerHandle.Process.Kill()
            $null = $runnerHandle.Process.WaitForExit(5000)
        }
    }
}
$results | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $ownDirectory 'runtime-results.json')
$results | ConvertTo-Json -Depth 10
Write-Output 'PASS: real process/CIM active peaks survive cleanup; persistent and single controls pass.'
`, { 'fixture-runner.mjs': `import { spawn } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [role, directory, sourceRoot] = process.argv.slice(2);
const timeout = setTimeout(() => process.exit(91), 25_000);
async function waitForSample() {
  const deadline = Date.now() + 15_000;
  for (;;) {
    try { await readFile(path.join(directory, 'sample-observed.json')); return; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (Date.now() >= deadline) throw new Error('Fixture sampler did not observe owned child allocation');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
if (role === 'child') {
  const allocation = Buffer.alloc(96 * 1024 * 1024, 0x5a);
  process.stdout.write(JSON.stringify({ pid: process.pid, parentPid: process.ppid,
    rssBytes: process.memoryUsage().rss, allocatedBytes: allocation.byteLength }) + '\\n');
  process.stdin.once('data', () => { clearTimeout(timeout); process.exit(allocation[0] === 0x5a ? 0 : 92); });
} else {
  let child;
  try {
    const { runnerPhaseChannel } = await import(pathToFileURL(path.join(sourceRoot, 'coordinator/src/benchmark/paired-runner-channel.mjs')));
    const channel = role === 'single' ? null : runnerPhaseChannel(directory);
    if (channel) await channel.ready();
    child = spawn(process.execPath, [process.argv[1], 'child', directory, sourceRoot], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'inherit'], cwd: sourceRoot,
    });
    const childExit = once(child, 'exit');
    let received = '';
    for await (const chunk of child.stdout) {
      received += chunk.toString();
      if (received.includes('\\n')) break;
    }
    const memory = JSON.parse(received.trim());
    const active = { ...memory, runnerPid: process.pid, role,
      observedAt: new Date().toISOString(), phase: 'trial', childExitedBeforeTrialEnd: false };
    await writeFile(path.join(directory, 'active-resource.json'), JSON.stringify(active));
    if (role === 'paired-transient') {
      await waitForSample();
      child.stdin.end('exit\\n');
      const [exit] = await childExit;
      if (exit !== 0) throw new Error(\`Fixture child exit \${exit}\`);
      active.childExitedBeforeTrialEnd = true;
      await writeFile(path.join(directory, 'active-resource.json'), JSON.stringify(active));
    }
    if (channel) await channel.cleanup({ scenarioId: 'fixture', status: 'PASSED' });
    else await writeFile(path.join(directory, 'runner-ready.json'), '{}');
    if (role !== 'paired-transient') {
      await waitForSample();
      child.stdin.end('exit\\n');
      const [exit] = await childExit;
      if (exit !== 0) throw new Error(\`Fixture child exit \${exit}\`);
    }
    await writeFile(path.join(directory, 'report.json'), JSON.stringify({ scenarioId: 'fixture', status: 'PASSED' }));
    clearTimeout(timeout);
  } catch (error) {
    if (child && child.exitCode === null) child.kill();
    console.error(error.stack);
    clearTimeout(timeout);
    process.exitCode = 1;
  }
}
` });
});
