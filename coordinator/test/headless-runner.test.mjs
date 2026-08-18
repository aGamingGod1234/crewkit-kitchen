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
		{ type: 'action', actionType: 'move', args: { x: 1 } },
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
	const audit = { rows: [
		{ direction: 'coordinator_to_server', type: 'action_command', agentId: 'runner-case-agent', payload: { actionType: 'move', arguments: { x: 1, y: 0, z: 0 } } },
	] };
	const files = new Map([
		['coordinator.jsonl', jsonl([
			{ event: 'chat', message: 'HEADLESS_PASS' },
			{ event: 'program_finished', status: 'COMPLETED' },
		])],
		['server.log', 'agent chat: HEADLESS_PASS\n'],
	]);
	const rcon = {
		command: async (command) => {
			commands.push(command);
			if (command.startsWith('codex summon-configured ')) return { text: 'Created runner-case-agent. It is ready for a task.' };
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
		protocolAudit: audit, providerTurnRecorder: { record: async () => { throw new Error('must not be called'); }, close: async () => { recorderClosed += 1; } },
		poll: async () => {},
	});

	assert.equal(report.status, 'PASSED');
	assert.equal(report.classification, 'PASSED');
	assert.match(commands[0], /^codex summon-configured codex gpt-5\.6-sol high priority survival headless_runner_case_/);
	assert.match(commands[1], /^codex start headless_runner_case_[^ ]+ Do the bounded task$/);
	assert.match(commands[2], /^codex status headless_runner_case_[^ ]+$/);
	assert.equal(commands.at(-1), 'data get entity @s Pos');
	assert.equal(commands.some((command) => command.includes('action_result')), false);
	assert.equal(recorderClosed, 1);
	assert.equal(report.assertions.every((result) => result.passed), true);
	assert.ok(report.evidence.paths.protocol);
});

test('evaluates exact chat, action arguments, program, lifecycle, and read-only RCON assertions', () => {
	const result = evaluateHeadlessAssertions([
		{ type: 'lifecycle', state: 'COMPLETED' },
		{ type: 'chat', message: 'hello' },
		{ type: 'action', actionType: 'place_block', args: { x: 2, face: 'up' } },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'list', match: 'There are 1' },
	], {
		lifecycle: 'COMPLETED', chats: ['hello'],
		actions: [{ actionType: 'place_block', arguments: { x: 2, face: 'up', extra: true } }],
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
			command: async (command) => command.startsWith('codex summon-configured') ? { text: 'Created agent. ready' } : command.startsWith('codex start') ? { text: 'started' } : { text: statusText },
			close: overrides.close ?? (async () => {}),
		};
		const selectedScenario = overrides.scenario ?? scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] });
		return runHeadlessScenario({
			scenario: selectedScenario,
			runDirectory: 'C:/runs/classifications', rcon, now: () => clock++, readFile: async () => '', poll: async () => {},
			...overrides,
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
