package dev.agaminggod.arenaagents.client.perception;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public record EntitySnapshot(
		String stableId,
		String typeId,
		String name,
		double x,
		double y,
		double z,
		double distanceSquared,
		float health,
		float maxHealth,
		boolean hostile
) {
	public EntitySnapshot {
		stableId = requireText(stableId, "stableId", false);
		typeId = requireText(typeId, "typeId", false);
		name = requireText(name, "name", true);
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
		requireNonNegativeFinite(distanceSquared, "distanceSquared");
		requireNonNegativeFinite(health, "health");
		requireNonNegativeFinite(maxHealth, "maxHealth");
	}

	private static String requireText(String value, String field, boolean emptyAllowed) {
		Objects.requireNonNull(value, field + " must not be null");
		if (!emptyAllowed && value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (value.length() > ProtocolConstants.MAX_IDENTIFIER_LENGTH) {
			throw new IllegalArgumentException(
					field + " must not exceed " + ProtocolConstants.MAX_IDENTIFIER_LENGTH + " characters"
			);
		}
		return value;
	}

	private static void requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
	}

	private static void requireNonNegativeFinite(double value, String field) {
		requireFinite(value, field);
		if (value < 0.0D) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
	}
}
