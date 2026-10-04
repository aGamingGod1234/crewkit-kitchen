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
class ProgramSupervisor extends ActiveGoalSupervisor {
 leases=[];expirations=[];
 constructor(options,delayed){super(options);this.delayed=delayed;}
 begin(key,kind,options){const adjusted=kind==='program'&&this.delayed?{...options,timeoutMs:options.timeoutMs+1000}:options;const token=super.begin(key,kind,adjusted);this.leases.push({kind,options:adjusted,token});return token;}
}
async function deadline(t,delayed,ack){
 const registry=new AgentRegistry(),bridge=new FakeBridge(),planner=new FakePlanner(registry),provider=new FakeProvider(),errors=[],traces=[];
 let run,request,handle;
 const supervisor=new ProgramSupervisor({requestObservation:key=>run.coordinator.requestSupervisedObservation(key),onExpire:e=>{supervisor.expirations.push(e);run.coordinator.handleLeaseExpired(e);}},delayed);
 planner.requestNativeTurn=async r=>{planner.requests.push(r);if(!request){request=r;handle=await r.executeTool({agentId:r.agentId,goalRevision:r.goalRevision,turnId:'deadline',callId:'run',tool:{kind:'run_program',background:true,timeoutMs:200,source:'program.onUnhandledAttention("continue_and_notify"); await player.wait(10000);'}});}return new Promise(()=>{});};
 const send=bridge.send.bind(bridge);bridge.send=async(type,id,payload,options)=>{if(type==='action_command'||type==='action_cancel')validateProtocolV2Payload(type,payload);await send(type,id,payload,options);if(type==='action_cancel'&&ack)queueMicrotask(()=>bridge.emit('action_result',{agentId:id,payload:{goalRevision:payload.goalRevision,actionId:payload.actionId,actionType:'wait',state:'CANCELLED',reasonCode:'ACTION_CANCELLED',eventSequence:10,observedAtEpochMs:10}}));};
 run=await start({registry,bridge,planner,codexService:provider,goalSupervisor:supervisor,memoryDirectory:await directory(t),setStatusInterval:()=>null,clearStatusInterval:()=>{},traceWriter:{write:(event,data)=>traces.push({event,...data})},config:{bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}}});
 run.coordinator.on('runtimeError',e=>errors.push({code:e.code,message:e.message}));
 try{
  bridge.emit('goal_control',{agentId:'agent-a',payload:{operation:'start',goalRevision:1,goal:'Wait.'}});
  bridge.emit('observation',{agentId:'agent-a',payload:{goalRevision:1,eventSequence:1,observation:{player:{x:0,y:64,z:0,health:20},inventory:{items:[]}}}});
  await eventually(()=>handle&&bridge.sent.some(m=>m.type==='action_command'));await eventually(()=>bridge.sent.some(m=>m.type==='action_cancel'));await sleep(70);await flush();
  const tool=async(callId,tool)=>request.executeTool({agentId:'agent-a',goalRevision:1,turnId:'deadline',callId,tool});
  const status=await tool('status',{kind:'program_status',programId:handle.programId});
  let replacement,error=null;try{replacement=await tool('replace',{kind:'start_action',actionType:'wait',arguments:{durationMs:1}});}catch(e){error=e.code;}
  const result={delayed,ack,status,replacement,error,commands:bridge.sent.filter(m=>m.type==='action_command'),cancels:bridge.sent.filter(m=>m.type==='action_cancel'),expirations:supervisor.expirations,programLease:supervisor.leases.filter(l=>l.kind==='program'),errors,traces:traces.filter(t=>['work_lease_expired','native_program_completed','native_program_ended'].includes(t.event))};
  if(ack){assert.equal(status.state,'TIMED_OUT');assert.equal(status.reasonCode,'PROGRAM_DEADLINE');assert.equal(status.receipts.length,1);assert.equal(status.receipts[0].state,'CANCELLED');assert.equal(error,null);}else{assert.equal(status.action.state,'CANCELLING');assert.equal(error,'NATIVE_PROGRAM_IN_PROGRESS');assert.equal(result.commands.length,1);}
  return result;
 }finally{await run.coordinator.stop();}
}

for (const delayed of [false,true]) for (const ack of [false,true]) test(`production program deadline (${delayed?'delayed outer-lease control':'normal outer lease'}, ${ack?'ACK':'no ACK'})`, async t => { await deadline(t,delayed,ack); });
