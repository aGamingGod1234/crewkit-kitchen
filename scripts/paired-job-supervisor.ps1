[CmdletBinding()]
param([Parameter(Mandatory = $true)][string] $RequestPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Windows job containment must exist before the phase worker receives startup.
# Reflection declares the system APIs directly; no compiler/build or installation.
$assembly = [AppDomain]::CurrentDomain.DefineDynamicAssembly([Reflection.AssemblyName]::new('ArenaPairedJob'), [Reflection.Emit.AssemblyBuilderAccess]::Run)
$type = $assembly.DefineDynamicModule('Native').DefineType('Job', 'Public, Sealed, Abstract')
function Add-Native([string] $Name, [Type] $Return, [Type[]] $Parameters) {
	$method = $type.DefinePInvokeMethod($Name, 'kernel32.dll', 'Public, Static, PinvokeImpl', 'Standard', $Return, $Parameters, 'Winapi', 'Unicode')
	$method.SetImplementationFlags($method.GetMethodImplementationFlags() -bor [Reflection.MethodImplAttributes]::PreserveSig)
}
Add-Native 'CreateJobObjectW' ([IntPtr]) @([IntPtr], [string])
Add-Native 'SetInformationJobObject' ([bool]) @([IntPtr], [int], [IntPtr], [uint32])
Add-Native 'AssignProcessToJobObject' ([bool]) @([IntPtr], [IntPtr])
Add-Native 'QueryInformationJobObject' ([bool]) @([IntPtr], [int], [IntPtr], [uint32], [IntPtr])
Add-Native 'TerminateJobObject' ([bool]) @([IntPtr], [uint32])
Add-Native 'CloseHandle' ([bool]) @([IntPtr])
$native = $type.CreateType()
$job = $native::CreateJobObjectW([IntPtr]::Zero, $null)
if ($job -eq [IntPtr]::Zero -or [IntPtr]::Size -ne 8) { throw 'Paired execution requires Windows x64 job containment' }
$worker = $null
$healthy = $false
$bindings = [System.Collections.Generic.List[object]]::new()
function Lock-Binding([string] $File, [string] $ExpectedHash) {
	# Keep the verified source/artifact bytes immutable throughout this arm.
	$stream = [IO.File]::Open($File, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
	$bindings.Add($stream)
	$algorithm = [Security.Cryptography.SHA256]::Create()
	try { $actual = [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
	finally { $algorithm.Dispose(); $stream.Position = 0 }
	if ($actual -ne $ExpectedHash) { throw 'Immutable arm binding changed before launch' }
}
function Get-JobActive {
	$buffer = [Runtime.InteropServices.Marshal]::AllocHGlobal(48)
	try {
		if (-not $native::QueryInformationJobObject($job, 1, $buffer, 48, [IntPtr]::Zero)) { throw 'Job accounting unavailable' }
		return [Runtime.InteropServices.Marshal]::ReadInt32($buffer, 40)
	} finally { [Runtime.InteropServices.Marshal]::FreeHGlobal($buffer) }
}
try {
	$request = Get-Content -Raw -LiteralPath $RequestPath | ConvertFrom-Json
	Lock-Binding $request.matrixPath $request.matrixSha256
	Lock-Binding $request.arm.sourceManifestPath $request.arm.sourceManifestSha256
	Lock-Binding $request.arm.artifactPath $request.arm.artifactSha256
	$manifest = Get-Content -Raw -LiteralPath $request.arm.sourceManifestPath | ConvertFrom-Json
	foreach ($entry in $manifest.files) { Lock-Binding (Join-Path $request.arm.sourceRoot $entry.path) $entry.sha256 }
	# JOBOBJECT_EXTENDED_LIMIT_INFORMATION (x64): LimitFlags at byte 16.
	$limits = [Runtime.InteropServices.Marshal]::AllocHGlobal(144)
	try {
		[Runtime.InteropServices.Marshal]::Copy([byte[]]::new(144), 0, $limits, 144)
		[Runtime.InteropServices.Marshal]::WriteInt32($limits, 16, 0x2000) # KILL_ON_JOB_CLOSE; no breakaway
		if (-not $native::SetInformationJobObject($job, 9, $limits, 144)) { throw 'Cannot enforce job lifetime' }
	} finally { [Runtime.InteropServices.Marshal]::FreeHGlobal($limits) }
	$info = [Diagnostics.ProcessStartInfo]::new()
	$info.FileName = (Join-Path $PSHOME 'powershell.exe')
	$info.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $PSScriptRoot 'paired-phase-worker.ps1') + '" -RequestPath "' + $RequestPath.Replace('"', '\"') + '"'
	$info.UseShellExecute = $false; $info.CreateNoWindow = $true
	$info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
	$worker = [Diagnostics.Process]::Start($info)
	if (-not $native::AssignProcessToJobObject($job, $worker.Handle)) { throw 'Cannot contain phase worker; startup refused' }
	[Console]::Out.WriteLine('PAIR_EVENT ' + (@{ kind = 'resource'; value = @{ containment = 'windows_job_kill_on_close'; workerPid = $worker.Id } } | ConvertTo-Json -Compress)); [Console]::Out.Flush()
	$parentInput = [IO.StreamReader]::new([Console]::OpenStandardInput())
	$inputTask = $parentInput.ReadLineAsync()
	$outputTask = $worker.StandardOutput.ReadLineAsync()
	$errorTask = $worker.StandardError.ReadToEndAsync()
	while ($true) {
		if ($inputTask.IsCompleted) {
			$line = $inputTask.Result
			if ($null -eq $line) { throw 'Parent disconnected; terminating owned job' }
			$worker.StandardInput.WriteLine($line); $worker.StandardInput.Flush()
			$inputTask = $parentInput.ReadLineAsync()
		}
		if ($outputTask.IsCompleted) {
			$line = $outputTask.Result
			if ($null -ne $line) { [Console]::Out.WriteLine($line); [Console]::Out.Flush(); $outputTask = $worker.StandardOutput.ReadLineAsync() }
			elseif ($worker.HasExited) { break }
		}
		Start-Sleep -Milliseconds 5
	}
	# Windows may signal the worker handle before job accounting reaches zero.
	# The Node parent's existing cleanup timer still owns this wait.
	while ((Get-JobActive) -gt 0) { Start-Sleep -Milliseconds 5 }
	$healthy = $worker.ExitCode -eq 0
} finally {
	# Closing the last job handle is enforced by Windows even if this supervisor
	# is killed while a copy, child command, provider, or output drain is blocked.
	if (-not $healthy) { $null = $native::TerminateJobObject($job, 1) }
	$null = $native::CloseHandle($job)
	if ($null -ne $worker -and -not $worker.HasExited) { $worker.Kill(); $worker.WaitForExit() }
	foreach ($binding in $bindings) { $binding.Dispose() }
}
if (-not $healthy) { exit 1 }
