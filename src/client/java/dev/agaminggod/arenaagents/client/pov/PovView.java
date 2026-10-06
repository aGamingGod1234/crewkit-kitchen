package dev.agaminggod.arenaagents.client.pov;

/**
 * Answers view-rotation reads for the entity the POV camera is bound to. It holds no Minecraft
 * types because the rotation mixins also run for integrated-server entities, which never match.
 */
public final class PovView {
	private static Object target;
	private static boolean takeover;
	private static boolean hasPose;
	private static float previousYaw;
	private static float previousPitch;
	private static float currentYaw;
	private static float currentPitch;
	private static float latestYaw;
	private static float latestPitch;

	private PovView() {
	}

	public static void bind(Object entity, boolean takeoverMode) {
		target = entity;
		takeover = takeoverMode;
	}

	public static boolean isTarget(Object entity) {
		return entity != null && entity == target;
	}

	public static void acceptPose(float yaw, float pitch) {
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
		latestYaw = yaw;
		latestPitch = PovLook.clampPitch(pitch);
		if (hasPose) return;
		previousYaw = currentYaw = latestYaw;
		previousPitch = currentPitch = latestPitch;
		hasPose = true;
	}

	/** Shifts the interpolation window once per client tick so frames lerp across a whole tick. */
	public static void tick() {
		previousYaw = currentYaw;
		previousPitch = currentPitch;
		currentYaw = latestYaw;
		currentPitch = latestPitch;
	}

	public static float yaw(Object entity, float partialTick, float original) {
		if (entity == null || entity != target) return original;
		if (takeover) return PovLook.yaw();
		return hasPose ? PovLook.lerpYaw(previousYaw, currentYaw, partialTick) : original;
	}

	public static float pitch(Object entity, float partialTick, float original) {
		if (entity == null || entity != target) return original;
		if (takeover) return PovLook.pitch();
		return hasPose ? previousPitch + (currentPitch - previousPitch) * partialTick : original;
	}

	public static float currentYaw() {
		return takeover ? PovLook.yaw() : latestYaw;
	}

	public static float currentPitch() {
		return takeover ? PovLook.pitch() : latestPitch;
	}

	public static void reset() {
		target = null;
		takeover = false;
		hasPose = false;
		previousYaw = previousPitch = currentYaw = currentPitch = latestYaw = latestPitch = 0.0F;
	}
}
