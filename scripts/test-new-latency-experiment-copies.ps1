[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-FixtureGit {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Repository,
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & git -C $Repository @Arguments 2>&1
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Fixture git command failed: git $($Arguments -join ' ')"
    }
    return @($output)
}

function Assert-Fixture {
    param(
        [Parameter(Mandatory = $true)]
        [bool] $Condition,
        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    if (-not $Condition) { throw $Message }
}

$scriptRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('latency-experiment-copy-fixture-' + [guid]::NewGuid().ToString('N'))
$projectRoot = Join-Path $fixtureRoot 'project'
$destinationRoot = Join-Path $fixtureRoot 'destination'
$secretPath = Join-Path $projectRoot 'runtime\bridge-secret.txt'
$indexPath = $null
$worktreePaths = @()

try {
    New-Item -ItemType Directory -Force -Path $projectRoot, $destinationRoot | Out-Null
    Invoke-FixtureGit -Repository $fixtureRoot -Arguments @('init', '--quiet', $projectRoot) | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('config', 'user.name', 'Latency Fixture') | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('config', 'user.email', 'latency-fixture@example.invalid') | Out-Null

    Set-Content -LiteralPath (Join-Path $projectRoot '.gitignore') -Value "runtime/`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'tracked.txt') -Value "before edit`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'staged.txt') -Value "before stage`n"
    $excludedPaths = @(
        '.env',
        '.env.local',
        'credentials.json',
        'credentials\service-account.json',
        '.gradle\cache.txt',
        'build\artifact.txt',
        'run\pid.txt',
        'logs\server.log',
        '.playwright-cli\trace.json',
        'output\result.json',
        '.worktrees\old-snapshot.txt'
    )
    foreach ($relativePath in $excludedPaths) {
        $absolutePath = Join-Path $projectRoot $relativePath
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $absolutePath) | Out-Null
        Set-Content -LiteralPath $absolutePath -Value ('excluded-' + $relativePath)
    }
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('add', '.gitignore', 'tracked.txt', 'staged.txt') | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('add', '--', '.env', '.env.local', 'credentials.json', 'credentials/service-account.json', '.gradle/cache.txt', 'build/artifact.txt', 'run/pid.txt', 'logs/server.log', '.playwright-cli/trace.json', 'output/result.json', '.worktrees/old-snapshot.txt') | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('commit', '--quiet', '-m', 'fixture baseline') | Out-Null

    Set-Content -LiteralPath (Join-Path $projectRoot 'tracked.txt') -Value "tracked working edit`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'staged.txt') -Value "staged working edit`n"
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('add', 'staged.txt') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'src\voice-addon') | Out-Null
    Set-Content -LiteralPath (Join-Path $projectRoot 'src\voice-addon\untracked-source.mjs') -Value "export const fixtureSource = true;`n"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $secretPath) | Out-Null
    Set-Content -LiteralPath $secretPath -Value ('fixture-secret-' + [guid]::NewGuid().ToString('N'))

    $indexPath = Join-Path $projectRoot '.git\index'
    $indexBefore = [Convert]::ToBase64String([IO.File]::ReadAllBytes($indexPath))
    $secretBefore = (Get-Item -LiteralPath $secretPath).Length

    $creationScript = Join-Path $scriptRoot 'scripts\new-latency-experiment-copies.ps1'
    $result = & $creationScript -ProjectRoot $projectRoot -DestinationRoot $destinationRoot
    if ($LASTEXITCODE -ne 0) { throw 'Copy creation script returned a non-zero exit code.' }
    if ($null -eq $result) { throw 'Copy creation script returned no result.' }

    $baselinePath = [IO.Path]::GetFullPath([string]$result.BaselinePath)
    $optimizedPath = [IO.Path]::GetFullPath([string]$result.OptimizedPath)
    $worktreePaths = @($baselinePath, $optimizedPath)
    foreach ($copyPath in $worktreePaths) {
        Assert-Fixture (Test-Path -LiteralPath $copyPath -PathType Container) "Snapshot worktree is missing: $copyPath"
        Assert-Fixture ((Get-Content -Raw -LiteralPath (Join-Path $copyPath 'tracked.txt')).TrimEnd([char[]] @("`r", "`n")) -eq 'tracked working edit') 'Tracked edit was not copied.'
        Assert-Fixture ((Get-Content -Raw -LiteralPath (Join-Path $copyPath 'staged.txt')).TrimEnd([char[]] @("`r", "`n")) -eq 'staged working edit') 'Pre-staged file content was not copied.'
        Assert-Fixture (Test-Path -LiteralPath (Join-Path $copyPath 'src\voice-addon\untracked-source.mjs') -PathType Leaf) 'Untracked source file was not copied.'
        Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $copyPath 'runtime\bridge-secret.txt'))) 'Ignored runtime secret was copied.'
        foreach ($relativePath in $excludedPaths) {
            Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $copyPath $relativePath))) "Excluded path was copied: $relativePath"
        }
    }

    $indexAfter = [Convert]::ToBase64String([IO.File]::ReadAllBytes($indexPath))
    Assert-Fixture ($indexBefore -eq $indexAfter) 'Original Git index bytes changed.'
    Assert-Fixture ((Get-Item -LiteralPath $secretPath).Length -eq $secretBefore) 'Original runtime secret metadata changed.'
    Assert-Fixture ([string]$result.BaselineSourceHash -eq [string]$result.OptimizedSourceHash) 'Snapshot source hashes do not match.'
    Assert-Fixture (-not [string]::IsNullOrWhiteSpace([string]$result.SnapshotId)) 'Snapshot ID is missing.'

    Write-Host 'Latency experiment copy fixture test passed.'
}
finally {
    foreach ($worktreePath in $worktreePaths) {
        if (Test-Path -LiteralPath $worktreePath) {
            & git -C $projectRoot worktree remove --force -- $worktreePath *> $null
        }
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
