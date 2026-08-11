package dev.agaminggod.arenaagents.server.runtime.controller;

public record SurvivalThreat(
		boolean onFire,
		int air,
		int maxAir,
		boolean suffocating,
		boolean dangerousFall,
		boolean tookDamage,
		double attackerX,
		double attackerZ
) {
	public SurvivalThreat {
		if (air < 0 || maxAir <= 0 || air > maxAir) {
			throw new IllegalArgumentException("air must be inside [0, maxAir]");
		}
		if (!Double.isFinite(attackerX) || !Double.isFinite(attackerZ)) {
			throw new IllegalArgumentException("attacker coordinates must be finite");
		}
	}
}
