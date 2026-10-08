import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { createDynamicCoordinator as createProductionCoordinator } from '../../src/dynamic-main.mjs';
import { validateProtocolV2Payload } from '../../src/protocol-v2.mjs';
import { encodeNativeEventInput, decodeModelFacts } from '../../src/model-fact-encoding.mjs';
async function until(predicate, label) {
  for (let i = 0; i < 1000; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  throw new Error(`Finite probe failed to reach: ${label}`);
}
async function flush() { await new Promise(resolve => setTimeout(resolve, 150)); }
function gate() { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; }
const record = () => ({ agentId:'agent-a', provider:'codex', model:'gpt-6-astra', reasoningEffort:'high', serviceTier:'priority', state:'IDLE', goalRevision:0, queue:[] });
const event = (sequence, long = true, revision = 0) => ({ sequence, kind:'player_message', sourceId:'player-a', recipientId:'agent-a', scope:'direct', text: sequence === 3 ? 'USER INSTRUCTION: preserve the oak tree beside the house.' : `Instruction ${sequence}: ${long ? 'x'.repeat(250) : 'hello'}`, goalRevision:revision, observedAtEpochMs:sequence });
class Bridge extends EventEmitter {
  ready=false; sent=[];
  start() { this.ready=true; }
  stop() { this.ready=false; }
  async send(type, agentId, payload, options={}) { this.sent.push({type,agentId,payload,connectionEpoch:options.connectionEpoch}); }
  async deliver(type, payload, connectionEpoch=1) {
    validateProtocolV2Payload(type, payload);
    const pending=[];
    this.emit(type, {agentId:'agent-a',payload,connectionEpoch,waitUntil: p=>pending.push(p)});
    await Promise.all(pending);
  }
}
class Supervisor {
  activate(){} terminate(){} begin(key,kind){ return {...key,kind}; } end(){} progress(){} observed(){} recover(){} ensure(){} factualProgress(){} suspend(){} close(){}
}
async function fixture(handlers={}) {
  const registry = new AgentRegistry(), bridge = new Bridge(), calls=[], errors=[], traces=[]; let statusTick;
  const traceEvents = new EventEmitter();
  // Use actual completion evidence for disk-backed work. The test runner's
  // existing file deadline bounds a missing event without a guessed sleep.
  function waitForTrace(predicate) {
    if (predicate(traces)) return Promise.resolve();
    return new Promise(resolve => {
      const check = () => {
        if (predicate(traces)) { traceEvents.off('trace', check); resolve(); }
      };
      traceEvents.on('trace', check);
    });
  }
  function capture(kind,request) {
    const input=request.input;
    const raw=JSON.parse(input.split('\n')[1]);
    const encoded=decodeModelFacts(JSON.parse(encodeNativeEventInput(input).split('\n')[1]));
    assert.deepEqual(encoded.conversation,raw.conversation);
    const call={kind,goalRevision:request.goalRevision,conversation:raw.conversation,accepted:false}; calls.push(call); return call;
  }
  async function captureSteer(request) {
    const input=typeof request.input==='function'?await request.input():request.input;
    return capture('steer',{...request,input});
  }
  const planner={
    async requestPlan(){throw new Error('ArenaScript planning is outside this native probe');},
    beginReconcile(records,options){ const result=registry.reconcile(records,options); return {registry:result,complete:Promise.resolve({registry:result,providers:{valid:result.records,invalid:[],catalog:{models:[]}}})}; },
    async requestNativeTurn(request){ const call=capture('start',request); await handlers.start?.(call,calls,request); call.accepted=true; return {toolCalls:handlers.toolCalls ?? 1}; },
    async steerNativeTurn(request){ const call=await captureSteer(request); await handlers.steer?.(call,calls); call.accepted=true; return {}; },
    async interrupt(){}, async remove(id){return registry.remove(id);},
  };
  const provider={catalog:{stale:false,refresh:async()=>({models:[]}),assertSupported(){}},async start(){},async stop(){}};
  const coordinator=createProductionCoordinator({bridge:{port:25570,secret:'s'.repeat(32)},codex:{controlProtocol:'native_tools'}},{bridge,registry,planner,codexService:provider,memoryDirectory:handlers.memoryDirectory ?? null,runtimeHooks:handlers.runtimeHooks,setStatusInterval:cb=>{statusTick=cb;return null;},clearStatusInterval:()=>{},goalSupervisor:handlers.supervisor ?? new Supervisor(),traceWriter:{write:(event,data)=>{traces.push({event,...data});traceEvents.emit('trace');}}});
  coordinator.on('runtimeError', e=>errors.push({code:e.code,message:e.message}));
  await coordinator.start(); bridge.emit('ready',{serverInstanceId:'r17-server',connectionEpoch:1,registry:[handlers.record ?? record()]});
  await until(()=>bridge.sent.some(m=>m.type==='agent_ready'),'ready');
  if (handlers.waitForReconciliation !== false) await until(()=>bridge.sent.some(m=>m.type==='coordinator_status'&&m.payload.reconciled)||errors.length>0,'reconciliation finished');
  return {registry,bridge,coordinator,calls,errors,traces,planner,waitForTrace,statusTick:()=>statusTick()};
}

export {fixture,gate,event,record,until,flush};
