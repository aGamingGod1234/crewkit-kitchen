package dev.agaminggod.arenaagents.client.perception;

import java.util.Objects;

public record BlockSnapshot(
		int x,
		int y,
		int z,
		String blockId,
		String fluidId,
		boolean collisionShapeEmpty,
		boolean fullCollisionBlock,
		double distanceSquared
) {
	public BlockSnapshot {
		blockId = requireText(blockId, "blockId");
		fluidId = requireText(fluidId, "fluidId");
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
}
