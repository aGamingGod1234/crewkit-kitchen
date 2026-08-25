export const PROTOCOL_VERSION = 1;
export const MULTIPLEXED_PROTOCOL_VERSION = 2;
export const MAX_LINE_BYTES = 65_536;
export const MAX_COMMAND_ID_LENGTH = 128;
export const MAX_CHAT_LENGTH = 256;
export const MAX_CONVERSATION_LENGTH = 512;
export const MAX_VOICE_TEXT_LENGTH = 280;
export const MAX_SUMMARY_LENGTH = 2_048;
export const MAX_GOAL_LENGTH = 4_096;
export const MAX_IDENTIFIER_LENGTH = 256;
export const MAX_PROVENANCE_TEXT_LENGTH = 256;
export const MAX_DESIRED_STATE_LENGTH = 512;
export const MAX_BUILD_SEQUENCE_PLACEMENTS = 32;
export const MAX_TARGET_SELECTOR_LENGTH = 256;
export const MAX_TARGET_ID_LENGTH = 36;
export const MAX_REASON_CODE_LENGTH = 128;
export const MAX_RESULT_MESSAGE_LENGTH = 2_048;
export const MIN_DURATION_MS = 1;
export const MAX_DURATION_MS = 600_000;
export const MIN_MOVEMENT_TOLERANCE = 0.01;
export const MAX_MOVEMENT_TOLERANCE = 16;
export const MAX_ENTITIES = 64;
export const MAX_BLOCKS = 128;
export const MAX_EFFECTS = 32;
export const MAX_INVENTORY_SUMMARIES = 64;
export const MAX_OBSERVATION_TAGS = 32;
export const MAX_TAG_COUNT_ENTRIES = 128;
export const LOOPBACK_HOST = '127.0.0.1';
export const DEFAULT_AGENT_CAP = 16;
export const DEFAULT_GOAL_QUEUE_CAP = 16;
export const DEFAULT_PLANNING_CONCURRENCY = DEFAULT_AGENT_CAP;
export const DEFAULT_SERVICE_TIER = 'priority';
export const DEFAULT_CONNECTION_QUEUE_CAP = 256;
export const DEFAULT_AGENT_MESSAGE_QUEUE_CAP = 32;
export const MAX_BRIDGE_SECRET_LENGTH = 512;

export const TERMINAL_ACTION_STATES = Object.freeze([
	'SUCCEEDED',
	'FAILED',
	'CANCELLED',
	'TIMED_OUT',
]);

export const ACTION_FIELDS = Object.freeze({
	move_to: Object.freeze(['x', 'y', 'z', 'tolerance', 'sprint']),
	look_at: Object.freeze(['x', 'y', 'z']),
	attack: Object.freeze(['targetId', 'timeoutMs']),
	select_item: Object.freeze(['itemId']),
	use_item: Object.freeze(['durationMs']),
	break_block: Object.freeze(['x', 'y', 'z', 'timeoutMs']),
	pick_up_item: Object.freeze(['targetSelector']),
	place_block: Object.freeze(['x', 'y', 'z', 'face', 'itemId', 'desiredState']),
	chat: Object.freeze(['message', 'audience', 'recipientId']),
	wait: Object.freeze(['durationMs']),
	set_door: Object.freeze(['x', 'y', 'z', 'open']),
	drop_item: Object.freeze(['slot', 'count']),
	navigate_to: Object.freeze(['x', 'y', 'z', 'tolerance', 'sprint', 'timeoutMs']),
	transfer_container: Object.freeze(['x', 'y', 'z', 'sourceKind', 'sourceSlot', 'destinationKind', 'destinationSlot', 'count', 'expectedItemId', 'timeoutMs']),
	craft_inventory: Object.freeze(['recipeId', 'count', 'timeoutMs']),
	craft_table: Object.freeze(['recipeId', 'x', 'y', 'z', 'count', 'timeoutMs']),
	furnace_transaction: Object.freeze(['x', 'y', 'z', 'operation', 'inventorySlot', 'count', 'expectedItemId', 'timeoutMs']),
	equip_item: Object.freeze(['sourceSlot', 'targetSlot', 'expectedItemId']),
	select_tool: Object.freeze(['sourceSlot', 'hotbarSlot', 'expectedItemId', 'minRemainingDurability']),
	block_with_shield: Object.freeze(['durationMs']),
	use_ranged: Object.freeze(['targetId', 'drawDurationMs', 'timeoutMs']),
	interact_block: Object.freeze(['x', 'y', 'z', 'face', 'hand', 'expectedItemId']),
	interact_entity: Object.freeze(['targetId', 'hand', 'expectedItemId']),
	dismount: Object.freeze([]),
	start_fall_flying: Object.freeze([]),
	menu_transfer: Object.freeze(['menuId', 'sourceSlot', 'destinationSlot', 'count', 'expectedItemId', 'timeoutMs']),
	menu_button: Object.freeze(['menuId', 'buttonId', 'timeoutMs']),
	anvil_rename: Object.freeze(['menuId', 'name', 'timeoutMs']),
	respawn: Object.freeze([]),
});

export const BLOCK_FACES = Object.freeze(['down', 'up', 'north', 'south', 'west', 'east']);
