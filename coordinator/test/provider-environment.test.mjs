import assert from 'node:assert/strict';
import test from 'node:test';

import { createProviderChildEnvironment } from '../src/provider-environment.mjs';

test('provider environment removes default and configured bridge credentials without removing provider settings', () => {
	const environment = createProviderChildEnvironment({
		PATH: 'C:\\Windows\\System32',
		APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming',
		OPENAI_API_KEY: 'provider-key',
		ARENA_AGENT_BRIDGE_SECRET: 'default-secret',
		ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\default.secret',
		CUSTOM_BRIDGE_SECRET: 'custom-secret',
	}, 'CUSTOM_BRIDGE_SECRET');

	assert.equal(environment.PATH, 'C:\\Windows\\System32');
	assert.equal(environment.APPDATA, 'C:\\Users\\lucas\\AppData\\Roaming');
	assert.equal(environment.OPENAI_API_KEY, 'provider-key');
	assert.equal(environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(environment.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined);
	assert.equal(environment.CUSTOM_BRIDGE_SECRET, undefined);
});
