package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservationBudgetVerification {
	private ObservationBudgetVerification() {
	}

	public static int verify() {
		ObservationDispatchQueue<String> queue = new ObservationDispatchQueue<>(3, 2);
		assertTrue(queue.offer("agent-a"), "first observation request is queued");
		assertFalse(queue.offer("agent-a"), "duplicate observation request is coalesced");
		queue.offer("agent-b");
		queue.offer("agent-c");
		assertThrows(() -> queue.offer("agent-d"), "distinct request beyond capacity fails closed");
		List<String> first = new ArrayList<>();
		queue.drain(first::add);
		assertEquals(List.of("agent-a", "agent-b"), first, "drain is FIFO and tick bounded");
		List<String> second = new ArrayList<>();
		queue.drain(second::add);
		assertEquals(List.of("agent-c"), second, "remaining observation is deferred to the next drain");

		ObservationSectionCache<String, JsonObject> cache = new ObservationSectionCache<>(2, 10L, JsonObject::deepCopy);
		AtomicInteger loads = new AtomicInteger();
		JsonObject initial = cache.getOrCompute("agent-a", 100L, () -> value("v" + loads.incrementAndGet()));
		initial.addProperty("value", "caller mutation");
		assertEquals("v1", cache.getOrCompute("agent-a", 110L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"fresh cache reuse is defensively copied");
		assertEquals("v2", cache.getOrCompute("agent-a", 111L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"expired cache entry reloads");
		cache.invalidate("agent-a");
		assertEquals("v3", cache.getOrCompute("agent-a", 112L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"explicit invalidation reloads");
		return 9;
	}

	private static JsonObject value(String text) {
		JsonObject value = new JsonObject();
		value.addProperty("value", text);
		return value;
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

	private static void assertThrows(Runnable operation, String label) {
		try {
			operation.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError(label);
	}
}
