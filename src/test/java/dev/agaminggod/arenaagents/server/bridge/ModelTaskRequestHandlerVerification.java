package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.conversation.ModelTaskAdoption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Every identifiable task_request gets an answer, whatever goes wrong behind it. */
public final class ModelTaskRequestHandlerVerification {
	private static final String PLAYER = "11111111-1111-4111-8111-111111111111";

	private ModelTaskRequestHandlerVerification() {
	}

	public static int verify() {
		ModelTaskAdoption.TaskRequest[] seen = new ModelTaskAdoption.TaskRequest[1];
		JsonObject accepted = ModelTaskRequestHandler.handle(payload(), request -> {
			seen[0] = request;
			return ModelTaskAdoption.Outcome.accepted("TASK_STARTED", "Started the task.", 4L);
		}, () -> 3L).orElseThrow();
		assertEquals("accepted", accepted.get("status").getAsString(), "accepted status");
		assertEquals(4L, accepted.get("goalRevision").getAsLong(), "accepted revision");
		assertEquals(new ModelTaskAdoption.TaskRequest("r-1", 3L, UUID.fromString(PLAYER), 7L, "go get iron", false), seen[0],
				"decoded request keeps the delivered message sequence and requester");

		JsonObject refused = ModelTaskRequestHandler.handle(payload(), request -> {
			throw new AgentDomainException("AGENT_TAKEN_OVER", "A player is controlling this agent right now.");
		}, () -> 3L).orElseThrow();
		assertEquals("rejected", refused.get("status").getAsString(), "domain refusal status");
		assertEquals("AGENT_TAKEN_OVER", refused.get("reasonCode").getAsString(), "domain refusal code");
		assertEquals(3L, refused.get("goalRevision").getAsLong(), "refusal reports the current revision");

		JsonObject failed = ModelTaskRequestHandler.handle(payload(), request -> {
			throw new IllegalStateException("boom");
		}, () -> { throw new IllegalStateException("registry gone"); }).orElseThrow();
		assertEquals("TASK_REQUEST_FAILED", failed.get("reasonCode").getAsString(), "unexpected failures still answer");
		assertEquals(0L, failed.get("goalRevision").getAsLong(), "an unreadable revision falls back to zero");

		JsonObject malformed = payload();
		malformed.addProperty("resume", "yes");
		assertEquals("INVALID_TASK_REQUEST", ModelTaskRequestHandler.handle(malformed, request -> {
			throw new AssertionError("malformed requests never reach adoption");
		}, () -> 3L).orElseThrow().get("reasonCode").getAsString(), "malformed fields are answered");
		JsonObject extra = payload();
		extra.addProperty("replace", true);
		assertEquals("INVALID_TASK_REQUEST", ModelTaskRequestHandler.handle(extra, request -> null, () -> 3L).orElseThrow()
				.get("reasonCode").getAsString(), "unknown fields are refused");
		JsonObject badPlayer = payload();
		badPlayer.addProperty("requesterId", "Lucas");
		assertEquals("INVALID_REQUESTER", ModelTaskRequestHandler.handle(badPlayer, request -> null, () -> 3L).orElseThrow()
				.get("reasonCode").getAsString(), "requester must be a player UUID");
		JsonObject anonymous = payload();
		anonymous.remove("requestId");
		assertEquals(Optional.empty(), ModelTaskRequestHandler.handle(anonymous, request -> null, () -> 3L),
				"a request without an id cannot be answered (the coordinator times out)");
		return 10;
	}

	private static JsonObject payload() {
		JsonObject payload = new JsonObject();
		payload.addProperty("requestId", "r-1");
		payload.addProperty("goalRevision", 3L);
		payload.addProperty("requesterId", PLAYER);
		payload.addProperty("conversationSequence", 7L);
		payload.addProperty("request", " go get iron ");
		payload.addProperty("resume", false);
		return payload;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + " but was " + actual);
		}
	}
}
