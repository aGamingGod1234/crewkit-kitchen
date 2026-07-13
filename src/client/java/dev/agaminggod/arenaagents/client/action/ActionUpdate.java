package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public record ActionUpdate(ActionState state, String reasonCode, String message) {
	private static final String RUNNING_REASON = "RUNNING";

	public ActionUpdate {
		state = Objects.requireNonNull(state, "state must not be null");
		reasonCode = requireText(reasonCode, "reasonCode", ProtocolConstants.MAX_REASON_CODE_LENGTH, false);
		message = requireText(message, "message", ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH, true);
		if (state == ActionState.RUNNING && !RUNNING_REASON.equals(reasonCode)) {
			throw new IllegalArgumentException("running updates must use the RUNNING reason code");
		}
	}

	public static ActionUpdate running(String message) {
		return new ActionUpdate(ActionState.RUNNING, RUNNING_REASON, message);
	}

	public static ActionUpdate succeeded(String reasonCode, String message) {
		return new ActionUpdate(ActionState.SUCCEEDED, reasonCode, message);
	}

	public static ActionUpdate failed(String reasonCode, String message) {
		return new ActionUpdate(ActionState.FAILED, reasonCode, message);
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
