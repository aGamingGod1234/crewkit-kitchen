package dev.agaminggod.arenaagents.server.runtime;

final class ActionProgressTracker {
	private static final double MINIMUM_PROGRESS_BLOCKS = 0.1D;

	private final long stallTimeoutMs;
	private double bestDistance;
	private long lastProgressAt;

	ActionProgressTracker(double initialDistance, long startedAtEpochMs, long stallTimeoutMs) {
		if (!Double.isFinite(initialDistance) || initialDistance < 0.0D) {
			throw new IllegalArgumentException("initialDistance must be finite and non-negative");
		}
		if (stallTimeoutMs <= 0L) {
			throw new IllegalArgumentException("stallTimeoutMs must be positive");
		}
		this.bestDistance = initialDistance;
		this.lastProgressAt = startedAtEpochMs;
		this.stallTimeoutMs = stallTimeoutMs;
	}

	boolean stalled(double distance, long nowEpochMs) {
		if (!Double.isFinite(distance) || distance < 0.0D) {
			throw new IllegalArgumentException("distance must be finite and non-negative");
		}
		if (distance + MINIMUM_PROGRESS_BLOCKS < bestDistance) {
			bestDistance = distance;
			lastProgressAt = nowEpochMs;
			return false;
		}
		return Math.max(0L, nowEpochMs - lastProgressAt) >= stallTimeoutMs;
	}
}
