package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.Objects;

public record ServerActionRequest(
		AgentId agentId,
		long goalRevision,
		String actionId,
		ActionType type,
		JsonObject arguments,
		ActionProvenance provenance
) {
	public ServerActionRequest {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (goalRevision < 0L) {
			throw new IllegalArgumentException("goalRevision must not be negative");
		}
		if (actionId == null || actionId.isBlank() || actionId.length() > 128) {
			throw new IllegalArgumentException("actionId must contain at most 128 characters");
		}
		Objects.requireNonNull(type, "type must not be null");
		arguments = Objects.requireNonNull(arguments, "arguments must not be null").deepCopy();
		provenance = Objects.requireNonNull(provenance, "provenance must not be null");
	}

	@Override
	public JsonObject arguments() {
		return arguments.deepCopy();
	}
}
