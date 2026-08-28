import { mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';

const PERSISTENT_FILES = new Map();
const PERSIST_RETRY_BASE_MS = 10;
const PERSIST_RETRY_MAX_MS = 100;
const REPAIR_RETRY_MS = 25;

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

export async function loadPersistentVoiceProfileStore(filePath, dependencies = {}) {
	if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('filePath must not be blank');
	if (dependencies === null || typeof dependencies !== 'object' || Array.isArray(dependencies)) {
		throw new TypeError('voice profile dependencies must be an object');
	}
	const signal = dependencies.signal;
	const closeTimeoutMs = dependencies.closeTimeoutMs ?? 1_000;
	if (!Number.isSafeInteger(closeTimeoutMs) || closeTimeoutMs < 1 || closeTimeoutMs > 120_000) {
		throw new TypeError('closeTimeoutMs must be between 1 and 120000');
	}
	const read = dependencies.readFile ?? readFile;
	const makeDirectory = dependencies.mkdir ?? mkdir;
	const write = dependencies.writeFile ?? writeFile;
	const move = dependencies.rename ?? rename;
	const remove = dependencies.unlink ?? unlink;
	for (const [name, operation] of Object.entries({ readFile: read, mkdir: makeDirectory, writeFile: write, rename: move, unlink: remove })) {
		if (typeof operation !== 'function') throw new TypeError(`${name} must be a function`);
	}
	if (signal?.aborted) throw abortReason(signal);
	const resolvedPath = path.resolve(filePath);
	const coordinator = persistentCoordinator(resolvedPath);
	const ownerGeneration = ++coordinator.latestOwnerGeneration;
	coordinator.version += 1;
	coordinator.latestIo = { makeDirectory, write, move, remove };
	let assignments = {};
	try {
		const document = JSON.parse(await awaitAbortable(
			Promise.resolve().then(() => read(resolvedPath, { encoding: 'utf8', signal })),
			signal,
		));
		if (document?.schemaVersion === 1 && document.assignments !== null
				&& typeof document.assignments === 'object' && !Array.isArray(document.assignments)) {
			assignments = validAssignments(document.assignments);
		}
	} catch (error) {
		if (signal?.aborted) throw abortReason(signal);
		if (error?.code !== 'ENOENT') throw error;
	}
	mergeLoadedAssignments(coordinator, assignments);
	assignments = snapshotPersistentAssignments(coordinator);
	const ownerController = new AbortController();
	let desiredRevision = 0;
	let persistedRevision = 0;
	let pending = null;
	let lastError = null;
	let lastErrorReported = false;
	let closed = false;
	let closePromise = null;
	const store = new VoiceProfileStore(assignments, (snapshot) => {
		if (closed) return;
		desiredRevision += 1;
		mergeOwnerSnapshot(coordinator, ownerGeneration, snapshot);
		startDrain();
	});
	const io = { makeDirectory, write, move, remove };
	const startDrain = (externalSignal) => {
		if (pending !== null) return pending;
		const combinedSignal = combineSignals(ownerController.signal, signal, externalSignal);
		const operation = (async () => {
			while (persistedRevision < desiredRevision) {
				const targetRevision = desiredRevision;
				await persistCoordinator(coordinator, ownerGeneration, io, combinedSignal);
				persistedRevision = targetRevision;
			}
			lastError = null;
			lastErrorReported = false;
		})();
		pending = operation.catch((error) => {
			lastError = error;
			lastErrorReported = false;
			throw error;
		}).finally(() => { pending = null; });
		pending.catch(() => {});
		return pending;
	};
	const flush = ({ signal: flushSignal } = {}) => {
		if (flushSignal !== undefined && (flushSignal === null || typeof flushSignal.aborted !== 'boolean')) {
			return Promise.reject(new TypeError('voice profile flush signal must be an AbortSignal'));
		}
		if (closePromise !== null) return closePromise;
		if (pending !== null) {
			return pending.catch((error) => {
				lastErrorReported = true;
				throw error;
			});
		}
		if (lastError !== null && !lastErrorReported) {
			lastErrorReported = true;
			return Promise.reject(lastError);
		}
		if (persistedRevision >= desiredRevision) return Promise.resolve();
		return startDrain(flushSignal);
	};
	const close = () => {
		if (closePromise !== null) return closePromise;
		closed = true;
		const drainForClose = async () => {
			if (signal?.aborted) throw abortReason(signal);
			let failures = 0;
			while (persistedRevision < desiredRevision) {
				try {
					await (pending ?? startDrain());
					failures = 0;
				} catch (error) {
					if (ownerController.signal.aborted) throw abortReason(ownerController.signal);
					if (signal?.aborted) throw abortReason(signal);
					failures += 1;
					const retryDelayMs = Math.min(PERSIST_RETRY_BASE_MS * (2 ** (failures - 1)), PERSIST_RETRY_MAX_MS);
					await waitForRetry(retryDelayMs, ownerController.signal);
				}
			}
		};
		const draining = drainForClose();
		let timer;
		const deadline = new Promise((_, reject) => {
			timer = setTimeout(() => {
				const error = abortError('Voice profile store close timed out');
				ownerController.abort(error);
				reject(error);
			}, closeTimeoutMs);
		});
		closePromise = Promise.race([draining, deadline]).finally(() => {
			clearTimeout(timer);
			ownerController.abort(abortError('Voice profile store was closed'));
		}).then(() => undefined);
		return closePromise;
	};
	Object.defineProperties(store, {
		flush: { value: flush, writable: true },
		close: { value: close, writable: true },
	});
	return Object.freeze({ store, flush, close });
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

function persistentCoordinator(filePath) {
	const key = process.platform === 'win32' ? filePath.toLowerCase() : filePath;
	let coordinator = PERSISTENT_FILES.get(key);
	if (coordinator !== undefined) return coordinator;
	coordinator = {
		filePath,
		assignments: new Map(),
		assignmentOwners: new Map(),
		latestOwnerGeneration: 0,
		latestIo: null,
		version: 0,
		writeSequence: 0,
		publishTail: Promise.resolve(),
		repairPromise: null,
		repairRequestedRevision: 0,
		repairCompletedRevision: 0,
		repairRetryTimer: null,
	};
	PERSISTENT_FILES.set(key, coordinator);
	return coordinator;
}

function validAssignments(assignments) {
	const valid = Object.create(null);
	for (const [agentId, profileId] of Object.entries(assignments)) {
		if (!isUuid(agentId) || !VOICE_PROFILES.some((entry) => entry.profileId === profileId)) continue;
		valid[agentId] = profileId;
	}
	return valid;
}

function mergeLoadedAssignments(coordinator, assignments) {
	let changed = false;
	for (const [agentId, profileId] of Object.entries(validAssignments(assignments))) {
		if (coordinator.assignments.has(agentId)) continue;
		coordinator.assignments.set(agentId, profileId);
		coordinator.assignmentOwners.set(agentId, 0);
		changed = true;
	}
	if (changed) coordinator.version += 1;
}

function mergeOwnerSnapshot(coordinator, ownerGeneration, snapshot) {
	let changed = false;
	for (const [agentId, profileId] of Object.entries(validAssignments(snapshot))) {
		const currentOwner = coordinator.assignmentOwners.get(agentId) ?? -1;
		if (ownerGeneration < currentOwner) continue;
		if (coordinator.assignments.get(agentId) !== profileId || currentOwner !== ownerGeneration) changed = true;
		coordinator.assignments.set(agentId, profileId);
		coordinator.assignmentOwners.set(agentId, ownerGeneration);
	}
	if (changed) coordinator.version += 1;
}

function snapshotPersistentAssignments(coordinator) {
	return Object.fromEntries(coordinator.assignments);
}

async function persistCoordinator(coordinator, ownerGeneration, io, signal) {
	for (;;) {
		if (signal?.aborted) throw abortReason(signal);
		const version = coordinator.version;
		const assignments = snapshotPersistentAssignments(coordinator);
		const temporary = `${coordinator.filePath}.tmp-${process.pid}-${ownerGeneration}-${++coordinator.writeSequence}`;
		const encoded = `${JSON.stringify({ schemaVersion: 1, assignments }, null, 2)}\n`;
		try {
			await awaitAbortable(
				Promise.resolve().then(() => io.makeDirectory(path.dirname(coordinator.filePath), { recursive: true, signal })),
				signal,
			);
			const physicalWrite = Promise.resolve().then(() => io.write(temporary, encoded, { encoding: 'utf8', signal }));
			physicalWrite.then(() => {}, () => {}).finally(() => {
				if (signal?.aborted) void removeTemporary(io.remove, temporary);
			});
			await awaitAbortable(physicalWrite, signal);
			const published = await withPublishOwnership(coordinator, async () => {
				if (signal?.aborted) throw abortReason(signal);
				if (version !== coordinator.version) return false;
				const physicalMove = Promise.resolve().then(() => io.move(temporary, coordinator.filePath, { signal }));
				physicalMove.then(
					() => {
						if (version !== coordinator.version || ownerGeneration < coordinator.latestOwnerGeneration) {
							requestRepair(coordinator);
						}
					},
					() => {},
				);
				await awaitAbortable(physicalMove, signal);
				return version === coordinator.version;
			});
			if (published) return;
		} finally {
			await removeTemporary(io.remove, temporary);
		}
	}
}

async function withPublishOwnership(coordinator, operation) {
	const previous = coordinator.publishTail;
	let release;
	coordinator.publishTail = new Promise((resolve) => { release = resolve; });
	await previous;
	try { return await operation(); }
	finally { release(); }
}

function requestRepair(coordinator) {
	if (coordinator.latestIo === null) return;
	coordinator.repairRequestedRevision += 1;
	if (coordinator.repairRetryTimer !== null) {
		clearTimeout(coordinator.repairRetryTimer);
		coordinator.repairRetryTimer = null;
	}
	startRepairDrain(coordinator);
}

function startRepairDrain(coordinator) {
	if (coordinator.repairPromise !== null || coordinator.latestIo === null) return;
	let failed = false;
	const operation = (async () => {
		while (coordinator.repairCompletedRevision < coordinator.repairRequestedRevision) {
			const targetRevision = coordinator.repairRequestedRevision;
			await persistCoordinator(
				coordinator,
				coordinator.latestOwnerGeneration,
				coordinator.latestIo,
				undefined,
			);
			coordinator.repairCompletedRevision = targetRevision;
		}
	})();
	coordinator.repairPromise = operation.catch(() => {
		failed = true;
	}).finally(() => {
		coordinator.repairPromise = null;
		if (coordinator.repairCompletedRevision >= coordinator.repairRequestedRevision) return;
		if (!failed) {
			startRepairDrain(coordinator);
			return;
		}
		coordinator.repairRetryTimer = setTimeout(() => {
			coordinator.repairRetryTimer = null;
			startRepairDrain(coordinator);
		}, REPAIR_RETRY_MS);
		coordinator.repairRetryTimer.unref?.();
	});
}

async function removeTemporary(remove, temporary) {
	try { await remove(temporary); }
	catch (error) { if (error?.code !== 'ENOENT') return; }
}

function combineSignals(...signals) {
	const present = signals.filter((candidate) => candidate !== undefined);
	return present.length === 1 ? present[0] : AbortSignal.any(present);
}

function awaitAbortable(value, signal) {
	if (signal === undefined) return value;
	if (signal.aborted) return Promise.reject(abortReason(signal));
	return new Promise((resolve, reject) => {
		let settled = false;
		const finish = (operation, result) => {
			if (settled) return;
			settled = true;
			signal.removeEventListener('abort', onAbort);
			operation(result);
		};
		const onAbort = () => finish(reject, abortReason(signal));
		signal.addEventListener('abort', onAbort, { once: true });
		Promise.resolve(value).then(
			(result) => signal.aborted ? onAbort() : finish(resolve, result),
			(error) => finish(reject, error),
		);
	});
}

function waitForRetry(delayMs, signal) {
	if (signal.aborted) return Promise.reject(abortReason(signal));
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => finish(resolve), delayMs);
		const onAbort = () => finish(reject, abortReason(signal));
		const finish = (operation, result) => {
			clearTimeout(timer);
			signal.removeEventListener('abort', onAbort);
			operation(result);
		};
		signal.addEventListener('abort', onAbort, { once: true });
	});
}

function abortReason(signal) {
	if (signal?.reason instanceof Error) return signal.reason;
	const error = new Error('Voice profile discovery was cancelled');
	error.name = 'AbortError';
	return error;
}

function abortError(message) {
	const error = new Error(message);
	error.name = 'AbortError';
	return error;
}
