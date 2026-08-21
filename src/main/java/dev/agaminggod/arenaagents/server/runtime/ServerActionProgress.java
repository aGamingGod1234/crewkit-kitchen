package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.Objects;

public record ServerActionProgress(
		AgentId agentId,
		long goalRevision,
		String actionId,
		ActionType actionType,
		double progress,
		long elapsedMs,
		long observedAtEpochMs
) {
	public ServerActionProgress {
		agentId = Objects.requireNonNull(agentId, "agentId must not be null");
		actionType = Objects.requireNonNull(actionType, "actionType must not be null");
		if (goalRevision < 0L) throw new IllegalArgumentException("goalRevision must be non-negative");
		if (actionId == null || actionId.isBlank() || actionId.length() > 128) {
			throw new IllegalArgumentException("actionId must be nonblank and at most 128 characters");
		}
		if (!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D) {
			throw new IllegalArgumentException("progress must be in [0, 1]");
		}
		if (elapsedMs < 0L || observedAtEpochMs < 0L) {
			throw new IllegalArgumentException("progress timestamps must be non-negative");
		}
	}
}
