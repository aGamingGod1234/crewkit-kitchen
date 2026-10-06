package dev.agaminggod.arenaagents.client.pov;

import dev.agaminggod.arenaagents.pov.PovMode;
import java.util.Objects;
import java.util.UUID;

/** The agent view the server most recently announced for this operator. */
public record PovClientSession(long sessionId, PovMode mode, UUID agentUuid, String agentName) {
	public PovClientSession {
		Objects.requireNonNull(mode, "mode must not be null");
		Objects.requireNonNull(agentUuid, "agentUuid must not be null");
		agentName = Objects.requireNonNullElse(agentName, "");
	}

	public boolean takeover() {
		return mode == PovMode.TAKEOVER;
	}
}
