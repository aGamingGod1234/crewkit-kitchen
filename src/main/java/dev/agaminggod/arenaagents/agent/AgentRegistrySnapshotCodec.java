package dev.agaminggod.arenaagents.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentRegistrySnapshotCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	public String encode(AgentRegistry.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", snapshot.schemaVersion());
		root.addProperty("max_agents", snapshot.maxAgents());
		root.addProperty("queue_limit", snapshot.queueLimit());
		JsonArray agents = new JsonArray();
		for (AgentRecord record : snapshot.records()) {
			agents.add(encodeRecord(record));
		}
		root.add("agents", agents);
		return GSON.toJson(root);
	}

	public AgentRegistry.Snapshot decode(String encoded) {
		try {
			JsonObject root = requireObject(JsonParser.parseString(encoded), "root");
			int schemaVersion = requireInt(root, "schema_version");
			int maxAgents = requireInt(root, "max_agents");
			int queueLimit = requireInt(root, "queue_limit");
			JsonArray agents = requireArray(root, "agents");
			ArrayList<AgentRecord> records = new ArrayList<>(agents.size());
			for (JsonElement element : agents) {
				AgentRecord record = decodeRecord(requireObject(element, "agent"));
				if (record.queuedGoals().size() > queueLimit) {
					throw failure("PERSISTED_QUEUE_TOO_LARGE", "Persisted queue exceeds configured limit");
				}
				records.add(record);
			}
			return new AgentRegistry.Snapshot(schemaVersion, maxAgents, queueLimit, records);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
			throw failure("INVALID_PERSISTED_DATA", "Invalid persisted agent data: " + exception.getMessage());
		}
	}

	private static JsonObject encodeRecord(AgentRecord record) {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", record.schemaVersion());
		json.addProperty("agent_id", record.agentId().toString());
		record.entityUuid().ifPresentOrElse(
				value -> json.addProperty("entity_uuid", value.toString()),
				() -> json.add("entity_uuid", null)
		);
		record.entityLocation().ifPresentOrElse(
				location -> json.add("entity_location", encodeEntityLocation(location)),
				() -> json.add("entity_location", null)
		);
		json.add("profile", encodeProfile(record.profile()));
		json.addProperty("state", record.state().name());
		record.currentGoal().ifPresentOrElse(
				goal -> json.add("current_goal", encodeGoal(goal)),
				() -> json.add("current_goal", null)
		);
		json.addProperty("goal_revision", record.goalRevision());
		JsonArray queue = new JsonArray();
		for (AgentGoal goal : record.queuedGoals()) {
			queue.add(encodeGoal(goal));
		}
		json.add("queue", queue);
		json.addProperty("last_summary", record.lastSummary());
		json.addProperty("inventory_snapshot", record.inventorySnapshot());
		json.addProperty("automatic_progress", record.automaticProgress());
		json.addProperty("respawn_policy", record.respawnPolicy().name());
		json.addProperty("created_at_epoch_ms", record.createdAtEpochMs());
		json.addProperty("updated_at_epoch_ms", record.updatedAtEpochMs());
		json.addProperty("last_error", record.lastError());
		return json;
	}

	private static AgentRecord decodeRecord(JsonObject json) {
		JsonArray queueJson = requireArray(json, "queue");
		ArrayList<AgentGoal> queue = new ArrayList<>(queueJson.size());
		for (JsonElement element : queueJson) {
			queue.add(decodeGoal(requireObject(element, "queued goal")));
		}
		return new AgentRecord(
				requireInt(json, "schema_version"),
				AgentId.parse(requireString(json, "agent_id")),
				optionalUuid(json, "entity_uuid"),
				optionalEntityLocation(json, "entity_location"),
				decodeProfile(requireObject(requireElement(json, "profile"), "profile")),
				parseEnum(AgentLifecycleState.class, requireString(json, "state"), "state"),
				optionalGoal(json, "current_goal"),
				requireLong(json, "goal_revision"),
				queue,
				requireString(json, "last_summary"),
				requireString(json, "inventory_snapshot"),
				optionalBoolean(json, "automatic_progress", true),
				parseEnum(RespawnPolicy.class, requireString(json, "respawn_policy"), "respawn_policy"),
				requireLong(json, "created_at_epoch_ms"),
				requireLong(json, "updated_at_epoch_ms"),
				requireString(json, "last_error")
		);
	}

	private static JsonObject encodeEntityLocation(AgentEntityLocation location) {
		JsonObject json = new JsonObject();
		json.addProperty("dimension", location.dimension());
		json.addProperty("chunk_x", location.chunkX());
		json.addProperty("chunk_z", location.chunkZ());
		return json;
	}

	private static Optional<AgentEntityLocation> optionalEntityLocation(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			return Optional.empty();
		}
		JsonObject json = requireObject(object.get(field), field);
		return Optional.of(new AgentEntityLocation(
				requireString(json, "dimension"),
				requireInt(json, "chunk_x"),
				requireInt(json, "chunk_z")
		));
	}

	private static JsonObject encodeProfile(AgentProfile profile) {
		JsonObject json = new JsonObject();
		json.addProperty("provider", profile.provider());
		json.addProperty("model", profile.model());
		json.addProperty("reasoning", profile.reasoning());
		profile.userName().ifPresentOrElse(
				name -> json.addProperty("user_name", name),
				() -> json.add("user_name", null)
		);
		json.addProperty("skin_variant", profile.skinVariant());
		json.addProperty("game_mode", profile.gameMode().wireName());
		return json;
	}

	private static AgentProfile decodeProfile(JsonObject json) {
		return new AgentProfile(
				json.has("provider") ? requireString(json, "provider") : "codex",
				requireString(json, "model"),
				requireString(json, "reasoning"),
				optionalString(json, "user_name"),
				requireInt(json, "skin_variant"),
				json.has("game_mode")
						? AgentGameMode.parse(requireString(json, "game_mode"))
						: AgentGameMode.SURVIVAL
		);
	}

	private static JsonObject encodeGoal(AgentGoal goal) {
		JsonObject json = new JsonObject();
		json.addProperty("goal_id", goal.goalId().toString());
		json.addProperty("prompt", goal.prompt());
		JsonArray steering = new JsonArray();
		for (String instruction : goal.steeringInstructions()) {
			steering.add(instruction);
		}
		json.add("steering", steering);
		json.addProperty("created_at_epoch_ms", goal.createdAtEpochMs());
		json.addProperty("updated_at_epoch_ms", goal.updatedAtEpochMs());
		return json;
	}

	private static AgentGoal decodeGoal(JsonObject json) {
		JsonArray steeringJson = requireArray(json, "steering");
		ArrayList<String> steering = new ArrayList<>(steeringJson.size());
		for (JsonElement element : steeringJson) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
				throw failure("INVALID_PERSISTED_FIELD", "steering entries must be strings");
			}
			steering.add(element.getAsString());
		}
		return new AgentGoal(
				parseUuid(requireString(json, "goal_id"), "goal_id"),
				requireString(json, "prompt"),
				steering,
				requireLong(json, "created_at_epoch_ms"),
				requireLong(json, "updated_at_epoch_ms")
		);
	}

	private static Optional<UUID> optionalUuid(JsonObject object, String field) {
		return optionalString(object, field).map(value -> parseUuid(value, field));
	}

	private static Optional<AgentGoal> optionalGoal(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (element.isJsonNull()) {
			return Optional.empty();
		}
		return Optional.of(decodeGoal(requireObject(element, field)));
	}

	private static Optional<String> optionalString(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (element.isJsonNull()) {
			return Optional.empty();
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a string or null");
		}
		return Optional.of(element.getAsString());
	}

	private static String requireString(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a string");
		}
		return element.getAsString();
	}

	private static int requireInt(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an integer");
		}
		return element.getAsInt();
	}

	private static long requireLong(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an integer");
		}
		return element.getAsLong();
	}

	private static JsonArray requireArray(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonArray()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an array");
		}
		return element.getAsJsonArray();
	}

	private static boolean optionalBoolean(JsonObject object, String field, boolean fallback) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			return fallback;
		}
		JsonElement element = object.get(field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a boolean");
		}
		return element.getAsBoolean();
	}

	private static JsonElement requireElement(JsonObject object, String field) {
		if (!object.has(field)) {
			throw failure("MISSING_PERSISTED_FIELD", "Missing persisted field: " + field);
		}
		return object.get(field);
	}

	private static JsonObject requireObject(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an object");
		}
		return element.getAsJsonObject();
	}

	private static UUID parseUuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a UUID");
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_PERSISTED_FIELD", "Unknown " + field + ": " + value);
		}
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
