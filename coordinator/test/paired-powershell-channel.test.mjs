import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { performance } from 'node:perf_hooks';
import { phaseWorker } from '../src/benchmark/paired-cli.mjs';
import { pairedSystemNow, runnerPhaseChannel } from '../src/benchmark/paired-runner-channel.mjs';
import { windowsPowerShellEnv } from '../src/benchmark/windows-powershell-env.mjs';
import { root } from './fixtures/paired-cli-fixture.mjs';

// A PowerShell cold start and the reserved cleanup window are harness allowances: the 300 ms trial cutoff and the
// relay arithmetic are what these tests exercise, and a loaded runner can take several seconds to start a shell.
const STARTUP_ALLOWANCE_MS = 60_000;
const CLEANUP_ALLOWANCE_MS = 30_000;

test('Windows PowerShell child environment removes only its incompatible module path', { skip: process.platform !== 'win32', timeout: 120_000 }, async t => {
	const directory = await mkdtemp(path.join(tmpdir(), 'paired-shell-env-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const module = path.join(directory, 'Microsoft.PowerShell.Utility'); await mkdir(module);
	await writeFile(path.join(module, 'Microsoft.PowerShell.Utility.psd1'), "@{ ModuleVersion='99.0.0'; RootModule='incompatible.psm1'; FunctionsToExport=@('Get-FileHash'); CmdletsToExport=@() }");
	await writeFile(path.join(module, 'incompatible.psm1'), "throw 'incompatible parent-shell fixture'");
	const inherited = { ...windowsPowerShellEnv(), PSModulePath: directory, PAIRED_ENV_SENTINEL: 'kept' };
	assert.equal(windowsPowerShellEnv('pwsh.exe', inherited).PSModulePath, directory);
	assert.equal(windowsPowerShellEnv('C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe', { pSmOdUlEpAtH: directory }).pSmOdUlEpAtH, undefined);
	const body = "$ErrorActionPreference='Stop'; $hash=(Get-FileHash -LiteralPath '.gitignore' -Algorithm SHA256).Hash; [Console]::Out.WriteLine('PAIR_EVENT '+(@{kind='startup';value=@{hash=$hash;sentinel=$env:PAIRED_ENV_SENTINEL}}|ConvertTo-Json -Compress)); [Console]::Out.Flush(); [Console]::In.ReadLine()|Out-Null";
	const args = ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(body, 'utf16le').toString('base64')];
	const broken = spawnSync('powershell.exe', args, { cwd: root, env: inherited, windowsHide: true, encoding: 'utf8', timeout: STARTUP_ALLOWANCE_MS });
	assert.notEqual(broken.status, 0); assert.match(broken.stderr, /CouldNotAutoloadMatchingModule/);
	const worker = phaseWorker('powershell.exe', args, { cwd: root, env: inherited });
	try {
		const result = await worker.phase('startup', { now: () => performance.now(), deadlineMs: performance.now() + STARTUP_ALLOWANCE_MS });
		assert.match(result.hash, /^[A-F0-9]{64}$/); assert.equal(result.sentinel, 'kept');
	} finally { await worker.terminate(); }
	assert.equal(inherited.PSModulePath, directory, 'parent environment remains intact');
});

test('actual PowerShell relay carries the original QPC cutoff through delayed trial and cleanup', { skip: process.platform !== 'win32', timeout: 120_000 }, async t => {
	const directory = await mkdtemp(path.join(tmpdir(), 'paired-ps-cutoff-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const quote = value => "'" + value.replaceAll("'", "''") + "'";
	const script = `$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile(${quote(path.join(root, 'scripts/paired-phase-worker.ps1'))},[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Worker parse failure'}
foreach($name in @('Get-PairedSystemNow','Get-PairedRemaining','Receive-PairedPhase','Write-PairedRunnerRequest','Send-PairedEvent')) {
 $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
 Invoke-Expression $definition.Extent.Text
}
$script:PairedState=@{phase='startup';cutoffMs=0;channel=${quote(directory)}}
Receive-PairedPhase 'startup'; Send-PairedEvent 'startup' @{systemNow=(Get-PairedSystemNow)}
Receive-PairedPhase 'trial'; Start-Sleep -Milliseconds 450
Write-PairedRunnerRequest 'runner-trial'; Send-PairedEvent 'trial' @{remaining=(Get-PairedRemaining)}
Receive-PairedPhase 'cleanup'; Start-Sleep -Milliseconds 150
Write-PairedRunnerRequest 'runner-cleanup'; Send-PairedEvent 'cleanup' @{remaining=(Get-PairedRemaining)}
`;
	const worker = phaseWorker('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { cwd: root });
	try {
		const before = pairedSystemNow();
		const start = await worker.phase('startup', { now: () => performance.now(), deadlineMs: performance.now() + STARTUP_ALLOWANCE_MS });
		assert.ok(start.systemNow >= before && start.systemNow <= pairedSystemNow(), 'Node hrtime and PowerShell Stopwatch share QPC origin');
		const channel = runnerPhaseChannel(directory), trialRequest = channel.ready();
		const deadlineMs = performance.now() + 300, cleanupDeadlineMs = deadlineMs + CLEANUP_ALLOWANCE_MS;
		assert.equal((await worker.phase('trial', { now: () => performance.now(), deadlineMs, cleanupDeadlineMs })).deadlineReached, true);
		assert.ok(await trialRequest < performance.now(), 'relay delay consumes trial allowance');
		const cleanupRequest = channel.cleanup({ classification: 'TIMEOUT' });
		await worker.phase('cleanup', { now: () => performance.now(), deadlineMs: cleanupDeadlineMs });
		const receivedDeadline = await cleanupRequest;
		assert.ok(receivedDeadline <= cleanupDeadlineMs + 1, 'relay cannot renew cleanup');
		assert.ok(receivedDeadline > performance.now(), 'usable reserved cleanup remains');
	} finally { await worker.terminate(); }
});
