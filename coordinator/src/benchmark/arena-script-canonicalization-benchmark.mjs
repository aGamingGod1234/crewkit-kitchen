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
const [{ ArenaScriptInterpreter }, { parseArenaScript }] = await Promise.all([
	import(new URL('src/arena-script/interpreter.mjs', moduleRoot)),
	import(new URL('src/arena-script/parser.mjs', moduleRoot)),
]);

const source = `
	program.onUnhandledAttention("continue_and_notify");
	program.watch(() => player.state().health < 5, { mode: "boundary" }, async () => { await player.wait(1); });
	await player.wait(1);
`;
const program = parseArenaScript(source);
const bindings = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
	})),
}));

function candidate(index) {
	return {
		id: `candidate-${index}`,
		kind: 'entity',
		x: index,
		y: 64,
		z: -index,
		distance: index,
		blockId: 'minecraft:stone',
		tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'],
	};
}

const facts = {
	player: { x: 0, y: 64, z: 0, health: 20, food: 20, yaw: 0, pitch: 0, onGround: true, name: 'benchmark-agent' },
	world: {
		items: [],
		entities: Array.from({ length: 64 }, (_unused, index) => candidate(index)),
		blocks: Array.from({ length: 128 }, (_unused, index) => candidate(index + 64)),
	},
	inventory: { items: [], tagCounts: {} },
};

function run() {
	const interpreter = new ArenaScriptInterpreter(program, bindings);
	interpreter.start(facts);
	for (let index = 0; index < WARMUP_EVALUATIONS; index += 1) interpreter.evaluateWatcher('watcher-0', facts);
	const startedAt = performance.now();
	for (let index = 0; index < EVALUATIONS; index += 1) interpreter.evaluateWatcher('watcher-0', facts);
	return performance.now() - startedAt;
}

function median(values) {
	const ordered = values.toSorted((left, right) => left - right);
	return ordered[Math.floor(ordered.length / 2)];
}

run();
const samples = Array.from({ length: RUNS }, run);
const medianMs = median(samples);
console.log(JSON.stringify({
	benchmark: 'arena-script-canonicalization',
	mode: 'indicative_non_gating_microbenchmark',
	scope: 'production fact canonicalization plus one watcher evaluation',
	target: targetLabel,
	node: process.version,
	runs: RUNS,
	warmupEvaluations: WARMUP_EVALUATIONS,
	evaluationsPerRun: EVALUATIONS,
	inputSha256: createHash('sha256').update(JSON.stringify(facts)).digest('hex'),
	medianMs: Number(medianMs.toFixed(3)),
	evaluationsPerSecond: Math.round(EVALUATIONS * 1_000 / medianMs),
}, null, 2));
