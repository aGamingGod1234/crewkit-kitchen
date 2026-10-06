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
	/** Two seconds at 20 TPS covers a rate-limited half turn with margin. */
	static final int MAX_TICKS = 40;

	enum State { AIMING, READY, FAILED }

	private int ticks;
	private int alignedTicks;
	private boolean ready;

	State observe(float yaw, float pitch, float targetYaw, float targetPitch) {
		if (ready) return State.READY;
		ticks++;
		boolean aligned = Math.abs(Mth.wrapDegrees(targetYaw - yaw)) <= TOLERANCE_DEGREES
				&& Math.abs(targetPitch - pitch) <= TOLERANCE_DEGREES;
		alignedTicks = aligned ? alignedTicks + 1 : 0;
		if (alignedTicks >= SETTLE_TICKS) {
			ready = true;
			return State.READY;
		}
		return ticks >= MAX_TICKS ? State.FAILED : State.AIMING;
	}

	boolean ready() {
		return ready;
	}
}
