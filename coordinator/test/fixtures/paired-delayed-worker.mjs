import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { createInterface } from 'node:readline';
import { phaseWorker } from '../../src/benchmark/paired-cli.mjs';
import { pairedSystemNow, runnerPhaseChannel, readPhaseMessage, writePhaseMessage } from '../../src/benchmark/paired-runner-channel.mjs';
import { normalizeHeadlessScenario, runHeadlessScenario } from '../../src/headless-matrix.mjs';

const output=path.dirname(fileURLToPath(import.meta.url));
const epoch=()=>performance.timeOrigin+performance.now();
const json=async file=>JSON.parse(await readFile(file,'utf8'));
const profile={provider:'codex',model:'offline-fixture',reasoningEffort:'high',serviceTier:'priority'};
const seed='1';
if(process.argv[2]==='--inert-child'){
  const [directory,delayText,zeroText]=process.argv.slice(3);const delay=Number(delayText);
  const channel=runnerPhaseChannel(directory);const commands=[];const audit=[];let started=false;
  const emit=(kind,value)=>process.stdout.write(`PAIR_EVENT ${JSON.stringify({kind,value})}\n`);
  const scenario=normalizeHeadlessScenario({id:'natural-probe',...profile,task:'Obtain oak logs',timeoutMs:300,scenarioTimeoutMs:300,world:{mode:'natural',seed},requireFactualSuccess:true,assert:[{type:'lifecycle',state:'COMPLETED'},{type:'rcon',command:'data get entity {agent} Inventory',match:'minecraft:oak_log'}]});
  const worldManifest={version:1,scenarioId:scenario.id,worldId:'headless-probe',fresh:true,world:scenario.world,savedSpawn:{source:'level.dat',dimension:'minecraft:overworld',x:0,y:64,z:0},spawnLoading:{operation:'temporary_spawn_chunk_loading',x:0,z:0,ready:true,elapsedMs:1,terrainModified:false,inventoryModified:false}};
  const scenarioPromise=(async()=>{
    const trialDeadlineMs=await channel.ready();
    await writeFile(path.join(directory,'runner-deadline.json'),JSON.stringify({trialDeadlineMs,deadlineEpochMs:performance.timeOrigin+trialDeadlineMs,receivedEpochMs:epoch()}));
    return runHeadlessScenario({scenario,worldManifest,protocolAudit:audit,runDirectory:directory,now:()=>performance.now(),trialDeadlineMs,
      onCleanup:event=>channel.cleanup(event),fileSize:async()=>0,readFile:async()=>{throw Object.assign(new Error('absent fixture'),{code:'ENOENT'});},writeFile:async()=>{},
      rcon:{close:async()=>{},command:async command=>{
        commands.push({command,dispatchedSystemMs:pairedSystemNow()});
        if(command==='seed')return {text:'Seed: [1]'};
        if(command==='difficulty')return {text:'The difficulty is normal'};
        if(command.includes(' if loaded '))return {text:'The time is 1'};
        if(command.includes('summon-configured')){const name=command.split(' ').at(-1);audit.push({envelope:{type:'agent_registered',agentId:'offline-agent',payload:{agentId:'offline-agent',name,...profile}}});return {text:`Created ${name}. It is ready for a task.`};}
        if(command.startsWith('codex start '))started=true;
        if(command.startsWith('codex status '))return {text:'state=COMPLETED'};
        if(command.startsWith('codex stop '))return {text:`Stopped ${command.split(' ').at(-1)}.`};
        if(command.endsWith(' Pos'))return {text:'player has the following entity data: [0.5d, 64.0d, 0.5d]'};
        if(command.endsWith(' Inventory'))return {text:`player has the following entity data: ${started?'[{id:"minecraft:oak_log"}]':'[]'}`};
        return {text:'ok'};
      }}});
  })();
  for await(const line of createInterface({input:process.stdin})){
    const request=JSON.parse(line);
    if(request.phase==='startup'){await readPhaseMessage(directory,'runner-ready');emit('startup',{ready:true});}
    if(request.phase==='trial'){
      await writePhaseMessage(directory,'runner-trial',{clock:request.clock,cutoffMs:zeroText==='zero'?pairedSystemNow():request.cutoffMs});
      await writeFile(path.join(directory,'published.json'),JSON.stringify({request,publishedEpochMs:epoch(),delayMs:delay}));
      // Controlled event-loop pause after publication, before ready() can consume it.
      if(delay>0)Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,delay);
      emit('trial',await readPhaseMessage(directory,'runner-trial-ended'));
    }
    if(request.phase==='cleanup'){
      await writePhaseMessage(directory,'runner-cleanup',{clock:request.clock,cutoffMs:request.cutoffMs});
      const report=await scenarioPromise;await writeFile(path.join(directory,'commands.json'),JSON.stringify(commands,null,2));
      await writeFile(path.join(directory,'runner-report.json'),JSON.stringify(report,null,2));emit('cleanup',report);process.exit(0);
    }
  }
}
