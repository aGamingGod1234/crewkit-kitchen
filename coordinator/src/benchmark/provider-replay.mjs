import { createHash } from 'node:crypto';

import { parseDecision } from '../decision-parser.mjs';

export const REPLAY_PROTOCOL_VERSION = 1;
const MAX_TRIAL_ID_LENGTH = 128;
const MAX_HASH_LENGTH = 80;
const MAX_RECORDINGS = 65_536;

/**
 * Canonical, stable identity hashing used by bounded decision recordings.
 * Prompts and provider responses are never stored in the returned recording.
 */
export function hashIdentity(value) {
	return `sha256:${createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')}`;
}

export function canonicalJson(value) {
	return JSON.stringify(canonicalize(value));
}

export function canonicalize(value, depth = 0) {
	if (depth > 8) throw codedError('REPLAY_VALUE_TOO_DEEP', 'replay identity exceeds the maximum depth');
	if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
	if (typeof value === 'number') {
		if (!Number.isFinite(value)) throw codedError('REPLAY_INVALID_VALUE', 'replay identity contains a non-finite number');
		return value;
	}
	if (typeof value === 'undefined') return null;
	if (Array.isArray(value)) return value.map((entry) => canonicalize(entry, depth + 1));
	if (typeof value !== 'object') throw codedError('REPLAY_INVALID_VALUE', 'replay identity contains an unsupported value');
	const output = {};
	for (const key of Object.keys(value).sort()) output[key] = canonicalize(value[key], depth + 1);
	return output;
}

/** Normalize and validate the trusted planner decision envelope. */
export function normalizeDecision(value) {
	const parsed = typeof value === 'string' ? parseDecision(value) : parseDecision(JSON.stringify(value));
	return deepFreeze(structuredClone(parsed));
}

export function decisionHash(decision) {
	return hashIdentity(normalizeDecision(decision));
}

export function createReplayRecord({
	trialId,
	prompt,
	providerProfile,
	scenario,
	protocolVersion = 2,
	decision,
	replayVersion = REPLAY_PROTOCOL_VERSION,
} = {}) {
	const id = requireIdentifier(trialId, 'trialId');
	if (!Number.isSafeInteger(protocolVersion) || protocolVersion < 1) throw new TypeError('protocolVersion must be a positive safe integer');
	if (!Number.isSafeInteger(replayVersion) || replayVersion < 1) throw new TypeError('replayVersion must be a positive safe integer');
	const profile = normalizeProfile(providerProfile);
	const scenarioHash = hashIdentity(scenario);
	const normalizedDecision = normalizeDecision(decision);
	return deepFreeze({
		replayVersion,
		trialId: id,
		protocolVersion,
		promptHash: hashIdentity(requirePrompt(prompt)),
		profileHash: hashIdentity(profile),
		scenarioHash,
		decisionHash: decisionHash(normalizedDecision),
		decision: normalizedDecision,
	});
}

export function verifyReplayDecision(recording, decision) {
	const record = normalizeRecord(recording);
	const normalized = normalizeDecision(decision);
	if (decisionHash(normalized) !== record.decisionHash || canonicalJson(normalized) !== canonicalJson(record.decision)) {
		throw codedError('REPLAY_DECISION_MISMATCH', `Replay decision for '${record.trialId}' does not match the recorded decision`);
	}
	return true;
}

/** Bounded provider-service adapter that returns recorded decisions only. */
export class ReplayProvider {
	#recordings;
	#trialId;
	#prompt;
	#providerProfile;
	#scenario;
	#protocolVersion;
	#sessions = new Map();
	#stopped = false;
	calls = 0;

	constructor({ recordings, recording, trialId, prompt, providerProfile, scenario, protocolVersion = 2 } = {}) {
		const values = recording === undefined ? recordings : [recording];
		if (!Array.isArray(values) || values.length === 0 || values.length > MAX_RECORDINGS) throw new TypeError('replay recordings must be a non-empty bounded array');
		const normalizedValues = values.map((entry) => normalizeRecord(entry));
		if (new Set(normalizedValues.map((entry) => entry.trialId)).size !== normalizedValues.length) throw codedError('DUPLICATE_REPLAY_TRIAL', 'replay recordings contain duplicate trial IDs');
		this.#recordings = new Map(normalizedValues.map((normalized) => {
			return [normalized.trialId, normalized];
		}));
		this.#trialId = requireIdentifier(trialId, 'trialId');
		this.#prompt = requirePrompt(prompt);
		this.#providerProfile = normalizeProfile(providerProfile);
		this.#scenario = scenario;
		this.#protocolVersion = protocolVersion;
		this.available = true;
	}

	get provider() { return this.#providerProfile.provider; }
	get recording() { return this.#recordings.get(this.#trialId) ?? null; }

	async start() {
		if (this.#stopped) throw codedError('PROVIDER_STOPPED', 'replay provider is stopped');
	}

	async createAgent(profileValue) {
		if (this.#stopped) throw codedError('PROVIDER_STOPPED', 'replay provider is stopped');
		const profile = normalizeProfile(profileValue);
		if (hashIdentity(profile) !== hashIdentity(this.#providerProfile)) throw codedError('REPLAY_IDENTITY_MISMATCH', 'replay provider profile does not match the recording');
		const agentId = requireIdentifier(profileValue?.agentId, 'agentId');
		let session = this.#sessions.get(agentId);
		if (session !== undefined) return session;
		session = {
			goalRevision: 0,
			async setGoalRevision(revision) {
				if (!Number.isSafeInteger(revision) || revision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
				this.goalRevision = revision;
			},
			decide: (prompt, options = {}) => this.#decide(prompt, options),
			interrupt: async () => {},
		};
		this.#sessions.set(agentId, session);
		return session;
	}

	getAgent(agentId) { return this.#sessions.get(agentId) ?? null; }

	async removeAgent(agentId) { return this.#sessions.delete(agentId); }

	async stop() {
		this.#stopped = true;
		this.#sessions.clear();
	}

	async #decide(prompt, { signal } = {}) {
		if (signal?.aborted) throw signal.reason ?? codedError('PLAN_CANCELLED', 'replay decision was cancelled');
		this.#assertIdentity(prompt);
		const recording = this.recording;
		if (recording === null) throw codedError('REPLAY_RECORD_NOT_FOUND', `no replay recording exists for '${this.#trialId}'`);
		this.calls += 1;
		return structuredClone(recording.decision);
	}

	#assertIdentity(prompt) {
		const recording = this.recording ?? (this.#recordings.size === 1 ? this.#recordings.values().next().value : null);
		if (recording === null) throw codedError('REPLAY_RECORD_NOT_FOUND', `no replay recording exists for '${this.#trialId}'`);
		const configured = {
			trialId: this.#trialId,
			protocolVersion: this.#protocolVersion,
			promptHash: hashIdentity(this.#prompt),
			profileHash: hashIdentity(this.#providerProfile),
			scenarioHash: hashIdentity(this.#scenario),
		};
		if (configured.trialId !== recording.trialId || configured.protocolVersion !== recording.protocolVersion
			|| configured.promptHash !== recording.promptHash || configured.profileHash !== recording.profileHash
			|| configured.scenarioHash !== recording.scenarioHash) {
			throw codedError('REPLAY_IDENTITY_MISMATCH', `replay identity for '${this.#trialId}' does not match the recording`);
		}
		if (hashIdentity(requirePrompt(prompt ?? this.#prompt)) !== recording.promptHash) throw codedError('REPLAY_IDENTITY_MISMATCH', `replay prompt for '${this.#trialId}' does not match the recording`);
	}
}

export function createReplayProvider(options) { return new ReplayProvider(options); }

function normalizeRecord(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('replay recording must be an object');
	const trialId = requireIdentifier(value.trialId, 'recording.trialId');
	const protocolVersion = value.protocolVersion;
	if (!Number.isSafeInteger(protocolVersion) || protocolVersion < 1) throw new TypeError('recording.protocolVersion must be a positive safe integer');
	const replayVersion = value.replayVersion ?? REPLAY_PROTOCOL_VERSION;
	if (!Number.isSafeInteger(replayVersion) || replayVersion < 1) throw new TypeError('recording.replayVersion must be a positive safe integer');
	for (const key of ['promptHash', 'profileHash', 'scenarioHash', 'decisionHash']) {
		if (typeof value[key] !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value[key])) throw new TypeError(`recording.${key} must be a sha256 hash`);
	}
	const decision = normalizeDecision(value.decision);
	if (decisionHash(decision) !== value.decisionHash) throw codedError('REPLAY_RECORD_INVALID', 'recorded decision hash does not match the decision');
	return deepFreeze({
		replayVersion,
		trialId,
		protocolVersion,
		promptHash: value.promptHash,
		profileHash: value.profileHash,
		scenarioHash: value.scenarioHash,
		decisionHash: value.decisionHash,
		decision,
	});
}

function normalizeProfile(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('providerProfile must be an object');
	const profile = {};
	for (const key of ['provider', 'model', 'reasoningEffort', 'serviceTier']) profile[key] = requireIdentifier(value[key], `providerProfile.${key}`);
	return profile;
}

function requirePrompt(value) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError('prompt must be nonblank');
	return value;
}

function requireIdentifier(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_TRIAL_ID_LENGTH) throw new TypeError(`${field} must be nonblank and at most ${MAX_TRIAL_ID_LENGTH} characters`);
	return value.trim();
}

function codedError(code, message) { return Object.assign(new Error(message), { code }); }

function deepFreeze(value, seen = new WeakSet()) {
	if (value === null || typeof value !== 'object' || seen.has(value)) return value;
	seen.add(value);
	for (const child of Object.values(value)) deepFreeze(child, seen);
	return Object.freeze(value);
}
