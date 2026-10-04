import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { start, FakeBridge, FakePlanner, FakeProvider, AgentRegistry, record, DEATH, eventually, withCompletionContract, createDynamicCoordinator } from './fixtures/runtime-coordinator-fixture.mjs';
import { ActiveGoalSupervisor } from '../src/active-goal-supervisor.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const flush = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };
async function directory(t) {
 const path = await mkdtemp(join(tmpdir(), 'runtime-lifecycle-'));
 t.after(() => rm(path, {recursive:true, force:true}));
 return path;
}
async function deadRegistration(t,revised,mode){
 const registry=new AgentRegistry(),bridge=new FakeBridge(),planner=new FakePlanner(registry),errors=[];
 planner.requestPlan=async request=>{planner.requests.push(request);return withCompletionContract({summary:'Respawn.',directive:'replace',source:'program.onUnhandledAttention("continue_and_notify"); await player.respawn();'},request.goalRevision);};
 planner.requestNativeTurn=async request=>{planner.requests.push(request);await request.executeTool({agentId:request.agentId,goalRevision:request.goalRevision,turnId:`death-${request.goalRevision}`,callId:'respawn',tool:{kind:'start_action',actionType:'respawn',arguments:{}}});return {toolCalls:1};};
 const dead={...record(),state:'DEAD',currentGoal:'Survive.',goalRevision:4,death:DEATH,respawnPolicy:{resumeAfterRespawn:true}};
 const coordinator=createDynamicCoordinator({bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:mode}},{bridge,registry,planner,codexService:new FakeProvider(),memoryDirectory:await directory(t),setStatusInterval:()=>null,clearStatusInterval:()=>{}});
 coordinator.on('runtimeError',e=>errors.push({code:e.code,message:e.message}));await coordinator.start();
 try{
  bridge.emit('ready',{connectionEpoch:1,serverInstanceId:'dead-server',registry:[dead]});await eventually(()=>bridge.sent.some(m=>m.payload?.actionType==='respawn'));
  const command=bridge.sent.find(m=>m.payload?.actionType==='respawn');
  if(revised){bridge.emit('action_result',{agentId:'agent-a',payload:{goalRevision:4,actionId:command.payload.actionId,actionType:'respawn',state:'CANCELLED',reasonCode:'ACTION_CANCELLED',eventSequence:5,observedAtEpochMs:3}});await flush();}
  const next={...registry.get('agent-a'),goalRevision:revised?5:4,currentGoal:revised?'Survive and go home.':'Survive.',updatedAtEpochMs:3};
  const wire={schemaVersion:1,agentId:next.agentId,provider:next.provider,model:next.model,reasoningEffort:next.reasoningEffort,serviceTier:next.serviceTier,gameMode:'survival',skinVariant:'variant-0',state:'DEAD',currentGoal:next.currentGoal,goalRevision:next.goalRevision,queue:[],queueGoalSpecs:[],death:DEATH,createdAtEpochMs:1,updatedAtEpochMs:3};const payload=validateProtocolV2Payload('agent_registered',wire);
  bridge.emit('agent_registered',{agentId:'agent-a',connectionEpoch:1,payload});await eventually(()=>bridge.sent.some(m=>m.type==='agent_ready'&&m.payload.goalRevision===next.goalRevision&&m.payload.reconciled===false));if(revised)await eventually(()=>bridge.sent.filter(m=>m.payload?.actionType==='respawn').length===2);await sleep(150);await flush();
  const before={state:registry.get('agent-a').state,revision:registry.get('agent-a').goalRevision,plannerRevisions:planner.requests.map(r=>r.goalRevision),respawnRevisions:bridge.sent.filter(m=>m.payload?.actionType==='respawn').map(m=>m.payload.goalRevision),interruptions:[...planner.interruptions],errors:[...errors]};
  assert.deepEqual(before.plannerRevisions,revised?[4,5]:[4]);assert.deepEqual(before.respawnRevisions,revised?[4,5]:[4]);assert.equal(before.state,'DEAD');assert.deepEqual(before.errors,[]);
  bridge.emit('disconnected',{connectionEpoch:1});await flush();bridge.emit('ready',{connectionEpoch:2,serverInstanceId:'dead-server',registry:[next]});await eventually(()=>bridge.sent.filter(m=>m.payload?.actionType==='respawn').length===(revised?3:2));
  const after={plannerRevisions:planner.requests.map(r=>r.goalRevision),respawnRevisions:bridge.sent.filter(m=>m.payload?.actionType==='respawn').map(m=>m.payload.goalRevision)};assert.deepEqual(after.plannerRevisions,revised?[4,5,5]:[4,4]);assert.equal(registry.get('agent-a').state,'DEAD');assert.equal(registry.get('agent-a').respawnPolicy.resumeAfterRespawn,true);
  return {revised,before,reconnectControl:after};
 }finally{await coordinator.stop();}
}

for (const mode of ['arena_script','native_tools']) for (const revised of [false,true]) test(`${mode} DEAD registration ${revised?'retires the cancelled revision and schedules replacement respawn':'same-revision control leaves pending respawn alone'}`, async t => { await deadRegistration(t,revised,mode); });
