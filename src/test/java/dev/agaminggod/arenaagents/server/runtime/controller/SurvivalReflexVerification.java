package dev.agaminggod.arenaagents.server.runtime.controller;

public final class SurvivalReflexVerification {
	private SurvivalReflexVerification() {
	}

	public static int verify() {
		assertEquals(SurvivalReflex.SURFACE, SurvivalReflex.choose(
				new SurvivalThreat(true, 20, 300, true, true, true, 1.0D, 1.0D)),
				"low air has highest priority");
		assertEquals(SurvivalReflex.ESCAPE_SUFFOCATION, SurvivalReflex.choose(
				new SurvivalThreat(true, 300, 300, true, true, true, 1.0D, 1.0D)),
				"suffocation outranks fire");
		assertEquals(SurvivalReflex.LEAVE_FIRE, SurvivalReflex.choose(
				new SurvivalThreat(true, 300, 300, false, true, true, 1.0D, 1.0D)),
				"fire outranks a dangerous fall");
		assertEquals(SurvivalReflex.STOP_DANGEROUS_FALL, SurvivalReflex.choose(
				new SurvivalThreat(false, 300, 300, false, true, true, 1.0D, 1.0D)),
				"dangerous fall outranks attacker");
		assertEquals(SurvivalReflex.BACK_AWAY, SurvivalReflex.choose(
				new SurvivalThreat(false, 300, 300, false, false, true, 1.0D, 1.0D)),
				"recent damage backs away");
		assertEquals(SurvivalReflex.NONE, SurvivalReflex.choose(
				new SurvivalThreat(false, 300, 300, false, false, false, 0.0D, 0.0D)),
				"safe player has no forced behavior");
		return 6;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
