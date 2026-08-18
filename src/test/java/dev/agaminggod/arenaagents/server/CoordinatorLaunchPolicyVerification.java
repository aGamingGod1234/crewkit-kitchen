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
		return 4;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
