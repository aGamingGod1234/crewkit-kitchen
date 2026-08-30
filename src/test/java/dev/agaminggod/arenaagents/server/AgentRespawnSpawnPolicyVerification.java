package dev.agaminggod.arenaagents.server;

public final class AgentRespawnSpawnPolicyVerification {
	private AgentRespawnSpawnPolicyVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("PASS: " + verify() + " respawn and lifecycle assertions");
	}

	public static int verify() {
		long deadline = 2_000L;
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_REMOVAL,
				AgentRespawnSpawnPolicy.decide(false, true, 1_999L, deadline),
				"old fake player must leave before a replacement is requested"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, deadline, deadline),
				"stuck fake-player removal times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.REQUEST_SPAWN,
				AgentRespawnSpawnPolicy.decide(false, false, 1_000L, deadline),
				"replacement is requested only after absence is confirmed"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_SPAWN,
				AgentRespawnSpawnPolicy.decide(true, false, 1_999L, deadline),
				"accepted Carpet spawn remains pending until the player appears"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.VERIFY_PLAYER,
				AgentRespawnSpawnPolicy.decide(true, true, 1_500L, deadline),
				"physical player presence advances to verification"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(true, false, deadline, deadline),
				"accepted spawn without a physical player times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.WAIT_FOR_NATURAL_REMOVAL,
				AgentRespawnSpawnPolicy.existingPlayerAction(false),
				"a dead Carpet player must finish its own disconnect without a second kill"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.REMOVE_STALE_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true),
				"a stale live player must be removed before replacement"
		);
		CodexAgentManager.RespawnRemovalDecision beforeGrace = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 999L, 1_000L, deadline);
		assertFalse(
				beforeGrace.requestRemoval(),
				"respawn waits for the fixed removal grace deadline"
		);
		CodexAgentManager.RespawnRemovalDecision firstRemoval = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 1_000L, 1_000L, deadline);
		assertTrue(
				firstRemoval.requestRemoval(),
				"respawn requests stale-player removal once after grace"
		);
		assertEquals(deadline, firstRemoval.deadlineEpochMs(), "stale-player removal retains the original deadline");
		CodexAgentManager.RespawnRemovalDecision repeatedRemoval = CodexAgentManager.respawnRemovalDecision(
				false, true, true, 3_000L, 1_000L, firstRemoval.deadlineEpochMs());
		assertFalse(
				repeatedRemoval.requestRemoval(),
				"respawn does not request removal again while waiting for the fixed deadline"
		);
		assertEquals(deadline, repeatedRemoval.deadlineEpochMs(), "waiting does not extend the removal deadline");
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, 2_000L, deadline),
				"one removal request cannot extend the original removal deadline"
		);

		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger cleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException primary = new IllegalStateException("runtime hook failed");
		IllegalArgumentException secondary = new IllegalArgumentException("ticket cleanup failed");
		RuntimeException observed = expectRuntimeFailure(() -> CodexAgentManager.releaseOnce(
				released,
				() -> {
					cleanupCalls.incrementAndGet();
					throw primary;
				},
				cleanupCalls::incrementAndGet,
				() -> {
					cleanupCalls.incrementAndGet();
					throw secondary;
				}
		));
		assertSame(primary, observed, "shutdown reports the first cleanup failure");
		assertEquals(3, cleanupCalls.get(), "shutdown attempts every owned cleanup after a failure");
		assertEquals(1, observed.getSuppressed().length, "shutdown retains later cleanup failures");
		assertSame(secondary, observed.getSuppressed()[0], "shutdown suppresses the later failure on the primary");
		CodexAgentManager.releaseOnce(released, cleanupCalls::incrementAndGet);
		assertEquals(3, cleanupCalls.get(), "repeated shutdown is idempotent after a failed first release");

		java.util.concurrent.atomic.AtomicInteger physicalCleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger durableDeleteCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException cleanupFailure = new IllegalStateException("player removal failed");
		assertSame(cleanupFailure, expectRuntimeFailure(() -> CodexAgentManager.deleteAfterRequiredCleanup(
				() -> {
					physicalCleanupCalls.incrementAndGet();
					throw cleanupFailure;
				},
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		)), "failed physical cleanup is reported");
		assertEquals(0, durableDeleteCalls.get(), "failed player cleanup leaves the registry record retryable");
		assertEquals("removed", CodexAgentManager.deleteAfterRequiredCleanup(
				physicalCleanupCalls::incrementAndGet,
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		), "successful retry reaches durable deletion");
		assertEquals(2, physicalCleanupCalls.get(), "retry performs physical cleanup again");
		assertEquals(1, durableDeleteCalls.get(), "durable deletion happens once after cleanup succeeds");
		return 24;
	}

	private static RuntimeException expectRuntimeFailure(Runnable operation) {
		try {
			operation.run();
		} catch (RuntimeException failure) {
			return failure;
		}
		throw new AssertionError("expected cleanup failure");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected same instance");
	}
}
