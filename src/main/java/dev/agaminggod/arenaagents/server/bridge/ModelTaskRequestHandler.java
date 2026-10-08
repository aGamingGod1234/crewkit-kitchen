package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.conversation.ModelTaskAdoption;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a coordinator task_request and always produces a task_request_result once the request
 * can be identified: malformed fields, domain refusals and unexpected failures all become a
 * rejected result, so the model is never left waiting.
 */
final class ModelTaskRequestHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModelTaskRequestHandler.class);
	private static final Set<String> KEYS = Set.of("requestId", "goalRevision", "requesterId", "conversationSequence", "request", "resume");
	private static final int MAX_REQUEST_ID = 128;
	// The coordinator bounds 512 code points; allow their UTF-16 width here.
	private static final int MAX_REQUEST_TEXT = 1_024;

	private ModelTaskRequestHandler() {
	}

	static Optional<JsonObject> handle(
			JsonObject payload,
			Function<ModelTaskAdoption.TaskRequest, ModelTaskAdoption.Outcome> adopter,
			LongSupplier currentGoalRevision
	) {
		Objects.requireNonNull(adopter, "adopter must not be null");
		Objects.requireNonNull(currentGoalRevision, "currentGoalRevision must not be null");
		Optional<String> requestId = text(payload, "requestId", MAX_REQUEST_ID);
		if (requestId.isEmpty()) return Optional.empty();
		ModelTaskAdoption.Outcome outcome;
		try {
			outcome = adopter.apply(decode(payload, requestId.orElseThrow()));
		} catch (AgentDomainException exception) {
			outcome = ModelTaskAdoption.Outcome.rejected(exception.code(),
					MultiplexedServerBridge.boundedRejectionMessage(exception.getMessage()), revision(currentGoalRevision));
		} catch (RuntimeException exception) {
			LOGGER.error("Model task request {} failed", requestId.orElseThrow(), exception);
			outcome = ModelTaskAdoption.Outcome.rejected("TASK_REQUEST_FAILED",
					"Minecraft could not process this request.", revision(currentGoalRevision));
		}
		JsonObject result = new JsonObject();
		result.addProperty("requestId", requestId.orElseThrow());
		result.addProperty("status", outcome.status());
		result.addProperty("reasonCode", outcome.reasonCode());
		result.addProperty("message", outcome.message());
		result.addProperty("goalRevision", outcome.goalRevision());
		return Optional.of(result);
	}

	static ModelTaskAdoption.TaskRequest decode(JsonObject payload, String requestId) {
		if (payload == null || !payload.keySet().equals(KEYS)) invalid("task_request must carry exactly " + KEYS);
		UUID requesterId;
		try {
			requesterId = UUID.fromString(text(payload, "requesterId", 36).orElseThrow());
		} catch (RuntimeException exception) {
			throw new AgentDomainException("INVALID_REQUESTER", "Only a player can ask for a task.");
		}
		String request = text(payload, "request", MAX_REQUEST_TEXT).map(String::strip).filter(value -> !value.isEmpty())
				.orElseThrow(() -> new AgentDomainException("TASK_REQUEST_EMPTY", "The task request was empty."));
		JsonElement resume = payload.get("resume");
		if (!resume.isJsonPrimitive() || !resume.getAsJsonPrimitive().isBoolean()) invalid("resume must be a boolean");
		return new ModelTaskAdoption.TaskRequest(requestId, nonnegative(payload, "goalRevision"), requesterId,
				nonnegative(payload, "conversationSequence"), request, resume.getAsBoolean());
	}

	private static long nonnegative(JsonObject payload, String field) {
		JsonElement value = payload.get(field);
		try {
			if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
				long parsed = value.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
				if (parsed >= 0) return parsed;
			}
		} catch (ArithmeticException ignored) {
			// fall through to the uniform rejection
		}
		invalid(field + " must be a nonnegative integer");
		return 0L;
	}

	private static Optional<String> text(JsonObject payload, String field, int maximum) {
		if (payload == null) return Optional.empty();
		JsonElement value = payload.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.empty();
		String text = value.getAsString();
		return text.isEmpty() || text.length() > maximum ? Optional.empty() : Optional.of(text);
	}

	private static long revision(LongSupplier currentGoalRevision) {
		try {
			return currentGoalRevision.getAsLong();
		} catch (RuntimeException exception) {
			return 0L;
		}
	}

	private static void invalid(String message) {
		throw new AgentDomainException("INVALID_TASK_REQUEST", message);
	}
}
