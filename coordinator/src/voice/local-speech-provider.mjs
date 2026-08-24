import { spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import { createInterface } from 'node:readline';

const MAX_TTS_TEXT_CODE_POINTS = 280;
const MAX_TTS_PCM_BYTES = 24_000 * 2 * 20;
const MAX_STT_PCM_BYTES = 48_000 * 2 * 20;
const MAX_RESPONSE_LINE_CHARS = 2 * 1024 * 1024;

export class LocalSpeechProvider {
	#executable;
	#scriptPath;
	#timeoutMs;
	#child = null;
	#pending = new Map();
	#nextId = 1;
	#closed = false;

	constructor({ executable, scriptPath, timeoutMs = 120_000 } = {}) {
		if (typeof executable !== 'string' || executable.trim() === '') throw new TypeError('local speech executable must not be blank');
		if (typeof scriptPath !== 'string' || scriptPath.trim() === '') throw new TypeError('local speech scriptPath must not be blank');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be positive');
		this.#executable = executable;
		this.#scriptPath = scriptPath;
		this.#timeoutMs = timeoutMs;
	}

	static async createIfAvailable(options = {}) {
		try {
			await Promise.all([access(options.executable), access(options.scriptPath)]);
		} catch (error) {
			if (error?.code === 'ENOENT') return null;
			throw error;
		}
		return new LocalSpeechProvider(options);
	}

	async synthesize({ text, speed = 1, signal } = {}) {
		if (typeof text !== 'string' || text.trim() === '') throw new TypeError('text must not be blank');
		if ([...text].length > MAX_TTS_TEXT_CODE_POINTS) throw new TypeError('text must be at most 280 Unicode code points');
		if (!Number.isFinite(speed) || speed < 0.5 || speed > 2) throw new TypeError('speed must be between 0.5 and 2');
		const response = await this.#request({ op: 'tts', text, speed }, signal);
		const sampleRateHz = response.sampleRateHz;
		const pcm = decodePcm(response.pcmBase64);
		if (!Number.isSafeInteger(sampleRateHz) || sampleRateHz !== 24_000
				|| pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_TTS_PCM_BYTES) {
			throw typedError('TTS_MALFORMED_AUDIO', 'Local TTS returned invalid 24 kHz mono signed 16-bit PCM');
		}
		return Object.freeze({ sampleRateHz, channels: 1, sampleFormat: 's16le', pcm });
	}

	async transcribe({ pcm, signal } = {}) {
		if (!Buffer.isBuffer(pcm) || pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_STT_PCM_BYTES) {
			throw typedError('STT_MALFORMED_AUDIO', 'STT input must be at most 20 seconds of 48 kHz mono PCM');
		}
		const response = await this.#request({ op: 'stt', pcmBase64: pcm.toString('base64') }, signal);
		if (typeof response.transcript !== 'string' || !Number.isFinite(response.confidence)) {
			throw typedError('STT_PROVIDER_RESPONSE', 'Local STT returned an invalid transcript');
		}
		return Object.freeze({
			transcript: [...response.transcript.trim()].slice(0, 512).join(''),
			confidence: Math.max(0, Math.min(1, response.confidence)),
		});
	}

	async close() {
		if (this.#closed) return;
		this.#closed = true;
		const child = this.#child;
		this.#failProcess(typedError('LOCAL_SPEECH_CLOSED', 'Local speech provider is closed'));
		if (child === null || child.exitCode !== null) return;
		await new Promise((resolve) => {
			const timer = setTimeout(resolve, 1_000);
			timer.unref?.();
			child.once('close', () => { clearTimeout(timer); resolve(); });
		});
	}

	#request(payload, signal) {
		if (this.#closed) return Promise.reject(typedError('LOCAL_SPEECH_CLOSED', 'Local speech provider is closed'));
		if (signal?.aborted) return Promise.reject(abortError());
		const child = this.#ensureProcess();
		const id = this.#nextId++;
		return new Promise((resolve, reject) => {
			const finish = (operation) => {
				const pending = this.#pending.get(id);
				if (pending === undefined) return;
				this.#pending.delete(id);
				clearTimeout(pending.timer);
				signal?.removeEventListener('abort', pending.onAbort);
				operation();
			};
			const onAbort = () => finish(() => reject(abortError()));
			const timer = setTimeout(() => {
				const error = timeoutError();
				finish(() => reject(error));
				this.#failProcess(error);
			}, this.#timeoutMs);
			timer.unref?.();
			this.#pending.set(id, { resolve, reject, timer, onAbort, signal });
			signal?.addEventListener('abort', onAbort, { once: true });
			try {
				child.stdin.write(`${JSON.stringify({ id, ...payload })}\n`, (error) => {
					if (error) this.#failProcess(typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker input failed'));
				});
			} catch {
				this.#failProcess(typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker input failed'));
			}
		});
	}

	#ensureProcess() {
		if (this.#child !== null) return this.#child;
		const child = spawn(this.#executable, [this.#scriptPath], {
			stdio: ['pipe', 'pipe', 'pipe'],
			windowsHide: true,
			env: { ...process.env, PYTHONUNBUFFERED: '1' },
		});
		this.#child = child;
		const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
		lines.on('line', (line) => this.#acceptResponse(line));
		child.stderr.resume();
		child.once('error', () => this.#failProcess(typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker could not start'), child));
		child.once('close', (code) => this.#failProcess(typedError(
			'LOCAL_SPEECH_UNAVAILABLE',
			code === 0 ? 'Local speech worker stopped' : `Local speech worker exited with code ${String(code)}`,
		), child));
		return child;
	}

	#acceptResponse(line) {
		if (line.length > MAX_RESPONSE_LINE_CHARS) {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker response exceeded its size limit'));
			return;
		}
		let response;
		try { response = JSON.parse(line); }
		catch {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker returned invalid JSON'));
			return;
		}
		if (response === null || typeof response !== 'object' || !Number.isSafeInteger(response.id)) {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker response is invalid'));
			return;
		}
		const pending = this.#pending.get(response.id);
		if (pending === undefined) return;
		this.#pending.delete(response.id);
		clearTimeout(pending.timer);
		pending.onAbort && pending.signal?.removeEventListener?.('abort', pending.onAbort);
		if (response.ok === true) {
			pending.resolve(response);
			return;
		}
		pending.reject(typedError(workerErrorCode(response.code), workerErrorMessage(response.message)));
	}

	#failProcess(error, expectedChild = this.#child) {
		if (expectedChild !== this.#child) return;
		const child = this.#child;
		this.#child = null;
		if (child !== null && child.exitCode === null && !child.killed) child.kill();
		for (const [id, pending] of this.#pending) {
			this.#pending.delete(id);
			clearTimeout(pending.timer);
			pending.signal?.removeEventListener('abort', pending.onAbort);
			pending.reject(error);
		}
	}
}

function decodePcm(value) {
	if (typeof value !== 'string' || value === '' || value.length > MAX_RESPONSE_LINE_CHARS) return Buffer.alloc(0);
	return Buffer.from(value, 'base64');
}

function workerErrorCode(value) {
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'LOCAL_SPEECH_ERROR';
}

function workerErrorMessage(value) {
	return typeof value === 'string' && value.trim() !== '' ? [...value].slice(0, 256).join('') : 'Local speech inference failed';
}

function abortError() {
	const error = new Error('Local speech request was cancelled');
	error.name = 'AbortError';
	return error;
}

function timeoutError() {
	const error = typedError('LOCAL_SPEECH_TIMEOUT', 'Local speech inference timed out');
	error.name = 'TimeoutError';
	return error;
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
