import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptEngine } from '../src/arena-script/program-engine.mjs';

function observation(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20 },
		items: [], entities: [], blocks: [],
		inventory: { items: [], tagCounts: { '#minecraft:logs': 0 } },
		...overrides,
	};
}

function engineFor(source, callbacks = {}) {
	const dispatched = [];
	const modelRequests = [];
	const cancelled = [];
	const engine = new ArenaScriptEngine({
		dispatch: (command) => dispatched.push(command),
		cancel: (actionId) => cancelled.push(actionId),
		requestModel: (context) => modelRequests.push(context),
		...callbacks,
	});
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1,
		compiled: parseArenaScript(source), observation: callbacks.initialObservation ?? observation(), eventSequence: 1,
	});
	return { engine, dispatched, cancelled, modelRequests };
}

function acknowledge(engine, dispatched, observationValue, eventSequence) {
	const command = dispatched.at(-1);
	engine.ingestActionResult({ actionId: command.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence });
	engine.ingestObservation({ observation: observationValue, eventSequence, attention: false });
}

test('measures a multi-tree pickup loop instead of assuming a tree yield or pickup range', () => {
	const source = `
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(
			() => inventory.countTag("#minecraft:logs") >= 8,
			{ maxIterations: 8 },
			async () => {
				const drop = world.nearest(world.items({ tag: "#minecraft:logs", reachable: true }));
				if (drop !== null) { await player.moveTo(drop.position); return; }
				const tree = world.nearest(world.blocks({ tag: "#minecraft:logs", reachable: true }));
				if (tree !== null) await player.mine(tree.position);
			}
		);
		program.finish("Collected at least eight logs");
	`;
	const { engine, dispatched } = engineFor(source, { initialObservation: observation({
		blocks: [{ stableId: 'tree-one', blockId: 'minecraft:oak_log', x: 4, y: 64, z: 0, reachable: true, tags: ['#minecraft:logs'] }],
	}) });
	assert.equal(dispatched.at(-1).action.type, 'break_block');
	acknowledge(engine, dispatched, observation({
		items: [{ stableId: 'drop-five', itemId: 'minecraft:oak_log', count: 5, x: 8, y: 64, z: 0, reachable: true, tags: ['#minecraft:logs'] }],
	}), 2);
	assert.equal(dispatched.at(-1).action.type, 'move_to');
	acknowledge(engine, dispatched, observation({
		blocks: [{ stableId: 'tree-two', blockId: 'minecraft:oak_log', x: 10, y: 64, z: 0, reachable: true, tags: ['#minecraft:logs'] }],
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 5 }], tagCounts: { '#minecraft:logs': 5 } },
	}), 3);
	assert.equal(dispatched.at(-1).action.type, 'break_block');
	acknowledge(engine, dispatched, observation({
		items: [{ stableId: 'drop-three', itemId: 'minecraft:oak_log', count: 3, x: 8, y: 64, z: 0, reachable: true, tags: ['#minecraft:logs'] }],
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 5 }], tagCounts: { '#minecraft:logs': 5 } },
	}), 4);
	assert.equal(dispatched.at(-1).action.type, 'move_to');
	acknowledge(engine, dispatched, observation({
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 8 }], tagCounts: { '#minecraft:logs': 8 } },
	}), 5);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['break_block', 'move_to', 'break_block', 'move_to']);
	assert.equal(engine.snapshot().status, 'FINISHED');
});

test('watchers fire on false-to-true edges and boundary handlers wait for the action result', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(1); });
		await player.moveTo({ x: 4, y: 64, z: 0 });
	`);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 3, attention: true });
	assert.deepEqual(dispatched.map((row) => row.action.type), ['move_to']);
	acknowledge(engine, dispatched, observation({ player: { x: 4, y: 64, z: 0, health: 19 } }), 4);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['move_to', 'wait']);
});

test('interrupt watchers wait for cancellation acknowledgement and unmatched attention follows the authored policy', () => {
	const { engine, dispatched, cancelled, modelRequests } = engineFor(`
		program.onUnhandledAttention("pause_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(1); });
		await player.moveTo({ x: 4, y: 64, z: 0 });
	`);
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	assert.deepEqual(cancelled, [active.actionId]);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['move_to']);
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 2 });
	assert.deepEqual(dispatched.map((row) => row.action.type), ['move_to', 'wait']);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: true });
	assert.equal(modelRequests.length, 1);
	assert.equal(engine.snapshot().status, 'SUSPENDING');
});

test('coalesces unmatched continue policy notifications and ignores stale events without commands', () => {
	const { engine, dispatched, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 1, attention: true });
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].eventSequence, 2);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['wait']);
});

test('fences a replacement behind cancellation and rejects an old action result by generation', () => {
	const { engine, dispatched, cancelled } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const old = dispatched.at(-1);
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-b', version: 2,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(2);'), observation: observation(), eventSequence: 2,
	});
	assert.deepEqual(cancelled, [old.actionId]);
	assert.equal(dispatched.length, 1);
	engine.ingestActionResult({ actionId: old.actionId, state: 'CANCELLED', reasonCode: 'REPLACED', eventSequence: 2 });
	assert.equal(dispatched.length, 2);
	assert.notEqual(dispatched[1].actionId, old.actionId);
	engine.ingestActionResult({ actionId: old.actionId, state: 'SUCCEEDED', reasonCode: 'LATE', eventSequence: 3 });
	assert.equal(dispatched.length, 2);
});

test('holds an action result until an authoritative observation at or after its result sequence', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 5 });
	engine.ingestObservation({ observation: observation(), eventSequence: 4, attention: false });
	assert.equal(dispatched.length, 1);
	engine.ingestObservation({ observation: observation(), eventSequence: 5, attention: false });
	assert.equal(dispatched.length, 2);
});

test('runs a latched boundary watcher before the base continuation and retains a transient edge', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); });
		await player.wait(1); await player.wait(2);
	`);
	const first = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: false });
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 9]);
});

test('keeps an immutable request identity seen by a one-argument model callback', () => {
	const requests = [];
	const { engine } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);', {
		requestModel: (context) => { requests.push(context); },
	});
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	assert.equal(requests.length, 1);
	assert.ok(Object.isFrozen(requests[0]));
	assert.equal(requests[0].eventSequence, 2);
	engine.applyDirective({ directive: 'pause', agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, generation: requests[0].generation, eventSequence: 2 });
	assert.equal(engine.snapshot().status, 'SUSPENDING');
});

test('rejects incomplete provenance before creating a VM or dispatching', () => {
	const engine = new ArenaScriptEngine({ dispatch() { assert.fail('must not dispatch'); }, cancel() {}, requestModel() {} });
	assert.throws(() => engine.install({ agentId: '', goalRevision: 1, modelIdentity: 'model-a', programId: 'p', version: 1, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify");'), observation: observation(), eventSequence: 1 }), TypeError);
	assert.equal(engine.snapshot().status, 'IDLE');
});

test('rearmer watcher edges after a false observation and disposal waits for cancellation', () => {
	const { engine, dispatched, cancelled } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(1);
	`);
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 2 });
	const firstReaction = dispatched.at(-1);
	engine.ingestActionResult({ actionId: firstReaction.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: false });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 4, attention: true });
	assert.equal(dispatched.filter((command) => command.action.arguments === 9).length, 2);
	const active = dispatched.at(-1);
	engine.dispose();
	assert.deepEqual(cancelled, [base.actionId, active.actionId]);
	assert.notEqual(engine.snapshot().status, 'IDLE');
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'DISPOSED', eventSequence: 4 });
	assert.equal(engine.snapshot().status, 'IDLE');
});

test('drains multiple boundary watchers in edge order before resuming the base continuation', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); });
		program.watch(() => player.state().health < 19, { mode: "boundary" }, async () => { await player.wait(8); });
		await player.wait(1); await player.wait(2);
	`);
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 2, attention: false });
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 3, attention: false });
	const second = dispatched.at(-1);
	engine.ingestActionResult({ actionId: second.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 4 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 4, attention: false });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 9, 8, 2]);
	assert.match(first.provenance.source, /^watcher:watcher-0$/);
	assert.match(second.provenance.source, /^watcher:watcher-1$/);
});
