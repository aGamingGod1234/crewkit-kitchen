[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helperPath = Join-Path $PSScriptRoot 'distribution-runtime.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) { throw "Missing coordinator runtime deployment helper: $helperPath" }
. $helperPath

function Assert-Equal([object] $Expected, [object] $Actual, [string] $Message) {
	if ($Expected -ne $Actual) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

function New-TestSource([string] $Root, [string] $Version, [string] $DefaultConfig) {
	$coordinator = Join-Path $Root 'coordinator'
	New-Item -ItemType Directory -Force -Path (Join-Path $coordinator 'src'), (Join-Path $coordinator 'config') | Out-Null
	[IO.File]::WriteAllText((Join-Path $coordinator 'src\dynamic-main.mjs'), "runtime-$Version")
	[IO.File]::WriteAllText((Join-Path $coordinator 'config\dynamic-agents.json'), $DefaultConfig)
	[IO.File]::WriteAllText((Join-Path $coordinator 'package.json'), "{`"name`":`"arena-agents-$Version`"}")
}

function Get-Hash([string] $Path) {
	return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-agents-runtime-test-" + [Guid]::NewGuid().ToString('N'))
try {
	$sourceA = Join-Path $testRoot 'source-a'
	$sourceB = Join-Path $testRoot 'source-b'
	$sourceC = Join-Path $testRoot 'source-c'
	$installedRoot = Join-Path $testRoot 'installed'
	New-TestSource $sourceA 'a' '{"version":"default-a"}'
	New-TestSource $sourceB 'b' '{"version":"default-b"}'
	New-TestSource $sourceC 'c' '{"version":"default-c"}'
	$legacyConfig = Join-Path $installedRoot 'coordinator\config\dynamic-agents.json'
	New-Item -ItemType Directory -Force -Path (Split-Path -Parent $legacyConfig) | Out-Null
	[IO.File]::WriteAllText($legacyConfig, '{"version":"legacy-custom"}')
	$legacyConfigHash = Get-Hash $legacyConfig

	$first = Install-ArenaCoordinatorRuntime -SourceRoot $sourceA -InstalledPackageRoot $installedRoot
	if (-not $first.Changed -or -not $first.Candidate) { throw 'First runtime must activate as an unverified candidate.' }
	$missingGeneration = ('f' * 64)
	Write-ArenaGenerationState $installedRoot @{
		Phase = 'activate'; ActiveGeneration = $missingGeneration; VerifiedGeneration = ''
		CandidateGeneration = $missingGeneration; LastKnownGoodGeneration = ''
		StagingDirectory = "coordinator.staging-$missingGeneration"; PreviousActiveGeneration = $first.GenerationId
	}
	try {
		Complete-ArenaGenerationJournal $installedRoot
		throw 'Missing candidate journal unexpectedly recovered.'
	} catch {
		if ($_.Exception.Message -notmatch 'no validated runnable generation') { throw }
	}
	Assert-Equal 'runtime-a' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator\src\dynamic-main.mjs'))) 'Missing LKG recovery must preserve the sole runnable active generation'
	Assert-Equal $first.GenerationId (Get-ArenaInstalledGeneration (Join-Path $installedRoot 'coordinator')) 'Sole active generation must remain hash-valid after failed recovery'
	Write-ArenaGenerationState $installedRoot (Get-ArenaReadyState $first.GenerationId '' $first.GenerationId '')
	Confirm-ArenaCoordinatorGeneration -InstalledPackageRoot $installedRoot -GenerationId $first.GenerationId | Out-Null
	$config = Join-Path $installedRoot 'runtime\dynamic-agents.json'
	Assert-Equal $legacyConfigHash (Get-Hash $config) 'Legacy mutable config must migrate without changing its bytes'
	$secret = Join-Path $installedRoot 'runtime\bridge-secret.txt'
	$fishKey = Join-Path $installedRoot 'runtime\fish-api-key.txt'
	[IO.File]::WriteAllText($config, '{"version":"custom-user-config"}')
	[IO.File]::WriteAllText($secret, ('s' * 64))
	[IO.File]::WriteAllText($fishKey, 'external-provider-key')
	$configHash = Get-Hash $config
	$secretHash = Get-Hash $secret
	$fishHash = Get-Hash $fishKey

	$second = Install-ArenaCoordinatorRuntime -SourceRoot $sourceB -InstalledPackageRoot $installedRoot
	Assert-Equal 'runtime-b' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator\src\dynamic-main.mjs'))) 'Generation B must own the active path'
	Assert-Equal 'runtime-a' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator.last-known-good\src\dynamic-main.mjs'))) 'Verified generation A must be the only rollback target'
	Assert-Equal $configHash (Get-Hash $config) 'Candidate activation must preserve canonical config bytes'
	Assert-Equal $secretHash (Get-Hash $secret) 'Candidate activation must preserve bridge-secret bytes'
	Assert-Equal $fishHash (Get-Hash $fishKey) 'Candidate activation must preserve provider credential bytes'
	if (-not (Restore-ArenaCoordinatorGeneration -InstalledPackageRoot $installedRoot -GenerationId $second.GenerationId)) {
		throw 'Generation B rollback did not occur.'
	}
	Assert-Equal 'runtime-a' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator\src\dynamic-main.mjs'))) 'Rollback must restore generation A'
	if (Restore-ArenaCoordinatorGeneration -InstalledPackageRoot $installedRoot -GenerationId $second.GenerationId) {
		throw 'Repeated rollback must be idempotent.'
	}

	$secondRetry = Install-ArenaCoordinatorRuntime -SourceRoot $sourceB -InstalledPackageRoot $installedRoot
	Confirm-ArenaCoordinatorGeneration -InstalledPackageRoot $installedRoot -GenerationId $secondRetry.GenerationId | Out-Null
	$repeat = Install-ArenaCoordinatorRuntime -SourceRoot $sourceB -InstalledPackageRoot $installedRoot
	if ($repeat.Changed -or $repeat.Candidate) { throw 'Repeated install of promoted generation B must be idempotent.' }

	try {
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceC -InstalledPackageRoot $installedRoot -FailurePoint AfterRetain | Out-Null
		throw 'Injected interruption after retaining last-known-good did not occur.'
	} catch {
		if ($_.Exception.Message -notmatch 'Injected runtime deployment failure') { throw }
	}
	if (Test-Path -LiteralPath (Join-Path $installedRoot 'coordinator')) { throw 'Interrupted swap unexpectedly published an ambiguous active directory.' }
	Assert-Equal 'runtime-b' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator.last-known-good\src\dynamic-main.mjs'))) 'Interrupted swap must retain verified B'
	$recovered = Install-ArenaCoordinatorRuntime -SourceRoot $sourceC -InstalledPackageRoot $installedRoot
	if ($recovered.Changed) { throw 'Journal recovery must complete before the repeated install is classified idempotent.' }
	Assert-Equal 'runtime-c' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator\src\dynamic-main.mjs'))) 'Journal recovery must publish generation C deterministically'
	Assert-Equal 'runtime-b' ([IO.File]::ReadAllText((Join-Path $installedRoot 'coordinator.last-known-good\src\dynamic-main.mjs'))) 'Journal recovery must retain verified generation B'
	if (@(Get-ChildItem -LiteralPath $installedRoot -Directory -Filter 'coordinator.staging-*').Count -ne 0) { throw 'Recovered deployment left disposable staging directories.' }
	Assert-Equal $configHash (Get-Hash $config) 'Interrupted recovery must preserve canonical config bytes'
	Assert-Equal $secretHash (Get-Hash $secret) 'Interrupted recovery must preserve bridge-secret bytes'
	Assert-Equal $fishHash (Get-Hash $fishKey) 'Interrupted recovery must preserve provider credential bytes'

	Write-Host 'PASS: coordinator runtime generations activate, promote, roll back, recover, and preserve external state'
} finally {
	if (Test-Path -LiteralPath $testRoot) {
		$resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
		if (-not $resolvedTestRoot.StartsWith(([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
			throw "Refusing to remove test path outside the temporary directory: $resolvedTestRoot"
		}
		Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
	}
}
