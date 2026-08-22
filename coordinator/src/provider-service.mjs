const PROVIDERS = Object.freeze(['codex', 'gemini', 'kimi']);
const DEFAULT_SERVICE_TIER = 'priority';
const PROFILE_KEYS = Object.freeze(['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']);

export class ProviderProfileConflictError extends Error {
	constructor() {
		super('Agent profile is immutable for the active provider session');
		this.name = 'ProviderProfileConflictError';
		this.code = 'AGENT_PROFILE_CONFLICT';
	}
}

export class ProviderService {
	#services;
	#assignments = new Map();
	#creating = new Map();

	constructor(services) {
		if (services === null || typeof services !== 'object') throw new TypeError('provider services are required');
		this.#services = new Map(PROVIDERS.map((provider) => {
			const service = services[provider];
			if (service === null || service === undefined) throw new TypeError(`${provider} service is required`);
			return [provider, service];
		}));
		this.catalog = new CombinedProviderCatalog(this.#services);
	}

	async start() { for (const service of this.#services.values()) await service.start(); }
	async stop() {
		await Promise.allSettled([...this.#creating.values()].map((entry) => entry.promise));
		this.#creating.clear();
		await Promise.allSettled([...this.#services.values()].map((service) => service.stop()));
		this.#assignments.clear();
	}
	async bootstrapCatalog() {
		return this.catalog.refresh();
	}

	async createAgent(profileValue, options) {
		const profile = freezeProfile(profileValue);
		const existing = this.#assignments.get(profile.agentId);
		if (existing !== undefined) {
			assertSameProfile(existing, profile);
			return this.#services.get(existing.provider).createAgent(existing, options);
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) {
			assertSameProfile(creating.profile, profile);
			return creating.promise;
		}
		const promise = this.#services.get(profile.provider).createAgent(profile, options);
		this.#creating.set(profile.agentId, { profile, promise });
		try {
			const agent = await promise;
			this.#assignments.set(profile.agentId, profile);
			return agent;
		} finally {
			this.#creating.delete(profile.agentId);
		}
	}

	getAgent(agentId) {
		const assigned = this.#assignments.get(agentId);
		if (assigned !== undefined) return this.#services.get(assigned.provider).getAgent(agentId);
		for (const service of this.#services.values()) { const agent = service.getAgent?.(agentId); if (agent !== null && agent !== undefined) return agent; }
		return null;
	}

	async removeAgent(agentId) {
		const creating = this.#creating.get(agentId);
		if (creating !== undefined) {
			try { await creating.promise; } catch { /* failed creation has no runtime to remove */ }
		}
		const assigned = this.#assignments.get(agentId);
		this.#assignments.delete(agentId);
		if (assigned !== undefined) return this.#services.get(assigned.provider).removeAgent(agentId);
		const results = await Promise.all([...this.#services.values()].map((service) => service.removeAgent(agentId)));
		return results.some(Boolean);
	}

	async reconcile(records) {
		if (!Array.isArray(records)) throw new TypeError('provider reconciliation records must be an array');
		const groups = new Map(PROVIDERS.map((provider) => [provider, []]));
		for (const record of records) groups.get(normalizeProvider(record?.provider)).push({ ...record, provider: normalizeProvider(record?.provider) });
		const results = await Promise.all(PROVIDERS.map((provider) =>
			this.#services.get(provider).reconcile(groups.get(provider))));
		this.#assignments.clear();
		for (const result of results) {
			for (const profile of result.valid ?? []) {
				const normalized = freezeProfile(profile);
				const existing = this.#assignments.get(normalized.agentId);
				if (existing !== undefined) {
					assertSameProfile(existing, normalized);
					continue;
				}
				this.#assignments.set(normalized.agentId, normalized);
			}
		}
		return {
			valid: results.flatMap((result) => result.valid ?? []),
			invalid: results.flatMap((result) => result.invalid ?? []),
			removed: results.flatMap((result) => result.removed ?? []),
			catalog: await this.catalog.refresh(),
		};
	}
}

function freezeProfile(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('provider agent profile must be an object');
	return Object.freeze({
		...value,
		provider: normalizeProvider(value.provider),
		serviceTier: value.serviceTier ?? DEFAULT_SERVICE_TIER,
	});
}

function assertSameProfile(existing, requested) {
	if (!PROFILE_KEYS.every((key) => existing[key] === requested[key])) throw new ProviderProfileConflictError();
}

class CombinedProviderCatalog {
	constructor(services) { this.services = services; this.stale = false; }
	async refresh(options) {
		const snapshots = await Promise.all([...this.services].map(async ([provider, service]) => ({
			provider,
			...await service.catalog.refresh(options),
		})));
		return { refreshedAtEpochMs: Date.now(), models: snapshots.flatMap((snapshot) => (snapshot.models ?? []).map((model) => ({ ...model, provider: model.provider ?? snapshot.provider }))) };
	}
	assertSupported(provider, model, reasoningEffort, serviceTier) {
		const normalized = normalizeProvider(provider);
		return this.services.get(normalized).catalog.assertSupported(model, reasoningEffort, serviceTier);
	}
}

function normalizeProvider(value) {
	const provider = value ?? 'codex';
	if (!PROVIDERS.includes(provider)) throw new TypeError(`provider must be one of ${PROVIDERS.join(', ')}`);
	return provider;
}
