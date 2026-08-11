package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.List;

public final class PendingSpawnCancellationLedgerVerification {
	private PendingSpawnCancellationLedgerVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " pending spawn cancellation assertions");
	}

	public static int verify() {
		AgentId first = AgentId.random();
		AgentId second = AgentId.random();
		PendingSpawnCancellationLedger ledger = new PendingSpawnCancellationLedger(30_000L);

		ledger.record(first, 1_000L);
		assertEquals(List.of(first), ledger.active(30_999L), "cancellation remains active before expiry");
		assertEquals(List.of(), ledger.active(31_000L), "cancellation expires at its deadline");

		ledger.record(first, 40_000L);
		ledger.record(second, 40_001L);
		ledger.record(first, 50_000L);
		assertEquals(List.of(first, second), ledger.active(60_000L), "re-recording extends without duplicating an agent");
		assertEquals(List.of(first), ledger.active(70_001L), "independent expirations are pruned deterministically");
		return 4;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
