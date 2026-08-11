package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PendingSpawnCancellationLedger {
	private final long retentionMs;
	private final Map<AgentId, Long> expirations = new LinkedHashMap<>();

	PendingSpawnCancellationLedger(long retentionMs) {
		if (retentionMs <= 0L) {
			throw new IllegalArgumentException("retentionMs must be positive");
		}
		this.retentionMs = retentionMs;
	}

	void record(AgentId agentId, long nowEpochMs) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		expirations.put(agentId, Math.addExact(nowEpochMs, retentionMs));
	}

	List<AgentId> active(long nowEpochMs) {
		expirations.entrySet().removeIf(entry -> entry.getValue() <= nowEpochMs);
		return List.copyOf(expirations.keySet());
	}
}
