// Live result-to-next-command latency of authored programs on a headless server (no model involved).
// Eight agents each run one background program of alternating wait/lookAt actions, so every gap is
// coordinator + server only. Run the same script once per coordinator source tree to compare them:
//   node live-hotpath-latency.mjs <coordinatorSrcDir> <rconPort> <bridgePort> <secretFile> <label> <outFile> [agents] [actions] [graceMs]
// The secret file holds the bridge secret, which is also the RCON password (see far-sight-measure/start.sh).
// On a busy machine give the server and this process High priority, and alternate the trees (A B A B) so drift cancels.
// Output: gapMs is result received -> next action_command sent; observationRequestsPerAction counts inspection_request
// observation samples the coordinator asked the server for; publicationMisses counts grace periods that ended unmet.
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

const [srcDir, rconPortText, bridgePortText, secretFile, label, outFile, agentsText = '8', actionsText = '60', graceText] = process.argv.slice(2);
const load = (name) => import(pathToFileURL(join(srcDir, name)).href);
const { MultiplexedServerBridge } = await load('protocol-v2.mjs');
const { HeadlessRconClient } = await load('headless-rcon.mjs');
const { NativeToolRuntime } = await load('native-tool-runtime.mjs');
const { normalizeMinecraftToolCall } = await load('native-minecraft-tools.mjs');
const { adaptObservation } = await load('observation-adapter.mjs');
const { classifyObservationTrigger } = await load('dynamic-main.mjs');

const AGENTS = Number(agentsText);
const ACTIONS = Number(actionsText);
const PROFILE = { provider: 'codex', model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' };
const secret = (await readFile(secretFile, 'utf8')).trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const now = () => performance.now();
const terminal = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED']);

const bridge = new MultiplexedServerBridge({ port: Number(bridgePortText), secret });
const rcon = new HeadlessRconClient({ host: '127.0.0.1', port: Number(rconPortText), password: secret });
const command = async (text) => (await rcon.command(text)).text;
const records = new Map();
const messages = [];
const commands = [];
const requests = [];
const traces = [];
const results = [];
const publications = [];
const goalChanges = [];
let failure = null;
bridge.on('transportError', (error) => { failure = error; });
bridge.on('protocolError', (error) => { failure = error; });
bridge.on('message', (event) => messages.push({ ...event, at: now() }));

const inspections = new Map();
const passiveObservation = async (record) => {
	const requestId = `lh-${record.agentId}-${Math.random().toString(36).slice(2)}`;
	requests.push({ agentId: record.agentId, at: now() });
	const reply = new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error(`inspection timed out; agent ${record.agentId} request ${requestId} rev ${record.goalRevision} recent: ${JSON.stringify(messages.filter((e) => e.agentId === record.agentId && !['observation', 'heartbeat', 'action_progress'].includes(e.type)).slice(-8).map((e) => ({ type: e.type, agentId: e.agentId, payload: JSON.stringify(e.payload).slice(0, 200) })))}`)), 10000);
		inspections.set(requestId, (event) => { clearTimeout(timer); resolve(event); });
	});
	await bridge.send('inspection_request', record.agentId, { requestId, goalRevision: record.goalRevision, query: { section: 'observation', limit: 16, offset: 0 } });
	const event = await reply;
	if (event.payload.error) throw new Error(`inspection ${event.payload.error.code}`);
	const wire = event.payload.result;
	return { ...wire, observation: adaptObservation(wire.observation) };
};

const graceOption = graceText === undefined ? {} : { publicationGraceMs: Number(graceText) };
const runtime = new NativeToolRuntime({
	sessionId: `live-hotpath-${label}`,
	registry: { get: (agentId) => records.get(agentId) ?? null },
	bridge: { send: async (type, agentId, payload) => {
		if (type === 'action_command') commands.push({ agentId, actionId: payload.actionId, at: now() });
		return bridge.send(type, agentId, payload);
	} },
	requestObservation: passiveObservation,
	trace: (event, fields) => traces.push({ event, ...fields }),
	...graceOption,
});
bridge.on('message', (event) => {
	if (event.type === 'inspection_result') inspections.get(event.payload.requestId)?.(event);
	const record = records.get(event.agentId);
	if (record === undefined) return;
	if (event.type === 'observation' && event.payload.goalRevision === record.goalRevision) {
		const raw = event.payload.observation ?? event.payload;
		publications.push({ agentId: event.agentId, eventSequence: event.payload.eventSequence, at: now() });
		runtime.updateObservation(record, adaptObservation(raw), { eventSequence: event.payload.eventSequence, ...classifyObservationTrigger(event.payload, raw), changedFacts: event.payload.changedFacts });
	} else if (event.type === 'action_progress') {
		runtime.onActionProgress(record, event.payload);
	} else if (event.type === 'action_result' && commands.some((entry) => entry.actionId === event.payload.actionId)) {
		if (terminal.has(event.payload.state)) results.push({ agentId: event.agentId, actionId: event.payload.actionId, state: event.payload.state, at: now() });
		runtime.onActionResult(record, event.payload);
		bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: event.payload.actionId }).catch((error) => { failure = error; });
	} else if (event.type === 'goal_control') {
		// Like the coordinator: follow the server's goal revision and acknowledge operations that resume the goal.
		if (Number.isSafeInteger(event.payload.goalRevision) && event.payload.goalRevision !== record.goalRevision) {
			goalChanges.push({ agentId: event.agentId, operation: event.payload.operation, goalRevision: event.payload.goalRevision });
			record.goalRevision = event.payload.goalRevision;
		}
		if (['start', 'replace', 'resume', 'steer'].includes(event.payload.operation)) bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision }).catch((error) => { failure = error; });
	} else if (event.type === 'goal_spec_request') {
		bridge.send('goal_spec_proposal', record.agentId, { requestId: event.payload.requestId, summary: 'Obtain a stone pickaxe after the latency fixture.', predicate: { type: 'inventory_contains', itemId: 'minecraft:stone_pickaxe', count: 1 } }).catch((error) => { failure = error; });
	}
});

async function until(predicate, what, timeoutMs = 20000) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (failure) throw failure;
		const value = predicate();
		if (value) return value;
		await sleep(10);
	}
	throw new Error(`Timed out waiting for ${what}`);
}
const percentile = (values, fraction) => values.length === 0 ? null : [...values].sort((a, b) => a - b)[Math.min(values.length - 1, Math.ceil(fraction * values.length) - 1)];
const round = (value) => value === null ? null : Math.round(value * 10) / 10;

const names = Array.from({ length: AGENTS }, (_, index) => `LH${label.replace(/\W/g, '').slice(0, 6)}${index}`);
let timed = null;
try {
	await rcon.connect();
	const ready = new Promise((resolve, reject) => { bridge.once('ready', resolve); setTimeout(() => reject(new Error('bridge not ready')), 12000).unref(); });
	bridge.start(); await ready;
	await bridge.send('catalog_snapshot', 'server', { refreshedAtEpochMs: Date.now(), models: [{ provider: PROFILE.provider, model: PROFILE.model, id: PROFILE.model, displayName: 'latency fixture', reasoningEfforts: ['medium'], serviceTiers: ['priority', 'fast'] }] });
	for (const text of ['forceload add -32 -32 32 32', 'difficulty peaceful']) await command(text);
	await sleep(1200);
	await command('fill -4 63 -4 40 63 4 minecraft:stone');
	await command('fill -4 64 -4 40 70 4 minecraft:air');
	for (const [index, name] of names.entries()) {
		const offset = messages.length;
		await command(`execute in minecraft:overworld positioned ${index * 4 + 0.5} 64 0.5 run codex summon ${PROFILE.model} ${PROFILE.reasoningEffort} ${name}`);
		const registered = await until(() => messages.slice(offset).find((event) => event.type === 'agent_registered'), 'agent_registered');
		const record = { ...(registered.payload.record ?? registered.payload), agentId: registered.agentId };
		records.set(record.agentId, record);
		record.name = record.name ?? name;
		await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
		const goalOffset = messages.length;
		const startReply = await command(`codex start ${record.name} Get a stone pickaxe`);
		const goal = await until(() => messages.slice(goalOffset).find((event) => event.type === 'goal_control' && event.agentId === record.agentId && event.payload.operation === 'start'), 'goal_control').catch((error) => { throw new Error(`${error.message}; start reply: ${startReply}; seen: ${messages.slice(goalOffset).map((e) => e.type).join(',')}`); });
		record.goalRevision = goal.payload.goalRevision;
		await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
		await until(() => messages.slice(goalOffset).find((event) => event.type === 'observation' && event.agentId === record.agentId && event.payload.ready === true), 'ready observation');
		const initial = await passiveObservation(record);
		runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
	}
	await sleep(1500);
	const source = `program.onUnhandledAttention("continue_and_notify"); ${Array.from({ length: Math.ceil(ACTIONS / 2) }, (_, index) => `await player.wait(1); await player.lookAt({x: ${index % 2 === 0 ? 3 : -3}.5, y: 65.5, z: 2.5});`).join(' ')}`;
	const startedAt = now();
	const commandMark = commands.length, requestMark = requests.length, resultMark = results.length, publicationMark = publications.length, traceMark = traces.length;
	const tickBefore = await command('tick query');
	const programs = await Promise.all([...records.values()].map((record) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision, turnId: 'live-hotpath', callId: `live-hotpath-${record.agentId}`, tool: normalizeMinecraftToolCall('runProgram', { source, background: false, maxActions: ACTIONS, timeoutMs: 120000 }) }, record)));
	const endedAt = now();
	const tickAfter = await command('tick query');
	const windowCommands = commands.slice(commandMark), windowResults = results.slice(resultMark), windowRequests = requests.slice(requestMark), windowPublications = publications.slice(publicationMark);
	const gaps = [], publicationDelays = [];
	for (const result of windowResults) {
		const next = windowCommands.find((entry) => entry.agentId === result.agentId && entry.at > result.at);
		if (next !== undefined) gaps.push(next.at - result.at);
		const published = windowPublications.find((entry) => entry.agentId === result.agentId && entry.at >= result.at);
		if (published !== undefined) publicationDelays.push(published.at - result.at);
	}
	const anomalies = messages.filter((e) => ['goal_control', 'error', 'goal_spec_result'].includes(e.type) || (e.type === 'action_result' && e.payload.state !== 'SUCCEEDED')).slice(-12).map((e) => ({ type: e.type, agent: e.agentId?.slice(0, 4), payload: JSON.stringify(e.payload).slice(0, 260) }));
	const tickLines = (text) => text.split('\n').slice(0, 3).join(' | ');
	const stats = (values) => ({ n: values.length, mean: round(values.reduce((a, b) => a + b, 0) / Math.max(1, values.length)), p50: round(percentile(values, 0.5)), p90: round(percentile(values, 0.9)), p95: round(percentile(values, 0.95)), p99: round(percentile(values, 0.99)), max: round(Math.max(...values)) });
	timed = {
		label, srcDir, agents: AGENTS, actionsPerAgent: ACTIONS, graceMs: graceText ?? 'default', wallMs: round(endedAt - startedAt),
		results: windowResults.length, timeline: (() => { const first = [...records.keys()][0]; return [...windowCommands.filter((e) => e.agentId === first).map((e) => ['cmd', e.at - startedAt]), ...windowResults.filter((e) => e.agentId === first).map((e) => ['res', e.at - startedAt]), ...windowRequests.filter((e) => e.agentId === first).map((e) => ['req', e.at - startedAt])].sort((a, b) => a[1] - b[1]).map(([k, t]) => k + ':' + Math.round(t)).slice(0, 80).join(' '); })(), anomalies, goalChanges: goalChanges.map((c) => c.operation).join(','), programs: programs.map((p) => `${p.state}:${p.reasonCode}:${p.actions}`), tick: { before: tickLines(tickBefore), after: tickLines(tickAfter) },
		terminalStates: Object.fromEntries([...new Set(windowResults.map((r) => r.state))].map((state) => [state, windowResults.filter((r) => r.state === state).length])),
		gapMs: stats(gaps), publicationDelayMs: stats(publicationDelays),
		observationRequests: windowRequests.length, observationRequestsPerAction: round(windowRequests.length / Math.max(1, windowResults.length) * 1000) / 1000,
		publicationsPerAction: round(windowPublications.length / Math.max(1, windowResults.length) * 1000) / 1000,
		publicationMisses: traces.slice(traceMark).filter((t) => t.event === 'native_post_result_publication_missed').length,
	};
	console.log(JSON.stringify(timed));
} finally {
	try { await runtime.disposeAll(); } catch {}
	for (const record of records.values()) await command(`codex remove ${record.name}`).catch(() => {});
	for (const name of names) await command(`codex remove ${name}`).catch(() => {});
	if (timed !== null) await writeFile(outFile, JSON.stringify(timed, null, 2));
	bridge.stop(); await rcon.close().catch(() => {});
}
process.exit(timed === null ? 1 : 0);
