import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import { normalizeHeadlessScenario, runHeadlessMatrix, runHeadlessScenario } from '../src/headless-matrix.mjs';
import { HeadlessRconClient } from '../src/headless-rcon.mjs';
import { headlessTrialOutcome } from '../src/benchmark/paired-pilot.mjs';

const profile = { provider: 'codex', model: 'fixture', reasoningEffort: 'low', serviceTier: 'priority' };
const scenarioInput = () => ({
	id: 'clock', ...profile, task: 'Synthetic task', timeoutMs: 1000, requireFactualSuccess: true,
	world: { mode: 'natural', seed: '1' },
	assert: [{ type: 'rcon', command: 'data get entity {agent} Pos', match: 'entity data' }],
});
const scenario = () => normalizeHeadlessScenario(scenarioInput());
const manifest = (s) => ({
	version: 1, scenarioId: s.id, fresh: true, worldId: 'headless-synthetic', world: s.world,
	savedSpawn: { source: 'level.dat', dimension: 'minecraft:overworld', x: 10, y: 65, z: -4 },
	spawnLoading: { operation: 'temporary_spawn_chunk_loading', x: 10, z: -4, ready: true, elapsedMs: 0, terrainModified: false, inventoryModified: false },
});

// AgentIdentity.canonicalPublicName for ASCII fixture labels. Vanilla entity
// queries use this public name, not the registry's requested-label alias.
const canonical = (name) => {
	let safe = name.trim().replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '') || 'Agent';
	if (!/^[A-Za-z]/.test(safe)) safe = `Agent_${safe}`;
	return safe.slice(0, 16);
};
function world({ completed = false, removalFails = false, stopFails = false, afterStart = () => {} } = {}) {
	const state = { name: '', started: false };
	state.respond = (command) => {
		if (command === 'seed') return 'Seed: [1]';
		if (command === 'difficulty') return 'The difficulty is normal';
		if (command.includes(' if loaded ')) return 'The time is 500';
		if (command.includes('summon-configured')) {
			state.name = canonical(command.split(' ').at(-1));
			return `Created ${state.name}. It is ready for a task.`;
		}
		if (command.startsWith('data get entity ')) {
			if (command.split(' ')[3] !== state.name) return 'No entity was found';
			return `${state.name} has the following entity data: ${command.endsWith(' Inventory') ? '[]' : '[10.5d, 65.0d, -3.5d]'}`;
		}
		if (command.startsWith('codex start ')) { state.started = true; afterStart(); return 'started'; }
		if (command.startsWith('codex status ')) return completed ? 'state=COMPLETED' : 'state=RUNNING';
		if (command.startsWith('codex stop ')) return stopFails ? 'Failed to stop agent' : `Stopped ${state.name}.`;
		if (command.startsWith('codex remove ') && removalFails) return 'Failed to remove agent';
		return 'ok';
	};
	state.readFile = async (file) => String(file).endsWith('protocol.jsonl') && state.name
		? `${JSON.stringify({ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: 'fixture-agent', payload: { agentId: 'fixture-agent', name: state.name, ...profile } } })}\n` : '';
	return state;
}
const packet = (id, type, text = '') => {
	const body = Buffer.from(text);
	const framed = Buffer.alloc(14 + body.length);
	framed.writeInt32LE(10 + body.length, 0);
	framed.writeInt32LE(id, 4);
	framed.writeInt32LE(type, 8);
	body.copy(framed, 12);
	return framed;
};

// No network listener or connection. The real client must authenticate, frame
// each command and wait for a separate completion marker on every owned socket.
class FramedSocket extends EventEmitter {
	readyState = 'open';
	destroyed = false;
	timers = new Set();
	writes = [];
	responseDelivered = false;
	constructor(state, { statusDelay = 0, auth = 'ok', connectionHangs = false } = {}) {
		super(); this.world = state; this.statusDelay = statusDelay; this.auth = auth;
		if (connectionHangs) this.readyState = 'opening';
	}
	write(framed) {
		assert.equal(framed.readInt32LE(0), framed.length - 4);
		assert.equal(framed.at(-1), 0); assert.equal(framed.at(-2), 0);
		const id = framed.readInt32LE(4), type = framed.readInt32LE(8);
		const text = framed.subarray(12, framed.length - 2).toString('utf8');
		this.writes.push({ id, type, text });
		if (type === 3) assert.equal(text, 'synthetic');
		if (type === 3 && this.auth === 'hang') return true;
		if (type === 0) { assert.equal(this.responseDelivered, true); assert.equal(text, ''); }
		if (type === 2) this.responseDelivered = false;
		const response = packet(type === 3 && this.auth === 'fail' ? -1 : id, type === 3 ? 2 : 0,
			type === 2 ? this.world.respond(text) : '');
		const send = () => {
			if (this.destroyed) return;
			if (type === 2) this.responseDelivered = true;
			this.emit('data', response.subarray(0, 3));
			this.emit('data', response.subarray(3));
		};
		const delay = type === 2 && text.startsWith('codex status ') ? this.statusDelay : 0;
		if (delay) {
			const timer = setTimeout(() => { this.timers.delete(timer); send(); }, delay);
			this.timers.add(timer);
		} else queueMicrotask(send);
		return true;
	}
	destroy() {
		this.destroyed = true;
		for (const timer of this.timers) clearTimeout(timer);
		this.timers.clear(); queueMicrotask(() => this.emit('close'));
	}
}
const clientFor = (socket) => new HeadlessRconClient({
	port: 25575, password: 'synthetic', commandTimeoutMs: 5000, connectTimeoutMs: 5000, socketFactory: () => socket,
});
const commandTexts = (socket) => socket.writes.filter((row) => row.type === 2).map((row) => row.text);
const assertClosed = (sockets, clients) => {
	for (const socket of sockets) { assert.equal(socket.destroyed, true); assert.equal(socket.timers.size, 0); }
	for (const client of clients) { assert.equal(client.state, 'closed'); assert.equal(client.pending.size, 0); assert.equal(client.socket, null); }
};

async function matrixExpiry({ statusDelay = 1100, cleanupOptions = {}, cleanupBudget = 2000, removalFails = false, stopFails = false } = {}) {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'headless-phase-'));
	const s = scenario(), state = world({ removalFails, stopFails }), sockets = [], clients = [];
	const configPath = path.join(directory, 'matrix.json'), worldManifestPath = path.join(directory, 'world-manifest.json');
	let cleanupDeadline = null, cleanupEntries = 0, trialDeadline;
	try {
		const result = await runHeadlessMatrix({
			configPath, worldManifestPath, runDirectory: directory, rconPort: 25575, rconPasswordFile: 'fixture-password',
			phaseChannel: {
				now: () => performance.now(),
				ready: async () => (trialDeadline = performance.now() + 1000),
				cleanup: async () => { cleanupEntries += 1; return (cleanupDeadline = performance.now() + cleanupBudget); },
			},
			readFile: async (file) => file === configPath ? JSON.stringify({ version: 1, scenarios: [scenarioInput()] })
				: file === worldManifestPath ? JSON.stringify(manifest(s)) : file === 'fixture-password' ? 'synthetic' : state.readFile(file),
			fileSize: async () => 0, writeFile: async () => {}, mkdir: async () => {},
			rconFactory: (options) => {
				assert.deepEqual(options, { host: '127.0.0.1', port: 25575, password: 'synthetic' });
				if (sockets.length) assert.equal(sockets[0].destroyed, true, 'retire trial socket before replacement');
				const socket = new FramedSocket(state, sockets.length ? cleanupOptions : { statusDelay });
				const client = clientFor(socket); sockets.push(socket); clients.push(client); return client;
			},
		});
		assert.equal(cleanupEntries, 1); assert.ok(cleanupDeadline > trialDeadline);
		assert.ok(performance.now() < cleanupDeadline + 250, 'connection/authentication must use the existing cleanup deadline');
		assertClosed(sockets, clients);
		return { report: result.report.scenarios[0], sockets };
	} finally { await rm(directory, { recursive: true, force: true }); }
}

test('paired matrix retires status request at trial expiry and authenticates cleanup before stop/remove', { timeout: 120_000 }, async () => {
	const { report, sockets } = await matrixExpiry();
	assert.equal(report.classification, 'TIMEOUT'); assert.equal(report.cleanup.status, 'CLEAN');
	assert.equal(report.postRunEvidence.status, 'STOPPED');
	assert.deepEqual(headlessTrialOutcome(report), { status: 'TIMED_OUT', resourcesClean: true });
	assert.equal(sockets.length, 2);
	assert.equal(commandTexts(sockets[0]).some((command) => /^codex (stop|remove) /.test(command)), false);
	const cleanup = commandTexts(sockets[1]);
	assert.ok(cleanup[0].startsWith('codex stop ')); assert.ok(cleanup.at(-1).startsWith('codex remove '));
	assert.equal(sockets[1].writes[0].type, 3);
});

test('cleanup connection/authentication, stop and removal failures stay unclean and close both owned clients', { timeout: 120_000 }, async (t) => {
	for (const [label, options] of [
		['connection hangs', { cleanupOptions: { connectionHangs: true }, cleanupBudget: 80 }],
		['authentication hangs', { cleanupOptions: { auth: 'hang' }, cleanupBudget: 80 }],
		['authentication fails', { cleanupOptions: { auth: 'fail' } }],
		['stop fails', { stopFails: true }],
		['removal fails', { removalFails: true }],
	]) await t.test(label, async () => {
		const { report, sockets } = await matrixExpiry(options);
		assert.equal(report.classification, 'CLEANUP_FAILURE'); assert.equal(report.cleanup.status, 'FAILED');
		assert.deepEqual(headlessTrialOutcome(report), { status: 'ERROR', resourcesClean: false });
		assert.equal(sockets.length, 2);
		if (label === 'stop fails') {
			assert.equal(report.postRunEvidence.status, 'UNAVAILABLE');
			assert.ok(commandTexts(sockets[1]).some((command) => command.startsWith('codex remove ')), 'still remove after failed stop');
		} else if (label !== 'removal fails') assert.equal(commandTexts(sockets[1]).length, 0);
	});
});

test('paired poll expiry and unpaired delayed status controls keep the original authenticated transport', { timeout: 120_000 }, async (t) => {
	for (const paired of [true, false]) await t.test(paired ? 'paired polling' : 'unpaired request', async () => {
		const s = scenario(), state = world(), socket = new FramedSocket(state, { statusDelay: paired ? 0 : 1100 });
		const client = clientFor(socket); await client.connect();
		const now = () => performance.now(), deadline = now() + 1000;
		const report = await runHeadlessScenario({
			scenario: s, worldManifest: manifest(s), runDirectory: path.resolve('headless-fixture'), rcon: client,
			now, trialDeadlineMs: deadline,
			...(paired ? { onCleanup: async () => now() + 2000, cleanupRconFactory: () => { assert.fail('poll expiry needs no replacement'); } } : {}),
			readFile: state.readFile, fileSize: async () => 0, writeFile: async () => {},
			poll: async () => { await new Promise((resolve) => setTimeout(resolve, 1100)); },
		});
		assert.equal(report.classification, 'TIMEOUT'); assert.equal(report.cleanup.status, 'CLEAN');
		assert.deepEqual(headlessTrialOutcome(report), { status: 'TIMED_OUT', resourcesClean: true });
		for (const op of ['stop', 'remove']) assert.ok(commandTexts(socket).some((command) => command.startsWith(`codex ${op} `)));
		assertClosed([socket], [client]);
	});
});

test('paired fractional clocks produce unchanged public names while retaining fractional elapsed time', { timeout: 120_000 }, async (t) => {
	assert.equal(canonical('ha_clock_02s.i'), 'ha_clock_02s_i', 'dotted-name negative control');
	assert.equal(canonical('ha_clock_0002s'), 'ha_clock_0002s', 'integral-name control');
	for (const clock of [100, 100.5, 72.25, 75.5, 76.875]) await t.test(String(clock), async () => {
		let current = clock;
		const s = scenario(), state = world({ completed: true, afterStart: () => { current += 0.375; } });
		const socket = new FramedSocket(state), client = clientFor(socket); await client.connect();
		const report = await runHeadlessScenario({
			scenario: s, worldManifest: manifest(s), runDirectory: path.resolve('headless-fixture'), rcon: client,
			now: () => current, trialDeadlineMs: clock + 1000.125, onCleanup: async () => current + 2000.125,
			readFile: state.readFile, fileSize: async () => 0, writeFile: async () => {}, poll: async () => {},
		});
		assert.equal(report.classification, 'PASSED', report.diagnostics); assert.equal(state.started, true);
		assert.match(report.generatedName, /^[A-Za-z][A-Za-z0-9_]{0,15}$/);
		assert.equal(report.generatedName, state.name); assert.equal(canonical(report.generatedName), report.generatedName);
		assert.equal(report.elapsedMs, 0.375);
		assert.equal(report.generatedName, `ha_clock_${Math.floor(clock).toString(36).padStart(5, '0')}`);
		assertClosed([socket], [client]);
	});
});
