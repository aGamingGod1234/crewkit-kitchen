package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class AgentControlActions {
	private static final Set<AgentLifecycleState> STARTABLE = Set.of(
			AgentLifecycleState.IDLE,
			AgentLifecycleState.PAUSED,
			AgentLifecycleState.COMPLETED,
			AgentLifecycleState.ERROR,
			AgentLifecycleState.DISCONNECTED
	);

	private AgentControlActions() {
	}

	public static boolean supports(AgentControlAgent agent, String operation) {
		Objects.requireNonNull(agent, "agent must not be null");
		String checkedOperation = Objects.requireNonNull(operation, "operation must not be null")
				.toLowerCase(Locale.ROOT);
		AgentLifecycleState state;
		try {
			state = AgentLifecycleState.valueOf(agent.state().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return switch (checkedOperation) {
			case "start" -> agent.entityPresent() && STARTABLE.contains(state);
			case "queue" -> state != AgentLifecycleState.DEAD
					&& agent.queuedGoalCount() < AgentConstants.DEFAULT_QUEUE_LIMIT;
			case "steer" -> state.isActive() && !agent.currentGoal().isBlank();
			case "stop" -> state.isActive() && !agent.currentGoal().isBlank();
			case "resume" -> (state == AgentLifecycleState.PAUSED || state == AgentLifecycleState.DISCONNECTED)
					&& agent.entityPresent() && !agent.currentGoal().isBlank();
			case "respawn" -> state == AgentLifecycleState.DEAD;
			case "remove" -> true;
			default -> false;
		};
	}

	public static boolean everySupports(List<AgentControlAgent> agents, String operation) {
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		return !checkedAgents.isEmpty() && checkedAgents.stream().allMatch(agent -> supports(agent, operation));
	}
}
