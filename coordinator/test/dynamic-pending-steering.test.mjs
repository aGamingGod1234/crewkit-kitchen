import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { FakePlanner, record, DEATH, eventually, start, NativeLifecycleClock } from './fixtures/dynamic-main-fixture.mjs';

for (const state of [DynamicAgentState.PAUSED,DynamicAgentState.COMPLETED]) for (const toolCalls of [0,1]) {
 test(`review pending message ${state} ${toolCalls} tools is replayed once`,async()=>{
  const registry=new AgentRegistry(),planner=new FakePlanner(registry); let finish;let attempts=0;
  const traceEvents=[];
  const gate=new Promise(resolve=>{finish=resolve;});
  planner.requestNativeTurn=async request=>{planner.requests.push(request);if(planner.requests.length===1)await gate;return {toolCalls};};
  planner.steerNativeTurn=async()=>{attempts++;throw Object.assign(new Error('turn ended'),{code:'TURN_NOT_ACTIVE'});};
  const run=await start({registry,planner,traceWriter:{write(event){traceEvents.push(event);}},initialRegistry:[{...record(),state,currentGoal:'Wait.',goalRevision:1}],config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
  const say=sequence=>run.bridge.emit('conversation_event',{agentId:'agent-a',payload:{sequence,kind:'player_message',sourceId:'player-a',recipientId:'agent-a',scope:'direct',text:`Question ${sequence}`,goalRevision:1,observedAtEpochMs:sequence}});
  try{
   say(1);await eventually(()=>planner.requests.length===1);say(2);await eventually(()=>attempts===1);finish();await eventually(()=>planner.requests.length>=2);
   // Completion follows the durable receipt commit; event-loop turns do not drain disk I/O.
   await eventually(()=>traceEvents.filter(event=>event==='native_turn_completed').length===3);
   assert.match(planner.requests[1].input,/Question 2/);
   assert.equal(planner.requests.length,3,'without a successful chat receipt only one reply correction is allowed');
   assert.equal(registry.get('agent-a').state,state);
   assert.equal(run.bridge.sent.filter(m=>m.type==='action_command').length,0);
  }finally{finish();await run.coordinator.stop();}
 });
}

for (const code of ['STALE_PLAN','ECONNRESET']) test(`expired conversation late ${code} preserves successor provider lease`,async()=>{
 const clock=new NativeLifecycleClock(),registry=new AgentRegistry(),planner=new FakePlanner(registry);let rejectFirst;
 const gate=new Promise((resolve,reject)=>{rejectFirst=reject;});
 planner.getExecutionSettings=()=>({limits:{nativeTurnBudgetMs:100}});
 planner.requestNativeTurn=async request=>{planner.requests.push(request);if(planner.requests.length===1)return gate;return new Promise(()=>{});};
 const run=await start({registry,planner,...clock.dependencies(),config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 try{
  run.bridge.emit('conversation_event',{agentId:'agent-a',payload:{sequence:1,kind:'player_message',sourceId:'player-a',recipientId:'agent-a',scope:'direct',text:'Question one',goalRevision:0,observedAtEpochMs:1}});
  await eventually(()=>planner.requests.length===1);await clock.advance(100);await eventually(()=>planner.requests.length===2);assert.equal(planner.requests.length,2);
  rejectFirst(Object.assign(new Error('obsolete rejection'),{code}));await clock.advance(0);await clock.advance(100);
  await eventually(()=>planner.requests.length===3);
  assert.equal(planner.requests.length,3,'obsolete failure cannot remove successor supervision');
 }finally{rejectFirst(Object.assign(new Error('cleanup'),{code}));await run.coordinator.stop();}
});

test('review completed conversation waiting for failed steer cannot terminate its replacement',async()=>{
 const clock=new NativeLifecycleClock(),registry=new AgentRegistry(),planner=new FakePlanner(registry);let finishFirst,rejectSteer;let steers=0;
 const gate=new Promise(resolve=>{finishFirst=resolve;});const steerGate=new Promise((resolve,reject)=>{rejectSteer=reject;});
 planner.getExecutionSettings=()=>({limits:{nativeTurnBudgetMs:100}});
 planner.requestNativeTurn=async request=>{planner.requests.push(request);if(planner.requests.length===1)return gate;return new Promise(()=>{});};
 planner.steerNativeTurn=async()=>{steers++;return steerGate;};
 const run=await start({registry,planner,...clock.dependencies(),config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 const say=sequence=>run.bridge.emit('conversation_event',{agentId:'agent-a',payload:{sequence,kind:'player_message',sourceId:'player-a',recipientId:'agent-a',scope:'direct',text:`Held question ${sequence}`,goalRevision:0,observedAtEpochMs:sequence}});
 try{
  say(1);await eventually(()=>planner.requests.length===1);say(2);await eventually(()=>steers===1);finishFirst({toolCalls:1});await clock.advance(0);assert.equal(planner.requests.length,1);
  await clock.advance(100);await eventually(()=>planner.requests.length===2);assert.equal(planner.requests.length,2);assert.match(planner.requests[1].input,/Held question 2/);
  rejectSteer(Object.assign(new Error('obsolete steer'),{code:'TURN_NOT_ACTIVE'}));await clock.advance(0);await clock.advance(100);await eventually(()=>planner.requests.length===3);assert.equal(planner.requests.length,3);
 }finally{finishFirst({toolCalls:1});rejectSteer(new Error('cleanup'));await run.coordinator.stop();}
});

for(const outcome of ['empty','transient'])test(`review repeated DEAD ${outcome} retries stay bounded and stop`,async()=>{
 const clock=new NativeLifecycleClock(),registry=new AgentRegistry(),planner=new FakePlanner(registry);
 planner.requestNativeTurn=async request=>{planner.requests.push(request);if(outcome==='transient')throw Object.assign(new Error('temporary loss'),{code:'ECONNRESET'});return{toolCalls:0};};
 const run=await start({registry,planner,...clock.dependencies(),epochNow:()=>clock.now,initialRegistry:[{...record(),state:DynamicAgentState.DEAD,currentGoal:'Survive.',goalRevision:4,death:DEATH}],config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 try{
  await eventually(()=>planner.requests.length===1);await clock.advance(0);
  for(let attempt=1;attempt<=4;attempt++){await clock.advance(1999);assert.equal(planner.requests.length,attempt);await clock.advance(1);assert.equal(planner.requests.length,attempt+1);}
  run.bridge.emit('goal_control',{agentId:'agent-a',payload:{operation:'stop',goalRevision:5}});await eventually(()=>registry.get('agent-a').goalRevision===5);await clock.advance(900001);
  assert.equal(planner.requests.length,5);assert.equal(registry.get('agent-a').state,DynamicAgentState.PAUSED);
 }finally{await run.coordinator.stop();}
});

test('pending native steers use fresh facts and commit merged conversation reservations once', async () => {
	const registry = new AgentRegistry();
	const planner = new FakePlanner(registry);
	let finishTurn;
	let finishSteer;
	const turnGate = new Promise((resolve) => { finishTurn = resolve; });
	const steerGate = new Promise((resolve) => { finishSteer = resolve; });
	planner.steerRequests = [];
	planner.deliveredSteers = [];
	planner.requestNativeTurn = async (request) => {
		planner.requests.push(request);
		if (planner.requests.length === 1) await turnGate;
		return { toolCalls: 1 };
	};
	planner.steerNativeTurn = async (request) => {
		planner.steerRequests.push(request);
		request.onInterrupt?.();
		await steerGate;
		planner.deliveredSteers.push(typeof request.input === 'function' ? await request.input() : request.input);
		return { turnId: 'turn-1' };
	};
	const run = await start({ registry, planner, initialRegistry: [{ ...record(), state: DynamicAgentState.IDLE }],
		config: { bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'native_tools' } } });
	let conversationEvents = 0;
	run.coordinator.on('conversationEvent', () => { conversationEvents += 1; });
	const say = (sequence) => run.bridge.emit('conversation_event', { agentId: 'agent-a', payload: {
		sequence, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: `Question ${sequence}`, goalRevision: 0, observedAtEpochMs: sequence,
	} });
	const payloadOf = (input) => {
		const payload = input.slice(input.indexOf('\n') + 1);
		const retry = payload.indexOf('\nYour previous turn');
		return JSON.parse(retry < 0 ? payload : payload.slice(0, retry));
	};
	const deliveredConversation = (input) => payloadOf(input).conversation.entries;
	try {
		say(1);
		await eventually(() => planner.requests.length === 1);
		say(2);
		await eventually(() => planner.steerRequests.length === 1 && conversationEvents === 2);
		run.bridge.emit('observation', { agentId: 'agent-a', payload: {
			goalRevision: 0, eventSequence: 20, attention: false, observation: { player: { health: 7 } },
		} });
		say(3);
		await eventually(() => conversationEvents === 3);
		finishSteer();
		await eventually(() => planner.deliveredSteers.length === 1);
		const steerInput = planner.deliveredSteers[0];
		assert.equal(payloadOf(steerInput).observation.player.health, 7);
		assert.deepEqual(deliveredConversation(steerInput).map(({ sequence, text }) => [sequence, text]), [
			[2, 'Question 2'], [3, 'Question 3'],
		]);
		finishTurn();
		await eventually(() => planner.requests.length === 2);
		const retryMessages = deliveredConversation(planner.requests[1].input);
		assert.deepEqual(retryMessages, [], 'committed steer messages do not return in the following native turn');
	} finally {
		finishSteer();
		finishTurn();
		await run.coordinator.stop();
	}
});
