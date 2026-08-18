package dev.agaminggod.arenaagents.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ActionType {
	MOVE_TO("move_to"),
	LOOK_AT("look_at"),
	ATTACK("attack"),
	SELECT_ITEM("select_item"),
	USE_ITEM("use_item"),
	BREAK_BLOCK("break_block"),
	PLACE_BLOCK("place_block"),
	BUILD_SEQUENCE("build_sequence"),
	CHAT("chat"),
	WAIT("wait"),
	SET_DOOR("set_door"),
	PICK_UP_ITEM("pick_up_item"),
	DROP_ITEM("drop_item"),
	NAVIGATE_TO("navigate_to"),
	FIGHT_TARGET("fight_target"),
	FLEE_FROM("flee_from"),
	FOLLOW_ENTITY("follow_entity"),
	TRANSFER_CONTAINER("transfer_container"),
	CRAFT_INVENTORY("craft_inventory"),
	CRAFT_TABLE("craft_table"),
	FURNACE_TRANSACTION("furnace_transaction"),
	EQUIP_ITEM("equip_item"),
	SELECT_TOOL("select_tool"),
	BLOCK_WITH_SHIELD("block_with_shield"),
	USE_RANGED("use_ranged"),
	RESPAWN("respawn"),
	COMPLETE_GOAL("complete_goal");

	private static final Map<String, ActionType> BY_WIRE_NAME = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(ActionType::wireName, Function.identity()));

	private final String wireName;

	ActionType(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static Optional<ActionType> fromWireName(String wireName) {
		if (wireName == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
	}
}
