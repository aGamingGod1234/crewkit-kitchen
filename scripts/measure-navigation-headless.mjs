// Headless live navigation throughput and eight-agent controller CPU probe.
// Usage: node scripts/measure-navigation-headless.mjs <coordinator-src> <rcon-port> <bridge-port> <secret-file> <server-log> <result-file> <course|eight> [course-sprint:true|false]
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { fixtureAction } from '../coordinator/src/player-capability-probe.mjs';

const [srcDirectory, rconPortText, bridgePortText, secretFile, serverLog, resultFile, mode, courseSprintText] = process.argv.slice(2);
if (![srcDirectory, rconPortText, bridgePortText, secretFile, serverLog, resultFile].every(Boolean)
	|| !['course', 'eight'].includes(mode)
	|| courseSprintText !== undefined && !['true', 'false'].includes(courseSprintText)) throw new Error('USAGE');
const courseSprint = courseSprintText !== 'false';
const { MultiplexedServerBridge } = await import(pathToFileURL(join(srcDirectory, 'protocol-v2.mjs')).href);
const { HeadlessRconClient } = await import(pathToFileURL(join(srcDirectory, 'headless-rcon.mjs')).href);
const PROFILE = { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
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
			const waiter = { type, predicate, resolve: null, reject: null };
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
		else { this.events.push(event); if (this.events.length > 1024) this.events.shift(); }
	}
	fail(error) { for (const waiter of this.waiters.splice(0)) waiter.reject(error); }
}

const bridge = new MultiplexedServerBridge({ port: Number(bridgePortText), secret }, {});
const inbox = new Inbox(bridge);
const rcon = new HeadlessRconClient({ host: '127.0.0.1', port: Number(rconPortText), password: secret });
const command = async (text) => (await rcon.command(text)).text;
const records = new Map();
const observations = new Map();
const actions = new Map();
let ordinal = 0;
let barrierPromise = null;
let barrierAddedAt = null;
let barrierTrigger = null;
let firstHorizontalCollisionElapsedMs = null;
let courseActionId = null;
let tickProfilerActive = false;

bridge.on('goal_spec_request', (event) => {
	if (!records.has(event.agentId)) return;
	bridge.send('goal_spec_proposal', event.agentId, {
		requestId: event.payload.requestId,
		summary: 'Run a controlled headless navigation measurement fixture.',
		predicate: { type: 'operator_confirmed' },
	}).catch(() => {});
});
bridge.on('observation', (event) => {
	if (records.has(event.agentId) && event.payload.ready) observations.set(event.agentId, event.payload);
});
bridge.on('action_progress', (event) => {
	const samples = actions.get(event.payload.actionId);
	if (samples) samples.push(event.payload);
	const observedPosition = event.payload.actionObservation?.position;
	if (event.payload.actionId === courseActionId && firstHorizontalCollisionElapsedMs === null
		&& observedPosition?.x >= 23.0 && event.payload.actionObservation?.collision?.horizontal === true) {
		firstHorizontalCollisionElapsedMs = event.payload.elapsedMs ?? null;
	}
	if (event.payload.actionId !== courseActionId || barrierPromise) return;
	const position = observedPosition;
	if (!Number.isFinite(position?.x) || position.x < 21.0) return;
	barrierTrigger = { x: position.x, elapsedMs: event.payload.elapsedMs ?? null };
	barrierPromise = command('fill 24 64 98 24 70 102 minecraft:stone').then(() => { barrierAddedAt = Date.now(); });
});

async function observe(record) {
	const sequence = observations.get(record.agentId)?.eventSequence ?? 0;
	const waiting = inbox.wait('observation', (event) => event.agentId === record.agentId
		&& event.payload.ready && event.payload.goalRevision === record.goalRevision
		&& event.payload.eventSequence > sequence);
	await bridge.send('request_observation', record.agentId, { goalRevision: record.goalRevision });
	const value = (await waiting).payload;
	observations.set(record.agentId, value);
	return value;
}

async function spawnAgent(name, position) {
	const registered = inbox.wait('agent_registered');
	await command(`execute in minecraft:overworld positioned ${position.x} ${position.y} ${position.z} run codex summon ${PROFILE.model} ${PROFILE.reasoningEffort} ${name}`);
	const event = await registered;
	const record = { ...(event.payload.record ?? event.payload), agentId: event.agentId, name };
	records.set(record.agentId, record);
	await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
	const activated = inbox.wait('goal_control', (item) => item.agentId === record.agentId && item.payload.operation === 'start');
	await command(`codex start ${name} Stay alive for 600 seconds.`);
	record.goalRevision = (await activated).payload.goalRevision;
	await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
	return record;
}

function navPayload(record, observation, target) {
	return fixtureAction(record, observation, ++ordinal, 'navigate_to', {
		x: target.x, y: target.y, z: target.z, tolerance: 0.5, sprint: true, timeoutMs: 30_000,
	});
}

function parseNavigationTicks(text) {
	const rows = [];
	for (const line of text.split(/\r?\n/)) {
		if (!line.includes('NAV_TICK ')) continue;
		const match = /NAV_TICK tick=(\d+) elapsedMs=(\d+) durationUs=(\d+) pos=([^, ]+),([^, ]+),([^ ]+) dpos=([^ ]+) vel=([^ ]+) ground=(true|false) hcoll=(true|false).*?traversal=([A-Z_]+)/.exec(line);
		if (!match) continue;
		rows.push({ tick: Number(match[1]), elapsedMs: Number(match[2]), durationUs: Number(match[3]),
			position: [Number(match[4]), Number(match[5]), Number(match[6])], ground: match[9] === 'true',
			horizontalCollision: match[10] === 'true', traversal: match[11] });
	}
	return rows;
}

function percentiles(values) {
	if (values.length === 0) return null;
	const sorted = [...values].sort((left, right) => left - right);
	return {
		mean: values.reduce((sum, value) => sum + value, 0) / values.length,
		median: sorted[Math.floor((sorted.length - 1) * 0.5)],
		p95: sorted[Math.floor((sorted.length - 1) * 0.95)],
	};
}

async function setupCourse() {
	await command('forceload add -16 48 48 144');
	for (let attempt = 0; attempt < 100; attempt += 1) {
		const loaded = await command('execute if loaded 0 64 100 if loaded 32 64 102 run time query gametime');
		if (/time is \d+/i.test(loaded)) break;
		if (attempt === 99) throw new Error('COURSE_CHUNKS_NOT_LOADED');
		await sleep(100);
	}
	await command('fill 0 64 96 32 84 104 minecraft:air');
	await command('fill 0 63 99 5 63 101 minecraft:stone');
	for (const [x, y] of [[6, 64], [7, 65], [8, 66], [9, 67]]) await command(`fill ${x} ${y} 99 ${x} ${y} 101 minecraft:stone`);
	await command('fill 10 67 99 15 67 101 minecraft:stone');
	for (const [x, y] of [[16, 66], [17, 65], [18, 64]]) await command(`fill ${x} ${y} 99 ${x} ${y} 101 minecraft:stone`);
	await command('fill 19 63 99 32 63 101 minecraft:stone');
	await command('fill 0 64 98 32 67 98 minecraft:stone');
	await command('fill 0 64 102 32 67 102 minecraft:stone');
}

async function measureCourse(logOffset) {
	const record = await spawnAgent('NavCourse', { x: 0.5, y: 64, z: 100.5 });
	await observe(record);
	const action = fixtureAction(record, observations.get(record.agentId), ++ordinal, 'move_to', {
		x: 30.5, y: 64, z: 100.5, tolerance: 0.5, sprint: courseSprint,
	});
	courseActionId = action.actionId;
	actions.set(action.actionId, []);
	const waiting = inbox.wait('action_result', (event) => event.agentId === record.agentId
		&& event.payload.actionId === action.actionId
		&& ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(event.payload.state), 45_000);
	const startedAt = Date.now();
	await bridge.send('action_command', record.agentId, action);
	const result = (await waiting).payload;
	if (barrierPromise) await barrierPromise;
	await bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: action.actionId });
	await sleep(150);
	const log = await readFile(serverLog, 'utf8');
	const tickRows = parseNavigationTicks(log.slice(logOffset));
	const samples = actions.get(action.actionId);
	const positions = samples.map((sample) => sample.actionObservation?.position).filter((position) => Number.isFinite(position?.x));
	if (result.actionObservation?.position) positions.push(result.actionObservation.position);
	const furthestX = Math.max(0.5, ...positions.map((position) => position.x));
	const approach = barrierTrigger ?? positions.filter((position) => position.x >= 21).map((position) => ({ x: position.x })).at(-1) ?? null;
	const approachDistanceBlocks = approach ? Math.max(0, approach.x - 0.5) : null;
	const wallCollisionTick = tickRows.find((row) => row.horizontalCollision && row.position[0] >= 23.0) ?? null;
	// Wall-clock fields drift when the server lags and catches up; game ticks (50 ms each) do not.
	const approachRow = tickRows.find((row) => row.position[0] >= 21.0) ?? null;
	const approachGameTicks = approachRow && tickRows.length > 0 ? approachRow.tick - tickRows[0].tick : null;
	const measured = {
		state: result.state, reasonCode: result.reasonCode, elapsedMs: result.elapsedMs,
		furthestX, distanceBlocks: furthestX - 0.5,
		throughputBlocksPerSecond: result.elapsedMs > 0 ? (furthestX - 0.5) / (result.elapsedMs / 1_000) : null,
		approachDistanceBlocks,
		approachElapsedMs: barrierTrigger?.elapsedMs ?? null,
		approachGameTicks,
		approachGameBlocksPerSecond: approachGameTicks > 0 ? (approachRow.position[0] - tickRows[0].position[0]) / (approachGameTicks / 20) : null,
		approachBlocksPerSecond: barrierTrigger?.elapsedMs > 0
			? approachDistanceBlocks / (barrierTrigger.elapsedMs / 1_000) : null,
		barrierTrigger: approach,
		barrierAddedAtEpochMs: barrierAddedAt,
		barrierToResultMs: barrierAddedAt === null ? null : Date.now() - barrierAddedAt,
		barrierToResultActionElapsedMs: barrierAddedAt === null ? null : result.elapsedMs - (barrierAddedAt - startedAt),
		firstHorizontalCollisionElapsedMs: wallCollisionTick?.elapsedMs ?? firstHorizontalCollisionElapsedMs,
		firstHorizontalCollisionSource: wallCollisionTick ? 'per-tick NAV_TICK' : 'action observation',
		collisionObservationToResultMs: wallCollisionTick
			? result.elapsedMs - wallCollisionTick.elapsedMs
			: firstHorizontalCollisionElapsedMs === null ? null : result.elapsedMs - firstHorizontalCollisionElapsedMs,
		progressSamples: samples.length,
		debugRows: tickRows.length,
	};
	return measured;
}

async function setupEightLanes() {
	await command('forceload add -16 144 48 208');
	for (let attempt = 0; attempt < 100; attempt += 1) {
		const loaded = await command('execute if loaded 0 64 150 if loaded 32 64 171 run time query gametime');
		if (/time is \d+/i.test(loaded)) break;
		if (attempt === 99) throw new Error('EIGHT_AGENT_CHUNKS_NOT_LOADED');
		await sleep(100);
	}
	await command('fill 0 64 144 32 84 176 minecraft:air');
	await command('fill 0 63 148 28 63 173 minecraft:stone');
}

async function measureEight(logOffset) {
	const group = [];
	for (let index = 0; index < 8; index += 1) {
		const z = 150 + index * 3;
		group.push(await spawnAgent(`NavLoad${index}`, { x: 0.5, y: 64, z: z + 0.5 }));
	}
	await Promise.all(group.map(async (record, index) => {
		const z = 150 + index * 3 + 0.5;
		await command(`tp ${record.name} 0.5 64 ${z} -90 0`);
		await observe(record);
	}));
	const tickProfileStart = await command('debug start');
	tickProfilerActive = true;
	const entries = await Promise.all(group.map(async (record, index) => {
		const action = navPayload(record, observations.get(record.agentId), { x: 24.5, y: 64, z: 150 + index * 3 + 0.5 });
		actions.set(action.actionId, []);
		const waiting = inbox.wait('action_result', (event) => event.agentId === record.agentId
			&& event.payload.actionId === action.actionId
			&& ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(event.payload.state), 45_000);
		await bridge.send('action_command', record.agentId, action);
		return { record, action, waiting };
	}));
	const results = await Promise.all(entries.map(async ({ record, action, waiting }) => {
		const result = (await waiting).payload;
		await bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: action.actionId });
		return { agent: record.name, state: result.state, reasonCode: result.reasonCode, elapsedMs: result.elapsedMs };
	}));
	const tickProfileStop = await command('debug stop');
	tickProfilerActive = false;
	await sleep(150);
	const log = await readFile(serverLog, 'utf8');
	const rows = parseNavigationTicks(log.slice(logOffset));
	const byTick = new Map();
	for (const row of rows) {
		const current = byTick.get(row.tick) ?? { tick: row.tick, agents: 0, totalDurationUs: 0 };
		current.agents += 1;
		current.totalDurationUs += row.durationUs;
		byTick.set(row.tick, current);
	}
	const fullTicks = [...byTick.values()].filter((row) => row.agents === 8);
	return {
		results,
		tickProfileStart,
		tickProfileStop,
		debugRows: rows.length,
		fullEightAgentTicks: fullTicks.length,
		fullEightAgentTickCoverage: byTick.size === 0 ? null : fullTicks.length / byTick.size,
		navigationControllerCpuUsPerFullServerTick: percentiles(fullTicks.map((row) => row.totalDurationUs)),
	};
}

try {
	await rcon.connect();
	const ready = inbox.wait('ready'); bridge.start(); await ready;
	await bridge.send('catalog_snapshot', 'server', {
		refreshedAtEpochMs: Date.now(),
		models: [{ provider: PROFILE.provider, model: PROFILE.model, id: PROFILE.model,
			displayName: 'navigation measurement identity', reasoningEfforts: ['high'], serviceTiers: ['priority', 'fast'] }],
	});
	await command('difficulty peaceful');
	await command('time set noon');
	const logOffset = (await readFile(serverLog, 'utf8')).length;
	let measurement;
	if (mode === 'course') { await setupCourse(); measurement = await measureCourse(logOffset); }
	else { await setupEightLanes(); measurement = await measureEight(logOffset); }
	const summary = { mode, ...(mode === 'course' ? { sprint: courseSprint } : {}), measurement };
	await writeFile(resultFile, JSON.stringify(summary, null, 2));
	console.log(JSON.stringify(summary));
	if (mode === 'course' && !barrierTrigger) throw new Error('COURSE_BARRIER_TRIGGER_MISSING');
	if (mode === 'eight' && !measurement.tickProfileStop) throw new Error('SERVER_TICK_PROFILE_MISSING');
} finally {
	if (tickProfilerActive) await command('debug stop').catch(() => {});
	for (const record of records.values()) await command(`codex stop ${record.name}`).catch(() => {});
	bridge.stop();
	await rcon.close();
}
