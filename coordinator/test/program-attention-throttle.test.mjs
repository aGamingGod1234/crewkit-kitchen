import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';

const record = { agentId: 'attention-throttle', goalRevision: 1, provider: 'claude', model: 'selected-model', reasoningEffort: 'low', serviceTier: 'priority' };
const observation = (health = 20) => ({ player: { x: 0, y: 64, z: 0, health }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } });
const turn = () => new Promise(resolve => setImmediate(resolve));

function setup(t, policy = 'continue_and_notify') {
	let now = 1_000_000;
	const timers = [], decisions = [], pending = [];
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms, cleared: false }; timers.push(timer); return timer; },
		clearTimeoutFn: timer => { if (timer) timer.cleared = true; },
		now: () => now,
		ordinaryAttentionIntervalMs: 30_000,
	});
	const result = executor.run(record, { source: `program.onUnhandledAttention("${policy}"); await player.wait(1); await player.wait(2);`, programId: 'throttle' }, {
		observation: observation(), eventSequence: 1,
		executeAction: () => new Promise((resolve) => { pending.push(resolve); }),
		cancelAction: async () => { for (const resolve of pending.splice(0)) resolve({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); return { state: 'CANCELLED' }; },
		onDecision: (status, { priority }) => decisions.push({ decisionId: status.decision?.decisionId, priority }),
	});
	t.after(() => executor.cancel(record.agentId));
	let sequence = 1;
	const sight = (extra = {}) => executor.onObservation(record, { observation: observation(extra.health), eventSequence: ++sequence, attention: true, priority: 'ordinary', trigger: 'resource_discovery', ...extra });
	return { executor, result, timers, decisions, sight, advance: (ms) => { now += ms; } };
}

test('ordinary sightings during a running program wake the model once per window and keep one decision handle', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 1, 'the first sighting notifies at once');
	const { decisionId } = run.decisions[0];
	for (let index = 0; index < 5; index++) { run.advance(3_000); run.sight(); await turn(); }
	assert.equal(run.decisions.length, 1, 'repeated ordinary sightings within the window do not wake the model again');
	assert.equal(run.executor.status(record).decision.decisionId, decisionId, 'an in-flight respondProgram stays valid');
	run.advance(16_000);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 2, 'after the window the latest facts reach the model');
	assert.equal(run.decisions[1].decisionId, decisionId);
	const status = run.executor.respond(record, { programId: 'throttle', decisionId, directive: 'continue' });
	assert.equal(status.engineState, 'ACTIVE');
});

test('a deferred ordinary notification is delivered when its window ends', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	run.executor.respond(record, { programId: 'throttle', decisionId: run.decisions[0].decisionId, directive: 'continue' });
	run.advance(5_000);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 1, 'a new ordinary decision inside the window waits');
	const deferred = run.timers.find(timer => timer.ms === 25_000 && !timer.cleared);
	assert.ok(deferred, 'the remaining window is scheduled');
	run.advance(25_000);
	deferred.callback();
	assert.equal(run.decisions.length, 2);
	assert.notEqual(run.decisions[1].decisionId, run.decisions[0].decisionId);
});

test('urgent attention always notifies at once', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	run.advance(1_000);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 12 });
	await turn();
	assert.equal(run.decisions.length, 2);
	assert.equal(run.decisions[1].priority, 'urgent');
});

test('ordinary attention folded after the last notification is not lost when the model answers continue', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	const first = run.decisions[0].decisionId;
	run.advance(3_000);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 1);
	const folded = run.executor.status(record).decision;
	assert.equal(folded.decisionId, first);
	assert.equal(folded.eventSequence, 3, 'the folded decision carries the newest facts');
	run.executor.respond(record, { programId: 'throttle', decisionId: first, directive: 'continue' });
	await turn();
	const reopened = run.executor.status(record).decision;
	assert.ok(reopened && reopened.decisionId !== first, 'the unseen sighting opens a new decision');
	const deferred = run.timers.find(timer => !timer.cleared && timer.ms === 27_000);
	assert.ok(deferred, 'through the same window');
	run.advance(27_000);
	deferred.callback();
	assert.equal(run.decisions.length, 2);
	assert.equal(run.decisions[1].decisionId, reopened.decisionId);
});

test('a continue with nothing unseen does not reopen a decision', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	run.executor.respond(record, { programId: 'throttle', decisionId: run.decisions[0].decisionId, directive: 'continue' });
	await turn();
	assert.equal(run.executor.status(record).decision, undefined);
});

test('lava newly in view, lost health or a changed dimension skip the ordinary window', async t => {
	for (const change of [
		(observation) => ({ ...observation, blocks: [{ stableId: '3,63,0', x: 3, y: 63, z: 0, blockId: 'minecraft:lava', tags: [], state: {} }] }),
		(observation) => ({ ...observation, player: { ...observation.player, health: 19 } }),
		(observation) => ({ ...observation, world: { worldId: 'w', dimension: 'minecraft:the_nether' } }),
	]) {
		const run = setup(t);
		run.sight();
		await turn();
		run.executor.respond(record, { programId: 'throttle', decisionId: run.decisions[0].decisionId, directive: 'continue' });
		run.advance(2_000);
		run.sight({ observation: change({ ...observation(), world: { worldId: 'w', dimension: 'minecraft:overworld' } }) });
		await turn();
		assert.equal(run.decisions.length, 2, 'a hazard edge reaches the model at once');
	}
});

test('hazard edges compare against the previous facts only', async () => {
	const { hazardEdge } = await import('../src/native-program-executor.mjs');
	const lava = { blocks: [{ x: 1, y: 2, z: 3, blockId: 'minecraft:lava' }], player: { health: 20, air: 300 } };
	assert.equal(hazardEdge(lava, lava), false, 'lava already in view is not a new edge');
	assert.equal(hazardEdge({ blocks: [], player: { health: 20 } }, lava), true);
	assert.equal(hazardEdge({ player: { health: 20, air: 300 } }, { player: { health: 20, air: 280 } }), true);
	assert.equal(hazardEdge({ player: { health: 18 } }, { player: { health: 20 } }), false, 'healing is not a hazard');
	assert.equal(hazardEdge(null, lava), false);
});
