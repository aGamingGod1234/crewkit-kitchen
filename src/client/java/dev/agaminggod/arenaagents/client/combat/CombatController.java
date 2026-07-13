package dev.agaminggod.arenaagents.client.combat;

import java.util.Objects;

public final class CombatController {
	private static final float READY_COOLDOWN = 0.9F;

	public Phase phase(CombatTarget target, double reach, boolean aligned, float attackStrength) {
		Objects.requireNonNull(target, "target must not be null");
		if (!Double.isFinite(reach) || reach <= 0.0D) {
			throw new IllegalArgumentException("reach must be finite and positive");
		}
		if (!Float.isFinite(attackStrength) || attackStrength < 0.0F) {
			throw new IllegalArgumentException("attackStrength must be finite and nonnegative");
		}
		if (!target.alive()) {
			return Phase.COMPLETE;
		}
		if (target.distanceSquared() > reach * reach) {
			return Phase.APPROACH;
		}
		if (!aligned) {
			return Phase.FACE;
		}
		return attackStrength >= READY_COOLDOWN ? Phase.ATTACK : Phase.WAIT_COOLDOWN;
	}

	public enum Phase {
		APPROACH,
		FACE,
		WAIT_COOLDOWN,
		ATTACK,
		COMPLETE
	}
}
