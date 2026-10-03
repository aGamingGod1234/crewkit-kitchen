import { readFileSync } from 'node:fs';

// The full prior skill stays byte-for-byte available; only the always-loaded
// pointer/behavioral core is shorter. Topics expose reference, never gameplay.
const CONTROL_REFERENCE = readFileSync(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/references/control-reference.md', import.meta.url), 'utf8');
const TOPICS = new Map([['all', { title: 'Complete Minecraft control reference', text: CONTROL_REFERENCE }]]);
const headings = [...CONTROL_REFERENCE.matchAll(/^(#{1,3}) ([^\r\n]+)\r?$/gm)]
	.map((match) => ({ level: match[1].length, title: match[2], offset: match.index }));

const overviewEnd = headingOffset('Tool examples');
const actionsStart = headingOffset('Advanced action reference');
const validationStart = headingOffset('Dependent calls and rejected inputs');
TOPICS.set('overview', { title: 'Control workflow and behavioral rules', text: CONTROL_REFERENCE.slice(0, overviewEnd) });
TOPICS.set('tools', { title: 'All native tool contracts and examples', text: CONTROL_REFERENCE.slice(overviewEnd, actionsStart) });
TOPICS.set('actions', { title: 'All action contracts and examples', text: CONTROL_REFERENCE.slice(actionsStart, validationStart) });
TOPICS.set('validation', { title: 'Dependent calls and rejected inputs', text: CONTROL_REFERENCE.slice(validationStart) });

for (const [id, title] of Object.entries({ plan: 'Live advisory plan', workflow: 'Read facts, choose, act, verify', observations: 'Observations and memory', preparation: 'Trip preparation', travel: 'Cave travel', survival: 'Survival and recovery', precision: 'Input ownership and precision', interaction: 'Interaction details' })) {
	const heading = headings.find((entry) => entry.title === title);
	if (heading === undefined) throw new Error(`Missing Minecraft control reference heading: ${title}`);
	const end = headings.find((entry) => entry.offset > heading.offset && entry.level <= heading.level)?.offset ?? CONTROL_REFERENCE.length;
	TOPICS.set(id, { title, text: CONTROL_REFERENCE.slice(heading.offset, end) });
}
for (const [prefix, start, end] of [['tool', overviewEnd, actionsStart], ['action', actionsStart, validationStart]]) {
	const entries = headings.filter((heading) => heading.level === 3 && heading.offset > start && heading.offset < end);
	for (let index = 0; index < entries.length; index += 1) {
		const heading = entries[index];
		TOPICS.set(`${prefix}:${heading.title}`, { title: heading.title, text: CONTROL_REFERENCE.slice(heading.offset, entries[index + 1]?.offset ?? end) });
	}
}
TOPICS.set('tool:taskPlan', TOPICS.get('plan'));

export const CONTROL_REFERENCE_TOPICS = Object.freeze([...TOPICS.keys()]);

export function minecraftControlReference({ topic, offset = 0 } = {}) {
	if (topic === undefined) return {
		version: 1, section: 'control',
		detail: 'Read the relevant topic before unfamiliar calls. Topics are unchanged control contracts and examples; queries perform no gameplay. Follow nextOffset to read a whole topic.',
		topics: [...TOPICS].map(([id, reference]) => ({ id, title: reference.title, characters: Array.from(reference.text).length })),
		completeReference: { tool: 'capabilities', arguments: { section: 'control', topic: 'all', offset: 0 } },
	};
	const entry = TOPICS.get(topic);
	if (entry === undefined) throw new TypeError(`Unknown Minecraft control reference topic: ${topic}`);
	if (!Number.isSafeInteger(offset) || offset < 0) throw new TypeError('Control reference offset must be a nonnegative safe integer');
	const characters = Array.from(entry.text);
	if (offset > characters.length) throw new TypeError('Control reference offset exceeds this topic');
	// Unicode code-point offsets reconstruct the original exactly. Bound the
	// complete JSON result, including escaping, below the existing 16 KiB limit.
	let count = Math.min(characters.length - offset, 12_000);
	for (;;) {
		const nextOffset = offset + count;
		const result = {
			version: 1, section: 'control', topic, title: entry.title, offset,
			reference: characters.slice(offset, nextOffset).join(''),
			coverage: { totalCharacters: characters.length, returnedCharacters: count, complete: nextOffset === characters.length },
			...(nextOffset < characters.length ? { nextOffset } : {}),
		};
		if (Buffer.byteLength(JSON.stringify(result), 'utf8') <= 15_000) return result;
		count = Math.floor(count * 0.9);
	}
}

function headingOffset(title) {
	const heading = headings.find((entry) => entry.title === title);
	if (heading === undefined) throw new Error(`Missing Minecraft control reference heading: ${title}`);
	return heading.offset;
}
