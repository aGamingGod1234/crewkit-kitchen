[CmdletBinding()]
param([string] $OutputDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$verifier = Join-Path $PSScriptRoot 'verify-coordinator-packaging.ps1'
$temporary = [string]::IsNullOrWhiteSpace($OutputDirectory)
if ($temporary) { $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('arena-packaging-test-' + [guid]::NewGuid().ToString('N')) }
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $root) { throw 'Use a new output directory for packaging fixtures.' }
[IO.Directory]::CreateDirectory($root) | Out-Null
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$utf8 = [Text.UTF8Encoding]::new($false)
$source = Join-Path $root 'source'
$stage = Join-Path $root 'stage'
$jar = Join-Path $root 'fixture.jar'
$files = @(
    'package.json', 'package-lock.json', 'config/dynamic-agents.json', 'src/dynamic-main.mjs',
    'src/job-gate.mjs', 'src/posix-process-group.mjs', 'src/posix-process-wrapper.mjs',
    'src/voice/local-speech-requirements.txt', 'src/voice/local-speech-worker.py',
    'config/minecraft-agent/AGENTS.md', 'config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md',
    'node_modules/acorn/package.json', 'node_modules/acorn/dist/acorn.mjs'
)
$results = [Collections.Generic.List[object]]::new()
function Write-FixtureFile([string] $Path, [string] $Text) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, $utf8)
}
function Test-Case([string] $Name, [bool] $Expected, [string] $ErrorPattern = '', [switch] $JarOnly) {
    $accepted = $false
    $failure = ''
    try {
        if ($JarOnly) { $output = @(& $verifier -JarPath $jar *>&1) }
        else { $output = @(& $verifier -JarPath $jar -StagingPath $stage -SourceCoordinatorPath $source *>&1) }
        $accepted = $true
    } catch { $failure = $_.Exception.Message }
    $passed = $accepted -eq $Expected -and (-not $ErrorPattern -or $failure -match $ErrorPattern)
    $results.Add([pscustomobject]@{name=$Name; accepted=$accepted; expected=$Expected; passed=$passed; error=$failure})
    Write-Host "$Name : passed=$passed accepted=$accepted $failure"
}
function Set-ArchiveEntry([string] $Name, [byte[]] $Bytes) {
    $zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.GetEntry($Name)
        if ($null -ne $entry) { $entry.Delete() }
        $stream = $zip.CreateEntry($Name).Open()
        try { $stream.Write($Bytes, 0, $Bytes.Length) } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
}
try {
    $records = @(foreach ($relative in $files) {
        foreach ($tree in @($source, $stage)) { Write-FixtureFile (Join-Path $tree $relative) "fixture: $relative`n" }
        $hash = (Get-FileHash -LiteralPath (Join-Path $source $relative) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash $relative"
    })
    $manifest = $utf8.GetBytes(($records -join "`n") + "`n")
    $zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    $zip.Dispose()
    foreach ($relative in $files) { Set-ArchiveEntry ('arena-agents/coordinator/' + $relative) ([IO.File]::ReadAllBytes((Join-Path $source $relative))) }
    Set-ArchiveEntry 'arena-agents/coordinator/coordinator-manifest.txt' $manifest
    Test-Case 'jar-only' $true -JarOnly
    Test-Case 'raw-extraction' $true
    $marker = Join-Path $stage '.arena-agents-bundle-manifest'
    [IO.File]::WriteAllBytes($marker, $manifest)
    Test-Case 'installed-manifest' $true
    [IO.File]::SetAttributes($marker, [IO.FileAttributes]::Hidden)
    Test-Case 'hidden-installed-manifest' $true
    [IO.File]::SetAttributes($marker, [IO.FileAttributes]::Normal)
    [IO.File]::WriteAllText($marker, 'invalid', $utf8)
    [IO.File]::SetAttributes($marker, [IO.FileAttributes]::Hidden)
    Test-Case 'invalid-hidden-manifest' $false 'Installed coordinator manifest differs'
    [IO.File]::SetAttributes($marker, [IO.FileAttributes]::Normal)
    [IO.File]::WriteAllBytes($marker, $manifest)
    Test-Case 'installed-manifest-restored' $true
    [IO.File]::Delete($marker)
    $extra = Join-Path $stage 'unrelated.txt'
    Write-FixtureFile $extra 'extra'
    Test-Case 'unrelated-extra' $false 'staging does not match'
    [IO.File]::SetAttributes($extra, [IO.FileAttributes]::Hidden)
    Test-Case 'hidden-unrelated-extra' $false 'staging does not match'
    [IO.File]::SetAttributes($extra, [IO.FileAttributes]::Normal)
    [IO.File]::Delete($extra)
    $sourceFile = Join-Path $source 'src/dynamic-main.mjs'
    $stageFile = Join-Path $stage 'src/dynamic-main.mjs'
    $original = [IO.File]::ReadAllBytes($sourceFile)
    [IO.File]::AppendAllText($sourceFile, '// changed', $utf8)
    [IO.File]::WriteAllBytes($stageFile, [IO.File]::ReadAllBytes($sourceFile))
    Test-Case 'matching-source-stage-different-jar' $false 'Source coordinator hash differs from embedded'
    [IO.File]::WriteAllBytes($sourceFile, $original)
    Test-Case 'stage-only-mismatch' $false 'Installed coordinator hash differs'
    [IO.File]::WriteAllBytes($stageFile, $original)
    Test-Case 'restored-parity' $true
    Set-ArchiveEntry 'data/arenaagents/arena_modules/fixture.json' ($utf8.GetBytes('{"sourceKey":"re-structured"}'))
    Test-Case 'derived-module-missing-notice' $false 'Retained map notice is missing' -JarOnly
    Set-ArchiveEntry 'META-INF/licenses/re-structured-MIT.txt' ($utf8.GetBytes('unrelated attribution'))
    Test-Case 'derived-module-wrong-notice' $false 'Retained map notice hash differs' -JarOnly
    Set-ArchiveEntry 'META-INF/licenses/re-structured-MIT.txt' ([IO.File]::ReadAllBytes((Join-Path $project 'maps/licenses/re-structured-MIT.txt')))
    Test-Case 'derived-module-exact-notice' $true -JarOnly
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'results.json') -Encoding UTF8
    if (@($results | Where-Object { -not $_.passed }).Count) { throw 'Packaging regression failed; see case results.' }
    Write-Host "PASS: $($results.Count) offline packaging cases."
} finally {
    if ($temporary) {
        $resolved = (Resolve-Path -LiteralPath $root).Path
        $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
