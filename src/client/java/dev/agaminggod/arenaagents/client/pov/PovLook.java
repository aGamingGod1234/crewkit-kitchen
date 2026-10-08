package dev.agaminggod.arenaagents.client.pov;

/** Locally predicted takeover look. Mouse capture turns it and the camera reads it every frame. */
public final class PovLook {
	// Entity.turn scales raw mouse deltas by the same factor, in float arithmetic.
	static final float TURN_SCALE = 0.15F;
	private static float yaw;
	private static float pitch;

	private PovLook() {
	}

	public static void turn(double dx, double dy) {
		if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
		yaw = wrapDegrees(yaw + (float) dx * TURN_SCALE);
		pitch = clampPitch(pitch + (float) dy * TURN_SCALE);
	}

	public static void reset(float yaw, float pitch) {
		PovLook.yaw = Float.isFinite(yaw) ? wrapDegrees(yaw) : 0.0F;
		PovLook.pitch = Float.isFinite(pitch) ? clampPitch(pitch) : 0.0F;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	/** Same result as Mth.wrapDegrees: [-180, 180). */
	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) wrapped -= 360.0F;
		if (wrapped < -180.0F) wrapped += 360.0F;
		return wrapped;
	}

	public static float clampPitch(float pitch) {
		return Math.clamp(pitch, -90.0F, 90.0F);
	}

	/** Shortest-turn interpolation, matching Mth.rotLerp. */
	public static float lerpYaw(float from, float to, float delta) {
		return from + delta * wrapDegrees(to - from);
	}
}
