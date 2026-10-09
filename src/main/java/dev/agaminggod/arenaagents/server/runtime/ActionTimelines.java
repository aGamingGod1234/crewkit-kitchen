package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wall clock and server tick marks of recent actions, kept for latency tracing only. They travel beside a terminal
 * result and are never part of its identity, so a result rebuilt from the durable journal simply lacks them.
 */
public final class ActionTimelines {
	private static final int CAPACITY = 256;
	private static final Map<String, Timeline> RECENT = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Timeline> eldest) {
			return size() > CAPACITY;
		}
	};

	private ActionTimelines() { }

	/** startedAtEpochMs and startedTick are 0 when the action ended before it started. */
	public record Timeline(long acceptedAtEpochMs, long startedAtEpochMs, long startedTick, long endedTick) { }

	public static synchronized void remember(AgentId agentId, String actionId, Timeline timeline) {
		RECENT.put(agentId + "/" + actionId, timeline);
	}

	public static synchronized Timeline recall(AgentId agentId, String actionId) {
		return RECENT.get(agentId + "/" + actionId);
	}
}
