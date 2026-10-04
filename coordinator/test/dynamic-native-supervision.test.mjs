import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { FakePlanner, RecordingGoalSupervisor, record, factToWireObservation, DEATH, eventually, start, NativeLifecycleClock } from './fixtures/dynamic-main-fixture.mjs';

for (const providerEnd of ['failure', 'expiry']) {
	for (const startsSuccessor of [false, true]) {
		const body = startsSuccessor ? 'a started successor' : 'the original routine';
		test(`native preparation ${providerEnd} preserves ${body} after advisory steering`, async (t) => {
			t.mock.timers.enable({ apis: ['setTimeout'] });
			const registry = new AgentRegistry();
			const planner = new FakePlanner(registry);
			const supervisor = new RecordingGoalSupervisor();
			const providerLeases = [];
			const begin = supervisor.begin.bind(supervisor);
			supervisor.begin = (key, kind) => {
				const token = begin(key, kind);
				if (kind === 'provider') providerLeases.push({ key, lease: { kind, operationId: token.operationId } });
				return token;
			};
			const steers = [];
			let handle, releaseTurn;
			const turnGate = new Promise(resolve => { releaseTurn = resolve; });
			planner.getNativeDecisionTiming = () => ({ count: 4, p50Ms: 500, p95Ms: 999 });
			planner.steerNativeTurn = async request => { steers.push(request); };
			planner.requestNativeTurn = async request => {
				planner.requests.push(request);
				if (planner.requests.length > 1) return { status: 'completed', toolCalls: 0 };
				handle = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision,
					turnId: 'steered-preparation', callId: 'initial-program', tool: { kind: 'run_program', background: true,
						timeoutMs: 30_000, expectedDurationMs: 1000, maxActions: 2,
						source: `program.onUnhandledAttention("continue_and_notify"); await player.wait(1);${startsSuccessor ? '' : ' await player.wait(2);'}` } });
				await turnGate;
				if (providerEnd === 'failure') throw Object.assign(new Error('Prepared provider turn failed'), { code: 'TEST_PREPARATION_FAILED' });
				return { status: 'completed', toolCalls: startsSuccessor ? 2 : 1 };
			};
			const run = await start({ registry, planner, goalSupervisor: supervisor,
				config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
			const commands = () => run.bridge.sent.filter(message => message.type === 'action_command');
			const finish = command => run.bridge.emit('action_result', { agentId: 'agent-a', payload: {
				goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE',
				eventSequence: run.bridge.latestSequences.get('agent-a') + 1,
			} });
			try {
				run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Keep the authorised routine moving.' } });
				const wire = factToWireObservation({ player: { x: 0, y: 64, z: 0, health: 20 } }, 1, 1, false, 1);
				wire.world.worldId = 'prepared-handoff-test-world';
				run.bridge.emit('observation', { agentId: 'agent-a', payload: wire });
				await eventually(() => handle && commands().length === 1);
				t.mock.timers.tick(1);
				await eventually(() => steers.length === 1);
				assert.match(steers[0].input, /program_planning_due/);
				assert.equal(planner.requests.length, 1, 'the advisory belongs to the original selected-agent turn');

				let continuingProgramId = handle.programId;
				if (startsSuccessor) {
					const queued = await planner.requests[0].executeTool({ agentId: 'agent-a', goalRevision: 1,
						turnId: 'steered-preparation', callId: 'prepared-successor', tool: { kind: 'queue_program',
							afterProgramId: handle.programId, goalRevision: 1, programVersion: handle.programVersion,
							source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(7); await player.wait(8);',
							precondition: 'player.state().health === 20', maxActions: 2, timeoutMs: 30_000 } });
					assert.equal(queued.state, 'QUEUED');
					finish(commands()[0]);
					await eventually(() => commands().length === 2);
					continuingProgramId = commands()[1].payload.provenance.programId;
					assert.notEqual(continuingProgramId, handle.programId);
					assert.equal(commands()[1].payload.arguments.durationMs, 7);
					assert.equal(planner.requests.length, 1, 'handoff must not start a duplicate planner turn');
				}

				if (providerEnd === 'expiry') {
					assert.equal(providerLeases.length, 1);
					run.coordinator.handleLeaseExpired(providerLeases[0]);
					await eventually(() => planner.interruptions.includes('agent-a'));
					releaseTurn();
				} else {
					releaseTurn();
					await eventually(() => run.bridge.sent.some(message => message.type === 'agent_error' && message.payload.code === 'TEST_PREPARATION_FAILED'));
				}
				await new Promise(resolve => setImmediate(resolve));
				assert.equal(run.bridge.sent.some(message => message.type === 'action_cancel'), false, 'provider preparation cannot cancel authorised body work');
				assert.notEqual(registry.get('agent-a').state, DynamicAgentState.ERROR);
				const before = commands().length;
				finish(commands().at(-1));
				await eventually(() => commands().length === before + 1);
				assert.equal(commands().at(-1).payload.provenance.programId, continuingProgramId);
				assert.equal(commands().at(-1).payload.arguments.durationMs, startsSuccessor ? 8 : 2);
				assert.equal(planner.requests.length, 1, 'ordinary progress and a handoff must not replay a pending planner turn');
			} finally {
				releaseTurn();
				await run.coordinator.stop();
			}
		});
	}
}

test('stopping a native routine suppresses its pending planning reminder', async (t) => {
	// Keep the one-millisecond reminder pending until the stop is delivered.
	// A real timer can legitimately fire while eventually() yields on a busy host.
	t.mock.timers.enable({ apis: ['setTimeout'] });
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	const steers = [];
	let handle, releaseTurn;
	const turnGate = new Promise((resolve) => { releaseTurn = resolve; });
	planner.getNativeDecisionTiming = () => ({ count: 4, p50Ms: 4000, p95Ms: 4999 });
	planner.steerNativeTurn = async (request) => { steers.push(request); };
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		handle = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision,
			turnId: 'prepare-turn', callId: 'prepare-call', tool: { kind: 'run_program', background: true,
			timeoutMs: 5000, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);' } });
		await turnGate;
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({ registry, planner, goalSupervisor: new RecordingGoalSupervisor(),
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const commands = () => run.bridge.sent.filter((message) => message.type === 'action_command');
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Stop before preparing.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1,
			observation: { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } } } });
		await eventually(() => handle && commands().length === 1);
		assert.equal(steers.length, 0, 'the reminder has not fired before the stop');
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 2 } });
		await eventually(() => run.bridge.sent.some((message) => message.type === 'action_cancel'));
		t.mock.timers.tick(1);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(steers.length, 0, 'a stopped program cannot deliver its queued reminder');
		assert.equal(planner.requests.length, 1, 'stopping the body cannot create a successor model turn');
	} finally {
		releaseTurn();
		await run.coordinator.stop();
	}
});

test('native coordinator ingress preserves infinite effects through planning and fresh observe', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let observed, releaseTurn;
	const turnGate = new Promise((resolve) => { releaseTurn = resolve; });
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		observed = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision,
			turnId: 'infinite-effect-turn', callId: 'observe-effects', tool: { kind: 'observe' } });
		await turnGate;
		return { status: 'completed', toolCalls: 1 };
	};
	const run = await start({ registry, planner,
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	const runtimeErrors = [];
	run.coordinator.on('runtimeError', (error) => runtimeErrors.push(error));
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Observe my active effects.' } });
		const payload = factToWireObservation({ player: { x: 0, y: 64, z: 0, health: 20 } }, 1, 1, false, 1);
		payload.player.effects = [
			{ effectId: 'minecraft:speed', amplifier: 0, duration: 120 },
			{ effectId: 'minecraft:haste', amplifier: 1, duration: -1 },
		];
		run.bridge.emit('observation', { agentId: 'agent-a', payload: validateProtocolV2Payload('observation', payload) });
		await eventually(() => observed !== undefined);
		assert.equal(planner.requests.length, 1);
		assert.equal(observed.freshness.fresh, true);
		assert.ok(observed.eventSequence > payload.eventSequence, 'native observe completes with a newer server sample');
		assert.deepEqual(observed.observation.player.effects, payload.player.effects);
		assert.deepEqual(runtimeErrors, []);
		assert.equal(run.bridge.sent.some((message) => message.type === 'agent_error'), false);
		assert.equal(run.bridge.connected, true);
	} finally {
		releaseTurn();
		await run.coordinator.stop();
	}
});

for (const ending of ['settled', 'handoff', 'deadline', 'stuck', 'stopped']) {
	test(`native background lifetime ${ending} retains bounded ownership through timestamp-only heartbeats`, async () => {
		const clock = new NativeLifecycleClock(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
		let completed = false;
		planner.requestNativeTurn = async request => {
			planner.requests.push(request);
			if (planner.requests.length > 1) return new Promise(() => {});
			const handle = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'background', callId: 'run',
				tool: { kind: 'run_program', background: true, timeoutMs: 60000, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(10000);' } });
			if (ending === 'handoff') await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'background', callId: 'queue', tool: { kind: 'queue_program', afterProgramId: handle.programId, goalRevision: 1, programVersion: handle.programVersion, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(7000);', precondition: 'player.state().health === 20', timeoutMs: 60000 } });
			completed = true;
			return { toolCalls: 1 };
		};
		const run = await start({ registry, planner, ...clock.dependencies(), config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		const observations = () => run.bridge.sent.filter(message => message.type === 'request_observation');
		try {
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
			const wire = factToWireObservation({ player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } }, 1, 1, false, 1);
			wire.world.worldId = 'lifetime-test-world';
			run.bridge.emit('observation', { agentId: 'agent-a', payload: wire });
			await eventually(() => completed && run.bridge.sent.some(message => message.type === 'action_command'));
			await clock.advance(0);
			const before = observations().length;
			for (let i = 1; i <= 3; i++) {
				await clock.advance(1000);
				run.bridge.emit('observation', { agentId: 'agent-a', payload: { ...run.bridge.latestObservations.get('agent-a'), eventSequence: run.bridge.latestSequences.get('agent-a') + 1, observedAtEpochMs: i * 1000, attention: false, changedFacts: [] } });
				await clock.advance(0);
			}
			assert.equal(planner.requests.length, 1);
			assert.equal(observations().length, before, 'no idle recovery while the body owns its program deadline');
			const command = run.bridge.sent.find(message => message.type === 'action_command');
			if (ending === 'handoff') {
				run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 10 } });
				await eventually(() => run.bridge.sent.filter(message => message.type === 'action_command').length === 2);
				await clock.advance(3000);
				assert.equal(planner.requests.length, 1, 'authorized successor needs no duplicate turn');
				assert.equal(observations().length, before, 'successor inherits bounded ownership without an idle recovery gap');
			} else if (ending === 'settled') {
				run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 10 } });
				await eventually(() => planner.requests.length === 2);
				assert.match(planner.requests[1].input, /program_ended/);
			} else if (ending === 'stopped') {
				run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 2 } });
				await eventually(() => registry.get('agent-a').goalRevision === 2);
				await clock.advance(61000);
				assert.equal(planner.requests.length, 1);
				assert.equal(observations().length, before);
			} else {
				if (ending === 'deadline') {
					// New factual progress clears the independent stall timer, leaving the body deadline.
					await clock.advance(25000);
					const previous = run.bridge.latestObservations.get('agent-a');
					run.bridge.emit('observation', { agentId: 'agent-a', payload: validateProtocolV2Payload('observation', { ...previous, eventSequence: 10, position: { ...previous.position, x: 1 }, attention: false }) });
					await clock.advance(0);
					await clock.advance(25000);
					const latest = run.bridge.latestObservations.get('agent-a');
					run.bridge.emit('observation', { agentId: 'agent-a', payload: validateProtocolV2Payload('observation', { ...latest, eventSequence: 11, position: { ...latest.position, x: 2 }, attention: false }) });
					await clock.advance(0);
					await clock.advance(7000);
					assert.ok(run.bridge.sent.some(message => message.type === 'action_cancel'), 'expired program relinquishes physical authority');
				} else await clock.advance(27000);
				assert.equal(observations().length, before + 1, 'real stall/deadline still requests recovery');
				run.bridge.emit('observation', { agentId: 'agent-a', payload: { ...run.bridge.latestObservations.get('agent-a'), eventSequence: 20, observedAtEpochMs: clock.now, attention: false } });
				await eventually(() => planner.requests.length === 2);
				assert.match(planner.requests[1].input, /continuation/);
			}
		} finally { await run.coordinator.stop(); }
	});
}

for (const outcome of ['empty', 'transient', 'circuit']) {
	test(`native DEAD ${outcome} turn retries death facts through real supervision`, async () => {
		const clock = new NativeLifecycleClock(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
		planner.requestNativeTurn = async request => {
			planner.requests.push(request);
			if (planner.requests.length > 1) return new Promise(() => {});
			if (outcome === 'circuit') throw Object.assign(new Error('cooldown'), { code: 'PROVIDER_CIRCUIT_OPEN', nextProbeAtEpochMs: 5000 });
			if (outcome === 'transient') throw Object.assign(new Error('transport unavailable'), { code: 'ECONNRESET' });
			return { toolCalls: 0 };
		};
		const run = await start({ registry, planner, ...clock.dependencies(), epochNow: () => clock.now, initialRegistry: [{ ...record(), state: DynamicAgentState.DEAD, currentGoal: 'Survive.', goalRevision: 4, death: DEATH }], config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		try {
			await eventually(() => planner.requests.length === 1);
			await clock.advance(0);
			await clock.advance(2000);
			if (outcome === 'circuit') {
				assert.equal(planner.requests.length, 1, 'death recovery honors the provider probe deadline');
				await clock.advance(3000);
			}
			await eventually(() => planner.requests.length === 2);
			assert.equal(registry.get('agent-a').state, DynamicAgentState.DEAD);
			assert.equal(planner.requests[1].goalRevision, 4);
			assert.equal(planner.requests[1].preserveState, true);
			assert.match(planner.requests[1].input, /player_death/);
			assert.equal(run.bridge.sent.some(message => message.type === 'request_observation'), false);
			run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'stop', goalRevision: 5 } });
			await eventually(() => registry.get('agent-a').goalRevision === 5);
			await clock.advance(900001);
			assert.equal(planner.requests.length, 2, 'old recovery cannot cross goal revision');
		} finally { await run.coordinator.stop(); }
	});
}

for (const toolCalls of [0, 1]) {
	test(`idle failed steering replays pending conversation after ${toolCalls}-tool completion`, async () => {
		const registry = new AgentRegistry(), planner = new FakePlanner(registry);
		let finish, steerAttempts = 0;
		const gate = new Promise(resolve => { finish = resolve; });
		planner.requestNativeTurn = async request => { planner.requests.push(request); if (planner.requests.length === 1) await gate; else return new Promise(() => {}); return { toolCalls }; };
		planner.steerNativeTurn = async () => { steerAttempts++; throw Object.assign(new Error('ended'), { code: 'TURN_NOT_ACTIVE' }); };
		const run = await start({ registry, planner, config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
		const message = sequence => run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: { sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct', text: `Question ${sequence}`, goalRevision: 0, observedAtEpochMs: sequence } });
		try {
			message(1); await eventually(() => planner.requests.length === 1);
			message(2); await eventually(() => steerAttempts === 1);
			finish(); await eventually(() => planner.requests.length === 2);
			assert.match(planner.requests[1].input, /Question 2/);
			assert.equal(planner.requests[1].preserveState, true);
			assert.equal(registry.get('agent-a').state, DynamicAgentState.IDLE);
		} finally { finish(); await run.coordinator.stop(); }
	});
}

test('detached native action retains supervision until its actual receipt settles', async () => {
	const clock = new NativeLifecycleClock(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
	let handle;
	planner.requestNativeTurn = async request => {
		planner.requests.push(request);
		if (planner.requests.length > 1) return new Promise(() => {});
		handle = await request.executeTool({ agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'async-action', callId: 'start', tool: { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 10000 } } });
		return { toolCalls: 1 };
	};
	const run = await start({ registry, planner, ...clock.dependencies(), config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	try {
		run.bridge.emit('goal_control', { agentId: 'agent-a', payload: { operation: 'start', goalRevision: 1, goal: 'Wait.' } });
		run.bridge.emit('observation', { agentId: 'agent-a', payload: { goalRevision: 1, eventSequence: 1, observation: { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } } } });
		await eventually(() => handle);
		await clock.advance(0);
		const before = run.bridge.sent.filter(message => message.type === 'request_observation').length;
		await clock.advance(3000);
		assert.equal(run.bridge.sent.filter(message => message.type === 'request_observation').length, before);
		run.bridge.emit('action_result', { agentId: 'agent-a', payload: { goalRevision: 1, actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 } });
		await clock.advance(0);
		await clock.advance(2000);
		assert.equal(run.bridge.sent.filter(message => message.type === 'request_observation').length, before + 1, 'settled action releases ownership to continuation recovery');
	} finally { await run.coordinator.stop(); }
});

test('actual wire position progress prevents false factual stall', async () => {
 const clock = new NativeLifecycleClock(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
 let started = false;
 planner.requestNativeTurn = async request => {
  planner.requests.push(request);
  if (planner.requests.length > 1) return new Promise(() => {});
  await request.executeTool({agentId: request.agentId, goalRevision: request.goalRevision, turnId: 'review', callId: 'run', tool: {kind:'run_program',background:true,timeoutMs:60000,source:'program.onUnhandledAttention("continue_and_notify"); await player.wait(10000);'}});
  started = true; return {toolCalls:1};
 };
 const run = await start({registry,planner,...clock.dependencies(),config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 try {
  run.bridge.emit('goal_control',{agentId:'agent-a',payload:{operation:'start',goalRevision:1,goal:'Continue moving.'}});
  const wire = factToWireObservation({player:{x:0,y:64,z:0,health:20},inventory:{items:[]}},1,1,false,1);
  wire.world.worldId = 'review-world';
  run.bridge.emit('observation',{agentId:'agent-a',payload:validateProtocolV2Payload('observation',wire)});
  await eventually(()=>started && run.bridge.sent.some(m=>m.type==='action_command'));
  await clock.advance(0);
  const before = run.bridge.sent.filter(m=>m.type==='request_observation').length;
  for(let index=1;index<=6;index++) {
   await clock.advance(4999);
   const latest = run.bridge.latestObservations.get('agent-a');
   const next = {...latest,eventSequence:run.bridge.latestSequences.get('agent-a')+1,observedAtEpochMs:clock.now,position:{x:index,y:64,z:0},attention:false,changedFacts:[]};
   run.bridge.emit('observation',{agentId:'agent-a',payload:validateProtocolV2Payload('observation',next)});
   await clock.advance(0);
  }
  await clock.advance(6);
  assert.equal(run.bridge.sent.filter(m=>m.type==='request_observation').length,before,'six authoritative forward position changes must reset factual stall timer');
 } finally {await run.coordinator.stop();}
});

test('expired conversation late success preserves successor provider lease', async () => {
 const clock = new NativeLifecycleClock(), registry = new AgentRegistry(), planner = new FakePlanner(registry);
 let finishFirst;
 const gate = new Promise(resolve => { finishFirst = resolve; });
 planner.getExecutionSettings = () => ({limits:{nativeTurnBudgetMs:100}});
 planner.requestNativeTurn = async request => { planner.requests.push(request); if(planner.requests.length===1) return gate; return new Promise(()=>{}); };
 const run = await start({registry,planner,...clock.dependencies(),config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 try {
  run.bridge.emit('conversation_event',{agentId:'agent-a',payload:{sequence:1,kind:'player_message',sourceId:'player-a',recipientId:'agent-a',scope:'direct',text:'Question one',goalRevision:0,observedAtEpochMs:1}});
  await eventually(()=>planner.requests.length===1);
  await clock.advance(100);
  await eventually(()=>planner.requests.length===2);
  assert.equal(planner.requests.length,2,'expiry creates replacement conversation turn');
  finishFirst({toolCalls:1});
  await clock.advance(0);
  await clock.advance(100);
  await eventually(()=>planner.requests.length===3);
  assert.equal(planner.requests.length,3,'replacement must retain its deadline after obsolete completion');
 } finally { finishFirst({toolCalls:1}); await run.coordinator.stop(); }
});
