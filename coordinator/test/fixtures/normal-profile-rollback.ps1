[CmdletBinding()]
param([string] $ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path }
$project = [IO.Path]::GetFullPath($ProjectRoot)

# Load the exact assertions used by the packaging gate, without running its
# artifact-dependent body. The runtime installer itself is not mocked.
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $project 'scripts/test-install-normal-profile-update.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Installer regression assertions did not parse.' }
foreach ($name in @('Get-FileSnapshot', 'Assert-SameSnapshot', 'Assert-RuntimeRollbackSnapshot', 'Assert-RuntimeTransactionCleanup', 'Assert-GenerationStateBackupPruning')) {
    $function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if ($null -eq $function) { throw "Missing packaging assertion: $name" }
    Invoke-Expression $function.Extent.Text
}
. (Join-Path $project 'scripts/distribution-runtime.ps1')

$fixture = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('arena-rollback-' + [guid]::NewGuid().ToString('N'))))
function Write-Fixture([string] $Path, [string] $Text) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not $absolute.StartsWith($fixture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture write escaped its temporary directory.' }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $absolute) | Out-Null
    [IO.File]::WriteAllText($absolute, $Text, [Text.UTF8Encoding]::new($false))
}
$negativeControls = 0
function Assert-Rejected([scriptblock] $Action, [string] $Label) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "Negative control was accepted: $Label" }
    $script:negativeControls += 1
}

try {
    $source = Join-Path $fixture 'source'
    $coordinator = Join-Path $source 'coordinator'
    foreach ($relative in @('src/dynamic-main.mjs', 'config/dynamic-agents.json', 'package.json')) {
        Write-Fixture (Join-Path $coordinator $relative) '{}'
    }
    $entries = @(Get-ChildItem -LiteralPath $coordinator -File -Recurse | Sort-Object FullName | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + ' ' + $_.FullName.Substring($coordinator.Length + 1).Replace('\', '/')
    })
    Write-Fixture (Join-Path $coordinator '.arena-agents-bundle-manifest') (($entries -join "`n") + "`n")
    # This installer entry point copies and hashes Node; it never executes it.
    $nodeRelative = if ($env:OS -eq 'Windows_NT') { 'runtime/toolchains/node/node.exe' } else { 'runtime/toolchains/node/bin/node' }
    Write-Fixture (Join-Path $source $nodeRelative) 'inert fixture Node bytes'
    $cases = 0
    foreach ($hasState in @($true, $false)) {
        foreach ($failurePoint in @('AfterNodePromotion', 'AfterCoordinatorPromotion', 'AfterGenerationStatePromotion')) {
            $installed = Join-Path $fixture ('case-' + $cases)
            Write-Fixture (Join-Path $installed 'coordinator/stale.log') 'prior coordinator'
            Write-Fixture (Join-Path $installed 'config/provider-settings.json') '{"provider":"offline-fixture"}'
            Write-Fixture (Join-Path $installed '.arena-runtime-install.lock') ''
            # Cover fresh Node promotion as well as restoring an older Node.
            if ($failurePoint -ne 'AfterNodePromotion') { Write-Fixture (Join-Path $installed $nodeRelative) 'old Node bytes' }
            if ($hasState) { Write-Fixture (Join-Path $installed 'runtime/coordinator-generation.properties') "phase=ready`n" }
            $oldBackup = 'generation-state-backups/state-' + ('0' * 32) + '.properties'
            Write-Fixture (Join-Path $installed $oldBackup) 'older recovery bytes'
            Write-Fixture (Join-Path $installed 'coordinator-backups/older/keep.txt') 'older coordinator backup'
            Write-Fixture (Join-Path $installed 'node-runtime-backups/older/keep.txt') 'older Node backup'
            $before = Get-FileSnapshot $installed
            $failure = $null
            try { Install-ArenaCoordinatorRuntime -SourceRoot $source -InstalledPackageRoot $installed -FailurePoint $failurePoint | Out-Null } catch { $failure = $_.Exception.Message }
            if ($failure -notmatch '^Injected failure after') { throw "Wrong failure at ${failurePoint}: $failure" }
            $after = Get-FileSnapshot $installed
            Assert-RuntimeRollbackSnapshot $before $after $true 'Runtime rollback contract failed.'
            Assert-RuntimeTransactionCleanup $installed
            # Before runtime installation, even a valid extra backup is forbidden.
            Assert-RuntimeRollbackSnapshot $before $before $false 'Unchanged pre-runtime state failed.'
            if ($hasState) {
                $added = @($after | Where-Object { $_ -cnotin $before })[0]
                Assert-Rejected { Assert-SameSnapshot $before $after 'Legacy exact snapshot rejects retained backup.' } 'legacy snapshot'
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $after $false 'Unexpected pre-runtime backup.' } 'pre-runtime backup'
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $before $true 'Missing backup.' } 'missing backup'
                $wrongName = @($after | ForEach-Object { if ($_ -ceq $added) { $_.Replace('state-', 'other-') } else { $_ } })
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $wrongName $true 'Wrong backup path.' } 'backup path'
                $wrongBytes = @($after | ForEach-Object { if ($_ -ceq $added) { $_.Split(':')[0] + ':' + ('F' * 64) } else { $_ } })
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $wrongBytes $true 'Wrong backup bytes.' } 'backup bytes'
                $extra = $after + ('generation-state-backups/state-' + ('1' * 32) + '.properties:' + $added.Split(':')[1])
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $extra $true 'Extra backup.' } 'backup count'
            } else {
                Assert-SameSnapshot $before $after 'No-state rollback changed files.'
                $unexpected = $after + ('generation-state-backups/state-' + ('1' * 32) + '.properties:' + ('F' * 64))
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $unexpected $true 'Unexpected no-state backup.' } 'no-state backup'
            }
            foreach ($changedPath in @('coordinator/stale.log', $oldBackup, 'coordinator-backups/older/keep.txt')) {
                $changed = @($after | ForEach-Object { if ($_.Split(':')[0] -ceq $changedPath) { $changedPath + ':' + ('F' * 64) } else { $_ } })
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $changed $true 'Changed prior file.' } "prior file $changedPath"
                $removed = @($after | Where-Object { $_.Split(':')[0] -cne $changedPath })
                Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $removed $true 'Missing prior file.' } "missing file $changedPath"
            }
            $unrelated = $after + ('unexpected.txt:' + ('F' * 64))
            Assert-Rejected { Assert-RuntimeRollbackSnapshot $before $unrelated $true 'Unexpected file.' } 'unrelated file'
            foreach ($artifact in @('.arena-runtime-transaction.json', '.arena-runtime-transaction.json.tmp', 'runtime/coordinator-generation.properties.staging-fixture')) {
                Write-Fixture (Join-Path $installed $artifact) 'leftover'
                Assert-Rejected { Assert-RuntimeTransactionCleanup $installed } "cleanup $artifact"
                Remove-Item -LiteralPath (Join-Path $installed $artifact) -Force
            }
            foreach ($artifact in @('coordinator.staging-fixture', 'coordinator.last-known-good.staging-fixture', 'runtime/toolchains/node.staging-fixture')) {
                New-Item -ItemType Directory -Path (Join-Path $installed $artifact) | Out-Null
                Assert-Rejected { Assert-RuntimeTransactionCleanup $installed } "empty staging $artifact"
                Remove-Item -LiteralPath (Join-Path $installed $artifact) -Force
            }
            $deployment = Install-ArenaCoordinatorRuntime -SourceRoot $source -InstalledPackageRoot $installed
            $successful = Get-FileSnapshot $installed
            Assert-GenerationStateBackupPruning $after $successful
            Assert-RuntimeTransactionCleanup $installed
            $remaining = @(Get-ChildItem -LiteralPath (Join-Path $installed 'generation-state-backups') -File -Recurse)
            if ($hasState -and ($remaining.Count -ne 1 -or $remaining[0].FullName -cne $deployment.GenerationStateBackupPath)) { throw 'Success kept the wrong transaction backup.' }
            Assert-Rejected { Assert-GenerationStateBackupPruning $after $after } 'obsolete backup pruning'
            if ($hasState) {
                $changed = @($successful | ForEach-Object { if ($_ -clike 'generation-state-backups/*') { $_.Split(':')[0] + ':' + ('F' * 64) } else { $_ } })
                Assert-Rejected { Assert-GenerationStateBackupPruning $after $changed } 'successful backup bytes'
            }
            $cases += 1
        }
    }
    Write-Output "Runtime rollback fixture passed: $cases installer cases; $negativeControls rejection controls; exact prior-file parity, retained state bytes, cleanup and follow-up pruning. Node fixture bytes were never executed."
} finally {
    # Resolve and verify the only recursive delete target before cleanup.
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notmatch '^arena-rollback-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup target.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
