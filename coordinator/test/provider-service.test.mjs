import assert from 'node:assert/strict';
import test from 'node:test';

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

test('provider router defaults legacy profiles to Codex and isolates each backend', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const router = new ProviderService(services);
	assert.equal((await router.createAgent({ agentId: 'legacy' })).provider, 'codex');
	assert.equal((await router.createAgent({ agentId: 'g', provider: 'gemini' })).provider, 'gemini');
	assert.equal((await router.createAgent({ agentId: 'k', provider: 'kimi' })).provider, 'kimi');
	assert.deepEqual(services.codex.created.map((profile) => profile.provider), ['codex']);
	assert.equal(router.getAgent('g').provider, 'gemini');
	await router.removeAgent('k');
	assert.deepEqual(services.kimi.removed, ['k']);
	await router.stop();
	assert.equal(services.gemini.stopped, true);
});

test('provider reconciliation groups profiles and preserves an unavailable provider as an invalid subset', async () => {
	const codex = new FakeService('codex');
	const gemini = new FakeService('gemini');
	const kimi = new FakeService('kimi');
	gemini.reconcile = async (records) => ({ valid: [], invalid: records.map((record) => ({ profile: record, code: 'PROVIDER_UNAVAILABLE', message: 'login rejected' })), removed: [], catalog: { provider: 'gemini', models: [] } });
	const router = new ProviderService({ codex, gemini, kimi });
	const result = await router.reconcile([
		{ agentId: 'c', provider: 'codex' }, { agentId: 'g', provider: 'gemini' }, { agentId: 'k', provider: 'kimi' },
	]);
	assert.deepEqual(result.valid.map((record) => record.agentId), ['c', 'k']);
	assert.deepEqual(result.invalid.map((entry) => entry.profile.agentId), ['g']);
});

test('combined catalog refreshes independent provider CLIs concurrently', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
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
	assert.deepEqual(started, ['codex', 'gemini', 'kimi']);
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
			models: [{ id: `${provider}-model`, model: `${provider}-model` }],
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
