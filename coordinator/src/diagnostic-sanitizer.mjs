import { types as nodeTypes } from 'node:util';

export const DIAGNOSTIC_REDACTED = '[REDACTED]';
export const DIAGNOSTIC_UNSAFE = '[UNSAFE_OBJECT]';

const SENSITIVE_KEY = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|launcherAccount|accountData|token|credential|oauth)/i;
const QUOTED_SECRET_KEY = /(["'])([A-Za-z0-9_-]{0,64}(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]{0,64})\1\s*:\s*(["'])/gi;
const SECRET_ASSIGNMENT = /((?:[A-Za-z0-9_-]{0,64}(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)[A-Za-z0-9_-]{0,64})\s*[:=]\s*)([^\s,;)}\]"']+)/gi;
const BEARER = /\bBearer\s+[A-Za-z0-9._~+/=-]+/gi;
const RAW_PROMPT = /(?:raw\s+)?prompt\s*[:=]\s*[^\r\n]*/gi;
const QUOTED_ABSOLUTE_PATH = /(["'])(?:(?:file:\/{2,3})?(?:[A-Za-z]:[\\/]|\/{1,2})|\\\\[^\\/\s]+[\\/][^\\/\s]+[\\/])[^"'\r\n]+\1/gi;
const WINDOWS_PATH = /(?:file:\/{2,3})?[A-Za-z]:[\\/][^\s,;)}\]"']+/gi;
const UNC_PATH = /\\\\[^\\/\s]+[\\/][^\s,;)}\]"']+/g;
const POSIX_PATH = /(^|[\s(=:\[])(\/(?:[^\s,;)}\]"'\/]+\/)*[^\s,;)}\]"'\/]+)/g;

export function isSensitiveDiagnosticKey(value) {
	return typeof value === 'string' && SENSITIVE_KEY.test(value);
}

export function sanitizeDiagnosticText(value, { maxBytes = 2_048, redactPaths = true } = {}) {
	if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) throw new TypeError('maxBytes must be a nonnegative safe integer');
	let text;
	try { text = String(value ?? ''); } catch { text = '[unavailable]'; }
	text = redactQuotedSecrets(text)
		.replace(BEARER, `Bearer ${DIAGNOSTIC_REDACTED}`)
		.replace(SECRET_ASSIGNMENT, `$1${DIAGNOSTIC_REDACTED}`)
		.replace(RAW_PROMPT, DIAGNOSTIC_REDACTED);
	if (redactPaths) {
		text = text
			.replace(QUOTED_ABSOLUTE_PATH, '[location redacted]')
			.replace(UNC_PATH, '[location redacted]')
			.replace(WINDOWS_PATH, '[location redacted]')
			.replace(POSIX_PATH, (_match, prefix) => `${prefix}[location redacted]`);
	}
	text = text.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim();
	return truncateDiagnosticUtf8(text, maxBytes);
}

export function sanitizeDiagnosticValue(value, {
	maxDepth = 8,
	maxEntries = 64,
	maxNodes = 512,
	maxStringBytes = 2_048,
	redactPaths = true,
} = {}) {
	const seen = new WeakSet();
	let nodes = 0;
	const visit = (input, depth, key = null) => {
		if (isSensitiveDiagnosticKey(key)) return DIAGNOSTIC_REDACTED;
		if (typeof input === 'string') return sanitizeDiagnosticText(input, { maxBytes: maxStringBytes, redactPaths });
		if (input === null || ['boolean', 'number'].includes(typeof input)) return input;
		if (['undefined', 'bigint', 'function', 'symbol'].includes(typeof input)) return DIAGNOSTIC_UNSAFE;
		if (depth > maxDepth || nodes >= maxNodes) return '[BOUNDED]';
		nodes += 1;
		if (nodeTypes.isProxy(input)) return DIAGNOSTIC_UNSAFE;
		if (seen.has(input)) return '[CIRCULAR]';
		seen.add(input);
		let keys;
		try { keys = Reflect.ownKeys(input); } catch { return DIAGNOSTIC_UNSAFE; }
		const output = Array.isArray(input) ? [] : Object.create(null);
		let entries = 0;
		for (const property of keys) {
			if (typeof property !== 'string' || entries >= maxEntries) continue;
			let descriptor;
			try { descriptor = Object.getOwnPropertyDescriptor(input, property); } catch { continue; }
			if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
			entries += 1;
			output[property] = visit(descriptor.value, depth + 1, property);
		}
		return output;
	};
	return visit(value, 0);
}

export function truncateDiagnosticUtf8(value, maxBytes) {
	if (Buffer.byteLength(value, 'utf8') <= maxBytes) return value;
	return Buffer.from(value, 'utf8').subarray(0, maxBytes).toString('utf8').replace(/\uFFFD$/u, '');
}

function redactQuotedSecrets(value) {
	let result = '';
	let cursor = 0;
	QUOTED_SECRET_KEY.lastIndex = 0;
	let match;
	while ((match = QUOTED_SECRET_KEY.exec(value)) !== null) {
		const valueStart = QUOTED_SECRET_KEY.lastIndex;
		let valueEnd = valueStart;
		while (valueEnd < value.length) {
			if (value[valueEnd] === '\\') { valueEnd += 2; continue; }
			if (value[valueEnd] === match[3]) break;
			valueEnd += 1;
		}
		if (valueEnd >= value.length) break;
		result += value.slice(cursor, valueStart) + DIAGNOSTIC_REDACTED + match[3];
		cursor = valueEnd + 1;
		QUOTED_SECRET_KEY.lastIndex = cursor;
	}
	return result + value.slice(cursor);
}
