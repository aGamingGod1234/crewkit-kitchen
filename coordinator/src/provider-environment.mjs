const PROVIDER_ENVIRONMENT_VARIABLES = Object.freeze({
	codex: Object.freeze([
		'CODEX_HOME',
		'OPENAI_API_KEY',
		'OPENAI_BASE_URL',
		'OPENAI_ORGANIZATION',
		'OPENAI_ORG_ID',
		'OPENAI_PROJECT',
		'OPENAI_PROJECT_ID',
	]),
	gemini: Object.freeze([
		'AGY_HOME',
		'GEMINI_API_KEY',
		'GEMINI_CLI_HOME',
		'GOOGLE_API_KEY',
		'GOOGLE_APPLICATION_CREDENTIALS',
		'GOOGLE_CLOUD_LOCATION',
		'GOOGLE_CLOUD_PROJECT',
		'GOOGLE_CLOUD_QUOTA_PROJECT',
		'GOOGLE_GENAI_USE_GCA',
		'GOOGLE_GENAI_USE_VERTEXAI',
	]),
	claude: Object.freeze([
		'ANTHROPIC_API_KEY',
		'ANTHROPIC_AUTH_TOKEN',
		'ANTHROPIC_BASE_URL',
		'CLAUDE_CODE_OAUTH_TOKEN',
		'CLAUDE_CONFIG_DIR',
	]),
});

const OS_BOOTSTRAP_ENVIRONMENT_VARIABLES = Object.freeze([
	'APPDATA',
	'COLORTERM',
	'COMSPEC',
	'HOME',
	'HOMEDRIVE',
	'HOMEPATH',
	'LANG',
	'LC_ALL',
	'LC_CTYPE',
	'LOCALAPPDATA',
	'NODE_EXTRA_CA_CERTS',
	'NO_COLOR',
	'PATH',
	'PATHEXT',
	'PROGRAMDATA',
	'PROGRAMFILES',
	'PROGRAMFILES(X86)',
	'PROGRAMW6432',
	'SHELL',
	'SSL_CERT_DIR',
	'SSL_CERT_FILE',
	'SYSTEMDRIVE',
	'SYSTEMROOT',
	'TEMP',
	'TERM',
	'TMP',
	'TMPDIR',
	'USERPROFILE',
	'WINDIR',
	'XDG_CACHE_HOME',
	'XDG_CONFIG_HOME',
	'XDG_DATA_HOME',
	'XDG_STATE_HOME',
]);

const PROXY_ENVIRONMENT_VARIABLES = Object.freeze(['ALL_PROXY', 'HTTP_PROXY', 'HTTPS_PROXY']);

/**
 * Builds the complete environment for one model-provider process. Starting
 * from an allowlist prevents unrelated coordinator and voice credentials from
 * crossing the provider trust boundary.
 */
export function createProviderChildEnvironment(provider, environment = process.env, configuredBridgeSecretName = null, options = {}) {
	const normalizedProvider = requireProvider(provider);
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('provider environment must be an object');
	}
	if (options === null || typeof options !== 'object' || Array.isArray(options)) {
		throw new TypeError('provider environment options must be an object');
	}
	if (options.forwardProxyEnvironment !== undefined && typeof options.forwardProxyEnvironment !== 'boolean') {
		throw new TypeError('forwardProxyEnvironment must be a boolean');
	}
	const speechCredential = options.speechApiKeyEnvironmentVariable;
	if (speechCredential !== undefined && (typeof speechCredential !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(speechCredential))) {
		throw new TypeError('speechApiKeyEnvironmentVariable must be an environment variable name');
	}
	const allowedNames = [
		...OS_BOOTSTRAP_ENVIRONMENT_VARIABLES,
		...PROVIDER_ENVIRONMENT_VARIABLES[normalizedProvider],
	];
	const source = caseInsensitiveEnvironment(environment);
	const childEnvironment = {};
	for (const name of allowedNames) {
		const value = source.get(name);
		if (typeof value === 'string') childEnvironment[name] = value;
	}
	if (options.forwardProxyEnvironment === true) copyUnauthenticatedProxyEnvironment(source, childEnvironment);
	if (typeof configuredBridgeSecretName === 'string' && configuredBridgeSecretName.trim().length > 0) {
		delete childEnvironment[configuredBridgeSecretName.trim().toUpperCase()];
	}
	// A speech-only OpenAI key must not change the gameplay provider's saved-login billing.
	if (speechCredential !== undefined) delete childEnvironment[speechCredential.toUpperCase()];
	return childEnvironment;
}

function copyUnauthenticatedProxyEnvironment(source, target) {
	let forwarded = false;
	for (const name of PROXY_ENVIRONMENT_VARIABLES) {
		const value = source.get(name);
		if (typeof value !== 'string' || !isUnauthenticatedProxyUrl(value)) continue;
		target[name] = value;
		forwarded = true;
	}
	const noProxy = source.get('NO_PROXY');
	if (forwarded && typeof noProxy === 'string') target.NO_PROXY = noProxy;
}

function isUnauthenticatedProxyUrl(value) {
	try {
		const parsed = new URL(value);
		return ['http:', 'https:', 'socks:', 'socks4:', 'socks5:'].includes(parsed.protocol)
			&& parsed.hostname.length > 0
			&& parsed.username.length === 0
			&& parsed.password.length === 0;
	} catch {
		return false;
	}
}

function requireProvider(value) {
	if (typeof value !== 'string' || !Object.hasOwn(PROVIDER_ENVIRONMENT_VARIABLES, value)) {
		throw new TypeError(`provider must be one of ${Object.keys(PROVIDER_ENVIRONMENT_VARIABLES).join(', ')}`);
	}
	return value;
}

function caseInsensitiveEnvironment(environment) {
	const values = new Map();
	for (const [name, value] of Object.entries(environment)) {
		if (typeof value === 'string') values.set(name.toUpperCase(), value);
	}
	return values;
}
