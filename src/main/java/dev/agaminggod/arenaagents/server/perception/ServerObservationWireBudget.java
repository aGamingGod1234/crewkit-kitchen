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

	/** Fits a copy of {@code source}; the source is left untouched. */
	public static Fitted fit(JsonObject source, Predicate<JsonObject> fitsCompleteEnvelope) {
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(fitsCompleteEnvelope, "fitsCompleteEnvelope must not be null");
		return fit(source.deepCopy(), sectionTotals(source), fitsCompleteEnvelope, true);
	}

	/**
	 * Fits {@code owned} in place and returns it without copying. The caller hands the tree over: it must not use,
	 * share or retain it afterwards, and the predicate must not retain it either.
	 */
	public static Fitted fitOwned(JsonObject owned, Predicate<JsonObject> fitsCompleteEnvelope) {
		Objects.requireNonNull(owned, "owned must not be null");
		Objects.requireNonNull(fitsCompleteEnvelope, "fitsCompleteEnvelope must not be null");
		return fit(owned, sectionTotals(owned), fitsCompleteEnvelope, false);
	}

	private static Fitted fit(JsonObject candidate, int[] totals, Predicate<JsonObject> fitsCompleteEnvelope, boolean copyResult) {
		Predicate<JsonObject> fits = value -> {
			refreshCoverage(totals, value);
			return fitsCompleteEnvelope.test(value);
		};
		ArrayList<String> reductions = new ArrayList<>();
		if (fits.test(candidate)) return fitted(candidate, reductions, copyResult);

		if (removeCandidateTags(candidate)) reductions.add("candidateTags");
		if (candidate.has("coverage") && !reductions.isEmpty()) candidate.getAsJsonObject("coverage").addProperty("tagsOmitted", true);
		if (fits.test(candidate)) return fitted(candidate, reductions, copyResult);

		if (trimTail(candidate, candidate.get("landmarks"), fits, "landmarks", reductions)) {
			return fitted(candidate, reductions, copyResult);
		}
		if (trimTail(candidate, candidate.get("blocks"), fits, "blocks", reductions)) {
			return fitted(candidate, reductions, copyResult);
		}
		if (trimTail(candidate, candidate.get("nearbyContainers"), fits,
				"nearbyContainers", reductions)) {
			return fitted(candidate, reductions, copyResult);
		}
		if (trimTail(candidate, candidate.get("entities"), fits, "entities", reductions)) {
			return fitted(candidate, reductions, copyResult);
		}

		JsonObject player = object(candidate, "player");
		if (trimTail(candidate, player == null ? null : player.get("effects"), fits,
				"player.effects", reductions)) {
			return fitted(candidate, reductions, copyResult);
		}

		if (dropSingleton(candidate.get("landmarks"), "landmarks", reductions)
				&& fits.test(candidate)) return fitted(candidate, reductions, copyResult);
		if (dropSingleton(candidate.get("blocks"), "blocks", reductions)
				&& fits.test(candidate)) return fitted(candidate, reductions, copyResult);
		if (dropSingleton(candidate.get("nearbyContainers"), "nearbyContainers", reductions)
				&& fits.test(candidate)) return fitted(candidate, reductions, copyResult);
		if (dropSingleton(candidate.get("entities"), "entities", reductions)
				&& fits.test(candidate)) return fitted(candidate, reductions, copyResult);
		if (dropSingleton(player == null ? null : player.get("effects"), "player.effects", reductions)
				&& fits.test(candidate)) return fitted(candidate, reductions, copyResult);
		if (!fits.test(candidate)) {
			throw new BridgeProtocolException("OBSERVATION_TOO_LARGE",
					"Protected observation facts exceed the complete bridge envelope limit");
		}
		return fitted(candidate, reductions, copyResult);
	}

	private static final List<String> COVERED_SECTIONS = List.of("blocks", "landmarks", "entities", "nearbyContainers");

	/** Entries each covered section had before any trimming (returned plus already omitted); -1 when not reported. */
	private static int[] sectionTotals(JsonObject source) {
		int[] totals = new int[COVERED_SECTIONS.size()];
		JsonObject sourceSections = object(object(source, "coverage"), "sections");
		for (int index = 0; index < totals.length; index++) {
			JsonObject original = object(sourceSections, COVERED_SECTIONS.get(index));
			totals[index] = original == null ? -1 : original.get("returned").getAsInt()
					+ (original.has("omittedByWire") ? original.get("omittedByWire").getAsInt() : 0);
		}
		return totals;
	}

	private static void refreshCoverage(int[] totals, JsonObject candidate) {
		JsonObject coverage = object(candidate, "coverage");
		if (coverage == null) return;
		JsonObject sections = object(coverage, "sections");
		if (sections == null) return;
		for (int index = 0; index < totals.length; index++) {
			String field = COVERED_SECTIONS.get(index);
			JsonObject section = object(sections, field);
			if (section == null || totals[index] < 0) continue;
			int before = totals[index];
			JsonArray values = array(candidate, field);
			int returned = values == null ? 0 : values.size();
			section.addProperty("returned", returned);
			if (before > returned) section.addProperty("omittedByWire", before - returned);
		}
	}

	private static Fitted fitted(JsonObject candidate, List<String> reductions, boolean copyResult) {
		// Binary search restores its winning prefix after the last predicate call; an untrimmed tree was just refreshed by it.
		if (!reductions.isEmpty()) refreshCoverage(sectionTotals(candidate), candidate);
		// A caller that kept the source gets a copy the predicate never saw; an owner takes the tree itself.
		return Fitted.trusted(copyResult ? candidate.deepCopy() : candidate, reductions);
	}

	private static boolean removeCandidateTags(JsonObject observation) {
		// The per-block-type dictionary is the tags of the rows that stayed untagged, so it goes with them.
		boolean changed = observation.remove(ObservationBlockTags.FIELD) != null;
		for (String field : List.of("blocks", "landmarks", "nearbyContainers", "entities")) {
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

	private static boolean trimTail(
			JsonObject root,
			JsonElement value,
			Predicate<JsonObject> fitsCompleteEnvelope,
			String reduction,
			List<String> reductions
	) {
		if (value == null || !value.isJsonArray()) return false;
		JsonArray values = value.getAsJsonArray();
		int originalSize = values.size();
		if (originalSize <= 1) return false;
		List<JsonElement> original = List.copyOf(values.asList());
		setPrefix(values, original, 1);
		int retained = 1;
		boolean fits = fitsCompleteEnvelope.test(root);
		if (fits) {
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
		return fits;
	}

	private static boolean dropSingleton(JsonElement value, String reduction, List<String> reductions) {
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 1) return false;
		value.getAsJsonArray().remove(0);
		if (!reductions.contains(reduction)) reductions.add(reduction);
		return true;
	}

	private static void setPrefix(JsonArray target, List<JsonElement> source, int size) {
		while (!target.isEmpty()) target.remove(target.size() - 1);
		for (int index = 0; index < size; index++) target.add(source.get(index));
	}

	private static JsonArray array(JsonObject object, String field) {
		return object != null && object.has(field) && object.get(field).isJsonArray()
				? object.getAsJsonArray(field) : null;
	}

	private static JsonObject object(JsonObject object, String field) {
		return object != null && object.has(field) && object.get(field).isJsonObject()
				? object.getAsJsonObject(field) : null;
	}

	public static final class Fitted {
		private final JsonObject observation;
		private final List<String> reductions;

		public Fitted(JsonObject observation, List<String> reductions) {
			this(observation, reductions, false);
		}

		public JsonObject observation() {
			return observation.deepCopy();
		}

		/** The fitted tree itself, for a caller that owns this result and drops it; no copy is made. */
		public JsonObject takeObservation() {
			return observation;
		}

		public List<String> reductions() {
			return reductions;
		}

		private Fitted(JsonObject observation, List<String> reductions, boolean trusted) {
			JsonObject value = Objects.requireNonNull(observation, "observation must not be null");
			this.observation = trusted ? value : value.deepCopy();
			this.reductions = List.copyOf(Objects.requireNonNull(reductions, "reductions must not be null"));
		}

		private static Fitted trusted(JsonObject observation, List<String> reductions) {
			return new Fitted(observation, reductions, true);
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof Fitted fitted
					&& observation.equals(fitted.observation)
					&& reductions.equals(fitted.reductions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(observation, reductions);
		}

		@Override
		public String toString() {
			return "Fitted[observation=" + observation + ", reductions=" + reductions + "]";
		}
	}
}
