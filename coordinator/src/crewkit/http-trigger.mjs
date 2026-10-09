// Local trigger for CrewKit runs inside the coordinator. Binds 127.0.0.1 only.
//   POST /crewkit/run    { brief?, mode: "live"|"simulate"|"replay", speed?, tape? }  -> 202 { runId }
//   POST /crewkit/reset  {}                                                         -> 200 (emits a reset event)
//   GET  /crewkit/status                                                            -> 200 { active, last }
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { DEFAULT_BRIEF, MODES, startRun } from './runner.mjs';

export const DEFAULT_CREWKIT_PORT = 4777;

/** One run at a time; shared by the HTTP trigger and the crewkit_shop agent tool. */
export function createCrewkitController({ sink = () => {}, log = () => {} } = {}) {
  let active = null;
  let last = null;
  return {
    get active() { return active; },
    get last() { return last; },
    async start(brief, { mode = 'replay', speed, tape } = {}) {
      if (!MODES.includes(mode)) { const e = new Error(`mode must be one of ${MODES.join(', ')}`); e.status = 400; throw e; }
      if (active) { const e = new Error(`CrewKit run ${active.runId} is still in progress`); e.status = 409; throw e; }
      active = { runId: 'starting', mode, startedAt: Date.now() }; // claim the slot before any await
      let run;
      try {
        run = await startRun(brief ?? JSON.parse(readFileSync(DEFAULT_BRIEF, 'utf8')), {
          mode, bridgeSinks: [sink], log, ...(speed !== undefined ? { speed: Number(speed) } : {}), ...(typeof tape === 'string' && tape.endsWith('.tape.json') ? { tape } : {}),
        });
      } catch (e) { active = null; e.status ??= 400; throw e; }
      active = { runId: run.runId, mode, startedAt: Date.now() };
      run.done.then((r) => {
        last = { runId: run.runId, mode, status: r.status, reason: r.reason ?? null, record: r.record ?? null, calls: r.calls };
      }).catch((e) => { last = { runId: run.runId, mode, status: 'ERROR', reason: e.message }; })
        .finally(() => { active = null; });
      return run;
    },
    reset() {
      const payload = { runId: `ck-reset-${randomUUID().slice(0, 8)}`, seq: 1, event: 'reset', data: {} };
      sink(payload);
      return payload;
    },
  };
}

const readJson = (req) => new Promise((resolve, reject) => {
  let body = '';
  req.on('data', (c) => { body += c; if (body.length > 256_000) { reject(Object.assign(new Error('body too large'), { status: 413 })); req.destroy(); } });
  req.on('end', () => { try { resolve(body ? JSON.parse(body) : {}); } catch { reject(Object.assign(new Error('invalid JSON'), { status: 400 })); } });
  req.on('error', reject);
});

export function startCrewkitHttp({ controller, port = Number(process.env.CREWKIT_HTTP_PORT) || DEFAULT_CREWKIT_PORT, host = '127.0.0.1' } = {}) {
  const send = (res, status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  const server = createServer(async (req, res) => {
    try {
      if (req.method === 'POST' && req.url === '/crewkit/run') {
        const body = await readJson(req);
        const run = await controller.start(body.brief, { mode: body.mode || 'replay', speed: body.speed, tape: body.tape });
        return send(res, 202, { runId: run.runId, mode: body.mode || 'replay' });
      }
      if (req.method === 'POST' && req.url === '/crewkit/reset') return send(res, 200, controller.reset());
      if (req.method === 'GET' && req.url === '/crewkit/status') return send(res, 200, { active: controller.active, last: controller.last });
      return send(res, 404, { error: 'not found' });
    } catch (e) {
      return send(res, e.status || 500, { error: String(e.message).slice(0, 300) });
    }
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve(server));
  });
}
