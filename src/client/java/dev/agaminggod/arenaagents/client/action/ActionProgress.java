package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public record ActionProgress(
		String commandId,
		ActionType actionType,
		ActionState state,
		long elapsedMs,
		String message,
		long observedAtEpochMs
) {
	public ActionProgress {
		commandId = requireText(commandId, "commandId", ProtocolConstants.MAX_COMMAND_ID_LENGTH, false);
		actionType = Objects.requireNonNull(actionType, "actionType must not be null");
		state = Objects.requireNonNull(state, "state must not be null");
		if (state != ActionState.RUNNING) {
			throw new IllegalArgumentException("action progress state must be RUNNING");
		}
		if (elapsedMs < 0L) {
			throw new IllegalArgumentException("elapsedMs must not be negative");
		}
		message = requireText(message, "message", ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH, true);
		if (observedAtEpochMs <= 0L) {
			throw new IllegalArgumentException("observedAtEpochMs must be positive");
		}
	}

	private static String requireText(
			String value,
			String field,
			int maximumLength,
			boolean emptyAllowed
	) {
		Objects.requireNonNull(value, field + " must not be null");
		if (!emptyAllowed && value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (value.length() > maximumLength) {
			throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
		}
		return value;
	}
}
