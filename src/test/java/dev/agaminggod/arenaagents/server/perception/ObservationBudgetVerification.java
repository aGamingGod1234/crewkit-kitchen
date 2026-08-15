package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;

public final class ObservationBudgetVerification {
	private ObservationBudgetVerification() {
	}

	public static int verify() {
		JsonObject previous = observation(20.0D, false, 0.0D, "idle");
		JsonObject current = observation(18.0D, true, 2.0D, "mine");
		AttentionFactDelta delta = AttentionFactDelta.between(previous, current, 7L, 123L);
		assertTrue(delta.attention(), "material factual changes gain attention");
		assertEquals(7L, delta.eventSequence(), "event sequence is retained exactly");
		assertTrue(delta.changedFacts().contains("player.health"), "health delta is factual");
		assertTrue(delta.changedFacts().contains("player.onFire"), "fire delta is factual");
		assertTrue(delta.changedFacts().contains("player.fallDistance"), "fall delta is factual");
		assertTrue(delta.changedFacts().contains("currentAction"), "action delta is factual");
		assertFalse(delta.changedFacts().stream().anyMatch(path -> path.contains("danger") || path.contains("flee") || path.contains("fight")),
				"deltas do not invent tactical labels");
		AttentionFactDelta initialDelta = AttentionFactDelta.between(null, current, 1L, 123L);
		assertFalse(initialDelta.attention(), "initial observation is not attention");
		assertEquals(List.of(), initialDelta.changedFacts(), "initial observation has no changed facts");
		JsonObject heartbeat = current.deepCopy();
		heartbeat.getAsJsonObject("world").addProperty("gameTime", 99L);
		heartbeat.getAsJsonObject("world").addProperty("dayTime", 99L);
		AttentionFactDelta timeOnly = AttentionFactDelta.between(current, heartbeat, 8L, 124L);
		assertFalse(timeOnly.attention(), "world clock heartbeat is not attention");
		assertEquals(List.of(), timeOnly.changedFacts(), "world clock heartbeat has no changed facts");
		JsonObject crowdedBefore = observation(20.0D, false, 0.0D, "idle");
		JsonObject crowdedAfter = observation(20.0D, false, 0.0D, "idle");
		for (int index = 0; index < 64; index++) {
			crowdedBefore.getAsJsonArray("entities").add(entity(index));
			crowdedAfter.getAsJsonArray("entities").add(entity(index + 64));
		}
		for (int index = 0; index < 128; index++) {
			crowdedBefore.getAsJsonArray("blocks").add(block(index));
			crowdedAfter.getAsJsonArray("blocks").add(block(index + 128));
		}
		AttentionFactDelta crowded = AttentionFactDelta.between(crowdedBefore, crowdedAfter, 9L, 125L);
		assertTrue(crowded.changedFacts().size() <= 256, "changed facts remain protocol bounded at maximum disjoint entity and block changes");
		assertTrue(crowded.changedFacts().contains("entities"), "entity overflow coalesces to a factual aggregate");
		assertTrue(crowded.changedFacts().contains("blocks"), "block overflow coalesces to a factual aggregate");
		MultiplexedServerBridge.PublishedObservationState publication = new MultiplexedServerBridge.PublishedObservationState(16);
		AgentId retryAgent = AgentId.parse("01234567-89ab-cdef-0123-456789abcdef");
		publication.commit(retryAgent, previous);
		AttentionFactDelta failedPublication = publication.delta(retryAgent, current, 10L, 126L);
		assertTrue(failedPublication.attention(), "failed enqueue sees the material change");
		assertTrue(publication.markDirty(retryAgent), "failed enqueue retains a bounded retry marker");
		AttentionFactDelta retryPublication = publication.delta(retryAgent, current, 11L, 127L);
		assertTrue(retryPublication.attention() && retryPublication.changedFacts().contains("player.health"),
				"retry retains the last delivered baseline and factual material change");
		publication.commit(retryAgent, current);
		assertFalse(publication.delta(retryAgent, current, 12L, 128L).attention(), "successful retry advances the delivered baseline");

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

		Vec3 eye = new Vec3(0.0D, 1.6D, 0.0D);
		Vec3 forward = new Vec3(0.0D, 0.0D, 1.0D);
		assertTrue(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(0.0D, 1.6D, 5.0D)),
				"a target in front is visible to the current view");
		assertFalse(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(0.0D, 1.6D, -5.0D)),
				"a target behind the player is not reported as visible");
		assertTrue(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(1.0D, 1.6D, 0.0D)),
				"touch-distance awareness does not disappear outside the camera cone");
		assertFalse(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(8.0D, 1.6D, 0.0D)),
				"a distant side target is outside the bounded visual cone");
		ObservationDispatchQueue<String> burst = new ObservationDispatchQueue<>(16, 8);
		for (int index = 0; index < 16; index++) {
			String agentId = "agent-" + index;
			assertTrue(burst.offer(agentId), "each agent enters a bounded burst once");
			assertFalse(burst.offer(agentId), "same agent burst events coalesce");
		}
		List<String> burstFirst = new ArrayList<>();
		List<String> burstSecond = new ArrayList<>();
		burst.drain(burstFirst::add);
		burst.drain(burstSecond::add);
		assertEquals(8, burstFirst.size(), "first drain obeys eight-observation budget");
		assertEquals(8, burstSecond.size(), "second drain serves every remaining agent");
		assertEquals(0, burst.pendingCount(), "sixteen-agent burst clears in two drains");
		return 38;
	}

	private static JsonObject observation(double health, boolean onFire, double fallDistance, String actionType) {
		JsonObject value = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("health", health);
		player.addProperty("onFire", onFire);
		player.addProperty("fallDistance", fallDistance);
		value.add("player", player);
		value.add("inventory", new JsonObject());
		value.add("entities", new com.google.gson.JsonArray());
		value.add("blocks", new com.google.gson.JsonArray());
		JsonObject world = new JsonObject();
		world.addProperty("dimension", "minecraft:overworld");
		world.addProperty("gameTime", 0L);
		world.addProperty("dayTime", 0L);
		world.addProperty("raining", false);
		world.addProperty("thundering", false);
		value.add("world", world);
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", !"idle".equals(actionType));
		currentAction.addProperty("actionType", actionType);
		value.add("currentAction", currentAction);
		return value;
	}

	private static JsonObject entity(int index) {
		JsonObject value = new JsonObject();
		value.addProperty("uuid", String.format("00000000-0000-0000-0000-%012d", index));
		value.addProperty("type", "minecraft:zombie");
		return value;
	}

	private static JsonObject block(int index) {
		JsonObject value = new JsonObject();
		value.addProperty("x", index);
		value.addProperty("y", 64);
		value.addProperty("z", 0);
		value.addProperty("blockId", "minecraft:stone");
		return value;
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
