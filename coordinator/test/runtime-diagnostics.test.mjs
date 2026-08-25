import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { wireRuntimeDiagnostics } from '../src/runtime-diagnostics.mjs';

test('forwards coordinator runtime errors to the reporter', () => {
	const coordinator = new EventEmitter();
	const reported = [];
	const reporter = { report: (error) => reported.push(error), recovered: () => {} };
	const error = Object.assign(new Error('bad frame'), { code: 'INVALID_FRAME' });

	wireRuntimeDiagnostics(coordinator, reporter);
	coordinator.emit('runtimeError', error);

	assert.deepEqual(reported, [error]);
});

test('marks diagnostics recovered only after coordinator reconciliation', () => {
	const coordinator = new EventEmitter();
	let recoveryCount = 0;
	const reporter = { report: () => {}, recovered: () => { recoveryCount += 1; } };

	wireRuntimeDiagnostics(coordinator, reporter);
	coordinator.emit('recovered', { serverInstanceId: 'server-1' });
	assert.equal(recoveryCount, 0);
	coordinator.emit('reconciled', { serverInstanceId: 'server-1' });

	assert.equal(recoveryCount, 1);
});

test('returns a disposer that detaches both diagnostic listeners', () => {
	const coordinator = new EventEmitter();
	let reportCount = 0;
	let recoveryCount = 0;
	const reporter = {
		report: () => { reportCount += 1; },
		recovered: () => { recoveryCount += 1; },
	};
	const dispose = wireRuntimeDiagnostics(coordinator, reporter);

	dispose();
	coordinator.emit('runtimeError', new Error('ignored'));
	coordinator.emit('reconciled');

	assert.equal(reportCount, 0);
	assert.equal(recoveryCount, 0);
});
