import { randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, rename, rm, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';

const AGENTS_TEMPLATE = 'AGENTS.md';
const SKILL_TEMPLATE = path.join('.codex', 'skills', 'minecraft-control', 'SKILL.md');

export class MinecraftAgentWorkspace {
	#refresh = Promise.resolve();

	constructor({ root, templateRoot }, dependencies = {}) {
		if (typeof root !== 'string' || root.trim().length === 0) {
			throw new TypeError('minecraft agent workspace root must be a nonblank path');
		}
		if (typeof templateRoot !== 'string' || templateRoot.trim().length === 0) {
			throw new TypeError('minecraft agent workspace template root must be a nonblank path');
		}

		this.root = path.resolve(root);
		this.templateRoot = path.resolve(templateRoot);
		this.fs = {
			mkdir,
			readFile,
			writeFile,
			rename,
			rm,
			unlink,
			chmod,
			...(dependencies.fs ?? {}),
		};
	}

	prepare() {
		const refresh = this.#refresh.then(() => this.#prepare());
		this.#refresh = refresh.catch(() => {});
		return refresh;
	}

	async #prepare() {
		const skillRoot = path.join(this.root, '.codex', 'skills', 'minecraft-control');
		await this.fs.mkdir(skillRoot, { recursive: true });
		await this.#replace(AGENTS_TEMPLATE, path.join(this.root, AGENTS_TEMPLATE));
		await this.#replace(SKILL_TEMPLATE, path.join(skillRoot, 'SKILL.md'));
		return {
			cwd: this.root,
			selectedCapabilityRoots: [{
				id: 'minecraft-control',
				location: { type: 'environment', environmentId: 'local', path: skillRoot },
			}],
		};
	}

	async #replace(template, destination) {
		const content = await this.fs.readFile(path.join(this.templateRoot, template), 'utf8');
		const temporary = path.join(
			path.dirname(destination),
			`.${path.basename(destination)}.${process.pid}.${randomUUID()}.tmp`,
		);
		let replaced = false;
		try {
			await this.fs.writeFile(temporary, content, 'utf8');
			await this.fs.chmod(temporary, 0o644);
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
