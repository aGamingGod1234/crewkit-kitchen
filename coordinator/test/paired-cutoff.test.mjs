import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { phaseWorker } from '../src/benchmark/paired-cli.mjs';
import { pairedSystemNow, runnerPhaseChannel, writePhaseMessage } from '../src/benchmark/paired-runner-channel.mjs';
const json=async file=>JSON.parse(await readFile(file,'utf8'));
test('actual scenario dispatch rejects delayed trial and cleanup cutoffs without renewal', async t=>{
 const output=await mkdtemp(path.join(tmpdir(),'paired-cutoff-'));
 t.after(()=>rm(output,{recursive:true,force:true}));

  const results=[];
  for(const {mode,delay,zero} of [{mode:'no-delay-control',delay:0},{mode:'delayed-consumption',delay:450},{mode:'zero-remaining-control',delay:0,zero:true}]){
    const directory=await mkdtemp(path.join(output,`f12-${mode}-`));
    const events=[];const worker=phaseWorker(process.execPath,[fileURLToPath(new URL('./fixtures/paired-delayed-worker.mjs',import.meta.url)),'--inert-child',directory,String(delay),zero?'zero':'normal'],{cwd:output,onEvent:event=>events.push(event)});
    let trial,report,deadlineMs,cleanupDeadlineMs,exited=false;
    try{
      await worker.phase('startup',{now:()=>performance.now(),deadlineMs:performance.now()+5000});
      deadlineMs=performance.now()+300;cleanupDeadlineMs=deadlineMs+2000;
      await writeFile(path.join(directory,'parent-deadline.json'),JSON.stringify({deadlineMs,deadlineEpochMs:performance.timeOrigin+deadlineMs,cleanupDeadlineMs}));
      trial=await worker.phase('trial',{now:()=>performance.now(),deadlineMs,cleanupDeadlineMs});
      report=await worker.phase('cleanup',{now:()=>performance.now(),deadlineMs:cleanupDeadlineMs});
    }finally{await worker.terminate();try{process.kill(worker.pid,0);}catch(error){if(error.code!=='ESRCH')throw error;exited=true;}}
    assert.equal(exited,true);
    const commands=await json(path.join(directory,'commands.json'));const starts=commands.filter(row=>row.command.startsWith('codex start '));
    const published = await json(path.join(directory,'published.json'));
    const startOffsetMs=starts.length?starts[0].dispatchedSystemMs-published.request.cutoffMs:null;
    if(mode==='no-delay-control'){assert.equal(starts.length,1);assert.ok(startOffsetMs<0);assert.equal(report.status,'PASSED');assert.notEqual(trial.deadlineReached,true);}
    if(mode==='delayed-consumption'){assert.equal(trial.deadlineReached,true);assert.equal(starts.length,0);assert.equal(commands.some(row=>row.command.includes('summon-configured')),false);assert.equal(report.classification,'TIMEOUT');}
    if(mode==='zero-remaining-control'){assert.equal(starts.length,0);assert.equal(report.classification,'TIMEOUT');}
    results.push({mode,delayMs:delay,trial,reportStatus:report.status,classification:report.classification,startOffsetFromParentCutoffMs:startOffsetMs,runnerDeadline:await json(path.join(directory,'runner-deadline.json')),published,events,exited,directory:path.relative(output,directory)});
  }
  // The same fixed cutoff expires in transit for cleanup as well as trial.
  const cleanupDirectory=await mkdtemp(path.join(output,'f12-cleanup-channel-'));
  const publishedAtMs=performance.now();await writePhaseMessage(cleanupDirectory,'runner-cleanup',{clock:'system-monotonic-ms',cutoffMs:pairedSystemNow()+100});
  await new Promise(resolve=>setTimeout(resolve,180));
  const receivedAtMs=performance.now();const renewedDeadline=await runnerPhaseChannel(cleanupDirectory).cleanup({synthetic:true});
  assert.ok(receivedAtMs>publishedAtMs+100);assert.ok(renewedDeadline<receivedAtMs);
  const cleanup={kind:'channel-only',publishedAtMs,originalCutoffMs:publishedAtMs+100,receivedAtMs,renewedDeadline};
  await writeFile(path.join(output,'f12-results.json'),JSON.stringify({node:process.version,results,cleanup},null,2));


});

test('phase channel fails closed on old relative messages and invalid shared cutoffs', async t => {
 const directory=await mkdtemp(path.join(tmpdir(),'paired-invalid-cutoff-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 for (const request of [{remainingMs:1000}, {clock:'performance.now',cutoffMs:1000}, {clock:'system-monotonic-ms',cutoffMs:-1}, {clock:'system-monotonic-ms',cutoffMs:null}]) {
  await writePhaseMessage(directory,'runner-trial',request);
  await writePhaseMessage(directory,'runner-cleanup',request);
  const channel=runnerPhaseChannel(directory);
  await assert.rejects(channel.ready(), /Invalid shared phase cutoff/);
  await assert.rejects(channel.cleanup({}), /Invalid shared phase cutoff/);
 }
});
