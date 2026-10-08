import assert from 'node:assert/strict';
import test from 'node:test';

import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, NATIVE_AGENT_INSTRUCTIONS, normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';

const record = {
	agentId: 'model-action-overlap-test', goalRevision: 1,
	provider: 'codex', model: 'selected-test-model', reasoningEffort: 'high', serviceTier: 'priority',
};
const observation = { player: { health: 20 }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } };

function deterministicClock() {
	let now = 0;
	let nextId = 0;
	const timers = [];
	return {
		now: () => now,
		setTimeoutFn(callback, delayMs) {
			const timer = { id: ++nextId, dueAt: now + delayMs, callback, cancelled: false, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn(timer) { if (timer !== undefined && timer !== null) timer.cancelled = true; },
		async advanceTo(targetMs) {
			assert.ok(targetMs >= now, 'clock cannot move backwards');
			while (true) {
				const timer = timers
					.filter((candidate) => !candidate.cancelled && candidate.dueAt <= targetMs)
					.sort((left, right) => left.dueAt - right.dueAt || left.id - right.id)[0];
				if (timer === undefined) break;
				timer.cancelled = true;
				now = timer.dueAt;
				timer.callback();
				await new Promise((resolve) => setImmediate(resolve));
			}
			now = targetMs;
			await new Promise((resolve) => setImmediate(resolve));
		},
	};
}

test('native guidance exposes safe model-action overlap and the deterministic timing benchmark measures it', async (t) => {
	const programDescription = MINECRAFT_DYNAMIC_TOOLS.find(({ name }) => name === 'runProgram').description;
	const startActionDescription = MINECRAFT_DYNAMIC_TOOLS.find(({ name }) => name === 'startAction').description;
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /sequence for safe linear chains.*ArenaScript for conditional work; repeat work.*one looping background:true program/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /startAction to reason while one chosen action runs/);
	assert.match(programDescription, /One recent-p95 advisory may arrive near timeout/);
	assert.match(programDescription, /never chooses or dispatches actions/);
	assert.match(startActionDescription, /reason while it runs/);
	assert.match(startActionDescription, /does not authorize a dependent action without fresh facts/);

	const clock = deterministicClock();
	const actionDurationMs = 900;
	const decisionPrepMs = 100;
	const planningLeadMs = 200;
	const timeoutMs = 1_000;
	const commands = [];
	let actionFinishedAt = null;
	let planningStartedAt = null;
	let planningFinishedAt = null;
	let resolveActionStarted;
	const actionStarted = new Promise((resolve) => { resolveActionStarted = resolve; });
	let resolvePlanning;
	const planning = new Promise((resolve) => { resolvePlanning = resolve; });
	const executor = new NativeProgramExecutor({ setTimeoutFn: clock.setTimeoutFn, clearTimeoutFn: clock.clearTimeoutFn, sessionId: 'overlap-benchmark' });
	const context = {
		observation,
		eventSequence: 1,
		executeAction: (command) => {
			commands.push(command);
			resolveActionStarted();
			return new Promise((resolve) => clock.setTimeoutFn(() => {
				actionFinishedAt = clock.now();
				resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
			}, actionDurationMs));
		},
		cancelAction: async () => {},
		refreshObservation: async () => ({ observation, eventSequence: 2 }),
		onPlanningDue: (_status, details) => {
			assert.equal(details.planningLeadMs, planningLeadMs);
			planningStartedAt = clock.now();
			clock.setTimeoutFn(() => {
				planningFinishedAt = clock.now();
				resolvePlanning();
			}, decisionPrepMs);
		},
	};

	const run = executor.run(record, {
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
		timeoutMs,
		planningLeadMs,
		programId: 'overlap-benchmark',
	}, context);
	await actionStarted;
	await clock.advanceTo(timeoutMs - planningLeadMs);
	assert.equal(planningStartedAt, timeoutMs - planningLeadMs);
	assert.equal(commands.length, 1, 'preparation does not dispatch a gameplay action');
	await clock.advanceTo(actionDurationMs);
	const result = await run;
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(actionFinishedAt, actionDurationMs);
	await clock.advanceTo(planningStartedAt + decisionPrepMs);
	await planning;

	const serialNextIntentReadyAt = actionFinishedAt + decisionPrepMs;
	const overlappedNextIntentReadyAt = Math.max(actionFinishedAt, planningFinishedAt);
	const measuredBenefitMs = serialNextIntentReadyAt - overlappedNextIntentReadyAt;
	assert.equal(planningFinishedAt, actionFinishedAt, 'synthetic model reasoning finishes as the action settles');
	assert.equal(measuredBenefitMs, decisionPrepMs);
	assert.equal(commands.length, 1, 'only the authored source action ran');
	t.diagnostic(`deterministic overlap: action=${actionDurationMs}ms, preparation=${decisionPrepMs}ms, lead=${planningLeadMs}ms, overlap=${measuredBenefitMs}ms; serial next intention=${serialNextIntentReadyAt}ms, overlapped=${overlappedNextIntentReadyAt}ms`);
});

test('sequence finish stays an optional exact model-authored verification request', () => {
	const actions = [
		{ actionType: 'wait', arguments: { durationMs: 1 } },
		{ actionType: 'wait', arguments: { durationMs: 2 } },
	];
	assert.deepEqual(normalizeMinecraftToolCall('sequence', { actions }), { kind: 'sequence', actions });
	assert.deepEqual(normalizeMinecraftToolCall('sequence', { actions, finish: { summary: 'goal facts are satisfied' } }), {
		kind: 'sequence', actions, finish: { summary: 'goal facts are satisfied' },
	});
	for (const finish of [{}, { summary: '' }, { summary: 'x'.repeat(513) }, { summary: 'done', planner: 'another-model' }]) {
		assert.throws(() => normalizeMinecraftToolCall('sequence', { actions, finish }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS.find(({ name }) => name === 'sequence').inputSchema.properties.finish.required, ['summary']);
});
