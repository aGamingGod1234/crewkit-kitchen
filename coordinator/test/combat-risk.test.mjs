import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { DangerSteerCoalescer } from '../src/danger-steer-coalescer.mjs';
import { AWAITING_CONFIRMATION_EVENT_INSTRUCTION, buildNativeEventInput, classifyObservationTrigger } from '../src/dynamic-main.mjs';
import { MINECRAFT_DYNAMIC_TOOLS, NATIVE_AGENT_INSTRUCTIONS, toolResultContent } from '../src/native-minecraft-tools.mjs';
import { AWAITING_CONFIRMATION_MESSAGE } from '../src/native-tool-runtime.mjs';
import { adaptObservation, survivalFacts, threatFacts } from '../src/observation-adapter.mjs';
import { PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';
import { validateAction } from '../src/schema.mjs';

const ZOMBIE = '00000000-0000-0000-0000-0000000000aa';
const SKELETON = '00000000-0000-0000-0000-0000000000ab';
const PLAYER = '00000000-0000-0000-0000-0000000000b2';

const row = (uuid, type, distance, risk, signals) => ({ uuid, type, distance, bearing: 0, targeting: signals.includes('targeting'), swelling: false,
	lineOfSight: true, signals, risk, riskFactors: { proximity: 0.5, health: 1, speed: 1, size: 1, damage: 1, behaviour: 1.25 }, expectedHitDamage: 3 });

test('threats are ordered by open-ended risk and expose the highest-risk threat', () => {
	const facts = threatFacts({ entries: [row(ZOMBIE, 'minecraft:zombie', 2, 56, ['targeting']), row(PLAYER, 'minecraft:player', 5, 1840, ['attacked']),
		row(SKELETON, 'minecraft:skeleton', 9, 40, ['ranged_sight'])] });
	assert.deepEqual(facts.threats.map((threat) => threat.uuid), [PLAYER, ZOMBIE, SKELETON], 'highest risk first, not nearest');
	assert.equal(facts.highestRiskThreat.uuid, PLAYER);
	assert.equal(facts.highestRiskThreat.risk, 1840, 'risk is not capped at 100');
	assert.equal(facts.threats[0].riskFactors.behaviour, 1.25);
	assert.equal(facts.threat.uuid, PLAYER, 'a player who attacked is an urgent threat signal');
});

test('entity rows keep potential and active risk for watcher conditions', () => {
	const adapted = adaptObservation({
		ready: true, position: { x: 0, y: 64, z: 0 }, view: { yaw: 0, pitch: 0 }, player: { health: 12, maxHealth: 20 },
		entities: [{ uuid: PLAYER, type: 'minecraft:player', name: 'Steve', distance: 4, position: { x: 4, y: 64, z: 0 }, hostile: false, potentialRisk: 92, expectedHitDamage: 5 },
			{ uuid: ZOMBIE, type: 'minecraft:zombie', name: 'Zombie', distance: 3, position: { x: 3, y: 64, z: 0 }, hostile: true, potentialRisk: 56, risk: 56, expectedHitDamage: 3 }],
		blocks: [], inventory: { items: [] },
		survival: { safe: true, canHealNow: true, bestFood: { slot: 2, itemId: 'minecraft:cooked_beef', nutrition: 8 }, signals: ['heal_opportunity'] },
	});
	const [player, zombie] = adapted.entities;
	assert.equal(player.potentialRisk, 92);
	assert.equal(player.risk, undefined, 'an armed player who never attacked shows potential risk only');
	assert.equal(zombie.risk, 56);
	assert.equal(adapted.player.canHealNow, true);
	assert.deepEqual(adapted.player.bestFood, { slot: 2, itemId: 'minecraft:cooked_beef', nutrition: 8 });
	assert.equal(adapted.player.healOpportunity, true);
	assert.equal(adapted.player.lowHealthNoFood, false);
	assert.deepEqual(survivalFacts(undefined), { canHealNow: false, bestFood: null, healOpportunity: false, lowHealthNoFood: false });
});

test('healing signals are attention: low health without food is urgent, a safe chance to eat is ordinary', () => {
	assert.deepEqual(classifyObservationTrigger({ changedFacts: ['survival.low_health_no_food'] }, {}),
		{ attention: true, priority: 'urgent', trigger: 'low_health' });
	assert.deepEqual(classifyObservationTrigger({ changedFacts: ['survival.heal_opportunity'] }, {}),
		{ attention: true, priority: 'ordinary', trigger: 'heal_opportunity' });
	assert.equal(classifyObservationTrigger({ changedFacts: ['player.health', 'survival.low_health_no_food'] }, { player: { health: 9 } },
		{ previousPlayer: { health: 12 } }).trigger, 'damage', 'a hit in the same sample stays damage');
	assert.equal(classifyObservationTrigger({ changedFacts: [`threats.${PLAYER}.attacked`] }, {}).trigger, 'threat');
});

test('danger steering is delivered again when health crosses 70%, not first at five hearts', () => {
	const coalescer = new DangerSteerCoalescer({ intervalMs: 2_000 });
	const steer = (health) => ({ trigger: 'damage', observation: { player: { health, lastAttacker: { type: 'minecraft:zombie' } } } });
	assert.equal(coalescer.offer(steer(18), 0).action, 'deliver');
	assert.equal(coalescer.offer(steer(16), 100).action, 'fold');
	assert.equal(coalescer.offer(steer(13), 200).action, 'deliver', 'crossing 14 HP is materially new');
});

test('fight_target accepts a model-chosen targetPolicy everywhere it is authored', () => {
	assert.doesNotThrow(() => validateAction({ type: 'fight_target', targetId: ZOMBIE, targetPolicy: 'highest_risk', timeoutMs: 15000 }));
	assert.doesNotThrow(() => validateAction({ type: 'fight_target', targetId: ZOMBIE, targetPolicy: 'nearest_attacker', timeoutMs: 15000 }));
	assert.throws(() => validateAction({ type: 'fight_target', targetId: ZOMBIE, targetPolicy: 'auto', timeoutMs: 15000 }), /targetPolicy/);
	parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.fightTarget({ targetId: "${ZOMBIE}", targetPolicy: "highest_risk", timeoutMs: 15000 });`);
	const act = MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'act').description;
	assert.match(act, /targetPolicy: named \(default\), highest_risk or nearest_attacker/);
	assert.match(act, /replaceAction with a new fight_target retargets/);
	assert.match(PLANNER_SYSTEM_PROMPT, /targetPolicy:"highest_risk"\|"nearest_attacker"/);
	assert.match(PLANNER_SYSTEM_PROMPT, /Eat when \.canHealNow below 70% HP, not at 4-5 hearts; \.lowHealthNoFood: flee\./);
	assert.match(PLANNER_SYSTEM_PROMPT, /players threaten \(attacked\) only after hurting you/);
	assert.match(PLANNER_SYSTEM_PROMPT, /risk is uncapped/);
	assert.doesNotMatch(PLANNER_SYSTEM_PROMPT, /Low health: flee, eat\./, 'the vague low-health rule is replaced by a concrete threshold');
});

test('native guidance stays under the cap and says when to heal and that confirmation never blocks requests', () => {
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500, `native instructions are ${NATIVE_AGENT_INSTRUCTIONS.length} characters`);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /Eat when safe below 70% health; no food under threat: flee\./);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'act').description, /replaceAction with a new fight_target retargets/);
	assert.match(AWAITING_CONFIRMATION_MESSAGE, /never blocks new requests/);
	assert.match(AWAITING_CONFIRMATION_MESSAGE, /even more equipment changes/);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'finish').description, /Waiting never blocks new player requests/);
	const record = { goalRevision: 1, currentGoal: 'Equip armour; operator verifies.' };
	const waiting = buildNativeEventInput(record, { event: 'conversation', trigger: 'conversation', awaitingConfirmation: true });
	assert.equal(waiting.slice(0, waiting.indexOf('\n')), AWAITING_CONFIRMATION_EVENT_INSTRUCTION);
	assert.match(AWAITING_CONFIRMATION_EVENT_INSTRUCTION, /act on player messages now with any tool/);
	const working = buildNativeEventInput(record, { event: 'conversation', trigger: 'conversation' });
	assert.match(working.slice(0, working.indexOf('\n')), /^Live Minecraft event\. Advance the current goal/);
});

test('fight_target includePlayers is an explicit model opt-in, documented with the policy', () => {
	assert.doesNotThrow(() => validateAction({ type: 'fight_target', targetId: ZOMBIE, includePlayers: true, timeoutMs: 15000 }));
	assert.throws(() => validateAction({ type: 'fight_target', targetId: ZOMBIE, includePlayers: 'yes', timeoutMs: 15000 }), /includePlayers/);
	parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await player.fightTarget({ targetId: "${ZOMBIE}", includePlayers: false, timeoutMs: 15000 });`);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'act').description, /skip players unless includePlayers:true/);
	assert.match(PLANNER_SYSTEM_PROMPT, /players only if includePlayers:true/);
});

const riskyEntity = (index) => ({ uuid: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`, stableId: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
	type: 'minecraft:zombie', name: 'Zombie', distance: 3 + index, x: index, y: 64, z: 0, hostile: true, alive: true,
	potentialRisk: 45 + index, risk: 56 + index, expectedHitDamage: 3, equipment: Array.from({ length: 40 }, (_, slot) => ({ slot: `slot-${slot}`, itemId: 'minecraft:stone', enchanted: false, note: 'x'.repeat(200) })) });

test('entity risk fields survive event and tool-result compaction', () => {
	const entities = Array.from({ length: 12 }, (_, index) => riskyEntity(index));
	const input = buildNativeEventInput({ goalRevision: 1, currentGoal: 'Survive.' }, { event: 'observation', trigger: 'threat',
		observation: { player: { health: 12 }, entities, inventory: { items: [] }, items: [], blocks: [] } });
	const event = JSON.parse(input.slice(input.indexOf('\n') + 1));
	const row = event.observation.entities.find((entity) => entity.risk !== undefined);
	assert.ok(row, 'an engaging entity keeps its risk in the event');
	assert.equal(typeof row.potentialRisk, 'number');
	assert.equal(row.expectedHitDamage, 3);
	assert.equal(row.equipment, undefined, 'the heavy detail was compacted away, so compaction ran');
	const tool = JSON.parse(toolResultContent({ state: 'SUCCEEDED', observation: { player: { health: 12 }, entities, inventory: { items: [] }, blocks: [] } }).contentItems[0].text);
	const compacted = tool.observation.entities[0];
	assert.equal(compacted.risk, 56);
	assert.equal(compacted.potentialRisk, 45);
	assert.equal(compacted.expectedHitDamage, 3);
	assert.equal(compacted.equipment, undefined, 'the tool result went through row compaction');
});
