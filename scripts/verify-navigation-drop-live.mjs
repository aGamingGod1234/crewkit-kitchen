// Live regression probe: time lost crossing a 1-3 block drop, walking and sprinting, against the speed held on the flat run-up.
// Needs a server started with -Darenaagents.navigation.debugTicks=true.
// Usage: node scripts/verify-navigation-drop-live.mjs <coordinator-src> <rcon-port> <bridge-port> <secret-file> <server-log> <result-file>
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { fixtureAction } from '../coordinator/src/player-capability-probe.mjs';

const [srcDirectory, rconPortText, bridgePortText, secretFile, serverLog, resultFile] = process.argv.slice(2);
if (![srcDirectory, rconPortText, bridgePortText, secretFile, serverLog, resultFile].every(Boolean)) {
	throw new Error('USAGE');
}
const { MultiplexedServerBridge } = await import(pathToFileURL(join(srcDirectory, 'protocol-v2.mjs')).href);
const { HeadlessRconClient } = await import(pathToFileURL(join(srcDirectory, 'headless-rcon.mjs')).href);
const PROFILE = { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const AGENT = `NavProbe${Date.now().toString(36).slice(-5)}`;
const PENALTY_LIMIT_MS = Number(process.env.NAV_PENALTY_LIMIT_MS || 150);
const secret = (await readFile(secretFile, 'utf8')).trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Inbox {
	events = [];
	waiters = [];
	constructor(bridge) {
		bridge.on('message', (event) => this.accept(event));
		bridge.on('ready', (event) => this.accept({ type: 'ready', ...event }));
		bridge.on('protocolError', (error) => this.fail(error));
		bridge.on('transportError', (error) => this.fail(error));
	}
	wait(type, predicate = () => true, timeoutMs = 60_000) {
		const index = this.events.findIndex((event) => event.type === type && predicate(event));
		if (index >= 0) return Promise.resolve(this.events.splice(index, 1)[0]);
		return new Promise((resolve, reject) => {
			const waiter = { type, predicate, resolve, reject };
			const timer = setTimeout(() => {
				this.waiters = this.waiters.filter((entry) => entry !== waiter);
				reject(new Error(`TIMEOUT_${type}`));
			}, timeoutMs);
			waiter.resolve = (event) => { clearTimeout(timer); resolve(event); };
			waiter.reject = (error) => { clearTimeout(timer); reject(error); };
			this.waiters.push(waiter);
		});
	}
	accept(event) {
		const index = this.waiters.findIndex((waiter) => waiter.type === event.type && waiter.predicate(event));
		if (index >= 0) this.waiters.splice(index, 1)[0].resolve(event);
		else { this.events.push(event); if (this.events.length > 512) this.events.shift(); }
	}
	fail(error) {
		for (const waiter of this.waiters.splice(0)) waiter.reject(error);
	}
}

const bridge = new MultiplexedServerBridge({ port: Number(bridgePortText), secret }, {});
const inbox = new Inbox(bridge);
const rcon = new HeadlessRconClient({ host: '127.0.0.1', port: Number(rconPortText), password: secret });
const command = async (text) => (await rcon.command(text)).text;
let record = null;
let observation = null;
let ordinal = 0;
const results = [];

bridge.on('goal_spec_request', (event) => {
	if (record?.agentId !== event.agentId) return;
	bridge.send('goal_spec_proposal', record.agentId, {
		requestId: event.payload.requestId,
		summary: 'Run a controlled navigation landing regression fixture.',
		predicate: { type: 'operator_confirmed' },
	}).catch(() => {});
});
bridge.on('observation', (event) => {
	if (record?.agentId === event.agentId && event.payload.ready) observation = event.payload;
});

async function observe() {
	const sequence = observation?.eventSequence ?? 0;
	const waiting = inbox.wait('observation', (event) => event.agentId === record.agentId
		&& event.payload.ready && event.payload.goalRevision === record.goalRevision
		&& event.payload.eventSequence > sequence);
	await bridge.send('request_observation', record.agentId, { goalRevision: record.goalRevision });
	observation = (await waiting).payload;
	return observation;
}

function parseNavigationTicks(text) {
	const rows = [];
	for (const line of text.split(/\r?\n/)) {
		if (!line.includes('NAV_TICK ')) continue;
		const match = /NAV_TICK tick=(\d+) elapsedMs=(\d+) durationUs=\d+ pos=([^, ]+),([^, ]+),([^ ]+) dpos=\S+ vel=\S+ ground=(true|false) hcoll=(true|false)/.exec(line);
		if (!match) continue;
		rows.push({ tick: Number(match[1]), elapsedMs: Number(match[2]), x: Number(match[3]), y: Number(match[4]),
			z: Number(match[5]), ground: match[6] === 'true', horizontalCollision: match[7] === 'true' });
	}
	return rows;
}

// Server ticks (not wall time) to cross the drop, minus the ticks the same distance takes at the speed the body holds
// on the flat run-up. A landing that stops, backs up or re-accelerates shows as a penalty.
function dropPenalty(rows, edgeX) {
	const runUp = [];
	for (let index = 1; index < rows.length; index += 1) {
		const row = rows[index];
		if (row.x >= edgeX - 10 && row.x <= edgeX - 4 && row.ground && rows[index - 1].ground) runUp.push(row.x - rows[index - 1].x);
	}
	if (runUp.length < 4) return null;
	const steadyBlocksPerTick = runUp.reduce((sum, value) => sum + value, 0) / runUp.length;
	const before = rows.find((row) => row.x >= edgeX - 3);
	const after = rows.find((row) => row.x >= edgeX + 6);
	if (!before || !after || !(steadyBlocksPerTick > 0.05)) return null;
	const ticks = after.tick - before.tick;
	const ideal = (after.x - before.x) / steadyBlocksPerTick;
	const window = rows.filter((row) => row.tick >= before.tick && row.tick <= after.tick);
	let backwardBlocks = 0;
	for (let index = 1; index < window.length; index += 1) backwardBlocks = Math.max(backwardBlocks, window[index - 1].x - window[index].x);
	return { steadyBlocksPerTick, ticks, idealTicks: ideal, penaltyTicks: ticks - ideal, penaltyMs: (ticks - ideal) * 50, backwardBlocks };
}

async function navigate(label, start, destination, sprint) {
	await command(`tp ${AGENT} ${start.x} ${start.y} ${start.z} -90 0`);
	const fresh = await observe();
	const action = fixtureAction(record, fresh, ++ordinal, 'move_to', {
		x: destination.x, y: destination.y, z: destination.z, tolerance: 0.5, sprint,
	});
	const waiting = inbox.wait('action_result', (event) => event.agentId === record.agentId
		&& event.payload.actionId === action.actionId
		&& ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(event.payload.state), 45_000);
	const cursor = (await readFile(serverLog, 'utf8')).length;
	await bridge.send('action_command', record.agentId, action);
	const result = (await waiting).payload;
	await bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: action.actionId });
	await sleep(150);
	const rows = parseNavigationTicks((await readFile(serverLog, 'utf8')).slice(cursor));
	return { label, sprint, state: result.state, reasonCode: result.reasonCode, elapsedMs: result.elapsedMs, rows };
}

const EDGE_X = 303;
const TOP = 80;

async function buildDrop(height) {
	await command('fill 286 64 108 336 90 118 minecraft:air');
	await command(`fill 286 ${TOP - 1} 111 ${EDGE_X - 1} ${TOP - 1} 113 minecraft:stone`);
	await command(`fill ${EDGE_X} ${TOP - 1 - height} 111 334 ${TOP - 1 - height} 113 minecraft:stone`);
}

try {
	await rcon.connect();
	const ready = inbox.wait('ready'); bridge.start(); await ready;
	await bridge.send('catalog_snapshot', 'server', {
		refreshedAtEpochMs: Date.now(),
		models: [{ provider: PROFILE.provider, model: PROFILE.model, id: PROFILE.model,
			displayName: 'navigation fixture identity', reasoningEfforts: ['high'], serviceTiers: ['priority', 'fast'] }],
	});
	await command('difficulty peaceful');
	await command('time set noon');
	await command('forceload add 272 96 336 128');
	for (let attempt = 0; attempt < 100; attempt += 1) {
		const loaded = await command('execute if loaded 290 70 100 if loaded 330 80 112 run time query gametime');
		if (/time is \d+/i.test(loaded)) break;
		if (attempt === 99) throw new Error('FIXTURE_CHUNKS_NOT_LOADED');
		await sleep(100);
	}
	await command('fill 286 64 108 336 90 118 minecraft:air');
	await command(`fill 298 ${TOP - 1} 111 300 ${TOP - 1} 113 minecraft:stone`);

	const registered = inbox.wait('agent_registered');
	await command(`execute in minecraft:overworld positioned 299.5 ${TOP} 112.5 run codex summon gpt-5.6-sol high ${AGENT}`);
	const event = await registered;
	record = { ...(event.payload.record ?? event.payload), agentId: event.agentId };
	await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
	const activated = inbox.wait('goal_control', (item) => item.agentId === record.agentId && item.payload.operation === 'start');
	await command(`codex start ${AGENT} Stay alive for 600 seconds.`);
	record.goalRevision = (await activated).payload.goalRevision;
	await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });

	for (const sprint of [false, true]) {
		for (const height of [1, 2, 3]) {
			await buildDrop(height);
			for (const phase of [0, 0.13, 0.27]) {
				const run = await navigate(`${sprint ? 'sprint' : 'walk'} ${height}-block drop +${phase}`,
					{ x: 289.5 + phase, y: TOP, z: 112.5 }, { x: 322.5, y: TOP - height, z: 112.5 }, sprint);
				const { rows, ...summary } = run;
				results.push({ ...summary, tickRows: rows.length, penalty: dropPenalty(rows, EDGE_X) });
			}
		}
	}
	await buildDrop(4);
	const { rows: boundaryRows, ...boundary } = await navigate('four-block drop', { x: 289.5, y: TOP, z: 112.5 },
		{ x: 322.5, y: TOP - 4, z: 112.5 }, true);

	const measured = results.filter((item) => item.penalty);
	const penalties = measured.map((item) => item.penalty.penaltyMs);
	const summary = {
		maxPenaltyMs: penalties.length === 0 ? null : Math.max(...penalties),
		meanPenaltyMs: penalties.length === 0 ? null : penalties.reduce((sum, value) => sum + value, 0) / penalties.length,
		maxBackwardBlocks: Math.max(0, ...measured.map((item) => item.penalty.backwardBlocks)),
		penaltyLimitMs: PENALTY_LIMIT_MS, measured: penalties.length, fourBlockBoundary: boundary, results,
	};
	await writeFile(resultFile, JSON.stringify(summary, null, 2));
	console.log(JSON.stringify({ ...summary, results: undefined }));
	if (results.some((item) => item.state !== 'SUCCEEDED')) throw new Error('NAVIGATION_FIXTURE_DID_NOT_COMPLETE');
	if (penalties.length !== results.length) throw new Error('DROP_PENALTY_NOT_MEASURED');
	if (boundary.state !== 'FAILED' || boundary.reasonCode !== 'NO_PATH') throw new Error('FOUR_BLOCK_DROP_BOUNDARY_CHANGED');
	if (summary.maxPenaltyMs > PENALTY_LIMIT_MS) throw new Error(`DROP_LANDING_PENALTY_${summary.maxPenaltyMs}MS`);
} finally {
	if (record) await command(`codex stop ${AGENT}`).catch(() => {});
	bridge.stop();
	await rcon.close();
}
