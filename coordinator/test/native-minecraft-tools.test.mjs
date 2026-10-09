import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
	MINECRAFT_DYNAMIC_TOOLS,
	NATIVE_AGENT_INSTRUCTIONS,
	normalizeMinecraftToolCall,
	toolResultContent,
	minecraftCapabilities,
} from '../src/native-minecraft-tools.mjs';
import { ACTION_FIELDS } from '../src/constants.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { goalSpecFingerprint, parseGoalSpec } from '../src/goal-spec.mjs';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { TaskMemoryStore } from '../src/task-memory-store.mjs';

test('respondProgram carries the decision event sequence used to authorize a continue', () => {
	const tool = normalizeMinecraftToolCall('respondProgram', { programId: 'program-1', goalRevision: 1,
		decisionId: 'program-1:decision-2', eventSequence: 27, directive: 'continue' });
	assert.equal(tool.kind, 'respond_program');
	assert.equal(tool.eventSequence, 27);
});

test('respondProgram description retains directive semantics within the base byte budget', () => {
	const description = MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'respondProgram').description;
	assert.match(description, /continue preserves/i);
	assert.match(description, /replace.*fresh facts invalidate/i);
	assert.match(description, /pause stops/i);
	assert.match(description, /finish.*factual goal verification/i);
	assert.ok(Buffer.byteLength(description, 'utf8') <= 237, 'description stays within the base description byte count');
});

test('oversized real memory query pages round-trip every entry with advancing absolute offsets', async () => {
	const notebook = new ModelNotebook();
	const taskMemory = new TaskMemoryStore();
	const scope = { worldId: 'one', agentId: 'a', dimension: 'minecraft:overworld', goalRevision: 1 };
	for (let index = 0; index < 40; index++) {
		await notebook.writeNote('a', { worldId: 'one', key: `note-${index}`, text: 'x'.repeat(2048) });
		await taskMemory.remember(scope, { kind: 'lesson', key: `lesson-${index}`, label: `Lesson ${index}`, summary: 'x'.repeat(1024) });
	}
	for (const query of [
		(offset) => notebook.query('a', { worldId: 'one', offset, limit: 20 }),
		(offset) => taskMemory.query(scope, { kind: 'lesson', offset, limit: 20 }),
	]) {
		let offset = 0;
		let pageCount = 0;
		const entries = [];
		do {
			const page = await query(offset);
			const encoded = toolResultContent(page).contentItems[0].text;
			assert.ok(Buffer.byteLength(encoded) <= 16_384);
			const result = JSON.parse(encoded);
			assert.equal(result.offset, offset);
			assert.deepEqual(result.entries, page.entries.slice(0, result.entries.length));
			entries.push(...result.entries);
			assert.ok(result.nextOffset === null || result.nextOffset === offset + result.entries.length);
			assert.ok(result.nextOffset === null || result.nextOffset > offset);
			offset = result.nextOffset;
			assert.ok(++pageCount <= 40);
		} while (offset !== null);
		assert.ok(pageCount >= 3);
		assert.equal(entries.length, 40);
		assert.equal(new Set(entries.map(({ key }) => key)).size, 40);
	}
	await taskMemory.flush();
});

test('frontier kind filters are advertised and preserve the query discriminant', () => {
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS.find(({ name }) => name === 'exploreFrontier').inputSchema.properties.kind.enum, ['all', 'observed_block', 'unknown_cell']);
	for (const kind of ['all', 'observed_block', 'unknown_cell']) {
		assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', { kind }), { kind: 'explore_frontier', arguments: { kind, radius: 24, limit: 32 } });
	}
	assert.throws(() => normalizeMinecraftToolCall('exploreFrontier', { kind: 'destination' }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
});

test('oversized recovery compaction retains remembered provenance and reports omitted facts', () => {
	const remembered = { kind: 'placed', blockId: 'minecraft:crafting_table', x: 1, y: 64, z: 2, dimension: 'minecraft:overworld', worldId: 'one', remembered: true };
	const raw = { observation: { player: { health: 20 }, detail: 'x'.repeat(40_000), recovery: { alreadyHave: ['minecraft:crafting_table'], alreadyHaveFacts: Array.from({ length: 40 }, (_, index) => ({ ...remembered, x: index })), facts: 'Remembered (not currently verified): minecraft:crafting_table.' } } };
	const result = JSON.parse(toolResultContent(raw).contentItems[0].text);
	for (const recovery of [result.recovery, result.observation.recovery]) {
		assert.ok(recovery.alreadyHaveFacts.length > 0);
		assert.equal(recovery.alreadyHaveFacts.length + recovery.omittedAlreadyHaveFacts, 40);
		for (const fact of recovery.alreadyHaveFacts) assert.deepEqual(fact, { ...remembered, x: fact.x });
		assert.match(recovery.facts, /not currently verified/);
	}
});

test('capabilities reflect the shared action contract without inventing fields', () => {
	assert.deepEqual(minecraftCapabilities().actions.map(({ actionType, fields }) => ({ actionType, fields })), Object.entries(ACTION_FIELDS).map(([actionType, fields]) => ({ actionType, fields: [...fields] })));
	assert.deepEqual(minecraftCapabilities().actions.find(({ actionType }) => actionType === 'use_item').optionalFields, ['hand', 'expectedItemId', 'mode']);
	const copy = minecraftCapabilities();
	copy.actions[0].fields.push('invented');
	assert.ok(!minecraftCapabilities().actions[0].fields.includes('invented'));
	assert.deepEqual(normalizeMinecraftToolCall('capabilities', {}), { kind: 'capabilities' });
	const reference = minecraftCapabilities({ section: 'program' });
	assert.deepEqual(normalizeMinecraftToolCall('capabilities', { section: 'program' }), { kind: 'capabilities', section: 'program' });
	assert.equal(JSON.parse(toolResultContent(reference).contentItems[0].text).reference, reference.reference);
	assert.match(reference.reference, /program\.watch/);
});

test('inspection validates the exact server page and target contract', () => {
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'inventory', offset: 32 }), { kind: 'inspect', section: 'inventory', offset: 32, limit: 32 });
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'item', slot: 7, limit: 1 }), { kind: 'inspect', section: 'item', slot: 7, offset: 0, limit: 1 });
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'block', x: 1, y: 64, z: -2 }), { kind: 'inspect', section: 'block', x: 1, y: 64, z: -2, offset: 0, limit: 32 });
	assert.equal(normalizeMinecraftToolCall('inspect', { section: 'recipes', recipeId: 'minecraft:crafting_table' }).recipeId, 'minecraft:crafting_table');
	for (const args of [{ section: 'seed' }, { section: 'blocks', limit: 33 }, { section: 'blocks', offset: 4097 }, { section: 'item' }, { section: 'inventory', slot: 2 }, { section: 'block', x: 1, y: 64 }, { section: 'recipes', recipeId: 'invalid id' }, { section: 'menu', recipeId: 'minecraft:a' }]) {
		assert.throws(() => normalizeMinecraftToolCall('inspect', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('action handles and memory queries reject ambiguous or unbounded requests', () => {
	assert.deepEqual(normalizeMinecraftToolCall('startAction', { actionType: 'wait', arguments: { durationMs: 5 } }), { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 5 } });
	assert.deepEqual(normalizeMinecraftToolCall('cancelAction', { actionId: 'action-1', goalRevision: 4 }), { kind: 'cancel_action', actionId: 'action-1', goalRevision: 4 });
	assert.deepEqual(normalizeMinecraftToolCall('queryMemory', {}), { kind: 'query_memory', memoryKind: 'all', offset: 0, limit: 20 });
	assert.equal(normalizeMinecraftToolCall('notebook', { key: 'boundary', text: 'x'.repeat(2048) }).text.length, 2048);
	for (const [name, args] of [['cancelAction', { actionId: 'action-1' }], ['replaceAction', { actionId: 'action-1', goalRevision: 4, actionType: 'teleport', arguments: {} }], ['notebook', { key: 'a', text: 'x'.repeat(2049) }], ['queryMemory', { kind: 'secret' }], ['queryMemory', { offset: -1 }], ['exploreFrontier', { seek: 'nether' }]]) {
		assert.throws(() => normalizeMinecraftToolCall(name, args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('oversized observations retain input identity, freshness and explicit omissions', () => {
	const raw = {
		eventSequence: 8, freshness: { fresh: true, eventSequence: 8 },
		observation: {
			player: { health: 15 }, inventory: { items: [] }, revision: 10,
			coverage: { entities: { returned: 64, total: 80 } },
			interaction: { attackCooldown: 0.8, menu: { menuId: 'menu-9', stateRevision: 3, slots: [] }, input: { active: true, hand: 'off_hand' } },
			entities: Array.from({ length: 64 }, (_, id) => ({ id, name: 'x'.repeat(500) })),
		},
	};
	const result = JSON.parse(toolResultContent(raw).contentItems[0].text);
	assert.equal(result.truncated, true);
	assert.deepEqual(result.freshness, raw.freshness);
	assert.equal(result.observation.interaction.menu.stateRevision, 3);
	assert.equal(result.observation.interaction.input.hand, 'off_hand');
	assert.deepEqual(result.observation.coverage, raw.observation.coverage);
	assert.ok(result.observation.entities.length > 0);
	assert.ok(result.observation.entities.length < raw.observation.entities.length);
	assert.equal(result.observation.resultCoverage.entities.retained, result.observation.entities.length);
});

test('oversized inspection pages keep whole entries and a truthful continuation offset', () => {
	const raw = { section: 'inventory', revision: 7, offset: 10, coverage: { total: 40, returned: 20 }, entries: Array.from({ length: 20 }, (_, index) => ({ slot: index + 10, components: { text: 'x'.repeat(1500) } })) };
	const content = toolResultContent(raw).contentItems[0].text;
	const result = JSON.parse(content);
	assert.ok(Buffer.byteLength(content) <= 16_384);
	assert.ok(result.entries.length > 0 && result.entries.length < raw.entries.length);
	assert.deepEqual(result.entries, raw.entries.slice(0, result.entries.length));
	assert.equal(result.nextOffset, 10 + result.entries.length);
	assert.equal(result.revision, 7);
	assert.equal(result.coverage.resultTruncated, true);
});

test('forced inspection compaction preserves explicit empty omissions and terminal coverage', () => {
	for (const [offset, total] of [[0, 2], [64, 90], [0, 1]]) {
		// ObservationPage emits a deliberate empty page for one unrepresentable row.
		const coverage = { offset, limit: 1, total, returned: 0, hasMore: offset + 1 < total, nextOffset: offset + 1, complete: false, byteLimited: true, source: 'held_book', omittedEntry: { offset, reason: 'entry_exceeds_page_budget', utf8Bytes: 8230 } };
		const raw = { section: 'item', revision: 7, entries: [], coverage, outerDetails: 'x'.repeat(20_000) };
		const encoded = toolResultContent(raw).contentItems[0].text;
		const result = JSON.parse(encoded);
		assert.ok(Buffer.byteLength(encoded) <= 16_384);
		assert.deepEqual(result.entries, []);
		assert.deepEqual(result.coverage, { ...coverage, resultTruncated: true });
		assert.equal(result.nextOffset, offset + 1);
		assert.equal(result.reasonCode, undefined);
		assert.equal(result.section, 'item');
		assert.equal(result.revision, 7);
		assert.ok(result.omittedFields.includes('outerDetails'));
		const nested = JSON.parse(toolResultContent({ section: 'item', revision: 7, item: { slot: 0, itemId: 'minecraft:written_book', count: 1, details: 'x'.repeat(20_000) }, pages: { entries: [], coverage } }).contentItems[0].text);
		assert.deepEqual(nested.pages, { entries: [], coverage });
		assert.deepEqual(nested.item, { slot: 0, itemId: 'minecraft:written_book', count: 1 });
	}
	const complete = JSON.parse(toolResultContent({ entries: [], coverage: { returned: 0, hasMore: false, nextOffset: 0, complete: true }, detail: 'x'.repeat(20_000) }).contentItems[0].text);
	assert.equal(complete.coverage.complete, true);
	assert.equal(complete.coverage.hasMore, false);
	assert.equal(complete.nextOffset, 0);
});

test('Minecraft control guidance examples are valid executor tool calls', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/control-reference.md', import.meta.url), 'utf8');
	const turns = [...skill.matchAll(/```json executor-calls\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	const calls = [...skill.matchAll(/```json executor-call\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	assert.ok(calls.length >= 5, 'expected at least five executor-call examples');
	assert.ok(turns.some(({ calls: turnCalls }) => turnCalls.length >= 2), 'expected a multi-call turn example');

	const normalized = [...calls, ...turns.flatMap(({ calls: turnCalls }) => turnCalls)].map(({ tool, arguments: args }) => ({
		tool,
		result: normalizeMinecraftToolCall(tool, args),
	}));
	assert.ok(normalized.some(({ tool }) => tool === 'say'));
	assert.ok(normalized.some(({ tool }) => tool === 'mine'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'pick_up_item'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'craft_inventory'));
	assert.ok(normalized.some(({ tool }) => tool === 'finish'));
});

test('Minecraft control reference covers every executor tool and action with accepted and rejected examples', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/control-reference.md', import.meta.url), 'utf8');
	const parseExamples = (label) => [...skill.matchAll(new RegExp('```json ' + label + '\\s+([\\s\\S]*?)```', 'g'))]
		.map((match) => JSON.parse(match[1]));
	const goodCalls = parseExamples('executor-call');
	const badCalls = parseExamples('executor-bad-call');
	const expectedTools = MINECRAFT_DYNAMIC_TOOLS.map(({ name }) => name);
	const expectedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum;

	assert.deepEqual([...new Set(goodCalls.map(({ tool }) => tool))].sort(), [...expectedTools].sort());
	assert.deepEqual(
		[...new Set(goodCalls.filter(({ tool }) => tool === 'act').map(({ arguments: args }) => args.actionType))].sort(),
		[...expectedActions].sort(),
	);
	for (const { tool, arguments: args } of goodCalls) normalizeMinecraftToolCall(tool, args);
	for (const { arguments: args } of goodCalls.filter(({ tool }) => tool === 'runProgram' || tool === 'queueProgram')) {
		if (args.source !== undefined) parseArenaScript(args.source);
	}
	for (const { arguments: args } of goodCalls.filter(({ tool, arguments: args }) => tool === 'notebook' && args.key === 'bounded-wait')) parseArenaScript(args.text);
	assert.ok(badCalls.length >= 6, 'examples cover distinct malformed requests');
	for (const { tool, arguments: args } of badCalls) {
		assert.throws(() => normalizeMinecraftToolCall(tool, args), (error) => (
			error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS' || error?.code === 'UNKNOWN_MINECRAFT_TOOL'
		));
	}
});

test('program calls bound source bytes, action count and execution time', () => {
	const source = 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1);';
	assert.deepEqual(normalizeMinecraftToolCall('runProgram', { source }), { kind: 'run_program', source, maxActions: 64, timeoutMs: 30000 });
	for (const args of [{ source, maxActions: 257 }, { source, timeoutMs: 120001 }, { source: '😀'.repeat(20000) }, { source, planner: 'another-model' }]) assert.throws(() => normalizeMinecraftToolCall('runProgram', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
});

test('program parameters are detached bounded JSON and timing estimates respect the chosen deadline', () => {
	const source = 'program.onUnhandledAttention("pause_and_notify"); await player.wait(program.parameters().durationMs);';
	const parameters = { durationMs: 50, target: { x: 1, blockId: 'minecraft:stone' }, choices: [true, null, 2] };
	const result = normalizeMinecraftToolCall('runProgram', { source, parameters, expectedDurationMs: 50, timeoutMs: 5000 });
	assert.deepEqual(JSON.parse(JSON.stringify(result.parameters)), parameters);
	assert.ok(Object.isFrozen(result.parameters) && Object.isFrozen(result.parameters.target));
	parameters.target.x = 99;
	assert.equal(result.parameters.target.x, 1);
	assert.equal(result.expectedDurationMs, 50);
	assert.equal(result.timeoutMs, 5000);
	assert.equal(normalizeMinecraftToolCall('runProgram', { noteKey: 'routine', parameters: {}, expectedDurationMs: 30_000 }).expectedDurationMs, 30_000);
	const cyclic = {}; cyclic.self = cyclic;
	let nested = {};
	for (let index = 0; index < 18; index += 1) nested = { child: nested };
	for (const invalidParameters of [null, [], 1, cyclic, nested, { bad: undefined }, { bad: NaN }, { bad: () => {} }, { large: 'é'.repeat(2048) }, Object.fromEntries(Array.from({ length: 257 }, (_, index) => [`key${index}`, 0]))]) {
		assert.throws(() => normalizeMinecraftToolCall('runProgram', { source, parameters: invalidParameters }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	for (const expectedDurationMs of [0, 5001, 1.5, '50']) {
		assert.throws(() => normalizeMinecraftToolCall('runProgram', { source, expectedDurationMs, timeoutMs: 5000 }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('queued programs require exact predecessor identity, source choice and bounded precondition data', () => {
	const source = 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1);';
	const base = { afterProgramId: 'program-1', goalRevision: 3, programVersion: 1, source, precondition: 'player.state().health > 0' };
	assert.deepEqual(normalizeMinecraftToolCall('queueProgram', base), { kind: 'queue_program', ...base, maxActions: 64, timeoutMs: 30_000 });
	const { source: _source, ...identity } = base;
	const saved = normalizeMinecraftToolCall('queueProgram', { ...identity, noteKey: 'routine', parameters: { durationMs: 50 }, maxActions: 1, timeoutMs: 5000, observationIntervalMs: 100, expectedDurationMs: 50 });
	assert.equal(saved.noteKey, 'routine');
	assert.equal(saved.parameters.durationMs, 50);
	assert.equal(saved.maxActions, 1);
	assert.equal(saved.timeoutMs, 5000);
	assert.equal(saved.observationIntervalMs, 100);
	assert.equal(saved.expectedDurationMs, 50);
	for (const field of ['afterProgramId', 'goalRevision', 'programVersion', 'precondition', 'source']) {
		const incomplete = { ...base }; delete incomplete[field];
		assert.throws(() => normalizeMinecraftToolCall('queueProgram', incomplete), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	for (const override of [{ noteKey: 'also-source' }, { afterProgramId: '' }, { goalRevision: -1 }, { programVersion: 0 }, { programVersion: 1.5 }, { precondition: ' ' }, { precondition: 'é'.repeat(2049) }, { background: true }, { maxActions: 257 }, { timeoutMs: 120001 }, { timeoutMs: 1000, expectedDurationMs: 1001 }, { parameters: [] }]) {
		assert.throws(() => normalizeMinecraftToolCall('queueProgram', { ...base, ...override }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	assert.deepEqual(normalizeMinecraftToolCall('cancelQueuedProgram', { afterProgramId: 'program-1', goalRevision: 3, queueId: 'queue-1' }), { kind: 'cancel_queued_program', afterProgramId: 'program-1', goalRevision: 3, queueId: 'queue-1' });
	for (const args of [{ afterProgramId: 'program-1', goalRevision: 3 }, { afterProgramId: 'program-1', goalRevision: 3, queueId: ' ' }, { afterProgramId: 'program-1', goalRevision: 3, queueId: 'queue-1', programId: 'other' }]) {
		assert.throws(() => normalizeMinecraftToolCall('cancelQueuedProgram', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('oversized program results preserve factual status, body receipt references and omissions', () => {
	const raw = { state: 'YIELDED', reasonCode: 'PROGRAM_EXHAUSTED', programId: 'native-program-test', goalRevision: 3, programVersion: 1, pendingSuccessor: { queueId: 'queue-1', afterProgramId: 'native-program-test', goalRevision: 3, programVersion: 1, state: 'QUEUED', maxActions: 2, timeoutMs: 5000, sourceOrigin: 'source' }, actions: 64, eventSequence: 100, receipts: Array.from({ length: 64 }, (_, index) => ({ actionId: `engine:${index}`, bodyActionId: `native:${index}`, actionType: 'wait', sourceStepId: `step-${index}`, state: index === 63 ? 'FAILED' : 'SUCCEEDED', reasonCode: index === 63 ? 'INPUT_REJECTED' : '', executionStarted: true })), observation: { player: { health: 20 }, detail: 'x'.repeat(30000) } };
	const result = JSON.parse(toolResultContent(raw).contentItems[0].text);
	assert.equal(result.programId, raw.programId);
	assert.equal(result.goalRevision, 3);
	assert.equal(result.programVersion, 1);
	assert.deepEqual(result.pendingSuccessor, raw.pendingSuccessor);
	assert.equal(result.state, 'YIELDED');
	assert.equal(result.receipts.at(-1).bodyActionId, 'native:63');
	assert.equal(result.receipts.at(-1).state, 'FAILED');
	assert.equal(result.omittedReceipts + result.receipts.length, 64);
	assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 16384);
});

test('oversized terminal programs preserve whole-run counts and the exact handoff outcome beyond the receipt ring', () => {
	const retainedReceipts = Array.from({ length: 64 }, (_, index) => ({ actionId: `engine:${index + 192}`, bodyActionId: `native:${index + 192}`, actionType: 'wait', sourceStepId: `step-${index + 192}`, state: 'SUCCEEDED', reasonCode: 'DONE' }));
	const base = { state: 'YIELDED', reasonCode: 'PROGRAM_EXHAUSTED', programId: 'native-program-1', goalRevision: 3, programVersion: 1, actions: 256, actionsSucceeded: 256, actionsFailed: 0, receipts: retainedReceipts, observation: { player: { health: 20 }, detail: 'x'.repeat(30000) } };
	const handedOff = JSON.parse(toolResultContent({ ...base, successorProgramId: 'native-program-2' }).contentItems[0].text);
	assert.equal(handedOff.actions, 256);
	assert.equal(handedOff.actionsSucceeded, 256);
	assert.equal(handedOff.actionsFailed, 0);
	assert.equal(handedOff.successorProgramId, 'native-program-2');
	assert.ok(handedOff.receipts.length <= 64);
	assert.equal(handedOff.discardedSuccessor, undefined);
	const discardedSuccessor = { queueId: 'queue-1', afterProgramId: 'native-program-1', goalRevision: 3, programVersion: 1, state: 'QUEUED', maxActions: 2, timeoutMs: 5000, sourceOrigin: 'note', reasonCode: 'PREDECESSOR_NOT_SUCCESSFULLY_EXHAUSTED' };
	const discarded = JSON.parse(toolResultContent({ ...base, actionsSucceeded: 255, actionsFailed: 1, discardedSuccessor }).contentItems[0].text);
	assert.equal(discarded.actionsSucceeded, 255);
	assert.equal(discarded.actionsFailed, 1, 'the historical failure survives after leaving the 64-receipt ring');
	assert.ok(discarded.receipts.every(({ state }) => state === 'SUCCEEDED'));
	assert.deepEqual(discarded.discardedSuccessor, discardedSuccessor);
	assert.equal(discarded.successorProgramId, undefined);
	for (const result of [handedOff, discarded]) assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 16384);
});

test('generic oversized terminal metadata bounds successor summaries without losing cancellation facts', () => {
	const discardedSuccessor = { queueId: 'queue-1', afterProgramId: 'native-program-1', goalRevision: 3, programVersion: 1, state: 'QUEUED', maxActions: 2, timeoutMs: 5000, sourceOrigin: 'source', reasonCode: 'PREDECESSOR_NOT_SUCCESSFULLY_EXHAUSTED' };
	const raw = { state: 'CANCELLED', reasonCode: 'PROGRAM_CANCELLED', programId: 'native-program-1', actionsSucceeded: 50, actionsFailed: 1, discardedSuccessor: { ...discardedSuccessor, source: 'x'.repeat(30000), parameters: { privateData: 'x'.repeat(30000) } } };
	const text = toolResultContent(raw).contentItems[0].text;
	const result = JSON.parse(text);
	assert.equal(result.state, 'CANCELLED');
	assert.equal(result.reasonCode, 'PROGRAM_CANCELLED');
	assert.equal(result.actionsSucceeded, 50);
	assert.equal(result.actionsFailed, 1);
	assert.deepEqual(result.discardedSuccessor, discardedSuccessor);
	assert.ok(Buffer.byteLength(text) <= 16384);
});

test('native Minecraft tools expose the common fast path plus one validated advanced body operation', () => {
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS.map((tool) => tool.name), [
		'taskMemory', 'observe', 'capabilities', 'inspect', 'actionStatus', 'cancelAction', 'replaceAction', 'startAction', 'notebook', 'queryMemory', 'runProgram', 'queueProgram', 'cancelQueuedProgram', 'programStatus', 'respondProgram', 'cancelProgram', 'lookAround', 'survey', 'control', 'moveTo', 'exploreFrontier', 'mine', 'say', 'wait', 'act', 'sequence', 'taskPlan', 'takeTask', 'finish',
	]);
	assert.ok(MINECRAFT_DYNAMIC_TOOLS.every((tool) => tool.type === 'function'));
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /you.*choose every action/i);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'sequence').description, /Prefer sequence for safe 2\+ action chains/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /speech playback is asynchronous/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /exploreFrontier/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /death does not change the active goal/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /omitted or unobserved facts are unknown/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /background:true/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /queryMemory/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /reuse exact noteKey/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /prerequisites\/current targets/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /noteKey executes the entire note as source/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /Batch known independent reads and reuse fresh result facts/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /program\.parameters\(\)/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /queueProgram/);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'queueProgram').description, /runtime chooses no gameplay/);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'queueProgram').description, /Start requires successful natural PROGRAM_EXHAUSTED, no pending decision/);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'queueProgram').description, /optional expectedDurationMs must fit it/);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'runProgram').description, /noteKey executes the entire note text as ArenaScript/);
});

test('guidance tells the model to loop bulk work, reuse facts, stop polling and end turns without text', () => {
	const tools = Object.fromEntries(MINECRAFT_DYNAMIC_TOOLS.map((tool) => [tool.name, tool.description]));
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length <= 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /looping background:true program \(repeatUntil a count\), not per 2-3 blocks: an unqueued exhaustion needs another model decision/);
	assert.doesNotMatch(NATIVE_AGENT_INSTRUCTIONS, /exhaustion costs a decision/, 'a queued successor skips the model round');
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /When the next program is known, queueProgram it in the same turn with a side-effect-free precondition; only natural exhaustion starts it, with no model round/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /Reclaim workstations/);
	assert.match(tools.startAction, /routine changes wake you at most after 15 s and urgent ones at once; end your turn and its completion wakes you with fresh facts/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /reuse fresh result facts before observe\/inspect\. Never poll/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /End a turn by stopping, no closing text/);
	assert.doesNotMatch(tools.startAction, /actionStatus reads it sooner/, 'contradicts the no-polling rule');
	assert.match(tools.actionStatus, /Never poll/);
	assert.match(tools.programStatus, /Never poll/);
	assert.match(tools.observe, /skip it when a fresh postAction sample has them/);
	assert.match(tools.moveTo, /give the far target/);
	assert.match(tools.mine, /turns to the block's center itself/);
	assert.match(tools.mine, /collected while you keep mining; pick up stragglers once at the end/);
	assert.match(tools.survey, /One call batches several of these reads/);
	assert.match(tools.inspect, /several visible-world reads use survey/);
});

test('optional mining aim expands only the exact caller-chosen block and preserves the single-action default', () => {
	const args = { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone', timeoutMs: 5000 };
	const direct = { kind: 'action', actionType: 'break_block', arguments: args };
	assert.deepEqual(normalizeMinecraftToolCall('mine', args), direct);
	assert.deepEqual(normalizeMinecraftToolCall('mine', { ...args, autoAim: false }), direct);
	assert.deepEqual(normalizeMinecraftToolCall('act', { actionType: 'break_block', arguments: args }), direct);
	assert.deepEqual(normalizeMinecraftToolCall('mine', { ...args, autoAim: true }), {
		kind: 'sequence', actions: [
			{ actionType: 'look_at', arguments: { x: 2.5, y: 63.5, z: 4.5 } },
			{ actionType: 'break_block', arguments: args },
		],
	});
	for (const override of [{ autoAim: 'true' }, { autoAim: true, expectedBlockId: 'minecraft:air' }, { autoAim: true, targetSelector: 'nearest' }]) {
		assert.throws(() => normalizeMinecraftToolCall('mine', { ...args, ...override }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'break_block', arguments: { ...args, autoAim: true } }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, { actionType: 'break_block', arguments: { ...args, autoAim: true } }] }), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
});

test('advertised native actions exactly match Java model-authored dispatch', async () => {
	const executor = await readFile(new URL('../../src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java', import.meta.url), 'utf8');
	const allowlist = executor.match(/ARENA_SCRIPT_PRIMITIVES\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1] ?? '';
	const javaActions = [...allowlist.matchAll(/ActionType\.([A-Z_]+)/g)]
		.map(([, name]) => name.toLowerCase())
		.sort();
	const advertisedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum
		.toSorted();

	assert.deepEqual(advertisedActions, javaActions);
	assert.ok(advertisedActions.includes('pick_up_item'), 'working Java pickup controller remains reachable');
	assert.ok(advertisedActions.includes('fight_target') && advertisedActions.includes('flee_from'), 'Java fight/flee controllers are reachable');
	for (const unsupportedComposite of ['build_sequence', 'follow_entity']) {
		assert.ok(!advertisedActions.includes(unsupportedComposite), `${unsupportedComposite} is not advertised without native dispatch`);
	}
});

test('native Minecraft tool calls normalize to exact existing body actions', () => {
	assert.deepEqual(normalizeMinecraftToolCall('control', {
		forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true,
		attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20,
	}), {
		kind: 'action', actionType: 'control',
		arguments: { forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true, attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('lookAround', {
		centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	}), {
		kind: 'lookAround', centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	});
	assert.deepEqual(normalizeMinecraftToolCall('moveTo', { x: 1, y: 64, z: -2 }), {
		kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: -2, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', {}), {
		kind: 'explore_frontier', arguments: { radius: 24, limit: 32 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', { blockId: 'minecraft:stone', radius: 24, limit: 16 }), {
		kind: 'explore_frontier', arguments: { blockId: 'minecraft:stone', radius: 24, limit: 16 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('mine', { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone' }), {
		kind: 'action', actionType: 'break_block', arguments: { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'hi', recipientId: '550e8400-e29b-41d4-a716-446655440000' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'hi', audience: 'direct', recipientId: '550e8400-e29b-41d4-a716-446655440000' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'On it.', audience: 'proximity' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'On it.', audience: 'proximity' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('finish', {
		summary: 'Stone acquired.',
	}), {
		kind: 'finish', summary: 'Stone acquired.',
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	}), {
		kind: 'action', actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	}), {
		kind: 'action', actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('sequence', {
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone' } },
		],
	}), {
		kind: 'sequence',
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
		],
	});
});

test('native Minecraft boundary rejects unknown, oversized, and malformed calls', () => {
	assert.throws(() => normalizeMinecraftToolCall('attack', {}), (error) => error?.code === 'UNKNOWN_MINECRAFT_TOOL');
	assert.throws(() => normalizeMinecraftToolCall('moveTo', { x: '1', y: 2, z: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('mine', { x: 1, y: 64, z: 2 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('control', { forward: 1 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 1, ticksPerStep: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'x'.repeat(257) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'direct' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'proximity', recipientId: 'agent-b' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('finish', { summary: 'done', completionContract: {} }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:oak_planks' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'pick_up_item', arguments: { targetSelector: 'nearest_item' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'fight_target', arguments: { targetSelector: 'zombie', desiredRange: 2, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'build_sequence', arguments: { placements: [], timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'flee_from', arguments: { targetSelector: 'target', distance: 8, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'follow_entity', arguments: { targetSelector: 'target', distance: 3, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }] }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: Array.from({ length: 9 }, () => ({ actionType: 'wait', arguments: { durationMs: 1 } })) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
});

test('advanced actions cannot override their discriminator through nested arguments', () => {
	for (const type of ['wait', 'attack']) {
		const action = { actionType: 'attack', arguments: { type, durationMs: 100 } };
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('all native mining paths reject air before dispatch', () => {
	for (const expectedBlockId of ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']) {
		const args = { x: 2, y: 63, z: 4, expectedBlockId, timeoutMs: 15_000 };
		const action = { actionType: 'break_block', arguments: args };
		assert.throws(() => normalizeMinecraftToolCall('mine', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('tool results are compact deterministic inputText content', () => {
	assert.deepEqual(toolResultContent({ state: 'SUCCEEDED', reasonCode: '' }), {
		success: true,
		contentItems: [{ type: 'inputText', text: '{"state":"SUCCEEDED","reasonCode":""}' }],
	});
	assert.match(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text, /TRUNCATED/);
	assert.equal(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text.length <= 16_384, true);
	const truncatedDeath = toolResultContent({
		observation: {
			player: { health: 0, dead: true },
			inventory: { items: Array.from({ length: 64 }, (_, index) => ({ itemId: `minecraft:filler_${index}`, count: 64 })) },
			death: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
			recovery: {
				lastDeath: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Current inventory is empty. Lost on death: minecraft:stone_pickaxe.',
			},
			failureClass: 'recover',
			world: { dimension: 'minecraft:overworld' },
			blocks: Array.from({ length: 400 }, (_, index) => ({ blockId: 'minecraft:stone', x: index, y: 64, z: 0 })),
		},
	});
	assert.match(truncatedDeath.contentItems[0].text, /lastDeath/);
	assert.match(truncatedDeath.contentItems[0].text, /alreadyHave/);
	assert.match(truncatedDeath.contentItems[0].text, /stone_pickaxe/);
	assert.doesNotMatch(truncatedDeath.contentItems[0].text, /"state":"TRUNCATED"/);
	const oversizedIds = {
		observation: {
			recovery: {
				lastLostInventory: Array.from({ length: 16 }, (_, index) => ({
					itemId: `minecraft:${'a'.repeat(240)}_${index}`,
					count: 64,
				})),
				alreadyHave: Array.from({ length: 32 }, (_, index) => `minecraft:${'b'.repeat(240)}_${index}`),
				doNotRedo: Array.from({ length: 24 }, (_, index) => `minecraft:${'c'.repeat(240)}_${index}`),
				facts: 'f'.repeat(8_000),
			},
		},
	};
	const bounded = toolResultContent(oversizedIds);
	assert.equal(bounded.contentItems[0].text.length <= 16_384, true);
	assert.ok(Buffer.byteLength(bounded.contentItems[0].text, 'utf8') <= 16_384);
});

test('tool result byte cap still applies when survival facts are oversized', () => {
	const text = toolResultContent({
		observation: {
			death: { cause: 'lava', x: 1, y: 64, z: 2, dimensionId: 'minecraft:overworld' },
			recovery: { facts: 'x'.repeat(40_000) },
		},
	}).contentItems[0].text;
	assert.equal(Buffer.byteLength(text, 'utf8') <= 16_384, true);
});

test('oversized sequence results retain every authoritative step status', () => {
	const content = toolResultContent({
		state: 'SUCCEEDED', completed: 8,
		results: Array.from({ length: 8 }, (_, index) => ({
			actionType: 'break_block', state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}`,
			actionObservation: { detail: 'x'.repeat(8_000), step: index + 1 },
		})),
	});
	assert.equal(content.contentItems[0].text.length <= 16_384, true);
	const result = JSON.parse(content.contentItems[0].text);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.results.length, 8);
	assert.deepEqual(result.results.map(({ state, reasonCode }) => ({ state, reasonCode })), Array.from({ length: 8 }, (_, index) => ({ state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}` })));
});

test('invalid advanced action returns its full field contract for one-step correction', () => {
 assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'menu_close', arguments: {} }), error => {
  assert.equal(error.code, 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
  assert.deepEqual(error.actionContract.requiredFields, ['menuId', 'containerId', 'stateId']);
  assert.deepEqual(error.actionContract.optionalFields, []);
  assert.match(error.actionContract.hint, /namespaced string/);
  return true;
 });
});

test('oversized action feedback retains its receipt and fresh inventory', () => {
 const result = toolResultContent({ state: 'SUCCEEDED', reasonCode: 'ITEM_PICKED_UP', actionId: 'pickup-1', postAction: {
  freshness: { fresh: true }, eventSequence: 42,
  observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] }, entities: [], blocks: Array.from({ length: 800 }, (_, x) => ({ x, y: 64, z: 0, blockId: 'minecraft:stone' })) }
 }});
 const text = result.contentItems[0].text;
 assert.ok(Buffer.byteLength(text, 'utf8') <= 16_384);
 const decoded = JSON.parse(text);
 assert.equal(decoded.state, 'SUCCEEDED');
 assert.equal(decoded.reasonCode, 'ITEM_PICKED_UP');
 assert.equal(decoded.actionId, 'pickup-1');
 assert.equal(decoded.postAction.freshness.fresh, true);
 assert.equal(decoded.postAction.eventSequence, 42);
 assert.equal(decoded.postAction.observation.inventory.items[0].count, 4);
 assert.equal(decoded.postAction.truncated, true);
});

test('oversized action observation cannot crowd out fresh post-action inventory', () => {
	const text = toolResultContent({ state: 'FAILED', reasonCode: 'ITEM_NOT_FOUND', physicalAttempted: false,
		actionObservation: { detail: 'x'.repeat(40_000) }, recoveryHint: 'Check current inventory.',
		postAction: { eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] } } },
	}).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16_384);
	const result = JSON.parse(text);
	assert.equal(result.state, 'FAILED');
	assert.equal(result.reasonCode, 'ITEM_NOT_FOUND');
	assert.equal(result.physicalAttempted, false);
	assert.equal(result.recoveryHint, 'Check current inventory.');
	assert.equal(result.postAction.observation.inventory.items[0].count, 4);
	assert.equal(result.postAction.freshness.fresh, true);
});

test('oversized sequence preserves its final feedback and every factual receipt', () => {
	const text = toolResultContent({ state: 'FAILED', completed: 2, failedAt: 1,
		results: [{ actionType: 'break_block', state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', actionObservation: { detail: 'x'.repeat(20_000) } },
			{ actionType: 'break_block', state: 'FAILED', reasonCode: 'TARGET_OBSTRUCTED', physicalAttempted: false }],
		postAction: { eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 1 }] } } },
	}).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16_384);
	const result = JSON.parse(text);
	assert.equal(result.state, 'FAILED');
	assert.equal(result.failedAt, 1);
	assert.deepEqual(result.results.map(step => step.state), ['SUCCEEDED', 'FAILED']);
	assert.equal(result.results[1].physicalAttempted, false);
	assert.equal(result.postAction.observation.inventory.items[0].count, 1);
	assert.equal(result.postAction.eventSequence, 42);
});

test('maximum multibyte goal text cannot erase sequence receipts or fresh inventory', () => {
	const originalRequest = '\u6728'.repeat(4096);
	const fields = { originalRequest, predicate: { type: 'inventory_contains', itemId: 'minecraft:oak_log', count: 4 }, createdAtTick: 1 };
	const goalSpec = parseGoalSpec({ ...fields, fingerprint: goalSpecFingerprint(fields) });
	const text = toolResultContent({ state: 'FAILED', completed: 2, failedAt: 1,
		results: [{ actionType: 'navigate_to', state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' },
			{ actionType: 'break_block', state: 'FAILED', reasonCode: 'TARGET_OBSTRUCTED', physicalAttempted: false }],
		postAction: { goal: originalRequest, goalSpec, eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 1, slot: 0 }] } } },
	}).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16_384);
	const result = JSON.parse(text);
	assert.equal(result.completed, 2);
	assert.equal(result.failedAt, 1);
	assert.deepEqual(result.results.map(step => step.reasonCode), ['DESTINATION_REACHED', 'TARGET_OBSTRUCTED']);
	assert.equal(result.postAction.observation.inventory.items[0].count, 1);
	assert.equal(result.postAction.freshness.fresh, true);
	assert.deepEqual(result.postAction.goalSpec.predicate, goalSpec.predicate);
	assert.equal(result.postAction.goalSpec.fingerprint, goalSpec.fingerprint);
});

test('a valid oversized completion predicate leaves receipts and inventory within budget', () => {
	const fields = { originalRequest: 'Collect these items', createdAtTick: 1,
		predicate: { type: 'inventory_contains_any', count: 1,
			itemIds: Array.from({ length: 64 }, (_, index) => `minecraft:item_${index}_${'x'.repeat(238)}`),
		},
	};
	const goalSpec = parseGoalSpec({ ...fields, fingerprint: goalSpecFingerprint(fields) });
	const text = toolResultContent({ state: 'SUCCEEDED', completed: 2,
		results: [{ actionType: 'look_at', state: 'SUCCEEDED', reasonCode: 'LOOKED_AT' }, { actionType: 'break_block', state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN' }],
		postAction: { goalSpec, eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4, slot: 0 }] } } },
	}).contentItems[0].text;
	assert.ok(Buffer.byteLength(text) <= 16_384);
	const result = JSON.parse(text);
	assert.equal(result.completed, 2);
	assert.deepEqual(result.results.map(step => step.reasonCode), ['LOOKED_AT', 'BLOCK_BROKEN']);
	assert.equal(result.postAction.observation.inventory.items[0].count, 4);
	assert.equal(result.postAction.observation.resultCoverage.inventory.retained, 1);
	assert.equal(result.postAction.freshness.fresh, true);
	assert.equal(result.postAction.truncated, true);
});

test('sequence omission notice cannot push retained receipts over the byte limit', () => {
	for (let detailBytes = 15_700; detailBytes <= 16_000; detailBytes += 10) {
		const text = toolResultContent({ state: 'FAILED', completed: 2, failedAt: 1,
			results: [{ actionType: 'navigate_to', state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED', actionObservation: { detail: 'x'.repeat(detailBytes) } },
				{ actionType: 'break_block', state: 'FAILED', reasonCode: 'TARGET_OBSTRUCTED', physicalAttempted: false, actionObservation: { detail: 'y'.repeat(20_000) } }],
			postAction: { eventSequence: 42, freshness: { fresh: true }, observation: { inventory: { items: [{ itemId: 'minecraft:oak_log', count: 1, slot: 0 }] } } },
		}).contentItems[0].text;
		assert.ok(Buffer.byteLength(text) <= 16_384);
		const result = JSON.parse(text);
		assert.equal(result.completed, 2, `retained observation has ${detailBytes} bytes`);
		assert.equal(result.failedAt, 1);
		assert.deepEqual(result.results.map(step => step.reasonCode), ['DESTINATION_REACHED', 'TARGET_OBSTRUCTED']);
		assert.equal(result.postAction.observation.inventory.items[0].count, 1);
	}
});
