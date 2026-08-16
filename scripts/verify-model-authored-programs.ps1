[CmdletBinding()]
param(
    [string] $ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Coordinator = Join-Path $Project 'coordinator'
$JavaHome = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Gradle = Join-Path $Project 'gradlew.bat'

foreach ($required in @(
    (Join-Path $Coordinator 'package.json'),
    $Java,
    $Gradle
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing verification prerequisite: $required"
    }
}

function Invoke-CheckedNodeTests {
    param([string[]] $TestFiles)

    Push-Location $Coordinator
    try {
        $output = @(& node --test @TestFiles 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Model-authored Node verification failed with code $exitCode"
    }
    return $output
}

$nodeTests = @(
    'test/arena-script-facts.test.mjs',
    'test/arena-script-interpreter.test.mjs',
    'test/arena-script-parser.test.mjs',
    'test/arena-script-program-engine.test.mjs',
    'test/program-runtime-manager.test.mjs',
    'test/protocol-v2.test.mjs',
    'test/model-authored-programs-e2e.test.mjs'
)
$nodeOutput = Invoke-CheckedNodeTests -TestFiles $nodeTests

$summaryMatch = $null
foreach ($line in $nodeOutput) {
    $candidate = [regex]::Match([string] $line, 'TASK10_E2E_SUMMARY\s+(\{.*\})')
    if ($candidate.Success) { $summaryMatch = $candidate }
}
if ($null -eq $summaryMatch) { throw 'Missing TASK10_E2E_SUMMARY from model-authored E2E output.' }

try {
    $summary = $summaryMatch.Groups[1].Value | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw "Invalid TASK10_E2E_SUMMARY JSON: $($_.Exception.Message)"
}
if ($summary.timing.benchmarkRequired -ne $true) {
    throw 'The E2E summary did not require a real benchmark.'
}

Write-Host ("TASK10 scenarios: {0}/{0} passed." -f [int] $summary.passed)
Write-Host ("TASK10 segmented timing basis: {0} (synthetic fixture timing; not live measured latency)." -f $summary.timing.basis)
foreach ($segment in @($summary.timing.syntheticLocal) + @($summary.timing.syntheticProvider)) {
    Write-Host ("TASK10 latency [synthetic fixture, not measured] {0}: count={1}, p50Ms={2}, p95Ms={3}" -f $segment.operation, $segment.count, $segment.p50Ms, $segment.p95Ms)
}

$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location $Project
try {
    & $Gradle verifyCore --no-daemon --console=plain
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
if ($gradleExitCode -ne 0) {
    throw "Java verifyCore failed with code $gradleExitCode"
}

Write-Host 'Model-authored ArenaScript, protocol, manager, E2E, and Java verifyCore gate passed.'
