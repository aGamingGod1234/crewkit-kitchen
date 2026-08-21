package dev.agaminggod.arenaagents.server.runtime.controller;

public record CombatSnapshot(
		boolean targetAlive,
		boolean invulnerablePlayer,
		double distance,
		boolean attackReady
) {
	public CombatSnapshot {
		if (!Double.isFinite(distance) || distance < 0.0D) {
			throw new IllegalArgumentException("distance must be finite and non-negative");
		}
	}
}
