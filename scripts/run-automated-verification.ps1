[CmdletBinding()]
param([string] $ProjectRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$JavaHome = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Coordinator = Join-Path $Project 'coordinator'
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Missing project JDK: $Java" }
if (-not (Test-Path -LiteralPath (Join-Path $Coordinator 'package.json') -PathType Leaf)) { throw 'Missing coordinator package.json.' }

$env:JAVA_HOME = $JavaHome
Push-Location $Project
try {
    & .\gradlew.bat clean check build verifyCore --no-build-cache --rerun-tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed with code $LASTEXITCODE" }
} finally { Pop-Location }

Push-Location $Coordinator
try {
    & npm test
    if ($LASTEXITCODE -ne 0) { throw "Coordinator tests failed with code $LASTEXITCODE" }
} finally { Pop-Location }

Write-Host 'Automated Java, Fabric, coordinator, and fake-E2E verification passed.'
