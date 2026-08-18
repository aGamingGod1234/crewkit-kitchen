import assert from 'node:assert/strict';
import test from 'node:test';
import {
	evaluateHeadlessAssertions,
	normalizeHeadlessScenario,
	runHeadlessScenario,
	writeHeadlessReport,
} from '../src/headless-matrix.mjs';

const scenario = (overrides = {}) => normalizeHeadlessScenario({
	id: 'runner-case', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high',
	serviceTier: 'priority', task: 'Do the bounded task', timeoutMs: 1000,
	assert: [
		{ type: 'lifecycle', state: 'COMPLETED' },
		{ type: 'chat', message: 'HEADLESS_PASS' },
		{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'data get entity @s Pos', match: '1.0' },
	],
	...overrides,
});

const jsonl = (rows) => rows.map((row) => JSON.stringify(row)).join('\n') + '\n';

test('runs a real-provider scenario with exact RCON sequence and injected evidence', async () => {
	const commands = [];
	let statusReads = 0;
	let clock = 100;
	let recorderClosed = 0;
	const files = new Map([
		['coordinator.jsonl', jsonl([
			{ event: 'program_step', actionType: 'move', arguments: { x: 1, y: 0, z: 0 }, result: { state: 'SUCCEEDED', reasonCode: 'DONE' } },
			{ event: 'program_step', actionType: 'chat', arguments: { message: 'HEADLESS_PASS' }, result: null },
			{ event: 'program_step', actionType: 'chat', arguments: { message: 'HEADLESS_PASS' }, result: { state: 'SUCCEEDED', reasonCode: 'DONE' } },
			{ event: 'program_finished', status: 'COMPLETED' },
		])],
		['server.log', 'agent chat: HEADLESS_PASS\n'],
	]);
	const rcon = {
		command: async (command) => {
			commands.push(command);
			if (command.includes('forceload ')) return { text: 'OK' };
			if (command.includes(' run fill ')) return { text: 'Successfully filled blocks' };
			if (command.includes('codex summon-configured ')) return { text: 'Created runner-case-agent. It is ready for a task.' };
			if (command.startsWith('codex start ')) return { text: 'Goal started.' };
			if (command.startsWith('codex status ')) return { text: statusReads++ === 0 ? 'state=RUNNING' : 'state=COMPLETED' };
			if (command === 'data get entity @s Pos') return { text: '[1.0d, 64.0d, 1.0d]' };
			throw new Error(`unexpected command: ${command}`);
		},
		close: async () => {},
	};
	const report = await runHeadlessScenario({
		scenario: scenario(), runDirectory: 'C:/runs/runner-case', rcon,
		now: () => ++clock, readFile: async (file) => files.get(String(file).split(/[\\/]/).pop()) ?? '',
		providerTurnRecorder: { record: async () => { throw new Error('must not be called'); }, close: async () => { recorderClosed += 1; } },
		poll: async () => {},
	});

	assert.equal(report.status, 'PASSED');
	assert.equal(report.classification, 'PASSED');
	assert.equal(commands[0], 'execute in minecraft:overworld run forceload add 0 0');
	assert.equal(commands[1], 'execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone');
	assert.equal(commands[2], 'execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air');
	assert.match(commands[3], /^execute in minecraft:overworld positioned 0.5 201 0.5 run codex summon-configured codex gpt-5\.6-sol high priority survival headless_runner_case_/);
	assert.equal(commands[4], 'execute in minecraft:overworld run forceload remove 0 0');
	assert.match(commands[5], /^codex start headless_runner_case_[^ ]+ Do the bounded task$/);
	assert.match(commands[6], /^codex status headless_runner_case_[^ ]+$/);
	assert.equal(commands.at(-1), 'data get entity @s Pos');
	assert.equal(commands.some((command) => command.includes('action_result')), false);
	assert.equal(recorderClosed, 1);
	assert.equal(report.assertions.every((result) => result.passed), true);
	assert.deepEqual(report.assertions.find((result) => result.type === 'chat').actual, ['HEADLESS_PASS']);
	assert.ok(report.evidence.paths.protocol);
});

test('releases the temporary spawn chunk when summon fails', async () => {
	const commands = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/summon-failure',
		rcon: {
			command: async (command) => {
				commands.push(command);
				return { text: command.includes('summon-configured') ? 'ERROR: summon failed' : 'ok' };
			},
			close: async () => {},
		},
		now: () => 1,
		readFile: async () => '',
		poll: async () => {},
		writeFile: async () => {},
	});
	assert.equal(report.classification, 'ERROR');
	assert.deepEqual(commands.slice(0, 5), [
		'execute in minecraft:overworld run forceload add 0 0',
		'execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone',
		'execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air',
		commands[3],
		'execute in minecraft:overworld run forceload remove 0 0',
	]);
	assert.match(commands[3], /summon-configured/);
	assert.equal(commands.some((command) => command.startsWith('codex start ')), false);
});

test('parses the exact Java codex status lifecycle strings', async () => {
	for (const [statusText, expected] of [
		['runner | Task complete. Goal finished.', 'PASSED'],
		['runner | Needs attention. Provider failed.', 'ERROR'],
		['runner | Dead - awaiting model.', 'DEAD'],
	]) {
		const commands = [];
		const report = await runHeadlessScenario({
			scenario: scenario({ assert: [{ type: 'lifecycle', state: expected === 'PASSED' ? 'COMPLETED' : expected }] }),
			runDirectory: 'C:/runs/status-shapes',
			rcon: {
				command: async (command) => { commands.push(command); return { text: command.startsWith('codex status') ? statusText : 'ok' }; },
				close: async () => {},
			},
			now: () => 1,
			readFile: async () => '', poll: async () => {}, writeFile: async () => {},
		});
		assert.equal(report.classification, expected);
		assert.equal(commands.filter((command) => command.includes('codex status')).length, 1);
	}
});

test('polls beyond the old 256-attempt cap until a long-deadline terminal state', async () => {
	let clock = 0;
	let statusReads = 0;
	const report = await runHeadlessScenario({
		scenario: scenario({ timeoutMs: 20_000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/long-poll',
		rcon: {
		command: async (command) => ({ text: command.startsWith('codex status') ? (++statusReads > 300 ? 'runner | Task complete. Goal finished.' : 'runner | Working.') : 'ok' }),
		close: async () => {},
	},
	now: () => clock,
	readFile: async () => '',
	poll: async ({ phase }) => { if (phase === 'status') clock += 50; },
	writeFile: async () => {},
	});
	assert.equal(report.status, 'PASSED');
	assert.ok(statusReads > 256);
});

test('continues evidence polling for late markers and reads bounded tails', async () => {
	let evidenceReady = false;
	const padded = 'x'.repeat(20_000) + 'agent chat: LATE_PASS\n';
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'chat', message: 'LATE_PASS' }] }),
		runDirectory: 'C:/runs/late-evidence',
		rcon: {
		command: async (command) => ({ text: command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }),
		close: async () => {},
	},
	now: () => 1,
	readFile: async () => evidenceReady ? 'agent chat: LATE_PASS\n' : '',
		readTail: async () => evidenceReady ? padded : '',
	poll: async ({ phase }) => { if (phase === 'evidence') evidenceReady = true; },
	writeFile: async () => {},
	});
	assert.equal(report.status, 'PASSED');
});

test('redacts secret-bearing diagnostics and RCON evidence from serialized reports', async () => {
	let writes = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'list', match: 'missing' }] }),
		runDirectory: 'C:/runs/redaction',
		rcon: {
		command: async (command) => command === 'list' ? { text: '{"password":"shh-secret", "token":"tok-secret"}' } : { text: command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' },
		close: async () => {},
	},
	 now: () => 1, readFile: async () => '', poll: async () => {},
	writeFile: async (_file, content) => { writes.push(content); },
	});
	assert.equal(report.classification, 'ASSERTION_MISMATCH');
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /shh-secret|tok-secret/);
});

test('rejects mutation-capable RCON assertion commands using a conservative allowlist', async () => {
	for (const unsafe of ['scoreboard players set @s x 1', '/give @s diamond', 'weather thunder', 'time set day', 'gamemode creative', 'function foo', 'item replace entity @s weapon.mainhand stone', 'tag @s add admin', 'execute as @s run give @s diamond']) {
		let forwarded = false;
		const report = await runHeadlessScenario({
			scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: unsafe, match: 'never' }] }),
			runDirectory: 'C:/runs/rcon-deny',
			rcon: { command: async (command) => { forwarded = forwarded || command === unsafe; return { text: command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }; }, close: async () => {} },
			now: () => 1, readFile: async () => '', poll: async () => {}, writeFile: async () => {},
		});
		assert.equal(forwarded, false);
		assert.equal(report.classification, 'ERROR');
	}
});

test('returns cleanup failure even when cleanup report writing also fails', async () => {
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/cleanup-write',
		rcon: { command: async (command) => ({ text: command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }), close: async () => { throw new Error('port still open password=secret'); } },
		now: () => 1, readFile: async () => '', poll: async () => {}, writeFile: async () => { throw new Error('disk unavailable token=secret'); },
	});
	assert.equal(report.classification, 'CLEANUP_FAILURE');
	assert.equal(report.status, 'FAILED');
});

test('evaluates exact chat, action arguments, program, lifecycle, and read-only RCON assertions', () => {
	const result = evaluateHeadlessAssertions([
		{ type: 'lifecycle', state: 'COMPLETED' },
		{ type: 'chat', message: 'hello' },
		{ type: 'action', actionType: 'place_block', args: { x: 2, face: 'up' }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'list', match: 'There are 1' },
	], {
		lifecycle: 'COMPLETED', chats: ['hello'],
		actions: [{ actionType: 'place_block', arguments: { x: 2, face: 'up', extra: true }, result: { state: 'SUCCEEDED' } }],
		program: [{ event: 'program_finished', status: 'COMPLETED' }],
		rcon: [{ command: 'list', text: 'There are 1 of a max of 20 players online' }],
	});
	assert.equal(result.passed, true);
	assert.equal(result.results.length, 5);
});

test('classifies timeout, terminal ERROR/DEAD, skipped profiles, assertion mismatch, and cleanup failure', async (t) => {
	const make = async (statusText, overrides = {}) => {
		let clock = 0;
		const rcon = {
			command: async (command) => command.includes('codex summon-configured') ? { text: 'Created agent. ready' } : command.startsWith('codex start') ? { text: 'started' } : { text: statusText },
			close: overrides.close ?? (async () => {}),
		};
		const selectedScenario = overrides.scenario ? { ...scenario(), ...overrides.scenario } : scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] });
		return runHeadlessScenario({
			runDirectory: 'C:/runs/classifications', rcon, now: () => clock++, readFile: async () => '', poll: async () => {},
			...overrides,
			scenario: selectedScenario,
		});
	};
	assert.equal((await make('still running', { now: () => 2_000 })).classification, 'TIMEOUT');
	assert.equal((await make('state=ERROR')).classification, 'ERROR');
	assert.equal((await make('state=DEAD')).classification, 'DEAD');
	assert.equal((await make('state=COMPLETED', { scenario: { ...scenario(), skip: true, skipReason: 'profile unavailable' } })).status, 'SKIPPED');
	assert.equal((await make('state=COMPLETED', { scenario: { assert: [{ type: 'chat', message: 'missing' }] } })).classification, 'ASSERTION_MISMATCH');
	assert.equal((await make('state=COMPLETED', { close: async () => { throw new Error('port still open'); } })).classification, 'CLEANUP_FAILURE');
	await t.test('reports remain serializable', () => assert.doesNotThrow(() => JSON.stringify({ status: 'PASSED' })));
});

test('writes a bounded plain JSON report to the scenario directory', async () => {
	const writes = [];
	await writeHeadlessReport('C:/runs/write-case', { status: 'PASSED', diagnostics: 'x'.repeat(10000) }, async (file, content, options) => writes.push({ file, content, options }));
	assert.equal(writes.length, 1);
	assert.match(writes[0].file, /write-case[\\/]report\.json$/);
	assert.equal(writes[0].options.encoding, 'utf8');
	assert.ok(JSON.parse(writes[0].content).diagnostics.length <= 4096);
});
