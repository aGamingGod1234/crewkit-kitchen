package dev.agaminggod.arenaagents.server.runtime;

import net.minecraft.util.Mth;

/**
 * Holds a block interaction (placing, opening a crafting table) until the agent's actual view has turned onto the
 * target and stayed there briefly, like a player who looks before clicking. The turn itself is driven by the normal
 * agent input path, which may rate-limit rotation; this gate only observes the real yaw and pitch.
 */
final class AimGate {
	/** Close enough that the crosshair sits on the target face. */
	static final float TOLERANCE_DEGREES = 3.0F;
	/** Aligned ticks before the interaction, so a spectator sees the look settle before the arm swings. */
	static final int SETTLE_TICKS = 3;
	/** A swing needs the crosshair on the hitbox for one tick; players click as soon as it is on a moving mob. */
	static final int ATTACK_SETTLE_TICKS = 1;
	/** Two seconds at 20 TPS covers a rate-limited half turn with margin. */
	static final int MAX_TICKS = 40;

	enum State { AIMING, READY, FAILED }

	private final int settleTicks;
	private int ticks;
	private int alignedTicks;
	private boolean ready;

	AimGate() {
		this(SETTLE_TICKS);
	}

	AimGate(int settleTicks) {
		if (settleTicks < 1) throw new IllegalArgumentException("settleTicks must be positive");
		this.settleTicks = settleTicks;
	}

	State observe(float yaw, float pitch, float targetYaw, float targetPitch) {
		return observeAligned(Math.abs(Mth.wrapDegrees(targetYaw - yaw)) <= TOLERANCE_DEGREES
				&& Math.abs(targetPitch - pitch) <= TOLERANCE_DEGREES);
	}

	/** As {@link #observe}, with alignment already judged (for an attack: the crosshair ray meets the hitbox). */
	State observeAligned(boolean aligned) {
		if (ready) return State.READY;
		ticks++;
		alignedTicks = aligned ? alignedTicks + 1 : 0;
		if (alignedTicks >= settleTicks) {
			ready = true;
			return State.READY;
		}
		return ticks >= MAX_TICKS ? State.FAILED : State.AIMING;
	}

	boolean ready() {
		return ready;
	}
}
