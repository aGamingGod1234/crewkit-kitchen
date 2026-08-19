[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SourceKey,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-OfficialDigest([string]$Path, [psobject]$ArchiveRecord) {
    if ($null -ne $ArchiveRecord.sha512 -and -not [string]::IsNullOrWhiteSpace([string]$ArchiveRecord.sha512)) {
        $actualSha512 = (Get-FileHash -LiteralPath $Path -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actualSha512 -ne ([string]$ArchiveRecord.sha512).ToLowerInvariant()) {
            throw "Archive does not match the official ledger SHA512 digest."
        }
        return
    }
    if ($null -ne $ArchiveRecord.sha1 -and -not [string]::IsNullOrWhiteSpace([string]$ArchiveRecord.sha1)) {
        $actualSha1 = (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($actualSha1 -ne ([string]$ArchiveRecord.sha1).ToLowerInvariant()) {
            throw "Archive does not match the official ledger SHA1 digest."
        }
    }
}

function Test-ReparsePath([string]$Path, [string]$StopAt) {
    $current = [System.IO.Path]::GetFullPath($Path)
    $stop = [System.IO.Path]::GetFullPath($StopAt)
    while ($current.StartsWith($stop, [System.StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Destination contains a link or reparse point: $current"
            }
        }
        if ($current.Equals($stop, [System.StringComparison]::OrdinalIgnoreCase)) {
            return
        }
        $parent = [System.IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            return
        }
        $current = $parent.FullName
    }
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$ledgerPath = Join-Path $repositoryRoot 'maps\source-ledger.json'
$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceProperty = $ledger.sources.PSObject.Properties[$SourceKey]
if ($null -eq $sourceProperty) {
    throw "Source key '$SourceKey' is not present in the source ledger."
}

$source = $sourceProperty.Value
if ($null -eq $source.archive.url -or [string]::IsNullOrWhiteSpace([string]$source.archive.url)) {
    throw "Source '$SourceKey' has no ledger-approved archive URL. Its license or acquisition record is still provisional."
}
if ($source.licenseStatus -ne 'verified' -or -not $source.bundleEligible) {
    throw "Source '$SourceKey' is not approved for acquisition and bundled derivation."
}

$approvedUri = [System.Uri]$source.archive.url
if (-not $approvedUri.IsAbsoluteUri -or $approvedUri.Scheme -ne 'https' -or -not [string]::IsNullOrEmpty($approvedUri.UserInfo)) {
    throw "The ledger-approved archive URL for '$SourceKey' must be an unauthenticated HTTPS URL."
}

$researchRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot ([string]$ledger.researchRoot)))
if ([System.IO.Path]::IsPathRooted($Destination)) {
    $destinationPath = [System.IO.Path]::GetFullPath($Destination)
} else {
    $destinationPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Destination))
}
$researchPrefix = $researchRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $destinationPath.StartsWith($researchPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Destination must be a source-specific directory beneath ignored runtime/map-research."
}
Test-ReparsePath -Path $destinationPath -StopAt $repositoryRoot
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null

$archivePath = Join-Path $destinationPath ([string]$source.archive.filename)
$evidencePath = "$archivePath.sha256.json"
$lockedSha256 = if ($null -ne $source.archive.sha256) { ([string]$source.archive.sha256).ToLowerInvariant() } else { $null }
$existingEvidence = $null
if (Test-Path -LiteralPath $evidencePath) {
    $existingEvidence = Get-Content -LiteralPath $evidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($existingEvidence.sourceKey -ne $SourceKey -or $existingEvidence.url -ne $source.archive.url) {
        throw "Existing checksum evidence does not match source '$SourceKey' and its approved URL."
    }
    $evidenceSha256 = ([string]$existingEvidence.sha256).ToLowerInvariant()
    if ($null -ne $lockedSha256 -and $lockedSha256 -ne $evidenceSha256) {
        throw "Checksum drift exists between the ledger and retained evidence for '$SourceKey'."
    }
    $lockedSha256 = $evidenceSha256
}

if (Test-Path -LiteralPath $archivePath) {
    if ($null -eq $lockedSha256) {
        throw "An archive exists without a locked checksum. Move it aside before acquiring '$SourceKey'."
    }
    $existingFile = Get-Item -LiteralPath $archivePath
    if ($null -ne $source.archive.size -and $existingFile.Length -ne [long]$source.archive.size) {
        throw "Existing archive size $($existingFile.Length) does not match ledger size $($source.archive.size) for '$SourceKey'."
    }
    Assert-OfficialDigest -Path $archivePath -ArchiveRecord $source.archive
    $existingSha256 = Get-Sha256 -Path $archivePath
    if ($existingSha256 -ne $lockedSha256) {
        throw "Checksum drift detected for the existing '$SourceKey' archive."
    }
    [pscustomobject]@{
        sourceKey = $SourceKey
        archive = $archivePath
        evidence = $evidencePath
        sha256 = $existingSha256
        reused = $true
    }
    return
}

$acquisitionId = [System.Guid]::NewGuid().ToString('N')
$partialPath = Join-Path $destinationPath ('.' + [string]$source.archive.filename + '.' + $acquisitionId + '.partial')
$partialEvidencePath = "$evidencePath.$acquisitionId.partial"
$publishEvidence = -not (Test-Path -LiteralPath $evidencePath)
$archivePublishedByThisRun = $false
$evidencePublishedByThisRun = $false
$publishSucceeded = $false
try {
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -Uri $approvedUri.AbsoluteUri -OutFile $partialPath
    $downloadedFile = Get-Item -LiteralPath $partialPath
    if ($null -ne $source.archive.size -and $downloadedFile.Length -ne [long]$source.archive.size) {
        throw "Downloaded size $($downloadedFile.Length) does not match ledger size $($source.archive.size) for '$SourceKey'."
    }

    Assert-OfficialDigest -Path $partialPath -ArchiveRecord $source.archive
    $downloadedSha256 = Get-Sha256 -Path $partialPath
    if ($null -ne $lockedSha256 -and $downloadedSha256 -ne $lockedSha256) {
        throw "Checksum drift detected while acquiring '$SourceKey'."
    }

    $evidence = [ordered]@{
        schemaVersion = 1
        sourceKey = $SourceKey
        url = $approvedUri.AbsoluteUri
        filename = [string]$source.archive.filename
        retrievedAtUtc = [DateTime]::UtcNow.ToString('o')
        size = $downloadedFile.Length
        sha256 = $downloadedSha256
    }
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    if ($publishEvidence) {
        [System.IO.File]::WriteAllText($partialEvidencePath, (($evidence | ConvertTo-Json -Depth 4) + "`n"), $utf8WithoutBom)
    }
    Move-Item -LiteralPath $partialPath -Destination $archivePath
    $archivePublishedByThisRun = $true
    if ($publishEvidence) {
        if ($env:ARENAAGENTS_MAP_FETCH_FAIL_EVIDENCE_PROMOTION -eq '1') {
            throw "Injected evidence promotion failure."
        }
        Move-Item -LiteralPath $partialEvidencePath -Destination $evidencePath
        $evidencePublishedByThisRun = $true
    }
    $publishSucceeded = $true

    [pscustomobject]@{
        sourceKey = $SourceKey
        archive = $archivePath
        evidence = $evidencePath
        sha256 = $downloadedSha256
        reused = $false
    }
} finally {
    if (-not $publishSucceeded) {
        if ($evidencePublishedByThisRun -and (Test-Path -LiteralPath $evidencePath)) {
            Remove-Item -LiteralPath $evidencePath -Force
        }
        if ($archivePublishedByThisRun -and (Test-Path -LiteralPath $archivePath)) {
            Remove-Item -LiteralPath $archivePath -Force
        }
    }
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }
    if (Test-Path -LiteralPath $partialEvidencePath) {
        Remove-Item -LiteralPath $partialEvidencePath -Force
    }
}
