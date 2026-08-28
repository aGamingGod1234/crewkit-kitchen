import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import {
	AntigravityProviderService,
	buildAntigravityLaunch,
} from '../src/antigravity-service.mjs';
import { buildPlannerInput } from '../src/prompts.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';
import { replaceDecisionJson } from './provider-decision-fixtures.mjs';

test('Antigravity catalog retains the last discovered aliases when a later CLI refresh fails', async () => {
	let fail = false;
	const discovered = [{
		id: 'gemini-3.6-flash',
		model: 'gemini-3.6-flash',
		displayName: 'Gemini 3.6 Flash',
		reasoningEfforts: ['high', 'medium', 'low'],
		serviceTiers: [],
	}];
	const service = new AntigravityProviderService(
		{ cwd: 'C:\\workspace', catalogDiscovery: true },
		{ discoverCatalog: async () => { if (fail) throw new Error('offline'); return discovered; } },
	);
	const first = await service.catalog.refresh({ force: true });
	fail = true;
	const retained = await service.catalog.refresh({ force: true });
	assert.deepEqual(retained, first);
	assert.equal(retained.models[0].displayName, 'Gemini 3.6 Flash');
	assert.equal(service.catalog.stale, true);
});

test('Antigravity fallback configuration accepts the installed Gemini 3.7 Flash model', async () => {
	const service = new AntigravityProviderService({ cwd: 'C:\\workspace' });
	const agent = await service.createAgent({
		agentId: 'gemini-current', provider: 'gemini', model: 'gemini-3.7-flash', reasoningEffort: 'high',
	});
	assert.equal(agent.provider, 'gemini');
	await service.stop();
});

const DECISION = replaceDecisionJson();

class FakeChild extends EventEmitter {
	constructor() {
		super();
		this.stdout = new EventEmitter();
		this.stderr = new EventEmitter();
		this.exitCode = null;
		this.signalCode = null;
		this.pid = 4_242;
	}
}

function successfulSpawner(calls, output = DECISION) {
	return (command, args, options) => {
		calls.push({ command, args, options });
		const child = new FakeChild();
		queueMicrotask(() => {
			child.stdout.emit('data', Buffer.from(output));
			child.exitCode = 0;
			child.emit('close', 0, null);
		});
		return child;
	};
}

function cancellingTerminator(calls) {
	return async (child) => {
		calls.push(child);
		child.signalCode = 'SIGTERM';
		child.emit('close', null, 'SIGTERM');
	};
}

function config(overrides = {}) {
	return {
		provider: 'gemini',
		cwd: 'C:\\workspace',
		models: ['gemini-3.1-pro', 'gemini-3.6-flash'],
		modelReasoningEfforts: {
			'gemini-3.1-pro': ['high', 'low'],
			'gemini-3.6-flash': ['high', 'medium', 'low'],
		},
		planningTimeoutMs: 1_000,
		...overrides,
	};
}

function profile(overrides = {}) {
	return {
		agentId: 'gemini-a',
		provider: 'gemini',
		model: 'gemini-3.1-pro',
		reasoningEffort: 'high',
		...overrides,
	};
}

test('Antigravity launch maps the visible Gemini model and thinking to one exact CLI model', () => {
	const launch = buildAntigravityLaunch(profile(), config(), {
		cwd: 'C:\\agents\\gemini\\gemini-a',
		env: { PATH: 'test', ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret' },
		platform: 'win32',
	});
	assert.equal(launch.command, 'agy');
	assert.deepEqual(launch.argsBeforePrompt, ['--print']);
	assert.deepEqual(launch.argsAfterPrompt, [
		'--model', 'gemini-3.1-pro-high',
		'--sandbox',
		'--print-timeout', '1s',
	]);
	assert.equal(launch.options.cwd, 'C:\\agents\\gemini\\gemini-a');
	assert.equal(launch.options.env.PATH, 'test');
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined, 'Gemini child cannot inherit the bridge secret');
	assert.deepEqual(launch.options.stdio, ['ignore', 'pipe', 'pipe']);
});

test('Antigravity parses planner output and uses the stable per-agent workspace', async () => {
	const spawnCalls = [];
	const workspaceCalls = [];
	const service = new AntigravityProviderService(config(), {
		platform: 'win32',
		spawn: successfulSpawner(spawnCalls),
		workspaceManager: {
			async prepare(provider, agentId) {
				workspaceCalls.push({ provider, agentId });
				return 'C:\\agents\\gemini\\gemini-a';
			},
		},
	});
	const agent = await service.createAgent(profile(), { recoverySummary: 'Previous movement timed out.' });
	await agent.setGoalRevision(7);
	const decision = await agent.decide('Minecraft planner state (authoritative JSON):\n{}', { goalRevision: 7 });

	assert.equal(decision.directive, 'replace');
	assert.deepEqual(workspaceCalls, [{ provider: 'gemini', agentId: 'gemini-a' }]);
	assert.equal(spawnCalls[0].options.cwd, 'C:\\agents\\gemini\\gemini-a');
	assert.equal(spawnCalls[0].args[0], '--print');
	assert.match(spawnCalls[0].args[1], /strategic author for one Minecraft player/);
	assert.match(spawnCalls[0].args[1], /Previous movement timed out/);
	assert.deepEqual(spawnCalls[0].args.slice(-5), [
		'--model', 'gemini-3.1-pro-high', '--sandbox', '--print-timeout', '1s',
	]);
	await service.stop();
});

test('Antigravity structured turns use an isolated prompt and caller-supplied parser', async () => {
	const spawnCalls = [];
	const service = new AntigravityProviderService(config(), {
		platform: 'win32',
		spawn: successfulSpawner(spawnCalls, '{"requestId":"draft-1"}'),
	});
	const agent = await service.createAgent(profile({ agentId: 'gemini-structured' }));
	const result = await agent.decide('translate exactly', { goalRevision: 0, systemPrompt: '', parseOutput: JSON.parse });
	assert.deepEqual(result, { requestId: 'draft-1' });
	assert.equal(spawnCalls[0].args[1], 'translate exactly');
	await service.stop();
});

test('Antigravity reports its bounded visible result through the verbose adapter contract', async () => {
	const service = new AntigravityProviderService(config(), {
		platform: 'win32',
		spawn: successfulSpawner([]),
	});
	const agent = await service.createAgent(profile({ agentId: 'gemini-verbose' }));
	await agent.setGoalRevision(1);
	const events = [];
	await agent.decide('authoritative state', {
		goalRevision: 1,
		onVerbose(stage, message) { events.push({ stage, message }); },
	});
	assert.equal(events.every(({ stage, message }) => stage === 'output' && message.length <= 256), true);
	assert.equal(events.map(({ message }) => message).join(''), DECISION);
	await service.stop();
});

test('Antigravity malformed output records one final error row for the attempt', async () => {
	const spawnCalls = [];
	const service = new AntigravityProviderService(config(), { platform: 'win32', spawn: successfulSpawner(spawnCalls, 'not-json') });
	const agent = await service.createAgent(profile({ agentId: 'gemini-malformed-record' }));
	await agent.setGoalRevision(7);
	const rows = [];
	const turnRecorder = { async record(row) { rows.push(row); } };
	await assert.rejects(agent.decide('authoritative state', { goalRevision: 7, turnRecorder, attempt: 5, retry: true, queueWaitMs: 17 }), (error) => error?.code === 'MALFORMED_DECISION');
	assert.equal(rows.length, 1);
	assert.equal(rows[0].error?.code, 'MALFORMED_DECISION');
	assert.equal(rows[0].attempt, 5);
	assert.equal(rows[0].retry, true);
	assert.equal(rows[0].agentId, 'gemini-malformed-record');
	assert.equal(rows[0].timing.queueWaitMs, 17);
	assert.equal(Object.hasOwn(rows[0], 'tokens'), false);
	assert.ok(rows[0].timing.durationMs >= 0);
	assert.equal(rows[0].timing.apiDurationMs, null);
	await service.stop();
});

test('Antigravity continues compiler correction in the same selected-model workspace session', async () => {
	const spawnCalls = [];
	const service = new AntigravityProviderService(config(), {
		platform: 'win32', spawn: successfulSpawner(spawnCalls),
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(7);
	await agent.decide('Minecraft planner state (authoritative JSON):\n{}', { goalRevision: 7 });
	const correction = buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'unexpected token', line: 3, column: 2 },
		rejectedSourceHash: 'sha256:abc123', observation: { resourceCount: 3 },
	});
	const decision = await agent.decide(correction, { goalRevision: 7 });
	assert.equal(decision.directive, 'replace');
	assert.equal(spawnCalls.length, 2);
	assert.equal(spawnCalls[0].args.includes('--continue'), false);
	assert.equal(spawnCalls[1].args.includes('--continue'), true);
	assert.equal(spawnCalls[1].options.cwd, spawnCalls[0].options.cwd);
	assert.deepEqual(modelArgs(spawnCalls[1].args), modelArgs(spawnCalls[0].args));
	await service.stop();
});

test('Antigravity rejects unsupported model-thinking combinations and profile conflicts', async () => {
	const service = new AntigravityProviderService(config(), { spawn: successfulSpawner([]) });
	await assert.rejects(
		service.createAgent(profile({ model: 'missing' })),
		(error) => error?.code === 'UNSUPPORTED_MODEL',
	);
	await assert.rejects(
		service.createAgent(profile({ model: 'gemini-3.1-pro', reasoningEffort: 'medium' })),
		(error) => error?.code === 'UNSUPPORTED_THINKING',
	);
	await service.createAgent(profile());
	await assert.rejects(
		service.createAgent(profile({ reasoningEffort: 'low' })),
		(error) => error?.code === 'AGENT_PROFILE_CONFLICT',
	);
	await service.stop();
});

test('Antigravity retains service tier in the exact session profile', async () => {
	const service = new AntigravityProviderService(config(), { spawn: successfulSpawner([]) });
	const selected = profile({ serviceTier: 'fast' });
	const agent = await service.createAgent(selected);
	assert.equal(agent.sessionGeneration, 1);
	assert.equal(agent.profileFingerprint, profileFingerprint(selected));
	assert.equal(await service.createAgent(selected), agent, 'same profile reuses the existing Gemini session');
	await assert.rejects(
		service.createAgent({ ...selected, serviceTier: 'priority' }),
		(error) => error?.code === 'AGENT_PROFILE_CONFLICT',
	);
	await service.stop();
});

test('Antigravity exposes explicit best-effort continuation status without claiming a durable session', async () => {
	const spawnCalls = [];
	const service = new AntigravityProviderService(config(), { spawn: successfulSpawner(spawnCalls) });
	const selected = profile({ serviceTier: 'priority' });
	const agent = await service.createAgent(selected);
	await agent.setGoalRevision(1);
	await agent.decide('first authoritative state', { goalRevision: 1 });
	const first = agent.sessionMetadata();
	await agent.decide('second authoritative state', { goalRevision: 1 });
	const second = agent.sessionMetadata();
	assert.equal(first.sessionGeneration, 1);
	assert.equal(first.sessionState, 'warm');
	assert.equal(first.continuation, 'best_effort');
	assert.equal(first.durability, 'unverified');
	assert.equal(first.profileFingerprint, profileFingerprint(selected));
	assert.deepEqual(second, first);
	assert.equal(spawnCalls[1].args.includes('--continue'), true);
	await service.stop();
});

test('Antigravity does not warm a session when decision parsing fails', async () => {
	const spawnCalls = [];
	let attempt = 0;
	const service = new AntigravityProviderService(config(), {
		spawn: (command, args, options) => {
			spawnCalls.push({ command, args, options });
			const child = new FakeChild();
			attempt += 1;
			queueMicrotask(() => {
				const output = attempt === 1
					? '{"summary":"Wait safely.","directive":"replace","source":null}'
					: DECISION;
				child.stdout.emit('data', Buffer.from(output));
				child.exitCode = 0;
				child.emit('close', 0, null);
			});
			return child;
		},
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);
	await assert.rejects(agent.decide('state', { goalRevision: 1 }), (error) => error?.code === 'DECISION_FIELD_MISMATCH');
	assert.equal(agent.sessionMetadata().sessionState, 'cold');
	await agent.decide('state', { goalRevision: 1 });
	assert.equal(spawnCalls[1].args.includes('--continue'), false);
	assert.equal(agent.sessionMetadata().sessionState, 'warm');
	await service.stop();
});

test('Antigravity interruption terminates the active process and rejects the turn', async () => {
	const child = new FakeChild();
	const terminated = [];
	const service = new AntigravityProviderService(config(), {
		spawn: () => child,
		terminate: cancellingTerminator(terminated),
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);
	const turn = agent.decide('state', { goalRevision: 1 });
	await agent.interrupt();

	await assert.rejects(turn, (error) => error?.code === 'PLAN_CANCELLED');
	assert.deepEqual(terminated, [child]);
	await service.stop();
});

test('Antigravity starts a fresh replacement after first-turn process failure or interruption', async () => {
	const failedCalls = [];
	let failedAttempt = 0;
	const failedService = new AntigravityProviderService(config(), {
		spawn: (command, args, options) => {
			failedCalls.push({ command, args, options });
			const child = new FakeChild();
			failedAttempt += 1;
			queueMicrotask(() => {
				if (failedAttempt === 1) { child.exitCode = 1; child.emit('close', 1, null); return; }
				child.stdout.emit('data', Buffer.from(DECISION)); child.exitCode = 0; child.emit('close', 0, null);
			});
			return child;
		},
	});
	const failedAgent = await failedService.createAgent(profile());
	await failedAgent.setGoalRevision(1);
	await assert.rejects(failedAgent.decide('state', { goalRevision: 1 }), (error) => error?.code === 'PROVIDER_UNAVAILABLE');
	const failedReplacement = await failedService.replaceAgent(profile(), { expectedSessionGeneration: 1 });
	await failedReplacement.setGoalRevision(1);
	await failedReplacement.decide('state', { goalRevision: 1 });
	assert.equal(failedCalls[1].args.includes('--continue'), false);
	await failedService.stop();

	const interruptedCalls = [];
	let interruptedAttempt = 0;
	const interruptedService = new AntigravityProviderService(config(), {
		spawn: (command, args, options) => {
			interruptedCalls.push({ command, args, options });
			const child = new FakeChild();
			interruptedAttempt += 1;
			if (interruptedAttempt === 2) queueMicrotask(() => {
				child.stdout.emit('data', Buffer.from(DECISION)); child.exitCode = 0; child.emit('close', 0, null);
			});
			return child;
		},
		terminate: cancellingTerminator([]),
	});
	const interruptedAgent = await interruptedService.createAgent(profile());
	await interruptedAgent.setGoalRevision(1);
	const active = interruptedAgent.decide('state', { goalRevision: 1 });
	await interruptedAgent.interrupt();
	await assert.rejects(active, (error) => error?.code === 'PLAN_CANCELLED');
	await interruptedAgent.decide('state', { goalRevision: 1 });
	assert.equal(interruptedCalls[1].args.includes('--continue'), false);
	await interruptedService.stop();
});

test('Antigravity replaces a dead continued session instead of reusing its continuation', async () => {
	const calls = [];
	let attempt = 0;
	const service = new AntigravityProviderService(config(), {
		spawn: (command, args, options) => {
			calls.push({ command, args, options });
			const child = new FakeChild();
			attempt += 1;
			queueMicrotask(() => {
				if (attempt === 2) { child.exitCode = 1; child.emit('close', 1, null); return; }
				child.stdout.emit('data', Buffer.from(DECISION)); child.exitCode = 0; child.emit('close', 0, null);
			});
			return child;
		},
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);
	await agent.decide('state', { goalRevision: 1 });
	await assert.rejects(agent.decide('state', { goalRevision: 1 }), (error) => error?.code === 'PROVIDER_UNAVAILABLE');
	await assert.rejects(agent.decide('state', { goalRevision: 1 }), (error) => error?.code === 'SESSION_INVALIDATED');
	const replacement = await service.replaceAgent(profile(), { expectedSessionGeneration: 1 });
	await replacement.setGoalRevision(1);
	await replacement.decide('state', { goalRevision: 1 });
	assert.equal(calls[1].args.includes('--continue'), true);
	assert.equal(calls[2].args.includes('--continue'), false, 'a replacement must not reuse the dead continuation');
	await service.stop();
});

function modelArgs(args) {
	const modelIndex = args.indexOf('--model');
	return args.slice(modelIndex, modelIndex + 2);
}

test('Antigravity timeout terminates the process tree and reports PLANNING_TIMEOUT', async () => {
	const child = new FakeChild();
	const terminated = [];
	const service = new AntigravityProviderService(config({ planningTimeoutMs: 5 }), {
		spawn: () => child,
		terminate: cancellingTerminator(terminated),
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);

	await assert.rejects(
		agent.decide('state', { goalRevision: 1 }),
		(error) => error?.code === 'PLANNING_TIMEOUT',
	);
	assert.deepEqual(terminated, [child]);
	await service.stop();
});

test('Antigravity output overflow is bounded and terminates the process', async () => {
	const child = new FakeChild();
	const terminated = [];
	const service = new AntigravityProviderService(config({ stdoutLimitBytes: 8 }), {
		spawn: () => {
			queueMicrotask(() => child.stdout.emit('data', Buffer.from('too much output')));
			return child;
		},
		terminate: cancellingTerminator(terminated),
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);

	await assert.rejects(
		agent.decide('state', { goalRevision: 1 }),
		(error) => error?.code === 'OUTPUT_LIMIT_EXCEEDED',
	);
	assert.deepEqual(terminated, [child]);
	await service.stop();
});

test('Antigravity nonzero exit preserves bounded diagnostics without returning a decision', async () => {
	const service = new AntigravityProviderService(config(), {
		spawn: () => {
			const child = new FakeChild();
			queueMicrotask(() => {
				child.stderr.emit('data', Buffer.from('authentication required'));
				child.exitCode = 1;
				child.emit('close', 1, null);
			});
			return child;
		},
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);

	await assert.rejects(
		agent.decide('state', { goalRevision: 1 }),
		(error) => error?.code === 'PROVIDER_UNAVAILABLE' && error.message.includes('authentication required'),
	);
	await service.stop();
});

test('Antigravity process death invalidates only its owning generation and exact replacement coalesces', async () => {
	let attempt = 0;
	const selected = profile({ serviceTier: 'priority' });
	const originalSpawn = successfulSpawner([], DECISION);
	const recoverable = new AntigravityProviderService(config(), {
		spawn: (command, args, options) => {
			attempt += 1;
			if (attempt > 1) return originalSpawn(command, args, options);
			const child = new FakeChild();
			queueMicrotask(() => { child.exitCode = 1; child.emit('close', 1, null); });
			return child;
		},
	});
	const stale = await recoverable.createAgent(selected);
	await stale.setGoalRevision(1);
	await assert.rejects(stale.decide('state', { goalRevision: 1 }), (error) => error?.code === 'PROVIDER_UNAVAILABLE');
	assert.equal(recoverable.getAgent(selected.agentId), null);
	await assert.rejects(stale.decide('state', { goalRevision: 1 }), (error) => error?.code === 'SESSION_INVALIDATED');

	const first = recoverable.replaceAgent(selected, { expectedSessionGeneration: 1 });
	const second = recoverable.replaceAgent(selected, { expectedSessionGeneration: 1 });
	assert.equal(await first, await second);
	assert.equal((await first).sessionGeneration, 2);
	await recoverable.stop();
});

test('Antigravity fails closed before spawn when a Windows prompt is not safely representable', async () => {
	let spawned = false;
	const service = new AntigravityProviderService(config(), {
		platform: 'win32',
		spawn: () => {
			spawned = true;
			return new FakeChild();
		},
	});
	const agent = await service.createAgent(profile());
	await agent.setGoalRevision(1);

	await assert.rejects(
		agent.decide('x'.repeat(30_000), { goalRevision: 1 }),
		(error) => error?.code === 'PROMPT_TOO_LARGE',
	);
	assert.equal(spawned, false);
	await service.stop();
});
