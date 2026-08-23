package dev.agaminggod.arenaagents.server.runtime.controller;

public final class CombatPolicyVerification {
	private CombatPolicyVerification() {
	}

	public static int verify() {
		CombatPolicy policy = new CombatPolicy();
		CombatIntent fight = new CombatIntent("target", 3.0D, 30_000L, CombatIntent.Mode.FIGHT);
		assertEquals(CombatDecision.APPROACH,
				policy.decide(new CombatSnapshot(true, false, 8.0D, true), fight),
				"fight approaches outside desired range");
		assertEquals(CombatDecision.ATTACK,
				policy.decide(new CombatSnapshot(true, false, 2.5D, true), fight),
				"fight attacks in reach when ready");
		assertEquals(CombatDecision.FACE,
				policy.decide(new CombatSnapshot(true, false, 2.5D, false), fight),
				"fight faces while attack cools down");
		assertEquals(CombatDecision.APPROACH,
				policy.decide(new CombatSnapshot(true, false, 2.5D, true, false, 0.0D), fight),
				"fight pursues a target through a blocked line of sight");
		assertEquals(CombatDecision.FACE,
				policy.decide(new CombatSnapshot(true, false, 2.5D, true, true, 18.0D), fight),
				"fight settles aim before attacking");
		assertEquals(CombatDecision.TARGET_INVALID,
				policy.decide(new CombatSnapshot(true, true, 2.5D, true), fight),
				"creative or spectator players are invalid targets");
		assertEquals(CombatDecision.RETREAT,
				policy.decide(
						new CombatSnapshot(true, false, 4.0D, true),
						new CombatIntent("target", 12.0D, 30_000L, CombatIntent.Mode.FLEE)
				),
				"flee retreats inside requested distance");
		assertEquals(CombatDecision.HOLD,
				policy.decide(
						new CombatSnapshot(true, false, 14.0D, true),
						new CombatIntent("target", 12.0D, 30_000L, CombatIntent.Mode.FLEE)
				),
				"flee completes outside requested distance");
		assertEquals(CombatDecision.APPROACH,
				policy.decide(
						new CombatSnapshot(true, false, 8.0D, true),
						new CombatIntent("target", 3.0D, 30_000L, CombatIntent.Mode.FOLLOW)
				),
				"follow approaches without attacking");
		assertEquals(CombatDecision.TARGET_DEFEATED,
				policy.decide(new CombatSnapshot(false, false, 0.0D, false), fight),
				"dead target terminates");
		return 10;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
