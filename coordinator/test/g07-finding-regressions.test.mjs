import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { runLatencyRunnerCli } from '../src/benchmark/latency-runner-cli.mjs';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, writeFile, readFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { EventEmitter } from 'node:events';
import path from 'node:path';
import os from 'node:os';
import { performance } from 'node:perf_hooks';
import { setImmediate as realSetImmediate } from 'node:timers';
import { createLiveProviderFactory } from '../src/benchmark/live-provider-factories.mjs';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { NATIVE_REALTIME_WORKLOAD, runNativeRealtimeTrial, summarizeComparison } from '../src/benchmark/native-realtime-comparison.mjs';
import assert from 'node:assert/strict';
import test from 'node:test';
import { runLatencyMatrix } from '../src/benchmark/latency-runner.mjs';
import { runTask9SimulatorMatrix } from '../src/benchmark/task9-harness.mjs';
import { compareInstrumentationRuns } from '../src/benchmark/instrumentation-comparison.mjs';
import { compileScenarioDecision } from '../src/benchmark/scenario-program.mjs';

const profile = { provider: 'instant', model: 'fixture', reasoningEffort: 'fixed', serviceTier: 'local' };
const matrix = (extra = {}) => ({ version: 1, fixedSeeds: [1], agentLoads: [1,4,8,16], trials: [{ id: 'fixture', mode: 'instant', scenarioId: 'block-placement', seed: 1, agentLoad: 1, repetitions: 2, providerProfile: profile, turnBudgetMs: 100, trialBudgetMs: 500, turnCap: 8, providerAvailabilityRequired: false, ...extra }] });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const tick = () => new Promise(r => setImmediate(r));

test('late provider acquisition stays owned, aborts, and prevents another trial while unsettled', async () => {
  const gate = deferred(); let stops = 0, acquisitions = 0, signal;
  const result = await runLatencyMatrix({ matrix: matrix({ trialBudgetMs: 30 }), measurements: false,
    providerFactories: { instant: async (_, context) => { acquisitions++; signal = context.signal; await gate.promise; return { ...profile, stop: async () => { stops++; } }; } },
  });
  try {
    assert.equal(result.cleanup.ok, false);
    assert.equal(result.trials.length, 1);
    assert.equal(acquisitions, 1);
    assert.equal(signal.aborted, true);
    assert.equal(result.executionStopped.code, 'CLEANUP_INCOMPLETE');
  } finally { gate.resolve(); await tick(); await tick(); }
  assert.equal(stops, 1);
});

for (const mode of ['pending', 'rejected', 'completed']) test(`provider shutdown ${mode} is accounted for before the next trial`, async () => {
  const gate = deferred(); let stops = 0, acquisitions = 0;
  const result = await runLatencyMatrix({ matrix: matrix(), measurements: false,
    providerFactories: { instant: () => { acquisitions++; return { ...profile, available: true,
      start: async () => { throw Object.assign(new Error('controlled startup failure'), { code: 'STARTUP_FAILURE' }); },
      stop: async () => { stops++; if (mode === 'pending') await gate.promise; if (mode === 'rejected') throw new Error('controlled stop failure'); },
    }; } },
  });
  try {
    assert.equal(result.cleanup.ok, mode === 'completed');
    assert.equal(acquisitions, mode === 'completed' ? 2 : 1);
    assert.equal(result.trials[0].error.code, 'STARTUP_FAILURE');
    assert.equal(result.trials[0].cleanup.providerStop, mode);
  } finally { gate.resolve(); await tick(); }
  assert.equal(stops, acquisitions);
});

const instrumentationRun = () => ({ trials: Array.from({length:6}, (_, i) => ({trialId:'fixture', repetition:i+1, status:'PASSED', cleanup:{ok:true}, durationMs:100, debug:{actionCommandHash:'actions', scenarioDigest:'facts'}})) });
test('instrumentation certification requires timing for every successful matched pair', () => {
  for (const arm of ['enabled','disabled']) for (const value of [undefined,null,NaN,Infinity,-1,'100']) {
    const input={enabled:instrumentationRun(),disabled:instrumentationRun()}; input[arm].trials[5].durationMs=value;
    assert.equal(compareInstrumentationRuns(input).status,'FAILED', `${arm}/${value}`);
  }
  const input={enabled:instrumentationRun(),disabled:instrumentationRun()};
  assert.equal(compareInstrumentationRuns(input).status,'PASSED');
  input.disabled.trials[5].durationMs=0;
  assert.equal(compareInstrumentationRuns(input).status,'FAILED');
  input.disabled.trials[5].durationMs=100; input.enabled.trials[5].durationMs=0;
  assert.equal(compareInstrumentationRuns(input).status,'PASSED');
  input.enabled.trials[5].durationMs=200;
  assert.equal(compareInstrumentationRuns(input).status,'FAILED');
});

test('Task 9 fixed descriptor controls the actual scheduler and rejects unsupported adaptive descriptors', async () => {
  const m = { ...matrix({ agentLoad:4, scenarioId:'stone-tool-gathering', mode:'replay', providerProfile:{...profile,provider:'replay'}, repetitions:1, turnBudgetMs:1000, trialBudgetMs:5000 }), schemaVersion:3 };
  const result = await runTask9SimulatorMatrix({ matrix:m, virtualTickPacing:{tickMs:1}, scheduler:{mode:'fixed',fixedConcurrency:1},
    providerFactories:{ replay: (profile, context) => ({...profile, synthetic:true, available:true, async stop(){}, async createAgent(record){return { async setGoalRevision(){}, async decide(){return compileScenarioDecision(context.loadScenario.agentManifests[record.agentId]);} };} }) },
  });
  assert.equal(result.status,'PASSED');
  const admissions=result.trials[0].phaseEvents.filter(e=>e.stage==='scheduler_admitted');
  assert.ok(admissions.length>0);
  assert.ok(admissions.every(e=>e.fields.maxConcurrent===1));
  await assert.rejects(runTask9SimulatorMatrix({matrix:m,scheduler:{mode:'adaptive'}}), /adaptive.*scheduler|scheduler.*adaptive/i);
});

// Exercises the actual services and workspace manager with offline transports.
test('default preflight IDs cross both production workspace boundaries', async () => {
const ownedRoot = path.join(os.tmpdir(), 'g07-no-io-workspace');
const profiles = {
  codex: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' },
};

class OfflineTransport extends EventEmitter {
  calls = [];
  started = false;
  constructor(provider) { super(); this.provider = provider; }
  async start() { this.started = true; this.calls.push('$start'); }
  async stop() { this.started = false; this.calls.push('$stop'); }
  notify(method) { this.calls.push(method); }
  async request(method) {
    this.calls.push(method);
    if (method === 'initialize') return this.provider === 'codex' ? { userAgent: 'f004-offline' } : { protocolVersion: 1 };
    if (method === 'model/list') return { data: [{ id: profiles.codex.model, model: profiles.codex.model, supportedReasoningEfforts: [{ reasoningEffort: 'high' }], serviceTiers: [{ id: 'priority' }] }], nextCursor: null };
    if (method === 'thread/start') return { thread: { id: 'f004-offline-thread' } };
    throw new Error(`Unexpected offline method: ${method}`);
  }
}

function fixture(provider) {
  const transport = new OfflineTransport(provider);
  const mkdirCalls = [];
  const discoveryCalls = [];
  let transportCreations = 0;
  const profile = profiles[provider];
  const options = {
    cwd: ownedRoot,
    workspaceRoot: ownedRoot,
    environment: {},
    preflightTimeoutMs: 2000,
    workspaceDependencies: { mkdir: async (target, opts) => {
      const relative = path.relative(ownedRoot, target);
      assert.ok(relative && !relative.startsWith('..') && !path.isAbsolute(relative));
      mkdirCalls.push({ relative, recursive: opts.recursive });
    } },
    config: { codex: { launchProfile: { ...profile } } },
    codexTransport: transport,
    execFile: () => { throw new Error('Process execution is forbidden in this fixture'); },
  };
  return { options, transport, mkdirCalls, discoveryCalls, get transportCreations() { return transportCreations; } };
}


for (const provider of Object.keys(profiles)) {
  const f=fixture(provider);
  const result=await createLiveProviderFactory(provider,f.options)(profiles[provider]);
  try {
    assert.equal(result.available,true, result.reason);
    assert.equal(f.mkdirCalls.length,1);
    assert.equal(result.getAgent(result.preflight.agentId),null);
  } finally { await result.stop?.(); }
  assert.equal(f.transport.started,false);
  const invalid=fixture(provider);
  const rejected=await createLiveProviderFactory(provider,invalid.options)({...profiles[provider],agentId:'../invalid'});
  assert.equal(rejected.available,false);
  assert.equal(invalid.mkdirCalls.length,0);
  assert.equal(invalid.transport.started,false);
}
});

// Deterministic clock adapted from the independently verified interval probe.
test('useful preparation overlap excludes the idle tail and preserves absent-event controls', async () => {
async function measure(name, overrides = {}) {
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const originalNowDescriptor = Object.getOwnPropertyDescriptor(performance, 'now');
  let clock = 1000;
  let sequence = 0;
  const timers = new Map();
  const events = [];
  const preparation = [];
  const body = [];
  const config = { ...NATIVE_REALTIME_WORKLOAD, ...overrides };
  globalThis.setTimeout = (callback, delay, ...args) => {
    const stack = new Error().stack;
    const kind = stack.includes('at beginPreparation') ? 'preparation'
      : stack.includes('at sleep') ? 'body' : 'executor';
    const row = { id: ++sequence, kind, delay, startedAt: clock, finishedAt: null };
    if (kind === 'preparation') preparation.push(row);
    if (kind === 'body') body.push(row);
    const handle = { id: row.id, unref() { return this; } };
    timers.set(handle, { dueAt: clock + Number(delay), handle, row, callback, args });
    events.push({ event: 'schedule', ...row });
    return handle;
  };
  globalThis.clearTimeout = (handle) => { timers.delete(handle); };
  Object.defineProperty(performance, 'now', { configurable: true, value: () => clock });
  let trial;
  let settled = false;
  let caught;
  try {
    const pending = runNativeRealtimeTrial(NativeProgramExecutor, {
      arm: name === 'no-event-control' ? 'baseline' : 'optimized', repetition: 0,
      actionDurationMs: 220, config, pairingKey: 'f060-pair', order: 1,
    }).then(value => { trial = value; settled = true; }, error => { caught = error; settled = true; });
    for (let step = 0; step < 40 && !settled; step++) {
      await new Promise(resolve => realSetImmediate(resolve));
      if (settled) break;
      const timer = [...timers.values()].sort((a, b) => a.dueAt - b.dueAt || a.row.id - b.row.id)[0];
      assert.ok(timer, 'real code left no timer and no settled trial');
      timers.delete(timer.handle);
      clock = timer.dueAt;
      timer.row.finishedAt = clock;
      events.push({ event: 'fire', id: timer.row.id, kind: timer.row.kind, at: clock });
      timer.callback(...timer.args);
    }
    assert.ok(settled, 'bounded scheduler did not finish');
    await pending;
    if (caught) throw caught;
    assert.equal(trial.status, 'passed');
    assert.equal(trial.observed.bodyActionCount, 2);
    assert.equal(trial.observed.controllerFollowed, true);
    assert.equal(trial.observed.cancellationCount, 0);
    assert.equal(preparation.length, 1);
    assert.equal(body.length, 2);
    assert.equal(timers.size, 0, 'executor must clear deadline/planning timers');
    const prep = preparation[0];
    const predecessor = body[0];
    const overlap = Math.max(0, Math.min(prep.finishedAt, predecessor.finishedAt) - Math.max(prep.startedAt, predecessor.startedAt));
    const measuredPreparationMs = prep.finishedAt - prep.startedAt;
    const row = { name, controllerDecisionPrepMs: config.controllerDecisionPrepMs,
      preparation: prep, predecessor, actualPreparationOverlapMs: overlap,
      measuredPreparationMs, overstatementMs: trial.metrics.usefulOverlapMs === null ? null : trial.metrics.usefulOverlapMs - overlap,
      trial, events, remainingTimers: timers.size };
    return row;
  } finally {
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
    if (originalNowDescriptor) Object.defineProperty(performance, 'now', originalNowDescriptor);
    else delete performance.now;
  }
}


const rows=[];
for (const [name, overrides] of [['default',{}],['short-preparation',{controllerDecisionPrepMs:7}],['long-preparation',{controllerDecisionPrepMs:180}],['no-event-control',{planningLeadMs:undefined}]]) rows.push(await measure(name,overrides));
assert.deepEqual(rows.map(row=>row.trial.metrics.usefulOverlapMs),[100,7,120,null]);
assert.deepEqual(rows.map(row=>row.overstatementMs),[0,0,0,null]);
assert.equal(rows[2].trial.metrics.decisionGapMs,60);
assert.equal(rows[3].trial.metrics.decisionGapMs,100);
const summary=summarizeComparison([rows[3].trial,{...rows[0].trial,order:2}]);
assert.equal(summary.byArm.optimized.metrics.usefulOverlapMs.p50,100);
assert.equal(summary.pairedDeltaOptimizedMinusBaselineMs.usefulOverlapMs.count,0);
});

// Hidden child processes use only generated offline service modules. Artifacts are
// retained when G07_TEST_ARTIFACTS is supplied for an implementation audit.
const repository = fileURLToPath(new URL('../../', import.meta.url));
async function artifactRoot(label) {
  const base = process.env.G07_TEST_ARTIFACTS ?? os.tmpdir();
  await mkdir(base,{recursive:true});
  return mkdtemp(path.join(base,`g07-${label}-`));
}
function invoke(script, args, root) {
  const child=spawnSync(process.execPath,[path.join(repository,'coordinator/src',script),...args],{cwd:repository,windowsHide:true,encoding:'utf8',timeout:15000,maxBuffer:8*1024*1024,env:{...process.env,TEMP:root,TMP:root,TMPDIR:root}});
  assert.equal(child.error,undefined,child.stderr);
  assert.equal(child.signal,null);
  return {...child,report:child.stdout.trim() ? JSON.parse(child.stdout.trim().split(/\r?\n/).at(-1)) : null};
}

test('actual Task 9 CLI exits nonzero for returned failure and timeout, zero for factual success',async()=>{
  const root=await artifactRoot('task9-cli');
  for (const [name,scenarioId,budget,status] of [['missing','g07-nonexistent',5000,'FAILED'],['timeout','block-placement',0.000001,'TIMED_OUT'],['valid','block-placement',5000,'PASSED']]) {
    const input={...matrix({id:name,scenarioId,repetitions:1,trialBudgetMs:budget}),schemaVersion:3};
    const source=path.join(root,`${name}.json`); await writeFile(source,JSON.stringify(input));
    const output=path.join(root,name);
    const child=invoke('benchmark/task9-harness-cli.mjs',['--matrix',source,'--artifact-directory',output],root);
    await writeFile(path.join(root,`${name}-invocation.json`),JSON.stringify(child));
    assert.equal(child.status,status==='PASSED'?0:1,child.stderr);
    assert.equal(child.report.trials[0].status,status);
    const persisted=JSON.parse(await readFile(path.join(output,'trial-summary.json'),'utf8'));
    assert.equal(persisted.status,child.report.status);
    if(status==='PASSED') assert.equal(child.report.trials[0].correctness.factualSuccess,true);
  }
  const invalid=invoke('benchmark/task9-harness-cli.mjs',['--unknown','value'],root);
  assert.equal(invalid.status,1);
});

test('native A/B accepts required work only and excludes invalid arms from paired timings',async()=>{
  const root=await artifactRoot('native-ab');
  for(const mode of ['valid','zero','wrong','extra','reversed','missing-mine','wrong-args','real-zero']){
    const source=mode==='real-zero' ? `
      import {EventEmitter} from 'node:events';
      import {CodexService as ProductionService} from ${JSON.stringify(pathToFileURL(path.join(repository,'coordinator/src/codex-service.mjs')).href)};
      class OfflineTransport extends EventEmitter {
        sequence=0;
        async start(){} async stop(){} notify(){}
        async request(method){
          if(method==='initialize'||method==='turn/interrupt')return {};
          if(method==='model/list')return {data:[{id:'gpt-5.6-luna',supportedReasoningEfforts:[{reasoningEffort:'xhigh'}],serviceTiers:[{id:'fast'}]}],nextCursor:null};
          if(method==='thread/start')return {thread:{id:'offline-thread'}};
          if(method==='turn/start'){const id='turn-'+(++this.sequence);setImmediate(()=>this.emit('notification',{method:'turn/completed',params:{threadId:'offline-thread',turn:{id,status:'completed'}}}));return {turn:{id}};}
          throw Error('Unexpected offline method '+method);
        }
      }
      export class CodexService extends ProductionService {constructor(config,deps){super(config,{...deps,transport:new OfflineTransport()});}}
    ` : `export class CodexService {
      async start(){} async stop(){}
      async createAgent(){return {async setGoalRevision(){},async act(input,context){
        const mode=${JSON.stringify(mode)};
        let actions=input.includes('mine the known')?['navigate_to','break_block']:input.includes('craft_inventory')?['craft_inventory']:['chat'];
        if(mode==='zero')actions=[];
        if(mode==='wrong')actions=['wait'];
        if(mode==='extra')actions.push('chat');
        if(mode==='reversed')actions.reverse();
        if(mode==='missing-mine')actions=actions.filter(action=>action!=='break_block');
        for(const actionType of actions){
          const args=actionType==='chat'?{message:'hello'}:actionType==='craft_inventory'?{recipeId:'minecraft:oak_planks',count:4,timeoutMs:15000}:{x:2,y:64,z:1};
          if(actionType==='break_block')args.expectedBlockId='minecraft:stone';
          if(mode==='wrong-args'){args.count=1;args.x=99;args.message='';}
          await context.executeTool({tool:{kind:'action',actionType,arguments:args}});
        }
        return {status:'completed'};
      } }; }
    }`;
    const file=path.join(root,`${mode}.mjs`);await writeFile(file,source);
    const child=invoke('native-tool-ab-trial.mjs',[file,'control','1'],root);
    await writeFile(path.join(root,`${mode}-result.json`),JSON.stringify(child));
    assert.equal(child.status,mode==='valid'?0:1,mode);
    assert.equal(child.report.status,mode==='valid'?'PASSED':'FAILED',mode);
    if(mode==='valid') assert.deepEqual(['coldDm','warmDm','moveMine','craft'].map(p=>child.report[p].calls.length),[1,1,2,1]);
  }
  for(const [label,left,right,passed] of [['valid','valid','valid',4],['mixed','valid','real-zero',2],['empty','zero','zero',0]]){
    const output=path.join(root,`${label}-report.json`);
    const child=invoke('native-tool-ab-runner.mjs',[path.join(root,`${left}.mjs`),path.join(root,`${right}.mjs`),output,'2'],root);
    assert.equal(child.status,passed===4?0:1,child.stderr);
    const report=JSON.parse(await readFile(output,'utf8'));
    assert.equal(report.passed,passed);
    assert.equal(report.pairedCurrentMinusBaseline.coldDmFirstMs.count,passed===4?2:0);
    if(passed!==4)assert.equal(report.pairedCurrentMinusBaseline.coldDmFirstMs.p50,null);
  }
});

test('default live factory cancellation reclaims preflight and reports completed cleanup',async()=>{
  let creates=0,stops=0,aborted=false;
  const liveProfile={provider:'codex',model:'offline',reasoningEffort:'high',serviceTier:'priority'};
  const result=await runLatencyMatrix({matrix:matrix({mode:'live',providerProfile:liveProfile,repetitions:1,trialBudgetMs:30}),measurements:false,
    liveProviderOptions:{environment:{},service:{provider:'codex',async start(){},async stop(){stops++;},async createAgent(_, {signal}){creates++;return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>{aborted=true;reject(signal.reason);},{once:true}));}}},
  });
  await tick();
  assert.equal(creates,1);assert.equal(aborted,true);assert.equal(stops,1);
  assert.equal(result.trials[0].status,'TIMED_OUT');
  // A snapshot may remain conservative if factory retirement is still settling.
  if(result.cleanup.ok)assert.equal(result.trials[0].cleanup.providerAcquisition,'resolved');
});

test('default live adapter retains pending stop after its gameplay session deadline',async()=>{
  const createGate=deferred(),stopGate=deferred();let creates=0,stops=0,factories=0;
  const liveProfile={provider:'codex',model:'offline',reasoningEffort:'high',serviceTier:'priority'};
  const service={provider:'codex',async start(){},async createAgent(){if(++creates===1)return {};await createGate.promise;return {async decide(){throw Error('must not turn');}};},async removeAgent(){},async stop(){stops++;await stopGate.promise;}};
  try {
    // The deadline must fall after the second createAgent is pending; setup on a loaded runner can take well over 60 ms.
    const result=await runLatencyMatrix({matrix:matrix({mode:'live',providerProfile:liveProfile,trialBudgetMs:3000}),measurements:false,liveProviderOptions:{environment:{},serviceFactory:()=>{factories++;return service;}}});
    assert.equal(creates,2);assert.equal(stops,1);assert.equal(factories,1);
    assert.equal(result.trials[0].status,'TIMED_OUT');assert.equal(result.cleanup.ok,false);
    assert.equal(result.trials[0].cleanup.providerStop,'pending');assert.equal(result.executionStopped.unexecutedTrials,1);
  }finally{createGate.resolve();stopGate.resolve();await tick();await tick();}
});

test('legacy latency CLI preserves pending acquisition and halted-matrix evidence',async()=>{
  const root=await artifactRoot('latency-cli'), gate=deferred();
  const input=path.join(root,'matrix.json');await writeFile(input,JSON.stringify(matrix({trialBudgetMs:30})));
  const output=[];let stops=0;
  try{
    const exit=await runLatencyRunnerCli(['--matrix',input,'--artifact-directory',path.join(root,'artifacts')],{
      stdout:text=>output.push(text),stderr:()=>{},
      runLatencyMatrix: options=>runLatencyMatrix({...options,measurements:false,providerFactories:{instant:async()=>{await gate.promise;return {...profile,async stop(){stops++;}};}}}),
    });
    const report=JSON.parse(output.join(''));
    assert.equal(exit,1);assert.equal(report.cleanup.ok,false);
    assert.equal(report.trials[0].cleanup.providerAcquisition,'pending');
    assert.equal(report.executionStopped.unexecutedTrials,1);
  }finally{gate.resolve();await tick();await tick();}
  assert.equal(stops,1);
});

test('Task 9 accepts explicit adaptive execution and rejects a mismatched custom scheduler',async()=>{
  const m={...matrix({agentLoad:4,scenarioId:'stone-tool-gathering',mode:'replay',providerProfile:{...profile,provider:'replay'},repetitions:1,turnBudgetMs:1000,trialBudgetMs:5000}),schemaVersion:3};
  const common={matrix:m,virtualTickPacing:{tickMs:1},providerFactories:{replay:(profile,context)=>({...profile,available:true,synthetic:true,async stop(){},async createAgent(record){return {async setGoalRevision(){},async decide(){return compileScenarioDecision(context.loadScenario.agentManifests[record.agentId]);}};}})}};
  const adaptive=await runTask9SimulatorMatrix({...common,scheduler:{mode:'adaptive',controller:{fixture:true}},planningSchedulerFactory:({recorder})=>new PlanningScheduler({maxConcurrent:4,maxPending:0,planningMode:'adaptive',urgentReserve:0,benchmarkRecorder:recorder})});
  assert.equal(adaptive.status,'PASSED');assert.equal(adaptive.trials[0].scheduler.mode,'adaptive');
  await assert.rejects(runTask9SimulatorMatrix({...common,scheduler:{mode:'fixed',fixedConcurrency:1},planningSchedulerFactory:({recorder})=>new PlanningScheduler({maxConcurrent:4,maxPending:0,benchmarkRecorder:recorder})}),/does not match executed scheduler/);
  await assert.rejects(runTask9SimulatorMatrix({...common,scheduler:{mode:'fixed',fixedConcurrency:1},planningConcurrency:4}),/conflicts/);
});
