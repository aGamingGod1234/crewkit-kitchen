import { EventEmitter } from 'node:events';

const PROVIDERS = Object.freeze(['codex', 'gemini', 'kimi', 'cursor']);
const DEFAULT_SERVICE_TIER = 'priority';
const PROFILE_KEYS = Object.freeze(['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']);
const DEFAULT_OPERATION_TIMEOUT_MS = 60_000;

export class ProviderProfileConflictError extends Error {
	constructor() {
		super('Agent profile is immutable for the active provider session');
		this.name = 'ProviderProfileConflictError';
		this.code = 'AGENT_PROFILE_CONFLICT';
	}
}

export class ProviderService extends EventEmitter {
	#services;
	#assignments = new Map();
	#creating = new Map();
	#replacing = new Map();
	#starting = new Map();
	#startedProviders = new Set();
	#inFlight = new Set();
	#recovery = new Map();
	#failureBoundaries = new Map();
	#reconciliationGeneration = 0;
	#activeReconciliations = new Map();
	#turnRecorder;
	#operationTimeoutMs;
	#scheduleTimeout;
	#cancelTimeout;
	#now;
	#stopped = false;
	#lifecycleGeneration = 0;
	#stopPromise = null;

	constructor(services, { turnRecorder = null, operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS, scheduleTimeout = setTimeout, cancelTimeout = clearTimeout, now = Date.now } = {}) {
		super();
		if (services === null || typeof services !== 'object') throw new TypeError('provider services are required');
		if (turnRecorder !== null && (typeof turnRecorder !== 'object' || typeof turnRecorder.record !== 'function')) throw new TypeError('turnRecorder must provide record or be null');
		if (!Number.isSafeInteger(operationTimeoutMs) || operationTimeoutMs <= 0) throw new TypeError('operationTimeoutMs must be a positive safe integer');
		if (typeof scheduleTimeout !== 'function' || typeof cancelTimeout !== 'function') throw new TypeError('provider timeout dependencies must be functions');
		if (typeof now !== 'function') throw new TypeError('provider now dependency must be a function');
		this.#turnRecorder = turnRecorder;
		this.#operationTimeoutMs = operationTimeoutMs;
		this.#scheduleTimeout = scheduleTimeout;
		this.#cancelTimeout = cancelTimeout;
		this.#now = now;
		this.#services = new Map(PROVIDERS.filter((provider) => services[provider] !== undefined).map((provider) => {
			const service = services[provider];
			if (service === null || service === undefined) throw new TypeError(`${provider} service is required`);
			return [provider, service];
		}));
		for (const provider of ['codex', 'gemini', 'kimi']) if (!this.#services.has(provider)) throw new TypeError(`${provider} service is required`);
		this.catalog = new CombinedProviderCatalog(this.#services, {
			execute: (provider, operation) => this.#execute(provider, operation, 'catalog', { recordSuccess: false }),
			recordOutcome: (provider, source, recovery) => this.#recordCatalogOutcome(provider, source, recovery),
			recovery: () => this.recoverySnapshot(),
			now: this.#now,
		});
	}

	async start(providers = []) {
		const selected = normalizeProviderSelection(providers, this.#services, { defaultToAll: false });
		return Promise.allSettled(selected.map((provider) => this.#execute(provider, () => undefined, 'startup')));
	}
	stop() {
		if (this.#stopPromise !== null) return this.#stopPromise;
		this.#stopped = true;
		this.#lifecycleGeneration += 1;
		this.#reconciliationGeneration += 1;
		for (const { controller } of this.#activeReconciliations.values()) controller.abort(providerError('STALE_RECONCILIATION', 'Provider reconciliation was stopped'));
		this.#starting.clear();
		this.#stopPromise = this.#stopOnce();
		return this.#stopPromise;
	}

	async #stopOnce() {
		const operations = [...this.#inFlight];
		const backendStops = [...this.#services.entries()].map(([provider, service]) => withTimeout(
			Promise.resolve().then(() => service.stop()),
			this.#operationTimeoutMs,
			this.#scheduleTimeout,
			this.#cancelTimeout,
			provider,
		));
		await Promise.allSettled([...backendStops, ...operations]);
		await Promise.allSettled([...this.#services.entries()].map(([provider, service]) => withTimeout(
			Promise.resolve().then(() => service.stop()),
			this.#operationTimeoutMs,
			this.#scheduleTimeout,
			this.#cancelTimeout,
			provider,
		)));
		this.#assignments.clear();
		this.#creating.clear();
		this.#replacing.clear();
		this.#starting.clear();
		this.#startedProviders.clear();
		this.#inFlight.clear();
		this.#activeReconciliations.clear();
		this.#failureBoundaries.clear();
		this.#recovery.clear();
	}
	async bootstrapCatalog(recordsOrProviders = undefined) {
		const providers = normalizeProviderSelection(recordsOrProviders ?? [], this.#services, { defaultToAll: recordsOrProviders === undefined });
		return this.catalog.refresh({ providers });
	}

	recoverySnapshot() {
		return [...this.#services.keys()].map((provider) => {
			const record = this.#recovery.get(provider);
			return record === undefined
				? { provider, state: 'idle', fallbackMode: null, failureCode: null, consecutiveFailureCount: 0, nextProbeAtEpochMs: null, generation: 0, lastRecoveryAtEpochMs: null }
				: { provider, ...record };
		});
	}

	async createAgent(profileValue, options) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		const profile = freezeProfile(profileValue);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameProfile(replacing.profile, profile);
			return replacing.promise;
		}
		const existing = this.#assignments.get(profile.agentId);
		if (existing !== undefined) {
			assertSameProfile(existing, profile);
			return this.#execute(existing.provider, () => this.#services.get(existing.provider).createAgent(existing, this.#creationOptions(options)), 'create');
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) {
			assertSameProfile(creating.profile, profile);
			return creating.promise;
		}
		const service = this.#services.get(profile.provider);
		if (service === undefined) throw new TypeError(`${profile.provider} service is unavailable`);
		const promise = this.#execute(profile.provider, () => service.createAgent(profile, this.#creationOptions(options)), 'create');
		this.#creating.set(profile.agentId, { profile, promise });
		try {
			const agent = await promise;
			this.#assertActive(lifecycleGeneration);
			this.#assignments.set(profile.agentId, profile);
			return agent;
		} finally {
			this.#creating.delete(profile.agentId);
		}
	}

	async replaceAgent(profileValue, options = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		const profile = freezeProfile(profileValue);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameProfile(replacing.profile, profile);
			return replacing.promise;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) assertSameProfile(creating.profile, profile);
		const service = this.#services.get(profile.provider);
		if (service === undefined) throw new TypeError(`${profile.provider} service is unavailable`);
		const promise = (async () => {
			if (creating !== undefined) {
				try { await creating.promise; } catch { /* replacement recreates a failed attempt */ }
				this.#assertActive(lifecycleGeneration);
			}
			const assigned = this.#assignments.get(profile.agentId);
			if (assigned !== undefined) assertSameProfile(assigned, profile);
			return this.#execute(profile.provider, async () => {
				const agent = typeof service.replaceAgent === 'function'
					? await service.replaceAgent(profile, this.#creationOptions(options))
					: (await service.removeAgent(profile.agentId), await service.createAgent(profile, this.#creationOptions(options)));
				this.#assertActive(lifecycleGeneration);
				this.#assignments.set(profile.agentId, profile);
				return agent;
			}, 'replace');
		})();
		const entry = { profile, promise };
		this.#replacing.set(profile.agentId, entry);
		try { return await promise; }
		finally { if (this.#replacing.get(profile.agentId) === entry) this.#replacing.delete(profile.agentId); }
	}

	getAgent(agentId) {
		if (this.#stopped) return null;
		const assigned = this.#assignments.get(agentId);
		if (assigned !== undefined) return this.#services.get(assigned.provider).getAgent(agentId);
		for (const service of this.#services.values()) { const agent = service.getAgent?.(agentId); if (agent !== null && agent !== undefined) return agent; }
		return null;
	}

	async removeAgent(agentId) {
		const replacing = this.#replacing.get(agentId);
		if (replacing !== undefined) {
			try { await replacing.promise; } catch { /* failed replacement has no live runtime */ }
		}
		const creating = this.#creating.get(agentId);
		if (creating !== undefined) {
			try { await creating.promise; } catch { /* failed creation has no runtime to remove */ }
		}
		const assigned = this.#assignments.get(agentId);
		this.#assignments.delete(agentId);
		if (assigned !== undefined) return this.#execute(assigned.provider, () => this.#services.get(assigned.provider).removeAgent(agentId), 'remove');
		const results = await Promise.all([...this.#startedProviders].map((provider) => this.#execute(provider, () => this.#services.get(provider).removeAgent(agentId), 'remove')));
		return results.some(Boolean);
	}

	async reconcile(records) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		if (!Array.isArray(records)) throw new TypeError('provider reconciliation records must be an array');
		const reconciliationGeneration = ++this.#reconciliationGeneration;
		for (const { controller } of this.#activeReconciliations.values()) controller.abort(providerError('STALE_RECONCILIATION', 'Provider reconciliation was superseded'));
		const controller = new AbortController();
		this.#activeReconciliations.set(reconciliationGeneration, { controller });
		try {
		const availableProviders = [...this.#services.keys()];
		const groups = new Map(availableProviders.map((provider) => [provider, []]));
		for (const record of records) {
			const provider = normalizeProvider(record?.provider);
			if (!groups.has(provider)) throw new TypeError(`${provider} service is unavailable`);
			groups.get(provider).push({ ...record, provider });
		}
		const assignedProviders = new Set([...this.#assignments.values()].map(({ provider }) => provider));
		const selectedProviders = availableProviders.filter((provider) => groups.get(provider).length > 0 || assignedProviders.has(provider));
		const settled = await Promise.all(selectedProviders.map(async (provider) => {
			try {
				return { provider, result: await this.#execute(provider, () => this.#services.get(provider).reconcile(groups.get(provider), {
					signal: controller.signal,
					generation: reconciliationGeneration,
				}), 'reconcile', { recordSuccess: false }) };
			} catch (error) {
				return { provider, error };
			}
		}));
		this.#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration);
		const previousAssignments = this.#assignments;
		const nextAssignments = new Map();
		for (const { provider, result, error } of settled) {
			if (error !== undefined) {
				for (const profileValue of groups.get(provider)) {
					const profile = freezeProfile(profileValue);
					const previous = previousAssignments.get(profile.agentId);
					if (previous !== undefined && profilesMatch(previous, profile)) nextAssignments.set(profile.agentId, previous);
				}
				continue;
			}
			for (const profile of result.valid ?? []) {
				const normalized = freezeProfile(profile);
				const existing = previousAssignments.get(normalized.agentId) ?? nextAssignments.get(normalized.agentId);
				if (existing !== undefined) {
					assertSameProfile(existing, normalized);
					nextAssignments.set(normalized.agentId, existing);
					continue;
				}
				nextAssignments.set(normalized.agentId, normalized);
			}
		}
		const failures = settled.filter(({ error }) => error !== undefined);
		const catalog = await this.catalog.refresh({ providers: selectedProviders, fallbackProviders: failures.map(({ provider }) => provider) });
		this.#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration);
		this.#assignments = nextAssignments;
		for (const { provider, result } of settled) if (result !== undefined) this.#recordLive(provider, 'reconcile');
		return {
			valid: settled.flatMap(({ result }) => result?.valid ?? []),
			invalid: [
				...settled.flatMap(({ result }) => result?.invalid ?? []),
				...failures.flatMap(({ provider, error }) => groups.get(provider).map((profile) => providerFailure(profile, error))),
			],
			removed: settled.flatMap(({ result }) => result?.removed ?? []),
			catalog,
			recovery: this.recoverySnapshot(),
		};
		} finally {
			this.#activeReconciliations.delete(reconciliationGeneration);
		}
	}

	async #execute(provider, operation, boundary, { recordSuccess = true } = {}) {
		if (this.#stopped) throw providerError('PROVIDER_STOPPED', 'Provider service is stopped');
		const lifecycleGeneration = this.#lifecycleGeneration;
		let startup = null;
		const task = Promise.resolve()
			.then(() => {
				startup = this.#ensureStarted(provider, lifecycleGeneration);
				return startup?.promise;
			})
			.then(() => {
				this.#assertActive(lifecycleGeneration);
				return operation();
			});
		const observed = task.then((result) => {
			this.#assertActive(lifecycleGeneration);
			if (recordSuccess) this.#recordLive(provider, boundary);
			return result;
		}, (error) => {
			if (!isLifecycleFenceError(error)) this.#recordDegraded(provider, error, boundary);
			throw error;
		});
		const bounded = withTimeout(observed, this.#operationTimeoutMs, this.#scheduleTimeout, this.#cancelTimeout, provider);
		this.#inFlight.add(bounded);
		try {
			return await bounded;
		} catch (error) {
			if (error?.code === 'PROVIDER_TIMEOUT') {
				if (startup !== null && !this.#startedProviders.has(provider) && this.#starting.get(provider) === startup) this.#starting.delete(provider);
				this.#recordDegraded(provider, error, boundary);
			}
			throw error;
		} finally {
			this.#inFlight.delete(bounded);
		}
	}

	#ensureStarted(provider, lifecycleGeneration) {
		if (this.#startedProviders.has(provider)) return null;
		const pending = this.#starting.get(provider);
		if (pending !== undefined) return pending;
		const service = this.#services.get(provider);
		if (service === undefined) throw new TypeError(`${provider} service is unavailable`);
		const starting = { promise: null };
		starting.promise = Promise.resolve()
			.then(() => this.#assertActive(lifecycleGeneration))
			.then(() => service.start())
			.then(() => {
				this.#assertActive(lifecycleGeneration);
				if (this.#starting.get(provider) !== starting) throw providerError('STALE_PROVIDER_START', 'Provider startup generation was superseded');
				this.#startedProviders.add(provider);
			})
			.finally(() => { if (this.#starting.get(provider) === starting) this.#starting.delete(provider); });
		this.#starting.set(provider, starting);
		return starting;
	}

	#creationOptions(options) {
		return this.#turnRecorder === null ? options : { ...options, turnRecorder: this.#turnRecorder };
	}

	#assertActive(lifecycleGeneration) {
		if (this.#stopped || lifecycleGeneration !== this.#lifecycleGeneration) throw providerError('PROVIDER_STOPPED', 'Provider service is stopped');
	}

	#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration) {
		this.#assertActive(lifecycleGeneration);
		if (reconciliationGeneration !== this.#reconciliationGeneration) throw providerError('STALE_RECONCILIATION', 'Provider reconciliation was superseded');
	}

	#recordCatalogOutcome(provider, source, recovery) {
		if (source === 'live') {
			this.#recordLive(provider, 'catalog');
			return;
		}
		const code = recovery?.failureCode ?? (source === 'builtin' ? 'CATALOG_BUILTIN_FALLBACK' : 'CATALOG_STALE_FALLBACK');
		this.#recordDegraded(provider, providerError(code, `Provider catalog is using ${source}`), 'catalog', source);
	}

	#recordLive(provider, boundary) {
		if (this.#stopped) return;
		const previous = this.#recovery.get(provider);
		const failures = this.#failureBoundaries.get(provider);
		failures?.delete(boundary);
		if (failures?.size === 0) this.#failureBoundaries.delete(provider);
		const remaining = this.#failureBoundaries.get(provider);
		if (remaining?.size > 0) {
			const latest = [...remaining.values()].at(-1);
			this.#recovery.set(provider, {
				state: 'degraded', fallbackMode: latest.fallbackMode, failureCode: latest.failureCode,
				consecutiveFailureCount: [...remaining.values()].reduce((sum, failure) => Math.min(1_000_000, sum + failure.count), 0),
				nextProbeAtEpochMs: latest.nextProbeAtEpochMs, generation: previous?.generation ?? 1,
				lastRecoveryAtEpochMs: previous?.lastRecoveryAtEpochMs ?? null,
			});
			return;
		}
		const recovered = previous?.state === 'degraded';
		this.#recovery.set(provider, {
			state: 'live', fallbackMode: null, failureCode: null, consecutiveFailureCount: 0,
			nextProbeAtEpochMs: null, generation: previous?.generation ?? 1,
			lastRecoveryAtEpochMs: recovered ? safeNow(this.#now) : previous?.lastRecoveryAtEpochMs ?? null,
		});
		if (recovered) this.emit('providerRestored', { provider });
	}

	#recordDegraded(provider, error, boundary, fallbackMode = 'last_valid') {
		if (this.#stopped) return;
		const previous = this.#recovery.get(provider);
		const failures = this.#failureBoundaries.get(provider) ?? new Map();
		const priorBoundary = failures.get(boundary);
		const failureCount = Math.min(1_000_000, (priorBoundary?.count ?? 0) + 1);
		const now = safeNow(this.#now);
		const failure = {
			count: failureCount,
			failureCode: boundedFailureCode(error),
			fallbackMode,
			nextProbeAtEpochMs: now === null ? null : now + Math.min(30_000, 1_000 * (2 ** Math.min(5, failureCount - 1))),
		};
		failures.set(boundary, failure);
		this.#failureBoundaries.set(provider, failures);
		this.#recovery.set(provider, {
			state: 'degraded', fallbackMode, failureCode: failure.failureCode,
			consecutiveFailureCount: [...failures.values()].reduce((sum, entry) => Math.min(1_000_000, sum + entry.count), 0),
			nextProbeAtEpochMs: failure.nextProbeAtEpochMs,
			generation: (previous?.generation ?? 0) + (previous?.state === 'degraded' ? 0 : 1),
			lastRecoveryAtEpochMs: previous?.lastRecoveryAtEpochMs ?? null,
		});
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
	constructor(services, { execute, recordOutcome, recovery, now }) {
		this.services = services;
		this.execute = execute;
		this.recordOutcome = recordOutcome;
		this.recovery = recovery;
		this.now = now;
		this.lastValid = new Map();
		this.stale = false;
	}
	async refresh({ providers = undefined, fallbackProviders = [], ...options } = {}) {
		const selected = normalizeProviderSelection(providers ?? [], this.services, { defaultToAll: providers === undefined });
		const fallbackOnly = new Set(normalizeProviderSelection(fallbackProviders, this.services, { defaultToAll: false }));
		const settled = await Promise.all(selected.map(async (provider) => {
			const service = this.services.get(provider);
			if (fallbackOnly.has(provider)) {
				const retained = this.lastValid.get(provider);
				return retained === undefined ? null : { provider, ...structuredClone(retained), source: 'last_valid' };
			}
			try {
				const raw = await this.execute(provider, async () => validateCatalogSnapshot(await service.catalog.refresh(options), provider));
				const source = catalogSource(raw.source, service.catalog.stale, this.lastValid.has(provider));
				this.recordOutcome(provider, source, raw.recovery);
				if (source === 'live') this.lastValid.set(provider, raw);
				if (source === 'last_valid') {
					if (!this.lastValid.has(provider)) this.lastValid.set(provider, raw);
					return { provider, ...structuredClone(this.lastValid.get(provider)), source };
				}
				return { provider, ...raw, source };
			} catch {
				const retained = this.lastValid.get(provider);
				return retained === undefined ? null : { provider, ...structuredClone(retained), source: 'last_valid' };
			}
		}));
		const snapshots = settled.filter((snapshot) => snapshot !== null);
		this.stale = snapshots.length !== selected.length || snapshots.some(({ source }) => source !== 'live');
		return {
			refreshedAtEpochMs: safeNow(this.now) ?? 0,
			models: snapshots.flatMap((snapshot) => snapshot.models.map((model) => ({ ...model, provider: model.provider ?? snapshot.provider }))),
			source: combinedSource(snapshots, selected.length),
			recovery: this.recovery(),
		};
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

function normalizeProviderSelection(values, services, { defaultToAll }) {
	if (!Array.isArray(values)) throw new TypeError('provider selection must be an array');
	if (values.length === 0 && defaultToAll) return [...services.keys()];
	const selected = [];
	for (const value of values) {
		const provider = normalizeProvider(typeof value === 'string' ? value : value?.provider);
		if (!services.has(provider)) throw new TypeError(`${provider} service is unavailable`);
		if (!selected.includes(provider)) selected.push(provider);
	}
	return selected;
}

function profilesMatch(left, right) {
	return PROFILE_KEYS.every((key) => left[key] === right[key]);
}

function providerFailure(profile, error) {
	const code = boundedFailureCode(error);
	return {
		agentId: profile.agentId ?? null,
		profile: structuredClone(profile),
		code,
		message: `${profile.provider ?? 'AI'} provider is temporarily unavailable (${code}).`,
	};
}

function withTimeout(promise, timeoutMs, scheduleTimeout, cancelTimeout, provider) {
	let handle;
	const timeout = new Promise((_, reject) => {
		handle = scheduleTimeout(() => reject(providerError('PROVIDER_TIMEOUT', `${provider} provider operation timed out after ${timeoutMs} ms`)), timeoutMs);
		handle?.unref?.();
	});
	return Promise.race([promise, timeout]).finally(() => cancelTimeout(handle));
}

function isLifecycleFenceError(error) {
	return ['PROVIDER_STOPPED', 'STALE_PROVIDER_START', 'STALE_RECONCILIATION'].includes(error?.code);
}

function providerError(code, message) {
	return Object.assign(new Error(message), { code });
}

function boundedFailureCode(error) {
	return String(error?.code ?? 'PROVIDER_UNAVAILABLE').replace(/[^A-Za-z0-9._:-]/g, '_').slice(0, 128) || 'PROVIDER_UNAVAILABLE';
}

function safeNow(now) {
	try {
		const value = now();
		return Number.isSafeInteger(value) && value >= 0 ? value : null;
	} catch { return null; }
}

function validateCatalogSnapshot(value, provider) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.models)) {
		throw providerError('INVALID_CATALOG', `${provider} catalog snapshot must contain a models array`);
	}
	if (value.models.length === 0) throw providerError('INVALID_CATALOG', `${provider} catalog snapshot must contain at least one model`);
	for (const model of value.models) {
		if (model === null || typeof model !== 'object' || Array.isArray(model)) throw providerError('INVALID_CATALOG', `${provider} catalog model must be an object`);
		for (const [field, fieldValue] of [['id', model.id], ['model', model.model], ['displayName', model.displayName]]) {
			if (typeof fieldValue !== 'string' || fieldValue.trim().length === 0) throw providerError('INVALID_CATALOG', `${provider} catalog model ${field} must be nonblank`);
		}
		for (const [field, values] of [['reasoningEfforts', model.reasoningEfforts], ['serviceTiers', model.serviceTiers]]) {
			if (!Array.isArray(values) || values.some((entry) => typeof entry !== 'string' || entry.trim().length === 0)) throw providerError('INVALID_CATALOG', `${provider} catalog model ${field} must contain strings`);
		}
	}
	return {
		refreshedAtEpochMs: value.refreshedAtEpochMs ?? 0,
		models: structuredClone(value.models),
		...(value.source === undefined ? {} : { source: value.source }),
		...(value.recovery === undefined ? {} : { recovery: normalizeCatalogRecovery(value.recovery) }),
	};
}

function normalizeCatalogRecovery(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return { state: 'degraded', failureCode: 'CATALOG_REFRESH_FAILED', consecutiveFailureCount: 1 };
	return {
		state: value.state === 'live' ? 'live' : 'degraded',
		failureCode: value.failureCode === null ? null : boundedFailureCode({ code: value.failureCode }),
		consecutiveFailureCount: Number.isSafeInteger(value.consecutiveFailureCount) && value.consecutiveFailureCount >= 0
			? Math.min(1_000_000, value.consecutiveFailureCount)
			: 1,
	};
}

function catalogSource(value, stale, hasLastValid) {
	if (value === 'live') return 'live';
	if (value === 'last_valid') return 'last_valid';
	if (value === 'builtin') return 'builtin';
	return stale === true ? (hasLastValid ? 'last_valid' : 'builtin') : 'live';
}

function combinedSource(snapshots, expectedCount) {
	if (snapshots.length < expectedCount || snapshots.some(({ source }) => source === 'last_valid')) return 'last_valid';
	if (snapshots.some(({ source }) => source === 'builtin')) return 'builtin';
	return 'live';
}
