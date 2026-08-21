package dev.agaminggod.arenaagents.server.runtime.controller;

public final class CombatNavigationFailureVerification {
	private CombatNavigationFailureVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " combat navigation failure assertions");
	}

	public static int verify() {
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
		return 4;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
