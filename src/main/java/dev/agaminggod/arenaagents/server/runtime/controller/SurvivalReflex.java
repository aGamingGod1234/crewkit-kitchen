package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.Objects;

public enum SurvivalReflex {
	NONE,
	SURFACE,
	ESCAPE_SUFFOCATION,
	LEAVE_FIRE,
	STOP_DANGEROUS_FALL,
	BACK_AWAY;

	public static SurvivalReflex choose(SurvivalThreat threat) {
		Objects.requireNonNull(threat, "threat must not be null");
		if (threat.air() <= Math.max(20, threat.maxAir() / 4)) return SURFACE;
		if (threat.suffocating()) return ESCAPE_SUFFOCATION;
		if (threat.onFire()) return LEAVE_FIRE;
		if (threat.dangerousFall()) return STOP_DANGEROUS_FALL;
		if (threat.tookDamage()) return BACK_AWAY;
		return NONE;
	}
}
