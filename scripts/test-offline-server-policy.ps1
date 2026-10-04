[CmdletBinding()]
param([string] $FixtureRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
$retainFixtures = -not [string]::IsNullOrWhiteSpace($FixtureRoot)
if (-not $retainFixtures) { $FixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('arena-properties-' + [Guid]::NewGuid().ToString('N')) }
$FixtureRoot = [IO.Path]::GetFullPath($FixtureRoot)
$utf8 = [Text.UTF8Encoding]::new($false)
$cases = @(
    @{id='safe-offline'; text="online-mode=false`nserver-ip=127.0.0.1"; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='plain-duplicate'; text="online-mode=false`nserver-ip=127.0.0.1`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='hash-comment'; text="online-mode=false`nserver-ip=127.0.0.1`n# note \`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='bang-comment'; text="online-mode=false`nserver-ip=127.0.0.1`n `t! note \`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='odd-comment'; text="online-mode=false`nserver-ip=127.0.0.1`n# note \\\`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='even-comment'; text="online-mode=false`nserver-ip=127.0.0.1`n! note \\`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='hidden-offline'; text="online-mode=true`nserver-ip=0.0.0.0`n# note \`nonline-mode=false"; mode='false'; ip='0.0.0.0'; accepted=$false; online=$true},
    @{id='safe-online'; text="online-mode=true`nserver-ip=0.0.0.0"; mode='true'; ip='0.0.0.0'; accepted=$true; online=$true},
    @{id='continued-key'; text="online-mode=false`nserver-ip=127.0.0.1`nserver\`n  -ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='escaped-key'; text="online-mode=false`nserver-ip=127.0.0.1`nserver\-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='unicode-key'; text="online-mode=false`nserver-ip=127.0.0.1`nserver\u002dip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='continued-value'; text="online-mode=fa\`r`n `tlse`r`nserver-ip=127.0.\`r`n 0.1"; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='escaped-value'; text='online-mode=f\u0061lse' + "`n" + 'server-ip=127\.0.0.\u0031'; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='comment-in-value'; text="online-mode=false`nserver-ip=127.0.0.1`nmotd=hello\`n #world\`n !hello"; mode='false'; ip='127.0.0.1'; accepted=$true; motd='hello#world!hello'},
    @{id='empty-continuation-comment'; text="online-mode=false`nserver-ip=127.0.0.1`n\`n# note \`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false},
    @{id='blank-continuation-line'; text="online-mode=false`nserver-ip=127.0.0.1`nmotd=hello\`n `t`nserver-ip=0.0.0.0"; mode='false'; ip='0.0.0.0'; accepted=$false; motd='hello'},
    @{id='form-feed'; text=([string][char]12) + "online-mode`t:false`nserver-ip  =  127.0.0.1"; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='non-java-whitespace'; text="online-mode=false`nserver-ip=127.0.0.1`n" + ([string][char]0xA0) + '# property \' + "`nserver-ip=0.0.0.0"; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='uppercase-escape'; text="online-mode=false`nserver-ip=127.0.0.1`nmotd=\T\N\R\F\U0041"; mode='false'; ip='127.0.0.1'; accepted=$true; motd='TNRFU0041'},
    @{id='lowercase-escape'; text="online-mode=false`nserver-ip=127.0.0.1`nmotd=\t\n\r\f"; mode='false'; ip='127.0.0.1'; accepted=$true; motd="`t`n`r" + [char]12},
    @{id='trailing-escape'; text="online-mode=false`nserver-ip=127.0.0.1\"; mode='false'; ip='127.0.0.1'; accepted=$true},
    @{id='trailing-space'; text="online-mode=false`nserver-ip=127.0.0.1 "; mode='false'; ip='127.0.0.1 '; accepted=$false}
)
$failures = [Collections.Generic.List[string]]::new()
$results = [Collections.Generic.List[object]]::new()
try {
    New-Item -ItemType Directory -Force -Path $FixtureRoot | Out-Null
    $project = Join-Path $FixtureRoot 'launcher'
    $java = Join-Path $project 'runtime/toolchains/temurin-25/jdk-25.0.3+9/bin/java.exe'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $java) | Out-Null
    [IO.File]::WriteAllText($java, 'inert prerequisite; never execute', $utf8)
    $secret = Join-Path $project 'runtime/bridge-secret.txt'
    # All accepted preflights must stop here, before the native Java invocation.
    [IO.File]::WriteAllText($secret, 'x', $utf8)
    foreach ($serverName in @('server', 'server-offline-smoke')) {
        $server = Join-Path $project ('runtime/' + $serverName)
        New-Item -ItemType Directory -Force -Path $server | Out-Null
        [IO.File]::WriteAllText((Join-Path $server 'fabric-server-launch.jar'), 'inert prerequisite', $utf8)
    }
    foreach ($case in $cases) {
        $path = Join-Path $FixtureRoot ($case.id + '.properties')
        [IO.File]::WriteAllText($path, $case.text, $utf8)
        $online = $case.ContainsKey('online') -and $case.online
        $accepted = $true
        try {
            if ($online) { Assert-ArenaServerMode $path 'true' }
            else { Assert-ArenaOfflineServerLoopback $path -RequireOffline }
        } catch { $accepted = $false }
        if ($accepted -ne $case.accepted) { $failures.Add($case.id + ': unexpected guard acceptance ' + $accepted) }
        $server = Join-Path $project $(if ($online) { 'runtime/server' } else { 'runtime/server-offline-smoke' })
        Copy-Item -LiteralPath $path -Destination (Join-Path $server 'server.properties') -Force
        $launcherError = ''
        try { & (Join-Path $PSScriptRoot 'start-test-server.ps1') -ProjectRoot $project -SecretPath $secret -OfflineSmoke:(-not $online) 3>$null 6>$null | Out-Null }
        catch { $launcherError = $_.Exception.Message }
        $passedPreflight = $launcherError -eq "Bridge secret must contain at least 32 characters: $secret"
        if ($passedPreflight -ne $case.accepted) { $failures.Add($case.id + ': unexpected launcher preflight: ' + $launcherError) }
        if (-not $passedPreflight -and $launcherError -notmatch 'server.properties|Offline Minecraft') { $failures.Add($case.id + ': unexpected launcher boundary') }
        if ($case.ContainsKey('motd')) {
            $actual = @(Get-ArenaServerPropertyValues $path 'motd')
            if ($actual.Count -ne 1 -or $actual[0] -cne $case.motd) { $failures.Add($case.id + ': incorrect continued/escaped value') }
        }
        $results.Add([ordered]@{id=$case.id; guardAccepted=$accepted; launcherPassedPreflight=$passedPreflight; expectedMode=$case.mode; expectedIp=$case.ip; launcherError=$launcherError})
    }
    foreach ($invalid in @('\u00 1', '\u000', '\u00xz')) {
        $path = Join-Path $FixtureRoot 'malformed.properties'
        [IO.File]::WriteAllText($path, "online-mode=false`nserver-ip=127.0.0.1`nmotd=$invalid", $utf8)
        $rejected = $false
        try { Assert-ArenaOfflineServerLoopback $path -RequireOffline } catch { $rejected = $true }
        if (-not $rejected) { $failures.Add('Malformed Unicode was accepted: ' + $invalid) }
    }
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $FixtureRoot 'results.json') -Encoding utf8
    if ($failures.Count) { throw ($failures -join "`n") }
    Write-Output "PASS: $($cases.Count) real guard/launcher cases and 3 malformed escape cases; no server invoked"
} finally {
    if (-not $retainFixtures -and (Test-Path -LiteralPath $FixtureRoot)) {
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $FixtureRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $FixtureRoot) -notlike 'arena-properties-*') { throw 'Refusing cleanup outside owned temporary fixture' }
        Remove-Item -LiteralPath $FixtureRoot -Recurse -Force
    }
}
