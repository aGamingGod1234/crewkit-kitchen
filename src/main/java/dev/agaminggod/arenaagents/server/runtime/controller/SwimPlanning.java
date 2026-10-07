package dev.agaminggod.arenaagents.server.runtime.controller;

/**
 * Pure water rules for the movement the model already chose (flee_from, fight_target, navigate_to). They only make
 * that movement work in water the way a player's does: hold jump to rise and stay afloat, sprint to swim while
 * submerged, look up to surface, and never call an escape done while the agent is under water. Nothing here starts
 * an action; control and control_sequence frames keep the model's own explicit jump and sprint keys.
 */
public final class SwimPlanning {
	/** Vanilla drains one air point per tick under water and starts drowning damage at zero. */
	public static final int AIR_TICKS_PER_SECOND = 20;
	/** Looking this far up while sprint-swimming makes vanilla's swim physics rise toward the surface. */
	public static final float SURFACING_PITCH = -35.0F;
	/** navigate_to gives rising to the surface this long before it reports the start as unreachable. */
	public static final long SURFACING_TIMEOUT_MS = 10_000L;
	/** fight_target in water: a fair swim attempt (10 s) before TARGET_UNREACHABLE; on land it stays 5 s. */
	public static final int WATER_UNREACHABLE_TICKS = 200;
	/**
	 * Flee steering treats a heading through deep water as this many degrees worse than a land heading, so land within
	 * 90 degrees of "away" wins, but open water straight away still beats running back toward the threat.
	 */
	public static final float WATER_PENALTY_DEGREES = 90.0F;
	/** Drowned (and guardians) swim faster than a player; water is then a last resort. */
	public static final float AQUATIC_THREAT_WATER_PENALTY_DEGREES = 180.0F;

	private SwimPlanning() {
	}

	/** In water a player holds jump to rise and stay afloat; at a shore it also climbs out (vanilla water step). */
	public static boolean holdJump(boolean inWater) {
		return inWater;
	}

	/** Sprint-swim while the eyes are under water and moving forward, as a player does; never at the surface. */
	public static boolean swimSprint(boolean inWater, boolean eyesInWater, float forward, int foodLevel) {
		return inWater && eyesInWater && forward > 0.0F && foodLevel > 6;
	}

	/** View pitch for movement: look up to surface while the eyes are under water, otherwise the caller's pitch. */
	public static float swimPitch(boolean inWater, boolean eyesInWater, float pitch) {
		return inWater && eyesInWater ? SURFACING_PITCH : pitch;
	}

	/**
	 * A flee may report ESCAPED, TARGET_LOST or TARGET_GONE only once the agent breathes and has footing: out of the
	 * water, or standing in shallow water. Ending afloat or submerged hands the model a body that sinks.
	 */
	public static boolean fleeMayEnd(boolean inWater, boolean eyesInWater, boolean onGround) {
		return !eyesInWater && (!inWater || onGround);
	}

	/**
	 * Fight in water: hold jump (stay up, reach targets at or above) unless the target is clearly below, where
	 * releasing it lets the agent sink and sprint-swim down toward it.
	 */
	public static boolean fightJump(boolean inWater, double targetDy) {
		return inWater && targetDy > -1.0D;
	}

	/** Ticks without a hit or gained ground before TARGET_UNREACHABLE; swimming gets a fair attempt. */
	public static int unreachableTicks(boolean agentInWater, boolean targetInWater, int landTicks) {
		return agentInWater || targetInWater ? Math.max(landTicks, WATER_UNREACHABLE_TICKS) : landTicks;
	}

	/** navigate_to rises to the surface first while the eyes are under water (no path can start there). */
	public static boolean needsSurfacing(boolean inWater, boolean eyesInWater) {
		return inWater && eyesInWater;
	}

	/** Seconds of breath left before drowning damage starts (Respiration only makes it last longer). */
	public static double airSecondsLeft(int air) {
		return Math.max(0, air) / (double) AIR_TICKS_PER_SECOND;
	}
}
