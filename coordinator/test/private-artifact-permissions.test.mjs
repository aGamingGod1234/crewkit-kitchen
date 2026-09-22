import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import test from 'node:test';

import { applyWindowsOwnerOnlyDacl, isOwnerOnlyWindowsAcl, preparePrivateArtifact } from '../src/private-artifact-permissions.mjs';

const fixtureDirectory = path.dirname(path.resolve('private.jsonl'));

test('creates private directories and files with POSIX owner-only modes', async () => {
	const calls = [];
	await preparePrivateArtifact('private.jsonl', {
		mkdir: async (target, options) => { calls.push(['mkdir', target, options]); },
		lstat: async (target) => ({ mode: target === fixtureDirectory ? 0o700 : 0o600, isSymbolicLink: () => false, isDirectory: () => target === fixtureDirectory, isFile: () => target.endsWith('private.jsonl') }),
		open: async (_target, flag, mode) => ({ close: async () => { calls.push(['open', flag, mode]); } }),
		chmod: async (_target, mode) => { calls.push(['chmod', mode]); },
		platform: 'linux',
	});
	assert.equal(calls.find(([operation]) => operation === 'mkdir')[2].mode, 0o700);
	assert.deepEqual(calls.filter(([operation]) => operation === 'chmod').map(([, mode]) => mode), [0o700, 0o600]);
	assert.deepEqual(calls.find(([operation]) => operation === 'open'), ['open', 'a', 0o600]);
});

test('uses the current-user Windows ACL operation for directories and files', async () => {
	const secured = [];
	await preparePrivateArtifact('private.jsonl', {
		mkdir: async () => {},
		lstat: async (target) => ({ isSymbolicLink: () => false, isDirectory: () => target === fixtureDirectory, isFile: () => target.endsWith('private.jsonl') }),
		open: async () => ({ close: async () => {} }),
		platform: 'win32',
		secureWindowsPath: async (target, directory) => { secured.push({ target, directory }); },
	});
	assert.deepEqual(secured.map(({ directory }) => directory), [true, false]);
});

test('accepts only a protected ACL with one explicit current-user full-control ACE', async () => {
	const sid = 'S-1-5-21-100-200-300-400';
	const ownerOnly = { protected: true, entries: [{ sid, type: 'Allow', rights: 0x1f01ff, inherited: false, inheritance: 3 }] };
	assert.equal(isOwnerOnlyWindowsAcl(ownerOnly, sid, true), true);
	assert.equal(isOwnerOnlyWindowsAcl({
		protected: true,
		entries: [...ownerOnly.entries, { sid: 'S-1-1-0', type: 'Allow', rights: 0x1f01ff, inherited: false, inheritance: 3 }],
	}, sid, true), false, 'an explicit Everyone ACE must never be accepted');

	let script = '';
	await applyWindowsOwnerOnlyDacl('C:\\runtime\\private', true, {
		currentWindowsSid: async () => sid,
		runWindowsAcl: async ({ script: repairScript }) => {
			script = repairScript;
			return JSON.stringify(ownerOnly);
		},
	});
	assert.match(script, /SetSecurityDescriptorSddlForm/);
	assert.match(script, /D:P\(A;\$inheritance;FA;;;\$Sid\)/);
	assert.match(script, /GetAccessRules/);
});

test('fails closed when read-back Windows ACL retains an explicit broad grant', async () => {
	const sid = 'S-1-5-21-100-200-300-400';
	const broadGrant = {
		protected: true,
		entries: [
			{ sid, type: 'Allow', rights: 0x1f01ff, inherited: false, inheritance: 0 },
			{ sid: 'S-1-5-32-545', type: 'Allow', rights: 0x1f01ff, inherited: false, inheritance: 0 },
		],
	};
	await assert.rejects(applyWindowsOwnerOnlyDacl('C:\\runtime\\private.jsonl', false, {
		currentWindowsSid: async () => sid,
		runWindowsAcl: async () => JSON.stringify(broadGrant),
	}), /Unable to enforce private artifact permissions/);
});

test('real Windows private artifact setup accepts spaces, brackets, and apostrophes', { skip: process.platform !== 'win32' }, async t => {
 const root = await mkdtemp(path.join(os.tmpdir(), 'arena-private-acl-'));
 t.after(() => rm(root, { recursive: true, force: true }));
 const file = path.join(root, "agent's [trace]", 'events.jsonl');
 try { await preparePrivateArtifact(file); }
 catch (error) { assert.fail(`${error.message}: ${error.cause?.stderr ?? error.cause?.message ?? 'no underlying diagnostic'}`); }
 await writeFile(file, 'private test record');
 await preparePrivateArtifact(file);
 assert.equal(await readFile(file, 'utf8'), 'private test record');
});

test('ACL verification works when the parent shell exposes an incompatible Security module', { skip: process.platform !== 'win32' }, async t => {
 const root = await mkdtemp(path.join(os.tmpdir(), 'arena-acl-module-'));
 t.after(() => rm(root, { recursive: true, force: true }));
 const module = path.join(root, 'Microsoft.PowerShell.Security');
 await mkdir(module);
 await writeFile(path.join(module, 'Microsoft.PowerShell.Security.psd1'), "@{ ModuleVersion='99.0.0'; RootModule='broken.psm1'; FunctionsToExport=@('Get-Acl'); CmdletsToExport=@('Get-Acl') }");
 await writeFile(path.join(module, 'broken.psm1'), "throw 'Incompatible parent shell module fixture'");
 const previous = process.env.PSModulePath;
 process.env.PSModulePath = `${root};${previous ?? ''}`;
 try { await preparePrivateArtifact(path.join(root, 'private', 'events.jsonl')); }
 finally { if (previous === undefined) delete process.env.PSModulePath; else process.env.PSModulePath = previous; }
});
