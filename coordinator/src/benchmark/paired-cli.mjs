import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, open, readdir, lstat, rename } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createBudgetClock, preparePairedPilot, runPairedPilot, headlessTrialOutcome } from './paired-pilot.mjs';
import { normalizeHeadlessMatrix } from '../headless-matrix.mjs';
import { summarizeProviderAttestation } from '../headless-world.mjs';
import { pairedSystemNow } from './paired-runner-channel.mjs';
import { windowsPowerShellEnv } from './windows-powershell-env.mjs';

const profileKeys = ['provider', 'model', 'reasoningEffort', 'serviceTier'];

async function durable(file, value) {
	// Keep the last durable journal/report intact if a replacement write fails.
	const temporary = `${file}.tmp`;
	const handle = await open(temporary, 'w', 0o600);
	try { await handle.writeFile(JSON.stringify(value, null, 2) + '\n'); await handle.sync(); }
	finally { await handle.close(); }
	await rename(temporary, file);
}
async function sha256(file) {
	const hash = createHash('sha256');
	for await (const chunk of createReadStream(file)) hash.update(chunk);
	return hash.digest('hex');
}

/** Manifest binds all source/config files, plus package manifests when present.
 * Recheck before acquisition and after teardown; never copy a runtime or source.
 */
export async function verifyArm(arm) {
	for (const key of ['sourceRoot', 'artifactPath', 'sourceManifestPath']) assert.ok(path.isAbsolute(arm[key]), `${key} must be absolute`);
	assert.equal(await sha256(arm.artifactPath), arm.artifactSha256, 'artifact hash changed');
	assert.equal(await sha256(arm.sourceManifestPath), arm.sourceManifestSha256, 'source manifest changed');
	const manifest = JSON.parse(await readFile(arm.sourceManifestPath, 'utf8'));
	assert.equal(manifest.version, 1);
	assert.ok(Array.isArray(manifest.files) && manifest.files.length > 0);
	const expected = new Map();
	for (const entry of manifest.files) {
		assert.match(entry.path, /^(coordinator\/(src|config)\/|coordinator\/package(?:-lock)?\.json$)/);
		assert.ok(!entry.path.includes('..') && !entry.path.includes('\\') && !expected.has(entry.path));
		assert.match(entry.sha256, /^[a-f0-9]{64}$/);
		expected.set(entry.path, entry.sha256);
	}
	const found = [];
	async function walk(relative) {
		const target = path.join(arm.sourceRoot, relative);
		const info = await lstat(target);
		assert.ok(!info.isSymbolicLink(), 'source bindings cannot contain links');
		if (info.isDirectory()) { for (const child of await readdir(target)) await walk(`${relative}/${child}`); }
		else { assert.ok(info.isFile()); found.push(relative); assert.equal(await sha256(target), expected.get(relative), `source hash mismatch: ${relative}`); }
	}
	await walk('coordinator/src'); await walk('coordinator/config');
	for (const name of ['package.json', 'package-lock.json']) {
		try { await lstat(path.join(arm.sourceRoot, 'coordinator', name)); }
		catch (error) { if (error.code === 'ENOENT') continue; throw error; }
		await walk(`coordinator/${name}`);
	}
	assert.deepEqual(found.sort(), [...expected.keys()].sort(), 'manifest coverage differs');
	for (const required of ['dynamic-main.mjs', 'headless-matrix.mjs', 'headless-world-spawn.mjs']) assert.ok(expected.has(`coordinator/src/${required}`), `missing launch binding ${required}`);
	return { artifactVerified: true, sourceVerified: true };
}

/** A live child is never abandoned by a race. Trial expiry hands off to the
 * already reserved cleanup deadline; other expiry terminates the owned tree,
 * waits for the root exit, and records UNKNOWN for descendants after force-stop.
 * taskkill is containment recovery, not proof that every descendant was known.
 */
export function phaseWorker(command, args, { cwd, env = process.env, onResource = () => {}, onEvent = () => {} } = {}) {
	const child = spawn(command, args, { cwd, env: windowsPowerShellEnv(command, env), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
	let exited = false, exitCode = null, outputClosed = false, failed = false, pending = null, killing = null;
	let startupError = null, handoffTimer = null, awaitingTrialAcknowledgement = false;
	const queued = [];
	const exit = new Promise(resolve => {
		child.once('error', error => { startupError = error; exited = true; resolve(); wake(); });
		child.once('exit', code => { exited = true; exitCode = code; resolve(); wake(); });
	});
	child.stdin.on('error', () => {});
	// Do not retain private child stderr in paired public reports.
	child.stderr.resume();
	const lines = createInterface({ input: child.stdout });
	lines.once('close', () => { outputClosed = true; wake(); });
	lines.on('line', line => {
		if (!line.startsWith('PAIR_EVENT ')) return;
		try {
			const event = JSON.parse(line.slice(11));
			if (event.kind === 'resource') onResource(event.value);
			else { queued.push(event); wake(); }
		} catch { failed = true; void terminate(); }
	});
	function wake() { if (pending) { const resume = pending; pending = null; resume(); } }
	async function terminate() {
		clearTimeout(handoffTimer);
		if (killing) return killing;
		failed = true;
		killing = (async () => {
			if (!exited && child.pid) {
				if (process.platform === 'win32') {
					await new Promise(resolve => {
						const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
						killer.once('error', resolve); killer.once('exit', resolve);
					});
				}
				if (!exited) child.kill('SIGKILL');
			}
			await exit;
			// An escaped descendant can keep a pipe open. Its cleanup remains UNKNOWN;
			// do not mistake stream close for confirmation of descendant termination.
			lines.close(); child.stdout.destroy(); child.stderr.destroy(); child.stdin.destroy(); wake();
		})();
		return killing;
	}
	async function phase(name, context, beforeSend = async () => {}) {
		let reason = null, deadlineReached = false, timer;
		const stop = code => { reason ??= code; void terminate(); };
		const remaining = () => Math.max(0, context.deadlineMs - context.now());
		const expire = () => {
			if (deadlineReached) return;
			// Node timers can fire before a fractional deadline. Never cut gameplay short.
			if (remaining() > 0) { timer = setTimeout(expire, remaining()); return; }
			if (name === 'trial' && Number.isFinite(context.cleanupDeadlineMs)) {
				deadlineReached = true;
				// Keep containment armed even if the caller never starts cleanup.
				handoffTimer = setTimeout(() => void terminate(), Math.max(0, context.cleanupDeadlineMs - context.now()));
				wake();
			} else stop('HEADLESS_TIMEOUT');
		};
		clearTimeout(handoffTimer);
		timer = setTimeout(expire, remaining());
		const abort = () => stop('ABORTED');
		context.signal?.addEventListener('abort', abort, { once: true });
		try {
			if (context.signal?.aborted) abort();
			await beforeSend();
			if (context.stopOnly) stop('HEADLESS_TIMEOUT');
			else if (remaining() <= 0) expire();
			if (!reason && !deadlineReached && !failed && !exited) {
				if (name === 'trial') awaitingTrialAcknowledgement = true;
				// Sample the shared clock before remaining(): publication/relay delay
				// spends this fixed cutoff, including the already reserved cleanup.
				const sharedNow = pairedSystemNow();
				child.stdin.write(JSON.stringify({ phase: name, clock: 'system-monotonic-ms', cutoffMs: sharedNow + remaining() }) + '\n');
			}
			for (;;) {
				if (reason || failed) { await terminate(); throw Object.assign(new Error('Paired worker stopped; teardown unknown'), { code: reason ?? 'WORKER_FAILED', name: reason === 'ABORTED' ? 'AbortError' : 'Error' }); }
				if (deadlineReached) return { deadlineReached: true };
				const event = queued.shift();
				if (event) {
					// The runner can acknowledge its stop boundary after the parent cutoff.
					// Drain exactly that outstanding acknowledgement within cleanup's budget.
					const lateTrial = name === 'cleanup' && awaitingTrialAcknowledgement && event.kind === 'trial';
					if (event.kind === 'trial') awaitingTrialAcknowledgement = false;
					await onEvent({ phase: name, ...event });
					if (lateTrial) continue;
					if (event.kind !== name) throw new Error('Worker failed or returned an unexpected phase');
					if (name === 'cleanup') { await exit; if (exitCode !== 0) throw new Error('Worker exit failure'); }
					if (!reason && name === 'trial' && remaining() <= 0) { if (!deadlineReached) expire(); if (deadlineReached) return { deadlineReached: true }; }
					if (reason || remaining() <= 0) { await terminate(); throw Object.assign(new Error('Late phase completion'), { code: 'HEADLESS_TIMEOUT' }); }
					return event.value;
				}
				if (startupError) throw startupError;
				// Process exit can precede the final stdout data/EOF. Keep the current
				// phase deadline armed while draining its acknowledgement; an inherited
				// pipe that never closes still fails closed through the existing timer.
				if (exited && outputClosed) throw new Error('Worker exited before its phase acknowledgement');
				await new Promise(resolve => { pending = resolve; });
			}
		} finally { clearTimeout(timer); context.signal?.removeEventListener('abort', abort); }
	}
	return { pid: child.pid, phase, terminate, get forced() { return failed; } };
}

export async function runPairedCli(config, { launcher, entryElapsedMs = 0, now = () => performance.now(),
	startedAtMs = now(), signal, persist = durable, makeWorker = phaseWorker, checkArm = verifyArm } = {}) {
	// One validation history spans preparation, lifecycle callbacks and persistence.
	now = createBudgetClock(now, startedAtMs);
	assert.ok(Number.isSafeInteger(config.runtimeBudgetMs) && config.runtimeBudgetMs > 0, 'explicit positive runtimeBudgetMs required');
	assert.ok(Number.isFinite(entryElapsedMs) && entryElapsedMs >= 0);
	assert.ok(path.isAbsolute(config.outputDirectory), 'outputDirectory must be absolute');
	assert.ok(path.isAbsolute(launcher), 'launcher must be absolute');
	const deadlineMs = startedAtMs + config.runtimeBudgetMs - entryElapsedMs;
	const prepared = preparePairedPilot({ ...config, deadlineMs: Math.max(0, deadlineMs) });
	await mkdir(config.outputDirectory, { recursive: false });
	const intentPath = path.join(config.outputDirectory, 'intent.json');
	const slots = prepared.pairs.flatMap(pair => pair.order.map(arm => ({ pairId: pair.id, arm, status: 'NOT_STARTED' })));
	await persist(intentPath, { version: 1, prepared, slots, cleanup: 'UNKNOWN_UNTIL_CONFIRMED' });
	const journal = [];
	let writeQueue = Promise.resolve(), writeError = null;
	const record = value => {
		journal.push({ atMs: now(), ...value });
		const snapshot = structuredClone(journal);
		writeQueue = writeQueue.then(() => persist(path.join(config.outputDirectory, 'journal.json'), snapshot)).catch(error => { writeError ??= error; });
		return writeQueue;
	};
	let matrix = null, matrixSha256 = null, validationError = null;
	const boundMatrixPath = path.join(config.outputDirectory, 'matrix.json');
	const checkMatrix = async () => {
		assert.equal(await sha256(boundMatrixPath), matrixSha256, 'validated matrix bytes changed');
	};
	try {
		const rawMatrix = JSON.parse(await readFile(config.matrixPath, 'utf8'));
		matrix = normalizeHeadlessMatrix(rawMatrix);
		await persist(boundMatrixPath, rawMatrix);
		for (const pair of prepared.pairs) {
			const scenario = matrix.scenarios.find(value => value.id === pair.scenarioId);
			assert.ok(scenario && scenario.world.mode === 'natural');
			assert.equal(scenario.world.seed, pair.seed, 'scenario seed differs from paired intent');
			assert.equal(scenario.timeoutMs, pair.trialMs, 'full trial allocation mismatch');
			assert.equal(scenario.scenarioTimeoutMs, pair.trialMs, 'scenario timeout would shorten the trial');
			assert.deepEqual(Object.fromEntries(profileKeys.map(key => [key, scenario[key]])), prepared.arms[0].profile, 'requested profile mismatch');
		}
		const boundBytes = await readFile(boundMatrixPath);
		assert.deepEqual(JSON.parse(boundBytes.toString('utf8')), rawMatrix, 'persisted matrix differs from validated scenario');
		matrixSha256 = createHash('sha256').update(boundBytes).digest('hex');
		await record({ kind: 'matrix-binding', sha256: matrixSha256 });
	} catch (error) { validationError = error; }
	const active = new Set();
	let report;
	try {
		report = await runPairedPilot(prepared, { now, signal,
			async startup(context) {
				const resource = { id: `${context.pair.id}-${context.arm.id}-${randomUUID()}`, worker: null, startup: null };
				context.own(resource); active.add(resource);
				await record({ kind: 'acquire', id: resource.id, pairId: context.pair.id, arm: context.arm.id, cleanup: 'UNKNOWN' });
				if (validationError) throw validationError;
				await checkMatrix();
				await checkArm(context.arm);
				if (now() >= context.deadlineMs || signal?.aborted || writeError) throw new Error('Startup expired or persistence failed before acquisition');
				const runDirectory = path.join(config.outputDirectory, resource.id);
				const channel = path.join(runDirectory, 'channel');
				await mkdir(channel, { recursive: true });
				const scenario = matrix.scenarios.find(value => value.id === context.pair.scenarioId);
				const requestPath = path.join(runDirectory, 'request.json');
				await persist(requestPath, { launcher, arm: context.arm, scenario, runDirectory, channel, matrixPath: boundMatrixPath, matrixSha256, serverTemplate: config.serverTemplate });
				if (now() >= context.deadlineMs || signal?.aborted) throw new Error('Startup expired before worker launch');
				resource.worker = makeWorker('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(path.dirname(fileURLToPath(import.meta.url)), '../../..', 'scripts/paired-job-supervisor.ps1'), '-RequestPath', requestPath], {
					cwd: context.arm.sourceRoot,
					onResource: identity => { void record({ kind: 'resource', id: resource.id, identity }); },
					onEvent: event => record({ ...event, kind: 'phase', eventKind: event.kind, id: resource.id }),
				});
				resource.startup = await resource.worker.phase('startup', context, () => record({ kind: 'worker', id: resource.id, pid: resource.worker.pid }));
				assert.equal(resource.startup.modSha256, context.arm.artifactSha256, 'installed artifact differs');
				// Lock acquisition and hashing consume startup, preserving the full trial
				// allowance. The supervisor holds the matrix lock until this arm exits.
				await checkMatrix();
				return resource;
			},
			async runTrial(context) {
				if (writeError) throw new Error('Resource journal failed');
				const boundary = await context.resource.worker.phase('trial', context);
				if (boundary?.deadlineReached) return { status: 'TIMED_OUT', deadlineReached: true, resourcesClean: false };
				// Provisional only. The real single-scenario evidence is finalized in cleanup.
				return { status: 'PASSED' };
			},
			async cleanup(context) {
				const resource = context.resource;
				if (!resource) return { ok: true };
				try {
					if (!resource.worker) return { ok: true };
					const result = await resource.worker.phase('cleanup', context);
					await checkMatrix();
					await checkArm(context.arm);
					await record({ kind: 'settled', id: resource.id, result });
					const runner = result.runner;
					let identityValid = true;
					if (['PASSED', 'FAILED', 'TIMED_OUT'].includes(headlessTrialOutcome(runner).status)) {
						identityValid = runner?.world?.worldId === resource.startup?.worldId && runner?.world?.seed === context.pair.seed
							&& runner?.world?.fresh === true && runner?.settings?.configuredVerified === true
							&& profileKeys.every(key => runner.profile?.[key] === context.arm.profile[key] && runner.settings?.configured?.[key] === context.arm.profile[key])
							&& summarizeProviderAttestation([{ executionSettings: runner.settings }], context.arm.profile).mismatches.length === 0;
					}
					const validExit = runner?.status === 'FAILED' ? result.runnerExit === 1 : result.runnerExit === 0;
					return { ok: result.wrapper?.ok === true && !writeError && validExit,
						trialOutcome: validExit && identityValid ? headlessTrialOutcome(runner) : { status: 'ERROR', resourcesClean: false } };
				} catch {
					await resource.worker?.terminate();
					await record({ kind: 'settled', id: resource.id, cleanup: 'UNKNOWN' });
					return { ok: false };
				} finally { active.delete(resource); }
			},
		});
	} finally {
		for (const resource of active) await resource.worker?.terminate();
		await writeQueue;
	}
	if (validationError) report.validation = 'INVALID_SCENARIO_BINDING';
	else {
		try { await checkMatrix(); }
		catch { report.validation = 'INVALID_SCENARIO_BINDING'; report.status = 'INCOMPLETE'; }
	}
	report.matrixSha256 = matrixSha256;
	if (writeError) { report.persistence = 'FAILED'; report.status = 'INCOMPLETE'; }
	const accountPersistence = () => {
		const observedAtMs = now();
		if (observedAtMs > deadlineMs) report.status = 'INCOMPLETE';
		report.authorization = { startedAtMs, deadlineMs, observedAtMs, elapsedMs: observedAtMs - startedAtMs + entryElapsedMs,
			overrunMs: Math.max(0, observedAtMs - deadlineMs) };
		return observedAtMs;
	};
	accountPersistence();
	// A report alone is measurement evidence, not a persistence acknowledgement.
	// A missing/failed completion receipt must never authorize a complete run.
	report.persistence ??= 'REQUIRES_COMPLETION_RECEIPT';
	await persist(path.join(config.outputDirectory, 'report.json'), report);
	const receiptPath = path.join(config.outputDirectory, 'completion.json');
	// Fail closed even when a required report/receipt replacement fails. This
	// first durable receipt never certifies the pre-write observation.
	await persist(receiptPath, { version: 2, status: 'UNCONFIRMED', deadlineMs });
	accountPersistence();
	let finalObservedAtMs = accountPersistence();
	if (report.status === 'INCOMPLETE') {
		await persist(path.join(config.outputDirectory, 'report.json'), report);
		finalObservedAtMs = accountPersistence();
	}
	// Explicit terminal accounting boundary: all lifecycle, journal, report and
	// provisional receipt IO (including required corrections) is charged above.
	// The certification commit below only records that observation; its own IO
	// is outside the runtime allowance. No clock read, work, or revocation follows.
	// If this commit fails, the durable receipt remains UNCONFIRMED.
	await persist(receiptPath, { version: 2, status: report.status, deadlineMs,
		finalObservedAtMs, overrunMs: report.authorization.overrunMs,
		accountingBoundary: 'BEFORE_CERTIFICATION_COMMIT', certificationWriteCharged: false });
	return report;
}

async function main() {
	const values = new Map();
	for (let i = 2; i < process.argv.length; i += 2) {
		assert.ok(['--config', '--launcher', '--entry-elapsed-ms'].includes(process.argv[i]) && process.argv[i + 1] !== undefined, 'Invalid paired CLI arguments');
		values.set(process.argv[i], process.argv[i + 1]);
	}
	const config = JSON.parse(await readFile(values.get('--config'), 'utf8'));
	const controller = new AbortController();
	const cancel = () => controller.abort();
	process.on('SIGINT', cancel); process.on('SIGTERM', cancel);
	try {
		const report = await runPairedCli(config, { launcher: values.get('--launcher'), startedAtMs: 0,
			entryElapsedMs: Number(values.get('--entry-elapsed-ms') ?? 0), signal: controller.signal });
		process.stdout.write(`Paired: ${report.status}\nReport: ${path.join(config.outputDirectory, 'report.json')}\n`);
		process.exitCode = report.status === 'COMPLETE' ? 0 : 1;
	} finally { process.off('SIGINT', cancel); process.off('SIGTERM', cancel); }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	main().catch(() => { process.stderr.write('Paired execution failed; inspect intent and phase journal.\n'); process.exitCode = 1; });
}
