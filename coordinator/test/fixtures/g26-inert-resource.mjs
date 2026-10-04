import { spawn } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [role, directory, sourceRoot] = process.argv.slice(2);
// Bare test discovery loads helpers without a resource-fixture role.
if (role !== undefined) await runResource();

async function runResource() {
const timeout = setTimeout(() => process.exit(91), 25_000);
async function waitForSample() {
  const deadline = Date.now() + 15_000;
  for (;;) {
    try { await readFile(path.join(directory, 'sample-observed.json')); return; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (Date.now() >= deadline) throw new Error('Fixture sampler did not observe owned child allocation');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
if (role === 'child') {
  const allocation = Buffer.alloc(96 * 1024 * 1024, 0x5a);
  process.stdout.write(JSON.stringify({ pid: process.pid, parentPid: process.ppid,
    rssBytes: process.memoryUsage().rss, allocatedBytes: allocation.byteLength }) + '\n');
  process.stdin.once('data', () => { clearTimeout(timeout); process.exit(allocation[0] === 0x5a ? 0 : 92); });
} else {
  let child;
  try {
    const { runnerPhaseChannel } = await import(pathToFileURL(path.join(sourceRoot, 'coordinator/src/benchmark/paired-runner-channel.mjs')));
    const channel = role === 'single' ? null : runnerPhaseChannel(directory);
    if (channel) await channel.ready();
    child = spawn(process.execPath, [process.argv[1], 'child', directory, sourceRoot], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'inherit'], cwd: sourceRoot,
    });
    const childExit = once(child, 'exit');
    let received = '';
    for await (const chunk of child.stdout) {
      received += chunk.toString();
      if (received.includes('\n')) break;
    }
    const memory = JSON.parse(received.trim());
    const active = { ...memory, runnerPid: process.pid, role,
      observedAt: new Date().toISOString(), phase: 'trial', childExitedBeforeTrialEnd: false };
    await writeFile(path.join(directory, 'active-resource.json'), JSON.stringify(active));
    if (role === 'paired-transient') {
      await waitForSample();
      child.stdin.end('exit\n');
      const [exit] = await childExit;
      if (exit !== 0) throw new Error(`Fixture child exit ${exit}`);
      active.childExitedBeforeTrialEnd = true;
      await writeFile(path.join(directory, 'active-resource.json'), JSON.stringify(active));
    }
    if (channel) await channel.cleanup({ scenarioId: 'fixture', status: 'PASSED' });
    else await writeFile(path.join(directory, 'runner-ready.json'), '{}');
    if (role !== 'paired-transient') {
      await waitForSample();
      child.stdin.end('exit\n');
      const [exit] = await childExit;
      if (exit !== 0) throw new Error(`Fixture child exit ${exit}`);
    }
    await writeFile(path.join(directory, 'report.json'), JSON.stringify({ scenarioId: 'fixture', status: 'PASSED' }));
    clearTimeout(timeout);
  } catch (error) {
    if (child && child.exitCode === null) child.kill();
    console.error(error.stack);
    clearTimeout(timeout);
    process.exitCode = 1;
  }
}
}
