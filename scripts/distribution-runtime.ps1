Set-StrictMode -Version Latest

function Install-ArenaCoordinatorRuntime {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $SourceRoot,
		[Parameter(Mandatory)] [string] $InstalledPackageRoot,
		[ValidateSet('None', 'AfterCoordinatorPromotion', 'AfterNodePromotion')]
		[string] $FailurePoint = 'None'
	)

	$resolvedSourceRoot = [IO.Path]::GetFullPath($SourceRoot)
	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$sourceCoordinator = Join-Path $resolvedSourceRoot 'coordinator'
	$activeCoordinator = Join-Path $resolvedInstalledRoot 'coordinator'
	$sourceNode = Join-Path $resolvedSourceRoot $(if ($env:OS -eq 'Windows_NT') { 'runtime\toolchains\node\node.exe' } else { 'runtime/toolchains/node/bin/node' })
	$sourceNodeDirectory = Join-Path $resolvedSourceRoot 'runtime\toolchains\node'
	$activeNodeDirectory = Join-Path $resolvedInstalledRoot 'runtime\toolchains\node'
	foreach ($requiredRelativePath in @('src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $sourceCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator source is missing required file '$requiredRelativePath'."
		}
	}
	if (-not (Test-Path -LiteralPath $sourceNode -PathType Leaf)) {
		throw "Coordinator source is missing its bundled Node.js runtime: $sourceNode"
	}

	New-Item -ItemType Directory -Force -Path $resolvedInstalledRoot | Out-Null
	$transactionId = [Guid]::NewGuid().ToString('N')
	$stagingCoordinator = "$activeCoordinator.staging-$transactionId"
	$stagingNodeDirectory = "$activeNodeDirectory.staging-$transactionId"
	New-Item -ItemType Directory -Path $stagingCoordinator | Out-Null
	foreach ($entry in Get-ChildItem -LiteralPath $sourceCoordinator -Force) {
		Copy-Item -LiteralPath $entry.FullName -Destination $stagingCoordinator -Recurse -Force
	}
	New-Item -ItemType Directory -Force -Path (Split-Path -Parent $activeNodeDirectory) | Out-Null
	Copy-Item -LiteralPath $sourceNodeDirectory -Destination $stagingNodeDirectory -Recurse -Force
	foreach ($requiredRelativePath in @('src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $stagingCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator staging is missing required file '$requiredRelativePath'."
		}
	}
	$stagedNode = Join-Path $stagingNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
	if (-not (Test-Path -LiteralPath $stagedNode -PathType Leaf)) {
		throw "Coordinator staging is missing its bundled Node.js runtime: $stagedNode"
	}

	$backupPath = $null
	$nodeBackupPath = $null
	$coordinatorPromoted = $false
	$nodePromoted = $false
	try {
		if (Test-Path -LiteralPath $activeCoordinator) {
			$backupDirectory = Join-Path $resolvedInstalledRoot 'coordinator-backups'
			New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
			$backupPath = Join-Path $backupDirectory ("coordinator-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + "-$transactionId")
			Move-Item -LiteralPath $activeCoordinator -Destination $backupPath -ErrorAction Stop
		}
		if (Test-Path -LiteralPath $activeNodeDirectory) {
			$nodeBackupDirectory = Join-Path $resolvedInstalledRoot 'node-runtime-backups'
			New-Item -ItemType Directory -Force -Path $nodeBackupDirectory | Out-Null
			$nodeBackupPath = Join-Path $nodeBackupDirectory ("node-" + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + "-$transactionId")
			Move-Item -LiteralPath $activeNodeDirectory -Destination $nodeBackupPath -ErrorAction Stop
		}

		Move-Item -LiteralPath $stagingCoordinator -Destination $activeCoordinator -ErrorAction Stop
		$coordinatorPromoted = $true
		if ($FailurePoint -eq 'AfterCoordinatorPromotion') { throw 'Injected failure after coordinator promotion.' }
		Move-Item -LiteralPath $stagingNodeDirectory -Destination $activeNodeDirectory -ErrorAction Stop
		$nodePromoted = $true
		if ($FailurePoint -eq 'AfterNodePromotion') { throw 'Injected failure after Node.js promotion.' }
	} catch {
		$promotionFailure = $_
		$rollbackFailures = [Collections.Generic.List[string]]::new()
		foreach ($runtimeState in @(
			[pscustomobject]@{ Active = $activeNodeDirectory; Backup = $nodeBackupPath; Promoted = $nodePromoted; Label = 'Node.js' },
			[pscustomobject]@{ Active = $activeCoordinator; Backup = $backupPath; Promoted = $coordinatorPromoted; Label = 'coordinator' }
		)) {
			try {
				$hasBackup = $null -ne $runtimeState.Backup -and (Test-Path -LiteralPath $runtimeState.Backup)
				if (($runtimeState.Promoted -or $hasBackup) -and (Test-Path -LiteralPath $runtimeState.Active)) {
					Remove-Item -LiteralPath $runtimeState.Active -Recurse -Force -ErrorAction Stop
				}
				if ($hasBackup) {
					Move-Item -LiteralPath $runtimeState.Backup -Destination $runtimeState.Active -ErrorAction Stop
				}
			} catch {
				$rollbackFailures.Add("$($runtimeState.Label): $($_.Exception.Message)")
			}
		}
		if ($rollbackFailures.Count -ne 0) {
			throw "Runtime promotion failed and rollback was incomplete: $($rollbackFailures -join '; '). Original failure: $($promotionFailure.Exception.Message)"
		}
		throw $promotionFailure
	} finally {
		foreach ($stagingPath in @($stagingCoordinator, $stagingNodeDirectory)) {
			if (Test-Path -LiteralPath $stagingPath) {
				Remove-Item -LiteralPath $stagingPath -Recurse -Force -ErrorAction SilentlyContinue
			}
		}
	}

	return [PSCustomObject]@{
		ActivePath = $activeCoordinator
		BackupPath = $backupPath
		NodePath = Join-Path $activeNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
		NodeBackupPath = $nodeBackupPath
	}
}
