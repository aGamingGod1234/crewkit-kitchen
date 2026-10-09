import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { syncBuiltinESMExports } from 'node:module';
import { mkdtemp, readFile, writeFile, readdir, unlink, rmdir } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { BenchmarkRecorder } from '../src/benchmark/benchmark-recorder.mjs';
import { TraceWriter } from '../src/trace-writer.mjs';
import { ProviderTurnRecorder, recordProviderTurn } from '../src/provider-turn-recorder.mjs';
import { summarize } from '../src/benchmark/live-action-gaps.mjs';

async function fixture(t) {
 const dir = await mkdtemp(path.join(os.tmpdir(), 'arena-g06-'));
 t.after(async () => { for (const name of await readdir(dir)) { const file = path.resolve(dir, name); assert.equal(path.dirname(file), dir); await unlink(file); } await rmdir(dir); });
 return dir;
}

test('benchmark artifacts preserve validated token counts and reject credential and hostile token objects', async t => {
 const recorder = new BenchmarkRecorder();
 const tokens = {input:50, output:15, cached:20, reasoning:0, cacheWrite:null};
 let traps = 0;
 const accessor = Object.defineProperty({input:1}, 'output', {enumerable:true, get(){traps++; throw Error('getter');}});
 const proxy = new Proxy({}, {ownKeys(){traps++; throw Error('proxy');}});
 const row = recorder.record('provider_response_completed', {}, {tokens, accessToken:'fixture-secret', malformed:{tokens:{input:'fixture-secret'}}, extra:{tokens:{input:1,password:'fixture-secret'}}, accessor:{tokens:accessor}, unsafe:{tokens:proxy}});
 assert.deepEqual({...row.tokens}, tokens);
 assert.deepEqual({...row.fields.tokens}, tokens);
 for (const key of ['malformed','extra','accessor','unsafe']) assert.equal(row[key].tokens, '[REDACTED]');
 assert.equal(row.accessToken, '[REDACTED]');
 assert.equal(traps, 0);
 const out = await recorder.writeArtifacts(await fixture(t));
 const text = await readFile(out.eventsPath, 'utf8');
 assert.deepEqual(JSON.parse(text).tokens, tokens);
 assert.ok(!text.includes('fixture-secret'));
});

test('trace startup waits outside operation deadlines and preserves paired order', async () => {
 let release; const ready = new Promise(resolve => {release=resolve;});
 const rows=[], timers=new Set(); let operationTimers=0;
 const writer = new TraceWriter('virtual-public.jsonl', {diagnosticFilePath:'virtual-private.jsonl', mkdir:()=>ready, appendFile:async (file,line)=>rows.push([path.basename(file),JSON.parse(line).index]), schedule(fn,ms){if(ms===250) operationTimers++; const timer=setTimeout(()=>{timers.delete(timer);fn();},ms); timers.add(timer); return timer;},cancel(timer){clearTimeout(timer);timers.delete(timer);}});
 try {
  for(let index=1;index<=4;index++){await writer.write('startup',{index}); await writer.writeDiagnostic('startup',{index});}
  await new Promise(resolve => setTimeout(resolve, 550));
  assert.equal(operationTimers,0,'setup must not start an append operation deadline');
  assert.equal(rows.length,0);
  release(); await writer.close();
  assert.deepEqual(rows.map(row=>row[1]),[1,1,2,2,3,3,4,4]);
  assert.equal(writer.statusSnapshot().incompleteCapture,false);
 } finally {release(); await writer.close(); assert.equal(timers.size,0);}
});

test('trace unresolved startup keeps bounded close and never appends after close discards backlog', async () => {
 let release; const ready=new Promise(resolve=>{release=resolve;}); let appends=0;
 const writer=new TraceWriter('virtual.jsonl',{mkdir:()=>ready,appendFile:async()=>{appends++;},closeTimeoutMs:10});
 await writer.write('startup'); await writer.close(); release();
 await new Promise(setImmediate); await new Promise(setImmediate);
 assert.equal(appends,0); assert.equal(writer.statusSnapshot().incompleteCapture,true);
});

test('private-only turn recording omits public hashes but public capture and private failures remain intact', async () => {
 const original=crypto.createHash; let hashes=0;
 crypto.createHash=(...args)=>{hashes++;return original(...args);}; syncBuiltinESMExports();
 const recorders=[]; const fields={input:'input '.repeat(15000),output:'output '.repeat(15000)};
 async function capture(publicSink=null, fail=false){const rows=[]; const recorder=new ProviderTurnRecorder({runId:'g06',scenarioId:'fixture',privatePath:'virtual.jsonl',now:()=>1,publicSink,appendFile:async(_file,line)=>{if(fail)throw Error('fixture');rows.push(JSON.parse(line));}});recorders.push(recorder);recordProviderTurn(recorder,fields); await recorder.close();return {rows,status:recorder.statusSnapshot()};}
 try {
  const privateOnly=await capture(); assert.equal(hashes,0);
  const publicRows=[]; const both=await capture(row=>publicRows.push(row)); assert.equal(hashes,2);
  assert.deepEqual(both.rows,privateOnly.rows);assert.equal(Buffer.byteLength(both.rows[0].input),65536);
  assert.equal(publicRows[0].inputHash,`sha256:${original('sha256').update(fields.input).digest('hex')}`);
  assert.equal(Buffer.byteLength(publicRows[0].inputExcerpt),512);
  const failed=await capture(null,true);assert.equal(failed.status.failedOperationCount,1);assert.equal(failed.status.incompleteCapture,true);
  for(const recorder of recorders)recordProviderTurn(recorder,fields); recordProviderTurn(null,fields);assert.equal(hashes,2);
 } finally {await Promise.all(recorders.map(recorder=>recorder.close()));crypto.createHash=original;syncBuiltinESMExports();}
});

for(const [name,durations,rotate,expected] of [['complete',[500,100],false,600],['rotated',[500,100],true,600],['overflow',[200,150,100,75,50],true,375],['absent',[],false,null]]) {
 test(`retained timing ${name} reports observed sums without claiming full-run coverage`,async t=>{
  const dir=await fixture(t);await writeFile(path.join(dir,'report.json'),JSON.stringify({status:'PASSED',elapsedMs:1000}));
  const writer=new TraceWriter(path.join(dir,'coordinator.jsonl'),{preparePrivateArtifact:async()=>{},operationTimeoutMs:30000,closeTimeoutMs:60000,...(rotate?{maxFileBytes:1}: {})});
  for(const [index,duration] of durations.entries())await writer.write('native_decision_timing',{segmentDurationMs:duration,segmentIndex:index+1,traceId:'fixture'});
  await writer.close();assert.equal(writer.statusSnapshot().incompleteCapture,false);
  const result=await summarize(dir);assert.equal(result.modelSegmentMsTotal,expected);
  assert.equal(result.modelDecisionTimingComplete,false);
  const cli=JSON.parse(execFileSync(process.execPath,[fileURLToPath(new URL('../src/benchmark/live-action-gaps.mjs', import.meta.url)),`fixture=${dir}`],{encoding:'utf8',windowsHide:true,timeout:5000})).fixture;
  assert.equal(cli.nonModelMsP50,null);assert.equal(cli.modelDecisionTimingCoverage,0);
  assert.equal(cli.modelDecisionTimingAvailableRuns,durations.length?1:0);
 });
}
