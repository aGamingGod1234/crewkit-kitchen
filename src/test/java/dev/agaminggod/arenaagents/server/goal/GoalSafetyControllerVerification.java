package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.server.goal.GoalSafetyController.HazardSnapshot;
import dev.agaminggod.arenaagents.server.goal.GoalSafetyController.SafetyDirective;
import java.util.Arrays;

public final class GoalSafetyControllerVerification {
	private GoalSafetyControllerVerification() {
	}

	public static int verify() {
		assertEquals(SafetyDirective.SWIM_UP, decide(false, true, false, false, 20, false, false, false, false), "surface while drowning");
		assertEquals(SafetyDirective.LEAVE_LAVA, decide(false, false, true, true, 300, false, false, true, false), "leave lava before lesser hazards");
		assertEquals(SafetyDirective.LEAVE_FIRE, decide(false, false, false, true, 300, false, false, true, false), "leave fire only over a verified route");
		assertEquals(SafetyDirective.RAISE_EQUIPPED_SHIELD, decide(false, false, false, false, 300, true, true, false, true), "raise an already equipped shield after repeated damage");
		assertEquals(SafetyDirective.RETREAT_FROM_REPEATED_DAMAGE, decide(false, false, false, false, 300, true, false, false, true), "retreat only over a verified route");
		assertEquals(SafetyDirective.NONE, decide(false, false, false, false, 300, false, false, false, false), "healthy bodies receive no invented strategy");
		assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("ATTACK")), "safety cannot represent attack");
		assertTrue(Arrays.stream(SafetyDirective.values()).noneMatch(value -> value.name().equals("CONSUME_ITEM")), "safety cannot represent item spending");
		return 8;
	}

	private static SafetyDirective decide(
			boolean dead, boolean inWater, boolean inLava, boolean onFire, int air,
			boolean repeatedDamage, boolean shield, boolean escape, boolean retreat
	) {
		return GoalSafetyController.decide(new HazardSnapshot(
				dead, inWater, inLava, onFire, air, 40, repeatedDamage, shield, escape, retreat));
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		System.out.println("PASS: " + label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
		System.out.println("PASS: " + label);
	}
}
