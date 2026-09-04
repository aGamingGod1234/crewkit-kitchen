package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RecentDamageTracker {
	private static final float DAMAGE_EPSILON = 0.001F;
	private final Map<AgentId, Float> healthByAgent = new LinkedHashMap<>();

	boolean observe(AgentId agentId, float health) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (!Float.isFinite(health) || health < 0.0F) throw new IllegalArgumentException("health must be finite and nonnegative");
		Float previous = healthByAgent.put(agentId, health);
		return previous != null && health + DAMAGE_EPSILON < previous;
	}

	void retainAgents(Set<AgentId> agentIds) {
		healthByAgent.keySet().retainAll(Set.copyOf(Objects.requireNonNull(agentIds, "agentIds must not be null")));
	}
}
