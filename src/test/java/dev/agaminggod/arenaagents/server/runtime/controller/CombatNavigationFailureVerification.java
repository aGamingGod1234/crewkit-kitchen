package dev.agaminggod.arenaagents.server.runtime.controller;

import net.minecraft.world.level.Level;

public final class CombatNavigationFailureVerification {
	private CombatNavigationFailureVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " combat navigation failure assertions");
	}

	public static int verify() {
		assertTrue(ServerCombatController.remainsInDimension(Level.OVERWORLD, Level.OVERWORLD),
				"combat remains valid in its starting dimension");
		assertTrue(!ServerCombatController.remainsInDimension(Level.OVERWORLD, Level.END),
				"combat rejects a target after the player changes dimension");
		assertEquals(7_500L, ServerCombatController.remainingNavigationTimeout(10_000L, 2_500L),
				"combat passes its remaining action timeout to navigation");
		assertEquals(1_000L, ServerCombatController.remainingNavigationTimeout(10_000L, Long.MAX_VALUE),
				"combat bounds remaining navigation timeout after elapsed-time saturation");
		ServerController.TickResult navigationFailure = ServerController.TickResult.failed(
				"PATH_BLOCKED",
				"Navigation could not recover from repeated stalls",
				0.25D
		);

		ServerController.TickResult combatResult = ServerCombatController.resolveNavigationTick(
				navigationFailure,
				0.75D
		);

		assertEquals(ServerController.State.FAILED, combatResult.state(), "nested navigation failure state");
		assertEquals("PATH_BLOCKED", combatResult.reasonCode(), "nested navigation failure reason");
		assertEquals(navigationFailure.message(), combatResult.message(), "nested navigation failure message");
		assertEquals(0.25D, combatResult.progress(), "nested navigation failure progress");
		return 8;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
