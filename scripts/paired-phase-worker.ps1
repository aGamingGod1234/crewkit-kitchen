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
	return [Math]::Max(0, [Math]::Floor($script:PairedState.cutoffMs - (Get-PairedSystemNow)))
}
function Get-PairedSystemNow {
	# QPC is shared with Node/libuv hrtime on this Windows host, in milliseconds.
	return [Diagnostics.Stopwatch]::GetTimestamp() * (1000.0 / [Diagnostics.Stopwatch]::Frequency)
}
function Receive-PairedPhase([string] $Expected) {
	$line = [Console]::In.ReadLine()
	if ($null -eq $line) { throw 'Paired supervisor disconnected' }
	$message = $line | ConvertFrom-Json
	if ($message.phase -ne $Expected -or $message.clock -ne 'system-monotonic-ms' -or [double]::IsNaN([double]$message.cutoffMs) -or [double]::IsInfinity([double]$message.cutoffMs) -or $message.cutoffMs -lt 0) { throw 'Invalid paired phase transition' }
	$script:PairedState.phase = $Expected
	$script:PairedState.cutoffMs = [double] $message.cutoffMs
}
function Write-PairedRunnerRequest([string] $Name) {
	$destination = Join-Path $script:PairedState.channel "$Name.json"
	[IO.File]::WriteAllText("$destination.tmp", (@{ clock = 'system-monotonic-ms'; cutoffMs = $script:PairedState.cutoffMs } | ConvertTo-Json -Compress))
	Move-Item -LiteralPath "$destination.tmp" -Destination $destination
}

$script:PairedState = @{
	phase = 'startup'; cutoffMs = 0
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
