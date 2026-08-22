[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'run-headless-provider-matrix.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
	throw 'Expected lifecycle wrapper to exist'
}

function Assert-Fails([scriptblock] $Action, [string] $Pattern) {
	try {
		& $Action 2>&1 | Out-Null
		throw "Expected failure matching '$Pattern'"
	} catch {
		if ($_.Exception.Message -notmatch $Pattern) {
			throw "Failure did not match '$Pattern': $($_.Exception.Message)"
		}
	}
}

function Set-TestEnvironment([string] $Name, [string] $Value) {
	[Environment]::SetEnvironmentVariable($Name, $Value)
}

function New-Fixture([string] $Root) {
	New-Item -ItemType Directory -Path (Join-Path $Root 'build\libs'), (Join-Path $Root 'runtime\server-template\mods'), (Join-Path $Root 'runtime\server-template\logs'), (Join-Path $Root 'runtime\server-template\world'), (Join-Path $Root 'coordinator\config') -Force | Out-Null
	Set-Content -LiteralPath (Join-Path $Root 'build\libs\arena-agents-0.1.0.jar') -Value 'fixture' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\fabric-server-launch.jar') -Value 'not-a-real-jar' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'runtime\server-template\world\stale.dat') -Value 'must not be copied' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'coordinator\config\dynamic-agents.json') -Value '{"bridge":{"host":"127.0.0.1","port":25570,"secretEnvironmentVariable":"ARENA_AGENT_BRIDGE_SECRET"},"codex":{},"limits":{"agentCap":1,"goalQueueCap":1,"planningConcurrency":1}}' -NoNewline
	Set-Content -LiteralPath (Join-Path $Root 'matrix.json') -Value '{"version":1,"scenarios":[{"id":"fixture","provider":"codex","model":"fixture","reasoningEffort":"low","serviceTier":"fast","task":"fixture","timeoutMs":1000,"assert":[{"type":"lifecycle","state":"COMPLETED"}]}]}' -NoNewline
}

function Stop-TestProcessTree([int] $ProcessId) {
	$children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
	foreach ($child in $children) { Stop-TestProcessTree ([int] $child.ProcessId) }
	Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Test-PortClosed([int] $Port) {
	return $null -eq (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

$project = Join-Path ([IO.Path]::GetTempPath()) "arena-headless-wrapper-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $project -Force | Out-Null
try {
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' (Join-Path $project 'not-java.exe')
	Assert-Fails { & $scriptPath -ProjectRoot $project } 'Java 25|prerequisite|missing'
	Set-TestEnvironment 'ARENA_HEADLESS_JAVA' $null

	$missingTemplateProject = Join-Path $project 'missing-template'
	New-Item -ItemType Directory -Path $missingTemplateProject -Force | Out-Null
	Assert-Fails { & $scriptPath -ProjectRoot $missingTemplateProject -ServerTemplate (Join-Path $missingTemplateProject 'no-server') } 'template|server'

	$fixture = Join-Path $project 'fixture'
	New-Fixture $fixture
	Set-TestEnvironment 'ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT' '1'
	Set-TestEnvironment 'ARENA_HEADLESS_MINECRAFT_PORT' '39165'
	Set-TestEnvironment 'ARENA_HEADLESS_RCON_PORT' '39166'
	Set-TestEnvironment 'ARENA_HEADLESS_BRIDGE_PORT' '39167'
	$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 39165)
	$listener.Start()
	try {
		Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'occupied|already|port'
	} finally {
		$listener.Stop()
	}
	Write-Output 'PASS occupied-port validation starts no provider process'

	Set-TestEnvironment 'ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS' '1'
	Assert-Fails { & $scriptPath -ProjectRoot $fixture -MatrixPath (Join-Path $fixture 'matrix.json') -ServerTemplate (Join-Path $fixture 'runtime\server-template') } 'ready|timed out|failed|required'
	if (-not (Test-PortClosed 39165) -or -not (Test-PortClosed 39166) -or -not (Test-PortClosed 39167)) { throw 'Allocated ports remained open after timeout cleanup' }
	$runRoot = Join-Path $fixture 'runtime\headless-runs'
	$reports = @(Get-ChildItem -LiteralPath $runRoot -Recurse -Filter report.json -ErrorAction SilentlyContinue)
	if ($reports.Count -lt 1) { throw 'Timeout cleanup did not write a scenario report' }
	$copiedWorlds = @(Get-ChildItem -LiteralPath $runRoot -Recurse -Directory -Filter world -ErrorAction SilentlyContinue)
	if ($copiedWorlds.Count -gt 0) { throw 'Server template world was copied into a scenario' }

	$dummyScript = Join-Path $project 'dummy-child-tree.ps1'
	Set-Content -LiteralPath $dummyScript -Value "Start-Process powershell -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 30'`nStart-Sleep -Seconds 30" -NoNewline
	$dummyRoot = Start-Process powershell -ArgumentList '-NoProfile','-File',$dummyScript -PassThru
	try {
		Start-Sleep -Milliseconds 500
		Stop-TestProcessTree $dummyRoot.Id
		Start-Sleep -Milliseconds 250
		if (-not $dummyRoot.HasExited) { throw 'Dummy child-tree root survived cleanup' }
	} finally {
		Stop-TestProcessTree $dummyRoot.Id
	}

	$wrapperText = Get-Content -Raw -LiteralPath $scriptPath
	foreach ($requiredPattern in @('Stop-ProcessTree', 'Get-CimInstance Win32_Process', 'Wait-Condition', 'Test-Port', 'ARENA_AGENT_BRIDGE_SECRET', 'provider-workspaces')) {
		if ($wrapperText -notmatch [regex]::Escape($requiredPattern)) { throw "Lifecycle wrapper missing cleanup/isolation hook '$requiredPattern'" }
	}
	if ($wrapperText -notmatch [regex]::Escape("Test-Port `$bridgePort 'Established'")) { throw 'Lifecycle wrapper can start the runner before the coordinator authenticates.' }
	Write-Output 'PASS timeout cleanup, port verification, child-tree cleanup, and provider isolation hooks'
} finally {
	foreach ($name in @('ARENA_HEADLESS_JAVA','ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT','ARENA_HEADLESS_MINECRAFT_PORT','ARENA_HEADLESS_RCON_PORT','ARENA_HEADLESS_BRIDGE_PORT','ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS')) { Set-TestEnvironment $name $null }
	if (Test-Path -LiteralPath $project) {
		Remove-Item -LiteralPath $project -Recurse -Force
	}
}
