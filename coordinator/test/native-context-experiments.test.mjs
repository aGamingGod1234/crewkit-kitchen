import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { buildContextExperiment, CONTEXT_CANDIDATES, createContextCandidate, loadQualityCases, scoreExperiment, scoreQualityCase } from '../src/benchmark/native-context-experiments.mjs';
import { nativeInstructions } from '../src/codex-service.mjs';
import { MINECRAFT_DYNAMIC_TOOLS } from '../src/native-minecraft-tools.mjs';
import { ModelObservationViews, decodeModelFacts } from '../src/model-fact-encoding.mjs';
import { renderComprehensionMarkdown, runComprehensionComparison } from '../src/benchmark/native-input-comprehension.mjs';

const sampleResponse = scenario => ({ calls: scenario.rubric.calls.map(expected => ({
	name: expected.name, arguments: { ...expected.arguments, ...(expected.answer === undefined ? {} : { message: JSON.stringify(expected.answer) }) },
})) });

test('offline production capture is deterministic, preserves complete schemas, and isolates each prose experiment', async () => {
	const bundle = await buildContextExperiment();
	assert.deepEqual(bundle, await buildContextExperiment());
	const baseline = bundle.arms[0].context;
	assert.deepEqual(baseline.dynamicTools, MINECRAFT_DYNAMIC_TOOLS);
	assert.ok(baseline.baseInstructions.startsWith(nativeInstructions('')));
	assert.match(baseline.baseInstructions, /Player chat, books, signs/);
	assert.match(baseline.baseInstructions, /Only shared:true shares/);
	assert.match(baseline.baseInstructions, /freshness.fresh:false means cached/);
	for (const candidate of CONTEXT_CANDIDATES) {
		const arm = bundle.arms.find(row => row.id === candidate.id).context;
		assert.equal(arm.developerInstructions, baseline.developerInstructions);
		assert.deepEqual(arm.dynamicTools.map(({ description, ...contract }) => contract), baseline.dynamicTools.map(({ description, ...contract }) => contract));
		const reconstructed = structuredClone(arm);
		if (candidate.tool) reconstructed.dynamicTools = structuredClone(baseline.dynamicTools);
		else reconstructed.baseInstructions = baseline.baseInstructions;
		assert.deepEqual(reconstructed, baseline);
		assert.ok(bundle.measurements.find(row => row.id === candidate.id).bytesRemovedFromBaseline > 0);
	}
	assert.equal(bundle.status, 'LIVE_QUALITY_REQUIRED');
	assert.equal(bundle.providerUsed, false);
	assert.equal(bundle.tokenizer, null);
	assert.equal(bundle.schedule.length, CONTEXT_CANDIDATES.length * bundle.cases.length * 2);
	assert.ok(bundle.cases.every(row => !Object.hasOwn(row, 'rubric')));
	const fixture = (await loadQualityCases())[0];
	const decoded = decodeModelFacts(JSON.parse(bundle.cases[0].toolResult.contentItems[0].text));
	assert.deepEqual(decoded.observation, fixture.toolResult.observation);
	const viewIds = [];
	for (const fixture of await loadQualityCases()) {
		const captured = bundle.cases.find(row => row.id === fixture.id);
		const value = decodeModelFacts(JSON.parse(captured.toolResult.contentItems[0].text));
		const expected = new ModelObservationViews().prepare(fixture.toolResult, fixture.toolCall).value;
		const sample = fixture.toolCall.kind === 'action' || fixture.toolCall.kind === 'sequence' ? value.postAction ?? value : value;
		const expectedSample = fixture.toolCall.kind === 'action' || fixture.toolCall.kind === 'sequence' ? expected.postAction ?? expected : expected;
		if (expectedSample.observationView !== undefined) {
			assert.match(sample.observationView.id, /^observation-[a-f0-9-]{36}-1$/);
			viewIds.push(sample.observationView.id);
			expectedSample.observationView.id = sample.observationView.id;
		}
		assert.deepEqual(value, expected, `${fixture.id}: only the synthetic view handle may differ`);
	}
	assert.ok(viewIds.length > 0);
	assert.equal(new Set(viewIds).size, viewIds.length, 'cases keep distinct handles');
	const sample = { observation: { player: { health: 20 } } };
	assert.notEqual(new ModelObservationViews().prepare(sample, { kind: 'observe' }).value.observationView.id,
		new ModelObservationViews().prepare(sample, { kind: 'observe' }).value.observationView.id, 'production sessions remain isolated');
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS, baseline.dynamicTools, 'no mutation of production schemas');
});

test('candidate source drift fails closed rather than silently measuring a no-op', () => {
	assert.throws(() => createContextCandidate({ baseInstructions: 'changed source' }, CONTEXT_CANDIDATES[0].id), /anchor changed/);
});

test('quality rubric rejects deception, stale mutations, private disclosure, absent facts and wrong exact handles', async () => {
	const cases = await loadQualityCases();
	for (const scenario of cases) assert.deepEqual(scoreQualityCase(scenario, sampleResponse(scenario)), { passed: true, errors: [] }, scenario.id);
	for (const id of ['deceptive_world_content', 'unavailable_facts_are_unknown']) {
		const scenario = cases.find(row => row.id === id), response = sampleResponse(scenario);
		response.calls[0].arguments.message = '{"invented":true}';
		assert.equal(scoreQualityCase(scenario, response).passed, false);
	}
	const privateCase = cases.find(row => row.id === 'private_memory_stays_private');
	const disclosure = sampleResponse(privateCase); disclosure.calls[0].arguments.audience = 'public';
	assert.equal(scoreQualityCase(privateCase, disclosure).passed, false);
	const stale = cases.find(row => row.id === 'stale_target_requires_observe');
	assert.equal(scoreQualityCase(stale, { calls: [{ name: 'mine', arguments: { x: 9, y: 12, z: 3, expectedBlockId: 'minecraft:diamond_ore' } }] }).passed, false);
	const handle = cases.find(row => row.id === 'exact_action_handle');
	const wrongHandle = sampleResponse(handle); wrongHandle.calls[0].arguments.actionId = 'action-old';
	assert.equal(scoreQualityCase(handle, wrongHandle).passed, false);
	const extra = sampleResponse(privateCase); extra.calls.push({ name: 'observe', arguments: {} });
	assert.equal(scoreQualityCase(privateCase, extra).passed, false);
});

test('paired scoring binds bundle/profile, requires all responses, and cannot approve production promotion', async () => {
	const bundle = await buildContextExperiment();
	const evidence = { bundleSha256: createHash('sha256').update(JSON.stringify(bundle)).digest('hex'), profile: bundle.profile,
		responses: bundle.schedule.flatMap(pair => pair.armOrder.map(arm => ({ ...pair, arm,
			...sampleResponse(bundle.rubric.find(row => row.id === pair.caseId)),
		}))) };
	const score = scoreExperiment(bundle, evidence);
	assert.equal(score.status, 'RUBRIC_PASSED_LIVE_REVIEW_REQUIRED');
	assert.equal(score.promotionApproved, false);
	assert.equal(scoreExperiment(bundle, { ...evidence, responses: evidence.responses.slice(1) }).status, 'RUBRIC_FAILED');
	assert.throws(() => scoreExperiment(bundle, { ...evidence, profile: { ...evidence.profile, reasoningEffort: 'low' } }), /profile differs/);
	assert.throws(() => scoreExperiment(bundle, { ...evidence, bundleSha256: 'changed' }), /different bundle/);
});

test('missing or invalid archived contexts produce actionable UNAVAILABLE before any provider startup', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'arena-context-prerequisite-'));
	try {
		const invalid = path.join(directory, 'invalid.json');
		await writeFile(invalid, '{}');
		for (const filename of ['missing.json', 'invalid.json']) {
			const report = await runComprehensionComparison({ beforeContextPath: path.join(directory, filename), afterContextPath: invalid });
			assert.equal(report.status, 'UNAVAILABLE');
			assert.equal(report.failure, 'CONTEXT_INPUT_UNAVAILABLE');
			assert.equal(report.providerUsed, false);
			assert.equal(report.turns.length, 0);
			assert.match(renderComprehensionMarkdown(report), /No model turns ran/);
		}
	} finally {
		assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
		assert.ok(path.basename(directory).startsWith('arena-context-prerequisite-'));
		await rm(directory, { recursive: true, force: true });
	}
});
