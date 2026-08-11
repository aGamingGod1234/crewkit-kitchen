package dev.agaminggod.arenaagents.server.runtime;

public final class ActionProgressTrackerVerification {
	private ActionProgressTrackerVerification() {
	}

	public static int verify() {
		ActionProgressTracker tracker = new ActionProgressTracker(10.0D, 1_000L, 3_000L);
		assertEquals(false, tracker.stalled(9.95D, 3_000L), "minor jitter is not progress");
		assertEquals(false, tracker.stalled(9.0D, 3_500L), "material progress resets the stall clock");
		assertEquals(false, tracker.stalled(8.98D, 6_499L), "movement remains active before the stall deadline");
		assertEquals(true, tracker.stalled(8.98D, 6_500L), "movement fails at the stall deadline");
		return 4;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
