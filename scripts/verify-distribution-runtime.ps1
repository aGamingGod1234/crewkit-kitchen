[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helperPath = Join-Path $PSScriptRoot 'distribution-runtime.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
	throw "Missing coordinator runtime deployment helper: $helperPath"
}

. $helperPath

function Assert-Equal {
	param([object] $Expected, [object] $Actual, [string] $Message)
	if ($Expected -ne $Actual) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-agents-runtime-test-" + [Guid]::NewGuid().ToString('N'))
try {
	$sourceRoot = Join-Path $testRoot 'source'
	$installedRoot = Join-Path $testRoot 'installed'
	$sourceCoordinator = Join-Path $sourceRoot 'coordinator'
	$activeCoordinator = Join-Path $installedRoot 'coordinator'
	$sourceNode = Join-Path $sourceRoot $(if ($env:OS -eq 'Windows_NT') { 'runtime\toolchains\node\node.exe' } else { 'runtime/toolchains/node/bin/node' })
	$activeNode = Join-Path $installedRoot $(if ($env:OS -eq 'Windows_NT') { 'runtime\toolchains\node\node.exe' } else { 'runtime/toolchains/node/bin/node' })
	New-Item -ItemType Directory -Force -Path (Join-Path $sourceCoordinator 'src'), (Join-Path $sourceCoordinator 'config'), $activeCoordinator, (Split-Path -Parent $sourceNode) | Out-Null
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'config\dynamic-agents.json'), '{"version":"new"}')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'package.json'), '{"name":"arena-agents-test"}')
	[IO.File]::WriteAllText($sourceNode, 'node-runtime-v1')
	[IO.File]::WriteAllText((Join-Path $activeCoordinator 'legacy.txt'), 'old-runtime')

	$result = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'The active coordinator must come from the source package'
	if (Test-Path -LiteralPath (Join-Path $activeCoordinator 'legacy.txt')) { throw 'The active coordinator retained stale files after replacement.' }
	if ([string]::IsNullOrWhiteSpace($result.BackupPath) -or -not (Test-Path -LiteralPath (Join-Path $result.BackupPath 'legacy.txt'))) {
		throw 'The replaced coordinator was not retained in a backup.'
	}
	Assert-Equal 'node-runtime-v1' ([IO.File]::ReadAllText($activeNode)) 'The bundled Node.js runtime must be installed with the coordinator'
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime-v2')
	[IO.File]::WriteAllText($sourceNode, 'node-runtime-v2')
	foreach ($failurePoint in @('AfterCoordinatorPromotion', 'AfterNodePromotion')) {
		$failed = $false
		try {
			Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot -FailurePoint $failurePoint | Out-Null
		} catch {
			$failed = $true
		}
		if (-not $failed) { throw "Injected runtime deployment failure did not fail at $failurePoint." }
		Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) "Coordinator rollback failed at $failurePoint"
		Assert-Equal 'node-runtime-v1' ([IO.File]::ReadAllText($activeNode)) "Node.js rollback failed at $failurePoint"
		$stagingLeaks = @(Get-ChildItem -LiteralPath $installedRoot -Recurse -Directory | Where-Object { $_.Name -like '*.staging-*' })
		if ($stagingLeaks.Count -ne 0) { throw "Runtime rollback retained staging directories at $failurePoint." }
	}
	Write-Host 'PASS: coordinator and Node.js roll back together after either promotion fails'

	$interrupted = $false
	try {
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot -FailurePoint CrashAfterCoordinatorPromotion | Out-Null
	} catch {
		$interrupted = $true
	}
	if (-not $interrupted) { throw 'Injected hard runtime interruption did not fail.' }
	if (-not (Test-Path -LiteralPath (Join-Path $installedRoot '.arena-runtime-transaction.json') -PathType Leaf)) {
		throw 'Hard interruption did not retain its recovery journal.'
	}
	$recovered = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Recovery must promote a coherent coordinator version'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'Recovery must promote the matching bundled Node.js version'
	if (Test-Path -LiteralPath (Join-Path $installedRoot '.arena-runtime-transaction.json')) {
		throw 'Successful interruption recovery retained its transaction journal.'
	}
	Write-Host 'PASS: an interrupted two-directory promotion recovers on the next install'

	$heldLock = [IO.File]::Open(
		(Join-Path $installedRoot '.arena-runtime-install.lock'),
		[IO.FileMode]::OpenOrCreate,
		[IO.FileAccess]::ReadWrite,
		[IO.FileShare]::None
	)
	$concurrentInstallRejected = $false
	try {
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot | Out-Null
	} catch {
		$concurrentInstallRejected = $_.Exception.Message -match 'already in progress'
	} finally {
		$heldLock.Dispose()
	}
	if (-not $concurrentInstallRejected) { throw 'A concurrent runtime installation was not rejected by the exclusive lock.' }
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Concurrent rejection must not modify the active coordinator'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'Concurrent rejection must not modify the active Node.js runtime'
	Write-Host 'PASS: concurrent runtime installation is rejected without changing active files'

	$second = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'A repeated install must promote the newest coordinator'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'A repeated install must promote the newest bundled Node.js runtime'
	if ($second.BackupPath -eq $recovered.BackupPath -or $second.BackupPath -eq $result.BackupPath) { throw 'Rapid coordinator updates must use unique backup paths.' }
	if ([string]::IsNullOrWhiteSpace($second.NodeBackupPath) -or -not (Test-Path -LiteralPath $second.NodeBackupPath)) {
		throw 'The replaced bundled Node.js runtime was not retained in a backup.'
	}
	Write-Host 'PASS: coordinator runtime deployment replaces stale files and retains a backup'
	foreach ($version in 3..5) {
		[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), "new-runtime-v$version")
		[IO.File]::WriteAllText($sourceNode, "node-runtime-v$version")
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot | Out-Null
	}
	Assert-Equal 'new-runtime-v5' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Repeated installs must retain the newest coordinator'
	Assert-Equal 'node-runtime-v5' ([IO.File]::ReadAllText($activeNode)) 'Repeated installs must retain the newest Node.js runtime'
	$coordinatorBackups = @(Get-ChildItem -LiteralPath (Join-Path $installedRoot 'coordinator-backups') -Directory -Force)
	$nodeBackups = @(Get-ChildItem -LiteralPath (Join-Path $installedRoot 'node-runtime-backups') -Directory -Force)
	Assert-Equal 1 $coordinatorBackups.Count 'Repeated installs must retain only one coordinator backup'
	Assert-Equal 1 $nodeBackups.Count 'Repeated installs must retain only one Node.js backup'
	Write-Host 'PASS: repeated installs keep one bounded last-known-good runtime generation'
	Write-Host 'PASS: bundled Node.js deployment and rapid repeated updates are self-contained'
} finally {
	if (Test-Path -LiteralPath $testRoot) {
		$resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
		if (-not $resolvedTestRoot.StartsWith(([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
			throw "Refusing to remove test path outside the temporary directory: $resolvedTestRoot"
		}
		Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
	}
}
