package dev.agaminggod.arenaagents.server.runtime.controller;

/**
 * Tracks material path progress without depending on Minecraft runtime types.
 */
public final class WaypointProgress {
	private static final double MATERIAL_PROGRESS = 0.1D;

	private final double initialDistance;
	private final long stallTimeoutMs;
	private final int maximumReplans;
	private double bestRemainingDistance;
	private long lastProgressAt;
	private int replans;

	public WaypointProgress(
			double initialDistance,
			long startedAtEpochMs,
			long stallTimeoutMs,
			int maximumReplans
	) {
		if (!Double.isFinite(initialDistance) || initialDistance < 0.0D) {
			throw new IllegalArgumentException("initialDistance must be finite and non-negative");
		}
		if (stallTimeoutMs <= 0L || maximumReplans < 0) {
			throw new IllegalArgumentException("stall timeout must be positive and replans non-negative");
		}
		this.initialDistance = Math.max(MATERIAL_PROGRESS, initialDistance);
		this.bestRemainingDistance = initialDistance;
		this.lastProgressAt = startedAtEpochMs;
		this.stallTimeoutMs = stallTimeoutMs;
		this.maximumReplans = maximumReplans;
	}

	public Update observe(double remainingDistance, boolean waypointReached, long nowEpochMs) {
		if (!Double.isFinite(remainingDistance) || remainingDistance < 0.0D) {
			throw new IllegalArgumentException("remainingDistance must be finite and non-negative");
		}
		if (remainingDistance + MATERIAL_PROGRESS < bestRemainingDistance) {
			bestRemainingDistance = remainingDistance;
			lastProgressAt = nowEpochMs;
		}
		long idleMs = Math.max(0L, nowEpochMs - lastProgressAt);
		Decision decision = idleMs < stallTimeoutMs
				? Decision.CONTINUE
				: replans >= maximumReplans ? Decision.FAIL : Decision.REPLAN;
		double completed = 1.0D - (remainingDistance / initialDistance);
		double bounded = Math.max(0.0D, Math.min(1.0D, completed));
		return new Update(decision, waypointReached, bounded);
	}

	public void replanned(double remainingDistance, long nowEpochMs) {
		if (!Double.isFinite(remainingDistance) || remainingDistance < 0.0D) {
			throw new IllegalArgumentException("remainingDistance must be finite and non-negative");
		}
		replans++;
		bestRemainingDistance = remainingDistance;
		lastProgressAt = nowEpochMs;
	}

	public enum Decision {
		CONTINUE,
		REPLAN,
		FAIL
	}

	public record Update(Decision decision, boolean advanceWaypoint, double progress) {
	}
}
