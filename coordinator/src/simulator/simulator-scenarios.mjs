const scenarioList = [
	{
		id: 'stone-tool-gathering',
		title: 'Gather stone and craft tools',
		actions: ['navigate_to', 'break_block', 'craft_inventory'],
		success: (state) => hasItems(state, ['minecraft:cobblestone', 'minecraft:stone_pickaxe']),
	},
	{
		id: 'obstacle-navigation',
		title: 'Navigate around a solid obstacle',
		actions: ['navigate_to'],
		success: (state) => atTarget(state),
	},
	{
		id: 'inventory-crafting',
		title: 'Craft an exact inventory count',
		actions: ['craft_inventory', 'craft_table'],
		success: (state) => itemCount(state, state.outputItemId ?? 'minecraft:oak_planks') === (state.expectedCount ?? 0),
	},
	{
		id: 'block-placement',
		title: 'Place and verify a block',
		actions: ['place_block'],
		success: (state) => state.block?.blockId === state.expectedBlockId || state.world?.blockAt?.(...(state.position ?? []))?.blockId === state.expectedBlockId,
	},
	{
		id: 'hostile-mob-combat',
		title: 'Fight a hostile mob with cooldown-limited attacks',
		actions: ['attack'],
		success: (state) => state.target?.dead === true || state.target?.health === 0 || state.healthAfter < state.healthBefore,
	},
	{
		id: 'lava-damage-reaction',
		title: 'React to environmental lava damage',
		actions: ['navigate_to', 'respawn'],
		success: (state) => state.damageTaken > 0 || state.reacted === true,
	},
	{
		id: 'checkpoint-respawn',
		title: 'Respawn at the recorded checkpoint',
		actions: ['respawn'],
		success: (state) => samePosition(state.position, state.checkpoint) && state.health > 0,
	},
	{
		id: 'direct-message-wake',
		title: 'Wake an idle agent through a direct message',
		actions: ['chat'],
		events: ['direct_message'],
		success: (state) => state.wakeState === 'AWAKE' || state.conversationEvents?.some((event) => event.recipientId === state.recipientId),
	},
	{
		id: 'stalled-action',
		title: 'Terminate a stalled action at its virtual tick deadline',
		actions: ['navigate_to'],
		success: (state) => state.timeout?.state === 'TIMED_OUT' || state.state === 'TIMED_OUT',
	},
	{
		id: 'invalid-decision-correction',
		title: 'Correct an invalid provider decision',
		actions: [],
		events: ['invalid_decision', 'correction'],
		success: (state) => Number.isSafeInteger(state.correctionCount) && state.correctionCount > 0,
	},
];

export const SIMULATOR_SCENARIOS = deepFreeze(Object.fromEntries(scenarioList.map((scenario) => [scenario.id, scenario])));
export const SCENARIO_MANIFESTS = SIMULATOR_SCENARIOS;

export function listSimulatorScenarios() { return scenarioList.map((scenario) => scenario.id); }
export function getSimulatorScenario(id) { return Object.hasOwn(SIMULATOR_SCENARIOS, id) ? SIMULATOR_SCENARIOS[id] : undefined; }

export function runScenarioSuccess(manifestOrId, state) {
	const manifest = typeof manifestOrId === 'string' ? getSimulatorScenario(manifestOrId) : manifestOrId;
	if (!manifest || typeof manifest.success !== 'function') throw new TypeError('unknown simulator scenario manifest');
	return manifest.success(state ?? {}) === true;
}

function itemCount(state, itemId) {
	const items = state?.inventory?.items ?? state?.world?.observation?.(state.agentId)?.inventory?.items ?? [];
	return items.filter((item) => item.itemId === itemId).reduce((total, item) => total + item.count, 0);
}

function hasItems(state, itemIds) { return itemIds.every((itemId) => itemCount(state, itemId) > 0); }
function atTarget(state) { return state?.arrived === true || state?.result?.reasonCode === 'ARRIVED' || (state?.distance !== undefined && state.distance <= (state.tolerance ?? 0.1)); }
function samePosition(left, right) { return Boolean(left && right) && left.x === right.x && left.y === right.y && left.z === right.z; }

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}
