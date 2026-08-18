const DEFAULT_BRIDGE_SECRET_NAMES = Object.freeze([
	'ARENA_AGENT_BRIDGE_SECRET',
	'ARENA_AGENT_BRIDGE_SECRET_FILE',
]);

/**
 * Returns the environment a model-provider child may inherit. Coordinator bridge
 * credentials deliberately never cross this trust boundary.
 */
export function createProviderChildEnvironment(environment = process.env, configuredBridgeSecretName = null) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('provider environment must be an object');
	}
	const childEnvironment = { ...environment };
	for (const name of DEFAULT_BRIDGE_SECRET_NAMES) delete childEnvironment[name];
	if (typeof configuredBridgeSecretName === 'string' && configuredBridgeSecretName.trim().length > 0) {
		delete childEnvironment[configuredBridgeSecretName];
	}
	return childEnvironment;
}
