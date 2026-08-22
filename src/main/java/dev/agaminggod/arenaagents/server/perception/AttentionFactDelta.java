package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A compact, factual description of a newly observed server-side change. */
public record AttentionFactDelta(long eventSequence, boolean attention, List<String> changedFacts, long observedAtEpochMs) {
	public static final int MAX_CHANGED_FACTS = 256;
	private static final Set<String> PLAYER_FACTS = Set.of(
			"health", "maxHealth", "armor", "foodLevel", "saturation", "gameMode", "onGround", "inWater",
			"onFire", "air", "maxAir", "suffocating", "fallDistance", "lastAttacker", "effects"
	);
	private static final Set<String> ACTIVE_ACTION_PLAYER_FACTS = Set.of(
			"health", "maxHealth", "gameMode", "onFire", "air", "maxAir", "suffocating", "fallDistance"
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
		if (!same(previous.get("ready"), current.get("ready"))) facts.add("ready");
		boolean activeActionWindow = activeAction(previous) || activeAction(current);
		if (!activeActionWindow) {
			for (String key : List.of("position", "velocity", "view", "inventory", "nearbyContainers")) {
				if (!same(previous.get(key), current.get(key))) facts.add(key);
			}
		}
		if (worldMateriallyChanged(previous.getAsJsonObject("world"), current.getAsJsonObject("world"))) facts.add("world");
		for (String field : activeActionWindow ? ACTIVE_ACTION_PLAYER_FACTS : PLAYER_FACTS) {
			if (!same(objectValue(previous, "player", field), objectValue(current, "player", field))) facts.add("player." + field);
		}
		if (activeActionWindow && attackerAppearedOrChanged(previous, current)) facts.add("player.lastAttacker");
		if (!activeActionWindow) {
			TreeSet<String> entityChanges = entityChanges(previous.getAsJsonArray("entities"), current.getAsJsonArray("entities"));
			TreeSet<String> blockChanges = blockChanges(previous.getAsJsonArray("blocks"), current.getAsJsonArray("blocks"));
			addSpatialChanges(facts, entityChanges, blockChanges);
		} else {
			addSpatialChanges(facts, Set.of(), lavaChanges(previous.getAsJsonArray("blocks"), current.getAsJsonArray("blocks")));
		}
		return new AttentionFactDelta(eventSequence, !facts.isEmpty(), List.copyOf(facts), observedAtEpochMs);
	}

	private static boolean activeAction(JsonObject observation) {
		if (observation == null || !observation.has("currentAction")
				|| !observation.get("currentAction").isJsonObject()) return false;
		JsonObject action = observation.getAsJsonObject("currentAction");
		return action.has("active") && action.get("active").isJsonPrimitive() && action.get("active").getAsBoolean();
	}

	private static boolean attackerAppearedOrChanged(JsonObject previous, JsonObject current) {
		JsonElement before = objectValue(previous, "player", "lastAttacker");
		JsonElement after = objectValue(current, "player", "lastAttacker");
		if (after == null || !after.isJsonObject()) return false;
		if (before == null || !before.isJsonObject()) return true;
		return !same(before.getAsJsonObject().get("uuid"), after.getAsJsonObject().get("uuid"))
				|| !same(before.getAsJsonObject().get("type"), after.getAsJsonObject().get("type"));
	}

	private static TreeSet<String> entityChanges(JsonArray previous, JsonArray current) {
		Map<String, JsonElement> before = entitiesById(previous);
		Map<String, JsonElement> after = entitiesById(current);
		Set<String> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		TreeSet<String> changed = new TreeSet<>();
		for (String id : ids) if (!same(before.get(id), after.get(id))) changed.add("entities." + id);
		return changed;
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

	private static TreeSet<String> blockChanges(JsonArray previous, JsonArray current) {
		Map<String, JsonElement> before = blocksByPosition(previous);
		Map<String, JsonElement> after = blocksByPosition(current);
		Set<String> positions = new HashSet<>(before.keySet());
		positions.addAll(after.keySet());
		TreeSet<String> changed = new TreeSet<>();
		for (String position : positions) if (!same(before.get(position), after.get(position))) changed.add("blocks." + position);
		return changed;
	}

	private static TreeSet<String> lavaChanges(JsonArray previous, JsonArray current) {
		Map<String, JsonElement> before = blocksByPosition(previous);
		Map<String, JsonElement> after = blocksByPosition(current);
		Set<String> positions = new HashSet<>(before.keySet());
		positions.addAll(after.keySet());
		TreeSet<String> changed = new TreeSet<>();
		for (String position : positions) {
			JsonElement beforeBlock = before.get(position);
			JsonElement afterBlock = after.get(position);
			if (!isLava(beforeBlock) && !isLava(afterBlock)) continue;
			if (!same(beforeBlock, afterBlock)) changed.add("blocks." + position);
		}
		return changed;
	}

	private static boolean isLava(JsonElement value) {
		if (value == null || !value.isJsonObject()) return false;
		JsonElement blockId = value.getAsJsonObject().get("blockId");
		return blockId != null && blockId.isJsonPrimitive() && "minecraft:lava".equals(blockId.getAsString());
	}

	private static void addSpatialChanges(Set<String> facts, Set<String> entityChanges, Set<String> blockChanges) {
		if (facts.size() + entityChanges.size() + blockChanges.size() <= MAX_CHANGED_FACTS) {
			facts.addAll(entityChanges);
			facts.addAll(blockChanges);
			return;
		}
		if (!entityChanges.isEmpty()) facts.add("entities");
		if (!blockChanges.isEmpty()) facts.add("blocks");
	}

	private static boolean worldMateriallyChanged(JsonObject previous, JsonObject current) {
		for (String field : List.of("dimension", "raining", "thundering")) {
			if (!same(objectValue(previous, field), objectValue(current, field))) return true;
		}
		return false;
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

	private static JsonElement objectValue(JsonObject parent, String field) {
		return parent == null ? null : parent.get(field);
	}

	private static boolean same(JsonElement left, JsonElement right) {
		return Objects.equals(left, right);
	}
}
