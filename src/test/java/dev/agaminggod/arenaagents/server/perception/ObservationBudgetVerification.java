package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
		assertFalse(delta.changedFacts().contains("player.fallDistance"), "fall progress stays quiet");
		assertFalse(delta.changedFacts().contains("currentAction"), "ordinary action progress stays quiet");
		assertFalse(delta.changedFacts().stream().anyMatch(path -> path.contains("danger") || path.contains("flee") || path.contains("fight")),
				"deltas do not invent tactical labels");
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
				"block visibility churn stays quiet");
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
		assertFalse(crowded.changedFacts().contains("blocks"), "block visibility changes do not request model attention");

		JsonObject movementBefore = signalObservation();
		JsonObject movingEntity = entity(1);
		movingEntity.addProperty("distance", 4.0D);
		movingEntity.add("position", vector(4.0D, 64.0D, 0.0D));
		movementBefore.getAsJsonArray("entities").add(movingEntity);
		for (int index = 1; index <= 200; index++) {
			JsonObject movementAfter = movementBefore.deepCopy();
			movementAfter.add("position", vector(index, 64.0D, index / 2.0D));
			movementAfter.add("velocity", vector(0.1D, 0.0D, 0.05D));
			movementAfter.getAsJsonObject("view").addProperty("yaw", index);
			movementAfter.getAsJsonObject("view").addProperty("pitch", index % 30);
			movementAfter.getAsJsonObject("world").addProperty("gameTime", index);
			movementAfter.getAsJsonObject("world").addProperty("dayTime", index);
			movementAfter.getAsJsonObject("world").addProperty("raining", index % 2 == 0);
			JsonObject movedEntity = movementAfter.getAsJsonArray("entities").get(0).getAsJsonObject();
			movedEntity.addProperty("distance", Math.max(0.0D, 4.0D - index / 100.0D));
			movedEntity.add("position", vector(4.0D + index / 10.0D, 64.0D, 0.0D));
			AttentionFactDelta movement = AttentionFactDelta.between(
					movementBefore, movementAfter, 1_000L + index, 2_000L + index);
			assertFalse(movement.attention(), "movement update " + index + " does not request a provider turn");
			assertEquals(List.of(), movement.changedFacts(), "movement update " + index + " emits no attention facts");
			movementBefore = movementAfter;
		}

		JsonObject signalBefore = signalObservation();
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("health", 19.0D)), "player.health");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("onFire", true)), "player.onFire");
		assertAttentionFact(signalBefore, changed(signalBefore, value -> value.addProperty("ready", false)), "ready");
		assertAttentionFact(signalBefore, changed(signalBefore, value -> value.addProperty("status", "PLAYER_DEAD")), "status");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("air", 60)), "player.air");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("foodLevel", 6)), "player.foodLevel");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("suffocating", true)), "player.suffocating");
		assertAttentionFact(signalBefore, changed(signalBefore, value -> {
			JsonObject item = new JsonObject();
			item.addProperty("itemId", "minecraft:torch");
			item.addProperty("count", 4);
			item.addProperty("slot", 0);
			value.getAsJsonObject("inventory").getAsJsonArray("items").add(item);
		}), "inventory");
		JsonObject inventoryBefore = changed(signalBefore, value -> {
			JsonObject item = new JsonObject();
			item.addProperty("itemId", "minecraft:torch");
			item.addProperty("count", 4);
			item.addProperty("slot", 0);
			value.getAsJsonObject("inventory").getAsJsonArray("items").add(item);
		});
		assertAttentionFact(inventoryBefore, changed(inventoryBefore, value ->
				value.getAsJsonObject("inventory").getAsJsonArray("items").get(0).getAsJsonObject()
						.addProperty("count", 3)), "inventory");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonArray("entities").add(entity(2))),
				"entities.00000000-0000-0000-0000-000000000002");
		JsonObject entityBefore = changed(signalBefore, value -> value.getAsJsonArray("entities").add(entity(5)));
		assertAttentionFact(entityBefore, changed(entityBefore, value ->
				value.getAsJsonArray("entities").remove(0)),
				"entities.00000000-0000-0000-0000-000000000005");
		assertAttentionFact(signalBefore, changed(signalBefore, value -> {
			JsonObject result = value.getAsJsonObject("lastResult");
			result.addProperty("present", true);
			result.addProperty("actionId", "action-1");
			result.addProperty("state", "FAILED");
			result.addProperty("reasonCode", "PATH_BLOCKED");
		}), "lastResult");
		assertAttentionFact(signalBefore, changed(signalBefore, value -> {
			JsonObject result = value.getAsJsonObject("lastResult");
			result.addProperty("present", true);
			result.addProperty("actionId", "action-2");
			result.addProperty("state", "TIMED_OUT");
			result.addProperty("reasonCode", "ACTION_TIMEOUT");
		}), "lastResult");
		assertAttentionFact(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("world").addProperty("dimension", "minecraft:the_nether")), "world.dimension");

		assertQuiet(signalBefore, changed(signalBefore, value ->
				value.getAsJsonObject("player").addProperty("health", 21.0D)), "healing");
		assertQuiet(signalBefore, changed(signalBefore, value -> {
			JsonObject action = value.getAsJsonObject("currentAction");
			action.addProperty("active", true);
			action.addProperty("actionType", "navigate_to");
			action.addProperty("progress", 0.5D);
		}), "ordinary action progress");
		assertQuiet(signalBefore, changed(signalBefore, value -> {
			JsonObject result = value.getAsJsonObject("lastResult");
			result.addProperty("present", true);
			result.addProperty("actionId", "action-3");
			result.addProperty("state", "SUCCEEDED");
			result.addProperty("reasonCode", "DONE");
		}), "successful result");
		assertQuiet(signalBefore, changed(signalBefore, value -> {
			value.getAsJsonObject("view").addProperty("yaw", 90.0D);
			value.getAsJsonArray("entities").add(entity(3));
		}), "view-cone membership change");
		assertQuiet(signalBefore, changed(signalBefore, value -> {
			value.add("position", vector(1.0D, 64.0D, 0.0D));
			value.getAsJsonArray("entities").add(entity(4));
		}), "movement membership change");
		AttentionFactDelta sorted = AttentionFactDelta.between(signalBefore, changed(signalBefore, value -> {
			value.addProperty("ready", false);
			value.addProperty("status", "PLAYER_DEAD");
			value.getAsJsonObject("player").addProperty("health", 0.0D);
			value.getAsJsonObject("world").addProperty("dimension", "minecraft:the_end");
		}), 52L, 52L);
		assertEquals(List.of("player.health", "ready", "status", "world.dimension"), sorted.changedFacts(),
				"attention fact paths are deterministic and sorted");
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
		ObservationDispatchQueue<String> burst = new ObservationDispatchQueue<>(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				2
		);
		for (int index = 0; index < 16; index++) {
			String agentId = "agent-" + index;
			assertTrue(burst.offer(agentId), "each agent enters a bounded burst once");
			assertFalse(burst.offer(agentId), "same agent burst events coalesce");
		}
		List<String> burstFirst = new ArrayList<>();
		burst.drain(burstFirst::add);
		assertEquals(List.of("agent-0", "agent-1"), burstFirst,
				"one drain serves exactly two full observations");
		assertEquals(14, burst.pendingCount(), "remaining observation work stays bounded for fair later drains");
		assertTrue(burst.offerFirst("agent-10"), "urgent pending work is promoted without duplication");
		List<String> urgentDrain = new ArrayList<>();
		burst.drain(urgentDrain::add);
		assertEquals(List.of("agent-10", "agent-2"), urgentDrain,
				"urgent result or vital work is included in the next two-observation drain");

		List<AgentRecord> active = activeRecords(16);
		ObservationCadencePolicy cadence = new ObservationCadencePolicy();
		List<AgentId> rotation = new ArrayList<>();
		for (long tick = 0L; tick < 8L; tick++) {
			List<AgentId> due = cadence.due(active, List.of(), tick, 2);
			assertEquals(2, due.size(), "active cadence uses exactly two full slots per tick");
			rotation.addAll(due);
		}
		assertEquals(active.stream().map(AgentRecord::agentId).toList(), rotation,
				"sixteen active agents receive one fair observation across eight ticks");
		AgentId urgent = active.get(9).agentId();
		List<AgentId> urgentDue = cadence.due(active, List.of(urgent), 8L, 2);
		assertTrue(urgentDue.contains(urgent), "urgent result or vital change displaces routine rotation work");
		assertEquals(2, urgentDue.size(), "urgent work remains inside the two-observation budget");

		ObservationCadencePolicy heartbeatCadence = new ObservationCadencePolicy();
		List<AgentRecord> idle = active.stream().limit(2)
				.map(record -> AgentRecord.create(record.agentId(), record.profile(), 10_000L))
				.toList();
		assertEquals(idle.stream().map(AgentRecord::agentId).toList(), heartbeatCadence.due(idle, List.of(), 0L, 2),
				"idle agents receive an initial factual observation");
		assertEquals(List.of(), heartbeatCadence.due(idle, List.of(), 19L, 2),
				"idle heartbeat does not run before twenty ticks");
		assertEquals(idle.stream().map(AgentRecord::agentId).toList(), heartbeatCadence.due(idle, List.of(), 20L, 2),
				"idle agents receive a twenty-tick heartbeat");

		RawSpatialObservation.Key facingA = new RawSpatialObservation.Key(retryAgent,
				"minecraft:overworld", 4, 64, -2);
		RawSpatialObservation.Key facingB = new RawSpatialObservation.Key(retryAgent,
				"minecraft:overworld", 4, 64, -2);
		assertEquals(facingA, facingB, "raw spatial cache identity excludes yaw and pitch");
		ObservationSectionCache<RawSpatialObservation.Key, JsonObject> tenTickCache =
				new ObservationSectionCache<>(2, 10L, JsonObject::deepCopy);
		AtomicInteger tenTickLoads = new AtomicInteger();
		tenTickCache.getOrCompute(facingA, 100L, () -> value("v" + tenTickLoads.incrementAndGet()));
		assertEquals("v1", tenTickCache.getOrCompute(facingB, 110L,
				() -> value("v" + tenTickLoads.incrementAndGet())).get("value").getAsString(),
				"raw spatial candidates are reused through the ten-tick cache window");
		assertEquals("v2", tenTickCache.getOrCompute(facingB, 111L,
				() -> value("v" + tenTickLoads.incrementAndGet())).get("value").getAsString(),
				"raw spatial candidates reload after the ten-tick window");
		AgentId otherAgent = AgentId.parse("fedcba98-7654-3210-fedc-ba9876543210");
		RawSpatialObservation.Key moved = new RawSpatialObservation.Key(retryAgent,
				"minecraft:overworld", 5, 64, -2);
		RawSpatialObservation.Key unrelated = new RawSpatialObservation.Key(otherAgent,
				"minecraft:overworld", 4, 64, -2);
		ObservationSectionCache<RawSpatialObservation.Key, JsonObject> invalidationCache =
				new ObservationSectionCache<>(3, 10L, JsonObject::deepCopy);
		AtomicInteger invalidationLoads = new AtomicInteger();
		invalidationCache.getOrCompute(facingA, 1L, () -> value("v" + invalidationLoads.incrementAndGet()));
		invalidationCache.getOrCompute(moved, 1L, () -> value("v" + invalidationLoads.incrementAndGet()));
		invalidationCache.getOrCompute(unrelated, 1L, () -> value("v" + invalidationLoads.incrementAndGet()));
		assertEquals(2, invalidationCache.invalidateMatching(key -> key.agentId().equals(retryAgent)),
				"world-changing success invalidates every cached position for that agent");
		assertEquals("v4", invalidationCache.getOrCompute(facingA, 2L,
				() -> value("v" + invalidationLoads.incrementAndGet())).get("value").getAsString(),
				"agent-scoped invalidation reloads its raw candidates immediately");
		assertEquals("v3", invalidationCache.getOrCompute(unrelated, 2L,
				() -> value("v" + invalidationLoads.incrementAndGet())).get("value").getAsString(),
				"agent-scoped invalidation preserves unrelated raw candidates");
		return 509;
	}

	private static List<AgentRecord> activeRecords(int count) {
		List<AgentRecord> records = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			AgentRecord idle = AgentRecord.create(
					AgentId.parse(String.format("10000000-0000-0000-0000-%012d", index + 1)),
					new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), index),
					1_000L + index
			);
			records.add(AgentLifecycleReducer.start(idle, "work " + index, 2_000L + index).after());
		}
		return List.copyOf(records);
	}

	private static JsonObject signalObservation() {
		JsonObject value = observation(20.0D, false, 0.0D, "idle");
		value.addProperty("ready", true);
		value.addProperty("status", "ACTING");
		value.add("position", vector(0.0D, 64.0D, 0.0D));
		value.add("velocity", vector(0.0D, 0.0D, 0.0D));
		JsonObject view = new JsonObject();
		view.addProperty("yaw", 0.0D);
		view.addProperty("pitch", 0.0D);
		value.add("view", view);
		JsonObject player = value.getAsJsonObject("player");
		player.addProperty("air", 300);
		player.addProperty("maxAir", 300);
		player.addProperty("foodLevel", 20);
		player.addProperty("suffocating", false);
		value.getAsJsonObject("inventory").add("items", new com.google.gson.JsonArray());
		value.getAsJsonObject("inventory").addProperty("selectedItem", "minecraft:air");
		value.add("nearbyContainers", new com.google.gson.JsonArray());
		JsonObject result = new JsonObject();
		result.addProperty("present", false);
		value.add("lastResult", result);
		return value;
	}

	private static JsonObject changed(JsonObject source, Consumer<JsonObject> mutation) {
		JsonObject value = source.deepCopy();
		mutation.accept(value);
		return value;
	}

	private static void assertAttentionFact(JsonObject previous, JsonObject current, String expectedFact) {
		AttentionFactDelta delta = AttentionFactDelta.between(previous, current, 50L, 50L);
		assertTrue(delta.attention(), expectedFact + " requests attention");
		assertTrue(delta.changedFacts().contains(expectedFact), expectedFact + " is reported exactly");
	}

	private static void assertQuiet(JsonObject previous, JsonObject current, String label) {
		AttentionFactDelta delta = AttentionFactDelta.between(previous, current, 51L, 51L);
		assertFalse(delta.attention(), label + " stays quiet");
		assertEquals(List.of(), delta.changedFacts(), label + " emits no attention facts");
	}

	private static JsonObject vector(double x, double y, double z) {
		JsonObject value = new JsonObject();
		value.addProperty("x", x);
		value.addProperty("y", y);
		value.addProperty("z", z);
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
