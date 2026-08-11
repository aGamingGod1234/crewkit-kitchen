package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;

public interface AgentRuntimeHooks {
	AgentRuntimeHooks NO_OP = new AgentRuntimeHooks() {
	};

	default void validateProfile(AgentProfile profile) {
	}

	default void onCreated(AgentRecord record) {
	}

	default void onTransition(AgentTransition transition) {
	}

	default void onRemoved(AgentId agentId, long terminalRevision) {
	}

	default void onServerStopping() {
	}
}
