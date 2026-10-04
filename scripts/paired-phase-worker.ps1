[CmdletBinding()]
param([Parameter(Mandatory = $true)][string] $RequestPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$request = Get-Content -Raw -LiteralPath $RequestPath | ConvertFrom-Json
. $request.launcher -ProjectRoot $request.arm.sourceRoot -FunctionsOnly

function Send-PairedEvent([string] $Kind, $Value) {
	[Console]::Out.WriteLine('PAIR_EVENT ' + (@{ kind = $Kind; value = $Value } | ConvertTo-Json -Depth 30 -Compress))
	[Console]::Out.Flush()
}
function Get-PairedRemaining {
	return [Math]::Max(0, [Math]::Floor($script:PairedState.remainingMs - $script:PairedState.clock.Elapsed.TotalMilliseconds))
}
function Receive-PairedPhase([string] $Expected) {
	$line = [Console]::In.ReadLine()
	if ($null -eq $line) { throw 'Paired supervisor disconnected' }
	$message = $line | ConvertFrom-Json
	if ($message.phase -ne $Expected -or $message.remainingMs -lt 0) { throw 'Invalid paired phase transition' }
	$script:PairedState.phase = $Expected
	$script:PairedState.remainingMs = [double] $message.remainingMs
	$script:PairedState.clock.Restart()
}
function Write-PairedRunnerRequest([string] $Name) {
	$destination = Join-Path $script:PairedState.channel "$Name.json"
	[IO.File]::WriteAllText("$destination.tmp", (@{ remainingMs = (Get-PairedRemaining) } | ConvertTo-Json -Compress))
	Move-Item -LiteralPath "$destination.tmp" -Destination $destination
}

$script:PairedState = @{
	phase = 'startup'; remainingMs = 0; clock = [Diagnostics.Stopwatch]::StartNew()
	channel = $request.channel; artifactSha256 = $request.arm.artifactSha256
	resources = [System.Collections.Generic.List[object]]::new(); drainFailed = $false
}
Receive-PairedPhase 'startup'
try {
	$java = Resolve-Java $request.arm.sourceRoot
	$node = Resolve-Node
	$preflight = Test-ProviderPreflight $request.scenario.provider
	if (-not $preflight.Available) { throw 'Requested provider unavailable' }
	$report = Invoke-Scenario $request.scenario $request.arm.sourceRoot $request.runDirectory $request.serverTemplate $request.matrixPath $java $node $request.arm.artifactPath
	Send-PairedEvent 'cleanup' $report
} catch {
	# Exceptions before the shared lifecycle owns handles cannot prove teardown.
	# Parent supervision will terminate the worker/tree and retain UNKNOWN.
	Send-PairedEvent 'failure' @{ status = 'ERROR'; cleanup = 'UNKNOWN'; resources = @($script:PairedState.resources.ToArray()) }
	exit 1
}
