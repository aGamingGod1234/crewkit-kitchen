[CmdletBinding()]
param([string] $ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$root = [IO.Path]::GetFullPath($ProjectRoot)
& (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $env:TEMP -TestProcessClassification
$target = Join-Path $env:TEMP ('arena-normal-profile-test-' + [guid]::NewGuid().ToString('N'))
$mods = Join-Path $target 'mods'
$runtimeCoordinator = Join-Path $target 'arena-agents-runtime\coordinator'
$runtime = Join-Path $target 'arena-agents-runtime\runtime'
New-Item -ItemType Directory -Force -Path $mods, $runtimeCoordinator, $runtime | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $root 'build\libs\arena-agents-0.1.0.jar') -Destination (Join-Path $mods 'arena-agents-0.0.1.jar')
    Set-Content -LiteralPath (Join-Path $mods 'arena-agents-voice-0.1.0.jar') -Value 'stale voice addon'
    Set-Content -LiteralPath (Join-Path $mods 'unrelated.jar') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $mods 'fabric-api-0.150.0+26.1.2.jar') -Value 'api'
    Set-Content -LiteralPath (Join-Path $mods 'fabric-carpet-26.1+v260402.jar') -Value 'carpet'
    Set-Content -LiteralPath (Join-Path $runtimeCoordinator 'stale.log') -Value 'remove'
    Set-Content -LiteralPath (Join-Path $target 'arena-agents-runtime\runtime\bridge-secret.txt') -Value ('a' * 32)

    & (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $target
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-0.1.0.jar'))) { throw 'Updated jar missing.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-voice-0.1.0.jar'))) { throw 'Updated voice addon missing.' }
    if (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-0.0.1.jar')) { throw 'Stale Arena jar remains.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar'))) { throw 'Unrelated mod was changed.' }
    if (Test-Path -LiteralPath (Join-Path $runtimeCoordinator 'stale.log')) { throw 'Stale coordinator state remains.' }

    $installedHash = (Get-FileHash (Join-Path $mods 'arena-agents-0.1.0.jar') -Algorithm SHA256).Hash
    $sourceHash = (Get-FileHash (Join-Path $root 'build\libs\arena-agents-0.1.0.jar') -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'Installed jar hash differs from source jar.' }
    $installedVoiceHash = (Get-FileHash (Join-Path $mods 'arena-agents-voice-0.1.0.jar') -Algorithm SHA256).Hash
    $sourceVoiceHash = (Get-FileHash (Join-Path $root 'voice-addon\build\libs\arena-agents-voice-0.1.0.jar') -Algorithm SHA256).Hash
    if ($installedVoiceHash -ne $sourceVoiceHash) { throw 'Installed voice addon hash differs from source jar.' }

    $secretPath = Join-Path $target 'arena-agents-runtime\runtime\bridge-secret.txt'
    $secretBefore = (Get-FileHash $secretPath -Algorithm SHA256).Hash

    foreach ($failurePoint in @('AfterJarsBackup', 'AfterBackup', 'AfterJarSwap', 'AfterCoordinatorSwap')) {
        $before = Get-ChildItem -LiteralPath $mods -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-FileHash $_.FullName).Hash }
        $runtimeBefore = Get-ChildItem -LiteralPath (Join-Path $target 'arena-agents-runtime') -Recurse -File | Sort-Object FullName | ForEach-Object { $_.FullName.Substring((Join-Path $target 'arena-agents-runtime').Length + 1) + ':' + (Get-FileHash $_.FullName).Hash }
        try { & (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $target -FailurePoint $failurePoint; throw "Failure injection did not occur: $failurePoint" } catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw } }
        if (@(Get-ChildItem -LiteralPath $target -Directory -Filter '.arena-agents-backup-*').Count -eq 0) { throw "No preserved backup after failure: $failurePoint" }
        $after = Get-ChildItem -LiteralPath $mods -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-FileHash $_.FullName).Hash }
        if (@(Compare-Object $before $after).Count -ne 0) { throw "Forced failure did not restore prior mod state: $failurePoint" }
        $runtimeAfter = Get-ChildItem -LiteralPath (Join-Path $target 'arena-agents-runtime') -Recurse -File | Sort-Object FullName | ForEach-Object { $_.FullName.Substring((Join-Path $target 'arena-agents-runtime').Length + 1) + ':' + (Get-FileHash $_.FullName).Hash }
        if (@(Compare-Object $runtimeBefore $runtimeAfter).Count -ne 0) { throw "Forced failure did not restore runtime state: $failurePoint" }
        if ((Get-FileHash $secretPath -Algorithm SHA256).Hash -ne $secretBefore) { throw "Secret changed after failure: $failurePoint" }
        if (-not (Test-Path -LiteralPath (Join-Path $target 'arena-agents-runtime\coordinator\src\dynamic-main.mjs'))) { throw "Forced failure did not restore coordinator state: $failurePoint" }
    }
    Write-Host 'Normal profile updater temp end-to-end test passed.'
} finally {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
