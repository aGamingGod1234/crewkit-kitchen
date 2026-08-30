package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Deterministically fits optional observation candidates into one complete bridge envelope. */
public final class ServerObservationWireBudget {
	private ServerObservationWireBudget() {
	}

	public static Fitted fit(JsonObject source, Predicate<JsonObject> fitsCompleteEnvelope) {
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(fitsCompleteEnvelope, "fitsCompleteEnvelope must not be null");
		JsonObject candidate = source.deepCopy();
		ArrayList<String> reductions = new ArrayList<>();
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);

		if (removeCandidateTags(candidate)) reductions.add("candidateTags");
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);

		trimTail(candidate, candidate.get("blocks"), fitsCompleteEnvelope, "blocks", reductions);
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		trimTail(candidate, candidate.get("nearbyContainers"), fitsCompleteEnvelope, "nearbyContainers", reductions);
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		trimTail(candidate, candidate.get("entities"), fitsCompleteEnvelope, "entities", reductions);
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);

		JsonObject player = object(candidate, "player");
		trimTail(candidate, player == null ? null : player.get("effects"), fitsCompleteEnvelope,
				"player.effects", reductions);
		if (fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);

		if (dropSingleton(candidate.get("blocks"), "blocks", reductions)
				&& fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		if (dropSingleton(candidate.get("nearbyContainers"), "nearbyContainers", reductions)
				&& fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		if (dropSingleton(candidate.get("entities"), "entities", reductions)
				&& fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		if (dropSingleton(player == null ? null : player.get("effects"), "player.effects", reductions)
				&& fitsCompleteEnvelope.test(candidate)) return new Fitted(candidate, reductions);
		if (!fitsCompleteEnvelope.test(candidate)) {
			throw new BridgeProtocolException("OBSERVATION_TOO_LARGE",
					"Protected observation facts exceed the complete bridge envelope limit");
		}
		return new Fitted(candidate, reductions);
	}

	private static boolean removeCandidateTags(JsonObject observation) {
		boolean changed = false;
		for (String field : List.of("blocks", "nearbyContainers", "entities")) {
			changed |= removeTags(array(observation, field));
		}
		JsonObject inventory = object(observation, "inventory");
		if (inventory != null) changed |= removeTags(array(inventory, "items"));
		return changed;
	}

	private static boolean removeTags(JsonArray values) {
		if (values == null) return false;
		boolean changed = false;
		for (JsonElement value : values) {
			if (value.isJsonObject()) changed |= value.getAsJsonObject().remove("tags") != null;
		}
		return changed;
	}

	private static void trimTail(
			JsonObject root,
			JsonElement value,
			Predicate<JsonObject> fitsCompleteEnvelope,
			String reduction,
			List<String> reductions
	) {
		if (value == null || !value.isJsonArray()) return;
		JsonArray values = value.getAsJsonArray();
		int originalSize = values.size();
		if (originalSize <= 1) return;
		List<JsonElement> original = values.asList().stream().map(JsonElement::deepCopy).toList();
		setPrefix(values, original, 1);
		int retained = 1;
		if (fitsCompleteEnvelope.test(root)) {
			int low = 2;
			int high = originalSize - 1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				setPrefix(values, original, middle);
				if (fitsCompleteEnvelope.test(root)) {
					retained = middle;
					low = middle + 1;
				} else {
					high = middle - 1;
				}
			}
		}
		setPrefix(values, original, retained);
		reductions.add(reduction);
	}

	private static boolean dropSingleton(JsonElement value, String reduction, List<String> reductions) {
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 1) return false;
		value.getAsJsonArray().remove(0);
		if (!reductions.contains(reduction)) reductions.add(reduction);
		return true;
	}

	private static void setPrefix(JsonArray target, List<JsonElement> source, int size) {
		while (!target.isEmpty()) target.remove(target.size() - 1);
		for (int index = 0; index < size; index++) target.add(source.get(index).deepCopy());
	}

	private static JsonArray array(JsonObject object, String field) {
		return object != null && object.has(field) && object.get(field).isJsonArray()
				? object.getAsJsonArray(field) : null;
	}

	private static JsonObject object(JsonObject object, String field) {
		return object != null && object.has(field) && object.get(field).isJsonObject()
				? object.getAsJsonObject(field) : null;
	}

	public record Fitted(JsonObject observation, List<String> reductions) {
		public Fitted {
			observation = Objects.requireNonNull(observation, "observation must not be null").deepCopy();
			reductions = List.copyOf(Objects.requireNonNull(reductions, "reductions must not be null"));
		}

		@Override
		public JsonObject observation() {
			return observation.deepCopy();
		}
	}
}
