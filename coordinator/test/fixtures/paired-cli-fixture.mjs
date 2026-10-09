import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { windowsPowerShellEnv } from '../../src/benchmark/windows-powershell-env.mjs';
import { spawn } from 'node:child_process';
import { normalizeHeadlessMatrix } from '../../src/headless-matrix.mjs';
import { runPairedCli, phaseWorker, verifyArm } from '../../src/benchmark/paired-cli.mjs';

export const root = path.resolve(fileURLToPath(new URL('../../../', import.meta.url)));
export const launcher = path.join(root, 'scripts/run-headless-provider-matrix.ps1');
const hash = value => createHash('sha256').update(value).digest('hex');
export const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const quotePS = value => `'${value.replaceAll("'", "''")}'`;
export async function json(file) { return JSON.parse(await readFile(file, 'utf8')); }
export async function fixture(t, mode = 'pass') {
	const directory = await mkdtemp(path.join(tmpdir(), 'arena-paired-tiny-'));
	// Only full_timeout exercises the trial allowance itself, by running it to the cutoff. The other modes finish at
	// once, so their allowance is just headroom for a loaded runner to relay the trial phase.
	const trialMs = mode === 'full_timeout' ? 3000 : 15000;
	t.after(() => rm(directory, { recursive: true, force: true }));
	const arms = [];
	for (const id of ['A', 'B']) {
		const sourceRoot = path.join(directory, id);
		const files = {
			'coordinator/src/dynamic-main.mjs': `// arm ${id}\nsetInterval(() => {}, 1000);`,
			'coordinator/src/headless-world-spawn.mjs': 'console.log(JSON.stringify({savedSpawn:{source:"level.dat",dimension:"minecraft:overworld",x:0,y:64,z:0},spawnLoading:{operation:"temporary_spawn_chunk_loading",x:0,z:0,ready:true,elapsedMs:0,terrainModified:false,inventoryModified:false}}));',
			'coordinator/config/dynamic-agents.json': '{}',
			'coordinator/src/headless-matrix.mjs': `
import { readFile, writeFile, open, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runnerPhaseChannel } from ${JSON.stringify(pathToFileURL(path.join(root, 'coordinator/src/benchmark/paired-runner-channel.mjs')).href)};
import { claimNaturalWorld } from ${JSON.stringify(pathToFileURL(path.join(root, 'coordinator/src/headless-world.mjs')).href)};
const args = new Map(); for(let i=2;i<process.argv.length;i+=2) args.set(process.argv[i],process.argv[i+1]);
const directory=args.get('--run-directory');
const manifest=JSON.parse(await readFile(args.get('--world-manifest'),'utf8'));
await claimNaturalWorld(args.get('--world-manifest'), manifest.worldId);
const scenario=JSON.parse(await readFile(args.get('--config'),'utf8')).scenarios[0];
let deadline;
const channel=args.has('--paired-channel') ? runnerPhaseChannel(args.get('--paired-channel')) : null;
if(channel) {
 const sourceRoot=fileURLToPath(new URL('../../',import.meta.url));
 const artifact=(await readdir(sourceRoot)).find(name=>name.endsWith('.jar'));
 for (const binding of ['coordinator/src/dynamic-main.mjs','coordinator/config/dynamic-agents.json','source-manifest.json',artifact].map(file=>path.join(sourceRoot,file)).concat(args.get('--config'))) {
  let locked=false; try { const handle=await open(binding,'r+'); await handle.close(); } catch { locked=true; }
  if(!locked) throw new Error('arm binding was mutable');
 }
 deadline = await channel.ready();
}
if (scenario.task === 'full_timeout') { while(performance.now()<deadline) await new Promise(resolve=>setTimeout(resolve,Math.max(1,deadline-performance.now()))); }
if (scenario.task === 'block_trial') { while(true) {} }
if(channel) await channel.cleanup({classification:'PENDING_EVIDENCE'});
const classification=({full_timeout:'TIMEOUT',error:'ERROR',wrong_profile:'PROFILE_MISMATCH',wrong_seed:'ERROR',failure:'FAILED_USER_OBJECTIVE',timeout:'TIMEOUT',cleanup_failure:'CLEANUP_FAILURE'})[scenario.task] ?? 'PASSED';
const report={scenarioId:scenario.id,status:classification==='PASSED'?'PASSED':'FAILED',classification,cleanup:{status:scenario.task==='cleanup_failure'?'FAILED':'CLEAN'},world:{worldId:manifest.worldId,fresh:manifest.fresh,...manifest.world},profile:${JSON.stringify(profile)},settings:{configuredVerified:true,configured:${JSON.stringify(profile)}}};
if(scenario.task==='passed_wrong_profile') report.settings.configured.reasoningEffort='low';
if(scenario.task==='passed_wrong_seed') report.world.seed='2';
await writeFile(path.join(directory,'report.json'),JSON.stringify(report));
process.exitCode=report.status==='FAILED'?1:0;
`,
		};
		for (const [relative, text] of Object.entries(files)) { await mkdir(path.dirname(path.join(sourceRoot, relative)), { recursive: true }); await writeFile(path.join(sourceRoot, relative), text); }
		const artifactPath = path.join(sourceRoot, `fake-${id}.jar`); await writeFile(artifactPath, `not a runtime: ${id}`);
		const sourceManifestPath = path.join(sourceRoot, 'source-manifest.json');
		await writeFile(sourceManifestPath, JSON.stringify({ version: 1, files: Object.entries(files).map(([path, contents]) => ({ path, sha256: hash(contents) })) }));
		arms.push({ id, profile, sourceRoot, artifactPath, sourceManifestPath, artifactSha256: hash(await readFile(artifactPath)), sourceManifestSha256: hash(await readFile(sourceManifestPath)) });
	}
	const serverTemplate = path.join(directory, 'tiny-template'); await mkdir(serverTemplate);
	await writeFile(path.join(serverTemplate, 'fabric-server-launch.jar'), 'harmless fixture, never executed');
	const server = path.join(directory, 'idle.mjs'); await writeFile(server, "process.stdin.on('data', () => process.exit(0)); setInterval(() => {},1000);");
	const matrixPath = path.join(directory, 'matrix.json');
	await writeFile(matrixPath, JSON.stringify({ version: 1, scenarios: [{ id: 'natural-fixture', ...profile, task: mode, timeoutMs: trialMs, scenarioTimeoutMs: trialMs, world: { mode: 'natural', seed: '-9223372036854775808' }, requireFactualSuccess: true, assert: [{ type: 'rcon', command: 'data get entity {agent} Inventory', match: 'oak_log' }] }] }));
	// Reserve setup, equal trial and cleanup for all four Windows fixture arms,
	// plus terminal IO. Setup includes multiple shell/Node launches on CI.
	// startupMs, cleanupMs and the terminal slack are ceilings, not waits: under CPU contention a
	// PowerShell cold start took 33 s against the former 30 s startup limit and a healthy cleanup
	// took 15.2 s against the former 15 s one, each of which left the run INCOMPLETE. The 3 s trial
	// is what the tests exercise; tests that need cleanup to expire set their own cleanupMs.
	const startupMs = 120000, cleanupMs = 60000;
	const config = { runtimeBudgetMs: 4 * (startupMs + trialMs + cleanupMs) + 60000, startupMs, cleanupMs, outputDirectory: path.join(directory, 'result'), matrixPath, serverTemplate, arms, scenarios: [{ id: 'natural-fixture', seed: '-9223372036854775808', trialMs }] };
	const fakeLauncher = path.join(directory, 'fake-launcher.ps1');
	await writeFile(fakeLauncher, `param([string] $ProjectRoot, [switch] $FunctionsOnly)
. ${quotePS(launcher)} -ProjectRoot $ProjectRoot -FunctionsOnly
$script:ActualStart = \${function:Start-RedirectedProcess}
$script:ActualRead = \u0024{function:Read-Text}
$script:FixtureHandles = [System.Collections.Generic.List[object]]::new()
$script:Port = 21000
function Resolve-Java($Project) { return ${quotePS(process.execPath)} }
function Resolve-Node { return ${quotePS(process.execPath)} }
function Test-ProviderPreflight($Provider) { return @{ Available = $true } }
function Protect-LocalFile($Path) {}
function Assert-ArenaOfflineServerLoopback($Path, [switch] $RequireOffline) {}
function New-ScenarioConfig($Source,$Destination,$BridgePort,$WorkspaceRoot) { [IO.File]::WriteAllText($Destination, '{}') }
function Reserve-FreePort($Preferred,$Excluded) { $script:Port++; return $script:Port }
function Test-Port($Port) { return @($script:FixtureHandles | Where-Object { -not $_.Process.HasExited }).Count -gt 0 }
function Read-Text($Path) { if ($Path.EndsWith('latest.log')) { return 'Done (' }; return (& $script:ActualRead $Path) }
function Test-CoordinatorReady($Path,$Scenario) { return $true }
function Get-ProcessSnapshot {
 $result = @{}
 foreach ($handle in $script:FixtureHandles) {
  $handle.Process.Refresh()
  if (-not $handle.Process.HasExited) { $result[[int] $handle.Process.Id] = [pscustomobject]@{ ProcessId = $handle.Process.Id; ParentProcessId = $PID; CreationDate = $handle.Identity.CreationDate; WorkingSetSize = $handle.Process.WorkingSet64 } }
 }
 return $result
}
function Start-RedirectedProcess($FileName,$Arguments,$WorkingDirectory,$StdoutPath,$StderrPath,$Environment) {
 if ($Arguments.StartsWith('-Darenaagents')) { $Arguments = '"' + ${quotePS(server)} + '"' }
 $handle = & $script:ActualStart $FileName $Arguments $WorkingDirectory $StdoutPath $StderrPath $Environment
 $script:FixtureHandles.Add($handle)
 return $handle
}
`);
	return { directory, config, fakeLauncher };
}

export function exec(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { cwd: root, env: windowsPowerShellEnv(command), windowsHide: true });
		let stdout = '', stderr = '';
		child.stdout.on('data', value => { stdout += value; }); child.stderr.on('data', value => { stderr += value; });
		child.once('error', reject); child.once('close', code => resolve({ code, stdout, stderr }));
	});
}
