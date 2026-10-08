package dev.agaminggod.arenaagents.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.Direction;

public final class BlockPlacementAttemptPolicy {
	private static final List<Direction> MODEL_FACE_ORDER = List.of(
			Direction.DOWN,
			Direction.UP,
			Direction.NORTH,
			Direction.SOUTH,
			Direction.WEST,
			Direction.EAST
	);
	static final long RETRY_INTERVAL_MS = 250L;
	static final int MAX_ATTEMPTS = 8;
	/**
	 * Consecutive refusals with no temporary cause (see ServerActionExecutor.placementRefusalMayClear) after which
	 * more clicks only repeat the same answer. One repeat is kept in case the first saw a stale view of the world.
	 */
	static final int FINAL_REFUSAL_LIMIT = 2;

	private BlockPlacementAttemptPolicy() {
	}

	static boolean shouldAttempt(long elapsedMs, int attempts) {
		return attempts >= 0
				&& attempts < MAX_ATTEMPTS
				&& Math.max(0L, elapsedMs) >= attempts * RETRY_INTERVAL_MS;
	}

	/** The attempts that count against the budget: all of it once the refusals are final. */
	static int countedAttempts(int attempts, int finalRefusals) {
		return finalRefusals >= FINAL_REFUSAL_LIMIT ? Math.max(attempts, MAX_ATTEMPTS) : attempts;
	}

	static boolean isExhausted(int attempts) {
		return attempts >= MAX_ATTEMPTS;
	}

	static Direction chooseFace(Direction requested, Predicate<Direction> usable) {
		return requested != null && usable.test(requested) ? requested : null;
	}

	public static List<Direction> supportedFaces(Predicate<Direction> usable) {
		ArrayList<Direction> supported = new ArrayList<>(MODEL_FACE_ORDER.size());
		for (Direction face : MODEL_FACE_ORDER) if (usable.test(face)) supported.add(face);
		return List.copyOf(supported);
	}
}
