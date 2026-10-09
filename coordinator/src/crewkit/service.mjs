// Process-wide CrewKit controller so the HTTP trigger and the crewkit_shop tool share one run slot and one sink.
import { createCrewkitController, startCrewkitHttp } from './http-trigger.mjs';

let sink = () => {};
let log = () => {};
const controller = createCrewkitController({ sink: (p) => sink(p), log: (m) => log(m) });

export const getCrewkitController = () => controller;

/**
 * Called once from the coordinator CLI. sendState(payload) delivers a crewkit_state message to the mod.
 * Starts the 127.0.0.1 HTTP trigger unless CREWKIT_HTTP=off.
 */
export async function startCrewkitService({ sendState, logger = () => {} } = {}) {
  log = logger;
  sink = (payload) => Promise.resolve(sendState?.(payload)).then((sent) => {
    if (sent === false) logger(`crewkit_state ${payload.event} #${payload.seq} not delivered: no server connected`);
  }).catch((e) => logger(`crewkit_state ${payload.event} #${payload.seq} send failed: ${e.code || e.message}`));
  if (process.env.CREWKIT_HTTP === 'off') return null;
  const server = await startCrewkitHttp({ controller });
  server.unref(); // must not keep the coordinator alive after SIGINT shutdown
  const addr = server.address();
  logger(`CrewKit trigger listening on http://${addr.address}:${addr.port}/crewkit/run`);
  return server;
}
