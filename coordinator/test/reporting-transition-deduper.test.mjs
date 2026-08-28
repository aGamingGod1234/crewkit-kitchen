import assert from 'node:assert/strict';
import test from 'node:test';

import { ReportingTransitionDeduper } from '../src/reporting-transition-deduper.mjs';

test('identical failures are suppressed until a distinct recovery transition occurs', () => {
	const deduper = new ReportingTransitionDeduper();
	const failed = {
		agentId: 'luna', goalRevision: 4, component: 'provider', boundary: 'planning', code: 'PLANNING_TIMEOUT', state: 'retrying',
	};
	assert.equal(deduper.accept(failed), true);
	assert.equal(deduper.accept({ ...failed }), false);
	assert.equal(deduper.accept({ ...failed, code: 'PROVIDER_RECOVERED', state: 'ready' }), true);
	assert.equal(deduper.accept(failed), true, 'a real recovery makes a later recurrence visible');
});

test('transition state is bounded and clearable on disable, removal, revision, and close', () => {
	const deduper = new ReportingTransitionDeduper({ maximumAgents: 2 });
	for (const agentId of ['a', 'b', 'c']) {
		assert.equal(deduper.accept({ agentId, goalRevision: 1, component: 'lifecycle', boundary: 'goal', code: 'START', state: 'ready' }), true);
	}
	assert.equal(deduper.size, 2);
	deduper.clear('c');
	assert.equal(deduper.size, 1);
	assert.equal(deduper.accept({ agentId: 'b', goalRevision: 2, component: 'lifecycle', boundary: 'goal', code: 'START', state: 'ready' }), true);
	deduper.clearAll();
	assert.equal(deduper.size, 0);
});
