import assert from 'node:assert/strict';
import test from 'node:test';

import { parseCliArguments, selectAgentConfigs } from '../src/main.mjs';

test('parses strict coordinator config and agent filters', () => {
	assert.deepEqual(parseCliArguments([]), { configPath: null, agent: 'all', checkModels: false });
	assert.deepEqual(parseCliArguments(['--config', 'C:\\runtime\\agents.json', '--agent', 'agent-55']), {
		configPath: 'C:\\runtime\\agents.json', agent: 'agent-55', checkModels: false,
	});
	assert.deepEqual(parseCliArguments(['--check-models', '--agent', 'agent-56']), {
		configPath: null, agent: 'agent-56', checkModels: true,
	});
});

test('rejects unknown, duplicate, missing, relative, or unsupported CLI values', () => {
	assert.throws(() => parseCliArguments(['--unknown']), /Unknown argument/);
	assert.throws(() => parseCliArguments(['--agent']), /requires a value/);
	assert.throws(() => parseCliArguments(['--agent', 'agent55']), /must be all, agent-55, or agent-56/);
	assert.throws(() => parseCliArguments(['--config', 'relative.json']), /absolute/);
	assert.throws(() => parseCliArguments(['--agent', 'all', '--agent', 'agent-55']), /may appear only once/);
});

test('selects exactly one approved agent or both', () => {
	const configs = [{ agentId: 'agent-55' }, { agentId: 'agent-56' }];
	assert.deepEqual(selectAgentConfigs(configs, 'all'), configs);
	assert.deepEqual(selectAgentConfigs(configs, 'agent-56'), [{ agentId: 'agent-56' }]);
	assert.throws(() => selectAgentConfigs(configs, 'agent-57'), /Unsupported agent/);
});
