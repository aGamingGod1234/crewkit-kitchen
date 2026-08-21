export const SCRIPT_PRIMITIVES = Object.freeze(new Set([
	'move_to', 'navigate_to', 'look_at', 'attack', 'select_item', 'use_item',
	'break_block', 'place_block', 'chat', 'wait', 'set_door', 'drop_item',
	'transfer_container', 'craft_inventory', 'craft_table', 'furnace_transaction',
	'equip_item', 'select_tool', 'block_with_shield', 'use_ranged',
	'interact_block', 'interact_entity', 'dismount', 'start_fall_flying',
	'menu_transfer', 'menu_button', 'anvil_rename',
	'respawn',
]));

export const PLAYER_MEMBER_PRIMITIVES = Object.freeze({
	moveTo: 'move_to', navigateTo: 'navigate_to', lookAt: 'look_at', attack: 'attack',
	selectItem: 'select_item', useItem: 'use_item', mine: 'break_block', place: 'place_block',
	chat: 'chat', wait: 'wait', setDoor: 'set_door', dropItem: 'drop_item',
	transferContainer: 'transfer_container', craftInventory: 'craft_inventory', craftTable: 'craft_table',
	furnaceTransaction: 'furnace_transaction', equipItem: 'equip_item', selectTool: 'select_tool',
	blockWithShield: 'block_with_shield', useRanged: 'use_ranged',
	interactBlock: 'interact_block', interactEntity: 'interact_entity',
	dismount: 'dismount', startFallFlying: 'start_fall_flying',
	menuTransfer: 'menu_transfer', menuButton: 'menu_button', anvilRename: 'anvil_rename',
	respawn: 'respawn',
});

export const EXACT_TARGET_ACTIONS = Object.freeze({
	attack: Object.freeze(['targetId', 'timeoutMs']),
	useRanged: Object.freeze(['targetId', 'drawDurationMs', 'timeoutMs']),
	interactEntity: Object.freeze(['targetId', 'hand', 'expectedItemId']),
});

export const FACTUAL_API_PATHS = Object.freeze(new Set([
	'player.state', 'world.items', 'world.entities', 'world.blocks', 'world.nearest',
	'inventory.count', 'inventory.countTag',
]));

export const SCRIPT_API_CALL_PATHS = Object.freeze(new Set([
	...Object.keys(PLAYER_MEMBER_PRIMITIVES).map((name) => `player.${name}`),
	...FACTUAL_API_PATHS,
]));

export const SCRIPT_BINDINGS = freezeRecord({
	player: freezeRecord(Object.fromEntries(Object.entries(PLAYER_MEMBER_PRIMITIVES).map(([name, primitive]) => [name, freezeRecord({ primitive })]))),
});

function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
