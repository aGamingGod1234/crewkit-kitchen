[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ProjectRoot,
    [Parameter(Mandatory = $true)] [string] $GameDirectory,
    [ValidateSet('None', 'AfterJarsBackup', 'AfterBackup', 'AfterJarSwap', 'AfterCoordinatorSwap')]
    [string] $FailurePoint = 'None',
    [switch] $TestProcessClassification
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ContainedPath([string] $Base, [string] $Child, [string] $Label) {
    $basePath = [IO.Path]::GetFullPath($Base).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $childPath = [IO.Path]::GetFullPath($Child)
    if (-not $childPath.StartsWith($basePath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes its validated target: $childPath"
    }
    return $childPath
}

function Assert-NoReparse([string] $Path, [string] $Label) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($null -ne $current -and $current.Length -gt 2) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $current" }
        }
        $parent = Split-Path -Parent $current
        if ($parent -eq $current) { break }
        $current = $parent
    }
}

function Assert-NoReparseTree([string] $Path, [string] $Label) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($item in @(Get-Item -LiteralPath $Path -Force) + @(Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction Stop)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $($item.FullName)" }
    }
}

function Test-UnsafeJavaProcess([string] $Name, [string] $CommandLine) {
    if ($Name -ieq 'javaw.exe') { return $true }
    if ($Name -ine 'java.exe') { return $false }
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $true }
    return $CommandLine -match '(?i)(net\.minecraft\.client\.main\.Main|net\.minecraft\.server\.Main|KnotClient|KnotServer|fabric-server-launch|minecraft_server)'
}

if ($TestProcessClassification) {
    if (-not (Test-UnsafeJavaProcess 'javaw.exe' '')) { throw 'javaw.exe must be unsafe even without a command line.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'net.minecraft.client.main.Main')) { throw 'Minecraft client main was not classified unsafe.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'KnotServer')) { throw 'KnotServer was not classified unsafe.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'GradleDaemon')) { } else { throw 'Gradle daemon was incorrectly classified unsafe.' }
    exit 0
}

function Get-ExpectedCoordinatorFiles([string] $CoordinatorRoot) {
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($fixed in @('package.json', 'package-lock.json')) {
        $file = Join-Path $CoordinatorRoot $fixed
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing coordinator runtime file: $file" }
        $paths.Add($fixed)
    }
    foreach ($root in @('config', 'src', 'node_modules\acorn')) {
        $rootPath = Join-Path $CoordinatorRoot $root
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Missing coordinator runtime root: $rootPath" }
        foreach ($file in Get-ChildItem -LiteralPath $rootPath -Recurse -File) {
            $paths.Add($file.FullName.Substring($CoordinatorRoot.Length + 1).Replace('\', '/'))
        }
    }
    return @($paths | Sort-Object -Unique)
}

function Get-BytesHash([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return (($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '').ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Assert-ArchiveParity([string] $JarPath, [string] $CoordinatorRoot, [string[]] $Expected) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $prefix = 'arena-agents/coordinator/'
        $files = @($zip.Entries | Where-Object { $_.FullName.StartsWith($prefix) -and -not $_.FullName.EndsWith('/') -and $_.FullName -ne ($prefix + 'coordinator-manifest.txt') } | ForEach-Object { $_.FullName.Substring($prefix.Length) } | Sort-Object)
        if (@(Compare-Object -ReferenceObject $Expected -DifferenceObject $files).Count -ne 0) { throw 'Embedded coordinator entries differ from the independently derived source set.' }
        $manifestEntry = $zip.GetEntry($prefix + 'coordinator-manifest.txt')
        if ($null -eq $manifestEntry) { throw 'Embedded coordinator manifest is missing.' }
        $manifestReader = [IO.StreamReader]::new($manifestEntry.Open())
        try { $manifestLines = @($manifestReader.ReadToEnd() -split "\r?\n" | Where-Object { $_ -ne '' }) }
        finally { $manifestReader.Dispose() }
		$manifestRecords = @($manifestLines | ForEach-Object {
			if ($_ -notmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>.+)$') { throw "Invalid embedded coordinator manifest entry: $_" }
			[pscustomobject]@{ Hash = $Matches.Hash.ToUpperInvariant(); Path = $Matches.Path }
		})
		$manifest = @($manifestRecords.Path | Sort-Object)
        if (@(Compare-Object -ReferenceObject $Expected -DifferenceObject $manifest).Count -ne 0) { throw 'Embedded coordinator manifest differs from the independently derived source set.' }
        foreach ($relative in $Expected) {
            $source = Join-Path $CoordinatorRoot ($relative.Replace('/', '\'))
            $entry = $zip.GetEntry($prefix + $relative)
            $stream = $entry.Open()
            try {
                $memory = [IO.MemoryStream]::new()
                try { $stream.CopyTo($memory); $archiveHash = Get-BytesHash $memory.ToArray() }
                finally { $memory.Dispose() }
            } finally { $stream.Dispose() }
            $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToUpperInvariant()
            if ($sourceHash -ne $archiveHash) { throw "Coordinator hash mismatch: $relative" }
			$manifestHash = ($manifestRecords | Where-Object { $_.Path -ceq $relative }).Hash
			if ($manifestHash -cne $archiveHash) { throw "Coordinator manifest hash mismatch: $relative" }
        }
    } finally { $zip.Dispose() }
}

function Copy-ExpectedCoordinator([string] $Source, [string] $Destination, [string[]] $Expected) {
    foreach ($relative in $Expected) {
        $target = Join-Path $Destination ($relative.Replace('/', '\'))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath (Join-Path $Source ($relative.Replace('/', '\'))) -Destination $target -Force
    }
}

$project = [IO.Path]::GetFullPath($ProjectRoot)
$game = [IO.Path]::GetFullPath($GameDirectory)
Assert-NoReparse $project 'project root'
Assert-NoReparse $game 'game directory'
$mods = Resolve-ContainedPath $game (Join-Path $game 'mods') 'mods target'
$runtime = Resolve-ContainedPath $game (Join-Path $game 'arena-agents-runtime') 'runtime target'
$jar = Join-Path $project 'build\libs\arena-agents-0.1.0.jar'
$coordinator = Join-Path $project 'coordinator'
Assert-NoReparse $mods 'mods target'
Assert-NoReparse $runtime 'runtime target'
Assert-NoReparse $coordinator 'coordinator root'
Assert-NoReparseTree $coordinator 'coordinator root'
Assert-NoReparseTree $mods 'mods target'
Assert-NoReparseTree $runtime 'runtime target'
foreach ($required in @($jar, (Join-Path $coordinator 'package.json'), (Join-Path $coordinator 'src'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing packaging prerequisite: $required" }
}
if (Get-Process -Name MinecraftLauncher, Minecraft -ErrorAction SilentlyContinue) { throw 'Close Minecraft and Minecraft Launcher before updating the normal profile.' }
try { $javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction Stop) }
catch { throw "Unable to inspect Java process command lines; refusing to update: $($_.Exception.Message)" }
foreach ($process in $javaProcesses) {
    $commandLine = [string]$process.CommandLine
    if (Test-UnsafeJavaProcess ([string]$process.Name) $commandLine) { throw "A Minecraft/Fabric Java process is active (PID $($process.ProcessId)); refusing to update." }
}
foreach ($dependency in @('fabric-api-0.150.0+26.1.2.jar', 'fabric-carpet-26.1+v260402.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $mods $dependency) -PathType Leaf)) { throw "Required dependency is missing from target mods: $dependency" }
}

$expected = Get-ExpectedCoordinatorFiles $coordinator
$secret = Join-Path $runtime 'runtime\bridge-secret.txt'
if (-not (Test-Path -LiteralPath $secret -PathType Leaf)) { throw "Installed bridge secret is missing: $secret" }
$secretHash = (Get-FileHash -LiteralPath $secret -Algorithm SHA256).Hash
Assert-ArchiveParity $jar $coordinator $expected
$stage = Join-Path $game ('.arena-agents-update-' + [guid]::NewGuid().ToString('N'))
$backup = Join-Path $game ('.arena-agents-backup-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N'))
$stageMods = Join-Path $stage 'mods'
$stageCoordinator = Join-Path $stage 'coordinator'
$installedCoordinator = Join-Path $runtime 'coordinator'
$installedJar = Join-Path $mods 'arena-agents-0.1.0.jar'
$backupMade = $false
$oldArena = @()
try {
    Assert-NoReparse $stage 'staging path'
    Assert-NoReparse $backup 'backup path'
    New-Item -ItemType Directory -Force -Path $stageMods, $stageCoordinator | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stageMods 'arena-agents-0.1.0.jar') -Force
    Copy-ExpectedCoordinator $coordinator $stageCoordinator $expected
    foreach ($relative in $expected) {
        $sourceHash = (Get-FileHash (Join-Path $coordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
        $stageHash = (Get-FileHash (Join-Path $stageCoordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
        if ($sourceHash -ne $stageHash) { throw "Staged coordinator hash mismatch: $relative" }
    }
    New-Item -ItemType Directory -Force -Path $backup | Out-Null
    $oldArena = @(Get-ChildItem -LiteralPath $mods -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^arena-agents-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?\.jar$' })
    foreach ($old in $oldArena) { Assert-NoReparseTree $old.FullName "Arena JAR $($old.Name)" }
    foreach ($old in $oldArena) { Copy-Item -LiteralPath $old.FullName -Destination (Join-Path $backup $old.Name) -Force }
    if ($FailurePoint -eq 'AfterJarsBackup') { throw 'Injected failure after JAR backup.' }
    if (Test-Path -LiteralPath $installedCoordinator) { Copy-Item -LiteralPath $installedCoordinator -Destination (Join-Path $backup 'coordinator') -Recurse -Force }
    Assert-NoReparseTree $backup 'backup path'
    foreach ($item in @(Get-ChildItem -LiteralPath $backup -Recurse -File)) {
        $relative = $item.FullName.Substring($backup.Length + 1)
        $source = if ($relative.StartsWith('coordinator\')) { Join-Path $installedCoordinator $relative.Substring('coordinator'.Length).TrimStart('\') } else { Join-Path $mods $relative }
        if ((Get-FileHash $source -Algorithm SHA256).Hash -ne (Get-FileHash $item.FullName -Algorithm SHA256).Hash) { throw "Backup hash mismatch: $relative" }
    }
    if ((Get-FileHash $secret -Algorithm SHA256).Hash -ne $secretHash) { throw 'Bridge secret changed during backup.' }
    $backupMade = $true
    if ($FailurePoint -eq 'AfterBackup') { throw 'Injected failure after full backup.' }
    Assert-NoReparse $game 'game directory before mutation'
    Assert-NoReparseTree $mods 'mods target before mutation'
    Assert-NoReparseTree $runtime 'runtime target before mutation'
    Assert-NoReparseTree $stage 'staging path before mutation'
    Assert-NoReparseTree $backup 'backup path before mutation'
    foreach ($old in $oldArena) { Remove-Item -LiteralPath $old.FullName -Force }
    Copy-Item -LiteralPath (Join-Path $stageMods 'arena-agents-0.1.0.jar') -Destination $installedJar -Force
    if ($FailurePoint -eq 'AfterJarSwap') { throw 'Injected failure after JAR swap.' }
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    if (Test-Path -LiteralPath $installedCoordinator) { Remove-Item -LiteralPath $installedCoordinator -Recurse -Force }
    Copy-Item -LiteralPath $stageCoordinator -Destination $installedCoordinator -Recurse -Force
    if ($FailurePoint -eq 'AfterCoordinatorSwap') { throw 'Injected failure after coordinator swap.' }
    if ((Get-FileHash $installedJar -Algorithm SHA256).Hash -ne (Get-FileHash $jar -Algorithm SHA256).Hash) { throw 'Installed JAR hash verification failed.' }
    foreach ($relative in $expected) {
        if ((Get-FileHash (Join-Path $installedCoordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash -ne (Get-FileHash (Join-Path $coordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash) { throw "Installed coordinator hash verification failed: $relative" }
    }
    if ((Get-FileHash $secret -Algorithm SHA256).Hash -ne $secretHash) { throw 'Installed bridge secret changed.' }
    Write-Host "Normal profile updated: $game"
    Write-Host "Jar SHA-256: $((Get-FileHash $installedJar -Algorithm SHA256).Hash)"
} catch {
    if ($backupMade) {
        if (Test-Path -LiteralPath $installedJar) { Remove-Item -LiteralPath $installedJar -Force }
        if (Test-Path -LiteralPath $installedCoordinator) { Remove-Item -LiteralPath $installedCoordinator -Recurse -Force }
        foreach ($old in $oldArena) { $saved = Join-Path $backup $old.Name; if (Test-Path -LiteralPath $saved) { Copy-Item -LiteralPath $saved -Destination $old.FullName -Force } }
        $oldCoordinator = Join-Path $backup 'coordinator'
        if (Test-Path -LiteralPath $oldCoordinator) { Copy-Item -LiteralPath $oldCoordinator -Destination $installedCoordinator -Recurse -Force }
        foreach ($old in $oldArena) { if ((Get-FileHash $old.FullName -Algorithm SHA256).Hash -ne (Get-FileHash (Join-Path $backup $old.Name) -Algorithm SHA256).Hash) { throw "Rollback hash verification failed; preserved backup: $backup" } }
        if ((Get-FileHash $secret -Algorithm SHA256).Hash -ne $secretHash) { throw "Rollback secret verification failed; preserved backup: $backup" }
        Write-Host "Rollback completed; verified backup preserved at $backup"
    }
    throw
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
