package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Player-facing activity copy. Lifecycle plumbing stays in the field console, not chat. */
public final class AgentActivityPresentation {
	private AgentActivityPresentation() {
	}

	public static String action(ActionType type) {
		return switch (Objects.requireNonNull(type, "type must not be null")) {
			case MOVE_TO, NAVIGATE_TO -> "Moving to the next position";
			case LOOK_AT -> "Looking around";
			case ATTACK, FIGHT_TARGET -> "Engaging a target";
			case SELECT_ITEM, SELECT_TOOL -> "Choosing the right item";
			case USE_ITEM -> "Using an item";
			case BREAK_BLOCK -> "Mining a block";
			case PLACE_BLOCK -> "Placing a block";
			case BUILD_SEQUENCE -> "Building a sequence";
			case CHAT -> "Sending a message";
			case WAIT -> "Waiting briefly";
			case SET_DOOR -> "Using a door";
			case PICK_UP_ITEM -> "Picking up an item";
			case DROP_ITEM -> "Dropping an item";
			case FLEE_FROM -> "Moving to safety";
			case FOLLOW_ENTITY -> "Following a target";
			case TRANSFER_CONTAINER -> "Moving items";
			case CRAFT_INVENTORY, CRAFT_TABLE -> "Crafting an item";
			case FURNACE_TRANSACTION -> "Using a furnace";
			case EQUIP_ITEM -> "Equipping an item";
			case BLOCK_WITH_SHIELD -> "Blocking with a shield";
			case USE_RANGED -> "Using a ranged weapon";
			case INTERACT_BLOCK -> "Interacting with a block";
			case INTERACT_ENTITY -> "Interacting with an entity";
			case DISMOUNT -> "Dismounting";
			case START_FALL_FLYING -> "Starting elytra flight";
			case MENU_TRANSFER -> "Moving items in a menu";
			case MENU_BUTTON -> "Selecting a menu option";
			case ANVIL_RENAME -> "Naming an item";
			case RESPAWN -> "Respawning";
			case COMPLETE_GOAL -> "Finishing the task";
		};
	}

	public static Optional<String> result(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		if (result.state() == ServerActionState.SUCCEEDED) return Optional.empty();
		if (!shouldAnnounceFailure(result.reasonCode())) return Optional.empty();
		String activity = action(result.actionType());
		String detail = readableFailure(result.reasonCode(), result.message());
		return Optional.of(activity + " needs attention" + (detail.isBlank() ? "." : ": " + detail));
	}

	public static boolean shouldAnnounceAction(ActionType type) {
		return switch (Objects.requireNonNull(type, "type must not be null")) {
			case BREAK_BLOCK, PLACE_BLOCK, BUILD_SEQUENCE, ATTACK, FIGHT_TARGET, CHAT, DROP_ITEM,
					TRANSFER_CONTAINER, CRAFT_INVENTORY, CRAFT_TABLE, FURNACE_TRANSACTION,
					EQUIP_ITEM, BLOCK_WITH_SHIELD, USE_RANGED, COMPLETE_GOAL -> true;
			default -> false;
		};
	}

	public static boolean shouldAnnounceFailure(String reasonCode) {
		if (reasonCode == null || reasonCode.isBlank()) return true;
		return switch (reasonCode.toUpperCase(Locale.ROOT)) {
			case "ACTION_CANCELLED", "PATH_BLOCKED", "NO_PATH", "NO_STANDABLE_PATH", "PATH_LIMIT_REACHED",
					"ACTION_TIMEOUT", "ACTION_TIMED_OUT", "NAVIGATION_TIMED_OUT",
					"TARGET_NOT_FOUND", "TARGET_UNAVAILABLE", "TARGET_TOO_FAR", "RANGED_TARGET_DENIED",
					"PLACEMENT_NOT_CONFIRMED", "PLACEMENT_STATE_MISMATCH", "PLACEMENT_CONFLICT",
					"NO_PLACEMENT_SUPPORT", "SELECTION_NOT_CONFIRMED", "ITEM_UNAVAILABLE",
					"TARGET_NOT_LOADED", "TARGET_LOST", "ITEM_PICKUP_TIMED_OUT", "RANGED_USE_TIMED_OUT",
					"RANGED_USE_NOT_STARTED", "RANGED_RELEASE_NOT_OBSERVED", "BUILD_SEQUENCE_TIMEOUT",
					"TARGET_OCCUPIED", "SHIELD_RELEASE_NOT_OBSERVED", "SHIELD_USE_NOT_STARTED",
					"SHIELD_USE_INTERRUPTED", "RETRY_SCHEDULED", "PROVIDER_RETRY", "PLANNING_RETRY",
					"MISSING_AGENT_MESSAGE", "MISSING_FINAL_MESSAGE" -> false;
			default -> true;
		};
	}

	/** Returns whether a reason belongs in public chat; routine failures stay structured. */
	public static boolean shouldShowInChat(String reasonCode, boolean routine) {
		return !routine || shouldAnnounceFailure(reasonCode);
	}

	private static String readableFailure(String reasonCode, String message) {
		if (message != null && !message.isBlank()) {
			String compact = message.replace('\n', ' ').replace('\r', ' ').trim();
			return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
		}
		if (reasonCode == null || reasonCode.isBlank()) return "";
		return reasonCode.toLowerCase(Locale.ROOT).replace('_', ' ');
	}
}
