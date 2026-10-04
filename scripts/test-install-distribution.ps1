[CmdletBinding()]
param(
	[Parameter(Mandatory)] [string] $PackageRoot,
	[Parameter(Mandatory)] [string] $JavaPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function File-Snapshot([string] $Root) {
	if (-not (Test-Path -LiteralPath $Root)) { return @() }
	return @(Get-ChildItem -LiteralPath $Root -File -Recurse -Force |
		Where-Object { $_.Name -notlike '*.lock' -and $_.FullName -notmatch '[\\/]distribution-backups[\\/]' } |
		Sort-Object FullName |
		ForEach-Object { $_.FullName.Substring($Root.Length + 1).Replace('\', '/') + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
}

function Assert-GenerationStateBackupRotation([string[]] $Before, [string[]] $After, [string] $Message) {
	$state = @($Before | Where-Object { $_ -cmatch '^runtime/coordinator-generation\.properties:[A-F0-9]{64}$' })
	$oldBackups = @($Before | Where-Object { $_ -clike 'generation-state-backups/*' })
	$backups = @($After | Where-Object { $_ -clike 'generation-state-backups/*' })
	foreach ($backup in $oldBackups) {
		if ($backup -cnotmatch '^generation-state-backups/state-[a-f0-9]{32}\.properties:[A-F0-9]{64}$') { throw "$Message Unexpected prior state backup: $backup" }
	}
	if ($state.Count -eq 0) {
		if ($backups.Count -ne 0) { throw "$Message An obsolete generation state backup remains." }
		return
	}
	if ($state.Count -ne 1 -or $backups.Count -ne 1 -or
		$backups[0] -cnotmatch '^generation-state-backups/state-[a-f0-9]{32}\.properties:[A-F0-9]{64}$' -or
		$backups[0].Split(':')[1] -cne $state[0].Split(':')[1]) {
		throw "$Message Expected exactly one byte-identical prior generation state backup."
	}
	$oldPaths = @($oldBackups | ForEach-Object { $_.Split(':')[0] })
	if ($backups[0].Split(':')[0] -cin $oldPaths) { throw "$Message An obsolete generation state backup was retained." }
}

function Assert-RuntimeRollbackSnapshot([string[]] $Before, [string[]] $After, [string] $Message) {
	# The inner runtime install commits before the outer installer can fail. It
	# rotates old state backups, and outer rollback retains only this transaction's
	# byte-identical state copy for retries. All other original files must match.
	Assert-GenerationStateBackupRotation $Before $After $Message
	$oldBackups = @($Before | Where-Object { $_ -clike 'generation-state-backups/*' })
	$newBackups = @($After | Where-Object { $_ -clike 'generation-state-backups/*' })
	if (@(Compare-Object @($Before | Where-Object { $_ -cnotin $oldBackups }) @($After | Where-Object { $_ -cnotin $newBackups })).Count -ne 0) { throw $Message }
}

function Assert-TransactionCleanup([string] $Root, [string] $Profiles) {
	foreach ($entry in Get-ChildItem -LiteralPath $Root -Recurse -Force) {
		$relative = $entry.FullName.Substring($Root.Length + 1).Replace('\', '/')
		if ($relative -cmatch '^\.arena-runtime-transaction\.json(\.tmp)?$|^\.distribution-staging-|^(coordinator|coordinator\.last-known-good|runtime/toolchains/node|runtime/coordinator-generation\.properties)\.staging-') {
			throw "Installation transaction artifact remains: $relative"
		}
	}
	if (@(Get-ChildItem -LiteralPath (Split-Path -Parent $Profiles) -File -Filter ((Split-Path -Leaf $Profiles) + '.arena-agents-*.tmp')).Count -ne 0) {
		throw 'Launcher profile staging file remains.'
	}
}

$package = (Resolve-Path -LiteralPath $PackageRoot).Path
$installer = Join-Path $package 'scripts\install-distribution.ps1'
$metadata = [ordered]@{}
foreach ($line in Get-Content -LiteralPath (Join-Path $package 'distribution.properties')) {
	$separator = $line.IndexOf('=')
	if ($separator -gt 0) { $metadata[$line.Substring(0, $separator)] = $line.Substring($separator + 1) }
}
# Keep nested backup paths below Windows PowerShell 5.1's legacy path limit.
# Expand a CI TEMP 8.3 alias before deriving file-snapshot prefixes.
$testRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("pkg-" + [Guid]::NewGuid().ToString('N'))))
$appData = Join-Path $testRoot 'appdata'
$game = Join-Path $testRoot 'game'
$launcherProfiles = Join-Path $appData '.minecraft\launcher_profiles.json'
$versionId = "fabric-loader-$($metadata.loader_version)-$($metadata.minecraft_version)"
$versionMetadata = Join-Path $appData ".minecraft\versions\$versionId\$versionId.json"
$mods = Join-Path $game 'mods'
$installedRoot = Join-Path $game 'arena-agents-runtime'
$previousAppData = $env:APPDATA
try {
	New-Item -ItemType Directory -Force -Path $mods, (Split-Path -Parent $launcherProfiles), (Split-Path -Parent $versionMetadata) | Out-Null
	[IO.File]::WriteAllText($launcherProfiles, '{"profiles":{}}')
	[IO.File]::WriteAllText($versionMetadata, '{}')
	[IO.File]::WriteAllText((Join-Path $mods 'arena-agents-old-unparseable.jar'), 'old arena')
	[IO.File]::WriteAllText((Join-Path $mods 'fabric-api-old.jar'), 'old api')
	[IO.File]::WriteAllText((Join-Path $mods 'fabric-carpet-old.jar'), 'old carpet')
	[IO.File]::WriteAllText((Join-Path $mods 'voicechat-fabric-old.jar'), 'old voicechat')
	[IO.File]::WriteAllText((Join-Path $mods 'unrelated.jar'), 'keep')
	$voiceAddon = Join-Path $mods 'arena-agents-voice-0.1.0.jar'
	[IO.File]::WriteAllText($voiceAddon, 'optional voice addon')
	$voiceAddonHash = (Get-FileHash -LiteralPath $voiceAddon -Algorithm SHA256).Hash
	$env:APPDATA = $appData

	& $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game
	$expectedModNames = @(
		"arena-agents-$($metadata.mod_version).jar",
		"arena-agents-voice-$($metadata.voice_addon_version).jar",
		"fabric-api-$($metadata.fabric_api_version).jar",
		"fabric-carpet-$($metadata.carpet_version).jar",
		"voicechat-fabric-$($metadata.voicechat_version).jar"
	)
	$owned = @(Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File |
		Where-Object {
			$_.Name -match '^(?i:fabric-api|fabric-carpet|voicechat-fabric)-.+\.jar$' -or
			$_.Name -match '^(?i:arena-agents).+\.jar$'
		} |
		Select-Object -ExpandProperty Name | Sort-Object)
	if (@(Compare-Object ($expectedModNames | Sort-Object) $owned).Count -ne 0) { throw 'Successful package update retained a stale package-owned JAR.' }
	if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar') -PathType Leaf)) { throw 'Successful package update removed an unrelated mod.' }
	if (Test-Path -LiteralPath $voiceAddon -PathType Leaf) {
		throw 'Successful package update retained a stale Arena Agents Voice add-on.'
	}
	if (Test-Path -LiteralPath (Join-Path $mods 'voicechat-fabric-old.jar') -PathType Leaf) {
		throw 'Successful package update retained a stale Simple Voice Chat JAR.'
	}
	foreach ($secretName in @('bridge-secret.txt', 'voice-secret.txt')) {
		if (-not (Test-Path -LiteralPath (Join-Path $installedRoot "runtime\$secretName") -PathType Leaf)) { throw "Installed secret is missing: $secretName" }
	}

	foreach ($failurePoint in @('AfterRuntimePromotion', 'AfterModRemoval', 'AfterModsPromotion')) {
		$modsBefore = File-Snapshot $mods
		$runtimeBefore = File-Snapshot $installedRoot
		$profilesBefore = (Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash
		$failed = $false
		try { & $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game -FailurePoint $failurePoint }
		catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw }; $failed = $true }
		if (-not $failed) { throw "Failure injection did not occur: $failurePoint" }
		if (@(Compare-Object $modsBefore (File-Snapshot $mods)).Count -ne 0) { throw "Mod rollback failed at $failurePoint." }
		Assert-RuntimeRollbackSnapshot $runtimeBefore (File-Snapshot $installedRoot) "Runtime rollback failed at $failurePoint."
		if ((Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash -ne $profilesBefore) { throw "Profile changed at $failurePoint." }
		Assert-TransactionCleanup $installedRoot $launcherProfiles
	}

	$profileDocument = Get-Content -LiteralPath $launcherProfiles -Raw | ConvertFrom-Json
	$profileDocument.profiles.'arena-agents-modpack'.javaArgs = '-Xms1G -Xmx4G -Dstale=true'
	[IO.File]::WriteAllText($launcherProfiles, ($profileDocument | ConvertTo-Json -Depth 64))
	$modsBefore = File-Snapshot $mods
	$runtimeBefore = File-Snapshot $installedRoot
	$profilesBefore = (Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash
	$failed = $false
	try { & $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game -FailurePoint AfterProfilePromotion }
	catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw }; $failed = $true }
	if (-not $failed) { throw 'Failure injection did not occur: AfterProfilePromotion' }
	if (@(Compare-Object $modsBefore (File-Snapshot $mods)).Count -ne 0) { throw 'Mod rollback failed after profile promotion.' }
	Assert-RuntimeRollbackSnapshot $runtimeBefore (File-Snapshot $installedRoot) 'Runtime rollback failed after profile promotion.'
	if ((Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash -ne $profilesBefore) { throw 'Profile rollback failed after profile promotion.' }
	Assert-TransactionCleanup $installedRoot $launcherProfiles

	$runtimeBefore = File-Snapshot $installedRoot
	& $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game
	Assert-GenerationStateBackupRotation $runtimeBefore (File-Snapshot $installedRoot) 'Successful retry failed to prune obsolete generation state backups.'
	Assert-TransactionCleanup $installedRoot $launcherProfiles
	$profileDocument = Get-Content -LiteralPath $launcherProfiles -Raw | ConvertFrom-Json
	if ($profileDocument.profiles.'arena-agents-modpack'.javaArgs -match '-Dstale=true') { throw 'Successful retry did not update the launcher profile.' }

	Write-Host 'PASS: packaged install removes stale core and voice JARs, rolls back runtime, mods, and launcher profile together, and prunes retry state backups'
} finally {
	$env:APPDATA = $previousAppData
	if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
