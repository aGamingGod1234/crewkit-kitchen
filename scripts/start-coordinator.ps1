[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $ConfigPath,
    [ValidateSet('all','agent-55','agent-56')]
    [string] $Agent = 'all'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Coordinator = Join-Path $Project 'coordinator'
$Main = Join-Path $Coordinator 'src\main.mjs'
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $Coordinator 'config\agents.json' }
$ResolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path

foreach ($required in @($Main,$ResolvedConfig)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing coordinator prerequisite: $required" }
}
$node = (Get-Command node -ErrorAction Stop).Source
$nodeVersion = (& $node --version).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v25\.') { throw "Node 25 is required; found $nodeVersion" }
$codex = (Get-Command codex -ErrorAction Stop).Source
& $codex login status | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Codex is not authenticated.' }

New-Item -ItemType Directory -Force -Path `
    (Join-Path $Project 'runtime\agent55\traces'), `
    (Join-Path $Project 'runtime\agent56\traces') | Out-Null
Push-Location $Coordinator
try {
    & $node $Main --config $ResolvedConfig --agent $Agent
    if ($LASTEXITCODE -ne 0) { throw "Coordinator exited with code $LASTEXITCODE" }
} finally { Pop-Location }
