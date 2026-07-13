export const PROTOCOL_VERSION = 1;
export const MAX_LINE_BYTES = 65_536;
export const MAX_COMMAND_ID_LENGTH = 128;
export const MAX_CHAT_LENGTH = 256;
export const MAX_SUMMARY_LENGTH = 2_048;
export const MAX_GOAL_LENGTH = 4_096;
export const MAX_IDENTIFIER_LENGTH = 256;
export const MAX_TARGET_SELECTOR_LENGTH = 256;
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
export const LOOPBACK_HOST = '127.0.0.1';

export const TERMINAL_ACTION_STATES = Object.freeze([
	'SUCCEEDED',
	'FAILED',
	'CANCELLED',
	'TIMED_OUT',
]);

export const ACTION_FIELDS = Object.freeze({
	move_to: Object.freeze(['x', 'y', 'z', 'tolerance', 'sprint']),
	look_at: Object.freeze(['x', 'y', 'z']),
	attack: Object.freeze(['targetSelector', 'timeoutMs']),
	select_item: Object.freeze(['itemId']),
	use_item: Object.freeze(['durationMs']),
	break_block: Object.freeze(['x', 'y', 'z', 'timeoutMs']),
	place_block: Object.freeze(['x', 'y', 'z', 'face', 'itemId']),
	chat: Object.freeze(['message']),
	wait: Object.freeze(['durationMs']),
	complete_goal: Object.freeze(['summary']),
});

export const BLOCK_FACES = Object.freeze(['down', 'up', 'north', 'south', 'west', 'east']);
