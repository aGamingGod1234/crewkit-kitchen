package dev.agaminggod.arenaagents.server.runtime.controller;

public final class ItemPickupProgressVerification {
	private ItemPickupProgressVerification() {
	}

	public static int verify() {
		assertEquals(ItemPickupProgress.Decision.RUNNING,
				ItemPickupProgress.evaluate(2, 2, true, false), "approaching a live item keeps moving");
		assertEquals(ItemPickupProgress.Decision.SUCCEEDED,
				ItemPickupProgress.evaluate(2, 3, false, false), "only an observed inventory increase succeeds");
		assertEquals(ItemPickupProgress.Decision.ITEM_UNAVAILABLE,
				ItemPickupProgress.evaluate(2, 2, false, false), "a vanished item never becomes synthetic loot");
		assertEquals(ItemPickupProgress.Decision.TIMED_OUT,
				ItemPickupProgress.evaluate(2, 2, true, true), "a live unreachable item times out");
		return 4;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
	}
}
