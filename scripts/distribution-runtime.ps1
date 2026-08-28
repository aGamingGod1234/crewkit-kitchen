Set-StrictMode -Version Latest

$script:ArenaCoordinatorManifestName = '.arena-agents-bundle-manifest'
$script:ArenaCoordinatorStateRelativePath = 'runtime\coordinator-generation.properties'
$script:ArenaCoordinatorConfigRelativePath = 'runtime\dynamic-agents.json'
$script:ArenaCoordinatorStagingPrefix = 'coordinator.staging-'

function Assert-ArenaContainedPath([string] $Root, [string] $Target, [string] $Label) {
	$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
	$targetPath = [IO.Path]::GetFullPath($Target)
	if ($targetPath -ne $rootPath -and -not $targetPath.StartsWith($rootPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
		throw "$Label escapes the coordinator package root: $targetPath"
	}
	return $targetPath
}

function Assert-ArenaNoReparsePath([string] $Path, [string] $Label) {
	$current = [IO.Path]::GetFullPath($Path)
	while ($null -ne $current -and $current.Length -gt 2) {
		if (Test-Path -LiteralPath $current) {
			$item = Get-Item -LiteralPath $current -Force
			if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $current" }
		}
		$parent = Split-Path -Parent $current
		if ($parent -eq $current) { break }
		$current = $parent
	}
}

function Assert-ArenaNoReparseTree([string] $Path, [string] $Label) {
	if (-not (Test-Path -LiteralPath $Path)) { return }
	foreach ($item in @(Get-Item -LiteralPath $Path -Force) + @(Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction Stop)) {
		if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $($item.FullName)" }
	}
}

function Remove-ArenaTree([string] $Root, [string] $Target) {
	$resolved = Assert-ArenaContainedPath $Root $Target 'coordinator removal target'
	if (-not (Test-Path -LiteralPath $resolved)) { return }
	Assert-ArenaNoReparseTree $resolved 'coordinator removal target'
	Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Get-ArenaSha256([byte[]] $Bytes) {
	$sha = [Security.Cryptography.SHA256]::Create()
	try { return (($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '') }
	finally { $sha.Dispose() }
}

function Get-ArenaCoordinatorSourceFiles([string] $CoordinatorRoot) {
	$files = [Collections.Generic.List[IO.FileInfo]]::new()
	foreach ($fixed in @('package.json', 'package-lock.json')) {
		$path = Join-Path $CoordinatorRoot $fixed
		if (Test-Path -LiteralPath $path -PathType Leaf) { $files.Add((Get-Item -LiteralPath $path)) }
	}
	foreach ($relativeRoot in @('config', 'src', 'node_modules\acorn')) {
		$path = Join-Path $CoordinatorRoot $relativeRoot
		if (Test-Path -LiteralPath $path -PathType Container) {
			foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) { $files.Add($file) }
		}
	}
	foreach ($required in @('package.json', 'src\dynamic-main.mjs', 'config\dynamic-agents.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $CoordinatorRoot $required) -PathType Leaf)) {
			throw "Coordinator source is missing required file '$required'."
		}
	}
	return @($files | Sort-Object FullName -Unique)
}

function New-ArenaCoordinatorManifest([string] $CoordinatorRoot, [IO.FileInfo[]] $Files) {
	$records = foreach ($file in $Files) {
		$relative = $file.FullName.Substring($CoordinatorRoot.Length + 1).Replace('\', '/')
		$hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
		"$hash $relative"
	}
	return (@($records | Sort-Object) -join "`n") + "`n"
}

function Get-ArenaGenerationId([string] $Manifest) {
	return Get-ArenaSha256 ([Text.UTF8Encoding]::new($false).GetBytes($Manifest))
}

function Get-ArenaInstalledGeneration([string] $Directory, [string] $ExpectedGeneration = '') {
	if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return '' }
	Assert-ArenaNoReparseTree $Directory 'coordinator generation'
	$manifestPath = Join-Path $Directory $script:ArenaCoordinatorManifestName
	if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { return '' }
	$manifest = [IO.File]::ReadAllText($manifestPath)
	$generation = Get-ArenaGenerationId $manifest
	if ($ExpectedGeneration -ne '' -and $generation -cne $ExpectedGeneration) { return '' }
	foreach ($line in @($manifest -split "`r?`n" | Where-Object { $_ -ne '' })) {
		if ($line -notmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>.+)$') { return '' }
		$relative = $Matches.Path
		if ($relative -ceq 'config/dynamic-agents.json') { continue }
		if ($relative.Contains('\') -or $relative.StartsWith('/') -or $relative -match '(^|/)\.\.(/|$)') { return '' }
		$file = Assert-ArenaContainedPath $Directory (Join-Path $Directory ($relative.Replace('/', '\'))) 'coordinator manifest entry'
		if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return '' }
		if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Matches.Hash) { return '' }
	}
	return $generation
}

function Read-ArenaGenerationState([string] $InstalledRoot) {
	$statePath = Join-Path $InstalledRoot $script:ArenaCoordinatorStateRelativePath
	$values = @{}
	if (Test-Path -LiteralPath $statePath -PathType Leaf) {
		Assert-ArenaNoReparsePath $statePath 'coordinator generation state'
		foreach ($line in [IO.File]::ReadAllLines($statePath)) {
			if ($line -match '^(?<Key>[^#=]+)=(?<Value>.*)$') { $values[$Matches.Key] = $Matches.Value }
		}
	}
	return [pscustomobject]@{
		Phase = if ($values.ContainsKey('phase')) { $values.phase } else { 'ready' }
		ActiveGeneration = if ($values.ContainsKey('activeGeneration')) { $values.activeGeneration } else { '' }
		VerifiedGeneration = if ($values.ContainsKey('verifiedGeneration')) { $values.verifiedGeneration } else { '' }
		CandidateGeneration = if ($values.ContainsKey('candidateGeneration')) { $values.candidateGeneration } else { '' }
		LastKnownGoodGeneration = if ($values.ContainsKey('lastKnownGoodGeneration')) { $values.lastKnownGoodGeneration } else { '' }
		StagingDirectory = if ($values.ContainsKey('stagingDirectory')) { $values.stagingDirectory } else { '' }
		PreviousActiveGeneration = if ($values.ContainsKey('previousActiveGeneration')) { $values.previousActiveGeneration } else { '' }
	}
}

function Write-ArenaGenerationState([string] $InstalledRoot, [hashtable] $State) {
	$runtime = Join-Path $InstalledRoot 'runtime'
	New-Item -ItemType Directory -Force -Path $runtime | Out-Null
	$target = Join-Path $InstalledRoot $script:ArenaCoordinatorStateRelativePath
	$staging = "$target.staging-$([Guid]::NewGuid().ToString('N'))"
	$content = @(
		"phase=$($State.Phase)",
		"activeGeneration=$($State.ActiveGeneration)",
		"verifiedGeneration=$($State.VerifiedGeneration)",
		"candidateGeneration=$($State.CandidateGeneration)",
		"lastKnownGoodGeneration=$($State.LastKnownGoodGeneration)",
		"stagingDirectory=$($State.StagingDirectory)",
		"previousActiveGeneration=$($State.PreviousActiveGeneration)"
	) -join "`n"
	try {
		[IO.File]::WriteAllText($staging, $content + "`n", [Text.UTF8Encoding]::new($false))
		Move-Item -LiteralPath $staging -Destination $target -Force
	} finally {
		if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Force }
	}
}

function Get-ArenaReadyState([string] $Active, [string] $Verified, [string] $Candidate, [string] $LastKnownGood) {
	return @{
		Phase = 'ready'; ActiveGeneration = $Active; VerifiedGeneration = $Verified
		CandidateGeneration = $Candidate; LastKnownGoodGeneration = $LastKnownGood
		StagingDirectory = ''; PreviousActiveGeneration = ''
	}
}

function Complete-ArenaGenerationJournal([string] $InstalledRoot, [string] $FailurePoint = 'None') {
	$state = Read-ArenaGenerationState $InstalledRoot
	if ($state.Phase -eq 'ready') { return }
	$active = Join-Path $InstalledRoot 'coordinator'
	$lkg = Join-Path $InstalledRoot 'coordinator.last-known-good'
	if ([string]::IsNullOrWhiteSpace($state.StagingDirectory) -or -not $state.StagingDirectory.StartsWith($script:ArenaCoordinatorStagingPrefix)) {
		throw 'Coordinator generation journal contains an invalid staging directory.'
	}
	$staging = Assert-ArenaContainedPath $InstalledRoot (Join-Path $InstalledRoot $state.StagingDirectory) 'coordinator journal staging'

	if ($state.Phase -eq 'activate') {
		if ((Get-ArenaInstalledGeneration $active $state.ActiveGeneration) -eq $state.ActiveGeneration) {
			$retained = Get-ArenaInstalledGeneration $lkg
			Write-ArenaGenerationState $InstalledRoot (Get-ArenaReadyState $state.ActiveGeneration $state.VerifiedGeneration $state.CandidateGeneration $retained)
			if (Test-Path -LiteralPath $staging) { Remove-ArenaTree $InstalledRoot $staging }
			return
		}
		if ((Get-ArenaInstalledGeneration $staging $state.ActiveGeneration) -ne $state.ActiveGeneration) {
			if ((Get-ArenaInstalledGeneration $lkg $state.VerifiedGeneration) -eq $state.VerifiedGeneration) {
				if (Test-Path -LiteralPath $active) { Remove-ArenaTree $InstalledRoot $active }
				Move-Item -LiteralPath $lkg -Destination $active
				Write-ArenaGenerationState $InstalledRoot (Get-ArenaReadyState $state.VerifiedGeneration $state.VerifiedGeneration '' '')
				return
			}
			throw 'Interrupted coordinator activation has no validated runnable generation.'
		}
		$retainActive = $state.VerifiedGeneration -ne '' -and $state.VerifiedGeneration -ceq $state.PreviousActiveGeneration
		if (Test-Path -LiteralPath $active) {
			if ($retainActive) {
				if (Test-Path -LiteralPath $lkg) { Remove-ArenaTree $InstalledRoot $lkg }
				Move-Item -LiteralPath $active -Destination $lkg
				if ($FailurePoint -eq 'AfterRetain') { throw 'Injected runtime deployment failure after retaining last-known-good.' }
			} else { Remove-ArenaTree $InstalledRoot $active }
		}
		Move-Item -LiteralPath $staging -Destination $active
		if ($FailurePoint -eq 'AfterActivate') { throw 'Injected runtime deployment failure after candidate activation.' }
		if ((Get-ArenaInstalledGeneration $active $state.ActiveGeneration) -ne $state.ActiveGeneration) { throw 'Activated coordinator generation failed hash validation.' }
		$retained = Get-ArenaInstalledGeneration $lkg
		Write-ArenaGenerationState $InstalledRoot (Get-ArenaReadyState $state.ActiveGeneration $state.VerifiedGeneration $state.CandidateGeneration $retained)
		return
	}

	if ($state.Phase -eq 'rollback') {
		if ((Get-ArenaInstalledGeneration $active $state.VerifiedGeneration) -ne $state.VerifiedGeneration) {
			if ((Get-ArenaInstalledGeneration $lkg $state.VerifiedGeneration) -ne $state.VerifiedGeneration) { throw 'Verified rollback generation failed hash validation.' }
			if (Test-Path -LiteralPath $active) { Move-Item -LiteralPath $active -Destination $staging }
			Move-Item -LiteralPath $lkg -Destination $active
		}
		Write-ArenaGenerationState $InstalledRoot (Get-ArenaReadyState $state.VerifiedGeneration $state.VerifiedGeneration '' '')
		if (Test-Path -LiteralPath $staging) { Remove-ArenaTree $InstalledRoot $staging }
		return
	}
	throw "Unknown coordinator generation journal phase '$($state.Phase)'."
}

function Initialize-ArenaExternalConfig([string] $InstalledRoot, [string] $SourceConfig, [string] $Active) {
	$config = Join-Path $InstalledRoot $script:ArenaCoordinatorConfigRelativePath
	if (Test-Path -LiteralPath $config -PathType Leaf) { return }
	$legacy = Join-Path $Active 'config\dynamic-agents.json'
	$source = if (Test-Path -LiteralPath $legacy -PathType Leaf) { $legacy } else { $SourceConfig }
	New-Item -ItemType Directory -Force -Path (Split-Path -Parent $config) | Out-Null
	$staging = "$config.staging-$([Guid]::NewGuid().ToString('N'))"
	try {
		Copy-Item -LiteralPath $source -Destination $staging
		if (-not (Test-Path -LiteralPath $config)) { Move-Item -LiteralPath $staging -Destination $config }
	} finally {
		if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Force }
	}
}

function Remove-ArenaStagingDirectories([string] $InstalledRoot) {
	$count = 0
	foreach ($directory in @(Get-ChildItem -LiteralPath $InstalledRoot -Directory -Filter "$($script:ArenaCoordinatorStagingPrefix)*" | Sort-Object Name)) {
		if ($count -ge 8) { break }
		Remove-ArenaTree $InstalledRoot $directory.FullName
		$count++
	}
}

function Install-ArenaCoordinatorRuntime {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $SourceRoot,
		[Parameter(Mandatory)] [string] $InstalledPackageRoot,
		[ValidateSet('None', 'AfterRetain', 'AfterActivate')] [string] $FailurePoint = 'None'
	)

	$resolvedSourceRoot = [IO.Path]::GetFullPath($SourceRoot)
	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$sourceCoordinator = Join-Path $resolvedSourceRoot 'coordinator'
	$active = Join-Path $resolvedInstalledRoot 'coordinator'
	$lkg = Join-Path $resolvedInstalledRoot 'coordinator.last-known-good'
	Assert-ArenaNoReparseTree $sourceCoordinator 'coordinator source'
	Assert-ArenaNoReparsePath $resolvedInstalledRoot 'coordinator package root'
	foreach ($target in @($active, $lkg, (Join-Path $resolvedInstalledRoot 'runtime'))) {
		Assert-ArenaContainedPath $resolvedInstalledRoot $target 'coordinator mutation target' | Out-Null
		Assert-ArenaNoReparsePath $target 'coordinator mutation target'
	}
	New-Item -ItemType Directory -Force -Path $resolvedInstalledRoot | Out-Null
	Complete-ArenaGenerationJournal $resolvedInstalledRoot

	$sourceFiles = @(Get-ArenaCoordinatorSourceFiles $sourceCoordinator)
	$manifest = New-ArenaCoordinatorManifest $sourceCoordinator $sourceFiles
	$generation = Get-ArenaGenerationId $manifest
	$state = Read-ArenaGenerationState $resolvedInstalledRoot
	if ((Get-ArenaInstalledGeneration $active $generation) -eq $generation -and $state.ActiveGeneration -ceq $generation) {
		Initialize-ArenaExternalConfig $resolvedInstalledRoot (Join-Path $sourceCoordinator 'config\dynamic-agents.json') $active
		Remove-ArenaStagingDirectories $resolvedInstalledRoot
		return [pscustomobject]@{
			ActivePath = $active; LastKnownGoodPath = $lkg; GenerationId = $generation
			Changed = $false; Candidate = ($state.CandidateGeneration -ceq $generation)
		}
	}

	$stagingName = $script:ArenaCoordinatorStagingPrefix + $generation
	$staging = Join-Path $resolvedInstalledRoot $stagingName
	if (Test-Path -LiteralPath $staging) { Remove-ArenaTree $resolvedInstalledRoot $staging }
	New-Item -ItemType Directory -Path $staging | Out-Null
	foreach ($file in $sourceFiles) {
		$relative = $file.FullName.Substring($sourceCoordinator.Length + 1)
		if ($relative.Replace('\', '/') -ceq 'config/dynamic-agents.json') { continue }
		$target = Join-Path $staging $relative
		New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
		Copy-Item -LiteralPath $file.FullName -Destination $target
	}
	[IO.File]::WriteAllText((Join-Path $staging $script:ArenaCoordinatorManifestName), $manifest, [Text.UTF8Encoding]::new($false))
	if ((Get-ArenaInstalledGeneration $staging $generation) -ne $generation) { throw 'Staged coordinator generation failed hash validation.' }
	Initialize-ArenaExternalConfig $resolvedInstalledRoot (Join-Path $sourceCoordinator 'config\dynamic-agents.json') $active

	$previous = Get-ArenaInstalledGeneration $active
	$retain = $state.VerifiedGeneration -ne '' -and $state.VerifiedGeneration -ceq $previous
	$retained = if ($retain) { $state.VerifiedGeneration } else { Get-ArenaInstalledGeneration $lkg }
	Write-ArenaGenerationState $resolvedInstalledRoot @{
		Phase = 'activate'; ActiveGeneration = $generation; VerifiedGeneration = $state.VerifiedGeneration
		CandidateGeneration = $generation; LastKnownGoodGeneration = $retained
		StagingDirectory = $stagingName; PreviousActiveGeneration = $previous
	}
	Complete-ArenaGenerationJournal $resolvedInstalledRoot $FailurePoint
	Remove-ArenaStagingDirectories $resolvedInstalledRoot
	return [pscustomobject]@{
		ActivePath = $active; LastKnownGoodPath = $lkg; GenerationId = $generation
		Changed = $true; Candidate = $true
	}
}

function Confirm-ArenaCoordinatorGeneration {
	[CmdletBinding()]
	param([Parameter(Mandatory)] [string] $InstalledPackageRoot, [Parameter(Mandatory)] [string] $GenerationId)
	$root = [IO.Path]::GetFullPath($InstalledPackageRoot)
	Complete-ArenaGenerationJournal $root
	$state = Read-ArenaGenerationState $root
	$active = Join-Path $root 'coordinator'
	if ((Get-ArenaInstalledGeneration $active $GenerationId) -ne $GenerationId) { throw 'Cannot promote an invalid coordinator generation.' }
	if ($state.VerifiedGeneration -ceq $GenerationId -and $state.CandidateGeneration -eq '') { return $false }
	if ($state.CandidateGeneration -cne $GenerationId) { return $false }
	Write-ArenaGenerationState $root (Get-ArenaReadyState $GenerationId $GenerationId '' $state.LastKnownGoodGeneration)
	return $true
}

function Restore-ArenaCoordinatorGeneration {
	[CmdletBinding()]
	param([Parameter(Mandatory)] [string] $InstalledPackageRoot, [Parameter(Mandatory)] [string] $GenerationId)
	$root = [IO.Path]::GetFullPath($InstalledPackageRoot)
	Complete-ArenaGenerationJournal $root
	$state = Read-ArenaGenerationState $root
	if ($state.ActiveGeneration -cne $GenerationId -or $state.CandidateGeneration -cne $GenerationId -or $state.LastKnownGoodGeneration -eq '') { return $false }
	$stagingName = $script:ArenaCoordinatorStagingPrefix + 'rollback-' + $GenerationId
	Write-ArenaGenerationState $root @{
		Phase = 'rollback'; ActiveGeneration = $GenerationId; VerifiedGeneration = $state.LastKnownGoodGeneration
		CandidateGeneration = $GenerationId; LastKnownGoodGeneration = $state.LastKnownGoodGeneration
		StagingDirectory = $stagingName; PreviousActiveGeneration = $GenerationId
	}
	Complete-ArenaGenerationJournal $root
	Remove-ArenaStagingDirectories $root
	return $true
}
