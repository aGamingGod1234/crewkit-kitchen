import {
	sanitizeDiagnosticErrorCode,
	sanitizeDiagnosticErrorMessage,
	sanitizeDiagnosticErrorStack,
} from './diagnostic-sanitizer.mjs';

export const NATIVE_TOOL_CLI_FAILURE = 1;

/** Runs one native benchmark/probe CLI behind a bounded, redacted terminal boundary. */
export async function runNativeToolCli(run, { stdout = process.stdout, stderr = process.stderr } = {}) {
	try {
		const exitCode = await run();
		return Number.isSafeInteger(exitCode) && exitCode >= 0 && exitCode <= 255 ? exitCode : 0;
	} catch (error) {
		const failure = {
			status: 'FAILED',
			code: sanitizeDiagnosticErrorCode(error, { fallback: 'ERROR', maxBytes: 64 }),
			message: containRedactedLocation(sanitizeDiagnosticErrorMessage(error, { maxBytes: 1_024 })),
			stack: containRedactedLocation(sanitizeDiagnosticErrorStack(error, { maxBytes: 4_096 })),
		};
		const line = `${JSON.stringify(failure)}\n`;
		try {
			stdout.write(line);
		} catch {
			try { stderr.write('{"status":"FAILED","code":"ERROR","message":"diagnostic output unavailable","stack":"diagnostic output unavailable"}\n'); }
			catch { /* No terminal remains. The exit code still reports failure. */ }
		}
		return NATIVE_TOOL_CLI_FAILURE;
	}
}

function containRedactedLocation(value) {
	const marker = '[location redacted]';
	const markerAt = value.indexOf(marker);
	return markerAt === -1 ? value : value.slice(0, markerAt + marker.length);
}
