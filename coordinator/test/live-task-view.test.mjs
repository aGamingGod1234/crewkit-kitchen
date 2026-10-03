import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { LiveTaskViews, validateTaskPlan } from '../src/live-task-view.mjs';
import { normalizeMinecraftToolCall } from '../src/native-minecraft-tools.mjs';
import { NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { goalSpecFingerprint, parseGoalSpec } from '../src/goal-spec.mjs';

const record={agentId:'agent-a',currentGoal:'Defeat the dragon',goalRevision:1,provider:'codex',model:'gpt-6.1-sol',reasoningEffort:'medium'};
const evidence={itemIds:[],count:1,dimension:null,x:null,y:null,z:null,blockId:null};
function plan() { return {steps:[
 {id:'iron',label:'Carry iron pickaxe',kind:'inventory',status:'active',dependsOn:[],detail:'A present possession',evidence:{...evidence,itemIds:['minecraft:iron_pickaxe']}},
 {id:'portal',label:'Established portal',kind:'world',status:'pending',dependsOn:['iron'],detail:'Known structure',evidence:{...evidence,dimension:'minecraft:overworld',x:3,y:64,z:5,blockId:'minecraft:nether_portal'}},
 {id:'nether',label:'Entered the Nether',kind:'milestone',status:'complete',dependsOn:['portal'],detail:'Past milestone, reported by agent',evidence:null},
 ]}; }
function observation(items=[{itemId:'minecraft:iron_pickaxe',count:1}],worldId='world-a') {return {ready:true,observedAtEpochMs:1000,player:{dead:false},world:{worldId,dimension:'minecraft:overworld'},inventory:{items},blocks:[{x:3,y:64,z:5,blockId:'minecraft:nether_portal'}]};}
test('plan dependencies reject cycles, unknown references and invented evidence',()=>{
 for(const mutate of [p=>p.steps[0].dependsOn=['nether'],p=>p.steps[0].dependsOn=['missing'],p=>p.steps[0].id='portal',p=>p.steps[0].evidence=null,p=>p.steps[1].evidence.x=null]){let p=plan();mutate(p);assert.throws(()=>validateTaskPlan(p));}
 assert.equal(validateTaskPlan(plan()).steps.length,3);
 assert.throws(()=>normalizeMinecraftToolCall('taskPlan',{operation:'replace'}),{code:'INVALID_MINECRAFT_TOOL_ARGUMENTS'});
});
test('death invalidates a possession while keeping known infrastructure and milestones',async()=>{
 const views=new LiveTaskViews();await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});
 assert.deepEqual(views.snapshot(record).plan.steps.map(s=>s.status),['complete','complete','complete']);
 await views.observe(record,{ready:false,player:{dead:true}});
 assert.deepEqual(views.snapshot(record).plan.steps.map(s=>s.status),['lost','complete','complete']);
 await views.observe(record,observation([]));assert.equal(views.snapshot(record).plan.steps[0].status,'lost');
 await views.observe(record,observation());assert.equal(views.snapshot(record).plan.steps[0].status,'complete');
 const changed=observation();changed.blocks[0].blockId='minecraft:air';await views.observe(record,changed);assert.equal(views.snapshot(record).plan.steps[1].status,'lost');
});
test('unseen terrain cannot become green merely because the agent asserted completion',async()=>{
 const views=new LiveTaskViews();const o=observation([]);o.blocks=[];await views.observe(record,o);
 const p=plan();p.steps[0].status='complete';p.steps[1].status='complete';await views.operate(record,{operation:'replace',plan:p});
 assert.deepEqual(views.snapshot(record).plan.steps.map(s=>s.status),['pending','pending','complete']);
});
test('revising away from a portal preserves its verified history and permits reordered work',async()=>{
 const views=new LiveTaskViews();await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});
 const far=observation([]);far.blocks=[];await views.observe(record,far);
 const p=(await views.operate(record,{operation:'read'})).plan;p.steps[1].dependsOn=[];await views.operate(record,{operation:'replace',plan:p});
 assert.equal(views.snapshot(record).plan.steps[1].status,'complete');
});
test('plans persist across coordinator restart and stay scoped to world and task',async t=>{
 const directory=await mkdtemp(path.join(os.tmpdir(),'arena-plan-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 let views=new LiveTaskViews({directory});await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});await views.flush();
 views=new LiveTaskViews({directory});const far=observation([]);far.blocks=[];await views.observe({...record,goalRevision:4},far);
 assert.deepEqual(views.snapshot({...record,goalRevision:4}).plan.steps.map(s=>s.status),['lost','complete','complete']);
 await views.flush();
 assert.equal(views.snapshot({...record,currentGoal:'Get food',goalRevision:5}).plan,null);
 views=new LiveTaskViews({directory});await views.observe(record,observation([],'other-world'));assert.equal(views.snapshot(record).plan,null);await views.flush();
});
test('usage updates are cumulative snapshots, not double-counted billable totals',()=>{
 const views=new LiveTaskViews();for(let i=0;i<3;i++)views.event(record,'live_usage',JSON.stringify({inputTokens:200,cachedInputTokens:150,outputTokens:20,totalTokens:220}));
 assert.equal(views.snapshot(record).usage.totalTokens,220);
 views.event(record,'live_allowance',JSON.stringify({secondary:{usedPercent:89,windowDurationMins:10080}}));assert.equal(views.snapshot(record).allowance.secondary.usedPercent,89);
 views.event(record,'live_tool','Authorization: Bearer example-secret');assert.doesNotMatch(JSON.stringify(views.snapshot(record)),/example-secret/);
 validateProtocolV2Payload('task_view',views.snapshot(record));
 assert.deepEqual(validateProtocolV2Payload('task_view_request',{goalRevision:1}),{goalRevision:1});
});
test('the native taskPlan entry point updates advice without sending any body or provider request',async()=>{
 const views=new LiveTaskViews();await views.observe(record,observation());const sent=[];
 const runtime=new NativeToolRuntime({bridge:{send:(...args)=>sent.push(args)},taskPlan:(r,tool)=>views.operate(r,tool)});
 const request={agentId:record.agentId,goalRevision:1,turnId:'turn-a',callId:'call-a',tool:normalizeMinecraftToolCall('taskPlan',{operation:'replace',plan:plan()})};
 const result=await runtime.execute(request,record);assert.equal(result.plan.steps.length,3);assert.deepEqual(sent,[]);
 await assert.rejects(runtime.execute({...request,goalRevision:0},record),{code:'STALE_NATIVE_TOOL'});
});
test('Luna advice is pending until observations or the main agent supply progress',async()=>{
 const views=new LiveTaskViews();views.suggest(record.agentId,record.currentGoal,plan());await views.observe(record,observation([]));
 assert.deepEqual(views.snapshot(record).plan.steps.map(s=>s.status),['pending','complete','pending']);
});

test('usage separates cached-inclusive totals, exact latest model segment and measured rate deltas',()=>{
 let now=1000;const views=new LiveTaskViews({now:()=>now});
 const update=value=>views.event(record,'live_usage',JSON.stringify({threadId:'private-thread',...value}));
 update({inputTokens:1000,cachedInputTokens:800,outputTokens:20,totalTokens:1020,last:{inputTokens:400,cachedInputTokens:350,outputTokens:10,totalTokens:410}});
 let usage=views.snapshot(record).usage;
 assert.equal(usage.inputTokens,1000);assert.equal(usage.uncachedInputTokens,200);assert.equal(usage.lastInputTokens,400);assert.equal(usage.lastUncachedInputTokens,50);
 assert.equal(usage.observedElapsedMs,undefined);assert.equal(usage.inputTokensPerMinute,undefined,'one snapshot cannot establish a rate');
 now+=60000;
 update({inputTokens:512811,cachedInputTokens:507296,outputTokens:1665,totalTokens:514476,last:{inputTokens:172000,cachedInputTokens:170800,outputTokens:200,totalTokens:172200}});
 usage=views.snapshot(record).usage;
 assert.equal(usage.inputTokens,512811,'replace cumulative total; never add samples');assert.equal(usage.uncachedInputTokens,5515);
 assert.equal(usage.lastInputTokens,172000);assert.equal(usage.lastCachedInputTokens,170800);assert.equal(usage.lastUncachedInputTokens,1200);assert.equal(usage.lastOutputTokens,200);
 assert.equal(usage.observedElapsedMs,60000);assert.equal(usage.inputTokensPerMinute,511811);assert.equal(usage.cachedInputTokensPerMinute,506496);assert.equal(usage.uncachedInputTokensPerMinute,5315);assert.equal(usage.outputTokensPerMinute,1645);
 assert.equal(usage.intervalInputTokens,511811);assert.equal(usage.intervalUncachedInputTokens,5315);
 for(const value of Object.values(usage))assert.ok(Number.isSafeInteger(value)&&value>=0);
 assert.doesNotMatch(JSON.stringify(views.snapshot(record)),/private-thread/);
 validateProtocolV2Payload('task_view',views.snapshot(record));
});

test('repeated totals and unavailable latest-segment fields do not invent new model calls or zero rates',()=>{
 let now=1000;const views=new LiveTaskViews({now:()=>now});
 const update=value=>views.event(record,'live_usage',JSON.stringify({threadId:'same-thread',...value}));
 update({inputTokens:100,cachedInputTokens:70,outputTokens:10});
 update({inputTokens:200,cachedInputTokens:160,outputTokens:20});
 assert.equal(views.snapshot(record).usage.observedElapsedMs,undefined,'same timestamp supplies no elapsed interval');
 now+=30000;update({inputTokens:300,cachedInputTokens:230,outputTokens:30});
 const measured=views.snapshot(record).usage;
 assert.equal(measured.inputTokensPerMinute,200);assert.equal(measured.uncachedInputTokensPerMinute,60);
 assert.equal(measured.lastInputTokens,undefined,'a cumulative delta is not necessarily one model segment');
 assert.equal(measured.intervalInputTokens,100);
 now+=10000;update({inputTokens:300,cachedInputTokens:230,outputTokens:30});
 assert.deepEqual(views.snapshot(record).usage,measured,'duplicate telemetry retains the last actual measured interval');
 now+=20000;update({inputTokens:400,cachedInputTokens:300,outputTokens:40});
 assert.equal(views.snapshot(record).usage.observedElapsedMs,30000,'duplicate notification does not move the sampling baseline');
});

test('provider timestamps and counter/session resets never mix unrelated usage intervals',()=>{
 const views=new LiveTaskViews({now:()=>900000});
 const update=value=>views.event(record,'live_usage',JSON.stringify(value));
 update({threadId:'one',reportedAtEpochMs:1000,inputTokens:100,cachedInputTokens:80,outputTokens:10});
 update({threadId:'one',reportedAtEpochMs:61000,inputTokens:300,cachedInputTokens:250,outputTokens:30});
 assert.equal(views.snapshot(record).usage.inputTokensPerMinute,200,'reported valid timestamp sets measured interval');
 update({threadId:'two',reportedAtEpochMs:121000,inputTokens:500,cachedInputTokens:400,outputTokens:50});
 assert.equal(views.snapshot(record).usage.inputTokensPerMinute,undefined,'new thread requires its own baseline');
 update({threadId:'two',reportedAtEpochMs:181000,inputTokens:600,cachedInputTokens:480,outputTokens:60});
 assert.equal(views.snapshot(record).usage.inputTokensPerMinute,100);
 update({threadId:'two',reportedAtEpochMs:241000,inputTokens:10,cachedInputTokens:8,outputTokens:1});
 assert.equal(views.snapshot(record).usage.inputTokensPerMinute,undefined,'a decreased cumulative counter resets the rate');
 update({threadId:'two',reportedAtEpochMs:240000,inputTokens:20,cachedInputTokens:16,outputTokens:2});
 assert.equal(views.snapshot(record).usage.inputTokensPerMinute,undefined,'a reversed clock resets the rate');
});

test('each new goal scope starts a fresh usage baseline even when text or provider thread is reused',async()=>{
 let now=0;const views=new LiveTaskViews({now:()=>now});
 const update=(current,inputTokens)=>{now+=60000;views.event(current,'live_usage',JSON.stringify({threadId:'reused',inputTokens,cachedInputTokens:inputTokens-10,outputTokens:10}));};
 update(record,100);update(record,200);assert.equal(views.snapshot(record).usage.inputTokensPerMinute,100);
 const second={...record,currentGoal:'Get iron',goalRevision:2};
 assert.equal(views.snapshot(second).usage,null);update(second,300);assert.equal(views.snapshot(second).usage.inputTokensPerMinute,undefined);
 const third={...second,goalRevision:3};
 assert.equal(views.snapshot(third).usage,null);update(third,400);assert.equal(views.snapshot(third).usage.inputTokensPerMinute,undefined);
 update(third,500);assert.equal(views.snapshot(third).usage.inputTokensPerMinute,100);
 await views.observe(third,observation());await views.observe(third,observation([],'different-world'));
 assert.equal(views.snapshot(third).usage,null,'world replacement clears the old interval');
 update(third,600);assert.equal(views.snapshot(third).usage.inputTokensPerMinute,undefined);
 views.begin(third,{fresh:true});assert.equal(views.snapshot(third).usage,null);update(third,700);assert.equal(views.snapshot(third).usage.inputTokensPerMinute,undefined);
});

test('malformed usage fields cannot display negative, fractional or unsafe token facts',()=>{
 let now=1000;const views=new LiveTaskViews({now:()=>now});
 views.event(record,'live_usage',JSON.stringify({inputTokens:100,cachedInputTokens:200,outputTokens:-1,totalTokens:1.5,last:{inputTokens:Number.MAX_SAFE_INTEGER+1,cachedInputTokens:5}}));
 const usage=views.snapshot(record).usage;
 assert.equal(usage.uncachedInputTokens,undefined);assert.equal(usage.outputTokens,undefined);assert.equal(usage.totalTokens,undefined);assert.equal(usage.lastInputTokens,undefined);
 for(const value of Object.values(usage))assert.ok(Number.isSafeInteger(value)&&value>=0);
 validateProtocolV2Payload('task_view',views.snapshot(record));
});
test('explicit new identical tasks clear old plan and terminal while pause, steer and restart retain them',async t=>{
 const directory=await mkdtemp(path.join(os.tmpdir(),'arena-plan-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 const views=new LiveTaskViews({directory});await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});
 views.event(record,'live_tool','Previous task output');views.verified(record);await views.flush();
 const resumed={...record,goalRevision:3};views.begin(resumed);
 assert.equal(views.snapshot(resumed).plan.steps[2].status,'complete');assert.equal(views.snapshot(resumed).events[0].message,'Previous task output');
 const fresh={...record,goalRevision:4};views.begin(fresh,{fresh:true});
 assert.equal(views.snapshot(fresh).plan,null);assert.deepEqual(views.snapshot(fresh).events,[]);assert.equal(views.snapshot(fresh).verified,false);
 await views.flush();const earlyRestart=new LiveTaskViews({directory});await earlyRestart.observe(fresh,observation());assert.equal(earlyRestart.snapshot(fresh).plan,null,'restart before the first new observation must not revive the old plan');
 await views.observe(fresh,observation());assert.equal(views.snapshot(fresh).plan,null);await views.flush();
 const restored=new LiveTaskViews({directory});await restored.observe(fresh,observation());assert.equal(restored.snapshot(fresh).plan,null,'restart must not revive the overwritten old task plan');
 await restored.operate(fresh,{operation:'replace',plan:plan()});await restored.flush();
 const restarted=new LiveTaskViews({directory});await restarted.observe({...fresh,goalRevision:5},observation());assert.equal(restarted.snapshot({...fresh,goalRevision:5}).plan.steps[2].status,'complete');await restarted.flush();
});
test('observation dates persist even when the known structure stays complete',async t=>{
 const directory=await mkdtemp(path.join(os.tmpdir(),'arena-plan-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 const views=new LiveTaskViews({directory});await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});
 const newer=observation();newer.observedAtEpochMs=5000;await views.observe(record,newer);await views.flush();
 const restarted=new LiveTaskViews({directory});const far=observation();far.blocks=[];await restarted.observe(record,far);assert.equal(restarted.snapshot(record).lastObserved.portal,5000);await restarted.flush();
});

test('steering retains the immutable task plan and persisted scope despite changed planner text',async t=>{
 const directory=await mkdtemp(path.join(os.tmpdir(),'arena-plan-steer-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 const fields={originalRequest:record.currentGoal,predicate:{type:'operator_confirmed'},createdAtTick:1};
 const goalSpec=parseGoalSpec({...fields,fingerprint:goalSpecFingerprint(fields)});
 const original={...record,currentGoalSpec:goalSpec};
 const views=new LiveTaskViews({directory});await views.observe(original,observation());await views.operate(original,{operation:'replace',plan:plan()});
 views.event(original,'live_tool','Existing route and work');await views.flush();
 const steered={...original,goalRevision:2,currentGoal:record.currentGoal+'\nLatest steering: use the existing portal.'};views.begin(steered,{fresh:false});
 assert.equal(views.snapshot(steered).goal,record.currentGoal);
 assert.deepEqual(views.snapshot(steered).plan.steps.map(s=>s.status),['complete','complete','complete']);
 assert.equal(views.snapshot(steered).events[0].message,'Existing route and work');
 const far=observation([]);far.blocks=[];await views.observe(steered,far);await views.flush();
 const restarted=new LiveTaskViews({directory});await restarted.observe(steered,far);
 assert.deepEqual(restarted.snapshot(steered).plan.steps.map(s=>s.status),['lost','complete','complete']);await restarted.flush();
 const replacementFields={...fields,createdAtTick:2};
 const replacement={...steered,goalRevision:3,currentGoalSpec:parseGoalSpec({...replacementFields,fingerprint:goalSpecFingerprint(replacementFields)})};
 assert.equal(views.snapshot(replacement).plan,null,'a distinct immutable goal never inherits the in-memory plan');
});

test('explicit steering preserves a legacy task while a fresh replacement still resets it',async()=>{
 const views=new LiveTaskViews();await views.observe(record,observation());await views.operate(record,{operation:'replace',plan:plan()});
 views.event(record,'live_tool','Existing legacy task');
 const steered={...record,goalRevision:2,currentGoal:record.currentGoal+'\nLatest steering: take a safer route.'};views.begin(steered,{fresh:false});
 assert.equal(views.snapshot(steered).plan.steps[1].status,'complete');
 assert.equal(views.snapshot(steered).events[0].message,'Existing legacy task');
 views.begin({...steered,goalRevision:3},{fresh:true});assert.equal(views.snapshot({...steered,goalRevision:3}).plan,null);
});
