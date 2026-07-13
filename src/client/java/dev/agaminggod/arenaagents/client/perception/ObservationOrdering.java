package dev.agaminggod.arenaagents.client.perception;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ObservationOrdering {
	private static final Comparator<EntitySnapshot> ENTITY_ORDER = Comparator
			.comparingDouble(EntitySnapshot::distanceSquared)
			.thenComparing(EntitySnapshot::stableId);
	private static final Comparator<BlockSnapshot> BLOCK_ORDER = Comparator
			.comparingDouble(BlockSnapshot::distanceSquared)
			.thenComparingInt(BlockSnapshot::x)
			.thenComparingInt(BlockSnapshot::y)
			.thenComparingInt(BlockSnapshot::z)
			.thenComparing(BlockSnapshot::blockId);

	private ObservationOrdering() {
	}

	public static List<EntitySnapshot> entities(List<EntitySnapshot> entities) {
		return sortedCopy(entities, ENTITY_ORDER);
	}

	public static List<BlockSnapshot> blocks(List<BlockSnapshot> blocks) {
		return sortedCopy(blocks, BLOCK_ORDER);
	}

	private static <T> List<T> sortedCopy(List<T> values, Comparator<? super T> comparator) {
		Objects.requireNonNull(values, "values must not be null");
		return values.stream().sorted(comparator).toList();
	}
}
