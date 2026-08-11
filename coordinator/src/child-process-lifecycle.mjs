import { execFile as nodeExecFile } from 'node:child_process';

export const DEFAULT_CHILD_STOP_TIMEOUT_MS = 2_000;

export async function terminateChildProcess(child, {
	timeoutMs = DEFAULT_CHILD_STOP_TIMEOUT_MS,
	platform = process.platform,
	execFile = nodeExecFile,
} = {}) {
	if (child === null || child === undefined) return;
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new TypeError('child process stop timeout must be a positive safe integer');
	if (platform === 'win32' && Number.isSafeInteger(child.pid) && child.pid > 0) {
		await terminateWindowsProcessTree(child, timeoutMs, execFile);
		return;
	}
	if (hasExited(child)) return;

	sendSignal(child, 'SIGTERM');
	if (hasExited(child) || await waitForExit(child, timeoutMs)) return;

	sendSignal(child, 'SIGKILL');
}

async function terminateWindowsProcessTree(child, timeoutMs, execute) {
	await runTaskkill(execute, child.pid, false);
	if (hasExited(child) || await waitForExit(child, timeoutMs)) return;
	const forced = await runTaskkill(execute, child.pid, true);
	if (!forced && !hasExited(child)) throw new Error(`Could not terminate provider process tree ${child.pid}`);
}

function runTaskkill(execute, pid, force) {
	const args = ['/PID', String(pid), '/T'];
	if (force) args.push('/F');
	return new Promise((resolve) => {
		execute('taskkill.exe', args, { windowsHide: true }, (error) => resolve(error === null || error === undefined));
	});
}

function hasExited(child) {
	return child.exitCode !== null || child.signalCode !== null;
}

function sendSignal(child, signal) {
	try {
		child.kill(signal);
	} catch (error) {
		if (!hasExited(child)) throw new Error(`Could not send ${signal} to provider process: ${error.message}`, { cause: error });
	}
}

function waitForExit(child, timeoutMs) {
	if (hasExited(child)) return Promise.resolve(true);
	return new Promise((resolve) => {
		let timer;
		const onExit = () => finish(true);
		const finish = (exited) => {
			clearTimeout(timer);
			child.off('exit', onExit);
			resolve(exited);
		};
		child.once('exit', onExit);
		timer = setTimeout(() => finish(hasExited(child)), timeoutMs);
	});
}
