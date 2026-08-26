package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Legacy contract parser retained for stored compatibility; never completion authority. */
public final class GoalCompletionContract {
	public static final int MAX_PREDICATES = 16;
	public static final int MAX_BYTES = 4_096;
	private static final Set<String> TYPES = Set.of("inventory_min", "position_within", "block_matches", "entity_state");
	private final long goalRevision;
	private final List<Predicate> predicates;

	public GoalCompletionContract(long goalRevision, List<Predicate> predicates) {
		if (goalRevision < 0L) throw invalid("goalRevision must be nonnegative");
		if (predicates == null || predicates.isEmpty() || predicates.size() > MAX_PREDICATES) throw invalid("predicates must contain 1-" + MAX_PREDICATES + " entries");
		this.goalRevision = goalRevision;
		this.predicates = List.copyOf(predicates);
	}

	public long goalRevision() { return goalRevision; }
	public List<Predicate> predicates() { return predicates; }

	public static GoalCompletionContract parse(JsonObject object) {
		if (object == null) throw invalid("completionContract must be an object");
		requireKeys(object, Set.of("goalRevision", "predicates"), "completionContract");
		long revision = safeLong(object, "goalRevision");
		JsonArray raw = object.getAsJsonArray("predicates");
		if (raw == null || raw.size() < 1 || raw.size() > MAX_PREDICATES) throw invalid("completionContract predicates are out of bounds");
		ArrayList<Predicate> predicates = new ArrayList<>();
		for (int index = 0; index < raw.size(); index++) {
			JsonElement element = raw.get(index);
			if (!element.isJsonObject()) throw invalid("completion predicate must be an object");
			predicates.add(parsePredicate(element.getAsJsonObject(), index));
		}
		GoalCompletionContract contract = new GoalCompletionContract(revision, predicates);
		if (object.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("completionContract is too large");
		return contract;
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.addProperty("goalRevision", goalRevision);
		JsonArray values = new JsonArray();
		for (Predicate predicate : predicates) values.add(predicate.toJson());
		object.add("predicates", values);
		return object;
	}

	/** Stable language-neutral bytes used by both Java and JavaScript completion handshakes. */
	public String canonicalText() {
		StringBuilder value = new StringBuilder("arena-completion-v1\n").append(goalRevision);
		for (Predicate predicate : predicates) value.append('\n').append(predicate.canonicalText());
		return value.toString();
	}

	public String fingerprint() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalText().getBytes(StandardCharsets.UTF_8));
			return "sha256:" + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static Predicate parsePredicate(JsonObject object, int index) {
		String type = requiredText(object, "type", "predicates[" + index + "]");
		if (!TYPES.contains(type)) throw invalid("Unsupported completion predicate type " + type);
		return switch (type) {
			case "inventory_min" -> {
				requireKeys(object, Set.of("type", "itemId", "count"), "predicates[" + index + "]");
				yield new Predicate(type, namespacedId(object, "itemId"), positiveInt(object, "count"), null, null, null, null, null, null, null, null);
			}
			case "position_within" -> {
				requireKeys(object, Set.of("type", "x", "y", "z", "radius"), "predicates[" + index + "]");
				yield new Predicate(type, null, 0, finite(object, "x"), finite(object, "y"), finite(object, "z"), nonnegative(object, "radius"), null, null, null, null);
			}
			case "block_matches" -> {
				requireKeys(object, Set.of("type", "x", "y", "z", "blockId"), "predicates[" + index + "]");
				yield new Predicate(type, null, 0, coordinate(object, "x"), coordinate(object, "y"), coordinate(object, "z"), null, namespacedId(object, "blockId"), null, null, null);
			}
			case "entity_state" -> {
				requireKeys(object, Set.of("type", "entityId", "state"), "predicates[" + index + "]");
				UUID entityId;
				try {
					String rawEntityId = requiredText(object, "entityId", "predicate");
					if (rawEntityId.length() != 36 || !rawEntityId.equals(rawEntityId.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("noncanonical");
					entityId = UUID.fromString(rawEntityId);
				}
				catch (IllegalArgumentException exception) { throw invalid("entityId must be a canonical UUID"); }
				String state = requiredText(object, "state", "predicate");
				if (!state.equals("alive") && !state.equals("dead")) throw invalid("entity state must be alive or dead");
				yield new Predicate(type, null, 0, null, null, null, null, null, entityId, state, null);
			}
			default -> throw invalid("Unsupported completion predicate type");
		};
	}

	public record Predicate(
			String type, String itemId, int count, Double x, Double y, Double z, Double radius,
			String blockId, UUID entityId, String entityState, String actionType
	) {
		public Predicate {
			Objects.requireNonNull(type, "type must not be null");
		}

		public JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("type", type);
			switch (type) {
				case "inventory_min" -> { object.addProperty("itemId", itemId); object.addProperty("count", count); }
				case "position_within" -> { addCanonicalNumber(object, "x", x); addCanonicalNumber(object, "y", y); addCanonicalNumber(object, "z", z); addCanonicalNumber(object, "radius", radius); }
				case "block_matches" -> { object.addProperty("x", x.intValue()); object.addProperty("y", y.intValue()); object.addProperty("z", z.intValue()); object.addProperty("blockId", blockId); }
				case "entity_state" -> { object.addProperty("entityId", entityId.toString()); object.addProperty("state", entityState); }
				default -> throw invalid("Unsupported completion predicate type");
			}
			return object;
		}

		private String canonicalText() {
			return switch (type) {
				case "inventory_min" -> "inventory_min|" + base64Url(itemId) + "|" + count;
				case "position_within" -> "position_within|" + doubleHex(x) + "|" + doubleHex(y) + "|" + doubleHex(z) + "|" + doubleHex(radius);
				case "block_matches" -> "block_matches|" + x.intValue() + "|" + y.intValue() + "|" + z.intValue() + "|" + base64Url(blockId);
				case "entity_state" -> "entity_state|" + base64Url(entityId.toString()) + "|" + base64Url(entityState);
				default -> throw invalid("Unsupported completion predicate type");
			};
		}

		private static String doubleHex(Double value) {
			if (value == null) throw invalid("Missing numeric predicate value");
			return String.format(java.util.Locale.ROOT, "%016x", Double.doubleToLongBits(value == 0.0D ? 0.0D : value));
		}

		private static void addCanonicalNumber(JsonObject object, String field, Double value) {
			if (value == null) throw invalid("Missing numeric predicate value");
			if (value == Math.rint(value) && Math.abs(value) <= 9_007_199_254_740_991D) object.addProperty(field, value.longValue());
			else object.addProperty(field, value);
		}
	}

	private static String base64Url(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void requireKeys(JsonObject object, Set<String> expected, String label) {
		for (String key : object.keySet()) if (!expected.contains(key)) throw invalid("Unknown " + label + " field " + key);
		for (String key : expected) if (!object.has(key)) throw invalid("Missing " + label + " field " + key);
	}

	private static String requiredText(JsonObject object, String field, String label) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) throw invalid(label + "." + field + " must be nonblank text");
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) throw invalid(label + "." + field + " is out of bounds");
		return value;
	}

	private static String namespacedId(JsonObject object, String field) {
		String value = requiredText(object, field, "predicate");
		try { Identifier.parse(value); } catch (RuntimeException exception) { throw invalid(field + " must be a namespaced ID"); }
		return value;
	}

	private static int positiveInt(JsonObject object, String field) {
		long value = safeLong(object, field);
		if (value < 1L || value > Integer.MAX_VALUE) throw invalid(field + " must be a positive integer");
		return (int) value;
	}

	private static long safeLong(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) throw invalid(field + " must be a number");
		try {
			double numeric = object.get(field).getAsDouble();
			if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || Math.abs(numeric) > 9_007_199_254_740_991D) throw new IllegalArgumentException("not an integer");
			return object.get(field).getAsLong();
		} catch (RuntimeException exception) { throw invalid(field + " must be a safe integer"); }
	}

	private static double finite(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) throw invalid(field + " must be finite");
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value)) throw invalid(field + " must be finite");
		return value;
	}

	private static double nonnegative(JsonObject object, String field) {
		double value = finite(object, field);
		if (value < 0.0D || value > 1_000_000.0D) throw invalid(field + " must be bounded and nonnegative");
		return value;
	}

	private static double coordinate(JsonObject object, String field) {
		double value = finite(object, field);
		if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(field + " must be an integer coordinate");
		return value;
	}

	private static AgentDomainException invalid(String message) { return new AgentDomainException("MALFORMED_CONTRACT", message); }
}
