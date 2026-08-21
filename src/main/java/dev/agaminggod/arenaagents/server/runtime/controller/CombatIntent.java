package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.Objects;

public record CombatIntent(
		String targetSelector,
		double desiredRange,
		long timeoutMs,
		Mode mode
) {
	public CombatIntent {
		if (Objects.requireNonNull(targetSelector, "targetSelector must not be null").isBlank()) {
			throw new IllegalArgumentException("targetSelector must not be blank");
		}
		if (!Double.isFinite(desiredRange) || desiredRange < 1.0D || desiredRange > 64.0D) {
			throw new IllegalArgumentException("desiredRange must be in [1, 64]");
		}
		if (timeoutMs <= 0L) {
			throw new IllegalArgumentException("timeoutMs must be positive");
		}
		Objects.requireNonNull(mode, "mode must not be null");
	}

	public enum Mode {
		FIGHT,
		FLEE,
		FOLLOW
	}
}
