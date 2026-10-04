import { readFile } from 'node:fs/promises';
import { powershellFixture } from './g26-powershell-fixture.mjs';
export async function verifyResourcePeak(mode) {
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
foreach ($name in @('Get-PairedSystemNow', 'Get-PairedRemaining', 'Write-PairedRunnerRequest')) {
    $definition = $workerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true)
    Invoke-Expression $definition.Extent.Text
}
$script:realSnapshot = (Get-Item Function:Get-ProcessSnapshot).ScriptBlock
$script:trace = [Collections.Generic.List[object]]::new()
function Receive-PairedPhase([string]$Expected) {
    # Only supervisor admission is injected; trial and cleanup messages use disk.
    $script:PairedState.phase = $Expected
    $script:PairedState.cutoffMs = (Get-PairedSystemNow) + 20000
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
    # Release paired fixtures only after an actual trial snapshot contains the
    # allocation; startup or cleanup samples cannot acknowledge the trial.
    if (($phase -eq 'trial' -or $phase -eq 'single-active') -and $childPresent -and [long]$snapshot[[int]$active.pid].WorkingSetSize -ge [long]$active.allocatedBytes) {
        [IO.File]::WriteAllText((Join-Path $script:caseDirectory 'sample-observed.json'), '{}')
    }
    return $snapshot
}

$fixture = Join-Path $ownDirectory 'fixture-runner.mjs'
$results = [Collections.Generic.List[object]]::new()
foreach ($mode in @('${mode}')) {
    $script:caseDirectory = Join-Path $ownDirectory ($mode + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    $null = New-Item -ItemType Directory -Path $script:caseDirectory
    $script:trace.Clear()
    $script:PairedState = if ($mode -eq 'single') { $null } else {
        @{phase='startup'; cutoffMs=((Get-PairedSystemNow)+20000); channel=$script:caseDirectory;
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
        # Retain child diagnostics on a fixture failure as well as on success.
        $script:PairedState = $null
        Complete-RedirectedProcess $runnerHandle
    }
}
$results | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $ownDirectory 'runtime-results.json')
$results | ConvertTo-Json -Depth 10
Write-Output 'PASS: real process/CIM active peaks survive cleanup; persistent and single controls pass.'
`, { 'fixture-runner.mjs': await readFile(new URL('./g26-inert-resource.mjs', import.meta.url), 'utf8') });
}
