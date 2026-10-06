package dev.agaminggod.arenaagents.client.pov;

/**
 * Smoothed look for the agent's first-person hands, kept exactly like LocalPlayer.applyInput keeps
 * xBob/yBob: once per client tick the bob chases the view by half the gap, so the hands lag a turn.
 * Yaw is tracked on an unwrapped scale so a view that crosses 180 degrees never jolts the hands.
 */
public final class PovHands {
	private static final float CHASE = 0.5F;
	private boolean seeded;
	private float xBob;
	private float yBob;
	private float xBobO;
	private float yBobO;

	/** One client tick with the agent's current view; the first tick after a reset seeds the bob. */
	public void tick(float pitch, float yaw) {
		if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) return;
		if (!seeded) {
			xBob = xBobO = pitch;
			yBob = yBobO = yaw;
			seeded = true;
			return;
		}
		xBobO = xBob;
		yBobO = yBob;
		xBob += (pitch - xBob) * CHASE;
		yBob += PovLook.wrapDegrees(yaw - yBob) * CHASE;
	}

	public float xBob(float partialTick) {
		return xBobO + (xBob - xBobO) * partialTick;
	}

	public float yBob(float partialTick) {
		return yBobO + (yBob - yBobO) * partialTick;
	}

	/** The view yaw expressed on the bob's unwrapped scale, so {@code viewYaw - yBob} is the short way round. */
	public float viewYaw(float partialTick, float actualYaw) {
		float bob = yBob(partialTick);
		return bob + PovLook.wrapDegrees(actualYaw - bob);
	}

	public boolean seeded() {
		return seeded;
	}

	public void reset() {
		seeded = false;
		xBob = yBob = xBobO = yBobO = 0.0F;
	}
}
