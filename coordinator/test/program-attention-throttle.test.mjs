import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';

const record = { agentId: 'attention-throttle', goalRevision: 1, provider: 'claude', model: 'selected-model', reasoningEffort: 'low', serviceTier: 'priority' };
const observation = (health = 20, { entities = [], player = {}, blocks = [] } = {}) => ({ player: { x: 0, y: 64, z: 0, health, ...player }, entities, items: [], blocks, inventory: { items: [], tagCounts: {} } });
const turn = () => new Promise(resolve => setImmediate(resolve));

function setup(t, policy = 'continue_and_notify') {
	let now = 1_000_000, sequence = 1;
	let latestObservation = observation();
	const timers = [], decisions = [], pending = [];
	const executor = new NativeProgramExecutor({
		setTimeoutFn: (callback, ms) => { const timer = { callback, ms, cleared: false }; timers.push(timer); return timer; },
		clearTimeoutFn: timer => { if (timer) timer.cleared = true; },
		now: () => now,
		ordinaryAttentionIntervalMs: 30_000,
	});
	const result = executor.run(record, { source: `program.onUnhandledAttention("${policy}"); await player.wait(1); await player.wait(2);`, programId: 'throttle' }, {
		observation: latestObservation, eventSequence: 1,
		executeAction: () => new Promise((resolve) => { pending.push(resolve); }),
		cancelAction: async () => { for (const resolve of pending.splice(0)) resolve({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED', observation: latestObservation, eventSequence: ++sequence }); return { state: 'CANCELLED' }; },
		onDecision: (status, { priority }) => decisions.push({ decisionId: status.decision?.decisionId, eventSequence: status.decision?.eventSequence, priority }),
	});
	t.after(() => executor.cancel(record.agentId));
	const sight = (extra = {}) => {
		const { health, entities, player, blocks, ...attention } = extra;
		latestObservation = observation(health, { entities, player, blocks });
		return executor.onObservation(record, { observation: latestObservation, eventSequence: ++sequence, attention: true, priority: 'ordinary', trigger: 'resource_discovery', ...attention });
	};
	const finishAction = () => { for (const resolve of pending.splice(0)) resolve({ state: 'SUCCEEDED', reasonCode: 'DONE', observation: latestObservation, eventSequence: ++sequence }); };
	return { executor, result, timers, decisions, sight, finishAction, advance: (ms) => { now += ms; } };
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

test('an urgent batch notifies the latest sequence and accepts continue without a status round trip', async t => {
	const run = setup(t);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18 });
	run.sight({ priority: 'urgent', trigger: 'damage', health: 16 });
	run.sight({ priority: 'urgent', trigger: 'damage', health: 14 });
	await turn();
	assert.equal(run.decisions.length, 1, 'urgent observations in one bridge batch coalesce');
	const { decisionId, eventSequence } = run.decisions[0];
	assert.equal(run.executor.status(record).decision.eventSequence, eventSequence, 'the live handle carries the latest observed facts');
	assert.equal(run.executor.status(record).decision.trigger, 'damage');
	assert.equal(eventSequence, 5, 'notification records the facts the model actually received');
	assert.equal(run.executor.respond(record, { programId: 'throttle', decisionId, directive: 'continue' }).engineState, 'ACTIVE');
});

test('continue accepts more damage from the same attacker within the ongoing-danger threshold', async t => {
	const run = setup(t);
	const zombie = { stableId: 'zombie-a', type: 'minecraft:zombie', hostile: true, x: 1, y: 64, z: 0 };
	run.sight();
	await turn();
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombie], player: { lastAttacker: { uuid: 'zombie-a' } } });
	await turn();
	assert.equal(run.decisions.length, 2, 'the urgent facts are notified after the ordinary attention');
	const notified = run.decisions.at(-1);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 16, entities: [zombie], player: { lastAttacker: { uuid: 'zombie-a' } } });
	assert.equal(run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
		eventSequence: notified.eventSequence, directive: 'continue' }).engineState, 'ACTIVE');
});

test('continue rejects a health loss greater than two hearts even from the same attacker', async t => {
	const run = setup(t);
	const zombie = { stableId: 'zombie-a', type: 'minecraft:zombie', hostile: true, x: 1, y: 64, z: 0 };
	run.sight();
	await turn();
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombie], player: { lastAttacker: { uuid: 'zombie-a' } } });
	await turn();
	const notified = run.decisions.at(-1);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 12, entities: [zombie], player: { lastAttacker: { uuid: 'zombie-a' } } });
	assert.throws(() => run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
		eventSequence: notified.eventSequence, directive: 'continue' }), (error) => {
		assert.equal(error.code, 'STALE_PROGRAM_DECISION');
		assert.equal(error.freshDecision.facts.health.droppedBy, 6);
		return true;
	});
});

test('materially new danger rejects continue with fresh decision facts for an immediate retry', async t => {
	const run = setup(t);
	const zombie = { stableId: 'zombie-a', type: 'minecraft:zombie', hostile: true, x: 1, y: 64, z: 0 };
	const creeper = { stableId: 'creeper-b', type: 'minecraft:creeper', hostile: true, swelling: false, x: 4, y: 64, z: 0 };
	run.sight();
	await turn();
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombie], player: { lastAttacker: { uuid: 'zombie-a' } } });
	await turn();
	assert.equal(run.decisions.length, 2, 'the urgent facts are notified after the ordinary attention');
	const notified = run.decisions.at(-1);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombie, creeper], player: { lastAttacker: { uuid: 'zombie-a' } } });
	let rejection;
	try {
		run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
			eventSequence: notified.eventSequence, directive: 'continue' });
	} catch (error) { rejection = error; }
	assert.equal(rejection?.code, 'STALE_PROGRAM_DECISION');
	assert.equal(rejection?.freshDecision?.eventSequence, run.executor.status(record).decision.eventSequence);
	assert.equal(rejection?.freshDecision?.trigger, 'damage');
	assert.deepEqual(rejection?.freshDecision?.facts?.newThreats?.map(({ uuid, type }) => ({ uuid, type })),
		[{ uuid: 'creeper-b', type: 'minecraft:creeper' }]);
	assert.match(rejection?.message ?? '', /Fresh decision:/, 'the fresh handle and facts are inline in the tool rejection');
	assert.equal(run.executor.respond(record, { programId: 'throttle', decisionId: rejection.freshDecision.decisionId,
		eventSequence: rejection.freshDecision.eventSequence, directive: 'continue' }).engineState, 'ACTIVE',
		'the tool rejection itself supplies the retry facts without programStatus');
});

test('continue rejects a newly attacking entity and a creeper that starts swelling', async t => {
	await t.test('new attacker', async subtest => {
		const run = setup(subtest);
		const zombieA = { stableId: 'zombie-a', type: 'minecraft:zombie', hostile: true, x: 1, y: 64, z: 0 };
		const zombieB = { stableId: 'zombie-b', type: 'minecraft:zombie', hostile: true, x: 2, y: 64, z: 0 };
		run.sight();
		await turn();
		run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombieA], player: { lastAttacker: { uuid: 'zombie-a' } } });
		await turn();
		const notified = run.decisions.at(-1);
		run.sight({ priority: 'urgent', trigger: 'damage', health: 18, entities: [zombieA, zombieB], player: { lastAttacker: { uuid: 'zombie-b' } } });
		assert.throws(() => run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
			eventSequence: notified.eventSequence, directive: 'continue' }), { code: 'STALE_PROGRAM_DECISION' });
	});
	await t.test('creeper starts swelling', async subtest => {
		const run = setup(subtest);
		const creeper = { stableId: 'creeper-a', type: 'minecraft:creeper', hostile: true, swelling: false, x: 4, y: 64, z: 0 };
		run.sight();
		await turn();
		run.sight({ priority: 'urgent', trigger: 'threat', entities: [creeper] });
		await turn();
		const notified = run.decisions.at(-1);
		run.sight({ priority: 'urgent', trigger: 'threat', entities: [{ ...creeper, swelling: true }] });
		assert.throws(() => run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
			eventSequence: notified.eventSequence, directive: 'continue' }), (error) => {
			assert.equal(error.code, 'STALE_PROGRAM_DECISION');
			assert.equal(error.freshDecision.facts.changedThreats[0].changes.swelling, true);
			return true;
		});
	});
});

test('urgent-to-urgent trigger escalation keeps the same decision handle and refreshes the trigger', async t => {
	const run = setup(t);
	run.sight({ priority: 'urgent', trigger: 'damage', health: 18 });
	await turn();
	const decisionId = run.decisions[0].decisionId;
	run.sight({ priority: 'urgent', trigger: 'suffocation', health: 18 });
	await turn();
	assert.equal(run.decisions.length, 2, 'a materially different urgent trigger still notifies');
	assert.equal(run.decisions[1].decisionId, decisionId, 'urgent escalation does not invalidate the handle already shown');
	assert.equal(run.executor.status(record).decision.trigger, 'suffocation');
	assert.equal(run.executor.status(record).decision.eventSequence, 4);
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

test('a folded ordinary decision is not rejected for an older eventSequence', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	const notified = run.decisions[0];
	run.advance(3_000);
	run.sight();
	await turn();
	assert.equal(run.executor.status(record).decision.eventSequence, notified.eventSequence + 1);
	assert.equal(run.executor.respond(record, { programId: 'throttle', decisionId: notified.decisionId,
		eventSequence: notified.eventSequence, directive: 'continue' }).engineState, 'ACTIVE');
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

test('a routine whose source ran out does not wait out the window for its pending decision', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	run.executor.respond(record, { programId: 'throttle', decisionId: run.decisions[0].decisionId, directive: 'continue' });
	run.advance(3_000);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 1, 'while the body works the sighting waits for the window');
	assert.ok(run.timers.some(timer => timer.ms === 27_000 && !timer.cleared));
	run.finishAction();
	await turn();
	run.finishAction();
	for (let index = 0; index < 5; index++) await turn();
	assert.equal(run.decisions.length, 2, 'the idle body notifies at once instead of after the window');
	assert.ok(run.timers.filter(timer => timer.ms === 27_000).every(timer => timer.cleared), 'the deferred timer is retired');
});

test('sightings on an idle body fold into the pending decision: one notification, and the in-flight answer stays valid', async t => {
	const run = setup(t);
	run.sight();
	await turn();
	assert.equal(run.decisions.length, 1);
	const { decisionId } = run.decisions[0];
	run.finishAction();
	await turn();
	run.finishAction();
	for (let index = 0; index < 5; index++) await turn();
	for (let index = 0; index < 4; index++) { run.advance(1_000); run.sight(); await turn(); }
	assert.equal(run.decisions.length, 1, 'no new handle and no new wake per observation');
	assert.equal(run.executor.status(record).decision.decisionId, decisionId);
	assert.doesNotThrow(() => run.executor.respond(record, { programId: 'throttle', decisionId, directive: 'continue' }), 'the model answers the handle it was given');
});
