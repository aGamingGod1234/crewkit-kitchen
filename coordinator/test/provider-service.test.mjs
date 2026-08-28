import assert from 'node:assert/strict';
import test from 'node:test';

import { AntigravityProviderService } from '../src/antigravity-service.mjs';
import { ProviderService } from '../src/provider-service.mjs';

class FakeService {
	constructor(provider) {
		this.provider = provider; this.created = []; this.removed = []; this.stopped = false;
		this.catalog = { refresh: async () => ({ provider, refreshedAtEpochMs: 1, models: [] }), assertSupported: () => {} };
	}
	async start() {}
	async stop() { this.stopped = true; }
	async createAgent(profile) { this.created.push(profile); return { agentId: profile.agentId, provider: this.provider }; }
	getAgent(agentId) { return this.created.some((profile) => profile.agentId === agentId) ? { agentId, provider: this.provider } : null; }
	async removeAgent(agentId) { this.removed.push(agentId); return true; }
	async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: { provider: this.provider, models: [] } }; }
}

function profile(provider, overrides = {}) {
	return {
		agentId: 'shared-agent',
		provider,
		model: `${provider}-model`,
		reasoningEffort: 'high',
		serviceTier: 'fast',
		...overrides,
	};
}

test('provider router defaults legacy profiles to Codex and isolates each backend', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi', 'cursor'].map((provider) => [provider, new FakeService(provider)]));
	const router = new ProviderService(services);
	assert.equal((await router.createAgent({ agentId: 'legacy' })).provider, 'codex');
	assert.equal((await router.createAgent({ agentId: 'g', provider: 'gemini' })).provider, 'gemini');
	assert.equal((await router.createAgent({ agentId: 'k', provider: 'kimi' })).provider, 'kimi');
	assert.equal((await router.createAgent({ agentId: 'u', provider: 'cursor' })).provider, 'cursor');
	assert.deepEqual(services.codex.created.map((profile) => profile.provider), ['codex']);
	assert.equal(router.getAgent('g').provider, 'gemini');
	await router.removeAgent('k');
	assert.deepEqual(services.kimi.removed, ['k']);
	await router.stop();
	assert.equal(services.gemini.stopped, true);
});

test('provider router rejects every profile mutation for an existing agent ID', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const router = new ProviderService(services);
	const selected = profile('codex');
	const agent = await router.createAgent(selected);
	assert.equal(agent.provider, 'codex');

	for (const mutation of [
		{ provider: 'gemini' },
		{ model: 'codex-other-model' },
		{ reasoningEffort: 'low' },
		{ serviceTier: 'priority' },
	]) {
		await assert.rejects(
			router.createAgent({ ...selected, ...mutation }),
			(error) => error?.code === 'AGENT_PROFILE_CONFLICT'
				&& error?.message.length <= 256
				&& !error?.message.includes('secret'),
		);
	}
	assert.equal(services.codex.created.length, 1);
	assert.equal(services.gemini.created.length, 0);
	assert.equal(services.kimi.created.length, 0);
	await router.stop();
});

test('provider reconciliation retains a Gemini fast profile and rejects a tier mutation', async () => {
	const gemini = new AntigravityProviderService({
		provider: 'gemini',
		cwd: 'C:\\workspace',
		models: ['gemini-3.1-pro'],
		modelReasoningEfforts: { 'gemini-3.1-pro': ['high', 'low'] },
	});
	const router = new ProviderService({
		codex: new FakeService('codex'),
		gemini,
		kimi: new FakeService('kimi'),
	});
	const selected = profile('gemini', {
		agentId: 'gemini-session',
		model: 'gemini-3.1-pro',
		serviceTier: 'fast',
	});
	const agent = await router.createAgent(selected);
	const reconciliation = await router.reconcile([selected]);
	assert.deepEqual(reconciliation.valid, [selected], 'reconciliation preserves the complete Gemini profile');
	assert.equal(await router.createAgent(selected), agent, 'same profile reuses the existing Gemini session');
	await assert.rejects(
		router.createAgent({ ...selected, serviceTier: 'priority' }),
		(error) => error?.code === 'AGENT_PROFILE_CONFLICT',
	);
	await router.stop();
});

test('provider router reserves an in-flight agent ID before recovery can mutate its profile', async () => {
	let release;
	const pending = new Promise((resolve) => { release = resolve; });
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.createAgent = async (value) => {
		services.codex.created.push(value);
		await pending;
		return { agentId: value.agentId, provider: value.provider };
	};
	const router = new ProviderService(services);
	const selected = profile('codex');
	const creating = router.createAgent(selected, { recoverySummary: 'same brain recovery' });
	await new Promise((resolve) => setImmediate(resolve));
	const mutation = router.createAgent({ ...selected, serviceTier: 'priority' }, { recoverySummary: 'mutated recovery' });
	release();
	await creating;
	await assert.rejects(mutation, (error) => error?.code === 'AGENT_PROFILE_CONFLICT');
	assert.equal(services.codex.created.length, 1);
	await router.stop();
});

test('provider reconciliation groups profiles and preserves an unavailable provider as an invalid subset', async () => {
	const codex = new FakeService('codex');
	const gemini = new FakeService('gemini');
	const kimi = new FakeService('kimi');
	const cursor = new FakeService('cursor');
	gemini.reconcile = async (records) => ({ valid: [], invalid: records.map((record) => ({ profile: record, code: 'PROVIDER_UNAVAILABLE', message: 'login rejected' })), removed: [], catalog: { provider: 'gemini', models: [] } });
	const router = new ProviderService({ codex, gemini, kimi, cursor });
	const result = await router.reconcile([
		{ agentId: 'c', provider: 'codex' }, { agentId: 'g', provider: 'gemini' }, { agentId: 'k', provider: 'kimi' },
		{ agentId: 'u', provider: 'cursor' },
	]);
	assert.deepEqual(result.valid.map((record) => record.agentId), ['c', 'k', 'u']);
	assert.deepEqual(result.invalid.map((entry) => entry.profile.agentId), ['g']);
});

test('combined catalog refreshes independent provider CLIs concurrently', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi', 'cursor'].map((provider) => [provider, new FakeService(provider)]));
	const started = [];
	const releases = new Map();
	for (const [provider, service] of Object.entries(services)) {
		service.catalog.refresh = () => new Promise((resolve) => {
			started.push(provider);
			releases.set(provider, () => resolve({ provider, refreshedAtEpochMs: 1, models: [] }));
		});
	}
	const router = new ProviderService(services);
	const refreshing = router.catalog.refresh();
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['codex', 'gemini', 'kimi', 'cursor']);
	for (const release of releases.values()) release();
	await refreshing;
});

test('reconciles independent providers concurrently', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const started = [];
	const releases = new Map();
	for (const [provider, service] of Object.entries(services)) {
		service.reconcile = (records) => new Promise((resolve) => {
			started.push(provider);
			releases.set(provider, () => resolve({ valid: records, invalid: [], removed: [], catalog: { models: [] } }));
		});
	}
	const router = new ProviderService(services);
	const reconciling = router.reconcile([
		{ agentId: 'c', provider: 'codex' }, { agentId: 'g', provider: 'gemini' }, { agentId: 'k', provider: 'kimi' },
	]);
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['codex', 'gemini', 'kimi']);
	for (const release of releases.values()) release();
	const result = await reconciling;
	assert.deepEqual(result.valid.map((profile) => profile.agentId), ['c', 'g', 'k']);
});

test('bootstrap catalog is complete before mixed-provider profiles can be accepted', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	for (const [provider, service] of Object.entries(services)) {
		service.catalog.refresh = async () => ({
			refreshedAtEpochMs: 42,
			models: [{ id: `${provider}-model`, model: `${provider}-model`, displayName: `${provider} model`, reasoningEfforts: ['high'], serviceTiers: [] }],
		});
	}
	const router = new ProviderService(services);

	const snapshot = await router.bootstrapCatalog();
	assert.deepEqual(snapshot.models.map((model) => [model.provider, model.id]), [
		['codex', 'codex-model'],
		['gemini', 'gemini-model'],
		['kimi', 'kimi-model'],
	]);
});

test('concurrent creation coalesces one lazy startup for the selected provider', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseStart;
	let startCalls = 0;
	services.codex.start = async () => {
		startCalls += 1;
		await new Promise((resolve) => { releaseStart = resolve; });
	};
	const router = new ProviderService(services);
	const first = router.createAgent(profile('codex', { agentId: 'codex-a' }));
	const second = router.createAgent(profile('codex', { agentId: 'codex-b' }));
	await new Promise((resolve) => setImmediate(resolve));
	try {
		assert.equal(startCalls, 1);
		assert.equal(services.codex.created.length, 0, 'agent creation waits for the shared provider startup');
	} finally {
		releaseStart?.();
		await Promise.allSettled([first, second]);
		await router.stop();
	}
});

test('a hung provider is bounded while healthy provider reconciliation remains usable', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseCodex;
	services.codex.reconcile = async () => new Promise((resolve) => { releaseCodex = () => resolve({ valid: [], invalid: [], removed: [] }); });
	const router = new ProviderService(services, { operationTimeoutMs: 5 });
	const reconciling = router.reconcile([
		profile('codex', { agentId: 'codex-a' }),
		profile('gemini', { agentId: 'gemini-a' }),
	]);
	try {
		const outcome = await Promise.race([
			reconciling,
			new Promise((resolve) => setTimeout(() => resolve('still-pending'), 50)),
		]);
		assert.notEqual(outcome, 'still-pending', 'one hung provider cannot hold reconciliation forever');
		assert.deepEqual(outcome.valid.map(({ agentId }) => agentId), ['gemini-a']);
		assert.deepEqual(outcome.invalid.map((entry) => entry.profile.agentId), ['codex-a']);
		assert.equal(outcome.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
	} finally {
		releaseCodex?.();
		await Promise.allSettled([reconciling]);
		await router.stop();
	}
});

test('provider catalog aggregation settles failures and promotes a restored provider live', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let codexAvailable = false;
	services.codex.catalog.refresh = async () => {
		if (!codexAvailable) throw Object.assign(new Error('Codex unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
		return { refreshedAtEpochMs: 2, models: [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex', reasoningEfforts: ['high'], serviceTiers: ['priority'] }] };
	};
	services.gemini.catalog.refresh = async () => ({
		refreshedAtEpochMs: 1,
		models: [{ id: 'gemini-model', model: 'gemini-model', displayName: 'Gemini', reasoningEfforts: ['high'], serviceTiers: [] }],
	});
	services.kimi.catalog.refresh = async () => ({ refreshedAtEpochMs: 1, models: [] });
	const router = new ProviderService(services, { operationTimeoutMs: 10 });

	const degraded = await router.catalog.refresh({ providers: ['codex', 'gemini'] });
	assert.deepEqual(degraded.models.map(({ provider, id }) => [provider, id]), [['gemini', 'gemini-model']]);
	assert.equal(degraded.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');

	codexAvailable = true;
	const restored = await router.catalog.refresh({ providers: ['codex', 'gemini'], force: true });
	assert.deepEqual(restored.models.map(({ provider, id }) => [provider, id]), [
		['codex', 'codex-model'], ['gemini', 'gemini-model'],
	]);
	assert.equal(restored.recovery.find(({ provider }) => provider === 'codex').state, 'live');

	services.codex.catalog.refresh = async () => ({
		refreshedAtEpochMs: 3,
		models: [{ id: 'codex-model', model: 'codex-model' }],
	});
	const retained = await router.catalog.refresh({ providers: ['codex', 'gemini'], force: true });
	assert.deepEqual(retained.models.map(({ provider, id }) => [provider, id]), [
		['codex', 'codex-model'], ['gemini', 'gemini-model'],
	]);
	assert.equal(retained.source, 'last_valid');
	assert.equal(retained.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
	await router.stop();
});
