import { readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

// libuv hrtime uses the system monotonic clock (QPC on Windows). Unlike
// performance.now(), its origin is shared by local processes. The Windows
// relay uses Stopwatch.GetTimestamp / Frequency, the same QPC clock in ms.
export const pairedSystemNow = () => Number(process.hrtime.bigint()) / 1e6;

// File messages are local, single-writer phase handoffs. The supervising parent
// owns the cutoff and kills blocked workers; these waits grant no runtime.
export async function writePhaseMessage(directory, name, value) {
	const target = path.join(directory, `${name}.json`);
	await writeFile(`${target}.tmp`, JSON.stringify(value), { mode: 0o600 });
	await rename(`${target}.tmp`, target);
}

export async function readPhaseMessage(directory, name) {
	for (;;) {
		try { return JSON.parse(await readFile(path.join(directory, `${name}.json`), 'utf8')); }
		catch (error) { if (error.code !== 'ENOENT') throw error; }
		await new Promise(resolve => setTimeout(resolve, 5));
	}
}

export function runnerPhaseChannel(directory) {
	return {
		now: () => performance.now(),
		async ready() {
			await writePhaseMessage(directory, 'runner-ready', { ready: true });
			const request = await readPhaseMessage(directory, 'runner-trial');
			return localDeadline(request);
		},
		async cleanup(value) {
			await writePhaseMessage(directory, 'runner-trial-ended', value);
			const request = await readPhaseMessage(directory, 'runner-cleanup');
			return localDeadline(request);
		},
	};
}

function localDeadline(request) {
	if (request.clock !== 'system-monotonic-ms' || !Number.isFinite(request.cutoffMs) || request.cutoffMs < 0) throw new Error('Invalid shared phase cutoff');
	// Sample the local clock first so conversion can only shorten the allowance.
	// Expired messages retain a past deadline; no transit time is granted again.
	const localNow = performance.now();
	return localNow + request.cutoffMs - pairedSystemNow();
}
