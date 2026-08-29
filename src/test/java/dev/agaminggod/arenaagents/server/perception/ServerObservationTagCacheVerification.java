package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import net.minecraft.world.item.Items;

/** Verifies that tag caching preserves values while isolating each JSON consumer. */
public final class ServerObservationTagCacheVerification {
	private ServerObservationTagCacheVerification() {
	}

	public static int verify() {
		ServerObservationCollector.clearTagCache();
		var holder = Items.DIAMOND.builtInRegistryHolder();

		JsonArray first = ServerObservationCollector.tags(holder);
		JsonArray expected = new JsonArray();
		holder.tags().map(tag -> "#" + tag.location().toString()).sorted()
				.limit(ServerObservationCollector.MAX_OBSERVATION_TAGS).forEach(expected::add);
		assertEquals(expected.toString(), first.toString(), "tag values preserve ordering and limit");

		first.add("#test:caller_mutation");
		JsonArray second = ServerObservationCollector.tags(holder);
		assertEquals(expected.toString(), second.toString(), "each consumer receives a defensive JsonArray");
		assertTrue(first != second, "each tags call returns a fresh JsonArray");

		ServerObservationCollector.clearTagCache();
		JsonArray afterReload = ServerObservationCollector.tags(holder);
		assertEquals(expected.toString(), afterReload.toString(), "cache invalidation preserves recomputed values");
		return 4;
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " server observation tag cache assertions");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
