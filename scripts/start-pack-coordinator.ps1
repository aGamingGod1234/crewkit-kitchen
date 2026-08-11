[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$SecretPath = Join-Path $PackageRoot 'runtime\bridge-secret.txt'
$CoordinatorPath = Join-Path $PackageRoot 'coordinator\src\dynamic-main.mjs'
$ConfigPath = Join-Path $PackageRoot 'coordinator\config\dynamic-agents.json'

foreach ($required in @($SecretPath, $CoordinatorPath, $ConfigPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing Arena Agents runtime file: $required"
    }
}
$node = Get-Command node.exe -ErrorAction SilentlyContinue
if ($null -eq $node) { throw 'Node.js 22 or newer is required to run the Arena Agents coordinator.' }
$nodeVersion = (& $node.Source --version).Trim()
if ($nodeVersion -notmatch '^v(\d+)') { throw "Could not parse Node.js version '$nodeVersion'." }
if ([int]$Matches[1] -lt 22) { throw "Node.js 22 or newer is required; detected $nodeVersion." }

$secret = [IO.File]::ReadAllText($SecretPath).Trim()
if ($secret.Length -lt 32) { throw 'Arena Agents bridge secret is missing or invalid.' }
$env:ARENA_AGENT_BRIDGE_SECRET = $secret
try {
    & $node.Source $CoordinatorPath --config $ConfigPath
    if ($LASTEXITCODE -ne 0) { throw "Arena Agents coordinator exited with code $LASTEXITCODE." }
} finally {
    Remove-Item Env:\ARENA_AGENT_BRIDGE_SECRET -ErrorAction SilentlyContinue
}
