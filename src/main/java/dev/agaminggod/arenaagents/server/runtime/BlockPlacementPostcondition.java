package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;

public final class BlockPlacementPostcondition {
	private BlockPlacementPostcondition() {
	}

	public static Decision evaluate(
			String initialBlockId,
			String currentBlockId,
			String expectedBlockId,
			boolean timedOut
	) {
		Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
		Objects.requireNonNull(currentBlockId, "currentBlockId must not be null");
		Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
		if (initialBlockId.equals(expectedBlockId)) return Decision.CONFLICT;
		if (currentBlockId.equals(expectedBlockId)) return Decision.SUCCEEDED;
		if (!currentBlockId.equals(initialBlockId)) return Decision.CONFLICT;
		return timedOut ? Decision.TIMED_OUT : Decision.WAITING;
	}

	public enum Decision {
		WAITING,
		SUCCEEDED,
		CONFLICT,
		TIMED_OUT
	}
}
