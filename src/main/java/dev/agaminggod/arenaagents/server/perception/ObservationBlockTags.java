package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Block tags belong to the block type, yet every block and landmark row repeated them (about 280 of ~950 bytes a row).
 * Rows of a type that appears more than once drop their tags and the observation lists them once under
 * {@value #FIELD}; the coordinator puts them back on each row before anything reads the observation.
 */
public final class ObservationBlockTags {
	public static final String FIELD = "blockTags";
	private static final List<String> SECTIONS = List.of("blocks", "landmarks");

	private ObservationBlockTags() {
	}

	public static void compact(JsonObject observation) {
		Map<String, JsonArray> tagsByBlock = new LinkedHashMap<>();
		Map<String, Integer> rowsByBlock = new HashMap<>();
		Set<String> inconsistent = new HashSet<>();
		forEachRow(observation, row -> {
			if (!row.has("tags") || !row.get("tags").isJsonArray() || !row.has("blockId")) return;
			String blockId = row.get("blockId").getAsString();
			JsonArray tags = row.getAsJsonArray("tags");
			JsonArray known = tagsByBlock.putIfAbsent(blockId, tags);
			rowsByBlock.merge(blockId, 1, Integer::sum);
			if (known != null && !known.equals(tags)) inconsistent.add(blockId);
		});
		// A type seen once is cheaper to leave inline than to name twice.
		JsonObject dictionary = new JsonObject();
		tagsByBlock.forEach((blockId, tags) -> {
			if (rowsByBlock.get(blockId) > 1 && !inconsistent.contains(blockId)) dictionary.add(blockId, tags);
		});
		if (dictionary.size() == 0) return;
		forEachRow(observation, row -> {
			if (row.has("blockId") && row.has("tags") && dictionary.has(row.get("blockId").getAsString())) row.remove("tags");
		});
		observation.add(FIELD, dictionary);
	}

	private static void forEachRow(JsonObject observation, Consumer<JsonObject> action) {
		for (String section : SECTIONS) {
			JsonElement values = observation.get(section);
			if (values == null || !values.isJsonArray()) continue;
			for (JsonElement row : values.getAsJsonArray()) {
				if (row.isJsonObject()) action.accept(row.getAsJsonObject());
			}
		}
	}
}
