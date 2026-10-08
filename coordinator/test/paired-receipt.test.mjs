import test from 'node:test';
import assert from 'node:assert/strict';
import { open, rename } from 'node:fs/promises';
import path from 'node:path';
import { runPairedCli } from '../src/benchmark/paired-cli.mjs';
import { fixture, launcher, json, profile } from './fixtures/paired-cli-fixture.mjs';

test('terminal receipt stays unconfirmed if either required replacement fails', async t => {
	const f = await fixture(t);
	for (const mode of ['healthy', 'late', 'report-correction-failure', 'receipt-correction-failure', 'initial-receipt-failure', 'certification-write-late']) {
		let time = 100, phases = 0;
		const counts = {};
		const config = { ...f.config, startupMs: 30000, runtimeBudgetMs: 195000, outputDirectory: path.join(f.directory, mode) };
		const operation = runPairedCli(config, { launcher, startedAtMs: 0, now: () => time,
			persist: async (file, value) => {
				const name = path.basename(file), count = counts[name] = (counts[name] ?? 0) + 1;
				if ((mode === 'report-correction-failure' && name === 'report.json' && count === 2)
					|| (mode === 'receipt-correction-failure' && name === 'completion.json' && count === 2)
					|| (mode === 'initial-receipt-failure' && name === 'completion.json' && count === 1)) {
					throw Object.assign(new Error('fixture ENOSPC before atomic replacement'), { code: 'ENOSPC' });
				}
				const handle = await open(`${file}.tmp`, 'w', 0o600);
				try { await handle.writeFile(JSON.stringify(value)); await handle.sync(); } finally { await handle.close(); }
				await rename(`${file}.tmp`, file);
				if (name === 'completion.json' && (count === 1 && !['healthy', 'certification-write-late'].includes(mode)
					|| count === 2 && mode === 'certification-write-late')) time = config.runtimeBudgetMs + 1;
			},
			makeWorker: (_command, args) => ({ pid: 0, terminate: async () => {}, phase: async (name, context, before) => {
				phases++; await before?.(); const request = await json(args.at(-1));
				if (name === 'startup') return { worldId: 'headless-receipt', modSha256: request.arm.artifactSha256 };
				if (name === 'trial') return {};
				return { runnerExit: 0, wrapper: { ok: true }, runner: { status: 'PASSED', classification: 'PASSED', profile,
					cleanup: { status: 'CLEAN' }, world: { worldId: 'headless-receipt', seed: context.pair.seed, fresh: true },
					settings: { configuredVerified: true, configured: profile } } };
			} }),
		});
		if (mode.endsWith('failure')) await assert.rejects(operation, { code: 'ENOSPC' });
		else assert.equal((await operation).status, mode === 'late' ? 'INCOMPLETE' : 'COMPLETE');
		assert.equal(phases, 12);
		assert.equal((await json(path.join(config.outputDirectory, 'report.json'))).counts.attempted, 4);
		const receiptPath = path.join(config.outputDirectory, 'completion.json');
		if (mode === 'initial-receipt-failure') await assert.rejects(json(receiptPath), { code: 'ENOENT' });
		else {
			const receipt = await json(receiptPath);
			assert.equal(receipt.status, mode.endsWith('failure') ? 'UNCONFIRMED' : mode === 'late' ? 'INCOMPLETE' : 'COMPLETE', mode);
			if (!mode.endsWith('failure')) {
				assert.equal(receipt.accountingBoundary, 'BEFORE_CERTIFICATION_COMMIT');
				assert.equal(receipt.certificationWriteCharged, false);
				assert.equal(receipt.overrunMs, mode === 'late' ? 1 : 0);
			}
		}
	}
});
