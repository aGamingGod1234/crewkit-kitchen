package dev.agaminggod.arenaagents.client.navigation;

import java.util.Objects;

@FunctionalInterface
public interface WalkabilityView {
	Cell cellAt(GridPosition position);

	default boolean isStandable(GridPosition feetPosition) {
		Objects.requireNonNull(feetPosition, "feetPosition must not be null");
		return cellAt(feetPosition) == Cell.CLEAR
				&& cellAt(feetPosition.above()) == Cell.CLEAR
				&& cellAt(feetPosition.below()) == Cell.SAFE_SUPPORT;
	}

	enum Cell {
		UNLOADED,
		CLEAR,
		SAFE_SUPPORT,
		BLOCKED,
		HAZARD
	}
}
