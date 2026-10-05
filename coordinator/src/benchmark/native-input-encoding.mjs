import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../../../', import.meta.url));
const sha256 = value => createHash('sha256').update(value).digest('hex');
const jsonValue = value => JSON.parse(JSON.stringify(value));
const bytes = value => Buffer.byteLength(value, 'utf8');
const rounded = value => Number(value.toFixed(3));
const reduction = (before, after) => before === 0 ? 0 : rounded((before - after) / before * 100);

/** Fixed fact fixtures, not a policy or a simulated model. Missing values deliberately stay missing. */
export function semanticFixtures() {
	const blocks = Array.from({ length: 32 }, (_, index) => ({
		stableId: `block:${index},63,0`, x: index, y: 63, z: 0, blockId: 'minecraft:stone',
		tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'], state: {},
		bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }],
	}));
	const player = { x: -0.125, y: 64, z: 0.5, health: 20, food: 18, dead: false, onGround: true };
	const world = { worldId: 'fixture-world', dimension: 'minecraft:overworld' };
	return [
		{ name: 'movement_coordinates_and_collision', value: { player, world, blocks,
			coverage: { blocks: { complete: true, radius: 16 }, entities: { complete: false, reason: 'out_of_range' } } } },
		{ name: 'urgent_damage_and_hostile_identity', value: { player: { ...player, health: 4 }, world,
			attention: { priority: 'urgent', trigger: 'damage', changedFacts: ['player.health', 'entities'] },
			entities: Array.from({ length: 12 }, (_, index) => ({ stableId: `hostile-${index}`,
				uuid: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
				type: 'minecraft:zombie', hostile: true, x: index + .5, y: 64, z: .5, alive: true })) } },
		{ name: 'death_recovery_and_retained_route', value: { player: { ...player, health: 0, dead: true }, world,
			inventory: { items: [], tagCounts: {} }, death: { x: 18.125, y: -45, z: -8.5, dimension: 'minecraft:overworld',
				cause: 'minecraft:zombie', lostItems: [{ itemId: 'minecraft:iron_pickaxe', count: 1, damage: 103 }] },
			taskMemory: { routes: [{ key: 'mine-access', from: 'camp', to: 'ore-face', status: 'active',
				waypoints: Array.from({ length: 24 }, (_, index) => ({ x: index + .5, y: 64 - index, z: .5 })) }] } } },
		{ name: 'dimension_identity_is_not_coordinates', value: { player, world: { ...world, dimension: 'minecraft:the_nether' },
			previousWorld: world, blocks, inventory: { items: [{ slot: 0, itemId: 'minecraft:cobblestone', count: 64 }] } } },
		{ name: 'unknown_stale_null_and_false_are_distinct', value: { ready: false, stale: true,
			freshness: { available: false, reasonCode: 'FRESH_OBSERVATION_UNAVAILABLE' },
			player: { health: null, dead: false }, blocks: [], entities: null,
			coverage: { blocks: { complete: false, reason: 'unknown' } },
			rows: [{ known: null }, { known: false }, {}, { known: 0 }, { known: '' }, { known: [] }] } },
		{ name: 'action_receipt_and_nested_observation', value: { state: 'FAILED', reasonCode: 'NO_STANDABLE_PATH',
			actionId: 'fixture-action', goalRevision: 7, executionStarted: true, physicalAttempted: true,
			observation: { player, world, blocks }, arguments: { x: 8.5, y: 64, z: .5, tolerance: .35, sprint: false } } },
		{ name: 'mixed_records_and_reserved_looking_keys', value: { rows: [
			{ columns: ['x'], rows: [[1]], dictionary: ['minecraft:stone'], $table: 1, x: 1 },
			{ x: 2, name: '__proto__', constructor: { safe: true }, unicode: 'stone 石 🪨' },
			JSON.parse('{"__proto__":{"factual":true},"prototype":"retained","constructor":"ordinary field"}'),
			{ x: 3, deep: { rows: [[null, false, 0, '']], type: 'object' } }, null, ['x'], 'minecraft:stone',
		] } },
	];
}

export function rowShapeFixtures() {
	const fixtures = [];
	for (const count of [4, 8, 16, 32, 64]) for (const columns of [2, 4, 8, 16]) {
		for (const style of ['numbers', 'flags', 'short_ids', 'mixed']) {
			const value = { rows: Array.from({ length: count }, (_, index) => Object.fromEntries(
				Array.from({ length: columns }, (_, column) => [`field${column}`,
					style === 'numbers' ? index * columns + column : style === 'flags' ? column % 2 === 0
						: style === 'short_ids' ? 'minecraft:stone' : { known: column % 2 === 0, count: index, itemId: 'minecraft:stone' }]),
			)) };
			fixtures.push({ index: fixtures.length, tool: `${count}x${columns}-${style}`, value });
		}
	}
	return fixtures;
}

function normalizeReplies(fixture) {
	const rows = Array.isArray(fixture) ? fixture : fixture.responses ?? fixture.toolResponses ?? fixture.payloads ?? fixture.entries;
	if (!Array.isArray(rows)) throw new TypeError('replay fixture must contain an array of tool responses');
	return rows.map((row, index) => {
		const value = Object.hasOwn(row, 'response') ? row.response
			: Object.hasOwn(row, 'value') ? row.value
				: Object.hasOwn(row, 'result') ? row.result : row;
		const parsed = typeof value === 'string' ? JSON.parse(value) : jsonValue(value);
		const tool = row.toolName ?? row.tool ?? row.name ?? factShape(parsed);
		return { tool: typeof tool === 'string' ? tool : 'unknown', index, value: parsed };
	});
}

function factShape(value) {
	if (value === null || typeof value !== 'object') return 'other';
	if ((Object.hasOwn(value, 'goal') && Object.hasOwn(value, 'player')) || (Object.hasOwn(value, 'eventSequence') && Object.hasOwn(value, 'goalSpec'))) return 'observation';
	if (Object.hasOwn(value, 'reference') && Object.hasOwn(value, 'version')) return 'capabilities';
	if (typeof value.section === 'string') return `inspection:${value.section}`;
	if (Object.hasOwn(value, 'programId')) return 'program';
	if (Object.hasOwn(value, 'actionId') || Object.hasOwn(value, 'physicalAttempted')) return 'action_receipt';
	if (Array.isArray(value.results)) return 'sequence';
	if (Object.hasOwn(value, 'entry') || Object.hasOwn(value, 'entries')) return 'memory_or_inspection';
	if (Object.hasOwn(value, 'steps') || Object.hasOwn(value, 'plan')) return 'plan';
	return 'other';
}

/** Compares JSON facts, never a model's output. Raw response text is excluded from the report. */
export function analyzeEncoding({ replies, encode, decode }) {
	const measured = [];
	for (const { tool, index, value } of replies) {
		const original = jsonValue(value);
		const originalSnapshot = JSON.stringify(original);
		const encoded = encode(original);
		assert.equal(JSON.stringify(original), originalSnapshot, `encoder input mutation at ${index}`);
		const after = JSON.stringify(encoded);
		assert.deepEqual(decode(encoded), original, `fact reconstruction at ${index}`);
		assert.deepEqual(decode(JSON.parse(after)), original, `serialized fact reconstruction at ${index}`);
		measured.push({ tool, index, before: originalSnapshot, after, beforeBytes: bytes(originalSnapshot),
			afterBytes: bytes(after), changed: originalSnapshot !== after });
	}
	return measured;
}

export function summarizeMeasurements(measured, tokenCounts = null) {
	const tools = new Map();
	const total = { replies: measured.length, changedReplies: 0, exactReconstructions: measured.length,
		beforeBytes: 0, afterBytes: 0 };
	if (tokenCounts !== null) {
		assert.equal(tokenCounts.length, measured.length * 2);
		total.beforeProxyTokens = 0;
		total.afterProxyTokens = 0;
		total.proxyTokenRegressionReplies = 0;
		total.proxyTokenRegressionTokens = 0;
	}
	for (const [index, row] of measured.entries()) {
		let tool = tools.get(row.tool);
		if (tool === undefined) {
			tool = { ...Object.fromEntries(Object.keys(total).map(key => [key, 0])) };
			tools.set(row.tool, tool);
		}
		tool.replies += 1;
		tool.exactReconstructions += 1;
		if (row.changed) { total.changedReplies += 1; tool.changedReplies += 1; }
		for (const [key, amount] of [['beforeBytes', row.beforeBytes], ['afterBytes', row.afterBytes]]) {
			total[key] += amount; tool[key] += amount;
		}
		if (tokenCounts !== null) {
			for (const [key, amount] of [['beforeProxyTokens', tokenCounts[index * 2]], ['afterProxyTokens', tokenCounts[index * 2 + 1]]]) {
				assert.ok(Number.isSafeInteger(amount) && amount >= 0);
				total[key] += amount; tool[key] += amount;
			}
			const extra = tokenCounts[index * 2 + 1] - tokenCounts[index * 2];
			if (extra > 0) {
				total.proxyTokenRegressionReplies += 1; tool.proxyTokenRegressionReplies += 1;
				total.proxyTokenRegressionTokens += extra; tool.proxyTokenRegressionTokens += extra;
			}
		}
	}
	for (const row of [total, ...tools.values()]) {
		row.byteReductionPercent = reduction(row.beforeBytes, row.afterBytes);
		if (tokenCounts !== null) row.proxyTokenReductionPercent = reduction(row.beforeProxyTokens, row.afterProxyTokens);
	}
	return { total, byTool: Object.fromEntries([...tools].sort(([left], [right]) => left.localeCompare(right))) };
}

/** Stateful view is measured separately from default self-contained encoding. */
export function analyzeObservationChanges({ replies, ModelObservationViews, encode, decode }) {
	const views = new ModelObservationViews();
	const raw = [], compact = [];
	let baselineId = null, baselineObservation = null, baselineMetadata = null, fullReplies = 0, changeReplies = 0;
	for (const { index, value } of replies) {
		if (!Object.hasOwn(value, 'goalSpec') || value.observation === null || typeof value.observation !== 'object') continue;
		const original = jsonValue(value);
		const prepared = views.prepare(original, { kind: 'observe', view: 'changes', afterObservationId: baselineId });
		const presented = jsonValue(prepared.value);
		const view = presented.observationView;
		assert.ok(view, 'observe view metadata');
		let reconstructedObservation;
		if (view.mode === 'full') {
			fullReplies += 1;
			reconstructedObservation = structuredClone(presented.observation);
		} else {
			changeReplies += 1;
			assert.equal(view.mode, 'changes');
			assert.equal(view.baseId, baselineId);
			assert.ok(baselineObservation !== null);
			reconstructedObservation = structuredClone(baselineObservation);
			for (const name of view.remove) delete reconstructedObservation[name];
			for (const [name, replacement] of Object.entries(view.replace)) {
				Object.defineProperty(reconstructedObservation, name, { value: replacement, enumerable: true, configurable: true, writable: true });
			}
		}
		const { observationView: _view, ...metadata } = presented;
		for (const name of view.retainMetadata ?? []) {
			assert.ok(baselineMetadata !== null && Object.hasOwn(baselineMetadata, name), `retained metadata ${name} exists in exact baseline`);
			metadata[name] = structuredClone(baselineMetadata[name]);
		}
		assert.deepEqual({ ...metadata, observation: reconstructedObservation }, original, `optional changes reconstruction at ${index}`);
		const encoded = encode(presented);
		assert.deepEqual(decode(encoded), presented, `optional changes encoding at ${index}`);
		const add = (target, beforeValue, afterValue) => {
			const before = JSON.stringify(beforeValue), after = JSON.stringify(afterValue);
			target.push({ index, tool: 'observe', before, after, beforeBytes: bytes(before), afterBytes: bytes(after), changed: before !== after });
		};
		add(raw, original, presented);
		add(compact, encode(original), encoded);
		prepared.commit();
		baselineId = view.id;
		baselineObservation = reconstructedObservation;
		baselineMetadata = metadata;
	}
	return { raw, compact, fullReplies, changeReplies };
}

/** Uses captured facts with the production builder; these are not captured provider event strings. */
export function analyzeNativeEventInputs({ replies, buildNativeEventInput, encodeNativeEventInput, decode }) {
	const measured = [];
	const record = { currentGoal: 'Obtain diamond pickaxe', goalRevision: 1,
		currentGoalSpec: { originalRequest: 'Obtain diamond pickaxe', type: 'inventory', itemId: 'minecraft:diamond_pickaxe', count: 1 } };
	const suffix = '\nRetry instruction: recheck a failed action against fresh facts; choose the next action yourself.';
	for (const { index, value } of replies) {
		const row = value !== null && typeof value === 'object' ? value : {};
		for (const [source, observation] of [['observation', row.observation], ['postAction.observation', row.postAction?.observation]]) {
			if (observation === null || typeof observation !== 'object' || Array.isArray(observation)) continue;
			const before = buildNativeEventInput(record, { event: 'program_attention', trigger: 'benchmark_fresh_facts',
				observation: structuredClone(observation), eventSequence: observation.eventSequence }) + suffix;
			const after = encodeNativeEventInput(before);
			const split = text => {
				const separator = text.indexOf('\n'), end = text.indexOf('\n', separator + 1);
				assert.ok(separator >= 0 && end >= 0);
				return { heading: text.slice(0, separator + 1), value: JSON.parse(text.slice(separator + 1, end)), suffix: text.slice(end) };
			};
			const original = split(before), presented = split(after);
			assert.equal(presented.heading, original.heading, `native event heading at ${index}`);
			assert.equal(presented.suffix, original.suffix, `native event retry text at ${index}`);
			assert.deepEqual(decode(presented.value), original.value, `native event facts at ${index}`);
			measured.push({ tool: source, index, before, after, beforeBytes: bytes(before), afterBytes: bytes(after), changed: before !== after });
		}
	}
	return measured;
}

function measureEncoderCpu(replies, encode, decode, iterations = 20) {
	const run = () => {
		let encodeMs = 0, decodeMs = 0;
		for (const { value } of replies) {
			let at = performance.now();
			const encoded = encode(value);
			encodeMs += performance.now() - at;
			at = performance.now();
			decode(encoded);
			decodeMs += performance.now() - at;
		}
		return { encodeMs, decodeMs };
	};
	for (let index = 0; index < 3; index += 1) run();
	const samples = Array.from({ length: iterations }, run);
	const median = key => samples.map(row => row[key]).sort((left, right) => left - right)[Math.floor(samples.length / 2)];
	return { scope: 'in-process encoder and decoder only; no model, server, network, or rendering',
		iterations, repliesPerIteration: replies.length, encodeMedianBatchMs: rounded(median('encodeMs')),
		decodeMedianBatchMs: rounded(median('decodeMs')),
		encodeMedianPerReplyMs: rounded(median('encodeMs') / Math.max(1, replies.length)) };
}

/** Optional offline tokenizer. o200k_base is explicitly a proxy, not this model's verified tokenizer. */
export function proxyTokenCounts(strings, { python = 'python', pythonModulePath = null, cacheDirectory = null } = {}) {
	const script = [
		'import json, sys',
		'import tiktoken',
		'encoding = tiktoken.get_encoding("o200k_base")',
		'values = json.load(sys.stdin)',
		'print(json.dumps({"encoding":"o200k_base","counts":[len(encoding.encode(value, disallowed_special=())) for value in values]}))',
	].join('\n');
	const env = { ...process.env };
	if (pythonModulePath !== null) env.PYTHONPATH = path.resolve(pythonModulePath);
	if (cacheDirectory !== null) env.TIKTOKEN_CACHE_DIR = path.resolve(cacheDirectory);
	const result = spawnSync(python, ['-c', script], { input: JSON.stringify(strings), encoding: 'utf8', env,
		maxBuffer: 1024 * 1024, timeout: 120_000, windowsHide: true });
	if (result.error || result.status !== 0) throw new Error(`offline tokenizer unavailable: ${result.error?.message ?? result.stderr.trim()}`);
	const parsed = JSON.parse(result.stdout);
	assert.equal(parsed.encoding, 'o200k_base');
	assert.equal(parsed.counts.length, strings.length);
	return parsed.counts;
}

async function contextParts(value) {
	if (value === null || value === undefined) return null;
	if (typeof value.nativeInstructions === 'string') {
		const { nativeInstructions } = await import('../codex-service.mjs');
		const composed = nativeInstructions(value.minecraftInstructions ?? '', value.skillInstructions ?? '', value.nativeInstructions);
		return { baseInstructions: composed,
			dynamicTools: JSON.stringify(value.tools) };
	}
	const request = value.threadStart ?? value.params ?? value;
	const parts = {};
	for (const key of ['baseInstructions', 'developerInstructions', 'dynamicTools']) {
		if (request[key] !== undefined) parts[key] = typeof request[key] === 'string' ? request[key] : JSON.stringify(request[key]);
	}
	if (Object.keys(parts).length === 0) throw new TypeError('context fixture must contain exact thread/start instruction or tool fields');
	return parts;
}

export async function runBenchmark({ inputPath, beforeContextPath = null, afterContextPath = null,
	encode = null, decode = null, tokenizer = null } = {}) {
	const defaultEncoder = encode === null || decode === null;
	if (encode === null || decode === null) {
		const module = await import('../model-fact-encoding.mjs');
		encode ??= module.encodeModelFacts;
		decode ??= module.decodeModelFacts;
	}
	const inputText = await readFile(inputPath, 'utf8');
	const replies = normalizeReplies(JSON.parse(inputText));
	const measured = analyzeEncoding({ replies, encode, decode });
	const beforeContext = beforeContextPath === null ? null : await contextParts(JSON.parse(await readFile(beforeContextPath, 'utf8')));
	const afterContext = afterContextPath === null ? null : await contextParts(JSON.parse(await readFile(afterContextPath, 'utf8')));
	const tokenStrings = measured.flatMap(row => [row.before, row.after]);
	const staticRows = [];
	if (beforeContext !== null && afterContext !== null) {
		for (const name of new Set([...Object.keys(beforeContext), ...Object.keys(afterContext)])) {
			const before = beforeContext[name] ?? '';
			const after = afterContext[name] ?? '';
			staticRows.push({ name, beforeBytes: bytes(before), afterBytes: bytes(after),
				byteReductionPercent: reduction(bytes(before), bytes(after)),
				beforeSha256: sha256(before), afterSha256: sha256(after), tokenOffset: tokenStrings.length });
			tokenStrings.push(before, after);
		}
	}
	const shapes = analyzeEncoding({ replies: rowShapeFixtures(), encode, decode });
	const shapeTokenOffset = tokenStrings.length;
	tokenStrings.push(...shapes.flatMap(row => [row.before, row.after]));
	const { buildNativeEventInput } = await import('../dynamic-main.mjs');
	const { encodeNativeEventInput } = await import('../model-fact-encoding.mjs');
	const eventInputs = analyzeNativeEventInputs({ replies, buildNativeEventInput, encodeNativeEventInput, decode });
	const eventTokenOffset = tokenStrings.length;
	tokenStrings.push(...eventInputs.flatMap(row => [row.before, row.after]));
	const counts = tokenizer === null ? null : proxyTokenCounts(tokenStrings, tokenizer);
	if (counts !== null) {
		for (const row of staticRows) {
			row.beforeProxyTokens = counts[row.tokenOffset];
			row.afterProxyTokens = counts[row.tokenOffset + 1];
			row.proxyTokenReductionPercent = reduction(row.beforeProxyTokens, row.afterProxyTokens);
		}
	}
	for (const row of staticRows) delete row.tokenOffset;
	const staticTotal = staticRows.length === 0 ? null : {
		scope: 'sum of measured static components; hidden provider instructions and message envelopes excluded',
		beforeBytes: staticRows.reduce((total, row) => total + row.beforeBytes, 0),
		afterBytes: staticRows.reduce((total, row) => total + row.afterBytes, 0),
	};
	if (staticTotal !== null) {
		staticTotal.byteReductionPercent = reduction(staticTotal.beforeBytes, staticTotal.afterBytes);
		if (counts !== null) {
			staticTotal.beforeProxyTokens = staticRows.reduce((total, row) => total + row.beforeProxyTokens, 0);
			staticTotal.afterProxyTokens = staticRows.reduce((total, row) => total + row.afterProxyTokens, 0);
			staticTotal.proxyTokenReductionPercent = reduction(staticTotal.beforeProxyTokens, staticTotal.afterProxyTokens);
		}
	}
	const { ModelObservationViews } = await import('../model-fact-encoding.mjs');
	const changes = analyzeObservationChanges({ replies, ModelObservationViews, encode, decode });
	const changeTokenStrings = [...changes.raw, ...changes.compact].flatMap(row => [row.before, row.after]);
	const changeTokenCounts = tokenizer === null ? null : proxyTokenCounts(changeTokenStrings, tokenizer);
	const synthetic = analyzeEncoding({ replies: semanticFixtures().map(({ name, value }, index) => ({ tool: name, value, index })), encode, decode });
	return {
		benchmark: 'native-input-lossless-fact-encoding', generatedAt: new Date().toISOString(), node: process.version,
		providerUsed: false, paidModelCalls: 0, minecraftServerUsed: false, installedClientVerified: false,
		scope: 'offline replay of captured dynamic-tool responses plus adversarial JSON fact fixtures',
		inputSha256: sha256(inputText),
		manifest: { benchmarkSha256: sha256(await readFile(fileURLToPath(import.meta.url))),
			encoderSha256: defaultEncoder ? sha256(await readFile(new URL('../model-fact-encoding.mjs', import.meta.url))) : null,
			nativeEventBuilderSourceSha256: sha256(await readFile(new URL('../dynamic-main.mjs', import.meta.url))) },
		tokenizer: counts === null ? null : { encoding: 'o200k_base', kind: 'offline proxy',
			verifiedModelTokenizer: false, includesMessageEnvelope: false },
		replay: summarizeMeasurements(measured, counts === null ? null : counts.slice(0, measured.length * 2)),
		rowShapeChecks: summarizeMeasurements(shapes, counts?.slice(shapeTokenOffset, eventTokenOffset) ?? null).total,
		nativeEventInputs: { scope: 'production-builder replay with captured observation facts and a neutral task; not captured actual event texts',
			originalProducer: 'buildNativeEventInput', presentation: 'encodeNativeEventInput',
			headingAndRetryTextUnchanged: true,
			...summarizeMeasurements(eventInputs, counts?.slice(eventTokenOffset) ?? null) },
		optionalObservationChanges: { scope: 'observe only; explicitly requested exact-baseline changes, measured separately',
			fullReplies: changes.fullReplies, changeReplies: changes.changeReplies,
			rawBeforeVsRawChanges: summarizeMeasurements(changes.raw, changeTokenCounts?.slice(0, changes.raw.length * 2) ?? null).total,
			defaultCompactBeforeVsCompactChanges: summarizeMeasurements(changes.compact, changeTokenCounts?.slice(changes.raw.length * 2) ?? null).total },
		semanticChecks: synthetic.map(row => ({ name: row.tool, result: 'PASSED', beforeBytes: row.beforeBytes, afterBytes: row.afterBytes })),
		staticContext: staticRows,
		staticContextTotal: staticTotal,
		encoderCpu: measureEncoderCpu(replies, encode, decode),
		limitations: [
			'Exact JSON reconstruction proves retained facts; it does not prove model comprehension or unchanged gameplay decisions.',
			'Token counts use o200k_base as a proxy; actual Codex tokenizer, cached usage, subscription cost and latency were not measured.',
			'Static context and per-tool payload savings are separate; their percentages must not be added.',
			'Replay categories without recorded tool names are inferred from response shape, not a tool-call execution trace.',
			'Native event inputs are generated by the current production builder from reused captured facts; they are not captured actual provider event strings. Existing producer bounds are unchanged.',
			'No raw tool payloads, private reasoning, credentials or device paths are included in this report.',
		],
	};
}

export function renderMarkdown(report) {
	const total = report.replay.total;
	const lines = ['# Native input encoding benchmark', '',
		'Offline replay of the same captured tool results. No paid model requests or live game changes.', '',
		'| Metric | Before | After | Reduction |', '|---|---:|---:|---:|',
		`| Serialized tool-result bytes | ${total.beforeBytes} | ${total.afterBytes} | ${total.byteReductionPercent}% |`];
	if (total.beforeProxyTokens !== undefined) lines.push(`| o200k_base proxy tokens | ${total.beforeProxyTokens} | ${total.afterProxyTokens} | ${total.proxyTokenReductionPercent}% |`);
	lines.push('', `${total.exactReconstructions}/${total.replies} tool responses reconstruct exactly. ${total.changedReplies} used compact encoding.${total.proxyTokenRegressionReplies === undefined ? '' : ` ${total.proxyTokenRegressionReplies} replies increased proxy token counts.`}`, '',
		'| Tool | Replies | Before bytes | After bytes | Reduction |', '|---|---:|---:|---:|---:|');
	for (const [name, row] of Object.entries(report.replay.byTool)) lines.push(`| ${name} | ${row.replies} | ${row.beforeBytes} | ${row.afterBytes} | ${row.byteReductionPercent}% |`);
	const events = report.nativeEventInputs.total;
	lines.push('', 'Production native event input is measured separately using captured observation facts and a neutral diamond-pickaxe task. These are generated event strings, not the actual logged provider inputs. Existing producer bounds apply equally before and after.', '',
		'| Metric | Before | After | Reduction |', '|---|---:|---:|---:|',
		`| Native event bytes | ${events.beforeBytes} | ${events.afterBytes} | ${events.byteReductionPercent}% |`);
	if (events.beforeProxyTokens !== undefined) lines.push(`| Native event o200k_base proxy tokens | ${events.beforeProxyTokens} | ${events.afterProxyTokens} | ${events.proxyTokenReductionPercent}% |`);
	lines.push('', `${events.exactReconstructions}/${events.replies} generated event JSON values reconstruct exactly; every original imperative heading and appended retry instruction remains byte-for-byte unchanged.`);
	if (report.staticContext.length > 0) {
		lines.push('', 'Static thread-start material is measured separately:', '', '| Field | Before bytes | After bytes | Byte reduction | Before proxy tokens | After proxy tokens | Proxy reduction |', '|---|---:|---:|---:|---:|---:|---:|');
		for (const row of [...report.staticContext, { name: 'Combined measured components', ...report.staticContextTotal }]) {
			lines.push(`| ${row.name} | ${row.beforeBytes} | ${row.afterBytes} | ${row.byteReductionPercent}% | ${row.beforeProxyTokens ?? 'not measured'} | ${row.afterProxyTokens ?? 'not measured'} | ${row.proxyTokenReductionPercent === undefined ? 'not measured' : `${row.proxyTokenReductionPercent}%`} |`);
		}
		lines.push('', 'Combined static counts sum separately tokenized components. They exclude hidden provider instructions and message envelopes. Static-prefix reduction does not establish the savings for a growing live thread.');
	}
	const changes = report.optionalObservationChanges;
	lines.push('', 'Optional exact-baseline observation changes are separate from the default self-contained format:', '',
		'| Compare | Before bytes | After bytes | Reduction |', '|---|---:|---:|---:|');
	for (const [name, row] of [['Raw observations vs raw changes', changes.rawBeforeVsRawChanges], ['Default compact observations vs compact changes', changes.defaultCompactBeforeVsCompactChanges]]) {
		lines.push(`| ${name} | ${row.beforeBytes} | ${row.afterBytes} | ${row.byteReductionPercent}% |`);
	}
	lines.push('', `Complete snapshots: ${changes.fullReplies}; changes views: ${changes.changeReplies}. Each reconstructs the original observation exactly. These savings are not added to the default-format percentages.`);
	lines.push('', `${report.semanticChecks.length} adversarial fixtures passed: movement precision and collision, urgent damage, death recovery and retained routes, dimension identity, unknown/stale values, action receipts, and mixed or reserved-looking records.`, '',
		`${report.rowShapeChecks.exactReconstructions}/${report.rowShapeChecks.replies} further row-shape fixtures reconstruct exactly (varied row count, column count, numbers, flags, short Minecraft identifiers and nested facts).`, '',
		`Encoder CPU median: ${report.encoderCpu.encodeMedianBatchMs} ms for all ${report.encoderCpu.repliesPerIteration} replies (${report.encoderCpu.encodeMedianPerReplyMs} ms per reply). This is an in-process microbenchmark.`, '',
		'Limits:', '', ...report.limitations.map(value => `- ${value}`), '',
		`Input replay SHA-256: ${report.inputSha256}`, '');
	return lines.join('\n');
}

async function main(args) {
	const options = {};
	for (let index = 0; index < args.length; index += 2) {
		if (!args[index].startsWith('--') || args[index + 1] === undefined) throw new TypeError('arguments must be --name value pairs');
		options[args[index].slice(2)] = args[index + 1];
	}
	const defaultRoot = path.join(repositoryRoot, 'runtime/input-usage-audit');
	const report = await runBenchmark({ inputPath: options.input ?? path.join(defaultRoot, 'tool-responses.json'),
		beforeContextPath: options.before ?? null, afterContextPath: options.after ?? null,
		tokenizer: options['python-module-path'] === undefined ? null : { pythonModulePath: options['python-module-path'],
			cacheDirectory: path.join(defaultRoot, 'tiktoken-cache') } });
	const destination = options.output ?? path.join(repositoryRoot, 'reports/native-input-encoding-2026-10-02.json');
	await mkdir(path.dirname(destination), { recursive: true });
	await writeFile(destination, `${JSON.stringify(report, null, 2)}\n`);
	await writeFile(options.markdown ?? destination.replace(/\.json$/i, '.md'), renderMarkdown(report));
	console.log(JSON.stringify({ report: path.basename(destination), replay: report.replay.total,
		semanticCases: report.semanticChecks.length, staticContext: report.staticContext,
		staticContextTotal: report.staticContextTotal, optionalObservationChanges: report.optionalObservationChanges,
		nativeEventInputs: report.nativeEventInputs.total,
		limitations: report.limitations }, null, 2));
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
	await main(process.argv.slice(2));
}
