import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { normalizeDynamicConfig } from '../src/dynamic-main.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProviderService } from '../src/provider-service.mjs';
import { GoalSpecTranslator } from '../src/goal-spec-translator.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';
import { SOURCE, createDynamicCoordinator, FakeBridge, FakeProvider, FakePlanner, record, eventually, ManualTimerQueue, start } from './fixtures/dynamic-main-fixture.mjs';

const CODEX_MISSING = "Codex CLI is not installed on the server machine (no 'codex' found on PATH). Install it, sign in with 'codex login', then restart Minecraft and relaunch this agent. This agent cannot think until that is fixed.";
function missingHealth(provider, message) {
	return { provider, status: 'missing', code: 'PROVIDER_CLI_MISSING', message, executable: provider, version: null, checkedAtEpochMs: 1, details: '' };
}

test('coordinator binds the Minecraft bridge without eagerly starting a provider', async () => {
	const bridge = new FakeBridge();
	let providerStarts = 0;
	let releaseProvider;
	const provider = new FakeProvider();
	provider.start = async () => {
		providerStarts += 1;
		await new Promise((resolve) => { releaseProvider = resolve; });
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, codexService: provider },
	);

	const starting = coordinator.start();
	await new Promise((resolve) => setImmediate(resolve));
	try {
		assert.equal(bridge.ready, true);
		assert.equal(providerStarts, 0, 'provider startup is lazy and cannot delay bridge readiness');
	} finally {
		releaseProvider?.();
		await Promise.allSettled([starting]);
		await coordinator.stop();
	}
});

test('concurrent coordinator stop callers await the same provider cleanup', async () => {
	const bridge = new FakeBridge();
	const provider = new FakeProvider();
	let releaseStop;
	const stopGate = new Promise((resolve) => { releaseStop = resolve; });
	let stopEntered = false;
	provider.stop = async () => {
		stopEntered = true;
		await stopGate;
	};
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, codexService: provider },
	);
	await coordinator.start();

	const first = coordinator.stop();
	await eventually(() => stopEntered);
	let secondResolved = false;
	const second = coordinator.stop().then(() => { secondResolved = true; });
	const sharedStopPromise = coordinator.stop() === first;
	await new Promise((resolve) => setImmediate(resolve));
	const resolvedBeforeCleanup = secondResolved;
	releaseStop();
	await Promise.all([first, second]);

	assert.equal(resolvedBeforeCleanup, false);
	assert.equal(sharedStopPromise, true);
});

test('an empty ready roster does not initialize providers through bootstrap catalog discovery', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const starts = [];
	const services = Object.fromEntries(['codex', 'gemini', 'claude'].map((provider) => [provider, {
		catalog: { stale: false, refresh: async () => ({ refreshedAtEpochMs: 1, models: [] }), assertSupported() {} },
		async start() { starts.push(provider); },
		async stop() {},
		async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: { models: [] } }; },
		getAgent() { return null; },
		async removeAgent() { return false; },
	}]));
	const provider = new ProviderService(services);
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, registry, planner, codexService: provider },
	);
	await coordinator.start();
	try {
		let startsAtBootstrapSnapshot = null;
		const send = bridge.send.bind(bridge);
		bridge.send = async (type, ...rest) => {
			if (type === 'catalog_snapshot' && startsAtBootstrapSnapshot === null) startsAtBootstrapSnapshot = [...starts];
			return send(type, ...rest);
		};
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'empty', registry: [] });
		await eventually(() => startsAtBootstrapSnapshot !== null);
		assert.deepEqual(startsAtBootstrapSnapshot, []);
	} finally {
		await coordinator.stop();
	}
});

test('a roster saved under one provider still publishes every provider to the summon menu', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const model = (provider, id) => ({ provider, id, model: id, displayName: id, reasoningEfforts: ['low'], serviceTiers: [] });
	const provider = new FakeProvider();
	provider.bootstrapCatalog = async () => ({ refreshedAtEpochMs: 1, models: [model('claude', 'claude-opus-5-5')] });
	provider.catalog.refresh = async () => ({
		refreshedAtEpochMs: 2,
		models: [model('codex', 'gpt-6-luna'), model('gemini', 'gemini-3.1-pro'), model('claude', 'claude-opus-5-5')],
	});
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script' } },
		{ bridge, registry, planner, codexService: provider },
	);
	await coordinator.start();
	try {
		bridge.emit('ready', { connectionEpoch: 1, serverInstanceId: 'claude-only', registry: [] });
		await eventually(() => bridge.sent.some(({ type, payload }) => type === 'catalog_snapshot' && payload.refreshedAtEpochMs === 2));
		const latest = bridge.sent.filter(({ type }) => type === 'catalog_snapshot').at(-1);
		assert.deepEqual([...new Set(latest.payload.models.map(({ provider }) => provider))], ['codex', 'gemini', 'claude']);
	} finally {
		await coordinator.stop();
	}
});

test('goal translation is isolated, coalesced, acknowledged, and does not change lifecycle state', async () => {
	const run = await start();
	const request = {
		agentId: 'agent-a',
		payload: { requestId: '00000000-0000-0000-0000-000000000101', originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'] },
	};
	try {
		run.bridge.emit('goal_spec_request', request);
		run.bridge.emit('goal_spec_request', structuredClone(request));
		await eventually(() => run.bridge.sent.some((message) => message.type === 'goal_spec_proposal'));
		assert.equal(run.planner.goalSpecRequests.length, 1);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.IDLE);
		assert.deepEqual(run.bridge.sent.find((message) => message.type === 'goal_spec_proposal').payload, {
			requestId: request.payload.requestId,
			summary: 'Obtain an iron pickaxe.',
			predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		});
		run.bridge.emit('goal_spec_result', { agentId: 'agent-a', payload: { requestId: request.payload.requestId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' } });
		await new Promise((resolve) => setImmediate(resolve));
	} finally {
		await run.coordinator.stop();
	}
});

test('goal translation stays charged against lifecycle capacity until a terminal result', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const run = await start({
		registry,
		planner,
		connectionOperationCap: 2,
		agentOperationCap: 1,
		goalSpecRequestCap: 3,
		initialRegistry: [record('agent-a'), record('agent-b'), record('agent-c')],
	});
	const completions = [];
	const request = (agentId, requestId) => ({
		agentId,
		payload: { requestId, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] },
		waitUntil: (operation) => completions.push(Promise.resolve(operation)),
	});
	const firstId = '00000000-0000-4000-8000-000000000111';
	const secondId = '00000000-0000-4000-8000-000000000112';
	const thirdId = '00000000-0000-4000-8000-000000000113';
	try {
		run.bridge.emit('goal_spec_request', request('agent-a', firstId));
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === firstId));
		let firstSettled = false;
		void completions[0].then(() => { firstSettled = true; });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(firstSettled, false, 'initial processing remains attached until the request is terminal');
		assert.throws(
			() => run.bridge.emit('goal_spec_request', request('agent-a', '00000000-0000-4000-8000-000000000114')),
			(error) => error.code === 'AGENT_INBOUND_BACKPRESSURE',
		);
		run.bridge.emit('goal_spec_request', request('agent-b', secondId));
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === secondId));
		assert.throws(
			() => run.bridge.emit('goal_spec_request', request('agent-c', thirdId)),
			(error) => error.code === 'CONNECTION_INBOUND_BACKPRESSURE',
		);
		run.bridge.emit('goal_spec_result', { agentId: 'agent-a', payload: { requestId: firstId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' } });
		run.bridge.emit('goal_spec_result', { agentId: 'agent-b', payload: { requestId: secondId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' } });
		await eventually(() => firstSettled);
		run.bridge.emit('goal_spec_request', request('agent-c', thirdId));
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === thirdId));
		run.bridge.emit('goal_spec_result', { agentId: 'agent-c', payload: { requestId: thirdId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' } });
	} finally {
		await run.coordinator.stop();
	}
});

test('goal translation request retention has an independent hard cap', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const run = await start({
		registry,
		planner,
		connectionOperationCap: 3,
		agentOperationCap: 3,
		goalSpecRequestCap: 1,
		initialRegistry: [record('agent-a'), record('agent-b')],
	});
	const firstId = '00000000-0000-4000-8000-000000000115';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a', payload: { requestId: firstId, originalRequest: 'Get iron', candidateIds: ['minecraft:iron_ingot'] },
		});
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === firstId));
		assert.throws(
			() => run.bridge.emit('goal_spec_request', {
				agentId: 'agent-b', payload: { requestId: '00000000-0000-4000-8000-000000000116', originalRequest: 'Get gold', candidateIds: ['minecraft:gold_ingot'] },
			}),
			(error) => error.code === 'GOAL_SPEC_REQUEST_BACKPRESSURE',
		);
		run.bridge.emit('goal_spec_result', { agentId: 'agent-a', payload: { requestId: firstId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' } });
	} finally {
		await run.coordinator.stop();
	}
});

test('a compiled dragon goal starts through the constrained fallback when optional Luna advice fails', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let calls = 0;
	planner.requestGoalSpec = async () => {
		calls += 1;
		throw Object.assign(new Error('Luna unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
	};
	const run = await start({ registry, planner });
	const requestId = '00000000-0000-4000-8000-000000000199';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a',
			payload: { requestId, originalRequest: 'Beat the game', candidateIds: ['minecraft:ender_dragon'] },
		});
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === requestId));
		assert.equal(calls, 1);
		assert.deepEqual(run.bridge.sent.find(({ type, payload }) => type === 'goal_spec_proposal' && payload.requestId === requestId).payload.predicate,
			{ type: 'entity_killed_by_agent', entityType: 'minecraft:ender_dragon', afterGoalStart: true });
		run.bridge.emit('goal_spec_result', {
			agentId: 'agent-a', payload: { requestId, status: 'accepted', reasonCode: 'PROPOSAL_ACTIVATED' },
		});
	} finally {
		await run.coordinator.stop();
	}
});

test('goal translation retries provider failure and retransmits until Minecraft acknowledges it', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let attempts = 0;
	planner.requestGoalSpec = async (request) => {
		planner.goalSpecRequests.push(request);
		attempts += 1;
		if (attempts === 1) throw Object.assign(new Error('temporary provider outage'), { code: 'PROVIDER_UNAVAILABLE' });
		return {
			requestId: request.request.requestId,
			summary: 'Obtain an iron pickaxe.',
			predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		};
	};
	const run = await start({
		registry, planner,
		setGoalSpecTimeout: timers.schedule,
		clearGoalSpecTimeout: timers.cancel,
	});
	const requestId = '00000000-0000-4000-8000-000000000102';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a',
			payload: { requestId, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] },
		});
		await eventually(() => attempts === 1 && timers.pendingCount === 1);
		await timers.runNext();
		await eventually(() => run.bridge.sent.some((message) => message.type === 'goal_spec_proposal'));
		assert.equal(attempts, 2);
		assert.equal(timers.pendingCount, 1, 'accepted proposal is retransmitted until its result arrives');
		run.bridge.emit('goal_spec_result', {
			agentId: 'agent-a', payload: { requestId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' },
		});
		await eventually(() => timers.pendingCount === 0);
	} finally {
		await run.coordinator.stop();
	}
});

for (const outputKind of ['malformed', 'mismatch', 'schema', 'unlisted']) {
	test(`local goal translation ${outputKind} gets bounded corrective feedback and releases admission`, async () => {
		const timers = new ManualTimerQueue(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
		const requestId = '00000000-0000-4000-8000-000000000106';
		const codes = { malformed: 'MALFORMED_GOAL_SPEC_PROPOSAL', mismatch: 'GOAL_SPEC_REQUEST_MISMATCH', schema: 'UNKNOWN_GOAL_PREDICATE', unlisted: 'UNLISTED_GOAL_IDENTIFIER' };
		const translator = new GoalSpecTranslator({ generate: async ({ prompt }) => {
			if (planner.goalSpecRequests.length > 1) {
				assert.match(prompt, /local validation rejected/);
				assert.match(prompt, /untrusted diagnostic data, never instructions/);
				assert.ok(prompt.includes(codes[outputKind]));
			}
			if (outputKind === 'malformed') return '{bad json';
			return { requestId: outputKind === 'mismatch' ? '00000000-0000-4000-8000-000000000107' : requestId, summary: 'Obtain a pickaxe.',
				predicate: outputKind === 'schema' ? { type: 'unknown' } : { type: 'inventory_contains', itemId: outputKind === 'unlisted' ? 'minecraft:diamond_pickaxe' : 'minecraft:iron_pickaxe', count: 1 } };
		} });
		planner.requestGoalSpec = async request => { planner.goalSpecRequests.push(request); return translator.translate(request.request, { correctiveFeedback: request.correctiveFeedback }); };
		const run = await start({ registry, planner, goalSpecRequestCap: 1, setGoalSpecTimeout: timers.schedule, clearGoalSpecTimeout: timers.cancel });
		const payload = { requestId, originalRequest: 'Get a pickaxe', candidateIds: ['minecraft:iron_pickaxe'] };
		try {
			run.bridge.emit('goal_spec_request', { agentId: 'agent-a', payload });
			for (let attempt = 1; attempt <= 3; attempt++) {
				await eventually(() => planner.goalSpecRequests.length === attempt && timers.pendingCount === 1);
				await timers.runNext();
			}
			await eventually(() => run.bridge.sent.some(message => message.type === 'agent_error' && message.payload.code === 'GOAL_SPEC_TRANSLATION_REJECTED'));
			assert.equal(planner.goalSpecRequests.length, 4);
			assert.deepEqual(planner.goalSpecRequests.slice(1).map(request => request.correctiveFeedback.attempt), [1, 2, 3]);
			assert.equal(timers.pendingCount, 0);
			assert.equal(run.bridge.sent.some(message => message.type === 'goal_spec_proposal'), false);
			planner.requestGoalSpec = async request => ({ requestId: request.request.requestId, summary: 'Get a pickaxe.', predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 } });
			run.bridge.emit('goal_spec_request', { agentId: 'agent-a', payload: { ...payload, requestId: '00000000-0000-4000-8000-000000000108' } });
			await eventually(() => run.bridge.sent.some(message => message.type === 'goal_spec_proposal'));
		} finally { await run.coordinator.stop(); }
	});
}

test('Minecraft rejection keeps a current goal draft alive and retries with bounded corrective feedback', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestGoalSpec = async (request) => {
		planner.goalSpecRequests.push(request);
		return {
			requestId: request.request.requestId,
			summary: `Attempt ${planner.goalSpecRequests.length}`,
			predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: planner.goalSpecRequests.length },
		};
	};
	const run = await start({
		registry, planner,
		setGoalSpecTimeout: timers.schedule,
		clearGoalSpecTimeout: timers.cancel,
	});
	const requestId = '00000000-0000-4000-8000-000000000104';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a',
			payload: { requestId, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] },
		});
		await eventually(() => run.bridge.sent.some((message) => message.type === 'goal_spec_proposal'));
		const rejectedProposal = structuredClone(run.bridge.sent.find((message) => message.type === 'goal_spec_proposal').payload);
		run.bridge.emit('goal_spec_result', {
			agentId: 'agent-a', payload: { requestId, status: 'rejected', reasonCode: 'INVALID_GOAL_PREDICATE' },
		});
		await eventually(() => timers.pendingCount === 1);
		await timers.runNext();
		await eventually(() => planner.goalSpecRequests.length === 2);
		assert.deepEqual(planner.goalSpecRequests[1].correctiveFeedback, {
			attempt: 1,
			reasonCode: 'INVALID_GOAL_PREDICATE',
			rejectedProposal,
		});
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'goal_spec_proposal').length === 2);
		run.bridge.emit('goal_spec_result', {
			agentId: 'agent-a', payload: { requestId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' },
		});
		await eventually(() => timers.pendingCount === 0);
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('repeated Minecraft proposal rejection ends with an explicit operator-visible terminal report', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const run = await start({
		registry, planner,
		setGoalSpecTimeout: timers.schedule,
		clearGoalSpecTimeout: timers.cancel,
	});
	const requestId = '00000000-0000-4000-8000-000000000105';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a',
			payload: { requestId, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] },
		});
		for (let rejection = 1; rejection <= 4; rejection += 1) {
			await eventually(() => run.bridge.sent.filter((message) => message.type === 'goal_spec_proposal').length === rejection);
			run.bridge.emit('goal_spec_result', {
				agentId: 'agent-a', payload: { requestId, status: 'rejected', reasonCode: 'INVALID_GOAL_PREDICATE' },
			});
			if (rejection <= 3) {
				await eventually(() => timers.pendingCount === 1);
				await timers.runNext();
			}
		}
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_error'));
		const report = run.bridge.sent.find((message) => message.type === 'agent_error');
		assert.equal(report.payload.code, 'GOAL_SPEC_TRANSLATION_REJECTED');
		assert.match(report.payload.message, /pending draft requires operator correction or cancellation/i);
		assert.equal(timers.pendingCount, 0);
	} finally {
		await run.coordinator.stop();
	}
});

test('replacing a goal cancels stale goal translation and suppresses its late proposal', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let release;
	planner.requestGoalSpec = async (request) => {
		planner.goalSpecRequests.push(request);
		await new Promise((resolve) => { release = resolve; });
		return {
			requestId: request.request.requestId,
			summary: 'Obtain an iron pickaxe.',
			predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		};
	};
	const run = await start({ registry, planner });
	const requestId = '00000000-0000-4000-8000-000000000103';
	try {
		run.bridge.emit('goal_spec_request', {
			agentId: 'agent-a',
			payload: { requestId, originalRequest: 'Get a good pickaxe', candidateIds: ['minecraft:iron_pickaxe'] },
		});
		await eventually(() => planner.goalSpecRequests.length === 1);
		run.bridge.emit('goal_control', {
			agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Get stone' },
		});
		await eventually(() => planner.goalSpecCancellations.some((entry) => entry.requestId === requestId));
		release();
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.some((message) => message.type === 'goal_spec_proposal' && message.payload.requestId === requestId), false);
	} finally {
		release?.();
		await run.coordinator.stop();
	}
});

test('normalizes fixed and adaptive planning modes with production bounds', () => {
	const base = { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: {} };
	assert.equal(normalizeDynamicConfig({ ...base, limits: { agentCap: 16, planningConcurrency: 8 } }).limits.planningMode, 'fixed');
	assert.equal(normalizeDynamicConfig({ ...base, limits: { agentCap: 16, planningConcurrency: 4, planningMode: 'adaptive' } }).limits.planningMode, 'adaptive');
	assert.throws(() => normalizeDynamicConfig({ ...base, limits: { agentCap: 16, planningConcurrency: 3, planningMode: 'adaptive' } }), /adaptive planningConcurrency/);
	assert.throws(() => normalizeDynamicConfig({ ...base, limits: { agentCap: 16, planningConcurrency: 17, planningMode: 'adaptive' } }), /adaptive planningConcurrency/);
});

test('dynamic config migrates legacy input and rejects unknown or future schema keys', () => {
	const legacy = {
		bridge: { port: 25570, secret: 's'.repeat(32) },
		codex: {},
		cursor: { serviceTiers: ['priority', 'fast'] },
		kimi: { models: ['kimi-code/k3'] },
	};
	const migrated = normalizeDynamicConfig(legacy);
	assert.equal(migrated.schemaVersion, 1);
	assert.equal(Object.hasOwn(migrated, 'cursor'), false, 'retired Cursor config is dropped');
	assert.equal(Object.hasOwn(migrated, 'kimi'), false, 'retired Kimi config is dropped');
	assert.equal(Object.hasOwn(normalizeDynamicConfig({ ...legacy, schemaVersion: 1 }), 'cursor'), false);
	assert.throws(() => normalizeDynamicConfig({ ...legacy, schemaVersion: 2 }), /schemaVersion 2/);
	assert.throws(() => normalizeDynamicConfig({ ...legacy, misspelledLimit: 1 }), /config\.misspelledLimit/);
	assert.throws(() => normalizeDynamicConfig({ ...legacy, bridge: { ...legacy.bridge, reconnectDelay: 5 } }), /bridge\.reconnectDelay/);
	assert.throws(() => normalizeDynamicConfig({
		...legacy,
		schemaVersion: 1,
		claude: { serviceTiers: ['priority'] },
	}), /claude\.serviceTiers/);
});

test('verbose feed never publishes raw provider chunks', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('output', 'Provider error: verbose mode is on');
		request.onVerbose('provider', 'Provider response received.');
		return withCompletionContract({ summary: 'Keep watch.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep watch.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const feed = run.bridge.sent.filter(({ type }) => type === 'verbose_event');
		assert.equal(feed.some(({ payload }) => payload.message.includes('Provider error: verbose mode is on')), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('repeated inbound action progress never becomes verbose player chat', async () => {
	const run = await start();
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const actionId = run.bridge.sent.find(({ type }) => type === 'action_command').payload.actionId;
		const progress = { goalRevision: 1, actionId, state: 'RUNNING', eventSequence: 2 };
		run.bridge.emit('action_progress', { agentId: 'agent-a', payload: progress });
		run.bridge.emit('action_progress', { agentId: 'agent-a', payload: progress });
		await new Promise((resolve) => setImmediate(resolve));
		const feed = run.bridge.sent.filter(({ type }) => type === 'verbose_event');
		assert.equal(feed.some(({ payload }) => ['action', 'progress', 'result'].includes(payload.stage)), false);
		assert.equal(feed.some(({ payload }) => payload.message.includes(actionId)), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose feed publishes the parsed plan summary once', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('decision', '{"directive":"replace","summary":"raw planner JSON"}');
		return withCompletionContract({ summary: 'Move to the safe ledge.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Move safely.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const decisions = run.bridge.sent
			.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision')
			.map(({ payload }) => payload.message);
		assert.deepEqual(decisions, ['Move to the safe ledge.']);
	} finally {
		await run.coordinator.stop();
	}
});

test('curated verbose boundaries preserve natural summaries while removing untrusted identifiers and retry prose', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('agent_message', 'I will hold position. actionId=native-action-123e4567-e89b-12d3-a456-426614174000 traceId=trace-123e4567-e89b-12d3-a456-426614174000');
		request.onVerbose('retry', 'Provider error: verbose mode is on. callId=call-123e4567-e89b-12d3-a456-426614174000');
		return withCompletionContract({
			summary: 'Hold position while watching the entrance. tool call dispatch actionId=native-action-123e4567-e89b-12d3-a456-426614174000 uuid=123e4567-e89b-12d3-a456-426614174000 password=hunter2 diagnostic trace.',
			directive: 'replace', source: SOURCE,
		}, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Hold position.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const feed = run.bridge.sent.filter(({ type }) => type === 'verbose_event');
		assert.deepEqual(feed.filter(({ payload }) => payload.stage === 'output').map(({ payload }) => payload.message), []);
		assert.deepEqual(feed.filter(({ payload }) => payload.stage === 'decision').map(({ payload }) => payload.message), ['Hold position while watching the entrance.']);
		assert.deepEqual(feed.filter(({ payload }) => payload.stage === 'retry'), []);
		assert.equal(feed.every(({ payload }) => payload.message.length <= 256), true);
		assert.doesNotMatch(JSON.stringify(feed), /123e4567-e89b-12d3-a456-426614174000|actionId|callId|tool call|diagnostic|hunter2|verbose mode is on/i);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose decisions reject actual identifier and tool-execution formats', async () => {
	for (const probe of [
		'00000000-0000-0000-0000-000000000000',
		'native:agent-a:1:7',
		'action-progress-1',
		'call_abc123',
		'Calling move_to with x=1',
		'agent-a:1:1:1:program-1-1:1:1:arena-state-1',
		'program-1-1:1:1:arena-state-1',
		'agent-a:1:1:1:program-1-1:1:1:step-1',
		'program-1-1:1:1:step-1',
		'trace-agent-a-1-1-initial',
	]) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			return withCompletionContract({ summary: `Proceed safely. ${probe}`, directive: 'replace', source: SOURCE }, request.goalRevision);
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Proceed safely.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
			const decisions = run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message);
			assert.deepEqual(decisions, ['Proceed safely.']);
			assert.doesNotMatch(JSON.stringify(decisions), new RegExp(probe.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('verbose decisions redact standalone provider credentials', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return withCompletionContract({
			summary: 'Credentials sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 and AIzaabcdefghijklmnopqrstuvwxyz0123456789 must stay hidden.',
			directive: 'replace',
			source: SOURCE,
		}, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message),
			['Credentials [REDACTED_KEY] and [REDACTED_KEY] must stay hidden.'],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose summaries preserve ordinary action and call prose while rejecting only structural tool syntax', async () => {
	for (const summary of [
		'Take an action-oriented approach and wait.',
		'Make a call-back plan before nightfall.',
		'Calling Lucas with a question is appropriate.',
	]) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			return withCompletionContract({ summary, directive: 'replace', source: SOURCE }, request.goalRevision);
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
			await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
			assert.deepEqual(run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message), [summary]);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('verbose boundary caps huge raw summaries before sanitizing and blocks a crossing trace marker', async () => {
	const prefix = `Safe route ${'x'.repeat(242)} `;
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return withCompletionContract({ summary: `${prefix}trace-agent-a-1-1-initial${'z'.repeat(1_000_000)}`, directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const decision = run.bridge.sent.find(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').payload.message;
		assert.equal(decision, 'Plan accepted.');
		assert.equal(decision.length <= 256, true);
		assert.doesNotMatch(decision, /trace-agent-a-1-1-initial/i);
	} finally {
		await run.coordinator.stop();
	}
});

test('native completed agent messages reject ArenaScript program identities', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		request.onVerbose('agent_message', 'Proceed safely. agent-a:1:1:1:program-1-1:1:1:arena-state-1');
		return { status: 'completed', toolCalls: 0 };
	};
	const run = await start({
		registry,
		planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
	});
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['decision', 'output'].includes(payload.stage)).map(({ payload }) => ({ stage: payload.stage, message: payload.message })),
			[{ stage: 'decision', message: 'Proceed safely.' }],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('the latency chain marks event ready and input built, and provider stage events reach the trace but never the chat', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		request.onVerbose('provider_event', JSON.stringify({ event: 'native_provider_turn_sent', turnId: '1:1' }));
		request.onVerbose('provider_event', JSON.stringify({ event: 'not_a_provider_stage', turnId: '1:1' }));
		request.onVerbose('provider_event', 'not json');
		return { status: 'completed', toolCalls: 0 };
	};
	const traces = [];
	const run = await start({
		registry,
		planner,
		traceWriter: { write: (event, fields) => { traces.push({ event, ...fields }); } },
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
	});
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => planner.requests.length === 1);
		await eventually(() => traces.some((trace) => trace.event === 'native_turn_completed'));
		const ready = traces.find((trace) => trace.event === 'native_event_ready');
		const built = traces.find((trace) => trace.event === 'native_input_built');
		const sent = traces.find((trace) => trace.event === 'native_provider_turn_sent');
		assert.equal(ready.mode, 'turn');
		assert.equal(ready.eventName, 'observation');
		assert.equal(ready.agentId, 'agent-a');
		assert.equal(built.traceId, ready.traceId);
		assert.ok(built.inputBytes > 0 && built.buildMs >= 0);
		assert.equal(sent.agentId, 'agent-a');
		assert.equal(sent.turnId, '1:1');
		assert.equal(traces.some((trace) => trace.event === 'not_a_provider_stage'), false);
		assert.ok(traces.indexOf(ready) < traces.indexOf(built) && traces.indexOf(built) < traces.indexOf(sent));
		assert.equal(run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'provider_event'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose raw cap never publishes a partial identifier after whitespace normalization', async () => {
	const probes = [
		`Safe ${' '.repeat(995)}00000000-0000-0000-0000-000000000000`,
		`Safe ${' '.repeat(1_014)}action-progress-12345`,
		`Safe ${' '.repeat(1_014)}native:agent-a:1:7`,
		`Safe ${' '.repeat(1_014)}call_abc123`,
	];
	for (const summary of probes) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			return withCompletionContract({ summary, directive: 'replace', source: SOURCE }, request.goalRevision);
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Proceed safely.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
			assert.deepEqual(
				run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message),
				['Plan accepted.'],
			);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('verbose raw cap never publishes a partial structural tool clause', async () => {
	for (const padding of [998, 999, 1_000]) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			return withCompletionContract({
				summary: `Safe ${' '.repeat(padding)}Calling move_to with x=1`,
				directive: 'replace', source: SOURCE,
			}, request.goalRevision);
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Proceed safely.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
			assert.deepEqual(
				run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message),
				['Plan accepted.'],
			);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('native verbose raw cap suppresses partial structural tool clauses', async () => {
	for (const padding of [998, 999, 1_000]) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
		planner.requestNativeTurn = async (request) => {
			planner.requests.push(request);
			request.onVerbose('agent_message', `Safe ${' '.repeat(padding)}Calling move_to with x=1`);
			return { status: 'completed', toolCalls: 0 };
		};
		const run = await start({
			registry,
			planner,
			config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
		});
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Proceed safely.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => planner.requests.length === 1);
			assert.deepEqual(
				run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['decision', 'output'].includes(payload.stage)).map(({ payload }) => payload.message),
				[],
			);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('verbose raw cap retains complete safe sentences before a truncated clause', async () => {
	const safeSentence = 'I will gather wood before searching for iron.';
	const summary = `${safeSentence} ${' '.repeat(1_000)}Calling move_to with x=1`;
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return withCompletionContract({ summary, directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Find iron.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message),
			[safeSentence],
		);
	} finally {
		await run.coordinator.stop();
	}

	const nativeRegistry = new AgentRegistry();
	const nativePlanner = new FakePlanner(nativeRegistry);
	nativePlanner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	nativePlanner.requestNativeTurn = async (request) => {
		nativePlanner.requests.push(request);
		request.onVerbose('agent_message', summary);
		return { status: 'completed', toolCalls: 0 };
	};
	const nativeRun = await start({
		registry: nativeRegistry,
		planner: nativePlanner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
	});
	try {
		nativeRun.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		nativeRun.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Find iron.' } });
		nativeRun.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => nativePlanner.requests.length === 1);
		assert.deepEqual(
			nativeRun.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message),
			[safeSentence],
		);
	} finally {
		await nativeRun.coordinator.stop();
	}
});

test('verbose boundaries scan identifiers that cross the 256-character public limit', async () => {
	for (const probe of [
		'00000000-0000-0000-0000-000000000000',
		'actionId=native:agent-a:1:7',
		'call_abc123',
	]) {
		const prefix = `Safe route ${'x'.repeat(242)} `;
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			return withCompletionContract({ summary: `${prefix}${probe}`, directive: 'replace', source: SOURCE }, request.goalRevision);
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Proceed safely.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
			const decision = run.bridge.sent.find(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').payload.message;
			assert.equal(decision, prefix.trim());
			assert.equal(decision.length <= 256, true);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('native turns alone may publish one safe agent message and decision summaries preserve brackets or fall back safely', async () => {
	const nativeRegistry = new AgentRegistry();
	const nativePlanner = new FakePlanner(nativeRegistry);
	nativePlanner.requestPlan = async () => assert.fail('native control must not use ArenaScript planning');
	nativePlanner.requestNativeTurn = async (request) => {
		nativePlanner.requests.push(request);
		request.onVerbose('agent_message', 'Use [the east entrance] and wait.');
		return { status: 'completed', toolCalls: 0 };
	};
	const nativeRun = await start({
		registry: nativeRegistry,
		planner: nativePlanner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } } },
	});
	try {
		nativeRun.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		nativeRun.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		nativeRun.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => nativePlanner.requests.length === 1);
		assert.deepEqual(
			nativeRun.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['decision', 'output'].includes(payload.stage)).map(({ payload }) => ({ stage: payload.stage, message: payload.message })),
			[{ stage: 'decision', message: 'Use [the east entrance] and wait.' }],
		);
	} finally {
		await nativeRun.coordinator.stop();
	}

	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		return withCompletionContract({ summary: '{"tool":"move_to","arguments":{"x":1}}', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.deepEqual(run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision').map(({ payload }) => payload.message), ['Plan accepted.']);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose recovery keeps authentication and planning infrastructure out of Error', async () => {
	for (const [code, expected] of [
		['AUTHENTICATION_REQUIRED', 'Provider access is unavailable; retrying automatically from fresh state.'],
		['PLANNING_TIMEOUT', 'Provider work failed; recovering automatically from fresh state.'],
	]) {
		const registry = new AgentRegistry();
		const planner = new FakePlanner(registry);
		planner.requestPlan = async (request) => {
			planner.requests.push(request);
			throw Object.assign(new Error(`private ${code} diagnostic`), { code });
		};
		const run = await start({ registry, planner });
		try {
			run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
			run.bridge.emit('observation', { agentId: 'agent-a', payload: {
				goalRevision: 1, eventSequence: 1,
				observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
			} });
			await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'retry'));
			assert.deepEqual(
				run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'retry').map(({ payload }) => payload.message),
				[expected],
			);
			assert.equal(run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'error'), false);
		} finally {
			await run.coordinator.stop();
		}
	}
});

test('retryable provider failures publish one canonical Problem and no Error', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		throw Object.assign(new Error('private timeout diagnostics'), { code: 'REQUEST_TIMEOUT' });
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'retry'));
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['retry', 'error'].includes(payload.stage)).map(({ payload }) => ({ stage: payload.stage, message: payload.message })),
			[{ stage: 'retry', message: 'Provider output was incomplete; retrying from the next fresh observation.' }],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('quiet lifecycle failures publish neither Problem nor Error', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		throw Object.assign(new Error('private stale-plan diagnostics'), { code: 'STALE_PLAN' });
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => planner.requests.length === 1);
		await new Promise((resolve) => setImmediate(resolve));
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['retry', 'error'].includes(payload.stage)),
			[],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('non-retryable domain failures publish one correctly scoped Error and no Problem', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		throw Object.assign(new Error('private domain diagnostics'), { code: 'INVALID_GOAL_SPEC' });
	};
	const run = await start({ registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'error'));
		assert.deepEqual(
			run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && ['retry', 'error'].includes(payload.stage)).map(({ payload }) => ({ stage: payload.stage, message: payload.message })),
			[{ stage: 'error', message: 'Coordinator error (INVALID_GOAL_SPEC).' }],
		);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose mode defaults off, emits curated revision-bound events, and stops immediately when disabled', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('planner', 'x'.repeat(1_000));
		request.onVerbose('provider', 'Provider response received');
		request.onVerbose('output', 'Visible plan output. password=hunter2 Authorization: Bearer top-secret Basic Zm9vOmJhcg== api-key="key-value"');
		request.onVerbose('error', 'Raw provider stderr private body password=raw-error-secret');
		request.onVerbose('decision', 'Decision accepted');
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ bridge, registry, planner });
	try {
		assert.equal(run.bridge.sent.some(({ type }) => type === 'verbose_event'), false, 'verbose is off by default');
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		await eventually(() => run.registry.get('agent-a')?.goalRevision === 1);
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Please wait.', goalRevision: 1, observedAtEpochMs: 2,
		} });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const command = run.bridge.sent.find(({ type }) => type === 'action_command');
		run.bridge.emit('action_progress', { agentId: 'agent-a', payload: {
			goalRevision: 1, actionId: command.payload.actionId, state: 'RUNNING', eventSequence: 2,
		} });
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
			goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3,
		} });
		await eventually(() => run.bridge.sent.some(({ type, payload }) => type === 'verbose_event' && payload.stage === 'decision'));
		const verbose = run.bridge.sent.filter(({ type }) => type === 'verbose_event');
		assert.equal(verbose.every(({ agentId, payload }) => agentId === 'agent-a' && payload.goalRevision === 1 && payload.message.length <= 256), true);
		assert.deepEqual(new Set(verbose.map(({ payload }) => payload.stage)), new Set(['conversation', 'lifecycle', 'decision']));
		assert.deepEqual(verbose.filter(({ payload }) => payload.stage === 'decision').map(({ payload }) => payload.message), ['Wait.']);
		assert.doesNotMatch(JSON.stringify(verbose), /hunter2|top-secret|Zm9vOmJhcg|key-value|private body|raw-error-secret|stderr/i);

		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: false } });
		const disabledAt = verbose.length;
		run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
			sequence: 2, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Still there?', goalRevision: 1, observedAtEpochMs: 3,
		} });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'verbose_event').length, disabledAt);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose send failures never break planning or action delivery', async () => {
	const bridge = new FakeBridge();
	const originalSend = bridge.send.bind(bridge);
	bridge.send = async (type, agentId, payload) => {
		if (type === 'verbose_event') throw new Error('verbose transport unavailable');
		return originalSend(type, agentId, payload);
	};
	const run = await start({ bridge });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose feed drops provider chunks containing split credentials', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('output', 'Visible head. Authori');
		request.onVerbose('output', 'zation: Bear');
		request.onVerbose('output', 'er split-bearer pass');
		request.onVerbose('output', `word=split-password ${'v'.repeat(300)} visible tail.`);
		request.onVerbose('decision', 'Decision accepted');
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ bridge, registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const outputEvents = run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'output');
		assert.deepEqual(outputEvents, []);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose feed drops provider JSON chunks containing credentials', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('output', 'Visible JSON provider output. {"to');
		request.onVerbose('output', 'ken":"split-json-secret private-json-value ');
		request.onVerbose('output', `${'private-json-value '.repeat(6)}end-secret"} visible tail.`);
		request.onVerbose('decision', 'Decision accepted');
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ bridge, registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const outputEvents = run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'output');
		assert.deepEqual(outputEvents, []);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose feed drops huge delimiter-free provider output', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async (request) => {
		planner.requests.push(request);
		request.onVerbose('output', 'x'.repeat(100_000));
		request.onVerbose('decision', 'Decision accepted');
		return withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision);
	};
	const run = await start({ bridge, registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const output = run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'output');
		assert.deepEqual(output, []);
	} finally {
		await run.coordinator.stop();
	}
});

test('verbose feed does not publish provider output before the plan completes', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let finishPlan = null;
	planner.requestPlan = (request) => new Promise((resolve) => {
		planner.requests.push(request);
		request.onVerbose('output', 'Visible realtime provider output. '.repeat(4));
		finishPlan = () => {
			request.onVerbose('decision', 'Decision accepted');
			resolve(withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision));
		};
	});
	const run = await start({ bridge, registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => finishPlan !== null);
		await new Promise((resolve) => setImmediate(resolve));
		const emittedBeforeCompletion = run.bridge.sent.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'output');
		const completePlan = finishPlan;
		finishPlan = null;
		completePlan();
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		assert.deepEqual(emittedBeforeCompletion, []);
	} finally {
		if (finishPlan !== null) finishPlan();
		await run.coordinator.stop();
	}
});

test('verbose re-enable does not replay raw provider output', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let finishPlan = null;
	planner.requestPlan = (request) => new Promise((resolve) => {
		planner.requests.push(request);
		request.onVerbose('output', 'stale buffered fragment ');
		finishPlan = () => {
			request.onVerbose('output', 'fresh visible output');
			request.onVerbose('decision', 'Decision accepted');
			resolve(withCompletionContract({ summary: 'Wait.', directive: 'replace', source: SOURCE }, request.goalRevision));
		};
	});
	const run = await start({ bridge, registry, planner });
	try {
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.', updatedAtEpochMs: 1 } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		} });
		await eventually(() => finishPlan !== null);
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: false } });
		run.bridge.emit('verbose_control', { agentId: 'server', payload: { enabled: true } });
		finishPlan();
		await eventually(() => run.bridge.sent.some(({ type }) => type === 'action_command'));
		const visible = run.bridge.sent
			.filter(({ type, payload }) => type === 'verbose_event' && payload.stage === 'output')
			.map(({ payload }) => payload.message).join('');
		assert.equal(visible, '');
	} finally {
		await run.coordinator.stop();
	}
});

test('packaged native configuration admits all sixteen ordinary agent turns in one scheduler wave', async () => {
	const production = JSON.parse(readFileSync(new URL('../config/dynamic-agents.json', import.meta.url), 'utf8'));
	const config = normalizeDynamicConfig(production, { ARENA_AGENT_BRIDGE_SECRET: 's'.repeat(32) });
	const scheduler = new PlanningScheduler({
		maxConcurrent: config.limits.planningConcurrency,
		maxPending: Math.max(0, config.limits.agentCap - config.limits.planningConcurrency),
		planningMode: config.limits.planningMode,
		urgentReserve: config.limits.urgentReserve,
	});
	const releases = [];
	const turns = Array.from({ length: 16 }, (_, index) => scheduler.schedule(`agent-${index + 1}`, () => new Promise((resolve) => releases.push(resolve)), { lane: 'codex', priority: 'ordinary' }));
	try {
		await Promise.resolve();
		assert.equal(scheduler.activeCount, 16);
		assert.equal(scheduler.pendingCount, 0);
	} finally {
		for (const release of releases) release();
		scheduler.close();
		await Promise.allSettled(turns);
	}
});

test('native reconciliation starts an observation-only provider prewarm without publishing planning state', async () => {
	const provider = new FakeProvider();
	const prewarms = [];
	provider.prewarmAgent = async (profileValue, options) => prewarms.push({ profileValue, options });
	const run = await start({
		codexService: provider,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		await eventually(() => prewarms.length === 1);
		assert.equal(prewarms[0].profileValue.agentId, 'agent-a');
		assert.deepEqual(prewarms[0].options, { goalRevision: 0 });
		assert.equal(run.bridge.sent.some((message) => message.type === 'planning_state'), false);
	} finally {
		await run.coordinator.stop();
	}
});

test('native reconciliation does not prewarm an explicitly paused agent', async () => {
	const provider = new FakeProvider();
	const prewarms = [];
	provider.prewarmAgent = async (profileValue, options) => prewarms.push({ profileValue, options });
	const run = await start({
		codexService: provider,
		initialRegistry: [{ ...record(), state: DynamicAgentState.PAUSED, currentGoal: 'Wait for Lucas.', goalRevision: 1 }],
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.deepEqual(prewarms, []);
	} finally {
		await run.coordinator.stop();
	}
});

test('native reconciliation re-arms an unfinished persisted goal', async () => {
	const timers = new ManualTimerQueue();
	const run = await start({
		initialRegistry: [{ ...record(), state: DynamicAgentState.STARTING, currentGoal: 'Keep working.', goalRevision: 1 }],
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		await eventually(() => run.registry.get('agent-a').state === DynamicAgentState.STARTING && timers.pendingCount === 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('a newly registered native agent starts observation-only provider prewarm after becoming ready', async () => {
	const provider = new FakeProvider();
	const prewarms = [];
	provider.prewarmAgent = async (profileValue, options) => prewarms.push({ profileValue, options });
	const run = await start({
		codexService: provider,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		await eventually(() => prewarms.length === 1);
		run.bridge.emit('agent_registered', { agentId: 'agent-b', payload: record('agent-b') });
		await eventually(() => prewarms.length === 2);
		assert.equal(prewarms[1].profileValue.agentId, 'agent-b');
		assert.deepEqual(prewarms[1].options, { goalRevision: 0 });
	} finally {
		await run.coordinator.stop();
	}
});

test('new agent readiness does not wait for a stale catalog refresh', async () => {
	let releaseRefresh;
	const refreshGate = new Promise((resolve) => { releaseRefresh = resolve; });
	const provider = new FakeProvider();
	provider.catalog = {
		stale: true,
		refresh: async () => {
			await refreshGate;
			return { models: [] };
		},
		assertSupported() {},
	};
	const run = await start({ codexService: provider });
	try {
		run.bridge.emit('agent_registered', { agentId: 'agent-b', payload: record('agent-b') });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(
			run.bridge.sent.some((message) => message.type === 'agent_ready' && message.agentId === 'agent-b'),
			true,
			'registration acknowledgement must not share the optional catalog-refresh critical path',
		);
	} finally {
		releaseRefresh();
		await run.coordinator.stop();
	}
});

test('native Codex control dispatches and returns a real body result inside one provider turn', async () => {
	const timers = new ManualTimerQueue();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	planner.requestPlan = async () => assert.fail('native Codex control must not request ArenaScript');
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		const result = await request.executeTool({
			agentId: request.agentId,
			goalRevision: request.goalRevision,
			turnId: 'turn-native-1',
			callId: 'call-native-1',
			tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Hi Lucas!', audience: 'public' } },
		});
		assert.equal(result.state, 'SUCCEEDED');
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({
		registry,
		planner,
		goalSchedule: timers.schedule,
		cancelGoalSchedule: timers.cancel,
		config: {
			bridge: { port: 25570, secret: 's'.repeat(32) },
			codex: { controlProtocol: 'native_tools', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' } },
		},
	});
	try {
		const runtimeErrors = [];
		run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Reply to Lucas.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => runtimeErrors.length > 0 || run.bridge.sent.some((message) => message.type === 'action_command'));
		assert.deepEqual(runtimeErrors, [], runtimeErrors[0]?.stack ?? runtimeErrors[0]?.message);
		const command = run.bridge.sent.find((message) => message.type === 'action_command');
		assert.equal(command.payload.actionType, 'chat');
		assert.equal(command.payload.provenance.model, 'gpt-5.6-sol');
		assert.match(planner.requests[0].input, /advance the current goal using fresh facts/i);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, eventSequence: 2 } });
		for (let index = 0; index < 20; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.deepEqual(runtimeErrors, [], runtimeErrors[0]?.stack ?? runtimeErrors[0]?.message);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PLANNING);
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
		await eventually(() => timers.pendingCount === 1);
		await timers.runNext();
		assert.equal(run.bridge.sent.filter(({ type }) => type === 'request_observation').length, 1);
	} finally {
		await run.coordinator.stop();
	}
});

test('native reads use correlated fresh samples and focused pages while an action remains active', async () => {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const timers = new ManualTimerQueue();
	const results = {};
	planner.getExecutionSettings = () => ({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		let call = 0;
		const execute = (tool) => request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'query-turn', callId: `query-${++call}`, tool });
		results.handle = await execute({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } });
		results.facts = await execute({ kind: 'observe' });
		results.status = await execute({ kind: 'action_status', actionId: results.handle.actionId });
		bridge.automaticInspections = false;
		results.page = await execute({ kind: 'inspect', section: 'inventory', offset: 8, limit: 2 });
		return { status: 'completed', toolCalls: call };
	};
	const run = await start({ bridge, registry, planner, goalSchedule: timers.schedule, cancelGoalSchedule: timers.cancel, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	try {
		bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Inspect my surroundings.' } });
		bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 7, observation: { player: { x: 3, y: 64, z: 4, health: 17 } } } });
		await eventually(() => bridge.sent.filter(({ type }) => type === 'inspection_request').length === 2);
		const [sample, page] = bridge.sent.filter(({ type }) => type === 'inspection_request');
		assert.deepEqual(sample.payload.query, { section: 'observation' });
		assert.equal(sample.connectionEpoch, 1);
		assert.equal(results.facts.freshness.fresh, true);
		assert.equal(results.facts.freshness.afterEventSequence, 7);
		assert.equal(results.facts.eventSequence, 8);
		assert.equal(results.facts.observation.player.health, 17);
		assert.deepEqual(results.facts.executionSettings, planner.getExecutionSettings());
		assert.equal(results.status.state, 'RUNNING');
		assert.equal(results.status.actionId, results.handle.actionId);
		assert.deepEqual(page.payload.query, { section: 'inventory', offset: 8, limit: 2 });
		const response = { entries: [{ slot: 8, itemId: 'minecraft:apple', count: 2 }], coverage: { offset: 8, returned: 1, hasMore: true, nextOffset: 9 }, eventSequence: 8 };
		bridge.replyInspection(page, response);
		await eventually(() => results.page !== undefined);
		assert.deepEqual(results.page, response);
		assert.equal(bridge.sent.filter(({ type }) => type === 'action_command').length, 1, 'read queries do not dispatch extra body actions');
	} finally {
		await run.coordinator.stop();
	}
});

test('a failed provider CLI probe at launch reaches chat as one agent_notice; a healthy probe sends none', async () => {
	const checks = [];
	const MISSING = "Claude Code CLI is not installed on the server machine (no 'claude' found on PATH). Install it, sign in with 'claude auth login', then restart Minecraft and relaunch this agent. This agent cannot think until that is fixed.";
	const providerCliHealth = {
		async check(provider) {
			checks.push(provider);
			if (provider === 'claude') return { provider, status: 'missing', code: 'PROVIDER_CLI_MISSING', message: MISSING, executable: 'claude', version: null, checkedAtEpochMs: 1, details: '' };
			return { provider, status: 'ok', code: null, message: null, executable: 'codex', version: '0.160.0', checkedAtEpochMs: 1, details: '' };
		},
	};
	const traces = [];
	const run = await start({ providerCliHealth, traceWriter: { write: (event, fields) => { traces.push({ event, ...fields }); } } });
	try {
		await eventually(() => checks.includes('codex'));
		const claudeRecord = { ...record('agent-claude'), provider: 'claude', model: 'claude-sonnet-5-5' };
		run.bridge.emit('agent_registered', { agentId: 'agent-claude', payload: claudeRecord });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_notice'));
		const notices = run.bridge.sent.filter((message) => message.type === 'agent_notice');
		assert.equal(notices.length, 1);
		assert.equal(notices[0].agentId, 'agent-claude');
		assert.deepEqual(notices[0].payload, { severity: 'error', code: 'PROVIDER_CLI_MISSING', message: MISSING });
		assert.ok(run.bridge.sent.findIndex((message) => message.type === 'agent_ready' && message.agentId === 'agent-claude')
			< run.bridge.sent.indexOf(notices[0]), 'the probe never delays agent readiness');
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_notice' && message.agentId === 'agent-a'), false, 'a healthy Codex CLI produces no notice');
		assert.ok(traces.some((trace) => trace.event === 'provider_cli_unhealthy' && trace.agentId === 'agent-claude' && trace.code === 'PROVIDER_CLI_MISSING'));

		run.bridge.emit('agent_registered', { agentId: 'agent-claude', payload: claudeRecord });
		await eventually(() => run.bridge.sent.filter((message) => message.type === 'agent_ready' && message.agentId === 'agent-claude').length === 2);
		for (let index = 0; index < 5; index += 1) await new Promise((resolve) => setImmediate(resolve));
		assert.equal(run.bridge.sent.filter((message) => message.type === 'agent_notice').length, 1, 'the same agent is told once per connection');
	} finally {
		await run.coordinator.stop();
	}
});

test('a provider CLI notice survives a goal started while the probe was still running', async () => {
	let release;
	const settled = new Promise((resolve) => { release = resolve; });
	const providerCliHealth = { async check(provider) { await settled; return missingHealth(provider, CODEX_MISSING); } };
	const run = await start({ providerCliHealth });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		await eventually(() => run.registry.get('agent-a').goalRevision === 1);
		release();
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_notice' && message.agentId === 'agent-a'));
		const notice = run.bridge.sent.find((message) => message.type === 'agent_notice');
		assert.deepEqual(notice.payload, { severity: 'error', code: 'PROVIDER_CLI_MISSING', message: CODEX_MISSING });
	} finally {
		release();
		await run.coordinator.stop();
	}
});

test('a failed agent_notice send does not consume the one notice an agent gets per connection', async () => {
	class FlakyBridge extends FakeBridge {
		failures = 0;
		async send(type, agentId, payload, options) {
			if (type === 'agent_notice' && this.failures === 0) { this.failures += 1; throw Object.assign(new Error('socket backpressure'), { code: 'BACKPRESSURE' }); }
			return super.send(type, agentId, payload, options);
		}
	}
	const providerCliHealth = { async check(provider) { return missingHealth(provider, CODEX_MISSING); } };
	const traces = [];
	const run = await start({ bridge: new FlakyBridge(), providerCliHealth, traceWriter: { write: (event, fields) => { traces.push({ event, ...fields }); } } });
	try {
		await eventually(() => traces.some((trace) => trace.event === 'provider_cli_notice_failed' && trace.agentId === 'agent-a'));
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_notice'), false);
		run.bridge.emit('agent_registered', { agentId: 'agent-a', payload: record('agent-a') });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'agent_notice' && message.agentId === 'agent-a'));
		assert.equal(run.bridge.sent.filter((message) => message.type === 'agent_notice').length, 1);
	} finally {
		await run.coordinator.stop();
	}
});
