package dev.agaminggod.arenaagents.control;

import java.util.List;
import java.util.Objects;

public final class AgentControlSelection {
	private AgentControlSelection() {
	}

	public static String resolve(String currentAgentId, List<AgentControlAgent> agents) {
		String current = currentAgentId == null ? "" : currentAgentId;
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (checkedAgents.stream().anyMatch(agent -> agent.agentId().equals(current))) {
			return current;
		}
		return checkedAgents.isEmpty() ? "" : checkedAgents.getFirst().agentId();
	}
}
