import assert from 'node:assert/strict';
import test from 'node:test';

import { startTwoAgentFixture } from './fixtures/two-agent-fixture.mjs';

test('agents remain isolated while progressing concurrently', async () => {
	const run = await startTwoAgentFixture();
	try {
		await run.goalBoth('enter the arena');
		await run.untilBothComplete();
		assert.equal(run.crossAgentMessages(), 0);
		assert.deepEqual(run.models(), ['gpt-5.5', 'gpt-5.6-sol']);
		assert.equal(run.promptsIdentical(), true);
		assert.deepEqual(run.actionCounts(), [2, 2]);
	} finally {
		await run.stop();
	}
});

test('reconnects, retains selected-model programs, and shuts down with trace evidence', async () => {
	const run = await startTwoAgentFixture({ malformedFirstAgent: 'agent-55' });
	try {
		await run.reconnect('agent-55');
		await run.goalBoth('enter the arena');
		await run.untilBothComplete();
		assert.ok(run.connectionCount('agent-55') >= 2);
		assert.ok(run.plannerAttempts('agent-55') >= 2, 'malformed provider output received a corrective retry');
		assert.equal(run.sameSelectedSession('agent-55'), true, 'correction stayed on the selected model session');
	} finally {
		const evidence = await run.stop();
		assert.ok(evidence['agent-55'].some((row) => row.event === 'program_compiled'));
		assert.ok(evidence['agent-56'].some((row) => row.event === 'program_compiled'));
		assert.ok(evidence['agent-55'].some((row) => row.event === 'program_finished'));
		assert.ok(evidence['agent-56'].some((row) => row.event === 'program_finished'));
	}
});
