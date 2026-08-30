package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;

public interface InputStateSink {
	void apply(AgentId agentId, AgentInputState previous, AgentInputState state);

	default void tick(AgentId agentId, AgentInputState state) {
	}

	void clear(AgentId agentId, AgentInputState previous);
}
