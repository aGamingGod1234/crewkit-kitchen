[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $ArchivePath,
    [string] $ArchiveSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$ExpectedVersion = '25.0.3'
$ToolchainRoot = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'runtime\toolchains\temurin-25'
$JdkRoot = Join-Path $ToolchainRoot 'jdk-25.0.3+9'
$Java = Join-Path $JdkRoot 'bin\java.exe'

function Assert-Java25([string] $JavaPath) {
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        throw "Java executable is missing: $JavaPath"
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $JavaPath
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    $versionOutput = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $versionOutput -notmatch [regex]::Escape($ExpectedVersion)) {
        throw "Expected Temurin Java $ExpectedVersion at $JavaPath. Output: $versionOutput"
    }
}

function Assert-JdkOwnedPath([string] $Path) {
    $root = [IO.Path]::GetFullPath($ToolchainRoot).TrimEnd('\', '/')
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing JDK operation outside its toolchain root: $full"
    }
    # Lexical containment alone does not establish ownership through a junction.
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if (((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing JDK operation through a reparse point: $cursor"
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
    return $full
}

if (Test-Path -LiteralPath $Java -PathType Leaf) {
    try {
        Assert-Java25 $Java
        Write-Host "Verified existing project-local JDK: $JdkRoot"
        return
    } catch {
        if ([string]::IsNullOrWhiteSpace($ArchivePath) -or [string]::IsNullOrWhiteSpace($ArchiveSha256)) { throw }
        Write-Warning 'Existing project-local Java was rejected; validating the supplied replacement archive.'
    }
}

if ([string]::IsNullOrWhiteSpace($ArchivePath) -or [string]::IsNullOrWhiteSpace($ArchiveSha256)) {
    throw 'JDK is absent. Supply -ArchivePath and its trusted -ArchiveSha256; unverified downloads are refused.'
}

$resolvedArchive = (Resolve-Path -LiteralPath $ArchivePath).Path
$actualHash = (Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256).Hash
if (-not $actualHash.Equals($ArchiveSha256, [StringComparison]::OrdinalIgnoreCase)) {
    throw "JDK archive SHA-256 mismatch. Expected $ArchiveSha256, got $actualHash."
}

$JdkRoot = Assert-JdkOwnedPath $JdkRoot
New-Item -ItemType Directory -Force -Path $ToolchainRoot | Out-Null
$transactionId = [Guid]::NewGuid().ToString('N')
$temporaryRoot = Assert-JdkOwnedPath (Join-Path $ToolchainRoot ".extracting-$transactionId")
$backupRoot = Assert-JdkOwnedPath "$JdkRoot.backup-$transactionId"
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    Expand-Archive -LiteralPath $resolvedArchive -DestinationPath $temporaryRoot
    $candidates = @(Get-ChildItem -LiteralPath $temporaryRoot -Directory)
    if ($candidates.Count -ne 1) { throw 'JDK archive must contain exactly one root directory.' }
    $candidate = $candidates[0]
    $candidatePath = Assert-JdkOwnedPath $candidate.FullName
    foreach ($item in @(Get-ChildItem -LiteralPath $candidatePath -Recurse -Force)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'JDK candidate contains a reparse point.' }
    }
    Assert-Java25 (Join-Path $candidatePath 'bin\java.exe')
    $backedUp = $false
    try {
        $null = Assert-JdkOwnedPath $JdkRoot
        if (Test-Path -LiteralPath $JdkRoot) {
            # Directory.Move renames exactly, never nests into an existing target.
            [IO.Directory]::Move($JdkRoot, $backupRoot)
            $backedUp = $true
        }
        [IO.Directory]::Move($candidatePath, $JdkRoot)
    } catch {
        $promotionFailure = $_
        if ($backedUp) {
            try { [IO.Directory]::Move($backupRoot, $JdkRoot) }
            catch { throw "JDK promotion failed and rollback was incomplete. Original files remain at $backupRoot. Recovery error: $($_.Exception.Message). Promotion error: $($promotionFailure.Exception.Message)" }
        }
        throw $promotionFailure
    }
    # Keep the exact previous tree, including unknown user files, for recovery.
    if ($backedUp) { Write-Host "Previous JDK directory retained at: $backupRoot" }
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $null = Assert-JdkOwnedPath $temporaryRoot
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

Write-Host "Installed and verified project-local JDK: $JdkRoot"
