import { writeFileSync } from 'node:fs';
import { CodexStdioTransport } from '../../src/codex-app-server.mjs';

// Committed CLI fixture: fail closed before authentication or any model request.
const counts = { starts: 0, stops: 0, requests: 0, modelTurns: 0 };
CodexStdioTransport.prototype.start = async function () {
	counts.starts += 1;
	throw Object.assign(new Error('Offline startup sentinel'), { code: 'OFFLINE_START_SENTINEL' });
};
CodexStdioTransport.prototype.stop = async function () { counts.stops += 1; };
CodexStdioTransport.prototype.request = async function (method) {
	counts.requests += 1;
	if (method === 'turn/start') counts.modelTurns += 1;
	throw new Error('Provider request forbidden in offline CLI test');
};
// Node also discovers fixture modules during a bare --test run. Keep the
// transport fail-closed, but write counts only for an explicitly configured CLI.
if (process.env.COMPREHENSION_TRANSPORT_COUNTS) {
	process.on('exit', () => writeFileSync(process.env.COMPREHENSION_TRANSPORT_COUNTS, JSON.stringify(counts)));
}
