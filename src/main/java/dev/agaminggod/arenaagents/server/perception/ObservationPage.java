package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntFunction;

/** A bounded page reports its omissions, so missing entries never mean observed absence. */
final class ObservationPage {
	static final int MAX_ENTRIES = 32;
	static final int MAX_BYTES = 12_000;
	// Match BridgeEnvelopeCodec's escaping when measuring bytes before delivery.
	private static final Gson WIRE_JSON = new GsonBuilder().serializeNulls().create();

	private ObservationPage() { }

	static JsonObject collect(int total, int offset, int limit, IntFunction<JsonObject> read, String source) {
		return collect(total, offset, limit, read, source, MAX_BYTES);
	}

	static JsonObject collect(int total, int offset, int limit, IntFunction<JsonObject> read, String source, int maximumBytes) {
		if (total < 0 || offset < 0 || limit < 1 || limit > MAX_ENTRIES) {
			throw new IllegalArgumentException("Invalid inspection page bounds");
		}
		JsonArray entries = new JsonArray();
		int nextOffset = Math.min(offset, total);
		JsonObject result = page(entries, total, offset, limit, nextOffset, source, false, null);
		for (int index = nextOffset; index < total && entries.size() < limit; index++) {
			JsonObject entry = read.apply(index);
			entries.add(entry);
			JsonObject candidate = page(entries, total, offset, limit, index + 1, source, false, null);
			// Coverage and JSON escaping consume the same UTF-8 budget as the entries.
			if (bytes(candidate) <= maximumBytes) {
				nextOffset = index + 1;
				result = candidate;
				continue;
			}
			entries.remove(entries.size() - 1);
			JsonObject omitted = null;
			if (entries.isEmpty()) {
				// One entry cannot fit even on its own. Report unknown content explicitly
				// and advance once; a synthetic entry could be mistaken for a real fact.
				omitted = new JsonObject();
				omitted.addProperty("offset", index);
				omitted.addProperty("reason", "entry_exceeds_page_budget");
				omitted.addProperty("utf8Bytes", bytes(entry));
				nextOffset = index + 1;
			}
			result = page(entries, total, offset, limit, nextOffset, source, true, omitted);
			break;
		}
		if (bytes(result) > maximumBytes) {
			throw new IllegalArgumentException("Inspection page budget cannot hold coverage metadata");
		}
		return result;
	}

	private static int bytes(JsonObject value) {
		return WIRE_JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
	}

	private static JsonObject page(JsonArray entries, int total, int offset, int limit, int nextOffset,
			String source, boolean byteLimited, JsonObject omitted) {
		JsonObject result = new JsonObject();
		result.add("entries", entries);
		JsonObject coverage = new JsonObject();
		coverage.addProperty("offset", offset);
		coverage.addProperty("limit", limit);
		coverage.addProperty("total", total);
		coverage.addProperty("returned", entries.size());
		coverage.addProperty("hasMore", nextOffset < total);
		coverage.addProperty("nextOffset", nextOffset);
		coverage.addProperty("complete", offset == 0 && entries.size() == total && omitted == null);
		coverage.addProperty("byteLimited", byteLimited);
		coverage.addProperty("source", source);
		if (omitted != null) coverage.add("omittedEntry", omitted);
		result.add("coverage", coverage);
		return result;
	}

	static JsonObject coverage(JsonObject observation) {
		JsonObject coverage = new JsonObject();
		coverage.addProperty("mode", "sampled_visible");
		coverage.addProperty("complete", false);
		coverage.addProperty("blocksRadius", ServerObservationCollector.BLOCK_RADIUS);
		coverage.addProperty("landmarkDistanceLimit", ServerObservationCollector.LANDMARK_SIGHT_DISTANCE);
		coverage.addProperty("entitiesDistanceLimit", ServerObservationCollector.ENTITY_SIGHT_DISTANCE);
		JsonObject sections = new JsonObject();
		for (String field : List.of("entities", "blocks", "landmarks", "nearbyContainers")) {
			JsonElement value = observation.get(field);
			JsonObject section = new JsonObject();
			section.addProperty("returned", value != null && value.isJsonArray() ? value.getAsJsonArray().size() : 0);
			section.addProperty("complete", false);
			sections.add(field, section);
		}
		coverage.add("sections", sections);
		return coverage;
	}
}
