package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Selects factual changes important enough to request model attention. */
public final class AttentionSignalPolicy {
	private static final int CRITICAL_AIR = 60;
	private static final int CRITICAL_FOOD = 6;

	private AttentionSignalPolicy() {
	}

	public static List<String> changedFacts(JsonObject previous, JsonObject current) {
		Objects.requireNonNull(current, "current must not be null");
		if (previous == null) return List.of();
		TreeSet<String> facts = new TreeSet<>();
		addChanged(facts, "ready", previous.get("ready"), current.get("ready"));
		addChanged(facts, "status", previous.get("status"), current.get("status"));

		JsonObject beforePlayer = object(previous, "player");
		JsonObject afterPlayer = object(current, "player");
		if (decreased(beforePlayer, afterPlayer, "health")) facts.add("player.health");
		if (started(beforePlayer, afterPlayer, "onFire")) facts.add("player.onFire");
		if (started(beforePlayer, afterPlayer, "suffocating")) facts.add("player.suffocating");
		if (crossedAtOrBelow(beforePlayer, afterPlayer, "air", CRITICAL_AIR)) facts.add("player.air");
		if (crossedAtOrBelow(beforePlayer, afterPlayer, "foodLevel", CRITICAL_FOOD)) facts.add("player.foodLevel");

		if (!inventory(previous).equals(inventory(current))) facts.add("inventory");
		if (newFailure(previous, current)) facts.add("lastResult");
		if (!Objects.equals(dimension(previous), dimension(current))) facts.add("world.dimension");

		boolean viewpointChanged = !Objects.equals(previous.get("position"), current.get("position"))
				|| !Objects.equals(previous.get("view"), current.get("view"));
		if (!viewpointChanged) addEntityMembershipChanges(facts, entityIds(previous), entityIds(current));
		return facts.stream().limit(AttentionFactDelta.MAX_CHANGED_FACTS).toList();
	}

	private static void addChanged(Set<String> facts, String path, JsonElement before, JsonElement after) {
		if (!Objects.equals(before, after)) facts.add(path);
	}

	private static boolean decreased(JsonObject before, JsonObject after, String field) {
		Double left = number(before, field);
		Double right = number(after, field);
		return left != null && right != null && right < left;
	}

	private static boolean crossedAtOrBelow(JsonObject before, JsonObject after, String field, double threshold) {
		Double left = number(before, field);
		Double right = number(after, field);
		return left != null && right != null && left > threshold && right <= threshold;
	}

	private static boolean started(JsonObject before, JsonObject after, String field) {
		return !booleanValue(before, field) && booleanValue(after, field);
	}

	private static Double number(JsonObject object, String field) {
		if (object == null || !object.has(field) || !object.get(field).isJsonPrimitive()
				|| !object.get(field).getAsJsonPrimitive().isNumber()) return null;
		double value = object.get(field).getAsDouble();
		return Double.isFinite(value) ? value : null;
	}

	private static boolean booleanValue(JsonObject object, String field) {
		return object != null && object.has(field) && object.get(field).isJsonPrimitive()
				&& object.get(field).getAsJsonPrimitive().isBoolean() && object.get(field).getAsBoolean();
	}

	private static InventorySignature inventory(JsonObject observation) {
		JsonObject inventory = object(observation, "inventory");
		String selected = primitiveString(inventory == null ? null : inventory.get("selectedItem"));
		ArrayList<InventoryItem> items = new ArrayList<>();
		JsonArray values = array(inventory, "items");
		if (values != null) {
			for (JsonElement value : values) {
				if (!value.isJsonObject()) continue;
				JsonObject item = value.getAsJsonObject();
				String itemId = primitiveString(item.get("itemId"));
				Double count = number(item, "count");
				if (itemId == null || count == null) continue;
				items.add(new InventoryItem(primitiveString(item.get("slot")), itemId, count.intValue()));
			}
		}
		items.sort(null);
		return new InventorySignature(selected, List.copyOf(items));
	}

	private static boolean newFailure(JsonObject previous, JsonObject current) {
		ResultSignature before = result(previous);
		ResultSignature after = result(current);
		return after != null && ("FAILED".equals(after.state()) || "TIMED_OUT".equals(after.state()))
				&& !after.equals(before);
	}

	private static ResultSignature result(JsonObject observation) {
		JsonObject result = object(observation, "lastResult");
		if (result == null || !booleanValue(result, "present")) return null;
		return new ResultSignature(
				primitiveString(result.get("actionId")),
				primitiveString(result.get("state")),
				primitiveString(result.get("reasonCode"))
		);
	}

	private static String dimension(JsonObject observation) {
		JsonObject world = object(observation, "world");
		return primitiveString(world == null ? null : world.get("dimension"));
	}

	private static TreeSet<String> entityIds(JsonObject observation) {
		TreeSet<String> ids = new TreeSet<>();
		JsonArray entities = array(observation, "entities");
		if (entities == null) return ids;
		for (JsonElement value : entities) {
			if (!value.isJsonObject()) continue;
			String id = primitiveString(value.getAsJsonObject().get("uuid"));
			if (id != null) ids.add(id);
		}
		return ids;
	}

	private static void addEntityMembershipChanges(TreeSet<String> facts, Set<String> before, Set<String> after) {
		TreeSet<String> changed = new TreeSet<>(before);
		changed.addAll(after);
		TreeSet<String> retained = new TreeSet<>(before);
		retained.retainAll(after);
		changed.removeAll(retained);
		if (facts.size() + changed.size() <= AttentionFactDelta.MAX_CHANGED_FACTS) {
			changed.forEach(id -> facts.add("entities." + id));
		} else if (!changed.isEmpty()) {
			facts.add("entities");
		}
	}

	private static JsonObject object(JsonObject parent, String field) {
		return parent != null && parent.has(field) && parent.get(field).isJsonObject()
				? parent.getAsJsonObject(field) : null;
	}

	private static JsonArray array(JsonObject parent, String field) {
		return parent != null && parent.has(field) && parent.get(field).isJsonArray()
				? parent.getAsJsonArray(field) : null;
	}

	private static String primitiveString(JsonElement value) {
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private record InventorySignature(String selectedItem, List<InventoryItem> items) {
	}

	private record InventoryItem(String slot, String itemId, int count) implements Comparable<InventoryItem> {
		@Override
		public int compareTo(InventoryItem other) {
			int slotComparison = Objects.toString(slot, "").compareTo(Objects.toString(other.slot, ""));
			if (slotComparison != 0) return slotComparison;
			int itemComparison = itemId.compareTo(other.itemId);
			return itemComparison != 0 ? itemComparison : Integer.compare(count, other.count);
		}
	}

	private record ResultSignature(String actionId, String state, String reasonCode) {
	}
}
