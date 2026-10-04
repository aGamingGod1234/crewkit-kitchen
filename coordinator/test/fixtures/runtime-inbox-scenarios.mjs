import assert from 'node:assert/strict';
import { fixture, gate, event, record, until, flush } from './runtime-inbox-fixture.mjs';
const summary = calls => calls.map(c=>({kind:c.kind,goalRevision:c.goalRevision,accepted:c.accepted,baseSequence:c.conversation.baseSequence,nextSequence:c.conversation.nextSequence,omittedEntries:c.conversation.omittedEntries,sequences:c.conversation.entries.map(e=>e.sequence)}));
async function burst(count,rejectSteer=false,long=true,onFixture=()=>{}) {
  const turn=gate(), steer=gate();
  const f=await fixture({start:async(c,calls)=>{if(calls.filter(x=>x.kind==='start').length===1)await turn.promise;},steer:async(c,calls)=>{if(calls.filter(x=>x.kind==='steer').length===1)await steer.promise;}});
  try {
    onFixture(f);
    await f.bridge.deliver('conversation_event',event(1,long)); await until(()=>f.calls.length===1,'first turn');
    await f.bridge.deliver('conversation_event',event(2,long)); await until(()=>f.calls.length===2,'blocked steering');
    for(let seq=3;seq<=count;seq++) await f.bridge.deliver('conversation_event',event(seq,long));
    assert.equal(f.calls.length,2,'burst is ingested while native steering is unresolved');
    if(rejectSteer)steer.reject(Object.assign(new Error('offline rejection before native acceptance'),{code:'TURN_NOT_ACTIVE'}));else steer.resolve();
    await f.waitForTrace(traces=>traces.some(t=>t.event===(rejectSteer?'native_turn_steer_deferred':'native_turn_steered')));
    turn.resolve();
    // Provider acceptance precedes durable commit. A followup sent during the
    // prior visible-reply correction is steering, not a fresh two-turn exchange.
    await f.waitForTrace(traces=>{
      const starts=f.calls.filter(c=>c.kind==='start');
      return starts.length>=2 && starts.at(-1).accepted && starts.at(-1).conversation.entries.length===0
        && f.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries).length>=count
        && traces.filter(t=>t.event==='native_turn_completed').length===starts.length;
    });
    const attempted=f.calls.flatMap(c=>c.conversation.entries.map(e=>e.sequence));
    const accepted=f.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries.map(e=>e.sequence));
    const missed=Array.from({length:count},(_,i)=>i+1).filter(s=>!accepted.includes(s));
    assert.equal(f.errors.length,0);
    assert.deepEqual(missed,[],'every admitted message reaches successful native delivery');
    assert.deepEqual(accepted,Array.from({length:count},(_,i)=>i+1),'successful prefixes preserve order without duplicates');
    for (const call of f.calls.filter(c=>c.accepted)) for (const entry of call.conversation.entries) assert.deepEqual(entry,event(entry.sequence,long));
    const out={name:`burst-${count}-${long?'bytes':'entries'}-${rejectSteer?'rejected':'accepted'}-steer`,ingested:count,providerCalls:summary(f.calls),missingAccepted:missed,instruction3EverAttempted:attempted.includes(3),coordinatorErrors:f.errors,wireErrors:f.bridge.sent.filter(m=>m.type==='agent_error'),traces:f.traces.filter(t=>['native_turn_steered','native_turn_steer_deferred','native_turn_completed'].includes(t.event))};
    if(count>32){
      const before=f.calls.length,completed=f.traces.filter(t=>t.event==='native_turn_completed').length;
      await f.bridge.deliver('conversation_event',event(count+1,long));
      await f.waitForTrace(traces=>traces.filter(t=>t.event==='native_turn_completed').length>=completed+2);
      const followup=f.calls.slice(before);
      assert.deepEqual(followup.flatMap(call=>call.conversation.entries.map(e=>e.sequence)),[count+1]);
      assert.equal(followup.length,2,'delivery plus one correction because this fixture never sends chat');
      assert.ok(followup.every(call=>call.kind==='start' && call.accepted),'followup starts after the prior correction committed');
      out.followup=summary(followup);
    }
    return out;
  } finally {steer.resolve();turn.resolve();await f.coordinator.stop();}
}
async function readHistory() {
  const f=await fixture();
  try { for(let s=1;s<=60;s++){await f.bridge.deliver('conversation_event',event(s));await flush();}
    const delivered=f.calls.flatMap(c=>c.conversation.entries.map(e=>e.sequence));assert.deepEqual(delivered,Array.from({length:60},(_,i)=>i+1));
    return {name:'already-read-history-eviction',messages:60,providerCalls:f.calls.length,exactlyOnceAtSuccessfulBoundary:true};
  } finally {await f.coordinator.stop();}
}
async function lifecycle(kind,count) {
  const turn=gate(),steer=gate();
  const f=await fixture({start:async(c,calls)=>{if(calls.filter(x=>x.kind==='start').length===1)await turn.promise;},steer:async(c,calls)=>{if(calls.filter(x=>x.kind==='steer').length===1)await steer.promise;}});
  try {
    await f.bridge.deliver('conversation_event',event(1));await until(()=>f.calls.length===1,'lifecycle turn');
    await f.bridge.deliver('conversation_event',event(2));await until(()=>f.calls.length===2,'lifecycle steer');
    for(let s=3;s<=count;s++)await f.bridge.deliver('conversation_event',event(s));
    let revision=0,epoch=1; const previous=f.calls.length;
    if(kind==='goal-change') {
      await f.bridge.deliver('goal_control',{operation:'start',goalRevision:1,updatedAtEpochMs:100,goal:'Build a fence.'});revision=1;
    } else {
      f.bridge.emit('disconnected',{connectionEpoch:1});await flush();
      const readyCount=f.bridge.sent.filter(m=>m.type==='agent_ready').length;
      f.bridge.emit('ready',{serverInstanceId:kind==='new-server'?'r17-new-server':'r17-server',connectionEpoch:2,registry:[record()]});epoch=2;
      await until(()=>f.bridge.sent.filter(m=>m.type==='agent_ready').length>readyCount,'reconnected');
    }
    // Settle obsolete callbacks before triggering current work. They cannot recreate the lost body.
    steer.resolve();turn.resolve();await flush();
    await f.bridge.deliver('conversation_event',event(kind==='new-server'?1:count+1,true,revision),epoch);await until(()=>f.calls.slice(previous).some(c=>c.accepted && c.conversation.entries.some(e=>e.sequence===(kind==='new-server'?1:count+1))),'lifecycle tail');await flush();
    const current=f.calls.slice(previous),sequences=current.flatMap(c=>c.conversation.entries.map(e=>e.sequence));
    assert.ok(current.length>0);
    if(kind==='new-server')assert.deepEqual(sequences,[1]);else assert.equal(sequences.includes(3),true);
    assert.deepEqual(f.errors,[]);
    return {name:`${kind}-${count}`,currentCalls:summary(current),originalInstruction3Carried:sequences.includes(3),entryGoalRevisions:current.flatMap(c=>c.conversation.entries.map(e=>e.goalRevision)),wireErrors:f.bridge.sent.filter(m=>m.type==='agent_error')};
  } finally {turn.resolve();steer.resolve();await f.coordinator.stop();}
}
async function wakeAck() {
  const turn=gate(),f=await fixture({start:()=>turn.promise});
  const payload={transactionId:'00000000-0000-0000-0000-000000000017',event:event(1),control:{operation:'start',goalRevision:1,updatedAtEpochMs:2,goal:'Respond to the player.'}};
  try {
    await f.bridge.deliver('conversation_wake',payload);await until(()=>f.calls.length===1,'wake native input');
    assert.equal(f.calls[0].accepted,false);
    assert.equal(f.bridge.sent.filter(m=>m.type==='conversation_wake_ack').length,1);
    await f.bridge.deliver('conversation_wake',structuredClone(payload));
    assert.equal(f.bridge.sent.filter(m=>m.type==='conversation_wake_ack').length,2);
    assert.equal(f.calls.length,1);
    return {name:'wake-ack-is-ingestion-not-model-acceptance',acks:2,nativeCalls:1,nativeAcceptedAtAck:false,revision:f.registry.get('agent-a').goalRevision};
  } finally {turn.resolve();await flush();await f.coordinator.stop();}
}
async function durableCoordinatorReload() {
  const {mkdtemp,rm}=await import('node:fs/promises');
  const {tmpdir}=await import('node:os'); const {join}=await import('node:path');
  const directory=await mkdtemp(join(tmpdir(),'inbox-coordinator-reload-'));
  const turn=gate(),steer=gate(); let first,second;
  try {
    first=await fixture({memoryDirectory:directory,start:()=>turn.promise,steer:()=>steer.promise});
    await first.bridge.deliver('conversation_event',event(1)); await until(()=>first.calls.length===1,'durable first start');
    await first.bridge.deliver('conversation_event',event(2)); await until(()=>first.calls.length===2,'durable held steer');
    for(let n=3;n<=60;n++) await first.bridge.deliver('conversation_event',event(n));
    await first.coordinator.stop();
    second=await fixture({memoryDirectory:directory});
    await second.bridge.deliver('conversation_event',event(61));
    await until(()=>second.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries).length===61,'reloaded durable backlog');
    assert.deepEqual(second.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries),Array.from({length:61},(_,i)=>event(i+1)));
    turn.resolve();steer.resolve();await flush();
    return {name:'coordinator-reload-preserves-60-pending',passed:true};
  } finally {turn.resolve();steer.resolve();await first?.coordinator.stop();await second?.coordinator.stop();await rm(directory,{recursive:true,force:true});}
}
async function removedUnopenedInbox() {
  const {mkdtemp,rm}=await import('node:fs/promises');
  const {tmpdir}=await import('node:os'); const {join}=await import('node:path');
  const directory=await mkdtemp(join(tmpdir(),'inbox-removed-unopened-'));
  const turn=gate(); let first,second;
  try {
    first=await fixture({memoryDirectory:directory,start:()=>turn.promise});
    await first.bridge.deliver('conversation_event',event(1)); await until(()=>first.calls.length===1,'old pending turn');
    await first.coordinator.stop();
    second=await fixture({memoryDirectory:directory,record:{...record(),state:'STARTING',goalRevision:1,currentGoal:'Remove this goal.'}});
    await second.bridge.deliver('agent_removed',{goalRevision:0});
    await second.bridge.deliver('agent_registered',{...record(),schemaVersion:1,skinVariant:'classic',createdAtEpochMs:1,updatedAtEpochMs:1});
    turn.resolve(); await flush();
    await second.bridge.deliver('conversation_event',event(2));
    await until(()=>second.calls.some(c=>c.accepted),'replacement turn'); await flush();
    assert.deepEqual(second.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries),[event(2)]);
    assert.deepEqual(second.errors,[]);
  } finally {turn.resolve();await first?.coordinator.stop();await second?.coordinator.stop();await rm(directory,{recursive:true,force:true});}
}
async function missingPendingReceipt(damage) {
  const {mkdtemp,rm,readFile,writeFile,unlink}=await import('node:fs/promises');
  const {createHash}=await import('node:crypto');
  const {tmpdir}=await import('node:os'); const {join}=await import('node:path');
  const directory=await mkdtemp(join(tmpdir(),'inbox-retry-receipt-'));
  const hash=value=>createHash('sha256').update(value).digest('hex');
  const file=key=>join(directory,`pending-${hash('agent-a')}`,`pending-${hash(key)}.json`);
  const turn=gate(); let first,second;
  try {
    first=await fixture({memoryDirectory:directory,start:()=>turn.promise});
    await first.bridge.deliver('conversation_event',event(1));await until(()=>first.calls.length===1,'pending retry turn');
    await first.coordinator.stop();
    const saved=JSON.parse(await readFile(file('index'),'utf8'));
    const receipt=file(`${saved.generation}:sequence:1`);
    if(damage==='missing')await unlink(receipt);else await writeFile(receipt,'null');
    second=await fixture({memoryDirectory:directory,record:{...record(),state:'STARTING',goalRevision:1,currentGoal:'Retry this goal.'}});
    await assert.rejects(second.bridge.deliver('conversation_event',event(1)),{code:'CONVERSATION_STORAGE_FAILED'});
    assert.equal(second.calls.length,0);
    assert.ok(second.bridge.sent.some(m=>m.type==='agent_error'&&m.payload.code==='CONVERSATION_STORAGE_FAILED'));
    assert.deepEqual(JSON.parse(await readFile(file(`${saved.generation}:0`),'utf8')).entry,event(1));
  } finally {turn.resolve();await first?.coordinator.stop();await second?.coordinator.stop();await rm(directory,{recursive:true,force:true});}
}
async function failedAdmission() {
  const {mkdtemp,writeFile,rm}=await import('node:fs/promises');
  const {tmpdir}=await import('node:os'); const {join}=await import('node:path');
  const directory=await mkdtemp(join(tmpdir(),'inbox-admission-fault-'));
  const blocked=join(directory,'blocked'); await writeFile(blocked,'fixture');
  let f;
  try {
    f=await fixture({memoryDirectory:blocked});
    await assert.rejects(f.bridge.deliver('conversation_event',event(1)),{code:'CONVERSATION_STORAGE_FAILED'});
    await assert.rejects(f.bridge.deliver('conversation_wake',{transactionId:'00000000-0000-0000-0000-000000000024',event:event(2),control:{operation:'start',goalRevision:1,updatedAtEpochMs:2,goal:'Respond.'}}),{code:'CONVERSATION_STORAGE_FAILED'});
    assert.equal(f.calls.length,0,'failed admission never starts the model');
    assert.equal(f.bridge.sent.filter(m=>m.type==='conversation_wake_ack').length,0,'failed admission cannot ACK a wake');
    assert.ok(f.bridge.sent.some(m=>m.type==='agent_error' && m.payload.code==='CONVERSATION_STORAGE_FAILED'),'failure is visible without private path data');
    return {name:'failed-admission-no-wake-ack',passed:true};
  } finally {await f?.coordinator.stop().catch(()=>{});await rm(directory,{recursive:true,force:true});}
}
async function rejectedStart() {
  const turn=gate(), steer=gate();
  const f=await fixture({start:async(c,calls)=>{if(calls.filter(x=>x.kind==='start').length===1)await turn.promise;},steer:async(c,calls)=>{if(calls.filter(x=>x.kind==='steer').length===1)await steer.promise;}});
  try {
    await f.bridge.deliver('conversation_event',event(1)); await until(()=>f.calls.length===1,'held start');
    await f.bridge.deliver('conversation_event',event(2)); await until(()=>f.calls.length===2,'held steer');
    for(let s=3;s<=60;s++) await f.bridge.deliver('conversation_event',event(s));
    steer.resolve(); await until(()=>f.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries).length===59,'successful steering tail');
    turn.reject(Object.assign(new Error('synthetic start failure'),{code:'CODEX_RPC_FAILED'})); await flush();
    await f.bridge.deliver('conversation_event',event(61));
    await until(()=>f.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries).length===61,'replacement after start failure');
    assert.deepEqual(f.calls.filter(c=>c.accepted).flatMap(c=>c.conversation.entries.map(e=>e.sequence)).sort((a,b)=>a-b),Array.from({length:61},(_,i)=>i+1));
    return {name:'rejected-start-after-successful-steering',passed:true};
  } finally {turn.resolve();steer.resolve();await f.coordinator.stop();}
}


export { burst, rejectedStart, readHistory, durableCoordinatorReload, failedAdmission, removedUnopenedInbox, missingPendingReceipt, lifecycle, wakeAck };
