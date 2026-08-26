package dev.agaminggod.arenaagents.agent.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GoalSpecCodec {
	public static final int MAX_LEAF_PREDICATES = 16;
	public static final int MAX_PREDICATE_DEPTH = 4;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public String encode(GoalSpec spec) {
		return GSON.toJson(encodeObject(spec));
	}

	public GoalSpec decode(String encoded) {
		try {
			return decodeObject(object(JsonParser.parseString(encoded), "goal spec"));
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
			throw failure("INVALID_GOAL_SPEC", "Invalid goal specification: " + exception.getMessage());
		}
	}

	public JsonObject encodeObject(GoalSpec spec) {
		JsonObject root = canonicalFields(spec.originalRequest(), spec.completion(), spec.createdAtTick());
		root.addProperty("fingerprint", spec.fingerprint());
		return root;
	}

	public GoalSpec decodeObject(JsonObject root) {
		requireExactKeys(root, Set.of("original_request", "completion", "created_at_tick", "fingerprint"));
		return new GoalSpec(
				string(root, "original_request"),
				decodePredicate(object(field(root, "completion"), "completion"), 0),
				exactLong(root, "created_at_tick"),
				string(root, "fingerprint")
		);
	}

	public static String fingerprint(GoalSpec spec) {
		return fingerprint(spec.originalRequest(), spec.completion(), spec.createdAtTick());
	}

	static String fingerprint(String request, GoalPredicate predicate, long createdAtTick) {
		validatePredicate(predicate);
		byte[] bytes = GSON.toJson(canonicalFields(request, predicate, createdAtTick)).getBytes(StandardCharsets.UTF_8);
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	static void validatePredicate(GoalPredicate predicate) {
		int leaves = countLeaves(predicate, 0);
		if (leaves > MAX_LEAF_PREDICATES) {
			throw failure("GOAL_PREDICATE_LIMIT_EXCEEDED", "Goal predicate contains too many leaves");
		}
	}

	private static int countLeaves(GoalPredicate predicate, int depth) {
		if (depth > MAX_PREDICATE_DEPTH) {
			throw failure("GOAL_PREDICATE_DEPTH_EXCEEDED", "Goal predicate is nested too deeply");
		}
		if (predicate instanceof GoalPredicate.AllOf all) {
			return countChildren(all.predicates(), depth);
		}
		if (predicate instanceof GoalPredicate.AnyOf any) {
			return countChildren(any.predicates(), depth);
		}
		return 1;
	}

	private static int countChildren(List<GoalPredicate> predicates, int depth) {
		int leaves = 0;
		for (GoalPredicate child : predicates) {
			leaves += countLeaves(child, depth + 1);
			if (leaves > MAX_LEAF_PREDICATES) return leaves;
		}
		return leaves;
	}

	private static JsonObject canonicalFields(String request, GoalPredicate predicate, long createdAtTick) {
		JsonObject root = new JsonObject();
		root.addProperty("original_request", request);
		root.add("completion", encodePredicate(predicate));
		root.addProperty("created_at_tick", createdAtTick);
		return root;
	}

	private static JsonObject encodePredicate(GoalPredicate predicate) {
		JsonObject json = new JsonObject();
		switch (predicate) {
			case GoalPredicate.InventoryContains inventory -> {
				json.addProperty("type", "inventory_contains");
				json.addProperty("item_id", inventory.itemId());
				json.addProperty("count", inventory.count());
			}
			case GoalPredicate.PositionWithin position -> {
				json.addProperty("type", "position_within");
				json.addProperty("x", position.x());
				json.addProperty("y", position.y());
				json.addProperty("z", position.z());
				json.addProperty("radius", position.radius());
				json.addProperty("stable_ticks", position.stableTicks());
			}
			case GoalPredicate.AdvancementGranted advancement -> {
				json.addProperty("type", "advancement_granted");
				json.addProperty("advancement_id", advancement.advancementId());
			}
			case GoalPredicate.EntityKilledByAgent killed -> {
				json.addProperty("type", "entity_killed_by_agent");
				json.addProperty("entity_type", killed.entityType());
				json.addProperty("after_goal_start", killed.afterGoalStart());
			}
			case GoalPredicate.BlockMatches block -> {
				json.addProperty("type", "block_matches");
				json.addProperty("x", block.x());
				json.addProperty("y", block.y());
				json.addProperty("z", block.z());
				json.addProperty("block_id", block.blockId());
				JsonObject properties = new JsonObject();
				block.properties().entrySet().stream()
						.sorted(Map.Entry.comparingByKey())
						.forEach(entry -> properties.addProperty(entry.getKey(), entry.getValue()));
				json.add("properties", properties);
			}
			case GoalPredicate.SurviveDuration survive -> {
				json.addProperty("type", "survive_duration");
				json.addProperty("ticks", survive.ticks());
			}
			case GoalPredicate.OperatorConfirmed ignored -> json.addProperty("type", "operator_confirmed");
			case GoalPredicate.AllOf all -> {
				json.addProperty("type", "all_of");
				json.add("predicates", encodeChildren(all.predicates()));
			}
			case GoalPredicate.AnyOf any -> {
				json.addProperty("type", "any_of");
				json.add("predicates", encodeChildren(any.predicates()));
			}
		}
		return json;
	}

	private static JsonArray encodeChildren(List<GoalPredicate> predicates) {
		JsonArray array = new JsonArray();
		predicates.forEach(predicate -> array.add(encodePredicate(predicate)));
		return array;
	}

	private static GoalPredicate decodePredicate(JsonObject json, int depth) {
		if (depth > MAX_PREDICATE_DEPTH) {
			throw failure("GOAL_PREDICATE_DEPTH_EXCEEDED", "Goal predicate is nested too deeply");
		}
		String type = string(json, "type");
		return switch (type) {
			case "inventory_contains" -> {
				requireExactKeys(json, Set.of("type", "item_id", "count"));
				yield new GoalPredicate.InventoryContains(string(json, "item_id"), exactInt(json, "count"));
			}
			case "position_within" -> {
				requireExactKeys(json, Set.of("type", "x", "y", "z", "radius", "stable_ticks"));
				yield new GoalPredicate.PositionWithin(finiteDouble(json, "x"), finiteDouble(json, "y"), finiteDouble(json, "z"), finiteDouble(json, "radius"), exactInt(json, "stable_ticks"));
			}
			case "advancement_granted" -> {
				requireExactKeys(json, Set.of("type", "advancement_id"));
				yield new GoalPredicate.AdvancementGranted(string(json, "advancement_id"));
			}
			case "entity_killed_by_agent" -> {
				requireExactKeys(json, Set.of("type", "entity_type", "after_goal_start"));
				yield new GoalPredicate.EntityKilledByAgent(string(json, "entity_type"), bool(json, "after_goal_start"));
			}
			case "block_matches" -> {
				requireExactKeys(json, Set.of("type", "x", "y", "z", "block_id", "properties"));
				yield new GoalPredicate.BlockMatches(exactInt(json, "x"), exactInt(json, "y"), exactInt(json, "z"), string(json, "block_id"), stringMap(object(field(json, "properties"), "properties")));
			}
			case "survive_duration" -> {
				requireExactKeys(json, Set.of("type", "ticks"));
				yield new GoalPredicate.SurviveDuration(exactLong(json, "ticks"));
			}
			case "operator_confirmed" -> {
				requireExactKeys(json, Set.of("type"));
				yield new GoalPredicate.OperatorConfirmed();
			}
			case "all_of" -> {
				requireExactKeys(json, Set.of("type", "predicates"));
				yield new GoalPredicate.AllOf(decodeChildren(array(json, "predicates"), depth + 1));
			}
			case "any_of" -> {
				requireExactKeys(json, Set.of("type", "predicates"));
				yield new GoalPredicate.AnyOf(decodeChildren(array(json, "predicates"), depth + 1));
			}
			default -> throw failure("UNKNOWN_GOAL_PREDICATE", "Unknown goal predicate: " + type);
		};
	}

	private static List<GoalPredicate> decodeChildren(JsonArray array, int depth) {
		ArrayList<GoalPredicate> predicates = new ArrayList<>(array.size());
		for (JsonElement element : array) predicates.add(decodePredicate(object(element, "predicate"), depth));
		return predicates;
	}

	private static Map<String, String> stringMap(JsonObject object) {
		if (object.size() > 16) throw failure("INVALID_GOAL_SPEC", "Too many block properties");
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		object.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
			JsonElement value = entry.getValue();
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
				throw failure("INVALID_GOAL_SPEC", "Block properties must be strings");
			}
			values.put(entry.getKey(), value.getAsString());
		});
		return values;
	}

	private static void requireExactKeys(JsonObject object, Set<String> expected) {
		HashSet<String> actual = new HashSet<>(object.keySet());
		if (!actual.equals(expected)) {
			throw failure("UNKNOWN_GOAL_FIELD", "Goal fields differ from the closed schema");
		}
	}

	private static JsonElement field(JsonObject object, String name) {
		if (!object.has(name)) throw failure("INVALID_GOAL_SPEC", "Missing goal field: " + name);
		return object.get(name);
	}

	private static JsonObject object(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) throw failure("INVALID_GOAL_SPEC", field + " must be an object");
		return element.getAsJsonObject();
	}

	private static JsonArray array(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonArray()) throw failure("INVALID_GOAL_SPEC", field + " must be an array");
		return value.getAsJsonArray();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw failure("INVALID_GOAL_SPEC", field + " must be a string");
		return value.getAsString();
	}

	private static boolean bool(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw failure("INVALID_GOAL_SPEC", field + " must be a boolean");
		return value.getAsBoolean();
	}

	private static long exactLong(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure("INVALID_GOAL_SPEC", field + " must be an integer");
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw failure("INVALID_GOAL_SPEC", field + " must be an integer");
		}
	}

	private static int exactInt(JsonObject object, String field) {
		long value = exactLong(object, field);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw failure("INVALID_GOAL_SPEC", field + " is out of range");
		return (int) value;
	}

	private static double finiteDouble(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_GOAL_SPEC", field + " must be finite");
		}
		try {
			double number = value.getAsDouble();
			if (!Double.isFinite(number)) throw failure("INVALID_GOAL_SPEC", field + " must be finite");
			return number;
		} catch (NumberFormatException exception) {
			throw failure("INVALID_GOAL_SPEC", field + " must be finite");
		}
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
