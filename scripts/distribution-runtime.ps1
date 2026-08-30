Set-StrictMode -Version Latest

function Assert-ArenaRuntimeChildPath {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $CandidatePath
	)
	$root = [IO.Path]::GetFullPath($InstalledRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
	$candidate = [IO.Path]::GetFullPath($CandidatePath)
	$prefix = $root + [IO.Path]::DirectorySeparatorChar
	if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
		throw "Refusing to modify runtime path outside the installed package root: $candidate"
	}
	return $candidate
}

function Restore-ArenaRuntimeTransaction {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $JournalPath
	)
	if (-not (Test-Path -LiteralPath $JournalPath -PathType Leaf)) { return }
	try {
		$journal = Get-Content -LiteralPath $JournalPath -Raw | ConvertFrom-Json -ErrorAction Stop
		$runtimes = @(
			[pscustomobject]@{
				Active = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Active)
				Backup = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Backup)
				Staging = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Staging)
				HadActive = [bool] $journal.Node.HadActive
				Label = 'Node.js'
			},
			[pscustomobject]@{
				Active = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Active)
				Backup = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Backup)
				Staging = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Staging)
				HadActive = [bool] $journal.Coordinator.HadActive
				Label = 'coordinator'
			}
		)
		$failures = [Collections.Generic.List[string]]::new()
		foreach ($runtime in $runtimes) {
			try {
				if (Test-Path -LiteralPath $runtime.Backup) {
					if (Test-Path -LiteralPath $runtime.Active) {
						Remove-Item -LiteralPath $runtime.Active -Recurse -Force -ErrorAction Stop
					}
					New-Item -ItemType Directory -Force -Path (Split-Path -Parent $runtime.Active) | Out-Null
					Move-Item -LiteralPath $runtime.Backup -Destination $runtime.Active -ErrorAction Stop
				} elseif (-not $runtime.HadActive -and (Test-Path -LiteralPath $runtime.Active)) {
					Remove-Item -LiteralPath $runtime.Active -Recurse -Force -ErrorAction Stop
				} elseif ($runtime.HadActive -and -not (Test-Path -LiteralPath $runtime.Active)) {
					throw "The previous active $($runtime.Label) runtime and its backup are both missing."
				}
				if (Test-Path -LiteralPath $runtime.Staging) {
					Remove-Item -LiteralPath $runtime.Staging -Recurse -Force -ErrorAction Stop
				}
			} catch {
				$failures.Add("$($runtime.Label): $($_.Exception.Message)")
			}
		}
		if ($failures.Count -ne 0) {
			throw "Runtime transaction recovery was incomplete: $($failures -join '; ')"
		}
		Remove-Item -LiteralPath $JournalPath -Force -ErrorAction Stop
	} catch {
		throw "Could not recover the interrupted runtime transaction: $($_.Exception.Message)"
	}
}

function Install-ArenaCoordinatorRuntime {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $SourceRoot,
		[Parameter(Mandatory)] [string] $InstalledPackageRoot,
		[ValidateSet('None', 'AfterCoordinatorPromotion', 'AfterNodePromotion', 'CrashAfterCoordinatorPromotion')]
		[string] $FailurePoint = 'None'
	)

	$resolvedSourceRoot = [IO.Path]::GetFullPath($SourceRoot)
	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$sourceCoordinator = Join-Path $resolvedSourceRoot 'coordinator'
	$sourceNodeDirectory = Join-Path $resolvedSourceRoot 'runtime\toolchains\node'
	$sourceNode = Join-Path $sourceNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
	foreach ($requiredRelativePath in @('src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $sourceCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator source is missing required file '$requiredRelativePath'."
		}
	}
	if (-not (Test-Path -LiteralPath $sourceNode -PathType Leaf)) {
		throw "Coordinator source is missing its bundled Node.js runtime: $sourceNode"
	}

	New-Item -ItemType Directory -Force -Path $resolvedInstalledRoot | Out-Null
	$lockPath = Join-Path $resolvedInstalledRoot '.arena-runtime-install.lock'
	$journalPath = Join-Path $resolvedInstalledRoot '.arena-runtime-transaction.json'
	$journalTempPath = Join-Path $resolvedInstalledRoot '.arena-runtime-transaction.json.tmp'
	$lock = $null
	try {
		try {
			$lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
		} catch {
			throw "Another Arena Agents runtime installation is already in progress: $($_.Exception.Message)"
		}
		Restore-ArenaRuntimeTransaction -InstalledRoot $resolvedInstalledRoot -JournalPath $journalPath
		if (Test-Path -LiteralPath $journalTempPath) {
			Remove-Item -LiteralPath $journalTempPath -Force -ErrorAction Stop
		}

		$activeCoordinator = Join-Path $resolvedInstalledRoot 'coordinator'
		$activeNodeDirectory = Join-Path $resolvedInstalledRoot 'runtime\toolchains\node'
		$transactionId = [Guid]::NewGuid().ToString('N')
		$stagingCoordinator = "$activeCoordinator.staging-$transactionId"
		$stagingNodeDirectory = "$activeNodeDirectory.staging-$transactionId"
		$backupPath = Join-Path $resolvedInstalledRoot "coordinator-backups\coordinator-$transactionId"
		$nodeBackupPath = Join-Path $resolvedInstalledRoot "node-runtime-backups\node-$transactionId"
		$hadCoordinator = Test-Path -LiteralPath $activeCoordinator
		$hadNode = Test-Path -LiteralPath $activeNodeDirectory
		$journal = [ordered]@{
			Version = 1
			TransactionId = $transactionId
			Coordinator = [ordered]@{
				Active = $activeCoordinator; Backup = $backupPath; Staging = $stagingCoordinator; HadActive = $hadCoordinator
			}
			Node = [ordered]@{
				Active = $activeNodeDirectory; Backup = $nodeBackupPath; Staging = $stagingNodeDirectory; HadActive = $hadNode
			}
		}
		[IO.File]::WriteAllText($journalTempPath, ($journal | ConvertTo-Json -Depth 4 -Compress))
		Move-Item -LiteralPath $journalTempPath -Destination $journalPath -Force -ErrorAction Stop

		$leaveInterrupted = $false
		try {
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

			if ($hadCoordinator) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backupPath) | Out-Null
				Move-Item -LiteralPath $activeCoordinator -Destination $backupPath -ErrorAction Stop
			}
			if ($hadNode) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $nodeBackupPath) | Out-Null
				Move-Item -LiteralPath $activeNodeDirectory -Destination $nodeBackupPath -ErrorAction Stop
			}
			Move-Item -LiteralPath $stagingCoordinator -Destination $activeCoordinator -ErrorAction Stop
			if ($FailurePoint -eq 'CrashAfterCoordinatorPromotion') {
				$leaveInterrupted = $true
				throw 'Injected hard interruption after coordinator promotion.'
			}
			if ($FailurePoint -eq 'AfterCoordinatorPromotion') { throw 'Injected failure after coordinator promotion.' }
			Move-Item -LiteralPath $stagingNodeDirectory -Destination $activeNodeDirectory -ErrorAction Stop
			if ($FailurePoint -eq 'AfterNodePromotion') { throw 'Injected failure after Node.js promotion.' }
			Remove-Item -LiteralPath $journalPath -Force -ErrorAction Stop
		} catch {
			$promotionFailure = $_
			if (-not $leaveInterrupted) {
				Restore-ArenaRuntimeTransaction -InstalledRoot $resolvedInstalledRoot -JournalPath $journalPath
			}
			throw $promotionFailure
		} finally {
			if (-not $leaveInterrupted -and (Test-Path -LiteralPath $journalTempPath)) {
				Remove-Item -LiteralPath $journalTempPath -Force -ErrorAction SilentlyContinue
			}
		}

		return [PSCustomObject]@{
			ActivePath = $activeCoordinator
			BackupPath = $(if ($hadCoordinator) { $backupPath } else { $null })
			NodePath = Join-Path $activeNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
			NodeBackupPath = $(if ($hadNode) { $nodeBackupPath } else { $null })
		}
	} finally {
		if ($null -ne $lock) { $lock.Dispose() }
	}
}
