import { DEFAULT_SERVICE_TIER } from './constants.mjs';

const DEFAULT_CATALOG_TTL_MS = 60_000;

export class ModelCatalogError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'ModelCatalogError';
		this.code = code;
	}
}

export class ModelCatalogCache {
	#loader;
	#ttlMs;
	#now;
	#models = [];
	#refreshedAtEpochMs = 0;
	#refreshPromise = null;

	constructor(loader, { ttlMs = DEFAULT_CATALOG_TTL_MS, now = Date.now } = {}) {
		if (typeof loader !== 'function') throw new TypeError('model catalog loader must be a function');
		if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0) throw new TypeError('catalog ttlMs must be a positive safe integer');
		if (typeof now !== 'function') throw new TypeError('catalog now dependency must be a function');
		this.#loader = loader;
		this.#ttlMs = ttlMs;
		this.#now = now;
	}

	get stale() {
		return this.#models.length === 0 || this.#now() - this.#refreshedAtEpochMs >= this.#ttlMs;
	}

	async refresh({ force = false } = {}) {
		if (!force && !this.stale) return this.snapshot();
		if (this.#refreshPromise !== null) return this.#refreshPromise;
		this.#refreshPromise = Promise.resolve()
			.then(() => this.#loader())
			.then((models) => {
				this.#models = normalizeCatalog(models);
				this.#refreshedAtEpochMs = this.#now();
				return this.snapshot();
			})
			.finally(() => { this.#refreshPromise = null; });
		return this.#refreshPromise;
	}

	snapshot() {
		return {
			refreshedAtEpochMs: this.#refreshedAtEpochMs,
			models: structuredClone(this.#models),
		};
	}

	find(modelId) {
		const model = this.#models.find((entry) => entry.id === modelId || entry.model === modelId);
		return model === undefined ? null : structuredClone(model);
	}

	assertSupported(modelId, reasoningEffort, serviceTier = DEFAULT_SERVICE_TIER) {
		const model = this.find(modelId);
		if (model === null) throw new ModelCatalogError('MODEL_UNAVAILABLE', `Model '${modelId}' is not present in the current Codex catalog`);
		if (!model.reasoningEfforts.includes(reasoningEffort)) throw new ModelCatalogError('REASONING_EFFORT_UNAVAILABLE', `Model '${modelId}' does not support reasoning effort '${reasoningEffort}'`);
		if (model.serviceTiers.length > 0 && !model.serviceTiers.includes(serviceTier)) throw new ModelCatalogError('SERVICE_TIER_UNAVAILABLE', `Model '${modelId}' does not support service tier '${serviceTier}'`);
		return model;
	}

	reconcileProfiles(profiles) {
		if (!Array.isArray(profiles)) throw new TypeError('model profiles must be an array');
		const valid = [];
		const invalid = [];
		for (const profile of profiles) {
			try {
				this.assertSupported(profile.model, profile.reasoningEffort, profile.serviceTier ?? DEFAULT_SERVICE_TIER);
				valid.push(structuredClone(profile));
			} catch (error) {
				invalid.push({ agentId: profile.agentId ?? null, code: error.code ?? 'INVALID_PROFILE', message: error.message });
			}
		}
		return { valid, invalid };
	}
}

export function normalizeCatalog(value) {
	if (!Array.isArray(value)) throw new ModelCatalogError('INVALID_CATALOG', 'Model catalog must be an array');
	const seen = new Set();
	return value.map((entry) => {
		if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) throw new ModelCatalogError('INVALID_CATALOG', 'Each model catalog entry must be an object');
		const id = requireText(entry.id ?? entry.model ?? entry.slug, 'model id');
		if (seen.has(id)) throw new ModelCatalogError('INVALID_CATALOG', `Duplicate model '${id}'`);
		seen.add(id);
		return {
			id,
			model: requireText(entry.model ?? entry.slug ?? id, 'model'),
			displayName: requireDisplayName(entry.displayName ?? entry.display_name, id),
			reasoningEfforts: normalizeStringList(
				entry.supportedReasoningEfforts ?? entry.supportedReasoningLevels ?? entry.supported_reasoning_levels,
				'reasoningEffort',
			),
			serviceTiers: normalizeServiceTiers(entry),
		};
	});
}

function normalizeServiceTiers(entry) {
	return [...new Set([
		...normalizeStringList(entry.serviceTiers ?? entry.service_tiers, 'id'),
		...normalizeStringList(entry.additionalSpeedTiers ?? entry.additional_speed_tiers, 'id'),
	])];
}

function normalizeStringList(value, objectKey) {
	if (value === undefined || value === null) return [];
	if (!Array.isArray(value)) throw new ModelCatalogError('INVALID_CATALOG', 'Model capability lists must be arrays');
	return [...new Set(value.map((entry) => requireText(
		typeof entry === 'string' ? entry : entry?.[objectKey] ?? (objectKey === 'reasoningEffort' ? entry?.effort : undefined),
		objectKey,
	)))];
}

function requireDisplayName(value, fallback) {
	return typeof value === 'string' && value.trim().length > 0 ? value.trim() : fallback;
}

function requireText(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new ModelCatalogError('INVALID_CATALOG', `${field} must be nonblank`);
	return value;
}
