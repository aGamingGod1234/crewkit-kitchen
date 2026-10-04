import assert from 'node:assert/strict';
import test from 'node:test';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { phaseWorker } from '../src/benchmark/paired-cli.mjs';

for (const mode of ['ack-after-exit', 'unterminated-ack', 'missing-ack', 'failed-exit', 'inherited-open-pipe', 'spawn-error']) {
  test(`paired worker drains final acknowledgement after process exit: ${mode}`, async () => {
    const originalSpawn = childProcess.spawn;
    const startupError = Object.assign(new Error('controlled spawn failure'), { code: 'ENOENT' });
    let worker;
    childProcess.spawn = () => {
      const child = new EventEmitter();
      child.stdin = new PassThrough();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      const finishOutput = () => {
        const ack = 'PAIR_EVENT ' + JSON.stringify({ kind: 'cleanup', value: { confirmed: true } });
        child.stdout.end(mode === 'missing-ack' ? '' : ack + (mode === 'unterminated-ack' ? '' : '\n'));
        child.stderr.end();
        child.emit('close', mode === 'failed-exit' ? 1 : 0);
      };
      if (mode === 'spawn-error') queueMicrotask(() => child.emit('error', startupError));
      else child.stdin.once('data', () => queueMicrotask(() => {
        child.emit('exit', mode === 'failed-exit' ? 1 : 0);
        // Node's exit event does not mean stdio has drained. Force that order
        // without relying on OS scheduling or spawning an uncontained child.
        if (mode !== 'inherited-open-pipe') setImmediate(finishOutput);
      }));
      return child;
    };
    syncBuiltinESMExports();
    try {
      worker = phaseWorker('node', []);
      const phase = worker.phase('cleanup', { deadlineMs: performance.now() + (mode === 'inherited-open-pipe' ? 50 : 5000), now: () => performance.now() });
      if (mode === 'spawn-error') await assert.rejects(phase, error => error === startupError);
      else if (mode === 'missing-ack') await assert.rejects(phase, /exited before its phase acknowledgement/);
      else if (mode === 'failed-exit') await assert.rejects(phase, /Worker exit failure/);
      else if (mode === 'inherited-open-pipe') {
        await assert.rejects(phase, { code: 'HEADLESS_TIMEOUT' });
        assert.equal(worker.forced, true);
      } else {
        assert.deepEqual(await phase, { confirmed: true });
        assert.equal(worker.forced, false);
      }
    } finally {
      await worker?.terminate();
      childProcess.spawn = originalSpawn;
      syncBuiltinESMExports();
    }
  });
}
