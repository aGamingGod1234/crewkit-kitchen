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

function Assert-SafeRegularFile([string]$Path, [string]$Description) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description must be a regular, non-reparse file: $Path"
    }
}

function Remove-StaleAcquisitionPartials([string]$DestinationPath, [string]$ArchiveFilename) {
    $archivePartialPattern = '^\.' + [System.Text.RegularExpressions.Regex]::Escape($ArchiveFilename) + '\.[0-9a-f]{32}\.partial$'
    $evidencePartialPattern = '^' + [System.Text.RegularExpressions.Regex]::Escape("$ArchiveFilename.sha256.json") + '\.[0-9a-f]{32}\.partial$'
    foreach ($item in Get-ChildItem -LiteralPath $DestinationPath -Force) {
        if ($item.Name -notmatch $archivePartialPattern -and $item.Name -notmatch $evidencePartialPattern) {
            continue
        }
        if ($item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Stale acquisition partial must be a regular, non-reparse file: $($item.FullName)"
        }
        Remove-Item -LiteralPath $item.FullName -Force
    }
}

function Enter-MapAcquisitionLock([string]$LockPath, [int]$TimeoutMilliseconds = 30000) {
    $waitTimer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        if (Test-Path -LiteralPath $LockPath) {
            $lockItem = Get-Item -LiteralPath $LockPath -Force
            if (($lockItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Acquisition lock path is a link or reparse point: $LockPath"
            }
        }
        try {
            # The marker persists, but only the OS FileShare.None handle grants
            # ownership. A crashed process therefore cannot leave a stale lock.
            return [System.IO.File]::Open(
                $LockPath,
                [System.IO.FileMode]::OpenOrCreate,
                [System.IO.FileAccess]::ReadWrite,
                [System.IO.FileShare]::None
            )
        } catch [System.IO.IOException] {
            $win32Error = $_.Exception.HResult -band 0xFFFF
            if ($win32Error -ne 32 -and $win32Error -ne 33) {
                throw
            }
            if ($waitTimer.ElapsedMilliseconds -ge $TimeoutMilliseconds) {
                throw "Timed out after $TimeoutMilliseconds ms waiting for the map acquisition lock: $LockPath"
            }
            Start-Sleep -Milliseconds 50
        }
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

$lockPath = Join-Path $destinationPath '.arenaagents-acquisition.lock'
$acquisitionLock = Enter-MapAcquisitionLock -LockPath $lockPath
try {
$archivePath = Join-Path $destinationPath ([string]$source.archive.filename)
$evidencePath = "$archivePath.sha256.json"
$archiveExistedBefore = Test-Path -LiteralPath $archivePath
$evidenceExistedBefore = Test-Path -LiteralPath $evidencePath
$evidenceOrphanAwaitingReplacement = $false
if ($archiveExistedBefore) {
    Assert-SafeRegularFile -Path $archivePath -Description 'Expected archive path'
}
if ($evidenceExistedBefore) {
    Assert-SafeRegularFile -Path $evidencePath -Description 'Expected evidence path'
}
if ($archiveExistedBefore -xor $evidenceExistedBefore) {
    Remove-StaleAcquisitionPartials -DestinationPath $destinationPath -ArchiveFilename ([string]$source.archive.filename)
    if ($archiveExistedBefore) {
        Remove-Item -LiteralPath $archivePath -Force
        $archiveExistedBefore = $false
    } else {
        # Keep the orphan evidence until the new archive has promoted. This
        # preserves it if acquisition fails before replacement, but it is not
        # read or trusted as checksum evidence for this run.
        $evidenceOrphanAwaitingReplacement = $true
        $evidenceExistedBefore = $false
    }
}
$lockedSha256 = if ($null -ne $source.archive.sha256) { ([string]$source.archive.sha256).ToLowerInvariant() } else { $null }
$existingEvidence = $null
if ($evidenceExistedBefore) {
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

if ($archiveExistedBefore) {
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
$publishEvidence = -not $evidenceExistedBefore
$archivePromotionAttemptedByThisRun = $false
$evidencePromotionAttemptedByThisRun = $false
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
    $archivePromotionAttemptedByThisRun = $true
    Move-Item -LiteralPath $partialPath -Destination $archivePath
    if ($publishEvidence) {
        if ($evidenceOrphanAwaitingReplacement) {
            Assert-SafeRegularFile -Path $evidencePath -Description 'Interrupted evidence path'
            Remove-Item -LiteralPath $evidencePath -Force
        }
        $evidencePromotionAttemptedByThisRun = $true
        Move-Item -LiteralPath $partialEvidencePath -Destination $evidencePath
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
        if ($evidencePromotionAttemptedByThisRun -and -not $evidenceExistedBefore) {
            if (Test-Path -LiteralPath $evidencePath) {
                Remove-Item -LiteralPath $evidencePath -Force
            }
        }
        if ($archivePromotionAttemptedByThisRun -and -not $archiveExistedBefore) {
            if (Test-Path -LiteralPath $archivePath) {
                Remove-Item -LiteralPath $archivePath -Force
            }
        }
    }
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }
    if (Test-Path -LiteralPath $partialEvidencePath) {
        Remove-Item -LiteralPath $partialEvidencePath -Force
    }
}
} finally {
    $acquisitionLock.Dispose()
}
