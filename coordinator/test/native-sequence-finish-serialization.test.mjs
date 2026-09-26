import assert from 'node:assert/strict';
import test from 'node:test';

import { toolResultContent } from '../src/native-minecraft-tools.mjs';

test('oversized sequence results retain bounded verification outcome and explicit fact omissions', () => {
	const facts = Array.from({ length: 40 }, (_, index) => ({
		type: `goal_fact_${index}`,
		satisfied: index !== 39,
		expectedValue: 'expected-'.repeat(100),
		observedValue: 'observed-'.repeat(100),
	}));
	const result = JSON.parse(toolResultContent({
		state: 'SUCCEEDED',
		completed: 2,
		results: [
			{ actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE' },
			{ actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE' },
		],
		finish: { state: 'ACTIVE', verified: false, reasonCode: 'PREDICATE_FAILED', facts },
		postAction: { freshness: { fresh: true }, observation: { player: { health: 20 }, entities: Array.from({ length: 100 }, (_, id) => ({ id, name: 'x'.repeat(500) })) } },
	}).contentItems[0].text);
	assert.ok(Buffer.byteLength(JSON.stringify(result), 'utf8') <= 16_384);
	assert.deepEqual({ state: result.finish.state, verified: result.finish.verified, reasonCode: result.finish.reasonCode }, {
		state: 'ACTIVE', verified: false, reasonCode: 'PREDICATE_FAILED',
	});
	assert.ok(result.finish.facts.some((fact) => fact.satisfied === false), 'retain an unmet verification fact');
	assert.ok(result.finish.factsTruncated === true && result.finish.omittedFacts > 0);
});
