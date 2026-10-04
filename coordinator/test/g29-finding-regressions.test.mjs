import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmdirSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repository = fileURLToPath(new URL('../../', import.meta.url));

test('source preparation feeds the authenticated launcher and preserves isolated smoke guards', {
  skip: process.platform !== 'win32' && 'Windows PowerShell launcher contract',
}, () => {
  const fixture = mkdtempSync(path.join(process.env.G29_FIXTURE_PARENT ?? tmpdir(), 'g29-startup-'));
  // Execute only the real properties AST statements: no installer, world copy,
  // profile mutation or Java execution is needed to test this integration boundary.
  const script = String.raw`
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = $env:G29_REPOSITORY
$fixture = $env:G29_FIXTURE
$utf8NoBom = [Text.UTF8Encoding]::new($false)
. (Join-Path $repo 'scripts/offline-server-policy.ps1')
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts/prepare-runtime.ps1'), [ref] $tokens, [ref] $parseErrors)
if ($parseErrors.Count) { throw 'Preparation failed to parse' }
$statements = @($ast.EndBlock.Statements)
$first = -1
$last = -1
for ($i = 0; $i -lt $statements.Count; $i++) {
    if ($statements[$i] -is [Management.Automation.Language.AssignmentStatementAst]) {
        if ($statements[$i].Left.Extent.Text -ceq '$propertiesPath') { $first = $i }
        if ($statements[$i].Left.Extent.Text -ceq '$buildEvidence') { $last = $i - 1 }
    }
}
if ($first -lt 0 -or $last -le $first) { throw 'Properties block boundaries missing' }
$prepare = [scriptblock]::Create(($statements[$first..$last] | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine)
$launcher = Join-Path $repo 'scripts/start-test-server.ps1'
$Server = Join-Path $fixture 'runtime/server'
$java = Join-Path $fixture 'runtime/toolchains/temurin-25/jdk-25.0.3+9/bin/java.exe'
New-Item -ItemType Directory -Path $Server, (Split-Path -Parent $java) -Force | Out-Null
[IO.File]::WriteAllText($java, 'inert; must never execute', $utf8NoBom)
[IO.File]::WriteAllText((Join-Path $Server 'fabric-server-launch.jar'), 'inert', $utf8NoBom)
$sentinel = Join-Path $fixture 'invalid-secret.txt'
[IO.File]::WriteAllText($sentinel, 'x', $utf8NoBom)
$propertiesPath = Join-Path $Server 'server.properties'

function Expect-Rejection([string] $Name, [scriptblock] $Action, [string] $Pattern) {
    $message = $null
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message -or $message -notmatch $Pattern) { throw "$Name expected $Pattern; got $message" }
    Write-Output "PASS $Name"
}
function Write-Properties([string] $Text) { [IO.File]::WriteAllText($propertiesPath, $Text, $utf8NoBom) }

& $prepare
$prepared = [IO.File]::ReadAllText($propertiesPath)
# The invalid secret proves the complete launcher passed prerequisites and mode
# guards, while guaranteeing it stops before spawning the inert Java fixture.
Expect-Rejection 'fresh preparation passes default launcher mode gate' { & $launcher -ProjectRoot $fixture -SecretPath $sentinel } 'Bridge secret must contain at least 32 characters'
Assert-ArenaServerMode $propertiesPath 'true'
& $prepare
if ([IO.File]::ReadAllText($propertiesPath) -cne $prepared) { throw 'Fresh reuse changed properties' }
Write-Output 'PASS authenticated preparation reuse is stable'

$offline = $prepared.Replace('online-mode=true', 'online-mode=false')
Write-Properties $offline
Expect-Rejection 'legacy offline preparation requires deliberate migration' { & $prepare } 'online-mode=true'
if ([IO.File]::ReadAllText($propertiesPath) -cne $offline) { throw 'Preparation rewrote rejected offline settings' }
Expect-Rejection 'default launcher rejects offline server' { & $launcher -ProjectRoot $fixture -SecretPath $sentinel } 'online-mode=true'

foreach ($case in @(
    @{name='duplicate mode'; text=$prepared + 'online\-mode=false' + [Environment]::NewLine; pattern='online-mode'},
    @{name='public listener'; text=$prepared.Replace('server-ip=127.0.0.1', 'server-ip=0.0.0.0'); pattern='server-ip'},
    @{name='escaped duplicate listener'; text=$prepared + 'server\-ip=0.0.0.0' + [Environment]::NewLine; pattern='server-ip'}
)) {
    Write-Properties $case.text
    Expect-Rejection $case.name { & $prepare } $case.pattern
    if ([IO.File]::ReadAllText($propertiesPath) -cne $case.text) { throw 'Rejected reuse changed properties' }
}

Write-Properties ($prepared.Replace('pause-when-empty-seconds=-1', 'pause-when-empty-seconds=60') + 'motd=keep my world' + [Environment]::NewLine)
& $prepare
$reused = [IO.File]::ReadAllText($propertiesPath)
if ($reused -notmatch 'pause-when-empty-seconds=-1' -or $reused -notmatch 'motd=keep my world') { throw 'Reuse lost existing setting or pause policy' }
Expect-Rejection 'reused preparation passes default launcher mode gate' { & $launcher -ProjectRoot $fixture -SecretPath $sentinel } 'Bridge secret must contain at least 32 characters'

Expect-Rejection 'offline smoke uses a separate directory' { & $launcher -ProjectRoot $fixture -OfflineSmoke -SecretPath $sentinel } 'Missing server prerequisite:.*server-offline-smoke'
$smoke = Join-Path $fixture 'runtime/server-offline-smoke'
New-Item -ItemType Directory -Path $smoke | Out-Null
[IO.File]::WriteAllText((Join-Path $smoke 'fabric-server-launch.jar'), 'inert', $utf8NoBom)
$smokeProperties = Join-Path $smoke 'server.properties'
[IO.File]::WriteAllText($smokeProperties, $offline, $utf8NoBom)
Expect-Rejection 'explicit isolated offline smoke passes guards' { & $launcher -ProjectRoot $fixture -OfflineSmoke -SecretPath $sentinel -WarningAction SilentlyContinue } 'Bridge secret must contain at least 32 characters'
[IO.File]::WriteAllText($smokeProperties, $offline.Replace('server-ip=127.0.0.1', 'server-ip=0.0.0.0'), $utf8NoBom)
Expect-Rejection 'offline public listener remains rejected' { & $launcher -ProjectRoot $fixture -OfflineSmoke -SecretPath $sentinel } 'server-ip=127\.0\.0\.1'
if (Test-Path -LiteralPath (Join-Path $fixture 'runtime/bridge-secret.txt')) { throw 'Fixture generated a bridge secret' }
`;
  try {
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {
      cwd: repository,
      env: { ...process.env, G29_REPOSITORY: repository, G29_FIXTURE: fixture },
      encoding: 'utf8',
    });
    assert.ifError(result.error);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.equal((result.stdout.match(/^PASS /gm) ?? []).length, 11, result.stdout);
  } finally {
    // Delete only exact files and empty directories inside this test's unique root.
    function clean(directory) {
      assert.ok(directory === fixture || directory.startsWith(`${fixture}${path.sep}`));
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) clean(target);
        else unlinkSync(target);
      }
      rmdirSync(directory);
    }
    clean(fixture);
  }
});
