import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';

const VOICE_PROFILES = Object.freeze([
	profile('moss', 'c5f56a6cc2ec4fa8920cb4c5889a3fb7', ['measured', 'clear', 'calm'], ['bright', 'breathy'], 0.94),
	profile('flint', 'd8a1340984ee4b63ad1ffae27a6a4339', ['crisp', 'confident', 'energetic'], ['soft', 'slow'], 1.03),
	profile('ember', '933563129e564b19a115bedd57b7406a', ['gentle', 'engaged', 'sincere'], ['booming', 'fast'], 0.98),
	profile('wren', '59e9dc1cb20c452584788a2690c80970', ['friendly', 'bright', 'expressive'], ['deep', 'flat'], 1.05),
	profile('cedar', '536d3a5e000945adb7038665781a4aca', ['curious', 'clear', 'authoritative'], ['breathy', 'playful'], 0.97),
	profile('sable', 'bf322df2096a46f18c579d0baa36f41d', ['deep', 'steady', 'deliberate'], ['bright', 'fast'], 0.91),
	profile('quill', 'b545c585f631496c914815291da4e893', ['bright', 'professional', 'enthusiastic'], ['raspy', 'slow'], 1.02),
	profile('rook', 'ef9c79b62ef34530bf452c0e50e3c260', ['low', 'mysterious', 'calm'], ['cheerful', 'high'], 0.92),
	profile('juniper', '9259a7392c454a1eb6436141abb5a558', ['friendly', 'confident', 'conversational'], ['slow', 'dramatic'], 1.0),
	profile('vale', '1936333080804be19655c6749b2ae7b2', ['deep', 'smooth', 'serious'], ['bright', 'fast'], 0.9),
	profile('kestrel', '4c6a6762e4ac4bdebdb4fa8525d054a2', ['dynamic', 'authoritative', 'dramatic'], ['quiet', 'breathy'], 1.0),
	profile('sol', 'f8dfe9c83081432386f143e2fe9767ef', ['raspy', 'wise', 'measured'], ['bright', 'young'], 0.88),
	profile('reed', '85d6a6c915f545b399b0bfb358244fb9', ['lively', 'friendly', 'playful'], ['deep', 'flat'], 1.04),
	profile('nova', 'a3b3f0a9c49340bd8fa722d83c81cb08', ['high', 'relaxed', 'friendly'], ['booming', 'formal'], 1.01),
	profile('ash', 'f48d143a59a946ab87c0130fd081f349', ['youthful', 'light', 'direct'], ['old', 'gravelly'], 1.0),
	profile('piper', '802e3bc2b27e49c2995d23ef70e6ac89', ['energetic', 'clear', 'enthusiastic'], ['slow', 'soft'], 1.06),
]);

export class VoiceProfileStore {
	#assignments = new Map();
	#used = new Set();
	#onChange;

	constructor(assignments = {}, onChange = () => {}) {
		if (assignments === null || typeof assignments !== 'object' || Array.isArray(assignments)) {
			throw new TypeError('voice profile assignments must be an object');
		}
		if (typeof onChange !== 'function') throw new TypeError('onChange must be a function');
		this.#onChange = onChange;
		for (const [agentId, profileId] of Object.entries(assignments)) {
			const profileIndex = VOICE_PROFILES.findIndex((entry) => entry.profileId === profileId);
			if (!isUuid(agentId) || profileIndex < 0 || this.#used.has(profileIndex)) continue;
			this.#assignments.set(agentId, profileIndex);
			this.#used.add(profileIndex);
		}
	}

	resolve(agentId) {
		if (!isUuid(agentId)) throw new TypeError('agentId must be a UUID');
		let index = this.#assignments.get(agentId);
		if (index === undefined) {
			const start = stableHash(agentId) % VOICE_PROFILES.length;
			for (let offset = 0; offset < VOICE_PROFILES.length; offset++) {
				const candidate = (start + offset) % VOICE_PROFILES.length;
				if (this.#used.has(candidate)) continue;
				index = candidate;
				break;
			}
			index ??= start;
			this.#assignments.set(agentId, index);
			this.#used.add(index);
			this.#onChange(this.snapshotAssignments());
		}
		return VOICE_PROFILES[index];
	}

	snapshotAssignments() {
		return Object.freeze(Object.fromEntries(
			[...this.#assignments].map(([agentId, index]) => [agentId, VOICE_PROFILES[index].profileId]),
		));
	}
}

export async function loadPersistentVoiceProfileStore(filePath) {
	if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('filePath must not be blank');
	let assignments = {};
	try {
		const document = JSON.parse(await readFile(filePath, 'utf8'));
		if (document?.schemaVersion === 1 && document.assignments !== null
				&& typeof document.assignments === 'object' && !Array.isArray(document.assignments)) {
			assignments = document.assignments;
		}
	} catch (error) {
		if (error?.code !== 'ENOENT') throw error;
	}
	let pending = Promise.resolve();
	const store = new VoiceProfileStore(assignments, (snapshot) => {
		pending = pending.then(async () => {
			await mkdir(path.dirname(filePath), { recursive: true });
			const temporary = `${filePath}.tmp`;
			await writeFile(temporary, `${JSON.stringify({ schemaVersion: 1, assignments: snapshot }, null, 2)}\n`, 'utf8');
			await rename(temporary, filePath);
		});
	});
	return Object.freeze({ store, flush: () => pending });
}

export function builtInVoiceProfiles() {
	return VOICE_PROFILES;
}

function profile(name, voiceId, identityAnchors, avoid, speed) {
	return Object.freeze({
		schemaVersion: 1,
		profileId: `voice.${name}.v1`,
		provider: 'fish',
		model: 's2.1-pro-free',
		voiceId,
		locale: 'en-US',
		identityAnchors: Object.freeze(identityAnchors),
		avoid: Object.freeze(avoid),
		speed,
		revision: 1,
		provenance: 'fish-public-provider-catalog',
		consent: 'provider-public-listing',
	});
}

function stableHash(value) {
	let hash = 0x811c9dc5;
	for (const byte of Buffer.from(value, 'utf8')) {
		hash ^= byte;
		hash = Math.imul(hash, 0x01000193) >>> 0;
	}
	return hash;
}

function isUuid(value) {
	return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
