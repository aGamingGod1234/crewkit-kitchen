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
	New-Item -ItemType Directory -Force -Path (Join-Path $sourceCoordinator 'src'), (Join-Path $sourceCoordinator 'config'), $activeCoordinator | Out-Null
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'config\dynamic-agents.json'), '{"version":"new"}')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'package.json'), '{"name":"arena-agents-test"}')
	[IO.File]::WriteAllText((Join-Path $activeCoordinator 'legacy.txt'), 'old-runtime')

	$result = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'The active coordinator must come from the source package'
	if (Test-Path -LiteralPath (Join-Path $activeCoordinator 'legacy.txt')) { throw 'The active coordinator retained stale files after replacement.' }
	if ([string]::IsNullOrWhiteSpace($result.BackupPath) -or -not (Test-Path -LiteralPath (Join-Path $result.BackupPath 'legacy.txt'))) {
		throw 'The replaced coordinator was not retained in a backup.'
	}
	Write-Host 'PASS: coordinator runtime deployment replaces stale files and retains a backup'
} finally {
	if (Test-Path -LiteralPath $testRoot) {
		$resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
		if (-not $resolvedTestRoot.StartsWith(([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
			throw "Refusing to remove test path outside the temporary directory: $resolvedTestRoot"
		}
		Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
	}
}
