import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('Java property comment and escape semantics preserve launcher policy', {
  skip: process.platform !== 'win32' && 'PowerShell launcher policy is Windows-specific',
}, () => {
  const script = fileURLToPath(new URL('../../scripts/test-offline-server-policy.ps1', import.meta.url));
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', script], {
    encoding: 'utf8',
    windowsHide: true,
  });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /PASS: 22 real guard\/launcher cases/);
});
