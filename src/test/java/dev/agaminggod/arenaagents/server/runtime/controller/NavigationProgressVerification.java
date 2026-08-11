package dev.agaminggod.arenaagents.server.runtime.controller;

public final class NavigationProgressVerification {
	private NavigationProgressVerification() {
	}

	public static int verify() {
		WaypointProgress progress = new WaypointProgress(10.0D, 1_000L, 4_000L, 3);

		WaypointProgress.Update advanced = progress.observe(8.0D, true, 1_100L);
		assertTrue(advanced.advanceWaypoint(), "waypoint tolerance advances the plan");
		assertEquals(WaypointProgress.Decision.CONTINUE, advanced.decision(), "advance keeps navigation running");
		assertBounded(advanced.progress());

		WaypointProgress.Update stalled = progress.observe(8.0D, false, 5_200L);
		assertEquals(WaypointProgress.Decision.REPLAN, stalled.decision(), "four seconds without progress replans");
		progress.replanned(8.0D, 5_200L);
		progress.observe(8.0D, false, 9_300L);
		progress.replanned(8.0D, 9_300L);
		progress.observe(8.0D, false, 13_400L);
		progress.replanned(8.0D, 13_400L);
		WaypointProgress.Update exhausted = progress.observe(8.0D, false, 17_500L);
		assertEquals(WaypointProgress.Decision.FAIL, exhausted.decision(), "three replans exhaust recovery");
		assertBounded(exhausted.progress());
		return 6;
	}

	private static void assertBounded(double value) {
		if (value < 0.0D || value > 1.0D || !Double.isFinite(value)) {
			throw new AssertionError("progress must remain in [0, 1]: " + value);
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
