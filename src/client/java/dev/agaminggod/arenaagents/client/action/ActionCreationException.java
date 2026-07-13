package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public final class ActionCreationException extends RuntimeException {
	private final String reasonCode;

	public ActionCreationException(String reasonCode, String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		this.reasonCode = requireReasonCode(reasonCode);
	}

	public String reasonCode() {
		return reasonCode;
	}

	private static String requireReasonCode(String reasonCode) {
		Objects.requireNonNull(reasonCode, "reasonCode must not be null");
		if (reasonCode.isBlank()) {
			throw new IllegalArgumentException("reasonCode must not be blank");
		}
		if (reasonCode.length() > ProtocolConstants.MAX_REASON_CODE_LENGTH) {
			throw new IllegalArgumentException(
					"reasonCode must not exceed " + ProtocolConstants.MAX_REASON_CODE_LENGTH + " characters"
			);
		}
		return reasonCode;
	}
}
