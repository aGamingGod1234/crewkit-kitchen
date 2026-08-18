Set-StrictMode -Version Latest

function Install-ArenaCoordinatorRuntime {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $SourceRoot,
		[Parameter(Mandatory)] [string] $InstalledPackageRoot
	)

	$resolvedSourceRoot = [IO.Path]::GetFullPath($SourceRoot)
	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$sourceCoordinator = Join-Path $resolvedSourceRoot 'coordinator'
	$activeCoordinator = Join-Path $resolvedInstalledRoot 'coordinator'
	foreach ($requiredRelativePath in @('src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $sourceCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator source is missing required file '$requiredRelativePath'."
		}
	}

	New-Item -ItemType Directory -Force -Path $resolvedInstalledRoot | Out-Null
	$stagingCoordinator = "$activeCoordinator.staging-$([Guid]::NewGuid().ToString('N'))"
	New-Item -ItemType Directory -Path $stagingCoordinator | Out-Null
	foreach ($entry in Get-ChildItem -LiteralPath $sourceCoordinator -Force) {
		Copy-Item -LiteralPath $entry.FullName -Destination $stagingCoordinator -Recurse -Force
	}
	foreach ($requiredRelativePath in @('src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $stagingCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator staging is missing required file '$requiredRelativePath'."
		}
	}

	$backupPath = $null
	if (Test-Path -LiteralPath $activeCoordinator) {
		$backupDirectory = Join-Path $resolvedInstalledRoot 'coordinator-backups'
		New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
		$backupPath = Join-Path $backupDirectory ("coordinator-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
		Move-Item -LiteralPath $activeCoordinator -Destination $backupPath -ErrorAction Stop
	}
	try {
		Move-Item -LiteralPath $stagingCoordinator -Destination $activeCoordinator -ErrorAction Stop
	} catch {
		if ($backupPath -ne $null -and -not (Test-Path -LiteralPath $activeCoordinator) -and (Test-Path -LiteralPath $backupPath)) {
			Move-Item -LiteralPath $backupPath -Destination $activeCoordinator -ErrorAction SilentlyContinue
		}
		throw
	}

	return [PSCustomObject]@{
		ActivePath = $activeCoordinator
		BackupPath = $backupPath
	}
}
