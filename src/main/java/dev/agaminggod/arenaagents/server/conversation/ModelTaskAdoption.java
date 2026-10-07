package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.Objects;

/**
 * Server rules for a model's takeTask call: the model decided a player's message asks it to do
 * something, and Minecraft decides whether that request may become the agent's goal. Mirrors the
 * existing speech rules: any player may start work on an idle, completed or paused agent; only an
 * operator may replace active work; a taken-over agent accepts no new task.
 */
public final class ModelTaskAdoption {
	public enum Operation { START, RESUME, REPLACE }

	private ModelTaskAdoption() {
	}

	public static Operation operation(AgentLifecycleState state, boolean resumeRequested, boolean operator, boolean reserved) {
		Objects.requireNonNull(state, "state must not be null");
		if (reserved) {
			throw new AgentDomainException("AGENT_TAKEN_OVER", "A player is controlling this agent right now.");
		}
		if (resumeRequested) {
			if (state != AgentLifecycleState.PAUSED) {
				throw new AgentDomainException("NOTHING_TO_RESUME", "There is no paused task to resume.");
			}
			return Operation.RESUME;
		}
		if (state == AgentLifecycleState.IDLE || state == AgentLifecycleState.COMPLETED || state == AgentLifecycleState.PAUSED) {
			return Operation.START;
		}
		if (state.isActive()) {
			if (operator) return Operation.REPLACE;
			throw new AgentDomainException("AGENT_BUSY", "I already have a task; only an operator can replace it.");
		}
		throw new AgentDomainException("AGENT_UNAVAILABLE", "I cannot take a task while " + state.name().toLowerCase(java.util.Locale.ROOT) + ".");
	}
}
