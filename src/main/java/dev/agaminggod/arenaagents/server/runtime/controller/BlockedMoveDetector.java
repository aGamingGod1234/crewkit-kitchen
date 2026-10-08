package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.ArrayDeque;
import java.util.Deque;

/** Detects sustained grounded collisions without mistaking a step-up for a blocked route. */
final class BlockedMoveDetector {
	private static final long REPLAN_AFTER_MS = 1_000L;
	private static final long FAIL_AFTER_MS = 3_000L;
	private static final int STILL_WINDOW_TICKS = 10;
	private static final double STILL_DISTANCE = 0.05D;

	private final Deque<Position> positions = new ArrayDeque<>(STILL_WINDOW_TICKS + 1);
	private long previousTimeMs;
	private long blockedElapsedMs;
	private boolean started;
	private boolean replanned;

	Decision observe(
			boolean horizontalCollision,
			boolean onGround,
			boolean jumpPending,
			boolean stepUp,
			double x,
			double z,
			long nowMs
	) {
		if (!horizontalCollision || !onGround || jumpPending || stepUp
				|| !Double.isFinite(x) || !Double.isFinite(z)) {
			reset();
			return Decision.CONTINUE;
		}
		if (!started) {
			started = true;
			previousTimeMs = nowMs;
			positions.addLast(new Position(x, z));
			return Decision.CONTINUE;
		}
		if (nowMs > previousTimeMs) {
			long delta;
			try {
				delta = Math.subtractExact(nowMs, previousTimeMs);
			} catch (ArithmeticException overflow) {
				delta = Long.MAX_VALUE;
			}
			blockedElapsedMs = blockedElapsedMs > Long.MAX_VALUE - delta
					? Long.MAX_VALUE : blockedElapsedMs + delta;
		}
		previousTimeMs = nowMs;
		positions.addLast(new Position(x, z));
		while (positions.size() > STILL_WINDOW_TICKS + 1) positions.removeFirst();
		boolean windowReady = positions.size() == STILL_WINDOW_TICKS + 1;
		boolean still = windowReady && positions.getFirst().distanceTo(x, z) < STILL_DISTANCE;
		if (windowReady && !still) {
			// A step or an actual shove is progress; require a fresh ten-tick still window before acting again.
			blockedElapsedMs = 0L;
			replanned = false;
			return Decision.CONTINUE;
		}
		if (!windowReady) return Decision.CONTINUE;
		if (blockedElapsedMs >= FAIL_AFTER_MS) return Decision.FAIL;
		if (blockedElapsedMs >= REPLAN_AFTER_MS && !replanned) {
			replanned = true;
			return Decision.REPLAN;
		}
		return Decision.CONTINUE;
	}

	void reset() {
		positions.clear();
		previousTimeMs = 0L;
		blockedElapsedMs = 0L;
		started = false;
		replanned = false;
	}

	enum Decision { CONTINUE, REPLAN, FAIL }

	private record Position(double x, double z) {
		double distanceTo(double otherX, double otherZ) {
			return Math.hypot(otherX - x, otherZ - z);
		}
	}
}
