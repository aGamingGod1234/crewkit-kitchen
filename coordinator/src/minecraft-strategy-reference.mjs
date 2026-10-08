import { readFileSync } from 'node:fs';

// Optional progression knowledge, read on demand through capabilities section
// strategy so it never grows the always-loaded instructions. It informs the
// model's choices; it never selects a route or issues gameplay.
const STRATEGY_GUIDE = readFileSync(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/strategy-guide.md', import.meta.url), 'utf8');

const TOPIC_TITLES = Object.freeze({
	resources: 'Getting ores and resources',
	structures: 'Structures and loot',
	water: 'Water and swimming',
	survival: 'Survival basics',
	'beat-the-game': 'Beating the game',
	nether: 'The Nether',
	end: 'The End',
});

const TOPICS = new Map();
const headings = [...STRATEGY_GUIDE.matchAll(/^## ([^\r\n]+)\r?$/gm)].map((match) => ({ title: match[1], offset: match.index }));
for (const [id, title] of Object.entries(TOPIC_TITLES)) {
	const index = headings.findIndex((heading) => heading.title === title);
	if (index < 0) throw new Error(`Missing Minecraft strategy guide heading: ${title}`);
	TOPICS.set(id, { title, text: STRATEGY_GUIDE.slice(headings[index].offset, headings[index + 1]?.offset ?? STRATEGY_GUIDE.length).trimEnd() });
}

export const STRATEGY_REFERENCE_TOPICS = Object.freeze([...TOPICS.keys()]);

export function minecraftStrategyReference({ topic } = {}) {
	if (topic === undefined) return {
		version: 1, section: 'strategy',
		detail: 'Optional Minecraft progression knowledge as option menus. You choose whether and how to use it; it is not an observation of this world. Queries perform no gameplay.',
		topics: [...TOPICS].map(([id, entry]) => ({ id, title: entry.title })),
	};
	const entry = TOPICS.get(topic);
	if (entry === undefined) throw new TypeError(`Unknown Minecraft strategy topic: ${topic}`);
	return { version: 1, section: 'strategy', topic, title: entry.title, reference: entry.text };
}

const HINT_RULES = [
	['resources', /\b(iron|diamonds?|ores?|coal|copper|gold|redstone|lapis|emeralds?|ancient debris|netherite|mine|mining|strip)\b/i],
	['structures', /\b(iron|diamonds?|loot|chests?|village|shipwreck|treasure|temple|mineshaft|armou?r)\b/i],
	['water', /\b(water|swim|swimming|ocean|river|flooded|underwater|drown)\b/i],
	['nether', /\b(nether|portal|obsidian|blaze|fortress|bastion|piglins?|pearls?)\b/i],
	['end', /\b(dragon|stronghold|eyes? of ender|ender eyes?|end portal|the end)\b/i],
	['beat-the-game', /\b(beat the game|ender dragon|kill the dragon|dragon)\b/i],
];

/**
 * Topic pointers for the current goal and unfinished plan steps. Only names the
 * relevant on-demand topics; the model decides whether to read them.
 */
export function strategyHints({ goal = null, plan = null } = {}) {
	const texts = [typeof goal === 'string' ? goal : goal?.text ?? goal?.originalRequest ?? ''];
	for (const step of Array.isArray(plan?.steps) ? plan.steps : []) {
		if (step?.status === 'complete') continue;
		texts.push([step?.label, step?.detail].filter((value) => typeof value === 'string').join(' '));
	}
	const text = texts.join('\n');
	const topics = HINT_RULES.filter(([, pattern]) => pattern.test(text)).map(([topic]) => topic);
	if (topics.length === 0) return null;
	return { topics, read: { tool: 'capabilities', arguments: { section: 'strategy', topic: topics[0] } }, advice: STRATEGY_ADVICE[topics[0]] ?? `Read capabilities strategy:${topics[0]} before this step.` };
}

// A bare topic list was easy to skip: say what the read is for, in one line.
const STRATEGY_ADVICE = Object.freeze({
	resources: 'Read capabilities strategy:resources before mining: visible caves, structures and whole ore veins usually beat strip mining.',
	structures: 'Read capabilities strategy:structures: looting a visible structure usually beats mining the same materials.',
});
