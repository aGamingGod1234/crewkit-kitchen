package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
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
		assertFalse(delta.changedFacts().contains("player.fallDistance"),
				"ordinary fall progress stays quiet below the hazard threshold");
		assertFalse(delta.changedFacts().contains("currentAction"),
				"action lifecycle is delivered by typed action events instead of attention");
		assertFalse(delta.changedFacts().stream().anyMatch(path -> path.contains("danger") || path.contains("flee") || path.contains("fight")),
				"deltas do not invent tactical labels");
		JsonObject movingBefore = movingObservation(0.0D, 0.0D, "minecraft:air", 8.0D);
		JsonObject movingAfter = movingObservation(1.0D, 15.0D, "minecraft:wooden_pickaxe", 7.0D);
		AttentionFactDelta movementDelta = AttentionFactDelta.between(movingBefore, movingAfter, 8L, 124L);
		assertFalse(movementDelta.attention(), "an active action does not interrupt itself with movement observations");
		assertEquals(List.of(), movementDelta.changedFacts(), "self-generated action churn has no attention facts");
		JsonObject factualBefore = observation(20.0D, false, 0.0D, "idle");
		JsonObject factualAfter = factualBefore.deepCopy();
		factualAfter.getAsJsonObject("player").addProperty("health", 18.0D);
		factualAfter.getAsJsonObject("inventory").addProperty("selectedItem", "minecraft:torch");
		factualAfter.getAsJsonArray("entities").add(entity(1));
		factualAfter.getAsJsonArray("blocks").add(block(1));
		factualAfter.getAsJsonObject("world").addProperty("raining", true);
		AttentionFactDelta worldDelta = AttentionFactDelta.between(factualBefore, factualAfter, 8L, 124L);
		assertTrue(worldDelta.attention(), "idle observations publish non-action factual changes");
		assertTrue(worldDelta.changedFacts().contains("player.health"), "damage changes are eligible without action progress");
		assertTrue(worldDelta.changedFacts().contains("inventory"), "inventory changes are eligible without action progress");
		assertTrue(worldDelta.changedFacts().contains("entities.00000000-0000-0000-0000-000000000001"),
				"entity changes are eligible without action progress");
		assertFalse(worldDelta.changedFacts().stream().anyMatch(path -> path.startsWith("blocks")),
				"ordinary block visibility churn stays quiet");
		assertFalse(worldDelta.changedFacts().contains("world"), "weather changes stay quiet");
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
		for (int index = 0; index < 300; index++) {
			crowdedBefore.getAsJsonArray("entities").add(entity(index));
			crowdedAfter.getAsJsonArray("entities").add(entity(index + 300));
		}
		for (int index = 0; index < 128; index++) {
			crowdedBefore.getAsJsonArray("blocks").add(block(index));
			crowdedAfter.getAsJsonArray("blocks").add(block(index + 128));
		}
		AttentionFactDelta crowded = AttentionFactDelta.between(crowdedBefore, crowdedAfter, 9L, 125L);
		assertTrue(crowded.changedFacts().size() <= 256, "changed facts remain protocol bounded at maximum disjoint entity and block changes");
		assertTrue(crowded.changedFacts().contains("entities"), "entity overflow coalesces to a factual aggregate");
		assertFalse(crowded.changedFacts().contains("blocks"), "ordinary block visibility changes stay quiet");
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
		MultiplexedServerBridge.PublishedObservationState boundedPublication =
				new MultiplexedServerBridge.PublishedObservationState(AgentConstants.DEFAULT_AGENT_LIMIT);
		for (int index = 0; index < AgentConstants.DEFAULT_AGENT_LIMIT; index++) {
			boundedPublication.markDirty(AgentId.parse(String.format("00000000-0000-0000-0000-%012d", index + 100)));
		}
		AgentId overflowRetry = AgentId.parse("00000000-0000-0000-0000-000000000116");
		assertFalse(boundedPublication.markDirty(overflowRetry), "failed deliveries stop at the bounded retry capacity");
		assertTrue(boundedPublication.markDirty(AgentId.parse("00000000-0000-0000-0000-000000000100")),
				"a repeated failed delivery remains retry-safe without another marker");
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, boundedPublication.retainedCount(),
				"failed-delivery retry markers remain bounded");
		boundedPublication.clear();
		assertEquals(0, boundedPublication.retainedCount(),
				"session loss clears old delivered baselines before reconnect");
		ObservationDispatchQueue<AgentId> removedQueue = new ObservationDispatchQueue<>(16, 8);
		removedQueue.offer(retryAgent);
		publication.markDirty(retryAgent);
		assertTrue(removedQueue.remove(retryAgent), "removed agent leaves pending observation queue");
		publication.remove(retryAgent);
		List<AgentId> removedDrains = new ArrayList<>();
		for (int drain = 0; drain < 8; drain++) removedQueue.drain(removedDrains::add);
		assertEquals(List.of(), removedDrains, "removed pending agent never retries across later drains");
		assertEquals(0, publication.retainedCount(), "removed agent leaves no delivered or dirty publication state");

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
		queue.offer("stale-agent");
		queue.clear();
		assertEquals(0, queue.pendingCount(), "session loss clears stale queued observations before reconnect");

		ObservationSectionCache<String, JsonObject> cache = new ObservationSectionCache<>(2, 1L, JsonObject::deepCopy);
		AtomicInteger loads = new AtomicInteger();
		JsonObject initial = cache.getOrCompute("agent-a", 100L, () -> value("v" + loads.incrementAndGet()));
		initial.addProperty("value", "caller mutation");
		assertEquals("v1", cache.getOrCompute("agent-a", 101L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"fresh cache reuse is defensively copied");
		assertEquals("v2", cache.getOrCompute("agent-a", 102L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"same-position spatial changes reload within the two-tick bound");
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
		HashMap<Long, Boolean> blockVisibility = new HashMap<>();
		AtomicInteger blockTraces = new AtomicInteger();
		BlockPos sharedPosition = new BlockPos(4, 64, 7);
		assertTrue(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, sharedPosition, position -> blockTraces.incrementAndGet() == 1),
				"first block visibility lookup uses the ray result");
		assertTrue(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(4, 64, 7), position -> false),
				"block and container sections share a cached visible result");
		assertFalse(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(5, 64, 7), position -> {
					blockTraces.incrementAndGet();
					return false;
				}), "a distinct block retains its own visibility result");
		assertFalse(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(5, 64, 7), position -> true),
				"occluded block results are cached as well as visible results");
		assertEquals(2, blockTraces.get(), "two positions require two block ray traces");
		ObservationDispatchQueue<String> burst = new ObservationDispatchQueue<>(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_AGENT_LIMIT
		);
		for (int index = 0; index < 16; index++) {
			String agentId = "agent-" + index;
			assertTrue(burst.offer(agentId), "each agent enters a bounded burst once");
			assertFalse(burst.offer(agentId), "same agent burst events coalesce");
		}
		List<String> burstFirst = new ArrayList<>();
		burst.drain(burstFirst::add);
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, burstFirst.size(), "one drain serves the sixteen-agent tick budget");
		assertEquals(0, burst.pendingCount(), "sixteen-agent burst clears in one drain");
		return 58;
	}

	private static JsonObject movingObservation(double x, double yaw, String selectedItem, double entityDistance) {
		JsonObject value = observation(20.0D, false, 0.0D, "navigate_to");
		value.addProperty("status", x == 0.0D ? "PLANNING" : "ACTING");
		JsonObject position = new JsonObject();
		position.addProperty("x", x);
		position.addProperty("y", 64.0D);
		position.addProperty("z", 0.0D);
		value.add("position", position);
		JsonObject velocity = new JsonObject();
		velocity.addProperty("x", x == 0.0D ? 0.0D : 0.1D);
		velocity.addProperty("y", 0.0D);
		velocity.addProperty("z", 0.0D);
		value.add("velocity", velocity);
		JsonObject view = new JsonObject();
		view.addProperty("yaw", yaw);
		view.addProperty("pitch", 0.0D);
		value.add("view", view);
		JsonObject player = value.getAsJsonObject("player");
		player.addProperty("onGround", x == 0.0D);
		player.addProperty("foodLevel", x == 0.0D ? 20 : 19);
		player.addProperty("fallDistance", 0.0D);
		JsonObject lastAttacker = new JsonObject();
		lastAttacker.addProperty("uuid", "00000000-0000-0000-0000-000000000002");
		lastAttacker.addProperty("type", "minecraft:zombie");
		lastAttacker.addProperty("distance", entityDistance);
		player.add("lastAttacker", lastAttacker);
		JsonObject effect = new JsonObject();
		effect.addProperty("effectId", "minecraft:speed");
		effect.addProperty("amplifier", 0);
		effect.addProperty("duration", x == 0.0D ? 100 : 99);
		com.google.gson.JsonArray effects = new com.google.gson.JsonArray();
		effects.add(effect);
		player.add("effects", effects);
		value.getAsJsonObject("inventory").addProperty("selectedItem", selectedItem);
		JsonObject nearbyEntity = entity(1);
		nearbyEntity.addProperty("distance", entityDistance);
		value.getAsJsonArray("entities").add(nearbyEntity);
		JsonObject lastResult = new JsonObject();
		lastResult.addProperty("present", x != 0.0D);
		value.add("lastResult", lastResult);
		return value;
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
