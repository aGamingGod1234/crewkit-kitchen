package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.InteractionHand;

public final class SafetyInputReflex {
	private static final long DURATION_MS = 2_000L;

	private SafetyInputReflex() {
	}

	public static Optional<Decision> choose(Threat threat) {
		Objects.requireNonNull(threat, "threat must not be null");
		if (threat.suffocating()) {
			return Optional.of(decision(Reason.SUFFOCATING, 1.0F, true, true, threat.currentYaw(), threat));
		}
		if (threat.air() * 4 <= threat.maxAir()) {
			return Optional.of(decision(Reason.LOW_AIR, 0.0F, true, false, threat.currentYaw(), threat));
		}
		if (threat.onFire()) {
			return Optional.of(decision(Reason.ON_FIRE, 1.0F, threat.onGround(), true, threat.currentYaw(), threat));
		}
		if (threat.recentlyDamaged() && threat.escapeYaw().isPresent()) {
			return Optional.of(decision(
					Reason.RECENT_DAMAGE, 1.0F, threat.onGround(), true, threat.escapeYaw().orElseThrow(), threat));
		}
		return Optional.empty();
	}

	public static Optional<Float> escapeYaw(
			double playerX,
			double playerZ,
			double attackerX,
			double attackerZ
	) {
		if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)
				|| !Double.isFinite(attackerX) || !Double.isFinite(attackerZ)) {
			throw new IllegalArgumentException("positions must be finite");
		}
		double awayX = playerX - attackerX;
		double awayZ = playerZ - attackerZ;
		if (awayX * awayX + awayZ * awayZ < 1.0E-8D) return Optional.empty();
		return Optional.of((float) Math.toDegrees(Math.atan2(-awayX, awayZ)));
	}

	private static Decision decision(
			Reason reason,
			float forward,
			boolean jump,
			boolean sprint,
			float yaw,
			Threat threat
	) {
		return new Decision(reason, new AgentInputState(
				forward, 0.0F, jump, false, sprint, false, false,
				yaw, threat.currentPitch(), threat.selectedSlot(), InteractionHand.MAIN_HAND
		), DURATION_MS);
	}

	public enum Reason {
		SUFFOCATING,
		LOW_AIR,
		ON_FIRE,
		RECENT_DAMAGE
	}

	public record Threat(
			boolean recentlyDamaged,
			boolean onFire,
			boolean suffocating,
			int air,
			int maxAir,
			boolean onGround,
			float currentYaw,
			float currentPitch,
			int selectedSlot,
			Optional<Float> escapeYaw
	) {
		public Threat {
			if (air < 0 || maxAir < 1 || air > maxAir) throw new IllegalArgumentException("air must be within the maximum");
			if (!Float.isFinite(currentYaw) || !Float.isFinite(currentPitch)) {
				throw new IllegalArgumentException("look angles must be finite");
			}
			Objects.requireNonNull(escapeYaw, "escapeYaw must not be null");
			if (escapeYaw.isPresent() && !Float.isFinite(escapeYaw.orElseThrow())) {
				throw new IllegalArgumentException("escapeYaw must be finite");
			}
		}
	}

	public record Decision(Reason reason, AgentInputState input, long durationMs) {
		public Decision {
			Objects.requireNonNull(reason, "reason must not be null");
			Objects.requireNonNull(input, "input must not be null");
			if (durationMs < 1L) throw new IllegalArgumentException("durationMs must be positive");
		}
	}
}
