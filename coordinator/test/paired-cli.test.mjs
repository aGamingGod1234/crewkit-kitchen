import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { normalizeHeadlessMatrix } from '../src/headless-matrix.mjs';
import { runPairedCli, phaseWorker, verifyArm } from '../src/benchmark/paired-cli.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const launcher = path.join(root, 'scripts/run-headless-provider-matrix.ps1');
const hash = value => createHash('sha256').update(value).digest('hex');
const profile = { provider: 'codex', model: 'offline-fixture', reasoningEffort: 'high', serviceTier: 'priority' };
const quotePS = value => `'${value.replaceAll("'", "''")}'`;
async function json(file) { return JSON.parse(await readFile(file, 'utf8')); }
async function fixture(t, mode = 'pass') {
	const directory = await mkdtemp(path.join(tmpdir(), 'arena-paired-tiny-'));
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
const channel=args.has('--paired-channel') ? runnerPhaseChannel(args.get('--paired-channel')) : null;
if(channel) {
 const sourceRoot=fileURLToPath(new URL('../../',import.meta.url));
 const artifact=(await readdir(sourceRoot)).find(name=>name.endsWith('.jar'));
 for (const binding of ['coordinator/src/dynamic-main.mjs','coordinator/config/dynamic-agents.json','source-manifest.json',artifact].map(file=>path.join(sourceRoot,file)).concat(args.get('--config'))) {
  let locked=false; try { const handle=await open(binding,'r+'); await handle.close(); } catch { locked=true; }
  if(!locked) throw new Error('arm binding was mutable');
 }
 await channel.ready();
}
if (scenario.task === 'block_trial') { while(true) {} }
if(channel) await channel.cleanup({classification:'PENDING_EVIDENCE'});
const classification=({error:'ERROR',wrong_profile:'PROFILE_MISMATCH',wrong_seed:'ERROR',failure:'FAILED_USER_OBJECTIVE',timeout:'TIMEOUT',cleanup_failure:'CLEANUP_FAILURE'})[scenario.task] ?? 'PASSED';
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
	await writeFile(matrixPath, JSON.stringify({ version: 1, scenarios: [{ id: 'natural-fixture', ...profile, task: mode, timeoutMs: 3000, scenarioTimeoutMs: 3000, world: { mode: 'natural', seed: '-9223372036854775808' }, requireFactualSuccess: true, assert: [{ type: 'rcon', command: 'data get entity {agent} Inventory', match: 'oak_log' }] }] }));
	const config = { runtimeBudgetMs: 150000, startupMs: 10000, cleanupMs: 15000, outputDirectory: path.join(directory, 'result'), matrixPath, serverTemplate, arms, scenarios: [{ id: 'natural-fixture', seed: '-9223372036854775808', trialMs: 3000 }] };
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

function exec(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { cwd: root, windowsHide: true });
		let stdout = '', stderr = '';
		child.stdout.on('data', value => { stdout += value; }); child.stderr.on('data', value => { stderr += value; });
		child.once('error', reject); child.once('close', code => resolve({ code, stdout, stderr }));
	});
}

test('actual PowerShell PairedConfig route persists all unstarted slots without resolving a game runtime', { skip: process.platform !== 'win32' }, async t => {
	const f = await fixture(t); f.config.runtimeBudgetMs = 1;
	const configPath = path.join(f.directory, 'paired.json'); await writeFile(configPath, JSON.stringify(f.config));
	const result = await exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', launcher, '-ProjectRoot', root, '-PairedConfig', configPath]);
	assert.equal(result.code, 1, result.stderr); assert.match(result.stdout, /Paired: INCOMPLETE/);
	const report = await json(path.join(f.config.outputDirectory, 'report.json'));
	assert.equal(report.counts.scheduled, 4); assert.equal(report.counts.started, 0);
	assert.equal((await json(path.join(f.config.outputDirectory, 'intent.json'))).slots.length, 4);
});

test('real driver and extracted launcher phases run AB/BA against isolated harmless fixtures', { skip: process.platform !== 'win32' }, async t => {
	const f = await fixture(t);
	const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
	assert.equal(report.status, 'COMPLETE', JSON.stringify({ report, journal: await json(path.join(f.config.outputDirectory, 'journal.json')) }));
	assert.equal(report.counts.attempted, 4);
	const journal = await json(path.join(f.config.outputDirectory, 'journal.json'));
	const starts = journal.filter(row => row.kind === 'phase' && row.phase === 'startup');
	assert.equal(new Set(starts.map(row => row.value.worldId)).size, 4);
	assert.deepEqual(starts.map(row => row.value.modSha256), [f.config.arms[0], f.config.arms[1], f.config.arms[1], f.config.arms[0]].map(arm => arm.artifactSha256));
	for (const start of starts) {
		const claim = await json(path.join(start.value.scenarioDirectory, 'world-manifest.json.claimed'));
		assert.equal(claim.worldId, start.value.worldId);
	}
	assert.equal(journal.filter(row => row.kind === 'settled').length, 4);
});

test('source and artifact changes fail binding instead of silently changing an arm', async t => {
	const f = await fixture(t); await verifyArm(f.config.arms[0]);
	await writeFile(path.join(f.config.arms[0].sourceRoot, 'coordinator/src/dynamic-main.mjs'), 'changed');
	await assert.rejects(verifyArm(f.config.arms[0]), /source hash mismatch/);
	await writeFile(f.config.arms[1].artifactPath, 'changed');
	await assert.rejects(verifyArm(f.config.arms[1]), /artifact hash changed/);
});

test('parent kills a blocked child and awaits its exit; forced cleanup remains unknown', async () => {
	const worker = phaseWorker(process.execPath, ['-e', 'while(true) {}'], { cwd: root });
	const start = performance.now();
	await assert.rejects(worker.phase('startup', { now: () => performance.now(), deadlineMs: start + 150 }), /stopped/);
	assert.equal(worker.forced, true);
	assert.throws(() => process.kill(worker.pid, 0));
});

test('cancellation settles the child before returning', async () => {
	const worker = phaseWorker(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { cwd: root });
	const controller = new AbortController();
	setTimeout(() => controller.abort(), 100);
	await assert.rejects(worker.phase('trial', { signal: controller.signal, now: () => performance.now(), deadlineMs: performance.now() + 10000 }), { name: 'AbortError' });
	assert.throws(() => process.kill(worker.pid, 0));
});

test('driver reserve boundary, outcomes, cleanup, cancellation and persistence use one clock', async t => {
	const f = await fixture(t);
	const matrix = await json(f.config.matrixPath); matrix.scenarios[0].timeoutMs = matrix.scenarios[0].scenarioTimeoutMs = 20;
	await writeFile(f.config.matrixPath, JSON.stringify(matrix));
	for (const mode of ['exact', 'short', 'failure', 'timeout', 'error', 'wrong-profile', 'wrong-seed', 'cleanup-failure', 'late', 'cancel-startup', 'cancel-trial', 'cancel-cleanup', 'write-overhead', 'write-failure']) {
		let time = 0, world = 0; const calls = []; const controller = new AbortController();
		const config = { ...f.config, outputDirectory: path.join(f.directory, mode), runtimeBudgetMs: mode === 'short' ? 69 : mode === 'exact' ? 70 : 140, startupMs: 10, cleanupMs: 5, scenarios: [{ ...f.config.scenarios[0], trialMs: 20 }] };
		const report = await runPairedCli(config, { launcher, now: () => time, signal: controller.signal,
			persist: async (file, value) => {
				if (mode === 'write-overhead' && file.endsWith('intent.json')) time += 71;
				if (mode === 'write-failure' && file.endsWith('journal.json')) throw new Error('fixture disk full');
				await writeFile(file, JSON.stringify(value));
			},
			makeWorker: (command, args) => {
				let request, worldId;
				return { pid: 123, terminate: async () => {}, phase: async (phase, context, before) => {
					await before?.();
					request ??= await json(args.at(-1));
					assert.equal(request.scenario.world.seed, '-9223372036854775808');
					assert.equal(request.scenario.timeoutMs, 20); assert.equal(request.scenario.scenarioTimeoutMs, 20);
					assert.deepEqual(request.arm.profile, profile);
					calls.push([phase, request.arm.id]);
					assert.equal(context.deadlineMs - time, phase === 'startup' ? 10 : phase === 'trial' ? 20 : mode === 'late' ? 4 : 5);
					time += phase === 'startup' ? 10 : phase === 'trial' ? 20 : 5;
					if (mode === `cancel-${phase}`) controller.abort();
					if (phase === 'startup') {
						assert.equal((await json(path.join(config.outputDirectory, 'intent.json'))).slots.length, 4);
						worldId = `headless-${++world}`;
						return { worldId, modSha256: request.arm.artifactSha256 };
					}
					if (phase === 'trial') { if (mode === 'late') time++; return { classification: 'PENDING_EVIDENCE' }; }
					const classification = ({ failure: 'FAILED_USER_OBJECTIVE', timeout: 'TIMEOUT', error: 'ERROR' })[mode] ?? 'PASSED';
					return { runnerExit: classification === 'PASSED' ? 0 : 1, wrapper: { ok: mode !== 'cleanup-failure' }, runner: {
						status: classification === 'PASSED' ? 'PASSED' : 'FAILED', classification, profile,
						world: { worldId, seed: mode === 'wrong-seed' ? '2' : request.scenario.world.seed, fresh: true },
						settings: { configuredVerified: true, configured: { ...profile, ...(mode === 'wrong-profile' ? { reasoningEffort: 'low' } : {}) } }, cleanup: { status: 'CLEAN' },
					} };
				} };
			},
		});
		assert.equal(report.counts.scheduled, 4, mode);
		assert.equal(report.counts.attempted, ['short', 'write-overhead', 'write-failure', 'cancel-startup'].includes(mode) ? 0 : mode === 'exact' ? 2 : ['failure', 'timeout'].includes(mode) ? 4 : 1, mode);
		assert.equal(report.status, ['failure', 'timeout'].includes(mode) ? 'COMPLETE' : 'INCOMPLETE', mode);
		if (['error', 'wrong-profile', 'wrong-seed'].includes(mode)) assert.equal(report.pairs[0].trials[0].status, 'ERROR');
		if (mode === 'exact') assert.equal(report.pairs[0].status, 'COMPLETE');
		if (['failure', 'timeout'].includes(mode)) assert.deepEqual(calls.filter(([phase]) => phase === 'trial').map(([, arm]) => arm), ['A', 'B', 'B', 'A']);
	}
});

test('final persistence overrun is recorded as incomplete in the authoritative completion receipt', async t => {
	const f = await fixture(t); let time = 0;
	f.config.runtimeBudgetMs = 1;
	const report = await runPairedCli(f.config, { launcher, now: () => time,
		persist: async (file, value) => { await writeFile(file, JSON.stringify(value)); if (file.endsWith('completion.json')) time += 10; },
	});
	assert.equal(report.status, 'INCOMPLETE');
	const receipt = await json(path.join(f.config.outputDirectory, 'completion.json'));
	assert.equal(receipt.status, 'INCOMPLETE'); assert.ok(receipt.overrunMs > 0);
});

test('real launcher rejects passed reports with the wrong profile or seed before the peer', { skip: process.platform !== 'win32' }, async t => {
	for (const mode of ['passed_wrong_profile', 'passed_wrong_seed']) {
		const f = await fixture(t, mode);
		const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
		assert.equal(report.status, 'INCOMPLETE', mode);
		assert.equal(report.counts.started, 1, mode);
		assert.equal(report.counts.attempted, 1, mode);
		assert.equal(report.pairs[0].trials[0].status, 'ERROR', mode);
		assert.equal(report.pairs[0].trials[1].status, 'NOT_STARTED', mode);
	}
});

test('invalid requested matrix binding persists intent and blocks worker acquisition', async t => {
	const f = await fixture(t);
	const original = await json(f.config.matrixPath);
	for (const field of ['seed', 'reasoningEffort', 'scenarioTimeoutMs']) {
		const matrix = structuredClone(original);
		if (field === 'seed') matrix.scenarios[0].world.seed = '2';
		else matrix.scenarios[0][field] = field === 'reasoningEffort' ? 'low' : 2999;
		await writeFile(f.config.matrixPath, JSON.stringify(matrix));
		const config = { ...f.config, outputDirectory: path.join(f.directory, `invalid-${field}`) };
		let acquisitions = 0;
		const report = await runPairedCli(config, { launcher, makeWorker: () => { acquisitions++; throw new Error('unexpected acquisition'); } });
		assert.equal(acquisitions, 0);
		assert.equal(report.validation, 'INVALID_SCENARIO_BINDING');
		assert.equal(report.status, 'INCOMPLETE');
		assert.equal(report.counts.attempted, 0);
		assert.equal(report.counts.scheduled, 4);
		assert.equal((await json(path.join(config.outputDirectory, 'intent.json'))).slots.length, 4);
	}
});

// Complete all four measurements first: an initially incomplete reserve test
// cannot detect a stale COMPLETE report left behind by late final persistence.
test('completed measurements become incomplete on report or receipt write overrun', async t => {
	const f = await fixture(t);
	for (const target of ['report.json', 'completion.json']) {
		let time = 0;
		const config = { ...f.config, outputDirectory: path.join(f.directory, `late-${target}`) };
		const report = await runPairedCli(config, { launcher, now: () => time,
			persist: async (file, value) => {
				await writeFile(file, JSON.stringify(value));
				if (path.basename(file) === target) time += config.runtimeBudgetMs + 1;
			},
			makeWorker: (command, args) => ({ pid: 0, terminate: async () => {}, phase: async (phase, context, before) => {
				await before?.(); const request = await json(args.at(-1));
				if (phase === 'startup') return { worldId: 'headless-fixture', modSha256: request.arm.artifactSha256 };
				if (phase === 'trial') return {};
				return { runnerExit: 0, wrapper: { ok: true }, runner: {
					status: 'PASSED', classification: 'PASSED', profile, cleanup: { status: 'CLEAN' },
					world: { worldId: 'headless-fixture', seed: context.pair.seed, fresh: true },
					settings: { configuredVerified: true, configured: profile },
				} };
			} }),
		});
		assert.equal(report.counts.attempted, 4);
		assert.ok(report.pairs.every(pair => pair.status === 'COMPLETE'));
		assert.equal(report.status, 'INCOMPLETE');
		assert.equal((await json(path.join(config.outputDirectory, 'report.json'))).status, 'INCOMPLETE');
		assert.equal((await json(path.join(config.outputDirectory, 'completion.json'))).status, 'INCOMPLETE');
		assert.ok(report.authorization.elapsedMs > config.runtimeBudgetMs);
	}
});

function successfulWorker(onPhase = async () => {}) {
	return (_command, args) => ({ pid: 0, terminate: async () => {}, phase: async (name, context, before) => {
		await before?.(); const request = await json(args.at(-1));
		await onPhase(name, request);
		if (name === 'startup') return { worldId: 'headless-clock-fixture', modSha256: request.arm.artifactSha256 };
		if (name === 'trial') return {};
		return { runnerExit: 0, wrapper: { ok: true }, runner: {
			status: 'PASSED', classification: 'PASSED', profile, cleanup: { status: 'CLEAN' },
			world: { worldId: 'headless-clock-fixture', seed: context.pair.seed, fresh: true },
			settings: { configuredVerified: true, configured: profile },
		} };
	} });
}

test('terminal persistence observation controls completion and retains lifecycle clock validation', async t => {
	const f = await fixture(t);
	for (const mode of ['normal', 'overrun', 'nan', 'regression']) {
		let receiptCommitted = false, finalReads = 0, phases = 0;
		const config = { ...f.config, runtimeBudgetMs: 150000, outputDirectory: path.join(f.directory, `clock-${mode}`) };
		const run = runPairedCli(config, { launcher, startedAtMs: 0,
			now: () => {
				if (!receiptCommitted || mode === 'normal') return 100;
				if (mode === 'overrun') return ++finalReads === 1 ? 150000 : 150001;
				return mode === 'nan' ? NaN : 99;
			},
			persist: async (file, value) => { await writeFile(file, JSON.stringify(value)); if (file.endsWith('completion.json')) receiptCommitted = true; },
			makeWorker: successfulWorker(async () => { phases++; }),
		});
		if (['nan', 'regression'].includes(mode)) await assert.rejects(run, /timestamp|backwards/);
		else {
			const report = await run;
			const expected = mode === 'normal' ? 'COMPLETE' : 'INCOMPLETE';
			assert.equal(report.status, expected);
			assert.equal((await json(path.join(config.outputDirectory, 'report.json'))).status, expected);
			assert.equal((await json(path.join(config.outputDirectory, 'completion.json'))).status, expected);
			assert.equal(report.authorization.overrunMs, mode === 'normal' ? 0 : 1);
		}
		assert.equal(phases, 12, 'final persistence cannot start more lifecycle work');
	}
});

test('CLI clock invalidity after acquisition still stops owned resources without starting a peer', async t => {
	const f = await fixture(t);
	for (const failurePhase of ['startup', 'trial']) {
		let time = 0, terminations = 0; const phases = [];
		const config = { ...f.config, outputDirectory: path.join(f.directory, `invalid-${failurePhase}`) };
		const base = successfulWorker(async name => { phases.push(name); if (name === failurePhase) time = NaN; });
		await assert.rejects(runPairedCli(config, { launcher, now: () => time, makeWorker: (...args) => {
			const worker = base(...args), phase = worker.phase;
			worker.terminate = async () => { terminations++; };
			worker.phase = async (name, context, before) => {
				if (name === 'cleanup') assert.equal(context.stopOnly, true);
				return phase(name, context, before);
			};
			return worker;
		} }), /timestamp/);
		assert.deepEqual(phases, failurePhase === 'startup' ? ['startup', 'cleanup'] : ['startup', 'trial', 'cleanup']);
		assert.equal(terminations, 1);
		assert.equal((await json(path.join(config.outputDirectory, 'intent.json'))).slots.length, 4);
	}
});

test('matrix byte mutation at startup or cleanup blocks comparison and preserves prepared slots', async t => {
	const f = await fixture(t);
	for (const mutationPhase of ['startup', 'cleanup']) {
		let starts = 0, measurements = 0; const matrixHashes = [];
		const config = { ...f.config, outputDirectory: path.join(f.directory, `matrix-${mutationPhase}`) };
		const report = await runPairedCli(config, { launcher, now: () => 0,
			makeWorker: successfulWorker(async (name, request) => {
				if (name === 'startup') starts++;
				if (name === 'trial') measurements++;
				matrixHashes.push(request.matrixSha256);
				if (name === mutationPhase) {
					const matrix = await json(request.matrixPath); matrix.scenarios[0].task = 'substituted task';
					await writeFile(request.matrixPath, JSON.stringify(matrix));
				}
			}),
		});
		assert.equal(report.status, 'INCOMPLETE'); assert.equal(starts, 1);
		for (const digest of matrixHashes) assert.match(digest, /^[a-f0-9]{64}$/);
		assert.equal(measurements, mutationPhase === 'startup' ? 0 : 1);
		assert.equal(report.validation, 'INVALID_SCENARIO_BINDING');
		assert.equal(report.pairs.flatMap(pair => pair.trials).filter(trial => trial.status === 'NOT_STARTED').length, 3);
		assert.equal((await json(path.join(config.outputDirectory, 'intent.json'))).slots.length, 4);
	}
});

test('final pair boundary rejects explicit effective model contradictions for measured gameplay outcomes', async t => {
	const f = await fixture(t);
	for (const classification of ['PASSED', 'DEAD', 'TIMEOUT']) for (const evidence of ['provider_reported', 'submitted', null]) {
		let starts = 0;
		const config = { ...f.config, outputDirectory: path.join(f.directory, `effective-${classification}-${evidence}`) };
		const base = successfulWorker(async name => { if (name === 'startup') starts++; });
		const report = await runPairedCli(config, { launcher, now: () => 0, makeWorker: (...args) => {
			const worker = base(...args), phase = worker.phase;
			worker.phase = async (...values) => {
				const result = await phase(...values);
				if (values[0] === 'cleanup') {
					result.runner.classification = classification;
					result.runner.status = classification === 'PASSED' ? 'PASSED' : 'FAILED';
					result.runnerExit = classification === 'PASSED' ? 0 : 1;
					result.runner.settings.effective = { model: 'different-effective-model' };
					result.runner.settings.evidence = { model: evidence };
				}
				return result;
			};
			return worker;
		} });
		assert.equal(report.status, evidence === 'provider_reported' ? 'INCOMPLETE' : 'COMPLETE');
		assert.equal(starts, evidence === 'provider_reported' ? 1 : 4);
		assert.equal(report.providerVerified, false);
	}
});

test('job containment kills orphaned descendants and does not trust a false clean wrapper', { skip: process.platform !== 'win32' }, async t => {
	const f = await fixture(t);
	const rogue = path.join(f.directory, 'orphan.mjs'); await writeFile(rogue, 'setInterval(()=>{},1000);');
	await writeFile(f.fakeLauncher, `param([string] $ProjectRoot,[switch] $FunctionsOnly)
. ${quotePS(launcher)} -ProjectRoot $ProjectRoot -FunctionsOnly
function Resolve-Java($Project) { return ${quotePS(process.execPath)} }
function Resolve-Node { return ${quotePS(process.execPath)} }
function Test-ProviderPreflight($Provider) { return @{ Available=$true } }
function Invoke-Scenario($Scenario,$Project,$RunDirectory,$Template,$MatrixFile,$Java,$Node,$BuiltJar) {
 $info=[Diagnostics.ProcessStartInfo]::new(); $info.FileName=$Node; $info.Arguments='"'+${quotePS(rogue)}+'"'; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
 $orphan=[Diagnostics.Process]::Start($info)
 Send-PairedEvent 'resource' @{ ProcessId=$orphan.Id; fixture='orphan' }
 Send-PairedEvent 'startup' @{ worldId='headless-orphan'; modSha256=(Get-FileSha256 $BuiltJar) }
 Receive-PairedPhase 'trial'; Send-PairedEvent 'trial' @{ classification='PENDING_EVIDENCE' }
 Receive-PairedPhase 'cleanup'
 return @{ runner=@{status='PASSED';classification='PASSED';cleanup=@{status='CLEAN'}};runnerExit=0;wrapper=@{ok=$true} }
}
`);
	f.config.cleanupMs = 500;
	const report = await runPairedCli(f.config, { launcher: f.fakeLauncher });
	assert.equal(report.status, 'INCOMPLETE'); assert.equal(report.counts.started, 1);
	assert.notEqual(report.pairs[0].trials[0].cleanup, 'CLEAN');
	const journal = await json(path.join(f.config.outputDirectory, 'journal.json'));
	const orphan = journal.find(row => row.identity?.fixture === 'orphan'); assert.ok(orphan);
	assert.throws(() => process.kill(orphan.identity.ProcessId, 0));
	assert.equal(journal.at(-1).cleanup, 'UNKNOWN');
});

test('extracted phase code preserves the existing single-scenario launcher path', { skip: process.platform !== 'win32' }, async t => {
 const f = await fixture(t); const arm = f.config.arms[0];
 const normalized = normalizeHeadlessMatrix(await json(f.config.matrixPath)).scenarios[0];
 const scenarioFile = path.join(f.directory, 'normalized.json'); await writeFile(scenarioFile, JSON.stringify(normalized));
 const script = path.join(f.directory, 'single.ps1'); const legacyDirectory = path.join(f.directory, 'legacy'); await mkdir(legacyDirectory);
 const legacyReport = path.join(f.directory, 'legacy-report.json');
 await writeFile(script, `. ${quotePS(f.fakeLauncher)} -ProjectRoot ${quotePS(arm.sourceRoot)} -FunctionsOnly
 $scenario=Get-Content -Raw -LiteralPath ${quotePS(scenarioFile)} | ConvertFrom-Json
 $report=Invoke-Scenario $scenario ${quotePS(arm.sourceRoot)} ${quotePS(legacyDirectory)} ${quotePS(f.config.serverTemplate)} ${quotePS(f.config.matrixPath)} ${quotePS(process.execPath)} ${quotePS(process.execPath)} ${quotePS(arm.artifactPath)}
 $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath ${quotePS(legacyReport)} -Encoding UTF8
 `);
 const result = await exec('powershell.exe', ['-NoProfile','-NonInteractive','-File',script]);
 assert.equal(result.code, 0, result.stderr);
 const report = JSON.parse((await readFile(legacyReport,'utf8')).replace(/^\uFEFF/,''));
 assert.equal(report.status,'PASSED'); assert.equal(report.cleanup.status,'CLEAN'); assert.equal(report.cleanup.runner.status,'CLEAN');
});
