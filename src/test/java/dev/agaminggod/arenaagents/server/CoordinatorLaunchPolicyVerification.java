package dev.agaminggod.arenaagents.server;

public final class CoordinatorLaunchPolicyVerification {
	private CoordinatorLaunchPolicyVerification() {
	}

	public static int verify() {
		long createdAt = 10_000L;
		assertFalse(CoordinatorLaunchPolicy.shouldStart(false, false, createdAt, createdAt), "startup grace prevents a duplicate coordinator");
		assertFalse(CoordinatorLaunchPolicy.shouldStart(true, false, createdAt, createdAt + 5_000L), "authenticated coordinator is reused");
		assertFalse(CoordinatorLaunchPolicy.shouldStart(false, true, createdAt, createdAt + 5_000L), "live owned coordinator is retained");
		assertTrue(CoordinatorLaunchPolicy.shouldStart(false, false, createdAt, createdAt + CoordinatorLaunchPolicy.STARTUP_GRACE_MS), "missing coordinator starts after grace");
		CoordinatorLaunchPolicy.RestartBudget budget = new CoordinatorLaunchPolicy.RestartBudget();
		assertTrue(budget.recordUnexpectedExit(), "first coordinator crash is restartable");
		assertEquals(2_000L, budget.nextDelayMs(), "first restart delay is bounded");
		assertTrue(budget.recordUnexpectedExit(), "second coordinator crash is restartable");
		assertEquals(5_000L, budget.nextDelayMs(), "second restart delay is bounded");
		assertTrue(budget.recordUnexpectedExit(), "third coordinator crash is restartable");
		assertEquals(15_000L, budget.nextDelayMs(), "third restart delay is bounded");
		assertFalse(budget.recordUnexpectedExit(), "restart budget is exhausted after three crashes");
		budget.resetAfterAuthentication();
		assertTrue(budget.recordUnexpectedExit(), "authentication resets the restart budget");
		assertEquals(2_000L, budget.nextDelayMs(), "reset budget uses the first delay");
		return 12;
	}

	private static void assertEquals(long expected, long actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
