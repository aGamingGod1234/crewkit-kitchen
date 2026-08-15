package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A compact, factual description of a newly observed server-side change. */
public record AttentionFactDelta(long eventSequence, boolean attention, List<String> changedFacts, long observedAtEpochMs) {
	private static final Set<String> PLAYER_FACTS = Set.of(
			"health", "maxHealth", "armor", "foodLevel", "saturation", "gameMode", "onGround", "inWater",
			"onFire", "air", "maxAir", "suffocating", "fallDistance", "lastAttacker", "effects"
	);

	public AttentionFactDelta {
		if (eventSequence < 1L) throw new IllegalArgumentException("eventSequence must be positive");
		if (observedAtEpochMs < 0L) throw new IllegalArgumentException("observedAtEpochMs must be non-negative");
		changedFacts = List.copyOf(Objects.requireNonNull(changedFacts, "changedFacts must not be null"));
		if (!attention && !changedFacts.isEmpty()) throw new IllegalArgumentException("non-attention observation cannot contain changed facts");
	}

	public static AttentionFactDelta between(JsonObject previous, JsonObject current, long eventSequence, long observedAtEpochMs) {
		Objects.requireNonNull(current, "current must not be null");
		if (previous == null) return new AttentionFactDelta(eventSequence, false, List.of(), observedAtEpochMs);
		TreeSet<String> facts = new TreeSet<>();
		for (String key : List.of("ready", "status", "position", "velocity", "view", "inventory", "nearbyContainers", "world", "currentAction", "lastResult")) {
			if (!same(previous.get(key), current.get(key))) facts.add(key);
		}
		for (String field : PLAYER_FACTS) {
			if (!same(objectValue(previous, "player", field), objectValue(current, "player", field))) facts.add("player." + field);
		}
		addEntityChanges(previous.getAsJsonArray("entities"), current.getAsJsonArray("entities"), facts);
		addBlockChanges(previous.getAsJsonArray("blocks"), current.getAsJsonArray("blocks"), facts);
		return new AttentionFactDelta(eventSequence, !facts.isEmpty(), List.copyOf(facts), observedAtEpochMs);
	}

	private static void addEntityChanges(JsonArray previous, JsonArray current, Set<String> facts) {
		Map<String, JsonElement> before = entitiesById(previous);
		Map<String, JsonElement> after = entitiesById(current);
		Set<String> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		for (String id : ids) if (!same(before.get(id), after.get(id))) facts.add("entities." + id);
	}

	private static Map<String, JsonElement> entitiesById(JsonArray values) {
		Map<String, JsonElement> result = new HashMap<>();
		if (values == null) return result;
		for (JsonElement value : values) {
			if (!value.isJsonObject() || !value.getAsJsonObject().has("uuid")) continue;
			result.put(value.getAsJsonObject().get("uuid").getAsString(), value);
		}
		return result;
	}

	private static void addBlockChanges(JsonArray previous, JsonArray current, Set<String> facts) {
		Map<String, JsonElement> before = blocksByPosition(previous);
		Map<String, JsonElement> after = blocksByPosition(current);
		Set<String> positions = new HashSet<>(before.keySet());
		positions.addAll(after.keySet());
		for (String position : positions) if (!same(before.get(position), after.get(position))) facts.add("blocks." + position);
	}

	private static Map<String, JsonElement> blocksByPosition(JsonArray values) {
		Map<String, JsonElement> result = new HashMap<>();
		if (values == null) return result;
		for (JsonElement value : values) {
			if (!value.isJsonObject()) continue;
			JsonObject block = value.getAsJsonObject();
			if (!block.has("x") || !block.has("y") || !block.has("z")) continue;
			result.put(block.get("x").getAsInt() + "," + block.get("y").getAsInt() + "," + block.get("z").getAsInt(), value);
		}
		return result;
	}

	private static JsonElement objectValue(JsonObject parent, String objectName, String field) {
		if (parent == null || !parent.has(objectName) || !parent.get(objectName).isJsonObject()) return null;
		return parent.getAsJsonObject(objectName).get(field);
	}

	private static boolean same(JsonElement left, JsonElement right) {
		return Objects.equals(left, right);
	}
}
