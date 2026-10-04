import { createHash, randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, readdir, rename, rm, unlink, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';

const AGENTS_TEMPLATE = 'AGENTS.md';
const SKILL_TEMPLATE = path.join('.codex', 'skills', 'minecraft-control', 'SKILL.md');
const CONTROL_REFERENCE_TEMPLATE = path.join('.codex', 'skills', 'minecraft-control', 'references', 'control-reference.md');
const permissionsConfig = (workspaceRoot) => `default_permissions = "minecraft"

[permissions.minecraft.filesystem]
":root" = "deny"
":minimal" = "read"
${JSON.stringify(workspaceRoot)} = "read"

[permissions.minecraft.network]
enabled = false
`;

export class MinecraftAgentWorkspace {
	#refresh = Promise.resolve();
	#preparationSignal = null;
	#codexHomeSanitized = false;
	#lastSyncedSourceAuthHash = undefined;

	constructor({ root, templateRoot }, dependencies = {}) {
		if (typeof root !== 'string' || root.trim().length === 0) {
			throw new TypeError('minecraft agent workspace root must be a nonblank path');
		}
		if (typeof templateRoot !== 'string' || templateRoot.trim().length === 0) {
			throw new TypeError('minecraft agent workspace template root must be a nonblank path');
		}

		this.root = path.resolve(root);
		this.templateRoot = path.resolve(templateRoot);
		this.codexHome = path.join(this.root, '.codex-home');
		this.sourceCodexHome = typeof dependencies.sourceCodexHome === 'string' && dependencies.sourceCodexHome.trim() !== ''
			? path.resolve(dependencies.sourceCodexHome)
			: typeof process.env.CODEX_HOME === 'string' && process.env.CODEX_HOME.trim() !== ''
				? path.resolve(process.env.CODEX_HOME)
				: path.join(homedir(), '.codex');
		this.fs = {
			mkdir,
			readFile,
			readdir,
			writeFile,
			rename,
			rm,
			unlink,
			chmod,
			...(dependencies.fs ?? {}),
		};
	}

	prepare({ sourceCodexHome, signal } = {}) {
		if (sourceCodexHome !== undefined && (typeof sourceCodexHome !== 'string' || sourceCodexHome.trim() === '')) {
			throw new TypeError('sourceCodexHome must be a nonblank path when provided');
		}
		const refresh = this.#refresh.then(async () => {
			this.#preparationSignal = signal;
			try {
				signal?.throwIfAborted();
				return await this.#prepare(sourceCodexHome);
			} finally { this.#preparationSignal = null; }
		});
		this.#refresh = refresh.catch(() => {});
		return refresh;
	}

	async #operation(method, ...args) {
		const signal = this.#preparationSignal;
		signal?.throwIfAborted();
		const pending = this.fs[method](...args);
		// Reads and recursive creation of these fixed directories can finish late
		// without overwriting a successor. Keep file mutations serialized until
		// the OS operation settles; aborting a Promise does not cancel a rename.
		const result = signal && ['readFile', 'readdir', 'mkdir'].includes(method)
			? await abandonablePreparationRead(pending, signal)
			: await pending;
		signal?.throwIfAborted();
		return result;
	}

	async #prepare(sourceCodexHome) {
		const workspaceRoot = path.join(this.root, 'workspace');
		const skillRoot = path.join(workspaceRoot, '.codex', 'skills', 'minecraft-control');
		await this.#operation('mkdir', skillRoot, { recursive: true });
		await this.#operation('mkdir', this.codexHome, { recursive: true });
		await this.#operation('chmod', this.codexHome, 0o700);
		if (!this.#codexHomeSanitized) {
			await this.#wipeNonAuthFiles();
			const savedHash = (await this.#readOptionalAuth(path.join(this.root, '.auth-source.sha256')))?.trim();
			this.#lastSyncedSourceAuthHash = savedHash === 'missing' ? null : /^[a-f0-9]{64}$/.test(savedHash ?? '') ? savedHash : undefined;
			this.#codexHomeSanitized = true;
		}
		await this.#syncAuth(sourceCodexHome);
		await this.#replaceContent(permissionsConfig(workspaceRoot), path.join(this.codexHome, 'config.toml'), 0o600);
		const instructions = await this.#readTemplate(AGENTS_TEMPLATE);
		const skillInstructions = await this.#readTemplate(SKILL_TEMPLATE);
		await this.#replace(AGENTS_TEMPLATE, path.join(workspaceRoot, AGENTS_TEMPLATE), instructions);
		await this.#replace(SKILL_TEMPLATE, path.join(skillRoot, 'SKILL.md'), skillInstructions);
		// References remain readable within the same dedicated capability root,
		// without joining the always-loaded instructions or widening permissions.
		const controlReference = await this.#readOptionalTemplate(CONTROL_REFERENCE_TEMPLATE);
		if (controlReference !== null) {
			await this.#operation('mkdir', path.join(skillRoot, 'references'), { recursive: true });
			await this.#replace(CONTROL_REFERENCE_TEMPLATE, path.join(skillRoot, 'references', 'control-reference.md'), controlReference);
		}
		return {
			cwd: workspaceRoot,
			codexHome: this.codexHome,
			permissionProfile: 'minecraft',
			instructions,
			skillInstructions,
			selectedCapabilityRoots: [{
				id: 'minecraft-control',
				location: { type: 'environment', environmentId: 'local', path: skillRoot },
			}],
		};
	}

	async #wipeNonAuthFiles() {
		for (const entry of await this.#operation('readdir', this.codexHome, { withFileTypes: true })) {
			if (entry.name !== 'auth.json') await this.#operation('rm', path.join(this.codexHome, entry.name), { recursive: true, force: true });
		}
	}

	async #readOptionalAuth(authPath) {
		try {
			return await this.#operation('readFile', authPath, 'utf8');
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
			return null;
		}
	}

	async #syncAuth(sourceCodexHome = this.sourceCodexHome) {
		const destination = path.join(this.codexHome, 'auth.json');
		const source = path.resolve(sourceCodexHome);
		if (source === path.resolve(this.codexHome)) {
			this.#lastSyncedSourceAuthHash = authHash(await this.#readOptionalAuth(destination));
			return;
		}
		const sourceContent = await this.#readOptionalAuth(path.join(source, 'auth.json'));
		const isolatedContent = await this.#readOptionalAuth(destination);
		const sourceHash = authHash(sourceContent);
		const isolatedHash = authHash(isolatedContent);
		if (isolatedContent == null) {
			if (sourceContent != null) {
				await this.#replaceContent(sourceContent, destination, 0o600);
				await this.#rememberSourceAuthHash(sourceHash);
			}
			return;
		}
		if (sourceContent == null) {
			if (this.#lastSyncedSourceAuthHash !== undefined && isolatedHash === this.#lastSyncedSourceAuthHash) await this.#operation('rm', destination, { force: true });
			await this.#rememberSourceAuthHash(null);
			return;
		}
		// Both homes may refresh the same login independently. Prefer the more recent
		// refresh even when the source file itself has not changed since the last sync.
		if (hasNewerSourceAuth(sourceContent, isolatedContent)) {
			await this.#replaceContent(sourceContent, destination, 0o600);
			await this.#rememberSourceAuthHash(sourceHash);
			return;
		}
		// With no baseline, keep independently refreshed auth unless the source is
		// demonstrably newer for the same account.
		if (this.#lastSyncedSourceAuthHash === undefined) return this.#rememberSourceAuthHash(sourceHash);
		if (sourceHash === this.#lastSyncedSourceAuthHash) return;
		if (isolatedHash === this.#lastSyncedSourceAuthHash) {
			await this.#replaceContent(sourceContent, destination, 0o600);
			await this.#rememberSourceAuthHash(sourceHash);
		}
	}

	async #rememberSourceAuthHash(hash) {
		await this.#replaceContent(`${hash ?? 'missing'}\n`, path.join(this.root, '.auth-source.sha256'), 0o600);
		this.#lastSyncedSourceAuthHash = hash;
	}

	async #readTemplate(template) {
		return this.#operation('readFile', path.join(this.templateRoot, template), 'utf8');
	}

	async #readOptionalTemplate(template) {
		try {
			return await this.#readTemplate(template);
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
			return null;
		}
	}

	async #replace(template, destination, content = undefined) {
		content ??= await this.#readTemplate(template);
		return this.#replaceContent(content, destination, 0o644);
	}

	async #replaceContent(content, destination, mode) {
		try {
			if (await this.#operation('readFile', destination, 'utf8') === content) return;
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
		}
		const temporary = path.join(
			path.dirname(destination),
			`.${path.basename(destination)}.${process.pid}.${randomUUID()}.tmp`,
		);
		let replaced = false;
		try {
			await this.#operation('writeFile', temporary, content, 'utf8');
			await this.#operation('chmod', temporary, mode);
			try {
				await this.#operation('rename', temporary, destination);
			} catch (error) {
				if (!['EEXIST', 'EPERM'].includes(error?.code)) throw error;
				await this.#operation('rm', destination, { force: true });
				await this.#operation('rename', temporary, destination);
			}
			replaced = true;
		} finally {
			if (!replaced) await this.fs.unlink(temporary).catch(() => {});
		}
	}
}

function abandonablePreparationRead(pending, signal) {
	let abort;
	return new Promise((resolve, reject) => {
		abort = () => reject(signal.reason);
		signal.addEventListener('abort', abort, { once: true });
		// Observe eventual rejection even if the operating-system read outlives us.
		Promise.resolve(pending).then(resolve, reject);
		if (signal.aborted) abort();
	}).finally(() => signal.removeEventListener('abort', abort));
}

function authHash(content) {
	return content === null ? null : createHash('sha256').update(content).digest('hex');
}

function hasNewerSourceAuth(sourceContent, isolatedContent) {
	try {
		const source = JSON.parse(sourceContent);
		const isolated = JSON.parse(isolatedContent);
		const sourceAccount = source?.tokens?.account_id;
		const isolatedAccount = isolated?.tokens?.account_id;
		if (typeof sourceAccount !== 'string' || !sourceAccount || sourceAccount !== isolatedAccount
			|| source?.auth_mode !== isolated?.auth_mode) return false;
		const sourceRefresh = Date.parse(source.last_refresh);
		const isolatedRefresh = Date.parse(isolated.last_refresh);
		return Number.isFinite(sourceRefresh) && Number.isFinite(isolatedRefresh) && sourceRefresh > isolatedRefresh;
	} catch {
		return false;
	}
}
