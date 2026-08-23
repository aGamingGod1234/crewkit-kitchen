package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.Objects;

public final class CombatPolicy {
	public static final double MAX_ATTACK_AIM_ERROR_DEGREES = 6.0D;
	public static final double MAX_AIM_ERROR_DEGREES = MAX_ATTACK_AIM_ERROR_DEGREES;

	public CombatDecision decide(CombatSnapshot snapshot, CombatIntent intent) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(intent, "intent must not be null");
		if (!snapshot.targetAlive()) {
			return CombatDecision.TARGET_DEFEATED;
		}
		if (intent.mode() == CombatIntent.Mode.FIGHT && snapshot.invulnerablePlayer()) {
			return CombatDecision.TARGET_INVALID;
		}
		return switch (intent.mode()) {
			case FIGHT -> {
				if (snapshot.distance() > intent.desiredRange()) yield CombatDecision.APPROACH;
				if (!snapshot.lineOfSight()) yield CombatDecision.APPROACH;
				if (snapshot.aimErrorDegrees() > MAX_ATTACK_AIM_ERROR_DEGREES) {
					yield CombatDecision.FACE;
				}
				yield snapshot.attackReady() ? CombatDecision.ATTACK : CombatDecision.FACE;
			}
			case FLEE -> snapshot.distance() < intent.desiredRange()
					? CombatDecision.RETREAT : CombatDecision.HOLD;
			case FOLLOW -> snapshot.distance() > intent.desiredRange()
					? CombatDecision.APPROACH : CombatDecision.HOLD;
		};
	}
}
