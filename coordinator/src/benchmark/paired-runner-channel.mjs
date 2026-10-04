import { readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

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
			return performance.now() + remaining(request);
		},
		async cleanup(value) {
			await writePhaseMessage(directory, 'runner-trial-ended', value);
			const request = await readPhaseMessage(directory, 'runner-cleanup');
			return performance.now() + remaining(request);
		},
	};
}

function remaining(request) {
	if (!Number.isFinite(request.remainingMs) || request.remainingMs < 0) throw new Error('Invalid remaining phase duration');
	return request.remainingMs;
}
