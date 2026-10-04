import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { CodexStdioTransport, listCodexModels, verifyModelProfile } from '../codex-app-server.mjs';
import { SharedCodexAgent, nativeInstructions } from '../codex-service.mjs';
import { minecraftCapabilities } from '../native-minecraft-tools.mjs';
import { decodeModelFacts, MODEL_FACT_FORMAT } from '../model-fact-encoding.mjs';

const repositoryRoot = fileURLToPath(new URL('../../../', import.meta.url));
const settings = Object.freeze({ model: 'gpt-6.1-sol', reasoningEffort: 'medium', serviceTier: 'fast' });
const hash = value => createHash('sha256').update(value).digest('hex');
const limits = Object.freeze({ maxTurns: 4, maxUncachedInputTokens: 60_000, maxToolCallsPerTurn: 5, turnDeadlineMs: 90_000 });

export function comprehensionCases() {
	const blocks = Array.from({ length: 32 }, (_, index) => ({ stableId: `block-${index}`, x: index, y: -54, z: -23,
		blockId: index === 9 ? 'minecraft:deepslate_diamond_ore' : 'minecraft:deepslate',
		tags: ['minecraft:mineable/pickaxe', 'minecraft:needs_iron_tool'], state: {},
		bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }] }));
	const inventory = { items: [
		{ slot: 0, itemId: 'minecraft:iron_pickaxe', count: 1 }, { slot: 1, itemId: 'minecraft:diamond', count: 2 },
		{ slot: 2, itemId: 'minecraft:cobblestone', count: 64 }, { slot: 3, itemId: 'minecraft:diamond', count: 3 },
		{ slot: 4, itemId: 'minecraft:bread', count: 6 }, { slot: 5, itemId: 'minecraft:stick', count: 4 },
	], tagCounts: {} };
	const first = { ready: true, world: { worldId: 'bench', dimension: 'minecraft:overworld' },
		player: { x: 8.5, y: -53, z: -22.5, health: 20, dead: false }, blocks, inventory,
		items: [], entities: [], coverage: { blocks: { complete: true }, inventory: { complete: true } } };
	const hostileUuid = '12345678-1234-4123-8123-123456789abc';
	const second = { ready: true, world: { worldId: 'bench', dimension: 'minecraft:overworld', destinationDimension: null },
		player: { x: 1.5, y: 64, z: 1.5, health: 4, dead: false }, blocks, inventory,
		entities: Array.from({ length: 16 }, (_, index) => ({ stableId: `hostile-${index}`,
			uuid: index === 7 ? hostileUuid : `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
			type: index === 7 ? 'minecraft:zombie' : 'minecraft:skeleton', hostile: true, x: index, y: 64, z: 2 })),
		coverage: { blocks: { complete: null }, entities: { complete: false } },
		attention: { priority: 'urgent', trigger: 'damage' },
		recovery: { previousDeath: { dimension: 'minecraft:the_nether', x: 17.25, y: 45, z: -6, cause: 'minecraft:zombie' } } };
	return [
		{ name: 'precise_mining_target_and_inventory', observation: first,
			question: 'Report blockId,x,y,z for stableId block-9. Report dc as the total minecraft:diamond count and pc as minecraft:diamond_pickaxe count in the complete inventory. JSON keys: blockId,x,y,z,dc,pc.',
			expected: { blockId: 'minecraft:deepslate_diamond_ore', x: 9, y: -54, z: -23, dc: 5, pc: 0 } },
		{ name: 'urgent_health_identity_death_and_unknowns', observation: second,
			question: 'JSON keys: h=player.health,d=player.dead,u=uuid of hostile-7,t=its type,w=world.worldId,dim=world.dimension,ld=recovery.previousDeath.dimension,lx=its x,bc=coverage.blocks.complete,ec=coverage.entities.complete,air=whether player has an air field. Preserve null and false distinctly.',
			expected: { h: 4, d: false, u: hostileUuid, t: 'minecraft:zombie', w: 'bench', dim: 'minecraft:overworld',
				ld: 'minecraft:the_nether', lx: 17.25, bc: null, ec: false, air: false } },
	];
}

class ComparisonTransport extends CodexStdioTransport {
	arm = 'after';
	before;
	onPresented = () => {};
	constructor(config, before) { super(config); this.before = before; }
	request(method, params, options) {
		if (method === 'thread/start' && this.arm === 'before') {
			params = { ...params,
				baseInstructions: nativeInstructions(this.before.minecraftInstructions, this.before.skillInstructions, this.before.nativeInstructions),
				dynamicTools: this.before.tools };
		}
		return super.request(method, params, options);
	}
	respond(id, response) {
		if (Array.isArray(response?.contentItems)) {
			response = { ...response, contentItems: response.contentItems.map(item => {
				if (item.type !== 'inputText') return item;
				let value;
				try { value = JSON.parse(item.text); } catch { return item; }
				if (this.arm === 'before') {
					value = decodeModelFacts(value);
					if (value?.observationView) { const { observationView: _view, ...original } = value; value = original; }
				}
				this.onPresented({ packed: value?.format === MODEL_FACT_FORMAT, bytes: Buffer.byteLength(JSON.stringify(value)) });
				return { ...item, text: JSON.stringify(value) };
			}) };
		}
		return super.respond(id, response);
	}
}

function usageValue(value) {
	return { inputTokens: value?.inputTokens ?? 0, cachedInputTokens: value?.cachedInputTokens ?? 0,
		outputTokens: value?.outputTokens ?? 0 };
}
function deltaUsage(before, after) {
	const output = Object.fromEntries(Object.keys(before).map(key => [key, Math.max(0, after[key] - before[key])]));
	output.uncachedInputTokens = Math.max(0, output.inputTokens - output.cachedInputTokens);
	return output;
}

export async function runComprehensionComparison({
	beforeContextPath = path.join(repositoryRoot, 'runtime/input-usage-audit/before-context.json'),
	afterContextPath = path.join(repositoryRoot, 'runtime/input-usage-audit/after-context.json'),
} = {}) {
	let before, after;
	try {
		[before, after] = await Promise.all([beforeContextPath, afterContextPath].map(async inputPath => {
			const context = JSON.parse(await readFile(inputPath, 'utf8'));
			assert.ok(['minecraftInstructions', 'skillInstructions', 'nativeInstructions'].every(key => typeof context[key] === 'string')
				&& Array.isArray(context.tools) && context.tools.length > 0, 'invalid archived context');
			return context;
		}));
	} catch (error) {
		// Archived inputs are optional local artifacts, not tracked prerequisites. Fail before provider startup.
		return { benchmark: 'native-input-model-comprehension', status: 'UNAVAILABLE', settings, limits,
			providerUsed: false, paidApiCalls: 0, installedGameplay: false, subscriptionVerified: false,
			failure: 'CONTEXT_INPUT_UNAVAILABLE', cause: error.code ?? error.name, turns: [], totalUsage: {}, byArm: {},
			limitations: ['Supply valid archived contexts using --before and --after, each containing minecraftInstructions, skillInstructions, nativeInstructions and tools.',
				'For reproducible current-source prose experiments, run native-context-experiments.mjs --output <directory>. No model comprehension has been measured.'] };
	}
	const directory = await mkdtemp(path.join(os.tmpdir(), 'arena-native-fact-check-'));
	const env = Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.toUpperCase().startsWith('OPENAI_')));
	const transport = new ComparisonTransport({ ...settings, cwd: directory, environment: env }, before);
	const agents = new Map(), usage = new Map(), rawNames = new Map();
	let controller = null, limitExceeded = false, budgetExceeded = false;
	const totalUncached = () => [...usage.values()].reduce((sum, row) => sum + Math.max(0, row.inputTokens - row.cachedInputTokens), 0);
	const report = { benchmark: 'native-input-model-comprehension', generatedAt: new Date().toISOString(), settings, limits,
		billing: 'existing ChatGPT subscription required; inherited OpenAI API environment omitted; account checked before any model turn',
		subscriptionVerified: false,
		paidApiCalls: 0, installedGameplay: false, status: 'UNAVAILABLE', turns: [],
		prefixSha256: { before: hash(nativeInstructions(before.minecraftInstructions, before.skillInstructions, before.nativeInstructions)),
			after: hash(nativeInstructions(after.minecraftInstructions, after.skillInstructions, after.nativeInstructions)) },
		limitations: ['Two factual scenarios, one before/after response each; this is a comprehension check, not a statistical behavior or speed benchmark.',
			'Only a fake read-only observation and captured say answers are used. No Minecraft commands execute.',
			'Elapsed times include provider scheduling and tool round trips. Raw reasoning is not retained.',
			'Actual reported tokens are shown separately from offline proxy counts; subscription allowance cost is not inferred.'] };
	transport.on('notification', ({ method, params }) => {
		if (method === 'thread/tokenUsage/updated') {
			usage.set(params.threadId, usageValue(params.tokenUsage?.total));
			if (totalUncached() > limits.maxUncachedInputTokens) { budgetExceeded = true; controller?.abort(); }
		}
		if (method === 'account/rateLimits/updated' && [params.rateLimits?.primary, params.rateLimits?.secondary].some(row => row?.usedPercent >= 100)) {
			limitExceeded = true; controller?.abort();
		}
	});
	transport.on('serverRequest', request => {
		if (request.method === 'item/tool/call') rawNames.set(request.params.callId, request.params.tool);
	});
	try {
		await transport.start();
		await transport.request('initialize', { clientInfo: { name: 'arena-native-fact-comprehension', title: 'Offline fact check', version: '1.0.0' },
			capabilities: { experimentalApi: true, requestAttestation: false } });
		transport.notify('initialized', {});
		const account = await transport.request('account/read', { refreshToken: false });
		if (account.account?.type !== 'chatgpt') throw Object.assign(new Error('ChatGPT subscription authentication unavailable'), { code: 'SUBSCRIPTION_AUTH_UNAVAILABLE' });
		report.subscriptionVerified = true;
		verifyModelProfile(await listCodexModels(transport), settings);
		for (const arm of ['before', 'after']) {
			transport.arm = arm;
			const response = await transport.request('thread/start', { model: settings.model, serviceTier: settings.serviceTier,
				cwd: directory, runtimeWorkspaceRoots: [directory], selectedCapabilityRoots: [], allowProviderModelFallback: false,
				approvalPolicy: 'never', sandbox: 'read-only', environments: [], ephemeral: true,
				baseInstructions: nativeInstructions(after.minecraftInstructions, after.skillInstructions, after.nativeInstructions), dynamicTools: after.tools,
				developerInstructions: 'This body is a text-only read-only fact check. Only observe, capabilities and say are permitted. No game, filesystem or external actions exist. Follow the fact question and end the turn after say.' });
			const threadId = response.thread?.id;
			assert.equal(typeof threadId, 'string');
			const agent = new SharedCodexAgent({ ...settings, agentId: `fact-check-${arm}`, provider: 'codex' }, threadId, transport,
				{ controlProtocol: 'native_tools', planningTimeoutMs: 90_000, reportedSettings: response });
			await agent.setGoalRevision(1);
			agents.set(arm, { agent, threadId });
		}
		const cases = comprehensionCases();
		for (const [index, arm] of [[0, 'before'], [0, 'after'], [1, 'after'], [1, 'before']]) {
			if (budgetExceeded || limitExceeded || totalUncached() >= limits.maxUncachedInputTokens) break;
			const scenario = cases[index], { agent, threadId } = agents.get(arm), calls = [], answers = [], presented = [];
			const previousUsage = usageValue(usage.get(threadId));
			transport.arm = arm; transport.onPresented = value => presented.push(value);
			controller = new AbortController();
			const timer = setTimeout(() => controller.abort(), limits.turnDeadlineMs);
			const at = performance.now();
			let result = null, failure = null;
			try {
				result = await agent.act(`Conversation-only factual check. Call observe exactly once, then say one compact JSON object (max 256 characters) answering the question and end this turn. No other tool calls, prose, reasoning traces, memory changes or game actions. ${scenario.question}`,
					{ goalRevision: 1, signal: controller.signal,
						executeTool: async request => {
							const name = rawNames.get(request.callId); calls.push(name);
							if (calls.length > limits.maxToolCallsPerTurn || !['observe', 'say', 'capabilities'].includes(name)) {
								controller.abort(); throw new Error('FACT_CHECK_TOOL_REJECTED');
							}
							if (name === 'observe') return { eventSequence: index + 1, goalSpec: null, observation: structuredClone(scenario.observation), freshness: { fresh: true } };
							if (name === 'capabilities') return minecraftCapabilities(request.tool);
							answers.push(JSON.parse(request.tool.arguments.message));
							return { state: 'SUCCEEDED', reasonCode: 'FACT_ANSWER_RECEIVED' };
						} });
			} catch (error) { failure = error.code ?? error.name; }
			finally { clearTimeout(timer); controller = null; }
			let factsMatch = false;
			try { assert.deepEqual(answers, [scenario.expected]); factsMatch = true; } catch { /* Report a factual failure without another model attempt. */ }
			const nativeCallsValid = calls.filter(name => name === 'observe').length === 1 && calls.filter(name => name === 'say').length === 1
				&& calls.every(name => ['observe', 'say', 'capabilities'].includes(name));
			const row = { scenario: scenario.name, arm, status: result && factsMatch && nativeCallsValid ? 'PASSED' : 'FAILED',
				factsMatch, nativeCallsValid, calls, answers, elapsedMs: Number((performance.now() - at).toFixed(3)),
				usage: deltaUsage(previousUsage, usageValue(usage.get(threadId))), presentedReplies: presented,
				executionSettings: agent.executionSettings, ...(failure === null ? {} : { failure }) };
			report.turns.push(row);
			process.stdout.write(`${JSON.stringify({ scenario: row.scenario, arm, status: row.status, usage: row.usage, elapsedMs: row.elapsedMs })}\n`);
			if (row.status !== 'PASSED' || budgetExceeded || limitExceeded) break;
		}
		report.status = budgetExceeded ? 'BUDGET_STOP' : limitExceeded ? 'USAGE_LIMIT_STOP'
			: report.turns.length === limits.maxTurns && report.turns.every(row => row.status === 'PASSED') ? 'PASSED' : 'INCOMPLETE';
	} catch (error) { report.failure = error.code ?? error.name; }
	finally {
		for (const { agent } of agents.values()) { try { await agent.dispose(); } catch { /* Transport stop owns remaining cleanup. */ } }
		await transport.stop();
		assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
		assert.ok(path.basename(directory).startsWith('arena-native-fact-check-'));
		await rm(directory, { recursive: true, force: true });
	}
	report.totalUsage = report.turns.reduce((total, row) => {
		for (const [key, value] of Object.entries(row.usage)) total[key] = (total[key] ?? 0) + value;
		return total;
	}, {});
	report.byArm = summarizeArms(report.turns);
	return report;
}

export function summarizeArms(turns) {
	const totals = {};
	for (const row of turns) {
		const total = totals[row.arm] ??= { turns: 0, factualPasses: 0, nativeCallPasses: 0,
			inputTokens: 0, cachedInputTokens: 0, uncachedInputTokens: 0, outputTokens: 0 };
		total.turns += 1;
		if (row.factsMatch) total.factualPasses += 1;
		if (row.nativeCallsValid) total.nativeCallPasses += 1;
		for (const key of ['inputTokens', 'cachedInputTokens', 'uncachedInputTokens', 'outputTokens']) total[key] += row.usage[key];
	}
	return totals;
}

export function renderComprehensionMarkdown(report) {
	const lines = ['# Native input model comprehension check', '',
		`Status: ${report.status}. Requested GPT-6.1 Sol, medium effort, Fast tier. Existing ChatGPT subscription; no API key billing.`, '',
		'Two controlled factual questions, each tested once before and after. Every turn used the actual native tool collector with a fake read-only observe/say executor. The before arm used frozen old instructions/tools and decoded plain replies; the after arm used the production compact formatter.', '',
		'| Scenario | Arm | Facts correct | Native calls valid | Input | Cached | Uncached | Output | Elapsed ms |',
		'|---|---|---|---|---:|---:|---:|---:|---:|'];
	for (const row of report.turns) lines.push(`| ${row.scenario} | ${row.arm} | ${row.factsMatch} | ${row.nativeCallsValid} | ${row.usage.inputTokens} | ${row.usage.cachedInputTokens} | ${row.usage.uncachedInputTokens} | ${row.usage.outputTokens} | ${row.elapsedMs} |`);
	lines.push('', 'Reported token totals for these two scenarios only:', '',
		'| Arm | Input | Cached | Uncached | Output |', '|---|---:|---:|---:|---:|');
	for (const [arm, row] of Object.entries(report.byArm ?? summarizeArms(report.turns))) lines.push(`| ${arm} | ${row.inputTokens} | ${row.cachedInputTokens} | ${row.uncachedInputTokens} | ${row.outputTokens} |`);
	const afterTurns = report.turns.filter(row => row.arm === 'after');
	const packedReplies = afterTurns.filter(row => row.presentedReplies.some(reply => reply.packed)).length;
	lines.push('', `The checks cover literal block identifiers, target coordinates, item counts, hostile UUID/type, health, current death flag, world/dimension identity, previous-death coordinates, missing fields, and null versus false. Compact production minecraft-facts-v1 observations reached the model in ${packedReplies}/${afterTurns.length} after turns. Exact factual passes: ${report.turns.filter(row => row.factsMatch).length}/${report.turns.length}.`, '',
		`Total uncached input: ${report.totalUsage.uncachedInputTokens ?? 0}; cap: ${report.limits.maxUncachedInputTokens}. Turns: ${report.turns.length}; cap: ${report.limits.maxTurns}.`, '',
		...(report.turns.length === 0 ? ['No model turns ran; provider/model confirmation and comprehension are unavailable.', '']
			: ['Effective execution settings are recorded per turn. Submitted effort is not independently confirmed unless echoed by the provider.', '']),
		'Limits:', '', ...report.limitations.map(value => `- ${value}`), '',
		'No statistical latency improvement, unchanged long-task behavior, weekly allowance savings, or installed-gameplay success is claimed from these four turns.', '');
	return lines.join('\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	const options = {};
	for (let index = 2; index < process.argv.length; index += 2) {
		assert.ok(['--before', '--after', '--output'].includes(process.argv[index]) && process.argv[index + 1], 'usage: [--before context.json --after context.json --output report.json]');
		options[process.argv[index].slice(2)] = process.argv[index + 1];
	}
	const result = await runComprehensionComparison({ beforeContextPath: options.before, afterContextPath: options.after });
	const destination = options.output ?? path.join(repositoryRoot, 'reports/native-input-comprehension-2026-10-02.json');
	assert.ok(destination.endsWith('.json'), '--output must end in .json');
	await mkdir(path.dirname(destination), { recursive: true });
	await writeFile(destination, `${JSON.stringify(result, null, 2)}\n`);
	await writeFile(destination.replace(/\.json$/, '.md'), renderComprehensionMarkdown(result));
	process.stdout.write(`${JSON.stringify({ status: result.status, turns: result.turns.length, totalUsage: result.totalUsage, failure: result.failure })}\n`);
}
