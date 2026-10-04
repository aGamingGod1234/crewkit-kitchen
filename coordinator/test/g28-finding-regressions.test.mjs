import { windowsPowerShellEnv } from '../src/benchmark/windows-powershell-env.mjs';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('packaging checks installed metadata, artifact parity and retained map attribution', {
	skip: process.platform !== 'win32' && 'Windows packaging verifier',
}, () => {
	const project = fileURLToPath(new URL('../../', import.meta.url));
	const result = spawnSync('powershell.exe', ['-NoProfile', '-File', 'scripts/test-coordinator-packaging.ps1'], {
		cwd: project,
		env: windowsPowerShellEnv(),
    encoding: 'utf8',
		windowsHide: true,
	});
	assert.ifError(result.error);
	assert.equal(result.status, 0, result.stdout + result.stderr);
	assert.match(result.stdout, /PASS: 14 offline packaging cases/);
});
