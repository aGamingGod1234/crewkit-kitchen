const PROVIDERS = Object.freeze(['codex', 'gemini', 'kimi', 'cursor']);

export class ProviderService {
	#services;
	#assignments = new Map();

	#turnRecorder;

	constructor(services, { turnRecorder = null } = {}) {
		if (services === null || typeof services !== 'object') throw new TypeError('provider services are required');
		if (turnRecorder !== null && (typeof turnRecorder !== 'object' || typeof turnRecorder.record !== 'function')) throw new TypeError('turnRecorder must provide record or be null');
		this.#turnRecorder = turnRecorder;
		this.#services = new Map(PROVIDERS.map((provider) => {
			const service = services[provider];
			if (service === null || service === undefined) throw new TypeError(`${provider} service is required`);
			return [provider, service];
		}));
		this.catalog = new CombinedProviderCatalog(this.#services);
	}

	async start() { for (const service of this.#services.values()) await service.start(); }
	async stop() { await Promise.allSettled([...this.#services.values()].map((service) => service.stop())); this.#assignments.clear(); }

	async createAgent(profileValue, options) {
		const profile = { ...profileValue, provider: normalizeProvider(profileValue?.provider) };
		const agent = await this.#services.get(profile.provider).createAgent(profile, this.#turnRecorder === null
			? options
			: { ...options, turnRecorder: this.#turnRecorder });
		this.#assignments.set(profile.agentId, profile.provider);
		return agent;
	}

	getAgent(agentId) {
		const assigned = this.#assignments.get(agentId);
		if (assigned !== undefined) return this.#services.get(assigned).getAgent(agentId);
		for (const service of this.#services.values()) { const agent = service.getAgent?.(agentId); if (agent !== null && agent !== undefined) return agent; }
		return null;
	}

	async removeAgent(agentId) {
		const assigned = this.#assignments.get(agentId);
		this.#assignments.delete(agentId);
		if (assigned !== undefined) return this.#services.get(assigned).removeAgent(agentId);
		const results = await Promise.all([...this.#services.values()].map((service) => service.removeAgent(agentId)));
		return results.some(Boolean);
	}

	async reconcile(records) {
		if (!Array.isArray(records)) throw new TypeError('provider reconciliation records must be an array');
		const groups = new Map(PROVIDERS.map((provider) => [provider, []]));
		for (const record of records) groups.get(normalizeProvider(record?.provider)).push({ ...record, provider: normalizeProvider(record?.provider) });
		const results = [];
		for (const provider of PROVIDERS) results.push(await this.#services.get(provider).reconcile(groups.get(provider)));
		this.#assignments.clear();
		for (const result of results) for (const profile of result.valid ?? []) this.#assignments.set(profile.agentId, normalizeProvider(profile.provider));
		return {
			valid: results.flatMap((result) => result.valid ?? []),
			invalid: results.flatMap((result) => result.invalid ?? []),
			removed: results.flatMap((result) => result.removed ?? []),
			catalog: await this.catalog.refresh(),
		};
	}
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
