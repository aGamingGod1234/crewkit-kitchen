import { createHash } from 'node:crypto';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';

const RUNS = 5;
const WARMUP_EVALUATIONS = 25;
const EVALUATIONS = 250;
const moduleRoot = process.argv[2] === undefined
	? new URL('../../', import.meta.url)
	: pathToFileURL(`${resolve(process.argv[2])}${sep}`);
const targetLabel = process.argv[3] ?? 'working-tree';
const [{ ArenaScriptInterpreter }, { parseArenaScript }, { createInterpreterFacts }] = await Promise.all([
	import(new URL('src/arena-script/interpreter.mjs', moduleRoot)),
	import(new URL('src/arena-script/parser.mjs', moduleRoot)),
	import(new URL('src/arena-script/facts.mjs', moduleRoot)),
]);

const bindings = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
	})),
}));

function candidate(index, kind = 'entity') {
	const common = {
		stableId: `candidate-${index}`,
		x: index,
		y: 64,
		z: -index,
		distance: index,
		tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'],
	};
	return kind === 'block' ? { ...common, blockId: 'minecraft:stone' } : { ...common, type: 'minecraft:zombie' };
}

const observation = {
	player: { x: 0, y: 64, z: 0, health: 20, food: 20, yaw: 0, pitch: 0, onGround: true, name: 'benchmark-agent' },
	items: [],
	entities: Array.from({ length: 64 }, (_unused, index) => candidate(index)),
	blocks: Array.from({ length: 128 }, (_unused, index) => candidate(index + 64, 'block')),
	inventory: { items: [], tagCounts: {} },
};
const facts = createInterpreterFacts(observation);

function programWithWatchers(watcherCount) {
	const watchers = Array.from({ length: watcherCount }, (_unused, index) =>
		`program.watch(() => player.state().health < ${index + 1}, { mode: "boundary" }, async () => { await player.wait(1); });`).join('\n');
	return parseArenaScript(`program.onUnhandledAttention("continue_and_notify");\n${watchers}\nawait player.wait(1);`);
}

function run(program, watcherCount) {
	const interpreter = new ArenaScriptInterpreter(program, bindings);
	interpreter.start(facts);
	for (let iteration = 0; iteration < WARMUP_EVALUATIONS; iteration += 1) {
		for (let index = 0; index < watcherCount; index += 1) interpreter.evaluateWatcher(`watcher-${index}`, facts);
	}
	const startedAt = performance.now();
	for (let iteration = 0; iteration < EVALUATIONS; iteration += 1) {
		for (let index = 0; index < watcherCount; index += 1) interpreter.evaluateWatcher(`watcher-${index}`, facts);
	}
	return performance.now() - startedAt;
}

function median(values) {
	const ordered = values.toSorted((left, right) => left - right);
	return ordered[Math.floor(ordered.length / 2)];
}

const cells = [1, 4, 16].map((watcherCount) => {
	const program = programWithWatchers(watcherCount);
	run(program, watcherCount);
	const samples = Array.from({ length: RUNS }, () => run(program, watcherCount));
	const medianMs = median(samples);
	const watcherEvaluations = EVALUATIONS * watcherCount;
	return {
		watcherCount,
		medianMs: Number(medianMs.toFixed(3)),
		watcherEvaluations,
		watcherEvaluationsPerSecond: Math.round(watcherEvaluations * 1_000 / medianMs),
	};
});
console.log(JSON.stringify({
	benchmark: 'arena-script-canonicalization',
	mode: 'indicative_non_gating_microbenchmark',
	scope: 'trusted production facts evaluated across watcher counts',
	target: targetLabel,
	node: process.version,
	runs: RUNS,
	warmupEvaluations: WARMUP_EVALUATIONS,
	evaluationsPerRun: EVALUATIONS,
	inputSha256: createHash('sha256').update(JSON.stringify(observation)).digest('hex'),
	cells,
}, null, 2));
