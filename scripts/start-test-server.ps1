[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [switch] $OfflineSmoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Java = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe'
$Server = Join-Path $Project $(if ($OfflineSmoke) { 'runtime\server-offline-smoke' } else { 'runtime\server' })
$Launcher = Join-Path $Server 'fabric-server-launch.jar'
$Properties = Join-Path $Server 'server.properties'

foreach ($required in @($Java,$Launcher,$Properties)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing server prerequisite: $required" }
}
$settings = Get-Content -LiteralPath $Properties -Raw
$expectedMode = if ($OfflineSmoke) { 'online-mode=false' } else { 'online-mode=true' }
if ($settings -notmatch "(?m)^$([regex]::Escape($expectedMode))$") {
    throw "Server mode mismatch. Expected $expectedMode in $Properties"
}
if ($OfflineSmoke) {
    Write-Warning 'Starting OFFLINE SMOKE server. Results are not authenticated-player verification.'
} else {
    Write-Host 'Starting authenticated online-mode test server.'
}

Push-Location $Server
try {
    & $Java -Xms1G -Xmx4G -jar $Launcher nogui
    if ($LASTEXITCODE -ne 0) { throw "Minecraft server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
