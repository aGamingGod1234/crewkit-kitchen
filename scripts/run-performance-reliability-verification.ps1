[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [ValidateRange(1, 500)]
    [int] $SoakRuns = 50
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$AutomatedVerifier = Join-Path $Project 'scripts\run-automated-verification.ps1'
$Coordinator = Join-Path $Project 'coordinator'
$SoakTest = Join-Path $Coordinator 'test\eight-agent-soak.test.mjs'

foreach ($required in @($AutomatedVerifier, $SoakTest)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing verification prerequisite: $required" }
}

$startedAt = [Diagnostics.Stopwatch]::StartNew()
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $verificationOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $AutomatedVerifier -ProjectRoot $Project 2>&1
    $verificationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($verificationExitCode -ne 0) {
    $verificationOutput | ForEach-Object { Write-Host $_ }
    throw "Automated verification failed with code $verificationExitCode"
}
$verificationOutput |
    Where-Object { $_ -match 'PASS: \d+ protocol and bridge assertions|# tests \d+|Automated Java' } |
    ForEach-Object { Write-Host $_ }

Push-Location $Coordinator
try {
    for ($iteration = 1; $iteration -le $SoakRuns; $iteration++) {
        $soakOutput = & node --test $SoakTest 2>&1
        if ($LASTEXITCODE -ne 0) {
            $soakOutput | ForEach-Object { Write-Host $_ }
            throw "Eight-agent soak failed on iteration $iteration"
        }
    }
} finally {
    Pop-Location
}

$startedAt.Stop()
Write-Host ("Performance and reliability verification passed: full verifier plus {0}/{0} soak runs in {1:N1}s." -f $SoakRuns, $startedAt.Elapsed.TotalSeconds)
