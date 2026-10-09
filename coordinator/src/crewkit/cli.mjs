#!/usr/bin/env node
// CrewKit CLI.
//   node coordinator/src/crewkit/cli.mjs run [brief.json] --mode replay|simulate|live [--speed 0.5] [--tape file] [--post [url]] [--quiet]
// Without --post the run happens in this process; events print as NDJSON and land in crewkit-records/.
// With --post the brief is sent to the running coordinator (POST /crewkit/run), which streams events to the mod.
import { readFileSync } from 'node:fs';
import { DEFAULT_BRIEF, startRun } from './runner.mjs';
import { consoleSink } from './events.mjs';
import { DEFAULT_CREWKIT_PORT } from './http-trigger.mjs';

const args = process.argv.slice(2);
const flag = (name, dflt) => { const i = args.indexOf(name); if (i === -1) return dflt; const v = args[i + 1]; return v && !v.startsWith('--') ? v : true; };
const [cmd, briefPath] = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--') && ['--mode', '--speed', '--tape', '--post'].includes(args[i - 1])));

async function main() {
  if (cmd !== 'run') {
    console.log('usage: node coordinator/src/crewkit/cli.mjs run [brief.json] --mode replay|simulate|live [--speed 1] [--tape file] [--post [url]] [--quiet] [--show-approval-url]');
    process.exitCode = cmd ? 1 : 0;
    return;
  }
  const brief = JSON.parse(readFileSync(briefPath || DEFAULT_BRIEF, 'utf8'));
  const mode = flag('--mode', 'replay');
  const post = flag('--post', false);
  if (post) {
    const url = post === true ? `http://127.0.0.1:${process.env.CREWKIT_HTTP_PORT || DEFAULT_CREWKIT_PORT}/crewkit/run` : post;
    const body = { brief, mode, ...(flag('--speed') ? { speed: Number(flag('--speed')) } : {}), ...(typeof flag('--tape') === 'string' ? { tape: flag('--tape') } : {}) };
    const res = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    console.log(res.status, await res.text());
    process.exitCode = res.ok ? 0 : 1;
    return;
  }
  const quiet = flag('--quiet', false);
  const { runId, done } = await startRun(brief, {
    mode,
    speed: Number(flag('--speed', 1)),
    ...(typeof flag('--tape') === 'string' ? { tape: flag('--tape') } : {}),
    sinks: quiet ? [] : [consoleSink()],
    // For a human approving from a terminal without the mod. Off by default so agents reading stdout never see it.
    bridgeSinks: flag('--show-approval-url', false) ? [(p) => { if (p.event === 'checkout' && p.data.approvalUrl && p.data.status === 'REQUIRES_ACTION') console.error(`[crewkit] approve at: ${p.data.approvalUrl}`); }] : [],
    log: (m) => console.error(`[crewkit] ${m}`),
  });
  console.error(`[crewkit] run ${runId} mode=${mode}`);
  const r = await done;
  console.error(`[crewkit] ${r.status === 'COMPLETED' ? 'order placed' : r.status}${r.reason ? ` (${r.reason})` : ''} calls=${r.calls} events=${r.events.length}`);
  if (r.record) console.error(`[crewkit] record ${JSON.stringify(r.record)}`);
  for (const f of [r.eventsFile, r.files?.jsonFile, r.files?.csvFile, r.tapeFile].filter(Boolean)) console.error(`[crewkit] wrote ${f}`);
  process.exitCode = r.status === 'COMPLETED' ? 0 : 2; // exitCode, not exit(): libuv assertion on Windows with fetch handles open
}

main().catch((e) => { console.error(`[crewkit] ${e.message}`); process.exitCode = 1; });
