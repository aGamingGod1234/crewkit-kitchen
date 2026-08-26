package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpecCodec;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PendingGoalDraftCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final Set<String> FIELDS = Set.of(
			"draft_id", "agent_id", "requesting_player_id", "original_request", "proposed_predicate", "intent", "created_at_tick",
			"expected_goal_revision", "expected_goal_id"
	);
	private final GoalSpecCodec goalCodec = new GoalSpecCodec();

	public String encode(PendingGoalDraft draft) {
		JsonObject json = new JsonObject();
		json.addProperty("draft_id", draft.draftId().toString());
		json.addProperty("agent_id", draft.agentId().toString());
		json.addProperty("requesting_player_id", draft.requestingPlayerId().toString());
		json.addProperty("original_request", draft.originalRequest());
		draft.proposedPredicate().ifPresentOrElse(
				predicate -> json.add("proposed_predicate", goalCodec.encodePredicateObject(predicate)),
				() -> json.add("proposed_predicate", null)
		);
		json.addProperty("intent", draft.intent().name());
		json.addProperty("created_at_tick", draft.createdAtTick());
		json.addProperty("expected_goal_revision", draft.expectedGoalRevision());
		draft.expectedGoalId().ifPresentOrElse(
				goalId -> json.addProperty("expected_goal_id", goalId.toString()),
				() -> json.add("expected_goal_id", null)
		);
		return GSON.toJson(json);
	}

	public PendingGoalDraft decode(String encoded) {
		try {
			JsonObject json = object(JsonParser.parseString(encoded), "draft");
			if (!json.keySet().equals(FIELDS)) throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Draft fields differ from the closed schema");
			JsonElement proposed = field(json, "proposed_predicate");
			Optional<GoalPredicate> predicate = proposed.isJsonNull()
					? Optional.empty()
					: Optional.of(goalCodec.decodePredicateObject(object(proposed, "proposed_predicate")));
			JsonElement expectedGoalId = field(json, "expected_goal_id");
			return new PendingGoalDraft(
					uuid(string(json, "draft_id"), "draft_id"),
					AgentId.parse(string(json, "agent_id")),
					uuid(string(json, "requesting_player_id"), "requesting_player_id"),
					string(json, "original_request"),
					predicate,
					enumeration(DraftIntent.class, string(json, "intent"), "intent"),
					exactLong(json, "created_at_tick"),
					exactLong(json, "expected_goal_revision"),
					expectedGoalId.isJsonNull()
						? Optional.empty()
						: Optional.of(uuid(string(json, "expected_goal_id"), "expected_goal_id"))
			);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
			throw failure("INVALID_GOAL_DRAFT", "Invalid persisted goal draft: " + exception.getMessage());
		}
	}

	private static JsonElement field(JsonObject object, String field) {
		if (!object.has(field)) throw failure("INVALID_GOAL_DRAFT", "Missing draft field: " + field);
		return object.get(field);
	}

	private static JsonObject object(JsonElement value, String field) {
		if (value == null || !value.isJsonObject()) throw failure("INVALID_GOAL_DRAFT", field + " must be an object");
		return value.getAsJsonObject();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw failure("INVALID_GOAL_DRAFT", field + " must be a string");
		return value.getAsString();
	}

	private static long exactLong(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure("INVALID_GOAL_DRAFT", field + " must be an integer");
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw failure("INVALID_GOAL_DRAFT", field + " must be an integer");
		}
	}

	private static UUID uuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_GOAL_DRAFT", field + " must be a UUID");
		}
	}

	private static <E extends Enum<E>> E enumeration(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_GOAL_DRAFT", "Unknown " + field + ": " + value);
		}
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
