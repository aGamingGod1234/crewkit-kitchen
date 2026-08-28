[CmdletBinding()]
param([string] $ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$root = [IO.Path]::GetFullPath($ProjectRoot)
& (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $env:TEMP -TestProcessClassification
$target = Join-Path $env:TEMP ('arena-update-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$mods = Join-Path $target 'mods'
$runtimeCoordinator = Join-Path $target 'arena-agents-runtime\coordinator'
$runtime = Join-Path $target 'arena-agents-runtime\runtime'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
try {
    . (Join-Path $root 'scripts\distribution-runtime.ps1')
    $initialSource = Join-Path $target 'initial-source'
    Copy-Item -LiteralPath (Join-Path $root 'coordinator') -Destination (Join-Path $initialSource 'coordinator') -Recurse -Force
    Add-Content -LiteralPath (Join-Path $initialSource 'coordinator\src\dynamic-main.mjs') -Value '// initial verified generation'
    $initial = Install-ArenaCoordinatorRuntime -SourceRoot $initialSource -InstalledPackageRoot (Join-Path $target 'arena-agents-runtime')
    if (-not (Confirm-ArenaCoordinatorGeneration -InstalledPackageRoot (Join-Path $target 'arena-agents-runtime') -GenerationId $initial.GenerationId)) { throw 'Initial coordinator generation was not promoted.' }

    Copy-Item -LiteralPath (Join-Path $root 'build\libs\arena-agents-0.1.0.jar') -Destination (Join-Path $mods 'arena-agents-0.0.1.jar')
    Set-Content -LiteralPath (Join-Path $mods 'arena-agents-voice-0.1.0.jar') -Value 'stale voice addon'
    Set-Content -LiteralPath (Join-Path $mods 'unrelated.jar') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $mods 'fabric-api-0.150.0+26.1.2.jar') -Value 'api'
    Set-Content -LiteralPath (Join-Path $mods 'fabric-carpet-26.1+v260402.jar') -Value 'carpet'
    Set-Content -LiteralPath (Join-Path $runtimeCoordinator 'stale.log') -Value 'remove'
    Set-Content -LiteralPath (Join-Path $target 'arena-agents-runtime\runtime\bridge-secret.txt') -Value ('a' * 32)
    Set-Content -LiteralPath (Join-Path $runtime 'dynamic-agents.json') -Value '{"external":"preserve exactly"}' -NoNewline
    Set-Content -LiteralPath (Join-Path $runtime 'fish-audio.key') -Value 'fish-key' -NoNewline

    $secretPath = Join-Path $runtime 'bridge-secret.txt'
    $externalHashes = @{}
    foreach ($external in @($secretPath, (Join-Path $runtime 'dynamic-agents.json'), (Join-Path $runtime 'fish-audio.key'))) {
        $externalHashes[$external] = (Get-FileHash $external -Algorithm SHA256).Hash
    }

    foreach ($failurePoint in @('AfterJarsBackup', 'AfterBackup', 'AfterJarSwap', 'AfterCoordinatorSwap')) {
        $before = Get-ChildItem -LiteralPath $mods -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-FileHash $_.FullName).Hash }
        $runtimeBefore = Get-ChildItem -LiteralPath (Join-Path $target 'arena-agents-runtime') -Recurse -File | Sort-Object FullName | ForEach-Object { $_.FullName.Substring((Join-Path $target 'arena-agents-runtime').Length + 1) + ':' + (Get-FileHash $_.FullName).Hash }
        try { & (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $target -FailurePoint $failurePoint; throw "Failure injection did not occur: $failurePoint" } catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw } }
        if (@(Get-ChildItem -LiteralPath $target -Directory -Filter '.arena-agents-backup-*').Count -eq 0) { throw "No preserved backup after failure: $failurePoint" }
        $after = Get-ChildItem -LiteralPath $mods -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-FileHash $_.FullName).Hash }
        if (@(Compare-Object $before $after).Count -ne 0) { throw "Forced failure did not restore prior mod state: $failurePoint" }
        $runtimeAfter = Get-ChildItem -LiteralPath (Join-Path $target 'arena-agents-runtime') -Recurse -File | Sort-Object FullName | ForEach-Object { $_.FullName.Substring((Join-Path $target 'arena-agents-runtime').Length + 1) + ':' + (Get-FileHash $_.FullName).Hash }
        if (@(Compare-Object $runtimeBefore $runtimeAfter).Count -ne 0) { throw "Forced failure did not restore runtime state: $failurePoint" }
        foreach ($external in $externalHashes.Keys) {
            if ((Get-FileHash $external -Algorithm SHA256).Hash -ne $externalHashes[$external]) { throw "External runtime state changed after failure: $failurePoint" }
        }
    }

    & (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $target
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-0.1.0.jar'))) { throw 'Updated jar missing.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-voice-0.1.0.jar'))) { throw 'Updated voice addon missing.' }
    if (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-0.0.1.jar')) { throw 'Stale Arena jar remains.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar'))) { throw 'Unrelated mod was changed.' }
    if (Test-Path -LiteralPath (Join-Path $runtimeCoordinator 'stale.log')) { throw 'Stale coordinator state remains.' }
    if (-not (Test-Path -LiteralPath (Join-Path $target 'arena-agents-runtime\coordinator.last-known-good\stale.log'))) { throw 'The prior verified coordinator was not retained as last-known-good.' }

    $installedHash = (Get-FileHash (Join-Path $mods 'arena-agents-0.1.0.jar') -Algorithm SHA256).Hash
    $sourceHash = (Get-FileHash (Join-Path $root 'build\libs\arena-agents-0.1.0.jar') -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'Installed jar hash differs from source jar.' }
    $installedVoiceHash = (Get-FileHash (Join-Path $mods 'arena-agents-voice-0.1.0.jar') -Algorithm SHA256).Hash
    $sourceVoiceHash = (Get-FileHash (Join-Path $root 'voice-addon\build\libs\arena-agents-voice-0.1.0.jar') -Algorithm SHA256).Hash
    if ($installedVoiceHash -ne $sourceVoiceHash) { throw 'Installed voice addon hash differs from source jar.' }

    foreach ($external in $externalHashes.Keys) {
        if ((Get-FileHash $external -Algorithm SHA256).Hash -ne $externalHashes[$external]) { throw 'Successful update changed external runtime state.' }
    }
    Write-Host 'Normal profile updater temp end-to-end test passed.'
} finally {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
