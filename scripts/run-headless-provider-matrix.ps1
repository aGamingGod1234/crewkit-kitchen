[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)] [string] $ProjectRoot,
	[string] $MatrixPath,
	[string] $ScenarioId,
	[string] $ServerTemplate,
	[switch] $RequireAll,
	[switch] $KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PollMilliseconds = 250
$StartupTimeoutSeconds = 120
$CleanupTimeoutSeconds = 30
$configuredStartupTimeout = 0
$configuredCleanupTimeout = 0
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS'), [ref] $configuredStartupTimeout) -and $configuredStartupTimeout -gt 0) { $StartupTimeoutSeconds = $configuredStartupTimeout }
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_CLEANUP_TIMEOUT_SECONDS'), [ref] $configuredCleanupTimeout) -and $configuredCleanupTimeout -gt 0) { $CleanupTimeoutSeconds = $configuredCleanupTimeout }
$MaxPortAttempts = 30
$StartupBindRetries = 2
$OutputDrainTimeoutMilliseconds = 1000

function Quote-Argument([string] $Value) {
	return '"' + $Value.Replace('"', '\"') + '"'
}

function Read-Text([string] $Path) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
	return [IO.File]::ReadAllText($Path)
}

function Test-BindFailure([string] $ServerLogPath, [string] $ServerStderrPath) {
	$text = "$(Read-Text $ServerLogPath)`n$(Read-Text $ServerStderrPath)"
	return $text -match '(?i)(address already in use|failed to bind|could not bind|bind.+failed|port.+already)'
}

function Protect-LocalFile([string] $Path) {
	# Do not inherit a broad ACL for generated credentials or their server config.
	$grant = "$($env:USERNAME):(R,W)"
	& icacls.exe $Path /inheritance:r /grant:r $grant | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "Could not restrict permissions on generated secret: $Path" }
}

function Write-PrivateText([string] $Path, [string] $Value) {
	$parent = Split-Path -Parent $Path
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	[IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
	Protect-LocalFile $Path
}

function Wait-Condition([scriptblock] $Condition, [int] $TimeoutSeconds, [string] $FailureMessage) {
	$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline) {
		if (& $Condition) { return }
		Start-Sleep -Milliseconds $PollMilliseconds
	}
	throw $FailureMessage
}

function Get-ProcessCommand([string] $Name) {
	$command = Get-Command $Name -ErrorAction SilentlyContinue
	if ($null -eq $command) { return $null }
	return $command.Source
}

function Test-Port([int] $Port) {
	$connections = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
	return $null -ne ($connections | Select-Object -First 1)
}

function Reserve-FreePort([int] $Preferred = 0, [int[]] $Exclude = @()) {
	if ($Preferred -gt 0) {
		if ($Exclude -contains $Preferred) { throw "Configured ports must be distinct; port $Preferred was requested more than once" }
		if (Test-Port $Preferred) { throw "Required port $Preferred is already occupied" }
		return $Preferred
	}
	for ($attempt = 0; $attempt -lt $MaxPortAttempts; $attempt += 1) {
		$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
		try {
			$listener.Start()
			$port = ([Net.IPEndPoint] $listener.LocalEndpoint).Port
			if ($Exclude -contains $port) { continue }
			return $port
		} finally {
			$listener.Stop()
		}
	}
	throw 'Could not allocate a free local TCP port'
}

function Get-ConfiguredPort([string] $EnvironmentName) {
	$value = [Environment]::GetEnvironmentVariable($EnvironmentName)
	if ([string]::IsNullOrWhiteSpace($value)) { return 0 }
	$port = 0
	if (-not [int]::TryParse($value, [ref] $port) -or $port -lt 1 -or $port -gt 65535) {
		throw "$EnvironmentName must be a valid TCP port"
	}
	return $port
}

function Get-ProcessTreeIds([int] $ProcessId) {
	$ids = @($ProcessId)
	$children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
	foreach ($child in $children) { $ids += Get-ProcessTreeIds -ProcessId ([int] $child.ProcessId) }
	return $ids
}

function Stop-ProcessTree([int] $ProcessId) {
	$ids = @(Get-ProcessTreeIds $ProcessId | Select-Object -Unique)
	$deadline = [DateTime]::UtcNow.AddSeconds($CleanupTimeoutSeconds)
	do {
		foreach ($id in @($ids)) {
			$children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" -ErrorAction SilentlyContinue)
			foreach ($child in $children) {
				if ($ids -notcontains [int] $child.ProcessId) { $ids += [int] $child.ProcessId }
			}
		}
		foreach ($id in ($ids | Sort-Object -Descending)) {
			try { Stop-Process -Id ([int] $id) -Force -ErrorAction Stop } catch {
				if (Get-Process -Id ([int] $id) -ErrorAction SilentlyContinue) { throw "Could not terminate process tree rooted at $ProcessId (process $id): $($_.Exception.Message)" }
			}
		}
		Start-Sleep -Milliseconds 100
		$remaining = @($ids | Where-Object { Get-Process -Id ([int] $_) -ErrorAction SilentlyContinue })
		if ($remaining.Count -eq 0) { return }
	} while ([DateTime]::UtcNow -lt $deadline)
	throw "Process tree rooted at $ProcessId remains alive: $($remaining -join ',')"
}

function Start-RedirectedProcess(
	[string] $FileName,
	[string] $Arguments,
	[string] $WorkingDirectory,
	[string] $StdoutPath,
	[string] $StderrPath,
	[hashtable] $Environment
) {
	$startInfo = [Diagnostics.ProcessStartInfo]::new()
	$startInfo.FileName = $FileName
	$startInfo.Arguments = $Arguments
	$startInfo.WorkingDirectory = $WorkingDirectory
	$startInfo.UseShellExecute = $false
	$startInfo.CreateNoWindow = $true
	$startInfo.RedirectStandardInput = $true
	$startInfo.RedirectStandardOutput = $true
	$startInfo.RedirectStandardError = $true
	foreach ($entry in $Environment.GetEnumerator()) {
		$startInfo.EnvironmentVariables[$entry.Key] = [string] $entry.Value
	}
	$process = [Diagnostics.Process]::new()
	$process.StartInfo = $startInfo
	if (-not $process.Start()) { throw "Could not start process: $FileName" }
	$stdoutTask = $process.StandardOutput.ReadToEndAsync()
	$stderrTask = $process.StandardError.ReadToEndAsync()
	return @{
		Process = $process
		StdoutTask = $stdoutTask
		StderrTask = $stderrTask
		StdoutPath = $StdoutPath
		StderrPath = $StderrPath
	}
}

function Complete-RedirectedProcess($Handle) {
	if ($null -eq $Handle) { return }
	if (-not $Handle.Process.HasExited) { return }
	foreach ($stream in @(@{ Task = $Handle.StdoutTask; Path = $Handle.StdoutPath }, @{ Task = $Handle.StderrTask; Path = $Handle.StderrPath })) {
		try {
			if ($stream.Task.Wait($OutputDrainTimeoutMilliseconds)) {
				[IO.File]::WriteAllText($stream.Path, [string] $stream.Task.Result)
			} else {
				[IO.File]::WriteAllText($stream.Path, '[output drain timed out]')
			}
		} catch {
			try { [IO.File]::WriteAllText($stream.Path, '[output drain failed]') } catch {}
		}
	}
}

function New-Secret() {
	$bytes = New-Object byte[] 48
	$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
	return [Convert]::ToBase64String($bytes)
}

function Set-ServerProperties([string] $Path, [hashtable] $Values) {
	$lines = @()
	if (Test-Path -LiteralPath $Path -PathType Leaf) { $lines = @(Get-Content -LiteralPath $Path) }
	$seen = @{}
	$result = @(
	foreach ($line in $lines) {
		if ($line -match '^\s*([^#=:\s]+)\s*=') {
			$key = $Matches[1]
			if ($Values.ContainsKey($key)) {
				$seen[$key] = $true
				"$key=$($Values[$key])"
				continue
			}
		}
		$line
	}
	)
	foreach ($entry in $Values.GetEnumerator()) {
		if (-not $seen.ContainsKey($entry.Key)) { $result += "$($entry.Key)=$($entry.Value)" }
	}
	[IO.File]::WriteAllText($Path, (($result -join [Environment]::NewLine) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
}

function Remove-ScenarioArtifacts([string] $ScenarioDirectory) {
	foreach ($relativePath in @('server', 'provider-workspaces', 'traces', 'logs', 'rcon-password.txt', 'coordinator-config.json', 'protocol.jsonl', 'provider-turns.private.jsonl')) {
		$target = Join-Path $ScenarioDirectory $relativePath
		if (-not (Test-Path -LiteralPath $target)) { continue }
		$deadline = [DateTime]::UtcNow.AddSeconds($CleanupTimeoutSeconds)
		$lastError = $null
		do {
			try {
				Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
				$lastError = $null
				break
			} catch {
				$lastError = $_
				if ([DateTime]::UtcNow -ge $deadline) { throw "Could not remove generated artifact '$target': $($_.Exception.Message)" }
				Start-Sleep -Milliseconds 100
			}
		} while ([DateTime]::UtcNow -lt $deadline)
		if ($null -ne $lastError -and (Test-Path -LiteralPath $target)) { throw "Could not remove generated artifact '$target': $($lastError.Exception.Message)" }
		if (Test-Path -LiteralPath $target) { throw "Artifact cleanup left '$target' behind" }
	}
}

function Resolve-Java([string] $Project) {
	$candidates = @()
	$override = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_JAVA')
	if (-not [string]::IsNullOrWhiteSpace($override)) {
		$candidates = @($override)
	} else {
		$candidates += (Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe')
		$found = Get-ProcessCommand 'java'
		if ($null -ne $found) { $candidates += $found }
	}
	foreach ($candidate in $candidates) {
		if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
		$previousErrorAction = $ErrorActionPreference
		$ErrorActionPreference = 'Continue'
		$version = (& $candidate -version 2>&1 | Out-String)
		$ErrorActionPreference = $previousErrorAction
		if ($LASTEXITCODE -ne 0) { continue }
		$match = [regex]::Match($version, 'version\s+"(?<major>\d+)')
		if ($match.Success -and [int] $match.Groups['major'].Value -ge 25) { return $candidate }
	}
	throw 'Java 25 or newer is required'
}

function Resolve-Node() {
	$override = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_NODE')
	$node = if (-not [string]::IsNullOrWhiteSpace($override)) { $override } else { Get-ProcessCommand 'node' }
	if ($null -eq $node -or -not (Test-Path -LiteralPath $node -PathType Leaf)) { throw 'Node.js 22 or newer is required' }
	$previousErrorAction = $ErrorActionPreference
	$ErrorActionPreference = 'Continue'
	$version = (& $node --version 2>&1 | Out-String)
	$ErrorActionPreference = $previousErrorAction
	$match = [regex]::Match($version, 'v(?<major>\d+)')
	if (-not $match.Success -or [int] $match.Groups['major'].Value -lt 22) { throw 'Node.js 22 or newer is required' }
	return $node
}

function Read-Matrix([string] $Path) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing matrix file: $Path" }
	$matrix = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
	if ($null -eq $matrix.scenarios -or @($matrix.scenarios).Count -eq 0) { throw 'Matrix must contain one or more scenarios' }
	return $matrix
}

function Get-ProviderCommand([string] $Provider) {
	switch ($Provider.ToLowerInvariant()) {
		'codex' { return 'codex' }
		'gemini' { return 'agy' }
		'kimi' { return 'kimi' }
		default { throw "Unsupported provider '$Provider'" }
	}
}

function ConvertTo-SafePathSegment([string] $Value) {
	$segment = [regex]::Replace($Value, '[^A-Za-z0-9._-]', '_').Trim('.')
	if ([string]::IsNullOrWhiteSpace($segment)) { $segment = 'scenario' }
	return $segment.Substring(0, [Math]::Min(48, $segment.Length))
}

function ConvertTo-BoundedText([object] $Value, [int] $Maximum = 128) {
	$text = [string] $Value
	if ($text.Length -gt $Maximum) { return $text.Substring(0, $Maximum) }
	return $text
}

function Assert-SafeScenarioId([string] $Value) {
	if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 128 -or $Value -match '[\\/\x00-\x1f\x7f]' -or $Value.Contains('..')) {
		throw "Scenario ID must be a safe path segment without separators, '..', or control characters: $Value"
	}
}

function Test-ProviderPreflight([string] $Provider) {
	if ([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT') -eq '1') {
		return [pscustomobject]@{ Available = $true; Reason = $null }
	}
	$commandName = Get-ProviderCommand $Provider
	$command = Get-ProcessCommand $commandName
	if ($null -eq $command) { return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' is unavailable" } }
	try {
		$previousErrorAction = $ErrorActionPreference
		$ErrorActionPreference = 'Continue'
		$null = & $command --version 2>&1
		$ErrorActionPreference = $previousErrorAction
		if ($LASTEXITCODE -ne 0) { return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' failed preflight" } }
	} catch {
		return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' failed preflight" }
	}
	return [pscustomobject]@{ Available = $true; Reason = $null }
}

function New-ScenarioConfig([string] $Source, [string] $Destination, [int] $BridgePort, [string] $WorkspaceRoot) {
	$config = Get-Content -Raw -LiteralPath $Source | ConvertFrom-Json
	$config.bridge.host = '127.0.0.1'
	$config.bridge.port = $BridgePort
	if ($null -eq $config.PSObject.Properties['workspaceRoot']) { $config | Add-Member -NotePropertyName workspaceRoot -NotePropertyValue $WorkspaceRoot } else { $config.workspaceRoot = $WorkspaceRoot }
	if ($null -eq $config.codex.PSObject.Properties['cwd']) { $config.codex | Add-Member -NotePropertyName cwd -NotePropertyValue $WorkspaceRoot } else { $config.codex.cwd = $WorkspaceRoot }
	if ($null -eq $config.codex.PSObject.Properties['launchProfile'] -or $null -eq $config.codex.launchProfile) { $config.codex | Add-Member -NotePropertyName launchProfile -NotePropertyValue ([pscustomobject]@{}) }
	$launchProfile = $config.codex.launchProfile
	if ($null -eq $launchProfile.PSObject.Properties['cwd']) { $launchProfile | Add-Member -NotePropertyName cwd -NotePropertyValue $WorkspaceRoot } else { $launchProfile.cwd = $WorkspaceRoot }
	[IO.File]::WriteAllText($Destination, ($config | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
}

function Invoke-Scenario($Scenario, [string] $Project, [string] $RunDirectory, [string] $Template, [string] $MatrixFile, [string] $Java, [string] $Node, [string] $BuiltJar, [switch] $Keep) {
	$scenarioId = [string] $Scenario.id
	$scenarioDirectory = Join-Path $RunDirectory ("$(ConvertTo-SafePathSegment $scenarioId)-$([Guid]::NewGuid().ToString('N').Substring(0, 8))")
	New-Item -ItemType Directory -Path $scenarioDirectory -Force | Out-Null
	$serverDirectory = Join-Path $scenarioDirectory 'server'
	Copy-Item -LiteralPath $Template -Destination $serverDirectory -Recurse -Force
	$world = Join-Path $serverDirectory 'world'
	if (Test-Path -LiteralPath $world) { Remove-Item -LiteralPath $world -Recurse -Force }
	$modsDirectory = Join-Path $serverDirectory 'mods'
	New-Item -ItemType Directory -Path $modsDirectory -Force | Out-Null
	Copy-Item -LiteralPath $BuiltJar -Destination (Join-Path $modsDirectory ([IO.Path]::GetFileName($BuiltJar))) -Force
	$worldName = "headless-$([IO.Path]::GetFileName($RunDirectory))-$scenarioId-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
	$bridgePort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_BRIDGE_PORT')
	$rconPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_RCON_PORT') @($bridgePort)
	$serverPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_MINECRAFT_PORT') @($bridgePort, $rconPort)
	$secret = New-Secret
	$secretPath = Join-Path $scenarioDirectory 'rcon-password.txt'
	Write-PrivateText $secretPath $secret
	$propertiesPath = Join-Path $serverDirectory 'server.properties'
	Set-ServerProperties $propertiesPath @{
		'online-mode' = 'false'
		'enable-rcon' = 'true'
		'rcon.password' = $secret
		'rcon.port' = $rconPort
		'rcon.ip' = '127.0.0.1'
		'server-port' = $serverPort
		'level-name' = $worldName
		'pause-when-empty-seconds' = '-1'
	}
	Protect-LocalFile $propertiesPath
	$logsDirectory = Join-Path $scenarioDirectory 'logs'
	$traceDirectory = Join-Path $scenarioDirectory 'traces'
	$providerWorkspace = Join-Path $scenarioDirectory 'provider-workspaces'
	New-Item -ItemType Directory -Path $logsDirectory, $traceDirectory, $providerWorkspace -Force | Out-Null
	$serverLog = Join-Path $serverDirectory 'logs\latest.log'
	$protocolAudit = Join-Path $scenarioDirectory 'protocol.jsonl'
	$providerTurns = Join-Path $scenarioDirectory 'provider-turns.private.jsonl'
	$coordinatorConfig = Join-Path $scenarioDirectory 'coordinator-config.json'
	$sourceConfig = Join-Path $Project 'coordinator\config\dynamic-agents.json'
	if (-not (Test-Path -LiteralPath $sourceConfig -PathType Leaf)) { throw "Missing coordinator config: $sourceConfig" }
	New-ScenarioConfig $sourceConfig $coordinatorConfig $bridgePort $providerWorkspace
	$manifest = [pscustomobject]@{
		runId = [IO.Path]::GetFileName($RunDirectory); scenarioId = $scenarioId; provider = [string] $Scenario.provider
		model = [string] $Scenario.model; reasoningEffort = [string] $Scenario.reasoningEffort; serviceTier = [string] $Scenario.serviceTier
		serverDirectory = $serverDirectory; providerWorkspace = $providerWorkspace; protocolAudit = $protocolAudit; providerTurns = $providerTurns
		ports = [pscustomobject]@{ minecraft = $serverPort; rcon = $rconPort; bridge = $bridgePort }; levelName = $worldName
	}
	[IO.File]::WriteAllText((Join-Path $scenarioDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
	$serverHandle = $null
	$coordinatorHandle = $null
	$runnerHandle = $null
	$failure = $null
	$runnerExit = $null
	$cleanupFailure = $null
	$processIds = @()
	try {
		$serverArgs = "-Darenaagents.bridgeSecretFile=$(Quote-Argument $secretPath) -Xms1G -Xmx4G -jar $(Quote-Argument (Join-Path $serverDirectory 'fabric-server-launch.jar')) nogui"
		$serverStdoutPath = Join-Path $logsDirectory 'fabric.stdout.log'
		$serverStderrPath = Join-Path $logsDirectory 'fabric.stderr.log'
		$serverAttempt = 0
		$serverReady = $false
		while (-not $serverReady) {
			$serverAttempt += 1
			$serverHandle = Start-RedirectedProcess $Java $serverArgs $serverDirectory $serverStdoutPath $serverStderrPath @{}
			try {
				Wait-Condition { (Test-Port $serverPort) -and ((Read-Text $serverLog).Contains('Done (')) } $StartupTimeoutSeconds 'Fabric server did not become ready'
				Wait-Condition { Test-Port $rconPort } $StartupTimeoutSeconds 'RCON did not become ready'
				$serverReady = $true
			} catch {
				Complete-RedirectedProcess $serverHandle
				$bindFailure = $serverHandle.Process.HasExited -and (Test-BindFailure $serverLog $serverStderrPath)
				if (-not $bindFailure -or $serverAttempt -gt $StartupBindRetries) { throw }
				$processIds += @(Get-ProcessTreeIds $serverHandle.Process.Id | Select-Object -Unique)
				try { Stop-ProcessTree $serverHandle.Process.Id } catch { throw "Server bind retry cleanup failed: $($_.Exception.Message)" }
				$serverHandle = $null
				$bridgePort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_BRIDGE_PORT')
				$rconPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_RCON_PORT') @($bridgePort)
				$serverPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_MINECRAFT_PORT') @($bridgePort, $rconPort)
				Set-ServerProperties $propertiesPath @{
					'online-mode' = 'false'; 'enable-rcon' = 'true'; 'rcon.password' = $secret; 'rcon.port' = $rconPort; 'rcon.ip' = '127.0.0.1'
					'server-port' = $serverPort; 'level-name' = $worldName; 'pause-when-empty-seconds' = '-1'
				}
				Protect-LocalFile $propertiesPath
				New-ScenarioConfig $sourceConfig $coordinatorConfig $bridgePort $providerWorkspace
			}
		}
		$coordinatorArgs = "$(Quote-Argument (Join-Path $Project 'coordinator\src\dynamic-main.mjs')) --config $(Quote-Argument $coordinatorConfig)"
		$coordinatorEnvironment = @{
			ARENA_AGENT_BRIDGE_SECRET = $secret; ARENA_HEADLESS_RUN_ID = [IO.Path]::GetFileName($RunDirectory); ARENA_HEADLESS_SCENARIO_ID = $scenarioId
			ARENA_PROTOCOL_AUDIT_PATH = $protocolAudit; ARENA_PROVIDER_TURNS_PATH = $providerTurns; ARENA_HEADLESS_TRACE_PATH = (Join-Path $traceDirectory 'coordinator.jsonl')
			ARENA_HEADLESS_BRIDGE_PORT = $bridgePort; ARENA_HEADLESS_RCON_PORT = $rconPort; ARENA_HEADLESS_MINECRAFT_PORT = $serverPort
		}
		$coordinatorHandle = Start-RedirectedProcess $Node $coordinatorArgs (Join-Path $Project 'coordinator') (Join-Path $traceDirectory 'dynamic.stdout.log') (Join-Path $traceDirectory 'dynamic.stderr.log') $coordinatorEnvironment
		Wait-Condition { Test-Port $bridgePort } $StartupTimeoutSeconds 'Coordinator bridge did not become ready'
		$runnerArgs = "$(Quote-Argument (Join-Path $Project 'coordinator\src\headless-matrix.mjs')) --config $(Quote-Argument $MatrixFile) --scenario $(Quote-Argument $scenarioId) --run-directory $(Quote-Argument $scenarioDirectory) --rcon-host 127.0.0.1 --rcon-port $rconPort --rcon-password-file $(Quote-Argument $secretPath) --protocol-audit $(Quote-Argument $protocolAudit) --provider-turns $(Quote-Argument $providerTurns)"
		if ($RequireAll) { $runnerArgs += ' --require-all' }
		$runnerEnvironment = @{
			ARENA_HEADLESS_RUN_ID = [IO.Path]::GetFileName($RunDirectory); ARENA_HEADLESS_SCENARIO_ID = $scenarioId
			ARENA_PROTOCOL_AUDIT_PATH = $protocolAudit; ARENA_PROVIDER_TURNS_PATH = $providerTurns
		}
		$runnerHandle = Start-RedirectedProcess $Node $runnerArgs (Join-Path $Project 'coordinator') (Join-Path $traceDirectory 'runner.stdout.log') (Join-Path $traceDirectory 'runner.stderr.log') $runnerEnvironment
		if (-not $runnerHandle.Process.WaitForExit(([int] $Scenario.timeoutMs + 30000))) { throw "Scenario '$scenarioId' timed out" }
		$runnerExit = $runnerHandle.Process.ExitCode
		Complete-RedirectedProcess $runnerHandle
		if ($runnerExit -ne 0) { throw "Scenario '$scenarioId' failed with runner exit code $runnerExit" }
	} catch {
		$failure = $_
	} finally {
		if ($null -ne $serverHandle -and $null -ne $serverHandle.Process) {
			try {
				if (-not $serverHandle.Process.HasExited) {
					$serverHandle.Process.StandardInput.WriteLine('stop')
					$serverHandle.Process.StandardInput.Flush()
					$null = $serverHandle.Process.WaitForExit(10000)
				}
			} catch {}
		}
		foreach ($handle in @($runnerHandle, $coordinatorHandle, $serverHandle)) {
			if ($null -ne $handle -and $null -ne $handle.Process) {
				$processIds += @(Get-ProcessTreeIds $handle.Process.Id | Select-Object -Unique)
				try { Stop-ProcessTree $handle.Process.Id } catch { if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }; if ($null -eq $failure) { $failure = $_ } }
			}
		}
		try { Wait-Condition { -not (Test-Port $serverPort) -and -not (Test-Port $rconPort) -and -not (Test-Port $bridgePort) } $CleanupTimeoutSeconds 'Scenario cleanup left an allocated listener running' } catch { $cleanupFailure = $_; if ($null -eq $failure) { $failure = $_ } }
		foreach ($handle in @($runnerHandle, $coordinatorHandle, $serverHandle)) { Complete-RedirectedProcess $handle }
	}
	if (-not $Keep) {
		try { Remove-ScenarioArtifacts $scenarioDirectory } catch { $cleanupFailure = $_; if ($null -eq $failure) { $failure = $_ } }
	}
	$status = if ($null -eq $failure) { 'PASSED' } else { 'FAILED' }
	$cleanupStatus = if ($null -eq $cleanupFailure) { 'CLEAN' } else { 'FAILED' }
	$report = [pscustomobject]@{ status = $status; scenarioId = $scenarioId; provider = [string] $Scenario.provider; model = [string] $Scenario.model; reasoningEffort = [string] $Scenario.reasoningEffort; exitCode = $runnerExit; cleanup = [pscustomobject]@{ status = $cleanupStatus; processIds = $processIds; diagnostics = if ($null -eq $cleanupFailure) { $null } else { $cleanupFailure.Exception.Message } }; artifacts = $manifest; artifactsKept = [bool] $Keep; diagnostics = if ($null -eq $failure) { $null } else { $failure.Exception.Message } }
	[IO.File]::WriteAllText((Join-Path $scenarioDirectory 'report.json'), ($report | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
	return $report
}

$root = [IO.Path]::GetFullPath($ProjectRoot)
if ([string]::IsNullOrWhiteSpace($MatrixPath)) { $MatrixPath = Join-Path $root 'coordinator\config\headless-provider-matrix.json' }
if ([string]::IsNullOrWhiteSpace($ServerTemplate)) {
		$ServerTemplate = Join-Path $root 'runtime\server-template'
		if (-not (Test-Path -LiteralPath $ServerTemplate -PathType Container)) { $ServerTemplate = Join-Path $root 'runtime\server' }
}
$MatrixPath = [IO.Path]::GetFullPath($MatrixPath)
$ServerTemplate = [IO.Path]::GetFullPath($ServerTemplate)

$java = Resolve-Java $root
$node = Resolve-Node
$serverLauncher = Join-Path $ServerTemplate 'fabric-server-launch.jar'
if (-not (Test-Path -LiteralPath $ServerTemplate -PathType Container)) { throw "Missing server template: $ServerTemplate" }
if (-not (Test-Path -LiteralPath $serverLauncher -PathType Leaf)) { throw "Missing Fabric server launcher: $serverLauncher" }
$builtJar = Join-Path $root 'build\libs\arena-agents-0.1.0.jar'
if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) { throw "Missing built mod JAR: $builtJar" }
$matrix = Read-Matrix $MatrixPath
$selected = @($matrix.scenarios | Where-Object { [string]::IsNullOrWhiteSpace($ScenarioId) -or [string] $_.id -eq $ScenarioId })
if ($selected.Count -eq 0) { throw "Unknown scenario '$ScenarioId'" }
foreach ($scenario in $selected) { Assert-SafeScenarioId ([string] $scenario.id) }
$manifestScenarios = @(
	foreach ($scenario in $selected) {
		$assertionTypes = @()
		if ($null -ne $scenario.PSObject.Properties['assert']) {
			$assertionTypes = @($scenario.assert | ForEach-Object {
				if ($null -ne $_ -and $null -ne $_.PSObject.Properties['type']) { ConvertTo-BoundedText $_.type 64 }
			}) | Select-Object -First 16
		}
		$serviceTier = 'priority'
		if ($null -ne $scenario.PSObject.Properties['serviceTier'] -and $null -ne $scenario.serviceTier) {
			$serviceTier = ConvertTo-BoundedText $scenario.serviceTier
		}
		[pscustomobject]@{
			id = ConvertTo-BoundedText $scenario.id
			provider = ConvertTo-BoundedText $scenario.provider
			model = ConvertTo-BoundedText $scenario.model
			reasoningEffort = ConvertTo-BoundedText $scenario.reasoningEffort
			serviceTier = $serviceTier
			timeoutMs = if ($null -ne $scenario.PSObject.Properties['timeoutMs']) { [int] $scenario.timeoutMs } else { $null }
			assertionTypes = @($assertionTypes)
		}
	}
)
$manifestVersion = if ($null -ne $matrix.PSObject.Properties['version']) { [int] $matrix.version } else { 1 }
$manifestSummary = [pscustomobject]@{ version = $manifestVersion; scenarioCount = $manifestScenarios.Count; scenarios = $manifestScenarios }
$runId = "run-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$runDirectory = Join-Path $root "runtime\headless-runs\$runId"
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$reports = @()
$manifestPath = Join-Path $runDirectory 'matrix-manifest.json'
[IO.File]::WriteAllText($manifestPath, ($manifestSummary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
foreach ($scenario in $selected) {
	$preflight = Test-ProviderPreflight ([string] $scenario.provider)
	if (-not $preflight.Available) {
		$status = if ($RequireAll) { 'FAILED' } else { 'SKIPPED' }
		$reports += [pscustomobject]@{ status = $status; scenarioId = [string] $scenario.id; provider = [string] $scenario.provider; skippedReason = $preflight.Reason; cleanup = [pscustomobject]@{ status = 'NOT_STARTED' } }
		continue
	}
	try {
		$reports += Invoke-Scenario $scenario $root $runDirectory $ServerTemplate $MatrixPath $java $node $builtJar -Keep:$KeepArtifacts
	} catch {
		$reports += [pscustomobject]@{ status = 'FAILED'; scenarioId = [string] $scenario.id; provider = [string] $scenario.provider; cleanup = [pscustomobject]@{ status = 'FAILED' }; diagnostics = $_.Exception.Message }
	}
}
$failed = @($reports | Where-Object { $_.status -eq 'FAILED' })
$matrixStatus = if ($failed.Count -gt 0) { 'FAILED' } elseif (@($reports | Where-Object { $_.status -eq 'PASSED' }).Count -gt 0) { 'PASSED' } else { 'SKIPPED' }
$matrixReport = [pscustomobject]@{ runId = $runId; status = $matrixStatus; requireAll = [bool] $RequireAll; scenarios = $reports; reportPath = (Join-Path $runDirectory 'matrix-report.json'); artifactsKept = [bool] $KeepArtifacts }
[IO.File]::WriteAllText($matrixReport.reportPath, ($matrixReport | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
$matrixReport | ConvertTo-Json -Depth 20
if ($failed.Count -gt 0) {
	$diagnostics = (@($failed | ForEach-Object { if ($_.diagnostics) { $_.diagnostics } }) -join '; ')
	if ([string]::IsNullOrWhiteSpace($diagnostics)) { $diagnostics = 'unknown failure' }
	throw "Headless provider matrix contains failed required scenarios: $diagnostics"
}
