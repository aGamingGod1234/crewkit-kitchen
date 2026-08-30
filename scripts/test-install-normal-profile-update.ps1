[CmdletBinding()]
param([string] $ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$root = [IO.Path]::GetFullPath($ProjectRoot)
$properties = [ordered]@{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
	$trimmed = $line.Trim()
	if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
	$separator = $trimmed.IndexOf('=')
	if ($separator -gt 0) { $properties[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim() }
}
$modJarName = "arena-agents-$($properties.mod_version).jar"
$fabricApiJarName = "fabric-api-$($properties.fabric_api_version).jar"
$carpetJarName = "fabric-carpet-$($properties.carpet_version).jar"
& (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $env:TEMP -TestProcessClassification
$target = Join-Path $env:TEMP ('arena-normal-profile-test-' + [guid]::NewGuid().ToString('N'))
$mods = Join-Path $target 'mods'
$runtimeCoordinator = Join-Path $target 'arena-agents-runtime\coordinator'
$runtime = Join-Path $target 'arena-agents-runtime\runtime'
New-Item -ItemType Directory -Force -Path $mods, $runtimeCoordinator, $runtime | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $root ("build\libs\" + $modJarName)) -Destination (Join-Path $mods 'arena-agents-old-unparseable.jar')
    Set-Content -LiteralPath (Join-Path $mods 'unrelated.jar') -Value 'keep'
	$voiceAddon = Join-Path $mods 'arena-agents-voice-0.1.0.jar'
	Set-Content -LiteralPath $voiceAddon -Value 'optional voice addon'
	$voiceAddonHash = (Get-FileHash -LiteralPath $voiceAddon -Algorithm SHA256).Hash
    Set-Content -LiteralPath (Join-Path $mods $fabricApiJarName) -Value 'api'
    Set-Content -LiteralPath (Join-Path $mods $carpetJarName) -Value 'carpet'
    Set-Content -LiteralPath (Join-Path $runtimeCoordinator 'stale.log') -Value 'remove'
    Set-Content -LiteralPath (Join-Path $target 'arena-agents-runtime\runtime\bridge-secret.txt') -Value ('a' * 32)

    & (Join-Path $root 'scripts\install-normal-profile-update.ps1') -ProjectRoot $root -GameDirectory $target
    if (-not (Test-Path -LiteralPath (Join-Path $mods $modJarName))) { throw 'Updated jar missing.' }
    if (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-old-unparseable.jar')) { throw 'Stale Arena jar remains.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar'))) { throw 'Unrelated mod was changed.' }
	if (-not (Test-Path -LiteralPath $voiceAddon -PathType Leaf) -or
		(Get-FileHash -LiteralPath $voiceAddon -Algorithm SHA256).Hash -ne $voiceAddonHash) {
		throw 'Normal profile update removed or changed the optional Arena Agents Voice add-on.'
	}
    if (Test-Path -LiteralPath (Join-Path $runtimeCoordinator 'stale.log')) { throw 'Stale coordinator state remains.' }

    $installedHash = (Get-FileHash (Join-Path $mods $modJarName) -Algorithm SHA256).Hash
    $sourceHash = (Get-FileHash (Join-Path $root ("build\libs\" + $modJarName)) -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'Installed jar hash differs from source jar.' }

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
    Write-Host 'Normal profile updater preserved the optional voice add-on and passed rollback verification.'
} finally {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
