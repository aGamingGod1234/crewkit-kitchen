import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { programSourceSummary, programTermination } from '../src/native-tool-runtime.mjs';

// Replays the event shape of the 2026-10-08 "find lava" session (sol-lava-trace.jsonl): an agent inside a
// deepslate wall mining forward. After every broken block Minecraft publishes ordinary attention (the drop
// lands in the inventory), the first lava pop is heard, and later lava comes into view.
const record = { agentId: 'lava-replay', goalRevision: 1, provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'low', serviceTier: 'priority' };
const turn = () => new Promise((resolve) => setImmediate(resolve));

const TUNNEL = (policy) => `program.onUnhandledAttention("${policy}");
program.watch(() => player.state().health < 10, { mode: "interrupt", after: "reconsider" }, async () => { await player.wait(1); });
let mined = 0;
let stuck = false;
await program.repeatUntil(() => stuck || mined >= 6, { maxIterations: 16 }, async () => {
  const next = world.nearest(world.blocks({ blockId: "minecraft:deepslate" }));
  if (next === null) { stuck = true; return; }
  await player.lookAt({ x: next.x + 0.5, y: next.y + 0.5, z: next.z + 0.5 });
  const mined_ = await tryResult(player.mine({ x: next.x, y: next.y, z: next.z, expectedBlockId: next.blockId, timeoutMs: 5000 }));
  if (mined_.succeeded) mined = mined + 1; else stuck = true;
});`;

function world() {
	const blocks = Array.from({ length: 10 }, (_, index) => ({ stableId: `${-180 - index},-52,1`, x: -180 - index, y: -52, z: 1, blockId: 'minecraft:deepslate' }));
	const state = { blocks, cobbled: 0, lava: false, heard: false };
	const observation = () => ({ player: { x: -178.7, y: -53, z: 1.7, health: 20, heard: state.heard ? [{ sound: 'lava' }] : [] },
		entities: [], items: [], blocks: [...state.blocks, ...(state.lava ? [{ stableId: '-186,-53,3', x: -186, y: -53, z: 3, blockId: 'minecraft:lava' }] : [])],
		inventory: { items: state.cobbled === 0 ? [] : [{ slot: 1, itemId: 'minecraft:cobbled_deepslate', count: state.cobbled }], tagCounts: {} } });
	return { state, observation };
}

function replay(policy) {
	let sequence = 1;
	const scene = world(), breaks = [], decisions = [];
	const executor = new NativeProgramExecutor({ ordinaryAttentionIntervalMs: 30_000 });
	// What Minecraft publishes after a block breaks, and how the coordinator classifies it.
	const publish = (changedFacts) => {
		const attention = classifyObservationTrigger({ attention: true, changedFacts }, scene.observation());
		executor.onObservation(record, { observation: scene.observation(), eventSequence: ++sequence, ...attention });
		return attention;
	};
	const published = [];
	const result = executor.run(record, { source: TUNNEL(policy), programId: `replay-${policy}`, maxActions: 64 }, {
		observation: scene.observation(), eventSequence: sequence,
		executeAction: async (command) => {
			// Each body action takes real ticks, so Minecraft's publications interleave with the routine.
			await turn();
			if (command.action.type === 'break_block') {
				scene.state.blocks = scene.state.blocks.filter((block) => block.x !== command.action.arguments.x);
				breaks.push(command.action.arguments.x);
				setImmediate(() => {
					scene.state.cobbled += 1;
					published.push(publish(['inventory']));
					if (breaks.length === 2) { scene.state.heard = true; published.push(publish(['heard'])); }
					if (breaks.length === 3) { scene.state.lava = true; published.push(publish(['blocks.-186,-53,3'])); }
				});
			}
			return { state: 'SUCCEEDED', reasonCode: command.action.type === 'break_block' ? 'BLOCK_BROKEN' : 'ACTION_COMPLETED' };
		},
		cancelAction: async () => ({ state: 'CANCELLED' }),
		refreshObservation: async () => ({ observation: scene.observation(), eventSequence: ++sequence }),
		onDecision: (status, { priority }) => {
			decisions.push({ trigger: status.decision?.trigger, priority, engineState: status.engineState, atBreak: breaks.length, id: status.decision?.decisionId });
			// The model answers each notification a moment later and keeps its routine.
			if (policy === 'continue_and_notify') setImmediate(() => {
				try { executor.respond(record, { programId: `replay-${policy}`, decisionId: status.decision.decisionId, directive: 'continue' }); } catch { /* superseded */ }
			});
		},
	});
	return { executor, result, breaks, decisions, published };
}

test('a looping tunnel keeps mining through post-break attention, first-heard lava and lava coming into view', async () => {
	const run = replay('continue_and_notify');
	const outcome = await run.result;
	assert.deepEqual(run.published.map(({ priority }) => priority), Array(run.published.length).fill('ordinary'), 'pickups, heard lava and visible lava are ordinary attention');
	assert.equal(run.breaks.length, 6, 'one program mines block after block');
	assert.equal(outcome.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(programTermination(outcome), 'exhausted');
	assert.equal(outcome.actionsFailed, 0);
	const told = run.decisions.map(({ atBreak }) => atBreak);
	assert.equal(told[0], 1, 'the first pickup reaches the model at once');
	assert.ok(!told.includes(2), 'later pickups and first-heard lava fold into the window while the routine keeps mining');
	assert.ok(told.includes(3), 'lava newly in view skips the window');
	assert.ok(run.decisions.every(({ engineState }) => engineState === 'ACTIVE'), 'no notification paused the routine');
});

test('an authored pause_and_notify routine stops at the first pickup: that is the model\'s policy, not the runtime', async () => {
	const run = replay('pause_and_notify');
	for (let index = 0; index < 20 && run.decisions.length === 0; index++) await turn();
	assert.equal(run.breaks.length, 1);
	assert.match(run.decisions[0].engineState, /^SUSPEND(ING|ED)$/);
	run.executor.cancel(record.agentId);
	assert.equal(programTermination(await run.result), 'cancelled');
});

test('program traces carry size, a short hash and a termination word, never the source', () => {
	const summary = programSourceSummary('await player.wait(1);');
	assert.deepEqual(Object.keys(summary), ['sourceBytes', 'sourceHash']);
	assert.equal(summary.sourceBytes, 21);
	assert.match(summary.sourceHash, /^[0-9a-f]{12}$/);
	assert.deepEqual(programSourceSummary(undefined), {});
	assert.equal(programTermination({ state: 'YIELDED', reasonCode: 'PROGRAM_FINISH_REQUESTED' }), 'finished');
	assert.equal(programTermination({ state: 'YIELDED', reasonCode: 'MODEL_PAUSED' }), 'paused_by_model');
	assert.equal(programTermination({ state: 'TIMED_OUT', reasonCode: 'PROGRAM_DEADLINE' }), 'timed_out');
	assert.equal(programTermination({ state: 'FAILED', reasonCode: 'X' }), 'failed');
	assert.equal(programTermination({ state: 'YIELDED', reasonCode: 'FRESH_OBSERVATION_REQUIRED' }), 'yielded');
});

test('a program result is not lost when an ordinary observation follows it into the same pending turn', async () => {
	const { mergePlannerRequest } = await import('../src/dynamic-main.mjs');
	const ended = { goalRevision: 1, priority: 'ordinary', trigger: 'program_ended', eventSequence: 5,
		nativeEvent: { event: 'program_ended', programId: 'p-1', result: { state: 'YIELDED', reasonCode: 'PROGRAM_EXHAUSTED', actions: 2 }, observation: { at: 5 }, eventSequence: 5 } };
	const seen = { goalRevision: 1, priority: 'ordinary', trigger: 'observation', eventSequence: 6, nativeEvent: { event: 'observation', trigger: 'observation', observation: { at: 6 } } };
	const merged = mergePlannerRequest(ended, seen);
	assert.equal(merged.nativeEvent.event, 'program_ended');
	assert.equal(merged.nativeEvent.result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.deepEqual(merged.nativeEvent.observation, { at: 6 }, 'with the newest facts');
	assert.equal(merged.nativeEvent.eventSequence, 6);
	const danger = { ...seen, priority: 'urgent', trigger: 'damage', nativeEvent: { event: 'observation', trigger: 'damage', observation: { at: 7 } } };
	const urgent = mergePlannerRequest(ended, danger);
	assert.equal(urgent.nativeEvent.trigger, 'damage', 'danger still leads');
	assert.equal(urgent.nativeEvent.event, 'observation');
	assert.deepEqual(urgent.nativeEvent.observation, { at: 7 });
	assert.equal(urgent.nativeEvent.result.reasonCode, 'PROGRAM_EXHAUSTED', 'and still carries the program result');
	const attention = { ...ended, nativeEvent: { event: 'program_attention', programId: 'p-2', status: { decision: { decisionId: 'p-2:decision-1' } }, observation: { at: 5 } } };
	assert.equal(mergePlannerRequest(attention, danger).nativeEvent.status.decision.decisionId, 'p-2:decision-1', 'or the decision handle');
	const { buildNativeEventInput } = await import('../src/dynamic-main.mjs');
	const input = buildNativeEventInput({ goalRevision: 1, currentGoal: 'Find lava' }, mergePlannerRequest(ended, danger).nativeEvent);
	const payload = JSON.parse(input.slice(input.indexOf('\n') + 1));
	assert.equal(payload.trigger, 'damage');
	assert.equal(payload.program.result.reasonCode, 'PROGRAM_EXHAUSTED', 'the model sees both');
});
