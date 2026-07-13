package dev.agaminggod.arenaagents.client.action;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.Objects;

public final class ActionFactory {
	private static final String NOT_IMPLEMENTED_REASON = "ACTION_NOT_IMPLEMENTED";

	public RunningAction create(ActionCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		JsonObject arguments = command.arguments();
		return switch (command.type()) {
			case WAIT -> new WaitAction(arguments.get("durationMs").getAsLong());
			case LOOK_AT -> new LookAtAction(
					arguments.get("x").getAsDouble(),
					arguments.get("y").getAsDouble(),
					arguments.get("z").getAsDouble()
			);
			case CHAT -> new ChatAction(arguments.get("message").getAsString());
			case SELECT_ITEM -> new SelectItemAction(arguments.get("itemId").getAsString());
			case USE_ITEM -> new UseItemAction(
					ActionContext.Hand.MAIN_HAND,
					arguments.get("durationMs").getAsLong()
			);
			case MOVE_TO -> new MoveToAction(
					arguments.get("x").getAsDouble(),
					arguments.get("y").getAsDouble(),
					arguments.get("z").getAsDouble(),
					arguments.get("tolerance").getAsDouble(),
					arguments.get("sprint").getAsBoolean()
			);
			case ATTACK, BREAK_BLOCK, PLACE_BLOCK -> deferred(command.type(), "Task 7 interaction");
			case COMPLETE_GOAL -> deferred(command.type(), "coordinator goal completion");
		};
	}

	private static RunningAction deferred(ActionType type, String owner) {
		throw new ActionCreationException(
				NOT_IMPLEMENTED_REASON,
				"Action '" + type.wireName() + "' is delegated to " + owner
		);
	}
}
