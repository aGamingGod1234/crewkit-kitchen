package dev.agaminggod.arenaagents.client.navigation;

import java.util.concurrent.atomic.AtomicInteger;

public final class MinecraftWalkabilityViewVerification {
	private MinecraftWalkabilityViewVerification() {
	}

	public static int verify() {
		AtomicInteger reads = new AtomicInteger();
		WalkabilityView.Cell unloaded = MinecraftWalkabilityView.inspectCachedCell(false, () -> {
			reads.incrementAndGet();
			return new MinecraftWalkabilityView.CellFacts(false, true, false);
		});
		assertEquals(WalkabilityView.Cell.UNLOADED, unloaded, "uncached cell is unloaded");
		assertEquals(0, reads.get(), "uncached cell performs no state read");

		assertEquals(
				WalkabilityView.Cell.HAZARD,
				MinecraftWalkabilityView.inspectCachedCell(
						true,
						() -> new MinecraftWalkabilityView.CellFacts(true, true, false)
				),
				"hazard classification precedes passability"
		);
		assertEquals(
				WalkabilityView.Cell.CLEAR,
				MinecraftWalkabilityView.inspectCachedCell(
						true,
						() -> new MinecraftWalkabilityView.CellFacts(false, true, false)
				),
				"empty collision cell is clear"
		);
		assertEquals(
				WalkabilityView.Cell.SAFE_SUPPORT,
				MinecraftWalkabilityView.inspectCachedCell(
						true,
						() -> new MinecraftWalkabilityView.CellFacts(false, false, true)
				),
				"full safe collision is support"
		);
		assertEquals(
				WalkabilityView.Cell.BLOCKED,
				MinecraftWalkabilityView.inspectCachedCell(
						true,
						() -> new MinecraftWalkabilityView.CellFacts(false, false, false)
				),
				"partial collision is blocked"
		);
		return 6;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
