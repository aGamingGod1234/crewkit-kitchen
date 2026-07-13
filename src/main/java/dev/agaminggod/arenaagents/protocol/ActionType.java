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
	CHAT("chat"),
	WAIT("wait"),
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
