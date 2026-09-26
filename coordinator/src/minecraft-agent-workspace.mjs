import { createHash, randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, readdir, rename, rm, unlink, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';

const AGENTS_TEMPLATE = 'AGENTS.md';
const SKILL_TEMPLATE = path.join('.codex', 'skills', 'minecraft-control', 'SKILL.md');
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

	prepare({ sourceCodexHome } = {}) {
		if (sourceCodexHome !== undefined && (typeof sourceCodexHome !== 'string' || sourceCodexHome.trim() === '')) {
			throw new TypeError('sourceCodexHome must be a nonblank path when provided');
		}
		const refresh = this.#refresh.then(() => this.#prepare(sourceCodexHome));
		this.#refresh = refresh.catch(() => {});
		return refresh;
	}

	async #prepare(sourceCodexHome) {
		const workspaceRoot = path.join(this.root, 'workspace');
		const skillRoot = path.join(workspaceRoot, '.codex', 'skills', 'minecraft-control');
		await this.fs.mkdir(skillRoot, { recursive: true });
		await this.fs.mkdir(this.codexHome, { recursive: true });
		await this.fs.chmod(this.codexHome, 0o700);
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
		for (const entry of await this.fs.readdir(this.codexHome, { withFileTypes: true })) {
			if (entry.name !== 'auth.json') await this.fs.rm(path.join(this.codexHome, entry.name), { recursive: true, force: true });
		}
	}

	async #readOptionalAuth(authPath) {
		try {
			return await this.fs.readFile(authPath, 'utf8');
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
		// An older installation has no baseline. Preserve any independently refreshed auth.
		if (this.#lastSyncedSourceAuthHash === undefined) return this.#rememberSourceAuthHash(sourceHash);
		if (sourceContent == null) {
			if (isolatedHash === this.#lastSyncedSourceAuthHash) await this.fs.rm(destination, { force: true });
			await this.#rememberSourceAuthHash(null);
			return;
		}
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
		return this.fs.readFile(path.join(this.templateRoot, template), 'utf8');
	}

	async #replace(template, destination, content = undefined) {
		content ??= await this.#readTemplate(template);
		return this.#replaceContent(content, destination, 0o644);
	}

	async #replaceContent(content, destination, mode) {
		try {
			if (await this.fs.readFile(destination, 'utf8') === content) return;
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
		}
		const temporary = path.join(
			path.dirname(destination),
			`.${path.basename(destination)}.${process.pid}.${randomUUID()}.tmp`,
		);
		let replaced = false;
		try {
			await this.fs.writeFile(temporary, content, 'utf8');
			await this.fs.chmod(temporary, mode);
			try {
				await this.fs.rename(temporary, destination);
			} catch (error) {
				if (!['EEXIST', 'EPERM'].includes(error?.code)) throw error;
				await this.fs.rm(destination, { force: true });
				await this.fs.rename(temporary, destination);
			}
			replaced = true;
		} finally {
			if (!replaced) await this.fs.unlink(temporary).catch(() => {});
		}
	}
}

function authHash(content) {
	return content === null ? null : createHash('sha256').update(content).digest('hex');
}
