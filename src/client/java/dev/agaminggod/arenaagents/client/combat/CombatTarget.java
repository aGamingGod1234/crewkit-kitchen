package dev.agaminggod.arenaagents.client.combat;

import java.util.Objects;
import java.util.UUID;

public record CombatTarget(
		UUID uuid,
		String name,
		String typeId,
		boolean player,
		boolean hostile,
		boolean alive,
		double x,
		double y,
		double z,
		double eyeY,
		double distanceSquared
) {
	public CombatTarget {
		uuid = Objects.requireNonNull(uuid, "uuid must not be null");
		name = requireText(name, "name");
		typeId = requireText(typeId, "typeId");
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
		requireFinite(eyeY, "eyeY");
		if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
			throw new IllegalArgumentException("distanceSquared must be finite and nonnegative");
		}
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
}
