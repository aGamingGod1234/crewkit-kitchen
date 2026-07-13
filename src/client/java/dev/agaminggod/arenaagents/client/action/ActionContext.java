package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public interface ActionContext {
	boolean isClientThread();

	long monotonicTimeMs();

	long epochTimeMs();

	SafetyState safetyState();

	LookResult lookAt(
			double x,
			double y,
			double z,
			float maxYawDelta,
			float maxPitchDelta,
			float toleranceDegrees
	);

	OperationResult sendChat(String message);

	OperationResult selectHotbarItem(String itemId);

	OperationResult startUsingItem(Hand hand);

	void stopUsingItem();

	void releaseAll();

	enum Hand {
		MAIN_HAND,
		OFF_HAND
	}

	record LookResult(boolean withinTolerance, float yawErrorDegrees, float pitchErrorDegrees) {
		public LookResult {
			if (!Float.isFinite(yawErrorDegrees) || !Float.isFinite(pitchErrorDegrees)) {
				throw new IllegalArgumentException("look errors must be finite");
			}
			if (yawErrorDegrees < 0.0F || pitchErrorDegrees < 0.0F) {
				throw new IllegalArgumentException("look errors must not be negative");
			}
		}
	}

	record OperationResult(boolean successful, String reasonCode, String message) {
		public OperationResult {
			reasonCode = requireText(
					reasonCode,
					"reasonCode",
					ProtocolConstants.MAX_REASON_CODE_LENGTH,
					false
			);
			message = requireText(message, "message", ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH, true);
		}

		public static OperationResult succeeded(String reasonCode, String message) {
			return new OperationResult(true, reasonCode, message);
		}

		public static OperationResult failed(String reasonCode, String message) {
			return new OperationResult(false, reasonCode, message);
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
}
