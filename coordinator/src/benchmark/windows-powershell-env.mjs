import path from 'node:path';

// pwsh can supply modules that Windows PowerShell 5.1 cannot load. Remove only
// the child search path and let that shell initialize its compatible defaults.
export function windowsPowerShellEnv(command = 'powershell.exe', environment = process.env) {
	const copy = { ...environment };
	if (path.win32.basename(command).toLowerCase() === 'powershell.exe') {
		for (const key of Object.keys(copy)) if (key.toLowerCase() === 'psmodulepath') delete copy[key];
	}
	return copy;
}
