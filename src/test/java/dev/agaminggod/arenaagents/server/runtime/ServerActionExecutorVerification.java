package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerActionExecutorVerification {
	private ServerActionExecutorVerification() {
	}

	public static int verify() {
		ServerTransactionAdapter.TerminalGate gate = new ServerTransactionAdapter.TerminalGate();
		ServerTransactionAdapter.TickResult success = ServerTransactionAdapter.TickResult.succeeded(
				"TRANSFER_CONFIRMED", "Transfer confirmed");
		ServerTransactionAdapter.TickResult conflict = ServerTransactionAdapter.TickResult.failed(
				"TRANSACTION_CONFLICT", "Unexpected slot changed");
		assertEquals(success, gate.finish(success), "first terminal result is retained");
		assertEquals(success, gate.finish(conflict), "later terminal result cannot replace the first");

		AtomicInteger cleanupCalls = new AtomicInteger();
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		assertEquals(1, cleanupCalls.get(), "menu, carried stack, use state, and lease cleanup runs once");
		ServerTransactionAdapter.TerminalGate retryableCleanup = new ServerTransactionAdapter.TerminalGate();
		AtomicInteger cleanupAttempts = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> retryableCleanup.cleanupOnce(() -> {
			cleanupAttempts.incrementAndGet();
			throw new IllegalStateException("first cleanup failed");
		}), "failed cleanup remains retryable");
		assertFalse(retryableCleanup.cleanupComplete(), "failed cleanup is not reported as clean");
		retryableCleanup.cleanupOnce(cleanupAttempts::incrementAndGet);
		assertEquals(2, cleanupAttempts.get(), "cleanup retries after a failed attempt");
		assertTrue(retryableCleanup.cleanupComplete(), "successful retry marks cleanup complete");

		AtomicInteger bestEffortSteps = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> ServerTransactionAdapter.runBestEffort(
				() -> { bestEffortSteps.incrementAndGet(); throw new IllegalStateException("menu close failed"); },
				bestEffortSteps::incrementAndGet,
				bestEffortSteps::incrementAndGet
		), "best-effort cleanup reports its first failure");
		assertEquals(3, bestEffortSteps.get(), "controller stop and player stop run after an earlier cleanup failure");

		ServerActionExecutor.CleanupRetry<String> retainedCleanup = new ServerActionExecutor.CleanupRetry<>();
		retainedCleanup.retain("terminal-result");
		assertThrows(IllegalStateException.class, () -> retainedCleanup.complete(() -> {
			throw new IllegalStateException("teardown failed");
		}), "failed executor teardown keeps its terminal result pending");
		assertTrue(retainedCleanup.hasPending(), "failed executor teardown retains the cleanup handle");
		retainedCleanup.retain("later-result");
		assertEquals("terminal-result", retainedCleanup.complete(() -> { }),
				"executor finish publishes only the first retained result after cleanup succeeds");
		assertFalse(retainedCleanup.hasPending(), "successful retry releases the cleanup handle");

		ResourceLeaseManager leases = new ResourceLeaseManager();
		AgentId owner = AgentId.random();
		assertTrue(leases.acquire("container:minecraft:overworld:42", owner, 1_000L, 5_000L),
				"transaction lease is acquired");
		assertTrue(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_001L),
				"active transaction owns its lease");
		leases.releaseAll(owner);
		assertFalse(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_002L),
				"cancellation releases every transaction lease");

		for (String reason : new String[] {"ACTION_CANCELLED", "ACTION_TIMED_OUT", "THREAT_DETECTED"}) {
			ServerTransactionAdapter.TerminalGate interrupted = new ServerTransactionAdapter.TerminalGate();
			AtomicInteger interruptedCleanup = new AtomicInteger();
			interrupted.finish(ServerTransactionAdapter.TickResult.failed(reason, reason));
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			assertEquals(1, interruptedCleanup.get(), reason + " cleanup runs once");
		}
		assertEquals(
				java.util.List.of("transfer_container"),
				ServerObservationCollector.transactionCapabilities("minecraft:chest"),
				"chest observation exposes only the supported transfer adapter"
		);
		assertEquals(
				java.util.List.of("furnace_transaction"),
				ServerObservationCollector.transactionCapabilities("minecraft:blast_furnace"),
				"furnace variants expose only the supported furnace adapter"
		);
		assertEquals(
				java.util.List.of(),
				ServerObservationCollector.transactionCapabilities("modded:machine"),
				"modded menu capabilities fail closed"
		);
		AgentId progressAgent = AgentId.random();
		ServerActionProgress progress = new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 0.5D, 250L, 1_750_000_000_250L
		);
		assertEquals(progressAgent, progress.agentId(), "progress retains agent identity");
		assertEquals(0.5D, progress.progress(), "progress retains bounded fraction");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 1.1D, 250L, 1_750_000_000_250L
		), "progress rejects fractions above one");
		return 25;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + type.getSimpleName());
	}
}
