package dev.agaminggod.arenaagents.client.pov;

/**
 * Spectate free look: while the sneak key is held the camera keeps the agent's eyes but takes its rotation from the
 * mouse; on release it eases back onto the agent's live look and locks again. Pure state with explicit clock values
 * so the camera, mouse and hand hooks share one answer per frame.
 */
public final class PovFreeLook {
	public enum Mode { LOCKED, FREE, RETURNING }

	/** Ease back to the agent's look; long enough to read as a glide, short enough to feel like a release. */
	public static final long RETURN_NANOS = 200_000_000L;
	private static Mode mode = Mode.LOCKED;
	private static float yaw;
	private static float pitch;
	private static float fromYaw;
	private static float fromPitch;
	private static long returnStart;
	private static boolean shown;
	private static float shownYaw;
	private static float shownPitch;

	private PovFreeLook() {
	}

	/**
	 * Feeds whether free look is wanted right now (spectating, no screen open, sneak held). Pressing starts from what
	 * the camera last showed, so grabbing again mid-return continues from there; releasing starts the return.
	 */
	public static void hold(boolean wanted, long now) {
		if (wanted) {
			if (mode == Mode.FREE) return;
			if (!shown) return;
			yaw = shownYaw;
			pitch = shownPitch;
			mode = Mode.FREE;
		} else if (mode == Mode.FREE) {
			fromYaw = yaw;
			fromPitch = pitch;
			returnStart = now;
			mode = Mode.RETURNING;
		}
	}

	/** Mouse deltas in the same scale as Entity.turn; ignored unless free look is held. */
	public static boolean turn(double dx, double dy) {
		if (mode != Mode.FREE) return false;
		if (!Double.isFinite(dx) || !Double.isFinite(dy)) return true;
		yaw = PovLook.wrapDegrees(yaw + (float) dx * PovLook.TURN_SCALE);
		pitch = PovLook.clampPitch(pitch + (float) dy * PovLook.TURN_SCALE);
		return true;
	}

	/** The camera yaw for this frame given the agent's own view yaw. */
	public static float yaw(float agentYaw, long now) {
		float result = switch (current(now)) {
			case FREE -> yaw;
			case RETURNING -> fromYaw + PovLook.wrapDegrees(agentYaw - fromYaw) * progress(now);
			case LOCKED -> agentYaw;
		};
		shownYaw = result;
		shown = true;
		return result;
	}

	/** The camera pitch for this frame given the agent's own view pitch. */
	public static float pitch(float agentPitch, long now) {
		float result = switch (current(now)) {
			case FREE -> pitch;
			case RETURNING -> fromPitch + (agentPitch - fromPitch) * progress(now);
			case LOCKED -> agentPitch;
		};
		shownPitch = result;
		return result;
	}

	/** True while the camera is not on the agent's look, which is when the agent's hands would mislead. */
	public static boolean detached(long now) {
		return current(now) != Mode.LOCKED;
	}

	public static Mode mode(long now) {
		return current(now);
	}

	public static void reset() {
		mode = Mode.LOCKED;
		yaw = pitch = fromYaw = fromPitch = shownYaw = shownPitch = 0.0F;
		returnStart = 0L;
		shown = false;
	}

	/** Ease-out cubic over {@link #RETURN_NANOS}: quick departure from the free view, gentle landing on the agent's. */
	static float ease(float t) {
		float clamped = Math.clamp(t, 0.0F, 1.0F);
		float inverse = 1.0F - clamped;
		return 1.0F - inverse * inverse * inverse;
	}

	private static float progress(long now) {
		return ease((float) (now - returnStart) / RETURN_NANOS);
	}

	private static Mode current(long now) {
		if (mode == Mode.RETURNING && now - returnStart >= RETURN_NANOS) mode = Mode.LOCKED;
		return mode;
	}
}
