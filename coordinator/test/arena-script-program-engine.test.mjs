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
	assert.equal(modelRequests[0].eventSequence, 3);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['wait']);
});
