package dev.agaminggod.arenaagents.client.perception;

import dev.agaminggod.arenaagents.client.config.AgentConfig;
import java.util.List;
import java.util.Objects;

public final class ObservationLimits {
	public static final int MAX_ENTITIES = 64;
	public static final int MAX_BLOCKS = 128;
	public static final int MAX_EFFECTS = 32;
	public static final int MAX_INVENTORY_SUMMARIES = 64;
	public static final int MAX_BLOCK_SCAN_POSITIONS = 8_192;

	private ObservationLimits() {
	}

	public static int clampObservationRadius(int radius) {
		return Math.clamp(
				radius,
				AgentConfig.MIN_OBSERVATION_RADIUS,
				AgentConfig.MAX_OBSERVATION_RADIUS
		);
	}

	public static List<EntitySnapshot> truncateEntities(List<EntitySnapshot> entities) {
		return truncate(entities, MAX_ENTITIES);
	}

	public static List<BlockSnapshot> truncateBlocks(List<BlockSnapshot> blocks, int requestedLimit) {
		return truncate(blocks, Math.min(requireLimit(requestedLimit), MAX_BLOCKS));
	}

	public static <T> List<T> truncateEffects(List<T> effects) {
		return truncate(effects, MAX_EFFECTS);
	}

	public static <T> List<T> truncateInventorySummaries(List<T> items) {
		return truncate(items, MAX_INVENTORY_SUMMARIES);
	}

	private static int requireLimit(int limit) {
		if (limit < 0) {
			throw new IllegalArgumentException("limit must not be negative");
		}
		return limit;
	}

	private static <T> List<T> truncate(List<T> values, int limit) {
		Objects.requireNonNull(values, "values must not be null");
		return List.copyOf(values.subList(0, Math.min(values.size(), limit)));
	}
}
