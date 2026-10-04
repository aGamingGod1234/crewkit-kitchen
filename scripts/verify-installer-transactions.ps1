[CmdletBinding()]
param([string] $ScriptsRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ScriptsRoot)) { $ScriptsRoot = $PSScriptRoot }
. (Join-Path $ScriptsRoot 'distribution-runtime.ps1')

# Run only the real transaction/secret AST blocks, never acquisition, processes,
# launcher profiles, or a real runtime. Fault wrappers delegate to the filesystem.
function Read-ScriptAst([string] $Name) {
	$tokens = $null
	$errors = $null
	$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $Name), [ref] $tokens, [ref] $errors)
	if ($errors.Count) { throw "$Name did not parse: $errors" }
	return $ast
}
function Assert-Equal($Expected, $Actual, [string] $Message) {
	if ($Expected -cne $Actual) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

$testRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('arena-installer-transactions-' + [Guid]::NewGuid().ToString('N'))))
$script:fixtureRoot = $testRoot
$script:operations = [Collections.Generic.List[string]]::new()
$script:faultOperation = ''
$script:faultPath = ''
function Assert-FixturePath([string] $Path) {
	$resolved = [IO.Path]::GetFullPath($Path)
	if (-not $resolved.StartsWith($script:fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
		throw "Refusing filesystem operation outside the current fixture: $resolved"
	}
}
function Trace-Operation([string] $Operation, [string] $Path, [string] $Destination = '') {
	Assert-FixturePath $Path
	if ($Destination) { Assert-FixturePath $Destination }
	$relative = $Path.Substring($script:fixtureRoot.Length + 1)
	$entry = "$Operation $relative"
	if ($Destination) { $entry += ' -> ' + $Destination.Substring($script:fixtureRoot.Length + 1) }
	$script:operations.Add($entry)
	if ($Operation -eq $script:faultOperation -and $Path -eq $script:faultPath) {
		$script:faultOperation = ''
		throw "Injected $Operation fault before filesystem mutation"
	}
}
function Move-Item {
	[CmdletBinding()] param([string] $LiteralPath, [string] $Destination, [switch] $Force)
	Trace-Operation 'move' $LiteralPath $Destination
	Microsoft.PowerShell.Management\Move-Item @PSBoundParameters
}
function Remove-Item {
	[CmdletBinding()] param([string] $LiteralPath, [switch] $Recurse, [switch] $Force)
	Trace-Operation 'remove' $LiteralPath
	Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
}
function Copy-Item {
	[CmdletBinding()] param([string] $LiteralPath, [string] $Destination, [switch] $Force, [switch] $Recurse)
	Trace-Operation 'copy' $LiteralPath $Destination
	Microsoft.PowerShell.Management\Copy-Item @PSBoundParameters
}
function Write-FixtureFile([string] $Path, [string] $Content) {
	Assert-FixturePath $Path
	[IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
	[IO.File]::WriteAllText($Path, $Content)
}
function New-Fixture([string] $Name) {
	$script:fixtureRoot = Join-Path $testRoot $Name
	[IO.Directory]::CreateDirectory($script:fixtureRoot) | Out-Null
	$script:operations.Clear()
	$script:faultOperation = ''
	$script:faultPath = ''
}
function Assert-Failed([scriptblock] $Action, [string] $Pattern) {
	$failureMessage = ''
	try { & $Action } catch { $failureMessage = $_.Exception.Message }
	if ($failureMessage -notmatch $Pattern) { throw "Expected failure '$Pattern', got '$failureMessage'." }
}

$nodeAst = Read-ScriptAst 'install-node-runtime.ps1'
$transactions = @($nodeAst.FindAll({ param($ast)
	$ast -is [Management.Automation.Language.TryStatementAst] -and
	$ast.CatchClauses.Count -gt 0 -and
	$ast.Body.Extent.Text -match 'Move-Item -LiteralPath \$staging -Destination \$runtime' -and
	$ast.CatchClauses.Extent.Text -match 'rollback was incomplete'
}, $true))
if ($transactions.Count -ne 1) { throw 'Could not identify the Node promotion transaction.' }
$transactionStart = $nodeAst.Extent.Text.IndexOf('$backedUp = $false', [StringComparison]::Ordinal)
if ($transactionStart -lt 0) { throw 'Node transaction initialization is missing.' }
$nodeTransaction = [scriptblock]::Create($nodeAst.Extent.Text.Substring($transactionStart, $transactions[0].Extent.EndOffset - $transactionStart))

function Verify-NodeTransaction([string] $Fault, [bool] $HadActive = $true) {
	New-Fixture "node-$Fault-$HadActive"
	$runtime = Join-Path $script:fixtureRoot 'active'
	$backup = Join-Path $script:fixtureRoot 'backup'
	$staging = Join-Path $script:fixtureRoot 'staging'
	$node = Join-Path $runtime 'node.exe'
	$nodeVersion = 'fixture'
	if ($HadActive) { Write-FixtureFile $node 'original' }
	Write-FixtureFile (Join-Path $staging 'node.exe') 'replacement'
	function Test-PinnedNodeRuntime([string] $NodePath) {
		Assert-Equal $node $NodePath 'Verification must inspect the promoted runtime'
		Assert-Equal 'replacement' ([IO.File]::ReadAllText($NodePath)) 'Promotion must precede verification'
		$script:operations.Add('verify active')
		return $Fault -ne 'verification'
	}
	if ($Fault -eq 'backup') { $script:faultOperation = 'move'; $script:faultPath = $runtime }
	if ($Fault -eq 'promotion') { $script:faultOperation = 'move'; $script:faultPath = $staging }
	$failureMessage = ''
	try { . $nodeTransaction } catch { $failureMessage = $_.Exception.Message }
	if ($Fault -eq 'none') {
		Assert-Equal '' $failureMessage 'Successful transaction failed'
		Assert-Equal 'replacement' ([IO.File]::ReadAllText($node)) 'Successful promotion'
		Assert-Equal 'original' ([IO.File]::ReadAllText((Join-Path $backup 'node.exe'))) 'Original backup remains for outer cleanup'
		$expected = 'move active -> backup|move staging -> active|verify active'
	} else {
		if ($failureMessage -notmatch 'Injected|failed verification') { throw "Missing expected failure: $failureMessage" }
		if ($HadActive) {
			Assert-Equal 'original' ([IO.File]::ReadAllText($node)) 'Failed transaction must preserve original bytes'
		} else {
			Assert-Equal $false (Test-Path -LiteralPath $runtime) 'Failed fresh promotion must remove only the replacement'
		}
		switch ($Fault) {
			'backup' { $expected = 'move active -> backup' }
			'promotion' { $expected = 'move active -> backup|move staging -> active|move backup -> active' }
			'verification' {
				$expected = if ($HadActive) { 'move active -> backup|move staging -> active|verify active|remove active|move backup -> active' } else { 'move staging -> active|verify active|remove active' }
			}
		}
	}
	Assert-Equal $expected ($script:operations -join '|') 'Node filesystem ordering'
}

function Verify-Recovery([string] $Fault) {
	New-Fixture "recovery-$Fault"
	$journal = [ordered]@{}
	foreach ($name in @('Node', 'Coordinator', 'LastKnownGood', 'GenerationState')) {
		$isState = $name -eq 'GenerationState'
		$active = Join-Path $script:fixtureRoot "$name-active"
		$backup = Join-Path $script:fixtureRoot "$name-backup"
		$staging = Join-Path $script:fixtureRoot "$name-staging"
		$journal[$name] = [ordered]@{ Active = $active; Backup = $backup; Staging = $staging; HadActive = $true }
		Write-FixtureFile $(if ($isState) { $active } else { Join-Path $active 'contents' }) 'replacement'
		Write-FixtureFile $(if ($isState) { $backup } else { Join-Path $backup 'contents' }) 'original'
		Write-FixtureFile $(if ($isState) { $staging } else { Join-Path $staging 'contents' }) 'staged'
	}
	$journalPath = Join-Path $script:fixtureRoot 'journal.json'
	Write-FixtureFile $journalPath ($journal | ConvertTo-Json -Depth 4)
	switch ($Fault) {
		'node' { $script:faultOperation = 'move'; $script:faultPath = $journal.Node.Backup }
		'journal' { $script:faultOperation = 'remove'; $script:faultPath = $journalPath }
		'state' { $script:faultOperation = 'copy'; $script:faultPath = $journal.GenerationState.Backup }
	}
	Assert-Failed { Restore-ArenaRuntimeTransaction $script:fixtureRoot $journalPath } 'Injected'
	Assert-Equal $true (Test-Path -LiteralPath $journalPath) 'Partial recovery must retain its journal'
	Assert-Equal 'original' ([IO.File]::ReadAllText($journal.GenerationState.Backup)) 'Partial recovery must retain the state backup'
	if ($Fault -ne 'state') {
		Assert-Equal 'original' ([IO.File]::ReadAllText($journal.GenerationState.Active)) 'First recovery restores generation state even if another step fails'
	}
	Restore-ArenaRuntimeTransaction $script:fixtureRoot $journalPath
	foreach ($name in $journal.Keys) {
		$active = $journal[$name].Active
		Assert-Equal 'original' ([IO.File]::ReadAllText($(if ($name -eq 'GenerationState') { $active } else { Join-Path $active 'contents' }))) "Retry restores $name"
		Assert-Equal $false (Test-Path -LiteralPath $journal[$name].Staging) "Retry cleans $name staging"
	}
	Assert-Equal $false (Test-Path -LiteralPath $journalPath) 'Completed recovery removes its journal'
	Assert-Equal 'remove journal.json' $script:operations[$script:operations.Count - 1] 'Journal deletion must follow all restoration operations'
	Restore-ArenaRuntimeTransaction $script:fixtureRoot $journalPath
}

function Verify-LauncherSecret {
	New-Fixture 'launcher-secret'
	$launcherAst = Read-ScriptAst 'install-launcher-profiles.ps1'
	$blocks = @($launcherAst.FindAll({ param($ast)
		$ast -is [Management.Automation.Language.IfStatementAst] -and
		$ast.Extent.Text -match 'RandomNumberGenerator' -and
		$ast.Clauses[0].Item1.Extent.Text -eq '-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)'
	}, $true))
	if ($blocks.Count -ne 1) { throw 'Could not identify launcher secret creation.' }
	$secretBlock = [scriptblock]::Create($blocks[0].Extent.Text)
	$SecretPath = Join-Path $script:fixtureRoot 'secret.txt'
	$BridgeSecretBytes = 32
	. $secretBlock
	$first = [IO.File]::ReadAllText($SecretPath)
	if ($first -cnotmatch '^[0-9a-f]{64}$') { throw 'Secret must contain 32 bytes encoded as lowercase hex.' }
	. $secretBlock
	Assert-Equal $first ([IO.File]::ReadAllText($SecretPath)) 'Existing secret must remain unchanged'
	$assignment = $launcherAst.Find({ param($ast)
		$ast -is [Management.Automation.Language.AssignmentStatementAst] -and $ast.Left.Extent.Text -eq '$generatedSecret'
	}, $true)
	$bytes = [byte[]] @(0, 1, 15, 16, 127, 128, 255)
	Assert-Equal '00010f107f80ff' (& ([scriptblock]::Create($assignment.Right.Extent.Text))) 'Hex encoding must preserve leading zeroes'
}

function New-RuntimeBundle([string] $Root, [string] $Version) {
	$coordinator = Join-Path $Root 'coordinator'
	Write-FixtureFile (Join-Path $coordinator 'src/dynamic-main.mjs') "coordinator-$Version"
	Write-FixtureFile (Join-Path $coordinator 'config/dynamic-agents.json') '{}'
	Write-FixtureFile (Join-Path $coordinator 'package.json') '{"name":"undo-fixture"}'
	Write-FixtureFile (Join-Path $Root 'runtime/toolchains/node/node.exe') "node-$Version"
	$manifest = foreach ($relative in @('src/dynamic-main.mjs', 'config/dynamic-agents.json', 'package.json')) {
		"$((Get-FileHash -LiteralPath (Join-Path $coordinator $relative) -Algorithm SHA256).Hash.ToLowerInvariant()) $relative"
	}
	Write-FixtureFile (Join-Path $coordinator '.arena-agents-bundle-manifest') (($manifest -join "`n") + "`n")
}

function Verify-OuterUndo([string] $Fault, [bool] $RetryViaUndo = $true) {
	New-Fixture "outer-undo-$Fault-$RetryViaUndo"
	$old = Join-Path $script:fixtureRoot 'old'
	$new = Join-Path $script:fixtureRoot 'new'
	$installed = Join-Path $script:fixtureRoot 'installed'
	New-RuntimeBundle $old 'old'
	New-RuntimeBundle $new 'new'
	Install-ArenaCoordinatorRuntime $old $installed | Out-Null
	$statePath = Join-Path $installed 'runtime/coordinator-generation.properties'
	$oldState = [IO.File]::ReadAllText($statePath)
	$deployment = Install-ArenaCoordinatorRuntime $new $installed
	$journalPath = Join-Path $installed '.arena-runtime-transaction.json'
	Write-FixtureFile (Join-Path $installed 'unrelated.txt') 'must remain'
	if ($Fault -eq 'foreign-journal') {
		Write-FixtureFile $journalPath '{"TransactionId":"another-installation"}'
		Assert-Failed { Undo-ArenaCoordinatorRuntimeInstall $installed $deployment } 'another runtime transaction'
		Assert-Equal '{"TransactionId":"another-installation"}' ([IO.File]::ReadAllText($journalPath)) 'Foreign journal preserved'
		Assert-Equal 'node-new' ([IO.File]::ReadAllText((Join-Path $installed 'runtime/toolchains/node/node.exe'))) 'Foreign transaction prevents mutation'
		return
	}
	if ($Fault -eq 'node') { $script:faultOperation = 'move'; $script:faultPath = $deployment.NodeBackupPath }
	if ($Fault -eq 'state') { $script:faultOperation = 'copy'; $script:faultPath = $deployment.GenerationStateBackupPath }
	if ($Fault -eq 'journal') { $script:faultOperation = 'remove'; $script:faultPath = $journalPath }
	if ($Fault -ne 'none') {
		# Exercise the actual package catch, after a simulated later package failure.
		$ast = Read-ScriptAst 'install-distribution.ps1'
		$outer = $ast.Find({ param($n) $n -is [Management.Automation.Language.CatchClauseAst] -and $n.Body.Extent.Text.Contains('Undo-ArenaCoordinatorRuntimeInstall') }, $true)
		$body = $outer.Body.Extent.Text
		$catchBlock = [scriptblock]::Create($body.Substring(1, $body.Length - 2))
		$InstalledPackageRoot = $installed; $runtimeDeployment = $deployment
		$profileReplaced = $false; $modsMutationStarted = $false
		$backupRoot = Join-Path $script:fixtureRoot 'package-backup'
		Assert-Failed { try { throw 'Later package failure' } catch { . $catchBlock } } 'Rollback was incomplete: runtime:'
		Assert-Equal $true (Test-Path -LiteralPath $journalPath) 'Outer Undo must retain durable recovery after a fault'
		Assert-Equal 'must remain' ([IO.File]::ReadAllText((Join-Path $installed 'unrelated.txt'))) 'Unrelated installed content'
	}
	if ($RetryViaUndo) { Undo-ArenaCoordinatorRuntimeInstall $installed $deployment }
	else { Restore-ArenaRuntimeTransaction $installed $journalPath }
	Assert-Equal 'coordinator-old' ([IO.File]::ReadAllText((Join-Path $installed 'coordinator/src/dynamic-main.mjs'))) 'Undo original coordinator'
	Assert-Equal 'node-old' ([IO.File]::ReadAllText((Join-Path $installed 'runtime/toolchains/node/node.exe'))) 'Undo original Node'
	Assert-Equal $oldState ([IO.File]::ReadAllText($statePath)) 'Undo exact original state'
	Assert-Equal $false (Test-Path -LiteralPath $journalPath) 'Completed Undo removes recovery journal'
}

function Verify-DistributionProcessPreflight {
	$ast = Read-ScriptAst 'install-distribution.ps1'
	$source = $ast.Extent.Text
	$start = $source.IndexOf('if (Get-Process -Name MinecraftLauncher', [StringComparison]::Ordinal)
	$end = $source.IndexOf('if ([string]::IsNullOrWhiteSpace($JavaPath))', $start, [StringComparison]::Ordinal)
	$definitions = @($ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-UnsafeJavaProcess' }, $true))
	$preflight = [scriptblock]::Create((($definitions | ForEach-Object { $_.Extent.Text }) -join "`n") + "`n" + $source.Substring($start, $end - $start))
	foreach ($case in @(
		@{name='empty'; javaName=''; command=''; blocked=$false},
		@{name='launcher'; javaName=''; command=''; blocked=$true},
		@{name='javaw'; javaName='javaw.exe'; command=''; blocked=$true},
		@{name='Minecraft'; javaName='java.exe'; command='net.minecraft.client.main.Main'; blocked=$true},
		@{name='Fabric'; javaName='java.exe'; command='net.fabricmc.loader.impl.launch.knot.KnotClient'; blocked=$true},
		@{name='server'; javaName='java.exe'; command='KnotServer'; blocked=$true},
		@{name='Gradle'; javaName='java.exe'; command='org.gradle.launcher.daemon.bootstrap.GradleDaemon'; blocked=$false},
		@{name='missing command'; javaName='java.exe'; command=''; blocked=$true},
		@{name='inspection failed'; javaName=''; command=''; blocked=$true}
	)) {
		function Get-Process { [CmdletBinding()] param([string[]] $Name) if ($case.name -eq 'launcher') { [pscustomobject]@{Name='MinecraftLauncher'} } }
		function Get-CimInstance {
			[CmdletBinding()] param([string] $ClassName, [string] $Filter)
			Assert-Equal 'Win32_Process' $ClassName 'Process query class'
			Assert-Equal "Name = 'java.exe' OR Name = 'javaw.exe'" $Filter 'Process query filter'
			if ($case.name -eq 'inspection failed') { throw 'Injected inspection failure' }
			if ($case.javaName) { [pscustomobject]@{Name=$case.javaName; CommandLine=$case.command; ProcessId=12345} }
		}
		$blocked = $false
		try { & $preflight } catch {
			if ($_.Exception.Message -notmatch '^(Close Minecraft|A Minecraft/Fabric Java process is active|Unable to inspect Java process command lines)') { throw }
			$blocked = $true
		}
		Assert-Equal $case.blocked $blocked "Distribution preflight: $($case.name)"
	}
}

function Verify-JdkReplacement([string] $Case) {
	New-Fixture "jdk-$Case"
	$ToolchainRoot = Join-Path $script:fixtureRoot 'toolchains'
	$JdkRoot = Join-Path $ToolchainRoot 'jdk-25.0.3+9'
	$Java = Join-Path $JdkRoot 'bin/java.exe'
	$ExpectedVersion = '25.0.3'
	$ArchivePath = Join-Path $script:fixtureRoot 'archive.zip'
	Write-FixtureFile $ArchivePath 'fixture archive, expansion supplied below'
	$ArchiveSha256 = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
	Write-FixtureFile (Join-Path $ToolchainRoot '.extracting/unrelated.txt') 'other extraction'
	if ($Case -ne 'absent') { Write-FixtureFile (Join-Path $JdkRoot 'unrelated.txt') 'original unrelated bytes' }
	if ($Case -in @('rejected', 'healthy')) { Write-FixtureFile $Java $Case }
	$script:validationPaths = [Collections.Generic.List[string]]::new()
	$script:jdkFileLock = $null
	function Assert-Java25([string] $JavaPath) {
		Assert-FixturePath $JavaPath
		$script:validationPaths.Add($JavaPath)
		$value = [IO.File]::ReadAllText($JavaPath)
		if ($value -notin @('candidate-valid', 'healthy')) { throw 'Rejected fixture Java version' }
		if ($Case -eq 'promotion-fault') {
			# A real Windows sharing violation challenges the atomic rename, not a mock.
			$script:jdkFileLock = [IO.File]::Open($JavaPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
		}
	}
	function Expand-Archive {
		[CmdletBinding()] param([string] $LiteralPath, [string] $DestinationPath)
		Assert-FixturePath $LiteralPath; Assert-FixturePath $DestinationPath
		Write-FixtureFile (Join-Path $DestinationPath 'jdk-25.0.3+9/bin/java.exe') $(if ($Case -eq 'invalid-candidate') { 'bad' } else { 'candidate-valid' })
	}
	$ast = Read-ScriptAst 'install-toolchain.ps1'
	# Keep the real control flow and filesystem calls; replace only archive and JVM effects.
	$start = $ast.Extent.Text.IndexOf('if (Test-Path -LiteralPath $Java -PathType Leaf)', [StringComparison]::Ordinal)
	$functions = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ne 'Assert-Java25' })
	# The original script's exit must return from this fixture, not end the verifier host.
	$block = [scriptblock]::Create((($functions | ForEach-Object { $_.Extent.Text }) -join "`n") + "`n" + $ast.Extent.Text.Substring($start).Replace('exit 0', 'return'))
	if ($Case -in @('invalid-candidate', 'promotion-fault')) {
		try { Assert-Failed { & $block } 'Rejected fixture|being used|access|Move' }
		finally { if ($null -ne $script:jdkFileLock) { $script:jdkFileLock.Dispose(); $script:jdkFileLock = $null } }
		Assert-Equal 'original unrelated bytes' ([IO.File]::ReadAllText((Join-Path $JdkRoot 'unrelated.txt'))) 'Rejected or unpromoted candidate retains original destination'
		if ($Case -eq 'promotion-fault') {
			$Case = 'retry'
			& $block
			Assert-Equal 'candidate-valid' ([IO.File]::ReadAllText($Java)) 'Retry repairs the direct JDK path'
		}
	} else {
		& $block
		Assert-Equal $(if ($Case -eq 'healthy') { 'healthy' } else { 'candidate-valid' }) ([IO.File]::ReadAllText($Java)) 'JDK direct final path'
		if ($Case -notin @('absent','healthy')) {
			$backup = @(Get-ChildItem -LiteralPath $ToolchainRoot -Directory | Where-Object { $_.Name -like 'jdk-25.0.3+9.backup-*' })
			Assert-Equal 1 $backup.Count 'Replaced destination kept as exact backup'
			Assert-Equal 'original unrelated bytes' ([IO.File]::ReadAllText((Join-Path $backup[0].FullName 'unrelated.txt'))) 'Preserved unrelated bytes in backup'
		}
		if ($Case -ne 'healthy') {
			$stagedValidations = @($script:validationPaths | Where-Object { $_ -like '*.extracting-*' })
			Assert-Equal 1 $stagedValidations.Count 'Candidate validated before publishing'
		}
	}
	Assert-Equal 'other extraction' ([IO.File]::ReadAllText((Join-Path $ToolchainRoot '.extracting/unrelated.txt'))) 'Unrelated pre-existing staging preserved'
	Assert-Equal $false (Test-Path -LiteralPath (Join-Path $JdkRoot 'jdk-25.0.3+9')) 'No implicit nested JDK'
}

function Verify-JdkPathOwnership {
	New-Fixture 'jdk-path-ownership'
	$ToolchainRoot = Join-Path $script:fixtureRoot 'toolchains'
	$ast = Read-ScriptAst 'install-toolchain.ps1'
	$definition = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-JdkOwnedPath' }, $true)
	. ([scriptblock]::Create($definition.Extent.Text))
	$outside = Join-Path $script:fixtureRoot 'unrelated'
	Write-FixtureFile (Join-Path $outside 'keep.txt') 'outside toolchain'
	Assert-Failed { Assert-JdkOwnedPath (Join-Path $ToolchainRoot '../unrelated') } 'outside its toolchain root'
	[IO.Directory]::CreateDirectory($ToolchainRoot) | Out-Null
	$link = Join-Path $ToolchainRoot 'linked'
	New-Item -ItemType Junction -Path $link -Target $outside | Out-Null
	try { Assert-Failed { Assert-JdkOwnedPath (Join-Path $link 'jdk') } 'reparse point' }
	finally { Assert-FixturePath $link; [IO.Directory]::Delete($link) }
	Assert-Equal 'outside toolchain' ([IO.File]::ReadAllText((Join-Path $outside 'keep.txt'))) 'Junction target remains unchanged'
}

$failures = [Collections.Generic.List[string]]::new()
try {
	Write-Host "PowerShell $($PSVersionTable.PSVersion) transaction regression checks"
	$cases = [ordered]@{
		'Node backup failure preserves original' = { Verify-NodeTransaction 'backup' }
		'Node promotion failure restores backup' = { Verify-NodeTransaction 'promotion' }
		'Node verification failure restores backup' = { Verify-NodeTransaction 'verification' }
		'Fresh Node verification failure removes replacement' = { Verify-NodeTransaction 'verification' $false }
		'Node successful transaction preserves backup' = { Verify-NodeTransaction 'none' }
		'Partial directory recovery can retry' = { Verify-Recovery 'node' }
		'Journal deletion failure can retry' = { Verify-Recovery 'journal' }
		'State copy failure can retry' = { Verify-Recovery 'state' }
		'Launcher secret generation and preservation' = { Verify-LauncherSecret }
		'Outer Undo healthy control' = { Verify-OuterUndo 'none' }
		'Outer Undo Node restoration retries' = { Verify-OuterUndo 'node' }
		'Outer Undo durable recovery without deployment object' = { Verify-OuterUndo 'node' $false }
		'Outer Undo state restoration retries' = { Verify-OuterUndo 'state' }
		'Outer Undo journal removal retries' = { Verify-OuterUndo 'journal' }
		'Outer Undo rejects another transaction journal' = { Verify-OuterUndo 'foreign-journal' }
		'Distribution Java process preflight' = { Verify-DistributionProcessPreflight }
		'JDK absent destination' = { Verify-JdkReplacement 'absent' }
		'JDK incomplete destination' = { Verify-JdkReplacement 'incomplete' }
		'JDK rejected destination' = { Verify-JdkReplacement 'rejected' }
		'JDK healthy reuse' = { Verify-JdkReplacement 'healthy' }
		'JDK invalid candidate preserves destination' = { Verify-JdkReplacement 'invalid-candidate' }
		'JDK promotion failure restores destination' = { Verify-JdkReplacement 'promotion-fault' }
		'JDK resolved path ownership' = { Verify-JdkPathOwnership }
	}
	foreach ($name in $cases.Keys) {
		try { & $cases[$name]; Write-Host "PASS: $name" } catch {
			$failures.Add("${name}: $($_.Exception.Message)")
			Write-Host "FAIL: $name - $($_.Exception.Message)"
		}
	}
} finally {
	if (Test-Path -LiteralPath $testRoot) {
		$resolved = (Resolve-Path -LiteralPath $testRoot).Path
		$tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
		if ($resolved -cne $testRoot -or -not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
			throw "Refusing to remove unexpected fixture root: $resolved"
		}
		Microsoft.PowerShell.Management\Remove-Item -LiteralPath $resolved -Recurse -Force
	}
}
if ($failures.Count) { throw ($failures -join "`n") }
