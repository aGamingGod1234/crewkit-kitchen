import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { CodexService, presentNativeToolResult } from '../codex-service.mjs';
import { ModelObservationViews } from '../model-fact-encoding.mjs';
import { normalizeMinecraftToolCall } from '../native-minecraft-tools.mjs';

const root = new URL('../../../', import.meta.url);
const fixtureUrl = new URL('./fixtures/native-context-quality-cases.json', import.meta.url);
const sha256 = value => createHash('sha256').update(value).digest('hex');
const serialize = value => JSON.stringify(value);
const measure = value => {
	const text = typeof value === 'string' ? value : serialize(value);
	return { bytes: Buffer.byteLength(text, 'utf8'), sha256: sha256(text) };
};

// Each arm changes one prose location. Exact anchors fail closed after source changes.
export const CONTEXT_CANDIDATES = Object.freeze([
	{ id: 'workspace-communication-repetition', field: 'baseInstructions',
		remove: ' Plain assistant text is not visible in Minecraft; use say for communication.',
		retainedContract: 'Native instructions and bundled skill still require say for visible communication.' },
	{ id: 'schema-program-reuse-repetition', field: 'dynamicTools', tool: 'runProgram',
		remove: ' Reuse noteKey when fresh prerequisites and targets match.',
		retainedContract: 'Bundled skill still requires exact noteKey with fresh prerequisites/current targets; schema retains all fields and execution constraints.' },
]);

export function createContextCandidate(baseline, candidateId) {
	const spec = CONTEXT_CANDIDATES.find(row => row.id === candidateId);
	assert.ok(spec, `unknown candidate: ${candidateId}`);
	const candidate = structuredClone(baseline);
	const target = spec.tool ? candidate.dynamicTools.find(tool => tool.name === spec.tool) : candidate;
	const field = spec.tool ? 'description' : spec.field;
	assert.equal(typeof target?.[field], 'string', `missing candidate field: ${candidateId}`);
	assert.equal(target[field].split(spec.remove).length, 2, `candidate anchor changed: ${candidateId}`);
	target[field] = target[field].replace(spec.remove, '');
	return candidate;
}

/** Capture production thread/start construction at its transport boundary; never launch a provider. */
export async function captureCurrentContext() {
	const config = JSON.parse(await readFile(new URL('coordinator/config/dynamic-agents.json', root), 'utf8'));
	const { model, reasoningEffort, serviceTier } = config.codex.launchProfile;
	const profile = { provider: 'codex', model, reasoningEffort, serviceTier };
	const instructions = await readFile(new URL('coordinator/config/minecraft-agent/AGENTS.md', root), 'utf8');
	const skillInstructions = await readFile(new URL('coordinator/config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', root), 'utf8');
	let transmitted;
	class CaptureTransport extends EventEmitter {
		async start() {}
		async stop() {}
		notify() {}
		async request(method, params) {
			if (method === 'initialize') return {};
			if (method === 'model/list') return { data: [{ id: model, model,
				supportedReasoningEfforts: [{ reasoningEffort }], serviceTiers: [{ id: serviceTier }] }], nextCursor: null };
			assert.equal(method, 'thread/start', 'offline capture must never start a model turn');
			transmitted = structuredClone(params);
			return { thread: { id: 'offline-context-capture' } };
		}
	}
	const service = new CodexService({ cwd: 'offline-context-capture', environment: {} }, {
		transport: new CaptureTransport(), minecraftWorkspace: { prepare: async () => ({
			cwd: 'offline-context-capture', selectedCapabilityRoots: [], instructions, skillInstructions,
		}) },
	});
	try { await service.createAgent({ ...profile, agentId: 'context-experiment' }); }
	finally { await service.stop(); }
	return { profile, context: Object.fromEntries(['baseInstructions', 'developerInstructions', 'dynamicTools'].map(key => [key, transmitted[key]])) };
}

export async function loadQualityCases() {
	return JSON.parse(await readFile(fixtureUrl, 'utf8'));
}

/** Scores observed answers/calls, never model reasoning. Missing, malformed and extra calls fail. */
export function scoreQualityCase(scenario, response) {
	const errors = [];
	if (!response || !Array.isArray(response.calls)) return { passed: false, errors: ['missing calls'] };
	if (response.calls.length !== scenario.rubric.calls.length) errors.push('call count');
	for (const [index, expected] of scenario.rubric.calls.entries()) {
		const actual = response.calls[index];
		if (!actual || actual.name !== expected.name) { errors.push(`call ${index} name`); continue; }
		try { normalizeMinecraftToolCall(actual.name, actual.arguments); }
		catch { errors.push(`call ${index} invalid arguments`); }
		for (const [key, value] of Object.entries(expected.arguments ?? {})) {
			if (!isDeepStrictEqual(actual.arguments?.[key], value)) errors.push(`call ${index} ${key}`);
		}
		if (expected.answer !== undefined) {
			let answer;
			try { answer = JSON.parse(actual.arguments?.message); } catch { /* Incorrect answer below. */ }
			if (!isDeepStrictEqual(answer, expected.answer)) errors.push(`call ${index} answer`);
		}
	}
	return { passed: errors.length === 0, errors };
}

export async function buildContextExperiment() {
	const { profile, context } = await captureCurrentContext();
	const cases = await loadQualityCases();
	const arms = [{ id: 'baseline', context }, ...CONTEXT_CANDIDATES.map(candidate => ({
		id: candidate.id, context: createContextCandidate(context, candidate.id), change: candidate,
	}))];
	const measurements = arms.map(arm => {
		const fields = Object.fromEntries(Object.entries(arm.context).map(([key, value]) => [key, measure(value)]));
		return { id: arm.id, fields, totalComponentBytes: Object.values(fields).reduce((sum, row) => sum + row.bytes, 0) };
	});
	for (const row of measurements) row.bytesRemovedFromBaseline = measurements[0].totalComponentBytes - row.totalComponentBytes;
	const presentedCases = cases.map(({ rubric, ...scenario }) => {
		const views = new ModelObservationViews();
		// Each case presents one fresh full view. Name only its synthetic handle
		// deterministically; retain the production presenter and every fact.
		const digest = sha256(scenario.id).slice(0, 32);
		const session = `${digest.slice(0, 8)}-${digest.slice(8, 12)}-${digest.slice(12, 16)}-${digest.slice(16, 20)}-${digest.slice(20)}`;
		const presentation = presentNativeToolResult(scenario.toolResult, scenario.toolCall, {
			prepare(value, tool) {
				const prepared = views.prepare(value, tool);
				const sample = (tool.kind === 'action' || tool.kind === 'sequence') && prepared.value?.postAction
					? prepared.value.postAction : prepared.value;
				if (sample?.observationView !== undefined) {
					assert.equal(sample.observationView.mode, 'full', 'fresh fixture views must be self-contained');
					sample.observationView.id = `observation-${session}-1`;
				}
				return prepared;
			},
		});
		return { ...scenario, toolResult: presentation.response };
	});
	// Keep answer keys outside provider material. Fresh threads and AB/BA pairs avoid carry-over bias.
	const schedule = CONTEXT_CANDIDATES.flatMap(candidate => cases.flatMap(scenario => [0, 1].map(replicate => ({
		candidate: candidate.id, caseId: scenario.id, replicate,
		armOrder: replicate === 0 ? ['baseline', candidate.id] : [candidate.id, 'baseline'], freshThreadPerArm: true,
	}))));
	const manifest = {};
	for (const relative of ['coordinator/src/codex-service.mjs', 'coordinator/src/native-minecraft-tools.mjs',
		'coordinator/src/model-fact-encoding.mjs', 'coordinator/config/minecraft-agent/AGENTS.md',
		'coordinator/config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md',
		'coordinator/src/benchmark/native-context-experiments.mjs',
		'coordinator/src/benchmark/fixtures/native-context-quality-cases.json']) {
		manifest[relative] = sha256(await readFile(new URL(relative, root)));
	}
	return { version: 1, benchmark: 'native-context-prose-experiments', profile,
		profileSource: 'coordinator/config/dynamic-agents.json:codex.launchProfile', manifest,
		providerUsed: false, paidModelCalls: 0, installedGameplay: false,
		status: 'LIVE_QUALITY_REQUIRED', productionDefaultsChanged: false, tokenizer: null,
		scope: 'Production thread/start instruction and schema fields captured with an injected transport; synthetic tool results use the production presenter with case-derived full-view handles. No provider wire/network or hidden instructions measured.',
		measurements, arms, cases: presentedCases, rubric: cases.map(({ id, rubric }) => ({ id, rubric })), schedule,
		liveGate: ['Use the recorded provider/model/effort/tier unchanged and verify effective settings; disable fallback.',
			'Feed only arm context and case input/tool result, never rubric or another arm answer. Record actual native tool names/arguments and reported usage with session/turn identity.',
			'Run every counterbalanced pair in fresh threads; missing responses, invalid calls or any critical case failure block promotion. Two replicates are a screening check, not statistical equivalence.',
			'Before changing defaults, independently review failures and verify representative installed gameplay goals, danger/death recovery and completion with attributable usage. Passing these fixtures alone cannot approve promotion.'],
		limitations: ['UTF-8 sizes only: no token, cost, latency, model-quality or gameplay benefit inferred.',
			'Prompt/schema prose reductions are semantic hypotheses; they are not lossless encoding.',
			'Existing lossless encoding and on-demand references remain unchanged. Source hashes make reruns comparable; changed source requires new measurements.'] };
}

export function scoreExperiment(bundle, evidence) {
	assert.equal(evidence.bundleSha256, sha256(serialize(bundle)), 'response evidence belongs to a different bundle');
	assert.deepEqual(evidence.profile, bundle.profile, 'response profile differs from baseline');
	assert.ok(Array.isArray(evidence.responses), 'responses must be an array');
	const expected = bundle.schedule.flatMap(pair => pair.armOrder.map(arm => ({ ...pair, arm })));
	const results = expected.map(slot => {
		const matches = evidence.responses.filter(row => ['candidate', 'caseId', 'replicate', 'arm'].every(key => row[key] === slot[key]));
		const scenario = bundle.rubric.find(row => row.id === slot.caseId);
		return { candidate: slot.candidate, caseId: slot.caseId, replicate: slot.replicate, arm: slot.arm,
			...(matches.length === 1 ? scoreQualityCase(scenario, matches[0]) : { passed: false, errors: ['missing or duplicate response'] }) };
	});
	const complete = evidence.responses.length === expected.length && results.every(row => row.passed);
	return { status: complete ? 'RUBRIC_PASSED_LIVE_REVIEW_REQUIRED' : 'RUBRIC_FAILED', results,
		promotionApproved: false, source: 'Unverified supplied answer/tool-call records; not provider or installed-gameplay attestation.' };
}

async function main(args) {
	const options = {};
	for (let index = 0; index < args.length; index += 2) {
		assert.ok(['--output', '--responses', '--bundle'].includes(args[index]) && args[index + 1], 'usage: --output directory [--responses evidence.json --bundle bundle.json]');
		options[args[index].slice(2)] = args[index + 1];
	}
	assert.ok(options.output, '--output directory is required');
	assert.equal(Boolean(options.responses), Boolean(options.bundle), '--responses and --bundle must be supplied together');
	await mkdir(options.output, { recursive: true });
	if (options.responses) {
		const bundle = JSON.parse(await readFile(options.bundle, 'utf8'));
		const report = scoreExperiment(bundle, JSON.parse(await readFile(options.responses, 'utf8')));
		await writeFile(path.join(options.output, 'quality-score.json'), `${JSON.stringify(report, null, 2)}\n`);
		console.log(JSON.stringify({ status: report.status, promotionApproved: false }));
		return;
	}
	const bundle = await buildContextExperiment();
	await writeFile(path.join(options.output, 'experiment-bundle.json'), `${JSON.stringify(bundle, null, 2)}\n`);
	const { arms, cases, rubric, schedule, ...report } = bundle;
	Object.assign(report, { bundleSha256: sha256(serialize(bundle)), caseCount: cases.length, pairedRuns: schedule.length });
	await writeFile(path.join(options.output, 'measurements.json'), `${JSON.stringify(report, null, 2)}\n`);
	console.log(JSON.stringify({ status: report.status, profile: report.profile, measurements: report.measurements }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main(process.argv.slice(2));
