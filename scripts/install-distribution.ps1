[CmdletBinding()]
param(
    [string] $JavaPath,
    [string] $LauncherProfiles = (Join-Path $env:APPDATA '.minecraft\launcher_profiles.json'),
    [string] $GameDirectory = (Join-Path $env:APPDATA '.minecraft-arena-agents')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$MinecraftVersion = '26.1.2'
$LoaderVersion = '0.19.3'
$VersionId = "fabric-loader-$LoaderVersion-$MinecraftVersion"
$ProfileId = 'arena-agents-modpack'
$ProfileName = 'Arena Agents'
$MinimumSecretLength = 32
$SecretByteCount = 32
$ExpectedModNames = @(
    'arena-agents-0.1.0.jar'
    'fabric-api-0.150.0+26.1.2.jar'
    'fabric-carpet-26.1+v260402.jar'
)
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$PackageRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$ModsSource = Join-Path $PackageRoot 'mods'
$RuntimeDirectory = Join-Path $PackageRoot 'runtime'
$RuntimeDeploymentHelper = Join-Path $PSScriptRoot 'distribution-runtime.ps1'
$StartupPackagingPreflight = Join-Path $PSScriptRoot 'verify-startup-packaging.ps1'
$SecretPath = Join-Path $RuntimeDirectory 'bridge-secret.txt'
$VoiceSecretPath = Join-Path $RuntimeDirectory 'voice-secret.txt'
$ResolvedGameDirectory = [IO.Path]::GetFullPath($GameDirectory)
$InstalledPackageRoot = Join-Path $ResolvedGameDirectory 'arena-agents-runtime'
$VersionMetadata = Join-Path $env:APPDATA ".minecraft\versions\$VersionId\$VersionId.json"

function Set-OwnerOnlyAccess([string] $Path, [bool] $Directory = $false) {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = if ($Directory) {
        [Security.AccessControl.DirectorySecurity]::new()
    } else {
        [Security.AccessControl.FileSecurity]::new()
    }
    $acl.SetOwner($currentUser)
    $acl.SetAccessRuleProtection($true, $false)
    $inheritance = if ($Directory) {
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $currentUser,
        [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    [void]$acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Initialize-PrivateSecret([string] $Path, [string] $Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $bytes = New-Object byte[] $SecretByteCount
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        $secretValue = (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
        [IO.File]::WriteAllText($Path, $secretValue, $Utf8NoBom)
    }
    Set-OwnerOnlyAccess $Path
    $value = [IO.File]::ReadAllText($Path).Trim()
    if ($value.Length -lt $MinimumSecretLength) { throw "The package $Label secret is too short." }
    $value
}

if (Get-Process -Name MinecraftLauncher,Minecraft -ErrorAction SilentlyContinue) {
    throw 'Close Minecraft and Minecraft Launcher before installing Arena Agents.'
}
if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JavaPath = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
    } else {
        $javaCommand = Get-Command javaw.exe -ErrorAction SilentlyContinue
        if ($null -ne $javaCommand) { $JavaPath = $javaCommand.Source }
    }
}
if ([string]::IsNullOrWhiteSpace($JavaPath) -or -not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw 'Java 25 javaw.exe was not found. Set JAVA_HOME or pass -JavaPath explicitly.'
}
$ResolvedJava = (Resolve-Path -LiteralPath $JavaPath).Path
$savedErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $ResolvedJava -version 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $savedErrorPreference
}
if ($LASTEXITCODE -ne 0) { throw "Java version check failed with code $LASTEXITCODE." }
if ($javaVersion -notmatch 'version "25(\.|")') {
    throw "Arena Agents requires Java 25. Detected: $($javaVersion.Trim())"
}
foreach ($required in @($LauncherProfiles, $VersionMetadata, $ModsSource, $RuntimeDeploymentHelper, $StartupPackagingPreflight)) {
	if (-not (Test-Path -LiteralPath $required)) { throw "Missing installation prerequisite: $required" }
}

. $RuntimeDeploymentHelper
& $StartupPackagingPreflight -PackageRoot $PackageRoot

New-Item -ItemType Directory -Force -Path $RuntimeDirectory, (Join-Path $ResolvedGameDirectory 'mods'), $InstalledPackageRoot | Out-Null
Set-OwnerOnlyAccess $RuntimeDirectory $true
$secret = Initialize-PrivateSecret $SecretPath 'bridge'
$voiceSecret = Initialize-PrivateSecret $VoiceSecretPath 'voice'
if ($secret -eq $voiceSecret) { throw 'Bridge and voice secrets must be distinct.' }

$includedMods = @(Get-ChildItem -LiteralPath $ModsSource -Filter '*.jar' -File)
$unexpectedMods = @(Compare-Object -ReferenceObject $ExpectedModNames -DifferenceObject @($includedMods.Name) -PassThru)
if ($unexpectedMods.Count -ne 0) {
    throw "Packaged mod JAR allowlist mismatch: $($unexpectedMods -join ', ')."
}
foreach ($mod in $includedMods) {
    Copy-Item -LiteralPath $mod.FullName -Destination (Join-Path $ResolvedGameDirectory 'mods') -Force
}

New-Item -ItemType Directory -Force -Path (Join-Path $InstalledPackageRoot 'runtime') | Out-Null
Copy-Item -LiteralPath $SecretPath -Destination (Join-Path $InstalledPackageRoot 'runtime\bridge-secret.txt') -Force
Copy-Item -LiteralPath $VoiceSecretPath -Destination (Join-Path $InstalledPackageRoot 'runtime\voice-secret.txt') -Force
Set-OwnerOnlyAccess (Join-Path $InstalledPackageRoot 'runtime') $true
Set-OwnerOnlyAccess (Join-Path $InstalledPackageRoot 'runtime\bridge-secret.txt')
Set-OwnerOnlyAccess (Join-Path $InstalledPackageRoot 'runtime\voice-secret.txt')
$runtimeDeployment = Install-ArenaCoordinatorRuntime -SourceRoot $PackageRoot -InstalledPackageRoot $InstalledPackageRoot

$resolvedProfiles = (Resolve-Path -LiteralPath $LauncherProfiles).Path
$document = Get-Content -LiteralPath $resolvedProfiles -Raw | ConvertFrom-Json
if ($null -eq $document.profiles) {
    $document | Add-Member -MemberType NoteProperty -Name profiles -Value ([pscustomobject]@{})
}
$javaArguments = "-Xms1G -Xmx4G -Darenaagents.bridgeSecretFile=`"$SecretPath`" -Darenaagents.voiceSecretFile=`"$VoiceSecretPath`" -Darenaagents.packageRoot=`"$InstalledPackageRoot`""
$expected = [ordered]@{
    gameDir = $ResolvedGameDirectory
    javaArgs = $javaArguments
    javaDir = $ResolvedJava
    lastVersionId = $VersionId
    name = $ProfileName
    type = 'custom'
}
$existing = $document.profiles.PSObject.Properties[$ProfileId]
$changed = $false
if ($null -ne $existing) {
    foreach ($field in $expected.Keys) {
        if ([string]$existing.Value.$field -ne [string]$expected[$field]) {
            throw "Existing launcher profile '$ProfileId' does not match expected field '$field'."
        }
    }
} else {
    $timestamp = (Get-Date).ToUniversalTime().ToString('o')
    $profile = [pscustomobject][ordered]@{
        created = $timestamp
        gameDir = $expected.gameDir
        icon = 'Furnace'
        javaArgs = $expected.javaArgs
        javaDir = $expected.javaDir
        lastUsed = $timestamp
        lastVersionId = $expected.lastVersionId
        name = $expected.name
        type = $expected.type
    }
    $document.profiles | Add-Member -MemberType NoteProperty -Name $ProfileId -Value $profile
    $changed = $true
}
if ($changed) {
    Copy-Item -LiteralPath $resolvedProfiles -Destination "$resolvedProfiles.arena-agents.backup" -Force
    [IO.File]::WriteAllText($resolvedProfiles, ($document | ConvertTo-Json -Depth 64), $Utf8NoBom)
}

Write-Host "Arena Agents installed to $ResolvedGameDirectory"
Write-Host "Launcher profile: $ProfileName"
if ($null -ne $runtimeDeployment.BackupPath) { Write-Host "Previous coordinator backup: $($runtimeDeployment.BackupPath)" }
Write-Host 'The bundled coordinator now starts and reconnects automatically in a world.'
