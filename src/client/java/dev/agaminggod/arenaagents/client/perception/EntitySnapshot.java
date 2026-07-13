package dev.agaminggod.arenaagents.client.perception;

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
		stableId = requireText(stableId, "stableId");
		typeId = requireText(typeId, "typeId");
		name = Objects.requireNonNull(name, "name must not be null");
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
		requireNonNegativeFinite(distanceSquared, "distanceSquared");
		requireNonNegativeFinite(health, "health");
		requireNonNegativeFinite(maxHealth, "maxHealth");
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
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
