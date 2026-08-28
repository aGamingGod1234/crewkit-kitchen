package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayList;
import java.util.Set;

/** Closed, versioned codec for the bounded kill ledger stored in Minecraft SavedData. */
public final class AgentKillLedgerCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "events");
	private static final Set<String> EVENT_FIELDS = Set.of("agent_id", "entity_type", "occurred_at_epoch_ms");

	public String encode(AgentKillLedger.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", snapshot.schemaVersion());
		JsonArray events = new JsonArray();
		for (AgentKillLedger.KillEvent event : snapshot.events()) {
			JsonObject encoded = new JsonObject();
			encoded.addProperty("agent_id", event.agentId().toString());
			encoded.addProperty("entity_type", event.entityType());
			encoded.addProperty("occurred_at_epoch_ms", event.occurredAtEpochMs());
			events.add(encoded);
		}
		root.add("events", events);
		return GSON.toJson(root);
	}

	public AgentKillLedger.Snapshot decode(String encoded) {
		try {
			JsonObject root = object(JsonParser.parseString(encoded), "kill ledger");
			exactFields(root, ROOT_FIELDS, "kill ledger");
			int version = exactInt(root, "schema_version");
			JsonElement eventValue = field(root, "events");
			if (!eventValue.isJsonArray()) throw failure("events must be an array");
			JsonArray events = eventValue.getAsJsonArray();
			if (events.size() > AgentKillLedger.MAX_EVENTS) throw failure("event count exceeds the bounded limit");
			ArrayList<AgentKillLedger.KillEvent> decoded = new ArrayList<>(events.size());
			for (JsonElement value : events) {
				JsonObject event = object(value, "kill event");
				exactFields(event, EVENT_FIELDS, "kill event");
				decoded.add(new AgentKillLedger.KillEvent(
						AgentId.parse(string(event, "agent_id")),
						string(event, "entity_type"),
						exactLong(event, "occurred_at_epoch_ms")
				));
			}
			return new AgentKillLedger.Snapshot(version, decoded);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
			throw failure("invalid persisted kill ledger: " + exception.getMessage());
		}
	}

	private static void exactFields(JsonObject object, Set<String> fields, String label) {
		if (!object.keySet().equals(fields)) throw failure(label + " fields differ from the closed schema");
	}

	private static JsonElement field(JsonObject object, String name) {
		if (!object.has(name)) throw failure("missing kill ledger field: " + name);
		return object.get(name);
	}

	private static JsonObject object(JsonElement value, String label) {
		if (value == null || !value.isJsonObject()) throw failure(label + " must be an object");
		return value.getAsJsonObject();
	}

	private static String string(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw failure(name + " must be a string");
		}
		return value.getAsString();
	}

	private static int exactInt(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure(name + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException | UnsupportedOperationException exception) {
			throw failure(name + " must be an integer");
		}
	}

	private static long exactLong(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure(name + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException | UnsupportedOperationException exception) {
			throw failure(name + " must be an integer");
		}
	}

	private static AgentDomainException failure(String message) {
		return new AgentDomainException("INVALID_PERSISTED_KILL_LEDGER", message);
	}
}
