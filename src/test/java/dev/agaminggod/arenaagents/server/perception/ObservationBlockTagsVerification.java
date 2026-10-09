package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Block tags listed once per type must give every row back exactly the tags it carried. */
public final class ObservationBlockTagsVerification {
	private ObservationBlockTagsVerification() {
	}

	public static int verify() {
		JsonObject observation = new JsonObject();
		observation.add("blocks", rows(row("minecraft:stone", 0, "#a", "#b"), row("minecraft:dirt", 1, "#c"),
				row("minecraft:stone", 2, "#a", "#b"), row("minecraft:grass_block", 3, "#g")));
		observation.add("landmarks", rows(row("minecraft:stone", 4, "#a", "#b"), row("minecraft:dirt", 5, "#c")));
		observation.add("entities", rows(row("minecraft:stone", 6, "#a", "#b")));
		JsonObject original = observation.deepCopy();
		int originalBytes = original.toString().length();

		ObservationBlockTags.compact(observation);
		JsonObject dictionary = observation.getAsJsonObject(ObservationBlockTags.FIELD);
		check(dictionary.keySet().equals(java.util.Set.of("minecraft:stone", "minecraft:dirt")),
				"only types seen more than once are listed (a singleton is cheaper inline)");
		check(!observation.getAsJsonArray("blocks").get(0).getAsJsonObject().has("tags"), "a listed type's rows drop their tags");
		check(observation.getAsJsonArray("blocks").get(3).getAsJsonObject().has("tags"), "a singleton keeps its tags inline");
		check(observation.getAsJsonArray("entities").get(0).getAsJsonObject().has("tags"), "entity rows are left alone");
		check(observation.toString().length() < originalBytes, "the observation is smaller");
		check(expand(observation).equals(original), "expanding the dictionary restores every row exactly");

		JsonObject disagreeing = new JsonObject();
		disagreeing.add("blocks", rows(row("minecraft:stone", 0, "#a"), row("minecraft:stone", 1, "#b")));
		JsonObject disagreeingOriginal = disagreeing.deepCopy();
		ObservationBlockTags.compact(disagreeing);
		check(!disagreeing.has(ObservationBlockTags.FIELD) && disagreeing.equals(disagreeingOriginal),
				"rows of one type that disagree keep their own tags");

		JsonObject untagged = new JsonObject();
		untagged.add("blocks", rows(row("minecraft:stone", 0), row("minecraft:stone", 1)));
		untagged.getAsJsonArray("blocks").forEach(value -> value.getAsJsonObject().remove("tags"));
		JsonObject untaggedOriginal = untagged.deepCopy();
		ObservationBlockTags.compact(untagged);
		check(untagged.equals(untaggedOriginal), "rows without tags are untouched");

		JsonObject empty = new JsonObject();
		ObservationBlockTags.compact(empty);
		check(empty.size() == 0, "an observation without block rows gains nothing");

		JsonObject oversize = observation.deepCopy();
		ServerObservationWireBudget.Fitted fitted = ServerObservationWireBudget.fit(oversize, candidate -> !hasAnyTags(candidate));
		check(!fitted.observation().has(ObservationBlockTags.FIELD) && fitted.reductions().contains("candidateTags"),
				"the wire budget drops the dictionary together with the tags it would otherwise keep");
		return 9;
	}

	/** What the coordinator does with a received observation, written the plain way. */
	static JsonObject expand(JsonObject observation) {
		JsonObject copy = observation.deepCopy();
		JsonObject dictionary = copy.has(ObservationBlockTags.FIELD) ? copy.getAsJsonObject(ObservationBlockTags.FIELD) : new JsonObject();
		copy.remove(ObservationBlockTags.FIELD);
		for (String section : List.of("blocks", "landmarks")) {
			if (!copy.has(section)) continue;
			for (JsonElement value : copy.getAsJsonArray(section)) {
				JsonObject row = value.getAsJsonObject();
				if (!row.has("tags") && dictionary.has(row.get("blockId").getAsString())) {
					row.add("tags", dictionary.get(row.get("blockId").getAsString()).deepCopy());
				}
			}
		}
		return copy;
	}

	private static boolean hasAnyTags(JsonObject observation) {
		if (observation.has(ObservationBlockTags.FIELD)) return true;
		for (String section : List.of("blocks", "landmarks", "entities")) {
			if (!observation.has(section)) continue;
			for (JsonElement value : observation.getAsJsonArray(section)) if (value.getAsJsonObject().has("tags")) return true;
		}
		return false;
	}

	private static JsonArray rows(JsonObject... values) {
		JsonArray array = new JsonArray();
		for (JsonObject value : values) array.add(value);
		return array;
	}

	private static JsonObject row(String blockId, int x, String... tags) {
		JsonObject row = new JsonObject();
		row.addProperty("x", x);
		row.addProperty("blockId", blockId);
		JsonArray values = new JsonArray();
		for (String tag : tags) values.add(tag);
		row.add("tags", values);
		return row;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " block tag dictionary assertions");
	}
}
