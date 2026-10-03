import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defaultBaselineBundle } from './cave-navigation-attention.mjs';

const repositoryRoot = fileURLToPath(new URL('../../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const settings = Object.freeze({ model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' });
const agentFile = 'coordinator/config/minecraft-agent/AGENTS.md';
const skillFile = 'coordinator/config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md';
const common = `This is a text-only guidance evaluation. The observations below are complete for this decision. Do not call tools, inspect files, execute commands, use a browser, or perform gameplay. The bundled guidance is supplied as text. Return exactly one JSON object with at most 180 words total in prose fields. Do not report reasoning traces. Make your own decision; no particular reserve quantity, route length or new API is required.`;
const cases = Object.freeze({
  preparation: `Goal: collect three diamonds during a cave trip. The operator gives no gather-quantity limit. Current verified inventory: iron pickaxe with 25 uses remaining, stone pickaxe with 20 uses remaining, 2 bread, 12 coal, 8 cobblestone, 1 oak log, 2 sticks, no raw iron, no spare iron pickaxe. Health 20, food 16. At the entrance there are a verified placed furnace and crafting table; both can remain or be recovered. A safe exposed iron vein has 6 blocks, and each observed block drops 1 raw iron. A safe cow is nearby and can supply food if you choose to spend time on it. The known downward route is 40 blocks; beyond it is unknown, no diamond is currently observed. Choose preparation before descending. Return JSON with gatherIronBlocks (integer 0..6), foodAction (string), targetIronPickaxes (integer 1..3), workstationAction (string), preparationNote (string), why (string). Consider current possessions and marginal cost. Do not claim any action happened.`,
  route: `Goal: reach the next cave junction. Current observation includes six verified clear waypoint destinations along one irregular route: (2.5,64,0.5), (2.5,64,2.5), (3.5,65,2.5), (4.5,65,2.5), (4.5,65,4.5), (6.5,65,4.5). Player starts at (0.5,64,0.5), health 20, food 20. Support, clearance and ledge geometry for these destinations were just observed. There are no observed hostiles, resources or hazards. Beyond the final waypoint and around the next junction is unknown. Later ordinary notifications may contain only unchanged known stone support and player movement. You remain responsible for new geometry, lost support, unknown coverage, new useful ore and observed hostiles. Choose first background route batch, reassessment policy and inspections. Return JSON with firstBatchWaypointCount (integer 1..6), stopAt (string), attentionPolicy (string), reassessWhen (string or null), broadBlockInspectionsPerWaypoint (boolean), conditionsToReconsider (array of strings), why (string). Describe only decisions supported by these observations.`,
});

function section(source, title) {
  const start = source.indexOf(title);
  if (start < 0) return '';
  const rest = source.slice(start + title.length);
  const end = rest.search(/\n##? /);
  return `${title}${end < 0 ? rest : rest.slice(0, end)}`.trim();
}

async function runCli(codexBin, prompt, directory) {
  // Auth remains the existing ChatGPT login. An inherited API key must not select paid API billing.
  const env = { ...process.env }; delete env.OPENAI_API_KEY;
  const args = [codexBin, 'exec', '--ignore-user-config', '--ephemeral', '--skip-git-repo-check',
    '--sandbox', 'read-only', '--color', 'never', '--json', '--model', settings.model,
    '-c', `model_reasoning_effort="${settings.reasoningEffort}"`, '-c', `service_tier="${settings.serviceTier}"`,
    '-c', 'project_doc_max_bytes=0', '-C', directory, '-'];
  return await new Promise(resolve => {
    const child = spawn(process.execPath, args, { env, cwd: directory, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', timedOut = false;
    child.stdout.on('data', data => { stdout += data; });
    child.stderr.on('data', data => { stderr += data; });
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, 180000);
    child.on('error', error => { clearTimeout(timer); resolve({ code: null, stdout, stderr: error.message, timedOut }); });
    child.on('close', code => { clearTimeout(timer); resolve({ code, stdout, stderr, timedOut }); });
    child.stdin.end(prompt);
  });
}

export async function runGuidanceComparison({ codexBin = path.join(process.env.APPDATA, 'npm/node_modules/@openai/codex/bin/codex.js') } = {}) {
  const baseline = JSON.parse(await readFile(defaultBaselineBundle, 'utf8'));
  const beforeAgent = baseline.files[agentFile].source, beforeSkill = baseline.files[skillFile].source;
  assert.equal(hash(beforeAgent), baseline.files[agentFile].sha256);
  assert.equal(hash(beforeSkill), baseline.files[skillFile].sha256);
  const afterAgent = await readFile(path.join(repositoryRoot, agentFile), 'utf8');
  const afterSkill = await readFile(path.join(repositoryRoot, skillFile), 'utf8');
  const directory = await mkdtemp(path.join(os.tmpdir(), 'arena-cave-guidance-'));
  const report = { schemaVersion: 1, benchmark: 'cave-guidance-model', measuredAt: new Date().toISOString(),
    kind: 'actual-model-text-guidance-comparison', settings, billing: 'existing ChatGPT subscription login; inherited OPENAI_API_KEY omitted',
    maxTurns: 4, turnsExecuted: 0, status: 'PASSED', installedGameplay: false,
    limitations: ['One fresh response per scenario and arm; choices are anecdotal guidance compliance, not a statistical behavior estimate.',
      'No Minecraft actions, route completion, model token speed, or end-to-end gameplay latency are measured.',
      'CLI reports request usage. Shared account allowance and any resulting allowance percentage change are unavailable.'],
    guidanceHashes: { before: { agents: hash(beforeAgent), skill: hash(beforeSkill) }, after: { agents: hash(afterAgent), skill: hash(afterSkill) } },
    turns: [] };
  try {
    for (const [scenario, arm] of [['preparation', 'before'], ['preparation', 'after'], ['route', 'after'], ['route', 'before']]) {
      const agent = arm === 'before' ? beforeAgent : afterAgent;
      const skill = arm === 'before' ? beforeSkill : afterSkill;
      const excerpt = scenario === 'preparation' ? section(skill, '## Trip preparation')
        : [section(skill, '## Cave travel'), section(skill, '### runProgram')].filter(Boolean).join('\n\n');
      const prompt = `${common}\n\nActual bundled AGENTS instructions:\n${agent}\n\nRelevant bundled skill excerpt:\n${excerpt || '(No dedicated preparation section in this baseline skill.)'}\n\nObserved scenario:\n${cases[scenario]}`;
      const result = await runCli(codexBin, prompt, directory);
      report.turnsExecuted++;
      const events = result.stdout.trim().split(/\r?\n/).filter(Boolean).flatMap(line => { try { return [JSON.parse(line)]; } catch { return []; } });
      const completed = events.find(event => event.type === 'turn.completed');
      const output = events.filter(event => event.type === 'item.completed' && event.item?.type === 'agent_message').map(event => event.item.text).at(-1) ?? null;
      const toolCalls = events.filter(event => event.type === 'item.completed' && !['agent_message', 'reasoning'].includes(event.item?.type)).map(event => event.item.type);
      let decision = null;
      try { decision = JSON.parse(output?.replace(/^```json\s*|\s*```$/g, '') ?? ''); } catch { /* Preserve response as emitted if JSON formatting was not obeyed. */ }
      const row = { scenario, arm, prompt, promptSha256: hash(prompt), response: output, decision,
        usage: completed?.usage ?? null, toolCalls, exitCode: result.code, timedOut: result.timedOut,
        status: result.code === 0 && completed && output && toolCalls.length === 0 ? 'PASSED' : 'UNAVAILABLE' };
      // Only concise operational errors are retained; no session reasoning events or credentials.
      if (row.status !== 'PASSED') row.failure = events.find(event => event.type === 'turn.failed')?.error?.message ?? (result.timedOut ? 'CLI timeout' : 'CLI did not complete a tool-free response');
      report.turns.push(row);
      process.stdout.write(`${JSON.stringify({ scenario, arm, status: row.status, usage: row.usage, decision: row.decision })}\n`);
      if (row.status !== 'PASSED') { report.status = 'UNAVAILABLE'; break; }
    }
    return report;
  } finally {
    const resolved = path.resolve(directory);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('arena-cave-guidance-'));
    await rm(resolved, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = await runGuidanceComparison();
  await writeFile(path.join(repositoryRoot, 'reports/cave-guidance-model-2026-10-02.json'), `${JSON.stringify(result, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify({ status: result.status, turnsExecuted: result.turnsExecuted, settings })}\n`);
}
