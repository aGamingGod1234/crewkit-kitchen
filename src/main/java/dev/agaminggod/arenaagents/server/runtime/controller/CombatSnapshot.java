package dev.agaminggod.arenaagents.server.runtime.controller;

public record CombatSnapshot(
		boolean targetAlive,
		boolean invulnerablePlayer,
		double distance,
		boolean attackReady,
		boolean lineOfSight,
		double aimErrorDegrees
) {
	public CombatSnapshot(
			boolean targetAlive,
			boolean invulnerablePlayer,
			double distance,
			boolean attackReady
	) {
		this(targetAlive, invulnerablePlayer, distance, attackReady, true, 0.0D);
	}

	public CombatSnapshot {
		if (!Double.isFinite(distance) || distance < 0.0D) {
			throw new IllegalArgumentException("distance must be finite and non-negative");
		}
		if (!Double.isFinite(aimErrorDegrees) || aimErrorDegrees < 0.0D || aimErrorDegrees > 180.0D) {
			throw new IllegalArgumentException("aimErrorDegrees must be finite and in [0, 180]");
		}
	}
}
