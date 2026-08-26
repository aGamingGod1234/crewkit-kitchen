package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Bounded server-owned attribution ledger for kills made by agent players. */
public final class AgentKillLedger {
	private static final int MAX_EVENTS = 4_096;
	private final Deque<Kill> kills = new ArrayDeque<>();

	public synchronized void record(AgentId agentId, String entityType, long serverTick) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		String type = Objects.requireNonNull(entityType, "entityType must not be null");
		if (!type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("entityType must be namespaced");
		if (serverTick < 0L) throw new IllegalArgumentException("serverTick must be nonnegative");
		kills.addLast(new Kill(agentId, type, serverTick));
		while (kills.size() > MAX_EVENTS) kills.removeFirst();
	}

	public synchronized int count(AgentId agentId, String entityType, long afterTickExclusive) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityType, "entityType must not be null");
		int count = 0;
		for (Kill kill : kills) {
			if (kill.agentId().equals(agentId) && kill.entityType().equals(entityType) && kill.serverTick() > afterTickExclusive) count++;
		}
		return count;
	}

	public synchronized int size() {
		return kills.size();
	}

	private record Kill(AgentId agentId, String entityType, long serverTick) { }
}
