// Hot-path replay for the coordinator's per-action work. It drives the real DynamicCoordinator, native tool
// runtime, ArenaScript executor and disk notebook against a tick-quantized stand-in for the Fabric server, so
// it measures coordinator cost only (no model, no Minecraft). Run it from any checkout, e.g. a "before" copy:
//   node src/benchmark/coordinator-hot-path-benchmark.mjs <coordinator dir> [agents] [actions] [model|program|obs]
//   model   each agent blocks on navigate_to calls one after another: tool call -> command sent, result -> tool returned
//   program each agent runs one ArenaScript routine of back-to-back waits: result -> next command sent
//   obs     a running routine receives a stream of observations: ingest latency per observation
// Environment: TICK_MS (server tick, default 50). Wall times are noisy on a shared machine; compare alternating runs.
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { mkdtemp, rm } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import os from 'node:os';

const root = path.resolve(process.argv[2]);
const AGENTS = Number(process.argv[3] ?? 1);
const ACTIONS = Number(process.argv[4] ?? 40);
const WORKLOAD = process.argv[5] ?? 'model'; // model | program
const TICK = Number(process.env.TICK_MS ?? 50);
const OBS_SIZE = Number(process.env.OBS_SCALE ?? 1);
const imp = (rel) => import(pathToFileURL(path.join(root, rel)).href);
const fx = await imp('test/fixtures/dynamic-main-fixture.mjs');
const { AgentRegistry } = await imp('src/agent-registry.mjs');
const { FakeBridge, FakePlanner, RecordingGoalSupervisor, factToWireObservation, eventually, start, record } = fx;

const cpuMs = () => { const u = process.cpuUsage(); return (u.user + u.system) / 1000; };
const q = (a, p) => { if (!a.length) return NaN; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.ceil(s.length * p) - 1)]; };
const fmt = (a) => a.length ? `n=${a.length} p50 ${q(a, .5).toFixed(2)} p95 ${q(a, .95).toFixed(2)} mean ${(a.reduce((x, y) => x + y, 0) / a.length).toFixed(2)}` : 'n=0';

const baseObs = new Map();
function bigObservation(agentId, goalRevision, seq) {
	const key = `${agentId}:${goalRevision}`;
	if (!baseObs.has(key)) baseObs.set(key, buildObservation(agentId, goalRevision, 1));
	return { ...baseObs.get(key), eventSequence: seq, observedAtEpochMs: Date.now() };
}
function buildObservation(agentId, goalRevision, seq) {
	const blocks = Array.from({ length: 120 }, (_, i) => ({ x: i % 13, y: 60 + (i % 7), z: (i * 3) % 17, blockId: 'minecraft:stone' }));
	const items = Array.from({ length: 40 }, (_, i) => ({ stableId: `item-${agentId}-${i}`, x: i, y: 64, z: i, itemId: 'minecraft:cobblestone', count: 1 + i }));
	const inv = Array.from({ length: 24 }, (_, i) => ({ itemId: `minecraft:item_${i}`, count: 3, slot: i }));
	const o = factToWireObservation({ player: { x: 0, y: 64, z: 0 }, blocks, items, inventory: { items: inv } }, goalRevision, seq, true, Date.now(), []);
	o.world.worldId = `bench-world-${agentId}`;
	return o;
}

class SimBridge extends FakeBridge {
	automaticInspections = false; trim = false;
	stats = { actionCommands: 0, inspections: 0 };
	marks = []; // { agentId, actionId, send, result, nextSend }
	inbound = []; running = []; seq = new Map();
	tickTimer = null; nextTick = 0;
	startTicks() { this.nextTick = performance.now(); const loop = () => { this.nextTick += TICK; this.tickTimer = setTimeout(() => { this.tick(); loop(); }, Math.max(0, this.nextTick - performance.now())); }; loop(); }
	stopTicks() { clearTimeout(this.tickTimer); }
	emit(event, message) {
		if (event === 'observation') { if (Number.isSafeInteger(message?.payload?.eventSequence)) this.latestSequences.set(message.agentId, Math.max(this.latestSequences.get(message.agentId) ?? 0, message.payload.eventSequence)); return EventEmitter.prototype.emit.call(this, event, message); }
		return super.emit(event, message);
	}
	nextSeq(agentId) { const n = (this.seq.get(agentId) ?? 1000) + 1; this.seq.set(agentId, n); return n; }
	async send(type, agentId, payload, options = {}) {
		if (this.trim) this.sent.length = 0; // keep memory flat
		if (type === 'action_command') {
			this.stats.actionCommands++;
			const mark = { agentId, actionId: payload.actionId, actionType: payload.actionType, send: performance.now(), cpuSend: cpuMs(), result: null, cpuResult: null };
			this.marks.push(mark);
			const ticks = payload.actionType === 'wait' ? Math.max(1, Math.ceil((payload.arguments.durationMs ?? 50) / TICK)) : 2;
			this.inbound.push(() => this.running.push({ mark, agentId, payload, remaining: ticks, epoch: options.connectionEpoch }));
		} else if (type === 'inspection_request') {
			this.stats.inspections++;
			const request = { type, agentId, payload, connectionEpoch: options.connectionEpoch };
			this.inbound.push(() => this.answerInspection(request));
		} else return super.send(type, agentId, payload, options);
	}
	answerInspection(request) {
		const seq = this.nextSeq(request.agentId);
		const o = bigObservation(request.agentId, request.payload.goalRevision, seq);
		this.emit('observation', { agentId: request.agentId, connectionEpoch: request.connectionEpoch, payload: o });
		// replyInspection expects the wire observation, same as sampleInspection
		this.replyInspection(request, { observation: o, eventSequence: seq });
	}
	tick() {
		for (const task of this.inbound.splice(0)) task();
		const done = [];
		for (const a of [...this.running]) { a.remaining -= 1; if (a.remaining <= 0) { this.running.splice(this.running.indexOf(a), 1); done.push(a); } }
		for (const a of done) {
			const seq = this.nextSeq(a.agentId);
			a.mark.result = performance.now(); a.mark.cpuResult = cpuMs();
			this.emit('action_result', { agentId: a.agentId, connectionEpoch: a.epoch, payload: { goalRevision: a.payload.goalRevision, actionId: a.payload.actionId, actionType: a.payload.actionType, state: 'SUCCEEDED', reasonCode: 'DONE', executionStarted: true, physicalAttempted: true, eventSequence: seq } });
			// post-physics publication of the attention observation, same tick
			const o = bigObservation(a.agentId, a.payload.goalRevision, this.nextSeq(a.agentId));
			setImmediate(() => this.rawObservation(a.agentId, a.epoch, o));
		}
	}
	rawObservation(agentId, epoch, o) { this.emit('observation', { agentId, connectionEpoch: epoch, payload: o }); }
}

const dir = await mkdtemp(path.join(os.tmpdir(), 'hot-notebook-'));
const registry = new AgentRegistry({ agentCap: 16 });
const planner = new FakePlanner(registry);
const executors = new Map();
planner.requestNativeTurn = async (request) => { executors.set(request.agentId, request); planner.requests.push(request); await new Promise(() => {}); };
const bridge = new SimBridge();
const agentIds = Array.from({ length: AGENTS }, (_, i) => `agent-${i + 1}`);
const config = { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } };
const run = await start({ bridge, registry, planner, goalSupervisor: new RecordingGoalSupervisor(), config, memoryDirectory: dir, initialRegistry: agentIds.map((id) => record(id)) });
for (const id of agentIds) {
	bridge.emit('goal_control', { agentId: id, payload: { operation: 'start', goalRevision: 1, goal: 'Bench loop.' } });
	bridge.emit('observation', { agentId: id, payload: bigObservation(id, 1, 1) });
	bridge.seq.set(id, 1);
}
run.coordinator.on('runtimeError', (e) => console.error('runtimeError', e?.code, e?.message));
try { await eventually(() => executors.size === AGENTS); } catch (e) { console.error('executors', executors.size, 'requests', planner.requests.length, 'states', agentIds.map((id) => registry.get(id)?.state)); throw e; }
bridge.trim = true; bridge.sent.length = 0;
bridge.startTicks();

const perCall = []; // tool latency minus server time
const sendDelays = []; // tool invoked -> command sent
const returnDelays = []; const sendCpu = []; const returnCpu = []; const gapCpu = []; // result emitted -> tool returned
let callId = 0;
async function modelLoop(id) {
	const req = executors.get(id);
	for (let i = 0; i < ACTIONS; i++) {
		const tool = { kind: 'action', actionType: 'navigate_to', arguments: { x: 4, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5000 } };
		const before = bridge.marks.length;
		const t0 = performance.now(); const c0 = cpuMs();
		const result = await req.executeTool({ agentId: id, goalRevision: 1, turnId: `turn-${id}`, callId: `call-${++callId}`, tool });
		const t1 = performance.now(); const c1 = cpuMs();
		const mark = bridge.marks.findLast((m) => m.agentId === id);
		if (i >= 3) {
			sendDelays.push(mark.send - t0); sendCpu.push(mark.cpuSend - c0);
			returnDelays.push(t1 - mark.result); returnCpu.push(c1 - mark.cpuResult);
		}
		if (result.state !== 'SUCCEEDED') throw new Error(`action ${result.state} ${result.reasonCode}`);
	}
}
const obsTimes = [];
async function obsLoop(id) {
	const req = executors.get(id);
	// a long-running program makes the ingest path identical to a busy scripted session
	const source = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(100000);';
	const pending = req.executeTool({ agentId: id, goalRevision: 1, turnId: `turn-${id}`, callId: `call-${++callId}`, tool: { kind: 'run_program', source, background: true, maxActions: 4, timeoutMs: 120000 } });
	await pending;
	for (let i = 0; i < ACTIONS; i++) {
		const o = { ...bigObservation(id, 1, bridge.nextSeq(id)), attention: false, changedFacts: [], position: { x: i * 0.1, y: 64, z: 0 } };
		let done; const ingress = new Promise((resolve) => { done = resolve; });
		const t0 = performance.now();
		bridge.emit('observation', { agentId: id, connectionEpoch: bridge.connectionEpoch, payload: o, waitUntil(promise) { promise.then(done, done); } });
		await ingress; obsTimes.push(performance.now() - t0);
		await new Promise((resolve) => setImmediate(resolve));
	}
}
async function programLoop(id) {
	const req = executors.get(id);
	const source = 'program.onUnhandledAttention("continue_and_notify"); ' + 'await player.wait(50);'.repeat(ACTIONS);
	const result = await req.executeTool({ agentId: id, goalRevision: 1, turnId: `turn-${id}`, callId: `call-${++callId}`, tool: { kind: 'run_program', source, maxActions: ACTIONS + 4, timeoutMs: 120000 } });
	if (result.reasonCode !== 'PROGRAM_EXHAUSTED') throw new Error(`program ended ${result.state}/${result.reasonCode} ${result.message ?? ''}`);
}
const cpu0 = process.cpuUsage();
const wall0 = performance.now();
await Promise.all(agentIds.map((id) => (WORKLOAD === 'model' ? modelLoop(id) : WORKLOAD === 'obs' ? obsLoop(id) : programLoop(id))));
const wall = performance.now() - wall0;
const cpu = process.cpuUsage(cpu0);
bridge.stopTicks();

const total = WORKLOAD === 'obs' ? ACTIONS * AGENTS : bridge.stats.actionCommands;
console.log(`workload=${WORKLOAD} agents=${AGENTS} actions/agent=${ACTIONS} tick=${TICK}ms obsScale=${OBS_SIZE}`);
console.log(`wall ${wall.toFixed(0)} ms, coordinator CPU ${((cpu.user + cpu.system) / 1000).toFixed(0)} ms (${((cpu.user + cpu.system) / 1000 / total).toFixed(2)} ms/action), commands ${total}, server inspection requests ${bridge.stats.inspections} (${(bridge.stats.inspections / total).toFixed(2)}/action)`);
if (WORKLOAD === 'model') {
	console.log('tool invoked -> command sent (ms):', fmt(sendDelays));
	console.log('result emitted -> tool returned (ms):', fmt(returnDelays));
	console.log('  CPU-time in tool->send:', fmt(sendCpu), '| CPU-time in result->return:', fmt(returnCpu));
} else if (WORKLOAD === 'obs') {
	console.log('observation ingest latency (ms):', fmt(obsTimes.slice(10)));
	for (let b = 0; b < obsTimes.length; b += Math.ceil(obsTimes.length / 6)) console.log('  obs', b, 'p50', q(obsTimes.slice(b, b + Math.ceil(obsTimes.length / 6)), .5).toFixed(1));
} else {
	// handoff gap: result of action k -> send of action k+1, per agent
	const gaps = [];
	for (const id of agentIds) {
		const m = bridge.marks.filter((x) => x.agentId === id);
		for (let i = 3; i < m.length - 1; i++) { gaps.push(m[i + 1].send - m[i].result); gapCpu.push(m[i + 1].cpuSend - m[i].cpuResult); }
	}
	console.log('program handoff: result emitted -> next command sent (ms):', fmt(gaps));
	console.log('  CPU-time in handoff:', fmt(gapCpu));
}
await run.coordinator.stop();
await rm(dir, { recursive: true, force: true });
process.exit(0);
